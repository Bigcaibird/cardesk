package com.cardesk.launcher.ui.pip

/**
 * 画中画「左右换位」的纯逻辑
 * `MainActivity.applyDesktopSideOrder`。
 *
 * ##换位语义
 *
 * **不是**交换两个画中画的包名，而是**布局层面左右调换**：
 * -：`desktopNavRow.setLayoutDirection(swapped ? 1: 0)`
 *   —— 整行改成 RTL，两个卡片（地图卡 / 组合卡）视觉上左右对调；
 * - 同一处还把三个子项各自 `setLayoutDirection(0)`（LTR）——
 *   **只换位置，不镜像卡片内部内容**；
 * -：外边距也跟着换（28 / 20 互换）。
 *
 * 持久化键是 `desk_sides_swapped`。
 *
 * 这里只放**不依赖 Android View** 的换算，方便单测；真正的布局动作在 `NavPage`。
 */
object PipSides {

    /** 左右互换开关的持久化键。 */
    const val KEY = PipRatio.KEY_SIDES_SWAPPED

    /** `View.LAYOUT_DIRECTION_LTR`（0）。 */
    const val DIR_LTR = 0

    /** `View.LAYOUT_DIRECTION_RTL`（1）。 */
    const val DIR_RTL = 1

    /**
     * 切换左右互换
     * `setSidesSwapped(!isSidesSwapped())`。
     */
    fun toggle(current: Boolean): Boolean = !current

    /**
     * 导航行该用哪个布局方向。
     *
     * @return [DIR_RTL] = 地图卡挪到右边；[DIR_LTR] = 地图卡在左边（默认）
     */
    fun layoutDirection(swapped: Boolean): Int = if (swapped) DIR_RTL else DIR_LTR

    /**
     * 分隔条该卡在地图卡的哪条边上。
     *
     * 分隔条是「地图卡 ↔ 组合卡」之间的那条缝：
     * - 不换位：地图卡在左，缝在它的**右**边（`mapRight`）；
     * - 换位：地图卡在右，缝在它的**左**边（`mapLeft`）。
     *
     * @param mapLeft 地图卡左边界
     * @param mapRight 地图卡右边界
     */
    fun dividerEdge(mapLeft: Int, mapRight: Int, swapped: Boolean): Int =
        if (swapped) mapLeft else mapRight
}
