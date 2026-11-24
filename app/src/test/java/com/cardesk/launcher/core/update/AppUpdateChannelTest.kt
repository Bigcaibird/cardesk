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
}
