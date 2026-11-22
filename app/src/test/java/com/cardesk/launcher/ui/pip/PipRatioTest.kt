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
        // —— 双画 0.5f、单画 0.6414f（画板 02-nav：1212 / 584）
        assertEquals(0.6414f, PipRatio.DEFAULT_NORMAL, 0f)
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

    // —— 第228轮：拖动吸附（量化）——
    //
    // 真车实测：镜像路线下拖分隔条只有 ~10fps ——
    // 卡片宽度每变一点，承载应用就按新尺寸**重画整张地图**。
    // 量化成 12px 一格 ⇒ 一次 200px 的拖动只触发 ~16 次重画。

    @Test
    fun quantize_snapsToPixelGrid() {
        // 总宽 1812px、吸附格 12px ⇒ 比例一格 = 12/1812 ≈ 0.00662
        val total = 1812f
        val step = 12f / total
        // 网格是「**从 0 起算**」的等距点（卡片宽度 = 比例 × 总宽 ⇒ 宽度是 12 的倍数）
        val onGrid = step * 25f
        // 正好在格子上 ⇒ 原样返回
        assertEquals(onGrid, PipRatio.quantize(onGrid, total, 12f), 1e-6f)
        // 偏离半格以内 ⇒ 吸回去
        assertEquals(onGrid, PipRatio.quantize(onGrid + step * 0.4f, total, 12f), 1e-6f)
        // 偏离半格以外 ⇒ 吸到下一格
        assertEquals(onGrid + step, PipRatio.quantize(onGrid + step * 0.6f, total, 12f), 1e-6f)
    }

    @Test
    fun quantize_errorNeverExceedsHalfStep() {
        // 吸附的误差必须**小于半格** —— 这是「吸附」的定义，
        // 也是「肉眼看不出跳动」的保证（半格 = 6px）。
        val total = 1812f
        val step = 12f / total
        var x = 0.1f
        while (x < 0.95f) {
            val snapped = PipRatio.quantize(x, total, 12f)
            assertTrue(
                "quantize($x) = $snapped 偏离超过半格 ${step / 2f}",
                Math.abs(snapped - x) <= step / 2f + 1e-6f,
            )
            x += 0.0037f
        }
    }

    @Test
    fun quantize_keepsRatioWhenWidthUnknown() {
        // 总宽 / 格宽非法时原样返回（不能把比例算坏）
        assertEquals(0.42f, PipRatio.quantize(0.42f, 0f, 12f), 1e-6f)
        assertEquals(0.42f, PipRatio.quantize(0.42f, 1812f, 0f), 1e-6f)
    }
}
