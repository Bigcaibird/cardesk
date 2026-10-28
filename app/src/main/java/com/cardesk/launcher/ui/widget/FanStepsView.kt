package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.vehicle.HvacIds

/**
 * 风量条：7 段，点哪段就切到哪档；自动风时整条显示为 AUTO 色。
 */
class FanStepsView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    var level: Int = 2
        set(value) {
            field = value
            invalidate()
        }

    var auto: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onLevel: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val gap = 10f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = HvacIds.FAN_MAX
        val segW = (width - gap * (n - 1)) / n
        val top = height * 0.18f
        val bottom = height * 0.82f
        for (i in 0 until n) {
            val left = i * (segW + gap)
            rect.set(left, top, left + segW, bottom)
            val on = if (auto) true else i < level
            paint.color = when {
                auto -> palette.accentSoft
                on -> palette.accent
                else -> palette.withAlpha(palette.stroke, 0x55)
            }
            canvas.drawRoundRect(rect, 10f, 10f, paint)
            if (on) {
                paint.color = palette.withAlpha(palette.accent, 0x33)
                rect.set(left + 3, top + 3, left + segW - 3, bottom + 3)
                canvas.drawRoundRect(rect, 8f, 8f, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val n = HvacIds.FAN_MAX
        val segW = (width - gap * (n - 1)) / n
        val index = (event.x / (segW + gap)).toInt().coerceIn(0, n - 1)
        onLevel?.invoke(index + 1)
        return true
    }
}
