package com.cardesk.launcher.core.dock

import com.cardesk.launcher.core.DockLayout
import com.cardesk.launcher.core.DockMediaLayout
import com.cardesk.launcher.ui.LauncherView
import com.cardesk.launcher.ui.shell.DockBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Dock 高度 ↔ 系统导航预留带高度**的对齐关系（第192轮）。
 *
 * ## 这条测试在守什么
 *
 * 全局 Dock 走的是「系统预留带」这条路：
 *
 * 1. `NavInsets` 的 RRO 覆盖包把 framework-res 的 `navigation_bar_height*`
 *    改成 **112dp** —— 系统因此在屏幕底部给**所有 App**留出 112px；
 * 2. 全局 Dock 窗口画进这条带子里（`TYPE_NAVIGATION_BAR_PANEL`），
 *    顶替掉原车 Dock。
 *
 * 这条链子上有**三个独立的数字**会决定「预留带和 Dock 对不对得上」：
 *
 * | 数字 | 在哪 |
 * |---|---|
 * | 预留带高 112 | `NavInsets.HEIGHT_DP`（= RRO 里的 dimen） |
 * | Dock 占多高 | `DockBottomProbe.DESIGN_BAR_HEIGHT` |
 * | Dock 画多高 | `DockBarView.DOCK_HEIGHT` |
 *
 * 它们**必须完全相等**：
 * - Dock 比预留带**矮** → 中间剩一条「系统留了位、却没人画」的缝
 *   （第191轮实测：预留带 112、Dock 104，缝 8px）；
 * - Dock 比预留带**高** → 盖住别的 App 让出来的那一截。
 *
 * 第183轮把 Dock 从 112 改成 104（贴底、去 8px 底边距），
 * 就和预留带差开了 8px —— 第192轮改回 112 并把这三个数字**钉在一起**。
 *
 * 纯逻辑，不碰 Android（三个常量都是 `const val`，编译期就内联了）。
 */
class DockHeightAlignmentTest {

    /** 车机屏幕设计尺寸（1920×1080 @160dpi，1dp = 1px）。 */
    private val designW = 1920
    private val designH = 1080

    /**
     * 空调簇的**实测**占位宽（与 `DockLayoutTest` 同口径）。
     *
     * 簇宽是按字体量的，这里用同一份估算值 —— 它只影响媒体卡能分到多少宽度，
     * 不影响「三个高度是否相等」这个核心断言。
     */
    private val clusterW = 192f + 32f + 16f + 104f + 75f + 224f * 2 + 100f + 22f

    @Test
    fun reservedAreaHeightIsDeclaredAs112() {
        // RRO 覆盖包里的 dimen 就是 112dp —— 改它要同步改 insets/res/values*/dimens.xml。
        assertEquals(112, NavInsets.HEIGHT_DP)
    }

    @Test
    fun dockBarHeightMatchesReservedAreaHeight() {
        // 全局 Dock 窗口占的高度 == 系统预留带高度
        assertEquals(
            NavInsets.HEIGHT_DP,
            DockBottomProbe.dockBarHeightPx(DockBottomProbe.DESIGN_WIDTH),
        )
    }

    @Test
    fun expectedNavBarHeightMatchesReservedAreaHeight() {
        // 诊断页判「预留区通没通」用的期望值，也必须等于 RRO 声明的高度。
        assertEquals(NavInsets.HEIGHT_DP, DockBottomProbe.EXPECTED_NAV_BAR_PX)
    }

    @Test
    fun dockViewHeightMatchesProbeHeight() {
        // Dock 本体画多高（设计层里的高度）== 全局 Dock 窗口占多高。
        // 不等的话，设计层底部会露出一条宿主的不透明底 —— 就是那条 8px 缝。
        assertEquals(DockBottomProbe.DESIGN_BAR_HEIGHT, DockBarView.DOCK_HEIGHT)
    }

    @Test
    fun dockTopSitsExactlyAboveTheReservedArea() {
        // 桌面上 Dock 的顶边 = 屏幕底 − Dock 高（贴底，底边距 0）。
        assertEquals(designH - DockBarView.DOCK_HEIGHT, LauncherView.DOCK_TOP_Y)
        assertEquals(968, LauncherView.DOCK_TOP_Y)
    }

    @Test
    fun globalDockWindowTopEqualsDesktopDockTop() {
        // 全局 Dock 窗口的顶边 == 桌面 Dock 的顶边 —— 两个 Dock 必须「一样大」，
        // 否则从桌面切到别的应用时底栏会跳一下。
        assertEquals(
            LauncherView.DOCK_TOP_Y,
            DockBottomProbe.dockTopY(designH, designW),
        )
    }

    /**
     * 图标整体放大（第192轮）之后，媒体卡仍要放得下
     * 「封面 + 歌名 + 三个键」，且**不压到导航键**。
     *
     * 横向预算是定死的：Dock 宽 1920 不会变，图标放大挤出来的宽度
     * 只能由中间的媒体卡让出来 —— 所以这一条是「放大有没有把音乐卡挤坏」的护栏。
     */
    @Test
    fun mediaCardStillFitsAfterIconEnlargement() {
        val s = DockLayout.sections(designW.toFloat(), clusterW)
        assertTrue("音乐卡不能压到导航键", s.mediaLeft >= s.keysRight)
        assertTrue(
            "音乐卡宽度 ${s.mediaWidth} 必须 ≥ ${DockMediaLayout.MIN_MEDIA_W}",
            s.mediaWidth >= DockMediaLayout.MIN_MEDIA_W,
        )
        val k = DockMediaLayout.keys(s.mediaWidth)
        assertTrue("歌名区宽度为负：${k.textWidth}", k.textWidth > 0f)
        // 歌名至少还能放下几个字（别把标题挤没了）
        assertTrue("歌名区只剩 ${k.textWidth}px，太窄", k.textWidth >= 80f)
    }

    /** 放大的图标在 112 高的 Dock 里必须放得下（不能顶出去）。 */
    @Test
    fun enlargedIconsFitInTheBar() {
        assertTrue(
            "媒体卡 ${DockMediaLayout.CARD_H} 必须 ≤ Dock 高 ${DockBarView.DOCK_HEIGHT}",
            DockMediaLayout.CARD_H <= DockBarView.DOCK_HEIGHT,
        )
        assertTrue(
            "快捷应用格 ${DockLayout.QUICK_TILE} 必须 ≤ Dock 高",
            DockLayout.QUICK_TILE <= DockBarView.DOCK_HEIGHT,
        )
        assertTrue(
            "导航键格 ${DockLayout.KEY_SLOT} 必须 ≤ Dock 高",
            DockLayout.KEY_SLOT <= DockBarView.DOCK_HEIGHT,
        )
    }
}
