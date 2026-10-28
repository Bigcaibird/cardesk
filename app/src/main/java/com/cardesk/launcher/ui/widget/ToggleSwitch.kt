package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.Skin

/**
 * 开关外观：76×44 圆角轨 + 36 圆点（中的 switch helper 尺寸）。
 * 自绘，避免不同 ROM 的 Switch 主题差异。
 */
class ToggleSwitch(context: Context) : View(context) {

    var tint: Int = Skin.ACCENT
    private var on = false

    var checked: Boolean
        get() = on
        set(value) {
            if (on == value) return
            on = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(76, 44)
    }

    fun toggle() {
        checked = !on
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)

        paint.style = Paint.Style.FILL
        paint.color = if (on) tint else if (Skin.isNight) 0x26FFFFFF else 0x220B1220
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = if (on) Skin.surfaceStrong else if (Skin.isNight) Skin.surfaceStrong else Skin.surfaceStrong
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (Skin.isNight || on) 0xF2FFFFFF.toInt() else 0xFF7A8798.toInt()
        val d = h - 8f
        val cx = if (on) w - 4f - d / 2f else 4f + d / 2f
        canvas.drawCircle(cx, h / 2f, d / 2f, paint)
    }
}
