package com.cardesk.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator
import com.cardesk.launcher.core.NightResolver
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 冷启动占位页（第九十三轮；第二百五十六轮按画板 `b01-boot-day` / `b02-boot-night` 重做日/夜双版）。
 *
 * ## 为什么要有
 *
 * 车机开机时用户看到的是「黑屏一段时间才出桌面」：
 * 系统 starting window 用的是 `AppTheme.windowBackground`（深色渐变），
 * 而 `LauncherView` 的构造 + 首帧要等车控/天气/媒体这些初始化做完。
 * 这段时间**没有任何反馈**，观感就是「死机了」。
 *
 * 现在 `MainActivity` 第一件事就是 `setContentView` 一个轻量根容器 + 这个 View：
 * 它不读车控、不联网、不建页面，`onDraw` 只画背景 + 圆环 + 品牌字 + 一句状态，
 * **第一帧毫秒级就能出来**。真正的桌面在它背后构建，画好后再淡出它。
 *
 * ## 第二百五十六轮：日/夜双版（画板 `b01`/`b02`）
 *
 * | 元素 | 画板值（1920×1080 设计坐标，运行时按高度等比缩放） |
 * |---|---|
 * | 圆环 | 168×168 槽、r=66、描边 6、缺口弧 120°（周长 414 的 138） |
 * | 弧渐变 | 日 `#00A9A0→#4FC3F7`、夜 `#4FC3F7→#7BE0D6` |
 * | 圆心点 | r=7（日 rgba(0,150,142,.34)、夜 rgba(79,195,247,.45)） |
 * | 品牌字 | 62px / 600 / 字距 .20em（日 `#12303A`、夜 `#E9F1F8`） |
 * | 状态文案 | 22px / 字距 .06em（日 rgba(24,52,64,.58)、夜 `#6F8394`） |
 * | 进度条 | 320×3、圆角 2、光带 120 宽左右扫（**不确定式，不显示百分比**） |
 * | 间距 | 环→字 34、字→文案 16、文案→条 26 |
 *
 * ## 背景为什么用烘焙底图
 *
 * 画板底不是一条渐变，而是「外层 `.cd` 三条渐变 + `::before/::after` 两个大圆盘 +
 * 内层四处高光/模糊盘」叠出来的（8 层）。CSS 的 `radial-gradient(circle, …)` 默认按
 * farthest-corner 取半径，和 Canvas `RadialGradient` 的口径不同 —— 逐层近似实测左上
 * 青绿区最大差 34/255，肉眼能看出「左上发白」。所以背景按画板原样渲染成 960×540 PNG
 * （`assets/splash/splash_day.png` / `splash_night.png`，共 320KB），运行时等比铺满；
 * 圆环/文字/进度条仍是代码绘制（要转、要扫、要淡）。
 *
 * 日夜只问一次 [NightResolver]（进 `onDraw` 之前就定好），不读车控、不联网、没有百分比。
 */
class LaunchSplashView(context: Context) : View(context) {

    /**
     * 当前是不是夜间版。
     *
     * 在构造时问一次 [NightResolver] —— 与桌面首帧用的**同一个解析器**，
     * 所以不会出现「启动页夜间、桌面日间」的劈叉。
     * 读不到就退回 `lastAppliedNightUi`（与桌面兜底一致：从没应用过按夜间）。
     */
    val night: Boolean = runCatching {
        val prefs = Prefs(context)
        val started = System.nanoTime()
        val resolved = NightResolver.resolve(prefs, context)
        val costMs = (System.nanoTime() - started) / 1_000_000
        Logx.i(LogTags.APP, "启动页配色", "night" to resolved, "resolveMs" to costMs)
        resolved
    }.getOrElse {
        runCatching { Prefs(context).lastAppliedNight() }.getOrNull() ?: true
    }

    /** 画板 `b01`/`b02` 的背景层（960×540，等比铺满）。缺资产时回落纯色。 */
    private val backdrop: Bitmap? = runCatching {
        val name = if (night) "splash/splash_night.png" else "splash/splash_day.png"
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = RING_STROKE
    }
    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = RING_STROKE * 2.6f
        alpha = ARC_GLOW_ALPHA
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE
        color = if (night) NIGHT_TRACK else DAY_TRACK
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) NIGHT_DOT else DAY_DOT
    }
    private val wordmarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = WORDMARK_SP
        letterSpacing = WORDMARK_TRACKING
        color = if (night) NIGHT_WORDMARK else DAY_WORDMARK
        isFakeBoldText = true
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = SUBTITLE_SP
        letterSpacing = SUBTITLE_TRACKING
        color = if (night) NIGHT_SUBTITLE else DAY_SUBTITLE
    }
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) NIGHT_BAR_TRACK else DAY_BAR_TRACK
    }
    private val barSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 弧的转角（度）。0 → 360，1400ms 转一圈，线性无限循环（画板节奏）。 */
    private var spinDeg = 0f

    /** 进度条光带进度（0..1）。1400ms 一轮，从左侧外扫到右侧外。 */
    private var barPhase = 0f

    /**
     * 从系统 starting window 的深色过渡到本页的淡入进度（0..1）。
     *
     * **不能用 ValueAnimator**：桌面构造把主线程占满时动画一帧都不走，淡入会停在 0 ——
     * 白天启动时用户看到的就是「深色启动页」（实测第一版就是这样）。
     * 改成按**墙钟**算，`onDraw` 每帧自己推进。
     */
    private var revealStartAt = 0L
    private var reveal = 0f

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            spinDeg = it.animatedValue as Float
            invalidate()
        }
    }

    private val barAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = BAR_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            barPhase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!spinner.isStarted) spinner.start()
        if (!barAnimator.isStarted) barAnimator.start()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        barAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val s = h / DESIGN_H
        drawBackdrop(canvas, w, h)

        // 垂直居中一列：环 168 → 34 → 品牌字 → 16 → 文案 → 26 → 进度条。
        val wordmarkH = wordmarkPaint.fontMetrics.let { it.descent - it.ascent }
        val subtitleH = subtitlePaint.fontMetrics.let { it.descent - it.ascent }
        val columnH = RING_BOX + GAP_RING_TEXT + wordmarkH + GAP_TEXT_SUB + subtitleH +
            GAP_SUB_BAR + BAR_H
        var top = (h - columnH) / 2f

        val ringCx = w / 2f
        val ringCy = top + RING_BOX / 2f
        drawRing(canvas, ringCx, ringCy, s)

        top += RING_BOX + GAP_RING_TEXT
        val wordmarkY = top - wordmarkPaint.fontMetrics.ascent
        drawTrackingText(canvas, "CarDesk", wordmarkPaint, w / 2f, wordmarkY, WORDMARK_TRACKING)
        top += wordmarkH + GAP_TEXT_SUB

        val subtitleY = top - subtitlePaint.fontMetrics.ascent
        drawTrackingText(
            canvas, "正在启动桌面…", subtitlePaint, w / 2f, subtitleY, SUBTITLE_TRACKING,
        )
        top += subtitleH + GAP_SUB_BAR

        drawBar(canvas, w / 2f, top, s)

        // ---- 从系统 starting window 的深色淡入（夜里几乎看不出，白天是柔和的过渡）----
        if (revealStartAt == 0L) revealStartAt = android.os.SystemClock.uptimeMillis()
        reveal = ((android.os.SystemClock.uptimeMillis() - revealStartAt).toFloat() / REVEAL_MS)
            .coerceIn(0f, 1f)
        if (reveal < 1f) {
            invalidate()
            backgroundPaint.shader = null
            backgroundPaint.color = STARTING_WINDOW
            backgroundPaint.alpha = ((1f - reveal) * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, backgroundPaint)
            backgroundPaint.alpha = 255
        }
    }

    /** 背景：画板烘焙底图等比铺满（`b01` 日 / `b02` 夜）。 */
    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        val bmp = backdrop
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), backgroundPaint)
            return
        }
        // 资产缺失时的兜底：纯色底（至少不会闪白）。
        backgroundPaint.shader = null
        backgroundPaint.alpha = 255
        backgroundPaint.color = backdropColor(night)
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)
    }

    /** 圆环：底色轨道 + 120° 渐变弧（带外发光）+ 圆心点。 */
    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val r = RING_R * s
        val stroke = RING_STROKE * s
        trackPaint.color = if (night) NIGHT_TRACK else DAY_TRACK
        trackPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r, trackPaint)

        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val from = if (night) NIGHT_ARC_FROM else DAY_ARC_FROM
        val to = if (night) NIGHT_ARC_TO else DAY_ARC_TO
        val start = -90f + spinDeg
        arcPaint.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            from, to, Shader.TileMode.CLAMP,
        )
        arcPaint.strokeWidth = stroke
        arcGlowPaint.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            from, to, Shader.TileMode.CLAMP,
        )
        arcGlowPaint.strokeWidth = stroke * 2.6f
        canvas.drawArc(box, start, ARC_SWEEP, false, arcGlowPaint)
        canvas.drawArc(box, start, ARC_SWEEP, false, arcPaint)

        dotPaint.color = if (night) NIGHT_DOT else DAY_DOT
        canvas.drawCircle(cx, cy, DOT_R * s, dotPaint)
    }

    /**
     * 居中绘制带字距的文本。
     *
     * Canvas 的 `letterSpacing` 在**最后一个字后面**也留一份字距，直接按测量宽度居中
     * 会整体偏左；画板用 `padding-left: .20em` 抵消，这里等价于右移半个字距。
     */
    private fun drawTrackingText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        cx: Float,
        baseline: Float,
        tracking: Float,
    ) {
        val measured = paint.measureText(text)
        val trailing = tracking * paint.textSize
        canvas.drawText(text, cx - (measured - trailing) / 2f, baseline, paint)
    }

    /** 不确定式进度条：320×3 轨道 + 120 宽光带左右扫（不显示百分比）。 */
    private fun drawBar(canvas: Canvas, cx: Float, top: Float, s: Float) {
        val barW = BAR_W * s
        val barH = BAR_H * s
        val radius = barH / 2f
        val left = cx - barW / 2f
        barTrackPaint.color = if (night) NIGHT_BAR_TRACK else DAY_BAR_TRACK
        canvas.drawRoundRect(RectF(left, top, left + barW, top + barH), radius, radius, barTrackPaint)

        val segW = BAR_SEG_W * s
        val seqLeft = left - segW + (barW + segW * 2f) * barPhase
        val accent = accent()
        val alpha = (if (night) BAR_SWEEP_ALPHA_NIGHT else BAR_SWEEP_ALPHA_DAY)
        val clear = accent and 0x00FFFFFF
        barSweepPaint.shader = LinearGradient(
            seqLeft, top, seqLeft + segW, top,
            intArrayOf(clear, (accent and 0x00FFFFFF) or (alpha shl 24), clear),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.clipRect(left, top, left + barW, top + barH)
        canvas.drawRoundRect(
            RectF(seqLeft, top, seqLeft + segW, top + barH), radius, radius, barSweepPaint,
        )
        canvas.restore()
        barSweepPaint.shader = null
    }

    /**
     * 进度条光带用的**强调色**。
     *
     * 注意别用圆环那套渐变 —— 画板里光带是 `var(--accent)`（日 #00A9A0 / 夜 #16C2B2），
     * 弧才是青→蓝渐变。第 256 轮验收就是在这里取了 `ARC_TO`，日间峰值偏成 (119,205,244)。
     */
    private fun accent(): Int = if (night) NIGHT_BAR_SWEEP else DAY_BAR_SWEEP

    companion object {
        const val DESIGN_W = 1920f
        const val DESIGN_H = 1080f

        const val RING_BOX = 168f
        const val RING_R = 66f
        const val RING_STROKE = 6f
        const val ARC_SWEEP = 120f
        const val DOT_R = 7f

        const val WORDMARK_SP = 62f
        const val WORDMARK_TRACKING = 0.20f
        const val SUBTITLE_SP = 22f
        const val SUBTITLE_TRACKING = 0.06f

        const val BAR_W = 320f
        const val BAR_H = 3f
        const val BAR_SEG_W = 120f

        const val GAP_RING_TEXT = 34f
        const val GAP_TEXT_SUB = 16f
        const val GAP_SUB_BAR = 26f

        const val SPIN_MS = 1400L
        const val BAR_MS = 1400L
        const val REVEAL_MS = 200f

        /** 系统 starting window 的深色底（`AppTheme.windowBackground` 的第一段）。 */
        const val STARTING_WINDOW = 0xFF05080F.toInt()

        const val ARC_GLOW_ALPHA = 70

        // 日间（画板 b01）
        const val DAY_ARC_FROM = 0xFF00A9A0.toInt()
        const val DAY_ARC_TO = 0xFF4FC3F7.toInt()
        const val DAY_WORDMARK = 0xFF12303A.toInt()
        const val DAY_SUBTITLE = 0x94183440.toInt() // rgba(24,52,64,.58)
        const val DAY_TRACK = 0x2900968E.toInt() // rgba(0,150,142,.16)
        const val DAY_DOT = 0x5700968E.toInt() // rgba(0,150,142,.34)
        const val DAY_BAR_TRACK = 0x1AFFFFFF

        /** 光带峰值色（画板 `var(--accent)`），透明度由 [BAR_SWEEP_ALPHA_DAY]/[BAR_SWEEP_ALPHA_NIGHT] 叠。 */
        const val DAY_BAR_SWEEP = 0xFF00A9A0.toInt()
        const val NIGHT_BAR_SWEEP = 0xFF16C2B2.toInt()

        // 夜间（画板 b02）
        const val NIGHT_ARC_FROM = 0xFF4FC3F7.toInt()
        const val NIGHT_ARC_TO = 0xFF7BE0D6.toInt()
        const val NIGHT_WORDMARK = 0xFFE9F1F8.toInt()
        const val NIGHT_SUBTITLE = 0xFF6F8394.toInt()
        const val NIGHT_TRACK = 0x244FC3F7.toInt() // rgba(79,195,247,.14)
        const val NIGHT_DOT = 0x734FC3F7.toInt() // rgba(79,195,247,.45)
        const val NIGHT_BAR_TRACK = 0x1AFFFFFF

        const val BAR_SWEEP_ALPHA_DAY = 179 // 0.70
        const val BAR_SWEEP_ALPHA_NIGHT = 217 // 0.85

        /** 根容器（`MainActivity` 的 splash 容器）用的底色：与底图同基调，避免淡出时露蓝边。 */
        fun backdropColor(night: Boolean): Int = if (night) 0xFF05080F.toInt() else 0xFFE3EEF2.toInt()

        /** 画板上的进度条轨道色（测试用）。 */
        fun barTrackColor(night: Boolean): Int = if (night) NIGHT_BAR_TRACK else DAY_BAR_TRACK

        /** 光带峰值色（画板 `var(--accent)`；不是弧的渐变端色）。 */
        fun barSweepColor(night: Boolean): Int = if (night) NIGHT_BAR_SWEEP else DAY_BAR_SWEEP

        /** 画板上的圆心点色（测试用）。 */
        fun dotColor(night: Boolean): Int = if (night) NIGHT_DOT else DAY_DOT

        /** 给单测用的强调色。 */
        fun arcColors(night: Boolean): Pair<Int, Int> =
            if (night) NIGHT_ARC_FROM to NIGHT_ARC_TO else DAY_ARC_FROM to DAY_ARC_TO

        /** `Color` 引用保留给调用方做 alpha 组合。 */
        fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    }
}
