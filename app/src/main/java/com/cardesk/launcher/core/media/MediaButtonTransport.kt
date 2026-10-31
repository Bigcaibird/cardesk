package com.cardesk.launcher.core.media

/**
 * 网易云音乐车机版（`com.netease.cloudmusic.iot`）的**定向按键控制**规则（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么不能用常规 `transportControls`（第八十七轮）
 *
 * 参考 UNI `NeteaseMediaAdapter.command` 的实测结论（针对网易云 6.2.81 / 6002081）：
 *
 * - 网易云**实现了** transport 回调（`onPlay`/`onPause`/`onSkipToNext`…），
 * - 但它**没有**给 `PlaybackState` 调 `setActions()` → `getActions()` 返回 0；
 * - 我们以前那套「先看 action 位，位不够就不发」的写法，对网易云**永远不发**；
 *   而全局 `MEDIA_BUTTON` 广播又会被别的播放器（或系统）接走，变成「按了没反应 / 按了乱跳」。
 *
 * 所以网易云走单独一条路：`controller.dispatchMediaButtonEvent(DOWN)` + `(UP)`。
 * UNI 注释里的关键一条：**网易云的接收方只消费 `ACTION_UP`**，只发 DOWN 不生效；
 * 也不要因为 `dispatch` 返回 false 就去补发全局广播 —— 返回值是「有没有派发成功」，
 * 不是「播放态有没有变」，补发全局广播会打到别人身上。
 *
 * 本文件只做「判定 + 按键映射」这类纯逻辑，真正下发在 [MusicSessionTracker] 里
 * （需要 `MediaController` / `KeyEvent`，属于 Android 依赖）。
 *
 * 为什么按键码也自己声明：这个文件要能在 JVM 单测里跑，而 `android.view.KeyEvent`
 * 在单测里是空壳。数值与 AOSP 一致，注释标明出处。
 */
object MediaButtonTransport {

    /** 网易云音乐车机版（IoT 版）包名；与 [MediaSources] 的 `netease` 来源一致。 */
    const val NETEASE_PACKAGE = "com.netease.cloudmusic.iot"

    /** `KeyEvent.KEYCODE_UNKNOWN`：没有对应按键（例如 seek 不走按键）。 */
    const val KEYCODE_UNKNOWN = 0

    /** `KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE`。 */
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85

    /** `KeyEvent.KEYCODE_MEDIA_STOP`。 */
    const val KEYCODE_MEDIA_STOP = 86

    /** `KeyEvent.KEYCODE_MEDIA_PLAY`。 */
    const val KEYCODE_MEDIA_PLAY = 126

    /** `KeyEvent.KEYCODE_MEDIA_PAUSE`。 */
    const val KEYCODE_MEDIA_PAUSE = 127

    /** `KeyEvent.KEYCODE_MEDIA_NEXT`。 */
    const val KEYCODE_MEDIA_NEXT = 87

    /** `KeyEvent.KEYCODE_MEDIA_PREVIOUS`。 */
    const val KEYCODE_MEDIA_PREVIOUS = 88

    /** 动作名：切换播放 / 暂停（UNI 的 `toggle-play`）。 */
    const val ACTION_TOGGLE_PLAY = "toggle-play"

    /** 动作名：播放。 */
    const val ACTION_PLAY = "play"

    /** 动作名：暂停。 */
    const val ACTION_PAUSE = "pause"

    /** 动作名：下一首。 */
    const val ACTION_NEXT = "next"

    /** 动作名：上一首。 */
    const val ACTION_PREVIOUS = "previous"

    /** 动作名：跳转进度（网易云**不走按键**，走 `transportControls.seekTo`）。 */
    const val ACTION_SEEK = "seek"

    /**
     * 这个包是不是网易云车机版。
     *
     * 包名比较用 `equals`（不是 `contains`）：网易云手机版也是 `com.netease.cloudmusic`，
     * 只有 IoT/车机版才有那套定向按键行为，手机版混进来会连累别的路径。
     */
    fun isNetease(pkg: String?): Boolean = pkg?.trim() == NETEASE_PACKAGE

    /**
     * 动作 → `KeyEvent` 按键码。
     *
     * `toggle-play` 按当前播放态选键：在放 → `MEDIA_PAUSE`，不在放 → `MEDIA_PLAY`。
     * 这是 UNI 的做法 —— **不要**用 `MEDIA_PLAY_PAUSE`（它的行为取决于 App 自己的切换逻辑，
     * 在状态不同步时会把暂停又切成播放）。
     *
     * seek 不走按键（网易云的 `onSeekTo` 直接接收毫秒），返回 [KEYCODE_UNKNOWN]，
     * 调用方据此改走 `transportControls.seekTo`。
     *
     * 不认识的动作返回 [KEYCODE_UNKNOWN]。
     */
    fun keyFor(action: String, playing: Boolean): Int = when (action) {
        ACTION_PLAY -> KEYCODE_MEDIA_PLAY
        ACTION_PAUSE -> KEYCODE_MEDIA_PAUSE
        ACTION_TOGGLE_PLAY -> if (playing) KEYCODE_MEDIA_PAUSE else KEYCODE_MEDIA_PLAY
        ACTION_NEXT -> KEYCODE_MEDIA_NEXT
        ACTION_PREVIOUS -> KEYCODE_MEDIA_PREVIOUS
        else -> KEYCODE_UNKNOWN
    }

    /**
     * 一个会话实际可用的控制能力。
     *
     * @param canToggle 能不能播放/暂停
     * @param canNext 能不能下一首
     * @param canPrevious 能不能上一首
     * @param canSeek 能不能拖进度
     */
    data class Caps(
        val canToggle: Boolean,
        val canNext: Boolean,
        val canPrevious: Boolean,
        val canSeek: Boolean,
    )

    /**
     * 计算会话的控制能力。
     *
     * - **网易云**：不看 action 位（它不 `setActions()`，位恒为 0），
     *   只要它带 `FLAG_HANDLES_MEDIA_BUTTONS`，播放/暂停/切歌就都能用
     *   （transport 回调实现了，按键能派发进去）；进度条直接 `seekTo`，恒可用。
     * - **其它播放器**：老老实实看 action 位（`PLAY`/`PAUSE`/`PLAY_PAUSE`、`SKIP_TO_*`、`SEEK_TO`）。
     *
     * @param pkg 会话包名
     * @param actions `PlaybackState.getActions()`
     * @param hasMediaButtonsFlag `MediaSession.getFlags() and FLAG_HANDLES_MEDIA_BUTTONS != 0`
     */
    fun capability(pkg: String?, actions: Long, hasMediaButtonsFlag: Boolean): Caps {
        if (isNetease(pkg)) {
            return Caps(
                canToggle = hasMediaButtonsFlag,
                canNext = hasMediaButtonsFlag,
                canPrevious = hasMediaButtonsFlag,
                canSeek = true,
            )
        }
        val toggleBits = TransportIntent.ACTION_PLAY or TransportIntent.ACTION_PAUSE or TransportIntent.ACTION_PLAY_PAUSE
        return Caps(
            canToggle = (actions and toggleBits) != 0L,
            canNext = (actions and TransportIntent.ACTION_SKIP_TO_NEXT) != 0L,
            canPrevious = (actions and TransportIntent.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canSeek = (actions and TransportIntent.ACTION_SEEK_TO) != 0L,
        )
    }

    /** `MediaSession.FLAG_HANDLES_MEDIA_BUTTONS`（AOSP 数值 1）。 */
    const val FLAG_HANDLES_MEDIA_BUTTONS = 1L

    /** `MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS`（AOSP 数值 2）。 */
    const val FLAG_HANDLES_TRANSPORT_CONTROLS = 2L

    /** 会话能不能接「媒体按键」（`FLAG_HANDLES_MEDIA_BUTTONS`）。 */
    fun canMediaButtons(flags: Long): Boolean = (flags and FLAG_HANDLES_MEDIA_BUTTONS) != 0L

    /** 会话能不能接 `transportControls` 调用（`FLAG_HANDLES_TRANSPORT_CONTROLS`）。 */
    fun canTransport(flags: Long): Boolean = (flags and FLAG_HANDLES_TRANSPORT_CONTROLS) != 0L

    /**
     * 给 [TransportIntent.decide] 用的「有效 action 位」（第八十七轮·通用补位）。
     *
     * ## 为什么要补位
     *
     * 很多播放器**实现了 transport 回调、却没给 `PlaybackState` 调 `setActions()`**
     * （网易云是典型，原厂多媒体 / DMT / 爱趣听也常见）。这类会话 `actions == 0`，
     * 直接喂给 [TransportIntent.decide] 会得到 `no-action` —— 于是**按下没反应**，
     * 比老实现「无条件 pause()」还糟（那是「按暂停没反应」的回归）。
     *
     * 所以（口径与 UNI 对网易云的补位一致，这里推广成通用规则）：
     * 1. **网易云**：只要带 media-button 或 transport 标志就补 `PLAY|PAUSE`
     *    （它恒不 `setActions()`，而按键通道能派发进去）；
     * 2. **其它播放器**：`actions == 0` 且带 `FLAG_HANDLES_TRANSPORT_CONTROLS`
     *    或 `FLAG_HANDLES_MEDIA_BUTTONS` → 补 `PLAY|PAUSE`。
     *    有 action 位的播放器**原样返回**（它自己声明了能力，我们不要替它猜）。
     *
     * 只补 `PLAY|PAUSE`，**绝不补** `SKIP_TO_NEXT/PREVIOUS/SEEK_TO`：
     * 切歌位乱补会让「下一首」从可用变不可用，甚至把不该发的切歌指令发出去。
     *
     * @param pkg 会话包名
     * @param actions `PlaybackState.getActions()`
     * @param flags `MediaController.getFlags()`（`MediaSession.FLAG_*` 位）
     */
    fun effectiveActions(pkg: String?, actions: Long, flags: Long): Long {
        val toggle = TransportIntent.ACTION_PLAY or TransportIntent.ACTION_PAUSE
        if (isNetease(pkg)) {
            return if (canMediaButtons(flags) || canTransport(flags)) actions or toggle else actions
        }
        if (actions != 0L) return actions
        return if (canTransport(flags) || canMediaButtons(flags)) actions or toggle else actions
    }

    /**
     * 该不该给这个会话发「定向」`dispatchMediaButtonEvent`。
     *
     * 只有网易云走这个通道；其它播放器保持 `transportControls` 优先、
     * 失败再回退全局按键的既有路径（第六十七 / 六十八轮的真机教训：
     * 给别的 App 多发一路广播，出现过「暂停把车机音乐会话搞没了」的事故）。
     */
    fun shouldDispatchMediaButton(pkg: String?, hasMediaButtonsFlag: Boolean): Boolean =
        isNetease(pkg) && hasMediaButtonsFlag
}
