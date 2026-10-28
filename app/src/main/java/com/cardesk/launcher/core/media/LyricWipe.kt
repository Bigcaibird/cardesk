package com.cardesk.launcher.core.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 歌词**擦除高亮**。
 *
 * ##在做什么
 *
 * 当前行歌词不是纯色，而是「**已经唱到的部分用强调色，后面还没唱到的部分用暗色**」
 * 进度随播放位置往前推（卡拉 OK 式擦除）。
 *
 * - 比例
 *   `(播放位置 − 当前行时间戳) / (下一行时间戳 − 当前行时间戳)`，钳到 `[0,1]`；
 *   我们在 `LyricTimeline.wipeFraction()` 里已经算好了。
 * - 动画：帧回调
 *   `display += (target - display) * (1 - exp(-dt * 18))`（指数逼近，时间常数 ≈ 55ms）。
 * - 跳变规则
 *   **往前**（target ≥ display + 0.02）走动画；**往回**（切歌、跳行）直接跳，不做倒带动画。
 * - 擦除边缘
 *   边缘宽度 `max(6px, 8dp)`，其中「已唱」的纯色段到 `擦除位置 − 边缘 × 0.35`，
 *   剩下的边缘带用 55% 透明度的强调色画，形成软过渡。
 *
 * 这里只放**纯逻辑**（不碰 Android），绘制在 `ui/widget/LyricWipeText.kt`。
 */
object LyricWipe {

    /**
     * 目标跳变阈值（`0.02f`）。
     *
     * 目标比当前显示值高出这个数以上才走动画，否则直接跳过去。
     */
    const val JUMP_THRESHOLD = 0.02f

    /** 指数逼近系数（`Math.exp(dt * -18.0)`）。 */
    const val RATE = 18f

    /** 擦除边缘宽度（dp，`density * 8.0f`）。 */
    const val EDGE_DP = 8f

    /** 擦除边缘宽度下限（px，`Math.max(6.0f, …)`）。 */
    const val EDGE_MIN_PX = 6f

    /** 边缘带里强调色的透明度比例（`activeColor >>> 24 & 0xff) * 0.55f`）。 */
    const val EDGE_ALPHA_RATIO = 0.55f

    /**
     * 擦除边缘宽度（px）。
     *
     * @param density 屏幕密度（1dp = density px）
     */
    fun edgeWidth(density: Float): Float = max(EDGE_MIN_PX, EDGE_DP * density)

    /**
     * 指数逼近一帧（帧回调里的算式）。
     *
     * @param display 当前显示值
     * @param target 目标值
     * @param dtMs 距上一帧的毫秒数
     */
    fun smoothStep(display: Float, target: Float, dtMs: Long): Float {
        val dt = if (dtMs > 0) min(0.05f, dtMs / 1000f) else 0.016f
        val next = (target - display) * (1f - exp(-RATE * dt)) + display
        // `Math.abs(target - next) < 4.0E-4f` 时直接吸附到目标。
        return if (abs(target - next) < 4e-4f) target else next
    }
}

/**
 * 歌词擦除的**显示状态机**（每个画歌词的控件各持一份）。
 *
 * 目标值来自播放进度（[LyricTimeline.wipeFraction]），
 * 显示值用指数逼近追上去（[LyricWipe.smoothStep]）。
 */
class LyricWipeState {

    /** 目标比例 0..1。 */
    var target: Float = 0f
        private set

    /** 当前显示比例 0..1。 */
    var display: Float = 0f
        private set

    private var lastFrameMs: Long = 0L

    /**
     * 设置目标比例。
     *
     * 往前（变大）走动画，往回（变小 / 切歌）直接跳。
     */
    fun setTarget(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (abs(v - target) < 2e-4f) return
        target = v
        if (v + LyricWipe.JUMP_THRESHOLD >= display) {
            // 往前：交给动画追。
            return
        }
        // 往回：直接跳，不做倒带。
        display = v
        lastFrameMs = 0L
    }

    /**
     * 推进一帧。
     *
     * @param nowMs 当前时间戳（毫秒）
     * @return true = 还在动（调用方需要继续请求下一帧）
     */
    fun advance(nowMs: Long): Boolean {
        val next = LyricWipe.smoothStep(display, target, nowMs - lastFrameMs)
        lastFrameMs = nowMs
        display = next
        return abs(target - display) >= 4e-4f
    }

    /**
     * **直接跳到**指定比例（不做动画）。
     *
     * 用途：进度被外部直接设定（跳歌 / seek / 渲染夹具）时，
     * 不希望再走一次「从旧值追到新值」的指数逼近 —— 直接落位。
     */
    fun jumpTo(value: Float) {
        val v = value.coerceIn(0f, 1f)
        target = v
        display = v
        lastFrameMs = 0L
    }

    /** 清零（切歌 / 没有歌词时）。 */
    fun reset() {
        target = 0f
        display = 0f
        lastFrameMs = 0L
    }
}
