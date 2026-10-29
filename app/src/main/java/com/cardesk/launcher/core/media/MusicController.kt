package com.cardesk.launcher.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 音乐总控：把「会话追踪 + 歌词多级回退」拼成 UI 可直接消费的一条流。
 * 沿用 的关键路径：
 *
 * 1. 会话：`MusicSessionTracker`（MediaSessionManager + 通知监听）；
 * 2. 歌词优先级：QQ 车机广播 → 在线竞速（4.5s 预算）→ 酷狗旧接口兜底 → 失败态；
 * 3. 显示：当前行 / 上一行 / 下一行 + 行内擦除比例，切歌时丢弃旧结果（代数号）；
 * 4. 失败 60 秒内不重复请求**同一首歌**
 * 5. 上次歌曲状态：每次元数据/进度变化落盘，启动时恢复
 *
 */
class MusicController private constructor() {

    interface Listener {
        fun onMediaChanged(track: TrackInfo?)
        fun onLyricChanged(timeline: LyricTimeline?, index: Int, wipe: Float)
    }

    /**
     * 「上次歌曲状态恢复」的可选监听。
     *
     * 单独开一个接口而不是加进 [Listener]：[Listener] 已经有实现方了（UI 侧），
     * 加方法会逼着所有实现方一起改。这里做成**可选接口** ——
     * 实现了它的监听者才会在 [addListener] 时收到恢复结果。
     */
    interface RestoredListener {
        fun onLastTrackRestored(state: LastTrackStore.State?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var context: Context? = null
    private var started = false

    /** 上次歌曲状态的持久化（MMKV 那 5 个键）。 */
    private var store: LastTrackStore? = null

    var track: TrackInfo? = null
        private set
    var lyric: LyricTimeline? = null
        private set
    var lyricIndex: Int = -1
        private set
    var lyricWipe: Float = 0f
        private set
    var lyricState: String = ""
        private set

    /**
     * 启动时从 [LastTrackStore] 恢复出来的「上次歌曲」状态。
     *
     * UI 层据此显示
     * 上次的歌名 / 歌手 / 总时长 / 进度，以及来源徽标（包名）。
     */
    var lastRestored: LastTrackStore.State? = null
        private set

    /** 失败冷却：记录「哪一首歌」在什么时候失败（按歌冷却）。 */
    private var lastFailAt = 0L
    private var lastFailKey = ""
    private var lyricNotFound = false

    /**
     * 正在取歌词的**歌名 key**（null = 没有请求在飞）。
     *
     * 之前是一个布尔量 只能表达「有没有请求在飞」。
     * 但同一时刻可能有两次请求在飞（切歌后旧歌的结果还没回来），
     * 布尔量没法区分「是我这次的结果」还是「别人的」，
     * 于是过期结果被丢弃时（`key != lastLyricSongKey`）标志位不复位，
     * 下一轮同歌的请求会被 `if (sameSong && inFlight) return` 挡掉 → 歌词永远不出来。
     * 改成按 key 作用域后：谁的结果谁负责清自己的标记。
     */
    private var lyricFetchInFlightKey: String? = null
    private var lastLyricSongKey = ""
    private var broadcastLyric: LyricTimeline? = null
    private var broadcastSongKey = ""
    private var tickerRunning = false

    /**
     * 当前歌词**来源候选自带的时长**（ms）；0 = 不知道（本地缓存 / 旧接口兜底）。
     *
     * 第四十轮新增：用来复核「挂上去的歌词是不是这首歌的那个长度」。
     * 车机会话的总时长常常晚几秒才推过来（诊断包实测晚 3.1 秒），
     * 取词那一刻时长可能是 0 —— 那时只能靠「同分取更长」来猜；
     * 等真实时长到齐，这里就能复核，差太多就重取（见 [revalidateLyricVersion]）。
     */
    private var lyricSourceDurationMs = 0L

    /**
     * 播放器自报的「当前正在唱的那一句」（第七十八轮新增）。
     *
     * 空串 = 这个来源不报（普通播放器），或这一帧拿不到。
     * 有值时**它优先于** LRC 时间轴算出来的当前行 —— 见 [recomputeLyric]。
     */
    private var playerCurrentLine: String = ""

    /**
     * 上一次**显示**出去的当前行文字（第七十八轮新增）。
     *
     * 用来判断「播放器自报的当前句变了」——
     * LRC 的行号没变但自报句变了时，界面也得跟着刷新，
     * 否则新的一句要等到行号跳了才显示出来。
     */
    private var lyricLiveText: String = ""

    /** 会话消失后的清空计时器代数。 */
    private var nullClearGeneration = 0
    private var pendingClearGeneration = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            recomputeLyric()
            main.postDelayed(this, LYRIC_TICK_MS)
        }
    }

    private val nullClearRunnable = Runnable {
        if (started && pendingClearGeneration == nullClearGeneration) clearToIdle()
    }

    private val qqReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            handleQqLyricBroadcast(intent ?: return)
        }
    }

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        this.context = app
        started = true
        // 上次歌曲状态：启动时恢复
        store = LastTrackStore(app)
        restoreLastSongState()
        MusicSessionTracker.get().addListener(trackerListener)
        MusicSessionTracker.get().start(context)
        registerQqReceiver(app)
        tickerRunning = true
        main.post(ticker)
        Logx.i(
            LogTags.MEDIA, "媒体总控启动",
            "lyricTickMs" to LYRIC_TICK_MS,
            "failCooldownMs" to LYRIC_FAIL_COOLDOWN_MS,
        )
    }

    fun stop() {
        // —— 退出前把当前状态落盘
        track?.let { persistTrack(it, force = true) }
        started = false
        tickerRunning = false
        Logx.i(LogTags.MEDIA, "媒体总控停止", "title" to (track?.title ?: ""))
        main.removeCallbacksAndMessages(null)
        MusicSessionTracker.get().removeListener(trackerListener)
        MusicSessionTracker.get().stop()
        runCatching { context?.unregisterReceiver(qqReceiver) }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onMediaChanged(track)
        l.onLyricChanged(lyric, lyricIndex, lyricWipe)
        (l as? RestoredListener)?.onLastTrackRestored(lastRestored)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ---------------------------------------------------------------- tracker

    private val trackerListener = object : MusicSessionTracker.Listener {
        override fun onTrackChanged(t: TrackInfo?) {
            if (t == null) {
                // —— 会话消失后延迟 NULL_SESSION_CLEAR_MS 再清空
                // 排障场景：界面突然变成「暂无播放」时，这里能看到会话是被谁收回的
                Logx.i(LogTags.MEDIA, "会话消失（延迟清空）", "delayMs" to NULL_SESSION_CLEAR_MS)
                nullClearGeneration++
                pendingClearGeneration = nullClearGeneration
                main.removeCallbacks(nullClearRunnable)
                main.postDelayed(nullClearRunnable, NULL_SESSION_CLEAR_MS)
                return
            }
            nullClearGeneration++
            main.removeCallbacks(nullClearRunnable)
            val prev = track
            val changed = isTrackChanged(track, t)
            // 会话推来的 metadata 里没有 bitmap 时，用已经取到的封面补回去 ——
            // 否则会话每 200ms~2.5s 推一次 metadata，会把刚取到的封面又抹掉。
            val merged = mergeCachedCover(t)
            track = merged
            listeners.forEach { it.onMediaChanged(merged) }
            // —— 播放器自报的当前句（第七十八轮新增）
            //
            // 变了就要重算当前行：LRC 的行号没动、但播放器报的那句变了，
            // 界面也得立刻换成新的一句。
            val liveChanged = playerCurrentLine != merged.playerCurrentLine
            playerCurrentLine = merged.playerCurrentLine
            if (liveChanged) recomputeLyric()
            // 会话没给封面 → 按优先级链自己取一张
            requestCover(merged)
            // 排障场景：换歌 / 歌手时长变了 → 落盘一条；每 2.5s 一次的重复推送只进 Logcat
            val contentChanged = prev == null || prev.title != t.title || prev.artist != t.artist ||
                prev.durationMs != t.durationMs || prev.mediaId != t.mediaId
            if (contentChanged) {
                Logx.i(
                    LogTags.MEDIA, "会话曲目变化",
                    "pkg" to t.pkg, "title" to t.title, "artist" to t.artist,
                    "durationMs" to t.durationMs, "mediaId" to t.mediaId,
                    "playing" to t.playing, "sourceId" to t.sourceId,
                )
            } else {
                Logx.d(
                    LogTags.MEDIA, "会话曲目推送（内容未变）",
                    "pkg" to t.pkg, "title" to t.title, "positionMs" to t.positionMs,
                )
            }
            // 元数据变了 → 立刻落盘
            persistTrack(t, force = changed)
            if (changed) {
                onTrackReset(t)
            }
            // —— 歌词版本复核（第四十轮新增，第七十八轮把触发条件补齐）
            //
            // 取词那一刻如果车机会话还没给出总时长（换歌瞬间很常见），
            // 就判别不出「专辑原版」和「电台剪辑版」—— 两者歌名歌手都相同，分数一样，
            // 只能靠「同分取更长」来猜。猜错了整首歌的歌词就越走越偏。
            //
            // 原来的条件是 `prev.durationMs <= 0 && t.durationMs > 0`，而且**只在
            // 「曲目内容没变」的分支里**调。可是「时长从 0 变成真值」在
            // [isTrackChanged] 里恰好算「变了」（时长差 >2s）→ 走的是上面的
            // `onTrackReset` 分支 → 这条复核**一次都没跑过**
            // （真机诊断包实测：日志里「复核」0 命中）。
            //
            // 现在改成：**只要时长变了就复核**，与「曲目变没变」无关。
            // 复核本身是安全的 —— 歌名/歌手/时长都没到齐时它会自己提前返回。
            if (prev != null && t.durationMs > 0 && prev.durationMs != t.durationMs) {
                revalidateLyricVersion(t)
            }
        }

        override fun onPlaybackChanged(playing: Boolean, positionMs: Long) {
            val cur = track ?: return
            if (cur.playing == playing && cur.positionMs == positionMs) return
            track = cur.copy(playing = playing, positionMs = positionMs)
            // 进度每 200ms 推一次，这里只进 Logcat（播放/暂停的切换由会话层记 INFO）
            Logx.d(
                LogTags.MEDIA, "播放进度推送",
                "pkg" to cur.pkg, "playing" to playing, "positionMs" to positionMs,
            )
            // 进度变化按 1s 粒度往 UI 推（UI 自身也是 1s 刷新，避免整页重绘）
            val lastPush = lastPositionPushAt
            if (System.currentTimeMillis() - lastPush >= POSITION_PUSH_MS) {
                lastPositionPushAt = System.currentTimeMillis()
                listeners.forEach { it.onMediaChanged(track) }
            }
            // 进度同样要落盘
            persistTrack(track ?: return)
        }
    }

    private var lastPositionPushAt = 0L

    // ---------------------------------------------------------------- 封面

    /**
     * 已经取到的封面（按歌记住）。
     *
     * 会话推来的 metadata 里通常**没有** bitmap（车机音乐 App 大多只给 URI，
     * 有的连 URI 都不给，所以封面是我们自己按优先级链取回来的。
     * 会话每 200ms~2.5s 推一次 metadata，若不留着，取回来的封面会被立刻抹掉。
     */
    private var coverBitmap: Bitmap? = null
    private var coverBitmapKey: String = ""

    /** 正在取封面的那首歌的键（同一首歌不重复请求）。 */
    private var coverRequestKey: String = ""

    /** 封面请求代数号：切歌后旧请求的结果要丢弃。 */
    private var coverRequestGen = 0

    /**
     * 会话没给 bitmap、但我们已经取过这首歌的封面 → 把封面补回去。
     */
    private fun mergeCachedCover(t: TrackInfo): TrackInfo {
        if (t.artwork != null) return t
        val bmp = coverBitmap ?: return t
        if (coverBitmapKey.isEmpty()) return t
        if (coverBitmapKey != CoverLoader.songKey(t.mediaId, t.title, t.artist)) return t
        return t.copy(artwork = bmp)
    }

    /**
     * 按优先级链取封面。
     *
     * 链路：会话 bitmap → → → 网络搜索
     * （`CoverArtSearch`：先 QQ 音乐，再酷狗）。
     *
     * 取回来后写进 [coverBitmap]（同一首歌复用）并推给 UI。
     */
    private fun requestCover(t: TrackInfo) {
        val ctx = context ?: return
        if (t.artwork != null) return
        if (t.title.isEmpty() || t.title == MusicSessionTracker.NO_PLAYBACK) return
        val key = CoverLoader.songKey(t.mediaId, t.title, t.artist)
        if (coverRequestKey == key) return
        coverRequestKey = key
        val gen = ++coverRequestGen
        Logx.i(
            LogTags.MEDIA, "开始取封面",
            "key" to key, "title" to t.title, "artist" to t.artist,
            "hasUri" to t.artworkUri.isNotEmpty(), "uri" to t.artworkUri,
        )
        CoverLoader.fetch(ctx, t.title, t.artist, t.durationMs, t.mediaId, t.artworkUri) { bmp, source ->
            if (gen != coverRequestGen) {
                Logx.d(LogTags.MEDIA, "丢弃过期封面结果", "key" to key)
                return@fetch
            }
            if (bmp == null) {
                Logx.i(
                    LogTags.MEDIA, "封面获取失败（保持占位图）",
                    "key" to key, "title" to t.title, "artist" to t.artist,
                )
                return@fetch
            }
            coverBitmap = bmp
            coverBitmapKey = key
            val cur = track ?: return@fetch
            // 期间换歌了 → 这张封面不属于当前歌，丢弃
            if (CoverLoader.songKey(cur.mediaId, cur.title, cur.artist) != key) {
                Logx.d(LogTags.MEDIA, "封面结果已过期（当前是别的歌）", "key" to key)
                return@fetch
            }
            track = cur.copy(artwork = bmp)
            listeners.forEach { it.onMediaChanged(track) }
            Logx.i(
                LogTags.MEDIA, "封面已回填",
                "key" to key, "source" to source, "w" to bmp.width, "h" to bmp.height,
            )
        }
    }

    // ---------------------------------------------------------------- 持久化

    /** 上次状态的落盘时刻（进度每 200ms 变一次，不能每次都写盘）。 */
    private var lastPersistAt = 0L

    /**
     * 启动时恢复上次歌曲状态。
     *
     * 规则：
     * - 歌名非空才恢复；**歌词源的歌名恢复成空串**
     * - 歌手非空才恢复；
     * - 总时长 > 0 才恢复；
     * - 进度 > 0 才恢复；
     * - 包名非空才恢复（来源徽标用）。
     */
    private fun restoreLastSongState() {
        val st = store ?: return
        // 判定「是不是歌词源」复用 LyricMatch（LyricMatch.isLyricTitleSource:82-88）
        val state = st.loadRestored { pkg -> LyricMatch.isLyricTitleSource(pkg) }
        lastRestored = state
        // 排障场景：冷启动后音乐页显示的是上次的歌，这里要能看到恢复了什么
        Logx.i(
            LogTags.MEDIA, "恢复上次歌曲状态",
            "pkg" to state.packageName,
            "title" to state.title,
            "artist" to state.artist,
            "durationMs" to state.durationMs,
            "positionMs" to state.positionMs,
            "empty" to state.isEmpty,
        )
        notifyRestored(state)
    }

    private fun notifyRestored(state: LastTrackStore.State?) {
        listeners.forEach { (it as? RestoredListener)?.onLastTrackRestored(state) }
    }

    /**
     * 把当前曲目写进「上次歌曲」存储。
     *
     * 歌名取**稳定歌名**（过滤掉「不稳定标题」——存的是 `lastTitle`
     * 见；
     * 歌手取 ARTIST；总时长取 DURATION；进度取播放位置；包名取当前会话的包名。
     *
     * @param force 换歌 / 退出时立刻写；平时按 [PERSIST_THROTTLE_MS] 节流，
     *   避免每 200ms 一次的进度 tick 把磁盘写爆
     *   （5000ms 节流）。
     */
    private fun persistTrack(t: TrackInfo, force: Boolean = false) {
        val st = store ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_THROTTLE_MS) return
        lastPersistAt = now
        val rawTitle = if (t.title == MusicSessionTracker.NO_PLAYBACK) "" else t.title
        val saved = LastTrackStore.State(
            packageName = t.pkg,
            title = MediaText.stableSongTitle(rawTitle),
            artist = t.artist,
            durationMs = t.durationMs,
            positionMs = t.positionMs,
        )
        st.save(saved)
        // 排障场景：冷启动后「上次播放」显示不对时，这里能看到到底落盘了什么
        if (force) {
            Logx.i(
                LogTags.MEDIA, "上次歌曲状态落盘（强制）",
                "pkg" to saved.packageName, "title" to saved.title, "rawTitle" to rawTitle,
                "artist" to saved.artist, "durationMs" to saved.durationMs,
                "positionMs" to saved.positionMs,
            )
        } else {
            Logx.d(
                LogTags.MEDIA, "上次歌曲状态落盘",
                "pkg" to saved.packageName, "title" to saved.title,
                "positionMs" to saved.positionMs,
            )
        }
    }

    /**
     * 是否「换歌了」（判定）
     * - 时长变化 >2s → 换歌；
     * - 「歌词型来源」（本地/蓝牙/USB）：歌手变化 → 换歌；
     * - 其他来源：mediaId 变化 → 换歌；
     * - 标题变化 → 换歌。
     */
    private fun isTrackChanged(prev: TrackInfo?, next: TrackInfo): Boolean {
        if (prev == null) return true
        if (next.title.isNotEmpty() && next.title != prev.title) return true
        val durationChanged = next.durationMs > 0 && prev.durationMs > 0 &&
            kotlin.math.abs(next.durationMs - prev.durationMs) > 2000
        if (durationChanged) return true
        val artistChanged = next.artist.isNotEmpty() && prev.artist.isNotEmpty() &&
            next.artist != prev.artist
        val mediaIdChanged = next.mediaId.isNotEmpty() && prev.mediaId.isNotEmpty() &&
            next.mediaId != prev.mediaId
        return if (LyricMatch.isLyricTitleSource(next.pkg)) artistChanged else mediaIdChanged
    }

    /**
     * 失去会话：清空曲目与歌词，歌词回到「等待播放」。
     *
     */
    private fun clearToIdle() {
        // 排障场景：界面变成「暂无播放 / 等待播放」时，这里能看到清空时机
        Logx.i(LogTags.MEDIA, "清空媒体状态（回到暂无播放）")
        track = null
        lyric = null
        lyricIndex = -1
        lyricWipe = 0f
        lyricLiveText = ""
        playerCurrentLine = ""
        lyricState = LYRIC_IDLE
        lyricNotFound = false
        lyricFetchInFlightKey = null
        lyricSourceDurationMs = 0L
        lastLyricSongKey = ""
        lastFailKey = ""
        coverBitmap = null
        coverBitmapKey = ""
        coverRequestKey = ""
        coverRequestGen++
        OnlineLyricCatalog.invalidate()
        listeners.forEach { it.onMediaChanged(null) }
        listeners.forEach { it.onLyricChanged(null, -1, 0f) }
    }

    /** 切歌：丢弃旧歌词结果，走多级回退重新取词。 */
    fun onTrackReset(t: TrackInfo) {
        val key = lyricKey(t)
        val sameSong = key == lastLyricSongKey
        if (sameSong && lyric != null && !lyric!!.isEmpty) {
            Logx.d(LogTags.MEDIA, "歌词已存在，跳过请求", "key" to key, "title" to t.title)
            return
        }
        if (sameSong && lyricFetchInFlightKey == key) {
            Logx.d(LogTags.MEDIA, "歌词请求进行中，跳过", "key" to key, "title" to t.title)
            return
        }

        lyric = null
        lyricIndex = -1
        lyricWipe = 0f
        lyricLiveText = ""
        lyricSourceDurationMs = 0L
        if (!sameSong) {
            lastLyricSongKey = key
            lyricNotFound = false
            // 换歌时**不**在这里清 `playerCurrentLine`：
            // 它是在 [onTrackChanged] 里跟着新曲目一起赋值的，
            // 清掉反而会把**新歌**自报的那一句也抹了（要等下一次推送才补回来）。
            // 换歌作废旧句这件事，由「每次推送都按新曲目重新赋值」天然保证。
        }

        // 1) 广播歌词（QQ 音乐车机版自己推的整份 LRC —— 权威性最高）
        val broadcast = broadcastLyric
        if (key == broadcastSongKey && broadcast != null) {
            lyric = broadcast
            lyricState = ""
            lyricSourceDurationMs = 0L
            Logx.i(
                LogTags.MEDIA, "使用 QQ 广播歌词",
                "key" to key, "lines" to broadcast.lines.size, "title" to t.title,
            )
            recomputeLyric()
            return
        }
        // 1.5) 播放器自供歌词（第四十一轮新增）
        //
        //  里带的 LRC（metadata 自定义 key / description extras）。
        // 这是**播放器自己那份**，跟播放的是同一首歌，比联网查更准 ——
        // 有就用它，不联网。没有这条通道，属于增强。
        val playerLrc = t.playerLyric
        if (playerLrc.isNotEmpty()) {
            val playerTimeline = LrcParser.parse(playerLrc)
            if (!playerTimeline.isEmpty) {
                lyric = playerTimeline
                lyricState = ""
                lyricNotFound = false
                lyricSourceDurationMs = 0L
                Logx.i(
                    LogTags.MEDIA, "使用播放器自供歌词",
                    "key" to key, "lines" to playerTimeline.lines.size,
                    "title" to t.title, "artist" to t.artist,
                )
                recomputeLyric()
                return
            }
        }
        // 2) 失败冷却 60s —— 只冷却「同一首歌」
        if (lyricNotFound && key == lastFailKey &&
            System.currentTimeMillis() - lastFailAt < LYRIC_FAIL_COOLDOWN_MS
        ) {
            // 排障场景：歌词一直是「纯音乐」提示时，要能看到是被冷却挡住的
            Logx.i(
                LogTags.MEDIA, "命中歌词失败冷却（按歌）",
                "key" to key, "title" to t.title,
                "remainMs" to (LYRIC_FAIL_COOLDOWN_MS - (System.currentTimeMillis() - lastFailAt)),
            )
            lyricState = LYRIC_NONE
            listeners.forEach { it.onLyricChanged(null, -1, 0f) }
            return
        }
        // 3) 在线竞速（QQ / 网易 / 酷狗并行）
        val pkg = t.pkg
        lyricState = LYRIC_LOADING
        listeners.forEach { it.onLyricChanged(null, -1, 0f) }
        lyricFetchInFlightKey = key
        // 排障场景：歌词不对 / 歌词不出来，这里能看到请求用的是哪首歌、哪个来源
        Logx.i(
            LogTags.MEDIA, "歌词请求发起",
            "title" to t.title, "artist" to t.artist, "pkg" to pkg,
            "durationMs" to t.durationMs, "key" to key,
        )
        OnlineLyricCatalog.load(t.title, t.artist, t.durationMs, pkg, t.mediaId) { result ->
            if (key != lastLyricSongKey) {
                // 过期结果丢弃（切歌后旧歌的歌词回来了）
                // 顺手清掉**自己**的在飞标记（新一轮请求用的是别的 key，不受影响）
                if (lyricFetchInFlightKey == key) lyricFetchInFlightKey = null
                Logx.d(LogTags.MEDIA, "丢弃过期歌词结果", "key" to key, "current" to lastLyricSongKey)
                return@load
            }
            if (result != null && !result.timeline.isEmpty) {
                Logx.i(
                    LogTags.MEDIA, "歌词目录命中",
                    "key" to key, "lines" to result.timeline.lines.size, "title" to t.title,
                    "provider" to result.provider, "score" to result.score,
                    "sourceDurationMs" to result.sourceDurationMs,
                )
                applyLyric(result.timeline, result.sourceDurationMs)
                return@load
            }
            Logx.i(
                LogTags.MEDIA, "歌词目录未命中",
                "key" to key, "title" to t.title, "artist" to t.artist,
            )
            // 4) 目录没命中 → 酷狗旧接口兜底
            fetchKugouFallback(t, key)
        }
    }

    /**
     * 复核：挂上去的歌词是不是这首歌**那个长度**的版本。
     *
     * 第四十轮新增（对应用户反馈「歌词和歌曲播放速度不匹配」）；
     * 第七十八轮把调用点补齐（原来只在「曲目没变」的分支里调，等于没跑过）。
     *
     * 取词那一刻如果车机会话还没给出总时长（本地音乐来源很常见），
     * 就没法判别「专辑原版」和「电台剪辑版」—— 后者的歌词时间轴更短，
     * 挂上去以后歌词会跑得比歌曲快。
     * 等真实时长到齐，这里比一次：
     *
     * - 差 ≤ 15s（[LyricCandidatePicker.DURATION_TOLERANCE_MS]）→ 同一个版本，不管；
     * - 差得太多 → **挂错版本了** → 清掉缓存重取（这次时长已知，
     *   [LyricCandidatePicker] 的时长硬过滤会挑中长度对得上的那条。
     */
    private fun revalidateLyricVersion(next: TrackInfo) {
        val source = lyricSourceDurationMs
        if (lyric == null || source <= 0) return
        if (next.durationMs <= 0) return
        val diff = kotlin.math.abs(next.durationMs - source)
        if (diff <= LyricCandidatePicker.DURATION_TOLERANCE_MS) {
            Logx.d(
                LogTags.MEDIA, "歌词版本复核通过",
                "key" to lastLyricSongKey, "sourceDurationMs" to source,
                "songDurationMs" to next.durationMs, "diffMs" to diff,
            )
            return
        }
        Logx.i(
            LogTags.MEDIA, "歌词版本复核不通过（时长对不上，重取）",
            "key" to lastLyricSongKey, "sourceDurationMs" to source,
            "songDurationMs" to next.durationMs, "diffMs" to diff,
            "title" to next.title,
        )
        OnlineLyricCatalog.evict(next.mediaId, next.title, next.artist)
        lyric = null
        lyricIndex = -1
        lyricWipe = 0f
        lyricLiveText = ""
        lyricSourceDurationMs = 0L
        onTrackReset(next)
    }

    /**
     * 酷狗旧接口兜底
     *
     *
     * 只有在线目录**没**命中（目录失败或都没匹配上）才走这条路；
     * 两条路都失败才记失败并进入 60s 按歌冷却。
     */
    private fun fetchKugouFallback(t: TrackInfo, key: String) {
        // —— 用清洗后的标题/歌手去查
        val query = LyricMatch.clean(t.title, t.artist)
        // 排障场景：目录没命中后是否走了旧接口兜底，这里能看出来
        Logx.i(
            LogTags.MEDIA, "酷狗旧接口兜底触发",
            "key" to key, "title" to query.title, "artist" to query.artist,
        )
        if (query.title.isEmpty()) {
            Logx.w(LogTags.MEDIA, "酷狗兜底放弃：清洗后歌名为空", "key" to key, "rawTitle" to t.title)
            markLyricFailed(key)
            return
        }
        KugouLyricFetcher.fetch(query.title, query.artist) { lines ->
            // 过期结果丢弃
            if (key != lastLyricSongKey) {
                // 同上：只清自己 key 的在飞标记（新一轮请求用的是别的 key，不受影响）
                if (lyricFetchInFlightKey == key) lyricFetchInFlightKey = null
                Logx.d(LogTags.MEDIA, "丢弃过期酷狗兜底结果", "key" to key)
                return@fetch
            }
            if (lines == null || lines.isEmpty() || !LyricMatch.isUsableLyric(lines)) {
                Logx.i(
                    LogTags.MEDIA, "酷狗兜底未命中",
                    "key" to key, "lines" to (lines?.size ?: 0),
                )
                markLyricFailed(key)
                return@fetch
            }
            val timeline = LyricTimeline(lines)
            Logx.i(LogTags.MEDIA, "酷狗兜底命中", "key" to key, "lines" to lines.size)
            // 兜底拿到的歌词也进共享缓存
            OnlineLyricCatalog.put(t.mediaId, query.title, query.artist, timeline)
            applyLyric(timeline, 0L)
        }
    }

    /**
     * 采纳一份歌词。
     *
     * [sourceDurationMs] = 这份歌词的**来源候选自带的时长**（0 = 不知道），
     * 供时长后到时复核版本（见 [revalidateLyricVersion]）。
     */
    private fun applyLyric(timeline: LyricTimeline, sourceDurationMs: Long) {
        lyricFetchInFlightKey = null
        lyric = timeline
        lyricState = ""
        lyricNotFound = false
        lyricSourceDurationMs = sourceDurationMs
        // 排障场景：歌词行数不对（少一半 / 重复）时，这里能看到拿到多少行
        Logx.i(
            LogTags.MEDIA, "歌词已应用",
            "key" to lastLyricSongKey, "lines" to timeline.lines.size,
            "sourceDurationMs" to sourceDurationMs,
        )
        recomputeLyric()
        // —— 立刻复核一次版本（第七十八轮新增）
        //
        // 取词那一刻歌曲时长可能还是 0（换歌瞬间车机常常先推歌名、后推时长），
        // 这时 [LyricCandidatePicker.durationTier] 判别不出「专辑原版」和
        // 「电台剪辑版」—— 两者歌名歌手都一样，只能靠「同分取更长」猜。
        //
        // 猜错了的后果不是「差一点」，而是**整首歌越走越偏**：
        // 2 分 17 秒的剪辑版配 4 分 37 秒的原曲，歌词进度会差出一大截。
        //
        // 现在歌已经挂上了、时长也到了，正是复核的时机：
        // 差太多就把这份错的丢掉重取（这次时长已知，挑选时会挑长度对得上的那条）。
        //
        // 原来的复核只在「曲目内容没变」的分支里调，
        // 而「时长从 0 变成真值」在 [isTrackChanged] 里算「变了」——
        // 于是这条复核**一次都没跑过**（真机诊断包实测：日志里「复核」0 命中）。
        val t = track
        if (t != null && t.durationMs > 0) revalidateLyricVersion(t)
    }

    /**
     * 两条路都没命中：记失败 + 进入 60s 按歌冷却。
     */
    private fun markLyricFailed(key: String) {
        lyricFetchInFlightKey = null
        lastFailAt = System.currentTimeMillis()
        lastFailKey = key
        lyricNotFound = true
        lyricState = LYRIC_NONE
        // 排障场景：歌词一直不出来时，这里能看到冷却的是哪一首歌
        Logx.i(
            LogTags.MEDIA, "歌词获取失败（进入按歌冷却）",
            "key" to key, "cooldownMs" to LYRIC_FAIL_COOLDOWN_MS,
        )
        listeners.forEach { it.onLyricChanged(null, -1, 0f) }
    }

    private fun recomputeLyric() {
        val t = track ?: return
        val timeline = lyric
        if (timeline == null || timeline.isEmpty) {
            if (lyricState.isNotEmpty()) {
                listeners.forEach { it.onLyricChanged(null, -1, 0f) }
            }
            return
        }
        val index = timeline.indexAt(t.positionMs)
        val wipe = if (index < 0) 0f else timeline.wipeFraction(t.positionMs, index, t.durationMs)
        // —— 播放器自报的当前句（第七十八轮新增）
        //
        // 「现在唱到哪一句」有两条来源：
        // 1. 联网查来的 LRC 时间轴 + 播放位置（**猜**的：版本/分段一对不上就错位）；
        // 2. 播放器自己报的那一句（**真值**：它就在播放的东西里）。
        //
        // 有第 2 条就用第 2 条 —— 口径跟 uni-launcher 一致
        // （`liveLine.isNotEmpty ? liveLine : lrc[active]`）。
        // 行号 / 擦除进度仍按 LRC 时间轴走（那是唯一有「上一句/下一句」的数据源）。
        val live = playerCurrentLine
        val liveChanged = live != lyricLiveText
        val changed = index != lyricIndex || kotlin.math.abs(wipe - lyricWipe) > 0.01f || liveChanged
        val indexChanged = index != lyricIndex
        lyricIndex = index
        lyricWipe = wipe
        lyricLiveText = live
        // 排障场景：歌词行跳得太快 / 停住不动时，这里能看到当前行与总行数。
        //
        // 第三十三轮加两个字段（**歌词不同步**的取证）：
        // - `lineMs`：当前行自己的时间戳；
        // - `offsetMs`：播放位置 − 当前行时间戳 = 「这首歌的歌词整体偏了多少」，
        //   正常应该是 0 ~ 下一行时间戳；**长期偏一个固定值 = 歌词源的时间轴不对**。
        //
        // 第七十八轮加 `live`：播放器自报的当前句（空 = 这个来源不报）。
        // 有 `live` 而 `lineText` 与它不一致 → 这首歌的歌词版本 / 分段跟实际唱的**对不上**，
        // 这是「歌词对不上」最直接的证据。
        if (indexChanged || liveChanged) {
            val line = timeline.lines.getOrNull(index)
            val lineMs = line?.timeMs ?: -1L
            val lineText = line?.text.orEmpty()
            Logx.d(
                LogTags.MEDIA, "歌词当前行",
                "index" to index, "total" to timeline.lines.size,
                "positionMs" to t.positionMs, "key" to lastLyricSongKey,
                "lineMs" to lineMs,
                "offsetMs" to (if (lineMs >= 0) t.positionMs - lineMs else -1L),
                "durationMs" to t.durationMs,
                "playing" to t.playing,
                "live" to live,
                "lineText" to lineText,
            )
        }
        if (changed) {
            val display = if (live.isNotEmpty() && index >= 0) timeline.withLineText(index, live) else timeline
            listeners.forEach { it.onLyricChanged(display, index, wipe) }
        }
    }

    // ---------------------------------------------------------------- QQ 广播

    private fun registerQqReceiver(ctx: Context) {
        val filter = IntentFilter().apply {
            addAction(QQ_LYRIC_ACTION)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // `Context.RECEIVER_EXPORTED` 是 API 33 才公开的常量，
                // 本项目 compileSdk 29（一致）编不到 —— 这里用它的值 2。
                ctx.registerReceiver(qqReceiver, filter, 2)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(qqReceiver, filter)
            }
        }
    }

    /**
     * QQ 车机歌词广播。
     *
     * 只认一个固定 extra（`QQ_LYRIC_EXTRA`）、只认 `command.method == "update_lyric"`
     * 且要求当前绑定的是 QQ 音乐 —— 旧实现「扫描所有 extra 找带 '[' 的字符串」过宽，
     * 会把无关广播当歌词，这里按收紧。
     */
    private fun handleQqLyricBroadcast(intent: Intent) {
        if (intent.action != QQ_LYRIC_ACTION) return
        val raw = intent.getStringExtra(QQ_LYRIC_EXTRA)?.takeIf { it.isNotEmpty() } ?: return
        // —— 只有绑定 QQ 音乐时才采纳广播歌词
        val boundPkg = track?.pkg.orEmpty()
        if (!QqLyric.isQqMusicPackage(boundPkg)) {
            // 排障场景：QQ 广播来了却被丢掉（当前绑定不是 QQ 音乐）
            Logx.d(
                LogTags.MEDIA, "忽略 QQ 歌词广播（当前会话不是 QQ 音乐）",
                "boundPkg" to boundPkg,
            )
            return
        }
        val lyricText = QqLyric.parseLyric(raw)
        if (lyricText.isEmpty()) {
            Logx.d(LogTags.MEDIA, "忽略 QQ 歌词广播（内容无法解析）", "boundPkg" to boundPkg)
            return
        }
        val timeline = LrcParser.parse(lyricText)
        if (timeline.isEmpty) {
            Logx.i(LogTags.MEDIA, "QQ 歌词广播解析后为空", "boundPkg" to boundPkg)
            return
        }
        broadcastLyric = timeline
        broadcastSongKey = track?.let { lyricKey(it) } ?: ""
        lyric = timeline
        lyricState = ""
        lyricNotFound = false
        Logx.i(
            LogTags.MEDIA, "收到 QQ 歌词广播",
            "boundPkg" to boundPkg, "lines" to timeline.lines.size,
        )
        recomputeLyric()
    }

    // ---------------------------------------------------------------- 取值入口

    /** 当前歌名（没有会话时是空串）。 */
    val currentTitle: String get() = track?.title.orEmpty()

    /** 当前歌手（没有会话时是空串）。 */
    val currentArtist: String get() = track?.artist.orEmpty()

    /** 当前封面（没有会话时是 null）。 */
    val currentArtwork: Bitmap? get() = track?.artwork

    /** 当前曲目时长（ms，没有会话时 0）。 */
    val currentDurationMs: Long get() = track?.durationMs ?: 0L

    // ------------------------------------------------- 上次歌曲（启动时恢复）

    /** 恢复出来的上次歌名（歌词源为空串；）。 */
    val restoredTitle: String get() = lastRestored?.title.orEmpty()

    /** 恢复出来的上次歌手。 */
    val restoredArtist: String get() = lastRestored?.artist.orEmpty()

    /** 恢复出来的上次总时长（ms）。 */
    val restoredDurationMs: Long get() = lastRestored?.durationMs ?: 0L

    /** 恢复出来的上次进度（ms）。 */
    val restoredPositionMs: Long get() = lastRestored?.positionMs ?: 0L

    /** 恢复出来的上次来源包名（来源徽标用）。 */
    val restoredSourcePkg: String get() = lastRestored?.packageName.orEmpty()

    /**
     * 恢复出来的曲目（用现有的 [TrackInfo] 结构承载；没有可恢复内容时是 null）。
     *
     * UI 层可以直接拿它渲染「上次的歌名 / 歌手 / 总时长 / 进度」。
     */
    val restoredTrack: TrackInfo? get() {
        val s = lastRestored ?: return null
        if (s.isEmpty) return null
        return TrackInfo(
            pkg = s.packageName,
            title = s.title,
            artist = s.artist,
            durationMs = s.durationMs,
            positionMs = s.positionMs,
            playing = false,
            sourceId = MediaSources.idForPackage(s.packageName),
        )
    }

    /**
     * 相对当前行的歌词文本：`-1` 上一行、`0` 当前行、`1` 下一行。
     * 没有歌词时返回空串。
     */
    fun lyricLineAt(offset: Int): String = lyric?.lineAt(lyricIndex + offset).orEmpty()

    // ---------------------------------------------------------------- controls

    fun playPause() = MusicSessionTracker.get().playPause()
    fun next() = MusicSessionTracker.get().next()
    fun previous() = MusicSessionTracker.get().previous()
    fun seekTo(positionMs: Long) = MusicSessionTracker.get().seekTo(positionMs)

    /**
     * 切来源：锁定包 → 启动该 App → **立即**释放锁。
     *
     * 9 个来源的包名解析与「未安装/未连接」提示统一走 [MediaSources]，
     * 这里只负责执行动作并把结果回给调用方（用于 Toast）。
     */
    fun selectSource(context: Context, sourceId: String): MediaSources.Pick {
        val source = MediaSources.byId(sourceId)
        if (source == null) {
            // 排障场景：来源 id 不认识（多半是菜单与映射表不一致）
            Logx.w(LogTags.MEDIA, "来源切换失败：未知来源 id", "sourceId" to sourceId)
            return MediaSources.Pick.Generic
        }
        // 用户点了哪个来源（关键交互，落盘后可复现用户操作路径）
        Logx.event(
            LogTags.MEDIA, "用户选择音乐来源",
            "sourceId" to source.id, "label" to source.label,
        )
        val isInstalled: (String) -> Boolean = { pkg ->
            runCatching {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            }.getOrDefault(false)
        }
        return when (val pick = MediaSources.pick(source, isInstalled)) {
            is MediaSources.Pick.Generic -> {
                // 通用音乐：不锁定包，跟随系统当前会话
                Logx.i(LogTags.MEDIA, "来源切换：通用音乐（跟随当前会话）", "sourceId" to source.id)
                MusicSessionTracker.get().clearPackageLock()
                pick
            }
            is MediaSources.Pick.Missing -> {
                // 排障场景：用户点了某个来源但车机上没装 / 没连上
                Logx.w(
                    LogTags.MEDIA, "来源切换失败",
                    "sourceId" to source.id, "label" to source.label,
                    "reason" to pick.message,
                )
                MusicSessionTracker.get().clearPackageLock()
                pick
            }
            is MediaSources.Pick.Launch -> {
                MusicSessionTracker.get().lockToPackage(pick.pkg)
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(pick.pkg)?.let { context.startActivity(it) }
                }.onFailure {
                    Logx.w(LogTags.MEDIA, "来源 App 启动失败", it, "pkg" to pick.pkg, "label" to source.label)
                }
                Logx.i(
                    LogTags.MEDIA, "来源切换成功",
                    "sourceId" to source.id, "label" to source.label, "pkg" to pick.pkg,
                )
                // —— 启动后立刻释放包锁（不再等 2.5s）
                MusicSessionTracker.get().clearPackageLock()
                pick
            }
        }
    }

    companion object {
        private val instance = MusicController()
        fun get(): MusicController = instance

        const val NULL_SESSION_CLEAR_MS = 600L

        const val LYRIC_IDLE = "等待播放"

        const val LYRIC_LOADING = "歌词加载中"

        const val LYRIC_NONE = "此歌曲为没有填词的纯音乐，请您欣赏"

        /** 歌词失败冷却 60s。 */
        const val LYRIC_FAIL_COOLDOWN_MS = 60_000L

        /** 歌词擦除刷新周期。 */
        const val LYRIC_TICK_MS = 16L

        /** 进度往 UI 推送的最小间隔（UI 自身 1s 刷新）。 */
        const val POSITION_PUSH_MS = 1_000L

        /**
         * 「上次歌曲」落盘的最小间隔。
         *
         * `now - lastSnapshotSavedElapsed >= 5000`
         * 才写，非强制（换歌/退出）一律 5s 一次。
         *
         * 之前我们复用了 [POSITION_PUSH_MS]（1s），写盘频率是 5 倍
         * 且 SharedPreferences 一次 会重写整个 prefs XML，
         * 5 个键逐个写 = 每秒 5 次 XML 重写，车机上就是掉帧 + 磨 flash。
         */
        const val PERSIST_THROTTLE_MS = 5_000L

        const val QQ_LYRIC_ACTION = "com.tencent.qqmusiccar.action.PLAY_COMMAND_SEND_FOR_THIRD"

        const val QQ_LYRIC_EXTRA = "com.tencent.qqmusiccar.EXTRA_COMMAND_DATA"

        fun songKey(title: String, artist: String): String =
            LyricMatch.buildSongKey(null, LyricMatch.clean(title, artist).title)

        /** 有 mediaId 用 mediaId，否则用清洗后的标题。 */
        fun lyricKey(track: TrackInfo): String =
            LyricMatch.buildSongKey(track.mediaId, LyricMatch.clean(track.title, track.artist).title)
    }
}
