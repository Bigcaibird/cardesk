package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体来源模型单测（映射规则）。
 */
class MediaSourcesTest {

    /** 已安装的包集合：测试里用这个谓词模拟车机上的安装情况。 */
    private fun installed(vararg pkgs: String): (String) -> Boolean {
        val set = pkgs.toSet()
        return { it in set }
    }

    @Test
    fun list_hasNineSourcesInReferenceOrder() {
        assertEquals(9, MediaSources.LIST.size)
        assertEquals(
            listOf("qq", "netease", "kugou", "kuwo", "soda", "local", "generic", "bluetooth", "usb"),
            MediaSources.LIST.map { it.id },
        )
    }

    @Test
    fun labels_and_brandColors_matchReferenceXml() {
        assertEquals("QQ音乐", MediaSources.byId("qq")?.label)
        assertEquals("通用音乐", MediaSources.byId("generic")?.label)
        assertEquals("USB 音乐", MediaSources.byId("usb")?.label)
        assertEquals(0xFF31C27C.toInt(), MediaSources.byId("qq")?.brandColor)
        assertEquals(0xFF8A94A6.toInt(), MediaSources.byId("usb")?.brandColor)
    }

    @Test
    fun packageMapping_followsLabelPrefix() {
        // 解析包名时按「第一个已安装的候选」取（未安装返回 null）
        val all = installed(
            "com.tencent.qqmusiccar",
            "com.netease.cloudmusic.iot",
            "com.kugou.android.auto",
            "com.luna.music.car",
        )
        assertEquals("com.tencent.qqmusiccar", MediaSources.packageFor("QQ音乐", all))
        assertEquals("com.netease.cloudmusic.iot", MediaSources.packageFor("网易云音乐", all))
        assertEquals("com.kugou.android.auto", MediaSources.packageFor("酷狗音乐", all))
        assertEquals("com.luna.music.car", MediaSources.packageFor("汽水音乐", all))
        // 通用音乐不锁定任何包
        assertEquals(null, MediaSources.packageFor("通用音乐", all))
        // 未安装时解析不出包名（提示文案由 pick 决定）
        assertEquals(null, MediaSources.packageFor("QQ音乐", installed()))
    }

    @Test
    fun kuwo_prefersClonePackageWhenInstalled() {
        val onlyClone = installed("cn.kuwo.kwmusiccarclone")
        assertEquals("cn.kuwo.kwmusiccarclone", MediaSources.packageFor("酷我音乐", onlyClone))
        val onlyOriginal = installed("cn.kuwo.kwmusiccar")
        assertEquals("cn.kuwo.kwmusiccar", MediaSources.packageFor("酷我音乐", onlyOriginal))
    }

    @Test
    fun multimedia_usesFirstInstalledCandidate() {
        assertEquals(
            "com.tencent.wecarflow",
            MediaSources.packageFor("多媒体", installed("com.tencent.wecarflow", "com.ecarx.multimedia")),
        )
        assertEquals(
            "com.ecarx.multimedia",
            MediaSources.packageFor("多媒体", installed("com.ecarx.multimedia")),
        )
        // 蓝牙/USB 的候选里多一个系统音乐
        assertEquals(
            "com.android.music",
            MediaSources.packageFor("蓝牙音乐", installed("com.android.music")),
        )
    }

    @Test
    fun pick_genericDoesNotLaunchAnything() {
        val source = MediaSources.byId("generic")!!
        val result = MediaSources.pick(source, installed())
        assertTrue(result is MediaSources.Pick.Generic)
    }

    @Test
    fun pick_installedSourceLaunchesItsPackage() {
        val source = MediaSources.byId("qq")!!
        val result = MediaSources.pick(source, installed("com.tencent.qqmusiccar"))
        assertEquals(MediaSources.Pick.Launch("com.tencent.qqmusiccar"), result)
    }

    @Test
    fun pick_missingSourceUsesReferenceToastText() {
        val isInstalled = installed()
        assertEquals(
            "未安装QQ音乐",
            (MediaSources.pick(MediaSources.byId("qq")!!, isInstalled) as MediaSources.Pick.Missing).message,
        )
        assertEquals(
            "蓝牙未连接",
            (MediaSources.pick(MediaSources.byId("bluetooth")!!, isInstalled) as MediaSources.Pick.Missing).message,
        )
        assertEquals(
            "未检测到USB设备",
            (MediaSources.pick(MediaSources.byId("usb")!!, isInstalled) as MediaSources.Pick.Missing).message,
        )
    }

    @Test
    fun brandColor_fallsBackToGenericForUnknownLabel() {
        assertEquals(0xFFEC4899.toInt(), MediaSources.brandColor("不存在的来源"))
        assertEquals(0xFF00B8D9.toInt(), MediaSources.brandColor("汽水音乐"))
    }

    @Test
    fun defaultMusicPackages_matchReferenceOrder() {
        //
        // QQ 音乐车机版 → 爱趣听 → 酷我 → 酷我 clone → 网易云车机版
        assertEquals(
            listOf(
                "com.tencent.qqmusiccar",
                "com.tencent.wecarflow",
                "cn.kuwo.kwmusiccar",
                "cn.kuwo.kwmusiccarclone",
                "com.netease.cloudmusic.iot",
            ),
            MediaSources.DEFAULT_MUSIC_PACKAGES,
        )
    }

    @Test
    fun defaultMusicPackages_kuwoOrderDiffersFromSourceMenu() {
        // 来源菜单里酷我首选是原包，默认音乐应用列表里原包也在 clone 之前 —— 两处都
        assertEquals("cn.kuwo.kwmusiccar", MediaSources.PRIMARY_PACKAGES["kuwo"])
        assertEquals("cn.kuwo.kwmusiccar", MediaSources.DEFAULT_MUSIC_PACKAGES[2])
        assertEquals("cn.kuwo.kwmusiccarclone", MediaSources.DEFAULT_MUSIC_PACKAGES[3])
    }
}
