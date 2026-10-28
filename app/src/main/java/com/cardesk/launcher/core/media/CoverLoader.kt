package com.cardesk.launcher.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 封面加载：把封面链路补齐。
 *
 * 优先级链
 * `
 * ① android.media.metadata.ALBUM_ART (bitmap) → 直接用
 * ② android.media.metadata.ART (bitmap) → 直接用
 * ③ android.media.metadata.DISPLAY_ICON(bitmap) → 直接用
 * ④ android.media.metadata.DISPLAY_ICON_URI → 按 URI 加载
 * ⑤ android.media.metadata.ART_URI → 按 URI 加载
 * ⑥ 都没有 → searchCoverIfNeeded() → CoverArtSearch（QQ → 酷狗）→ 按 URL 加载
 * `
 *
 * ①②③ 由 [MusicSessionTracker] 负责（直接放进 `TrackInfo.artwork`）；
 * 本类负责 **④⑤⑥** 与「把封面拉回来」这件事。
 *
 * ## 为什么自己写加载器
 *
 * 用 Glide，但我们
 * **刻意不引入任何运行时依赖**，所以这里用纯 framework API 实现同等能力：
 * 内存 LRU + 磁盘 LRU + 采样解码 + 后台线程 + 主线程回调。
 *
 * ## 磁盘缓存为什么封顶
 *
 * 车机存储紧张，日志占用已经封顶，封面缓存同理：
 * [DISK_CACHE_MAX_BYTES] + [DISK_CACHE_MAX_FILES] 双上限，按最后访问时间淘汰。
 */
object CoverLoader {

    /** 封面最大边长（px）。场景页封面只有 96px，音乐页 480px，512 够用。 */
    const val MAX_EDGE = 512

    /** 内存缓存上限（KB）。 */
    const val MEM_CACHE_KB = 6 * 1024

    /** 磁盘缓存上限（字节）= 16MB。 */
    const val DISK_CACHE_MAX_BYTES = 16L * 1024 * 1024L

    /** 磁盘缓存文件数上限。 */
    const val DISK_CACHE_MAX_FILES = 40

    /** 单张图片下载上限（字节）= 4MB，防止异常 URL 撑爆内存。 */
    const val MAX_DOWNLOAD_BYTES = 4L * 1024 * 1024L

    /** 搜索结果 URL 记忆条数（同一首歌不重复搜索）。 */
    private const val URL_MEMO_MAX = 16

    private const val UA = "Mozilla/5.0 (Linux; Android 10; CarDesk) AppleWebKit/537.36"
    private const val CONNECT_MS = 3000
    private const val READ_MS = 4000

    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "cover-load").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** 内存缓存（按 KB 计）。 */
    private val mem = object : LruCache<String, Bitmap>(MEM_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** 正在下载中的 URL，避免同一张图被并发下载多次。 */
    private val inflight = ConcurrentHashMap<String, AtomicBoolean>()

    /** 「歌 → 封面 URL」记忆（去重，这里做成多条 LRU）。 */
    private val urlMemo = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > URL_MEMO_MAX
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 按优先级链取封面（**异步**）。
     *
     * @param artworkUri 车机会话给的封面 URI，空串表示没有。
     * @param onCover 回调（主线程）：(封面, 来源说明)。拿不到时封面为 null。
     */
    fun fetch(
        context: Context,
        title: String,
        artist: String,
        durationMs: Long,
        mediaId: String?,
        artworkUri: String,
        onCover: (Bitmap?, String) -> Unit,
    ) {
        val app = context.applicationContext
        val songKey = songKey(mediaId, title, artist)
        // ① 车机给的 URI
        if (artworkUri.isNotBlank()) {
            load(app, artworkUri) { bmp ->
                if (bmp != null) {
                    Logx.i(
                        LogTags.MEDIA, "封面加载成功（车机会话 URI）",
                        "uri" to artworkUri, "w" to bmp.width, "h" to bmp.height,
                    )
                    onCover(bmp, "URI")
                    return@load
                }
                // URI 拿不到 → 落到网络搜索（此时只会显示占位图，这里是补强）
                Logx.i(LogTags.MEDIA, "封面 URI 加载失败，改走网络搜索", "uri" to artworkUri)
                search(app, title, artist, durationMs, songKey, onCover)
            }
            return
        }
        // ② 网络搜索
        search(app, title, artist, durationMs, songKey, onCover)
    }

    /** 网络搜索并加载封面（后台线程）。 */
    private fun search(
        context: Context,
        title: String,
        artist: String,
        durationMs: Long,
        songKey: String,
        onCover: (Bitmap?, String) -> Unit,
    ) {
        synchronized(urlMemo) {
            val memo = urlMemo[songKey]
            if (!memo.isNullOrEmpty()) {
                Logx.d(LogTags.MEDIA, "封面 URL 记忆命中", "key" to songKey, "url" to memo)
                load(context, memo) { bmp ->
                    if (bmp != null) {
                        onCover(bmp, "SEARCH")
                    } else {
                        // 记忆里的 URL 失效了 → 清掉记忆，允许下次重新搜
                        synchronized(urlMemo) { urlMemo.remove(songKey) }
                        onCover(null, "")
                    }
                }
                return
            }
        }
        pool.execute {
            val url = try {
                CoverArtSearch.find(title, artist, durationMs)
            } catch (t: Throwable) {
                Logx.w(LogTags.MEDIA, "封面搜索异常", t, "title" to title)
                ""
            }
            if (url.isEmpty()) {
                Logx.i(
                    LogTags.MEDIA, "封面搜索无结果",
                    "title" to title, "artist" to artist, "durationMs" to durationMs,
                )
                main.post { onCover(null, "") }
                return@execute
            }
            synchronized(urlMemo) { urlMemo[songKey] = url }
            load(context, url) { bmp ->
                if (bmp != null) {
                    Logx.i(
                        LogTags.MEDIA, "封面加载成功（网络搜索）",
                        "url" to url, "w" to bmp.width, "h" to bmp.height,
                    )
                    onCover(bmp, "SEARCH")
                } else {
                    Logx.i(LogTags.MEDIA, "封面下载失败", "url" to url)
                    onCover(null, "")
                }
            }
        }
    }

    /**
     * 按 URI 加载图片（**异步**，主线程回调）。
     *
     * 流程：内存缓存 → 磁盘缓存 → 下载/读取 → 采样解码。
     */
    fun load(context: Context, uri: String, onLoaded: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        mem.get(uri)?.let {
            Logx.d(LogTags.MEDIA, "封面内存缓存命中", "uri" to uri)
            main.post { onLoaded(it) }
            return
        }
        val guard = inflight.getOrPut(uri) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) {
            // 同一张图已经在下载了 → 直接等它，不重复下载
            Logx.d(LogTags.MEDIA, "封面正在下载中，跳过重复请求", "uri" to uri)
            return
        }
        pool.execute {
            val bmp = try {
                loadSync(app, uri)
            } catch (t: Throwable) {
                Logx.w(LogTags.MEDIA, "封面加载异常", t, "uri" to uri)
                null
            } finally {
                inflight.remove(uri)
            }
            if (bmp != null) mem.put(uri, bmp)
            main.post { onLoaded(bmp) }
        }
    }

    /** 清空内存缓存（切歌 / 内存紧张时调用）。 */
    fun trimMemory() {
        mem.evictAll()
        Logx.d(LogTags.MEDIA, "封面内存缓存已清空")
    }

    // ------------------------------------------------------------------ 内部实现

    /** 同步加载：磁盘缓存 → 网络/内容流。 */
    private fun loadSync(context: Context, uri: String): Bitmap? {
        val file = cacheFile(context, uri)
        if (file.exists() && file.length() > 0) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes != null) {
                decode(bytes)?.let {
                    file.setLastModified(System.currentTimeMillis())
                    Logx.d(LogTags.MEDIA, "封面磁盘缓存命中", "uri" to uri)
                    return it
                }
            }
        }
        val bytes = readBytes(context, uri) ?: return null
        val bmp = decode(bytes) ?: return null
        // 写磁盘缓存（失败无所谓，封面不是关键数据）
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            trimDiskCache(file.parentFile)
        }
        return bmp
    }

    /** 读原始字节：网络下载 / content:// / file://。 */
    private fun readBytes(context: Context, uri: String): ByteArray? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return when (parsed.scheme?.lowercase()) {
            "http", "https" -> httpGet(parsed.toString())
            "content", "android.resource", "file" -> openStream(context, parsed)?.use { readLimited(it) }
            else -> null
        }
    }

    private fun openStream(context: Context, uri: Uri): InputStream? = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()

    /** 带上限地读输入流。 */
    private fun readLimited(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_DOWNLOAD_BYTES) {
                Logx.w(LogTags.MEDIA, "封面数据超过上限，放弃", "limit" to MAX_DOWNLOAD_BYTES)
                return null
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun httpGet(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_MS
        conn.readTimeout = READ_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "image/*,*/*")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Logx.i(LogTags.MEDIA, "封面下载 HTTP 失败", "code" to code)
                return null
            }
            conn.inputStream.use { readLimited(it) }
        } catch (t: Throwable) {
            Logx.w(LogTags.MEDIA, "封面下载异常", t, "url" to url)
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 采样解码：最长边超过 [MAX_EDGE] 就按 2 的幂缩小，避免一张 1000×1000 的封面
     * 直接进内存（车机内存小，容易被 LMK 干掉）。
     */
    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    // ------------------------------------------------------------------ 磁盘缓存

    private fun cacheDir(context: Context): File = File(context.cacheDir, "covers")

    /** 缓存文件名 = URI 的 MD5。 */
    private fun cacheFile(context: Context, uri: String): File =
        File(cacheDir(context), md5(uri) + ".img")

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 磁盘缓存淘汰：按最后访问时间从旧到新删，直到体积与文件数都在上限内。
     * 车机存储紧张，这一步是必须的。
     */
    private fun trimDiskCache(dir: File?) {
        val d = dir ?: return
        val files = d.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        var total = files.sumOf { it.length() }
        var count = files.size
        if (total <= DISK_CACHE_MAX_BYTES && count <= DISK_CACHE_MAX_FILES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CACHE_MAX_BYTES && count <= DISK_CACHE_MAX_FILES) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                count -= 1
                Logx.d(
                    LogTags.MEDIA, "封面磁盘缓存淘汰",
                    "file" to f.name, "bytes" to len, "remainBytes" to total, "remainFiles" to count,
                )
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 歌 → 封面的记忆键（思路：有 mediaId 用 id，否则用标题）。
     */
    fun songKey(mediaId: String?, title: String, artist: String): String =
        LyricMatch.buildSongKey(mediaId, LyricMatch.clean(title, artist).title)
}
