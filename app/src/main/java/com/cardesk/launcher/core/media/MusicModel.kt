package com.cardesk.launcher.core.media

import android.graphics.Bitmap
import com.cardesk.launcher.core.JsonLite

/**
 * 一条可显示的曲目状态（来自，见MusicSessionTracker）。
 *
 * `mediaId` 对应 `MediaMetadata.MEDIA_ID`
 * 歌词缓存键优先用它。
 */
data class TrackInfo(
    val pkg: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val artwork: Bitmap? = null,
    val sourceId: String = "",
    val mediaId: String = "",
    /**
     * 车机会话给的封面 URI。
     *
     * 会话只给 URI 不给 bitmap 时
     * 按 URI 加载封面；两个都没有才走网络搜索。
     * 空串 = 会话没给。
     */
    val artworkUri: String = "",
    /**
     * **播放器自己带的歌词**（的 metadata / description extras 里带的 LRC）。
     *
     * 第四十一轮新增：这类歌词跟播放的是同一份，权威性最高，
     * 有就用它，不联网。空串 = 播放器没带（大多数车机都不带）。
     */
    val playerLyric: String = "",
)

/** 一行歌词及其时间戳（ms）。 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 歌词时间轴：给定播放位置算出「当前行 / 上一行 / 下一行 / 当前行擦除比例」。
 *
 */
class LyricTimeline(val lines: List<LyricLine>) {

    val isEmpty: Boolean get() = lines.isEmpty()

    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun lineAt(index: Int): String = lines.getOrNull(index)?.text.orEmpty()

    /**
     * 当前行擦除比例 0..1。
     *
     * 末行的跨度取**剩余时长**（曲目总长），总长不可用时退化为 +5000ms
     *
     */
    fun wipeFraction(positionMs: Long, index: Int, durationMs: Long = 0L): Float {
        val line = lines.getOrNull(index) ?: return 0f
        var end = lines.getOrNull(index + 1)?.timeMs ?: durationMs
        if (end <= line.timeMs) end = line.timeMs + 5000L
        val span = (end - line.timeMs).coerceAtLeast(1L)
        val p = ((positionMs - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
        return p
    }
}

/**
 * LRC 解析：`[mm:ss.xx]text`，支持一行多个时间戳。
 *
 * **第三十三轮**（这是「歌词不同步」的一个真凶）
 *
 * 解析是
 *
 * `java
 * Pattern.compile("\\[(\\d{2}):(\\d{2})(\\.(\\d{1,3}))?\\](.+)")
 *     matcher(str
 *         replaceAll("<\\d+,\\d+,\\d+>", "") // ① 剥掉逐字时间标签
 *         replaceAll("\\[(id|ti|ar|al|by|offset|total|hash|sign|qq|language):.*?\\]", "")) // ② 剥掉元数据标签
 * `
 *
 * - ① QQ 音乐 / 网易的 LRC 行首会带 `<起始毫秒,结束毫秒,时长毫秒>` 这种**逐字时间标签**，
 *   直接**删掉**；我们之前没删，歌词正文前面就带着 `<0,0,0>` 这种垃圾。
 * - ②把 `[offset:...]` 当**元数据标签删掉**，**不把偏移量算进时间戳**
 *   我们之前是**把 offset 加到时间戳上** —— 歌词整体前后平移几秒，
 *   表现就是「歌词跟唱对不上」。**这一条就是不同步的根因之一。**
 *
 * 这里照口径改：剥掉 `<n,n,n>`、剥掉元数据标签（**含 offset**，不参与时间戳计算）。
 */
object LrcParser {

    /** QQ 音乐 / 网易的逐字时间标签：`<1234,2345,100>`。 */
    private val WORD_TAG = Regex("<\\d+,\\d+,\\d+>")

    /** 元数据标签（**含 offset** ——是删掉，不是应用）。 */
    private val META_TAG = Regex("\\[(id|ti|ar|al|by|offset|total|hash|sign|qq|language):.*?]")

    /** 时间戳：`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]`。 */
    private val TIME_TAG = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String?): LyricTimeline {
        if (raw.isNullOrBlank()) return LyricTimeline(emptyList())
        // ① 剥掉逐字时间标签 ② 剥掉元数据标签（含 offset，口径：**不应用偏移**）
        val cleaned = META_TAG.replace(WORD_TAG.replace(raw, ""), "")
        val out = ArrayList<LyricLine>(64)
        for (line in cleaned.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val matches = TIME_TAG.findAll(trimmed).toList()
            if (matches.isEmpty()) continue
            val text = trimmed.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                out.add(LyricLine(min * 60_000 + sec * 1000 + frac, text))
            }
        }
        out.sortBy { it.timeMs }
        return LyricTimeline(out)
    }
}

/**
 * 歌名 / 歌手匹配与歌词文本清洗。
 *
 * 全部`LyricMatch`
 * - `clean`:29-43（标题 + 歌手成对清洗，标题里带「- 歌手」时拆开）
 * - `score`:150-184（标题 100/55/-80，歌手 ±70/±45，时长 35/30/5/-20，版本噪声 -60）
 * - `stripNoise`:186-191（去扩展名 → 去尾部方括号标签 → 去 track 前缀）
 */
object LyricMatch {

    const val MIN_SCORE = 80

    /** `com.bumptech.glide.request.target.Target.SIZE_ORIGINAL` = -1。 */
    const val EMPTY_SCORE = -1

    private val TRACK_PREFIX = Regex("""(?i)^(?:\d{1,3}(?:\s*[-.)]\s*|\s+)|track\s*\d+\s*[-.]?\s*)""")
    private val EXTENSION = Regex("""(?i)\.(mp3|flac|wav|m4a|aac|ogg|wma|ape|dsf|dff|aiff)$""")
    private val BRACKET_TAG = Regex("""(?i)\s*[\[(](flac|wav|ape|dsd|hi[-]?res|explicit)[\])]\s*$""")

    private val PUNCT_OR_SPACE = Regex("""[\p{P}\s]+""")
    private val DIGITS = Regex("""\d+""")
    private val TITLE_ARTIST_SPLIT = Regex("""\s+[-–—]\s+""")
    private val VERSION_NOISE = Regex("""(?i).*(伴奏|翻唱|现场|升调|降调|live|remix|dj|sped|slowed|ktv).*""")

    /** 清洗后的「标题 + 歌手」二元组（LyricMatch.Query）。 */
    data class Query(val title: String, val artist: String)

    /**
     *
     * 标题去掉噪声；歌手为空时把标题里的「歌名 - 歌手」拆开。
     */
    fun clean(title: String?, artist: String?): Query {
        var t = stripNoise(title)
        var a = artist?.trim() ?: ""
        if (a.isEmpty()) {
            val parts = t.split(TITLE_ARTIST_SPLIT, limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                t = parts[0].trim()
                a = stripNoise(parts[1])
            }
        } else {
            a = stripNoise(a)
        }
        return Query(t, a)
    }

    /** 只清洗标题（保留旧的单参调用）。 */
    fun clean(input: String?): String = clean(input, null).title

    /**
     *
     * ① 去文件扩展名 → ② 去尾部方括号标签（[FLAC] 之类） → ③ 去「01.」「Track 2」前缀。
     *
     * 注意与旧实现不同：括号**不是整段删**，只有尾部那类标签会被删。
     */
    fun stripNoise(input: String?): String {
        if (input == null) return ""
        var s = input.trim()
        s = EXTENSION.replace(s, "")
        s = BRACKET_TAG.replace(s, "")
        s = TRACK_PREFIX.replaceFirst(s, "")
        return s.trim()
    }

    /** 小写 + 去掉所有标点与空白。 */
    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return PUNCT_OR_SPACE.replace(input.lowercase(), "")
    }

    /**
     *
     *
     * QQ 音乐的接口有时会把 JSON 包在 JSONP 回调里（`callback({...})`），
     * 这里截出第一对大括号之间的 JSON 主体。
     */
    fun firstJsonCallback(raw: String?): String {
        if (raw == null) return ""
        val s = raw.trim()
        val i = s.indexOf('{')
        val j = s.lastIndexOf('}')
        return if (i < 0 || j <= i) s else s.substring(i, j + 1)
    }

    /** 歌曲 id：4 位以上的纯数字，且左右不挨着字母/数字。 */
    private val SONG_ID = Regex("(?<![A-Za-z0-9])(\\d{4,})(?![A-Za-z0-9])")

    /**
     * 从 mediaId / URI 里抠出歌曲 id。
     *
     * 网易系来源（网易云音乐）拿到的 mediaId 里通常就带歌曲 id，
     * 可以直接按 id 取歌词。
     */
    fun parseSongId(raw: String?): Long {
        if (raw.isNullOrBlank()) return -1L
        val m = SONG_ID.find(raw.trim()) ?: return -1L
        return m.groupValues[1].toLongOrNull() ?: -1L
    }

    fun hasVersionNoise(input: String?): Boolean =
        VERSION_NOISE.matches(input?.lowercase() ?: "")

    fun looksLikeLyricLine(input: String?): Boolean {
        if (input == null) return false
        val t = input.trim()
        if (t.length < 6) return false
        if (t.contains(" - ") || t.contains(" – ") || t.contains(" — ")) return false
        if (cjkCount(t) < 6) return false
        return t.contains("，") || t.contains("。") || t.contains("？") || t.contains("！") ||
            t.contains(",") || t.contains("...") || t.contains("…") || t.contains("、")
    }

    /**
     *
     * 「这串文字更像一句歌词而不是歌名」→ 视为**不稳定标题**。
     */
    fun isLikelyLyricTitle(input: String?): Boolean {
        if (input == null || input.trim().isEmpty()) return true
        if (looksLikeLyricLine(input)) return true
        val t = input.trim()
        val hasPunctuation = t.contains("，") || t.contains("。") || t.contains("；") ||
            t.contains("！") || t.contains("、") || t.contains("…") || t.contains("...") ||
            t.contains(",")
        if (!hasPunctuation) {
            if (t.length >= 16 && !t.contains(" - ") && !t.contains(" – ") &&
                !t.contains(" — ") && !t.contains("-")
            ) {
                if (cjkCount(t) >= 10) return true
            }
            return false
        }
        return true
    }

    fun isPlaceholderLyricText(input: String?): Boolean {
        if (input == null) return true
        val t = input.trim()
        return t.isEmpty() || t == "暂无歌词" || t == "纯音乐，请欣赏" || t == "纯音乐请欣赏" ||
            t == "此歌曲为没有填词的纯音乐，请您欣赏"
    }

    /** 至少有一行不是占位文案。 */
    fun isUsableLyric(lines: List<LyricLine>?): Boolean {
        if (lines.isNullOrEmpty()) return false
        return lines.any { !isPlaceholderLyricText(it.text) }
    }

    /** 这类来源的「标题」字段不可信。 */
    fun isLyricTitleSource(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val lower = pkg.lowercase()
        return lower.contains("aiquting") || lower.contains("qqmusictv") ||
            lower.contains("wecarflow") || lower.contains("ecarx.multimedia") ||
            lower.contains("bluetooth") || lower.contains("btmusic") || lower.contains(".bt.") ||
            lower.contains("a2dp") || lower.contains("avrcp") || lower.contains("usb") ||
            lower.contains("udisk") || lower.contains("u-disk") || lower.contains("localmusic") ||
            lower.contains("local.music") || lower.contains("local_music") ||
            (lower.contains("multimedia") && (lower.contains("local") || lower.contains("usb"))) ||
            (lower.contains("ecarx") && lower.contains("music") &&
                (lower.contains("local") || lower.contains("usb") || lower.contains("file")))
    }

    /**
     *
     *
     * 标题：完全相同 100；互相包含 55；否则 -80。
     * 歌手：完全相同 +70；互相包含 +45；否则 -45（仅当两边都非空）。
     * 时长：差 ≤2s +35；≤5s +30；≤15s +5；否则 -20。
     * 版本噪声：候选带噪声（伴奏/翻唱/live…）而期望不带 → 再 -60。
     */
    fun score(
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
        gotTitle: String,
        gotArtist: String,
        gotDurationMs: Long,
    ): Int {
        val wt = normalize(wantTitle)
        val gt = normalize(gotTitle)
        if (wt.isEmpty() || gt.isEmpty()) return EMPTY_SCORE
        var s = when {
            wt == gt -> 100
            wt.contains(gt) || gt.contains(wt) -> 55
            else -> -80
        }
        val wa = normalize(wantArtist)
        val ga = normalize(gotArtist)
        if (wa.isNotEmpty() && ga.isNotEmpty()) {
            s = when {
                wa == ga -> s + 70
                wa.contains(ga) || ga.contains(wa) -> s + 45
                else -> s - 45
            }
        }
        if (wantDurationMs > 0 && gotDurationMs > 0) {
            val diff = kotlin.math.abs(wantDurationMs - gotDurationMs)
            s += when {
                diff <= 2000L -> 35
                diff <= 5000L -> 30
                diff <= 15000L -> 5
                else -> -20
            }
        }
        return if (hasVersionNoise(wantTitle) || !hasVersionNoise(gotTitle)) s else s - 60
    }

    /**
     *
     * 有 mediaId 就用 `id:<mediaId>`；否则用 `t:<标题>`（标题为空时用「未知歌曲」）。
     */
    fun buildSongKey(mediaId: String?, title: String?): String {
        val id = mediaId?.trim() ?: ""
        if (id.isNotEmpty() && id != "0" && id != "-1") return "id:$id"
        val t = title?.trim() ?: ""
        return "t:" + if (t.isEmpty()) "未知歌曲" else t
    }

    /**
     * **粗**匹配歌名。
     *
     * 归一化后相等 / 互相包含 → 命中；否则把数字都去掉再比一次
     * （「夜航星」对「夜航星 2」这类同名不同版本能匹配上）。
     *
     * 酷狗旧接口兜底用它挑文件 hash。
     */
    fun titleRoughlyMatches(want: String?, got: String?): Boolean {
        val a = normalize(want)
        val b = normalize(got)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true
        val a2 = a.replace(DIGITS, "")
        val b2 = b.replace(DIGITS, "")
        if (a2.isEmpty() || b2.isEmpty()) return false
        return a2.contains(b2) || b2.contains(a2)
    }

    private fun cjkCount(s: String): Int {
        var n = 0
        for (c in s) {
            if (c.code in 19968..40959) n++
        }
        return n
    }
}

/**
 * MediaMetadata 的取值链（纯 Kotlin 快照，便于 JVM 单测）。
 *
 *
 * - `resolveTrackName`
 * - `resolveArtistName`
 * - `hasMetadata` MusicSessionTracker:332-337
 */
object MediaText {

    /** MediaMetadata / MediaDescription 的纯数据快照。 */
    data class Meta(
        val title: String = "",
        val displayTitle: String = "",
        val artist: String = "",
        val albumArtist: String = "",
        val album: String = "",
        val subtitle: String = "",
        val descriptionTitle: String = "",
        val mediaId: String = "",
        val durationMs: Long = 0L,
        /**
         * 车机会话给的封面 URI。
         *
         *
         * 两个都没有才落到网络搜索（`CoverArtSearch`）。
         */
        val artworkUri: String = "",
        /**
         * 播放器自带的歌词（第四十一轮新增）。
         *
         * **没有**这条通道（它读 `MediaDescription` 只取 title / subtitle）
         * 这里作为增强补上；只有能解析出带时间戳的 LRC 才认。
         */
        val playerLyric: String = "",
    )

    /** TITLE 或 DISPLAY_TITLE 任一非空。 */
    fun hasMetadata(meta: Meta?): Boolean =
        meta != null && (meta.title.isNotEmpty() || meta.displayTitle.isNotEmpty())

    /** 「不稳定标题」= 更像一句歌词。 */
    fun unstableTitle(title: String?): Boolean = LyricMatch.isLikelyLyricTitle(title)

    /**
     * 「稳定歌名」（纯函数）。
     *
     * `lastTitle` 只保存**稳定**标题 —— 不稳定标题（更像一句歌词）
     * 一律当作没有歌名。持久化上次歌曲状态时用的是这个值
     * （写的是 `this.lastTitle`）。
     */
    fun stableSongTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val t = title.trim()
        if (unstableTitle(t)) return ""
        return t
    }

    fun hasTitleArtistSeparator(value: String): Boolean =
        value.contains('-') || value.contains('–') || value.contains('—') ||
            value.contains('·') || value.contains('•') || value.contains('‧')

    fun looksLikeTitleArtistCombo(
        value: String?,
        artist: String?,
        title: String?,
        fallback: String?,
    ): Boolean {
        if (value.isNullOrEmpty()) return false
        val t = value.trim()
        if (!hasTitleArtistSeparator(t)) return false
        var titleRef = title
        if (titleRef.isNullOrEmpty()) titleRef = fallback
        if (!titleRef.isNullOrEmpty() && !unstableTitle(titleRef) && t.contains(titleRef.trim())) return true
        val a = artist
        if (a.isNullOrEmpty() || !t.contains(a.trim()) || t.length <= a.trim().length + 2) {
            return Regex("""(?s).{1,80}\s*[-–—·•‧]\s*.{1,80}""").matches(t)
        }
        return true
    }

    fun unwrapTitleArtistCombo(value: String?, displayTitle: String?, title: String?): String {
        if (value.isNullOrEmpty()) return value.orEmpty()
        if (!looksLikeTitleArtistCombo(value, null, displayTitle, title)) return value
        val parts = value.trim().split(Regex("""\s*[-–—·•‧]\s*"""), limit = 2)
        return if (parts.size != 2 || parts[1].isEmpty()) value else parts[1].trim()
    }

    fun firstStableSongTitle(primary: String?, secondary: String?): String? {
        if (!primary.isNullOrEmpty() && !unstableTitle(primary)) return primary
        if (secondary.isNullOrEmpty() || unstableTitle(secondary)) return null
        return secondary
    }

    /**
     *
     * - 普通来源：MediaDescription.title → TITLE；
     * - 「歌词型来源」（本地/蓝牙/USB 等）：DISPLAY_TITLE → TITLE（跳过不稳定标题），
     *   并且在允许回退时保留上一首歌名。
     */
    fun resolveTrackName(
        meta: Meta?,
        lastTitle: String?,
        lyricTitleSource: Boolean,
        allowFallback: Boolean,
    ): String? {
        if (meta == null) return if (lastTitle.isNullOrEmpty()) null else lastTitle
        if (!lyricTitleSource) {
            if (meta.descriptionTitle.isNotEmpty()) return meta.descriptionTitle
            return meta.title
        }
        if (!allowFallback && !lastTitle.isNullOrEmpty()) return lastTitle
        val first = firstStableSongTitle(meta.displayTitle, meta.title)
        if (!first.isNullOrEmpty()) return first
        if (lastTitle.isNullOrEmpty()) {
            return if (meta.displayTitle.isNotEmpty()) meta.displayTitle else meta.title
        }
        return lastTitle
    }

    /**
     *
     * ARTIST → ALBUM_ARTIST → MediaDescription.subtitle → ALBUM_ARTIST 兜底；
     * 「歌词型来源」还会把「歌名 - 歌手」这类组合串拆出歌手部分。
     */
    fun resolveArtistName(meta: Meta?, lyricTitleSource: Boolean): String? {
        if (meta == null) return null
        val display = meta.displayTitle
        val title = meta.title
        val artist = meta.artist
        if (lyricTitleSource) {
            if (artist.isNotEmpty()) return unwrapTitleArtistCombo(artist, display, title)
            if (meta.albumArtist.isNotEmpty()) return unwrapTitleArtistCombo(meta.albumArtist, display, title)
        }
        val subtitle = meta.subtitle
        if (subtitle.isNotEmpty()) {
            if (!lyricTitleSource || !looksLikeTitleArtistCombo(subtitle, artist, display, title)) {
                return subtitle
            }
            val unwrapped = unwrapTitleArtistCombo(subtitle, display, title)
            if (unwrapped != subtitle) return unwrapped
        }
        return if (artist.isNotEmpty()) artist else meta.albumArtist
    }
}

/**
 * QQ 车机歌词广播
 *
 *
 * **只**认一个固定 extra、**只**认 `command.method == "update_lyric"`
 * 且要求当前绑定的是 QQ 音乐 —— 解析逻辑单独抽出来是为了能纯 JVM 单测。
 */
object QqLyric {

    const val ACTION = "com.tencent.qqmusiccar.action.PLAY_COMMAND_SEND_FOR_THIRD"

    const val EXTRA = "com.tencent.qqmusiccar.EXTRA_COMMAND_DATA"

    /** 只解析 `command.method == "update_lyric"` 的 `data.lyric`，其余一律返回空串。 */
    fun parseLyric(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val root = JsonLite.parseObject(raw) ?: return ""
        val command = root["command"] as? Map<String, Any?> ?: return ""
        if (JsonLite.str(command, "method") != "update_lyric") return ""
        val data = command["data"] as? Map<String, Any?> ?: return ""
        return JsonLite.str(data, "lyric")
    }

    fun isQqMusicPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val lower = pkg.lowercase()
        if (lower.contains("wecarflow") || lower.contains("aiquting") || lower.contains("qqmusictv")) return false
        return lower.contains("qqmusic")
    }
}

/**
 * 从封面取强调色。
 *
 * 打分 = ((v * 0.65) + 0.35) * s²，在封面 10%..90% 区域按 1/16 步长采样，
 * 取分数最高的那个像素的色相，饱和度/明度分别抬到 ≥0.72 / ≥0.78。
 */
object CoverAccent {

    /** 0xFFB8743A。 */
    const val DEFAULT_ACCENT = 0xFFB8743A.toInt()

    /** 从一批 ARGB 像素里挑强调色（纯逻辑，可单测）。 */
    fun pick(pixels: IntArray): Int {
        if (pixels.isEmpty()) return DEFAULT_ACCENT
        var best = 0f
        var bestH = 0f
        var bestS = 0f
        var bestV = 0f
        for (p in pixels) {
            if ((p ushr 24) < 80) continue
            val hsv = rgbToHsv(p)
            val score = ((hsv[2] * 0.65f) + 0.35f) * hsv[1] * hsv[1]
            if (hsv[2] >= 0.12f && score > best) {
                best = score
                bestH = hsv[0]
                bestS = hsv[1]
                bestV = hsv[2]
            }
        }
        if (best < 0.04f) return DEFAULT_ACCENT
        val s = maxOf(0.72f, minOf(1.0f, bestS * 1.12f))
        val v = maxOf(0.78f, minOf(1.0f, bestV * 1.08f))
        return hsvToColor(bestH, s, v)
    }

    /** 从封面位图采样（10%..90% 区域，步长 宽/16、高/16）。 */
    fun sample(bitmap: Bitmap?): Int {
        val bmp = bitmap ?: return DEFAULT_ACCENT
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2) return DEFAULT_ACCENT
        val stepX = maxOf(1, w / 16)
        val stepY = maxOf(1, h / 16)
        val pixels = ArrayList<Int>(256)
        var y = h / 10
        while (y < (h * 9) / 10) {
            var x = w / 10
            while (x < (w * 9) / 10) {
                pixels.add(bmp.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        return pick(pixels.toIntArray())
    }

    private fun rgbToHsv(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val v = max
        val s = if (max <= 0f) 0f else d / max
        var h = 0f
        if (d > 0f) {
            h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> ((b - r) / d) + 2f
                else -> ((r - g) / d) + 4f
            } * 60f
            if (h < 0f) h += 360f
        }
        return floatArrayOf(h, s, v)
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hp = (((h % 360f) + 360f) % 360f) / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val m = v - c
        val rgb = when (hp.toInt()) {
            0 -> floatArrayOf(c, x, 0f)
            1 -> floatArrayOf(x, c, 0f)
            2 -> floatArrayOf(0f, c, x)
            3 -> floatArrayOf(0f, x, c)
            4 -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        val r = ((rgb[0] + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = ((rgb[1] + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = ((rgb[2] + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
