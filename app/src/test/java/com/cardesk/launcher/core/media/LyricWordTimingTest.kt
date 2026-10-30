package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词**逐字时间**的语义自校准 + 贴词的单测。
 *
 * ## 为什么要有这组测试
 *
 * QQ 歌词正文里那种 `<n,n,n>` 标签，**三个数字的语义至今没有权威来源**：
 * 原厂 APK 只 `replaceAll` 剥掉、从不解析；参考工程连剥都不剥。
 * 所以 CarDesk **不写死语义**，而是**用数据自证**（绝对 / 相对行首两种口径投票）。
 *
 * 这组测试把「自证」这件事本身钉死：
 *
 * - 判得出 ⇒ 按判出的口径挂词；
 * - 判不出 ⇒ **丢掉逐字数据、回退行级**（宁可退化，也不能错挂）。
 *
 * 另外还钉死了本轮的**核心回归**：
 * **「一句歌词还没擦除完就到下一句」** ⇒ 歌手提前唱完时高亮必须当场到 100%。
 */
class LyricWordTimingTest {

    private val eps = 1e-4f

    // ---------------------------------------------------------------- split

    /** 标签在行首 ⇒ 能切出逐字片段。 */
    @Test
    fun split_readsSegments() {
        val p = LyricWordTiming.split("<0,180,0>怎<180,360,0>么")
        assertTrue(p != null)
        assertEquals("怎么", p!!.text)
        assertEquals(2, p.segments.size)
        assertEquals(0L, p.segments[0].a)
        assertEquals(180L, p.segments[0].b)
        assertEquals(0, p.segments[0].charStart)
        assertEquals(1, p.segments[0].charEnd)
        assertEquals(1, p.segments[1].charStart)
        assertEquals(2, p.segments[1].charEnd)
    }

    /** 没有标签 ⇒ null（没有逐字数据）。 */
    @Test
    fun split_returnsNullWithoutTags() {
        assertTrue(LyricWordTiming.split("怎么我睡不着") == null)
    }

    /**
     * 标签**前面有正文** ⇒ 切不出映射，返回 null。
     *
     * 标签与字对不上号时，映射不可靠 ⇒ 宁可不挂词。
     */
    @Test
    fun split_returnsNullWhenTextPrecedesTags() {
        assertTrue(LyricWordTiming.split("前面<0,180,0>怎") == null)
    }

    // ---------------------------------------------------------------- attach：语义判定

    /**
     * 标签是**相对行首** ⇒ 判出 `RELATIVE_TO_LINE`。
     *
     * 夹具：第一行行首 0ms（两种口径都自洽）、第二行行首 3000ms
     * （只有相对行首自洽）⇒ 相对口径 2 票、绝对口径 1 票 ⇒ 判为相对行首。
     */
    @Test
    fun attach_detectsRelativeToLine() {
        val lines = listOf(LyricLine(0L, "怎么我"), LyricLine(3000L, "不着"))
        val parsed = listOf(
            LyricWordTiming.split("<0,180,0>怎<180,360,0>么<360,540,0>我"),
            LyricWordTiming.split("<0,200,0>不<200,400,0>着"),
        )
        val out = LyricWordTiming.attach(lines, parsed)
        assertEquals(3, out[0].words.size)
        // 第二行：字的时间应当是「行首 + 偏移」
        assertEquals(3000L, out[1].words[0].startMs)
        assertEquals(3200L, out[1].words[1].startMs)
        assertEquals(3400L, out[1].words[1].endMs)
    }

    /**
     * 标签是**绝对时刻** ⇒ 判出 `ABSOLUTE`。
     *
     * 夹具：第二行的标签值 5000/5180 正好是行首 5000 附近的绝对时刻，
     * 按相对行首解释会跑到 10000ms ⇒ 判不出来 ⇒ 绝对口径 2 票胜出。
     */
    @Test
    fun attach_detectsAbsolute() {
        val lines = listOf(LyricLine(0L, "怎么"), LyricLine(5000L, "我们"))
        val parsed = listOf(
            LyricWordTiming.split("<0,180,0>怎<180,360,0>么"),
            LyricWordTiming.split("<5000,5180,0>我<5180,5360,0>们"),
        )
        val out = LyricWordTiming.attach(lines, parsed)
        assertEquals(2, out[0].words.size)
        assertEquals(5000L, out[1].words[0].startMs)
        assertEquals(5180L, out[1].words[1].startMs)
    }

    /**
     * **只有一行**时也要能判出来。
     *
     * 以前末行不参与投票，单行歌词文件会「一票都投不出」⇒ 逐字数据被白白丢掉。
     * 现在末行也参与投票（末行没有「下一行行首」做上界，判定放宽）。
     */
    @Test
    fun attach_singleLineStillVotes() {
        val lines = listOf(LyricLine(600000L, "怎么"))
        val parsed = listOf(LyricWordTiming.split("<0,180,0>怎<180,360,0>么"))
        val out = LyricWordTiming.attach(lines, parsed)
        assertEquals("单行也应当判出相对行首口径", 2, out[0].words.size)
        assertEquals(600000L, out[0].words[0].startMs)
    }

    /**
     * **判不出来 ⇒ 一律不挂词**（回退行级），绝不瞎挂。
     *
     * 第一行的行首是 0ms ⇒ 绝对 / 相对行首两种口径**完全等价**；
     * 第二行只有 1 个片段（没有行内进展）不参与投票 ⇒ 1 : 1 打平 ⇒ 判不出来。
     */
    @Test
    fun attach_keepsNothingWhenAmbiguous() {
        val lines = listOf(LyricLine(0L, "怎么"), LyricLine(3000L, "着"))
        val parsed = listOf(
            LyricWordTiming.split("<0,180,0>怎<180,360,0>么"),
            LyricWordTiming.split("<0,180,0>着"),
        )
        val out = LyricWordTiming.attach(lines, parsed)
        assertTrue(out.all { it.words.isEmpty() })
    }

    /** 只有一个片段 = 这行没有「行内进展」⇒ 不参与逐字模式。 */
    @Test
    fun attach_ignoresSingleSegmentLines() {
        val lines = listOf(LyricLine(600000L, "怎么"))
        val parsed = listOf(LyricWordTiming.split("<0,180,0>怎"))
        val out = LyricWordTiming.attach(lines, parsed)
        assertTrue(out[0].words.isEmpty())
    }

    /**
     * 行窗口上界取「**严格大于**本行时间戳的最小值」。
     *
     * 相邻两行时间戳**相等**时，用 `lines[i + 1]` 会把上界算成同一个值 ⇒
     * 后面那行的逐字数据会被误杀。夹具里第 1 行的字拖到 20000ms，
     * 而下一行的行首是 30000ms ⇒ 上界应当是 30000ms 而不是 10000ms。
     */
    @Test
    fun attach_usesNextStrictlyGreaterTimestampAsWindowBound() {
        val lines = listOf(
            LyricLine(10000L, "拖长"),
            LyricLine(10000L, "接"),
            LyricLine(30000L, "下"),
        )
        val parsed = listOf(
            LyricWordTiming.split("<0,5000,0>拖<5000,10000,0>长"),
            LyricWordTiming.split("<0,500,0>接"),
            LyricWordTiming.split("<0,500,0>下"),
        )
        val out = LyricWordTiming.attach(lines, parsed)
        assertEquals("上界应当是 30000ms，逐字数据不该被误杀", 2, out[0].words.size)
        assertEquals(20000L, out[0].words[1].endMs)
    }

    // ---------------------------------------------------------------- mergeWords

    /** 行首时间戳**完全一致** ⇒ 贴上逐字数据。 */
    @Test
    fun mergeWords_attachesByExactTimestamp() {
        val base = listOf(LyricLine(0L, "怎么"), LyricLine(3000L, "不着"))
        val source = listOf(
            LyricLine(
                0L, "怎么",
                listOf(LyricWord(0L, 180L, 0, 1), LyricWord(180L, 360L, 1, 2)),
            ),
            LyricLine(3000L, "不着", listOf(LyricWord(3000L, 3200L, 0, 1))),
        )
        val merged = LyricWordTiming.mergeWords(base, source)
        assertEquals(2, merged[0].words.size)
        assertEquals(1, merged[1].words.size)
    }

    /** 时间戳差一毫秒 ⇒ **不贴**（宁可少贴，也不贴错）。 */
    @Test
    fun mergeWords_requiresExactTimestampMatch() {
        val base = listOf(LyricLine(0L, "怎么"), LyricLine(3000L, "不着"))
        val source = listOf(
            LyricLine(
                0L, "怎么",
                listOf(LyricWord(0L, 180L, 0, 1), LyricWord(180L, 360L, 1, 2)),
            ),
            LyricLine(3001L, "不着", listOf(LyricWord(3001L, 3201L, 0, 1))),
        )
        val merged = LyricWordTiming.mergeWords(base, source)
        assertEquals(2, merged[0].words.size)
        assertTrue("差一毫秒不配", merged[1].words.isEmpty())
    }

    /**
     * 贴词**只补逐字**：正文、行数、行时间戳一个字都不动。
     *
     * 这是本轮刻意选的「贴」而不是「换一份」的原因 ——
     * 各家歌词分段不同（实测网易云 62 行、QQ / 酷狗 54 行），
     * 换一份 = 换分段版式，属于另一个改动。
     */
    @Test
    fun mergeWords_doesNotChangeTextOrLineCount() {
        val base = listOf(LyricLine(0L, "怎么"), LyricLine(3000L, "不着"))
        val source = listOf(
            LyricLine(
                0L, "完全不一样的正文",
                listOf(LyricWord(0L, 180L, 0, 1), LyricWord(180L, 360L, 1, 2)),
            ),
        )
        val merged = LyricWordTiming.mergeWords(base, source)
        assertEquals(2, merged.size)
        assertEquals("怎么", merged[0].text)
        assertEquals(3000L, merged[1].timeMs)
    }

    // ---------------------------------------------------------------- LrcParser 端到端

    /**
     * QQ 那种 `<n,n,n>` 的 LRC：标签**不再被直接剥掉**，而是走自校准挂词。
     */
    @Test
    fun lrcParser_keepsWordTagsAndAttachesWords() {
        val raw = "[00:05.00]<0,180,0>怎<180,360,0>么<360,540,0>我\n" +
            "[00:15.00]<0,200,0>不<200,400,0>着\n"
        val timeline = LrcParser.parse(raw)
        assertTrue("应当判出逐字时间", timeline.hasWordTiming)
        assertEquals("怎么我", timeline.lines[0].text)
        assertFalse("正文里不该残留标签", timeline.lines[0].text.contains("<"))
        assertEquals(3, timeline.lines[0].words.size)
        // 行首是 [00:05.00] ⇒ 5000ms
        assertEquals(5000L, timeline.lines[0].words[0].startMs)
    }

    /** 没有标签的普通 LRC ⇒ 不挂词、**行级线性照旧**（与原厂一致，不倒退）。 */
    @Test
    fun lrcParser_plainLrcFallsBackToLineLevel() {
        val raw = "[00:00.00]怎么我睡不着\n[00:10.00]你也不来救我\n"
        val timeline = LrcParser.parse(raw)
        assertFalse(timeline.hasWordTiming)
        assertEquals(0f, timeline.wipeFraction(0L, 0), eps)
        // 行级线性：下一行在 10 秒 ⇒ 1000ms / 10000ms = 10%
        assertEquals(0.1f, timeline.wipeFraction(1000L, 0), eps)
    }

    /**
     * **本轮的核心回归**：歌手提前唱完 ⇒ 高亮当场到 100%。
     *
     * 夹具里第 1 行的两个字在 5360ms 就唱完了，而**下一行 15000ms 才开始**。
     * 行级线性此时只有 3.6% ⇒ 用户看到的正是
     * **「这一句还没擦完就跳到下一句」**。
     */
    @Test
    fun lrcParser_wipeReachesFullWhenSingerFinishesEarly() {
        val raw = "[00:05.00]<0,180,0>怎<180,360,0>么\n" +
            "[00:15.00]<0,200,0>不<200,400,0>着\n"
        val timeline = LrcParser.parse(raw)
        assertTrue(timeline.hasWordTiming)
        // 字唱完 ⇒ 100%
        assertEquals(1f, timeline.wipeFraction(5360L, 0), eps)
        // 行级线性此时只有 3.6%
        val linear = (360L / (15000L - 5000L)).toFloat()
        assertTrue("行级线性此时远未擦完", linear < 0.05f)
    }

    /** 逐字模式下：**唱到一半**按当前字自己的时间窗取进度。 */
    @Test
    fun lrcParser_wipeFollowsCurrentWordWindow() {
        val raw = "[00:05.00]<0,180,0>怎<180,360,0>么\n" +
            "[00:15.00]<0,200,0>不<200,400,0>着\n"
        val timeline = LrcParser.parse(raw)
        // 5270ms = 第 2 个字「么」（5180~5360）唱到一半 ⇒ 1.5/2 个字
        assertEquals(0.75f, timeline.wipeFraction(5270L, 0), eps)
    }
}
