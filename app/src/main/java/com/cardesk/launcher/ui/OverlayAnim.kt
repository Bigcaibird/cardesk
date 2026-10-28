package com.cardesk.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.PathInterpolator

/**
 * 覆盖页（设置 / 抽屉 / 空调）的**开关动画**。
 *
 * ##口径
 *
 * 打开（一族）：
 *
 * `java
 * scrim.animate().alpha(1.0f).setDuration(180L).start();
 * panel.animate().translationY(0.0f).setDuration(200L)
 *        setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f)).start();
 * `
 *
 * 关闭（一族）：
 *
 * `java
 * scrim.animate().alpha(0.0f).setDuration(200L).start();
 * ObjectAnimator slide = ObjectAnimator.ofFloat(panel, View.TRANSLATION_Y, panel.getTranslationY(), 56.0f);
 * slide.setDuration(240L);
 * slide.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f));
 * ObjectAnimator fade = ObjectAnimator.ofFloat(panel, View.ALPHA, panel.getAlpha(), 0.0f);
 * fade.setDuration(200L);
 * // 两者 playTogether；结束后 panel 复位（translationY=0 / alpha=1）并隐藏整层
 * `
 *
 * 也就是**关比开慢一点**（240ms vs 200ms），关的时候面板往下掉 56px 并淡出。
 *
 * 数值规格全在 [OverlaySpec]（纯 Kotlin，可 JVM 单测），这里只负责把动画跑起来。
 */
object OverlayAnim {

    /** 面板收起时的位移（设计 px，`setTranslationY(56.0f)`）。 */
    const val SLIDE_PX = OverlaySpec.SLIDE_PX

    /** 打开：面板上滑时长（ms）。 */
    const val OPEN_DURATION = OverlaySpec.OPEN_DURATION

    /** 打开：遮罩淡入时长（ms）。 */
    const val OPEN_SCRIM_DURATION = OverlaySpec.OPEN_SCRIM_DURATION

    /** 关闭：面板下滑时长（ms）。 */
    const val CLOSE_DURATION = OverlaySpec.CLOSE_DURATION

    /** 关闭：面板淡出 + 遮罩淡出时长（ms）。 */
    const val CLOSE_FADE_DURATION = OverlaySpec.CLOSE_FADE_DURATION

    /** `PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f)`。 */
    private val INTERPOLATOR = PathInterpolator(
        OverlaySpec.INTERPOLATOR_X1,
        OverlaySpec.INTERPOLATOR_Y1,
        OverlaySpec.INTERPOLATOR_X2,
        OverlaySpec.INTERPOLATOR_Y2,
    )

    /**
     * 打开动画：面板从下方 [SLIDE_PX] 处滑上来，遮罩淡入。
     *
     * @param panel 面板（玻璃卡）
     * @param scrim 遮罩（可空）
     */
    fun open(panel: View?, scrim: View?) {
        if (panel != null) {
            panel.animate().cancel()
            panel.translationY = SLIDE_PX
            panel.alpha = 1f
            panel.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(OPEN_DURATION)
                .setInterpolator(INTERPOLATOR)
                .start()
        }
        if (scrim != null) {
            scrim.animate().cancel()
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(OPEN_SCRIM_DURATION).start()
        }
    }

    /**
     * 关闭动画：面板下滑 [SLIDE_PX] 并淡出，遮罩淡出。
     *
     * 结束后回调 [onEnd]（一般是把这层从窗口里摘掉）。
     *
     * 两个动画 `playTogether`
     * 回调挂在 **set** 上，因此要等 240ms 的下滑和 200ms 的淡出都结束才摘层。
     */
    fun close(panel: View?, scrim: View?, onEnd: () -> Unit) {
        if (panel == null) {
            onEnd()
            return
        }
        panel.animate().cancel()
        scrim?.animate()?.cancel()
        val slide = ObjectAnimator.ofFloat(panel, View.TRANSLATION_Y, panel.translationY, SLIDE_PX).apply {
            duration = CLOSE_DURATION
            interpolator = INTERPOLATOR
        }
        val fade = ObjectAnimator.ofFloat(panel, View.ALPHA, panel.alpha, 0f).apply {
            duration = CLOSE_FADE_DURATION
            interpolator = INTERPOLATOR
        }
        scrim?.animate()?.alpha(0f)?.setDuration(CLOSE_FADE_DURATION)?.start()
        val set = AnimatorSet().apply {
            playTogether(slide, fade)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // 被取消（比如紧接着又打开）就不摘层、不复位。
                    if (cancelled) return
                    // 动画结束后把面板复位（translationY=0 / alpha=1）
                    // 这样下次打开是「从 56 处滑上来」而不是从残留位置滑。
                    panel.translationY = 0f
                    panel.alpha = 1f
                    onEnd()
                }
            })
        }
        set.start()
    }
}
