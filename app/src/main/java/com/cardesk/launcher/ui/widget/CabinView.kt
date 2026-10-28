package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.Palette

/**
 * 座舱吹风示意（CabinView）：逻辑坐标固定 260×200，按比例居中缩放。
 * 三组虚线分别表示吹面 / 吹脚 / 吹窗，虚线 offset 由时间驱动形成流动感。
 */
class CabinView(context: Context) : View(context) {

    var palette: Palette = Palette(
        true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )
        set(value) {
            field = value
            invalidate()
        }

    var face: Boolean = true
    var feet: Boolean = true
    var wind: Boolean = false

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var flow = 0f

    fun setFlow(face: Boolean, feet: Boolean, wind: Boolean) {
        this.face = face
        this.feet = feet
        this.wind = wind
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w / 260f, h / 200f)
        canvas.save()
        canvas.translate((w - 260f * s) / 2f, (h - 200f * s) / 2f)
        canvas.scale(s, s)

        body.style = Paint.Style.STROKE
        body.strokeWidth = 2.5f
        body.color = palette.withAlpha(palette.text, 0x66)
        rect.set(30f, 20f, 230f, 180f)
        canvas.drawRoundRect(rect, 40f, 40f, body)

        // 前风挡
        val glass = Path().apply {
            moveTo(60f, 46f); lineTo(200f, 46f); lineTo(184f, 84f); lineTo(76f, 84f); close()
        }
        body.style = Paint.Style.FILL
        body.color = palette.withAlpha(palette.accent, 0x33)
        canvas.drawPath(glass, body)

        // 座椅
        body.style = Paint.Style.FILL
        body.color = palette.withAlpha(palette.text, 0x22)
        rect.set(74f, 100f, 118f, 156f)
        canvas.drawRoundRect(rect, 14f, 14f, body)
        rect.set(142f, 100f, 186f, 156f)
        canvas.drawRoundRect(rect, 14f, 14f, body)

        line.style = Paint.Style.STROKE
        line.strokeWidth = 3f
        line.strokeCap = Paint.Cap.ROUND
        flow = (flow + 6f) % 100f
        line.pathEffect = DashPathEffect(floatArrayOf(14f, 12f), flow)

        drawFlow(canvas, face, palette.accent, 130f, 96f, 130f, 20f)
        drawFlow(canvas, feet, palette.accent, 130f, 130f, 130f, 190f)
        drawFlow(canvas, wind, palette.accent, 130f, 100f, 60f, 60f)

        canvas.restore()
        if (face || feet || wind) postInvalidateOnAnimation()
    }

    private fun drawFlow(canvas: Canvas, on: Boolean, color: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!on) return
        line.color = palette.withAlpha(color, 0xCC)
        canvas.drawLine(x0, y0, x1, y1, line)
    }
}
