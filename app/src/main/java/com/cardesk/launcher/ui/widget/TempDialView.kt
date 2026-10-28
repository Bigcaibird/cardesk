package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * 空调温度表盘：圆环 + 中间温度 + 左右 −/+ 方块按钮。
 *
 * ## 与对齐的交互
 *
 * | 行为 ||
 * |---|---|
 * | 按下 ± 键 | 立刻 ±0.5℃（在 ACTION_DOWN） |
 * | 按住表盘拖动 | `atan2` 取角度 → 夹到 [-135°,135°] → 映射 16~32℃ → 吸附 0.5℃ |
 * | 抬手 | 回调**绝对温度** `onTemp(temp)` |
 * | 拖动中回读 | 直接忽略，避免车机回声打断手势 |
 * | `setSynced(true)` | **只画徽标，不禁用触摸**——同步态下仍可调温 |
 *
 * ## 版式（第四十九轮按设计板 `s04-hvac` 重排）
 *
 * 容器固定 **340×424**：
 * `
 *  caption 26px 顶部居中（设计板把「主驾 / 副驾」放在环上方）
 *  圆环 直径 236（r = 114，描边 20.8），圆心 (170, 186)
 *  温度 62px 粗体，圆环正中
 *  − / + 72×72 圆角 24 方块，顶部 352
 * `
 *
 * 第五十轮按设计板 `s04-hvac` 修正两处：
 * - 描边 8 → **20.8**（SVG `stroke-width="8"` 在 100×100 viewBox 放大到 260px）；
 * - 弧长口径 `(t − 16) / 16` → **`t / 30`**（设计板 23.5℃ = 78.3%）。
 */
class TempDialView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    var temperature: Float = 23.5f
        set(value) {
            // setTemp:258-261 —— 拖动中不回读覆盖，避免手势被打断
            if (dragging) return
            field = value
            invalidate()
        }

    var caption: String = "主驾"
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 只影响 **caption** 的字色（同步态下 caption 变淡，但**仍可操作**，同款）。
     *
     * 第五十轮按设计板 `s04-hvac` 修正：圆环进度弧与温度数值**始终是满色**
     * （设计板里即使「已关机」也是满色 accent 弧 + 满色温度字），
     * 所以这里不再把弧 / 温度调暗。
     */
    var dimmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 真正拦截触摸（关机时用）。 */
    var disabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** ±0.5℃ 步进回调。 */
    var onDelta: ((Float) -> Unit)? = null

    /** 拖动结束的**绝对温度**回调。 */
    var onTemp: ((Float) -> Unit)? = null

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arcRect = RectF()
    private var minusCx = 0f
    private var plusCx = 0f
    private var buttonCy = 0f

    /** 是否正在拖动表盘。 */
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val cx = w / 2f
        val cy = RING_CY
        val r = RING_R

        // —— 圆环轨道（满圈）——
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        arc.color = palette.surfaceStrong
        canvas.drawCircle(cx, cy, r, arc)

        // —— 强调色进度：从正上方顺时针走，温度越高覆盖越多 ——
        //
        // 第五十轮按设计板 `s04-hvac` 修正弧长口径：
        // 设计板圆环是 `<svg viewBox="0 0 100 100">` 里的 r=44、
        // `stroke-dasharray="276.5"`（= 2πr，满圈）、`stroke-dashoffset="60"`，
        // 可见弧 = 276.5 − 60 = 216.5 → **216.5 / 276.5 = 78.3%**。
        //
        // 78.3% 对应的温度是 **23.5 / 30**（16℃ 起、30℃ 满格），
        // 所以比例口径是 `temperature / 30`，不是 `(t − 16) / 16`。
        val frac = (temperature / ARC_TEMP_SPAN).coerceIn(0f, 1f)
        if (frac > 0.005f) {
            arc.color = palette.accent
            canvas.drawArc(arcRect, RING_START_ANGLE, 360f * frac, false, arc)
        }

        // —— 温度数值（设计板：满色 text，不随关机变暗）——
        textPaint.color = palette.text
        textPaint.textSize = TEMP_TEXT_SIZE
        canvas.drawText(format(temperature), cx, cy + TEMP_TEXT_SIZE * 0.34f, textPaint)

        // —— caption（环上方）——
        textPaint.color = if (dimmed) palette.textFaint else palette.textDim
        textPaint.textSize = CAPTION_TEXT_SIZE
        canvas.drawText(caption, cx, CAPTION_BASELINE, textPaint)

        // —— − / + 方块按钮 ——
        buttonCy = BUTTON_TOP + BUTTON_SIZE / 2f
        minusCx = cx - (BUTTON_SIZE + BUTTON_GAP) / 2f
        plusCx = cx + (BUTTON_SIZE + BUTTON_GAP) / 2f
        buttonPaint.style = Paint.Style.FILL
        buttonPaint.color = palette.surfaceSoft
        val rr = BUTTON_RADIUS
        canvas.drawRoundRect(minusCx - BUTTON_SIZE / 2f, BUTTON_TOP, minusCx + BUTTON_SIZE / 2f, BUTTON_TOP + BUTTON_SIZE, rr, rr, buttonPaint)
        canvas.drawRoundRect(plusCx - BUTTON_SIZE / 2f, BUTTON_TOP, plusCx + BUTTON_SIZE / 2f, BUTTON_TOP + BUTTON_SIZE, rr, rr, buttonPaint)
        buttonPaint.style = Paint.Style.STROKE
        buttonPaint.strokeWidth = 1f
        buttonPaint.color = palette.stroke
        canvas.drawRoundRect(minusCx - BUTTON_SIZE / 2f, BUTTON_TOP, minusCx + BUTTON_SIZE / 2f, BUTTON_TOP + BUTTON_SIZE, rr, rr, buttonPaint)
        canvas.drawRoundRect(plusCx - BUTTON_SIZE / 2f, BUTTON_TOP, plusCx + BUTTON_SIZE / 2f, BUTTON_TOP + BUTTON_SIZE, rr, rr, buttonPaint)
        buttonPaint.style = Paint.Style.FILL

        buttonText.color = palette.text
        buttonText.textSize = BUTTON_TEXT_SIZE
        canvas.drawText("−", minusCx, buttonCy + BUTTON_TEXT_SIZE * 0.36f, buttonText)
        canvas.drawText("+", plusCx, buttonCy + BUTTON_TEXT_SIZE * 0.36f, buttonText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                // onTouchEvent:203-217 —— 落在 ± 键上立刻步进，否则进入拖动
                if (onButton(x, y)) {
                    onDelta?.invoke(if (x < width / 2f) -STEP else STEP)
                    return true
                }
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                temperature = temperatureFor(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    onTemp?.invoke(temperature)
                    dragging = false
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    /** 命中 ± 按钮热区（矩形，方块按钮）。 */
    private fun onButton(x: Float, y: Float): Boolean {
        if (y < BUTTON_TOP - 8f || y > BUTTON_TOP + BUTTON_SIZE + 8f) return false
        val half = BUTTON_SIZE / 2f + 8f
        return abs(x - minusCx) < half || abs(x - plusCx) < half
    }

    /**
     * 拖动角度 → 温度。
     *
     * `atan2(x - w/2, -(y - 圆心y))` → 角度夹到 [-135°,135°]
     * 线性映射到 16~32℃ → 吸附到 0.5℃。
     */
    private fun temperatureFor(x: Float, y: Float): Float {
        val cx = width / 2f
        val cy = RING_CY
        var degrees = Math.toDegrees(Math.atan2((x - cx).toDouble(), (-(y - cy)).toDouble()))
        if (degrees > 180.0) degrees -= 360.0
        if (degrees < -135.0) degrees = -135.0
        if (degrees > 135.0) degrees = 135.0
        val raw = ((degrees + 135.0) / 270.0 * 16.0) + 16.0
        return snap(raw.toFloat())
    }

    /** 吸附到 0.5℃。 */
    private fun snap(t: Float): Float {
        val snapped = Math.round(t * 2f) / 2f
        return snapped.coerceIn(16f, 32f)
    }

    /**
     * 温度文案：固定一位小数。
     */
    private fun format(t: Float): String = String.format(Locale.US, "%.1f°", t)

    companion object {
        /** 圆环半径（设计 px）—— 描边 8，外径 236。 */
        const val RING_R = 114f

        /** 圆环圆心 y（设计 px，相对容器顶）。 */
        const val RING_CY = 186f

        /** 圆环描边宽度（设计 px）—— 设计板 SVG `stroke-width="8"` × (260/100) = 20.8。 */
        const val RING_STROKE = 20.8f

        /**
         * 弧长口径的温度跨度（设计 px）。
         *
         * 设计板 `s04-hvac` 的圆环满圈对应 **30℃**（16℃ 起点不占弧），
         * 23.5℃ → 78.3% 与 SVG 的 `dasharray/dashoffset` 完全吻合。
         */
        const val ARC_TEMP_SPAN = 30f

        /** 强调色弧起点（度）—— 正上方。 */
        const val RING_START_ANGLE = -90f

        /** 温度字号（设计 px）。 */
        const val TEMP_TEXT_SIZE = 62f

        /** caption 字号（设计 px）。 */
        const val CAPTION_TEXT_SIZE = 26f

        /** caption 基线（设计 px，相对容器顶）。 */
        const val CAPTION_BASELINE = 26f

        /** ± 按钮边长（设计 px）。 */
        const val BUTTON_SIZE = 72f

        /** ± 按钮圆角（设计 px）。 */
        const val BUTTON_RADIUS = 24f

        /** ± 按钮顶部（设计 px，相对容器顶）。 */
        const val BUTTON_TOP = 352f

        /** ± 按钮中心间距（设计 px）。 */
        const val BUTTON_GAP = 64f

        /** ± 按钮字形字号（设计 px）。 */
        const val BUTTON_TEXT_SIZE = 30f

        /** 单次步进 0.5℃。 */
        const val STEP = 0.5f
    }
}
