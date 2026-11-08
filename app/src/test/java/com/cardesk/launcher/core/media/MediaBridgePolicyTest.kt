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
                "controlTarget",
                "title",
                // 第132轮：仪表有没有图
                "artwork",
                "artworkSource",
                // 第135轮：方控有没有被我们接管
                "claimTarget",
                "nativeSourcePlaying",
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

    // ------------------------------------------------------------ 第128轮：解耦「方控」与「歌名」

    @Test
    fun shouldRegister_doesNotRequireAnySample() {
        // 真车诊断包：bound=true 但 registered=false、callbackCount=0、
        // reason 停在「正在连接原车媒体中心」—— 说明 registerMusic 一次都没被调用过。
        // 卡住它的就是「必须先有样本（歌名）」。
        // 现在：只要桥在跑、Binder 就绪、还没注册 → 就该注册。
        assertTrue(MediaBridgePolicy.shouldRegister(running = true, apiReady = true, registered = false))
    }

    @Test
    fun shouldRegister_notWhenNotRunningOrNotReadyOrAlreadyRegistered() {
        assertFalse(MediaBridgePolicy.shouldRegister(running = false, apiReady = true, registered = false))
        assertFalse(MediaBridgePolicy.shouldRegister(running = true, apiReady = false, registered = false))
        assertFalse(MediaBridgePolicy.shouldRegister(running = true, apiReady = true, registered = true))
    }

    @Test
    fun controlTarget_prefersSampleSource() {
        // 有歌名时以歌名的来源为准（正在放的就是它）
        assertEquals(
            "com.netease.cloudmusic.iot",
            MediaBridgePolicy.controlTargetFor("com.netease.cloudmusic.iot", "com.tencent.qqmusiccar"),
        )
    }

    @Test
    fun controlTarget_fallsBackToChosenSourceWhenNoSample() {
        // 没有歌名（QQ 音乐车机版的常态）→ 用「用户选的媒体源」当兜底目标。
        assertEquals(
            "com.tencent.qqmusiccar",
            MediaBridgePolicy.controlTargetFor("", "com.tencent.qqmusiccar"),
        )
        assertEquals(
            "com.tencent.qqmusiccar",
            MediaBridgePolicy.controlTargetFor(null, "com.tencent.qqmusiccar"),
        )
        // 样本来源是空白字符时也算没有
        assertEquals(
            "com.tencent.qqmusiccar",
            MediaBridgePolicy.controlTargetFor("   ", "com.tencent.qqmusiccar"),
        )
    }

    @Test
    fun controlTarget_emptyWhenNeitherAvailable() {
        assertEquals("", MediaBridgePolicy.controlTargetFor(null, null))
        assertEquals("", MediaBridgePolicy.controlTargetFor("", ""))
    }

    @Test
    fun acceptsControl_onlyThirdPartyOnlinePlayers() {
        // 红线不变：方控**只**能发给三方在线播放器，
        // 原车收音机 / USB / 蓝牙一律不碰。
        assertTrue(MediaBridgePolicy.acceptsControl("com.tencent.qqmusiccar"))
        assertTrue(MediaBridgePolicy.acceptsControl("com.netease.cloudmusic.iot"))
        assertTrue(MediaBridgePolicy.acceptsControl("com.luna.music.car"))
        assertFalse(MediaBridgePolicy.acceptsControl("com.android.bluetooth"))
        assertFalse(MediaBridgePolicy.acceptsControl("com.tencent.wecarflow"))
        assertFalse(MediaBridgePolicy.acceptsControl(null))
        assertFalse(MediaBridgePolicy.acceptsControl(""))
    }

    // ------------------------------------------------------------ 第135轮：主动 claim

    @Test
    fun shouldClaim_yesWhenRegisteredAndTargetIsThirdPartyPlayer() {
        // 对标 App「启用即 claim」：注册成功 + 目标是三方在线播放器 → 主动接管播放入口。
        assertTrue(
            MediaBridgePolicy.shouldClaim(
                registered = true,
                target = "com.tencent.qqmusiccar",
                claimedTarget = null,
                nativeSourcePlaying = false,
            )
        )
    }

    @Test
    fun shouldClaim_noWhenNotRegisteredYet() {
        // 没注册就没有令牌，claim 无从谈起。
        assertFalse(
            MediaBridgePolicy.shouldClaim(
                registered = false,
                target = "com.tencent.qqmusiccar",
                claimedTarget = null,
                nativeSourcePlaying = false,
            )
        )
    }

    @Test
    fun shouldClaim_noForRadioUsbBtAndNativeAudio() {
        // 红线：原车收音机 / USB / 蓝牙 / 爱趣听，一概不碰。
        for (pkg in listOf(
            "com.android.bluetooth",
            "com.tencent.wecarflow",
            "com.ecarx.media",
            "",
        )) {
            assertFalse(
                "不该 claim $pkg",
                MediaBridgePolicy.shouldClaim(
                    registered = true,
                    target = pkg,
                    claimedTarget = null,
                    nativeSourcePlaying = false,
                )
            )
        }
        assertFalse(
            MediaBridgePolicy.shouldClaim(
                registered = true,
                target = null,
                claimedTarget = null,
                nativeSourcePlaying = false,
            )
        )
    }

    @Test
    fun shouldClaim_noWhenNativeSourceIsPlaying() {
        // 对标 App SessionBridge.pushToMediaCenter 开头就是
        // `else if (isNativeSystemMediaSource(pkg)) { ...skip... }` ——
        // 用户正在听电台时对标 App 自己都不 claim，我们照做。
        assertFalse(
            MediaBridgePolicy.shouldClaim(
                registered = true,
                target = "com.tencent.qqmusiccar",
                claimedTarget = null,
                nativeSourcePlaying = true,
            )
        )
    }

    @Test
    fun shouldClaim_noWhenAlreadyClaimedForThatTarget() {
        // 只 claim 一次：不跟用户在车机里手动切源来回打架。
        assertFalse(
            MediaBridgePolicy.shouldClaim(
                registered = true,
                target = "com.tencent.qqmusiccar",
                claimedTarget = "com.tencent.qqmusiccar",
                nativeSourcePlaying = false,
            )
        )
        // 目标换了 → 可以再 claim 一次
        assertTrue(
            MediaBridgePolicy.shouldClaim(
                registered = true,
                target = "com.netease.cloudmusic.iot",
                claimedTarget = "com.tencent.qqmusiccar",
                nativeSourcePlaying = false,
            )
        )
    }

    @Test
    fun hasNativeSourcePlaying_detectsNonThirdPartyPlayingSources() {
        // 对标 App 口径：车机自带音源（爱趣听一类）在放的时候，对标 App 自己都不 claim。
        assertTrue(
            MediaBridgePolicy.hasNativeSourcePlaying(
                listOf("com.tencent.wecarflow"),
            )
        )
        assertTrue(
            MediaBridgePolicy.hasNativeSourcePlaying(
                listOf("com.tencent.qqmusiccar", "com.android.bluetooth"),
            )
        )
        // 三方在线播放器在放 → 不是「车机自带音源在放」→ 正常 claim
        assertFalse(
            MediaBridgePolicy.hasNativeSourcePlaying(
                listOf("com.tencent.qqmusiccar"),
            )
        )
        assertFalse(MediaBridgePolicy.hasNativeSourcePlaying(emptyList()))
        // 空包名不算（那是「没有会话」，不是「车机自带音源在放」）
        assertFalse(MediaBridgePolicy.hasNativeSourcePlaying(listOf("")))
    }

    @Test
    fun snapshotKeywords_includeClaimDiagnostics() {
        // 真车排障第一眼要看的两行：接管了没有、接管的是谁。
        val keys = MediaBridgePolicy.snapshotKeywords()
        assertTrue(keys.contains("claimTarget"))
        assertTrue(keys.contains("nativeSourcePlaying"))
        assertEquals("已接管的播放入口", MediaBridgePolicy.labelFor("claimTarget"))
        assertEquals("车机自带音源正在放", MediaBridgePolicy.labelFor("nativeSourcePlaying"))
    }
}
