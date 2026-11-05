package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第127轮：网易云音乐车机版的**会话歌词协议**（[NeteaseSessionLyric]）纯逻辑单测。
 *
 * 协议出处：模拟器上装真的 `com.netease.cloudmusic.iot` 6.2.81（6002081），
 * `jadx` 反编译 `com/netease/cloudmusic/`：
 * - `vc/g$S(String)` → `builder.putString("android.media.metadata.LYRIC", lyric)`
 * - `vc/g$L(Integer, String)` → `I("wt_music_lyric", {mediaId, wt_music_lyric_obj, wt_music_lyric_state})`
 * - `vc/g$e.a(...)` → `state==2` 发 `lyric_message_action`；`state==5` 发 `lyric_loading_action`
 */
class NeteaseSessionLyricTest {

    // ------------------------------------------------------------ metadata 通道

    @Test
    fun lyricMetadataKey_isTheFullNetEaseKey() {
        // 短名取不到 —— `MediaMetadata.getText(key)` 是精确匹配
        assertEquals("android.media.metadata.LYRIC", NeteaseSessionLyric.LYRIC_METADATA_KEY)
        assertTrue(
            "网易云的歌词 key 必须在候选列表里，否则永远读不到",
            PlayerLyric.METADATA_LYRIC_KEYS.contains(NeteaseSessionLyric.LYRIC_METADATA_KEY),
        )
    }

    @Test
    fun playerLyric_findsNetEaseMetadataKey() {
        val lrc = "[ti:春风吹]\n[00:00.00]春风吹\n[00:05.00]春风吹花落"
        val found = PlayerLyric.findLrc(listOf(NeteaseSessionLyric.LYRIC_METADATA_KEY, lrc))
        assertNotNull(found)
        assertEquals(lrc, found)
    }

    // ------------------------------------------------------------ wt_music_lyric

    @Test
    fun lyricEvent_stateOneCarriesTheWholeLrc() {
        val lrc = "[00:00.00]春风吹\n[00:05.00]春风吹花落"
        val ev = NeteaseSessionLyric.parse(
            NeteaseSessionLyric.EVENT_LYRIC,
            mapOf(
                NeteaseSessionLyric.KEY_LYRIC_STATE to 1,
                NeteaseSessionLyric.KEY_LYRIC_OBJ to lrc,
            ),
        )
        assertNotNull(ev)
        assertEquals(lrc, ev!!.lrc)
        assertFalse(ev.cleared)
        assertTrue(ev.hasContent)
    }

    @Test
    fun lyricEvent_stateNotReadyMeansClear() {
        // state=2 是提示消息，state=5 是加载中 —— 两种都不是歌词
        for (state in listOf(2, 5, 0, -1)) {
            val ev = NeteaseSessionLyric.parse(
                NeteaseSessionLyric.EVENT_LYRIC,
                mapOf(
                    NeteaseSessionLyric.KEY_LYRIC_STATE to state,
                    NeteaseSessionLyric.KEY_LYRIC_OBJ to "歌词加载中",
                ),
            )
            assertNotNull(ev)
            assertTrue("state=$state 必须当作清空", ev!!.cleared)
            assertEquals("", ev.lrc)
        }
    }

    @Test
    fun lyricEvent_missingStateIsTreatedAsClear() {
        val ev = NeteaseSessionLyric.parse(
            NeteaseSessionLyric.EVENT_LYRIC,
            mapOf(NeteaseSessionLyric.KEY_LYRIC_OBJ to "[00:00.00]春风吹"),
        )
        assertNotNull(ev)
        assertTrue(ev!!.cleared)
    }

    @Test
    fun lyricEvent_stateAsLongIsAccepted() {
        // Bundle 里整数可能是 Integer 也可能是 Long
        val ev = NeteaseSessionLyric.parse(
            NeteaseSessionLyric.EVENT_LYRIC,
            mapOf(
                NeteaseSessionLyric.KEY_LYRIC_STATE to 1L,
                NeteaseSessionLyric.KEY_LYRIC_OBJ to "[00:00.00]春风吹",
            ),
        )
        assertNotNull(ev)
        assertFalse(ev!!.cleared)
    }

    @Test
    fun lyricEvent_oversizedLyricIsDropped() {
        val huge = "x".repeat(NeteaseSessionLyric.MAX_LENGTH + 1)
        val ev = NeteaseSessionLyric.parse(
            NeteaseSessionLyric.EVENT_LYRIC,
            mapOf(
                NeteaseSessionLyric.KEY_LYRIC_STATE to 1,
                NeteaseSessionLyric.KEY_LYRIC_OBJ to huge,
            ),
        )
        assertNotNull(ev)
        assertEquals("", ev!!.lrc)
    }

    // ------------------------------------------------------------ get_lyric_action

    @Test
    fun currentLinesEvent_readsPreviousCurrentNext() {
        val ev = NeteaseSessionLyric.parse(
            NeteaseSessionLyric.EVENT_CURRENT_LINES,
            mapOf(
                NeteaseSessionLyric.KEY_LYRIC_LINES to arrayOf("上一句", "当前句", "下一句"),
            ),
        )
        assertNotNull(ev)
        assertEquals(listOf("上一句", "当前句", "下一句"), ev!!.lines)
        assertEquals("", ev.lrc)
        assertFalse(ev.cleared)
    }

    @Test
    fun currentLinesEvent_acceptsListPayload() {
        val ev = NeteaseSessionLyric.parse(
            NeteaseSessionLyric.EVENT_CURRENT_LINES,
            mapOf(NeteaseSessionLyric.KEY_LYRIC_LINES to listOf("a", "b")),
        )
        assertNotNull(ev)
        assertEquals(listOf("a", "b"), ev!!.lines)
    }

    @Test
    fun currentLinesEvent_missingPayloadGivesEmptyLines() {
        val ev = NeteaseSessionLyric.parse(NeteaseSessionLyric.EVENT_CURRENT_LINES, emptyMap())
        assertNotNull(ev)
        assertTrue(ev!!.lines.isEmpty())
    }

    // ------------------------------------------------------------ 加载中 / 消息

    @Test
    fun loadingAndMessageEventsMeanClear() {
        for (event in listOf(
            NeteaseSessionLyric.EVENT_LOADING,
            NeteaseSessionLyric.EVENT_MESSAGE,
        )) {
            val ev = NeteaseSessionLyric.parse(event, emptyMap())
            assertNotNull(ev)
            assertTrue("$event 必须当作清空", ev!!.cleared)
        }
    }

    @Test
    fun unknownEventIsIgnored() {
        assertNull(NeteaseSessionLyric.parse("some.other.event", emptyMap()))
        assertNull(NeteaseSessionLyric.parse(null, emptyMap()))
    }

    // ------------------------------------------------------------ 过期判定

    @Test
    fun staleDetection_needsBothIdsAndDifference() {
        assertTrue(NeteaseSessionLyric.isStale("111", "222"))
        assertFalse(NeteaseSessionLyric.isStale("111", "111"))
        // 任一边为空都不判死（有些版本不带 id）
        assertFalse(NeteaseSessionLyric.isStale("", "222"))
        assertFalse(NeteaseSessionLyric.isStale("111", ""))
        assertFalse(NeteaseSessionLyric.isStale(null, null))
        assertFalse(NeteaseSessionLyric.isStale(null, "222"))
        assertFalse(NeteaseSessionLyric.isStale("111", null))
    }

    @Test
    fun mediaIdIsReadFromTheEventPayload() {
        val id = NeteaseSessionLyric.mediaIdOf(mapOf(NeteaseSessionLyric.KEY_MEDIA_ID to "150619"))
        assertEquals("150619", id)
        assertEquals("", NeteaseSessionLyric.mediaIdOf(emptyMap()))
    }
}
