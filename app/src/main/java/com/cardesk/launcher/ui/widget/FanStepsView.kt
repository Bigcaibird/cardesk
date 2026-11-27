package com.cardesk.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.vehicle.HvacIds

/**
 * 风量条：7 段，点哪段就切到哪档；自动风时整条显示为 AUTO 色。
 */
class FanStepsView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var level: Int = 2
        set(value) {
            if (field == value) return
            field = value
            // 档位变了 → 之前是「啪」地跳几段。
            // 数值渐变过去，段一段跟着亮，才看得出「加了几档」。
            animateLevel(value)
        }

    var auto: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onLevel: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val gap = 10f

    /**
     * 画出来的档位（动画中间态）。
     *
     * 真值还是 [level]；这里存的是「此刻该亮几段」。
     */
    private var displayLevel = 2f

    /**
     * 档位渐变的「凭证」。
     *
     * 档位可能来得比动画快（连续点、空调回读）——
     * 上一段动画的帧如果还在写 [displayLevel]，两段动画会互相打架（画面会抖）。
     */
    private var levelToken: Any? = null

    /** 档位渐变到新值。 */
    private fun animateLevel(target: Int) {
        val from = displayLevel
        val token = Any()
        levelToken = token
        Motion.animateValue(from, target.toFloat()) { v ->
            if (levelToken !== token) return@animateValue
            displayLevel = v
            invalidate()
        }
    }

    /**
     * 当前被按住的段（能点，所以要有按压反馈）。
     *
     * 段是**画在这个视图里**的（不是子 View），没法用 [Motion.press] ——
     * 只能记下按的是哪一段，画的时候以**段自己的中心**为中心缩放
     * （和 Dock 自绘按键一个做法）。
     */
    private var pressedIndex: Int? = null

    /** 按下缩放的当前值（动画中间态）。 */
    private var pressScale = 1f

    /** 按下 / 松手的缩放动画。 */
    private var pressAnimator: ValueAnimator? = null

    /** 按住某一段：缩小一点。 */
    private fun startPress(index: Int) {
        pressedIndex = index
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, MotionSpec.PRESS_SCALE).apply {
            duration = MotionSpec.PRESS_DOWN_DURATION
            interpolator = Motion.STANDARD
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    /** 松手：弹回原大。 */
    private fun endPress() {
        pressedIndex = null
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, 1f).apply {
            duration = MotionSpec.PRESS_UP_DURATION
            interpolator = Motion.REBOUND
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    /** 屏幕坐标 → 段下标（夹取，和原来一致）。 */
    private fun indexAt(x: Float): Int {
        val n = HvacIds.FAN_MAX
        val segW = (width - gap * (n - 1)) / n
        return (x / (segW + gap)).toInt().coerceIn(0, n - 1)
    }

    /** 坐标是不是真的落在某一段上（落在外面就不给按压反馈）。 */
    private fun isOnSegment(x: Float): Boolean {
        val n = HvacIds.FAN_MAX
        val segW = (width - gap * (n - 1)) / n
        val index = (x / (segW + gap)).toInt()
        return index in 0 until n
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = HvacIds.FAN_MAX
        val segW = (width - gap * (n - 1)) / n
        val top = height * 0.18f
        val bottom = height * 0.82f
        for (i in 0 until n) {
            val left = i * (segW + gap)
            rect.set(left, top, left + segW, bottom)
            // 档位渐变时用「小数」判断：到 3.5 时前三段亮着、第四段正在亮
            val on = if (auto) true else (i + 1) <= displayLevel
            paint.color = when {
                auto -> palette.accentSoft
                on -> palette.accent
                else -> palette.withAlpha(palette.stroke, 0x55)
            }
            // 按住的那一段以**自己的中心**为中心缩小（自绘控件没法用 Motion.press）
            val s = if (pressedIndex == i) pressScale else 1f
            if (s != 1f) {
                canvas.save()
                canvas.scale(s, s, left + segW / 2f, (top + bottom) / 2f)
            }
            canvas.drawRoundRect(rect, 10f, 10f, paint)
            if (on) {
                paint.color = palette.withAlpha(palette.accent, 0x33)
                rect.set(left + 3, top + 3, left + segW - 3, bottom + 3)
                canvas.drawRoundRect(rect, 8f, 8f, paint)
            }
            if (s != 1f) canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isOnSegment(event.x)) startPress(indexAt(event.x))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pressedIndex != null) endPress()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedIndex != null) endPress()
                onLevel?.invoke(indexAt(event.x) + 1)
                return true
            }
        }
        return true
    }
}
