package com.cardesk.launcher.ui.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画分屏比例的纯逻辑单测
 *
 *
 * 只测纯逻辑，不依赖 Android View。
 */
class PipRatioTest {

    @Test
    fun defaultRatio_matchesReferenceValues() {
        // —— 双画 0.5f、单画 0.71030045f
        assertEquals(0.71030045f, PipRatio.DEFAULT_NORMAL, 0f)
        assertEquals(0.5f, PipRatio.DEFAULT_DUAL, 0f)
        assertEquals(PipRatio.DEFAULT_NORMAL, PipRatio.defaultRatio(false), 0f)
        assertEquals(PipRatio.DEFAULT_DUAL, PipRatio.defaultRatio(true), 0f)
    }

    @Test
    fun persistenceKeys_matchReferenceKeys() {
        // KEY_DUAL_PIP_RATIO /:80 KEY_NORMAL_PIP_RATIO
        assertEquals("desk_primary_ratio_normal_v1", PipRatio.KEY_NORMAL)
        assertEquals("desk_primary_ratio_dual_v1", PipRatio.KEY_DUAL)
        assertEquals(PipRatio.KEY_NORMAL, PipRatio.key(false))
        assertEquals(PipRatio.KEY_DUAL, PipRatio.key(true))
    }

    @Test
    fun clamp_keepsRatioInsideZeroToOne() {
        assertEquals(0f, PipRatio.clamp(-0.5f, false), 0f)
        assertEquals(1f, PipRatio.clamp(1.5f, false), 0f)
        assertEquals(0.42f, PipRatio.clamp(0.42f, false), 0f)
        assertEquals(0f, PipRatio.clamp(0f, true), 0f)
        assertEquals(1f, PipRatio.clamp(1f, true), 0f)
    }

    @Test
    fun clamp_nonFiniteFallsBackToDefault() {
        // —— NaN / Inf 都回落到默认值
        assertEquals(PipRatio.DEFAULT_NORMAL, PipRatio.clamp(Float.NaN, false), 0f)
        assertEquals(PipRatio.DEFAULT_NORMAL, PipRatio.clamp(Float.POSITIVE_INFINITY, false), 0f)
        assertEquals(PipRatio.DEFAULT_NORMAL, PipRatio.clamp(Float.NEGATIVE_INFINITY, false), 0f)
        assertEquals(PipRatio.DEFAULT_DUAL, PipRatio.clamp(Float.NaN, true), 0f)
    }

    @Test
    fun dragRatio_movesProportionallyToDragWidth() {
        // 起始 0.5，总宽 1000px，右移 100px → 0.6
        assertEquals(0.6f, PipRatio.dragRatio(0.5f, 100f, 1000f, swapped = false, dual = false), 1e-6f)
        // 左移 100px → 0.4
        assertEquals(0.4f, PipRatio.dragRatio(0.5f, -100f, 1000f, swapped = false, dual = false), 1e-6f)
    }

    @Test
    fun dragRatio_swappedSidesInvertsDelta() {
        // —— 左右互换时位移取反
        assertEquals(0.4f, PipRatio.dragRatio(0.5f, 100f, 1000f, swapped = true, dual = false), 1e-6f)
        assertEquals(0.6f, PipRatio.dragRatio(0.5f, -100f, 1000f, swapped = true, dual = false), 1e-6f)
    }

    @Test
    fun dragRatio_clampsToZeroToOne() {
        assertEquals(1f, PipRatio.dragRatio(0.9f, 500f, 1000f, swapped = false, dual = false), 1e-6f)
        assertEquals(0f, PipRatio.dragRatio(0.1f, -500f, 1000f, swapped = false, dual = false), 1e-6f)
    }

    @Test
    fun dragRatio_zeroWidthKeepsStartRatio() {
        assertEquals(0.42f, PipRatio.dragRatio(0.42f, 100f, 0f, swapped = false, dual = false), 1e-6f)
    }

    @Test
    fun dragRatio_nonFiniteStartFallsBackToDefault() {
        assertTrue(
            PipRatio.dragRatio(Float.NaN, 10f, 1000f, swapped = false, dual = true) == PipRatio.DEFAULT_DUAL,
        )
    }
}
