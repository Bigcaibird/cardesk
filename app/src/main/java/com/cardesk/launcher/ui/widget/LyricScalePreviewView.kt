package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.media.LyricBlockLayout
import com.cardesk.launcher.core.media.LyricLayout
import com.cardesk.launcher.core.media.LyricSizeTier
import com.cardesk.launcher.ui.pages.ScenePage

/**
 * 设置页里的「首页中央歌词」**预览条**（第 115 轮；第 117 轮改成跟布局联动）。
 *
 * 画的是**桌面正中那块歌词的缩小版**：同样的行数、同样的字号比例、同样的行距、
 * 同样的配色档、同样的居中规则 —— 拖滑杆、换布局时能立刻看到「长什么样」。
 *
 * 走的是 [LyricBlockLayout] + [LyricLayout] 那两份**共用规则**，
 * 所以预览跟桌面实际画出来的一致：
 * 放大到几成就几成、会不会被截断、块是不是还居中，所见即所得。
 *
 * 宽度用**真实的**歌词最大宽度（[ScenePage.LYRIC_MAX_W]），
 * 所以「一句能显示多少字」也是真的（预览 View 比它窄时按 View 宽度截）。
 */
class LyricScalePreviewView(context: Context) : View(context) {

    /** 缩放系数（0.7 ~ 1.4，1 = 设计原值）。 */
    var scale: Float = 1f
        set(value) {
            val next = value.coerceIn(0.7f, 1.4f)
            if (field == next) return
            field = next
            invalidate()
        }

    /**
     * 预览的**布局**（跟桌面同一个开关）。
     *
     * 换布局时预览要立刻跟着变 —— 否则用户在设置里选了半天，
     * 看到的还是旧布局。
     */
    var layout: LyricLayout = LyricLayout.THREE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 歌词窗口（顺序 = 布局的行序：最上 → 最下）。
     *
     * 空串 = 那一行不画（位置仍然留着，跟桌面一样）。
     * 长度应当等于布局的行数；短了就当空行处理。
     */
    var lyricLines: List<String> = defaultLines(LyricLayout.THREE)
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 底板：跟桌面卡片一样的「浮屿」底色，衬得住歌词。
        box.set(0f, 0f, w, h)
        val radius = 24f
        paint.style = Paint.Style.FILL
        paint.color = Skin.surface
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = Skin.NAV_STROKE
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.style = Paint.Style.FILL

        val lay = layout
        val rows = lay.rows
        val current = lay.currentIndex
        val k = scale

        // 字号 / 行距：跟桌面用的是**同一套常量**，只是整体乘了缩放。
        val sizes = FloatArray(rows) { sizeOf(lay.sizeTiers[it]) * k }
        val asc = FloatArray(rows)
        val desc = FloatArray(rows)
        for (i in 0 until rows) {
            paint.typeface = if (i == current && lay.boldCurrent) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
            paint.textSize = sizes[i]
            val fm = paint.fontMetrics
            asc[i] = -fm.ascent
            desc[i] = fm.descent
        }
        val base = LyricBlockLayout.baselines(
            asc,
            desc,
            ScenePage.LYRIC_GAP_MAIN * lay.gapScale * k,
            rows,
            current,
        )
        val shift = LyricBlockLayout.centerShift(asc, desc, base, h / 2f, rows)
        for (i in 0 until rows) base[i] += shift

        // 截断宽度跟桌面一致（预览 View 比它窄时按窄的来）。
        val maxW = minOf(ScenePage.LYRIC_MAX_W, w - 16f)
        paint.textAlign = Paint.Align.CENTER
        for (i in 0 until rows) {
            val s = lyricLines.getOrNull(i) ?: continue
            if (s.isEmpty()) continue
            paint.typeface = if (i == current && lay.boldCurrent) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
            paint.textSize = sizes[i]
            paint.color = colorOf(lay.sizeTiers[i])
            canvas.drawText(clip(s, maxW), w / 2f, base[i], paint)
        }
    }

    /** 字号档 → 实际字号（跟桌面同一套常量）。 */
    private fun sizeOf(tier: Int): Float = when (tier) {
        LyricSizeTier.MAIN -> ScenePage.LYRIC_MAIN_SIZE
        LyricSizeTier.NEAR -> ScenePage.LYRIC_NEAR_SIZE
        else -> ScenePage.LYRIC_FAR_SIZE
    }

    /** 字号档 → 颜色（越靠近当前句越亮，跟桌面一致）。 */
    private fun colorOf(tier: Int): Int = when (tier) {
        LyricSizeTier.MAIN -> Skin.TEXT
        LyricSizeTier.NEAR -> Skin.TEXT_SOFT
        else -> Skin.TEXT_DIM
    }

    /** 超宽时按末尾省略（跟桌面一致）。 */
    private fun clip(s: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return s
        if (paint.measureText(s) <= maxWidth) return s
        var out = s
        while (out.isNotEmpty() && paint.measureText("$out…") > maxWidth) out = out.dropLast(1)
        return "$out…"
    }

    companion object {
        /**
         * 没有歌词时用的示例文案（按布局给对应行数）。
         *
         * 顺序 = 布局的行序（最上 → 最下）。
         */
        fun defaultLines(layout: LyricLayout): List<String> = when (layout) {
            // 三行：上一句 / 当前句 / 下一句
            LyricLayout.THREE -> listOf(
                "第一句 夜空中最亮的星",
                "第二句 风雨里追赶",
                "第三句 我们都曾勇敢地往前走",
            )
            // KTV 两行：当前句 / 下一句（当前句在上）
            LyricLayout.KTV -> listOf(
                "当前句 我们都曾勇敢地往前走",
                "下一句 直到看见远方的灯火",
            )
        }
    }
}
