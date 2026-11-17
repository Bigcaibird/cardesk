package com.cardesk.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec

/**
 * 座椅加热 / 通风小控件：座椅轮廓 + 3 段档位，点一次加一档，3 档再点回到 0。
 * 加热用暖色、通风用冷色，一眼区分。
 */
class SeatLevelView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    var level: Int = 0
        set(value) {
            if (field == value) return
            field = value
            // 档位变了 → 之前是「啪」地跳一档。
            // 数值渐变过去，档位点一颗一颗亮，才看得出「加了一档」。
            animateLevel(value)
        }

    var heat: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var caption: String = "加热"
        set(value) {
            field = value
            invalidate()
        }

    var onCycle: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val seatPath = Path()

    /**
     * 画出来的档位（动画中间态）。
     *
     * 真值还是 [level]；这里存的是「此刻该亮几颗点」。
     */
    private var displayLevel = 0f

    /**
     * 档位渐变的「凭证」。
     *
     * 档位可能来得比动画快（连点、空调回读）——
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
     * 按下时的缩放（动画中间态）。
     *
     * 座椅控件是**画在这个视图里**的（不是子 View），没法用 [Motion.press] ——
     * 只能自己在画的时候缩放（和 Dock 自绘按键一个做法）。
     */
    private var pressScale = 1f

    /** 按下 / 松手的缩放动画。 */
    private var pressAnimator: ValueAnimator? = null

    /** 按住：缩小一点（点一下换一档，得让用户确认「点到了」）。 */
    private fun startPress() {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tint = if (heat) palette.warn else palette.accent
        val active = displayLevel > 0f

        // 按下时整个控件以**中心**缩小（自绘控件没法用 Motion.press）
        val s = pressScale
        if (s != 1f) {
            canvas.save()
            canvas.scale(s, s, width / 2f, height / 2f)
        }

        // 座椅轮廓（靠背 + 坐垫）
        val w = width.toFloat()
        val h = height.toFloat()
        seatPath.reset()
        rect.set(w * 0.30f, h * 0.10f, w * 0.70f, h * 0.52f)
        seatPath.addRoundRect(rect, 18f, 18f, Path.Direction.CW)
        rect.set(w * 0.22f, h * 0.54f, w * 0.78f, h * 0.78f)
        seatPath.addRoundRect(rect, 20f, 20f, Path.Direction.CW)

        paint.style = Paint.Style.FILL
        paint.color = if (active) palette.withAlpha(tint, 0x33) else palette.surfaceSoft
        canvas.drawPath(seatPath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = if (active) tint else palette.stroke
        canvas.drawPath(seatPath, paint)
        paint.style = Paint.Style.FILL

        // 档位点
        val dotsY = h * 0.90f
        val spacing = w * 0.16f
        val startX = w / 2f - spacing
        val unlit = palette.withAlpha(palette.stroke, 0x66)
        for (i in 0 until 3) {
            val cx = startX + spacing * i
            // 档位渐变时，正在亮的那颗点会「一点点亮起来」而不是突然出现
            val t = (displayLevel - i).coerceIn(0f, 1f)
            paint.color = MotionSpec.lerpColor(unlit, tint, t)
            canvas.drawCircle(cx, dotsY, 7f, paint)
        }

        // 文案
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = w * 0.16f
            color = if (active) palette.text else palette.textDim
            isFakeBoldText = true
        }
        canvas.drawText(caption, w / 2f, h * 0.30f, tp)

        if (s != 1f) canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startPress()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endPress()
                return true
            }
            MotionEvent.ACTION_UP -> {
                endPress()
                val next = if (level >= 3) 0 else level + 1
                onCycle?.invoke(next)
                return true
            }
        }
        return true
    }
}
