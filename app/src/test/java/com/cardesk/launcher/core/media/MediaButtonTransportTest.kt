package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第八十七轮：网易云定向控制规则（[MediaButtonTransport]）的纯逻辑单测。
 *
 * 覆盖契约要求的三块：包名判定、按键映射、capability 覆盖（含「不 setActions 的播放器」）。
 */
class MediaButtonTransportTest {

    // ------------------------------------------------------------ 包名判定

    @Test
    fun isNetease_onlyMatchesCarIotPackage() {
        assertTrue(MediaButtonTransport.isNetease("com.netease.cloudmusic.iot"))
        assertTrue(MediaButtonTransport.isNetease(" com.netease.cloudmusic.iot ")) // 容错空格
        // 手机版不是车机版：定向按键行为不同，不能混进来
        assertFalse(MediaButtonTransport.isNetease("com.netease.cloudmusic"))
        assertFalse(MediaButtonTransport.isNetease("com.tencent.qqmusiccar"))
        assertFalse(MediaButtonTransport.isNetease(null))
        assertFalse(MediaButtonTransport.isNetease(""))
    }

    // ------------------------------------------------------------ 按键映射

    @Test
    fun keyFor_togglePlayPicksKeyByState() {
        // 在放 → PAUSE；不在放 → PLAY。绝不能用 PLAY_PAUSE（会把暂停切成播放）
        assertEquals(
            MediaButtonTransport.KEYCODE_MEDIA_PAUSE,
            MediaButtonTransport.keyFor(MediaButtonTransport.ACTION_TOGGLE_PLAY, playing = true),
        )
        assertEquals(
            MediaButtonTransport.KEYCODE_MEDIA_PLAY,
            MediaButtonTransport.keyFor(MediaButtonTransport.ACTION_TOGGLE_PLAY, playing = false),
        )
    }

    @Test
    fun keyFor_mapsExplicitActions() {
        assertEquals(
            MediaButtonTransport.KEYCODE_MEDIA_PLAY,
            MediaButtonTransport.keyFor(MediaButtonTransport.ACTION_PLAY, playing = false),
        )
        assertEquals(
            MediaButtonTransport.KEYCODE_MEDIA_PAUSE,
            MediaButtonTransport.keyFor(MediaButtonTransport.ACTION_PAUSE, playing = true),
        )
        assertEquals(
            MediaButtonTransport.KEYCODE_MEDIA_NEXT,
            MediaButtonTransport.keyFor(MediaButtonTransport.ACTION_NEXT, playing = false),
        )
        assertEquals(
            MediaButtonTransport.KEYCODE_MEDIA_PREVIOUS,
            MediaButtonTransport.keyFor(MediaButtonTransport.ACTION_PREVIOUS, playing = false),
        )
        // 数值与 AOSP KeyEvent 对齐（日志里能直接对照按键码）
        assertEquals(126, MediaButtonTransport.KEYCODE_MEDIA_PLAY)
        assertEquals(127, MediaButtonTransport.KEYCODE_MEDIA_PAUSE)
        assertEquals(87, MediaButtonTransport.KEYCODE_MEDIA_NEXT)
        assertEquals(88, MediaButtonTransport.KEYCODE_MEDIA_PREVIOUS)
    }

    @Test
    fun keyFor_seekAndUnknownHaveNoKey() {
        // seek 不走按键（网易云的 onSeekTo 直接收毫秒）
        assertEquals(
            MediaButtonTransport.KEYCODE_UNKNOWN,
            MediaButtonTransport.keyFor(MediaButtonTransport.ACTION_SEEK, playing = true),
        )
        assertEquals(
            MediaButtonTransport.KEYCODE_UNKNOWN,
            MediaButtonTransport.keyFor("no-such-action", playing = true),
        )
    }

    // ------------------------------------------------------------ capability

    @Test
    fun capability_neteaseIgnoresActionBits() {
        // 网易云 getActions() 恒为 0，但带 FLAG_HANDLES_MEDIA_BUTTONS → 三个控制都能用
        val caps = MediaButtonTransport.capability(
            MediaButtonTransport.NETEASE_PACKAGE,
            actions = 0L,
            hasMediaButtonsFlag = true,
        )
        assertTrue(caps.canToggle)
        assertTrue(caps.canNext)
        assertTrue(caps.canPrevious)
        assertTrue(caps.canSeek)
    }

    @Test
    fun capability_neteaseWithoutMediaButtonFlagOnlySeek() {
        // 没有 FLAG_HANDLES_MEDIA_BUTTONS 时按键派发不进去 → 只剩进度条
        val caps = MediaButtonTransport.capability(
            MediaButtonTransport.NETEASE_PACKAGE,
            actions = 0L,
            hasMediaButtonsFlag = false,
        )
        assertFalse(caps.canToggle)
        assertFalse(caps.canNext)
        assertFalse(caps.canPrevious)
        assertTrue(caps.canSeek)
    }

    @Test
    fun capability_otherPlayersUseActionBits() {
        val playOnly = MediaButtonTransport.capability(
            "com.tencent.qqmusiccar",
            TransportIntent.ACTION_PLAY.toLong(),
            hasMediaButtonsFlag = true,
        )
        assertTrue(playOnly.canToggle)
        assertFalse(playOnly.canNext)
        assertFalse(playOnly.canPrevious)
        assertFalse(playOnly.canSeek)

        val everything = MediaButtonTransport.capability(
            "com.tencent.qqmusiccar",
            TransportIntent.ACTION_PLAY or
                TransportIntent.ACTION_PAUSE or
                TransportIntent.ACTION_SKIP_TO_NEXT or
                TransportIntent.ACTION_SKIP_TO_PREVIOUS or
                TransportIntent.ACTION_SEEK_TO,
            hasMediaButtonsFlag = true,
        )
        assertTrue(everything.canToggle)
        assertTrue(everything.canNext)
        assertTrue(everything.canPrevious)
        assertTrue(everything.canSeek)

        val nothing = MediaButtonTransport.capability("com.kugou.android.auto", 0L, false)
        assertFalse(nothing.canToggle)
        assertFalse(nothing.canNext)
        assertFalse(nothing.canPrevious)
        assertFalse(nothing.canSeek)
    }

    // ------------------------------------------------------------ 与幂等决策的配合

    /**
     * M1 与 M2 的接缝：网易云 actions=0 直接喂 [TransportIntent.decide] 会判 no-action，
     * 补上有效 action 位之后就能正常下发 —— 否则「按了没反应」会一直存在。
     */
    @Test
    fun effectiveActions_unblocksNeteaseDecision() {
        val pkg = MediaButtonTransport.NETEASE_PACKAGE
        val raw = 0L
        val blocked = TransportIntent.decide(TransportIntent.STATE_PAUSED, true, raw)
        assertFalse(blocked.send)
        assertEquals(TransportIntent.REASON_NO_ACTION, blocked.reason)

        val effective = MediaButtonTransport.effectiveActions(
            pkg,
            raw,
            MediaButtonTransport.FLAG_HANDLES_MEDIA_BUTTONS,
        )
        val allowed = TransportIntent.decide(TransportIntent.STATE_PAUSED, true, effective)
        assertTrue(allowed.send)
        assertEquals(TransportIntent.REASON_SEND_PLAY, allowed.reason)
    }

    /**
     * 推广后的通用补位（Lead 第八十七轮复核要求）：**原厂音源**常见
     * 「实现了 transport 回调、但不 setActions()」—— `actions == 0` 且带
     * `FLAG_HANDLES_TRANSPORT_CONTROLS`。老实现无条件 `pauseTransport()`，
     * 若这里被判 no-action，就是「按暂停没反应」的回归，必须补上。
     */
    @Test
    fun effectiveActions_fillsToggleForTransportOnlyPlayer() {
        val pkg = "ecarx.xsf.mediacenter"
        val flags = MediaButtonTransport.FLAG_HANDLES_TRANSPORT_CONTROLS
        val effective = MediaButtonTransport.effectiveActions(pkg, 0L, flags)
        assertEquals(
            TransportIntent.ACTION_PLAY or TransportIntent.ACTION_PAUSE,
            effective,
        )

        val pause = TransportIntent.decide(TransportIntent.STATE_PLAYING, false, effective)
        assertTrue(pause.send)
        assertEquals(TransportIntent.REASON_SEND_PAUSE, pause.reason)

        val play = TransportIntent.decide(TransportIntent.STATE_PAUSED, true, effective)
        assertTrue(play.send)
        assertEquals(TransportIntent.REASON_SEND_PLAY, play.reason)
    }

    /** `actions == 0` 且什么标志都没有：这种真发不进去，必须保持 no-action（别乱发）。 */
    @Test
    fun effectiveActions_noFlagsStaysNoAction() {
        val effective = MediaButtonTransport.effectiveActions("com.kugou.android.auto", 0L, 0L)
        assertEquals(0L, effective)
        assertFalse(TransportIntent.decide(TransportIntent.STATE_PLAYING, false, effective).send)
        assertFalse(TransportIntent.decide(TransportIntent.STATE_PAUSED, true, effective).send)
        assertEquals(
            TransportIntent.REASON_NO_ACTION,
            TransportIntent.decide(TransportIntent.STATE_PAUSED, true, effective).reason,
        )
    }

    /** 补位只补 `PLAY|PAUSE`：切歌 / 拖进度位绝不能凭空出现（否则「下一首」会从可用变误发）。 */
    @Test
    fun effectiveActions_neverInventsSkipOrSeekBits() {
        val flags = MediaButtonTransport.FLAG_HANDLES_TRANSPORT_CONTROLS or
            MediaButtonTransport.FLAG_HANDLES_MEDIA_BUTTONS
        val effective = MediaButtonTransport.effectiveActions("ecarx.xsf.mediacenter", 0L, flags)
        assertEquals(0L, effective and TransportIntent.ACTION_SKIP_TO_NEXT)
        assertEquals(0L, effective and TransportIntent.ACTION_SKIP_TO_PREVIOUS)
        assertEquals(0L, effective and TransportIntent.ACTION_SEEK_TO)
    }

    /** 有 action 位的普通播放器：行为完全不变（补位不介入）。 */
    @Test
    fun effectiveActions_keepsDeclaredActionBitsForNormalPlayers() {
        val declared = TransportIntent.ACTION_PLAY or
            TransportIntent.ACTION_PAUSE or
            TransportIntent.ACTION_SKIP_TO_NEXT
        val flags = MediaButtonTransport.FLAG_HANDLES_TRANSPORT_CONTROLS
        assertEquals(
            declared,
            MediaButtonTransport.effectiveActions("com.tencent.qqmusiccar", declared, flags),
        )
        // 在放时想「播放」→ 已满足，不发；想「暂停」→ 发
        assertFalse(TransportIntent.decide(TransportIntent.STATE_PLAYING, true, declared).send)
        assertTrue(TransportIntent.decide(TransportIntent.STATE_PLAYING, false, declared).send)
    }

    /** 网易云但没有 media-button / transport 标志：补了也发不进去，不要硬补。 */
    @Test
    fun effectiveActions_neteaseWithoutAnyFlagStaysBlocked() {
        assertEquals(
            0L,
            MediaButtonTransport.effectiveActions(MediaButtonTransport.NETEASE_PACKAGE, 0L, 0L),
        )
    }

    /** flags 位判定（供 tracker 算 buttons / transport 两个通道）。 */
    @Test
    fun flagHelpers_matchAospBits() {
        assertEquals(1L, MediaButtonTransport.FLAG_HANDLES_MEDIA_BUTTONS)
        assertEquals(2L, MediaButtonTransport.FLAG_HANDLES_TRANSPORT_CONTROLS)
        assertTrue(MediaButtonTransport.canMediaButtons(1L))
        assertFalse(MediaButtonTransport.canMediaButtons(2L))
        assertTrue(MediaButtonTransport.canTransport(2L))
        assertFalse(MediaButtonTransport.canTransport(1L))
        assertFalse(MediaButtonTransport.canMediaButtons(0L))
        assertFalse(MediaButtonTransport.canTransport(0L))
    }

    @Test
    fun shouldDispatchMediaButton_onlyNeteaseWithFlag() {
        assertTrue(
            MediaButtonTransport.shouldDispatchMediaButton(MediaButtonTransport.NETEASE_PACKAGE, true),
        )
        assertFalse(
            MediaButtonTransport.shouldDispatchMediaButton(MediaButtonTransport.NETEASE_PACKAGE, false),
        )
        assertFalse(
            MediaButtonTransport.shouldDispatchMediaButton("com.tencent.qqmusiccar", true),
        )
        assertFalse(MediaButtonTransport.shouldDispatchMediaButton(null, true))
    }
}
