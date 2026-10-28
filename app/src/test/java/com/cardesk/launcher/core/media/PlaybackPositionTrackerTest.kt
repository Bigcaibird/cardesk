package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三十三轮：播放位置解析（歌词不同步的另一半）。
 *
 *
 * 位置 = 「会话上报的位置 + 从锚点经过的时间」。
 */
class PlaybackPositionTrackerTest {

    @Test
    fun `会话报了位置就按它算`() {
        val t = PlaybackPositionTracker()
        assertEquals(5_000L, t.resolve(5_000L, 10_000L, playing = true, durationMs = 0L))
        assertFalse(t.positionUnknown)
    }

    @Test
    fun `播放中按锚点外推`() {
        val t = PlaybackPositionTracker()
        // t=1000 报 5000 → 锚点落在 1000
        assertEquals(5_000L, t.resolve(5_000L, 1_000L, playing = true, durationMs = 0L))
        // 200ms 后 → 5200
        assertEquals(5_200L, t.resolve(5_000L, 1_200L, playing = true, durationMs = 0L))
    }

    @Test
    fun `位置变了就重新打锚点`() {
        val t = PlaybackPositionTracker()
        t.resolve(5_000L, 1_000L, playing = true, durationMs = 0L)
        // 会话报新位置 6000（锚点重置到 1500）
        assertEquals(6_000L, t.resolve(6_000L, 1_500L, playing = true, durationMs = 0L))
        assertEquals(6_200L, t.resolve(6_000L, 1_700L, playing = true, durationMs = 0L))
    }

    @Test
    fun `暂停时位置不外推`() {
        val t = PlaybackPositionTracker()
        t.resolve(5_000L, 1_000L, playing = true, durationMs = 0L)
        assertEquals(5_000L, t.resolve(5_000L, 9_000L, playing = false, durationMs = 0L))
    }

    @Test
    fun `会话没给位置时沿用上一次已知位置继续外推`() {
        val t = PlaybackPositionTracker()
        t.resolve(5_000L, 1_000L, playing = true, durationMs = 0L)
        // 会话改成「位置未知」（-1）：不能从 -1 重新数，要接着上一次的位置
        assertEquals(5_200L, t.resolve(-1L, 1_200L, playing = true, durationMs = 0L))
        assertTrue(t.positionUnknown)
        assertEquals(-1L, t.lastObservedMs)
    }

    @Test
    fun `从来没拿到过位置时回落到零`() {
        val t = PlaybackPositionTracker()
        assertEquals(0L, t.resolve(-1L, 5_000L, playing = true, durationMs = 0L))
        assertTrue(t.positionUnknown)
    }

    @Test
    fun `位置不会超过总时长`() {
        val t = PlaybackPositionTracker()
        t.resolve(5_000L, 1_000L, playing = true, durationMs = 0L)
        // 一路外推到 100 秒，但歌只有 10 秒
        assertEquals(10_000L, t.resolve(5_000L, 100_000L, playing = true, durationMs = 10_000L))
    }

    @Test
    fun `位置不会小于零`() {
        val t = PlaybackPositionTracker()
        assertEquals(0L, t.resolve(-5L, 0L, playing = false, durationMs = 0L))
    }

    @Test
    fun `复位后重新开始`() {
        val t = PlaybackPositionTracker()
        t.resolve(5_000L, 1_000L, playing = true, durationMs = 0L)
        t.reset()
        assertTrue(t.positionUnknown)
        assertEquals(0L, t.resolve(-1L, 2_000L, playing = true, durationMs = 0L))
    }

    @Test
    fun `钳位符合预期`() {
        assertEquals(0L, PlaybackPositionTracker.clamp(-1L, 10_000L))
        assertEquals(10_000L, PlaybackPositionTracker.clamp(372_000L, 10_000L))
        assertEquals(30_000L, PlaybackPositionTracker.clamp(30_000L, 60_000L))
        // 总长未知 → 只做下限保护
        assertEquals(372_000L, PlaybackPositionTracker.clamp(372_000L, 0L))
    }
}
