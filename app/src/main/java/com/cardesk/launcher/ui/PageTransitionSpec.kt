package com.cardesk.launcher.ui

/**
 * 页面切换动画的**数值规格**（纯 Kotlin，可 JVM 单测）。
 *
 * 这里只放数字，不碰 Android —— `ui/LauncherView.slidePage()` 负责把它们
 * 喂给 `ObjectAnimator`。
 *
 * ## 第191轮：竖滑改横滑，600ms 改 260ms
 *
 * 之前那套是「竖向滑 ±60px + 交叉淡入 + 缩放 0.98」，600 / 700ms。
 *
 * 两个问题：
 *
 * 1. **方向对不上手势**。桌面的主翻页手势是**单指横滑**
 *    （见 [com.cardesk.launcher.core.PageGesture]），
 *    动画却往上下动 —— 手指往左划，页面往上跑，
 *    视觉和触觉互相打架，这就是「切换太僵硬」的主因。
 * 2. **太慢**。600~700ms 在车机 HMI 上属于「慢半拍」，
 *    用户会先看到「点了没反应」，然后才看到页面在挪。
 *
 * 现在：
 *
 * - **横滑**，方向跟着页号走（往下一页 = 新页从右边进）；
 * - **260ms**，车机级的干脆利落；
 * - **强减速曲线**（起步快、收尾稳），落位时不会「飘」；
 * - 位移只走页宽的一小段（不是整屏横移），配合交叉淡入和轻微缩放做纵深 ——
 *   整屏横移在 1920 宽的车机屏上太重，会像「换了个应用」而不是「翻了一页」。
 */
object PageTransitionSpec {

    /**
     * 一次切换的总时长（毫秒）。
     *
     * 260ms：车机 HMI 的常规节奏 ——
     * 低于 200ms 会觉得「跳」，高于 320ms 会觉得「粘」。
     */
    const val DURATION = 260L

    /**
     * 位移占**页宽**的比例（0..1）。
     *
     * 不是整屏横移 —— 1920px 整屏横移在车机上太重；
     * 22% 足以表达「这一页从旁边进来」的方向感，
     * 又不会把画面甩得太远。
     */
    const val TRAVEL_RATIO = 0.22f

    /**
     * 新页 / 旧页的缩放差（纵深）。
     *
     * 进入页从 [ENTER_SCALE] 放大到 1，离开页从 1 缩到 [EXIT_SCALE] ——
     * 一点点透视，配合交叉淡入就有「前后两层」的感觉。
     */
    const val ENTER_SCALE = 0.96f
    const val EXIT_SCALE = 0.96f

    /**
     * 交叉淡入的时长（毫秒）。
     *
     * 比位移**短一点**：新页要尽快「有实体感」，
     * 旧页可以慢一点退场 —— 两页不是对称地淡入淡出。
     */
    const val FADE_DURATION = 200L

    /**
     * 插值器控制点：`PathInterpolator(0.15f, 0.9f, 0.2f, 1.0f)`。
     *
     * **强减速**：起步很冲（0.15 → 0.9，斜率陡），
     * 尾巴拉长慢慢落位 —— 页面「甩」出去再稳稳停住，
     * 而不是匀速平移。
     */
    const val INTERPOLATOR_X1 = 0.15f
    const val INTERPOLATOR_Y1 = 0.9f
    const val INTERPOLATOR_X2 = 0.2f
    const val INTERPOLATOR_Y2 = 1f

    /**
     * 一页的位移距离（像素）。
     *
     * @param pageWidth 页宽（设计坐标 px）
     */
    fun travelPx(pageWidth: Int): Float = pageWidth * TRAVEL_RATIO

    /**
     * 切页方向。
     *
     * @param from 旧页号
     * @param to 新页号
     * @return true = 往**后**翻（下一页），新页从右边进
     */
    fun forward(from: Int, to: Int): Boolean = to > from

    /**
     * 新页的起始位移（像素，正数 = 在右边）。
     *
     * 往下一页 → 新页从**右**边进（+travel）；
     * 往上一页 → 新页从**左**边进（-travel）。
     */
    fun enterTranslation(pageWidth: Int, forward: Boolean): Float {
        val travel = travelPx(pageWidth)
        return if (forward) travel else -travel
    }

    /**
     * 旧页的终位移（像素）。
     *
     * 往下一页 → 旧页往**左**退（-travel）；
     * 往上一页 → 旧页往**右**退（+travel）。
     *
     * 跟 [enterTranslation] **反号**：两页像一副牌被推开，
     * 新页从一边进、旧页往另一边退，方向感才一致。
     */
    fun exitTranslation(pageWidth: Int, forward: Boolean): Float {
        val travel = travelPx(pageWidth)
        return if (forward) -travel else travel
    }
}
