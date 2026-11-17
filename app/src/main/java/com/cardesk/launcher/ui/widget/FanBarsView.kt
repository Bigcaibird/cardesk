package com.cardesk.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec

/**
 * 7 格风量条（第四十九轮按设计板 `s04-hvac` 改成**等大方块**）。
 *
 * 设计板：7 个 **56×56 圆角 16** 的方块，间距 14，整行在中间列里居中；
 * 已开的档位用强调色实底，其余用 `surfaceStrong`。
 * 点第 i 个 = 设到 i+1 档。
 */
class FanBarsView(context: Context) : View(context) {

    var palette: Palette = Palette(
        true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )
        set(value) {
            field = value
            invalidate()
        }

    var level: Int = 0
        set(value) {
            if (field == value) return
            field = value
            // 档位变了 → 之前是「啪」地跳几格。
            // 数值渐变过去，一格一格跟着亮，才看得出「加了几档」。
            animateLevel(value)
        }

    var auto: Boolean = false
        set(value) { field = value; invalidate() }

    var onLevel: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /**
     * 画出来的档位（动画中间态）。
     *
     * 真值还是 [level]；这里存的是「此刻该亮几格」。
     */
    private var displayLevel = 0f

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
     * 当前被按住的格子（能点，所以要有按压反馈）。
     *
     * 格子是**画在这个视图里**的（不是子 View），没法用 [Motion.press] ——
     * 只能记下按的是哪一格，画的时候以**格子自己的中心**为中心缩放
     * （和 Dock 自绘按键一个做法）。
     */
    private var pressedIndex: Int? = null

    /** 按下缩放的当前值（动画中间态）。 */
    private var pressScale = 1f

    /** 按下 / 松手的缩放动画。 */
    private var pressAnimator: ValueAnimator? = null

    /** 按住某一格：缩小一点。 */
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

    /** 屏幕坐标 → 格子下标（夹取到最近的格子，和原来一致）。 */
    private fun indexAt(x: Float): Int {
        val w = width.toFloat()
        val total = COUNT * CELL + (COUNT - 1) * GAP
        val startX = (w - total) / 2f
        return ((x - startX) / (CELL + GAP)).toInt().coerceIn(0, COUNT - 1)
    }

    /** 坐标是不是真的落在某一格上（落在外面就不给按压反馈）。 */
    private fun isOnCell(x: Float): Boolean {
        val w = width.toFloat()
        val total = COUNT * CELL + (COUNT - 1) * GAP
        val startX = (w - total) / 2f
        val index = ((x - startX) / (CELL + GAP)).toInt()
        return index in 0 until COUNT
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val count = COUNT
        val total = count * CELL + (count - 1) * GAP
        val startX = (w - total) / 2f
        val cellH = minOf(h, CELL.toFloat())
        val top = (h - cellH) / 2f
        for (i in 0 until count) {
            val l = startX + i * (CELL + GAP)
            rect.set(l, top, l + CELL, top + cellH)
            // 档位渐变时用「小数」判断：到 3.5 时前 3 格亮着、第 4 格正在亮
            val on = (i + 1) <= displayLevel
            paint.style = Paint.Style.FILL
            paint.color = if (on) palette.accent else palette.surfaceStrong
            // 按住的那一格以**自己的中心**为中心缩小（自绘控件没法用 Motion.press）
            val s = if (pressedIndex == i) pressScale else 1f
            if (s != 1f) {
                canvas.save()
                canvas.scale(s, s, l + CELL / 2f, top + cellH / 2f)
            }
            canvas.drawRoundRect(rect, RADIUS, RADIUS, paint)
            if (s != 1f) canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isOnCell(event.x)) startPress(indexAt(event.x))
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

    companion object {
        /** 档位格数。 */
        const val COUNT = 7

        /** 单格边长（设计 px）。 */
        const val CELL = 56f

        /** 格间距（设计 px）。 */
        const val GAP = 14f

        /** 单格圆角（设计 px）。 */
        const val RADIUS = 16f
    }
}
