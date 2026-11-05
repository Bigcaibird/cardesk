package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「会话消失」时的清理范围（第126轮，纯逻辑，可单测）。
 *
 * ## 背景
 *
 * QQ 音乐车机版**不发布可用的 MediaSession**（模拟器上 `dumpsys media_session` 实测：
 * `metadata: null` + `state=0`），它的歌名 / 歌词**只能**来自三方广播。
 *
 * 会话追踪器**每 2.5s 重扫一次**：QQ 音乐那两个会话没有 metadata → 选不中 →
 * 每 2.5s 发一次 `onTrackChanged(null)` → 3s 后触发清空。
 *
 * 老实现只有「会话」一个来源，清空 = 全清 → **卡片上的歌名和歌词每 3 秒跳一次**
 * （和第 97 轮修掉的「播放/暂停状态不断跳动」是同一个毛病）。
 */
class SessionClearPolicyTest {

    private fun track(title: String) = TrackInfo(
        pkg = QqBroadcast.PACKAGE,
        title = title,
        artist = "方大同",
        album = "十五",
        durationMs = 200_666L,
        positionMs = 1_000L,
        playing = true,
        sourceId = MediaSources.QQ_ID,
    )

    @Test
    fun `广播曲目还在时界面继续显示它`() {
        val t = track("春风吹")
        val r = SessionClearPolicy.afterSessionClear(broadcast = t, hasLyric = true)
        assertNotNull("广播那份还在，界面不该被清空", r.track)
        assertEquals("春风吹", r.track?.title)
    }

    @Test
    fun `广播曲目还在时歌词和封面都留着`() {
        val r = SessionClearPolicy.afterSessionClear(broadcast = track("春风吹"), hasLyric = true)
        assertTrue("歌词要留着", r.keepLyric)
        assertTrue("封面要留着（不然每 3 秒重取一次）", r.keepCover)
    }

    @Test
    fun `广播曲目没了就全清`() {
        val r = SessionClearPolicy.afterSessionClear(broadcast = null, hasLyric = true)
        assertNull("真没东西了就回到「暂无播放」", r.track)
        assertFalse("歌词要清", r.keepLyric)
        assertFalse("封面要清", r.keepCover)
    }

    @Test
    fun `广播曲目在但本来就没歌词时歌词标记为不清`() {
        // 有歌没词是常态（QQ 的 update_state 广播只带歌名）。
        val r = SessionClearPolicy.afterSessionClear(broadcast = track("春风吹"), hasLyric = false)
        assertNotNull(r.track)
        assertFalse("本来就没歌词，不算「保留」", r.keepLyric)
        assertTrue("封面照样留着", r.keepCover)
    }
}
