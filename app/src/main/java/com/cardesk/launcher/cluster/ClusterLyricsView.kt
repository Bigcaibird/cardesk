package com.cardesk.launcher.cluster

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.cardesk.launcher.core.media.LyricWipe
import com.cardesk.launcher.ui.widget.LyricWipeText

/**
 * 仪表屏上的歌词面板（纯 `View` + Canvas）。
 *
 * ## 为什么不用第二个渲染引擎
 *
 * 面板里只有三行字，Canvas 画一遍是几十微秒；再起一套引擎去画，
 * 既要重新注册一遍插件，还要给那块屏单独喂一份 `DisplayMetrics`。
 *
 * ## 为什么高亮直接复用 [LyricWipeText]
 *
 * UNI 那边踩过一个很难看的坑：桌面卡片读 LRC、面板读 KRC，
 * 同一首歌在两处显示成两回事；后来靠「两份歌词文档统一」才修好。
 *
 * 这里从根上避免：面板的高亮**直接调用桌面卡片那支画笔**
 * （[LyricWipeText.draw]），比例也是同一个
 * （`LyricTimeline.wipeFraction`）——所以仪表上的擦除边界
 * 和桌面卡片**逐像素一致**，不存在「两份各自估算」。
 *
 * 移植自 UNI 桌面（`ClusterLyricsView`），差别是那边用「已唱前缀字符串」做渐变，
 * 这边用比例 + 同一支画笔，两边天然对齐。
 */
class ClusterLyricsView(context: Context) : View(context) {

    companion object {
        /** 胶囊底板：一条半透明圆角底，压在仪表自己的画面上方。 */
        const val STYLE_PILL = 0

        /** 纯文字：不画底、不加阴影。 */
        const val STYLE_PLAIN = 1

        /** 发光：加重阴影，字看起来是「亮」在仪表上。 */
        const val STYLE_GLOW = 2

        /** 行文距（占屏宽比例）：左右各留一点，不要顶到边。 */
        private const val SIDE_MARGIN = 0.03f

        /** 发光样式的光晕半径（px）。 */
        private const val GLOW_RADIUS = 12f

        /**
         * 主歌词的描边宽度系数（相对字号）。
         *
         * 描边画在字形轮廓上，露在外面的宽度约为它的一半，
         * 所以 0.10 听起来小，实际在 42px 的主行上是**约 2px** 的一圈白边/黑边。
         */
        private const val HALO_RATIO_MAIN = 0.10f

        /**
         * 副歌词的描边宽度系数（相对字号）。
         *
         * 小字**不能**按比例放大描边：18sp 在 240dpi 屏上只有 27px，
         * 笔画本身才 2~3px，描边一厚就把笔画挤没了（字看着像个白壳子）。
         * 分离度靠的是**明暗差**（描边和底色差 5 : 1 以上），不是靠宽度堆 ——
         * 所以这里给小字的比例反而比大字还小一点。
         */
        private const val HALO_RATIO_SUB = 0.09f

        /** 胶囊底板的描边宽度（px）。 */
        private const val PLATE_STROKE_WIDTH = 2f

        /** 深色底板上的描边色（半透明白）。 */
        private const val PLATE_STROKE_ON_DARK = 0x66FFFFFF

        /** 浅色底板上的描边色（半透明黑）。 */
        private const val PLATE_STROKE_ON_LIGHT = 0x66000000
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plateRect = RectF()

    // ---------------------------------------------------------------- 配置

    /** 当前句字号（px，已按目标屏密度换算）。 */
    var mainSizePx: Float = 28f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** 上一句 / 下一句字号（px）。 */
    var subSizePx: Float = 18f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** 可见行数 1..3。 */
    var rows: Int = ClusterPanelGeometry.DEFAULT_ROWS
        set(value) {
            field = ClusterPanelGeometry.rows(value)
            requestLayout()
            invalidate()
        }

    /** 已唱到部分 + 当前句的颜色。 */
    var accentColor: Int = 0xFF7FE3D4.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** 上一句 / 下一句的颜色。 */
    var mutedColor: Int = 0xFF9FB4BB.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 文字**描边色**（`0` = 不描边）。
     *
     * ## 这是「压在地图上还看得清」的关键（第一百四十二轮）
     *
     * 承载面是高德地图，底色**不是纯色**：日间是米白 + 绿地 + 深色 POI 标签，
     * 夜间是深蓝黑 + 青蓝路网 + 亮色路名。只靠一个字色压不住 ——
     * 深字会陷进地图的深色细节里，浅字会混进地图的青色元素里。
     *
     * 描边是地图类 UI 的标准做法：字形外圈套一圈与底色**相反**的颜色
     * （白天套白边、夜里套黑边），**不管底下是地图还是照片，字都立得住**。
     */
    var haloColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 底板颜色（alpha = 0 时不画底）。
     *
     * 名字**不能叫 `backgroundColor`**：`View` 已经有 `setBackgroundColor(Int)`，
     * 同名属性会生成同一个 JVM 签名 `setBackgroundColor(I)V`，
     * 编译器直接报 "Accidental override"。所以叫 `plateColor`（底板色）。
     */
    var plateColor: Int = 0xB3000000.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** 样式（[STYLE_PILL] / [STYLE_PLAIN] / [STYLE_GLOW]）。 */
    var style: Int = STYLE_PILL
        set(value) {
            field = value
            invalidate()
        }

    /** 当前要画的快照。 */
    var snapshot: ClusterLyricSnapshot = ClusterLyricSnapshot()
        set(value) {
            field = value
            invalidate()
        }

    /** 一次性改完所有配置（避免每个 setter 都触发一次重绘）。 */
    fun configure(
        mainSizePx: Float,
        subSizePx: Float,
        rows: Int,
        accent: Int,
        muted: Int,
        background: Int,
        style: Int,
        halo: Int = 0,
    ) {
        this.mainSizePx = mainSizePx
        this.subSizePx = subSizePx
        this.rows = rows
        this.accentColor = accent
        this.mutedColor = muted
        this.plateColor = background
        this.style = style
        this.haloColor = halo
    }

    // ---------------------------------------------------------------- 测量

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val wanted = ClusterPanelGeometry.height(mainSizePx, subSizePx, rows)
        setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1).coerceAtLeast(wanted))
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        if (style != STYLE_PLAIN && (plateColor ushr 24) != 0) {
            drawPlate(canvas, w, h)
        }

        val state = snapshot
        if (!state.hasLyrics) {
            // 三种空态说三件不同的事（见 [ClusterLyricSnapshot.emptyLabel]）。
            // 空态时整块面板**就这一句话**，所以要垂直居中 ——
            // 放在第一行的话字会贴在底板上沿，看起来像「面板画歪了」。
            drawCenteredRow(canvas, state.emptyLabel(), mutedColor, 0.82f)
            return
        }

        // 行表：每一行 = 文字 + 字号 + 是否带擦除。
        // 行数决定**哪一行是当前句**（大字 + 擦除）。
        val lines = rowsOf(state)

        // 逐行往下排：**累加**每一行占的高度。
        var top = 0f
        for (line in lines) {
            if (line.text.isNotEmpty()) {
                if (line.wipe) {
                    drawWipeRow(canvas, line.text, top, line.size, state.wipe)
                } else {
                    drawPlainRow(canvas, line.text, top, line.size, mutedColor, line.alpha)
                }
            }
            top += line.size * ClusterPanelGeometry.ROW_SPACING
        }
    }

    /**
     * 按 [rows] 组出这一帧要画的几行。
     *
     * | rows | 上一句 | 当前句 | 下一句 |
     * | --- | --- | --- | --- |
     * | 1 | — | 大字 + 擦除 | — |
     * | 2 | — | 大字 + 擦除 | 小字 |
     * | 3 | 小字 | 大字 + 擦除 | 小字 |
     */
    private fun rowsOf(state: ClusterLyricSnapshot): List<Line> = when (rows) {
        1 -> listOf(Line(state.currentText, mainSizePx, wipe = true, alpha = 1f))
        2 -> listOf(
            Line(state.currentText, mainSizePx, wipe = true, alpha = 1f),
            Line(state.nextText, subSizePx, wipe = false, alpha = 0.7f),
        )
        else -> listOf(
            Line(state.previousText, subSizePx, wipe = false, alpha = 0.72f),
            Line(state.currentText, mainSizePx, wipe = true, alpha = 1f),
            Line(state.nextText, subSizePx, wipe = false, alpha = 0.7f),
        )
    }

    /**
     * 一行歌词。
     *
     * @param size 字号（px）
     * @param wipe 是否带擦除高亮（只有当前句带）
     * @param alpha 纯色行的透明度
     */
    private data class Line(
        val text: String,
        val size: Float,
        val wipe: Boolean,
        val alpha: Float,
    )

    /**
     * 底板。
     *
     * 宽条上用「胶囊」圆角读起来像一块贴在仪表上的药丸；
     * 30% 的圆角则更像一块属于这块屏的板子。
     *
     * ## 为什么底板要带一圈描边（第一百四十三轮）
     *
     * 底板**本身**的颜色是跟着日夜翻的：夜里是接近黑的深色，白天是接近白的浅色。
     * 这在「纯色仪表盘面」上没问题，但在**地图**上有个硬伤 ——
     *
     * - 夜间的深色底板压在夜间地图（深蓝黑）上，几乎**同色**；
     * - 日间的浅色底板压在日间地图（米白）上，也几乎同色。
     *
     * 结果就是「胶囊」样式画出来了、却看不出是一块胶囊 —— 和「纯文字」没区别。
     *
     * 所以底板加一圈描边，颜色取**与底板明度相反**的那一边
     * （深底板配浅描边、浅底板配深描边，见 [plateStrokeColor]）。
     * 这和文字描边是同一个思路：**形状要靠描边才立得住**。
     */
    private fun drawPlate(canvas: Canvas, w: Int, h: Int) {
        val radius = minOf(h * 0.30f, w * 0.02f)
        backgroundPaint.shader = null
        backgroundPaint.color = plateColor
        plateRect.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(plateRect, radius, radius, backgroundPaint)
        // 顶边一道柔光，别让胶囊读成一条黑杠。
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf((plateColor and 0x00FFFFFF) or 0x14FFFFFF, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(plateRect, radius, radius, backgroundPaint)
        backgroundPaint.shader = null
        // 描边：与底板明度相反，保证胶囊的**形状**在任何底色上都看得出来。
        backgroundPaint.style = Paint.Style.STROKE
        backgroundPaint.strokeWidth = PLATE_STROKE_WIDTH
        backgroundPaint.color = plateStrokeColor()
        canvas.drawRoundRect(plateRect, radius, radius, backgroundPaint)
        backgroundPaint.style = Paint.Style.FILL
    }

    /**
     * 胶囊描边色：**与底板明度相反**。
     *
     * 深底板 → 浅描边；浅底板 → 深描边。
     *
     * 明度用经典的 299/587/114 加权（够判断「深还是浅」，不必上完整的 sRGB 线性化）。
     */
    private fun plateStrokeColor(): Int {
        val rgb = plateColor and 0x00FFFFFF
        val luma = (
            (rgb ushr 16 and 0xFF) * 299 +
                (rgb ushr 8 and 0xFF) * 587 +
                (rgb and 0xFF) * 114
            ) / 1000
        return if (luma < 128) PLATE_STROKE_ON_DARK else PLATE_STROKE_ON_LIGHT
    }

    /**
     * 当前句：带擦除高亮。
     *
     * 画法**就是桌面卡片那支画笔**（[LyricWipeText.draw]），
     * 所以两边的擦除边界逐像素一致。
     */
    private fun drawWipeRow(canvas: Canvas, text: String, top: Float, size: Float, fraction: Float) {
        if (text.isEmpty()) return
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val baseline = top + size * ClusterPanelGeometry.ROW_SPACING * 0.72f
        val edge = LyricWipe.edgeWidth(resources.displayMetrics.density)
        LyricWipeText.draw(
            canvas = canvas,
            text = text,
            cx = width / 2f,
            baseline = baseline,
            paint = paint,
            activeColor = accentColor,
            inactiveColor = mutedColor,
            fraction = fraction.coerceIn(0f, 1f),
            edgePx = edge,
            maxWidth = width * (1f - SIDE_MARGIN * 2f),
            // 描边垫在字底下；光晕套在描边外面 —— 两者都由画笔自己排好顺序。
            haloColor = haloColor,
            haloWidth = haloWidth(size, HALO_RATIO_MAIN),
            glowRadius = glowRadius(),
            glowColor = accentColor,
        )
    }

    /** 上一句 / 下一句：纯色。 */
    private fun drawPlainRow(canvas: Canvas, text: String, top: Float, size: Float, color: Int, alpha: Float) {
        if (text.isEmpty()) return
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val shown = ellipsize(text, width * (1f - SIDE_MARGIN * 2f))
        val tw = paint.measureText(shown)
        val left = maxOf(width * SIDE_MARGIN, (width - tw) / 2f)
        val baseline = top + size * ClusterPanelGeometry.ROW_SPACING * 0.72f
        // 描边先画（垫底），再画正文。
        //
        // **正文必须不透明**：半透明的正文叠在描边上会被冲淡 ——
        // 深色字压在白色描边上本来就浅，再乘一层 0.7 的透明度就等于没画
        // （在深色 POI 标签上实测只剩 1.05 : 1）。
        // 层次改由「描边更淡 + 字号更小」表达：字本身始终是实的。
        drawHalo(canvas, shown, left, baseline, size, HALO_RATIO_SUB, alpha)
        paint.color = color
        paint.alpha = 255
        applyGlow()
        canvas.drawText(shown, left, baseline, paint)
        clearGlow()
        paint.alpha = 255
    }

    /**
     * 描边：字形外圈套一圈 [haloColor]。
     *
     * 用 `Paint.Style.STROKE` 画一遍字 —— 描边画在字形轮廓上，
     * 里外各占一半，内侧那一半被随后的正文盖住，
     * **露在外面的宽度约为 [width] 的一半**。
     *
     * 与正文一致：这一行压暗多少（[alpha]），描边也跟着压暗多少，
     * 否则小字那两行会出现「亮边包着暗字」的怪相。
     */
    private fun drawHalo(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        ratio: Float,
        alpha: Float = 1f,
    ) {
        if (haloColor == 0) return
        // 这一遍是「借」画笔用的：**颜色、样式、描边宽度都要原样还回去**。
        // 漏还原颜色的话，后面那次 `drawText` 会拿描边色去画正文 ——
        // 结果就是「字是描边色、描边反而看不见」的白字幽灵。
        val oldStyle = paint.style
        val oldStroke = paint.strokeWidth
        val oldColor = paint.color
        val oldAlpha = paint.alpha
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = haloWidth(size, ratio)
        paint.color = (clampAlpha(alpha) shl 24) or (haloColor and 0x00FFFFFF)
        paint.clearShadowLayer()
        canvas.drawText(text, x, baseline, paint)
        paint.style = oldStyle
        paint.strokeWidth = oldStroke
        paint.color = oldColor
        paint.alpha = oldAlpha
    }

    /** 描边宽度（px）：`0` = 不描边。 */
    private fun haloWidth(size: Float, ratio: Float): Float =
        if (haloColor == 0) 0f else size * ratio

    /**
     * 发光样式：给字加一层同色光晕。
     *
     * 仪表自己的底色通常很深，字加上光晕之后像是「亮」在仪表上，
     * 远看比纯色字更醒目 —— 这是 [STYLE_GLOW] 与 [STYLE_PILL] 的唯一区别。
     *
     * 用 [android.graphics.Paint.setShadowLayer] 实现：它对文字有效，
     * 但**只在硬件加速画布上生效**（软件画布会忽略）。
     * 本面板的窗口带 `FLAG_HARDWARE_ACCELERATED`，真机上一定生效。
     */
    private fun applyGlow() {
        if (style != STYLE_GLOW) return
        paint.setShadowLayer(GLOW_RADIUS, 0f, 0f, accentColor)
    }

    /** 去掉光晕（否则下一行的字会继承上一层的光晕）。 */
    private fun clearGlow() {
        if (style != STYLE_GLOW) return
        paint.clearShadowLayer()
    }

    /** 发光半径（`0` = 不发光）。 */
    private fun glowRadius(): Float = if (style == STYLE_GLOW) GLOW_RADIUS else 0f

    /**
     * 空态：整块面板就一句话，**垂直居中**。
     *
     * 水平居中跟普通行一样，垂直上多留了 `size * 0.35` ——
     * 这是中文/西文字体的视觉中心大致在基线上方 0.35 个字号处，
     * 直接用 `height/2` 当基线的话，字看着会偏下。
     */
    private fun drawCenteredRow(canvas: Canvas, text: String, color: Int, alpha: Float) {
        if (text.isEmpty()) return
        val size = subSizePx
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val shown = ellipsize(text, width * (1f - SIDE_MARGIN * 2f))
        val tw = paint.measureText(shown)
        val left = maxOf(width * SIDE_MARGIN, (width - tw) / 2f)
        val baseline = height / 2f + size * 0.35f
        // 空态那句也是压在地图上的，一样要描边；正文同样保持不透明（同 [drawPlainRow]）。
        drawHalo(canvas, shown, left, baseline, size, HALO_RATIO_SUB, alpha)
        paint.color = color
        paint.alpha = 255
        applyGlow()
        canvas.drawText(shown, left, baseline, paint)
        clearGlow()
        paint.alpha = 255
    }

    /**
     * 某一行的基线 y。
     *
     * 每一行占**自己那个字号**的高度（字号 × 行距系数），
     * 下一行的起点接在这一行的终点 —— **累加**。
     *
     * ## 踩过的坑
     *
     * 原来写成 `top = row × (本行字号 × 行距)`：
     * 第 2 行（大字）的基线 = 1 × 大字行高 + 大字行高 × 0.72 = 117，
     * 而第 3 行（小字）的基线 = 2 × 小字行高 + 小字行高 × 0.72 = 119 ——
     * 两行几乎**重叠**在同一处，三行歌词糊成一团。
     *
     * 根因：第 3 行的「起点」用了小字的行高去乘行号，
     * 但第 2 行实际占掉的是大字的高度 —— 两套口径。
     *
     * @param top 这一行顶部的 y（由上一行累加得到）
     * @param size 这一行的字号（px）
     */
    private fun rowBaseline(top: Float, size: Float): Float =
        top + size * ClusterPanelGeometry.ROW_SPACING * 0.72f

    /** 超宽时按中间省略（保留开头，结尾加省略号）。 */
    private fun ellipsize(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return text
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun clampAlpha(alpha: Float): Int =
        (255 * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
}
