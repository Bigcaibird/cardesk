package com.cardesk.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 冷启动占位页（第九十三轮）。
 *
 * ## 为什么要有
 *
 * 车机开机时用户看到的是「黑屏一段时间才出桌面」：
 * 系统 starting window 用的是 `AppTheme.windowBackground`（近黑色），
 * 而 `LauncherView` 的构造 + 首帧要等车控/天气/媒体这些初始化做完。
 * 这段时间**没有任何反馈**，观感就是「死机了」。
 *
 * 现在 `MainActivity` 第一件事就是 `setContentView` 一个轻量根容器 + 这个 View：
 * 它不读车控、不联网、不建页面，`onDraw` 只画一层渐变 + 品牌字 + 一个转圈，
 * **第一帧毫秒级就能出来**。真正的桌面在它背后构建，画好后再淡出它。
 *
 * ## 为什么不放位图
 *
 * 仓库里没有 logo 位图（`res/drawable` 只有 `ic_cardesk.xml`），
 * 启动页又要求极快 —— 加载解码位图反而增加首帧时间。
 * 所以全部用 Canvas 画：圆环 + 品牌字 + 一句「正在启动桌面」，够体面也不拖慢。
 *
 * ## 动画
 *
 * 只转一个圆弧（`ValueAnimator` 0→360，线性、无限循环）。
 * 转圈同时是「我没死」的信号。窗口不可见时会自动停（见 [onDetachedFromWindow]），
 * 不会在后台白烧 CPU。
 */
class LaunchSplashView(context: Context) : View(context) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE9F1F8.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6F8394.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x334FC3F7
    }

    /** 转圈角度（0..360）。 */
    private var spin = 0f

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            spin = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // 纯装饰：不抢焦点、不吃点击（桌面在它背后已经就绪，留着它反而挡事）。
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!spinner.isStarted) spinner.start()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 背景：与桌面同色系（#05080F）的纵向渐变，比纯黑多一点层次。
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF071320.toInt(), 0xFF05080F.toInt(), 0xFF03060A.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val cx = w / 2f
        val cy = h / 2f

        // 品牌标记：一圈暗环 + 一段亮弧（转圈）。尺寸按屏幕高度换算，车机横屏下比例稳定。
        val ringRadius = h * 0.075f
        val ringCenterY = cy - h * 0.045f

        ringPaint.strokeWidth = h * 0.005f
        ringPaint.color = 0x1A4FC3F7
        canvas.drawCircle(cx, ringCenterY, ringRadius, ringPaint)

        ringPaint.color = 0xFF4FC3F7.toInt()
        canvas.drawArc(
            cx - ringRadius,
            ringCenterY - ringRadius,
            cx + ringRadius,
            ringCenterY + ringRadius,
            spin - 90f,
            120f,
            false,
            ringPaint,
        )

        // 环心的一个小圆点：不转，作为视觉锚点。
        canvas.drawCircle(cx, ringCenterY, h * 0.008f, dotPaint)

        // 品牌字。
        titlePaint.textSize = h * 0.052f
        canvas.drawText("CarDesk", cx, cy + h * 0.075f, titlePaint)

        // 状态文案：明确告诉用户「在启动，不是死机」。
        subtitlePaint.textSize = h * 0.022f
        canvas.drawText("正在启动桌面…", cx, cy + h * 0.125f, subtitlePaint)
    }
}
