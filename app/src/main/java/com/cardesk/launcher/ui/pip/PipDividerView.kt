package com.cardesk.launcher.ui.pip

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
                if (next != pendingRatio) {
                    pendingRatio = next
                    // 排障场景：「分隔条拖不动」——拖动中每帧的比例用 DEBUG，不落盘。
                    Logx.d(
                        LogTags.NAV_UI,
                        "画中画分隔条拖动比例",
                        "from" to startRatio,
                        "to" to next,
                        "dx" to dx,
                    )
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
        /**
         * 判定「移动过」的位移阈值（屏幕 px）。
         * `Math.hypot(...) > 24.0d`。
         */
        const val MOVE_SLOP_PX = 24f
    }
}
