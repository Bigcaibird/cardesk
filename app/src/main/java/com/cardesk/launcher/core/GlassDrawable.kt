package com.cardesk.launcher.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 给**普通 View** 用的玻璃背景（第242轮）。
 *
 * 桌面大部分页面（抽屉 / 空调 / 设置）是「ViewGroup + 子 View 背景」搭出来的，
 * 子 View 画不到 Canvas 的卡片路径上 —— 但设计稿要求它们也是**同一套玻璃**。
 * 所以这里做一个 [Drawable]：
 *
 * - 取 [Glass.backdrop]（整屏背景的模糊缩略图）里**与本 View 位置对应**的一块；
 * - 叠设计稿的填充色、1px 白描边、顶部内高光；
 * - 需要时画柔阴影。
 *
 * 位置对齐用的是画布矩阵里的**平移**（滚动容器里正好是滚动偏移）；
 * 缩放不参与 —— 玻璃是糊的，缩放误差看不出来，而算它会把代码搞复杂。
 */
class GlassDrawable(
    private var radius: Float = Shape.CARD,
    private var fill: Int = if (Skin.isNight) 0xA8161B22.toInt() else 0xBAFFFFFF.toInt(),
    private var strokeColor: Int = if (Skin.isNight) 0x1FFFFFFF else 0x59FFFFFF,
    private var shadow: Boolean = true,
    private var sheen: Boolean = true,
) : Drawable() {

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mat = Matrix()
    private val values = FloatArray(9)
    private val rect = RectF()
    private var backdropBmp: Bitmap? = null
    private var backdropShader: BitmapShader? = null
    private var cachedSheenTop = Float.NaN
    private var cachedSheenH = Float.NaN
    private var cachedSheenNight: Boolean? = null
    private var cachedSheenShader: LinearGradient? = null

    fun setFill(color: Int) {
        if (fill == color) return
        fill = color
        invalidateSelf()
    }

    fun setRadius(r: Float) {
        if (radius == r) return
        radius = r
        invalidateSelf()
    }

    fun setShadow(on: Boolean) {
        if (shadow == on) return
        shadow = on
        invalidateSelf()
    }

    fun setStroke(color: Int) {
        if (strokeColor == color) return
        strokeColor = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        if (shadow) drawShadow(canvas)

        val bmp = Glass.backdrop
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            if (bmp !== backdropBmp || backdropShader == null) {
                backdropBmp = bmp
                backdropShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            // BitmapShader 的 localMatrix 方向为「位图坐标 → 画布坐标」：
            // 480×270 的模糊缩略图铺满 1920×1080 设计坐标需要放大 (Design.W / bmp.width, Design.H / bmp.height)，
            // 再按本 View 在设计根容器内的偏移 (-ox, -oy) 对齐背后壁纸对应切片。
            val sx = Design.W / bmp.width.toFloat()
            val sy = Design.H / bmp.height.toFloat()
            var ox = 0f
            var oy = 0f
            var v = callback as? android.view.View
            val designW = Design.W.toInt()
            val designH = Design.H.toInt()
            while (v != null && !(v.width == designW && v.height == designH)) {
                ox += v.left - v.scrollX
                oy += v.top - v.scrollY
                v = v.parent as? android.view.View
            }
            mat.reset()
            mat.setScale(sx, sy)
            if (ox != 0f || oy != 0f) mat.postTranslate(-ox, -oy)
            backdropShader?.let { s ->
                s.setLocalMatrix(mat)
                bmpPaint.shader = s
                canvas.drawRoundRect(rect, radius, radius, bmpPaint)
                bmpPaint.shader = null
            }
        }

        fillPaint.color = fill
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (sheen) {
            val night = Skin.isNight
            val h = rect.height()
            if (cachedSheenShader == null || cachedSheenTop != rect.top || cachedSheenH != h || cachedSheenNight != night) {
                cachedSheenTop = rect.top
                cachedSheenH = h
                cachedSheenNight = night
                cachedSheenShader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.top + h * 0.38f,
                    if (night) 0x14FFFFFF else 0x2EFFFFFF,
                    0x00FFFFFF,
                    Shader.TileMode.CLAMP,
                )
            }
            sheenPaint.shader = cachedSheenShader
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
            sheenPaint.shader = null
        }

        rimPaint.color = strokeColor
        canvas.drawRoundRect(
            RectF(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f),
            (radius - 0.5f).coerceAtLeast(0f), (radius - 0.5f).coerceAtLeast(0f), rimPaint,
        )
    }

    private fun drawShadow(canvas: Canvas) {
        val base = if (Skin.isNight) 0xFF000000.toInt() else 0xFF1B2A3A.toInt()
        val rings = floatArrayOf(10f, 7f, 4f, 2f)
        val alphas = intArrayOf(3, 5, 7, 9)
        for (i in rings.indices) {
            shadowPaint.color = Color.argb(
                alphas[i], Color.red(base), Color.green(base), Color.blue(base),
            )
            val e = rings[i]
            canvas.drawRoundRect(
                RectF(rect.left - e, rect.top - e + 6f, rect.right + e, rect.bottom + e + 6f),
                radius + e, radius + e, shadowPaint,
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
