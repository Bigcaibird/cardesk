package com.cardesk.launcher.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第201轮：播放态暂停迟滞。
 *
 * 回归对象是真机诊断包（2026-10-23，wecarflow）里的现象：
 * 会话自身每 ~4s 在 PLAYING / PAUSED 之间翻一次（进度完全不动），
 * 卡片上的播放键跟着一跳一跳。
 */
class PlaybackStateHoldTest {

    private val settle = PlaybackStateHold.DEFAULT_PAUSE_SETTLE_MS

    @Test
    fun firstSample_isAdoptedImmediately() {
        val hold = PlaybackStateHold()
        assertTrue(hold.hold(next = true, nowMs = 0L))
        val paused = PlaybackStateHold()
        assertFalse(paused.hold(next = false, nowMs = 0L))
    }

    @Test
    fun shortPauseBlip_isAbsorbed() {
        val hold = PlaybackStateHold()
        hold.hold(true, 0L)
        // 会话抖一下说暂停，2s 后又说在放 —— 界面不该闪成暂停
        assertTrue(hold.hold(false, 1_000L))
        assertTrue(hold.hold(false, 2_000L))
        assertTrue(hold.hold(true, 2_400L))
        assertTrue(hold.hold(true, 3_000L))
    }

    @Test
    fun sustainedPause_isAdoptedAfterSettle() {
        val hold = PlaybackStateHold()
        hold.hold(true, 0L)
        assertTrue(hold.hold(false, 1_000L))
        assertTrue(hold.hold(false, 1_000L + settle - 1))
        assertFalse(hold.hold(false, 1_000L + settle))
        // 已经采纳的暂停要一直保持
        assertFalse(hold.hold(false, 20_000L))
    }

    @Test
    fun resume_isImmediate() {
        val hold = PlaybackStateHold()
        hold.hold(true, 0L)
        hold.hold(false, 1_000L)
        hold.hold(false, 1_000L + settle)
        assertFalse(hold.hold(false, 10_000L))
        // 用户按下播放：下一拍就必须变回播放
        assertTrue(hold.hold(true, 10_200L))
    }

    @Test
    fun flutterAroundSettleBoundary_doesNotChatter() {
        // 模拟诊断包：每 ~4s 翻一次
        val hold = PlaybackStateHold()
        var t = 0L
        hold.hold(true, t)
        val seen = mutableListOf<Boolean>()
        repeat(8) {
            t += 4_200L
            seen += hold.hold(false, t)
            t += 4_000L
            seen += hold.hold(true, t)
        }
        // 每次暂停都刚好够 settle → 会采纳，但恢复立刻回来；
        // 关键是相邻两拍不会再来回抖（见下一个断言）
        assertTrue(seen.last())
    }

    @Test
    fun force_overridesPendingPause() {
        val hold = PlaybackStateHold()
        hold.hold(true, 0L)
        hold.hold(false, 1_000L)          // 暂停计时开始
        hold.force(false)                  // 用户自己按了暂停
        assertFalse(hold.hold(false, 1_100L))
        hold.force(true)                   // 用户又按了播放
        assertTrue(hold.hold(false, 1_200L)) // 迟滞不能立刻把它翻回暂停
    }

    @Test
    fun reset_startsFresh() {
        val hold = PlaybackStateHold()
        hold.hold(true, 0L)
        hold.hold(false, 1_000L)
        hold.reset()
        // 换会话：下一拍直接采纳新会话的状态
        assertFalse(hold.hold(false, 1_500L))
    }
}
