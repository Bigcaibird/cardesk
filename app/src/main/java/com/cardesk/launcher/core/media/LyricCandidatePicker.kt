package com.cardesk.launcher.core.media

import kotlin.math.abs

/**
 * 线上歌词候选：搜索结果里的一条歌。
 *
 * 第四十轮新增 —— 这是「歌词和歌曲播放速度不匹配」的**核心数据结构**。
 *
 * ## 为什么需要它
 *
 * 三个线上渠道（网易 / QQ / 酷狗）的搜索结果里，**同一首歌会同时出现好几个版本**：
 *
 * | 歌名 | 歌手 | 时长 | 说明 |
 * |---|---|---|---|
 * | 小镇姑娘 | 陶喆 | 295666ms | 专辑原版 ✅ |
 * | 小镇姑娘 | 陶喆 | 213000ms | **电台剪辑版**（3:33）❌ |
 * | 小镇姑娘 (Live) | 陶喆 | 270813ms | 现场版 ❌ |
 * | 小镇姑娘 (温柔女声版) | 许梦宸 | 176000ms | 别人的翻唱 ❌ |
 *
 * 之前我们「遍历到**第一条**能解析出歌词的就 return」，而搜索结果的**顺序并不稳定**
 * （同一个关键词，网易 / 酷狗的返回顺序每次都可能不同）。于是可能给一首 4:55 的歌
 * 挂上 3:33 电台版的歌词 —— 歌词时间轴被压缩，**歌词比歌曲跑得快**，
 * 用户看到的就是「歌词和歌曲播放速度不匹配」。
 *
 * ##正确做法
 *
 * `OnlineLyricCatalog`
 * 三家渠道都是**遍历全部候选 → 逐条 `LyricMatch.score` → 取最高分**，
 * 最高分低于 `MIN_SCORE(80)` 才放弃；**先选歌，再取词**。
 *
 * 这里把那段逻辑抽成纯 Kotlin（不碰网络），既能，又能单测。
 */
data class LyricCandidate(
    /** 候选歌名（渠道返回的原始值）。 */
    val title: String,
    /** 候选歌手（渠道返回的原始值）。 */
    val artist: String,
    /** 候选自带的时长（ms）；渠道给不出时为 0。 */
    val durationMs: Long,
    /** 渠道自用的取词凭据：网易 `id` / QQ `songmid` / 酷狗 `FileHash`。 */
    val payload: String,
)

/**
 * 候选与目标歌曲的**匹配层级**（第四十一轮按用户要求改成层级排序）。
 *
 * 用户要求：「**首先要匹配歌名，其次要匹配歌手，然后是歌曲长度，
 * 优先取三者都匹配的版本**」。
 *
 * 所以这里不再是三个维度**加权求和**（`LyricMatch.score` 的做法）
 * 而是三个维度**分层比较**：
 *
 * `
 * 歌名 tier 3 = 归一化后完全相同
 *            2 = 互相包含（「小镇姑娘」 vs 「小镇姑娘 (Live)」）
 *            1 = 去掉数字后仍相同（「小镇姑娘」 vs 「小镇姑娘 2」）
 *            0 = 不是同一首
 * 歌手 tier 2 = 完全相同（任一为空 → 2 = 中性，不参与判定）
 *            1 = 互相包含
 *            0 = 不匹配
 * 时长 tier 2 = 差 ≤ 2s （歌曲时长未知 → 2 = 中性）
 *            1 = 差 ≤ 5s
 *            0 = 差 ≤ 15s
 *           -1 = 差 > 15s（判为「另一个版本」：电台剪辑版 / 加速版 / 合辑截取）
 * `
 *
 * 排序按 tier **逐层降序**（歌名 → 歌手 → 时长），最后才比 `LyricMatch.score`。
 * 这样「三者都匹配」的版本（3, 2, 2）一定排在最前面。
 */
data class MatchTier(val title: Int, val artist: Int, val duration: Int)

/**
 * 候选挑选（纯逻辑，JVM 可单测）。
 *
 * 挑选分三步：
 *
 * 1. **逐条打分** ——：标题 100/55/-80
 *    歌手 ±70/±45/-45，时长 ≤2s +35 / ≤5s +30 / ≤15s +5 / 否则 -20，
 *    候选带版本噪声（伴奏/翻唱/现场/live/dj…）而目标不带 → 再 -60。
 *    分数 < `MIN_SCORE(80)` 的候选直接淘汰（门槛）。
 * 2. **时长分池** —— 只保留「时长档位最好」的那批候选。
 *    歌曲时长已知时，差 >15s 的候选是**另一个版本**（电台剪辑版 / 加速版），
 *    它们的歌词时间轴会被压缩 → 挂上去就是「歌词和歌曲速度不匹配」。
 *    全都超差时不做任何过滤（宁可给可能不准的歌词，也不给没有歌词）。
 * 3. **分层排序** —— 歌名 tier → 歌手 tier → 时长 tier → `LyricMatch.score`；
 *    歌曲时长**未知**时同分再取**更长**的（电台剪辑版永远比原版短）。
 */
object LyricCandidatePicker {

    /**
     * 「同一个版本」的时长容差。
     *
     * `LyricMatch.score` 的时长分档：≤2s +35、≤5s +30、≤15s +5、>15s -20
     * 这里沿用它的 **15s** 口径作为「算不算同一个版本」的界线。
     */
    const val DURATION_TOLERANCE_MS = 15_000L

    // ---- 歌名 tier ----

    /** 归一化后完全相同。 */
    const val TITLE_EXACT = 3

    /** 互相包含（「小镇姑娘」 vs 「小镇姑娘 (Live)」）。 */
    const val TITLE_CONTAINS = 2

    /** 去掉数字后仍相同（「小镇姑娘」 vs 「小镇姑娘 2」）。 */
    const val TITLE_LOOSE = 1

    /** 不是同一首。 */
    const val TITLE_NONE = 0

    // ---- 歌手 tier ----

    /** 完全相同；任一为空时也用这个值（中性，不参与判定）。 */
    const val ARTIST_EXACT = 2

    /** 互相包含。 */
    const val ARTIST_CONTAINS = 1

    /** 不匹配。 */
    const val ARTIST_MISMATCH = 0

    // ---- 时长 tier ----

    /** 差 ≤ 2s；歌曲时长未知时也用这个值（中性）。 */
    const val DURATION_EXACT = 2

    /** 差 ≤ 5s。 */
    const val DURATION_CLOSE = 1

    /** 差 ≤ 15s。 */
    const val DURATION_NEAR = 0

    /** 差 > 15s —— 另一个版本。 */
    const val DURATION_FAR = -1

    /** 候选 + 分数 + 匹配层级。 */
    data class Scored(val candidate: LyricCandidate, val score: Int, val tier: MatchTier)

    /** 去掉数字（用于「小镇姑娘 2」这种同名不同版本的粗匹配）。 */
    private val DIGITS = Regex("\\d+")

    /** 逐条打分 + 算层级（分数口径完全`LyricMatch.score`）。 */
    fun scoreAll(
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
        candidates: List<LyricCandidate>,
    ): List<Scored> = candidates.map {
        Scored(
            it,
            LyricMatch.score(
                wantTitle, wantArtist, wantDurationMs,
                it.title, it.artist, it.durationMs,
            ),
            tierOf(wantTitle, wantArtist, wantDurationMs, it),
        )
    }

    /** 歌名 tier：完全相同 3 / 互相包含 2 / 去数字后相同 1 / 否则 0。 */
    fun titleTier(wantTitle: String, gotTitle: String): Int {
        val a = LyricMatch.normalize(wantTitle)
        val b = LyricMatch.normalize(gotTitle)
        if (a.isEmpty() || b.isEmpty()) return TITLE_LOOSE
        if (a == b) return TITLE_EXACT
        if (a.contains(b) || b.contains(a)) return TITLE_CONTAINS
        val a2 = a.replace(DIGITS, "")
        val b2 = b.replace(DIGITS, "")
        if (a2.isNotEmpty() && a2 == b2) return TITLE_LOOSE
        return TITLE_NONE
    }

    /** 歌手 tier：完全相同 2 / 互相包含 1 / 不匹配 0；任一为空 → 2（中性）。 */
    fun artistTier(wantArtist: String, gotArtist: String): Int {
        val a = LyricMatch.normalize(wantArtist)
        val b = LyricMatch.normalize(gotArtist)
        if (a.isEmpty() || b.isEmpty()) return ARTIST_EXACT
        if (a == b) return ARTIST_EXACT
        if (a.contains(b) || b.contains(a)) return ARTIST_CONTAINS
        return ARTIST_MISMATCH
    }

    /** 时长 tier：≤2s 2 / ≤5s 1 / ≤15s 0 / >15s -1；歌曲时长未知 → 2（中性）。 */
    fun durationTier(wantDurationMs: Long, gotDurationMs: Long): Int {
        if (wantDurationMs <= 0 || gotDurationMs <= 0) return DURATION_EXACT
        val diff = abs(wantDurationMs - gotDurationMs)
        return when {
            diff <= 2_000L -> DURATION_EXACT
            diff <= 5_000L -> DURATION_CLOSE
            diff <= DURATION_TOLERANCE_MS -> DURATION_NEAR
            else -> DURATION_FAR
        }
    }

    /** 三个维度的层级。 */
    fun tierOf(
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
        candidate: LyricCandidate,
    ): MatchTier = MatchTier(
        title = titleTier(wantTitle, candidate.title),
        artist = artistTier(wantArtist, candidate.artist),
        duration = durationTier(wantDurationMs, candidate.durationMs),
    )

    /**
     * 匹配度的比较器：**歌名 → 歌手 → 时长档位 → 时长贴合度（精确差值）→ 分数**。
     *
     * 第四十一轮新增 —— 网易 / QQ / 酷狗三家渠道各自选出的最优候选，
     * 歌名、歌手、时长档位可能**完全打平**（三家都是原版）。
     * 这时候按用户要求的顺序继续往下比：**歌曲长度要更贴合**。
     *
     * 贴合度的实际差别：网易给毫秒级时长（295666ms），
     * QQ / 酷狗只给到秒（295000ms），同一首歌差 666ms —— 网易更准。
     *
     * 歌曲时长未知时不做精确贴合度比较，改为「取更长」（电台剪辑版永远比原版短）。
     */
    fun matchComparator(
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
    ): Comparator<Scored> {
        val byTier = compareByDescending<Scored> { it.tier.title }
            .thenByDescending { it.tier.artist }
            .thenByDescending { it.tier.duration }
            .thenByDescending { it.score }
        return if (wantDurationMs > 0) {
            byTier.thenBy { abs(wantDurationMs - it.candidate.durationMs) }
        } else {
            byTier.thenByDescending { it.candidate.durationMs }
        }
    }

    /** 过滤 + 排序（见类注释的三步）。 */
    fun rank(
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
        candidates: List<LyricCandidate>,
    ): List<Scored> {
        val passed = scoreAll(wantTitle, wantArtist, wantDurationMs, candidates)
            .filter { it.score >= LyricMatch.MIN_SCORE }
        if (passed.isEmpty()) return emptyList()

        // 时长分池：只留「时长档位最好」的那批。
        // 歌曲时长未知时所有候选都是中性档（2），池子不变。
        val bestDuration = passed.maxOf { it.tier.duration }
        val pool = passed.filter { it.tier.duration == bestDuration }

        return pool.sortedWith(matchComparator(wantTitle, wantArtist, wantDurationMs))
    }

    /** 取最优候选（层级最高且过门槛）；没有合格候选时返回 null。 */
    fun pickBest(
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
        candidates: List<LyricCandidate>,
    ): LyricCandidate? = rank(wantTitle, wantArtist, wantDurationMs, candidates).firstOrNull()?.candidate
}
