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
     * 与 [MediaSources] 的 `netease` / `qq` 来源一致；`com.tencent.qqmusic` 是手机版，
     * 留在列表里是因为部分车机装的是手机版（UNI 也一并接受）。
     * 顺序固定，便于日志与单测断言。
     */
    private val ELIGIBLE_PACKAGES = listOf(
        "com.netease.cloudmusic.iot",
        "com.tencent.qqmusiccar",
        "com.tencent.qqmusic",
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
        "callbackCount" -> "方控/仪表回调次数"
        "lastCallback" -> "最近一次回调"
        "reason" -> "当前状态说明"
        else -> key
    }
}
