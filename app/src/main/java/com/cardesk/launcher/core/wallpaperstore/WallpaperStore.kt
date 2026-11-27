package com.cardesk.launcher.core.wallpaperstore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.cardesk.launcher.core.CustomWallpaper
import com.cardesk.launcher.core.VideoLibrary
import com.cardesk.launcher.core.VideoProbe
import com.cardesk.launcher.core.VideoSpec
import com.cardesk.launcher.core.VideoWallpaper
import com.cardesk.launcher.core.apkstore.ApkStore
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.net.FileDownload
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 在线壁纸资源库（Cloudflare R2：`cardesk/wallpapers.json`）。
 *
 * 与 [ApkStore] 共用同一个 R2 域名（`file.chuxi.bid`）和 [FileDownload] 校验下载链路：
 * - 支持静态壁纸（下载后走 [CustomWallpaper.importFromFile] 裁切落盘）
 * - 支持动态/视频壁纸（下载后先做 [VideoSpec.inspect] 校验，再走 [VideoWallpaper.importFromFile] 落盘，支持日间/夜间两档）
 */
object WallpaperStore {

    const val HOST = ApkStore.HOST
    const val INDEX_PATH = "cardesk/wallpapers.json"
    const val INDEX_URL = "https://$HOST/$INDEX_PATH"
    const val ROOT_URL = "https://$HOST/"

    /** 公共 Download 目录下 CarDesk 自有的壁纸子目录（`Download/CarDesk/wallpapers`）。 */
    const val WALLPAPER_DIR_NAME = "wallpapers"

    private const val THUMB_MAX_W = 480
    private const val THUMB_MAX_H = 270

    sealed interface FetchResult {
        data class Ok(val index: WallpaperIndex) : FetchResult
        data class Failed(val reason: String) : FetchResult
    }

    sealed interface ApplyResult {
        data class StaticApplied(val night: Boolean = false, val savedPath: String = "") : ApplyResult
        data class VideoApplied(val night: Boolean, val warning: String? = null, val savedPath: String = "") : ApplyResult
        data class Failed(val reason: String) : ApplyResult
    }

    /**
     * 在线壁纸下载保存目录：优先 `Download/CarDesk/wallpapers/`（与局域网传输、应用内照片/视频库共用
     * `Download/CarDesk` 根，下载后保留在目录内，方便离线再次切换）；外部存储不可写时回退缓存目录。
     */
    fun downloadDir(context: Context): File {
        val publicDownloads = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (publicDownloads != null) {
            val dir = File(File(publicDownloads, VideoLibrary.SHARE_DIR_NAME), WALLPAPER_DIR_NAME)
            if (dir.exists() || dir.mkdirs()) {
                if (dir.canWrite()) return dir
            }
        }
        return File(context.cacheDir, "wallpaperstore").apply { mkdirs() }
    }

    /** 生成落在 `Download/CarDesk/wallpapers/` 里的可读文件名（优先展示名 + 原后缀）。 */
    fun targetFileName(entry: OnlineWallpaperEntry): String {
        val rawFile = entry.file.substringAfterLast('/').ifBlank {
            if (entry.kind == WallpaperKind.VIDEO) "wallpaper.mp4" else "wallpaper.jpg"
        }
        val dot = rawFile.lastIndexOf('.')
        val ext = if (dot > 0) rawFile.substring(dot).lowercase() else {
            if (entry.kind == WallpaperKind.VIDEO) ".mp4" else ".jpg"
        }
        val safeName = entry.name.trim()
            .replace(Regex("""[/\\:*?"<>|\r\n\t]+"""), "_")
            .trim('.', ' ')
            .take(80)
        return if (safeName.isNotEmpty()) "$safeName$ext" else rawFile
    }

    /** 人类可读的保存路径（如 `Download/CarDesk/wallpapers/慵懒黑猫.mp4`）。 */
    fun displayPath(file: File): String {
        val marker = "/${Environment.DIRECTORY_DOWNLOADS}/"
        val abs = file.absolutePath
        val idx = abs.indexOf(marker)
        return if (idx >= 0) abs.substring(idx + 1) else file.name
    }

    /** 拉取在线壁纸清单（阻塞，后台线程调用）。 */
    fun fetchIndex(context: Context, timeoutMs: Int = 10_000): FetchResult {
        return try {
            val conn = URL(INDEX_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CarDesk")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                conn.disconnect()
                Logx.i(LogTags.SETTING, "在线壁纸清单尚不存在（404，按空列表处理）", "url" to INDEX_URL)
                return FetchResult.Ok(WallpaperIndex("", emptyList()))
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw java.io.IOException("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val index = WallpaperIndex.parse(text, ROOT_URL)
            Logx.i(
                LogTags.SETTING,
                "在线壁纸清单已拉取",
                "count" to index.items.size,
                "updatedAt" to index.updatedAt,
            )
            FetchResult.Ok(index)
        } catch (t: Throwable) {
            Logx.w(LogTags.SETTING, "在线壁纸清单拉取失败", t, "url" to INDEX_URL)
            FetchResult.Failed(t.message ?: "网络异常")
        }
    }

    /** 下载一张静态/动态壁纸到 `Download/CarDesk/wallpapers/` 并校验长度和 SHA-256（阻塞，后台线程调用）。 */
    fun download(
        context: Context,
        entry: OnlineWallpaperEntry,
        onProgress: (Int) -> Unit = {},
        onDetail: (Long, Long, Long) -> Unit = { _, _, _ -> },
    ): File? {
        val dir = downloadDir(context)
        val fileName = targetFileName(entry)
        val target = File(dir, fileName)
        if (isLocalFileValid(target, entry.size, entry.sha256)) {
            onProgress(100)
            onDetail(target.length(), target.length(), 1L)
            Logx.i(
                LogTags.SETTING,
                "在线壁纸已存在于本地目录，直接复用",
                "name" to entry.name,
                "path" to target.absolutePath,
                "bytes" to target.length(),
            )
            return target
        }
        val result = FileDownload.download(
            url = entry.url,
            target = target,
            expectSize = entry.size,
            expectSha256 = entry.sha256,
            onProgress = onProgress,
            onDetail = onDetail,
        )
        return when (result) {
            is FileDownload.Result.Ok -> {
                Logx.i(
                    LogTags.SETTING,
                    "在线壁纸下载完成",
                    "name" to entry.name,
                    "kind" to entry.kind.wire,
                    "path" to target.absolutePath,
                    "bytes" to result.bytes,
                    "elapsedMs" to result.elapsedMs,
                )
                target
            }
            is FileDownload.Result.Failed -> {
                Logx.w(
                    LogTags.SETTING,
                    "在线壁纸下载失败",
                    "name" to entry.name,
                    "reason" to result.reason,
                    "url" to entry.url,
                )
                null
            }
        }
    }

    private fun isLocalFileValid(file: File, expectSize: Long, expectSha256: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (expectSize > 0L && file.length() != expectSize) return false
        if (expectSha256.isBlank()) return false
        val actual = runCatching { sha256File(file) }.getOrNull() ?: return false
        return actual.equals(expectSha256, ignoreCase = true)
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 把下载好的文件导入为本地静态或动态壁纸（阻塞，后台线程调用）。
     *
     * 落在 `Download/CarDesk/wallpapers/` 里的源文件会**保留**，方便后续在应用内照片/视频库离线复用；
     * 仅在回退到 cacheDir 或视频校验拒绝时清理源文件。
     */
    fun applyDownloaded(
        context: Context,
        entry: OnlineWallpaperEntry,
        downloadedFile: File,
        asNightVideo: Boolean = false,
    ): ApplyResult {
        val app = context.applicationContext ?: context
        val isCacheTemp = downloadedFile.absolutePath.startsWith(app.cacheDir.absolutePath)
        val savedPath = if (isCacheTemp) "" else displayPath(downloadedFile)
        return when (entry.kind) {
            WallpaperKind.STATIC -> {
                val ok = CustomWallpaper.importFromFile(app, downloadedFile, night = asNightVideo)
                if (isCacheTemp) runCatching { downloadedFile.delete() }
                if (ok) {
                    ApplyResult.StaticApplied(night = asNightVideo, savedPath = savedPath)
                } else {
                    ApplyResult.Failed("静态壁纸解码或导入失败")
                }
            }
            WallpaperKind.VIDEO -> {
                val verdict = VideoProbe.validate(downloadedFile)
                if (verdict is VideoSpec.Verdict.Reject) {
                    runCatching { downloadedFile.delete() }
                    return ApplyResult.Failed(verdict.reason)
                }
                val ok = VideoWallpaper.importFromFile(app, downloadedFile, night = asNightVideo)
                if (isCacheTemp) runCatching { downloadedFile.delete() }
                if (ok) {
                    val warn = (verdict as? VideoSpec.Verdict.Warn)?.reason
                    ApplyResult.VideoApplied(night = asNightVideo, warning = warn, savedPath = savedPath)
                } else {
                    ApplyResult.Failed("动态壁纸导入失败（请检查存储空间或文件格式）")
                }
            }
        }
    }

    /** 下载并解码预览缩略图（带本地缓存，后台线程调用）。 */
    fun loadThumbnail(context: Context, thumbUrl: String): Bitmap? {
        if (thumbUrl.isBlank()) return null
        val cacheDir = File(context.cacheDir, "wallpaperstore/thumbs").apply { mkdirs() }
        val key = sha16(thumbUrl)
        val cached = File(cacheDir, "$key.img")
        if (!cached.isFile || cached.length() <= 0L) {
            val res = FileDownload.download(url = thumbUrl, target = cached)
            if (res !is FileDownload.Result.Ok) return null
        }
        return decodeThumb(cached)
    }

    private fun decodeThumb(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_MAX_W && bounds.outHeight / (sample * 2) >= THUMB_MAX_H) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    private fun sha16(s: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return md.take(8).joinToString("") { "%02x".format(it) }
    }
}
