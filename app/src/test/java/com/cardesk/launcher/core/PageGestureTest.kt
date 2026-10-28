package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第三十二轮：桌面翻页手势（左右三指滑动 / 右缘竖滑 / 取模环绕）的纯逻辑单测。
 */
class PageGestureTest {

    // ---------------------------------------------------------- 三指横滑

    @Test
    fun `三指横滑位移不足不触发`() {
        assertEquals(0, PageGesture.threeFingerStep(0f))
        assertEquals(0, PageGesture.threeFingerStep(159f))
        assertEquals(0, PageGesture.threeFingerStep(-159f))
    }

    @Test
    fun `三指横滑恰好等于阈值不触发`() {
        assertEquals(0, PageGesture.threeFingerStep(160f))
        assertEquals(0, PageGesture.threeFingerStep(-160f))
    }

    @Test
    fun `三指左滑翻到下一页`() {
        assertEquals(1, PageGesture.threeFingerStep(-161f))
        assertEquals(1, PageGesture.threeFingerStep(-600f))
    }

    @Test
    fun `三指右滑翻到上一页`() {
        assertEquals(-1, PageGesture.threeFingerStep(161f))
        assertEquals(-1, PageGesture.threeFingerStep(600f))
    }

    @Test
    fun `三指横滑阈值可覆盖`() {
        assertEquals(0, PageGesture.threeFingerStep(80f, threshold = 80f))
        assertEquals(1, PageGesture.threeFingerStep(-81f, threshold = 80f))
        assertEquals(-1, PageGesture.threeFingerStep(81f, threshold = 80f))
    }

    @Test
    fun `三指横滑默认阈值是160设计px`() {
        assertEquals(160f, PageGesture.THREE_FINGER_PX, 0.0001f)
    }

    // ---------------------------------------------------------- 右缘竖滑

    @Test
    fun `右缘竖滑位移不足不触发`() {
        assertEquals(0, PageGesture.edgeSwipeStep(0f))
        assertEquals(0, PageGesture.edgeSwipeStep(119f))
        assertEquals(0, PageGesture.edgeSwipeStep(-119f))
    }

    @Test
    fun `右缘上滑翻到下一页`() {
        assertEquals(1, PageGesture.edgeSwipeStep(-121f))
        assertEquals(1, PageGesture.edgeSwipeStep(-400f))
    }

    @Test
    fun `右缘下滑翻到上一页`() {
        assertEquals(-1, PageGesture.edgeSwipeStep(121f))
        assertEquals(-1, PageGesture.edgeSwipeStep(400f))
    }

    @Test
    fun `右缘竖滑默认阈值是120设计px`() {
        assertEquals(120f, PageGesture.EDGE_TRIGGER, 0.0001f)
    }

    // ---------------------------------------------------------- 单指横滑（第三十七轮）

    @Test
    fun `单指横滑位移不足不触发`() {
        assertEquals(0, PageGesture.singleFingerStep(0f, 0f))
        assertEquals(0, PageGesture.singleFingerStep(159f, 0f))
        assertEquals(0, PageGesture.singleFingerStep(-159f, 0f))
    }

    @Test
    fun `单指横滑恰好等于阈值不触发`() {
        assertEquals(0, PageGesture.singleFingerStep(160f, 0f))
        assertEquals(0, PageGesture.singleFingerStep(-160f, 0f))
    }

    @Test
    fun `单指左滑翻到下一页`() {
        assertEquals(1, PageGesture.singleFingerStep(-161f, 0f))
        assertEquals(1, PageGesture.singleFingerStep(-600f, -30f))
    }

    @Test
    fun `单指右滑翻到上一页`() {
        assertEquals(-1, PageGesture.singleFingerStep(161f, 0f))
        assertEquals(-1, PageGesture.singleFingerStep(600f, 30f))
    }

    @Test
    fun `单指横滑必须明显比纵向更横才触发`() {
        // 纵向为主（抽屉滚动 / 温度盘拖动）→ 不翻页
        assertEquals(0, PageGesture.singleFingerStep(-300f, -300f))
        // 横向占比不够（300 ≤ 280 × 1.2 = 336）→ 仍不触发
        assertEquals(0, PageGesture.singleFingerStep(-300f, -280f))
        // 横向确实更大 → 翻页
        assertEquals(1, PageGesture.singleFingerStep(-300f, -100f))
        assertEquals(-1, PageGesture.singleFingerStep(300f, 100f))
    }

    @Test
    fun `单指横滑阈值与轴比可覆盖`() {
        assertEquals(0, PageGesture.singleFingerStep(80f, 0f, threshold = 80f))
        assertEquals(1, PageGesture.singleFingerStep(-81f, 0f, threshold = 80f))
        assertEquals(-1, PageGesture.singleFingerStep(81f, 0f, threshold = 80f))
        // 轴比放宽到 1.0 后，纵向位移更小也算横滑
        assertEquals(1, PageGesture.singleFingerStep(-400f, -300f, axisRatio = 1f))
        // 轴比放宽到 0.9 时，纵向位移相等也能算横滑
        assertEquals(1, PageGesture.singleFingerStep(-300f, -300f, axisRatio = 0.9f))
    }

    @Test
    fun `单指横滑默认阈值是160设计px`() {
        assertEquals(160f, PageGesture.SINGLE_FINGER_PX, 0.0001f)
        assertEquals(1.2f, PageGesture.SINGLE_FINGER_AXIS_RATIO, 0.0001f)
    }

    // ---------------------------------------------------------- 取模环绕

    @Test
    fun `两页之间正反环绕`() {
        assertEquals(1, PageGesture.wrapIndex(0, 1, 2))
        assertEquals(0, PageGesture.wrapIndex(1, 1, 2))
        // 两页是循环的：第 0 页的上一页是第 1 页，第 1 页的上一页是第 0 页
        assertEquals(1, PageGesture.wrapIndex(0, -1, 2))
        assertEquals(0, PageGesture.wrapIndex(1, -1, 2))
    }

    @Test
    fun `多页环绕也能回绕`() {
        assertEquals(2, PageGesture.wrapIndex(1, 1, 3))
        assertEquals(0, PageGesture.wrapIndex(2, 1, 3))
        assertEquals(2, PageGesture.wrapIndex(0, -1, 3))
    }

    @Test
    fun `页数为零时兜底回零`() {
        assertEquals(0, PageGesture.wrapIndex(1, 1, 0))
        assertEquals(0, PageGesture.wrapIndex(1, -1, -3))
    }
}
