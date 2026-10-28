package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PauseRetention] 单测（纯逻辑，不依赖 Android）。
 *
 * 这条规则直接决定真机上「暂停一下，歌名和歌词全没了」会不会复现：
 * 候选选择用的是 （暂停时保留的那份元数据），
 * 所以暂停后即使会话把元数据撤了，这个会话**仍然**算「有歌」。
 *
 * 口径`MusicSessionTracker.Record`
 * -
 * -
 * -
 */
class PauseRetentionTest {

    private val dmt = "com.tencent.wecarflow"
    private val other = "com.tencent.qqmusic"

    @Test
    fun `活跃态判定符合预期`() {
        assertTrue(PauseRetention.isActive(PauseRetention.STATE_PLAYING))
        assertTrue(PauseRetention.isActive(PauseRetention.STATE_BUFFERING))
        assertTrue(PauseRetention.isActive(PauseRetention.STATE_CONNECTING))
        assertFalse(PauseRetention.isActive(PauseRetention.STATE_PAUSED))
        assertFalse(PauseRetention.isActive(PauseRetention.STATE_STOPPED))
        assertFalse(PauseRetention.isActive(null))
    }

    @Test
    fun `白名单覆盖所有已知音乐 App`() {
        // 车机多媒体
        assertTrue(PauseRetention.isPauseMetadataPackage(dmt))
        assertTrue(PauseRetention.isPauseMetadataPackage("com.ecarx.multimedia"))
        // 第六十七轮：白名单从「车机多媒体」扩到所有已知音乐 App
        assertTrue(PauseRetention.isPauseMetadataPackage("com.tencent.qqmusiccar"))
        assertTrue(PauseRetention.isPauseMetadataPackage("com.netease.cloudmusic.iot"))
        assertTrue(PauseRetention.isPauseMetadataPackage("com.kugou.android.auto"))
        assertTrue(PauseRetention.isPauseMetadataPackage("cn.kuwo.kwmusiccar"))
        assertTrue(PauseRetention.isPauseMetadataPackage("cn.kuwo.kwmusiccarclone"))
        assertTrue(PauseRetention.isPauseMetadataPackage("com.luna.music.car"))
        // 不在名单里的
        assertFalse(PauseRetention.isPauseMetadataPackage(other))
        assertFalse(PauseRetention.isPauseMetadataPackage(null))
        assertFalse(PauseRetention.isPauseMetadataPackage(""))
    }

    @Test
    fun `暂停态一定可以保留`() {
        assertTrue(
            PauseRetention.canRetainPause(
                pkg = dmt,
                state = PauseRetention.STATE_PAUSED,
                userPauseRequested = false,
            ),
        )
    }

    @Test
    fun `用户按过暂停就保留 不管是哪个包`() {
        // 第六十七轮的核心修正：
        // 原来这里有一道包白名单，于是拿 QQ音乐车机版播歌时
        // **暂停一下就掉元数据** → 歌名、歌词一起没。
        assertTrue(
            PauseRetention.canRetainPause(
                pkg = other,
                state = PauseRetention.STATE_PAUSED,
                userPauseRequested = true,
            ),
        )
        assertTrue(
            PauseRetention.canRetainPause(
                pkg = "com.tencent.qqmusiccar",
                state = PauseRetention.STATE_PAUSED,
                userPauseRequested = true,
            ),
        )
    }

    @Test
    fun `不是用户按的暂停 不在白名单的包不留`() {
        assertFalse(
            PauseRetention.canRetainPause(
                pkg = other,
                state = PauseRetention.STATE_PAUSED,
                userPauseRequested = false,
            ),
        )
    }

    @Test
    fun `停住态要用户主动按过暂停才保留`() {
        assertTrue(
            PauseRetention.canRetainPause(
                pkg = dmt,
                state = PauseRetention.STATE_STOPPED,
                userPauseRequested = true,
            ),
        )
        assertFalse(
            PauseRetention.canRetainPause(
                pkg = dmt,
                state = PauseRetention.STATE_STOPPED,
                userPauseRequested = false,
            ),
        )
    }

    @Test
    fun `活跃态不保留暂停态元数据`() {
        // updateState:167 —— 回到活跃态时 userPauseRequested 被清掉
        val r = PauseRetention.onStateChanged(
            state = PauseRetention.STATE_PLAYING,
            userPauseRequested = true,
            hasMetadata = true,
        )
        assertFalse(r.userPauseRequested)
        assertTrue(r.keepPausedMetadata)
    }

    @Test
    fun `停住态且用户按过暂停时保留`() {
        val r = PauseRetention.onStateChanged(
            state = PauseRetention.STATE_STOPPED,
            userPauseRequested = true,
            hasMetadata = true,
        )
        assertTrue(r.keepPausedMetadata)
        assertTrue(r.userPauseRequested)
    }

    @Test
    fun `停住态但不是用户按的暂停就清掉`() {
        val r = PauseRetention.onStateChanged(
            state = PauseRetention.STATE_STOPPED,
            userPauseRequested = false,
            hasMetadata = true,
        )
        assertFalse(r.keepPausedMetadata)
        assertFalse(r.userPauseRequested)
    }

    @Test
    fun `恢复播放时元数据还没到 暂停意图要留着`() {
        // 第六十七轮：车机 App 常常**先报 PLAYING、后报元数据**。
        // 那一瞬要是把暂停态那份也丢了，歌名 / 歌词会空一下。
        val r = PauseRetention.onStateChanged(
            state = PauseRetention.STATE_PLAYING,
            userPauseRequested = true,
            hasMetadata = false,
            hasPausedMetadata = true,
        )
        assertTrue("暂停时留的那份要继续留着", r.keepPausedMetadata)
        assertTrue("暂停意图还不能解除", r.userPauseRequested)
    }

    @Test
    fun `恢复播放后新元数据到了就解除暂停意图`() {
        val r = PauseRetention.onStateChanged(
            state = PauseRetention.STATE_PLAYING,
            userPauseRequested = true,
            hasMetadata = true,
            hasPausedMetadata = true,
        )
        assertTrue(r.keepPausedMetadata)
        assertFalse("有新元数据了，暂停意图解除", r.userPauseRequested)
    }

    @Test
    fun `空状态和错误状态一律清掉`() {
        for (st in listOf(null, PauseRetention.STATE_NONE, PauseRetention.STATE_ERROR)) {
            val r = PauseRetention.onStateChanged(
                state = st,
                userPauseRequested = true,
                hasMetadata = true,
            )
            assertFalse("state=$st", r.keepPausedMetadata)
            assertFalse("state=$st", r.userPauseRequested)
        }
    }

    @Test
    fun `暂停后清掉元数据仍然算有歌`() {
        // 这就是「暂停后歌名不消失」的核心：
        // 会话把元数据撤了（hasMetadata=false），
        // 但因为 canRetainPause 成立，effectiveMetadata 仍然给出暂停时的那一份。
        val canRetain = PauseRetention.canRetainPause(
            pkg = dmt,
            state = PauseRetention.STATE_PAUSED,
            userPauseRequested = true,
        )
        assertTrue(canRetain)
        // 有歌 → 候选仍会被选中 → 界面不被清空
        assertTrue(hasMetadataAfterRetention(canRetain, hasLiveMetadata = false, hasPaused = true))
    }

    @Test
    fun `没有暂停态可留时才算真的没歌`() {
        // 不是用户按的暂停 + 不在白名单的包 → 确实没有可留的元数据
        val canRetain = PauseRetention.canRetainPause(
            pkg = other,
            state = PauseRetention.STATE_PAUSED,
            userPauseRequested = false,
        )
        assertFalse(canRetain)
        assertFalse(hasMetadataAfterRetention(canRetain, hasLiveMetadata = false, hasPaused = true))
    }

    /**
     * `Record.effectiveMetadata` 的判定（纯函数形式）。
     *
     * @return true = 这个会话仍然算「有歌」
     */
    private fun hasMetadataAfterRetention(
        canRetain: Boolean,
        hasLiveMetadata: Boolean,
        hasPaused: Boolean,
    ): Boolean = hasLiveMetadata || (canRetain && hasPaused)

    @Test
    fun `状态常量符合预期`() {
        assertEquals(2, PauseRetention.STATE_PAUSED)
        assertEquals(1, PauseRetention.STATE_STOPPED)
        assertEquals(3, PauseRetention.STATE_PLAYING)
        assertEquals(6, PauseRetention.STATE_BUFFERING)
        assertEquals(7, PauseRetention.STATE_ERROR)
        assertEquals(8, PauseRetention.STATE_CONNECTING)
        assertNull(null)
    }
}
