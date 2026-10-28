package com.cardesk.launcher.core.media

import com.cardesk.launcher.core.JsonLite
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线封面搜索。
 *
 * 调用点
 * —— 车机会话既没给 bitmap、也没给 URI 时，用「歌名 + 歌手 + 时长」去网上搜封面。
 *
 * 渠道顺序照：**先 QQ 音乐，再酷狗**。
 *
 * | 渠道 | 搜索接口 | 封面 URL |
 * |---|---|---|
 * | QQ 音乐 | `c.y.qq.com/soso/fcgi-bin/client_search_cp` | `y.gtimg.cn/music/photo_new/T002R500x500M000\<albummid\>.jpg` |
 * | 酷狗 | `songsearch.kugou.com/song_search_v2` | 候选里的 `Image` 字段（`{size}` 换成 `400`） |
 *
 * 命中判定与完全一致：**用 [LyricMatch.score] 打分，最高分 ≥ [MIN_SCORE]（80）才算命中**
 * —— 避免「同名不同歌」拿到别的歌的封面。
 *
 * 网络请求在这里是**同步**的，调用方（[CoverLoader]）负责放到后台线程。
 */
object CoverArtSearch {

    /** 相似度门槛（`CoverArtSearch` 里 `i3 < 80` 的判断，与歌词同一套阈值）。 */
    const val MIN_SCORE = 80

    /** `CoverArtSearch.fromQq` 的搜索接口。 */
    const val QQ_SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=10&w=%s&format=json"

    /** `CoverArtSearch.fromQq` 的 Referer。 */
    const val QQ_REFERER = "https://y.qq.com/"

    /** `CoverArtSearch.fromQq` 的封面 URL 前缀（后面拼 albummid + `.jpg`）。 */
    const val QQ_COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R500x500M000"

    /** `CoverArtSearch.fromKugou` 的搜索接口。 */
    const val KUGOU_SEARCH =
        "https://songsearch.kugou.com/song_search_v2?keyword=%s&page=1&pagesize=10&userid=-1&clientver=&platform=WebFilter"

    /** `CoverArtSearch.fromKugou` 的 Referer。 */
    const val KUGOU_REFERER = "https://www.kugou.com/"

    /** 酷狗图片 URL 里的尺寸占位符。 */
    const val KUGOU_SIZE_TOKEN = "{size}"

    /** 酷狗图片 URL 的实际尺寸（`CoverArtSearch.fromKugou` `replace("{size}", "400")`）。 */
    const val KUGOU_SIZE = "400"

    private const val UA = "Mozilla/5.0 LyricsCompanion/desktop"
    private const val CONNECT_MS = 2500
    private const val READ_MS = 3000

    /** 一条封面候选（搜索结果里的一条）。 */
    data class Candidate(
        val title: String,
        val artist: String,
        val durationMs: Long,
        /** 封面图片 URL（已经是可直接下载的地址）。 */
        val imageUrl: String,
        /** 相似度得分（[LyricMatch.score]）。 */
        val score: Int,
    )

    // ------------------------------------------------------------------ 纯函数

    /**
     * 从 QQ 音乐搜索结果里挑出最匹配的一条（纯函数，可单测）。
     *
     * `CoverArtSearch.fromQugou`… 更正：`CoverArtSearch.fromQq`
     * 取 `data.song.list[]`，每条的 `songname` / `singer[0].name` / `interval`（秒）/ `albummid`，
     * 用 [LyricMatch.score] 打分取最高分。
     */
    fun pickQq(
        root: Map<*, *>?,
        title: String,
        artist: String,
        durationMs: Long,
    ): Candidate? {
        val data = root?.get("data") as? Map<*, *>
        val song = data?.get("song") as? Map<*, *>
        val list = song?.get("list") as? List<*> ?: return null
        var best: Candidate? = null
        for (raw in list) {
            val item = raw as? Map<String, Any?> ?: continue
            val singer = (item["singer"] as? List<*>)?.firstOrNull() as? Map<String, Any?>
            val mid = JsonLite.str(item, "albummid")
            if (mid.isEmpty()) continue
            val cand = Candidate(
                title = JsonLite.str(item, "songname"),
                artist = singer?.let { JsonLite.str(it, "name") }.orEmpty(),
                durationMs = JsonLite.int(item, "interval").toLong() * 1000L,
                imageUrl = qqCoverUrl(mid),
                score = 0,
            )
            val score = LyricMatch.score(
                title, artist, durationMs,
                cand.title, cand.artist, cand.durationMs,
            )
            if (score > (best?.score ?: LyricMatch.EMPTY_SCORE)) {
                best = cand.copy(score = score)
            }
        }
        val hit = best ?: return null
        return if (hit.score < MIN_SCORE) null else hit
    }

    /**
     * 从酷狗搜索结果里挑出最匹配的一条（纯函数，可单测）。
     *
     * `CoverArtSearch.fromKugou`：取 `data.lists[]`，每条的
     * `SongName` / `SingerName` / `Duration`（秒）/ `Image`。
     */
    fun pickKugou(
        root: Map<*, *>?,
        title: String,
        artist: String,
        durationMs: Long,
    ): Candidate? {
        val data = root?.get("data") as? Map<*, *>
        val list = data?.get("lists") as? List<*> ?: return null
        var best: Candidate? = null
        for (raw in list) {
            val item = raw as? Map<String, Any?> ?: continue
            val image = JsonLite.str(item, "Image")
            if (image.isEmpty()) continue
            val cand = Candidate(
                title = JsonLite.str(item, "SongName"),
                artist = JsonLite.str(item, "SingerName"),
                durationMs = JsonLite.int(item, "Duration").toLong() * 1000L,
                imageUrl = kugouCoverUrl(image),
                score = 0,
            )
            val score = LyricMatch.score(
                title, artist, durationMs,
                cand.title, cand.artist, cand.durationMs,
            )
            if (score > (best?.score ?: LyricMatch.EMPTY_SCORE)) {
                best = cand.copy(score = score)
            }
        }
        val hit = best ?: return null
        return if (hit.score < MIN_SCORE) null else hit
    }

    /** QQ 封面 URL（`CoverArtSearch.fromQq` 结尾的拼接）。 */
    fun qqCoverUrl(albumMid: String): String = QQ_COVER_PREFIX + albumMid + ".jpg"

    /** 酷狗封面 URL（把 `{size}` 换成 `400`，`CoverArtSearch.fromKugou`）。 */
    fun kugouCoverUrl(image: String): String =
        image.replace(KUGOU_SIZE_TOKEN, KUGOU_SIZE).replaceFirst("http://", "https://")

    /**
     * 搜索用的关键词（`CoverArtSearch` 里 `歌名 + (" " + 歌手)`）。
     * 歌手为空（或只有空白）时只有歌名。
     */
    fun keyword(title: String, artist: String): String {
        val a = artist.trim()
        return if (a.isEmpty()) title.trim() else "${title.trim()} $a"
    }

    // ------------------------------------------------------------------ 网络

    /**
     * 同步搜索封面 URL（**阻塞**，调用方要放到后台线程）。
     *
     * 先 QQ，QQ 失败再酷狗。
     * 返回空串表示没找到。
     */
    fun find(title: String, artist: String, durationMs: Long): String {
        if (title.isBlank()) return ""
        val kw = keyword(title.trim(), artist.trim())
        // ① QQ 音乐
        try {
            val body = http(String.format(QQ_SEARCH, enc(kw)), QQ_REFERER)
            val hit = pickQq(JsonLite.parseObject(body), title, artist, durationMs)
            if (hit != null) {
                Logx.i(
                    LogTags.MEDIA, "封面搜索命中（QQ 音乐）",
                    "score" to hit.score, "imageUrl" to hit.imageUrl,
                    "gotTitle" to hit.title, "gotArtist" to hit.artist,
                )
                return hit.imageUrl
            }
            Logx.i(
                LogTags.MEDIA, "封面搜索未命中（QQ 音乐）",
                "minScore" to MIN_SCORE, "title" to title, "artist" to artist,
            )
        } catch (t: Throwable) {
            Logx.w(LogTags.MEDIA, "QQ 音乐封面搜索异常", t, "title" to title)
        }
        // ② 酷狗
        return try {
            val body = http(String.format(KUGOU_SEARCH, enc(kw)), KUGOU_REFERER)
            val hit = pickKugou(JsonLite.parseObject(body), title, artist, durationMs)
            if (hit != null) {
                Logx.i(
                    LogTags.MEDIA, "封面搜索命中（酷狗）",
                    "score" to hit.score, "imageUrl" to hit.imageUrl,
                    "gotTitle" to hit.title, "gotArtist" to hit.artist,
                )
                hit.imageUrl
            } else {
                Logx.i(
                    LogTags.MEDIA, "封面搜索未命中（酷狗）",
                    "minScore" to MIN_SCORE, "title" to title, "artist" to artist,
                )
                ""
            }
        } catch (t: Throwable) {
            Logx.w(LogTags.MEDIA, "酷狗封面搜索异常", t, "title" to title)
            ""
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 2.5s 连接 / 3s 读超时 + UA + Referer。 */
    private fun http(url: String, referer: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_MS
        conn.readTimeout = READ_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
        conn.setRequestProperty("Referer", referer)
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val buffer = ByteArrayOutputStream()
            stream?.use { it.copyTo(buffer) }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            String(buffer.toByteArray(), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
