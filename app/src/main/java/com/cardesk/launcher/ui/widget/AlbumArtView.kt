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

    /**
     * 「没有封面时的占位底色」（第四十九轮「浮屿」：不透明的弱一档面）。
     */
    var placeholderColor: Int = Skin.surfaceSoft
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 封面位图的画笔（centerCrop 缩放绘制）。
     *
     * **必须与占位底的画笔分开** —— 见 [placeholderPaint] 的说明。
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 「没有封面」时那块占位底的画笔。
     *
     * ## 为什么不能和 [paint] 共用一个
     *
     * `Paint.setColor()` **连 alpha 一起改** —— 而占位底色是半透明的
     * （导航页是 `pal.withAlpha(Skin.TEXT, 0x14)`，alpha 只有 **20/255**）。
     *
     * 共用一个画笔时，真机上会踩到这条顺序：
     *
     * ① 桌面上电 → 还没有封面 → 走占位分支 → `paint` 的 alpha 被改成 **20**；
     * ② 歌曲起来 → 封面到位 → 走封面分支 → `drawBitmap(bmp, src, dst, paint)`
     *    **用的还是那个 alpha=20 的画笔** → 封面只画了 8% 的不透明度。
     *
     * 结果就是：第二页音乐卡的封面像蒙了一层**半透明灰纱**，
     * 底下隐约透出卡片底色 —— 看着就像「图上盖了一层东西」。
     *
     * （首页音乐卡与 Dock 都不受影响：它们各自 `new Paint(...)` 现画现用，
     * alpha 永远是 255。所以同一首歌，**Dock 里是亮的、第二页里是暗的**。）
     *
     * 拆成两个画笔后，占位底色怎么改都碰不到封面的不透明度。
     */
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
            placeholderPaint.color = placeholderColor
            placeholderPaint.shader = null
            canvas.drawRect(r, placeholderPaint)
        } else {
            // centerCrop：等比缩放到铺满，再居中裁掉多余部分
            val bw = bmp.width.toFloat()
            val bh = bmp.height.toFloat()
            if (bw <= 0f || bh <= 0f) {
                placeholderPaint.color = placeholderColor
                placeholderPaint.shader = null
                canvas.drawRect(r, placeholderPaint)
            } else {
                // 封面必须**满不透明度**地画 —— 半透明画笔会露出卡片底色，
                // 看起来像「图上蒙了一层灰纱」。这里显式钉死 alpha=255，
                // 免得以后有人往这个画笔上设色时又把它带暗。
                paint.alpha = 255
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
