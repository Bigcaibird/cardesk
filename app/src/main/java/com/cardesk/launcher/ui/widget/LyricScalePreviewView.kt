package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.media.LyricBlockLayout
import com.cardesk.launcher.ui.pages.ScenePage

/**
 * 设置页里的「首页中央歌词」**预览条**（第 115 轮）。
 *
 * 画的是**桌面正中那块五行歌词的缩小版**：同样的字号比例、同样的行距、
 * 同样的三档配色、同样的居中规则 —— 拖滑杆时能立刻看到「字变大之后长什么样」。
 *
 * 走的是 [LyricBlockLayout] 那份**共用排版**，所以预览跟桌面实际画出来的一致：
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
     * 五行歌词（顺序：上 2 / 上 1 / 当前 / 下 1 / 下 2）。
     *
     * 空串 = 那一行不画（位置仍然留着，跟桌面一样）。
     */
    var lyricLines: List<String> = DEFAULT_LINES
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

        val k = scale
        // 字号 / 行距：跟桌面用的是**同一套常量**，只是整体乘了缩放。
        val sizes = floatArrayOf(
            ScenePage.LYRIC_FAR_SIZE * k,
            ScenePage.LYRIC_NEAR_SIZE * k,
            ScenePage.LYRIC_MAIN_SIZE * k,
            ScenePage.LYRIC_NEAR_SIZE * k,
            ScenePage.LYRIC_FAR_SIZE * k,
        )
        val asc = FloatArray(LyricBlockLayout.ROWS)
        val desc = FloatArray(LyricBlockLayout.ROWS)
        for (i in 0 until LyricBlockLayout.ROWS) {
            paint.typeface = if (i == LyricBlockLayout.CURRENT) {
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
            ScenePage.LYRIC_GAP_MAIN * k,
            ScenePage.LYRIC_GAP_SIDE * k,
        )
        val shift = LyricBlockLayout.centerShift(asc, desc, base, h / 2f)
        for (i in 0 until LyricBlockLayout.ROWS) base[i] += shift

        // 截断宽度跟桌面一致（预览 View 比它窄时按窄的来）。
        val maxW = minOf(ScenePage.LYRIC_MAX_W, w - 16f)
        paint.textAlign = Paint.Align.CENTER
        for (i in 0 until LyricBlockLayout.ROWS) {
            val s = lyricLines.getOrNull(i) ?: continue
            if (s.isEmpty()) continue
            paint.typeface = if (i == LyricBlockLayout.CURRENT) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
            paint.textSize = sizes[i]
            paint.color = colors[i]
            canvas.drawText(clip(s, maxW), w / 2f, base[i], paint)
        }
    }

    /** 每行的颜色：越靠近当前行越亮（跟桌面一致）。 */
    private val colors: IntArray
        get() = intArrayOf(
            Skin.TEXT_DIM,
            Skin.TEXT_SOFT,
            Skin.TEXT,
            Skin.TEXT_SOFT,
            Skin.TEXT_DIM,
        )

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
         * 没有歌词时用的示例文案（五行，正好占满一个窗口）。
         *
         * 顺序：上 2 / 上 1 / 当前 / 下 1 / 下 2。
         */
        val DEFAULT_LINES: List<String> = listOf(
            "第一句 夜空中最亮的星",
            "第二句 风雨里追赶",
            "第三句 我们都曾勇敢地往前走",
            "第四句 直到看见远方的灯火",
            "第五句 请指引我靠近你",
        )
    }
}
