package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第八十七轮：OEM MediaCenter 桥的准入 / 接线规则（[MediaBridgePolicy]）单测。
 *
 * 真车上没有原车 SDK 就没法验这条反射链，所以**边界必须在 JVM 上钉死**：
 * 谁能进白名单、什么时候抢播放权、暂停要上报什么值、诊断字段有哪些。
 */
class MediaBridgePolicyTest {

    // ------------------------------------------------------------ 谁能进白名单

    @Test
    fun eligiblePackages_onlyThirdPartyOnlinePlayers() {
        // 顺序固定（日志 / 报告按它对照），内容只有三方在线播放器
        assertEquals(
            listOf(
                "com.netease.cloudmusic.iot",
                "com.tencent.qqmusiccar",
                "com.tencent.qqmusic",
                "com.luna.music.car",
            ),
            MediaBridgePolicy.eligiblePackages(),
        )
        assertTrue(MediaBridgePolicy.eligiblePackages().contains("com.netease.cloudmusic.iot"))
    }

    @Test
    fun isEligible_acceptsExactlyTheWhitelist() {
        assertTrue(MediaBridgePolicy.isEligible("com.netease.cloudmusic.iot"))
        assertTrue(MediaBridgePolicy.isEligible("com.tencent.qqmusiccar"))
        assertTrue(MediaBridgePolicy.isEligible("com.tencent.qqmusic"))
        // 第127轮：汽水音乐车机版也是三方在线播放器，必须能接管播放权
        assertTrue(MediaBridgePolicy.isEligible("com.luna.music.car"))
        // 容错空格（会话包名偶尔带空白）
        assertTrue(MediaBridgePolicy.isEligible(" com.tencent.qqmusic "))
    }

    @Test
    fun isEligible_rejectsEverythingElseIncludingOnesRadioAndBt() {
        // 红线：原车收音机 / USB / 蓝牙 / 系统多媒体有自己的 owner，绝不接管
        assertFalse(MediaBridgePolicy.isEligible("ecarx.xsf.mediacenter"))
        assertFalse(MediaBridgePolicy.isEligible("com.ecarx.multimedia"))
        assertFalse(MediaBridgePolicy.isEligible("com.android.bluetooth"))
        assertFalse(MediaBridgePolicy.isEligible("com.ecarx.radio"))
        // 手机版网易云不是车机版（它没那套按键行为）
        assertFalse(MediaBridgePolicy.isEligible("com.netease.cloudmusic"))
        assertFalse(MediaBridgePolicy.isEligible(null))
        assertFalse(MediaBridgePolicy.isEligible(""))
        assertFalse(MediaBridgePolicy.isEligible("   "))
    }

    // ------------------------------------------------------------ 抢播放权

    @Test
    fun shouldAcquire_onlyWhenPlayingAndSomethingChanged() {
        // 刚开始播（暂停→播放）
        assertTrue(MediaBridgePolicy.shouldAcquire(playing = true, stateChanged = true, sourceChanged = false))
        // 换了来源（网易云→QQ 音乐）且正在播
        assertTrue(MediaBridgePolicy.shouldAcquire(playing = true, stateChanged = false, sourceChanged = true))
        // 两个都变
        assertTrue(MediaBridgePolicy.shouldAcquire(playing = true, stateChanged = true, sourceChanged = true))
    }

    @Test
    fun shouldAcquire_neverWhenPaused() {
        // 暂停时抢播放权会让原车把「当前音频源」切到在线音乐，
        // 而用户其实可能在听收音机 —— 所以任何变化都不抢。
        assertFalse(MediaBridgePolicy.shouldAcquire(playing = false, stateChanged = true, sourceChanged = true))
        assertFalse(MediaBridgePolicy.shouldAcquire(playing = false, stateChanged = true, sourceChanged = false))
        assertFalse(MediaBridgePolicy.shouldAcquire(playing = false, stateChanged = false, sourceChanged = true))
    }

    @Test
    fun shouldAcquire_neverWhenNothingChanged() {
        // 每 200ms 一 tick 都抢会把原车播放权来回甩，必须只在变化时抢一次
        assertFalse(MediaBridgePolicy.shouldAcquire(playing = true, stateChanged = false, sourceChanged = false))
    }

    // ------------------------------------------------------------ 上报给原车的播放状态

    @Test
    fun wirePlayback_matchesOriginalProtocolValues() {
        // 原车 IMusicPlaybackInfo.getPlaybackStatus：1 = 播放中，0 = 非播放（不是布尔）
        assertEquals(1, MediaBridgePolicy.wirePlayback(true))
        assertEquals(0, MediaBridgePolicy.wirePlayback(false))
    }

    // ------------------------------------------------------------ 诊断字段

    @Test
    fun snapshotKeywords_areFlatStableAndComplete() {
        val keys = MediaBridgePolicy.snapshotKeywords()
        assertEquals(
            listOf(
                "registered",
                "playbackAccepted",
                "playClaimed",
                "bound",
                "sourceType",
                "sourceKind",
                "source",
                "title",
                "callbackCount",
                "lastCallback",
                "reason",
            ),
            keys,
        )
        // 顺序即设置页展示顺序，且不许有重复
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun diagnosticLabelsCoverEveryKeyword() {
        // 设置页要靠 labelFor 显示中文；漏一个字段就等于设置页多一行英文键名
        MediaBridgePolicy.snapshotKeywords().forEach { key ->
            assertTrue(
                "字段 $key 缺少中文标签",
                MediaBridgePolicy.labelFor(key) != key,
            )
        }
        assertEquals("当前状态说明", MediaBridgePolicy.labelFor("reason"))
        // 未知键原样返回，不抛
        assertEquals("unknown-key", MediaBridgePolicy.labelFor("unknown-key"))
    }

    @Test
    fun onlineSourceType_isSix() {
        // 原车协议常量：在线音乐 = 6（UNI 的 ONLINE）
        assertEquals(6, MediaBridgePolicy.ONLINE_SOURCE_TYPE)
    }

    // ------------------------------------------------------------ 来源合并（第126轮）

    @Test
    fun pickSource_sessionWinsOverBroadcast() {
        // 真的有别的 App 接管了会话 → 广播那份让位。
        // 否则仪表上会一直挂着上一首 QQ 音乐的歌。
        assertEquals(
            MediaBridgePolicy.BridgeSource.SESSION,
            MediaBridgePolicy.pickSource(sessionAvailable = true, broadcastAvailable = true),
        )
    }

    @Test
    fun pickSource_broadcastUsedWhenNoSession() {
        // QQ 音乐车机版没有可用会话 → 只能用广播那份。
        // 不然桥拿不到样本 → 不注册到原车 → 方控还在爱趣听上（真车现象）。
        assertEquals(
            MediaBridgePolicy.BridgeSource.BROADCAST,
            MediaBridgePolicy.pickSource(sessionAvailable = false, broadcastAvailable = true),
        )
    }

    @Test
    fun pickSource_noneWhenBothMissing() {
        assertEquals(
            MediaBridgePolicy.BridgeSource.NONE,
            MediaBridgePolicy.pickSource(sessionAvailable = false, broadcastAvailable = false),
        )
    }
}
