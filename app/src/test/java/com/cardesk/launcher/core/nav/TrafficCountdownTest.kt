package com.cardesk.launcher.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红绿灯读秒**显示**状态机单测（第八十六轮）。
 *
 * 背景：真车实测绿灯时读秒**一直在 3 与 2 之间来回跳**。
 * 根因是旧算法每收到一帧就把读秒**重新锚回原始值**，
 * 而高德不是每秒都刷这个字段（真车帧间隔约 2 秒）——
 * 于是显示变成 `3 →（本机减 1）2 →（新帧重锚）3 → 2 …` 的锯齿。
 *
 * 这里锁死的新规则：**同一灯态里读秒只减不增。**
 */
class TrafficCountdownTest {

    private val green = AmapTrafficLight.Lamp.GREEN
    private val red = AmapTrafficLight.Lamp.RED

    // ------------------------------------------------------------ 基本

    @Test
    fun `第一帧直接用原始读秒当锚点`() {
        val t = TrafficCountdown()
        assertEquals(9, t.accept(green, 9, 0L))
        assertEquals(9, t.current(0L))
    }

    @Test
    fun `没有新帧时按本机时钟往下减`() {
        val t = TrafficCountdown()
        t.accept(green, 9, 0L)
        assertEquals(8, t.current(1000L))
        assertEquals(7, t.current(2000L))
        assertEquals(6, t.current(3000L))
    }

    @Test
    fun `current 不推进状态`() {
        val t = TrafficCountdown()
        t.accept(green, 9, 0L)
        assertEquals(8, t.current(1000L))
        // 再读一次还是 8 —— 没有把状态往前推
        assertEquals(8, t.current(1000L))
        assertEquals(8, t.current(1000L))
    }

    @Test
    fun `归零之后不显示数字`() {
        val t = TrafficCountdown()
        t.accept(green, 2, 0L)
        assertEquals(1, t.current(1000L))
        assertNull(t.current(2000L))
    }

    // ------------------------------------------------------------ 本轮的核心回归

    /**
     * 真车症状的复现：原始读秒**恒定**（供应商没刷新），帧间隔 2 秒。
     *
     * 旧算法会显示成 `3 → 2 → 3 → 2 …`（每帧重锚），
     * 新算法必须**单调递减**直到归零。
     */
    @Test
    fun `原始值恒定且帧间隔 2 秒时读秒单调递减不回跳`() {
        val t = TrafficCountdown()
        val shown = ArrayList<Int?>()
        // 10 秒内每 2 秒来一帧，原始值一直是 3
        for (frame in 0..4) {
            val at = frame * 2000L
            shown.add(t.accept(green, 3, at))
            // 帧与帧之间的每一秒也读一次
            shown.add(t.current(at + 1000L))
        }
        // 期望：3,2,1,1,0(空),…  —— 关键是没有一次比前一次大
        val numbers = shown.filterNotNull()
        assertTrue(
            "读秒不能回跳，实际序列：$shown",
            numbers.zipWithNext().all { (a, b) -> b <= a },
        )
        assertEquals(3, numbers.first())
        assertTrue("应该能走到 1 秒以下，实际：$numbers", numbers.contains(1))
    }

    @Test
    fun `新帧的原始值没变时不会把已经走掉的秒数加回去`() {
        val t = TrafficCountdown()
        assertEquals(3, t.accept(green, 3, 0L))
        // 两秒后同一灯态又来一帧，原始值还是 3
        // 此时本机已经把读秒减到 1 —— 不能被重锚回 3
        assertEquals(1, t.accept(green, 3, 2000L))
    }

    @Test
    fun `新帧的原始值更小时以它为准`() {
        val t = TrafficCountdown()
        t.accept(green, 12, 0L)
        // 供应商说只剩 5 秒 —— 以它为准
        assertEquals(5, t.accept(green, 5, 3000L))
    }

    @Test
    fun `供应商把秒数加回去时显示值不跟着涨`() {
        val t = TrafficCountdown()
        t.accept(green, 5, 0L)
        assertEquals(4, t.current(1000L))
        // 原始值反而变大了（多半是供应商没刷新）
        assertTrue("显示值不能变大", t.accept(green, 9, 2000L)!! <= 4)
    }

    @Test
    fun `归零之后不会被新帧复活`() {
        val t = TrafficCountdown()
        t.accept(green, 2, 0L)
        assertNull(t.current(2000L))
        // 又来一帧，原始值还是 2 —— 不该把数字再显示出来
        assertNull(t.accept(green, 2, 3000L))
        assertNull(t.current(4000L))
    }

    // ------------------------------------------------------------ 灯态切换

    @Test
    fun `灯态变了重新起一轮`() {
        val t = TrafficCountdown()
        t.accept(green, 3, 0L)
        // 变红灯，读秒用新一帧的值
        assertEquals(25, t.accept(red, 25, 5000L))
        assertEquals(24, t.current(6000L))
    }

    @Test
    fun `灯态变了之后旧的归零状态不跟着过来`() {
        val t = TrafficCountdown()
        t.accept(green, 1, 0L)
        assertNull(t.current(1000L))
        // 换灯态 → 重新起一轮，读秒回来了
        assertEquals(20, t.accept(red, 20, 2000L))
    }

    // ------------------------------------------------------------ 其它

    @Test
    fun `这一帧没带读秒时沿用上一帧的值继续减`() {
        val t = TrafficCountdown()
        t.accept(green, 6, 0L)
        assertEquals(5, t.accept(green, null, 1000L))
        assertEquals(4, t.current(2000L))
    }

    @Test
    fun `reset 之后从头开始`() {
        val t = TrafficCountdown()
        t.accept(green, 3, 0L)
        t.reset()
        assertNull(t.current(1000L))
        assertEquals(8, t.accept(red, 8, 2000L))
    }

    @Test
    fun `lastAnchorAt 报告上一次收到信号的时刻`() {
        val t = TrafficCountdown()
        assertEquals(0L, t.lastAnchorAt())
        t.accept(green, 3, 1234L)
        assertEquals(1234L, t.lastAnchorAt())
        t.reset()
        assertEquals(0L, t.lastAnchorAt())
    }
}
