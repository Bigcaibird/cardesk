package com.cardesk.launcher.core.update

import com.cardesk.launcher.core.JsonLite
import java.net.URI

/**
 * R2 上的「安装包」条目（第八十三轮）。
 *
 * @property name 文件名（`CarDesk-ecarx-cardesk-4148.apk`），只用于展示和落盘命名。
 * @property url 绝对下载地址（相对路径已在 [R2Release.parse] 里按清单地址解析好）。
 * @property size 字节数（0 = 清单没给 → 不做长度校验）。
 * @property sha256 十六进制小写摘要（空 = 清单没给 → 不做摘要校验）。
 */
data class R2Apk(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

/**
 * R2 上的「最新版本清单」（第八十三轮）。
 *
 * ## 为什么自己定一套清单格式
 *
 * 之前更新源是 GitHub Releases，App 解析的是 GitHub 自己的 `releases` 数组
 * （字段名 `tag_name` / `assets` / `browser_download_url` 都是 GitHub 定的）。
 * 换成 R2 之后 R2 只是个**对象存储**——它只有「一个 key 一个字节流」，
 * 没有 release、没有 tag、也没有附件列表这种东西。
 *
 * 所以清单格式必须由我们自己定，并约定放到固定路径上：
 *
 * ```
 * https://file.chuxi.bid/cardesk/latest.json
 * ```
 *
 * 约定内容（`tools/make_manifest.py` 生成）：
 *
 * ```json
 * {
 *   "schema": 1,
 *   "version": "1.0.1052",
 *   "versionCode": 4148,
 *   "tag": "v1.0.1052",
 *   "name": "CarDesk 4148 / 1.0.1052-system",
 *   "notes": "本轮更新说明……",
 *   "publishedAt": "2026-10-30T15:00:00Z",
 *   "apk": {
 *     "name": "CarDesk-ecarx-cardesk-4148.apk",
 *     "url": "cardesk/CarDesk-ecarx-cardesk-4148.apk",
 *     "size": 1941768,
 *     "sha256": "b9a4ed58f19c9f4476c5a79b77bdd890aa32f4c8d5464144143daf7cd0dc56b9"
 *   }
 * }
 * ```
 *
 * ## `url` 字段为什么写相对路径
 *
 * 写的是**R2 对象 key**（`cardesk/CarDesk-...apk`），
 * 也就是往 bucket 里上传时用的那个 key —— 「填什么就传什么」，
 * 不用在清单里再写一遍域名，改域名也不用改清单。
 * 也接受写完整地址（`https://file.chuxi.bid/cardesk/...`），原样使用。
 *
 * ## 为什么不沿用 GitHub 的字段名
 *
 * 字段名照抄 GitHub 只会让后来人以为「还在读 GitHub」，而两边的语义其实不同
 * （GitHub 的 `assets` 是数组、可以挂多个包；这里只有一个安装包）。
 * 按自己的语义命名，读代码的人一眼就知道数据来自 R2。
 *
 * ## 为什么带 `sha256`
 *
 * R2 自定义域是走公网的，下载中断 / 被中间设备截断都不会报错，
 * 只看 HTTP 200 和「文件大于 1KB」是不够的。清单里带上摘要，
 * 下载完本地算一遍对不上就当这次更新失败，不去装一个可能残缺的包。
 * 清单没给摘要时不校验（不猜）。
 */
data class R2Release(
    /** 版本号（`1.0.1052`，不带 flavor 后缀）。 */
    val version: String,
    /** versionCode（`4148`；0 = 清单没给）。 */
    val versionCode: Long,
    /** 发布标记（`v1.0.1052`；空 = 清单没给）。 */
    val tag: String,
    /** 展示标题（`CarDesk 4148 / 1.0.1052-system`）。 */
    val name: String,
    /** 更新说明（可为空）。 */
    val notes: String,
    /** 发布时间（ISO 串；可为空）。 */
    val publishedAt: String,
    /** 安装包（null = 清单没挂包 → 没有可更新的东西）。 */
    val apk: R2Apk?,
) {
    companion object {

        /**
         * 解析清单。
         *
         * @param json 清单原文。
         * @param rootUrl 域名根地址（`https://file.chuxi.bid/`），
         *   用来把 `apk.url` 里的 R2 对象 key（`cardesk/x.apk`）拼成完整下载地址。
         * @return 解析结果；**结构不对返回 null**（调用方按「查不到更新」处理）。
         */
        fun parse(json: String, rootUrl: String): R2Release? {
            val root = JsonLite.parseObject(json) ?: return null
            // 没有版本号就没法跟本地比 —— 直接当成「读不懂」，不猜
            val version = JsonLite.str(root, "version").trim()
            if (version.isEmpty()) return null
            return R2Release(
                version = version,
                versionCode = JsonLite.long(root, "versionCode", 0L),
                tag = JsonLite.str(root, "tag").trim(),
                name = JsonLite.str(root, "name").trim(),
                notes = JsonLite.str(root, "notes"),
                publishedAt = JsonLite.str(root, "publishedAt").trim(),
                apk = parseApk(root["apk"], rootUrl),
            )
        }

        /** 解析 `apk` 段；没有可用下载地址时返回 null。 */
        private fun parseApk(raw: Any?, rootUrl: String): R2Apk? {
            val map = raw as? Map<String, Any?> ?: return null
            val rawUrl = JsonLite.str(map, "url").trim()
            if (rawUrl.isEmpty()) return null
            val url = resolveUrl(rawUrl, rootUrl) ?: return null
            // 只允许 http/https —— 清单是外部数据，不能让它指到 file:// 之类的地方
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            val name = JsonLite.str(map, "name").trim().ifEmpty { url.substringAfterLast('/') }
            val digest = JsonLite.str(map, "sha256").trim().lowercase()
            return R2Apk(
                name = name,
                url = url,
                size = JsonLite.long(map, "size", 0L),
                // 不是 64 位十六进制就当没有摘要（不猜、不半信）
                sha256 = if (digest.length == 64 && digest.all { it in "0123456789abcdef" }) digest else "",
            )
        }

        /**
         * 相对地址按域名根拼开；不是 http/https 就返回 null。
         *
         * 注意：这里用的是**域名根**而不是清单所在目录 ——
         * 清单里写的就是 R2 对象 key，跟上传时用的 key 完全一致。
         */
        private fun resolveUrl(raw: String, rootUrl: String): String? = runCatching {
            val base = URI.create(rootUrl)
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else base.resolve(raw.removePrefix("/")).toString()
        }.getOrNull()
    }
}
