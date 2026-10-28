package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes

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
            field = value
            invalidate()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tint = if (heat) palette.warn else palette.accent
        val active = level > 0

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
        for (i in 0 until 3) {
            val cx = startX + spacing * i
            paint.color = if (i < level) tint else palette.withAlpha(palette.stroke, 0x66)
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val next = if (level >= 3) 0 else level + 1
        onCycle?.invoke(next)
        return true
    }
}
