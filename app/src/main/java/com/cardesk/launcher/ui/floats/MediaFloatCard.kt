package com.cardesk.launcher.ui.floats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import java.util.Locale

/**
 * **媒体浮窗卡**（设计板 `s13-float` 的那张 460×250 卡片）。
 *
 * ## 为什么多这一种浮窗
 *
 * 桌面浮窗体系原本只有一种形态：**承载三方 App 的浮窗**
 * （`ActivityView` 嵌入，失败时降级成「图标 + 名称 + 全屏打开」卡片）。
 * 设计板 画的是另一种形态 —— **媒体浮窗**：一块 460×250 的小卡片，
 * 上面是当前曲目、歌手、进度和关闭键，不承载 App。
 *
 * 两者都保留：
 * - 承载 App 的浮窗：抽屉里「小窗打开」走它（口径不变）
 * - 媒体浮窗：本类，入口是 **Dock 媒体卡长按**（桌面自带的小播放器）。
 *
 * ## 版式（设计坐标 1920×1080，卡片 460×250）
 *
 * `
 * 「CarDesk 浮窗」 20px textFaint (26, 20)
 * 曲名 32px bold (26, 62)
 * 歌手 22px textDim (26, 108)
 * 进度条 8px (26, 166) 宽 408（surfaceStrong 槽 + accent 进度）
 * 1:24 / 4:05 18px textFaint (26, 192)
 * 细进度线 4px (26, 212) 宽 408
 * `
 */
class MediaFloatCard(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    /** 卡片顶上的小标题（默认「CarDesk 浮窗」）。 */
    var windowTitle: String = "CarDesk 浮窗"
        set(value) {
            field = value
            invalidate()
        }

    /** 曲名。 */
    var trackTitle: String = "暂无播放"
        set(value) {
            field = value
            invalidate()
        }

    /** 歌手（可为空）。 */
    var artist: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** 播放位置（毫秒）。 */
    var positionMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    /** 总时长（毫秒）；0 = 不知道总时长（进度条画成 0）。 */
    var durationMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // —— 小标题 20px textFaint（26, 20）——
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f
        text.color = palette.textFaint
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(windowTitle, 26f, 20f + 18f, text)

        // —— 曲名 32px 粗体（26, 62）——
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 32f
        text.color = palette.text
        canvas.drawText(trackTitle, 26f, 62f + 28f, text)

        // —— 歌手 22px textDim（26, 108）——
        if (artist.isNotEmpty()) {
            text.typeface = Typeface.DEFAULT
            text.textSize = 22f
            text.color = palette.textDim
            canvas.drawText(artist, 26f, 108f + 20f, text)
        }

        // —— 进度条 8px（26, 166）宽 408 ——
        val barW = (w - 52f).coerceAtLeast(0f)
        fill.color = palette.surfaceStrong
        canvas.drawRoundRect(RectF(26f, 166f, 26f + barW, 174f), 999f, 999f, fill)
        val frac = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (frac > 0.005f) {
            fill.color = palette.accent
            canvas.drawRoundRect(RectF(26f, 166f, 26f + barW * frac, 174f), 999f, 999f, fill)
        }

        // —— 时间 18px textFaint（26, 192）——
        text.typeface = Typeface.DEFAULT
        text.textSize = 18f
        text.color = palette.textFaint
        canvas.drawText("${formatTime(positionMs)} / ${formatTime(durationMs)}", 26f, 192f + 16f, text)

        // —— 细进度线 4px（26, 212）——
        fill.color = palette.stroke
        canvas.drawRoundRect(RectF(26f, 212f, 26f + barW, 216f), 999f, 999f, fill)
    }

    /** `m:ss` / `h:mm:ss`（`formatGuideEta` 之外的播放时长口径）。 */
    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) {
            String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.CHINA, "%d:%02d", m, s)
        }
    }

    companion object {
        /** 卡片宽（设计 px）。 */
        const val CARD_W = 460

        /** 卡片高（设计 px）。 */
        const val CARD_H = 250

        /** 卡片左上角（设计 px）—— 设计板 `s13-float`。 */
        const val CARD_LEFT = 1300

        /** 卡片左上角（设计 px）—— 设计板 `s13-float`。 */
        const val CARD_TOP = 320
    }
}
