package com.cardesk.launcher.ui.pip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import kotlin.math.hypot
import kotlin.math.max

/**
 * 画中画分隔条（`pipDivider` 玻璃 chip）。
 *
 * 把「单击主画全屏」和「长按拖分屏比例」放在**同一个**分隔条上，本组件保持一致的分工
 *
 * - 触摸状态机：
 * - 长按触发（进入拖比例）：
 * - 每帧刷新（`postOnAnimation` 合并）：
 * - 比例换算：
 * - 松手落盘：
 *
 * ## 分工
 *
 * - **单击**（未触发长按、且没有明显移动）→ [onSingleTap]：主画全屏 / 还原；
 * - **长按后横向拖动** → [onRatioChanged]（拖动中实时，每帧一次）+ [onRatioCommitted]（松手落盘）。
 *
 * 组件本身不认识「比例」的业务含义：宿主通过 [ratioProvider] / [totalDragWidth] /
 * [dualMode] / [sidesSwapped] 喂入手势所需参数，通过三个回调取走结果。
 */
class PipDividerView(context: Context) : View(context) {

    /** 单击：主画全屏 / 还原（`performClick`）。 */
    var onSingleTap: (() -> Unit)? = null

    /** 拖动过程中的实时比例（0..1）。 */
    var onRatioChanged: ((Float) -> Unit)? = null

    /** 拖动结束、比例已确定（落盘 + 刷新布局）。 */
    var onRatioCommitted: ((Float) -> Unit)? = null

    /**
     * 拖动起始比例的取值源（DOWN 时读一次）。
     */
    var ratioProvider: () -> Float = { 0f }

    /**
     * 可拖动总宽度（设计 px）：地图卡宽度 + 右槽宽度
     *
     */
    var totalDragWidth: Float = 0f

    /** 手势开始时是否双画模式。 */
    var dualMode: Boolean = false

    /** 手势开始时左右是否互换。 */
    var sidesSwapped: Boolean = false

    /** 手势是否可用（画中画全屏时关闭）。 */
    var gestureEnabled: Boolean = true

    // ------------------------------------------------------------------ 手势状态

    private var downRawX = 0f
    private var downRawY = 0f

    /** 是否已经移动过（超过 [MOVE_SLOP_PX]）。 */
    private var moved = false

    /** 长按是否已触发（进入拖比例）。 */
    private var dragging = false

    /** 待落盘的比例；NaN = 没有。 */
    private var pendingRatio = Float.NaN

    /** 手势起始比例。 */
    private var startRatio = 0f

    /** 手势起始时是否双画。 */
    private var gestureDual = false

    /** 手势起始时左右是否互换。 */
    private var gestureSwapped = false

    /** 手势起始时的可拖动总宽度。 */
    private var gestureWidth = 0f

    private var longPress: Runnable? = null

    /** 是否已经 post 过帧回调（避免每帧重复 post）。 */
    private var framePosted = false

    /** 上一次记「拖动比例」的时刻（节流用，第228轮）。 */
    private var lastDragLogAt = 0L

    // ------------------------------------------------------------------ 外观（自绘）

    /** 玻璃胶囊填充。 */
    private val capsuleFill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 玻璃胶囊描边（1px）。 */
    private val capsuleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_PX
    }

    /** 4 个 grip 与下方 ⋮ 的填充。 */
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 当前主题的调色板（只读）。
     *
     * 本组件只拿 [Context] 构造，拿不到宿主传进来的 `Palette`，所以按 [Skin.isNight]
     * 取 [Palettes] 里那一套 —— 透明度统一走 `pal.withAlpha(...)`，
     * 不新增任何颜色字面量。
     */
    private val pal get() = if (Skin.isNight) Palettes.night else Palettes.day

    /** 帧回调：把待落盘比例推给宿主刷新布局。 */
    private val frameRunnable = Runnable {
        framePosted = false
        if (!dragging || !gestureEnabled) return@Runnable
        val ratio = pendingRatio
        if (!ratio.isNaN()) onRatioChanged?.invoke(ratio)
    }

    init {
        isClickable = true
        contentDescription = "单击主画全屏，长按左右拖动调整分屏"
    }

    // ------------------------------------------------------------------ 外观

    /**
     * 忽略宿主设的旧底板（`NavPage.buildPipDivider` 里的 `Skin.flat(6f, true)`）。
     *
     * 旧版把手就是那块 12×144 的白胶囊；现在把手由 [onDraw] 自绘成
     * 18×120 的玻璃胶囊 + 4 个 grip + ⋮，再叠一层旧底板就会把自绘内容糊成白板。
     *
     * 这里**只吞掉** `setBackground`，不碰 `backgroundTintList` / 前景，
     * 触摸状态机与回调完全不受影响。
     */
    override fun setBackground(background: Drawable?) {
        super.setBackground(null)
        invalidate()
    }

    /**
     * 宽度自撑到 [HANDLE_W]（18），高度沿用宿主给的值（`LayoutParams(12, 144)`）。
     *
     * 宿主按 `pipDivider.width` 把把手横向居中在 gutter 里（`(edge - width/2)`），
     * 所以这里改宽度不会破坏定位，只是让把手从 12 变成设计稿的 18。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> HANDLE_H
        }
        setMeasuredDimension(HANDLE_W, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val capsuleW = minOf(CAPSULE_W, w)
        val capsuleH = minOf(CAPSULE_H, h)
        val capsuleLeft = cx - capsuleW / 2f
        val capsuleTop = 0f
        // 胶囊圆角：Shape.PILL 会被裁到半宽，视觉上就是完全圆头（与设计稿一致）。
        val radius = minOf(Shape.PILL, capsuleW / 2f)

        // ---- 玻璃胶囊：TEXT 低透明度填充 + GLASS_STROKE 1px 描边 ----
        // 只用 Skin 的墨色 + 透明度（与 `pal.withAlpha(Skin.TEXT, 0x14)` 同一口径），
        // 不引入新的颜色字面量：日间是浅灰、夜间是浅雾，两边都看得见。
        capsuleFill.color = pal.withAlpha(Skin.TEXT, CAPSULE_ALPHA)
        canvas.drawRoundRect(
            capsuleLeft,
            capsuleTop,
            capsuleLeft + capsuleW,
            capsuleTop + capsuleH,
            radius,
            radius,
            capsuleFill,
        )
        capsuleStroke.color = Skin.GLASS_STROKE
        canvas.drawRoundRect(
            capsuleLeft,
            capsuleTop,
            capsuleLeft + capsuleW,
            capsuleTop + capsuleH,
            radius,
            radius,
            capsuleStroke,
        )

        // ---- 4 个 grip（两列两行），在胶囊中央 ----
        gripPaint.color = Skin.TEXT_DIM
        val gripCy = capsuleTop + capsuleH / 2f
        for (row in 0..1) {
            for (col in 0..1) {
                canvas.drawCircle(
                    cx + if (col == 0) -GRIP_DX else GRIP_DX,
                    gripCy + if (row == 0) -GRIP_DY else GRIP_DY,
                    GRIP_R,
                    gripPaint,
                )
            }
        }

        // ---- 胶囊下方 ⋮（竖排三枚圆点）----
        val dotsCy = capsuleTop + capsuleH + DOTS_START
        for (i in -1..1) {
            canvas.drawCircle(cx, dotsCy + i * DOT_GAP, DOT_R, gripPaint)
        }
    }

    // ------------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // —— DOWN——
        if (action == MotionEvent.ACTION_DOWN) {
            isPressed = true
            dragging = false
            pendingRatio = Float.NaN
            removeCallbacks(frameRunnable)
            framePosted = false
            downRawX = event.rawX
            downRawY = event.rawY
            moved = false
            val press = Runnable { fireLongPress() }
            longPress = press
            postDelayed(press, ViewConfiguration.getLongPressTimeout().toLong())
            startRatio = ratioProvider()
            gestureDual = dualMode
            gestureSwapped = sidesSwapped
            gestureWidth = totalDragWidth
            parent?.requestDisallowInterceptTouchEvent(true)
            // 排障场景：「分隔条按了没反应」——按下时把手势起始参数落盘，
            // 便于比对「起始比例 / 可拖动宽度」是否算错。
            Logx.d(
                LogTags.NAV_UI,
                "画中画分隔条按下",
                "startRatio" to startRatio,
                "dual" to gestureDual,
                "swapped" to gestureSwapped,
                "width" to gestureWidth,
                "enabled" to gestureEnabled,
            )
        }

        // —— MOVE：长按还没触发时，位移超阈值就当作「移动过」并取消长按——
        if (action == MotionEvent.ACTION_MOVE && !dragging &&
            hypot(event.rawX - downRawX, event.rawY - downRawY) > MOVE_SLOP_PX
        ) {
            moved = true
            longPress?.let { removeCallbacks(it) }
        }

        // —— MOVE：长按已触发 → 拖比例——
        if (action == MotionEvent.ACTION_MOVE && dragging) {
            if (gestureEnabled && gestureWidth > 0f) {
                // raw 位移是屏幕像素，先除以累计缩放换回设计 px
                val dx = (event.rawX - downRawX) / max(0.01f, cumulativeScale())
                val next = PipRatio.dragRatio(
                    startRatio = startRatio,
                    dxDesign = dx,
                    totalWidth = gestureWidth,
                    swapped = gestureSwapped,
                    dual = gestureDual,
                )
                // 第228轮：**按像素量化**（吸附到 12px 的格）。
                //
                // ## 为什么
                //
                // 拖分隔条时，卡片宽度每变一点，承载面就要跟着换尺寸 ——
                // 镜像路线下这会让承载应用**按新尺寸重新排版并重画整张地图**
                // （真车实测：每个中间尺寸都重画一次 ⇒ 拖动只有 ~10fps）。
                //
                // 量化成 12px 一格 ⇒ 一次 200px 的拖动只触发 **~16 次**重画
                // （而不是 200 次）⇒ 拖动跟手得多，而 12px 的跳动肉眼看不出来
                // （跟窗口吸附是一个道理）。
                val stepped = quantize(next)
                if (stepped != pendingRatio) {
                    pendingRatio = stepped
                    // 排障场景：「分隔条拖不动」——拖动中每帧的比例用 DEBUG，不落盘。
                    //
                    // 第228轮：**节流**。拖动时每帧一条 logcat，在车机上
                    // 就是拖动卡顿的一部分（真车实测：拖动只有 ~10fps）。
                    // 起始 / 结束的关键信息另有落盘日志，这里只留稀疏采样。
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastDragLogAt >= DRAG_LOG_THROTTLE_MS) {
                        lastDragLogAt = now
                        Logx.d(
                            LogTags.NAV_UI,
                            "画中画分隔条拖动比例",
                            "from" to startRatio,
                            "to" to next,
                            "dx" to dx,
                        )
                    }
                    if (!framePosted) {
                        framePosted = true
                        postOnAnimation(frameRunnable)
                    }
                }
            }
            return true
        }

        // —— UP / CANCEL——
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isPressed = false
            longPress?.let { removeCallbacks(it) }
            longPress = null
            val wasDragging = dragging
            dragging = false
            // 没长按、没明显移动 → 单击（主画全屏 / 还原）
            if (action == MotionEvent.ACTION_UP && !wasDragging && !moved && gestureEnabled) {
                performClick()
            }
            parent?.requestDisallowInterceptTouchEvent(false)
            removeCallbacks(frameRunnable)
            framePosted = false
            if (wasDragging) {
                if (action == MotionEvent.ACTION_UP && !pendingRatio.isNaN()) {
                    // 排障场景：「比例拖完没保存」——落盘前后的值都记一条。
                    Logx.event(
                        LogTags.NAV_UI,
                        "画中画分隔条拖动结束",
                        "startRatio" to startRatio,
                        "committed" to pendingRatio,
                        "dual" to gestureDual,
                    )
                    onRatioCommitted?.invoke(pendingRatio)
                } else {
                    // 排障场景：「长按拖了但没比例」——手势被打断要能看出来。
                    Logx.w(
                        LogTags.NAV_UI,
                        "画中画分隔条拖动结束但没有有效比例",
                        "action" to action,
                        "startRatio" to startRatio,
                    )
                }
                pendingRatio = Float.NaN
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        // 排障场景：「点分隔条画中画没全屏」——单击事件落盘。
        Logx.event(LogTags.NAV_UI, "画中画分隔条单击")
        onSingleTap?.invoke()
        return true
    }

    /**
     * 把比例量化到「卡片宽度变化 N 像素」一格（第228轮）。
     *
     * 卡片宽度 = `ratio × 可拖动总宽度`，所以比例的一格 = `N / 总宽度`。
     */
    private fun quantize(ratio: Float): Float =
        PipRatio.quantize(ratio, gestureWidth, RATIO_STEP_PX)

    /** 长按触发 → 进入拖比例。 */
    private fun fireLongPress() {
        if (moved || !gestureEnabled) {
            // 排障场景：「长按分隔条没进入拖比例」——被什么拦住了要写清楚。
            Logx.w(
                LogTags.NAV_UI,
                "画中画分隔条长按未触发拖比例",
                "moved" to moved,
                "enabled" to gestureEnabled,
            )
            return
        }
        dragging = true
        // 排障场景：「长按拖比例没反应」——起始比例落盘，便于比对。
        Logx.event(
            LogTags.NAV_UI,
            "画中画分隔条长按进入拖比例",
            "startRatio" to startRatio,
            "dual" to gestureDual,
            "width" to gestureWidth,
        )
        // `performHapticFeedback(0)`
        // 0 即 `HapticFeedbackConstants.LONG_PRESS`。
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * 本层及其祖先的累计缩放（桌面整层按 1920×1080 设计稿等比缩放）。
     * `DesignCanvas.cumulativeScale(pipChromeParent)`。
     */
    private fun cumulativeScale(): Float {
        var scale = 1f
        var v: View? = this
        while (v != null) {
            scale *= v.scaleX
            v = v.parent as? View
        }
        return if (scale > 0f) scale else 1f
    }

    companion object {
        /** 把手宽度（设计 px）：gutter 38 正中的玻璃胶囊，18 宽。 */
        const val HANDLE_W = 18

        /** 把手高度（设计 px）：宿主给的是 144（`LayoutParams(12, 144)`）。 */
        const val HANDLE_H = 144

        /** 玻璃胶囊宽度（= 把手宽度）。 */
        private const val CAPSULE_W = 18f

        /** 玻璃胶囊高度（约 120，设计稿量测 92～120）。 */
        private const val CAPSULE_H = 120f

        /** 胶囊描边宽度。 */
        private const val STROKE_PX = 1f

        /** 胶囊填充透明度（`pal.withAlpha(Skin.TEXT, 0x24)`，与设计稿的浅灰胶囊同档）。 */
        private const val CAPSULE_ALPHA = 0x24

        /** grip 两列到中心的横向距离。 */
        private const val GRIP_DX = 4.5f

        /** grip 两行到中心的纵向距离。 */
        private const val GRIP_DY = 6f

        /** grip 半径。 */
        private const val GRIP_R = 1.7f

        /** 胶囊底到 ⋮ 中心的距离。 */
        private const val DOTS_START = 6f

        /** ⋮ 圆点间距（圆心到圆心）。 */
        private const val DOT_GAP = 5f

        /** ⋮ 圆点半径。 */
        private const val DOT_R = 1.5f

        /**
         * 判定「移动过」的位移阈值（屏幕 px）。
         * `Math.hypot(...) > 24.0d`。
         */
        const val MOVE_SLOP_PX = 24f

        /**
         * 「拖动比例」这条逐帧日志的最小间隔（毫秒，第228轮）。
         *
         * 拖动时每帧写一条 logcat，在车机上就是拖动卡顿的一部分
         * （真车实测：镜像路线下拖动只有 ~10fps）。
         */
        const val DRAG_LOG_THROTTLE_MS = 400L

        /**
         * 拖动时比例的**吸附格**（卡片宽度的像素数，第228轮）。
         *
         * 12px：肉眼看不出跳动，但能把一次拖动触发的「承载应用重排版」次数
         * 降一个数量级（真车实测：镜像路线下拖动只有 ~10fps）。
         */
        const val RATIO_STEP_PX = 12f
    }
}
