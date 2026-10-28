package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.Skin

/**
 * 专辑图：圆角 + centerCrop 的封面视图。
 *
 * 里封面出现在三处，规格各不相同
 *
 * | 位置 | 尺寸 | 圆角 ||
 * |---|---|---|---|
 * | 场景页音乐卡 | 96×96 | 20 | （`clipToOutline`） |
 * | Dock 媒体槽 | 60×60 | 16 | |
 * | 导航页组合卡 | 80×80 | 26 | |
 *
 * ## 为什么自己画而不用 ImageView.clipToOutline
 *
 * `clipToOutline` 只在**硬件加速**绘制路径上生效，软件绘制（Robolectric 预览、
 * 部分车机 ROM 的离屏渲染会画成方角。所以这里用 `Canvas.clipPath` 自绘，
 * 哪里都一致。
 */
class AlbumArtView(context: Context) : View(context) {

    /** 封面位图；null = 画占位底色。 */
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 圆角半径（设计坐标 px）。 */
    var cornerRadius: Float = 26f
        set(value) {
            field = value
            invalidate()
        }

    /** 没有封面时的占位底色（第四十九轮「浮屿」：不透明的弱一档面）。 */
    var placeholderColor: Int = Skin.surfaceSoft
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val src = Rect()
    private val dst = Rect()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = RectF(0f, 0f, w, h)
        path.reset()
        path.addRoundRect(r, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        val bmp = bitmap
        if (bmp == null) {
            paint.color = placeholderColor
            paint.shader = null
            canvas.drawRect(r, paint)
        } else {
            // centerCrop：等比缩放到铺满，再居中裁掉多余部分
            val bw = bmp.width.toFloat()
            val bh = bmp.height.toFloat()
            if (bw <= 0f || bh <= 0f) {
                canvas.drawRect(r, paint)
            } else {
                val scale = maxOf(w / bw, h / bh)
                val sw = w / scale
                val sh = h / scale
                src.set(
                    ((bw - sw) / 2f).toInt().coerceAtLeast(0),
                    ((bh - sh) / 2f).toInt().coerceAtLeast(0),
                    ((bw + sw) / 2f).toInt().coerceAtMost(bmp.width),
                    ((bh + sh) / 2f).toInt().coerceAtMost(bmp.height),
                )
                dst.set(0, 0, width, height)
                canvas.drawBitmap(bmp, src, dst, paint)
            }
        }
        canvas.restore()
    }
}
