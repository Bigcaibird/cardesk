package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes

/**
 * 底部 Dock：空调 / 360 / 车辆 / 应用 / 设置 五个入口。
 * 高度按设计稿 132px。
 */
class DockView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    /** 当前高亮项（0..4），-1 表示无 */
    var highlight: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var onItem: ((Int) -> Unit)? = null

    private val labels = arrayOf("空调", "360", "车辆", "应用", "设置")
    private val tiles = Array(5) { RectF() }
    private var pressed = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 24f
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        paint.color = palette.surface
        canvas.drawRoundRect(RectF(16f, 10f, w - 16f, h - 8f), 32f, 32f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = palette.stroke
        canvas.drawRoundRect(RectF(16f, 10f, w - 16f, h - 8f), 32f, 32f, paint)
        paint.style = Paint.Style.FILL

        val tileW = 224f
        val gap = 26f
        val total = tileW * 5 + gap * 4
        val startX = (w - total) / 2f
        val top = 22f
        val bottom = h - 22f

        for (i in 0 until 5) {
            val left = startX + i * (tileW + gap)
            tiles[i].set(left, top, left + tileW, bottom)
            val active = i == highlight
            paint.color = when {
                pressed == i -> palette.accentSoft
                active -> palette.withAlpha(palette.accent, 0x22)
                else -> 0
            }
            if (paint.color != 0) {
                canvas.drawRoundRect(tiles[i], 24f, 24f, paint)
            }
            val cx = tiles[i].centerX()
            val cy = tiles[i].centerY()
            drawIcon(canvas, i, cx, cy - 12f, if (active) palette.accent else palette.textDim)
            text.color = if (active) palette.text else palette.textDim
            canvas.drawText(labels[i], cx, tiles[i].bottom - 16f, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val item = hit(event.x, event.y)
                val was = pressed
                pressed = -1
                invalidate()
                if (item >= 0 && item == was) onItem?.invoke(item)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in 0 until 5) if (tiles[i].contains(x, y)) return i
        return -1
    }

    private fun drawIcon(canvas: Canvas, index: Int, cx: Float, cy: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f
        when (index) {
            0 -> { // 雪花
                for (i in 0 until 3) {
                    val a = Math.PI * i / 3.0
                    val dx = Math.cos(a).toFloat() * 16f
                    val dy = Math.sin(a).toFloat() * 16f
                    canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
                }
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, 3f, paint)
            }
            1 -> { // 相机
                canvas.drawRoundRect(RectF(cx - 18f, cy - 11f, cx + 18f, cy + 13f), 5f, 5f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + 1f, 6f, paint)
            }
            2 -> { // 车
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(cx - 20f, cy - 4f, cx + 20f, cy + 10f), 6f, 6f, paint)
                canvas.drawRoundRect(RectF(cx - 11f, cy - 12f, cx + 11f, cy + 2f), 6f, 6f, paint)
                paint.color = palette.bgTop
                canvas.drawCircle(cx - 11f, cy + 12f, 5f, paint)
                canvas.drawCircle(cx + 11f, cy + 12f, 5f, paint)
            }
            3 -> { // 应用
                paint.style = Paint.Style.FILL
                for (r in 0 until 2) for (c in 0 until 2) {
                    canvas.drawRoundRect(
                        RectF(cx - 16f + c * 18f, cy - 16f + r * 18f, cx - 4f + c * 18f, cy - 4f + r * 18f),
                        3f, 3f, paint,
                    )
                }
            }
            else -> { // 设置齿轮
                canvas.drawCircle(cx, cy, 13f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, 5f, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }
}
