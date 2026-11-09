package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.media.MediaSourceIconKind
import com.cardesk.launcher.core.media.MediaSources

/**
 * 媒体源图标（**View 版**，第138轮）。
 *
 * ## 为什么要有 View 版
 *
 * 首页音乐卡是 **Canvas 自绘**（`ScenePage.drawMusicCard`），
 * 来源图标直接画在画布上；可第二页（导航页）音乐卡是 **View 布局**（`NavPage`），
 * 画布上插不进去 —— 原来那里只放了一个品牌色小圆点，
 * 用户看到的是「知道是哪家，但认不出是哪个 App」。
 *
 * 这个 View 把首页那套逻辑（[MediaSourceIcons]）原样搬过来：
 *
 * 1. **真实 App 图标优先**（QQ音乐 / 网易云 / 酷狗 / 酷我 / 汽水 / 多媒体）——
 *    圆角裁剪成小方块，和首页一致；
 * 2. 取不到就用**自绘符号**（蓝牙 / USB / 通用音乐的音符）。
 *
 * ## 和首页的关系
 *
 * 两页用的是**同一份缓存**（`MediaSourceIcons.cached`）和**同一个判定**
 * （`MediaSources.iconKind`），所以不会出现「首页是 App 图标、第二页是小圆点」
 * 这种同屏不一致。真正的差异只有一处：首页是画布坐标（要自己缩放），
 * 这里直接用 View 的尺寸（`onMeasure` 固定成正方形）。
 */
class MediaSourceIconView(context: Context) : View(context) {

    /**
     * 来源 id（决定符号种类）。
     *
     * 取值口径与首页一致：`MediaSources.iconKind(sourceId)`。
     */
    var sourceId: String = MediaSources.GENERIC_ID
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /**
     * 来源文案（决定自绘符号的**品牌色**）。
     *
     * 品牌色按**文案**查（`MediaSources.brandColor(label)`），
     * 与首页 `drawMusicCard` 里的取法一致 —— 那边拿到的也是文案。
     */
    var sourceLabel: String = ""
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /**
     * 真实 App 图标（位图）。
     *
     * null = 没取到（或这个来源本来就没有对应 App）→ 走自绘符号。
     */
    var icon: Bitmap? = null
        set(v) {
            if (field === v) return
            field = v
            invalidate()
        }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val clipPath = Path()

    private val box = RectF()

    /** 图标种类（由 [sourceId] 决定，缓存一下省得每帧都去查表）。 */
    private fun kind(): MediaSourceIconKind = MediaSources.iconKind(sourceId)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 固定正方形：调用方给的宽高都当成边长。
        val size = if (MeasureSpec.getSize(widthMeasureSpec) > 0) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            suggestedMinimumWidth
        }
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val appIcon = icon
        if (appIcon != null) {
            // 圆角矩形裁剪（与首页音乐卡里的来源图标一个风格）。
            val r = minOf(w, h) * 0.22f
            box.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(box, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(appIcon, null, box, bitmapPaint)
            canvas.restore()
            return
        }
        // 没有真图标 → 自绘符号（蓝牙 / USB / 音符）。
        MediaSourceIcons.drawFallback(
            canvas,
            kind(),
            cx,
            cy,
            minOf(w, h) / 2f,
            MediaSources.brandColor(sourceLabel),
        )
    }
}
