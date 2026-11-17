package com.cardesk.launcher.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.cardesk.launcher.core.media.MediaActions

/**
 * 音乐卡「收藏 / 播放模式」两个按钮的**图标**（第 195 轮）。
 *
 * ## 为什么不用文字符号
 *
 * 第一版直接拿 Unicode 字符当图标（`♡` / `♥` / `顺序` / `随机`），
 * 实机截图出来两个问题：
 *
 * - `♡` 在车机的字体里是个**细瘦的轮廓**，而且上下不对称 —— 看着像
 *   「画歪了的对勾」，不像心；
 * - 播放模式写成**中文**（顺序 / 单曲 / 随机）→ 跟周围的纯图形控件
 *   （‹ ▶ ›）不是一个语言，视觉上多了一块文字噪声。
 *
 * → 改成**矢量图标**：心形 + 三个标准播放模式符号。
 *
 * ## 为什么是自绘而不是贴 SVG / 图片资源
 *
 * - 项目约定「所有图形由本项目自绘，不使用任何图片资源」（见 [Skin] 的 KDoc）；
 * - 设备端 `android.graphics.Path` **没有公开的 `parse`**（那是 @hide），
 *   裁剪过的 `android.jar` 里也没有 → SVG path data 解析不了；
 * - 所以这里用**几何图元**（直线 / 贝塞尔 / 圆弧）画，
 *   全部落在 24×24 的基准视口里，绘制时按实际 bounds 等比缩放 → 任意尺寸都不糊。
 *
 * ## 这几个符号用户已经认得
 *
 * | 模式 | 符号 | 约定 |
 * |---|---|---|
 * | 顺序播放 | 上下两条反向箭头 `⇄` | 列表循环（主流播放器都这么画） |
 * | 单曲循环 | 圆弧箭头 + 中间一个 1 | repeat-one |
 * | 随机播放 | 两条交叉的折线 + 箭头 | shuffle |
 *
 * 收藏则是**心形**（空心 / 实心两态）。
 */
object MediaActionIcons {

    /** 图标种类。 */
    enum class Kind {
        /** 空心心形（未收藏 / 不知道）。 */
        HEART,

        /** 实心心形（已收藏）。 */
        HEART_FILLED,

        /** 顺序播放（列表循环）。 */
        REPEAT_ALL,

        /** 单曲循环。 */
        REPEAT_ONE,

        /** 随机播放。 */
        SHUFFLE,
    }

    /**
     * 收藏图标。
     *
     * @param filled true = 实心（已收藏）；false = 空心（未收藏 / 不知道）
     * @param color 图标颜色
     */
    fun heart(filled: Boolean, color: Int): Drawable =
        MediaActionIconDrawable(if (filled) Kind.HEART_FILLED else Kind.HEART, color)

    /**
     * 播放模式图标。
     *
     * 状态**未知**时（null）画「顺序」——
     * 因为此时点一下就是切到顺序播放（见 [MediaActions.nextPlayMode]），
     * 按钮上显示的就是**点完之后的那个模式**。
     *
     * @param mode 当前模式；null = 不知道
     * @param color 图标颜色
     */
    fun playMode(mode: MediaActions.PlayMode?, color: Int): Drawable = MediaActionIconDrawable(
        when (mode) {
            MediaActions.PlayMode.REPEAT_ONE -> Kind.REPEAT_ONE
            MediaActions.PlayMode.SHUFFLE -> Kind.SHUFFLE
            // REPEAT_ALL 与「未知」都画顺序箭头。
            else -> Kind.REPEAT_ALL
        },
        color,
    )
}

/**
 * 音乐卡动作按钮的图标 [Drawable]。
 *
 * 图形全部画在 **24×24 基准视口**里，[draw] 时按 bounds **等比缩放**，
 * 取短边 → 容器什么形状都不会把图形拉变形。
 */
class MediaActionIconDrawable(
    private val kind: MediaActionIcons.Kind,
    color: Int,
) : Drawable() {

    private val strokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var tint: Int = color

    /** 换颜色（并重绘）。 */
    fun setIconColor(color: Int) {
        if (tint == color) return
        tint = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = minOf(b.width(), b.height()) / VIEWPORT
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(scale, scale)
        strokePaint.color = tint
        fillPaint.color = tint
        when (kind) {
            MediaActionIcons.Kind.HEART -> drawHeart(canvas, filled = false)
            MediaActionIcons.Kind.HEART_FILLED -> drawHeart(canvas, filled = true)
            MediaActionIcons.Kind.REPEAT_ALL -> drawRepeatAll(canvas)
            MediaActionIcons.Kind.REPEAT_ONE -> drawRepeatOne(canvas)
            MediaActionIcons.Kind.SHUFFLE -> drawShuffle(canvas)
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ 心形

    /**
     * 心形。
     *
     * 两条三次贝塞尔拼一个标准心形：左右两瓣各一段曲线，
     * 顶端在 (12, 5.6) 处收一个**凹口**（没有凹口就不是心，是水滴）。
     *
     * 空心用**描边**、实心用**填充**——
     * 同一路径两种画法，视觉重量才对得上（描边会向外扩 1px，
     * 正好补上填充比描边「小一圈」的差）。
     */
    private fun drawHeart(canvas: Canvas, filled: Boolean) {
        val path = heartPath
        if (filled) {
            canvas.drawPath(path, fillPaint)
        } else {
            canvas.drawPath(path, strokePaint)
        }
    }

    private val heartPath: Path by lazy {
        Path().apply {
            moveTo(12f, 20.8f)
            // 左瓣：从底部尖角往上，甩到左侧圆弧
            cubicTo(12f, 20.8f, 3.2f, 14.6f, 3.2f, 9.0f)
            cubicTo(3.2f, 5.9f, 5.6f, 3.4f, 8.6f, 3.4f)
            // 顶端收口（凹口）
            cubicTo(10.3f, 3.4f, 11.6f, 4.6f, 12f, 5.7f)
            cubicTo(12.4f, 4.6f, 13.7f, 3.4f, 15.4f, 3.4f)
            // 右瓣
            cubicTo(18.4f, 3.4f, 20.8f, 5.9f, 20.8f, 9.0f)
            cubicTo(20.8f, 14.6f, 12f, 20.8f, 12f, 20.8f)
            close()
        }
    }

    // ------------------------------------------------------------------ 顺序播放

    /**
     * 顺序播放（列表循环）：上下两条**反向**箭头。
     *
     * 上箭头向右、下箭头向左 —— 就是播放器里表示「列表循环」的那个 `⇄`。
     */
    private fun drawRepeatAll(canvas: Canvas) {
        // 上：向左的横线 + 右端箭头
        canvas.drawLine(4.5f, 9f, 18.6f, 9f, strokePaint)
        canvas.drawLine(15.3f, 5.7f, 18.6f, 9f, strokePaint)
        canvas.drawLine(15.3f, 12.3f, 18.6f, 9f, strokePaint)
        // 下：向右的横线 + 左端箭头
        canvas.drawLine(19.5f, 15f, 5.4f, 15f, strokePaint)
        canvas.drawLine(8.7f, 11.7f, 5.4f, 15f, strokePaint)
        canvas.drawLine(8.7f, 18.3f, 5.4f, 15f, strokePaint)
    }

    // ------------------------------------------------------------------ 单曲循环

    /**
     * 单曲循环：圆弧箭头 + 中间一个 1。
     *
     * 圆弧从 40° 起顺时针扫 270°（缺口在右侧），
     * 箭头头部接在弧的**起点**（右下角），指向切线方向。
     */
    private fun drawRepeatOne(canvas: Canvas) {
        canvas.drawArc(arcRect, 40f, 270f, false, strokePaint)
        // 箭头头部：弧起点 (18.13, 17.14)，切线指向左下
        canvas.drawLine(15.2f, 17.0f, 18.13f, 17.14f, strokePaint)
        canvas.drawLine(16.9f, 14.6f, 18.13f, 17.14f, strokePaint)
        // 中间的 1：一小段斜线接一条竖线
        canvas.drawLine(10.3f, 11.3f, 12f, 9.6f, strokePaint)
        canvas.drawLine(12f, 9.6f, 12f, 15.2f, strokePaint)
    }

    private val arcRect = RectF(4f, 4f, 20f, 20f)

    // ------------------------------------------------------------------ 随机播放

    /**
     * 随机播放：两条**交叉**的折线，各带一个向右的箭头。
     *
     * 交叉点是识别「随机」的关键 —— 跟顺序播放那两条**平行**箭头正好区分开。
     */
    private fun drawShuffle(canvas: Canvas) {
        // A：左上出发 → 斜下 → 右下
        canvas.drawLine(4.5f, 8f, 8.4f, 8f, strokePaint)
        canvas.drawLine(8.4f, 8f, 15.8f, 15.4f, strokePaint)
        canvas.drawLine(15.8f, 15.4f, 19.4f, 15.4f, strokePaint)
        canvas.drawLine(17.1f, 13.1f, 19.4f, 15.4f, strokePaint)
        canvas.drawLine(17.1f, 17.7f, 19.4f, 15.4f, strokePaint)
        // B：左下出发 → 斜上 → 右上
        canvas.drawLine(4.5f, 16f, 8.4f, 16f, strokePaint)
        canvas.drawLine(8.4f, 16f, 15.8f, 8.6f, strokePaint)
        canvas.drawLine(15.8f, 8.6f, 19.4f, 8.6f, strokePaint)
        canvas.drawLine(17.1f, 6.3f, 19.4f, 8.6f, strokePaint)
        canvas.drawLine(17.1f, 10.9f, 19.4f, 8.6f, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = VIEWPORT.toInt()

    override fun getIntrinsicHeight(): Int = VIEWPORT.toInt()

    private companion object {
        /** 基准视口边长。 */
        const val VIEWPORT = 24f

        /** 线宽（基准视口单位）。 */
        const val STROKE_WIDTH = 2f
    }
}
