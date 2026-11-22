package com.cardesk.launcher.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「是不是真的在放」判定（第202轮，见 [SessionAudibility]）。
 *
 * 样本取自真车诊断包里的僵尸会话：状态每 4 秒翻一次、位置一动不动。
 */
class SessionAudibilityTest {

    private val window = SessionAudibility.WINDOW_MS

    private fun sample(playing: Boolean, pos: Long, now: Long): SessionAudibility.Sample =
        SessionAudibility.next(playing, pos, SessionAudibility.Sample(), now)

    @Test
    fun firstPlayingSample_isAudible() {
        assertTrue(sample(playing = true, pos = 16044, now = 1_000).audible)
    }

    @Test
    fun advancingPosition_staysAudible() {
        var s = SessionAudibility.Sample()
        var pos = 16044L
        var now = 1_000L
        repeat(6) {
            pos += 2_500
            now += 2_500
            s = SessionAudibility.next(playing = true, positionMs = pos, prev = s, nowMs = now)
            assertTrue("第 $it 次采样应仍在放", s.audible)
        }
    }

    @Test
    fun frozenPosition_goesInaudibleAfterWindow() {
        var s = sample(playing = true, pos = 16044, now = 1_000)
        s = SessionAudibility.next(true, 16044, s, 1_000 + window)
        assertTrue("宽限期内仍算在放", s.audible)
        s = SessionAudibility.next(true, 16044, s, 1_000 + window + 2_500)
        assertFalse("超过窗口仍在原位置 → 其实没在放", s.audible)
    }

    @Test
    fun pause_makesInaudibleImmediately() {
        val s = SessionAudibility.next(false, 16044, SessionAudibility.Sample(), 5_000)
        assertFalse(s.audible)
    }

    @Test
    fun resumeAfterLongPause_getsGrace() {
        // 1s 时在放，5s 时暂停，60s 时恢复 → 停了 55s，应重新给宽限
        var s = sample(playing = true, 1000, now = 1_000)
        s = SessionAudibility.next(false, 1000, s, 5_000)
        s = SessionAudibility.next(true, 1000, s, 60_000)
        assertTrue("长时间暂停后恢复，第一拍应算在放", s.audible)
    }

    /** 真车 wecarflow：PLAYING/PAUSED 每 4 秒翻一次，位置永远不推进。 */
    @Test
    fun flappingZombieSession_endsInaudibleAndStaysThere() {
        var s = SessionAudibility.Sample()
        var now = 1_000L
        var playing = true
        // 前 30 秒按 4 秒一翻
        repeat(8) {
            s = SessionAudibility.next(playing, 16044, s, now)
            now += 4_000
            playing = !playing
        }
        assertFalse("翻完 30 秒，位置没动过 → 判定没在放", s.audible)

        // 再翻 20 秒也不该恢复（暂停时长永远够不到 RESUME_GAP_MS）
        repeat(5) {
            s = SessionAudibility.next(playing, 16044, s, now)
            assertFalse(s.audible)
            now += 4_000
            playing = !playing
        }
    }

    @Test
    fun unknownPosition_isTreatedAsPlaying() {
        val s = SessionAudibility.next(true, -1L, SessionAudibility.Sample(), 10_000)
        assertTrue(s.audible)
    }

    // ---------------------------------------------------------------- 第203轮：音频输出

    @Test
    fun outputtingTrue_isAudibleEvenWhenPositionFrozen() {
        // 网易云真车现象：在放，但 position 停在旧值 4 秒不动。
        var s = SessionAudibility.Sample(lastPositionMs = 174, lastPlayingAtMs = 1_000, lastAdvanceAtMs = 1_000, audible = true)
        s = SessionAudibility.next(true, 174, s, 2_000)
        s = SessionAudibility.next(true, 174, s, 6_000, outputting = true)
        assertTrue("系统说它正在出声，位置冻住也算在放", s.audible)
    }

    @Test
    fun outputtingFalse_isInaudibleEvenWhenPositionAdvances() {
        var s = SessionAudibility.Sample(lastPositionMs = 1_000, lastPlayingAtMs = 1_000, lastAdvanceAtMs = 1_000, audible = true)
        s = SessionAudibility.next(true, 3_000, s, 2_000, outputting = false)
        assertFalse("没在出声就是没在放，位置在动也不算", s.audible)
    }

    @Test
    fun outputtingNull_fallsBackToPositionHeuristic() {
        // 探测不可用时保持第202轮的行为：位置推进 → 在放。
        var s = SessionAudibility.Sample(lastPositionMs = 1_000, lastPlayingAtMs = 1_000, lastAdvanceAtMs = 1_000, audible = true)
        s = SessionAudibility.next(true, 3_000, s, 2_000, outputting = null)
        assertTrue(s.audible)
        s = SessionAudibility.next(true, 3_000, s, 9_000, outputting = null)
        assertFalse("位置冻住超过窗口 → 没在放", s.audible)
    }
}
