package com.cardesk.launcher.ui.widget

import android.view.View
import android.view.animation.Interpolator

/**
 * 歌词「落位」动画：歌词切到新的一行时，当前行从上方落回原位。
 *
 *
 * - → 起始位移 32dp（按 density 换算成 px）；
 * - → 动画时长 360ms；
 * - → 插值器 `1 - (1 - t)^3`；
 * - → 先 `animate().cancel()`，再从 32dp 落到 0。
 *
 * 调用点对齐 （换行时对当前行歌词播一次）。
 */
object LyricSettle {

    /** 起始位移 32dp。 */
    const val AMPLITUDE_DP = 32f

    /** 动画时长 360ms。 */
    const val DURATION_MS = 360L

    /**
     * `1 - (1 - t)^3`（cubic out remainder）。
     * 只依赖 `Interpolator` 接口，单元测试里构造它不会碰到 Android 运行时（懒加载）。
     */
    val CUBIC_OUT_REMAINDER: Interpolator by lazy {
        object : Interpolator {
            override fun getInterpolation(input: Float): Float = cubicOutRemainder(input)
        }
    }

    /** 纯函数版插值曲线（可单测）：`1 - (1 - t)^3`。 */
    fun cubicOutRemainder(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val rest = 1f - x
        return 1f - rest * rest * rest
    }

    /** 落位动画。 */
    fun play(view: View) {
        view.animate().cancel()
        view.translationY = view.resources.displayMetrics.density * AMPLITUDE_DP
        view.animate()
            .translationY(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(CUBIC_OUT_REMAINDER)
            .start()
    }
}
