package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第八十七轮：幂等播放/暂停决策（[TransportIntent]）的纯逻辑单测。
 *
 * 覆盖契约要求的四条：连点两次期望最终一致、BUFFERING 判定、缺 action 位、窗口过期。
 */
class TransportIntentTest {

    private val toggleActions =
        TransportIntent.ACTION_PLAY or TransportIntent.ACTION_PAUSE or TransportIntent.ACTION_PLAY_PAUSE

    // ------------------------------------------------------------ 已满足（幂等）

    @Test
    fun decide_playingAndDesiredPlaying_isAlreadySatisfied() {
        val d = TransportIntent.decide(TransportIntent.STATE_PLAYING, true, toggleActions)
        assertFalse(d.send)
        assertTrue(d.desiredPlaying)
        assertEquals(TransportIntent.REASON_ALREADY_SATISFIED, d.reason)
    }

    @Test
    fun decide_pausedAndDesiredPaused_isAlreadySatisfied() {
        val d = TransportIntent.decide(TransportIntent.STATE_PAUSED, false, toggleActions)
        assertFalse(d.send)
        assertFalse(d.desiredPlaying)
        assertEquals(TransportIntent.REASON_ALREADY_SATISFIED, d.reason)
    }

    /** 停止 / 未知状态按「没在放」算：用户按播放时应该真的下发。 */
    @Test
    fun decide_stoppedNoneAndDesiredPlaying_sends() {
        for (state in listOf(TransportIntent.STATE_STOPPED, TransportIntent.STATE_NONE, TransportIntent.STATE_ERROR)) {
            val d = TransportIntent.decide(state, true, toggleActions)
            assertTrue("state=$state 应该下发播放", d.send)
            assertEquals(TransportIntent.REASON_SEND_PLAY, d.reason)
        }
    }

    // ------------------------------------------------------------ BUFFERING 判定

    /**
     * 缓冲中算「在放」。
     *
     * 以前只看 `PLAYING`，缓冲时按播放键会被当成「暂停中」→ 按下反而继续播放（真机现象）。
     */
    @Test
    fun decide_bufferingCountsAsPlaying() {
        assertTrue(TransportIntent.isObservedPlaying(TransportIntent.STATE_BUFFERING))
        val stay = TransportIntent.decide(TransportIntent.STATE_BUFFERING, true, toggleActions)
        assertFalse(stay.send)
        assertEquals(TransportIntent.REASON_ALREADY_SATISFIED, stay.reason)

        val pause = TransportIntent.decide(TransportIntent.STATE_BUFFERING, false, toggleActions)
        assertTrue(pause.send)
        assertEquals(TransportIntent.REASON_SEND_PAUSE, pause.reason)
    }

    // ------------------------------------------------------------ 缺 action 位

    @Test
    fun decide_missingActionBits_isNoAction() {
        // 网易云这类播放器 actions 恒为 0，直接喂进来就是 no-action
        assertEquals(
            TransportIntent.REASON_NO_ACTION,
            TransportIntent.decide(TransportIntent.STATE_PAUSED, true, 0L).reason,
        )
        assertEquals(
            TransportIntent.REASON_NO_ACTION,
            TransportIntent.decide(TransportIntent.STATE_PLAYING, false, 0L).reason,
        )
        // 只有 PLAY 没有 PAUSE：播放能发、暂停不能发
        val onlyPlay = TransportIntent.ACTION_PLAY.toLong()
        assertTrue(TransportIntent.decide(TransportIntent.STATE_PAUSED, true, onlyPlay).send)
        assertFalse(TransportIntent.decide(TransportIntent.STATE_PLAYING, false, onlyPlay).send)
        // PLAY_PAUSE 一个位两个方向都算
        val onlyToggle = TransportIntent.ACTION_PLAY_PAUSE.toLong()
        assertTrue(TransportIntent.decide(TransportIntent.STATE_PAUSED, true, onlyToggle).send)
        assertTrue(TransportIntent.decide(TransportIntent.STATE_PLAYING, false, onlyToggle).send)
    }

    // ------------------------------------------------------------ 连点仲裁

    /**
     * 契约验收：**连点两次期望最终一致** —— 第二次的期望等于第一次下发的期望。
     *
     * 场景：暂停中按第一下（下发播放）→ 300ms 后会话还没把状态报上来，用户又按一下。
     * 第二次不能「按实测的暂停再翻转成播放」以外的结果，也不能因为实测没变就再翻转一次。
     */
    @Test
    fun nextToggleTarget_doubleTapKeepsFirstIntent() {
        val firstPressAt = 1_000L
        // 第一次：实测暂停 → 期望播放
        val first = TransportIntent.nextToggleTarget(false, 0L, firstPressAt)
        assertTrue(first)
        // 下发后锚点 = 第一次下发时刻
        val userIntentAt = firstPressAt
        // 第二次 300ms 后：窗口内不翻转，仍然是「播放」
        val second = TransportIntent.nextToggleTarget(true, userIntentAt, firstPressAt + 300L)
        assertEquals(first, second)

        // 会话追上（PLAYING）之后再看：第一次的期望已经满足，第二次什么都不用发
        val afterFirst = TransportIntent.decide(TransportIntent.STATE_PLAYING, first, toggleActions)
        val afterSecond = TransportIntent.decide(TransportIntent.STATE_PLAYING, second, toggleActions)
        assertFalse(afterFirst.send)
        assertFalse(afterSecond.send)
        assertEquals(afterFirst.desiredPlaying, afterSecond.desiredPlaying)
    }

    /** 窗口**过期**的第二次点击是明确意图：翻转回原状态（这里是暂停）。 */
    @Test
    fun nextToggleTarget_windowExpiredFlips() {
        val userIntentAt = 1_000L
        val inWindow = TransportIntent.nextToggleTarget(true, userIntentAt, userIntentAt + 800L)
        assertTrue("窗口边界内不翻转", inWindow)
        val outWindow = TransportIntent.nextToggleTarget(true, userIntentAt, userIntentAt + 801L)
        assertFalse("窗口外以实测为准，翻转成暂停", outWindow)
    }

    @Test
    fun nextToggleTarget_neverSentFlips() {
        // 从没下发过（锚点 0）→ 正常翻转
        assertTrue(TransportIntent.nextToggleTarget(false, 0L, 5_000L))
        assertFalse(TransportIntent.nextToggleTarget(true, 0L, 5_000L))
    }

    /** 时钟回拨（now < 锚点）不能把键锁死：按窗口外处理。 */
    @Test
    fun nextToggleTarget_clockGoingBackwardsFlips() {
        assertFalse(TransportIntent.nextToggleTarget(true, 2_000L, 1_000L))
    }

    /** 自定义窗口（调用方想收紧 / 放宽时）。 */
    @Test
    fun nextToggleTarget_customWindow() {
        assertTrue(TransportIntent.nextToggleTarget(true, 1_000L, 1_200L, windowMs = 300L))
        assertFalse(TransportIntent.nextToggleTarget(true, 1_000L, 1_500L, windowMs = 300L))
    }

    // ------------------------------------------- 意图窗口 + 实测滞后的仲裁（force）

    /**
     * 第八十八轮语义：唯一被跳过的是「实测已满足」；**缺少 action 位不再静默**。
     *
     * 真车教训：车机音乐（`com.tencent.wecarflow`）的 `PlaybackState` 常常不声明
     * `ACTION_PLAY`，第八十七轮的硬闸门把用户明确按下的播放整条吞掉 → 「按播放没反应」。
     */
    @Test
    fun shouldDispatch_onlyAlreadySatisfiedIsSkipped() {
        val satisfied = TransportIntent.decide(TransportIntent.STATE_PLAYING, true, toggleActions)
        assertEquals(TransportIntent.REASON_ALREADY_SATISFIED, satisfied.reason)
        assertFalse("不加 force 时已满足不下发", TransportIntent.shouldDispatch(satisfied, false))
        assertTrue("force 越过已满足", TransportIntent.shouldDispatch(satisfied, true))

        // 播放器没声明能力位：命令照发（用户明确按了键，且走的是定向通道）
        val noAction = TransportIntent.decide(TransportIntent.STATE_PLAYING, false, 0L)
        assertEquals(TransportIntent.REASON_NO_ACTION, noAction.reason)
        assertTrue("缺 action 位不再吞掉用户操作", TransportIntent.shouldDispatch(noAction, false))
        assertTrue(TransportIntent.shouldDispatch(noAction, true))

        // 正常需要下发的，force 与否都一样
        val send = TransportIntent.decide(TransportIntent.STATE_PAUSED, true, toggleActions)
        assertTrue(TransportIntent.shouldDispatch(send, false))
        assertTrue(TransportIntent.shouldDispatch(send, true))
    }

    // ------------------------------------------- 第八十八轮：以界面显示态为切换基准

    /**
     * 真车核心场景：暂停后会话 `PlaybackState` 仍报 PLAYING（`stop()` 落地晚），
     * 此时按「播放」必须以界面为准翻成播放，并且强推越过 `already-satisfied`。
     */
    @Test
    fun toggleFromDisplayed_staleSessionStillResumes() {
        val t = TransportIntent.toggleFromDisplayed(
            displayedPlaying = false,
            observedPlaying = true,
            userIntentAtMs = 1_000L,
            nowMs = 30_000L, // 意图窗口早已过期
        )
        assertFalse(t.coalesced)
        assertTrue("按播放必须翻成播放，而不是再发一次暂停", t.desiredPlaying)
        assertTrue("实测等于目标态 → 必须强推", t.force)
        val decision = TransportIntent.decide(TransportIntent.STATE_PLAYING, t.desiredPlaying, toggleActions)
        assertEquals(TransportIntent.REASON_ALREADY_SATISFIED, decision.reason)
        assertTrue("强推后仍然下发", TransportIntent.shouldDispatch(decision, t.force))
    }

    /** 界面与实测一致时就是普通翻转，不需要 force。 */
    @Test
    fun toggleFromDisplayed_normalFlipDoesNotForce() {
        val toPause = TransportIntent.toggleFromDisplayed(true, true, 0L, 5_000L)
        assertFalse(toPause.coalesced)
        assertFalse(toPause.desiredPlaying)
        assertFalse(toPause.force)

        val toPlay = TransportIntent.toggleFromDisplayed(false, false, 0L, 5_000L)
        assertFalse(toPlay.coalesced)
        assertTrue(toPlay.desiredPlaying)
        assertFalse(toPlay.force)
    }

    /** 800ms 内的重复触摸不翻转（沿用界面当前态），边界值 800 算连点、801 算新按键。 */
    @Test
    fun toggleFromDisplayed_windowCoalescesRepeatTaps() {
        val inside = TransportIntent.toggleFromDisplayed(false, false, 1_000L, 1_500L)
        assertTrue(inside.coalesced)
        assertFalse(inside.desiredPlaying)
        assertFalse(inside.force)

        assertTrue(TransportIntent.toggleFromDisplayed(false, false, 1_000L, 1_800L).coalesced)
        assertFalse(TransportIntent.toggleFromDisplayed(false, false, 1_000L, 1_801L).coalesced)
    }

    /** 从没下发过（`0`）/ 时钟倒退：都按「新按键」处理。 */
    @Test
    fun toggleFromDisplayed_neverSentOrClockBackwardsFlips() {
        val never = TransportIntent.toggleFromDisplayed(true, true, 0L, 100L)
        assertFalse(never.coalesced)
        assertFalse(never.desiredPlaying)

        val backwards = TransportIntent.toggleFromDisplayed(true, true, 2_000L, 1_000L)
        assertFalse(backwards.coalesced)
        assertFalse(backwards.desiredPlaying)
    }

    /**
     * 三次连点 `pause → pause → play`：前两次都下发暂停（实测仍 PLAYING、命令在路上），
     * 第三次目标翻成播放 —— 必须 force 下发，否则被落后的实测值吞掉，最终停在暂停。
     */
    @Test
    fun forceRescuesThirdTapAfterPauseInFlight() {
        val actions = toggleActions
        var observed = TransportIntent.STATE_PLAYING // 会话上报滞后，仍是播放
        var lastDispatchAt = 0L

        // 第 1 次：实测 PLAYING → 目标暂停 → 下发
        val desired1 = TransportIntent.nextToggleTarget(true, lastDispatchAt, 1_000L)
        assertFalse(desired1)
        assertTrue(TransportIntent.shouldDispatch(TransportIntent.decide(observed, desired1, actions), false))
        lastDispatchAt = 1_000L

        // 第 2 次（+300ms，窗口内）：不翻转，仍是暂停 → 再下发一次暂停（同向重复，无害）
        val desired2 = TransportIntent.nextToggleTarget(false, lastDispatchAt, 1_300L)
        assertFalse(desired2)
        assertTrue(TransportIntent.shouldDispatch(TransportIntent.decide(observed, desired2, actions), false))
        lastDispatchAt = 1_300L

        // 第 3 次（+1s，窗口外）：实测仍滞后报 PLAYING，但用户要播放
        val desired3 = TransportIntent.nextToggleTarget(false, lastDispatchAt, 2_300L)
        assertTrue(desired3)
        val decision = TransportIntent.decide(observed, desired3, actions)
        assertEquals(TransportIntent.REASON_ALREADY_SATISFIED, decision.reason)
        val lastDispatchedTarget = desired2 // 上一次用户实际下发的方向：暂停
        val force = desired3 != lastDispatchedTarget // 意图=暂停、目标=播放 → 方向变了
        assertTrue("方向变了必须 force 下发", TransportIntent.shouldDispatch(decision, force))
    }
}
