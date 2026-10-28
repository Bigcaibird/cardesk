package com.cardesk.launcher.core.media

import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.JsonLite
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次线上取词的结果（带来源信息，供事后复核）。
 *
 * 第四十轮新增 [sourceDurationMs]：被选中候选**自带的时长**。
 * 车机会话的总时长常常晚几百毫秒到几秒才推过来（诊断包实测晚 3.1 秒），
 * 取词时拿到的时长可能是 0 —— 那时就没法判别「原版 vs 电台剪辑版」。
 * 记下候选自己的时长，等真实时长到齐后就能复核：
 * 「选中的版本长度」和「歌曲实际长度」差太远 → 说明挂错了版本 → 重取。
 */
data class OnlineLyricResult(
    val timeline: LyricTimeline,
    /** 命中的渠道名（netease / qq / kugou / netease-id）。 */
    val provider: String,
    /** 该候选的匹配分数（`LyricMatch.score`）。 */
    val score: Int,
    /** 被选中候选自带的时长（ms）；渠道给不出时为 0。 */
    val sourceDurationMs: Long,
)

/**
 * 在线歌词目录：还原多来源竞速。
 *
 * 供应商：网易云 / QQ 音乐 / 酷狗（顺序按来源包调整），三家并行查询，
 * 相似度门槛 [LyricMatch.MIN_SCORE] = 80；
 * 结果缓存 30 分钟；用代数号（generation）丢弃过期结果，避免切歌后旧结果覆盖新歌。
 *
 * 缓存键：**优先用 mediaId**，没有才用清洗后的标题。
 *
 * ## 第四十轮：先选歌、再取词
 *
 * 之前三家渠道都是「遍历搜索结果 → **第一条能解析出歌词的就 return**」，
 * 完全没看歌名 / 歌手 / 时长 —— 搜索结果的**顺序又不稳定**，于是可能给
 * 一首 4:55 的歌挂上 3:33 **电台剪辑版**的歌词，歌词时间轴被压缩，
 * 表现就是用户说的「**歌词和歌曲播放速度不匹配**」。
 *
 * 现在改成`OnlineLyricCatalog` 的口径
 * - 搜索拿 **10 条**候选 → 逐条 `LyricMatch.score` → **取最高分**（门槛 80）；
 * - 目标时长已知时再做一次**时长硬过滤**（差 >15s 判为另一个版本）；
 * - 同分时目标时长未知取**更长**的（电台剪辑版永远更短）；
 * - **先选歌，再按选中的那一条去取词**。
 *
 * ## 第四十一轮：跨渠道统一择优
 *
 * 第四十轮「先选歌再取词」只在**渠道内部**做（每家各自选自己的最优）。
 * 三家选出的是同一首歌（原版）时，歌名 / 歌手 / 时长档位**完全打平**，
 * 谁先返回纯看网络运气 —— 实测 QQ 的 72 行（末行 @288250）就靠网络快，
 * 赢过网易的 78 行（末行 @295242，贴着歌长）。
 *
 * 而用户要的优先级是「**歌名 → 歌手 → 歌曲长度**」，长度这一维必须能在
 * **渠道之间**也比。所以流程改成：
 *
 * 1. **并行选歌**：三家各发一次搜索请求，各自跑一遍 [LyricCandidatePicker.rank]；
 * 2. **跨渠道择优**：把各家的候选放一起，按「歌名 → 歌手 → 时长档位
 *    时长贴合度（精确差值）→ 分数」排一次（[LyricCandidatePicker.matchComparator]）；
 * 3. **依次取词**：按全局顺序取词，第一个拿到歌词的即命中；
 *    同一家渠道最多试 [MAX_FETCH_PER_PROVIDER] 条，免得一家把次数用光。
 *
 * 这样既是「先选歌、再取词」，又比多做了一步跨渠道择优 ——
 * 详见 `analysis/findings/06-parity-round2.md`「偏离」表。
 */
object OnlineLyricCatalog {

    const val RACE_BUDGET_MS = 4_500L
    const val CACHE_TTL_MS = 30 * 60_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 10; CarDesk) AppleWebKit/537.36"

    /**
     * 选歌阶段预算（各家并行搜索，最多等这么久就进入择优）。
     *
     * 超时不等满：已到的候选先参与择优，总好过干等一家卡住的搜索。
     */
    const val SEARCH_BUDGET_MS = 1_500L

    /** 取词阶段预算（从开始取词算起）。 */
    const val FETCH_BUDGET_MS = 4_500L

    /** 单次 HTTP 超时（毫秒），用于兜底超时换算。 */
    private const val HTTP_TIMEOUT_MS = 3_000L

    /** 搜索条数（limit=10 / n=10 / pagesize=10）。 */
    private const val SEARCH_LIMIT = 10

    /** 最多尝试几个候选去取词（第一个没歌词就试下一个）。 */
    private const val MAX_FETCH_TRY = 4

    /** 同一家渠道最多试几条（别让一家渠道把次数用光，其他家没机会试）。 */
    private const val MAX_FETCH_PER_PROVIDER = 2

    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "lyric-race").apply { isDaemon = true } }
    private val generation = AtomicLong(0)
    private val main = Handler(Looper.getMainLooper())

    private data class CacheEntry(
        val timeline: LyricTimeline,
        val at: Long,
        val provider: String,
        val score: Int,
        val sourceDurationMs: Long,
    )
    private val cache = LinkedHashMap<String, CacheEntry>()

    private class Hit(
        val provider: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val lrc: LyricTimeline,
        val score: Int,
    )

    /** 某家渠道选好的一条候选（带渠道名，跨渠道择优时用）。 */
    private class Picked(
        val provider: Provider,
        val scored: LyricCandidatePicker.Scored,
    )

    /** 查询歌词；命中回调一次性触发（主线程），失败回调 null。 */
    fun load(
        title: String,
        artist: String,
        durationMs: Long,
        sourcePkg: String?,
        mediaId: String? = null,
        callback: (OnlineLyricResult?) -> Unit,
    ) {
        val key = buildSongKey(mediaId, title, artist)
        cache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.at < CACHE_TTL_MS) {
                Logx.i(
                    LogTags.MEDIA, "歌词缓存命中",
                    "key" to key, "title" to title, "artist" to artist,
                    "lines" to entry.timeline.lines.size,
                )
                callback(
                    OnlineLyricResult(
                        entry.timeline, entry.provider, entry.score, entry.sourceDurationMs,
                    ),
                )
                return
            }
            cache.remove(key)
        }
        if (title.isBlank()) {
            Logx.w(LogTags.MEDIA, "歌词目录请求放弃：歌名为空", "key" to key)
            callback(null)
            return
        }
        val gen = generation.incrementAndGet()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver(hit: Hit?) {
            if (!finished.compareAndSet(false, true)) return
            if (generation.get() != gen) {
                // 切歌后旧歌的结果回来了 → 丢弃（代数号机制）
                Logx.d(LogTags.MEDIA, "丢弃过期歌词结果（代数号不匹配）", "gen" to gen, "key" to key)
                return
            }
            if (hit == null) {
                Logx.i(
                    LogTags.MEDIA, "歌词竞速未命中（预算内没有可用结果）",
                    "key" to key, "budgetMs" to RACE_BUDGET_MS, "title" to title,
                )
                callback(null)
                return
            }
            cache[key] = CacheEntry(hit.lrc, System.currentTimeMillis(), hit.provider, hit.score, hit.durationMs)
            trimCache()
            Logx.i(
                LogTags.MEDIA, "歌词竞速命中",
                "provider" to hit.provider, "score" to hit.score,
                "lines" to hit.lrc.lines.size, "key" to key,
                "sourceDurationMs" to hit.durationMs,
            )
            callback(
                OnlineLyricResult(
                    hit.lrc, hit.provider, hit.score, hit.durationMs,
                ),
            )
        }
        val providers = providerOrder(sourcePkg)
        // 排障场景：歌词不对 / 不出来，这里能看到发了哪些渠道、用的哪首歌
        Logx.i(
            LogTags.MEDIA, "在线歌词目录请求",
            "title" to title, "artist" to artist, "durationMs" to durationMs,
            "sourcePkg" to (sourcePkg ?: ""), "key" to key,
            "providers" to providers.joinToString(",") { it.name },
        )

        // ---------------- 阶段一：并行「选歌」（各家一次搜索请求） ----------------
        val picked = java.util.concurrent.ConcurrentLinkedQueue<Picked>()
        val remaining = java.util.concurrent.atomic.AtomicInteger(providers.size)
        val proceedStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        fun proceed() {
            if (!proceedStarted.compareAndSet(false, true)) return
            val all = picked.toList()
            if (all.isEmpty()) {
                Logx.i(LogTags.MEDIA, "歌词跨渠道择优：没有可用候选", "key" to key, "title" to title)
                deliver(null)
                return
            }
            // 跨渠道择优：歌名 → 歌手 → 时长档位 → 时长贴合度 → 分数
            val comparator = LyricCandidatePicker.matchComparator(title, artist, durationMs)
            val ordered = all.sortedWith { a, b -> comparator.compare(a.scored, b.scored) }
            Logx.d(
                LogTags.MEDIA, "歌词跨渠道择优",
                "key" to key, "title" to title, "artist" to artist,
                "wantDurationMs" to durationMs, "candidates" to all.size,
                "ordered" to ordered.take(6).joinToString(" | ") {
                    "${it.provider.name}:${it.scored.candidate.title}/${it.scored.candidate.artist}/" +
                        "${it.scored.candidate.durationMs}ms" +
                        "(t=${it.scored.tier.title},${it.scored.tier.artist},${it.scored.tier.duration}" +
                        " s=${it.scored.score})"
                },
            )
            // ---------------- 阶段二：按全局顺序依次取词 ----------------
            pool.execute {
                val perProvider = HashMap<String, Int>()
                var tried = 0
                val fetchStart = System.currentTimeMillis()
                for (item in ordered) {
                    if (tried >= MAX_FETCH_TRY) break
                    if (System.currentTimeMillis() - fetchStart > FETCH_BUDGET_MS) {
                        Logx.i(
                            LogTags.MEDIA, "歌词取词预算用尽，停止尝试",
                            "key" to key, "tried" to tried, "budgetMs" to FETCH_BUDGET_MS,
                        )
                        break
                    }
                    val used = perProvider[item.provider.name] ?: 0
                    if (used >= MAX_FETCH_PER_PROVIDER) continue
                    perProvider[item.provider.name] = used + 1
                    tried++
                    val c = item.scored.candidate
                    val lrc = try {
                        item.provider.fetchLyric(c)
                    } catch (t: Throwable) {
                        Logx.w(
                            LogTags.MEDIA, "歌词渠道异常", t,
                            "provider" to item.provider.name, "title" to title,
                        )
                        null
                    }
                    if (lrc == null || lrc.isEmpty) {
                        Logx.i(
                            LogTags.MEDIA, "歌词渠道候选无歌词，换下一条",
                            "provider" to item.provider.name, "gotTitle" to c.title,
                            "gotArtist" to c.artist, "score" to item.scored.score,
                        )
                        continue
                    }
                    Logx.i(
                        LogTags.MEDIA, "歌词渠道命中",
                        "provider" to item.provider.name, "score" to item.scored.score,
                        // 第四十一轮：层级（歌名 → 歌手 → 时长），越大越匹配
                        "tierTitle" to item.scored.tier.title,
                        "tierArtist" to item.scored.tier.artist,
                        "tierDuration" to item.scored.tier.duration,
                        "gotTitle" to c.title, "gotArtist" to c.artist,
                        "sourceDurationMs" to c.durationMs,
                        "lines" to lrc.lines.size,
                    )
                    val hit = Hit(
                        item.provider.name, c.title, c.artist, c.durationMs, lrc, item.scored.score,
                    )
                    main.post { deliver(hit) }
                    return@execute
                }
                main.post { deliver(null) }
            }
        }

        for (provider in providers) {
            pool.execute {
                try {
                    val ranked = provider.pick(title, artist, durationMs)
                    for (s in ranked) picked.add(Picked(provider, s))
                } catch (t: Throwable) {
                    Logx.w(LogTags.MEDIA, "歌词渠道异常", t, "provider" to provider.name, "title" to title)
                } finally {
                    if (remaining.decrementAndGet() == 0) main.post { proceed() }
                }
            }
        }
        // 选歌阶段预算：某家搜索卡住时不拖累整体
        main.postDelayed({ proceed() }, SEARCH_BUDGET_MS)
        // 兜底：整体预算到期仍未命中 → 交空结果
        main.postDelayed({ deliver(null) }, SEARCH_BUDGET_MS + FETCH_BUDGET_MS + HTTP_TIMEOUT_MS)

        // 网易系来源 + mediaId 能解析出歌曲 id
        // 直接按 id 取词（精确匹配，不会有版本错配），优先级最高。
        if (isNeteasePackage(sourcePkg)) {
            val songId = LyricMatch.parseSongId(mediaId)
            if (songId > 0) {
                pool.execute {
                    try {
                        val lrc = NeteaseProvider.fetchById(songId)
                        if (lrc == null || lrc.isEmpty) {
                            Logx.i(LogTags.MEDIA, "网易直连取词无歌词", "songId" to songId)
                            return@execute
                        }
                        val hit = Hit(
                            "netease-id", title, artist, durationMs, lrc,
                            LyricMatch.score(title, artist, durationMs, title, artist, durationMs),
                        )
                        main.post { deliver(hit) }
                    } catch (t: Throwable) {
                        Logx.w(LogTags.MEDIA, "网易直连取词异常", t, "songId" to songId)
                    }
                }
            }
        }
    }

    fun invalidate() {
        generation.incrementAndGet()
        Logx.d(LogTags.MEDIA, "歌词目录缓存失效（代数号 +1）")
    }

    /**
     * 丢掉某一首歌的缓存（复核发现挂错版本时用）。
     *
     * 第四十轮新增：车机会话的总时长晚到时，会复核「选中的版本长度」，
     * 发现对不上就 evict 掉重新取一次。
     */
    fun evict(mediaId: String?, title: String, artist: String) {
        val key = buildSongKey(mediaId, title, artist)
        if (cache.remove(key) != null) {
            Logx.i(LogTags.MEDIA, "歌词目录缓存逐条清除", "key" to key)
        }
    }

    /**
     * 把别处拿到的歌词写进同一份缓存。
     *
     * 里「在线目录」和「酷狗旧接口兜底」共用一份 []
     * （命中就复用，
     *  写回，这里保持同样的行为：
     * 兜底通道拿到的歌词也进缓存，避免同一首歌反复走网络。
     *
     * 只接受「可用」的歌词（`isUsableLyric` 判断）。
     */
    fun put(mediaId: String?, title: String, artist: String, timeline: LyricTimeline) {
        if (!LyricMatch.isUsableLyric(timeline.lines)) {
            Logx.d(LogTags.MEDIA, "歌词写入目录缓存被拒（不是可用歌词）", "title" to title)
            return
        }
        val key = buildSongKey(mediaId, title, artist)
        cache[key] = CacheEntry(timeline, System.currentTimeMillis(), "kugou-legacy", 0, 0L)
        Logx.d(LogTags.MEDIA, "歌词写入目录缓存", "key" to key, "lines" to timeline.lines.size)
        trimCache()
    }

    private fun trimCache() {
        while (cache.size > 8) {
            val first = cache.keys.firstOrNull() ?: break
            cache.remove(first)
        }
    }

    /** 有 mediaId 用 `id:` 前缀，否则用 `t:` + 清洗后的标题。 */
    fun buildSongKey(mediaId: String?, title: String, artist: String): String =
        LyricMatch.buildSongKey(mediaId, LyricMatch.clean(title, artist).title)

    private fun providerOrder(sourcePkg: String?): List<Provider> = when (sourcePkg) {
        "com.kugou.android.auto" -> listOf(KugouProvider, NeteaseProvider, QqProvider)
        "com.tencent.qqmusiccar" -> listOf(QqProvider, NeteaseProvider, KugouProvider)
        else -> listOf(NeteaseProvider, QqProvider, KugouProvider)
    }

    private fun isNeteasePackage(pkg: String?): Boolean {
        val lower = pkg?.lowercase() ?: return false
        return lower.contains("netease") || lower.contains("cloudmusic")
    }

    // ---------------------------------------------------------------- providers

    private interface Provider {
        /** 渠道名（写日志用，便于在真机日志里区分 QQ / 网易 / 酷狗 三路结果）。 */
        val name: String

        /** 搜索候选歌（：三家都取 10 条）。 */
        fun search(title: String, artist: String): List<LyricCandidate>

        /** 按候选取歌词。 */
        fun fetchLyric(candidate: LyricCandidate): LyricTimeline?

        /**
         * **只选歌、不取词**（第四十一轮：跨渠道先选歌 → 统一择优 → 最后取词）。
         *
         * ① 搜索 → ② 逐条打分（门槛 [LyricMatch.MIN_SCORE]）→ ③ 时长分池，
         * 返回按匹配度排好的候选列表，交给上层做**跨渠道**择优。
         *
         * 为什么不各家自己取词再赛跑：三家选出的是同一首歌（原版），
         * 歌名 / 歌手 / 时长档位完全打平，谁先返回纯看网络运气 ——
         * 实测 QQ 的 72 行（末行 @288250）就靠网络快，赢过网易的 78 行（末行 @295242）。
         * 而用户的优先级是「歌名 → 歌手 → **歌曲长度**」，
         * 长度这一维必须能在渠道之间也比。
         */
        fun pick(title: String, artist: String, durationMs: Long): List<LyricCandidatePicker.Scored> {
            val candidates = search(title, artist)
            if (candidates.isEmpty()) {
                Logx.i(LogTags.MEDIA, "歌词渠道搜索无结果", "provider" to name, "title" to title)
                return emptyList()
            }
            val ranked = LyricCandidatePicker.rank(title, artist, durationMs, candidates)
            if (ranked.isEmpty()) {
                // 排障场景：搜索有结果但都没过相似度门槛 —— 多半是「同名不同歌」
                Logx.i(
                    LogTags.MEDIA, "歌词渠道候选全部低于门槛",
                    "provider" to name, "minScore" to LyricMatch.MIN_SCORE,
                    "candidates" to candidates.size, "title" to title,
                )
                return emptyList()
            }
            // 排障场景：把每个候选的层级都打出来 —— 「为什么选了这条 / 没选那条」
            Logx.d(
                LogTags.MEDIA, "歌词候选排序",
                "provider" to name, "wantTitle" to title, "wantArtist" to artist,
                "wantDurationMs" to durationMs,
                "ranked" to ranked.joinToString(" | ") {
                    "${it.candidate.title}/${it.candidate.artist}/${it.candidate.durationMs}" +
                        "(score=${it.score} t=${it.tier.title},${it.tier.artist},${it.tier.duration})"
                },
            )
            return ranked
        }
    }

    private fun http(url: String, referer: String? = null, timeoutMs: Int = 3000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        if (referer != null) conn.setRequestProperty("Referer", referer)
        return try {
            val stream = conn.inputStream
            val buffer = ByteArrayOutputStream()
            stream.use { it.copyTo(buffer) }
            String(buffer.toByteArray(), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** 表单 POST（网易搜索接口要求 POST + Referer）。 */
    private fun postForm(url: String, referer: String, body: String, timeoutMs: Int = 3000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", referer)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = conn.inputStream
            val buffer = ByteArrayOutputStream()
            stream.use { it.copyTo(buffer) }
            String(buffer.toByteArray(), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decodeBase64(s: String): String = runCatching {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrDefault("")

    /** 网易云：搜索 → 歌词接口。 */
    private object NeteaseProvider : Provider {
        override val name: String = "netease"

        override fun search(title: String, artist: String): List<LyricCandidate> {
            // POST 表单 + Referer + limit=10
            val body = "s=${enc("$title $artist")}&type=1&limit=$SEARCH_LIMIT&offset=0"
            val search = postForm(
                "https://music.163.com/api/search/get/web", "https://music.163.com/", body,
            )
            val root = JsonLite.parseObject(search) ?: return emptyList()
            val songs = (root["result"] as? Map<*, *>)?.get("songs") as? List<*> ?: return emptyList()
            val out = ArrayList<LyricCandidate>(songs.size)
            for (raw in songs) {
                val song = raw as? Map<String, Any?> ?: continue
                val artists = song["artists"] as? List<*> ?: song["ar"] as? List<*>
                val artistName = (artists?.firstOrNull() as? Map<String, Any?>)
                    ?.let { JsonLite.str(it, "name") }.orEmpty()
                val id = JsonLite.long(song, "id")
                if (id <= 0) continue
                out.add(
                    LyricCandidate(
                        title = JsonLite.str(song, "name"),
                        artist = artistName,
                        durationMs = JsonLite.long(song, "duration"),
                        // 网易的 id 是**数字**（150619），不是字符串 —— 用 str 取会拿到空串，
                        // 整条渠道就永远取不到词（诊断包日志「歌词渠道未命中 provider=netease」）。
                        payload = id.toString(),
                    ),
                )
            }
            return out
        }

        override fun fetchLyric(candidate: LyricCandidate): LyricTimeline? {
            val id = candidate.payload.toLongOrNull() ?: return null
            return fetchById(id)
        }

        fun fetchById(id: Long): LyricTimeline? {
            val json = http(
                "https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1",
                referer = "https://music.163.com/",
            )
            val lrcObj = JsonLite.parseObject(json)?.get("lrc") as? Map<String, Any?>
            val raw = JsonLite.str(lrcObj, "lyric")
            return LrcParser.parse(raw)
        }
    }

    /** QQ 音乐：搜索 → 歌词接口（明文 LRC）。 */
    private object QqProvider : Provider {
        override val name: String = "qq"

        override fun search(title: String, artist: String): List<LyricCandidate> {
            // `?p=1&n=10&w=<kw>&format=json` + Referer
            val search = http(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=$SEARCH_LIMIT" +
                    "&w=${enc("$title $artist")}&format=json",
                referer = "https://y.qq.com/",
            )
            val root = JsonLite.parseObject(LyricMatch.firstJsonCallback(search)) ?: return emptyList()
            val song = (root["data"] as? Map<*, *>)?.get("song") as? Map<*, *>
            val list = song?.get("list") as? List<*> ?: return emptyList()
            val out = ArrayList<LyricCandidate>(list.size)
            for (raw in list) {
                val item = raw as? Map<String, Any?> ?: continue
                val singer = (item["singer"] as? List<*>)?.firstOrNull() as? Map<String, Any?>
                out.add(
                    LyricCandidate(
                        title = JsonLite.str(item, "songname"),
                        artist = singer?.let { JsonLite.str(it, "name") }.orEmpty(),
                        durationMs = JsonLite.long(item, "interval") * 1000,
                        payload = JsonLite.str(item, "songmid"),
                    ),
                )
            }
            return out
        }

        override fun fetchLyric(candidate: LyricCandidate): LyricTimeline? {
            // `nobase64=1` → 明文 LRC
            val json = http(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${enc(candidate.payload)}&format=json&nobase64=1",
                referer = "https://y.qq.com/",
            )
            val raw = JsonLite.str(JsonLite.parseObject(LyricMatch.firstJsonCallback(json)), "lyric")
            if (raw.isBlank()) return null
            // 个别情况下仍回 base64，这里兜底解一次
            if (raw.contains('[')) {
                val plain = LrcParser.parse(raw)
                if (!plain.isEmpty) return plain
            }
            return LrcParser.parse(decodeBase64(raw))
        }
    }

    /** 酷狗：搜索 → 歌词搜索（按 hash）→ 歌词下载（base64）。 */
    private object KugouProvider : Provider {
        override val name: String = "kugou"

        override fun search(title: String, artist: String): List<LyricCandidate> {
            // pagesize=10 + userid=-1 + platform=WebFilter
            val search = http(
                "https://songsearch.kugou.com/song_search_v2?keyword=${enc("$title $artist")}" +
                    "&page=1&pagesize=$SEARCH_LIMIT&userid=-1&clientver=&platform=WebFilter",
                referer = "https://www.kugou.com/",
            )
            val root = JsonLite.parseObject(search) ?: return emptyList()
            val list = (root["data"] as? Map<*, *>)?.get("lists") as? List<*> ?: return emptyList()
            val out = ArrayList<LyricCandidate>(list.size)
            for (raw in list) {
                val item = raw as? Map<String, Any?> ?: continue
                out.add(
                    LyricCandidate(
                        title = JsonLite.str(item, "SongName"),
                        artist = JsonLite.str(item, "SingerName"),
                        durationMs = JsonLite.long(item, "Duration") * 1000,
                        payload = JsonLite.str(item, "FileHash"),
                    ),
                )
            }
            return out
        }

        override fun fetchLyric(candidate: LyricCandidate): LyricTimeline? {
            // 按 **hash** 查歌词候选 → 取第一条 → 下载
            val lrcSearch = http(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${enc(candidate.payload)}",
                referer = "https://www.kugou.com/",
            )
            val cand = (JsonLite.parseObject(lrcSearch)?.get("candidates") as? List<*>)?.firstOrNull()
                as? Map<String, Any?> ?: return null
            val id = JsonLite.str(cand, "id")
            val accessKey = JsonLite.str(cand, "accesskey")
            if (id.isEmpty() || accessKey.isEmpty()) return null
            val download = http(
                "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}" +
                    "&accesskey=${enc(accessKey)}&fmt=lrc&charset=utf8",
                referer = "https://www.kugou.com/",
            )
            val content = JsonLite.str(JsonLite.parseObject(download), "content")
            if (content.isEmpty()) return null
            return LrcParser.parse(decodeBase64(content))
        }
    }
}
