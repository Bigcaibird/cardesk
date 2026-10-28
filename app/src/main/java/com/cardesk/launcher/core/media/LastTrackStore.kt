package com.cardesk.launcher.core.media

import android.content.Context
import android.content.SharedPreferences
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 上次歌曲状态的持久化。
 *
 * 用 MMKV 存 5 个键
 * `last_music_package` / `last_song_title` / `last_song_artist` /
 * `last_song_duration` / `last_song_position`。
 *
 * ## 为什么把存取抽成 [Storage]
 *
 * 本项目开了 `unitTests.isReturnDefaultValues = true`，android.jar 是空壳：
 * 直接调 `Context.getSharedPreferences` 在 JVM 单测里拿不到真实存储，
 * 持久化逻辑就验证不了。这里把存取抽成接口 —— 生产环境用 [SharedPrefsStore]
 * （和 `core/Prefs.kt` 是**同一个** prefs 文件 "cardesk"），单测用内存实现，
 * 两边跑的是同一份代码。
 *
 * 行为对齐：
 * - 写入：空串 / 0 不覆盖旧值，进度每次都写；
 * - 读取。
 */
class LastTrackStore(private val storage: Storage) {

    /** 上一次写入的内容（仅用于日志比对，避免重复记录）。 */
    private var lastSaved: State? = null

    /** 键值存储抽象：生产用 SharedPreferences，单测用内存实现。 */
    interface Storage {
        fun getString(key: String, default: String = ""): String
        fun getLong(key: String, default: Long = 0L): Long
        fun putString(key: String, value: String)
        fun putLong(key: String, value: Long)
        fun remove(key: String)

        /**
         * 批量写。
         *
         * 默认实现逐键调用 [putString] / [putLong]（内存实现走这条）。
         * SharedPreferences 实现会覆写成「一次 Editor 提交」。
         *
         * 为什么要批量：SharedPreferences 每次 `edit().apply()` 都会把整个
         * prefs XML 重写一遍。[save] 一次要写 5 个键，逐键写就是 5 次全量重写；
         * 播放时进度每秒落一次盘，等于每秒 5 次 XML 重写 —— 在车机上
         * 这是掉帧 + 磨 flash 的量级。合并成一次提交后只剩 1 次。
         */
        fun putAll(values: Map<String, Any>) {
            values.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Int -> putLong(key, value.toLong())
                }
            }
        }

        /** 批量删（默认逐键删；SharedPreferences 实现合并成一次提交）。 */
        fun removeAll(keys: Collection<String>) {
            keys.forEach { remove(it) }
        }
    }

    /**
     * 生产环境构造：写进和 `core/Prefs.kt` 相同的 prefs 文件（"cardesk"）。
     *
     * 注意：不要改成 `core/Prefs.kt` 里的封装 —— 那边由别的 worker 维护，
     * 这里自己开一个同名 prefs，键名不冲突。
     */
    constructor(context: Context) : this(
        SharedPrefsStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
    )

    /** 上次歌曲的快照。 */
    data class State(
        val packageName: String = "",
        val title: String = "",
        val artist: String = "",
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
    ) {
        /** 是否什么都没有可恢复。 */
        val isEmpty: Boolean
            get() = packageName.isEmpty() && title.isEmpty() && artist.isEmpty() &&
                durationMs <= 0L && positionMs <= 0L
    }

    /**
     * 写入上次状态。
     *
     *
     * - 包名 / 歌名 / 歌手为空时**不覆盖**旧值（保留最后一次已知的信息）；
     * - 总时长只有 > 0 才写；
     * - 进度每次都写（>= 0）。
     */
    fun save(state: State) {
        // 先算出「真正要写的键」，再一次提交（避免 5 次 XML 全量重写）
        val batch = LinkedHashMap<String, Any>()
        if (state.packageName.isNotEmpty()) batch[KEY_PACKAGE] = state.packageName
        if (state.title.isNotEmpty()) batch[KEY_TITLE] = state.title
        if (state.artist.isNotEmpty()) batch[KEY_ARTIST] = state.artist
        if (state.durationMs > 0L) batch[KEY_DURATION] = state.durationMs
        if (state.positionMs >= 0L) batch[KEY_POSITION] = state.positionMs
        if (batch.isNotEmpty()) storage.putAll(batch)
        // 排障场景：冷启动后「上次播放」显示不对时，这里能看到写进了什么
        Logx.d(
            LogTags.MEDIA, "上次歌曲状态写入",
            "pkg" to state.packageName, "title" to state.title, "artist" to state.artist,
            "durationMs" to state.durationMs, "positionMs" to state.positionMs,
        )
        // 只有内容（歌名/歌手/时长/来源）变了才记 INFO —— 进度每秒都在变，不能每次都落盘一条
        val prev = lastSaved
        lastSaved = state
        if (prev == null || prev.packageName != state.packageName || prev.title != state.title ||
            prev.artist != state.artist || prev.durationMs != state.durationMs
        ) {
            Logx.i(
                LogTags.MEDIA, "上次歌曲状态变更",
                "pkg" to state.packageName, "title" to state.title, "artist" to state.artist,
                "durationMs" to state.durationMs, "positionMs" to state.positionMs,
            )
        }
    }

    /** 读取上次状态（没存过就是全空）。 */
    fun load(): State {
        val state = State(
            packageName = storage.getString(KEY_PACKAGE),
            title = storage.getString(KEY_TITLE),
            artist = storage.getString(KEY_ARTIST),
            durationMs = storage.getLong(KEY_DURATION),
            positionMs = storage.getLong(KEY_POSITION),
        )
        Logx.i(
            LogTags.MEDIA, "读取上次歌曲状态",
            "pkg" to state.packageName, "title" to state.title, "artist" to state.artist,
            "durationMs" to state.durationMs, "positionMs" to state.positionMs,
        )
        return state
    }

    /**
     * 读取并套用「恢复规则」后的状态。
     *
     * 歌词源（本地/蓝牙/USB 等）的
     * 「标题」字段其实是一句歌词，恢复时必须置空，否则音乐页会显示一句歌词当歌名。
     */
    fun loadRestored(isLyricSource: (String) -> Boolean): State {
        val state = load()
        val restored = state.copy(title = restoredTitle(state.packageName, state.title, isLyricSource))
        // 排障场景：恢复后歌名是空的（歌词型来源会被置空），这里能看到原因
        Logx.i(
            LogTags.MEDIA, "上次歌曲状态恢复",
            "pkg" to restored.packageName, "rawTitle" to state.title, "title" to restored.title,
            "artist" to restored.artist, "durationMs" to restored.durationMs,
            "positionMs" to restored.positionMs, "empty" to restored.isEmpty,
        )
        return restored
    }

    /** 清空全部上次状态。 */
    fun clear() {
        storage.removeAll(listOf(KEY_PACKAGE, KEY_TITLE, KEY_ARTIST, KEY_DURATION, KEY_POSITION))
        lastSaved = null
        Logx.i(LogTags.MEDIA, "清空上次歌曲状态")
    }

    companion object {
        /** 和 `core/Prefs.kt` 共用的 prefs 文件名。 */
        const val PREFS_NAME = "cardesk"

        const val KEY_PACKAGE = "last_music_package"

        const val KEY_TITLE = "last_song_title"

        const val KEY_ARTIST = "last_song_artist"

        const val KEY_DURATION = "last_song_duration"

        const val KEY_POSITION = "last_song_position"
    }
}

/**
 * 「歌词源 → 歌名置空」的恢复规则（顶层纯函数，方便单测）。
 *
 *
 * `String str = LyricMatch.isLyricTitleSource(pkg) ? "": title;`
 *
 * @param pkg 上次播放的来源包名
 * @param title 持久化下来的歌名
 * @param isLyricSource 「这个包是不是歌词源」的判定（我们用 [LyricMatch.isLyricTitleSource]）
 */
fun restoredTitle(pkg: String, title: String, isLyricSource: (String) -> Boolean): String =
    if (isLyricSource(pkg)) "" else title

/** SharedPreferences 实现的 [LastTrackStore.Storage]。 */
private class SharedPrefsStore(private val sp: SharedPreferences) : LastTrackStore.Storage {
    override fun getString(key: String, default: String): String = sp.getString(key, default) ?: default
    override fun getLong(key: String, default: Long): Long = sp.getLong(key, default)
    override fun putString(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    override fun putLong(key: String, value: Long) {
        sp.edit().putLong(key, value).apply()
    }

    override fun remove(key: String) {
        sp.edit().remove(key).apply()
    }

    /** 覆写批量写：所有键合并成一次 Editor 提交，只重写一遍 prefs XML。 */
    override fun putAll(values: Map<String, Any>) {
        val editor = sp.edit()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putLong(key, value.toLong())
            }
        }
        editor.apply()
    }

    /** 覆写批量删：一次 Editor 提交删掉全部键。 */
    override fun removeAll(keys: Collection<String>) {
        val editor = sp.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }
}
