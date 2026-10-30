package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗 **KRC**（逐字歌词）解密 + 解析的单测。
 *
 * ## 为什么要有这组测试
 *
 * 第八十一轮用户反馈：**「一句歌词还没擦除完就到下一句了」**。
 * 根因是行级线性擦除把「唱完」绑死在「下一行开始」上，
 * 而行时间戳里没有「字怎么分」⇒ 必须用**逐字时间**。
 *
 * 逐字时间目前唯一实测可拿到的在线通道就是酷狗 KRC（加密的），
 * 所以解密 + 解析这条链路**必须有测试兜着**，
 * 否则密钥/容器格式一改就静默退化成行级线性（用户看不出问题，只会觉得「没修好」）。
 *
 * ## 夹具是怎么来的
 *
 * 用**同样的公开协议**（`krc1` 头 + 16 字节循环异或 + zlib）离线加密一段
 * 2 行的 KRC 文本，再把结果 base64 写死在这里 ——
 * 这样测试里**不需要联网**，也不需要把整首歌的 KRC 塞进仓库。
 */
class KugouKrcTest {

    /** 手工构造的 KRC 容器（base64）。解密后应当等于 [fixtureKrc]。 */
    private val fixtureBase64 =
        "a3JjMTid6kGOA8Z35rqEHB7j2lmQdtEMiOITRDN3SWK4pLZx24aETXjo7N2RTv7K4DbkX3Z3Ye1eFjRhUV+hs9NvWC9Gd7+E5z9CYSHoushTjm4Iu2YG"

    /** 夹具解密后应当得到的 KRC 文本。 */
    private val fixtureKrc =
        "[0,2879]<0,180,0>怎<180,180,0>么<360,180,0>我<540,180,0>睡\n" +
            "[3000,2000]<0,200,0>不<200,200,0>着<400,200,0>觉\n"

    private val eps = 1e-4f

    // ---------------------------------------------------------------- 解密

    /** 解密应当**逐字节还原**出 KRC 文本。 */
    @Test
    fun decrypt_restoresKrcText() {
        assertEquals(fixtureKrc, KugouKrc.decrypt(fixtureBase64))
    }

    /** 空输入 / 空白 → null（调用方回退明文 LRC）。 */
    @Test
    fun decrypt_rejectsBlank() {
        assertNull(KugouKrc.decrypt(null))
        assertNull(KugouKrc.decrypt(""))
        assertNull(KugouKrc.decrypt("   "))
    }

    /** 不是 base64 → null，不能抛异常把取词链路带崩。 */
    @Test
    fun decrypt_rejectsNonBase64() {
        assertNull(KugouKrc.decrypt("这不是 base64 !!!"))
    }

    /** base64 合法但没有 `krc1` 头 → null（不是 KRC 容器）。 */
    @Test
    fun decrypt_rejectsWrongMagic() {
        val notKrc = java.util.Base64.getEncoder().encodeToString("krc2xxxx".toByteArray())
        assertNull(KugouKrc.decrypt(notKrc))
    }

    /** 解密后必须仍是 KRC 文本，不能是乱码。 */
    @Test
    fun decrypt_resultIsReadableText() {
        val out = KugouKrc.decrypt(fixtureBase64)
        assertNotNull(out)
        assertTrue("应当含行标签", out!!.contains("[0,2879]"))
    }

    // ---------------------------------------------------------------- 解析

    /** 解析出 2 行，行首时间戳与行标签一致。 */
    @Test
    fun parse_readsLineTimestamps() {
        val lines = KugouKrc.parse(fixtureKrc)
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(3000L, lines[1].timeMs)
    }

    /** 正文 = 各字文字拼接。 */
    @Test
    fun parse_concatenatesWordText() {
        val lines = KugouKrc.parse(fixtureKrc)
        assertEquals("怎么我睡", lines[0].text)
        assertEquals("不着觉", lines[1].text)
    }

    /**
     * 逐字时间 = 行首 + 偏移，时长换算成结束时刻。
     *
     * KRC 的字标签语义是**「相对行首的偏移 + 这个字的时长」**，
     * 有权威来源（两个独立开源实现口径一致），不是猜的。
     */
    @Test
    fun parse_computesWordWindows() {
        val lines = KugouKrc.parse(fixtureKrc)
        val w0 = lines[0].words
        assertEquals(4, w0.size)
        assertEquals(0L, w0[0].startMs)
        assertEquals(180L, w0[0].endMs)
        assertEquals(180L, w0[1].startMs)
        assertEquals(360L, w0[1].endMs)
        assertEquals(540L, w0[3].startMs)
        assertEquals(720L, w0[3].endMs)
        // 第二行：偏移是**相对行首 3000** 的
        assertEquals(3000L, lines[1].words[0].startMs)
        assertEquals(3200L, lines[1].words[1].startMs)
        assertEquals(3600L, lines[1].words[2].endMs)
    }

    /** 逐字片段的字符区间应当首尾相接、覆盖整行正文。 */
    @Test
    fun parse_wordCharRangesCoverText() {
        val lines = KugouKrc.parse(fixtureKrc)
        val w = lines[0].words
        assertEquals(0, w[0].charStart)
        assertEquals(lines[0].text.length, w.last().charEnd)
        for (i in 1 until w.size) {
            assertEquals("第 $i 段应当接上一段", w[i - 1].charEnd, w[i].charStart)
        }
    }

    /** 元信息行（`[ar:...]` 之类）不该被当成歌词行。 */
    @Test
    fun parse_skipsMetadataLines() {
        val krc = "[ar:焦迈奇]\n[ti:怎么我睡不着你也不来救我]\n[0,2879]<0,180,0>怎<180,180,0>么\n"
        val lines = KugouKrc.parse(krc)
        assertEquals(1, lines.size)
        assertEquals("怎么", lines[0].text)
    }

    /** 没有逐字标签的纯文字行要**保留**（只是没有逐字时间），不能整行丢掉。 */
    @Test
    fun parse_keepsPlainLineWithoutWords() {
        val krc = "[0,2879]纯音乐\n[3000,2000]<0,200,0>不<200,200,0>着\n"
        val lines = KugouKrc.parse(krc)
        assertEquals(2, lines.size)
        assertEquals("纯音乐", lines[0].text)
        assertTrue(lines[0].words.isEmpty())
        assertEquals(2, lines[1].words.size)
    }

    /** 空文本 → 空列表。 */
    @Test
    fun parse_returnsEmptyForBlank() {
        assertTrue(KugouKrc.parse(null).isEmpty())
        assertTrue(KugouKrc.parse("").isEmpty())
        assertTrue(KugouKrc.parse("\n\n").isEmpty())
    }

    /**
     * 逐字时间**冲出本行时间窗** ⇒ 丢掉逐字数据、回退行级。
     *
     * 这条是「不猜」的兜底：拿不准的逐字数据宁可丢掉。
     */
    @Test
    fun parse_dropsWordsOutsideTimeWindow() {
        // 第一行：字拖到 10000ms，远超下一行行首 2000ms + 容差 1500ms
        val krc = "[0,1000]<0,5000,0>拖<5000,5000,0>长\n[2000,1000]<0,500,0>下\n"
        val lines = KugouKrc.parse(krc)
        assertEquals(2, lines.size)
        assertTrue("超出时间窗的逐字数据应当被丢掉", lines[0].words.isEmpty())
        assertEquals(1, lines[1].words.size)
    }

    // ---------------------------------------------------------------- 逐字擦除

    /**
     * **本轮的核心回归**：歌手提前唱完 ⇒ 高亮**当场到 100%**。
     *
     * 夹具里第 1 行的字在 720ms 就唱完了，而**下一行 3000ms 才开始**。
     * 行级线性在 720ms 时只会擦到 720/3000 = 24% ⇒ 用户看到的
     * 「这句还没擦完就跳下一句」。
     */
    @Test
    fun wipe_byWord_reachesFullWhenSingerFinishesEarly() {
        val lines = KugouKrc.parse(fixtureKrc)
        val line = lines[0]
        assertEquals("歌手已唱完，字应当全亮", 1f, line.wipeFractionByWord(720L), eps)
        // 行级线性此时只有 24% —— 正是用户抱怨的那个现象
        val linear = (720f / (lines[1].timeMs - lines[0].timeMs)).coerceIn(0f, 1f)
        assertTrue("行级线性此时远未擦完", linear < 0.3f)
    }

    /** 唱到一半 ⇒ 按**当前字自己的时间窗**取部分进度。 */
    @Test
    fun wipe_byWord_usesCurrentWordWindow() {
        val line = KugouKrc.parse(fixtureKrc)[0]
        // 270ms 落在第 2 个字「么」（180~360）的中点 ⇒ 已擦 1.5 个字 / 共 4 个
        assertEquals(0.375f, line.wipeFractionByWord(270L), eps)
    }

    /** 还没唱到第一个字 ⇒ 0。 */
    @Test
    fun wipe_byWord_isZeroBeforeFirstWord() {
        val line = KugouKrc.parse(fixtureKrc)[0]
        assertEquals(0f, line.wipeFractionByWord(-100L), eps)
    }

    /** 没有逐字数据 ⇒ 0（由上层回退行级线性）。 */
    @Test
    fun wipe_byWord_isZeroWithoutWords() {
        val line = LyricLine(0L, "纯音乐")
        assertEquals(0f, line.wipeFractionByWord(1000L), eps)
    }

    // ---------------------------------------------------------------- 时间轴接入

    /** 带逐字数据的时间轴应当报告 `hasWordTiming`。 */
    @Test
    fun timeline_reportsWordTiming() {
        val timeline = LyricTimeline(KugouKrc.parse(fixtureKrc))
        assertTrue(timeline.hasWordTiming)
    }

    /** 时间轴的擦除比例应当走逐字口径（而不是行级线性）。 */
    @Test
    fun timeline_wipeFractionUsesWordTiming() {
        val timeline = LyricTimeline(KugouKrc.parse(fixtureKrc))
        // 720ms：字唱完了 ⇒ 100%
        assertEquals(1f, timeline.wipeFraction(720L, 0), eps)
        // 0ms：字还没开始 ⇒ 0
        assertEquals(0f, timeline.wipeFraction(0L, 0), eps)
    }
}
