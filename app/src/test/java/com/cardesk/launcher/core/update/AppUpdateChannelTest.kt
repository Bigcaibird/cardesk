package com.cardesk.launcher.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新通道（正式版 / 开发版）单测 —— 纯逻辑，不联网。
 *
 * 第三百零九轮：App 可以在设置页选「正式版」或「开发版」，两条通道各查各的清单：
 * - 正式版 → `cardesk/latest.json`
 * - 开发版 → `cardesk/preview/latest.json`
 *
 * 这几条断言就是「两条通道不会串」的底线：清单路径、地址、中文名、未知值回落。
 */
class AppUpdateChannelTest {

    @Test
    fun `正式版与开发版用的是两份不同清单`() {
        assertEquals("cardesk/latest.json", AppUpdate.manifestPath(AppUpdate.CHANNEL_STABLE))
        assertEquals("cardesk/preview/latest.json", AppUpdate.manifestPath(AppUpdate.CHANNEL_PREVIEW))
        assertNotEquals(
            AppUpdate.manifestUrl(AppUpdate.CHANNEL_STABLE),
            AppUpdate.manifestUrl(AppUpdate.CHANNEL_PREVIEW),
        )
    }

    @Test
    fun `清单地址挂在 R2 域名下`() {
        assertEquals(
            "https://file.chuxi.bid/cardesk/latest.json",
            AppUpdate.manifestUrl(AppUpdate.CHANNEL_STABLE),
        )
        assertEquals(
            "https://file.chuxi.bid/cardesk/preview/latest.json",
            AppUpdate.manifestUrl(AppUpdate.CHANNEL_PREVIEW),
        )
        // 默认参数 = 正式版（老代码/老调用点不传通道时行为不变）
        assertEquals(AppUpdate.MANIFEST_URL, AppUpdate.manifestUrl(AppUpdate.CHANNEL_STABLE))
    }

    @Test
    fun `未知或空通道一律回落到正式版`() {
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.normalizeChannel(null))
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.normalizeChannel(""))
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.normalizeChannel("  "))
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.normalizeChannel("dev"))
        // 大小写/空格容错：prefs 里存脏了也别把正式版用户带到开发版
        assertEquals(AppUpdate.CHANNEL_PREVIEW, AppUpdate.normalizeChannel(" preview "))
        assertEquals(AppUpdate.CHANNEL_PREVIEW, AppUpdate.normalizeChannel("PREVIEW"))
    }

    @Test
    fun `通道中文名`() {
        assertEquals("正式版", AppUpdate.channelLabel(AppUpdate.CHANNEL_STABLE))
        assertEquals("开发版", AppUpdate.channelLabel(AppUpdate.CHANNEL_PREVIEW))
        assertEquals("正式版", AppUpdate.channelLabel(null))
    }

    @Test
    fun `通道归一之后路径与名字一致`() {
        for (channel in listOf("preview", "PREVIEW", " preview ")) {
            assertEquals(
                AppUpdate.MANIFEST_PATH_PREVIEW,
                AppUpdate.manifestPath(channel),
            )
            assertTrue(AppUpdate.manifestUrl(channel).endsWith(AppUpdate.MANIFEST_PATH_PREVIEW))
            assertEquals("开发版", AppUpdate.channelLabel(channel))
        }
    }

    // ---------------------------------------------------------------- 第三百一十四轮

    /** 造一个只关心版本号的清单条目（apk = null，不联网也能构造）。 */
    private fun release(version: String, code: Long) = R2Release(
        version = version,
        versionCode = code,
        tag = "v$version",
        name = "CarDesk $code / $version",
        notes = "",
        publishedAt = "",
        apk = null,
    )

    @Test
    fun `从 versionName 判断包属于哪条通道`() {
        // 开发版包名带 -preview 后缀（打包脚本写死）
        assertEquals(AppUpdate.CHANNEL_PREVIEW, AppUpdate.channelOfVersionName("1.0.1230-preview"))
        assertEquals(AppUpdate.CHANNEL_PREVIEW, AppUpdate.channelOfVersionName("1.0.1230-PREVIEW"))
        // 正式版 / 系统变体 / 读不到都不算什么特殊分支 → 正式版
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.channelOfVersionName("1.0.1219-system"))
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.channelOfVersionName("1.0.1219"))
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.channelOfVersionName("?"))
        assertEquals(AppUpdate.CHANNEL_STABLE, AppUpdate.channelOfVersionName(""))
    }

    @Test
    fun `跨通道判定不比较版本号`() {
        assertTrue(AppUpdate.isBranchSwitch(AppUpdate.CHANNEL_STABLE, AppUpdate.CHANNEL_PREVIEW))
        assertTrue(AppUpdate.isBranchSwitch(AppUpdate.CHANNEL_PREVIEW, AppUpdate.CHANNEL_STABLE))
        // 同通道（含大小写/空格脏值）不算跨
        assertTrue(!AppUpdate.isBranchSwitch(AppUpdate.CHANNEL_STABLE, AppUpdate.CHANNEL_STABLE))
        assertTrue(!AppUpdate.isBranchSwitch(" PREVIEW ", AppUpdate.CHANNEL_PREVIEW))
        assertTrue(!AppUpdate.isBranchSwitch("", AppUpdate.CHANNEL_STABLE))
    }

    @Test
    fun `跨通道时低版本号也算可更新`() {
        // 装着开发版 4326、切到正式版 4315：版本号更小，但这是跨通道换包，应当可更新
        val stable = release("1.0.1219", 4315)
        assertTrue(!AppUpdate.shouldOffer(stable, "1.0.1230-preview", 4326, branchSwitch = false))
        assertTrue(AppUpdate.shouldOffer(stable, "1.0.1230-preview", 4326, branchSwitch = true))
    }

    @Test
    fun `同通道仍然按版本号判断`() {
        // 同通道：更高才提示；相等或更低都不打扰
        assertTrue(AppUpdate.shouldOffer(release("1.0.1231-preview", 4327), "1.0.1230-preview", 4326, false))
        assertTrue(!AppUpdate.shouldOffer(release("1.0.1230-preview", 4326), "1.0.1230-preview", 4326, false))
        assertTrue(!AppUpdate.shouldOffer(release("1.0.1229-preview", 4325), "1.0.1230-preview", 4326, false))
        // 清单没给 versionCode 时退回版本号字符串比较
        assertTrue(AppUpdate.shouldOffer(release("1.0.1231", 0), "1.0.1230-preview", 0, false))
    }
}
