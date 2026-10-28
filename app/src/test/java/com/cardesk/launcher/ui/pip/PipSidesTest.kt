package com.cardesk.launcher.ui.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画「左右换位」的纯逻辑单测
 *
 *
 * 只测纯逻辑，不依赖 Android View。
 */
class PipSidesTest {

    @Test
    fun key_matchesReferenceKey() {
        // —— KEY_SIDES_SWAPPED = "desk_sides_swapped"
        assertEquals("desk_sides_swapped", PipSides.KEY)
        assertEquals(PipRatio.KEY_SIDES_SWAPPED, PipSides.KEY)
    }

    @Test
    fun toggle_flipsState() {
        assertTrue(PipSides.toggle(false))
        assertFalse(PipSides.toggle(true))
    }

    @Test
    fun layoutDirection_matchesReference() {
        // —— setLayoutDirection(swapped ? 1: 0)
        assertEquals(0, PipSides.layoutDirection(false))
        assertEquals(1, PipSides.layoutDirection(true))
        assertEquals(PipSides.DIR_LTR, PipSides.layoutDirection(false))
        assertEquals(PipSides.DIR_RTL, PipSides.layoutDirection(true))
    }

    @Test
    fun dividerEdge_followsMapCardSide() {
        // 不换位：地图卡在左，缝在地图卡右边（1920 画布下地图卡右边界 1363）
        assertEquals(1363, PipSides.dividerEdge(0, 1363, swapped = false))
        // 换位：地图卡在右，缝在地图卡左边（左边界 = 1920 - 1363 = 557）
        assertEquals(557, PipSides.dividerEdge(557, 1920, swapped = true))
    }
}
