package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词匹配 / 清洗的纯逻辑单测。
 *
 * （clean:29-43、score:150-184、stripNoise:186-191）
 * （buildSongKey:28-38）。
 */
class LyricMatchTest {

    // ---------------------------------------------------------------- stripNoise

    /** stripNoise:186-191 —— 括号**不是整段删**，只有尾部那类标签才删。 */
    @Test
    fun stripNoise_onlyRemovesTrailingQualityTags() {
        assertEquals("夜航星", LyricMatch.stripNoise("01. 夜航星 [FLAC]"))
        assertEquals("夜航星", LyricMatch.stripNoise("夜航星.mp3"))
        assertEquals("夜航星", LyricMatch.stripNoise("Track 3. 夜航星"))
        // 普通括号不是标签 → 原样保留
        assertEquals("夜航星 (Live)", LyricMatch.stripNoise("夜航星 (Live)"))
        assertEquals("夜航星（现场版）", LyricMatch.stripNoise("夜航星（现场版）"))
    }

    /** clean:29-43 —— 歌手为空时把「歌名 - 歌手」拆开。 */
    @Test
    fun clean_splitsTitleAndArtistWhenArtistMissing() {
        val q = LyricMatch.clean("夜航星 - 不才", null)
        assertEquals("夜航星", q.title)
        assertEquals("不才", q.artist)

        val q2 = LyricMatch.clean("夜航星", "不才")
        assertEquals("夜航星", q2.title)
        assertEquals("不才", q2.artist)
    }

    // ---------------------------------------------------------------- normalize

    @Test
    fun normalize_removesPunctuationAndCase() {
        assertEquals("夜航星", LyricMatch.normalize("夜 航 星"))
        assertEquals("happy", LyricMatch.normalize("Happy!"))
        assertEquals("", LyricMatch.normalize(null))
    }

    // ---------------------------------------------------------------- score

    @Test
    fun score_exactMatchUsesReferenceWeights() {
        // 标题 100 + 歌手 70 + 时长差 1s（35）= 205
        assertEquals(205, LyricMatch.score("夜航星", "不才", 245_000, "夜航星", "不才", 244_000))
    }

    @Test
    fun score_titleContainsIs55AndArtistContainsIs45() {
        // 标题包含 55 + 歌手包含 45 + 时长差 3s（30）= 130
        assertEquals(130, LyricMatch.score("夜航星", "不才", 245_000, "夜航星完整版", "不才Band", 242_000))
    }

    @Test
    fun score_mismatchIsNegative() {
        // 标题 -80 + 歌手 -45 + 时长差 24s（-20）= -145
        assertEquals(-145, LyricMatch.score("夜航星", "不才", 245_000, "晴天", "周杰伦", 269_000))
    }

    @Test
    fun score_emptyTitleReturnsSizeOriginal() {
        assertEquals(LyricMatch.EMPTY_SCORE, LyricMatch.score("", "不才", 245_000, "晴天", "周杰伦", 269_000))
    }

    /** score:183 —— 候选带版本噪声而期望不带 → 再 -60。 */
    @Test
    fun score_versionNoisePenaltyApplies() {
        // 候选带噪声、期望不带：标题包含 55 + 歌手 70 + 时长 35 = 160，再 -60 → 100
        assertEquals(100, LyricMatch.score("夜航星", "不才", 245_000, "夜航星现场版", "不才", 245_000))
        // 期望本身带噪声 → 不再扣分：标题相同 100 + 歌手 70 + 时长 35 = 205
        assertEquals(205, LyricMatch.score("夜航星live", "不才", 245_000, "夜航星live", "不才", 245_000))
    }

    @Test
    fun hasVersionNoise_detectsRemixWords() {
        assertTrue(LyricMatch.hasVersionNoise("夜航星 (Live)"))
        assertTrue(LyricMatch.hasVersionNoise("夜航星 伴奏"))
        assertFalse(LyricMatch.hasVersionNoise("夜航星"))
    }

    // ---------------------------------------------------------------- 不稳定标题

    @Test
    fun isLikelyLyricTitle_detectsLyricLines() {
        assertTrue(LyricMatch.isLikelyLyricTitle("我看见夜空中最亮的星，请指引我靠近你"))
        assertTrue(LyricMatch.isLikelyLyricTitle(""))
        assertFalse(LyricMatch.isLikelyLyricTitle("夜航星"))
    }

    // ---------------------------------------------------------------- 缓存键

    @Test
    fun buildSongKey_prefersMediaId() {
        assertEquals("id:12345", LyricMatch.buildSongKey("12345", "夜航星"))
        assertEquals("t:夜航星", LyricMatch.buildSongKey("0", "夜航星"))
        assertEquals("t:夜航星", LyricMatch.buildSongKey("-1", "夜航星"))
        assertEquals("t:夜航星", LyricMatch.buildSongKey(null, "夜航星"))
        assertEquals("t:未知歌曲", LyricMatch.buildSongKey(null, "  "))
    }

    @Test
    fun isLyricTitleSource_marksLocalAndBluetoothPackages() {
        assertTrue(LyricMatch.isLyricTitleSource("com.tencent.wecarflow"))
        assertTrue(LyricMatch.isLyricTitleSource("com.ecarx.multimedia"))
        assertTrue(LyricMatch.isLyricTitleSource("com.oem.bt.music"))
        assertTrue(LyricMatch.isLyricTitleSource("com.oem.usb.player"))
        assertFalse(LyricMatch.isLyricTitleSource("com.tencent.qqmusiccar"))
    }

    // ---------------------------------------------------------------- JSONP 回调体

    /** 截出 JSONP 里的大括号主体。 */
    @Test
    fun firstJsonCallback_extractsJsonBody() {
        assertEquals(
            """{"retcode":0}""",
            LyricMatch.firstJsonCallback("""callback({"retcode":0})"""),
        )
        assertEquals("""{"a":1}""", LyricMatch.firstJsonCallback("""{"a":1}"""))
        // 没有大括号 → 原样返回
        assertEquals("plain", LyricMatch.firstJsonCallback("plain"))
        assertEquals("", LyricMatch.firstJsonCallback(null))
    }

    // ---------------------------------------------------------------- 歌曲 id

    /** 抠出 4 位以上的纯数字 id。 */
    @Test
    fun parseSongId_extractsNumericId() {
        assertEquals(102069682L, LyricMatch.parseSongId("102069682"))
        assertEquals(150619L, LyricMatch.parseSongId("song/150619/x.mp3"))
        // 少于 4 位不算 id
        assertEquals(-1L, LyricMatch.parseSongId("123"))
        // 挨着字母的数字不算
        assertEquals(-1L, LyricMatch.parseSongId("abc102069682"))
        assertEquals(-1L, LyricMatch.parseSongId(null))
    }
}
