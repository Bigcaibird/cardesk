package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Wallpapers.WallpaperSpec

/**
 * 壁纸缩略图（设置页用）。
 *
 * 画的是**和桌面上一模一样**的那张壁纸 —— 同一个 [WallpaperRenderer] / 默认日夜流光底，
 * 同一套配色，只是尺寸小，所以缩略图选的就是屏幕上会看到的那张。
 */
class WallpaperThumbView(
    context: Context,
    spec: WallpaperSpec,
    private val night: Boolean,
    private val accentColor: Int,
) : View(context) {

    /** 要显示的壁纸（换壁纸时替换后重画）。 */
    var spec: WallpaperSpec = spec
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 是否展示**默认日夜壁纸**（true 时直接画与桌面一致的 L0 日夜流光渐层）。 */
    var defaultDayNight: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 默认日夜壁纸绘制用的主题色板。 */
    var palette: Palette = if (night) Palettes.night else Palettes.day
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 是否是当前选中的那张（避开 View 自带的 `selected` 属性）。 */
    var chosen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val radius = Shape.INNER
    private val clipPath = Path()
    private val washPaint = Paint(Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.DITHER_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        clipPath.reset()
        clipPath.addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        if (defaultDayNight) {
            BackgroundView.drawDefaultDayNight(canvas, palette, w, h, washPaint, glowPaint, opaqueBase = true)
        } else {
            WallpaperRenderer.draw(canvas, spec, w, h, night)
        }
        canvas.restore()
        border.color = if (chosen) accentColor else 0x00000000
        canvas.drawRoundRect(RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f), radius, radius, border)
    }
}

/**
 * 「默认日夜壁纸」双模预览卡片（左半边日间、右半边夜间，直接复用 [BackgroundView.drawDefaultDayNight]）。
 */
class DayNightWallpaperCardView(
    context: Context,
    private val accentColor: Int,
) : View(context) {

    var chosen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val radius = Shape.INNER
    private val clipPath = Path()
    private val rect = RectF()
    private val washPaint = Paint(Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.DITHER_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = 2f
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val half = w / 2f

        rect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // 左半：日间默认壁纸
        canvas.save()
        canvas.clipRect(0f, 0f, half, h)
        BackgroundView.drawDefaultDayNight(canvas, Palettes.day, half, h, washPaint, glowPaint, opaqueBase = true)
        canvas.restore()

        // 右半：夜间默认壁纸
        canvas.save()
        canvas.clipRect(half, 0f, w, h)
        canvas.translate(half, 0f)
        BackgroundView.drawDefaultDayNight(canvas, Palettes.night, w - half, h, washPaint, glowPaint, opaqueBase = true)
        canvas.restore()

        // 中缝细线
        canvas.drawLine(half, 0f, half, h, dividerPaint)

        // 左下 / 右下角标
        drawTag(canvas, "日间", 14f, h - 38f, bgColor = 0xB8FFFFFF.toInt(), fgColor = 0xFF1A2230.toInt())
        drawTag(canvas, "夜间", half + 14f, h - 38f, bgColor = 0x99121826.toInt(), fgColor = 0xFFEAF0F6.toInt())

        canvas.restore()

        border.color = if (chosen) accentColor else 0x33FFFFFF
        border.strokeWidth = if (chosen) 3f else 1.5f
        rect.set(1.5f, 1.5f, w - 1.5f, h - 1.5f)
        canvas.drawRoundRect(rect, radius, radius, border)
    }

    private fun drawTag(canvas: Canvas, label: String, left: Float, top: Float, bgColor: Int, fgColor: Int) {
        val tw = textPaint.measureText(label)
        val pw = tw + 20f
        val ph = 26f
        pillPaint.color = bgColor
        rect.set(left, top, left + pw, top + ph)
        canvas.drawRoundRect(rect, ph / 2f, ph / 2f, pillPaint)
        textPaint.color = fgColor
        val fm = textPaint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, rect.centerX(), baseline, textPaint)
    }
}
