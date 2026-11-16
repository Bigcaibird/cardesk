package com.cardesk.launcher.core.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局 Dock 底部占位的**几何口径**（第 159 轮）。
 *
 * 纯逻辑，不碰 Android —— 这些数字决定了「某个 App 会不会被全局 Dock 盖住底部」，
 * 写死它们是为了让口径不会随着谁改常量而悄悄漂移。
 */
class DockBottomProbeTest {

    @Test
    fun dockBarHeightScalesWithScreenWidth() {
        // 1920 宽的车机：Dock 112（第192轮起与系统导航预留带一样高，贴底）
        assertEquals(112, DockBottomProbe.dockBarHeightPx(1920))
        // 1280 宽：round(1280 * 112 / 1920) = round(74.67) = 75
        assertEquals(75, DockBottomProbe.dockBarHeightPx(1280))
        // 再窄也不能算成 0（否则 Dock 直接消失）
        assertEquals(1, DockBottomProbe.dockBarHeightPx(1))
    }

    @Test
    fun dockTopSitsOneBarAboveScreenBottom() {
        // 1080 高的屏幕，Dock 占底部 112 → 顶边在 968
        assertEquals(968, DockBottomProbe.dockTopY(1080, 1920))
    }

    @Test
    fun immersiveAppIsFullyCovered() {
        // 沉浸型 App（网易云车机版 / 汽水音乐车机版：自己 hideNavigationBars）
        // 画面一路铺到屏幕底 1080 → 被全局 Dock 整整盖住 112px
        assertEquals(112, DockBottomProbe.overlapPx(1080, 968))
    }

    @Test
    fun insetAwareAppIsNotCoveredAtAll() {
        // 遵守系统导航栏 inset 的 App（QQ 音乐车机版）底边正好停在预留区上沿 968
        // → 一像素都不压
        assertEquals(0, DockBottomProbe.overlapPx(968, 968))
    }

    @Test
    fun appEndingAboveDockTopHasNoOverlap() {
        assertEquals(0, DockBottomProbe.overlapPx(900, 968))
    }

    @Test
    fun reachesScreenBottomDetectsImmersiveApps() {
        assertTrue(DockBottomProbe.reachesScreenBottom(1080, 1080))
        assertTrue(DockBottomProbe.reachesScreenBottom(1079, 1080))
        assertFalse(DockBottomProbe.reachesScreenBottom(968, 1080))
        assertFalse(DockBottomProbe.reachesScreenBottom(967, 1080))
    }

    @Test
    fun expectedNavBarHeightMatchesDockBarHeight() {
        // 预留区的高度必须**正好**等于全局 Dock 占的高度：
        // 预留区不足 → 遵守 inset 的 App 也会被压；
        // 预留区过大 → App 上方多出一条没用的空带。
        // 这两个数同源（第159轮起共用 [DockBottomProbe] 的常量），改一个必须改另一个。
        assertEquals(
            DockBottomProbe.EXPECTED_NAV_BAR_PX,
            DockBottomProbe.dockBarHeightPx(DockBottomProbe.DESIGN_WIDTH),
        )
    }
}
