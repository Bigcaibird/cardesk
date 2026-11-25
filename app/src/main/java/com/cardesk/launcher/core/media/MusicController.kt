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

    /**
     * 「收藏态 / 播放模式」的**可选**监听（第一九五轮）。
     *
     * ## 为什么单独开一个接口
     *
     * 和 [RestoredListener] 同一个理由：[Listener] 已经有实现方了
     * （`LauncherView` / `GlobalDockService`），加方法会逼着它们一起改。
     *
     * 音乐卡上的**收藏按钮**和**播放模式按钮**要靠它刷新 ——
     * 这两个状态**不是**曲目的一部分（歌没换，收藏态变了），
     * 所以不能塞进 `onMediaChanged`。
     */
    interface ActionStateListener {
        /**
         * 收藏态 / 播放模式变了。
         *
         * @param favorite 当前收藏态（true / false / null=不知道）
         * @param playMode 当前播放模式（null = 不知道）
         */
        fun onActionStateChanged(favorite: MediaActions.FavoriteState, playMode: MediaActions.PlayModeState)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val actionStateListeners = LinkedHashSet<ActionStateListener>()
    private var context: Context? = null
    private var started = false

    /** 上次歌曲状态的持久化（MMKV 那 5 个键）。 */
    private var store: LastTrackStore? = null

    /** 设置项（读「默认媒体源」用；第126轮）。 */
    private var prefs: com.cardesk.launcher.core.Prefs? = null

    // ---------------------------------------------------- QQ 广播统计（第126轮）

    /** 收到的 QQ 车机广播总条数。 */
    var qqBroadcastCount: Int = 0
        private set

    /** 其中**被采纳**的条数。 */
    var qqBroadcastAccepted: Int = 0
        private set

    /** 其中**被门挡掉**的条数。 */
    var qqBroadcastDropped: Int = 0
        private set

    /** 最近一条广播的原始内容（截断到 600 字，给诊断包看）。 */
    var lastQqBroadcastRaw: String = ""
        private set

    /**
     * QQ 音乐的「允许对外发送」开关**是不是已经开了**（第126轮）。
     *
     * 判据是**收到过广播** —— QQ 音乐一旦收到 `action=100`，
     * 就会**立刻**回一条 `update_state`（实测：
     * `setAllowSendOutside, allow=true` 后面紧跟着一条 `update_state`）。
     * 所以「收到广播」就是「门开了」的凭据，比我们自己记一个标志可靠。
     */
    var qqDoorOpened: Boolean = false
        private set

    /**
     * 已发出的 QQ 音乐方控命令条数（第126轮）。
     *
     * 原车把方向盘方控交给我们之后，按键要靠它才能作用到 QQ 音乐上；
     * 这条计数能看出「方控有没有真的落到 QQ 音乐」。
     */
    var qqControlCount: Int = 0
        private set

    /** 上次发「开门」命令的时刻（用于重试节流）。 */
    private var qqDoorSentAt = 0L

    /** 缓存的「默认媒体源」id（避免 16ms 一次的 tick 里反复读 prefs）。 */
    private var qqSourceCache: String = ""

    /**
     * 缓存的「**当前**媒体源」id（第128轮）。
     *
     * 为什么单独缓存：用户在**卡片来源行**里选 QQ 音乐，写的是
     * `design_music_source_v1`（[Prefs.musicSource]），
     * 与设置页里的「默认媒体源」（`default_music_source_v1`）**是两个不同的键**。
     * 真车诊断包实测：用户只在前者上选过 QQ 音乐，后者**压根没写过**，
     * 于是第126轮只读后者 → 采纳门永远关着、开门广播一次都没发。
     */
    private var qqCurrentCache: String = ""

    /** 上次读「默认媒体源」的时刻。 */
    private var qqSourceCheckedAt = 0L

    /** 「允许对外发送」开门命令**已发送次数**（第128轮，诊断用）。 */
    var qqDoorSentCount: Int = 0
        private set

    /**
     * 上一次的开门判定结果（`null` = 还没判过）。
     *
     * 只用来做「变化时才记日志」—— 判定每 16ms 跑一次，
     * 不去重会把车机磁盘刷爆。
     */
    private var qqDoorDecision: Boolean? = null

    /**
     * QQ 广播的 **method 计数表**（第128轮，诊断用）。
     *
     * 形如 `update_song×3, update_state×12, update_app_foreground×4`。
     *
     * 为什么不能只看「最后一条 raw」：真车诊断包里最后一条是
     * `update_app_foreground`（QQ 音乐说「我在不在前台」），
     * 它**不含歌名**，看完会误判成「QQ 音乐根本没发曲目广播」。
     * 计数表能一眼看出 QQ 到底发过哪些 method、各几条。
     */
    private val qqBroadcastMethods = LinkedHashMap<String, Int>()

    /** 上面那张计数表的可读形式（诊断包里显示）。 */
    val qqBroadcastMethodSummary: String
        get() = qqBroadcastMethods.entries.joinToString(", ") { "${it.key}×${it.value}" }

    var track: TrackInfo?
        get() = sessionTrack ?: broadcastTrack
        private set(value) {
            sessionTrack = value
        }
    /** 会话推来的曲目（权威来源）。 */
    private var sessionTrack: TrackInfo? = null

    /**
     * 从 **QQ 车机广播**合成的曲目（第126轮）。
     *
     * ## 为什么需要它
     *
     * 真车实测：QQ 音乐车机版**不发布可枚举的 MediaSession**（连续 24 分钟的
     * 会话枚举里一次都没出现过）。所以它**只能**通过广播
     * （[QqBroadcast.ACTION]）把歌名 / 歌手 / 歌词告诉我们。
     *
     * 没有它，QQ 音乐在桌面上就是「暂无播放」，歌词也永远不来。
     *
     * 优先级：**会话优先**（有会话就以会话为准），会话没有才用广播合成的。
     */
    private var broadcastTrack: TrackInfo? = null

    // ------------------------------------------------- 收藏态 / 播放模式（第一九五轮）

    /**
     * 当前**收藏态**（三态：已收藏 / 未收藏 / 不知道）。
     *
     * ## 三态为什么不能省
     *
     * QQ 音乐只在 `update_song` 那类广播里带 `key_is_fav`，
     * `update_state`（只有播放态）里**没有** →
     * 那时若当成「未收藏」，界面会在每次播放态更新时闪一下「未收藏」，
     * **用户看着像收藏状态丢了**。
     */
    var favoriteState: MediaActions.FavoriteState = MediaActions.FavoriteState.UNKNOWN
        private set

    /**
     * 当前**播放模式**（null = 不知道）。
     *
     * ## 网易云：问得出来
     *
     * 发 `"get_play_mode_action"`，它用会话事件回（见 [NeteaseMediaActions]）。
     *
     * ## QQ 音乐：问不出来
     *
     * 它的三方广播里没有播放模式字段，URI 协议也只有「设置」没有「读取」→
     * 所以 QQ 的模式只能由桌面**自己记**（记自己下发的最后一次）。
     */
    var playModeState: MediaActions.PlayModeState = MediaActions.PlayModeState.UNKNOWN
        private set

    /**
     * 写收藏态（变了才通知上层）。
     *
     * @param state 新的收藏态
     * @param optimistic true = 这是**乐观**写入（等回报的兜底），
     *   不作废挂起的乐观更新
     */
    private fun setFavoriteState(state: MediaActions.FavoriteState, optimistic: Boolean = false) {
        // 播放器的**权威回报**到了 → 挂起的乐观更新作废（以回报为准）。
        if (!optimistic) cancelPendingFavorite()
        if (favoriteState == state) return
        Logx.event(
            LogTags.MEDIA, "收藏态变化",
            "old" to (favoriteState.favorited?.toString() ?: "unknown"),
            "new" to (state.favorited?.toString() ?: "unknown"),
            "乐观" to optimistic,
        )
        favoriteState = state
        actionStateListeners.forEach { it.onActionStateChanged(favoriteState, playModeState) }
    }

    /**
     * **挂起中的乐观收藏更新**（第一九五轮）。
     *
     * ## 为什么不立即乐观更新
     *
     * 收藏按钮是**实心/空心**的心形 —— 立即乐观更新的话，
     * 界面会先跳成实心，几十毫秒后又被播放器的权威回报纠正回来 →
     * **肉眼看到一次闪烁**（看着像按钮抽风）。
     *
     * → 改成**延后乐观**：下发后先等播放器回报；
     * 回报到了就用回报值；一直没人回报（比如这个播放器不回报），
     * [OPTIMISTIC_FAVORITE_MS] 之后才落乐观值。
     *
     * 播放模式那边没有这个问题（图标是 repeat / repeat_one / shuffle，
     * 闪一下不明显，而且 QQ 根本不回报模式 → 必须立即乐观），所以它不走这里。
     */
    private var pendingFavorite: Runnable? = null

    /** 取消挂起的乐观收藏更新。 */
    private fun cancelPendingFavorite() {
        pendingFavorite?.let { main.removeCallbacks(it) }
        pendingFavorite = null
    }

    /**
     * 排一个**延后**的乐观收藏更新。
     *
     * @param target true = 乐观认为「已收藏」
     */
    private fun scheduleOptimisticFavorite(target: Boolean) {
        cancelPendingFavorite()
        val state = if (target) {
            MediaActions.FavoriteState.FAVORITED
        } else {
            MediaActions.FavoriteState.NOT_FAVORITED
        }
        val job = Runnable {
            pendingFavorite = null
            setFavoriteState(state, optimistic = true)
        }
        pendingFavorite = job
        main.postDelayed(job, OPTIMISTIC_FAVORITE_MS)
    }

    /**
     * 写播放模式（变了才通知上层）。
     *
     * @param state 新的播放模式
     */
    private fun setPlayModeState(state: MediaActions.PlayModeState) {
        if (playModeState == state) return
        playModeState = state
        actionStateListeners.forEach { it.onActionStateChanged(favoriteState, playModeState) }
    }

    /**
     * 换歌 → 收藏态复位成「**不知道**」（第一九五轮）。
     *
     * 新歌的收藏态要么由播放器重发 metadata 带来（网易云），
     * 要么由下一条 `update_song` 广播带来（QQ 音乐）。
     */
    private fun resetActionStateOnSongChange() {
        setFavoriteState(MediaActions.FavoriteState.UNKNOWN)
        // 换歌了 → 上一首歌挂起的乐观更新没有意义。
        cancelPendingFavorite()
        // 换歌了 → 上一首歌的指令目标/回滚快照一并作废（第二轮二三）：
        // onFavoriteResult 再带着旧歌的结果来，也不能写进新歌的状态。
        lastFavoriteTarget = null
        favoriteStateBeforeSend = null
        // 播放模式是**播放器级**的（不是歌曲级）→ 换歌不该清它。
    }

    /**
     * 当前会话的**运行时探测**结果（第一九六轮）。
     *
     * ## 干什么用
     *
     * 能力表（[MediaActions.capsOf]）只覆盖逆向过的三个播放器；
     * 桌面上还有一堆别的音乐 App → 靠**标准 MediaSession 通道**兜底。
     *
     * 但不能无脑乱发 → 先看这个会话**自己发布的事实**：
     *
     * - metadata 里**有**心形评分 → 它用标准收藏语义 → 可以亮收藏按钮；
     * - `PlaybackState` **暴露了**模式字段 → 它用标准模式语义 → 可以亮播放模式按钮。
     *
     * @return 探测结果；**null = 没有会话**（探不到 → 兜底一律不支持）
     */
    val actionProbe: MediaActions.Probe?
        get() {
            val c = MusicSessionTracker.get().boundController() ?: return null
            // 只认**当前曲目**那个包的会话 —— 别的 App 的会话探出来的事实
            // 跟当前播放器没关系（会话是全局的，当前绑定的未必是当前在播的）。
            val cur = track?.pkg
            if (cur != null && c.packageName != cur) return null
            val metadata = runCatching { c.metadata }.getOrNull()
            val state = runCatching { c.playbackState }.getOrNull()
            return MediaActions.Probe(
                favoriteHeart = StandardMediaActions.favoriteHeart(metadata),
                repeatMode = StandardMediaActions.repeatModeOf(state),
                shuffleMode = StandardMediaActions.shuffleModeOf(state),
            )
        }

    /**
     * 当前播放器**实际支持**哪些动作（能力表 + 运行时探测）。
     *
     * 这是界面决定「按钮亮不亮」的唯一依据。
     */
    val actionCaps: MediaActions.Caps
        get() = MediaActions.capsOf(track?.pkg, actionProbe)

    /**
     * 从**当前会话的 metadata** 里读收藏态（第一九五轮 → 第一九六轮通用化）。
     *
     * 走标准通道的播放器（网易云 / 汽水 / 其它）都把收藏态放在**心形评分**里，
     * 收藏 / 取消收藏后都会重发 metadata → 这里直接读当前控制器的那份。
     */
    private fun readFavoriteFromSession() {
        val c = MusicSessionTracker.get().boundController() ?: return
        // 同上：只认当前曲目那个包的会话
        val cur = track?.pkg
        if (cur != null && c.packageName != cur) return
        val heart = StandardMediaActions.favoriteHeart(runCatching { c.metadata }.getOrNull()) ?: return
        setFavoriteState(
            if (heart) MediaActions.FavoriteState.FAVORITED
            else MediaActions.FavoriteState.NOT_FAVORITED,
        )
    }

    /**
     * 下一次收藏指令的**目标状态**（true = 收藏）。
     *
     * 播放器回报结果时用它来校正状态
     * （见 [trackerActionListener] 的 [MusicSessionTracker.ActionStateListener.onFavoriteResult]）。
     */
    private var lastFavoriteTarget: Boolean? = null

    /** 下发收藏指令**之前**的状态（失败要回滚到它）。 */
    private var favoriteStateBeforeSend: MediaActions.FavoriteState? = null

    /**
     * 上一次**成功下发**收藏指令的时刻（第二轮二三）。
     *
     * QQ 的收藏回报走服务器推送、会迟到且乱序（实测 61 秒）——
     * 刚下发之后的短暂窗口里，与目标相反的回报是**上一次切换的回声**，
     * 不能让它把心形打回去。宽限窗长度见 [FAVORITE_ECHO_GRACE_MS]。
     */
    private var lastFavoriteSentAt = 0L

    /**
     * **收藏 / 取消收藏**当前歌曲（第一九五轮）。
     *
     * ## 三态在这里怎么用
     *
     * 状态「不知道」时，按下收藏按钮 → **收藏**（不是「取消收藏」）。
     * 理由：用户点收藏按钮的心智是「我想收藏它」，
     * 不是「我想把状态翻转一下」。
     *
     * ## 乐观更新
     *
     * 下发成功就先按目标状态更新界面，等播放器把真实状态带回来再校正
     * （网易云重发 metadata / QQ 音乐重发 `update_song`）。
     *
     * @return 是否真的下发了（false = 当前播放器不支持，或下发失败）
     */
    fun toggleFavorite(): Boolean {
        val ctx = context ?: return false
        val pkg = track?.pkg
        val caps = actionCaps
        if (!caps.favorite) {
            Logx.i(LogTags.MEDIA, "收藏：当前播放器不支持", "pkg" to (pkg ?: ""))
            return false
        }
        val target = !favoriteState.isFavorited
        lastFavoriteTarget = target
        favoriteStateBeforeSend = favoriteState
        val ok = MediaActionsDispatcher.sendFavorite(
            context = ctx,
            controller = MusicSessionTracker.get().boundController(),
            packageName = pkg,
            favorited = target,
            caps = caps,
        )
        if (ok) {
            // **不立即乐观更新** —— 等播放器的权威回报（QQ 的 `update_fav` 广播 /
            // 网易云重发的 metadata）。没人回报时才在 [OPTIMISTIC_FAVORITE_MS] 后兜底。
            lastFavoriteSentAt = System.currentTimeMillis()
            scheduleOptimisticFavorite(target)
        }
        return ok
    }

    /**
     * **切播放模式**（顺序 → 单曲 → 随机 → 顺序）（第一九五轮）。
     *
     * ## 状态未知时先落到「顺序播放」
     *
     * 见 [MediaActions.nextPlayMode]：随机播放会让用户「下一首听到的歌跟预期不一样」，
     * 在不知道当前状态时先落到最不容易出意外的档位。
     *
     * @return 是否真的下发了
     */
    fun cyclePlayMode(): Boolean {
        val ctx = context ?: return false
        val pkg = track?.pkg
        val caps = actionCaps
        if (!caps.playMode) {
            Logx.i(LogTags.MEDIA, "播放模式：当前播放器不支持", "pkg" to (pkg ?: ""))
            return false
        }
        val next = MediaActions.nextPlayMode(playModeState.mode)
        val ok = MediaActionsDispatcher.sendPlayMode(
            context = ctx,
            controller = MusicSessionTracker.get().boundController(),
            packageName = pkg,
            mode = next,
            caps = caps,
        )
        if (ok) {
            setPlayModeState(MediaActions.PlayModeState(next))
            // 网易云答得出来 → 顺口问一句，用它的答案校正我们记的值。
            MediaActionsDispatcher.requestPlayMode(ctx, MusicSessionTracker.get().boundController(), pkg)
        }
        return ok
    }

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

    /**
     * 来自**会话事件**的歌词（第127轮）。
     *
     * 网易云音乐车机版把歌词塞进 `MediaMetadata`（`android.media.metadata.LYRIC`）
     * **或**用会话事件推（`wt_music_lyric`）—— 走哪一条由车机配置决定，
     * 两条都得接（协议见 [NeteaseSessionLyric]）。
     */
    private var sessionLyric: LyricTimeline? = null

    /** 会话歌词对应的歌 key（空串 = 还没有会话歌词）。 */
    private var sessionLyricSongKey = ""

    /**
     * 收到的**会话歌词事件**条数（第127轮）。
     *
     * 真车排障时最想知道的是「网易云到底有没有往会话里推歌词」——
     * 计数为 0 就说明那台车的 `notifyLyricManner` 走的是 metadata 通道，
     * 或者压根没推。
     */
    var sessionLyricEventCount: Int = 0
        private set

    /** 最近一次会话歌词事件的行数（0 = 没收到过）。 */
    var sessionLyricLines: Int = 0
        private set

    /**
     * 最近一次会话歌词的**来源**（诊断用）。
     *
     * `netease-metadata` = 走 metadata 的 `android.media.metadata.LYRIC`；
     * `netease-session-event` = 走会话事件 `wt_music_lyric`。
     */
    var sessionLyricSource: String = ""
        private set

    /**
     * 当前显示的歌词是不是来自会话事件。
     *
     * 决定「播放器让我们清空歌词」（加载中 / 出错）时要不要把界面上那份一起清掉 ——
     * 只有本来就是它给的才清，否则会把联网查来的好歌词误伤。
     */
    private var lyricFromSession = false
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

    /**
     * 连续收到多少次「没有会话」的通知（第202轮）。
     *
     * 用来判断会话是不是**真的**没了：追一次 null 可能只是重扫的空档，
     * 连续两次（约 5s）就是真没了。
     */
    private var consecutiveNullNotifies = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            recomputeLyric()
            // 第126轮：开门重试（只在「默认源是 QQ 且门还没开」时才真的发广播）
            maybeOpenQqDoor()
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

    private val retainedClients = linkedSetOf<String>()

    /**
     * 按客户端标识（如 MainActivity / GlobalDockService / ClusterLyricsService）引用计数启动媒体总控。
     * 避免单个 Activity 销毁时误停同进程常驻服务的媒体监听。
     */
    fun retain(context: Context, clientTag: String) {
        synchronized(retainedClients) {
            retainedClients.add(clientTag)
        }
        start(context)
    }

    /** 释放客户端引用；仅当无任何活跃客户端时才停止媒体总控。 */
    fun release(clientTag: String) {
        val shouldStop = synchronized(retainedClients) {
            retainedClients.remove(clientTag)
            retainedClients.isEmpty()
        }
        if (shouldStop) stop()
    }

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        this.context = app
        started = true
        // 上次歌曲状态：启动时恢复
        store = LastTrackStore(app)
        // 第126轮：读「默认媒体源」—— QQ 广播的采纳条件之一。
        runCatching { prefs = com.cardesk.launcher.core.Prefs(app) }
        restoreLastSongState()
        MusicSessionTracker.get().addListener(trackerListener)
        MusicSessionTracker.get().addActionStateListener(trackerActionListener)
        MusicSessionTracker.get().start(context)
        // 第126轮：把「广播来源的方控」落点交给 tracker ——
        // 原车把方向盘方控交给我们之后，按键要能真的作用到 QQ 音乐上
        //（它没有可用会话，`transportControls` 发过去没人接）。
        MusicSessionTracker.get().broadcastControl = { action -> sendQqControl(action) }
        registerQqReceiver(app)
        // 第126轮：默认源是 QQ 音乐的话，先把「允许对外发送」这扇门打开 ——
        // 不开门，QQ 音乐一个字节都不会往外广播（歌名、歌词全都没有）。
        maybeOpenQqDoor(force = true)
        tickerRunning = true
        main.post(ticker)
        Logx.i(
            LogTags.MEDIA, "媒体总控启动",
            "lyricTickMs" to LYRIC_TICK_MS,
            "failCooldownMs" to LYRIC_FAIL_COOLDOWN_MS,
        )
    }

    fun stop() {
        synchronized(retainedClients) { retainedClients.clear() }
        // —— 退出前把当前状态落盘
        track?.let { persistTrack(it, force = true) }
        started = false
        tickerRunning = false
        Logx.i(LogTags.MEDIA, "媒体总控停止", "title" to (track?.title ?: ""))
        main.removeCallbacksAndMessages(null)
        MusicSessionTracker.get().removeListener(trackerListener)
        MusicSessionTracker.get().removeActionStateListener(trackerActionListener)
        MusicSessionTracker.get().stop()
        // 第126轮：广播来源的方控落点也要一起摘掉。
        MusicSessionTracker.get().broadcastControl = null
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

    // ---------------------------------------------- 收藏态 / 播放模式（第一九五轮）

    /** 注册「收藏态 / 播放模式」监听（可选，见 [ActionStateListener]）。 */
    fun addActionStateListener(l: ActionStateListener) {
        actionStateListeners.add(l)
        l.onActionStateChanged(favoriteState, playModeState)
    }

    /** 注销「收藏态 / 播放模式」监听。 */
    fun removeActionStateListener(l: ActionStateListener) {
        actionStateListeners.remove(l)
    }

    // ---------------------------------------------------------------- tracker

    private val trackerListener = object : MusicSessionTracker.Listener, MusicSessionTracker.SessionLyricListener {
        /**
         * 会话推来了歌词（第127轮：网易云 `wt_music_lyric`）。
         */
        override fun onSessionLyric(pkg: String, lrc: String, lines: List<String>, cleared: Boolean) {
            handleSessionLyric(pkg, lrc, lines, cleared)
        }

        override fun onTrackChanged(t: TrackInfo?) {
            if (t == null) {
                consecutiveNullNotifies++
                // —— 会话消失后延迟 NULL_SESSION_CLEAR_MS 再清空
                // 排障场景：界面突然变成「暂无播放」时，这里能看到会话是被谁收回的
                //
                // 第202轮：追踪器是 2.5s 一轮，而延迟是 3s —— 只要它**持续**发 null，
                // 这颗延迟 Runnable 就永远被重排，清空**从来不会发生**：
                // 之前绑定的那个会话曲目会一直挂在卡片上（选定来源被僵尸会话占住时，
                // 广播那份也被它挡着）。所以这里加两条立即清空的出口：
                // - 广播那份还在（QQ 音乐车机版就靠它）：会话该让位就让位；
                // - 连续两次 null（约 5s）：会话确实没有了，别再挂着旧曲目。
                val hasBroadcast = broadcastTrack != null
                val needClear = sessionTrack != null &&
                    (hasBroadcast || consecutiveNullNotifies >= NULL_CLEAR_AFTER_NOTIFIES)
                if (needClear) {
                    Logx.i(
                        LogTags.MEDIA, "会话让位（立即清空）",
                        "broadcast" to hasBroadcast,
                        "nullNotifies" to consecutiveNullNotifies,
                    )
                    nullClearGeneration++
                    main.removeCallbacks(nullClearRunnable)
                    clearToIdle()
                    return
                }
                Logx.i(LogTags.MEDIA, "会话消失（延迟清空）", "delayMs" to NULL_SESSION_CLEAR_MS)
                nullClearGeneration++
                pendingClearGeneration = nullClearGeneration
                main.removeCallbacks(nullClearRunnable)
                main.postDelayed(nullClearRunnable, NULL_SESSION_CLEAR_MS)
                return
            }
            consecutiveNullNotifies = 0
            nullClearGeneration++
            main.removeCallbacks(nullClearRunnable)
            val prev = track
            val changed = isTrackChanged(track, t)
            // 第126轮：**会话优先** —— 真的有会话了，就把广播合成的曲目让位。
            //
            // QQ 音乐车机版那种「只有广播、没有会话」的来源，全靠广播撑着；
            // 一旦有别的 App（爱趣听 / 网易云…）真的接管了会话，
            // 广播那份就必须让位，否则仪表上会一直挂着上一首 QQ 音乐的歌。
            if (broadcastTrack != null) {
                broadcastTrack = null
                OfficialMediaBridge.clearBroadcast()
                Logx.i(LogTags.MEDIA, "会话接管，放弃 QQ 广播曲目", "pkg" to t.pkg)
            }
            // 会话推来的 metadata 里没有 bitmap 时，用已经取到的封面补回去 ——
            // 否则会话每 200ms~2.5s 推一次 metadata，会把刚取到的封面又抹掉。
            val merged = mergeCachedCover(t)
            track = merged
            listeners.forEach { it.onMediaChanged(merged) }
            // 第134轮：会话**自带**位图时也要推给桥（会话没给 Uri 的话，
            // 桥只能靠我们这份位图来给仪表出图）。
            pushArtworkToBridge(merged)
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
                // 第一九五轮：换歌 → 收藏态复位成「不知道」。
                //
                // 新歌的收藏态由播放器自己带回来：
                // 网易云重发 metadata（心形评分），QQ 音乐重发 `update_song`（`key_is_fav`）。
                resetActionStateOnSongChange()
                readFavoriteFromSession()
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

    /**
     * 会话侧的**收藏态 / 播放模式**监听（第一九五轮 → 第一九六轮通用化）。
     *
     * ## 这条通道是谁
     *
     * [MusicSessionTracker] 从**会话元数据**里读**心形评分**（= 收藏态），
     * 从**会话事件**里读网易云答回来的播放模式。
     *
     * ## 收藏态为什么不再只认网易云（第一九六轮）
     *
     * 汽水（`com.luna.music.car`）也把收藏态放在**心形评分**里
     * （逆向：`CoreRemoteControlListener.onSetRating` → `handleCollectOrUnCollectCommand`），
     * 而且它收藏完会重发 metadata → **同一套读法**就能拿到。
     *
     * ## 但要分清「是谁的收藏态」
     *
     * 别的 App 的会话事件也会走到这里 →
     * 只有**当前曲目那个包**的状态才认（`pkg` 对不上就丢）。
     */
    private val trackerActionListener = object : MusicSessionTracker.ActionStateListener {
        override fun onActionStateChanged(favoriteHeart: Boolean?, playModeCode: Int?, pkg: String?) {
            // 只认**当前**播放器的会话 —— 别的 App 的会话事件也会走到这里。
            val cur = track?.pkg
            // 收藏态：**标准通道**通用（网易云 / 汽水 / 其它未适配的播放器）
            if (favoriteHeart != null && pkg != null && pkg == cur) {
                setFavoriteState(
                    if (favoriteHeart) MediaActions.FavoriteState.FAVORITED
                    else MediaActions.FavoriteState.NOT_FAVORITED,
                )
            }
            // 播放模式编码是**网易云口径**（1/2/3），只有网易云会回
            if (playModeCode != null && pkg == MediaButtonTransport.NETEASE_PACKAGE) {
                val mode = MediaActions.playModeFromNeteaseCode(playModeCode)
                if (mode != null) setPlayModeState(MediaActions.PlayModeState(mode))
            }
        }

        /**
         * 播放器回报了「收藏指令的结果」（第一九六轮）。
         *
         * ## 为什么要校正
         *
         * 网易云进程内缓存的收藏态，和它发出来的 metadata **可能不一致**
         * （缓存 true、metadata false）→ 桌面按 metadata 判「未收藏」，
         * 再发一次收藏指令，网易云回「已经是收藏状态了」→ **什么都不会变**，
         * 界面却停在「未收藏」。
         *
         * → 听到回报就**校正**（`0` 成功 / `-2` 本来就是这样 → 都按目标态；
         * 其它 → 失败，**回滚**到下发前的状态）。
         */
        override fun onFavoriteResult(code: Int, message: String, pkg: String?) {
            if (pkg != null && pkg != track?.pkg) return
            val target = lastFavoriteTarget ?: return
            val corrected = MediaActions.favoriteStateFromResult(code, target)
            if (corrected != null) {
                setFavoriteState(corrected)
                return
            }
            // 失败 → 回到下发前的状态（别让界面停在「以为收藏了」）
            cancelPendingFavorite()
            favoriteStateBeforeSend?.let { setFavoriteState(it) }
            Logx.i(
                LogTags.MEDIA, "收藏指令失败，已回滚",
                "code" to code, "message" to message, "pkg" to (pkg ?: ""),
            )
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
     * 把封面推给原车媒体桥（第134轮）。
     *
     * ## 为什么需要这一步
     *
     * 仪表的封面走 `IMediaInfo#getArtwork()`（返回 `android.net.Uri`）。
     * 桥自己能拿到的是**会话 metadata 里的 Uri 字符串**；
     * 但下面两种情况它拿不到：
     * - 会话只给了**位图**、没给 Uri；
     * - **根本没有会话** —— QQ 音乐车机版不发布会话，它的广播里也没有图
     *   （实测 apk：广播字段只有 `key_title` / `key_artist` / `key_album` / `key_is_fav`）。
     *
     * 这两种情况下，唯一的图就是**我们自己取到的那张**
     * （会话位图，或 [CoverLoader] 搜出来的网络图）。
     *
     * 压 JPEG + 落盘 + 换 Uri 都在桥的 worker 线程上做（主线程不碰文件 IO）。
     */
    private fun pushArtworkToBridge(t: TrackInfo?) {
        val bmp = t?.artwork ?: return
        val key = BridgeArtwork.songKey(t.pkg, t.title, t.artist)
        if (key.isEmpty()) return
        OfficialMediaBridge.updateArtwork(bmp, key)
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
            // 第134轮：把封面推给原车媒体桥 → 仪表才有图。
            pushArtworkToBridge(track)
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
        // 第九十七轮：已经空过了就别再空一次。
        //
        // 诊断包里「清空媒体状态」出现 164 次、每 2.5s 一次：没有可用会话时
        // 每次 reconcile 都会发一次 null，界面被反复通知「暂无播放」，
        // 和刚恢复的歌名叠在一起就是「播放/暂停状态不断跳动」。
        if (track == null && lyric == null) return
        // 排障场景：界面变成「暂无播放 / 等待播放」时，这里能看到清空时机
        Logx.i(LogTags.MEDIA, "清空媒体状态（回到暂无播放）")
        track = null
        // ——第126轮：广播那份**不能**被会话清空带走——
        //
        // QQ 音乐车机版没有可用会话，它的歌名/歌词只能来自广播；
        // 会话追踪器每 2.5s 扫一次、每 2.5s 发一次 null → 清空每 3 秒触发一次。
        // 全清的话，卡片上的歌名和歌词就每 3 秒跳一次（第97轮修过的老毛病）。
        // 规则见 [SessionClearPolicy]。
        val keep = SessionClearPolicy.afterSessionClear(broadcastTrack, lyric != null)
        if (!keep.keepLyric) {
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
            // 第127轮：会话歌词也一起清（会话都没了，那份歌词已经没有归属）
            sessionLyric = null
            sessionLyricSongKey = ""
            lyricFromSession = false
        }
        if (!keep.keepCover) {
            coverBitmap = null
            coverBitmapKey = ""
            coverRequestKey = ""
            coverRequestGen++
            OnlineLyricCatalog.invalidate()
        }
        // 通知时给**有效曲目**（广播那份），不能给 null ——
        // 否则界面会被清成「暂无播放」，歌名每 3 秒闪一次。
        listeners.forEach { it.onMediaChanged(keep.track) }
        listeners.forEach {
            it.onLyricChanged(if (keep.keepLyric) lyric else null, lyricIndex, lyricWipe)
        }
    }

    /**
     * 收到会话事件歌词（第127轮：网易云 `wt_music_lyric`）。
     *
     * ## 这条通道解决什么
     *
     * 网易云车机版把歌词塞进 `MediaMetadata`（`android.media.metadata.LYRIC`）
     * **或**用会话事件推 —— 走哪条由**车机配置**（`notifyLyricManner`）决定，
     * 用户那台车是几我们不知道 → **两条都得接**。
     *
     * 协议细节见 [NeteaseSessionLyric]。
     *
     * ## 两种载荷分开处理
     *
     * - **整份 LRC**（`wt_music_lyric_obj`）→ 解析成时间轴，直接挂上去；
     * - **当前三行**（`get_lyric_action`）→ **没有时间戳**，只能当
     *   「播放器自报的当前句」（[playerCurrentLine]）——
     *   那是最权威的「现在唱到哪一句」，比联网查来的时间轴更准。
     */
    private fun handleSessionLyric(pkg: String, lrc: String, lines: List<String>, cleared: Boolean) {
        sessionLyricEventCount++
        sessionLyricLines = lines.size
        sessionLyricSource = if (cleared) "cleared" else "netease-session-event"
        // ① 当前句（没有时间戳）：直接当「播放器自报的当前行」
        if (lines.size >= 2) {
            val cur = lines[1].trim()
            if (cur.isNotEmpty() && cur != playerCurrentLine) {
                playerCurrentLine = cur
                Logx.i(
                    LogTags.MEDIA, "会话事件：更新当前歌词行",
                    "pkg" to pkg, "line" to cur,
                )
                recomputeLyric()
            }
        }
        // ② 播放器让我们清空歌词（加载中 / 出错）
        if (cleared) {
            sessionLyric = null
            sessionLyricSongKey = ""
            if (lyricFromSession) {
                lyric = null
                lyricIndex = -1
                lyricWipe = 0f
                lyricLiveText = ""
                lyricState = LYRIC_LOADING
                lyricFromSession = false
                Logx.i(LogTags.MEDIA, "会话事件：歌词被播放器清空", "pkg" to pkg)
                listeners.forEach { it.onLyricChanged(null, -1, 0f) }
            }
            return
        }
        // ③ 整份 LRC
        if (lrc.isEmpty()) return
        val timeline = LrcParser.parse(lrc)
        if (timeline.isEmpty) {
            Logx.i(LogTags.MEDIA, "会话事件歌词解析后为空", "pkg" to pkg, "len" to lrc.length)
            return
        }
        val t = track
        if (t == null) {
            // 还没有曲目：先存着，等曲目来了再挂上去
            sessionLyric = timeline
            sessionLyricSongKey = ""
            return
        }
        val key = lyricKey(t)
        sessionLyric = timeline
        sessionLyricSongKey = key
        Logx.i(
            LogTags.MEDIA, "收到会话事件歌词",
            "pkg" to pkg, "key" to key, "lines" to timeline.lines.size,
            "title" to t.title, "artist" to t.artist,
        )
        // 已经是当前这首歌 → 直接挂上去
        if (key == lastLyricSongKey) {
            lyric = timeline
            lyricState = ""
            lyricNotFound = false
            lyricSourceDurationMs = 0L
            lyricFromSession = true
            recomputeLyric()
        }
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
        // 1.2) 会话事件歌词（第127轮：网易云 `wt_music_lyric`）
        //
        // 网易云的歌词有两条通道：metadata 的 `android.media.metadata.LYRIC`
        // 与会话事件 `wt_music_lyric` —— 走哪条由**车机配置**决定，我们都得接。
        // 事件那份排在「播放器自供」前面：它是播放器**刚刚**推过来的那一份。
        val sess = sessionLyric
        if (sess != null && key == sessionLyricSongKey) {
            lyric = sess
            lyricState = ""
            lyricNotFound = false
            lyricSourceDurationMs = 0L
            lyricFromSession = true
            Logx.i(
                LogTags.MEDIA, "使用会话事件歌词",
                "key" to key, "lines" to sess.lines.size, "title" to t.title,
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
            // 诊断：这条歌词是**播放器自供**的（网易云走的就是 metadata 里的
            // `android.media.metadata.LYRIC`；见 [NeteaseSessionLyric]）
            sessionLyricSource = "netease-metadata"
            val playerTimeline = LrcParser.parse(playerLrc)
            sessionLyricLines = playerTimeline.lines.size
            if (!playerTimeline.isEmpty) {
                lyric = playerTimeline
                lyricState = ""
                lyricNotFound = false
                lyricSourceDurationMs = 0L
                lyricFromSession = false
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
        // 联网查来的歌词不是「会话事件歌词」——
        // 播放器让我们清空歌词时，不能把这一份一起清掉。
        lyricFromSession = false
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
     * QQ 车机广播（第126轮重写）。
     *
     * ## 旧实现错在哪
     *
     * 旧实现只认 `command.method == "update_lyric"` **且**要求「当前绑定的会话是
     * QQ 音乐」。但 QQ 音乐车机版**根本不发可用的 MediaSession** ——
     * 第126轮在模拟器上装了真的 `com.tencent.qqmusiccar` 1.9.8.22，
     * `dumpsys media_session` 实测：
     *
     * ```
     * mbr com.tencent.qqmusiccar/mbr
     *   state=PlaybackState {state=0, position=0, ..., actions=631}
     *   metadata: null
     * ```
     *
     * `metadata: null` + `state=0` + 没有 `setCallback` —— 这个会话**等于没有**：
     * 桌面绑不到它、拿不到歌名、也发不动 transport。
     *
     * 于是形成**循环死锁**：
     *
     * ```
     * QQ 音乐不发可用会话 → 桌面绑不到 QQ 音乐 → 广播被「当前不是 QQ 音乐」挡掉
     *                       → 歌词永远不来 → 歌名也拿不到
     * ```
     *
     * 模拟器上实测到的那一行就是这个死锁的现场：
     * `CarDesk/Media: 忽略 QQ 歌词广播（当前会话不是 QQ 音乐） boundPkg=`
     *
     * ## 现在的采纳条件
     *
     * 1. **当前绑定的会话就是 QQ 音乐** —— 原来就有，保持；
     * 2. **当前没有绑定任何会话**，而且**用户在设置里把默认媒体源选成了 QQ 音乐**
     *    —— 真车上的实际情况（`default_music_source_v1=qq`）。
     *
     * 条件 2 不会误伤：一旦有别的播放器在播（比如爱趣听），绑定包就不是空了，
     * 条件 2 不成立 → QQ 的广播不会盖掉正在播的歌。
     *
     * ## 采纳之后做什么
     *
     * - **歌名 / 歌手 / 时长 / 进度 / 播放态**：合成一张 [TrackInfo]（[broadcastTrack]）；
     * - **歌词**：[QqBroadcast.parse] 拿 `data.lyric` → 解析成时间轴；
     * - **原车媒体桥**：把合成的曲目喂给 [OfficialMediaBridge] ——
     *   没有这一条，原车不会把**方向盘方控**交给我们（真车现象：方控还在爱趣听上）。
     */
    private fun handleQqLyricBroadcast(intent: Intent) {
        if (intent.action != QQ_LYRIC_ACTION) return
        val raw = intent.getStringExtra(QQ_LYRIC_EXTRA)?.takeIf { it.isNotEmpty() } ?: return
        qqBroadcastCount++
        // 收到广播 = 「允许对外发送」那扇门确实开了（第126轮新增的判据）。
        qqDoorOpened = true
        // 原始内容留一份（截断）—— 下次诊断包就能看到 QQ 音乐到底发了什么字段。
        lastQqBroadcastRaw = if (raw.length > 600) raw.take(600) + "…" else raw

        // method 计数表（第128轮）：先记，解析失败也要记
        val method = QqBroadcast.methodOf(raw)
        qqBroadcastMethods[method] = (qqBroadcastMethods[method] ?: 0) + 1

        val boundPkg = sessionTrack?.pkg.orEmpty()
        if (!acceptQqBroadcast(boundPkg)) {
            qqBroadcastDropped++
            Logx.i(
                LogTags.MEDIA, "忽略 QQ 广播（当前会话不是 QQ 音乐）",
                "boundPkg" to boundPkg,
                "defaultSrc" to (prefs?.defaultMusicSource ?: ""),
                "currentSrc" to (prefs?.musicSource ?: ""),
                "method" to method,
            )
            return
        }

        val payload = QqBroadcast.parse(raw)
        if (payload == null) {
            qqBroadcastDropped++
            Logx.i(LogTags.MEDIA, "忽略 QQ 广播（内容无法解析）", "boundPkg" to boundPkg)
            return
        }
        qqBroadcastAccepted++
        Logx.d(
            LogTags.MEDIA, "采纳 QQ 广播",
            "method" to method, "module" to payload.module,
            "boundPkg" to boundPkg,
        )

        // ——歌名 / 歌手 / 进度：合成曲目（没有会话时，这是唯一的来源）——
        //
        // `update_fav` **不是播放事件**：它只回报收藏态，绝不能拿它合成/切换曲目 ——
        // 它走 TME 服务器推送、延迟可达分钟级（第二轮二三实测 61 秒），
        // 迟到的旧歌 update_fav 会把整个卡片拖回上一首。
        val isFavEvent = payload.method == "update_fav"
        if (payload.hasSong && !isFavEvent) {
            applyQqBroadcastTrack(payload)
        }

        // ——收藏态（第一九五轮）：QQ 音乐在 `update_song` 这类广播里带 `key_is_fav` ——
        //
        // 出处：`BroadcastSenderCenterForThird.putSongInfo()`
        // （`i4 = (songInfo == null || !MyFavManager.H().R(songInfo)) ? 0 : 1`）
        // → **1 = 已收藏，0 = 未收藏**。
        //
        // `update_state`（只有播放态）里**没有**这个字段 → 那时是「不知道」，
        // 不能当成「未收藏」，否则界面会在每次播放态更新时闪一下（看着像收藏丢了）。
        //
        // 放在 [applyQqBroadcastTrack] **之后**：换歌时那里会先把收藏态复位成「不知道」，
        // 这里再把**这首歌**的收藏态写进去。
        //
        // ——第二轮二三：收藏回报的**采纳判定**——
        //
        // 迟到的旧歌回报（key_title 对不上当前曲目）不能写到当前心形上；
        // 挂起指令未决时，与目标相反的回报是上一次切换的回声（推送乱序），同样丢弃。
        val fav = MediaActions.favoriteFromRaw(payload.favorite)
        if (fav.known) {
            val now = System.currentTimeMillis()
            val echoGrace = pendingFavorite != null ||
                now - lastFavoriteSentAt < FAVORITE_ECHO_GRACE_MS
            when (
                MediaActions.favoriteReportVerdict(
                    payloadTitle = payload.title,
                    currentTitle = currentTitle,
                    incomingFavorited = fav.isFavorited,
                    pendingTarget = lastFavoriteTarget,
                    echoGrace = echoGrace,
                )
            ) {
                MediaActions.FavReportVerdict.DROP_STALE_SONG ->
                    Logx.i(
                        LogTags.MEDIA, "丢弃迟到的收藏回报（歌名已换）",
                        "method" to payload.method,
                        "payloadTitle" to payload.title,
                        "currentTitle" to currentTitle,
                        "fav" to payload.favorite,
                    )
                MediaActions.FavReportVerdict.DROP_ECHO ->
                    Logx.i(
                        LogTags.MEDIA, "丢弃收藏回声（与挂起目标相反）",
                        "method" to payload.method,
                        "fav" to payload.favorite,
                        "target" to lastFavoriteTarget,
                    )
                MediaActions.FavReportVerdict.APPLY -> {
                    // 报文确认到了 → 不再需要乐观兜底（避免它之后又把旧目标写回来）。
                    cancelPendingFavorite()
                    setFavoriteState(fav)
                }
            }
        } else {
            // 没带 key_is_fav → 维持现状。
            // 注意**不要**在这里调 setFavoriteState：它会把挂起的乐观更新
            // 当成「权威回报到了」误杀（QQ 的 update_lyric/update_state 一秒一条，
            // 无脑调用 = 乐观兜底永远轮不到落地）。
        }

        // ——喂给原车媒体桥：方控要靠它才能被原车接管——
        pushBroadcastToBridge(payload)

        if (!payload.hasLyric) {
            Logx.d(
                LogTags.MEDIA, "QQ 广播（本次不含歌词）",
                "boundPkg" to boundPkg, "method" to payload.method,
                "title" to payload.title, "artist" to payload.artist,
            )
            return
        }
        val timeline = LrcParser.parse(payload.lyric)
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
            "title" to payload.title, "artist" to payload.artist,
        )
        recomputeLyric()
    }

    /**
     * 这条 QQ 广播要不要采纳（第126轮）。
     *
     * 判定规则在 [QqBroadcast.shouldAccept]（纯 Kotlin，可单测）；
     * 这里只负责把「设置里的默认媒体源」塞进去。
     */
    private fun acceptQqBroadcast(boundPkg: String): Boolean =
        QqBroadcast.shouldAccept(
            boundPkg,
            prefs?.defaultMusicSource,
            prefs?.musicSource,
        )

    /**
     * 把「允许对外发送」的门打开（第126轮）。
     *
     * ## 为什么这一步是**第一因**
     *
     * `BroadcastSenderCenterForThird` 里每个 `updateXxx()` 的第一句都是
     * `if (this.allowSendOutside)` —— **QQ 音乐默认不往外挂广播**，
     * 而这个开关只有第三方发 `action=100` 才能打开。
     *
     * 我们以前**从来没发过这一条** → QQ 音乐一个字都不往外广播 →
     * 「拿不到歌名、没有歌词」就是这么来的（不是歌词格式认错了，是**根本没收到**）。
     *
     * ## 什么时候发
     *
     * 只在**用户把默认媒体源选成 QQ 音乐**时发（这是用户的显式选择，
     * 不是我们替用户开的）；门开过一次之后就不再发（QQ 那边把开关持久化了）。
     * 没收到广播就按 [QQ_DOOR_RETRY_MS] 重试。
     */
    private fun maybeOpenQqDoor(force: Boolean = false) {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        // 默认媒体源读得便宜但也别 16ms 一次地读 —— 缓存 5 秒。
        if (force || now - qqSourceCheckedAt > QQ_SOURCE_CACHE_MS) {
            qqSourceCheckedAt = now
            qqSourceCache = runCatching { prefs?.defaultMusicSource }.getOrNull().orEmpty()
            qqCurrentCache = runCatching { prefs?.musicSource }.getOrNull().orEmpty()
        }
        // 「默认媒体源」或「当前媒体源」任一选了 QQ 音乐即可（第128轮）。
        // 真车诊断包：用户只在卡片来源行选了 QQ（design_music_source_v1=qq），
        // 设置页的 default_music_source_v1 压根没写过 → 第126轮的门永远关着，
        // 开门广播一次都没发出去 → QQ 音乐不往外挂 update_song → 我们拿不到歌名。
        if (!QqBroadcast.isQqSelected(qqSourceCache, qqCurrentCache)) {
            // 只在**判定结果变化**时记一条。
            // 这个方法每 16ms 被 ticker 调一次，无脑记日志会把车机磁盘刷爆
            // （项目里踩过：2.5s 一次的 WARN 就已经刷爆过）。
            if (qqDoorDecision != false) {
                qqDoorDecision = false
                Logx.i(
                    LogTags.MEDIA, "QQ 开门广播：用户没选 QQ 音乐，不发",
                    "defaultSrc" to qqSourceCache, "currentSrc" to qqCurrentCache,
                )
            }
            return
        }
        if (qqDoorDecision != true) {
            qqDoorDecision = true
            Logx.i(
                LogTags.MEDIA, "QQ 开门广播：已选 QQ 音乐，准备开门",
                "defaultSrc" to qqSourceCache, "currentSrc" to qqCurrentCache,
            )
        }
        if (qqDoorOpened && !force) return
        if (!force && now - qqDoorSentAt < QQ_DOOR_RETRY_MS) return
        val sent = QqBroadcast.openSendDoor(ctx)
        qqDoorSentAt = now
        qqDoorSentCount++
        Logx.i(
            LogTags.MEDIA, "请求 QQ 音乐打开「允许对外发送」",
            "sent" to sent, "doorOpened" to qqDoorOpened,
            "defaultSrc" to qqSourceCache, "currentSrc" to qqCurrentCache,
        )
    }

    /**
     * 把 QQ 音乐的传输控制命令发出去（第126轮）。
     *
     * 走 QQ 音乐自己的三方协议（`qqmusiccar://?action=20&m0=…`）——
     * 广播通道的 `action=0/2/3` 只会把 QQ 音乐的界面打开，**不会**切歌。
     */
    private fun sendQqControl(action: String): Boolean {
        val ctx = context ?: return false
        val ok = QqBroadcast.sendControl(ctx, action)
        qqControlCount++
        Logx.i(LogTags.MEDIA, "QQ 音乐方控命令", "action" to action, "ok" to ok)
        return ok
    }

    /**
     * 用户换了「默认媒体源」（第126轮）。
     *
     * 换成 QQ 音乐的话，**立刻**把「允许对外发送」的门打开 ——
     * 不开门，QQ 音乐不会往外挂任何广播（歌名、歌词、方控全都没有）。
     */
    fun onDefaultMediaSourceChanged(sourceId: String) {
        qqSourceCache = sourceId
        qqSourceCheckedAt = System.currentTimeMillis()
        if (sourceId == MediaSources.QQ_ID) maybeOpenQqDoor(force = true)
    }

    /**
     * 用户在**卡片来源行**里换了媒体源（第128轮）。
     *
     * 与设置页的「默认媒体源」是**两个不同的键**，但对「我要用 QQ 音乐」
     * 这件事来说是同一个意思 —— 开门广播要跟着它走。
     */
    fun onCurrentMediaSourceChanged(sourceId: String) {
        qqCurrentCache = sourceId
        qqSourceCheckedAt = System.currentTimeMillis()
        if (sourceId == MediaSources.QQ_ID) maybeOpenQqDoor(force = true)
    }

    /**
     * 把广播里的曲目喂给原车媒体桥（第126轮）。
     *
     * 没有这一条，桥拿不到 QQ 音乐的样本 → 不会注册到原车 →
     * **方向盘方控还在原车音源（爱趣听）上**（真车实测的现象）。
     */
    private fun pushBroadcastToBridge(payload: QqBroadcast.Payload) {
        val t = broadcastTrack ?: return
        OfficialMediaBridge.updateBroadcast(
            source = QqBroadcast.PACKAGE,
            title = t.title,
            artist = t.artist,
            album = t.album,
            positionMs = payload.positionMs,
            durationMs = if (t.durationMs > 0) t.durationMs else payload.durationMs,
            playing = payload.playing,
        )
    }

    /**
     * 把广播里的歌名 / 歌手合成成曲目。
     *
     * 只有**当前没有会话曲目**时才合成 —— 有会话的话以会话为准。
     */
    private fun applyQqBroadcastTrack(payload: QqBroadcast.Payload) {
        if (sessionTrack != null) return
        val title = payload.title
        val artist = payload.artist
        if (title.isEmpty() && artist.isEmpty()) return
        val prev = broadcastTrack
        if (prev != null && prev.title == title && prev.artist == artist &&
            prev.durationMs == payload.durationMs
        ) {
            // ——同一首歌：只更新进度 / 播放态——
            //
            // QQ 音乐的 `update_state` 是**反复**发的（每次状态变化都发一条），
            // 这里必须把 position / playing 一起更新，否则卡片上的进度条永远不动。
            val updated = prev.copy(
                positionMs = payload.positionMs,
                playing = payload.playing,
            )
            if (updated != prev) {
                broadcastTrack = updated
                listeners.forEach { it.onMediaChanged(track) }
            }
            return
        }
        val synthetic = TrackInfo(
            pkg = QqBroadcast.PACKAGE,
            title = title,
            artist = artist,
            album = payload.album,
            durationMs = payload.durationMs,
            positionMs = payload.positionMs,
            playing = payload.playing,
            sourceId = MediaSources.QQ_ID,
        )
        broadcastTrack = synthetic
        // 第一九五轮：换歌了 → 收藏态复位成「不知道」。
        //
        // 这条广播自己就带着**这首歌**的 `key_is_fav`，
        // 复位之后调用方会把它的收藏态写回来（顺序在 [handleQqLyricBroadcast] 里）。
        resetActionStateOnSongChange()
        Logx.i(
            LogTags.MEDIA, "QQ 广播合成曲目",
            "title" to title, "artist" to artist,
            "durationMs" to payload.durationMs, "method" to payload.method,
            "positionMs" to payload.positionMs, "playing" to payload.playing,
        )
        listeners.forEach { it.onMediaChanged(track) }
        requestCover(synthetic)
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

    /**
     * **幂等**设置播放态（第八十七轮）：`true` = 播放，`false` = 暂停。
     *
     * 全局 Dock 的播放键、仪表盘、方控（原车 MediaCenter 桥）都调这个，
     * 而不是各自调 [playPause] 的「切换」——「切换」在多点并发 / 连点时会互相抵消。
     *
     * 返回值 = **是否真的下发了控制命令**：已满足（界面在放、再设一次在放）、
     * 播放器不支持该动作、没有会话且不需要动作时返回 `false`。
     * 乐观 UI 由 [MusicSessionTracker] 内部统一处理，这里**不再**重复加一层。
     */
    fun setPlaying(desired: Boolean): Boolean = MusicSessionTracker.get().setPlaying(desired)

    fun next() = MusicSessionTracker.get().next()
    fun previous() = MusicSessionTracker.get().previous()
    fun seekTo(positionMs: Long) = MusicSessionTracker.get().seekTo(positionMs)

    /**
     * 设置页「桥接开关」切换后立刻应用（第八十七轮）。
     *
     * 桥的启停在 [MusicSessionTracker] 里按 `session_bridge_enabled` 判，
     * 只有桌面启动时读一次；这里让用户不用重启桌面就能生效。
     */
    fun syncBridgePreference() = MusicSessionTracker.get().syncBridgePreference()

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

        // 第九十七轮：会话短暂消失（音乐 App 暂停/缓冲时会把会话销毁几秒）不再 600ms 就清屏，
        // 否则界面会在「暂无播放」和歌名之间来回跳；3s 覆盖 2.5s 周扫的空窗。
        const val NULL_SESSION_CLEAR_MS = 3_000L

        /** 连续收到这么多次「没有会话」就直接清空（第202轮，约 5s）。 */
        const val NULL_CLEAR_AFTER_NOTIFIES = 2

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
         * 收藏的**乐观更新延迟**（第一九五轮）。
         *
         * 下发收藏指令后，等这么久还没等到播放器的权威回报，才落乐观值。
         *
         * - QQ 音乐：回报是 `update_fav` 广播，**实测 50ms 内**就到；
         * - 网易云：回报是重发的 metadata（`setRating` 之后），也就百来毫秒；
         * - 这个间隔只是**兜底**（万一某个播放器不回报），不是等待时间。
         */
        const val OPTIMISTIC_FAVORITE_MS = 600L

        /**
         * 收藏「回声宽限窗」（第二轮二三）。
         *
         * QQ 的收藏回报走服务器推送、会迟到且乱序（实测 61 秒、同一条发两遍）——
         * 刚下发收藏指令之后这个窗口内，与目标相反的回报按**上一次切换的回声**
         * 丢弃（[MediaActions.favoriteReportVerdict]）。窗口过后照常采纳
         * （比如用户直接在 QQ 音乐自己的界面上点了收藏）。
         */
        const val FAVORITE_ECHO_GRACE_MS = 5_000L

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

        /**
         * 「允许对外发送」的重试间隔（第126轮）。
         *
         * QQ 音乐没在运行 / 没装 / 版本不同协议时，开门广播会**没人接**，
         * 于是歌名、歌词、方控全都拿不到 —— 这条重试就是给这种情况兜底。
         */
        const val QQ_DOOR_RETRY_MS = 30_000L

        /** 「默认媒体源」的缓存时长（第126轮；ticker 是 16ms 一次，别每次都读 prefs）。 */
        const val QQ_SOURCE_CACHE_MS = 5_000L

        fun songKey(title: String, artist: String): String =
            LyricMatch.buildSongKey(null, LyricMatch.clean(title, artist).title)

        /** 有 mediaId 用 mediaId，否则用清洗后的标题。 */
        fun lyricKey(track: TrackInfo): String =
            LyricMatch.buildSongKey(track.mediaId, LyricMatch.clean(track.title, track.artist).title)
    }
}
