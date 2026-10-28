package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体域「持久化 / 兜底」相关的纯逻辑单测。
 *
 *
 * - 稳定歌名： 的语义
 * - 粗匹配歌名：
 * - 酷狗旧接口：（关键词 / hash 挑选 / LRC 解析）
 */
class MusicPersistenceTest {

    // ---------------------------------------------------------------- 稳定歌名

    @Test
    fun stableSongTitle_keepsNormalTitle() {
        assertEquals("夜航星", MediaText.stableSongTitle("夜航星"))
        assertEquals("夜航星", MediaText.stableSongTitle("  夜航星  "))
    }

    /** 不稳定标题（更像一句歌词）不能当歌名存。 */
    @Test
    fun stableSongTitle_dropsUnstableTitle() {
        assertEquals("", MediaText.stableSongTitle("这是一句很长很长的歌词内容，带标点。"))
        assertEquals("", MediaText.stableSongTitle(""))
        assertEquals("", MediaText.stableSongTitle("   "))
        assertEquals("", MediaText.stableSongTitle(null))
    }

    // ---------------------------------------------------------------- 粗匹配

    @Test
    fun titleRoughlyMatches_matchesEqualOrContained() {
        assertTrue(LyricMatch.titleRoughlyMatches("夜航星", "夜航星"))
        assertTrue(LyricMatch.titleRoughlyMatches("夜航星", "夜航星 Live"))
        assertTrue(LyricMatch.titleRoughlyMatches("夜航星2", "夜航星"))
    }

    @Test
    fun titleRoughlyMatches_rejectsDifferentSong() {
        assertFalse(LyricMatch.titleRoughlyMatches("夜航星", "晴天"))
        assertFalse(LyricMatch.titleRoughlyMatches("", "晴天"))
        assertFalse(LyricMatch.titleRoughlyMatches("夜航星", null))
    }

    // ---------------------------------------------------------------- 酷狗旧接口

    /** KugouLyricFetcher.fetchRawLegacy:42-58 —— 关键词 = 歌手 + " " + 歌名。 */
    @Test
    fun kugou_keywordPutsArtistFirst() {
        assertEquals("不才 夜航星", KugouLyricFetcher.searchKeyword("夜航星", "不才"))
        assertEquals("夜航星", KugouLyricFetcher.searchKeyword("夜航星", "  "))
        assertEquals("夜航星", KugouLyricFetcher.searchKeyword(" 夜航星 ", ""))
    }

    /** KugouLyricFetcher.parseLrc:163-191 —— `[mm:ss.xx]歌词`。 */
    @Test
    fun kugou_parsesLrcLines() {
        val lines = KugouLyricFetcher.parseLrc(
            """
            [00:01.50]第一行
            [00:05.00]第二行
            [01:02.25]第三行
            """.trimIndent(),
        )
        assertEquals(3, lines.size)
        assertEquals(1_500L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
        assertEquals(5_000L, lines[1].timeMs)
        assertEquals(62_250L, lines[2].timeMs)
    }

    /** KugouLyricFetcher.parseLrc:166 —— 先洗掉 KRC / LRC 元信息。 */
    @Test
    fun kugou_stripsMetadataTags() {
        val lines = KugouLyricFetcher.parseLrc("[id:12345]\n<100,200,300>[00:03.00]好的\n")
        assertEquals(1, lines.size)
        assertEquals(3_000L, lines[0].timeMs)
        assertEquals("好的", lines[0].text)
    }

    @Test
    fun kugou_ignoresGarbage() {
        assertTrue(KugouLyricFetcher.parseLrc("").isEmpty())
        assertTrue(KugouLyricFetcher.parseLrc(null).isEmpty())
        assertTrue(KugouLyricFetcher.parseLrc("没有时间戳的纯文本").isEmpty())
    }

    /** KugouLyricFetcher.parseLrcText:193-205 —— 解析不出来就当成 0ms 的一行。 */
    @Test
    fun kugou_parseLrcTextFallsBackToSingleLine() {
        val lines = KugouLyricFetcher.parseLrcText("纯文本歌词")
        assertEquals(1, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals("纯文本歌词", lines[0].text)
        assertTrue(KugouLyricFetcher.parseLrcText("   ").isEmpty())
    }

    /** KugouLyricFetcher.searchSongLegacy:213-236 —— 挑歌名匹配的那个 hash。 */
    @Test
    fun kugou_findsMatchingHash() {
        val hash = "0123456789ABCDEF0123456789ABCDEF"
        val json = """
            {"status":1,"data":{"lists":[{"hash":"$hash","songname":"夜航星","SingerName":"不才"}]}}
        """.trimIndent()
        assertEquals(hash, KugouLyricFetcher.findHash(json, "夜航星"))
        // 歌名对不上 → 不挑这个
        assertNull(KugouLyricFetcher.findHash(json, "晴天"))
    }

    @Test
    fun kugou_findHashHandlesBrokenPayload() {
        assertNull(KugouLyricFetcher.findHash(null, "夜航星"))
        assertNull(KugouLyricFetcher.findHash("", "夜航星"))
        // hash 不足 32 位
        assertNull(KugouLyricFetcher.findHash("{\"hash\":\"0123\"}", "夜航星"))
    }

    /** KugouLyricFetcher.nearbyJsonString:151-161 —— 在附近找 `"key":"value"`。 */
    @Test
    fun kugou_nearbyJsonStringFindsValue() {
        val json = """{"songname":"夜航星","hash":"0123456789ABCDEF0123456789ABCDEF"}"""
        val at = json.indexOf("\"hash\":\"")
        assertEquals("夜航星", KugouLyricFetcher.nearbyJsonString(json, at, "songname"))
        assertEquals("", KugouLyricFetcher.nearbyJsonString(json, at, "notExist"))
    }

    @Test
    fun kugou_urlsMatchReferenceProtocol() {
        assertTrue(KugouLyricFetcher.SEARCH_URL.startsWith("http://mobilecdn.kugou.com/api/v3/search/song?keyword="))
        assertTrue(KugouLyricFetcher.SEARCH_URL.contains("&page=1&pagesize=5"))
        assertTrue(KugouLyricFetcher.LYRIC_URL.contains("cmd=100"))
        assertTrue(KugouLyricFetcher.LYRIC_URL.contains("timelength=999999"))
        assertNotNull(KugouLyricFetcher.LYRIC_URL)
    }
}
