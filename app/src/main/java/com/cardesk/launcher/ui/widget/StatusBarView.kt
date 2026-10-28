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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 顶部状态栏：时间/日期 + 车辆信息 + 连接图标 + 设置入口。
 * 高度按设计稿 76px。
 */
class StatusBarView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    var timeText: String = "9:41"
    var dateText: String = "10月21日 周二"
    var rangeText: String = "386 km"
    var outdoorText: String = "26.5°"
    var sourceText: String = "模拟车辆"

    var onSettings: (() -> Unit)? = null
    var onVehicle: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val gearRect = RectF()

    fun bind(time: String, date: String, range: String, outdoor: String, source: String) {
        timeText = time
        dateText = date
        rangeText = range
        outdoorText = outdoor
        sourceText = source
        invalidate()
    }

    /**
     * 用格式刷新时钟：`SimpleDateFormat("H:mm", Locale.CHINA)`
     * （**单小时不补零**）。
     */
    fun setClock(date: Date) {
        timeText = CLOCK.format(date)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()

        bold.color = palette.text
        bold.textSize = 38f
        canvas.drawText(timeText, 56f, h * 0.62f, bold)

        text.color = palette.textDim
        text.textSize = 22f
        canvas.drawText(dateText, 56f + bold.measureText(timeText) + 26f, h * 0.60f, text)

        // 车辆来源小胶囊（点一下进车辆页）
        val pillW = text.measureText(sourceText) + 54f
        val pill = RectF(56f + bold.measureText(timeText) + 26f + text.measureText(dateText) + 34f, h * 0.22f,
            (56f + bold.measureText(timeText) + 26f + text.measureText(dateText) + 34f) + pillW, h * 0.78f)
        paint.color = palette.surfaceSoft
        canvas.drawRoundRect(pill, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = palette.stroke
        canvas.drawRoundRect(pill, 16f, 16f, paint)
        paint.style = Paint.Style.FILL
        paint.color = palette.ok
        canvas.drawCircle(pill.left + 20f, h / 2f, 6f, paint)
        text.color = palette.textDim
        text.textSize = 21f
        canvas.drawText(sourceText, pill.left + 34f, h / 2f + 8f, text)

        // 右侧信息与图标
        var x = width.toFloat() - 56f
        gearRect.set(x - 44f, h / 2f - 22f, x, h / 2f + 22f)
        drawGear(canvas, gearRect.centerX(), gearRect.centerY(), 19f)
        x -= 76f

        drawBattery(canvas, x - 46f, h / 2f - 12f, 46f, 24f)
        x -= 74f
        drawWifi(canvas, x - 34f, h / 2f)
        x -= 62f
        drawBluetooth(canvas, x - 26f, h / 2f)
        x -= 62f

        bold.color = palette.text
        bold.textSize = 26f
        val rangeW = bold.measureText(rangeText)
        canvas.drawText(rangeText, x - rangeW, h / 2f + 9f, bold)
        x -= rangeW + 22f
        text.color = palette.textDim
        text.textSize = 22f
        canvas.drawText("续航", x - text.measureText("续航"), h / 2f + 8f, text)
        x -= text.measureText("续航") + 30f

        text.color = palette.warn
        text.textSize = 24f
        canvas.drawText(outdoorText, x - text.measureText(outdoorText), h / 2f + 8f, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        if (gearRect.contains(event.x, event.y)) onSettings?.invoke()
        return true
    }

    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = palette.textDim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        canvas.drawCircle(cx, cy, r * 0.42f, paint)
        canvas.drawCircle(cx, cy, r, paint)
        val teeth = 8
        for (i in 0 until teeth) {
            val a = Math.PI * 2 * i / teeth
            canvas.drawLine(
                cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r,
                cx + cos(a).toFloat() * (r + 6f), cy + sin(a).toFloat() * (r + 6f),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = palette.textDim
        val rect = RectF()
        for (i in 1..3) {
            val r = 8f + i * 8f
            rect.set(cx - r, cy - r + 6f, cx + r, cy + r + 6f)
            canvas.drawArc(rect, 215f, 110f, false, paint)
        }
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy + 14f, 3f, paint)
    }

    private fun drawBluetooth(canvas: Canvas, cx: Float, cy: Float) {
        val p = Path()
        p.moveTo(cx, cy - 16f)
        p.lineTo(cx + 12f, cy - 4f)
        p.lineTo(cx - 12f, cy + 8f)
        p.lineTo(cx, cy + 18f)
        p.lineTo(cx, cy - 18f)
        p.lineTo(cx - 12f, cy - 6f)
        p.moveTo(cx - 12f, cy + 6f)
        p.lineTo(cx + 12f, cy - 6f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = palette.textDim
        canvas.drawPath(p, paint)
        paint.style = Paint.Style.FILL
    }

    companion object {
        /** `H:mm`，单小时不补零。 */
        private val CLOCK = SimpleDateFormat("H:mm", Locale.CHINA)
    }

    private fun drawBattery(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = palette.textDim
        canvas.drawRoundRect(RectF(left, top, left + w, top + h), 5f, 5f, paint)
        canvas.drawRoundRect(RectF(left + w, top + h * 0.3f, left + w + 5f, top + h * 0.7f), 2f, 2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = palette.ok
        canvas.drawRoundRect(RectF(left + 4f, top + 4f, left + w * 0.68f, top + h - 4f), 3f, 3f, paint)
    }
}
