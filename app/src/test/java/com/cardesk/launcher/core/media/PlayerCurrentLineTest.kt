package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 「播放器自报的当前歌词行」相关纯逻辑的单测（第七十八轮）。
 *
 *
 *
 * 背景：QQ 音乐车机版（`com.tencent.wecarflow`）把**正在唱的那一句**塞进
 * `METADATA_KEY_TITLE`，真歌名放进 `DISPLAY_TITLE`。
 * 真机诊断包实测：`METADATA_KEY_TITLE` 随播放从
 * 「OP/SP：北京有此山文化传媒有限公司」→「近日我体内寂寞癌细胞恶化」
 * → 「害我成了原发性失眠者」→ …… 一句一句地变。
 *
 * 我们要认出来的就是这一句 —— 它是「现在唱到哪」的权威答案。
 */
class PlayerCurrentLineTest {

    private val pkg = "com.tencent.wecarflow"
    private val song = "怎么我睡不着你也不来救我"
    private val artist = "焦迈奇"

    // ---------------------------------------------------------------- 认当前句

    /** 歌词型来源：原始 TITLE 是一句歌词 → 认出来。 */
    @Test
    fun lyricSource_recognizesLyricLine() {
        assertEquals(
            "近日我体内寂寞癌细胞恶化",
            MediaText.playerCurrentLine(
                rawTitle = "近日我体内寂寞癌细胞恶化",
                resolvedTitle = song,
                artist = artist,
                pkg = pkg,
            ),
        )
    }

    /**
     * 不是歌词型来源 → TITLE 就是歌名，不可能是歌词行。
     *
     * QQ 音乐手机版（`com.tencent.qqmusiccar`）不在黑名单里，
     * 它的 TITLE 是真歌名 —— 不能把它当歌词行。
     */
    @Test
    fun nonLyricSource_neverTreatsTitleAsLyric() {
        assertEquals(
            "",
            MediaText.playerCurrentLine(
                rawTitle = "夜航星",
                resolvedTitle = "夜航星",
                artist = "不才",
                pkg = "com.tencent.qqmusiccar",
            ),
        )
    }

    /** 原始 TITLE 跟真歌名一模一样 → 那是歌名，不是歌词。 */
    @Test
    fun sameAsSongTitle_isNotLyricLine() {
        assertEquals(
            "",
            MediaText.playerCurrentLine(
                rawTitle = song,
                resolvedTitle = song,
                artist = artist,
                pkg = pkg,
            ),
        )
    }

    /**
     * 切歌那一瞬间的「歌名 - 歌手」格式 → 不是歌词。
     *
     * 真机实测过这个形态：`rawTitle=怎么我睡不着你也不来救我 - 焦迈奇`。
     * 不排除掉的话，唱到第一句之前界面会显示这串东西。
     */
    @Test
    fun songTitleDashArtist_isNotLyricLine() {
        assertEquals(
            "",
            MediaText.playerCurrentLine(
                rawTitle = "$song - $artist",
                resolvedTitle = song,
                artist = artist,
                pkg = pkg,
            ),
        )
        assertEquals(
            "",
            MediaText.playerCurrentLine(
                rawTitle = "$artist - $song",
                resolvedTitle = song,
                artist = artist,
                pkg = pkg,
            ),
        )
    }

    /** 空标题 → 没有当前句。 */
    @Test
    fun blankTitle_hasNoCurrentLine() {
        assertEquals(
            "",
            MediaText.playerCurrentLine(
                rawTitle = "   ",
                resolvedTitle = song,
                artist = artist,
                pkg = pkg,
            ),
        )
    }

    /**
     * 没有标点、也没到 16 个字的歌词行也必须认出来。
     *
     * 这是**不用**「像不像歌词」那套判据的原因：
     * [LyricMatch.isLikelyLyricTitle] 要求有标点或 ≥16 字，
     * 「近日我体内寂寞癌细胞恶化」（12 字、无标点）会被判成歌名 → 当前句就丢了。
     */
    @Test
    fun shortUnpunctuatedLine_isStillRecognized() {
        val line = "近日我体内寂寞癌细胞恶化"
        assertEquals(
            line,
            MediaText.playerCurrentLine(
                rawTitle = line,
                resolvedTitle = song,
                artist = artist,
                pkg = pkg,
            ),
        )
    }

    /** 首尾空白要吃掉，不能带着空格显示。 */
    @Test
    fun trimsSurroundingSpaces() {
        assertEquals(
            "害我成了原发性失眠者",
            MediaText.playerCurrentLine(
                rawTitle = "  害我成了原发性失眠者  ",
                resolvedTitle = song,
                artist = artist,
                pkg = pkg,
            ),
        )
    }

    // ---------------------------------------------------------------- 盖到时间轴上

    /** 盖当前行：只有当前行的文字被换掉，上一句 / 下一句不动。 */
    @Test
    fun withLineText_replacesOnlyTargetLine() {
        val timeline = LyricTimeline(
            listOf(
                LyricLine(0L, "第一句"),
                LyricLine(1000L, "第二句"),
                LyricLine(2000L, "第三句"),
            ),
        )
        val patched = timeline.withLineText(1, "播放器自报的第二句")
        assertEquals("第一句", patched.lines[0].text)
        assertEquals("播放器自报的第二句", patched.lines[1].text)
        assertEquals("第三句", patched.lines[2].text)
        // 时间戳不能动 —— 擦除进度与上/下句都靠它
        assertEquals(1000L, patched.lines[1].timeMs)
    }

    /**
     * **不能**就地改共享缓存里的那份时间轴。
     *
     * 改了的话，下一次换歌复用同一份缓存时就带着上一次的「当前句」——
     * 越用越乱。所以必须是**新的一份**。
     */
    @Test
    fun withLineText_doesNotMutateOriginal() {
        val timeline = LyricTimeline(listOf(LyricLine(0L, "原句")))
        val patched = timeline.withLineText(0, "新句")
        assertEquals("原句", timeline.lines[0].text)
        assertNotSame(timeline, patched)
    }

    /** 文字没变时直接返回原对象（不做无谓的复制）。 */
    @Test
    fun withLineText_sameText_returnsSameInstance() {
        val timeline = LyricTimeline(listOf(LyricLine(0L, "同一句")))
        assertSame(timeline, timeline.withLineText(0, "同一句"))
    }

    /** 越界索引不越界、不崩。 */
    @Test
    fun withLineText_outOfRange_isSafe() {
        val timeline = LyricTimeline(listOf(LyricLine(0L, "只有一句")))
        assertSame(timeline, timeline.withLineText(-1, "x"))
        assertSame(timeline, timeline.withLineText(5, "x"))
    }
}
