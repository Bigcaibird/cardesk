package com.cardesk.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面切换动画的**数值规格**单测（纯 Kotlin，不碰 Android）。
 *
 * 第191轮：竖滑 ±60px / 600~700ms → 横滑 / 260ms。
 */
class PageTransitionSpecTest {

    /** 一次切换 260ms（车机级节奏）。 */
    @Test
    fun duration_matchesReference() {
        assertEquals(260L, PageTransitionSpec.DURATION)
    }

    /** 位移走页宽的 22%（不是整屏横移）。 */
    @Test
    fun travelIsPartialPageWidth() {
        assertEquals(0.22f, PageTransitionSpec.TRAVEL_RATIO, 1e-4f)
        assertTrue(PageTransitionSpec.TRAVEL_RATIO < 0.5f)
    }

    /** 1920 设计宽 → 位移约 422px。 */
    @Test
    fun travelPx_scalesWithPageWidth() {
        assertEquals(1920 * 0.22f, PageTransitionSpec.travelPx(1920), 0.01f)
        assertEquals(0f, PageTransitionSpec.travelPx(0), 0.01f)
    }

    /** 交叉淡入比位移短一点（新页先「有实体感」）。 */
    @Test
    fun fadeIsShorterThanTravel() {
        assertTrue(PageTransitionSpec.FADE_DURATION < PageTransitionSpec.DURATION)
        assertEquals(200L, PageTransitionSpec.FADE_DURATION)
    }

    /** 纵深缩放：新页从 0.96 放大到 1。 */
    @Test
    fun scaleProvidesDepth() {
        assertEquals(0.96f, PageTransitionSpec.ENTER_SCALE, 1e-4f)
        assertEquals(0.96f, PageTransitionSpec.EXIT_SCALE, 1e-4f)
        assertTrue(PageTransitionSpec.ENTER_SCALE < 1f)
    }

    /** 强减速曲线 `PathInterpolator(0.15f, 0.9f, 0.2f, 1.0f)`。 */
    @Test
    fun interpolatorMatchesReference() {
        assertEquals(0.15f, PageTransitionSpec.INTERPOLATOR_X1, 1e-4f)
        assertEquals(0.9f, PageTransitionSpec.INTERPOLATOR_Y1, 1e-4f)
        assertEquals(0.2f, PageTransitionSpec.INTERPOLATOR_X2, 1e-4f)
        assertEquals(1f, PageTransitionSpec.INTERPOLATOR_Y2, 1e-4f)
    }

    /** 往下一页 = 新页从右边进。 */
    @Test
    fun forward_entersFromRight() {
        assertTrue(PageTransitionSpec.forward(0, 1))
        assertEquals(PageTransitionSpec.travelPx(1920), PageTransitionSpec.enterTranslation(1920, true), 0.01f)
    }

    /** 往上一页 = 新页从左边进。 */
    @Test
    fun backward_entersFromLeft() {
        assertTrue(!PageTransitionSpec.forward(1, 0))
        assertEquals(-PageTransitionSpec.travelPx(1920), PageTransitionSpec.enterTranslation(1920, false), 0.01f)
    }

    /**
     * 关键：新页的进入方向与旧页的退出方向**互为反号**。
     *
     * 两页像一副牌被推开 —— 新页从一边进、旧页往另一边退。
     * 如果同号，两页会朝同一个方向挤过去，看起来像「页面在抖」。
     */
    @Test
    fun enterAndExitAreOppositeDirections() {
        for (forward in listOf(true, false)) {
            val enter = PageTransitionSpec.enterTranslation(1920, forward)
            val exit = PageTransitionSpec.exitTranslation(1920, forward)
            assertEquals("forward=$forward", -enter, exit, 0.01f)
        }
    }

    /** 方向与主翻页手势（单指横滑）一致：往下一页 → 画面整体往左走。 */
    @Test
    fun directionMatchesHorizontalSwipeGesture() {
        // 单指左滑（dx < 0）→ 下一页 → 新页从右进、旧页往左退。
        val forward = PageTransitionSpec.forward(0, 1)
        assertTrue(forward)
        assertTrue(PageTransitionSpec.enterTranslation(1920, forward) > 0f)
        assertTrue(PageTransitionSpec.exitTranslation(1920, forward) < 0f)
    }
}
