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

    // ---------------------------------------------------------------- ① 收藏（第一九五轮）

    /**
     * 收藏当前歌曲。
     *
     * `BroadcastReceiverCenterForThird.controlPlay()` 的 `case 5`：
     *
     * ```java
     * case 5:
     * case 6:
     *     songInfo = MusicPlayerHelper.j0().y0();   // 当前歌曲
     *     if (songInfo != null) SongInfoUtilsForShow.s(context, songInfo, 5, "");
     * ```
     *
     * → `m0=5` 是「**收藏**」，`m0=6` 是「**取消收藏**」
     * （`SongInfoUtilsForShow` 的类型 5 = 喜欢；6 = 取消）。
     *
     * **注意**：这两个码在 `controlPlay()` 里走的是**同一个 case**，
     * 收藏与取消的区别在 `SongInfoUtilsForShow` 内部按类型区分。
     */
    const val CMD_FAVORITE = 5

    /** 取消收藏当前歌曲（见 [CMD_FAVORITE]）。 */
    const val CMD_UNFAVORITE = 6

    // ---------------------------------------------------------------- ② 播放模式（第一九五轮）

    /**
     * 单曲循环。
     *
     * `BroadcastReceiverCenterForThird` 常量：
     * `MODE_SONG_REPEAT = 101`。
     *
     * 这三个码走 `controlPlay()` 的另一组分支（`i4 == 101/103/105`），
     * 最终调 `MusicPlayerHelper.j0().S1(mode)` —— **真的切播放模式**，
     * 与 `m0=0/1/2/3` 那组（走 `setPlay` / `prevPlay` / `backPlay`）不是一回事。
     */
    const val MODE_SONG_REPEAT = 101

    /** 列表循环（顺序播放）。 */
    const val MODE_LIST_REPEAT = 103

    /** 随机播放。 */
    const val MODE_SONG_SHUFFLE = 105

    /** 「打开允许对外发送」开关。 */
    const val CMD_ALLOW_SEND_OUTSIDE = 100

    // ---------------------------------------------------------------- 动作名（第一九五轮）

    /** 动作名：收藏当前歌曲。 */
    const val ACTION_FAVORITE = "favorite"

    /** 动作名：取消收藏当前歌曲。 */
    const val ACTION_UNFAVORITE = "unfavorite"

    /** 动作名：单曲循环。 */
    const val MODE_REPEAT_ONE = "mode-repeat-one"

    /** 动作名：列表循环（顺序播放）。 */
    const val MODE_REPEAT_ALL = "mode-repeat-all"

    /** 动作名：随机播放。 */
    const val MODE_SHUFFLE = "mode-shuffle"

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
        /**
         * 收藏态（`key_is_fav`；-1 = 这条广播里没有）。
         *
         * ## 出处（第一九五轮逆向 `com.tencent.qqmusiccar` 1.9.8.22 得到）
         *
         * `BroadcastSenderCenterForThird.putSongInfo()`：
         *
         * ```java
         * i4 = (songInfo == null || !MyFavManager.H().R(songInfo)) ? 0 : 1;
         * jSONObject.put("key_is_fav", i4);
         * ```
         *
         * → **1 = 已收藏，0 = 未收藏**。
         * 只有 `update_song` 这类带歌曲信息的广播里才有它；
         * `update_state`（只有播放态）里没有 → 那时用 **-1 表示「不知道」**，
         * 不能当成「未收藏」，否则界面会闪成未收藏。
         */
        val favorite: Int = -1,
    ) {
        /** 有没有歌名或歌手（够合成一张卡片了）。 */
        val hasSong: Boolean get() = title.isNotEmpty() || artist.isNotEmpty()

        /** 有没有歌词。 */
        val hasLyric: Boolean get() = lyric.isNotEmpty()

        /**
         * 收藏态**已知**吗（-1 = 不知道）。
         *
         * 界面上「不知道」和「未收藏」要分开：
         * 不知道 → 不点亮收藏按钮、也不置灰；
         * 未收藏 → 显示未收藏的样子。
         */
        val favoriteKnown: Boolean get() = favorite >= 0

        /** 是否已收藏（`favoriteKnown` 为 false 时返回 false，别拿它当状态用）。 */
        val favorited: Boolean get() = favorite == 1

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
            favorite = if (data.containsKey("key_is_fav")) {
                JsonLite.long(data, "key_is_fav", 0L).toInt()
            } else {
                -1
            },
        )
    }

    /**
     * 只取广播里的 `command.method`（第128轮，纯逻辑，可单测）。
     *
     * ## 为什么单独要一个函数
     *
     * 真车诊断包原来只记了**最后一条**广播的原文，那一条是
     * `{"module":"app","command":{"method":"update_app_foreground",…}}` ——
     * QQ 音乐在说「我在不在前台」，**本来就不含歌名**。
     * 只看它会误判成「QQ 音乐根本没发过曲目广播」。
     *
     * 所以诊断要改成记 **method 计数表**：`update_song` / `update_state` /
     * `update_lyric` 各几条，一眼就能看出 QQ 到底发了什么。
     * 这个函数与 [parse] 独立：**即使 JSON 解析失败也要能报出 method**
     * （解析失败本身就是重要信号）。
     *
     * @return method 名；取不到时返回空串
     */
    fun methodOf(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val root = JsonLite.parseObject(raw) ?: return ""
        val command = root["command"] as? Map<String, Any?> ?: return ""
        return JsonLite.str(command, "method")
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
     * 把动作翻译成 QQ 音乐的命令码（`m0`）。
     *
     * **这套编号在两个通道里是同一套**（实测 + 反编译都对上了）：
     * `0=PLAY 1=PAUSE 2=PREVIOUS 3=NEXT`，广播通道的 action 码与
     * URI 通道的 `m0` 完全一致。
     *
     * 第一九五轮新增（同样走 URI 通道的 `action=20`）：
     *
     * | 动作 | 码 | QQ 音乐那边 |
     * |---|---|---|
     * | 收藏 | [CMD_FAVORITE] = 5 | `controlPlay` → `case 5` → `SongInfoUtilsForShow.s(…, 5, …)` |
     * | 取消收藏 | [CMD_UNFAVORITE] = 6 | `controlPlay` → `case 6` |
     * | 单曲循环 | [MODE_SONG_REPEAT] = 101 | `controlPlay` → `S1(101)` |
     * | 列表循环 | [MODE_LIST_REPEAT] = 103 | `controlPlay` → `S1(103)` |
     * | 随机播放 | [MODE_SONG_SHUFFLE] = 105 | `controlPlay` → `S1(105)` |
     *
     * @param action `play` / `pause` / `next` / `previous` / `stop` /
     *   `favorite` / `unfavorite` / `mode-repeat-one` / `mode-repeat-all` / `mode-shuffle`
     * @return 对应的命令码；不认识返回 null。
     */
    fun commandFor(action: String): Int? = when (action) {
        "play" -> CMD_PLAY
        "pause" -> CMD_PAUSE
        "next" -> CMD_NEXT
        "previous" -> CMD_PREVIOUS
        "stop" -> CMD_STOP
        ACTION_FAVORITE -> CMD_FAVORITE
        ACTION_UNFAVORITE -> CMD_UNFAVORITE
        MODE_REPEAT_ONE -> MODE_SONG_REPEAT
        MODE_REPEAT_ALL -> MODE_LIST_REPEAT
        MODE_SHUFFLE -> MODE_SONG_SHUFFLE
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

    /**
     * 收藏 / 取消收藏当前歌曲（第一九五轮）。
     *
     * 走 ③ 号 URI 通道：`qqmusiccar://?action=20&m0=5`（收藏）/ `&m0=6`（取消）。
     *
     * ## 为什么不用广播通道
     *
     * `dispacherAction()`（广播通道）里 `case 5/6` 走的是 `openQQMusic()` ——
     * **只会把 QQ 音乐的界面打开**，不会真的收藏。
     * 真正执行收藏的是 `controlPlay(m0)`，它只从 URI 的 `action=20` 进来。
     *
     * ## 收藏态怎么回来
     *
     * QQ 音乐收藏成功后会通过第三方广播把 `key_is_fav` 回传
     * （`BroadcastSenderCenterForThird.putSongInfo()`：`MyFavManager.H().R(songInfo)`）。
     * → 界面上的收藏态**以广播为准**，不靠乐观 UI 猜。
     *
     * ## ⚠️ 这条通道是「**切换**」语义，不是「设为」
     *
     * 桌面第一次发出去时按「设为收藏」理解，**逆向后发现不是**：
     * `BroadcastReceiverCenterForThird.controlPlay()` 里
     *
     * ```java
     * case 5:
     * case 6:                                  // ← 两个 case 落在一起
     *     SongInfoUtilsForShow.s(context, songInfo, 5, "");   // ← 参数都是 5
     * ```
     *
     * 而 `s(..., 5, ...)` → `g()` 里是：
     *
     * ```java
     * final boolean zR = MyFavManager.H().R(songInfo);        // 当前是否已收藏
     * FavorSongServer.a(songInfo, Boolean.valueOf(true ^ zR)); // 收藏 / 取消收藏
     * ```
     *
     * → **m0=5 与 m0=6 完全等价，都是「翻转当前收藏态」**。
     *
     * 后果（已实测）：桌面状态还是「不知道」时点一下收藏，
     * 若 QQ 那边**本来就已收藏**，这一下会把它**取消**掉
     * → 然后 QQ 回 `key_is_fav=0`，桌面按回报显示「未收藏」。
     *
     * 这是**协议本身的限制**（第三方通道只有 toggle，没有「设为」），
     * 桌面这侧只能做到「状态已知时点一下必然翻转正确」，
     * 状态未知时的第一次点击**可能与用户预期相反**。
     *
     * @param favorited true = 收藏，false = 取消收藏
     *   （**注意**：QQ 这条通道上是 toggle，两者实际等价）
     * @return 是否真的发出去了
     */
    fun sendFavorite(context: Context, favorited: Boolean): Boolean =
        sendControl(context, if (favorited) ACTION_FAVORITE else ACTION_UNFAVORITE)

    /**
     * 切播放模式（第一九五轮）。
     *
     * 走 ③ 号 URI 通道：`qqmusiccar://?action=20&m0=101|103|105`
     * （单曲循环 / 列表循环 / 随机播放）。
     *
     * @param mode [MODE_REPEAT_ONE] / [MODE_REPEAT_ALL] / [MODE_SHUFFLE]
     * @return 是否真的发出去了
     */
    fun sendPlayMode(context: Context, mode: String): Boolean = sendControl(context, mode)

    // ---------------------------------------------------------------- 采纳门

    /**
     * 这条 QQ 广播**要不要采纳**（第126轮；第128轮加「当前媒体源」，纯逻辑，可单测）。
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
     * —— 广播明明到了，就是被这道门挡掉的。
     *
     * ## 第128轮：真车诊断包把这个假设**证伪**了
     *
     * 第126轮在这段注释里写「真车上的实际情况（`default_music_source_v1=qq`）」——
     * **这是错的**。2026-11-06 的真车诊断包（1.0.1130 / 4226）实测：
     *
     * ```
     * SharedPreferences(cardesk) 全量键值里 **没有** default_music_source_v1
     * design_music_source_v1 = qq      <- 有
     * 忽略 QQ 广播（当前会话不是 QQ 音乐） boundPkg= defaultSrc=generic
     * qqBroadcast: 收到=4 采纳=0 丢弃=4
     * ```
     *
     * 用户是**在音乐卡片的来源行里**选了 QQ 音乐（那是 `design_music_source_v1`，
     * 也就是 [prefs.musicSource]），**从来没在设置页里改过「默认媒体源」**
     * （那是 `default_music_source_v1` / [prefs.defaultMusicSource]）。
     * 于是：
     *
     * - 采纳门只读 `default_music_source_v1` → 读到的永远是默认值 `generic`
     *   → 4 条广播**全部被丢弃**；
     * - 开门广播（`action=100`）用同一个键判条件 → **一次都没发过**
     *   → QQ 音乐不往外挂 `update_song` / `update_state` / `update_lyric`
     *   → 我们永远拿不到歌名。
     *
     * 两个选择是**同一个意思**（「我要用 QQ 音乐」），只存在不同的键里。
     * 所以这道门现在**两个都认**。
     *
     * ## 现在的通路
     *
     * 1. **当前绑定的会话就是 QQ 音乐** —— 原来就有，保持；
     * 2. **当前没有绑定任何会话**，而且用户把 QQ 音乐选成了
     *    **默认媒体源**（设置页）或**当前媒体源**（卡片来源行）—— 任一即可。
     *
     * 条件 2 不会误伤别的 App：一旦有别的播放器在播（比如爱趣听），
     * 绑定包就不是空了 → 条件 2 不成立 → QQ 的广播**不会盖掉正在播的歌**。
     *
     * @param boundPkg 当前绑定的会话包名（空串 / null = 没有绑定任何会话）
     * @param defaultSourceId 设置页里的「默认媒体源」id（空串 / null = 没设）
     * @param currentSourceId 卡片来源行里**当前生效**的媒体源 id（空串 / null = 没选）
     */
    fun shouldAccept(
        boundPkg: String?,
        defaultSourceId: String?,
        currentSourceId: String?,
    ): Boolean {
        if (QqLyric.isQqMusicPackage(boundPkg)) return true
        if (!boundPkg.isNullOrEmpty()) return false
        return isQqSelected(defaultSourceId, currentSourceId)
    }

    /**
     * 用户**是不是**把 QQ 音乐选成了媒体源（第128轮，纯逻辑，可单测）。
     *
     * 「默认媒体源」和「当前媒体源」任一命中即可 —— 理由见 [shouldAccept]。
     *
     * 这个判断要同时用在两个地方：
     * - **采纳广播**（[shouldAccept]）；
     * - **开门广播**（`action=100`，让 QQ 音乐开始往外挂曲目）。
     *   第126轮的开门广播只看「默认媒体源」，真车上因此一次都没发出去。
     */
    fun isQqSelected(defaultSourceId: String?, currentSourceId: String?): Boolean =
        defaultSourceId == MediaSources.QQ_ID || currentSourceId == MediaSources.QQ_ID

}
