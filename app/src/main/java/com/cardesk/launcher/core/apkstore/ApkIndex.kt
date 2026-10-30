package com.cardesk.launcher.core.apkstore

import com.cardesk.launcher.core.JsonLite
import java.net.URI
import java.net.URLEncoder

/**
 * 云端应用库里的一个 APK 条目（第八十四轮）。
 *
 * @property name 展示名（`酷狗音乐`；空 = 用文件名去掉 `.apk`）。
 * @property file 原始文件名（落盘用）。
 * @property url 绝对下载地址（相对 key 已在 [ApkIndex.parse] 里拼好）。
 * @property size 字节数（0 = 清单没给 → 不做长度校验）。
 * @property sha256 十六进制小写摘要（空 = 清单没给 → 不做摘要校验）。
 * @property addedAt 上传时间（ISO 串，只用于展示）。
 */
data class ApkEntry(
    val name: String,
    val file: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val addedAt: String,
)

/**
 * 云端应用库的清单（第八十四轮）。
 *
 * ## 清单长什么样
 *
 * R2 只是对象存储，没有「应用列表」这种东西，所以清单格式是我们自己定的，
 * 约定放在固定路径上：
 *
 * ```
 * https://file.chuxi.bid/cardesk/apks.json
 * ```
 *
 * ```json
 * {
 *   "schema": 1,
 *   "updatedAt": "2026-10-30T09:00:00Z",
 *   "items": [
 *     {
 *       "name": "酷狗音乐",
 *       "file": "kugou.apk",
 *       "key": "cardesk/apks/kugou.apk",
 *       "size": 12345678,
 *       "sha256": "…64 位十六进制…",
 *       "addedAt": "2026-10-30T09:00:00Z"
 *     }
 *   ]
 * }
 * ```
 *
 * ## 谁写的
 *
 * 两边写，格式必须一致：
 *
 * - `worker/src/index.js` —— 手机上用浏览器传（Cloudflare Worker）；
 * - `tools/publish_apks.py` —— 电脑上用命令行传（开发和验证用）。
 *
 * 格式的**权威在本文档和 [ApkIndex.parse]**：解析端认什么，产出端就得写什么。
 *
 * ## `key` 为什么是相对路径
 *
 * 写的是 **R2 对象 key**（`cardesk/apks/kugou.apk`），也就是上传时用的那个 key ——
 * 「填什么就传什么」，改域名不用改清单。绝对地址也认，原样使用。
 *
 * ## 一条坏了不影响别的
 *
 * 和「在线更新」的清单不同（那边只有一条，坏了就整体读不懂），
 * 这里**一条坏只跳掉那一条**：整个应用列表因为一条脏数据就全空，
 * 比少一个包难用得多。
 */
data class ApkIndex(
    /** 清单生成时间（ISO 串；空 = 清单没给）。 */
    val updatedAt: String,
    /** 可安装的包（新传的在前）。 */
    val items: List<ApkEntry>,
) {
    companion object {

        /**
         * 「这是个绝对 URI」的判据：`scheme://`。
         *
         * 用来把「清单里写的是完整地址」和「清单里写的是 R2 对象 key」分开 ——
         * 前者要检查协议（只允许 http/https），后者要拼域名根。
         */
        private val ABSOLUTE_URI = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

        /**
         * 解析清单。
         *
         * @param json 清单原文。
         * @param rootUrl 域名根地址（`https://file.chuxi.bid/`），
         *   用来把 `key` 里的 R2 对象 key 拼成完整下载地址。
         * @return 解析结果；**结构完全不对时返回空清单**（调用方按「没有可装的包」处理）。
         */
        fun parse(json: String, rootUrl: String): ApkIndex {
            val root = JsonLite.parseObject(json) ?: return ApkIndex("", emptyList())
            val rawItems = root["items"]
            if (rawItems !is List<*>) return ApkIndex(JsonLite.str(root, "updatedAt"), emptyList())
            val items = rawItems.mapNotNull { parseEntry(it as? Map<String, Any?> ?: return@mapNotNull null, rootUrl) }
            return ApkIndex(JsonLite.str(root, "updatedAt"), items)
        }

        /** 解析一条；**读不懂就跳过这一条**（不拖累整份清单）。 */
        private fun parseEntry(map: Map<String, Any?>?, rootUrl: String): ApkEntry? {
            val rawKey = JsonLite.str(map, "key").trim()
            if (rawKey.isEmpty()) return null
            // —— 先看**原始**串 ——
            // 清单是外部数据，不能让它把 App 指向 file:// 之类的地方。
            // 这道检查必须在百分号编码**之前**做：
            // 编码之后 `file:///etc/passwd` 会变成 `file%3A///etc/passwd`，
            // 看着像个相对路径，就被当成合法 key 放行了。
            if (ABSOLUTE_URI.containsMatchIn(rawKey)) {
                if (!rawKey.startsWith("http://") && !rawKey.startsWith("https://")) return null
            }
            val url = resolveUrl(rawKey, rootUrl) ?: return null
            // 拼完再确认一次：拼出来的地址只允许 http/https
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            val file = JsonLite.str(map, "file").trim().ifEmpty { url.substringAfterLast('/') }
            val digest = JsonLite.str(map, "sha256").trim().lowercase()
            return ApkEntry(
                name = JsonLite.str(map, "name").trim().ifEmpty { file.removeSuffix(".apk") },
                file = file,
                url = url,
                size = JsonLite.long(map, "size", 0L),
                // 不是 64 位十六进制就当没有摘要（不猜、不半信）
                sha256 = if (digest.length == 64 && digest.all { it in "0123456789abcdef" }) digest else "",
                addedAt = JsonLite.str(map, "addedAt").trim(),
            )
        }

        /**
         * 相对地址按域名根拼开；不是 http/https 就返回 null。
         *
         * 注意用的是**域名根**而不是清单所在目录 ——
         * 清单里写的就是 R2 对象 key，跟上传时用的 key 完全一致。
         */
        private fun resolveUrl(raw: String, rootUrl: String): String? = runCatching {
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else URI.create(rootUrl).resolve(encodePath(raw.removePrefix("/"))).toString()
        }.getOrNull()

        /**
         * 把 key 里的路径分段做百分号编码。
         *
         * 用户传上去的 APK **文件名里可能有中文**，而 [URI.create] 碰到非 ASCII
         * 会直接抛异常 —— 结果是这条记录被静默丢掉、用户看不到自己刚传的包。
         * 所以先编码再拼。
         *
         * [URLEncoder] 是表单编码（空格变 `+`），这里换回 `%20`；
         * 它还会把 `(` `)` 之类编码掉，无妨 —— URL 上等价。
         */
        private fun encodePath(path: String): String =
            path.split("/").joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
    }
}
