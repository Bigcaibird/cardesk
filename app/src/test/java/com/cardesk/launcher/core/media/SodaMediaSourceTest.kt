package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第127轮：汽水音乐车机版（`com.luna.music.car`）的协议事实单测。
 *
 * 事实来源：模拟器上装真的汽水音乐车机版 1.1.27（versionCode 101027），
 * `jadx` 反编译 `com/luna/` + `aapt2` 导清单。
 *
 * 这些断言的作用是**把协议钉死**：以后有人改白名单 / 把汽水当成「歌词型来源」，
 * 这里立刻变红。
 */
class SodaMediaSourceTest {

    @Test
    fun packageNameIsTheCarBuild() {
        assertEquals("com.luna.music.car", SodaMediaSource.PACKAGE)
        // 手机版是另一个包（`com.luna.music`），不能混
        assertFalse(SodaMediaSource.PACKAGE == "com.luna.music")
    }

    @Test
    fun usesStandardTransport_soNoNeteaseStyleKeyException() {
        // 汽水声明了 action 位（950/694）且实现了 onPlay/onPause/onSkipToNext
        // → 标准 transportControls 就能控，不需要定向按键特例
        assertTrue(SodaMediaSource.usesStandardTransport())
        // 网易云那种特例只对网易云生效
        assertTrue(MediaButtonTransport.shouldUseTransportControl(
            MediaButtonTransport.NETEASE_PACKAGE, MediaButtonTransport.ACTION_PLAY,
        ))
        assertFalse(MediaButtonTransport.shouldUseTransportControl(
            SodaMediaSource.PACKAGE, MediaButtonTransport.ACTION_PLAY,
        ))
    }

    @Test
    fun doesNotExposeLyricSoLyricsMustComeFromNetwork() {
        // metadata 无歌词 key、无会话事件、browse 树为空 → 三方拿不到歌词
        assertFalse(SodaMediaSource.exposesLyricToThirdParty())
    }

    @Test
    fun titleHoldsSongNameNotLyricLine() {
        // 车机版把 blueToothLyricOpen 硬编码成 false → TITLE 永远是歌名
        assertFalse(SodaMediaSource.titleHoldsLyricLine())
        // 所以汽水**不是**「歌词型来源」（与微信车机 / 爱趣听相反）
        assertFalse(LyricMatch.isLyricTitleSource(SodaMediaSource.PACKAGE))
        assertTrue(LyricMatch.isLyricTitleSource("com.tencent.wecarflow"))
    }

    // ------------------------------------------------------------ 接线

    @Test
    fun oemBridgeAcceptsSodaSoSteeringWheelCanReachIt() {
        // 不在白名单 → 原车不会把方向盘方控交给我们 → 控不到汽水
        assertTrue(MediaBridgePolicy.isEligible(SodaMediaSource.PACKAGE))
    }

    @Test
    fun sodaIsAMusicSourceAndCanBeTheDefault() {
        assertEquals("汽水音乐", MediaSources.labelForPackage(SodaMediaSource.PACKAGE))
        assertEquals("soda", MediaSources.idForPackage(SodaMediaSource.PACKAGE))
        assertTrue(MediaSources.DEFAULT_MUSIC_PACKAGES.contains(SodaMediaSource.PACKAGE))
        // 图标走真实 App 图标
        assertEquals(
            listOf(SodaMediaSource.PACKAGE),
            MediaSources.iconPackageCandidates("soda"),
        )
    }
}
