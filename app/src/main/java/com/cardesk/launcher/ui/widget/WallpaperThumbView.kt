package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Wallpapers.WallpaperSpec

/**
 * 壁纸缩略图（设置页用）。
 *
 * 画的是**和桌面上一模一样**的那张壁纸 —— 同一个 [WallpaperRenderer]、同一套配色，
 * 只是尺寸小，所以缩略图选的就是屏幕上会看到的那张。
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
            field = value
            invalidate()
        }

    /** 是否是当前选中的那张（避开 View 自带的 `selected` 属性）。 */
    var chosen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val radius = Shape.INNER
    private val clipPath = Path()
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
        WallpaperRenderer.draw(canvas, spec, w, h, night)
        canvas.restore()
        border.color = if (chosen) accentColor else 0x00000000
        canvas.drawRoundRect(RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f), radius, radius, border)
    }
}
