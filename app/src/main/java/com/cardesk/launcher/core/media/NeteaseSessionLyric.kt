package com.cardesk.launcher.core.media

/**
 * 网易云音乐车机版（`com.netease.cloudmusic.iot`）的**歌词协议**（第127轮）。
 *
 * ## 背景：网易云的歌词有**两条**通道，我们以前**一条都没接**
 *
 * 第127轮在模拟器（Android 10 / arm64）上装了真的网易云车机版
 * **6.2.81（versionCode 6002081）**，`jadx` 反编译 `com/netease/cloudmusic/` 得到：
 *
 * ### ① metadata 通道（默认走这条）
 *
 * `vc/g$S(String lyric)`：
 *
 * ```java
 * builder.putString("android.media.metadata.LYRIC", lyric);
 * ```
 *
 * 歌词**整份 LRC** 就挂在 MediaSession metadata 的
 * **`android.media.metadata.LYRIC`** 这个 key 上。
 *
 * ### ② 会话事件通道（车机配置选它时才走这条）
 *
 * `vc/g$L(Integer state, String lyric)` → `I("wt_music_lyric", map)`：
 *
 * ```java
 * map = {
 *   MediaMetadata.METADATA_KEY_MEDIA_ID : audioId,
 *   "wt_music_lyric_obj"  : lyric,      // 整份 LRC
 *   "wt_music_lyric_state": state,      // 1 = 歌词就绪；2 = 提示消息；5 = 加载中
 * }
 * ```
 *
 * ### 走哪一条由**车机配置**决定
 *
 * `vc/g$e.a(musicId, lyric, state)`：
 *
 * ```java
 * Integer notifyLyricManner = player.getNotifyLyricManner();
 * if (notifyLyricManner == 1)      S(lyric);        // 只写 metadata
 * else if (notifyLyricManner == 2) L(state, lyric); // 只发会话事件
 * else if (notifyLyricManner == 0) { S(lyric); L(state, lyric); } // 两条都发
 * else                             S(lyric);
 * ```
 *
 * `notifyLyricManner` 是**每台车自己的配置**（`iot` 自定义配置），
 * 我们**不知道用户那台车是几** → **两条都得接**。
 *
 * ## 我们以前为什么拿不到网易云的歌词
 *
 * [PlayerLyric.METADATA_LYRIC_KEYS] 里的 key 是
 * `lyric` / `lyrics` / `lrc` / `lyric_text` / `current_lyric` / `caption` / `subtitles` ——
 * **全是短名**，而网易云用的是**带前缀的全名**
 * `android.media.metadata.LYRIC`（`meta.getText(key)` 是**精确匹配**）→
 * **永远读不到**。
 *
 * 这就是「网易云没歌词」的真因（不是歌词格式认错了，是**根本没取到那一项**）。
 *
 * ## 三个会话事件
 *
 * | 事件 | 载荷 | 含义 |
 * |---|---|---|
 * | `wt_music_lyric` | `wt_music_lyric_obj` + `wt_music_lyric_state` | 整份歌词（state==1 才是歌词） |
 * | `get_lyric_action` | `lyric`（`String[]`） | 上一句 / 当前句 / 下一句（**没有时间戳**） |
 * | `lyric_loading_action` / `lyric_message_action` | — | 加载中 / 提示消息 → **清空歌词** |
 *
 * 纯 Kotlin（不碰 `android.os.Bundle`），保证能 JVM 单测。
 */
object NeteaseSessionLyric {

    /**
     * 网易云把歌词挂在 MediaSession metadata 的这个 key 上。
     *
     * 来自 `vc/g$S(String)`：`builder.putString("android.media.metadata.LYRIC", lyric)`。
     */
    const val LYRIC_METADATA_KEY = "android.media.metadata.LYRIC"

    /** 会话事件：歌词（`wt_music_lyric_obj` + `wt_music_lyric_state`）。 */
    const val EVENT_LYRIC = "wt_music_lyric"

    /** 会话事件：当前三行歌词（`lyric` = `String[]`）。 */
    const val EVENT_CURRENT_LINES = "get_lyric_action"

    /** 会话事件：歌词加载中。 */
    const val EVENT_LOADING = "lyric_loading_action"

    /** 会话事件：歌词提示消息（不是歌词本身）。 */
    const val EVENT_MESSAGE = "lyric_message_action"

    /** `wt_music_lyric` 里的歌词正文（整份 LRC）。 */
    const val KEY_LYRIC_OBJ = "wt_music_lyric_obj"

    /** `wt_music_lyric` 里的状态：**1 = 歌词就绪**。 */
    const val KEY_LYRIC_STATE = "wt_music_lyric_state"

    /** `get_lyric_action` 里的三行歌词数组。 */
    const val KEY_LYRIC_LINES = "lyric"

    /**
     * 事件里带的歌曲 id（`MediaMetadata.METADATA_KEY_MEDIA_ID` 的字面值）。
     *
     * 写成常量而不是引用 `MediaMetadata`：本文件要能在**纯 JVM 单测**里跑，
     * `android.media.MediaMetadata` 在单测里是空壳。
     */
    const val KEY_MEDIA_ID = "android.media.media.id"

    /** `wt_music_lyric_state == 1` 才算「歌词就绪」。 */
    const val STATE_READY = 1

    /**
     * 歌词长度上限（131072 = 128 KiB）。
     *
     * 口径对齐 UNI `NeteaseMediaAdapter.MAX_LYRIC_LENGTH`：超长的一律当没有 ——
     * 防止把整页 HTML / JSON 当歌词塞进解析器。
     */
    const val MAX_LENGTH = 131072

    /**
     * 一条会话事件解析出来的结果。
     *
     * @param lrc 整份 LRC（空串 = 这条事件不带整份歌词）
     * @param lines 当前三行（上一句 / 当前句 / 下一句；空列表 = 这条事件不带行）
     * @param cleared 播放器让我们**清空**歌词（加载中 / 提示消息）
     */
    data class Event(
        val lrc: String = "",
        val lines: List<String> = emptyList(),
        val cleared: Boolean = false,
    ) {
        /** 这条事件带了有用的东西吗。 */
        val hasContent: Boolean get() = lrc.isNotEmpty() || lines.isNotEmpty() || cleared
    }

    /**
     * 解析一条会话事件。
     *
     * @param event 事件名（`MediaController.Callback.onSessionEvent` 的第一个参数）
     * @param extras 事件载荷（`Bundle` 转成的 Map；值可能是 `String` / `Int` / `String[]`）
     * @return 解析结果；**不认识的事件返回 null**（调用方据此「这条与我们无关」）。
     */
    fun parse(event: String?, extras: Map<String, Any?>): Event? = when (event) {
        EVENT_LYRIC -> {
            // state == 1 才是歌词；2 / 5 是提示消息 / 加载中 → 清空。
            val state = intOf(extras[KEY_LYRIC_STATE])
            if (state == STATE_READY) {
                Event(lrc = text(extras[KEY_LYRIC_OBJ]))
            } else {
                Event(cleared = true)
            }
        }

        EVENT_CURRENT_LINES -> {
            // 下标 0/1/2 = 上一句 / 当前句 / 下一句（没有时间戳）
            val raw = extras[KEY_LYRIC_LINES]
            val arr: List<Any?> = when (raw) {
                is Array<*> -> raw.toList()
                is List<*> -> raw
                else -> emptyList()
            }
            val lines = arr.map { text(it) }
            Event(lines = lines)
        }

        EVENT_LOADING, EVENT_MESSAGE -> Event(cleared = true)

        else -> null
    }

    /**
     * 这条歌词事件是不是**过期**的（属于上一首歌）。
     *
     * 网易云切歌时会先把上一首的歌词事件补发一遍（网络回来得慢）。
     * 事件里带了 `METADATA_KEY_MEDIA_ID`，和当前会话的歌 id 对不上就是过期。
     *
     * 口径对齐 UNI `NeteaseMediaAdapter`：
     * **只有两边都非空且不相等**才算过期 —— 任一边为空都不判死
     * （有些车机版本不带 id，带着判就会把真歌词也丢掉）。
     *
     * @param eventMediaId 事件里的歌 id
     * @param currentMediaId 当前会话的歌 id
     */
    fun isStale(eventMediaId: String?, currentMediaId: String?): Boolean {
        val e = eventMediaId?.trim().orEmpty()
        val c = currentMediaId?.trim().orEmpty()
        if (e.isEmpty() || c.isEmpty()) return false
        return e != c
    }

    /** 取事件里的歌 id（`METADATA_KEY_MEDIA_ID`）。 */
    fun mediaIdOf(extras: Map<String, Any?>): String = text(extras[KEY_MEDIA_ID])

    /** 值转字符串；超过 [MAX_LENGTH] 的当没有（防把整页 HTML 当歌词）。 */
    private fun text(value: Any?): String {
        val s = value as? String ?: return ""
        return if (s.length > MAX_LENGTH) "" else s
    }

    /** 值转 int（`Bundle` 里可能是 `Integer` 也可能是 `Long`）。 */
    private fun intOf(value: Any?): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        else -> -1
    }
}
