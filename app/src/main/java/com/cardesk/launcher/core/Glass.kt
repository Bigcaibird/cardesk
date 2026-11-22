package com.cardesk.launcher.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * 「液态玻璃」材质（第 242 轮，对齐 `cardesk-ui/design` 的设计语言）。
 *
 * ## 为什么不是简单的半透明白
 *
 * 设计稿的卡片是 `rgba(255,255,255,.62)` + **背景模糊 20px**。
 * 只画半透明白，卡片下面的照片会**原样透出来**（细节全在），
 * 看起来像「贴了一张灰色塑料片」，而不是玻璃。
 *
 * API 28 没有 `RenderEffect`（API 31 才有），所以这里用
 * **预模糊背景图 + 半透明色 + 内高光 + 柔阴影** 的方式实现等价效果：
 *
 * 1. [backdrop]：`BackgroundView` 在壁纸/日夜变化时把**整屏背景**
 *    （底色 + 柔光 + 壁纸）画进一张 `1/4` 缩略图并做盒式模糊，
 *    模糊半径按 1/4 缩放（20px → 5px）。
 * 2. 每张卡片按自己的矩形**从这张图里取对应区域**，
 *    所以卡片透出来的是「它正下方那块背景的模糊版」——
 *    位置、颜色都对得上，不是一张糊死的整屏图。
 * 3. 再叠设计稿的填充色、1px 白描边（rim）、顶部 10% 的内高光，
 *    以及 `0 8 24 / 10%` 的柔阴影。
 *
 * ## 线程
 *
 * [backdrop] 在主线程写、主线程读（`BackgroundView.refreshBackdrop`），
 * 不跨线程，所以用 `@Volatile` 只是防御性写法。
 */
object Glass {

    /** 模糊背影的缩放比（1/4：480×270，够用且模糊便宜）。 */
    const val SCALE = 0.25f

    /** 设计稿的背景模糊半径（px），落到缩略图上要乘 [SCALE]。 */
    const val BLUR_DESIGN = 20f

    /** 当前背景的模糊缩略图（null = 还没有，退化成纯半透明）。 */
    @Volatile
    var backdrop: Bitmap? = null
        private set

    private val shaderMatrix = Matrix()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 发布一张**已经模糊过**的背景缩略图。
     *
     * @param blurred 尺寸应为 `Design.W*SCALE × Design.H*SCALE`
     */
    fun publish(blurred: Bitmap?) {
        backdrop = blurred
    }

    /**
     * 画一张玻璃卡片（阴影 + 模糊背景 + 填充 + 描边 + 内高光）。
     *
     * 调用方只需在返回后画内容；卡片矩形就是内容区。
     *
     * @param rect 卡片矩形（设计坐标）
     * @param radius 圆角
     * @param fill 填充色（默认 L1 卡面；L2 浮层传更实的那个）
     * @param shadow 是否画柔阴影（贴在别的卡上的小卡可以不画）
     * @param sheen 是否画顶部内高光
     */
    fun card(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        fill: Int = if (Skin.isNight) 0xA8161B22.toInt() else 0x9EFFFFFF.toInt(),
        shadow: Boolean = true,
        sheen: Boolean = true,
        stroke: Int = if (Skin.isNight) 0x1FFFFFFF else 0x59FFFFFF,
    ) {
        if (shadow) drawShadow(canvas, rect, radius)

        val bmp = backdrop
        if (bmp != null && !bmp.isRecycled) {
            shaderMatrix.setScale(bmp.width / Design.W, bmp.height / Design.H)
            bitmapPaint.shader = android.graphics.BitmapShader(
                bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP,
            ).apply { setLocalMatrix(shaderMatrix) }
            canvas.drawRoundRect(rect, radius, radius, bitmapPaint)
            bitmapPaint.shader = null
        }

        fillPaint.color = fill
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (sheen) {
            // 顶部 38% 高度的一道白，8% → 0：玻璃的「湿润感」全靠它。
            sheenPaint.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.top + rect.height() * 0.38f,
                if (Skin.isNight) 0x14FFFFFF else 0x2EFFFFFF,
                0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
            sheenPaint.shader = null
        }

        rimPaint.color = stroke
        rimPaint.strokeWidth = 1f
        canvas.drawRoundRect(
            RectF(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f),
            radius - 0.5f, radius - 0.5f, rimPaint,
        )
    }

    /**
     * 只画柔阴影（`0 8 24 / 10%` 的近似）。
     *
     * `setShadowLayer` 在硬件加速画布上对**形状**不生效（只对文字生效），
     * 而给每张卡开软件图层 `saveLayer` 在车机上太贵，
     * 所以用「多层渐隐圆角矩形」逼近：外扩 2/5/8/12px，alpha 4%→1%。
     */
    fun drawShadow(canvas: Canvas, rect: RectF, radius: Float) {
        val base = if (Skin.isNight) 0xFF000000.toInt() else 0xFF1B2A3A.toInt()
        val rings = floatArrayOf(12f, 8f, 5f, 2f)
        val alphas = intArrayOf(4, 6, 8, 10)
        for (i in rings.indices) {
            shadowPaint.color = Color.argb(
                alphas[i],
                Color.red(base), Color.green(base), Color.blue(base),
            )
            val e = rings[i]
            canvas.drawRoundRect(
                RectF(rect.left - e, rect.top - e + 8f, rect.right + e, rect.bottom + e + 8f),
                radius + e, radius + e, shadowPaint,
            )
        }
    }

    /** 清掉（退出桌面 / 视频壁纸接管时）。 */
    fun clear() {
        backdrop = null
    }

    /**
     * 盒式模糊（原地，三遍 ⇒ 近似高斯）。
     *
     * @param src 源图（会被读，不会被改）
     * @param radius 缩略图上的半径（设计半径 × [SCALE]）
     */
    fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            blurPass(pixels, tmp, w, h, radius, true)
            blurPass(tmp, pixels, w, h, radius, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val span = r * 2 + 1
        if (horizontal) {
            for (y in 0 until h) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0
                val row = y * w
                for (i in -r..r) {
                    val c = src[row + i.coerceIn(0, w - 1)]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = ((a / span) shl 24) or ((rr / span) shl 16) or ((gg / span) shl 8) or (bb / span)
                    val out = src[row + (x - r).coerceIn(0, w - 1)]
                    val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                    a += (inn ushr 24) - (out ushr 24)
                    rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    gg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    bb += (inn and 0xFF) - (out and 0xFF)
                }
            }
        } else {
            for (x in 0 until w) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0
                for (i in -r..r) {
                    val c = src[i.coerceIn(0, h - 1) * w + x]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = ((a / span) shl 24) or ((rr / span) shl 16) or ((gg / span) shl 8) or (bb / span)
                    val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                    val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    a += (inn ushr 24) - (out ushr 24)
                    rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    gg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    bb += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
    }
}
