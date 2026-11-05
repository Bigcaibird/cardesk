package com.cardesk.launcher.core.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cardesk.launcher.core.JsonLite
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * QQ 音乐车机版的**广播协议**（第126轮）。
 *
 * ## 背景：为什么不能用标准 MediaSession
 *
 * 真车（领克 dcy11 / Android 9）诊断包实测：QQ 音乐车机版在会话枚举里
 * **一次都没出现过**。第126轮在模拟器上装了真的 `com.tencent.qqmusiccar`
 * 1.9.8.22 反编译 + 实测，才看清它到底做了什么：
 *
 * ```
 * mbr com.tencent.qqmusiccar/mbr
 *   state=PlaybackState {state=0, position=0, ..., actions=631}
 *   metadata: null
 * MediaSessionHelper-com.tencent.qqmusiccar com.tencent.qqmusiccar/MediaSessionHelper-...
 *   state=PlaybackState {state=0, position=0, ..., actions=1022}
 *   metadata: null
 * ```
 *
 * 三个致命点：
 *
 * 1. **`metadata: null`** —— 它只把歌名塞进老式的 `RemoteControlClient`，
 *    **MediaSession 一个字节的元数据都没有** → 桌面拿不到歌名 / 歌手；
 * 2. **`state=0`（STATE_NONE）** —— 它从不更新播放态 → 我们的会话选择
 *    判它「没元数据、没在放」→ 直接把界面清空；
 * 3. **没有 `setCallback`** —— 会话是个「空壳」，`transportControls`
 *    发过去也**没人接**。
 *
 * 所以标准 MediaSession 这条路对 QQ 音乐车机版是**死的**。
 *
 * ## 但它有一整套**专门给第三方用的广播协议**
 *
 * 反编译 `com.tencent.qqmusiccar` 1.9.8.22 得到下面每一条，
 * 并且**在模拟器上逐条实测过**（2026-11-05，见本文件的「实测记录」）。
 *
 * ### ① QQ 音乐 → 第三方（我们要收的：歌名 / 歌手 / 歌词 / 播放态）
 *
 * action：`com.tencent.qqmusiccar.action.PLAY_COMMAND_SEND_FOR_THIRD`
 * extra：`com.tencent.qqmusiccar.EXTRA_COMMAND_DATA`（一段 JSON）
 *
 * ```json
 * {
 *   "module": "play",
 *   "command": {
 *     "method": "update_song" | "update_state" | "update_lyric" | ...,
 *     "data": {
 *       "code": 0,
 *       "state": 2,
 *       "curr_time": 115208,
 *       "total_time": 200666,
 *       "lyric": "[ti:春风吹]...",
 *       "key_title": "春风吹",
 *       "key_artist": [ { "singer": "方大同" } ],
 *       "key_album": "十五",
 *       "key_is_fav": 0
 *     }
 *   }
 * }
 * ```
 *
 * 出处：`BroadcastSenderCenterForThird.putSongInfo()`（`key_*` 字段）
 * 与 `updateLyric()` / `updatePlayState()`（`state` / `curr_time` / `total_time` / `lyric`）。
 *
 * > **歌手是数组不是字符串** —— `key_artist` 是 `[{"singer":"方大同"}]`。
 * > 以前按字符串解析，**永远取不到歌手**。
 *
 * ### ② 第三方 → QQ 音乐（**开门**：`action = 100`）
 *
 * action：`com.tencent.qqmusiccar.action`
 * extra：`action`（int）+ `pull_from`（long）
 *
 * | action | 含义 | 出处 |
 * |---|---|---|
 * | 0 / 1 / 2 / 3 / 9 | 播放 / 暂停 / 上一首 / 下一首 / 停止 | `BroadcastReceiverCenterForThird` 常量 |
 * | **100** | **打开「允许对外发送」开关** | `dispacherAction()` 里的 `i == 100` |
 * | 2017 | 打开「允许对外发送给车机」开关 | `i == 2017` |
 *
 * ### ③ 第三方 → QQ 音乐（**真正的传输控制**：URI scheme）
 *
 * `qqmusiccar://?action=20&m0=<命令码>`
 *
 * | m0 | 含义 | 出处 |
 * |---|---|---|
 * | 0 | 播放 | `controlPlay()` → `setPlay(true)` |
 * | 1 | 暂停 | `controlPlay()` → `setPlay(false)` |
 * | 2 | 上一首 | `controlPlay()` → `prevPlay()` → `MusicPlayer.playPrev` |
 * | 3 | 下一首 | `controlPlay()` → `backPlay()` → `MusicPlayer.playNext` |
 * | 4 / 5 / 6 | MV / 收藏 / 取消收藏 | `controlPlay()` |
 * | 7 / 8 | 快进 / 快退（m1 = 秒） | `controlPlay()` |
 *
 * `action=20` 才是真正下发给播放器的（`parseUrlObject()` → `controlPlay(m0)`）；
 * 走 ② 的 `action=0/2/3` 只会**把 QQ 音乐的界面打开**（`openQQMusic()`），
 * **不会**真的切歌 —— 这是两个通道最容易踩的坑。
 *
 * ## 「允许对外发送」开关（**最关键的一条**）
 *
 * `BroadcastSenderCenterForThird` 里的每个 `updateXxx()` 第一句都是
 * `if (this.allowSendOutside)` —— **QQ 音乐默认不往外挂广播**。
 *
 * 而 `allowSendOutside` 只有一条路能打开：第三方发 `action = 100` 给它。
 *
 * **我们以前从来没发过这条** → QQ 音乐一个字都不往外广播 →
 * 「无歌词」就是这么来的（不是歌词格式认错了，是**根本没收到**）。
 *
 * ## 实测记录（模拟器 emulator-5556，Android 10，真包 1.9.8.22）
 *
 * ```
 * # 发 action=100（模拟器上用 am broadcast 定向发）
 * $ adb shell am broadcast -a com.tencent.qqmusiccar.action --ei action 100 \
 *       --el pull_from 45124 -p com.tencent.qqmusiccar
 * I BroadcastReceiverCenterForThird: onReceive： uri:null
 * D BroadcastReceiverCenterForThird: dispacherAction and action is:100
 * I BroadcastSenderCenterForThird: setAllowSendOutside, allow=true
 * D BroadcastSenderCenterForThird: updatePlayState commandString :{"module":"play","command":
 *   {"method":"update_state","data":{"state":0,"curr_time":0,"total_time":0,
 *   "key_title":"","key_artist":[{"singer":""}],"key_album":"","key_is_fav":0}}}
 * ```
 *
 * → 开门后 QQ 音乐**立刻**回一条 `update_state`，JSON 结构与上面完全一致。
 *
 * ```
 * # 传输控制（m0=3 = 下一首）
 * $ adb shell "am broadcast -a android.intent.action.VIEW \
 *       -d 'qqmusiccar://?action=20&m0=3' -p com.tencent.qqmusiccar"
 * I BroadcastReceiverCenterForThird: onReceive： uri:qqmusiccar://?action=20&m0=3
 * D BroadcastReceiverCenterForThird: ACTION : 20
 * E MusicPlayerHelper: eventType = 2what = 10, subwhat = 0, ex = gotoNextSong
 * ```
 *
 * → `gotoNextSong` 真的下发到播放器了（模拟器上没登录，
 * 所以后面报 `PLAY_ERR_CANNT_PLAY_OTHER` —— 那是没有播放列表，不是命令没到）。
 */
object QqBroadcast {

    // ---------------------------------------------------------------- ① QQ → 第三方

    /** QQ 音乐推给第三方的 action。 */
    const val ACTION_FROM_QQ = "com.tencent.qqmusiccar.action.PLAY_COMMAND_SEND_FOR_THIRD"

    /** 广播里 JSON 所在的 extra key。 */
    const val EXTRA = "com.tencent.qqmusiccar.EXTRA_COMMAND_DATA"

    /** QQ 音乐车机版的包名。 */
    const val PACKAGE = "com.tencent.qqmusiccar"

    // ---------------------------------------------------------------- ② 第三方 → QQ

    /** 第三方给 QQ 音乐下命令用的 action（接收器 `BroadcastReceiverCenterForThird`）。 */
    const val ACTION_TO_QQ = "com.tencent.qqmusiccar.action"

    /** 命令码 extra 的 key。 */
    const val EXTRA_ACTION = "action"

    /** 「来源」extra 的 key（第三方标识，默认 45124）。 */
    const val EXTRA_PULL_FROM = "pull_from"

    /** 默认的第三方标识（QQ 音乐自己用的默认值）。 */
    const val DEFAULT_PULL_FROM = 45124L

    /** 播放。 */
    const val CMD_PLAY = 0

    /** 暂停。 */
    const val CMD_PAUSE = 1

    /** 上一首。 */
    const val CMD_PREVIOUS = 2

    /** 下一首。 */
    const val CMD_NEXT = 3

    /** 停止。 */
    const val CMD_STOP = 9

    /**
     * 「允许对外发送」开关。
     *
     * 第三方发这个命令，QQ 音乐才会开始往外挂 `update_song` / `update_state` /
     * `update_lyric`。**不发这一条，QQ 音乐一个字节都不会往外广播。**
     */
    const val CMD_ALLOW_SEND_OUTSIDE = 100

    // ---------------------------------------------------------------- ③ 第三方 → QQ（URI）

    /** QQ 音乐车机版的三方控制 scheme。 */
    const val SCHEME = "qqmusiccar"

    /** URI 里的 `action=20` → 走 `controlPlay(m0)`（真正的传输控制）。 */
    const val URI_ACTION_CONTROL = 20

    // ---------------------------------------------------------------- 播放态（QQ 侧）

    /** 无播放。 */
    const val STATE_NONE = 0

    /** 缓冲中。 */
    const val STATE_BUFFERING = 1

    /** 播放中。 */
    const val STATE_PLAYING = 2

    /** 暂停。 */
    const val STATE_PAUSED = 3

    /** 已停止。 */
    const val STATE_STOPPED = 4

    // ---------------------------------------------------------------- 载荷

    /** 一条广播里能读出来的东西。 */
    data class Payload(
        /** `module`（`play` / `app` / `login` …）。 */
        val module: String,
        /** `command.method`（`update_song` / `update_state` / `update_lyric` …）。 */
        val method: String,
        /** 歌名（`key_title`）。 */
        val title: String,
        /** 歌手（`key_artist` 数组里第一个 `singer`）。 */
        val artist: String,
        /** 专辑（`key_album`）。 */
        val album: String,
        /** 总时长（ms，`total_time`）。 */
        val durationMs: Long,
        /** 播放位置（ms，`curr_time`）。 */
        val positionMs: Long,
        /** 播放态（`state`；-1 = 这条广播里没有）。 */
        val state: Int,
        /** LRC 歌词原文（`lyric`）。 */
        val lyric: String,
    ) {
        /** 有没有歌名或歌手（够合成一张卡片了）。 */
        val hasSong: Boolean get() = title.isNotEmpty() || artist.isNotEmpty()

        /** 有没有歌词。 */
        val hasLyric: Boolean get() = lyric.isNotEmpty()

        /** 播放态是不是「在放」（缓冲中也算 —— 用户按了暂停就是想停）。 */
        val playing: Boolean get() = state == STATE_PLAYING || state == STATE_BUFFERING
    }

    /**
     * 解析广播里的 JSON。
     *
     * 结构不认识（不是 `command` 对象 / 不是 JSON）时返回 null ——
     * 调用方据此「这条广播与我们无关」。
     */
    fun parse(raw: String?): Payload? {
        if (raw.isNullOrBlank()) return null
        val root = JsonLite.parseObject(raw) ?: return null
        val command = root["command"] as? Map<String, Any?> ?: return null
        val data = command["data"] as? Map<String, Any?> ?: emptyMap()
        return Payload(
            module = JsonLite.str(root, "module"),
            method = JsonLite.str(command, "method"),
            title = JsonLite.str(data, "key_title"),
            artist = artistOf(data),
            album = JsonLite.str(data, "key_album"),
            durationMs = JsonLite.long(data, "total_time", 0L),
            positionMs = JsonLite.long(data, "curr_time", 0L),
            state = if (data.containsKey("state")) JsonLite.long(data, "state", -1L).toInt() else -1,
            lyric = JsonLite.str(data, "lyric"),
        )
    }

    /**
     * 取歌手。
     *
     * `key_artist` 是**数组** `[{"singer":"方大同"}]`（QQ 音乐自己的写法），
     * 但也兼容它是纯字符串的版本 —— 两个都试。
     */
    private fun artistOf(data: Map<String, Any?>): String {
        when (val v = data["key_artist"]) {
            is String -> return v.trim()
            is List<*> -> {
                for (item in v) {
                    when (item) {
                        is Map<*, *> -> {
                            val s = (item["singer"] as? String)?.trim()
                            if (!s.isNullOrEmpty()) return s
                        }
                        is String -> if (item.isNotBlank()) return item.trim()
                    }
                }
            }
            is Map<*, *> -> {
                val s = (v["singer"] as? String)?.trim()
                if (!s.isNullOrEmpty()) return s
            }
        }
        // 兜底：有些版本把歌手直接放在 `singer`。
        return JsonLite.str(data, "singer").trim()
    }

    // ---------------------------------------------------------------- 发送：广播通道

    /**
     * 给 QQ 音乐发一条**广播**命令（走 ② 号通道）。
     *
     * 用它发 [CMD_ALLOW_SEND_OUTSIDE] 开门；**不要**用它发播放/切歌 ——
     * 那几个命令码只会把 QQ 音乐的界面打开（见 [sendControl]）。
     *
     * @param action 命令码（[CMD_ALLOW_SEND_OUTSIDE] 等）
     * @param pullFrom 第三方标识（QQ 音乐用它统计来源，默认用它自己的默认值）
     * @return 是否真的发出去了
     */
    fun sendCommand(
        context: Context,
        action: Int,
        pullFrom: Long = DEFAULT_PULL_FROM,
    ): Boolean = runCatching {
        val intent = Intent(ACTION_TO_QQ)
            .putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_PULL_FROM, pullFrom)
            // 定向到 QQ 音乐 —— Android 8 起，隐式广播**不再**投递给
            // 清单里声明的接收器；带上包名才是「定向广播」，才会送到。
            .setPackage(PACKAGE)
        context.applicationContext.sendBroadcast(intent)
        true
    }.getOrElse {
        Logx.w(LogTags.MEDIA, "给 QQ 音乐发命令失败", it, "action" to action)
        false
    }

    /**
     * 打开 QQ 音乐的「允许对外发送」开关。
     *
     * **不发这一条，QQ 音乐不会往外挂任何广播**（歌名、歌词都没有）。
     * 幂等：QQ 音乐那边只是把开关置 true，重复发没有副作用 ——
     * 而且每次都会**立刻**回一条 `update_state`，正好当我们的「已开门」凭据。
     */
    fun openSendDoor(context: Context): Boolean =
        sendCommand(context, CMD_ALLOW_SEND_OUTSIDE)

    // ---------------------------------------------------------------- 发送：URI 通道（真控制）

    /**
     * 把播放/暂停/切歌的动作翻译成 QQ 音乐的命令码。
     *
     * **这套编号在两个通道里是同一套**（实测 + 反编译都对上了）：
     * `0=PLAY 1=PAUSE 2=PREVIOUS 3=NEXT`，广播通道的 action 码与
     * URI 通道的 `m0` 完全一致。
     *
     * @param action `play` / `pause` / `next` / `previous` / `stop`
     * @return 对应的命令码；不认识返回 null。
     */
    fun commandFor(action: String): Int? = when (action) {
        "play" -> CMD_PLAY
        "pause" -> CMD_PAUSE
        "next" -> CMD_NEXT
        "previous" -> CMD_PREVIOUS
        "stop" -> CMD_STOP
        else -> null
    }

    /**
     * 生成传输控制用的 URI（纯逻辑，可单测）。
     *
     * `qqmusiccar://?action=20&m0=<命令码>`
     *
     * 只有 `action=20` 才会真正下发到播放器（`controlPlay(m0)`）；
     * 别的 action 在这个 URI 里只会「打开 QQ 音乐的某个页面」。
     */
    fun controlUri(action: String): String? {
        val code = commandFor(action) ?: return null
        return "$SCHEME://?action=$URI_ACTION_CONTROL&m0=$code"
    }

    /**
     * 给 QQ 音乐发**真正的传输控制**（走 ③ 号 URI 通道）。
     *
     * ## 为什么必须用 URI 而不是广播
     *
     * 广播通道的 `action=0/2/3` 在 QQ 音乐那边是 `openQQMusic()` ——
     * **只会把界面打开，不会切歌**（`dispacherAction()` 里没有这几个 case）。
     * 真正下发给播放器的只有 `controlPlay(m0)`，而它只从
     * URI 的 `action=20` 进来。
     *
     * ## 为什么用 `startActivity` 而不是 `sendBroadcast`
     *
     * URI scheme 对应的是 QQ 音乐清单里的 **`DispacherActivityForThird`
     * 这个 Activity**（exported），它内部再转成广播给接收器。
     * 用 `startActivity` 走 Activity 这条路，**不受 Android 8 起
     * 「隐式广播不再投递给清单接收器」的限制**，一定能送到。
     *
     * QQ 音乐没在运行时，`DispacherActivityForThird` 会把 QQ 音乐拉起来
     * —— 对「按了方控播放」这个场景来说，这正是用户想要的结果。
     *
     * @param action `play` / `pause` / `next` / `previous`
     * @return 是否真的发出去了
     */
    fun sendControl(context: Context, action: String): Boolean {
        val uri = controlUri(action) ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .setPackage(PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.applicationContext.startActivity(intent)
            true
        }.getOrElse {
            Logx.w(LogTags.MEDIA, "给 QQ 音乐发控制命令失败", it, "action" to action, "uri" to uri)
            false
        }
    }

    // ---------------------------------------------------------------- 采纳门

    /**
     * 这条 QQ 广播**要不要采纳**（第126轮，纯逻辑，可单测）。
     *
     * ## 为什么不能只看「当前绑定的会话是不是 QQ 音乐」
     *
     * 老实现的门是「绑定包必须是 QQ 音乐」。但 QQ 音乐车机版**不发布会话**
     * （它那个会话 `metadata: null` + `state=0`，等于没有），
     * 桌面**永远绑不到它** → 这道门**永远关着** → 歌词和歌名永远不来
     * （循环死锁）。
     *
     * 模拟器实测（装了真的 QQ 音乐车机版）：
     * `CarDesk/Media: 忽略 QQ 歌词广播（当前会话不是 QQ 音乐） boundPkg=`
     * —— 广播明明到了，就是被这道门挡掉了。
     *
     * ## 现在的两条通路
     *
     * 1. **当前绑定的会话就是 QQ 音乐** —— 原来就有，保持；
     * 2. **当前没有绑定任何会话**，而且**用户把默认媒体源选成了 QQ 音乐**
     *    —— 真车上的实际情况（`default_music_source_v1=qq`）。
     *
     * 条件 2 不会误伤别的 App：一旦有别的播放器在播（比如爱趣听），
     * 绑定包就不是空了 → 条件 2 不成立 → QQ 的广播**不会盖掉正在播的歌**。
     *
     * @param boundPkg 当前绑定的会话包名（空串 / null = 没有绑定任何会话）
     * @param defaultSourceId 设置里的「默认媒体源」id（空串 / null = 没设）
     */
    fun shouldAccept(boundPkg: String?, defaultSourceId: String?): Boolean {
        if (QqLyric.isQqMusicPackage(boundPkg)) return true
        if (!boundPkg.isNullOrEmpty()) return false
        return defaultSourceId == MediaSources.QQ_ID
    }
}
