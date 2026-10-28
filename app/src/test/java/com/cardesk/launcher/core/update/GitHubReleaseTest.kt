package com.cardesk.launcher.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GitHubRelease] 单测（纯逻辑）。
 *
 * 这条规则决定「查到的新版对不对」：
 * - 解析的是 GitHub `GET /repos/{owner}/{repo}/releases` 的真实结构；
 * - 挑最新的那个**按版本号**挑，不按发布时间（补发旧版时不会挑错）。
 */
class GitHubReleaseTest {

    /** 从真实接口返回里裁出来的最小样本（字段名与真实一致）。 */
    private val sample = """
        [
          {
            "tag_name": "v1.0.1035",
            "name": "CarDesk 4131 / 1.0.1035-system",
            "body": "## CarDesk 4131 / 1.0.1035-system\n\n| versionCode | **4131** |",
            "html_url": "https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1035",
            "published_at": "2026-10-28T15:12:18Z",
            "draft": false,
            "prerelease": false,
            "assets": [
              {
                "name": "CarDesk-ecarx-cardesk-4131.apk",
                "browser_download_url": "https://github.com/Bigcaibird/cardesk/releases/download/v1.0.1035/CarDesk-ecarx-cardesk-4131.apk",
                "size": 1790833
              }
            ]
          },
          {
            "tag_name": "v1.0.1038",
            "name": "CarDesk 4134 / 1.0.1038-system",
            "body": "第六十八轮",
            "html_url": "https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1038",
            "published_at": "2026-10-29T00:10:00Z",
            "draft": false,
            "prerelease": false,
            "assets": [
              {
                "name": "CarDesk-ecarx-cardesk-4134.apk",
                "browser_download_url": "https://github.com/Bigcaibird/cardesk/releases/download/v1.0.1038/CarDesk-ecarx-cardesk-4134.apk",
                "size": 1830705
              },
              {
                "name": "sha256sums.txt",
                "browser_download_url": "https://github.com/Bigcaibird/cardesk/releases/download/v1.0.1038/sha256sums.txt",
                "size": 120
              }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `解析 release 列表`() {
        val list = GitHubRelease.parse(sample)
        assertEquals(2, list.size)
        val r = list[0]
        assertEquals("v1.0.1035", r.tag)
        assertEquals("CarDesk 4131 / 1.0.1035-system", r.name)
        assertTrue(r.notes.contains("versionCode"))
        assertEquals("https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1035", r.htmlUrl)
        assertTrue(!r.draft && !r.prerelease)
        assertEquals(1, r.assets.size)
        assertEquals("CarDesk-ecarx-cardesk-4131.apk", r.assets[0].name)
        assertEquals(1790833L, r.assets[0].size)
        assertTrue(r.assets[0].downloadUrl.endsWith("CarDesk-ecarx-cardesk-4131.apk"))
    }

    @Test
    fun `归一后的版本号`() {
        val list = GitHubRelease.parse(sample)
        assertEquals("1.0.1035", list[0].version)
        assertEquals("1.0.1038", list[1].version)
    }

    @Test
    fun `APK 附件能挑出来`() {
        val list = GitHubRelease.parse(sample)
        // 第二个 release 还挂了一个 sha256sums.txt —— 不能被当成安装包
        assertEquals("CarDesk-ecarx-cardesk-4134.apk", list[1].apk?.name)
        assertNotNull(list[0].apk?.downloadUrl)
    }

    @Test
    fun `没有 APK 附件时 apk 为空`() {
        val json = """
            [{"tag_name":"v1.0.1","assets":[{"name":"notes.txt","browser_download_url":"https://x/n.txt","size":1}]}]
        """.trimIndent()
        assertNull(GitHubRelease.parse(json)[0].apk)
    }

    @Test
    fun `挑最新按版本号而不是发布时间`() {
        // 故意让「旧版本」排在前面、新版本排在后面 —— 结果必须一样
        val list = GitHubRelease.parse(sample)
        assertEquals("v1.0.1038", GitHubRelease.pickLatest(list)?.tag)
        assertEquals("v1.0.1038", GitHubRelease.pickLatest(list.reversed())?.tag)
    }

    @Test
    fun `草稿和预发布不参与`() {
        val json = """
            [
              {"tag_name":"v1.0.9999","draft":true,"prerelease":false,"assets":[]},
              {"tag_name":"v1.0.8888","draft":false,"prerelease":true,"assets":[]},
              {"tag_name":"v1.0.1038","draft":false,"prerelease":false,"assets":[]}
            ]
        """.trimIndent()
        assertEquals("v1.0.1038", GitHubRelease.pickLatest(GitHubRelease.parse(json))?.tag)
    }

    @Test
    fun `解析失败返回空列表`() {
        assertTrue(GitHubRelease.parse("").isEmpty())
        assertTrue(GitHubRelease.parse("not json").isEmpty())
        assertTrue(GitHubRelease.parse("{}").isEmpty())
        // 没有 tag 的条目直接丢掉
        assertTrue(GitHubRelease.parse("""[{"name":"没有 tag"}]""").isEmpty())
    }
}
