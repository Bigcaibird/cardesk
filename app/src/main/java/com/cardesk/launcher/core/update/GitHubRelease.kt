package com.cardesk.launcher.core.update

import com.cardesk.launcher.core.JsonLite

/**
 * GitHub Releases 的一个发布项（第六十九轮）。
 *
 * 只取更新需要的字段 —— 解析用项目自己的 [JsonLite]（不引三方库）。
 */
data class ReleaseAsset(
    /** 附件名（`CarDesk-ecarx-cardesk-4134.apk`）。 */
    val name: String,
    /** 直接下载地址（`browser_download_url`）。 */
    val downloadUrl: String,
    /** 字节数（0 = 接口没给）。 */
    val size: Long,
)

/**
 * 一个 Release（tag / 标题 / 说明 / 附件）。
 *
 * 数据结构取自 GitHub 公开接口 `GET /repos/{owner}/{repo}/releases`：
 *
 * ```json
 * { "tag_name": "v1.0.1035", "name": "CarDesk 4131 / 1.0.1035-system",
 *   "body": "……", "html_url": "…", "published_at": "…",
 *   "draft": false, "prerelease": false,
 *   "assets": [{ "name": "…apk", "browser_download_url": "…", "size": 1790833 }] }
 * ```
 */
data class GitHubRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val publishedAt: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
) {
    /** 归一后的版本号（`v1.0.1035` → `1.0.1035`）。 */
    val version: String get() = ReleaseVersion.normalize(tag)

    /** APK 附件（没有就 null —— 有 release 没挂包的情况是存在的）。 */
    val apk: ReleaseAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    companion object {

        /** 解析整个列表；解析失败返回空列表（调用方按「查不到」处理）。 */
        fun parse(json: String): List<GitHubRelease> {
            return JsonLite.parseArray(json).mapNotNull { map ->
                val tag = JsonLite.str(map, "tag_name")
                if (tag.isBlank()) return@mapNotNull null
                GitHubRelease(
                    tag = tag,
                    name = JsonLite.str(map, "name"),
                    notes = JsonLite.str(map, "body"),
                    htmlUrl = JsonLite.str(map, "html_url"),
                    publishedAt = JsonLite.str(map, "published_at"),
                    draft = JsonLite.bool(map, "draft"),
                    prerelease = JsonLite.bool(map, "prerelease"),
                    assets = parseAssets(map["assets"]),
                )
            }
        }

        /**
         * 挑「最新」那个。
         *
         * **按版本号挑，不按发布时间** —— GitHub 的列表是按发布时间倒序的，
         * 但如果哪天补发了一个旧版本的 release，按时间挑就会把旧版本当成「最新」。
         */
        fun pickLatest(releases: List<GitHubRelease>): GitHubRelease? =
            releases
                .filter { !it.draft && !it.prerelease }
                .maxWithOrNull { a, b -> ReleaseVersion.compare(a.tag, b.tag) }

        private fun parseAssets(raw: Any?): List<ReleaseAsset> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { item ->
                val map = item as? Map<String, Any?> ?: return@mapNotNull null
                val name = JsonLite.str(map, "name")
                if (name.isBlank()) return@mapNotNull null
                ReleaseAsset(
                    name = name,
                    downloadUrl = JsonLite.str(map, "browser_download_url"),
                    size = JsonLite.long(map, "size", 0L),
                )
            }
        }
    }
}
