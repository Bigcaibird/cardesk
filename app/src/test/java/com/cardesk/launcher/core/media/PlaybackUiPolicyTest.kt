package com.cardesk.launcher.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第九十七轮：真机诊断包（4183）里 wecarflow 缓冲时 PLAYING + position 卡 2.5s，
 * 老逻辑直接改判暂停 → 播放键图标来回跳。这里的口径是：
 * 短暂卡顿不算暂停，长时间停滞（STALL_UI_MS 以上）才算。
 */
class PlaybackUiPolicyTest {

    @Test
    fun playingWithoutStall_isPlaying() {
        assertTrue(PlaybackUiPolicy.uiPlaying(observedPlaying = true, stalledMs = 0L))
    }

    @Test
    fun shortBufferingStall_staysPlaying() {
        // 关键回归：2.5s 的缓冲卡顿在真机上很常见，不能把界面改成「暂停」
        assertTrue(PlaybackUiPolicy.uiPlaying(observedPlaying = true, stalledMs = 2_500L))
        assertTrue(PlaybackUiPolicy.uiPlaying(observedPlaying = true, stalledMs = 5_999L))
    }

    @Test
    fun longStall_isTreatedAsPaused() {
        assertFalse(PlaybackUiPolicy.uiPlaying(observedPlaying = true, stalledMs = PlaybackUiPolicy.STALL_UI_MS))
        assertFalse(PlaybackUiPolicy.uiPlaying(observedPlaying = true, stalledMs = 30_000L))
    }

    @Test
    fun pausedSession_isPausedRegardlessOfStall() {
        assertFalse(PlaybackUiPolicy.uiPlaying(observedPlaying = false, stalledMs = 0L))
        assertFalse(PlaybackUiPolicy.uiPlaying(observedPlaying = false, stalledMs = 2_500L))
    }

    @Test
    fun clearGrace_coversOneRescanWindow() {
        // 会话消失的宽限期必须覆盖 2.5s 周扫的一次空窗，否则界面会在
        // 「暂无播放」和歌名之间来回跳
        assertTrue(MusicController.NULL_SESSION_CLEAR_MS >= 2_500L)
    }
}
