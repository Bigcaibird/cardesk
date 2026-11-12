package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「桌面启动后唤起默认媒体源 App 并让它播放」的判定逻辑（第149轮）。
 *
 * ## 这条测试锁住的是「**什么时候不该动**」
 *
 * 这个功能最危险的失败模式不是「没唤起」，而是**不该唤起却唤起了** ——
 * 用户明明关了开关，开机还是弹出音乐 App；或者默认源是「通用音乐」
 * （压根没有对应的 App），硬去拉一个包名。
 *
 * 所以判定分支一个都不能少，每条都单独锁住。
 */
class MediaAppLauncherTest {

    // -------------------------------------------------------------- shouldLaunch

    @Test
    fun `开关关着时不动作`() {
        assertFalse(
            "开关关着就不能唤起（默认就是关的）",
            MediaAppLauncher.shouldLaunch(autoLaunchEnabled = false, sourceId = "qq"),
        )
    }

    @Test
    fun `通用音乐不动作`() {
        assertFalse(
            "「通用音乐」是跟随系统当前会话，根本没有对应的 App 可拉",
            MediaAppLauncher.shouldLaunch(autoLaunchEnabled = true, sourceId = MediaSources.GENERIC_ID),
        )
    }

    @Test
    fun `来源是空串时不动作`() {
        assertFalse(
            "空来源 id 不该去解析包名",
            MediaAppLauncher.shouldLaunch(autoLaunchEnabled = true, sourceId = ""),
        )
    }

    @Test
    fun `开关开着且选了具体来源才动作`() {
        assertTrue(
            "开关开着 + 选了 QQ音乐 → 该动作",
            MediaAppLauncher.shouldLaunch(autoLaunchEnabled = true, sourceId = "qq"),
        )
    }

    // -------------------------------------------------------------- resolvePackage

    @Test
    fun `能解析出已安装的 App 包名`() {
        val pkg = MediaAppLauncher.resolvePackage("qq") { true }
        assertEquals("QQ音乐 对应的包名", "com.tencent.qqmusiccar", pkg)
    }

    @Test
    fun `没装对应 App 时解析不出包名`() {
        assertNull(
            "一个都没装 → 没有包名可拉",
            MediaAppLauncher.resolvePackage("qq") { false },
        )
    }

    @Test
    fun `通用音乐解析不出包名`() {
        assertNull(
            "通用音乐没有对应 App",
            MediaAppLauncher.resolvePackage(MediaSources.GENERIC_ID) { true },
        )
    }

    @Test
    fun `不认识的来源 id 解析不出包名`() {
        assertNull(
            "未知来源 id 不该崩、也不该瞎拉一个包",
            MediaAppLauncher.resolvePackage("not_a_real_source") { true },
        )
    }

    @Test
    fun `多候选来源取第一个已装的`() {
        // 酷我有 clone 变体：cn.kuwo.kwmusiccarclone / cn.kuwo.kwmusiccar
        val installed = setOf("cn.kuwo.kwmusiccar")
        val pkg = MediaAppLauncher.resolvePackage("kuwo") { it in installed }
        assertEquals("应该取到已安装的那个候选", "cn.kuwo.kwmusiccar", pkg)
    }

    // -------------------------------------------------------------- sessionMatches

    @Test
    fun `会话包名和目标一致才算命中`() {
        assertTrue(
            "同一个包名算命中",
            MediaAppLauncher.sessionMatches("com.tencent.qqmusiccar", "com.tencent.qqmusiccar"),
        )
    }

    @Test
    fun `会话包名不一样不算命中`() {
        assertFalse(
            "别的 App 的会话不能当成自己的（打了就等于替第三方按播放）",
            MediaAppLauncher.sessionMatches("com.other.player", "com.tencent.qqmusiccar"),
        )
    }

    @Test
    fun `还没有会话时不算命中`() {
        assertFalse(
            "null 会话不能当成命中",
            MediaAppLauncher.sessionMatches(null, "com.tencent.qqmusiccar"),
        )
    }
}
