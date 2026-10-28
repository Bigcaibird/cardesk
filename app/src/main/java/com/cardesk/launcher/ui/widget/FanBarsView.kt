package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette

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
        set(value) { field = value; invalidate() }

    var auto: Boolean = false
        set(value) { field = value; invalidate() }

    var onLevel: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

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
            val on = i < level
            paint.style = Paint.Style.FILL
            paint.color = if (on) palette.accent else palette.surfaceStrong
            canvas.drawRoundRect(rect, RADIUS, RADIUS, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val w = width.toFloat()
            val total = COUNT * CELL + (COUNT - 1) * GAP
            val startX = (w - total) / 2f
            val index = ((event.x - startX) / (CELL + GAP)).toInt().coerceIn(0, COUNT - 1)
            onLevel?.invoke(index + 1)
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
