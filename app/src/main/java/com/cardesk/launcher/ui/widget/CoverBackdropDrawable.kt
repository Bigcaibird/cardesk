package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.cardesk.launcher.core.media.CoverBackdrop

/**
 * 「专辑封面当卡片背景」的背景层。
 *
 * 它**替代**了组合卡原来的 `Skin.flat(Shape.CARD)`（一块纯色面 + 1px 描边）：
 *
 * ```
 * 有封面：模糊后的封面（圆角裁剪，已经做过亮度映射）→ 1px 描边
 * 无封面：纯色卡面（和原来的 Skin.flat 一模一样）→ 1px 描边
 * ```
 *
 * ## 为什么自己画而不用 ImageView + RenderEffect
 *
 * - `RenderEffect`（真正的模糊）是 **API 31** 才有的，本项目 `compileSdk = 29`，用不了；
 * - `RenderScript` 在 API 31 起被废弃，车机 ROM 上行为不稳；
 * - 所以走「**缩到很小再拉回来**」这条老路：先把封面裁成卡片的比例、
 *   逐级减半缩到短边 [CoverBackdrop.BLUR_SHORT_SIDE]（64px），再双线性拉回全卡。
 *   9 倍以上的放大，细节早就糊成色块 —— 这正是「氛围底」要的效果，
 *   而且全程只有两次 `drawBitmap`，**不依赖任何平台 API**。
 *
 * ## 封面怎么被「压」到读得清字
 *
 * 用的是**亮度映射**（tone map），不是叠一层半透明遮罩 ——
 * 后者为了让纯白封面落到亮度上限，遮罩要加到 92%，整张封面只剩 8% 的份量，
 * 实测下来整张卡是一片几乎均匀的深蓝灰，**封面等于没画**。
 *
 * 亮度映射是：先量出封面里最亮的像素有多亮，再把整张图的亮度按一条曲线
 * 压进「文字读得清」的区间（缩放在线性光空间做，**色相不变**）。
 * 详细的口径见 [CoverBackdrop]。
 *
 * ## 踩过的坑
 *
 * - **中间位图必须自己回收**：`buildBlurred` 里逐级减半会产生临时位图，
 *   它们从没上过屏，可以安全 `recycle()`；
 *   但**最终那张模糊图上过屏**，换封面时**只能丢引用不能 recycle** ——
 *   硬件加速下它可能还在显示列表里，recycle 会直接崩（`Canvas: trying to use a recycled bitmap`）。
 * - **源封面不能碰**：那是媒体会话那边的位图，回收它会让别处（首页 / Dock）也炸。
 */
class CoverBackdropDrawable(
    surfaceColor: Int = 0xFF1F232D.toInt(),
    strokeColor: Int = 0xFF333947.toInt(),
    cornerRadius: Float = 28f,
    textColor: Int = 0xFF757E8E.toInt(),
) : Drawable() {

    /** 卡面色（alpha 段是「卡片不透明度」设置，见 [setStyle]）。 */
    private var surfaceColor: Int = surfaceColor

    /** 1px 描边色。 */
    private var strokeColor: Int = strokeColor

    /** 卡片圆角（设计坐标 px）。 */
    private var cornerRadius: Float = cornerRadius

    /** 最弱那档文字色 —— 遮罩强度由它反推。 */
    private var textColor: Int = textColor

    /** 封面原图（**不归我们所有**，只能读不能回收）。 */
    var bitmap: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            rebuild()
            invalidateSelf()
        }

    /**
     * 「用封面当背景」总开关。
     *
     * 关掉之后这张 drawable **只画纯色卡面 + 描边** —— 也就是退回第146轮之前
     * `Skin.flat(Shape.CARD)` 的样子（见 [drawFace]）。
     *
     * ## 为什么关掉时要把模糊图丢掉，而不是只跳过绘制
     *
     * 模糊图是**逐级减半缩到 64px 再拉回来**算出来的，一张封面要走十几次
     * `drawBitmap`（见 [buildBlurred]）。开着的时候这活儿只在换歌时干一次；
     * 关掉之后还留着它，等于白白占着一张位图、还会在换歌时白算一遍。
     *
     * 所以关掉 → `blurred` 直接丢成 null（不进 [drawFace] 的绘制分支）；
     * 再打开 → [rebuild] 会拿还在的 [bitmap] 重新算一遍。
     *
     * **注意**：丢的是引用，不是 `recycle()` —— 这张模糊图上过屏，
     * 硬件加速下可能还在显示列表里，回收会直接崩（见类注释「踩过的坑」）。
     */
    var coverEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            invalidateSelf()
        }

    /** 模糊后的小图（归我们所有，但上过屏所以换的时候只丢引用）。 */
    private var blurred: Bitmap? = null

    /**
     * 当前背景亮度的允许区间（相对亮度 `[下限, 上限]`）。
     *
     * 由「最弱那档文字与背景的对比度 ≥ 3:1」反推，见 [CoverBackdrop]。
     */
    private var toneRange: DoubleArray = doubleArrayOf(0.0, 0.0)

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clipPath = Path()
    private val dstRect = Rect()

    /**
     * 换肤 / 换卡片风格时刷新样式。
     *
     * @param surface 卡面色；**alpha 段是卡片不透明度**（设置页「卡片风格」），
     *   这里整层都会按它变透明，和原来 `Skin.flat` 的行为一致。
     * @param stroke 描边色
     * @param radius 圆角
     * @param text 卡片上最弱那档文字色
     */
    fun setStyle(surface: Int, stroke: Int, radius: Float, text: Int) {
        surfaceColor = surface
        strokeColor = stroke
        cornerRadius = radius
        textColor = text
        rebuild()
        invalidateSelf()
    }

    /** 当前算出来的背景亮度允许区间（供单测 / 排障读取）。 */
    val currentToneRange: DoubleArray get() = toneRange

    /** 当前卡面色（供单测 / 排障读取）—— 含 alpha 段（卡片不透明度）。 */
    val surfaceColorNow: Int get() = surfaceColor

    /** 当前模糊图的短边（供单测读取；null = 没有封面）。 */
    val blurredSize: Pair<Int, Int>?
        get() = blurred?.let { it.width to it.height }

    // ------------------------------------------------------------------ Drawable

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        // 模糊图的尺寸取决于卡片尺寸 → 尺寸变了要重算。
        rebuild()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = RectF(b)

        // 卡面不透明度（设置里的「卡片不透明度」）。
        // 封面层要跟卡面**一起**变透明，所以整段放进一个带 alpha 的图层里画。
        val alpha = surfaceColor ushr 24
        if (alpha >= 255) {
            drawFace(canvas, r)
        } else {
            val save = canvas.saveLayerAlpha(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                alpha,
            )
            drawFace(canvas, r)
            canvas.restoreToCount(save)
        }

        // 描边画在最上层（原来的 `Skin.flat` 也是描边压在填充上）。
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = 1f
        canvas.drawRoundRect(r, cornerRadius, cornerRadius, strokePaint)
    }

    /** 卡面 + 封面。 */
    private fun drawFace(canvas: Canvas, r: RectF) {
        facePaint.color = surfaceColor
        canvas.drawRoundRect(r, cornerRadius, cornerRadius, facePaint)

        val small = blurred ?: return

        // 封面：圆角裁剪后铺满整张卡。
        //
        // 位图里的像素**已经过亮度映射**（见 [rebuild]），
        // 所以这里直接画就行 —— 不需要再叠遮罩。
        clipPath.reset()
        clipPath.addRoundRect(r, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(small, null, r, bitmapPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        // 整体透明度由「卡片不透明度」设置决定，这里不叠加。
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // 不用颜色过滤器。
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // ------------------------------------------------------------------ 模糊

    /** 封面 / 尺寸 / 样式变了都要重算。 */
    private fun rebuild() {
        val src = bitmap
        val b = bounds
        blurred = null
        // 开关关着 → 不生成模糊图（省掉整条「缩到 64px 再拉回来」的管线）。
        if (!coverEnabled) return
        if (src == null || b.width() <= 0 || b.height() <= 0) return
        if (src.width <= 0 || src.height <= 0) return

        val small = buildBlurred(src, b.width(), b.height()) ?: return

        // 亮度映射：把封面压进「文字读得清」的亮度区间。
        //
        // 区间由「最弱那档文字（`Skin.TEXT_DIM`）与背景的对比度 ≥ 3:1」反推 ——
        // 卡面色变了（用户把卡片调亮 / 调暗）区间也会跟着变，所以每次重建都重算。
        toneRange = CoverBackdrop.targetRange(surfaceColor and 0xFFFFFF, textColor)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val mapped = CoverBackdrop.toneMap(pixels, toneRange[0], toneRange[1])
        small.setPixels(mapped, 0, small.width, 0, 0, small.width, small.height)

        blurred = small
    }

    /**
     * 封面 → 模糊后的小图（比例与卡片一致）。
     *
     * 步骤：
     * ① 按卡片的宽高比从封面里 **centerCrop** 出一块；
     * ② **逐级减半**缩到目标尺寸（一步缩 8 倍会有混叠，阶梯缩没有）；
     * ③ 得到一张和卡片同比例的小图 —— 画回卡片时是等比拉伸，不会再裁一次。
     */
    private fun buildBlurred(src: Bitmap, cardW: Int, cardH: Int): Bitmap? {
        val scale = CoverBackdrop.BLUR_SHORT_SIDE.toFloat() / minOf(cardW, cardH)
        val targetW = maxOf(1, (cardW * scale).toInt())
        val targetH = maxOf(1, (cardH * scale).toInt())

        var cur: Bitmap = src
        var curRect = centerCrop(src.width, src.height, cardW, cardH)

        // 逐级减半：每一步最多缩一半，避免大比例缩放出现锯齿 / 摩尔纹。
        while (curRect.width() > targetW * 2 && curRect.height() > targetH * 2) {
            val nw = maxOf(targetW, curRect.width() / 2)
            val nh = maxOf(targetH, (nw.toLong() * curRect.height() / curRect.width()).toInt())
            if (nw <= 0 || nh <= 0) return null
            val next = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(cur, curRect, Rect(0, 0, nw, nh), bitmapPaint)
            if (cur !== src) cur.recycle()
            cur = next
            curRect = Rect(0, 0, nw, nh)
        }

        val small = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        dstRect.set(0, 0, targetW, targetH)
        Canvas(small).drawBitmap(cur, curRect, dstRect, bitmapPaint)
        if (cur !== src) cur.recycle()
        return small
    }

    /** 从源图里裁出与目标宽高比一致的最大区域（居中）。 */
    private fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcAspect = srcW.toFloat() / srcH
        val dstAspect = dstW.toFloat() / dstH
        return if (srcAspect > dstAspect) {
            // 源更宽 → 裁左右
            val w = (srcH * dstAspect).toInt().coerceIn(1, srcW)
            val x = (srcW - w) / 2
            Rect(x, 0, x + w, srcH)
        } else {
            // 源更高 → 裁上下
            val h = (srcW / dstAspect).toInt().coerceIn(1, srcH)
            val y = (srcH - h) / 2
            Rect(0, y, srcW, y + h)
        }
    }
}
