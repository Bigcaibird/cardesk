package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlayTarget] 的单测（第131轮）。
 *
 * ## 这条测试守住什么
 *
 * 用户诉求：「没打开 QQ 音乐或任何第三方播放器时，按方向盘播放键，
 * **打开的是爱趣听** —— 能不能让方控控制桌面默认选定的播放器？」
 *
 * 判据全在纯逻辑里，所以这几条能钉死：
 * - **桌面选定的来源优先**于「记住的上次播放器」（后者是历史）；
 * - **车机原厂音源不由我们接管**（红线：原车收音机 / USB / 蓝牙不碰）；
 * - 判不准（没装 / 没有目标）时**宁可不动**。
 */
class PlayTargetTest {

    /** 模拟「装了什么」。 */
    private val installed = setOf(
        "com.tencent.qqmusiccar",
        "com.netease.cloudmusic.iot",
        "com.luna.music.car",
        "com.tencent.wecarflow",
    )

    private fun isInstalled(pkg: String) = pkg in installed

    private val defaults = MediaSources.DEFAULT_MUSIC_PACKAGES

    // ---------------------------------------------------------------- 原厂音源判定

    @Test
    fun `爱趣听一类算车机原厂音源`() {
        assertTrue(PlayTarget.isCarAudioPkg("com.tencent.wecarflow"))
        assertTrue(PlayTarget.isCarAudioPkg("com.aiquting.radio"))
        assertTrue(PlayTarget.isCarAudioPkg("com.tencent.qqmusictv"))
    }

    @Test
    fun `三方播放器不算车机原厂音源`() {
        assertFalse(PlayTarget.isCarAudioPkg("com.tencent.qqmusiccar"))
        assertFalse(PlayTarget.isCarAudioPkg("com.netease.cloudmusic.iot"))
        assertFalse(PlayTarget.isCarAudioPkg("com.luna.music.car"))
    }

    @Test
    fun `空目标当作没有目标_不由我们接管`() {
        assertTrue(PlayTarget.isCarAudioPkg(""))
        assertTrue(PlayTarget.isCarAudioPkg(null))
        assertFalse(PlayTarget.shouldTakeOver(""))
        assertFalse(PlayTarget.shouldTakeOver(null))
    }

    @Test
    fun `原厂音源不由我们接管`() {
        assertFalse(PlayTarget.shouldTakeOver("com.tencent.wecarflow"))
        assertTrue(PlayTarget.shouldTakeOver("com.tencent.qqmusiccar"))
    }

    // ---------------------------------------------------------------- 目标解析

    @Test
    fun `桌面选定的来源优先于记住的上次播放器`() {
        // 真车诊断包实测的错位：用户选了 QQ 音乐，上次播的却是原厂音源
        val target = PlayTarget.resolve(
            currentSourcePkg = "com.tencent.qqmusiccar",
            defaultSourcePkg = "",
            rememberedPkg = "com.tencent.wecarflow",
            defaultPackages = defaults,
            isInstalled = ::isInstalled,
        )
        assertEquals("com.tencent.qqmusiccar", target)
    }

    @Test
    fun `卡片来源是通用音乐时用设置页的默认源`() {
        val target = PlayTarget.resolve(
            currentSourcePkg = "", // 通用音乐 → 没有对应 App
            defaultSourcePkg = "com.netease.cloudmusic.iot",
            rememberedPkg = "com.luna.music.car",
            defaultPackages = defaults,
            isInstalled = ::isInstalled,
        )
        assertEquals("com.netease.cloudmusic.iot", target)
    }

    @Test
    fun `都没选时用记住的上次播放器`() {
        val target = PlayTarget.resolve(
            currentSourcePkg = "",
            defaultSourcePkg = "",
            rememberedPkg = "com.luna.music.car",
            defaultPackages = defaults,
            isInstalled = ::isInstalled,
        )
        assertEquals("com.luna.music.car", target)
    }

    @Test
    fun `记住的是原厂音源时跳过它_不要打开爱趣听`() {
        val target = PlayTarget.resolve(
            currentSourcePkg = "",
            defaultSourcePkg = "",
            rememberedPkg = "com.tencent.wecarflow",
            defaultPackages = defaults,
            isInstalled = ::isInstalled,
        )
        // 兜底走默认音乐应用列表：QQ 音乐车机版排第一
        assertEquals("com.tencent.qqmusiccar", target)
    }

    @Test
    fun `选定的来源没装就跳到下一个候选`() {
        val target = PlayTarget.resolve(
            currentSourcePkg = "com.kugou.android.auto", // 没装
            defaultSourcePkg = "com.netease.cloudmusic.iot", // 装了
            rememberedPkg = "com.luna.music.car",
            defaultPackages = defaults,
            isInstalled = ::isInstalled,
        )
        assertEquals("com.netease.cloudmusic.iot", target)
    }

    @Test
    fun `什么都没选也没记住时用默认音乐应用列表的第一个`() {
        val target = PlayTarget.resolve(
            currentSourcePkg = "",
            defaultSourcePkg = "",
            rememberedPkg = "",
            defaultPackages = defaults,
            isInstalled = ::isInstalled,
        )
        assertEquals("com.tencent.qqmusiccar", target)
    }

    @Test
    fun `一个都没有时返回空_不乱拉应用`() {
        val target = PlayTarget.resolve(
            currentSourcePkg = "",
            defaultSourcePkg = "",
            rememberedPkg = "",
            defaultPackages = defaults,
            isInstalled = { false },
        )
        assertEquals("", target)
    }

    // ---------------------------------------------------------------- 走哪条路

    @Test
    fun `有明确三方目标时走桌面选定媒体源那条路`() {
        assertEquals(
            PlayTarget.NoSessionAction.SELECTED_SOURCE,
            PlayTarget.noSessionAction("com.tencent.qqmusiccar"),
        )
    }

    @Test
    fun `没有目标或目标是原厂音源时走旧路`() {
        // 没有目标 → 旧逻辑（车机原厂音源那套 DMT 恢复）
        assertEquals(PlayTarget.NoSessionAction.FALLBACK, PlayTarget.noSessionAction(""))
        assertEquals(PlayTarget.NoSessionAction.FALLBACK, PlayTarget.noSessionAction(null))
        // 目标是原厂音源 → 旧逻辑（红线：不碰原车音源）
        assertEquals(
            PlayTarget.NoSessionAction.FALLBACK,
            PlayTarget.noSessionAction("com.tencent.wecarflow"),
        )
    }

    @Test
    fun `QQ音乐车机版走三方协议`() {
        assertTrue(PlayTarget.isQqTarget("com.tencent.qqmusiccar"))
        assertTrue(PlayTarget.isQqTarget(" com.tencent.qqmusiccar "))
        assertFalse(PlayTarget.isQqTarget("com.netease.cloudmusic.iot"))
        assertFalse(PlayTarget.isQqTarget(""))
    }
}
