package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体源的**图标映射**与**默认源解析**（第 125 轮）。
 *
 * 这两件事都是纯逻辑，不碰 Android —— 所以能直接 JVM 单测。
 *
 * - 图标映射：哪个来源用真 App 图标、哪个用自绘符号、该取哪个包；
 * - 默认源解析：设置里选的默认源 → 该锁哪个包（**通用音乐不锁包**）。
 */
class MediaSourceIconMappingTest {

    @Test
    fun `有对应App的来源用真图标`() {
        // 这 5 家 + 多媒体都有 App，装了就能取到真实图标。
        for (id in listOf("qq", "netease", "kugou", "kuwo", "soda", "local")) {
            assertEquals(
                "$id 应该用真实 App 图标",
                MediaSourceIconKind.APP,
                MediaSources.iconKind(id),
            )
            assertTrue(
                "$id 应该有图标候选包",
                MediaSources.iconPackageCandidates(id).isNotEmpty(),
            )
        }
    }

    @Test
    fun `蓝牙_USB_通用音乐用自绘符号`() {
        assertEquals(MediaSourceIconKind.BLUETOOTH, MediaSources.iconKind("bluetooth"))
        assertEquals(MediaSourceIconKind.USB, MediaSources.iconKind("usb"))
        assertEquals(MediaSourceIconKind.NOTE, MediaSources.iconKind("generic"))
        // 自绘符号没有对应 App，不该有候选包。
        assertTrue(MediaSources.iconPackageCandidates("bluetooth").isEmpty())
        assertTrue(MediaSources.iconPackageCandidates("usb").isEmpty())
        assertTrue(MediaSources.iconPackageCandidates("generic").isEmpty())
    }

    @Test
    fun `不认识的来源id回落到音符符号`() {
        assertEquals(MediaSourceIconKind.NOTE, MediaSources.iconKind("who-knows"))
        assertTrue(MediaSources.iconPackageCandidates("who-knows").isEmpty())
    }

    @Test
    fun `图标候选包和来源菜单的候选包一致`() {
        // 图标必须取**同一个 App** —— 取成别的 App 图标会张冠李戴。
        for (id in listOf("qq", "netease", "kugou", "kuwo", "soda")) {
            val source = MediaSources.byId(id)!!
            assertEquals(
                "$id 的图标包应该和来源候选一致",
                MediaSources.packageCandidates(source.label),
                MediaSources.iconPackageCandidates(id),
            )
        }
    }

    @Test
    fun `通用音乐不锁包`() {
        // 默认源是「通用音乐」= 跟随系统当前会话，**不能**锁任何包。
        assertNull(
            MediaSources.resolveDefaultPackage("generic") { true },
        )
    }

    @Test
    fun `默认源锁已安装的那个包`() {
        val pkg = MediaSources.resolveDefaultPackage("qq") { it == "com.tencent.qqmusiccar" }
        assertEquals("com.tencent.qqmusiccar", pkg)
    }

    @Test
    fun `默认源没装就不锁包`() {
        assertNull(
            MediaSources.resolveDefaultPackage("qq") { false },
        )
    }

    @Test
    fun `不认识的默认源不锁包`() {
        assertNull(
            MediaSources.resolveDefaultPackage("nope") { true },
        )
    }

    @Test
    fun `多个候选时取第一个已安装的`() {
        // 酷我有 clone 变体：两个候选里装了哪个就取哪个。
        val pkg = MediaSources.resolveDefaultPackage("kuwo") { it == "cn.kuwo.kwmusiccar" }
        assertEquals("cn.kuwo.kwmusiccar", pkg)
    }
}
