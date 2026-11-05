package com.cardesk.launcher.ui

/**
 * 覆盖页（设置 / 抽屉 / 空调）开关动画的**数值规格**（纯 Kotlin，可 JVM 单测）。
 *
 * 这里只放数字，不碰 Android —— `ui/OverlayAnim.kt` 负责把它们喂给
 * `ObjectAnimator` /。
 *
 * ##口径
 *
 * 打开（一族）
 *
 * `java
 * panel.setTranslationY(56.0f); // 准备位
 * scrim.setAlpha(0.0f);
 * scrim.animate().alpha(1.0f).setDuration(180L).start();
 * panel.animate().translationY(0.0f).setDuration(200L)
 *        setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f)).start();
 * `
 *
 * 关闭（一族）
 *
 * `java
 * scrim.animate().alpha(0.0f).setDuration(200L).start();
 * ObjectAnimator slide = ObjectAnimator.ofFloat(panel, View.TRANSLATION_Y, …, 56.0f);
 * slide.setDuration(240L);
 * slide.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f));
 * ObjectAnimator fade = ObjectAnimator.ofFloat(panel, View.ALPHA, …, 0.0f);
 * fade.setDuration(200L);
 * animatorSet.playTogether(slide, fade);
 * `
 *
 * 也就是**关比开慢一点**（下滑 240ms vs 上滑 200ms），关的时候面板往下掉 56px 并淡出。
 */
object OverlaySpec {

    /** 面板收起时的位移（设计坐标 px，`setTranslationY(56.0f)`）。 */
    const val SLIDE_PX = 56f

    /** 打开：面板上滑时长（ms，`setDuration(200L)`）。 */
    const val OPEN_DURATION = 200L

    /** 打开：遮罩淡入时长（ms，`setDuration(180L)`）。 */
    const val OPEN_SCRIM_DURATION = 180L

    /** 关闭：面板下滑时长（ms，`slide.setDuration(240L)`）。 */
    const val CLOSE_DURATION = 240L

    /** 关闭：面板淡出 + 遮罩淡出时长（ms，`fade.setDuration(200L)`）。 */
    const val CLOSE_FADE_DURATION = 200L

    /** 插值器控制点（`PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f)`）。 */
    const val INTERPOLATOR_X1 = 0.2f
    const val INTERPOLATOR_Y1 = 0.8f
    const val INTERPOLATOR_X2 = 0.2f
    const val INTERPOLATOR_Y2 = 1f

    // ---------------------------------------------------------------
    // 第 124 轮：背景板「透出桌面」
    // ---------------------------------------------------------------

    /**
     * 透出桌面时，`.bgwash` 底色保留的 **alpha**（`0x4D` ≈ 30%）。
     *
     * 原来 `.bgwash` 是**不透明**纯色底（`0xFF…`），桌面被完全盖死。
     * 透出模式下底色改成半透明，保留顶部那道椭圆径向渐变的质感，
     * 但不再把下面的桌面挡得一丝不露。
     */
    const val THROUGH_BG_ALPHA = 0x4D

    /**
     * 透出桌面时，`.scrim` 遮罩保留的**比例**（原来的一半）。
     *
     * `.scrim` 的作用是「压暗浮层自己的背景」。
     * 背景板变透明之后它就变成「压暗桌面」了 —— 不减弱的话，
     * 桌面透上来了也还是被压得死黑，等于没透。
     */
    const val THROUGH_SCRIM_SCALE = 0.5f

    /**
     * 两层半透明叠加之后，下面的桌面还能透出**多少**（0..1）。
     *
     * 先过 `.bgwash`（保留 [bgAlpha]/255），再过 `.scrim`（保留 [scrimAlpha]/255）：
     * 桌面剩下的比例 = `(1 - bg) × (1 - scrim)`。
     *
     * 纯 Kotlin（不碰 `android.graphics`），保证能 JVM 单测。
     */
    fun throughRate(bgAlpha: Int, scrimAlpha: Int): Float {
        val bg = bgAlpha.coerceIn(0, 255) / 255f
        val scrim = scrimAlpha.coerceIn(0, 255) / 255f
        return (1f - bg) * (1f - scrim)
    }
}
