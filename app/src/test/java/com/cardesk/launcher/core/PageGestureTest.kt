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

    // ---------------------------------------------------------------- 第206轮：双指横滑

    // 第207轮：`twoFingerStep` 的判定口径改了 ——
    // 间距用**绝对距离**（span0 / span）而不是「比例」，
    // 纵向位移也参与判定（与横向同源，都取两指中位点）。

    @Test
    fun `双指左滑翻到下一页`() {
        assertEquals(1, PageGesture.twoFingerStep(-200f, 0f, 200f, 200f))
    }

    @Test
    fun `双指右滑翻到上一页`() {
        assertEquals(-1, PageGesture.twoFingerStep(200f, 0f, 200f, 200f))
    }

    @Test
    fun `双指位移不够时不翻页`() {
        assertEquals(0, PageGesture.twoFingerStep(-120f, 0f, 200f, 200f))
    }

    @Test
    fun `捏合缩放不当作翻页`() {
        // 间距 200 → 120（缩了 80px，40%）
        assertEquals(0, PageGesture.twoFingerStep(-400f, 0f, 200f, 120f))
        // 间距 200 → 500（放大了 300px）
        assertEquals(0, PageGesture.twoFingerStep(-400f, 0f, 200f, 500f))
    }

    @Test
    fun `两指间距轻微变化仍可翻页`() {
        // 200 → 215（变化 15px，远小于 60px 的绝对容差）
        assertEquals(1, PageGesture.twoFingerStep(-200f, 0f, 200f, 215f))
    }

    @Test
    fun `两指靠得近时轻微抖动仍可翻页`() {
        // 第207轮：初始间距只有 30px，手指抖 6px 就是 **20%**——
        // 纯相对判定（18% 线）会误杀成捏合，绝对容差（60px）兜住这种场景。
        // 这组数值是**能区分新旧实现**的：旧口径（`delta > span0 * 0.18`）
        // 在这里会返回 0（误判成捏合），新口径返回 1。
        assertEquals(1, PageGesture.twoFingerStep(-200f, 0f, 30f, 36f))
        // 抖动更小也一样能翻页
        assertEquals(1, PageGesture.twoFingerStep(-200f, 0f, 30f, 35f))
    }

    @Test
    fun `两指靠得近但明显张开仍是捏合`() {
        // 30 → 120：张开了 90px，超过绝对容差，也超过 30% 的相对量
        assertEquals(0, PageGesture.twoFingerStep(-400f, 0f, 30f, 120f))
    }

    @Test
    fun `纵向为主的滑动不翻页`() {
        // 横向 200px、纵向 300px —— 纵向压倒横向，不算横滑
        assertEquals(0, PageGesture.twoFingerStep(-200f, 300f, 200f, 200f))
    }

    @Test
    fun `捏合判定线`() {
        // 变化不超过绝对容差（60px）→ 不是捏合
        assertEquals(false, PageGesture.isTwoFingerPinch(200f, 250f))
        // 变化超过绝对容差，且超过 30% 相对量 → 是捏合
        assertEquals(true, PageGesture.isTwoFingerPinch(200f, 120f))
        // 变化超过绝对容差，但相对量不到 30%（初始间距很大）→ 不是捏合
        assertEquals(false, PageGesture.isTwoFingerPinch(1000f, 900f))
    }
}
