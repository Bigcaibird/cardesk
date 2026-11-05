package com.cardesk.launcher.core.media

/**
 * 汽水音乐车机版（`com.luna.music.car`）的**协议事实**（第127轮）。
 *
 * ## 事实来自哪里
 *
 * 第127轮在模拟器（Android 10 / arm64）上装了真的汽水音乐车机版
 * **1.1.27（versionCode 101027）**，`jadx` 反编译 `com/luna/` + `aapt2` 导清单得到。
 * （模拟器上它自己的接口 `/service/settings/v3/` 被服务端拒了，界面停在「加载异常」，
 * 拿不到真实播放；但**清单与源码是权威依据**，下面每条都能对上反编译的具体位置。）
 *
 * ## ① 它是一个**规矩**的 MediaSession App
 *
 * `com/luna/player/impl/remotecontrol/RemoteControlSession.java`：
 *
 * ```java
 * getMMediaSessionCompat().setFlags(3);   // HANDLES_MEDIA_BUTTONS | HANDLES_TRANSPORT_CONTROLS
 * ```
 *
 * `com/luna/player/impl/remotecontrol/CoreRemoteControl.java`：
 *
 * ```java
 * playbackStateBuilder.setActions(m.d(canSeek(), TRUE) ? 950L : 694L);
 * ```
 *
 * → **它声明了 action 位**（950 = 0x3B6 / 694 = 0x2B6，含 PLAY / PAUSE / SKIP_TO_* / SEEK_TO），
 * 而且会话回调**实现了** `onPlay` / `onPause` / `onSkipToNext` / `onSkipToPrevious` / `onSeekTo`
 * （`com/luna/music/car/adaption/platform/DefaultMediaSessionCallback.java`）。
 *
 * → 所以标准 `transportControls` 通道**直接可用**，
 * **不需要**网易云那种「定向按键（DOWN+UP）」特例。
 *
 * ## ② metadata 是标准布局
 *
 * `CoreRemoteControl.java`：
 *
 * ```java
 * putString(METADATA_KEY_TITLE,   blueToothLyricContent)  // 默认 = 歌名
 * putString(METADATA_KEY_ALBUM,   mediaSessionSubTitle)   // = 专辑名
 * putString(METADATA_KEY_ARTIST,  notificationContent)    // = 歌手名
 * putString(METADATA_KEY_MEDIA_ID, queueItemId)
 * putLong (METADATA_KEY_DURATION, duration)
 * putBitmap(METADATA_KEY_ART,     coverBitmap)
 * ```
 *
 * `blueToothLyricContent` 来自 `RemoteControlContext.getBlueToothLyricOpen()`，
 * 而 `CompositeRemoteControl.buildRemoteControlContext()` 里**硬编码 `false`**
 * → 车机版**永远**把**歌名**放在 TITLE，不会变成「当前歌词行」。
 * （这一点与微信车机 / 爱趣听那种「歌词型来源」正好相反，
 * 所以**不能**把汽水加进 [LyricMatch.isLyricTitleSource]。）
 *
 * ## ③ 它**不向第三方暴露歌词**（这一条决定了歌词只能联网查）
 *
 * 三条可能的三方歌词通道，逐条排除：
 *
 * | 通道 | 汽水的情况 | 证据 |
 * |---|---|---|
 * | metadata 自定义 key | **没有** | 全包 grep 不到 `android.media.metadata.LYRIC` 之类的 key；`CoreRemoteControl` 只写上面 6 个标准字段 |
 * | `MediaController.Callback.onSessionEvent` | **没有** | 全包 grep 不到 `sendSessionEvent` |
 * | `MediaBrowserService` 的 browse 树 | **空的** | `MediaBrowserPlayerService.onLoadChildren()` → `sendResultSafe(result, null)`；`onGetRoot()` → `null`（连外部客户端都不接） |
 *
 * → 汽水的歌词**只在自己界面里**。我们只能**联网查**
 * （[OnlineLyricCatalog]），渠道顺序：网易 → QQ → 酷狗。
 *
 * ## ④ 所以「适配汽水」真正要做的一件事
 *
 * **把它加进 OEM 媒体桥的白名单**（[MediaBridgePolicy.ELIGIBLE_PACKAGES]）。
 *
 * 原车把方向盘方控交给我们的前提是「我们接管了当前在线音乐源」；
 * 汽水不在白名单 → 桥根本不注册它 → **方向盘按下去控不到汽水**
 * （与第126轮 QQ 音乐同一个根因）。
 */
object SodaMediaSource {

    /** 汽水音乐车机版的包名（与 [MediaSources] 的 `soda` 来源一致）。 */
    const val PACKAGE = "com.luna.music.car"

    /**
     * 汽水是不是「规矩的」会话来源（标准 `transportControls` 就能控）。
     *
     * 依据：会话 `setFlags(3)` + `setActions(950/694)` + 回调实现了
     * `onPlay` / `onPause` / `onSkipToNext` / `onSkipToPrevious` / `onSeekTo`。
     *
     * → **不需要**网易云那种定向按键特例。
     */
    fun usesStandardTransport(): Boolean = true

    /**
     * 汽水会不会把歌词**主动推给第三方**。
     *
     * **不会**（metadata 无歌词 key、无会话事件、browse 树为空）。
     * → 歌词只能走联网查。
     */
    fun exposesLyricToThirdParty(): Boolean = false

    /**
     * 汽水会不会把 `METADATA_KEY_TITLE` 变成「当前歌词行」。
     *
     * **不会**（车机版把 `blueToothLyricOpen` 硬编码成 `false`）。
     * → 所以汽水**不是**「歌词型来源」，歌名直接读 TITLE。
     */
    fun titleHoldsLyricLine(): Boolean = false
}
