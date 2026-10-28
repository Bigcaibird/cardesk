package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCoreTest {

    // ---------------------------------------------------------------- LRC

    @Test
    fun lrc_parsesTimestampsAndText() {
        val timeline = LrcParser.parse(
            """
            [00:01.50]第一行
            [00:05.00]第二行
            [01:02.25]第三行
            """.trimIndent(),
        )
        assertEquals(3, timeline.lines.size)
        assertEquals(1500L, timeline.lines[0].timeMs)
        assertEquals("第二行", timeline.lines[1].text)
        assertEquals(62_250L, timeline.lines[2].timeMs)
    }

    /**
     * 第三十三轮：**`[offset:...]` 不再参与时间戳计算**。
     *
     * 把 `offset` 当元数据标签**删掉**；我们之前是把它**加到时间戳上**
     * 歌词整体前后平移 —— 这就是「歌词跟唱对不上」的一个真凶。
     */
    @Test
    fun lrc_supportsMultipleTimestampsPerLineAndIgnoresOffset() {
        val timeline = LrcParser.parse("[offset:100]\n[00:02.00][00:04.00]副歌\n")
        assertEquals(2, timeline.lines.size)
        assertEquals(2000L, timeline.lines[0].timeMs)
        assertEquals(4000L, timeline.lines[1].timeMs)
        assertEquals("副歌", timeline.lines[1].text)
    }

    /** 第三十三轮：`[offset:-2000]` 这种大偏移也不能把歌词整体平移（口径：忽略）。 */
    @Test
    fun lrc_ignoresLargeNegativeOffset() {
        val timeline = LrcParser.parse("[offset:-2000]\n[00:30.00]副歌\n")
        assertEquals(1, timeline.lines.size)
        assertEquals(30_000L, timeline.lines[0].timeMs)
    }

    /**
     * 第三十三轮：**剥掉 QQ 音乐 / 网易的逐字时间标签** `<起,止,时>`。
     */
    @Test
    fun lrc_stripsWordLevelTimeTags() {
        val timeline = LrcParser.parse("[00:12.34]<0,1200,0>我看见<1200,2400,0>夜空中\n")
        assertEquals(1, timeline.lines.size)
        assertEquals(12_340L, timeline.lines[0].timeMs)
        assertEquals("我看见夜空中", timeline.lines[0].text)
    }

    /** 第三十三轮：元数据标签整行剔掉（`ti` / `ar` / `by` / `offset` / `total` …）。 */
    @Test
    fun lrc_stripsMetadataTags() {
        val timeline = LrcParser.parse(
            "[ti:歌名]\n[ar:歌手]\n[al:专辑]\n[by:]\n[offset:0]\n[total:240000]\n[00:05.00]真歌词\n",
        )
        assertEquals(1, timeline.lines.size)
        assertEquals("真歌词", timeline.lines[0].text)
        assertEquals(5_000L, timeline.lines[0].timeMs)
    }

    @Test
    fun lrc_ignoresGarbageAndEmptyLines() {
        val timeline = LrcParser.parse("\n纯文本\n[xx:yy]坏行\n[00:03.00]好行\n")
        assertEquals(1, timeline.lines.size)
        assertEquals("好行", timeline.lines[0].text)
    }

    @Test
    fun timeline_findsCurrentLineAndWipe() {
        val timeline = LrcParser.parse("[00:00.00]a\n[00:02.00]b\n[00:04.00]c\n")
        assertEquals(0, timeline.indexAt(500))
        assertEquals(1, timeline.indexAt(2_000))
        assertEquals(2, timeline.indexAt(9_000))
        assertEquals(-1, timeline.indexAt(-1))
        assertEquals(0.5f, timeline.wipeFraction(3_000, 1), 0.001f)
    }

    /** —— 末行跨度取剩余时长。 */
    @Test
    fun timeline_lastLineWipeUsesRemainingDuration() {
        val timeline = LrcParser.parse("[00:00.00]a\n[00:02.00]b\n")
        // 末行：总长 10s → 2s..10s 擦除
        assertEquals(0.5f, timeline.wipeFraction(6_000, 1, 10_000), 0.001f)
        // 没有总长时退化为 +5000ms
        assertEquals(0.4f, timeline.wipeFraction(4_000, 1), 0.001f)
    }

    // ---------------------------------------------------------------- match

    @Test
    fun match_exactTitleAndArtistScoresHigh() {
        val score = LyricMatch.score("夜航星", "不才", 245_000, "夜航星", "不才", 244_000)
        assertTrue(score >= LyricMatch.MIN_SCORE)
    }

    /**
     * LyricMatch.score:150-184 —— 标题不匹配是 **-80** 起步（不是 0）
     * 歌手不同再 -45，时长差 >15s 再 -20。
     */
    @Test
    fun match_differentSongScoresNegative() {
        assertEquals(-145, LyricMatch.score("夜航星", "不才", 245_000, "晴天", "周杰伦", 269_000))
    }

    @Test
    fun match_cleansTrackPrefixAndBrackets() {
        assertEquals("夜航星", LyricMatch.clean("01. 夜航星 [FLAC]"))
    }

    // ---------------------------------------------------------------- chooser

    @Test
    fun chooser_prefersActiveSession() {
        val now = 1_000_000L
        val playing = SessionCandidate("com.a", playing = true, hasMetadata = true, updatedAt = now, title = "a")
        val idle = SessionCandidate("com.b", playing = false, hasMetadata = true, updatedAt = now, title = "b")
        val chosen = SessionChooser.choose(listOf(idle, playing), lockedPkg = null, now = now)
        assertEquals("com.a", chosen?.pkg)
    }

    /**
     * MusicSessionTracker.chooseBest:197-213 —— 锁定包时**只**在锁定包里挑
     * 锁定包既不活跃又没有元数据就「没有可显示的会话」。
     */
    @Test
    fun chooser_lockedPackageWinsOnlyWithinLockedPackage() {
        val now = 1_000_000L
        val playing = SessionCandidate("com.a", playing = true, hasMetadata = true, updatedAt = now, title = "a")
        val lockedDead = SessionCandidate("com.c", playing = false, hasMetadata = false, updatedAt = 0L)
        assertNull(SessionChooser.choose(listOf(playing, lockedDead), lockedPkg = "com.c", now = now))

        val lockedAlive = SessionCandidate("com.c", playing = true, hasMetadata = true, updatedAt = now, title = "c")
        assertEquals(
            "com.c",
            SessionChooser.choose(listOf(playing, lockedAlive), lockedPkg = "com.c", now = now)?.pkg,
        )
    }

    @Test
    fun chooser_returnsNullWhenEmpty() {
        assertNull(SessionChooser.choose(emptyList(), null, 0L))
        assertNull(SessionChooser.choose(listOf(SessionCandidate("p", false, false, 0L)), null, 1L))
    }

    // ---------------------------------------------------------------- sources

    @Test
    fun sourceMapping_matchesReferencePackages() {
        assertEquals("com.tencent.qqmusiccar", MusicSessionTracker.packageForSource("qq"))
        assertEquals("com.netease.cloudmusic.iot", MusicSessionTracker.packageForSource("netease"))
        assertEquals("com.kugou.android.auto", MusicSessionTracker.packageForSource("kugou"))
        assertEquals("qq", MusicSessionTracker.sourceIdForPackage("com.tencent.qqmusiccar"))
        assertNull(MusicSessionTracker.packageForSource("unknown"))
    }
}
