package com.cardesk.launcher.core.media

import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 酷狗**旧接口**歌词兜底通道。
 *
 * 分工
 * - 在线歌词目录（QQ / 网易 / 酷狗并行竞速，`OnlineLyricCatalog.load`）是**主路**；
 * - 目录没命中时，才走**酷狗旧接口**兜底
 *   回调。
 *
 * 旧接口协议
 * 1. 搜歌：`http://mobilecdn.kugou.com/api/v3/search/song?keyword=<歌名 歌手>&page=1&pagesize=5`
 *    从返回里扫 `"hash":"`（后面 32 位就是文件 hash），再用歌名做**粗匹配**挑一个
 *
 * 2. 取词：`http://m.kugou.com/app/i/krc.php?cmd=100&hash=<hash>&timelength=999999`
 *    明文 LRC。
 *
 * 解析规则
 * 先去掉 `<数字,数字,数字>` 标签和 `[id:...]` 一类的元信息行，再按 `[mm:ss.xx]歌词` 解析。
 *
 * ## 线程
 *
 * 网络请求在后台线程（单线程池），回调切回主线程
 *
 */
object KugouLyricFetcher {

    /**  UA。 */
    private const val UA = "Mozilla/5.0"

    /** 连接超时（6s，与超时口径一致）。 */
    const val CONNECT_TIMEOUT_MS = 6_000

    /** 读超时（6s，同上）。 */
    const val READ_TIMEOUT_MS = 6_000

    const val SEARCH_URL =
        "http://mobilecdn.kugou.com/api/v3/search/song?keyword=%s&page=1&pagesize=5"

    const val LYRIC_URL = "http://m.kugou.com/app/i/krc.php?cmd=100&hash=%s&timelength=999999"

    /** 搜索结果里文件 hash 的固定长度。 */
    private const val HASH_LEN = 32

    /** `"hash":"` 的长度。 */
    private const val HASH_TOKEN_LEN = 8

    /** 向前/向后找歌名的最大距离。 */
    private const val NEARBY_LIMIT = 500

    /** 取出来的歌名最大长度。 */
    private const val NEARBY_VALUE_LIMIT = 200

    /** KRC `<数字,数字,数字>` 元信息。 */
    private val KRC_TAG = Regex("""<\d+,\d+,\d+>""")

    /** LRC `[id:...]` 一类元信息行。 */
    private val LRC_META = Regex("""\[(id|ti|ar|al|by|offset|total|hash|sign|qq|language):.*?]""")

    /** 行正则。 */
    private val LRC_LINE = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?\](.+)""")

    // 主线程 Handler / 后台线程池都延迟创建：纯解析函数在 JVM 单测里不该碰 Android。
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val pool by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "kugou-lyric").apply { isDaemon = true }
        }
    }

    /**
     * 异步取歌词：命中回调一次（主线程），失败回调 null。
     *
     * @param title 歌名（里传的是清洗后的标题，见）
     * @param artist 歌手
     */
    fun fetch(title: String, artist: String, callback: (List<LyricLine>?) -> Unit) {
        // 排障场景：歌词取不到时，这里能看到旧接口用的是哪个检索词
        Logx.i(
            LogTags.MEDIA, "酷狗旧接口兜底请求",
            "title" to title, "artist" to artist, "keyword" to searchKeyword(title, artist),
        )
        pool.execute {
            val lines = runCatching { fetchBlocking(title, artist) }
                .onFailure { Logx.w(LogTags.MEDIA, "酷狗兜底请求异常", it, "title" to title) }
                .getOrNull()
            main.post { callback(lines) }
        }
    }

    /** 同步取歌词（跑在后台线程里）。 */
    private fun fetchBlocking(title: String, artist: String): List<LyricLine>? {
        val keyword = searchKeyword(title, artist)
        if (keyword.isEmpty()) {
            Logx.i(LogTags.MEDIA, "酷狗兜底放弃：检索关键词为空", "title" to title)
            return null
        }
        val searchJson = httpGet(SEARCH_URL.replace("%s", encode(keyword))) ?: run {
            Logx.i(LogTags.MEDIA, "酷狗兜底失败：搜歌请求无响应", "keyword" to keyword)
            return null
        }
        val hash = findHash(searchJson, title) ?: run {
            Logx.i(LogTags.MEDIA, "酷狗兜底失败：没找到匹配的文件 hash", "title" to title)
            return null
        }
        val raw = httpGet(LYRIC_URL.replace("%s", hash)) ?: run {
            Logx.i(LogTags.MEDIA, "酷狗兜底失败：歌词下载无响应", "hash" to hash)
            return null
        }
        if (raw.isBlank()) {
            Logx.i(LogTags.MEDIA, "酷狗兜底失败：歌词内容为空", "hash" to hash)
            return null
        }
        val lines = parseLrc(raw)
        if (lines.isEmpty()) {
            Logx.i(LogTags.MEDIA, "酷狗兜底失败：LRC 解析为空", "hash" to hash, "rawLen" to raw.length)
            return null
        }
        Logx.i(LogTags.MEDIA, "酷狗兜底命中", "lines" to lines.size, "hash" to hash)
        return lines
    }

    /**
     * 检索关键词（纯函数）。
     *
     * 歌手非空 → `歌手 + " " + 歌名`
     * 否则只用歌名。
     */
    fun searchKeyword(title: String, artist: String): String {
        val a = artist.trim()
        val t = title.trim()
        return if (a.isNotEmpty()) "$a $t" else t
    }

    /**
     * 从搜索结果里挑出歌名匹配的那个文件 hash（纯函数）。
     *
     *
     * 扫 `"hash":"`，取后面 32 位；再找它附近的 `songname`（或 `SongName`），
     * 用 [LyricMatch.titleRoughlyMatches] 粗匹配歌名，命中就返回 hash。
     */
    fun findHash(json: String?, expectedTitle: String?): String? {
        if (json.isNullOrEmpty()) return null
        var cursor = 0
        while (cursor < json.length) {
            val at = json.indexOf("\"hash\":\"", cursor)
            if (at < 0) return null
            val start = at + HASH_TOKEN_LEN
            val end = start + HASH_LEN
            if (end > json.length) return null
            val hash = json.substring(start, end)
            if (hash.length == HASH_LEN) {
                var name = nearbyJsonString(json, at, "songname")
                if (name.isEmpty()) name = nearbyJsonString(json, at, "SongName")
                if (LyricMatch.titleRoughlyMatches(expectedTitle, name)) return hash
            }
            cursor = end
        }
        return null
    }

    /**
     * 在 [fromIndex] 附近找 `"key":"value"` 的 value（纯函数）。
     *
     *
     * 先往回找（500 字符内），找不到再往前找（500 字符内），
     * 取到的值长度不能超过 200。
     */
    fun nearbyJsonString(text: String, fromIndex: Int, key: String): String {
        val token = "\"$key\":\""
        var at = text.lastIndexOf(token, fromIndex)
        val forward = text.indexOf(token, fromIndex)
        if (at < 0 || fromIndex - at >= NEARBY_LIMIT) {
            at = if (forward < 0 || forward - fromIndex >= NEARBY_LIMIT) -1 else forward
        }
        if (at < 0) return ""
        val valueStart = at + token.length
        val valueEnd = text.indexOf('"', valueStart)
        if (valueEnd <= valueStart) return ""
        if (valueEnd - valueStart > NEARBY_VALUE_LIMIT) return ""
        return text.substring(valueStart, valueEnd)
    }

    /**
     * 解析 LRC 文本（纯函数）。
     *
     * 先洗掉 KRC / LRC 元信息
     * 再按 `[mm:ss.xx]歌词` 取行；毫秒位数不同按 100/10/1 缩放。
     */
    fun parseLrc(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw.replace(KRC_TAG, "").replace(LRC_META, "")
        val out = ArrayList<LyricLine>()
        for (m in LRC_LINE.findAll(cleaned)) {
            val min = m.groupValues[1].toIntOrNull() ?: continue
            val sec = m.groupValues[2].toIntOrNull() ?: continue
            val fracRaw = m.groupValues[3]
            val frac = when (fracRaw.length) {
                0 -> 0
                1 -> fracRaw.toInt() * 100
                2 -> fracRaw.toInt() * 10
                else -> fracRaw.toInt()
            }
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            out.add(LyricLine(min * 60_000L + sec * 1_000L + frac, text))
        }
        return out
    }

    /**
     * 解析 LRC；一行时间戳都解析不出来时，把整段文本当成 0ms 的一行（纯函数）。
     *
     *
     */
    fun parseLrcText(raw: String?): List<LyricLine> {
        val lines = parseLrc(raw)
        if (lines.isNotEmpty() || raw == null) return lines
        val text = raw.trim()
        if (text.isEmpty()) return lines
        return listOf(LyricLine(0L, text))
    }

    // ------------------------------------------------------------------ http

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * GET 一次。
     *
     * 非 200 时读错误流（和一致），读不到内容就返回 null。
     */
    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
            }
            conn = c
            val stream = if (c.responseCode == 200) c.inputStream else c.errorStream
            if (stream == null) {
                Logx.w(LogTags.MEDIA, "酷狗兜底 HTTP 无响应体", "code" to c.responseCode, "url" to url)
                null
            } else {
                val buffer = ByteArrayOutputStream()
                stream.use { it.copyTo(buffer) }
                String(buffer.toByteArray(), Charsets.UTF_8).trim()
            }
        } catch (t: Throwable) {
            Logx.w(LogTags.MEDIA, "酷狗兜底请求异常", t, "url" to url)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
