package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.media.LyricMarquee
import com.cardesk.launcher.core.media.LyricMarqueeState
import com.cardesk.launcher.core.media.LyricWipe
import com.cardesk.launcher.core.media.LyricWipeState

/**
 * 歌词**擦除高亮**的绘制。
 *
 * 画法是「画两遍」
 *
 * 1. 整行用**暗色**（未唱到）画一遍；
 * 2. 从左边起、宽度 = `文字宽度 × 擦除比例` 的部分，用**强调色**（已唱到）再画一遍；
 * 3. 擦除边缘是一段 `max(6px, 8dp)` 的**软过渡带**（强调色 55% 透明度）。
 *
 * 这里的实现保持同样的几何，但用一次 `drawText` + 两次 `clipRect` 完成，
 * 免去里「先 `super.onDraw` 再画一遍」的重复绘制。
 *
 * 第三十九轮（4385）加了**跑马灯**：歌词放大后长句容易超宽，
 * 这时不再 `ellipsize` 截断，而是整句按 [com.cardesk.launcher.core.media.LyricMarquee] 平移。
 */
object LyricWipeText {

    /**
     * 画一行带擦除高亮的歌词（**水平居中**于 `cx`）。
     *
     * @param text 歌词内容
     * @param cx 文字中心 x（跑马灯模式下忽略）
     * @param baseline 文字基线 y
     * @param paint 画笔（**只读**文字样式：字号 / typeface / 对齐会被临时改写后还原）
     * @param activeColor 已唱到部分的颜色
     * @param inactiveColor 未唱到部分的颜色
     * @param fraction 擦除比例 0..1
     * @param edgePx 擦除边缘宽度（px）
     * @param maxWidth 最大宽度（<=0 表示不限制）；超宽按中间省略
     * @param scrollable 走**跑马灯**模式：不再省略，文字左对齐并按 [marqueeOffset] 平移
     * @param marqueeOffset 跑马灯偏移（<=0）；0 = 文字左边缘贴容器左边，负值 = 向左移
     */
    fun draw(
        canvas: Canvas,
        text: String,
        cx: Float,
        baseline: Float,
        paint: Paint,
        activeColor: Int,
        inactiveColor: Int,
        fraction: Float,
        edgePx: Float,
        maxWidth: Float = 0f,
        scrollable: Boolean = false,
        marqueeOffset: Float = 0f,
    ) {
        if (text.isEmpty()) return
        val oldColor = paint.color
        val oldAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT

        val shown = if (scrollable) text else clip(text, paint, maxWidth)
        val tw = paint.measureText(shown)
        // 跑马灯：x0 就是偏移量本身（0 = 左对齐，-overflow = 右端）。
        // 普通模式：按 cx 居中。
        val x0 = if (scrollable) marqueeOffset else cx - tw / 2f

        // 1) 整行暗色
        paint.color = inactiveColor
        canvas.drawText(shown, x0, baseline, paint)

        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) {
            paint.color = oldColor
            paint.textAlign = oldAlign
            return
        }

        // 2) 已唱到部分：强调色
        val wipeEnd = x0 + tw * f
        val edgeStart = maxOf(x0, wipeEnd - edgePx * 0.35f)
        paint.color = activeColor
        val save = canvas.save()
        canvas.clipRect(x0, baseline - paint.textSize, edgeStart, baseline + paint.textSize * 0.35f)
        canvas.drawText(shown, x0, baseline, paint)
        canvas.restoreToCount(save)

        // 3) 边缘软过渡带：强调色 55% 透明度
        if (wipeEnd > edgeStart) {
            val save2 = canvas.save()
            canvas.clipRect(edgeStart, baseline - paint.textSize, wipeEnd, baseline + paint.textSize * 0.35f)
            val alpha = (((activeColor ushr 24) and 0xFF) * LyricWipe.EDGE_ALPHA_RATIO).toInt().coerceIn(0, 255)
            paint.color = (alpha shl 24) or (activeColor and 0x00FFFFFF)
            canvas.drawText(shown, x0, baseline, paint)
            paint.color = activeColor
            canvas.restoreToCount(save2)
        }

        paint.color = oldColor
        paint.textAlign = oldAlign
    }

    /** 超宽时按中间省略（与 TextView 的 `ellipsize=END` 接近，但保留开头）。 */
    private fun clip(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f) return text
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}

/**
 * 一行歌词的自绘控件。
 *
 * 两种模式：
 *
 * - **当前句**（[wipeEnabled] = true）：带**擦除高亮**
 * - **其余句**（[wipeEnabled] = false）：整行纯色。
 *
 * 第三十九轮（4385）两种模式都支持**跑马灯**：文字比容器宽时整句水平滚动，
 * 而不是被 `ellipsize` 截掉半句。
 */
class WipeLyricTextView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private val wipeState = LyricWipeState()

    private val marqueeState = LyricMarqueeState()

    /** 歌词内容。 */
    var text: String = ""
        set(value) {
            field = value
            // 换句就重新起滚（锚点 = 换句的时刻）。
            marqueeState.setText(value, SystemClock.uptimeMillis())
            invalidate()
        }

    /** 字号（px）。 */
    var textSizePx: Float = 28f
        set(value) {
            field = value
            invalidate()
        }

    /** 是否加粗。 */
    var bold: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** 已唱到部分的颜色（第五十一轮：默认跟随主题的强调色，不再写死夜间浅蓝）。 */
    var activeColor: Int = Skin.ACCENT
        set(value) {
            field = value
            invalidate()
        }

    /** 未唱到部分的颜色（第五十一轮：跟随主题的弱文字色）。 */
    var inactiveColor: Int = Skin.TEXT_SOFT
        set(value) {
            field = value
            invalidate()
        }

    /** 是否走**擦除高亮**（当前句 true，其余句 false）。 */
    var wipeEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** 纯色模式下的文字颜色（[wipeEnabled] = false 时用；第五十一轮跟随主题）。 */
    var plainColor: Int = Skin.TEXT
        set(value) {
            field = value
            invalidate()
        }

    /** 是否允许**跑马灯**（超宽时整句滚动，而不是截断）。 */
    var scrollable: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 擦除比例目标 0..1（播放位置算出来的那一份）。
     *
     * 实际显示值会**指数逼近**这个目标（帧回调）。
     */
    var wipeTarget: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            wipeState.setTarget(field)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 用**单调时钟**（`uptimeMillis`）而不是墙上时间：
        // 擦除动画与跑马灯都是连续动画，墙上时间被系统调整（NTP 校时、用户改时间）会跳变。
        val now = SystemClock.uptimeMillis()
        val moving = wipeState.advance(now)
        if (moving) postInvalidateOnAnimation()

        paint.textSize = textSizePx
        paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT

        val viewW = width.toFloat()
        val textW = paint.measureText(text)
        val scrolling = scrollable && LyricMarquee.needsScroll(textW, viewW)
        val offset = if (scrolling) marqueeState.offset(now, textW, viewW) else 0f
        // 跑马灯要一直转下去（哪怕擦除动画已经停了）。
        if (scrolling) postInvalidateOnAnimation()

        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f

        if (!wipeEnabled) {
            // 纯色模式：整行一个颜色（其余歌词行）。
            paint.color = plainColor
            val x0 = if (scrolling) offset else viewW / 2f - textW / 2f
            canvas.drawText(text, x0, baseline, paint)
            return
        }

        val edge = LyricWipe.edgeWidth(resources.displayMetrics.density)
        LyricWipeText.draw(
            canvas = canvas,
            text = text,
            cx = viewW / 2f,
            baseline = baseline,
            paint = paint,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            fraction = wipeState.display,
            edgePx = edge,
            maxWidth = viewW,
            scrollable = scrolling,
            marqueeOffset = offset,
        )
    }
}
