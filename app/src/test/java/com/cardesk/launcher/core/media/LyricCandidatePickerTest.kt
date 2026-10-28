package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线上歌词**候选挑选**的纯逻辑单测。
 *
 * 场景：第四十轮（4386）用户反馈「歌词和歌曲播放速度不匹配」，
 * 用陶喆《小镇姑娘》复现；第四十一轮按用户要求把匹配改成
 * **歌名 → 歌手 → 歌曲长度**的层级排序。
 *
 * 线上搜索里同一首歌有一堆版本，真实返回（实测 2026-10-27）：
 *
 * `
 * 酷狗 pagesize=10（参数）
 *   [0] 小镇姑娘 陶喆 295s ← 专辑原版 ✅
 *   [1] 小镇姑娘 (Live) 陶喆 270s
 *   [2] 小镇姑娘 (2023最美的夜晚会现场) 陶喆 221s
 *
 *   [8] 小镇姑娘 陶喆 213s ← 电台剪辑版 ❌
 * `
 *
 * 但**搜索顺序不稳定**（同一个关键词多调几次，网易/酷狗的返回顺序会变），
 * 而且存在「小镇姑娘 / 陶喆 / 213000ms」这种**电台剪辑版**（3:33）——
 * 歌名歌手和原版一模一样，歌词时间轴却短了一大截，
 * 挂上去以后**歌词比歌曲跑得快**，就是用户说的「速度不匹配」。
 */
class LyricCandidatePickerTest {

    private fun c(title: String, artist: String, durationMs: Long, payload: String = "p") =
        LyricCandidate(title, artist, durationMs, payload)

    // ---------------------------------------------------------------- 层级判定

    /** 歌名 tier：完全相同 3 / 互相包含 2 / 去数字后相同 1 / 否则 0。 */
    @Test
    fun titleTier_coversFourLevels() {
        assertEquals(LyricCandidatePicker.TITLE_EXACT, LyricCandidatePicker.titleTier("小镇姑娘", "小镇姑娘"))
        assertEquals(
            LyricCandidatePicker.TITLE_CONTAINS,
            LyricCandidatePicker.titleTier("小镇姑娘", "小镇姑娘 (Live)"),
        )
        // 「小镇姑娘 2」是「小镇姑娘」的扩展 → 互相包含（2），不是「去数字后相同」
        assertEquals(
            LyricCandidatePicker.TITLE_CONTAINS,
            LyricCandidatePicker.titleTier("小镇姑娘", "小镇姑娘 2"),
        )
        // 两边编号不同、互不包含 → 去数字后相同（1）
        assertEquals(
            LyricCandidatePicker.TITLE_LOOSE,
            LyricCandidatePicker.titleTier("小镇姑娘 2", "小镇姑娘 3"),
        )
        assertEquals(LyricCandidatePicker.TITLE_NONE, LyricCandidatePicker.titleTier("小镇姑娘", "找自己"))
    }

    /** 歌手 tier：完全相同 2 / 互相包含 1 / 不匹配 0；任一为空 → 2（中性）。 */
    @Test
    fun artistTier_treatsEmptyAsNeutral() {
        assertEquals(LyricCandidatePicker.ARTIST_EXACT, LyricCandidatePicker.artistTier("陶喆", "陶喆"))
        assertEquals(LyricCandidatePicker.ARTIST_CONTAINS, LyricCandidatePicker.artistTier("陶喆", "陶喆乐队"))
        assertEquals(LyricCandidatePicker.ARTIST_MISMATCH, LyricCandidatePicker.artistTier("陶喆", "许梦宸"))
        // 任一为空 → 中性，不参与判定
        assertEquals(LyricCandidatePicker.ARTIST_EXACT, LyricCandidatePicker.artistTier("", "陶喆"))
        assertEquals(LyricCandidatePicker.ARTIST_EXACT, LyricCandidatePicker.artistTier("陶喆", ""))
    }

    /** 时长 tier：≤2s 2 / ≤5s 1 / ≤15s 0 / >15s -1；歌曲时长未知 → 2（中性）。 */
    @Test
    fun durationTier_hasFourLevels() {
        assertEquals(LyricCandidatePicker.DURATION_EXACT, LyricCandidatePicker.durationTier(295666, 295000))
        assertEquals(LyricCandidatePicker.DURATION_CLOSE, LyricCandidatePicker.durationTier(295666, 293000))
        assertEquals(LyricCandidatePicker.DURATION_NEAR, LyricCandidatePicker.durationTier(295666, 285000))
        assertEquals(LyricCandidatePicker.DURATION_FAR, LyricCandidatePicker.durationTier(295666, 213000))
        // 歌曲时长未知 → 中性
        assertEquals(LyricCandidatePicker.DURATION_EXACT, LyricCandidatePicker.durationTier(0, 213000))
        // 候选没给时长 → 中性
        assertEquals(LyricCandidatePicker.DURATION_EXACT, LyricCandidatePicker.durationTier(295666, 0))
    }

    // ----------------------------------------------------------------口径：逐条打分取最高分

    /**
     * 遍历**全部**候选逐条打分，取最高分。
     *
     * 之前我们「取第一条能解析出歌词的」，这里锁死新口径。
     */
    @Test
    fun pickBest_takesHighestScoreNotFirst() {
        val candidates = listOf(
            c("小镇姑娘 (Live)", "陶喆", 270813, "live"),
            c("小镇姑娘", "你的九儿", 194995, "other"),
            c("小镇姑娘", "陶喆", 295666, "orig"),
        )
        val best = LyricCandidatePicker.pickBest("小镇姑娘", "陶喆", 295666, candidates)
        assertNotNull(best)
        assertEquals("orig", best!!.payload)
    }

    /** 门槛：最高分 < `LyricMatch.MIN_SCORE`(80) → 一个都不给。 */
    @Test
    fun pickBest_rejectsAllBelowThreshold() {
        val candidates = listOf(
            c("小镇姑娘 (Live)", "陶喆", 270813, "live"),
            c("小镇姑娘 (温柔女声版)", "许梦宸", 176000, "cover"),
        )
        assertNull(LyricCandidatePicker.pickBest("小镇姑娘", "陶喆", 295666, candidates))
    }

    // ---------------------------------------------------------------- 时长分池：电台剪辑版

    /**
     * **本轮的核心回归**：歌名歌手都一样的「电台剪辑版」（213s），
     * 目标时长已知（295666ms）时必须被排除，选中 295s 原版。
     */
    @Test
    fun pickBest_dropsRadioEditWhenSongDurationKnown() {
        val candidates = listOf(
            c("小镇姑娘", "陶喆", 213000, "radio-edit"),
            c("小镇姑娘", "陶喆", 295666, "album"),
        )
        val best = LyricCandidatePicker.pickBest("小镇姑娘", "陶喆", 295666, candidates)
        assertNotNull(best)
        assertEquals("album", best!!.payload)
    }

    /**
     * 目标时长未知（车机会话还没推来 DURATION，本地音乐来源很常见）时，
     * 同分的候选取**更长**的 —— 电台剪辑版永远比原版短。
     */
    @Test
    fun pickBest_takesLongerOnTieWhenDurationUnknown() {
        val candidates = listOf(
            c("小镇姑娘", "陶喆", 213000, "radio-edit"),
            c("小镇姑娘", "陶喆", 295666, "album"),
        )
        val best = LyricCandidatePicker.pickBest("小镇姑娘", "陶喆", 0, candidates)
        assertNotNull(best)
        assertEquals("album", best!!.payload)
    }

    /**
     * 时长硬过滤**不能把候选全干掉**：万一渠道给的时长都不准，
     * 宁可给一份可能不准的歌词，也不给「没有歌词」。
     */
    @Test
    fun pickBest_fallsBackWhenDurationFilterWouldEmptyPool() {
        val candidates = listOf(
            c("小镇姑娘", "陶喆", 213000, "radio-edit"),
        )
        val best = LyricCandidatePicker.pickBest("小镇姑娘", "陶喆", 295666, candidates)
        assertNotNull(best)
        assertEquals("radio-edit", best!!.payload)
    }

    /**
     * 时长**分池**：只保留「时长档位最好」的那批候选。
     *
     * 原版（差 0ms，档位 2）在池子里；差 12.6s 的那条（档位 0）不在。
     */
    @Test
    fun rank_keepsOnlyBestDurationTier() {
        val candidates = listOf(
            c("小镇姑娘", "陶喆", 295666, "album"),
            c("小镇姑娘", "陶喆", 283000, "close-enough"),
        )
        val ranked = LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates)
        assertEquals(1, ranked.size)
        assertEquals("album", ranked.first().candidate.payload)
    }

    // ---------------------------------------------------------------- 层级优先级：歌名 → 歌手 → 时长

    /**
     * **歌名优先**：歌名完全相同、歌手不匹配的那条，
     * 排在「歌名只是包含、歌手完全匹配」的那条**前面**（用户明确要求「首先要匹配歌名」）。
     */
    @Test
    fun rank_titleBeatsArtist() {
        val candidates = listOf(
            // 「小镇姑娘 2」：歌名是包含关系、歌手完全匹配
            c("小镇姑娘 2", "陶喆", 295666, "contains-title"),
            // 「小镇姑娘」：歌名完全相同、歌手不匹配
            c("小镇姑娘", "群星", 295666, "cover-by-group"),
        )
        val ranked = LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates)
        assertEquals(2, ranked.size)
        assertEquals("cover-by-group", ranked.first().candidate.payload)
        assertEquals(
            LyricCandidatePicker.TITLE_EXACT,
            ranked.first().tier.title,
        )
        // 歌名完全相同的那条，歌手 tier 反而更低 —— 说明排序确实是「先歌名后歌手」
        assertEquals(
            LyricCandidatePicker.ARTIST_MISMATCH,
            ranked.first().tier.artist,
        )
    }

    /** **歌手次之**：歌名同级时，歌手完全匹配的排前面。 */
    @Test
    fun rank_artistBreaksTitleTie() {
        val candidates = listOf(
            c("小镇姑娘", "群星", 295666, "cover-by-group"),
            c("小镇姑娘", "陶喆", 295666, "album"),
        )
        val ranked = LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates)
        assertEquals("album", ranked.first().candidate.payload)
    }

    /** **三者都匹配**的版本一定排最前（层级 3,2,2）。 */
    @Test
    fun rank_allThreeMatchWins() {
        val candidates = listOf(
            c("小镇姑娘 (Live)", "陶喆", 270813, "live"),
            c("小镇姑娘", "陶喆", 213000, "radio-edit"),
            c("小镇姑娘", "陶喆", 295666, "album"),
        )
        val ranked = LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates)
        assertEquals("album", ranked.first().candidate.payload)
        val t = ranked.first().tier
        assertEquals(LyricCandidatePicker.TITLE_EXACT, t.title)
        assertEquals(LyricCandidatePicker.ARTIST_EXACT, t.artist)
        assertEquals(LyricCandidatePicker.DURATION_EXACT, t.duration)
    }

    // ---------------------------------------------------------------- 版本噪声 / 歌手不符

    /** 带版本噪声的候选（Live / DJ / 温柔女声版…）分数被压到门槛以下 → 淘汰。 */
    @Test
    fun pickBest_rejectsVersionNoiseCandidates() {
        val candidates = listOf(
            c("小镇姑娘 (Live)", "陶喆", 270813, "live"),
            c("小镇姑娘 (DJ Bo版)", "陶喆", 223000, "dj"),
            c("小镇姑娘", "陶喆", 295666, "album"),
        )
        val ranked = LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates)
        assertEquals(1, ranked.size)
        assertEquals("album", ranked.first().candidate.payload)
    }

    /** 别人的翻唱 → 歌手不匹配 → 淘汰。 */
    @Test
    fun pickBest_rejectsOtherSinger() {
        val candidates = listOf(
            c("小镇姑娘", "你的九儿", 194995, "cover"),
            c("小镇姑娘", "陶喆", 295666, "album"),
        )
        val ranked = LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates)
        assertEquals(1, ranked.size)
        assertEquals("album", ranked.first().candidate.payload)
    }

    // ---------------------------------------------------------------- 边界

    /** 分数高的排前面（分数优先于时长）。 */
    @Test
    fun rank_ordersByScoreFirst() {
        val candidates = listOf(
            // 群星合辑版：歌名一样、歌手不同 → 分数被扣，但仍过门槛；时长档位与原版相同
            c("小镇姑娘", "群星", 295666, "cover-by-group"),
            c("小镇姑娘", "陶喆", 295666, "album"),
        )
        val ranked = LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates)
        assertEquals(2, ranked.size)
        assertEquals("album", ranked.first().candidate.payload)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    /** 空候选 → 空结果（不抛异常）。 */
    @Test
    fun rank_handlesEmptyCandidates() {
        assertTrue(LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, emptyList()).isEmpty())
    }

    /** 候选列表里全是不合格候选 → 空结果。 */
    @Test
    fun rank_returnsEmptyWhenNothingPasses() {
        val candidates = listOf(c("完全不同的歌", "别人", 100000, "x"))
        assertTrue(LyricCandidatePicker.rank("小镇姑娘", "陶喆", 295666, candidates).isEmpty())
    }

    // ---------------------------------------------------------------- 跨渠道择优

    /**
     * 跨渠道择优：**三家层级完全打平**时，按「歌曲长度贴合度」决胜。
     *
     * 真实场景（实测 2026-10-27）：
     * - 网易给毫秒级时长 295666ms
     * - QQ / 酷狗只给到秒 295000ms（interval=295 → ×1000）
     *
     * 三家的歌名 / 歌手 / 时长档位都是 (3, 2, 2)，分数也都是 205。
     * 按用户要的「然后是歌曲长度」，**贴合歌长的网易**应该赢。
     */
    @Test
    fun matchComparator_prefersTighterDurationAcrossProviders() {
        val entries = LyricCandidatePicker.scoreAll(
            "小镇姑娘", "陶喆", 295666,
            listOf(
                c("小镇姑娘", "陶喆", 295000, "qq"),       // 差 666ms
                c("小镇姑娘", "陶喆", 295666, "netease"),  // 差 0ms
            ),
        )
        val best = entries.sortedWith(
            LyricCandidatePicker.matchComparator("小镇姑娘", "陶喆", 295666),
        ).first()
        assertEquals("netease", best.candidate.payload)
        // 两条层级完全一致 —— 说明决胜确实来自「长度贴合度」这一维
        assertEquals(entries[0].tier, entries[1].tier)
    }

    /** 跨渠道择优：**歌名优先** —— 歌名更准的那家赢，哪怕它的时长差一点。 */
    @Test
    fun matchComparator_titleBeatsDuration() {
        val entries = LyricCandidatePicker.scoreAll(
            "小镇姑娘", "陶喆", 295666,
            listOf(
                c("小镇姑娘 (Live)", "陶喆", 295000, "live"),  // 歌名 tier=2，时长更贴
                c("小镇姑娘", "陶喆", 296000, "album"),        // 歌名 tier=3，时长差 334ms
            ),
        )
        val best = entries.sortedWith(
            LyricCandidatePicker.matchComparator("小镇姑娘", "陶喆", 295666),
        ).first()
        assertEquals("album", best.candidate.payload)
    }

    /** 跨渠道择优：**歌手次之** —— 歌名打平时歌手完全匹配的那家赢。 */
    @Test
    fun matchComparator_artistBeatsDuration() {
        val entries = LyricCandidatePicker.scoreAll(
            "小镇姑娘", "陶喆", 295666,
            listOf(
                c("小镇姑娘", "群星", 295666, "cover"),  // 歌手不匹配，时长一样
                c("小镇姑娘", "陶喆", 296000, "album"),  // 歌手匹配，时长差 334ms
            ),
        )
        val best = entries.sortedWith(
            LyricCandidatePicker.matchComparator("小镇姑娘", "陶喆", 295666),
        ).first()
        assertEquals("album", best.candidate.payload)
    }

    /** 跨渠道择优：歌曲时长**未知**时不做精确贴合度比较，改为取更长（剪辑版更短）。 */
    @Test
    fun matchComparator_longerWinsWhenSongDurationUnknown() {
        val entries = LyricCandidatePicker.scoreAll(
            "小镇姑娘", "陶喆", 0,
            listOf(
                c("小镇姑娘", "陶喆", 213000, "radio-edit"),
                c("小镇姑娘", "陶喆", 295666, "album"),
            ),
        )
        val best = entries.sortedWith(
            LyricCandidatePicker.matchComparator("小镇姑娘", "陶喆", 0),
        ).first()
        assertEquals("album", best.candidate.payload)
    }

    /** 跨渠道择优：时长差超过 15s 的那家（电台剪辑版）排到最后。 */
    @Test
    fun matchComparator_radioEditRanksLast() {
        val entries = LyricCandidatePicker.scoreAll(
            "小镇姑娘", "陶喆", 295666,
            listOf(
                c("小镇姑娘", "陶喆", 213000, "radio-edit"),
                c("小镇姑娘", "陶喆", 295666, "album"),
            ),
        )
        val ordered = entries.sortedWith(
            LyricCandidatePicker.matchComparator("小镇姑娘", "陶喆", 295666),
        )
        assertEquals("album", ordered.first().candidate.payload)
        assertEquals("radio-edit", ordered.last().candidate.payload)
    }
}
