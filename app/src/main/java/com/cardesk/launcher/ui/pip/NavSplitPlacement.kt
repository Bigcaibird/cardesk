package com.cardesk.launcher.ui.pip

import android.view.Gravity

/**
 * 导航页「画中画全屏按钮」（`navSplit`）的**挂载与定位规则** —— 纯逻辑。
 *
 * ## 这个按钮是什么
 *
 * 导航页那条 36×110 的玻璃 chevron 按钮：点一下把画中画切成全屏，
 * 全屏时它变成「还原」按钮。
 *
 * ## 第129轮改了什么
 *
 * 以前它挂在**导航页本体**上（`Gravity.START`）—— 也就是**独占了 navRow
 * 左边那 44px 留白**（`NavPage.NAV_SIDE_MARGIN`），画中画左侧长期空着
 * 一条竖玻璃条，还跟画面不对齐。
 *
 * 现在它挂在**画中画承载面**（`NavPage.mapCard`）里 —— 悬浮叠在画中画
 * 画面左边缘：
 * - 不再独占一条留白；
 * - 画中画左右换位时按钮**跟着画面走**，不会跑到画面对面；
 * - 位置与**全屏态**完全一致（左侧 + 垂直居中），用户不用记两个位置。
 *
 * ## 为什么把规则抽到这个文件
 *
 * 「非全屏悬浮在画中画上」和「全屏浮在画面上」这两态的按钮，
 * **宽 / 高 / gravity 必须逐字相同**。
 * 两态各写一份字面量的话，迟早会漂（改了一处忘了另一处，
 * 用户就会看到「点全屏后按钮跑位了」）。
 * 所以两态都从本对象取，单测直接钉住这条不变量。
 *
 * 纯逻辑（不碰 View），可 JVM 单测。
 */
object NavSplitPlacement {

    /** 按钮宽（设计坐标 px）。 */
    const val WIDTH = 36

    /** 按钮高（设计坐标 px）。 */
    const val HEIGHT = 110

    /**
     * 按钮在宿主容器里的 gravity：**左侧 + 垂直居中**。
     *
     * 非全屏态与全屏态**必须是同一个值** —— 见类注释。
     */
    const val GRAVITY = Gravity.START or Gravity.CENTER_VERTICAL

    /** 宿主标识：画中画承载面（`NavPage.mapCard`）。 */
    const val HOST_PIP_CARD = "pipCard"

    /** 宿主标识：桌面根容器（`LauncherView.content`）。 */
    const val HOST_DESKTOP_ROOT = "desktopRoot"

    /**
     * 非全屏态（悬浮叠在画中画上）的宿主。
     *
     * 必须是 [HOST_PIP_CARD] —— 挂到导航页本体就等于回到「独占一条留白」
     * 的旧行为（第129轮要修的就是这个）。
     */
    const val HOST_OVERLAID = HOST_PIP_CARD

    /**
     * 全屏态的宿主。
     *
     * 全屏时画中画被提到桌面根容器上，按钮也得跟着提上去，
     * 否则会被全屏画面盖住 —— 用户就找不到还原入口了。
     */
    const val HOST_FULLSCREEN = HOST_DESKTOP_ROOT

    /**
     * 该态下按钮挂在哪个宿主上。
     *
     * @param fullscreen 是否画中画全屏态
     */
    fun host(fullscreen: Boolean): String =
        if (fullscreen) HOST_FULLSCREEN else HOST_OVERLAID

    /**
     * 该态下按钮的 gravity。
     *
     * **两态返回同一个值** —— 这是本对象存在的核心不变量：
     * 按钮在「画中画」和「全屏」两个形态下位置必须一致。
     */
    @Suppress("UNUSED_PARAMETER")
    fun gravity(fullscreen: Boolean): Int = GRAVITY

    /**
     * 画中画承载面自身的布局方向。
     *
     * 必须是 **LTR**（[PipSides.DIR_LTR]）。
     *
     * 画中画左右换位是靠外层 `navRow` 改成 RTL 实现的，承载面自己保持 LTR，
     * **只换位置、不镜像内容**。按钮在承载面里按 `Gravity.START` 定位，
     * 所以承载面一旦被镜像，按钮就会跑到画中画的**右边**去 —— 跟画面错位。
     * 这里显式钉死，避免以后有人顺手把 RTL 传进来。
     */
    const val PIP_CARD_LAYOUT_DIRECTION = PipSides.DIR_LTR
}
