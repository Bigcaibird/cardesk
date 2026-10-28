package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播放器自供歌词」识别的纯逻辑单测。
 *
 * 第四十一轮新增：除了 QQ 音乐车机版的广播（`update_lyric`），
 * 某些播放器会把歌词塞进 的 metadata / description extras 里。
 * 没有这条通道（增强）。
 *
 * 误判风险必须压死：歌名里带方括号、描述里带 `[1]` 之类都**不能**被当成歌词。
 */
class PlayerLyricTest {

    /** 正常的 LRC。 */
    private val lrc = """
        [ti:小镇姑娘]
        [ar:陶喆]
        [00:25.20]还记得多年前跟你手牵手
        [00:28.36]你都害羞的不敢抬头
        [00:31.39]只会傻傻的看着天上的星星
    """.trimIndent()

    @Test
    fun looksLikeLrc_acceptsRealLyric() {
        assertTrue(PlayerLyric.looksLikeLrc(lrc))
    }

    @Test
    fun looksLikeLrc_rejectsPlainText() {
        assertFalse(PlayerLyric.looksLikeLrc("这是一句普通的描述文字"))
        assertFalse(PlayerLyric.looksLikeLrc("小镇姑娘 - 陶喆"))
        assertFalse(PlayerLyric.looksLikeLrc(""))
        assertFalse(PlayerLyric.looksLikeLrc(null))
    }

    /** 只有一行时间戳 → 可能是歌名里的 `[Live]` 之类，不认。 */
    @Test
    fun looksLikeLrc_rejectsSingleTimestamp() {
        assertFalse(PlayerLyric.looksLikeLrc("[00:25.20]还记得多年前跟你手牵手"))
    }

    /** 歌名里带方括号不能被当成歌词。 */
    @Test
    fun looksLikeLrc_rejectsBracketedTitle() {
        assertFalse(PlayerLyric.looksLikeLrc("小镇姑娘 [Live]"))
        assertFalse(PlayerLyric.looksLikeLrc("夜航星 [FLAC]"))
    }

    /** 超长文本（HTML / JSON）不认。 */
    @Test
    fun looksLikeLrc_rejectsHugeText() {
        val huge = buildString { repeat(20_000) { append("[00:25.20]歌词\n") } }
        assertFalse(PlayerLyric.looksLikeLrc(huge))
    }

    /** 从候选里挑出时间戳行最多的那段。 */
    @Test
    fun findLrc_picksTheRichestCandidate() {
        val found = PlayerLyric.findLrc(listOf("普通描述", lrc, null, ""))
        assertEquals(lrc, found)
    }

    /** 候选里没有歌词 → null。 */
    @Test
    fun findLrc_returnsNullWhenNothingLooksLikeLyric() {
        assertNull(PlayerLyric.findLrc(listOf("普通描述", "", null)))
        assertNull(PlayerLyric.findLrc(emptyList()))
    }

    /** 解析出来的行数对得上（和 `LrcParser` 配合）。 */
    @Test
    fun parsedLyricHasTimestamps() {
        val timeline = LrcParser.parse(lrc)
        assertEquals(3, timeline.lines.size)
        assertEquals(25_200L, timeline.lines[0].timeMs)
        assertEquals("还记得多年前跟你手牵手", timeline.lines[0].text)
    }
}
