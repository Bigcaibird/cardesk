package com.cardesk.launcher.core.media

/**
 * OEM MediaCenter 桥的**准入 / 接线规则**（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么要有这个文件（第八十七轮）
 *
 * 原车（IP753 / Ecarx）的仪表与方向盘方控走的是**私有 MediaCenter Binder**，
 * 不看 Android `MediaSession`。想让「仪表显示的歌」和「方控切歌」作用到我们接管的
 * 三方音乐 App，必须把自己注册成原车的一个「在线音乐源」。
 *
 * 但这里有一条**红线**：我们只接管**三方在线播放器**（网易云 / QQ 音乐车机版 / QQ 音乐）。
 * 原车的收音机、USB、蓝牙音乐有它们自己的 owner —— 我们去抢那些 sourceType，
 * 轻则仪表显示错乱，重则原车功能直接失效。参考 UNI 的注释：
 * "Never pose as the original radio/BT/USB owner"。
 *
 * 把「谁能进、什么时候抢播放权、给原车报什么状态」抽成纯对象，是为了：
 * 1. 能在 JVM 单测里把边界钉死（真车上没有原车 SDK 就没法验）；
 * 2. 反射桥（[OfficialMediaBridge]）只剩「搬砖」，读代码时不用同时理解策略。
 */
object MediaBridgePolicy {

    /** 原车 MediaCenter 的 `sourceType`：在线音乐 = 6（UNI `ONLINE`）。 */
    const val ONLINE_SOURCE_TYPE = 6

    /**
     * 允许接管的包名（**只**三方在线播放器）。
     *
     * 与 [MediaSources] 的 `netease` / `qq` / `soda` 来源一致；`com.tencent.qqmusic` 是手机版，
     * 留在列表里是因为部分车机装的是手机版（UNI 也一并接受）。
     *
     * 第127轮新增 `com.luna.music.car`（**汽水音乐车机版**）：
     * 它也是三方在线播放器，**不进白名单原车就不会把方向盘方控交给我们** ——
     * 与 QQ 音乐车机版同一条红线（只抢三方在线播放器的播放权，
     * 原车收音机 / USB / 蓝牙一律不碰）。
     *
     * 顺序固定，便于日志与单测断言。
     */
    private val ELIGIBLE_PACKAGES = listOf(
        "com.netease.cloudmusic.iot",
        "com.tencent.qqmusiccar",
        "com.tencent.qqmusic",
        "com.luna.music.car",
    )

    /** 允许接管的包名（只读视图）。 */
    fun eligiblePackages(): List<String> = ELIGIBLE_PACKAGES

    /** 这个包能不能注册给原车（白名单精确匹配，不用 `contains`：避免误伤别的应用）。 */
    fun isEligible(pkg: String?): Boolean {
        val p = pkg?.trim().orEmpty()
        return p.isNotEmpty() && ELIGIBLE_PACKAGES.contains(p)
    }

    /**
     * 要不要向原车「抢占播放权」（`requestPlay` + `updateCurrentSourceType`）。
     *
     * 口径与 UNI 一致：**只有真的开始播放、且是刚刚开始播 / 换了来源**时才抢。
     * 为什么要收紧：
     * - 每 200ms 一 tick 都抢一次会把原车的播放权来回甩；
     * - 暂停状态下抢播放权，原车可能据此把「当前音频源」切到我们身上，
     *   于是用户看到的仪表来源变成「在线音乐」，但他其实在听收音机。
     *
     * @param playing 下一刻采样到的播放态
     * @param stateChanged 播放态与上一次采样相比变了（`playing != lastPlaying`）
     * @param sourceChanged 来源包名与上一次采样相比变了
     */
    fun shouldAcquire(playing: Boolean, stateChanged: Boolean, sourceChanged: Boolean): Boolean =
        playing && (stateChanged || sourceChanged)

    /**
     * 原车 `IMusicPlaybackInfo.getPlaybackStatus` 的取值：**1 = 播放中，0 = 非播放**。
     *
     * 这是原车 SDK 的协议常量（不是布尔），`publish()` 里直接喂给 `updateMusicPlaybackState`。
     * 单独抽出来是为了在单测里把「暂停必须上报 0」这件事钉住 ——
     * 如果这里偷懒返回 -1 / 不更新，仪表上的播放图标就会一直亮着。
     */
    fun wirePlayback(playing: Boolean): Int = if (playing) 1 else 0

    /**
     * 原车媒体桥的**来源合并**结果（第126轮）。
     */
    enum class BridgeSource {
        /** 用会话来源。 */
        SESSION,

        /** 用广播来源（QQ 音乐那种没有可用会话的）。 */
        BROADCAST,

        /** 都没有 → 不给原车任何来源。 */
        NONE,
    }

    /**
     * 会话与广播两个来源里，谁喂给原车（纯逻辑，可单测）。
     *
     * ## 为什么第126轮起桥有了两个来源
     *
     * - **会话**：网易云 / QQ 音乐手机版这种正常发布会话的；
     * - **广播**：QQ 音乐车机版这种**不发布可用会话**的 ——
     *   第126轮在模拟器上装了真包，`dumpsys media_session` 实测
     *   `metadata: null` + `state=0` + 没有 `setCallback`，
     *   它只能靠自己的三方广播把曲目告诉我们（[QqBroadcast]）。
     *
     * ## 规则：**会话优先**
     *
     * - 真的有别的 App 接管了会话 → 广播那份**让位**
     *   （否则仪表上会一直挂着上一首 QQ 音乐的歌）；
     * - 没有会话 → 才用广播那份 ——
     *   不然桥拿不到样本 → 不注册到原车 → 原车不把**方向盘方控**交给我们
     *   （真车现象：方控按下去还是在控制爱趣听）。
     */
    fun pickSource(sessionAvailable: Boolean, broadcastAvailable: Boolean): BridgeSource = when {
        sessionAvailable -> BridgeSource.SESSION
        broadcastAvailable -> BridgeSource.BROADCAST
        else -> BridgeSource.NONE
    }

    // ---------------------------------------------------------------- 第128轮：解耦「方控」与「歌名」

    /**
     * 要不要现在就去原车媒体中心**注册**（第128轮，纯逻辑，可单测）。
     *
     * ## 第128轮修的真 bug
     *
     * 老实现里注册的前提是「**有样本**（拿到歌名了）」：
     *
     * ```kotlin
     * private fun register() {
     *     if (!running || !sample.available || registered || api == null) return
     * ```
     *
     * 真车诊断包（1.0.1130 / 4226）实测：
     *
     * ```
     * bridge.bound: true          <- 原车服务**连上了**
     * bridge.registered: false    <- 但注册**一次都没成功**
     * bridge.reason: 正在连接原车媒体中心
     * bridge.callbackCount: 0     <- 原车一次方控回调都没给过我们
     * ```
     *
     * `reason` 停在「正在连接原车媒体中心」说明：`registerMusic()` 这个
     * 原车 API **根本没被调用过**（调用过的话 reason 会变成
     * 「原车媒体注册未返回令牌」或某个异常名）。
     * 卡住它的就是 `!sample.available` —— 而 QQ 音乐车机版
     * **不发布会话**，歌名只能靠它的三方广播，拿不到就永远注册不上。
     *
     * ## 现在的规则
     *
     * **有没有歌名与能不能注册是两件事**：
     * - 注册 = 「我是一个在线音乐源」，原车因此把**方向盘方控**交给我们；
     * - 歌名 = 「我在放什么」，只影响仪表显示与桌面卡片。
     *
     * 把两者解耦之后，**即使还没拿到歌名也能先注册上**，
     * 方控回调就能进来，命令可以直接发给 QQ 音乐
     * （`qqmusiccar://?action=20&m0=N`），**根本不需要先知道歌名**。
     *
     * 抢播放权（`requestPlay` + `updateCurrentSourceType`）**仍然要求样本**，
     * 原车的收音机 / USB / 蓝牙一律不碰（见本文件顶部的红线）。
     */
    fun shouldRegister(running: Boolean, apiReady: Boolean, registered: Boolean): Boolean =
        running && apiReady && !registered

    /**
     * 方控按键要发给谁（第128轮，纯逻辑，可单测）。
     *
     * 优先级：
     * 1. **样本的来源包名**（有歌名时最准 —— 正在放的就是它）；
     * 2. **兜底目标**（用户选的媒体源对应的包名）——
     *    没有歌名时用它（例如 QQ 音乐车机版：只有方控目标，没有歌名）。
     *
     * @param sampleSource 样本来源包名（空 / null = 没有样本）
     * @param fallbackTarget 兜底目标包名（空 / null = 没有）
     */
    fun controlTargetFor(sampleSource: String?, fallbackTarget: String?): String {
        val s = sampleSource?.trim().orEmpty()
        if (s.isNotEmpty()) return s
        return fallbackTarget?.trim().orEmpty()
    }

    /**
     * 这个方控目标**能不能**接我们的命令（第128轮，纯逻辑，可单测）。
     *
     * 只认白名单里的三方在线播放器 —— 原车收音机 / USB / 蓝牙不碰
     * （本文件顶部的红线）。目标为空也不认（没有目标就发不出去，
     * 总比发给原车音源强）。
     */
    fun acceptsControl(target: String?): Boolean = isEligible(target)

    // ---------------------------------------------------------------- 第135轮：主动 claim

    /**
     * 要不要**主动 claim**（`requestPlay` + `updateCurrentSourceType`）——
     * 第135轮，纯逻辑，可单测。
     *
     * ## 为什么需要「主动 claim」
     *
     * 证据来自**参考 App（君君桌面）** —— 它是同款车机上跑通的另一个桌面，
     * 用的是同一套原车 MediaCenter SDK（`com.ecarx.sdk.mediacenter`）。
     *
     * 它的 `SessionBridge.setBridgeEnabled(true)` 做的正是：
     *
     * ```
     * registerMediaCenterSource(); start(); refresh(); pushToMediaCenter(true);
     * ```
     *
     * 而 `pushToMediaCenter(true)` 里的 `t.a()` 就是 claim
     * —— 也就是这条路上「**启用即 claim**」。
     *
     * 反过来更直白：`setBridgeEnabled(false)` 的日志原文是
     * 「bridge disabled → MediaCenter unregistered, **OEM source can take over**」
     * —— **不 claim，音源就归原车**。
     *
     * ## 出处要说准（别把它当成「官方口径」）
     *
     * 参考 App 的包名是 `com.ecarx.qq.tool`，`app_name` 是「君君桌面」——
     * **它是第三方**（平台签名预装），**不是车机官方桌面**。
     * 反编译产物里它的 Java 包前缀是 `com.claunchercard.manager.*`，
     * 那是**同一个 APK 内部**的包名，不是另一个 App
     * （`inner_dec` / `outer_dec` 两个产物的 manifest package 都是
     * `com.ecarx.qq.tool`）。
     *
     * 所以这里的证据性质是「**另一个第三方桌面在同款车机上就是这么干的**」，
     * 不是「厂商规定的口径」。我们跟它对齐是因为它跑通过；
     * **能不能成，最终看真车复验**。
     *
     * 我们以前只在「有样本**且正在播**」时才 claim（[shouldAcquire]），
     * 于是「**没打开播放器**」时整条链是断的：
     *
     * 1. 没有样本 → 不 claim → 原车认为当前音源还是爱趣听；
     * 2. 原车不把方向盘方控交给我们 → **我们连回调都收不到**
     *    （真车诊断包 4231：`bridge.callbackCount=0`、`bridge.playClaimed=false`）；
     * 3. 按键落进空里 → 原车只好自己把爱趣听拉起来。
     *
     * 这正是用户报的「没打开播放器时方控默认唤起的还是原车爱趣听」。
     *
     * ## 四条前置条件
     *
     * | 条件 | 为什么 |
     * | --- | --- |
     * | 已注册到原车 | 没注册就没有令牌，claim 无从谈起 |
     * | 目标在白名单（三方在线播放器） | **红线**：原车收音机 / USB / 蓝牙一律不碰 |
     * | 目标不是车机自带音源在放 | 参考 App 的 `SessionBridge.pushToMediaCenter` 遇到 `isNativeSystemMediaSource(pkg)` 直接 skip —— 它那么谨慎，我们照做，别去打断用户正在听的电台 |
     * | 还没 claim 过这个目标 | 只 claim 一次，不跟用户在车机里手动切源来回打架 |
     */
    fun shouldClaim(
        registered: Boolean,
        target: String?,
        claimedTarget: String?,
        nativeSourcePlaying: Boolean,
    ): Boolean = registered &&
        acceptsControl(target) &&
        target != claimedTarget &&
        !nativeSourcePlaying

    /**
     * 系统里有没有**车机自带音源**（爱趣听一类）正在放（第135轮，纯逻辑，可单测）。
     *
     * 判据：有一个会话处于播放中，而它的包名**不在**三方在线播放器白名单里。
     *
     * 依据：参考 App 的 `SessionBridge.pushToMediaCenter` 遇到
     * `isNativeSystemMediaSource(pkg)` 直接 skip —— **车机自带音源在放的时候不 claim**。
     * 它是另一个第三方桌面（君君桌面），我们照做，别去打断用户正在听的电台。
     *
     * @param playingPackages 处于播放中的会话包名（可以多几个）
     */
    fun hasNativeSourcePlaying(playingPackages: Collection<String>): Boolean =
        playingPackages.any { pkg -> pkg.isNotBlank() && !isEligible(pkg) }

    /**
     * 设置页诊断要展示的字段名（顺序即展示顺序）。
     *
     * Lead 会把这些塞进设置页说明与 emu-verify 报告；字段必须来自
     * [OfficialMediaBridge.snapshot]，是 `String -> 基础类型` 的扁平结构。
     */
    fun snapshotKeywords(): List<String> = listOf(
        "registered",
        "playbackAccepted",
        "playClaimed",
        "bound",
        "sourceType",
        "sourceKind",
        "source",
        "controlTarget",
        "title",
        // 第134轮：仪表有没有图 —— 真车排障第一眼就要看这两行。
        "artwork",
        "artworkSource",
        // 第135轮：我们主动接管了播放入口没有？接管的是谁？
        "claimTarget",
        "nativeSourcePlaying",
        "callbackCount",
        "lastCallback",
        "reason",
    )

    /** 诊断字段的中文标签（设置页直接显示，避免 UI 层再抄一遍映射）。 */
    fun labelFor(key: String): String = when (key) {
        "registered" -> "已注册到原车媒体中心"
        "playbackAccepted" -> "原车已接受播放信息"
        "playClaimed" -> "已接管播放入口"
        "bound" -> "服务已绑定"
        "sourceType" -> "在线音乐源类型"
        "sourceKind" -> "当前来源类型"
        "source" -> "当前来源包名"
        "controlTarget" -> "兜底方控目标"
        "title" -> "当前歌名"
        "artwork" -> "仪表封面 Uri"
        "artworkSource" -> "封面来源"
        "claimTarget" -> "已接管的播放入口"
        "nativeSourcePlaying" -> "车机自带音源正在放"
        "callbackCount" -> "方控/仪表回调次数"
        "lastCallback" -> "最近一次回调"
        "reason" -> "当前状态说明"
        else -> key
    }
}
