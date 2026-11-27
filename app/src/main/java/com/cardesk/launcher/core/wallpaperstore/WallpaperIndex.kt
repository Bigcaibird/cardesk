package com.cardesk.launcher.core.wallpaperstore

import com.cardesk.launcher.core.JsonLite
import java.net.URI
import java.net.URLEncoder

/** 在线壁纸资源类型。 */
enum class WallpaperKind(val wire: String, val label: String) {
    STATIC("static", "静态壁纸"),
    VIDEO("video", "动态壁纸"),
}

/**
 * R2 在线壁纸清单里的单条资源。
 *
 * @property name 展示名（空时退回文件名去扩展名）
 * @property kind 静态图片或动态视频
 * @property file 原始文件名
 * @property url 绝对下载地址
 * @property thumbUrl 可选预览缩略图绝对地址（空表示未单独提供缩略图）
 * @property size 字节数（0 = 清单未提供）
 * @property sha256 64 位小写十六进制校验摘要（空 = 不校验）
 * @property addedAt 上传时间（ISO8601）
 */
data class OnlineWallpaperEntry(
    val name: String,
    val kind: WallpaperKind,
    val file: String,
    val url: String,
    val thumbUrl: String,
    val size: Long,
    val sha256: String,
    val addedAt: String,
)

/**
 * R2 在线壁纸资源清单（`cardesk/wallpapers.json`）。
 *
 * 格式与 `cardesk/apks.json` 一脉相承，单条损坏时跳过该条，不影响其余壁纸展示。
 */
data class WallpaperIndex(
    val updatedAt: String,
    val items: List<OnlineWallpaperEntry>,
) {
    companion object {
        private val ABSOLUTE_URI = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "ts")

        fun parse(json: String, rootUrl: String): WallpaperIndex {
            val root = JsonLite.parseObject(json) ?: return WallpaperIndex("", emptyList())
            val rawItems = root["items"]
            if (rawItems !is List<*>) return WallpaperIndex(JsonLite.str(root, "updatedAt"), emptyList())
            val items = rawItems.mapNotNull { parseEntry(it as? Map<String, Any?>, rootUrl) }
            return WallpaperIndex(JsonLite.str(root, "updatedAt"), items)
        }

        private fun parseEntry(map: Map<String, Any?>?, rootUrl: String): OnlineWallpaperEntry? {
            if (map == null) return null
            val rawKey = JsonLite.str(map, "key").trim()
            if (rawKey.isEmpty()) return null
            val url = resolveHttpUrl(rawKey, rootUrl) ?: return null
            val file = JsonLite.str(map, "file").trim().ifEmpty { url.substringAfterLast('/') }
            val kind = parseKind(JsonLite.str(map, "type").trim(), file, rawKey)
            val rawThumb = JsonLite.str(map, "thumbKey").trim().ifEmpty { JsonLite.str(map, "thumbUrl").trim() }
            val thumbUrl = if (rawThumb.isNotEmpty()) {
                resolveHttpUrl(rawThumb, rootUrl).orEmpty()
            } else if (kind == WallpaperKind.STATIC) {
                url
            } else {
                ""
            }
            val digest = JsonLite.str(map, "sha256").trim().lowercase()
            val stem = file.substringBeforeLast('.', file)
            return OnlineWallpaperEntry(
                name = JsonLite.str(map, "name").trim().ifEmpty { stem },
                kind = kind,
                file = file,
                url = url,
                thumbUrl = thumbUrl,
                size = JsonLite.long(map, "size", 0L),
                sha256 = if (digest.length == 64 && digest.all { it in "0123456789abcdef" }) digest else "",
                addedAt = JsonLite.str(map, "addedAt").trim(),
            )
        }

        private fun parseKind(rawType: String, file: String, rawKey: String): WallpaperKind {
            val t = rawType.lowercase()
            if (t == "video" || t == "dynamic" || t == "live") return WallpaperKind.VIDEO
            if (t == "static" || t == "image" || t == "photo") return WallpaperKind.STATIC
            val ext = file.substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTS || rawKey.contains("/video/")) return WallpaperKind.VIDEO
            return WallpaperKind.STATIC
        }

        private fun resolveHttpUrl(raw: String, rootUrl: String): String? {
            if (ABSOLUTE_URI.containsMatchIn(raw)) {
                if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
                return raw
            }
            val resolved = runCatching {
                URI.create(rootUrl).resolve(encodePath(raw.removePrefix("/"))).toString()
            }.getOrNull() ?: return null
            if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return null
            return resolved
        }

        private fun encodePath(path: String): String =
            path.split("/").joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
    }
}
