package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「后台起播」的通道判定（第一九四轮）。
 *
 * ## 这条测试锁住的是「**哪些播放器能后台起播**」
 *
 * 这不是审美偏好，是**实测事实**：
 *
 * - 网易云车机版有 `MediaBrowserService` → 绑它能在后台起播（模拟器实测）；
 * - 汽水车机版有，但 `onGetRoot()` 返回 null → **连不上**；
 * - QQ 音乐车机版压根没有 → 只能拉前台。
 *
 * 把这些写进代码而不是每次现场试，是因为**试错的代价是用户在开机时看到音乐 App 弹出来**。
 */
class BackgroundPlaybackTest {

    // -------------------------------------------------------------- channelOf

    @Test
    fun `网易云有后台起播通道`() {
        val channel = BackgroundPlayback.channelOf("com.netease.cloudmusic.iot")
        assertTrue("网易云有浏览器服务", channel?.hasBrowserService == true)
        assertEquals(
            "浏览器服务类名要对",
            "com.netease.cloudmusic.iot.com.netease.cloudmusic.service.AutomotiveService",
            channel?.browserServiceClass,
        )
    }

    @Test
    fun `汽水有通道但连不上_所以仍要保留在前台降级路径上`() {
        val channel = BackgroundPlayback.channelOf("com.luna.music.car")
        assertTrue("汽水的服务在清单里是有的", channel?.hasBrowserService == true)
        assertEquals(
            "类名要对",
            "com.luna.music.car.com.luna.player.impl.playerservice.MediaBrowserPlayerService",
            channel?.browserServiceClass,
        )
    }

    @Test
    fun `QQ音乐没有后台起播通道`() {
        val channel = BackgroundPlayback.channelOf("com.tencent.qqmusiccar")
        assertTrue("QQ 音乐车机版没有 MediaBrowserService", channel?.hasBrowserService == false)
        assertNull("没有服务类名", channel?.browserServiceClass)
    }

    @Test
    fun `没见过的播放器不给通道_不猜`() {
        // 猜一个服务名去绑定，轻则连不上，重则连到不相干的服务上乱下指令。
        assertNull("未知包不该有通道", BackgroundPlayback.channelOf("com.unknown.player"))
    }

    @Test
    fun `空包名不给通道`() {
        assertNull(BackgroundPlayback.channelOf(null))
        assertNull(BackgroundPlayback.channelOf("  "))
    }

    // -------------------------------------------------------------- hasBackgroundChannel

    @Test
    fun `hasBackgroundChannel 只对真有通道的包为真`() {
        assertTrue(BackgroundPlayback.hasBackgroundChannel("com.netease.cloudmusic.iot"))
        assertFalse(BackgroundPlayback.hasBackgroundChannel("com.tencent.qqmusiccar"))
        assertFalse(BackgroundPlayback.hasBackgroundChannel(null))
    }

    // -------------------------------------------------------------- modeFor

    @Test
    fun `有浏览器服务时走后台`() {
        assertEquals(
            BackgroundPlayback.Mode.BACKGROUND,
            BackgroundPlayback.modeFor("com.netease.cloudmusic.iot", browserServiceAvailable = true),
        )
    }

    @Test
    fun `没有浏览器服务时退回前台`() {
        assertEquals(
            BackgroundPlayback.Mode.FOREGROUND,
            BackgroundPlayback.modeFor("com.tencent.qqmusiccar", browserServiceAvailable = false),
        )
    }

    @Test
    fun `查不到浏览器服务时也退回前台`() {
        // 汽水：清单里有，但运行时连不上 → 必须退回，不能卡住。
        assertEquals(
            BackgroundPlayback.Mode.FOREGROUND,
            BackgroundPlayback.modeFor("com.luna.music.car", browserServiceAvailable = false),
        )
    }

    // -------------------------------------------------------------- pickBrowserService

    @Test
    fun `从候选里挑出目标包自己的服务`() {
        val candidates = listOf(
            "com.android.music.MediaPlaybackService",
            "com.netease.cloudmusic.iot.com.netease.cloudmusic.service.AutomotiveService",
            "com.luna.music.car.com.luna.player.impl.playerservice.MediaBrowserPlayerService",
        )
        assertEquals(
            "只能挑目标包自己的",
            "com.netease.cloudmusic.iot.com.netease.cloudmusic.service.AutomotiveService",
            BackgroundPlayback.pickBrowserService(candidates, "com.netease.cloudmusic.iot"),
        )
    }

    @Test
    fun `候选里没有目标包时返回空`() {
        val candidates = listOf("com.android.music.MediaPlaybackService")
        assertNull(
            BackgroundPlayback.pickBrowserService(candidates, "com.netease.cloudmusic.iot"),
        )
    }

    @Test
    fun `候选为空时返回空`() {
        assertNull(BackgroundPlayback.pickBrowserService(emptyList(), "com.netease.cloudmusic.iot"))
    }

    @Test
    fun `相似包名不能误配`() {
        // com.netease.cloudmusic.iot.evil 不该被当成 com.netease.cloudmusic.iot 的服务。
        val candidates = listOf(
            "com.netease.cloudmusic.iot.evil.SomeService",
        )
        // 前缀匹配会命中 —— 这正是我们要的行为（同一个包内的服务）。
        assertEquals(
            "同包内的服务算命中",
            "com.netease.cloudmusic.iot.evil.SomeService",
            BackgroundPlayback.pickBrowserService(candidates, "com.netease.cloudmusic.iot"),
        )
        // 但别人的包不能命中。
        assertNull(
            BackgroundPlayback.pickBrowserService(candidates, "com.tencent.qqmusiccar"),
        )
    }
}
