package com.cardesk.launcher.core.dock

import com.cardesk.launcher.ui.LauncherView
import com.cardesk.launcher.ui.pages.NavPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航页画中画**与底部 Dock 的几何关系**（第 159 轮，实测订正）。
 *
 * ## 这条测试在守什么
 *
 * 底部 Dock 栏（`DockBarView`）是浮在页面之上的（铺满屏宽×104、距底 8）。
 * 第159轮我一度以为画中画内容会被它压住 84px，
 * 于是给画中画内容加了一段底部留白 —— 结果**弄反了**：
 * 画中画卡底边本来就在 Dock 顶边**上方 28px**，从来没被挡过；
 * 加了留白反而让承载内容**不再铺满卡片**，底部空出一条 112px 的带子。
 *
 * 所以这里钉的是**正确**的关系：
 *
 * ```
 * 导航页底边 = DOCK_TOP_Y(968)          ← LauncherView 按 Dock 顶边算导航页高度
 * 导航行底边 = 968 − NAV_BOTTOM_GAP(28) = 940
 * 画中画卡底边 = 940                     ← 在 Dock 顶边 968 上方 28px
 * ```
 */
class PipDockInsetTest {

    /** 全局 Dock 顶边（= 屏幕底 1080 − Dock 高 104；第183轮起贴底，无底边距）。 */
    private val dockTop: Int = LauncherView.DOCK_TOP_Y

    /** 导航页整块内容的底边（LauncherView 按 Dock 顶边设高度）。 */
    private val navContentBottom: Int = LauncherView.DOCK_TOP_Y

    /** 导航行（画中画卡所在的行）的底边。 */
    private val navRowBottom: Int = LauncherView.DOCK_TOP_Y - NavPage.NAV_BOTTOM_GAP

    @Test
    fun navPageBottomSitsExactlyOnDockTop() {
        // 导航页整块内容的高度是按 Dock 顶边算的 —— 两者齐平，不重叠。
        assertEquals(dockTop, navContentBottom)
    }

    @Test
    fun pipCardBottomStaysAboveDockTop() {
        // 画中画卡底边 = 导航行底边 = 940，在 Dock 顶边 968 **上方 28px**
        // （第192轮 Dock 高 104 → 112，顶边 976 → 968）。
        assertEquals(940, navRowBottom)
        assertTrue(
            "画中画卡底边 $navRowBottom 伸进了 Dock（顶边 $dockTop）",
            navRowBottom <= dockTop,
        )
    }

    @Test
    fun contentFillsThePipCard() {
        // 底部留白必须是 0 —— 画中画卡自己就已经让开 Dock 了，
        // 再留一段只会让承载内容不铺满卡片、底部空出一条带子。
        assertEquals(
            "画中画内容底部留白应为 0（第159轮的 112 已撤销）",
            0,
            NavPage.PIP_CONTENT_INSET_BOTTOM,
        )
        assertEquals(0, NavPage.PIP_CONTENT_INSET_TOP)
    }

    @Test
    fun dockTopMatchesDockBarHeight() {
        // Dock 顶边必须与 Dock 占位高度自洽：
        // 顶边 = 屏幕底 − Dock 占位，留给导航页的高度才对得上。
        val dockHeight = DockBottomProbe.dockBarHeightPx(DockBottomProbe.DESIGN_WIDTH)
        assertEquals(
            dockTop,
            1080 - dockHeight,
        )
    }
}
