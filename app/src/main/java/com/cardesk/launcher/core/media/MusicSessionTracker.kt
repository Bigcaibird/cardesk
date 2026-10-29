package com.cardesk.launcher.core.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 媒体通知监听服务。`MusicBridgeService`：只是拿到通知访问权限
 * 真正的会话枚举走 MediaSessionManager.getActiveSessions(component)。
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        MusicSessionTracker.get().onListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        instance = null
        MusicSessionTracker.get().onListenerDisconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 通知变化常伴随切歌/播放态变化，做一次防抖重扫（periodicRescan）
        MusicSessionTracker.get().requestRescan(debounceMs = 250)
    }

    companion object {
        @Volatile
        var instance: MediaNotificationListener? = null
            private set
    }
}

/**
 * 媒体会话追踪器，沿用 `MusicSessionTracker` 的骨架：
 * - `MediaSessionManager` + `OnActiveSessionsChangedListener`；
 * - 2.5s 周期重扫（`RESCAN_MS`），重连退避 {0,300,800,2000}；
 * - 选择规则见 [SessionChooser]（播放中 > 有元数据 > 锁定包 > 最近更新）；
 * - 锁定包（切来源时 lockToPackage，选完 clear）；
 * - 对外只暴露一个「当前曲目」，并转发播放态回调。
 */
class MusicSessionTracker private constructor() {

    interface Listener {
        fun onTrackChanged(track: TrackInfo?)
        fun onPlaybackChanged(playing: Boolean, positionMs: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lockedPkg: String? = null
    private var started = false
    private val rescan = Runnable { rescanNow() }

    /**
     * 当前已连接的通知监听服务（类名集合）。
     *
     * 替换版的 system 变体注册了两个通知监听服务（自己的 + 与同名的别名）
     * 两个都被授权时系统会各绑定一次 —— 这里记下来，免得一个断开就把整体状态清掉。
     */
    private val connectedServices = LinkedHashSet<String>()

    /** 是否已经向 MediaSessionManager 注册过会话变化监听（避免重复注册抛异常）。 */
    private var attached = false

    /** 注册会话变化监听时用的组件（组件变了要重新注册）。 */
    private var attachedComponent: ComponentName? = null

    /** 系统真正绑定过的监听服务组件（被绑定 = 该组件已被系统启用）。 */
    private var connectedComponent: ComponentName? = null

    /** 上一次枚举会话是否被系统拒绝（用于只在翻转时记日志，避免刷爆车机磁盘）。 */
    private var enumerationDenied: Boolean? = null

    /** 每 RESCAN_MS 重扫一次。 */
    private val periodicRescan = object : Runnable {
        override fun run() {
            if (!started) return
            rescanNow()
            main.postDelayed(this, RESCAN_MS)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            pushProgress()
            main.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    val track: TrackInfo? get() = current
    private var current: TrackInfo? = null

    /**
     * **会话记录表**（`MusicSessionTracker.Record`）。
     *
     * 用一个 `Map<MediaSession.Token, Record>`保存每个会话的
     * 「当前元数据 / 暂停态元数据 / 播放状态 / 用户是否按过暂停 / 最后 touch 时刻」，
     * 候选选择时用的是 （暂停后会保留上一首的元数据），
     * 而不是会话此刻上报的原始元数据。
     *
     * **必须按会话 token 索引，不能按包名。**
     *
     * 真机（领克 dcy11 / Android 9）实测：同一个音乐 App（`com.tencent.wecarflow`）
     * 会**同时发布两个 **（`会话候选变化 count=3`，两个都叫 wecarflow），
     * 其中**只有一个**带完整 metadata（歌名/歌手/时长/mediaId），另一个是空的。
     *
     * 按包名索引时，「空的那个会话」会先占住坑，后面带 metadata 的会话
     * 因为 `records.containsKey(pkg)` 被跳过 —— **永远拿不到歌名**，
     * 表现就是「音乐模块完全无法获取播放状态」。
     *
     * 按 token 索引，两个会话各自成一条候选，带 metadata 的那个会被选中。
     */
    private val records = LinkedHashMap<String, Record>()

    /**
     * 当前已绑定的会话 token（`MusicSessionTracker.selectedToken`）。
     *
     * 80ms 稳定判定是**会话级**的（比较的是 与候选的 token）
     * 不是包级 —— 同一个包的两个会话之间也要能互相替换。
     */
    private var selectedTokenKey: String? = null

    /** 上次记住的音乐包（MMKV `last_music_package`）：touch 时 +1ms 加权。 */
    private var lastMusicPkg: String = ""

    /**
     * 「用户自己按过播放/暂停」的标记。
     *
     * 在车机多媒体暂停时置 `true`
     * 之后点播放会走 DMT 专用的恢复路径（`tryResumeDmtPlayback`），
     * 而不是普通的。
     */
    private var customPlayFlag = false

    /**
     * 是否处于「用户请求恢复播放」状态。
     *
     * 它是 `tryResumeDmtPlayback` 的闸门
     * 只有用户明确点了播放才允许补发按键。
     */
    private var dmtResumeRequested = false

    // ---------------------------------------------------- 暂停态快照（第六十七轮）

    /**
     * 暂停时保留的元数据（**会话之外**的一份备份）。
     *
     * ## 为什么要在会话之外再存一份
     *
     * `Record` 是按会话 token 存的。车机音乐应用暂停时经常**把会话一起销毁**
     * （我们给车机多媒体的暂停下发的是 `stop()`），于是：
     *
     * - `Record` 被移除 → 它里面的 `pausedMetadata` 一起没了；
     * - 候选清单空了 → 界面清空 → **歌名、歌词全没了**；
     * - 再按播放也没有会话可恢复 → 只能「打开音乐 App」，用户看着像「播放按钮没用」。
     *
     * 所以这里在会话之外再存一份：会话没了，歌名 / 歌词还能继续显示着，
     * 播放按钮也知道该往哪个包恢复。
     */
    private var pausedSnapshot: MediaMetadata? = null

    /** 暂停快照对应的包名（恢复播放时往这个包发指令）。 */
    private var pausedSnapshotPkg: String = ""

    /** 暂停快照的时刻（选候选时当「最近更新」用）。 */
    private var pausedSnapshotAt = 0L

    /**
     * 「唤醒上次音乐包」是否已经做过（`MusicSessionTracker.defaultWakeDone`）。
     *
     * 在监听服务断开重连时会把它复位。
     */
    private var defaultWakeDone = false

    /** 第三十七轮：唤醒音乐应用后仍未接管到会话时的重试次数。 */
    private var wakeRetryCount = 0

    /**
     * 会话记录（`MusicSessionTracker.Record`）。
     */
    private inner class Record(
        /** 会话唯一标识。 */
        val tokenKey: String,
        val pkg: String,
        val controller: MediaController,
    ) {
        /** 最近一次收到的元数据。 */
        var metadata: MediaMetadata? = controller.metadata

        /** 暂停态保留的元数据（`Record.pausedMetadata`）。 */
        var pausedMetadata: MediaMetadata? =
            if (hasMetadata(controller.metadata)) controller.metadata else null

        /** 最近一次收到的播放状态。 */
        var state: PlaybackState? = controller.playbackState

        /** 用户是否主动按过暂停（`Record.userPauseRequested`）。 */
        var userPauseRequested = false

        /** 最后一次 touch 的时刻（`Record.lastTouchElapsed`）。 */
        var lastTouchElapsed: Long = SystemClock.elapsedRealtime()

        fun canRetainPause(): Boolean = PauseRetention.canRetainPause(
            pkg = pkg,
            state = state?.state,
            userPauseRequested = userPauseRequested,
        )

        fun effectiveMetadata(): MediaMetadata? {
            if (!hasMetadata(metadata) && canRetainPause()) return pausedMetadata
            return metadata
        }

        /**
         * 元数据变化。
         *
         * ## 第六十七轮改了什么
         *
         * 原来「元数据被撤掉」时会把暂停时留的那份也一起清掉
         * （除非正好处于可保留的暂停态）。
         * 于是「恢复播放时 App 先报状态、后报元数据」的那一瞬，
         * 歌名 / 歌词会空掉。
         *
         * 现在改成：
         * - **有真值** → 覆盖暂停时留的那份，暂停意图解除；
         * - **被撤成 null** → **留着**（只要不是换歌）；
         * - **换歌了** → 那份作废。
         *
         * 什么时候「暂停时留的那份」会被用上，由 [canRetainPause] 把关
         * （真正停下来了就该清屏）。
         */
        fun updateMetadata(m: MediaMetadata?) {
            if (hasMetadata(m)) {
                pausedMetadata = m
                // 有真值了 → 不再需要「暂停态保留」，暂停意图可以解除
                userPauseRequested = false
            } else if (identityChanged(pausedMetadata, m)) {
                // 换歌了 → 暂停时留的那份作废
                pausedMetadata = null
                userPauseRequested = false
            }
            metadata = m
        }

        fun updateState(s: PlaybackState?) {
            state = s
            val r = PauseRetention.onStateChanged(
                state = s?.state,
                userPauseRequested = userPauseRequested,
                hasMetadata = hasMetadata(metadata),
                hasPausedMetadata = pausedMetadata != null,
            )
            if (!r.keepPausedMetadata) pausedMetadata = null
            userPauseRequested = r.userPauseRequested
        }

        fun touch() {
            lastTouchElapsed = SystemClock.elapsedRealtime()
        }

        /**
         * 这个会话的回调（`MusicSessionTracker.C06122`）。
         *
         * 给**每一个**会话都注册回调（不只是当前选中的那个）
         * 因为候选选择要用各会话自己的 state / effectiveMetadata。
         */
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                //
                val s = controller.playbackState
                if (s != null && !isActiveState(s)) updateState(s)
                updateMetadata(metadata)
                touch()
                selectAndNotify("metadata")
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                //
                updateState(state)
                touch()
                selectAndNotify("playback")
            }

            override fun onSessionDestroyed() {
                // —— 按 **token** 销记录
                // 不是按包名（同一个包可能有多个会话，销错会连累另一个）。
                records.remove(tokenKey)
                selectAndNotify("session-destroyed")
                requestRescan(debounceMs = 0)
            }
        }
    }

    /** 乐观 UI 的播放意图窗口。 */
    private var userTransportIntentPlaying: Boolean? = null
    private var userTransportIntentUntil = 0L

    /** 进度外推用的锚点（`MusicManager.lastPlaybackPosition/lastPositionUpdateTime`）。 */
    private var lastObservedPosition = 0L
    private var lastObservedPlaying = false
    private var lastPositionAt = 0L

    /**
     * 播放位置解析器（第三十三轮：抽成纯逻辑 `PlaybackPositionTracker`）。
     *
     * 之前这段是内联在 [pushProgress] 里的，会话**没给位置**（`position == -1`）时会算错。
     */
    private val positionTracker = PlaybackPositionTracker()

    /**
     * 播放停滞检测的锚点（`MusicManager.lastObservedMediaPos/lastObservedMediaPosAt`）。
     *
     * 播放状态报的是 PLAYING、但进度 2.5 秒内没前进超过 200ms，就当成「其实没在放」。
     */
    private var lastObservedMediaPos = -1L
    private var lastObservedMediaPosAt = 0L

    /** 2.5s 周期重扫的累计次数（只用于日志，便于判断「会话是不是一直没被发现」）。 */
    private var rescanCount = 0L

    /** 上一次的会话候选清单签名：只在清单变化时打 INFO，避免 2.5s 一次刷屏。 */
    private var lastCandidateSig = ""

    /** 用户主动点了「下一首」：用来把「用户切歌」和「自动切到下一曲」区分开。 */
    private var userSkipPending = false

    /** 停滞是否已经报过（避免每 200ms 重复打同一条 INFO）。 */
    private var stallReported = false

    /** 上一次对外播报的播放态（用于只在播放/暂停切换时打日志）。 */
    private var lastReportedPlaying: Boolean? = null

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onTrackChanged(current)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val ctx = appContext
        val granted = ctx != null && NotificationAccess.isGranted(ctx)
        val mcc = ctx != null && NotificationAccess.hasMediaContentControl(ctx)
        val enumerable = ctx != null && NotificationAccess.sessionEnumerationAvailable(ctx)
        // 排障场景：车机上音乐接管不到时，先确认追踪器本身有没有正常起来
        Logx.i(
            LogTags.MEDIA, "媒体会话追踪启动",
            "rescanMs" to RESCAN_MS, "tickMs" to PROGRESS_TICK_MS,
            "manager" to (manager != null),
            "notifyAccess" to granted,
            "mediaContentControl" to mcc,
            "enumeration" to (if (enumerable) "可用" else "不可用"),
            "notifyComponents" to (ctx?.let { NotificationAccess.candidateComponents(it) } ?: emptyList<String>()),
        )
        if (!enumerable) {
            // 排障场景：两条通路都没有时，系统会拒绝 getActiveSessions()
            // （我们兜成空列表），光看「扫不到会话」完全分不清是没授权还是没音乐在播。
            Logx.w(
                LogTags.MEDIA, "媒体会话枚举不可用：既无通知使用权也无 MEDIA_CONTENT_CONTROL（音乐区将显示占位）",
                "pkg" to (ctx?.packageName ?: ""),
            )
        } else if (!granted) {
            // 有 MEDIA_CONTENT_CONTROL 兜底，枚举不受影响；但通知监听器没绑上，
            // 少了「通知变化即刻刷新」这条路（靠 2.5s 周期重扫顶）。
            Logx.i(
                LogTags.MEDIA, "未授予通知使用权，但已持有 MEDIA_CONTENT_CONTROL：会话枚举不受影响",
                "pkg" to (ctx?.packageName ?: ""),
            )
        }
        main.postDelayed(this::tryAttach, 0)
        main.postDelayed(ticker, PROGRESS_TICK_MS)
        // MusicSessionTracker:461-462 —— 2.5s 周期重扫
        main.removeCallbacks(periodicRescan)
        main.postDelayed(periodicRescan, RESCAN_MS)
    }

    fun stop() {
        started = false
        attached = false
        attachedComponent = null
        connectedComponent = null
        connectedServices.clear()
        Logx.i(LogTags.MEDIA, "媒体会话追踪停止", "rescanCount" to rescanCount)
        main.removeCallbacksAndMessages(null)
        runCatching {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        }
        // 会话记录一起清掉
        for (rec in records.values) {
            runCatching { rec.controller.unregisterCallback(rec.callback) }
        }
        records.clear()
        releaseController()
    }

    // ---------------------------------------------------------------- attach

    private fun tryAttach(retryIndex: Int = 0) {
        if (!started) return
        val msm = manager ?: return
        val component = componentName() ?: return
        if (attached) {
            // 已经注册过、而且用的还是同一个组件 → 什么都不用做
            if (attachedComponent == component) return
            // 组件变了（通常是「刚启动时还没绑定上来」→ 后来系统绑定了真正被授权的那个）：
            // 先摘掉旧注册，再按新组件重新注册。
            // 注意 AOSP 的 addOnActiveSessionsChangedListener 对同一个 listener
            // 重复 add 是「打条 warn 就忽略」，不是抛异常 —— 不摘干净的话新组件不会生效。
            runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
            attached = false
            Logx.i(
                LogTags.MEDIA, "会话监听换用新组件重新注册",
                "from" to (attachedComponent?.flattenToString() ?: ""),
                "to" to component.flattenToString(),
            )
        }
        val ok = runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            true
        }.getOrDefault(false)
        if (!ok) {
            // 排障场景：会话监听挂不上 → 永远扫不到音乐 App 的会话
            Logx.w(LogTags.MEDIA, "会话监听注册失败（将退避重试）", "retry" to retryIndex)
            val backoff = longArrayOf(0, 300, 800, 2000)
            if (retryIndex < backoff.size) {
                main.postDelayed({ tryAttach(retryIndex + 1) }, backoff[retryIndex])
            }
            return
        }
        attached = true
        attachedComponent = component
        val granted = appContext?.let { NotificationAccess.isGranted(it) } ?: false
        Logx.i(
            LogTags.MEDIA, "会话监听注册成功",
            "component" to component.flattenToString(),
            "notifyAccess" to granted,
        )
        rescanNow()
    }

    /**
     * 通知监听服务被系统绑定 = 系统确实把通知使用权给了我们。
     *
     * AOSP 只有把本应用写进 `enabled_notification_listeners` 才会绑定这个服务，
     * 所以「服务连上了」比「白名单串里有我们的组件」更权威 ——
     * 有些车机 ROM 是按**包级**授权的，白名单串里根本没有我们的组件名。
     */
    fun onListenerConnected(service: NotificationListenerService) {
        connectedServices.add(service.javaClass.name)
        // 系统绑定了哪个服务，就说明那个组件是被系统启用的监听器 ——
        // 枚举会话时必须传它（见 componentName() 的说明）
        connectedComponent = ComponentName(service.packageName, service.javaClass.name)
        NotificationAccess.markListenerConnected(true)
        // 监听重新接上 = 新的唤醒机会，重试计数归零。
        wakeRetryCount = 0
        Logx.i(
            LogTags.MEDIA, "通知监听服务已连接（系统已授予通知使用权）",
            "service" to service.javaClass.name,
            "services" to connectedServices.size,
            "useComponent" to connectedComponent?.flattenToString(),
        )
        if (started) {
            tryAttach()
            main.removeCallbacks(periodicRescan)
            main.postDelayed(periodicRescan, RESCAN_MS)
            requestRescan(debounceMs = 0)
        }
        // 第三十七轮：**唤醒不再被 `started` 挡住**。
        //
        // 在监听服务连上后
        // 是**无条件**走一遍 `maybeWakeDefaultMusic` 的
        // （还带 0/300/800/2000ms 四次重试）。
        // 之前我们把唤醒整段放在 `if (started)` 里 ——
        // 真机「开机自启播放」恰好就是这个时序：系统绑定通知监听服务发生在
        //  之前，于是唤醒指令**一次都没发出去**，
        // 车机多媒体那个会话既没有 metadata 也不是 active（诊断包里
        // `meta=false active=false play=false`，桌面就一直「暂无播放」。
        maybeWakeDefaultMusic("listener-connected")
    }

    /**
     * 唤醒上次播过的音乐应用。
     *
     * 逻辑
     * - 只做一次（`defaultWakeDone`）；
     * - 已经选中了会话就不做；
     * - 记住的包是 mediacenter 就不做（那是车机媒体中心，不是真正的音乐 app）；
     * - 否则发一路**定向** `MEDIA_BUTTON`（126 = KEYCODE_MEDIA_PLAY），
     *   音乐应用收到后会重新发布，桌面就能接管到。
     */
    private fun maybeWakeDefaultMusic(reason: String) {
        if (defaultWakeDone) return
        if (controller != null) {
            defaultWakeDone = true
            return
        }
        val pkg = rememberedMusicPkg()
        if (pkg.isEmpty() || SessionChooser.isMediaCenter(pkg)) return
        defaultWakeDone = true
        Logx.i(LogTags.MEDIA, "唤醒上次播放的音乐应用", "pkg" to pkg, "reason" to reason)
        sendOrderedMediaButton(pkg, KeyEvent.KEYCODE_MEDIA_PLAY)
        // 第三十七轮：再补一路**通用**媒体键。
        //
        // 定向广播只送到目标包的 MediaButtonReceiver；这台车机上 `com.tencent.wecarflow`
        // 不一定收那路广播（诊断包里唤醒后依然 meta=false）。
        // 通用按键由系统路由给**当前活跃会话** —— 开机那一刻唯一的会话就是它，
        // 比定向广播更可能真的把音乐叫醒（我们自己的 `tryResumeDmtPlayback`
        // 在恢复播放时也是两路一起发。
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "唤醒上次音乐应用")
        // 在 +400ms / +1200ms 各刷一次；这里多刷几轮
        // 覆盖「车机多媒体比桌面晚起来」的自启播放场景。
        for (delay in WAKE_REFRESH_DELAYS_MS) {
            main.postDelayed({ requestRescan(debounceMs = 0) }, delay)
        }
        // 唤醒后还没接管到会话 → 隔一会儿再叫一次（最多 WAKE_RETRY_MAX 次）。
        main.postDelayed({
            if (controller == null && wakeRetryCount < WAKE_RETRY_MAX) {
                wakeRetryCount++
                defaultWakeDone = false
                Logx.i(
                    LogTags.MEDIA, "唤醒音乐应用后仍未接管到会话，再叫一次",
                    "pkg" to pkg, "retry" to wakeRetryCount,
                )
                maybeWakeDefaultMusic("wake-retry-$wakeRetryCount")
            }
        }, WAKE_RETRY_MS)
    }

    /** 记住的「上次音乐包」（MMKV）。 */
    private fun rememberedMusicPkg(): String {
        if (lastMusicPkg.isNotEmpty()) return lastMusicPkg
        val ctx = appContext ?: return ""
        return runCatching {
            LastTrackStore(ctx).load().packageName
        }.getOrDefault("")
    }


    fun onListenerDisconnected(service: NotificationListenerService?) {
        val name = service?.javaClass?.name
        if (name != null) connectedServices.remove(name) else connectedServices.clear()
        NotificationAccess.markListenerConnected(connectedServices.isNotEmpty())
        if (connectedServices.isEmpty()) {
            // 全部断开：那个组件不再算「被启用」，枚举要换回白名单里命中的组件
            connectedComponent = null
        }
        Logx.w(
            LogTags.MEDIA, "通知监听服务已断开（通知使用权可能被撤销）",
            "service" to (name ?: ""),
            "remaining" to connectedServices.size,
        )
        // —— 监听断开后允许重新唤醒
        if (connectedServices.isEmpty()) {
            defaultWakeDone = false
            wakeRetryCount = 0
        }
        // 还有别的服务连着就不清空当前会话
        if (connectedServices.isEmpty()) releaseController()
    }

    fun requestRescan(debounceMs: Long) {
        main.removeCallbacks(rescan)
        main.postDelayed(rescan, debounceMs)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        reconcile(controllers ?: emptyList())
    }

    // ---------------------------------------------------------------- state

    fun lockToPackage(pkg: String) {
        lockedPkg = pkg
        Logx.d(LogTags.MEDIA, "锁定来源包", "pkg" to pkg)
        // `refreshInternal("source-lock")`
        // 而 是**同步**调 `reconcile(getActiveSessions(...))` 的。
        // 这里之前用的是 `requestRescan(0)`（postDelayed 异步）——
        // 调用方紧接着就 两次 rescan 都在锁被清掉之后才跑，
        // **锁定完全没生效**：切来源时那次「按锁定包选中」的 reconcile 从来没发生过，
        // 只能等目标 App 自己发布带元数据的会话（车机上实测 45 秒）。
        // 改成同步扫描，时序。
        rescanNow()
    }

    fun clearPackageLock() {
        lockedPkg = null
        Logx.d(LogTags.MEDIA, "释放来源包锁定")
        // `refreshInternal("source-auto")`（同步）
        rescanNow()
    }

    fun isLockedTo(pkg: String?): Boolean = !pkg.isNullOrEmpty() && pkg == lockedPkg

    /** 通知访问权限是否已授予（MusicBridgeService 的启用判断）。 */
    fun hasNotificationAccess(context: Context): Boolean = NotificationAccess.isGranted(context)

    // ---------------------------------------------------------------- scanning

    /**
     * 要传给 的组件。
     *
     * AOSP 的 `MediaSessionManager.getActiveSessions()` 文档写明
     * 「you must pass the ComponentName of your **enabled** listener」——
     * 权限校验是按组件**精确判定**的：传一个没被授权的组件进去，
     * 服务端会抛 `SecurityException("Missing permission to control media.")`。
     *
     * 所以这里不能固定用自己的主服务：车机出厂预置的白名单指向的是
     * **与同名的那个组件**（`com.ecarx.qq.tool.service.MusicBridgeService`）
     * 那个条目才是真正被系统启用的监听器。优先级：
     *
     * 1. 系统**实际绑定过**的监听服务（最权威：被绑定 = 已被启用）；
     * 2. 系统白名单里命中的候选组件；
     * 3. 自己的主服务（兜底）。
     */
    private fun componentName(): ComponentName? {
        val ctx = appContext ?: return null
        connectedComponent?.let { return it }
        NotificationAccess.grantedComponent(ctx)?.let { return it }
        return ComponentName(ctx, MediaNotificationListener::class.java)
    }

    private fun rescanNow() {
        val ctx = appContext ?: return
        val msm = manager ?: return
        val component = componentName() ?: return
        val result = runCatching { msm.getActiveSessions(component) ?: emptyList() }
        val controllers = result.getOrDefault(emptyList())
        rescanCount++
        // 排障场景：会话枚举被系统拒绝（SecurityException）和「当前确实没音乐在播」
        // 表现都是空列表，必须分开 —— 否则根本看不出是没授权还是没歌。
        // AOSP MediaSessionService.enforceMediaPermissions() 在拿不到
        // MEDIA_CONTENT_CONTROL、且传入组件不是已启用的通知监听器时会抛这个异常。
        // 只在「翻转」时记一次：WARN 是落盘的，2.5s 一次会把车机磁盘刷爆。
        // 注意首次扫描不算「翻转」—— 否则一启动就会打一条「恢复正常」。
        val denied = result.exceptionOrNull() is SecurityException
        val prev = enumerationDenied
        enumerationDenied = denied
        if (denied && prev != true) {
            Logx.w(
                LogTags.MEDIA, "会话枚举被系统拒绝（组件未获通知使用权或缺 MEDIA_CONTENT_CONTROL）",
                "component" to component.flattenToString(),
                "error" to (result.exceptionOrNull()?.message ?: "SecurityException"),
            )
        } else if (!denied && prev == true) {
            Logx.i(LogTags.MEDIA, "会话枚举恢复正常")
        }
        // 排障场景：确认 2.5s 周期重扫确实在跑（若计数不涨，说明扫描线程被卡住）
        Logx.d(LogTags.MEDIA, "周期重扫", "count" to rescanCount, "sessions" to controllers.size)
        reconcile(controllers)
    }

    private fun reconcile(controllers: List<MediaController>) {
        val now = SystemClock.elapsedRealtime()
        val selfPkg = appContext?.packageName
        // MusicSessionTracker.reconcile:509-523 —— 过滤不可用包名
        val usable = controllers.filter { SessionChooser.isUsablePackage(it.packageName, selfPkg) }
        // 排障场景：车机上「音乐没接管」时，要看出系统里到底有哪些会话、哪些被过滤掉了
        logCandidates(controllers, usable, selfPkg)
        // ——reconcile:515-537：见不到的会话建记录，消失的会话销记录 ——
        // **按会话 token 存活判定**（用 `HashSet<MediaSession.Token>`）
        // 不能按包名 —— 同一个包的多个会话要各自活着。
        val live = LinkedHashSet<String>()
        for (c in usable) {
            val pkg = c.packageName ?: continue
            val tokenKey = c.tokenKey()
            live.add(tokenKey)
            if (records.containsKey(tokenKey)) continue
            val rec = Record(tokenKey, pkg, c)
            rec.touch()
            records[tokenKey] = rec
            runCatching { c.registerCallback(rec.callback, main) }
                .onFailure { Logx.w(LogTags.MEDIA, "注册会话回调失败", it, "pkg" to pkg) }
            // 排障关键行：真车上同一个 App 会发两个会话，其中一个 metadata 是空的。
            // 把会话标识 + metadata 关键字段一起打出来，才能一眼看出「到底哪个会话有歌」。
            Logx.i(
                LogTags.MEDIA, "新增会话记录",
                "pkg" to pkg, "token" to tokenKey,
                "hasMeta" to hasMetadata(c.metadata),
                "state" to (c.playbackState?.state ?: -1),
                "title" to metaSnapshot(c.metadata).title,
                "displayTitle" to metaSnapshot(c.metadata).displayTitle,
                "artist" to metaSnapshot(c.metadata).artist,
                "mediaId" to metaSnapshot(c.metadata).mediaId,
                "durationMs" to metaSnapshot(c.metadata).durationMs,
            )
        }
        val gone = records.keys.filter { it !in live }
        for (tokenKey in gone) {
            val rec = records.remove(tokenKey) ?: continue
            runCatching { rec.controller.unregisterCallback(rec.callback) }
            Logx.i(LogTags.MEDIA, "会话消失，移除记录", "pkg" to rec.pkg, "token" to tokenKey)
        }
        selectAndNotify("reconcile")
    }

    /**
     * 选出当前会话并把状态推给上层。
     *
     * 关键点：候选的「有没有元数据」用的是 ——
     * 暂停后即使会话把元数据撤了，也仍然算有歌（保留暂停时的那一份），
     * 所以暂停不会导致界面被清空。
     */
    private fun selectAndNotify(reason: String) {
        val now = SystemClock.elapsedRealtime()
        val candidates = buildCandidates()
        // —— 候选是**会话级**的
        // 已选中会话用 **token** 比对（用）。
        val best = SessionChooser.choose(candidates, lockedPkg, now, selectedTokenKey, lastMusicPkg)
        if (best == null) {
            // —— 暂停态保留（第六十七轮）：会话没了，但歌名 / 歌词要留着
            //
            // 真机现象：车机音乐应用暂停时会把会话一起销毁 → 候选清单空了 →
            // 这里原来直接 `onTrackChanged(null)` → 界面清空 → 歌名、歌词全没，
            // 再按播放也没得恢复。
            //
            // 现在有「用户按过暂停」的快照时**不清屏**：把歌名 / 歌词继续显示着，
            // 播放按钮也知道往哪个包恢复。
            val snap = pausedSnapshot
            if (snap != null && current != null) {
                Logx.i(
                    LogTags.MEDIA,
                    "会话没了，保留暂停态的歌名与歌词",
                    "reason" to reason,
                    "pkg" to pausedSnapshotPkg,
                    "title" to current?.title,
                )
                // 播放态按「暂停」处理（本来就停着）
                applyPlayingUi(false)
                return
            }
            // 排障场景：没有选中任何会话 → 界面会显示「暂无播放」，这里要能看到候选清单
            Logx.w(
                LogTags.MEDIA,
                "没有选中任何会话（界面将清空）",
                "reason" to reason,
                "candidates" to candidates.size,
                "locked" to (lockedPkg ?: ""),
                "detail" to candidates.joinToString("; ") {
                    "${it.pkg}/${it.tokenKey} meta=${it.hasMetadata} active=${it.active} play=${it.playing}"
                },
            )
            // notifyListeners(null,...) —— 上层据此清空「暂无播放」界面
            releaseController()
            current = null
            listeners.forEach { it.onTrackChanged(null) }
            return
        }
        val rec = records[best.tokenKey]
        if (rec == null) {
            releaseController()
            current = null
            listeners.forEach { it.onTrackChanged(null) }
            return
        }
        if (selectedTokenKey != best.tokenKey) bindController(rec) else pushMetadata(best)
    }

    /**
     * 候选清单（里对每个会话取的 state / effectiveMetadata）。
     *
     * **一个会话一条候选**（传的是 `List<MediaController>`）
     * 同一个包的多个会话会各自成为候选 —— 这正是真机上「两个 wecarflow 会话」的关键。
     */
    private fun buildCandidates(): List<SessionCandidate> = records.values.map { rec ->
        val snapshot = metaSnapshot(rec.effectiveMetadata())
        SessionCandidate(
            pkg = rec.pkg,
            tokenKey = rec.tokenKey,
            playing = rec.state?.state == PlaybackState.STATE_PLAYING,
            hasMetadata = MediaText.hasMetadata(snapshot),
            updatedAt = rec.lastTouchElapsed,
            title = snapshot.title,
            artist = snapshot.artist,
            active = isActiveState(rec.state),
            mediaCenter = SessionChooser.isMediaCenter(rec.pkg),
        )
    }

    /**
     * 记录会话候选清单（每个包的可用性与被过滤的原因）。
     *
     * 排障场景：车机上「点了音乐没反应 / 没接管到 QQ 音乐」时，
     * 要能一眼看出系统里有哪些、哪个被当成系统进程过滤掉了。
     *
     * 清单**变化**时才打 INFO（落盘），没变化只打 DEBUG，避免 2.5s 一次把磁盘刷爆。
     */
    private fun logCandidates(
        all: List<MediaController>,
        usable: List<MediaController>,
        selfPkg: String?,
    ) {
        val usablePkgs = usable.mapNotNull { it.packageName }.toSet()
        val detail = all.joinToString("; ") { c ->
            val pkg = c.packageName.orEmpty()
            "pkg=$pkg usable=${pkg in usablePkgs} reason=${candidateReason(pkg, selfPkg)}"
        }
        val sig = detail
        if (sig != lastCandidateSig) {
            lastCandidateSig = sig
            if (all.isEmpty()) {
                Logx.w(LogTags.MEDIA, "系统里没有任何媒体会话", "selfPkg" to selfPkg)
            } else {
                Logx.i(
                    LogTags.MEDIA, "会话候选变化",
                    "count" to all.size, "usable" to usablePkgs.size, "detail" to detail,
                )
            }
        } else {
            Logx.d(
                LogTags.MEDIA, "会话候选未变化",
                "count" to all.size, "usable" to usablePkgs.size,
            )
        }
    }

    /** 候选被过滤掉的原因（对应 [SessionChooser.isUsablePackage] 的规则）。 */
    private fun candidateReason(pkg: String, selfPkg: String?): String {
        if (pkg.isEmpty()) return "包名为空"
        if (selfPkg != null && pkg == selfPkg) return "本应用自身"
        if (pkg == "android") return "系统进程"
        val lower = pkg.lowercase()
        if (lower.contains("launcher")) return "桌面/启动器"
        if (lower.contains("systemui")) return "系统 UI"
        return "可用"
    }

    private fun bindController(rec: Record) {
        releaseController()
        controller = rec.controller
        selectedTokenKey = rec.tokenKey
        // 排障场景：确认到底接管到了哪个 App、映射成了哪个来源
        val pkg = rec.pkg
        Logx.i(
            LogTags.MEDIA, "选中并绑定会话",
            "pkg" to pkg,
            "token" to rec.tokenKey,
            "sourceId" to sourceIdForPackage(pkg),
            "label" to sourceLabelForPackage(pkg),
            "locked" to (lockedPkg ?: ""),
        )
        pushMetadata(null)
    }

    /**
     * 解绑当前会话。
     *
     * 注意：**这里不再清「暂停态元数据」**。
     * `Record` 是按会话 token 存的，解绑并不会丢掉 `pausedMetadata`
     * 之前我们在解绑时清空，暂停一下就会把歌名和歌词一起清掉。
     */
    private fun releaseController() {
        controller = null
        // —— 解绑时清 selectedToken。
        selectedTokenKey = null
        // 第三十三轮：换会话要把位置锚点一起复位，否则新会话的位置会接着旧会话外推。
        positionTracker.reset()
        lastObservedPosition = 0L
        lastObservedPlaying = false
        lastPositionAt = 0L
    }

    // ---------------------------------------------------------------- metadata

    private fun metaSnapshot(meta: MediaMetadata?): MediaText.Meta {
        if (meta == null) return MediaText.Meta()
        val desc = meta.description
        return MediaText.Meta(
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            displayTitle = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            albumArtist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            subtitle = desc?.subtitle?.toString().orEmpty(),
            descriptionTitle = desc?.title?.toString().orEmpty(),
            mediaId = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            // 封面 URI：DISPLAY_ICON_URI → ART_URI
            artworkUri = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty()
                .ifEmpty { meta.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty() },
            // 播放器自带的歌词（第四十一轮新增）：metadata 自定义 key / description extras
            playerLyric = playerLyricOf(meta, desc),
        )
    }

    /**
     * 播放器自己带的歌词（第四十一轮新增）。
     *
     * 两个来源：
     * - `MediaMetadata` 的自定义 key（`lyric` / `lyrics` / `lrc` / `caption` …）；
     * - `MediaDescription.extras` 里 key 名带 `lyric` / `lrc` / `caption` 的字符串值。
     *
     * **没有**这条通道（读 `MediaDescription` 只取
     * `title` / `subtitle`，不看 `extras`，这里作为增强补上。
     *
     * 只有能解析出带时间戳的 LRC 才认（见 [PlayerLyric.findLrc]），
     * 免得把歌名里的方括号、描述里的 `[1]` 之类误当成歌词。
     */
    private fun playerLyricOf(meta: MediaMetadata, desc: MediaDescription?): String {
        val candidates = ArrayList<String?>(12)
        for (key in PlayerLyric.METADATA_LYRIC_KEYS) {
            runCatching { candidates.add(meta.getText(key)?.toString()) }
        }
        val extras = runCatching { desc?.extras }.getOrNull()
        if (extras != null) {
            runCatching {
                for (key in extras.keySet()) {
                    val lower = key.lowercase()
                    if (PlayerLyric.EXTRAS_KEY_HINTS.any { lower.contains(it) }) {
                        candidates.add(extras.get(key)?.toString())
                    }
                }
            }
        }
        return PlayerLyric.findLrc(candidates).orEmpty()
    }

    /** TITLE 或 DISPLAY_TITLE 任一非空。 */
    private fun hasMetadata(meta: MediaMetadata?): Boolean =
        MediaText.hasMetadata(metaSnapshot(meta))

    /** PLAYING(3) / BUFFERING(6) / CONNECTING(8)。 */
    private fun isActiveState(state: PlaybackState?): Boolean =
        PauseRetention.isActive(state?.state)

    /**
     * 换歌判定：暂停态保留的元数据与新元数据是不是同一首。
     *
     *
     * 比 MEDIA_ID / MEDIA_URI / ARTIST，外加时长。
     */
    private fun identityChanged(old: MediaMetadata?, new: MediaMetadata?): Boolean {
        if (old == null || new == null) return false
        for (key in arrayOf(
            MediaMetadata.METADATA_KEY_MEDIA_ID,
            MediaMetadata.METADATA_KEY_MEDIA_URI,
            MediaMetadata.METADATA_KEY_ARTIST,
        )) {
            val v = new.getString(key)
            if (!v.isNullOrEmpty() && v != old.getString(key)) return true
        }
        val dNew = new.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val dOld = old.getLong(MediaMetadata.METADATA_KEY_DURATION)
        return dNew > 0 && dOld > 0 && dNew != dOld
    }

    /**
     * 取值链
     * 歌名（resolveTrackName + 不稳定标题过滤）与歌手（resolveArtistName）。
     */
    private fun pushMetadata(ignored: SessionCandidate?) {
        val c = controller ?: return
        val state = c.playbackState
        // —— 暂停后仍然用保留的那份元数据
        val rec = records[c.tokenKey()]
        val meta = rec?.effectiveMetadata() ?: c.metadata
        val snapshot = metaSnapshot(meta)
        val pkg = c.packageName.orEmpty()
        val lyricSource = LyricMatch.isLyricTitleSource(pkg)

        val last = current
        val lastTitle = last?.title.orEmpty()
        val lastArtist = last?.artist.orEmpty()
        val lastDuration = last?.durationMs ?: 0L
        val lastMediaId = last?.mediaId.orEmpty()

        val artist = MediaText.resolveArtistName(snapshot, lyricSource).orEmpty()
        val newDuration = snapshot.durationMs
        val durationChanged = newDuration > 0 && lastDuration > 0 &&
            kotlin.math.abs(newDuration - lastDuration) > 2000
        val artistChanged = artist.isNotEmpty() && lastArtist.isNotEmpty() && artist != lastArtist
        val mediaIdChanged = snapshot.mediaId.isNotEmpty() && lastMediaId.isNotEmpty() &&
            snapshot.mediaId != lastMediaId
        val trackChanged = when {
            durationChanged -> true
            lyricSource -> artistChanged
            else -> mediaIdChanged
        }
        val allowFallback = !lyricSource || trackChanged || lastTitle.isEmpty()

        var title = MediaText.resolveTrackName(snapshot, lastTitle, lyricSource, allowFallback).orEmpty()
        // 取值链的第一手结果（后面可能被「稳定优先」规则替换，这里留一份用于日志）
        val resolvedTitle = title
        // —— 播放器自报的「当前歌词行」（第七十八轮新增）
        //
        // 原始 `METADATA_KEY_TITLE`（snapshot.title）在「歌词型来源」里装的是
        // **正在唱的那一句**，而不是歌名。这句话是「现在唱到哪」的权威答案，
        // 丢了它就只能靠联网查来的 LRC 时间轴去猜 —— 猜错就整句错位。
        val playerCurrentLine = MediaText.playerCurrentLine(
            rawTitle = snapshot.title,
            resolvedTitle = resolvedTitle,
            artist = artist,
            pkg = pkg,
        )
        // —— 稳定优先，别让「不稳定标题」顶掉上一首
        if (lyricSource && lastTitle.isNotEmpty() && !trackChanged && title != lastTitle) {
            Logx.d(
                LogTags.MEDIA, "歌名被上一首覆盖（稳定优先）",
                "resolved" to title, "use" to lastTitle, "pkg" to pkg,
            )
            title = lastTitle
        }
        if (lyricSource && MediaText.unstableTitle(title) && lastTitle.isNotEmpty()) {
            // 排障场景：歌名显示成了一句歌词（多见于蓝牙 / USB 这类「歌词型来源」）
            Logx.i(
                LogTags.MEDIA, "不稳定标题被过滤",
                "raw" to title, "use" to lastTitle, "pkg" to pkg,
            )
            title = lastTitle
        }
        if (title.isEmpty()) title = lastTitle

        // —— ALBUM_ART → ART → DISPLAY_ICON
        val artAlbum = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val artArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val artIcon = meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artwork = artAlbum ?: artArt ?: artIcon
        // 排障场景：封面出不来看，是走了哪一级回退、还是退到了占位图
        val artFrom = when {
            artAlbum != null -> "ALBUM_ART"
            artArt != null -> "ART"
            artIcon != null -> "DISPLAY_ICON"
            // 会话只给了 URI→ 交给 CoverLoader 按 URI 加载
            snapshot.artworkUri.isNotEmpty() -> "URI"
            else -> "占位"
        }

        val observedPlaying = state?.state == PlaybackState.STATE_PLAYING
        val observedPaused = state?.state == PlaybackState.STATE_PAUSED ||
            state?.state == PlaybackState.STATE_STOPPED ||
            state?.state == PlaybackState.STATE_NONE
        val playing = resolvePlaying(observedPlaying, observedPaused)

        val position = state?.position ?: 0L
        val next = TrackInfo(
            pkg = pkg,
            title = title.ifEmpty { NO_PLAYBACK },
            artist = artist,
            album = snapshot.album,
            durationMs = newDuration,
            positionMs = position,
            playing = playing,
            artwork = artwork,
            sourceId = sourceIdForPackage(pkg),
            mediaId = snapshot.mediaId,
            // 封面 URI（会话没给 bitmap 时按 URI 加载；两个都没有才走网络搜索）
            artworkUri = snapshot.artworkUri,
            // 播放器自带的歌词（第四十一轮新增）：有就用它，不联网
            playerLyric = snapshot.playerLyric,
            // 播放器自报的「当前歌词行」（第七十八轮新增）
            playerCurrentLine = playerCurrentLine,
        )
        lastMusicPkg = next.pkg
        // 换歌判定：用来区分「用户点的下一首」和「自动切到下一曲」
        //
        // 注意：上一状态是「暂无播放」时不算换歌 —— 那是**第一次真正开始播放**，
        // 不是「自动切到下一曲」。之前没区分，日志里会出现一堆假的「自动切到下一曲」。
        val wasIdle = lastTitle.isEmpty() || lastTitle == NO_PLAYBACK
        val firstRealPlay = last != null && wasIdle && title.isNotEmpty() && title != NO_PLAYBACK
        val songSwitched = last != null && !wasIdle &&
            (lastTitle != title || lastMediaId != snapshot.mediaId)
        if (songSwitched) {
            if (userSkipPending) {
                Logx.i(
                    LogTags.MEDIA, "切歌（用户指令）",
                    "pkg" to pkg, "from" to lastTitle, "to" to title, "mediaId" to snapshot.mediaId,
                )
                userSkipPending = false
            } else {
                // 排障场景：没有用户操作却换歌了 → 来源 App 自己切歌 / 会话被抢占
                Logx.i(
                    LogTags.MEDIA, "自动切到下一曲",
                    "pkg" to pkg, "from" to lastTitle, "to" to title, "mediaId" to snapshot.mediaId,
                )
            }
        } else if (firstRealPlay) {
            // 排障场景：从「暂无播放」第一次进入真实播放 —— 单独记一条，
            // 免得被误读成「来源 App 自己切了歌」。
            if (userSkipPending) {
                Logx.i(
                    LogTags.MEDIA, "首次进入真实播放（用户指令）",
                    "pkg" to pkg, "to" to title, "mediaId" to snapshot.mediaId,
                )
                userSkipPending = false
            } else {
                Logx.i(
                    LogTags.MEDIA, "首次进入真实播放",
                    "pkg" to pkg, "to" to title, "mediaId" to snapshot.mediaId,
                )
            }
        }
        // 取值链快照（歌名 / 歌手 / 时长 / 封面来源）：换歌时落盘，平时只进 Logcat
        logMetadataChain(
            snapshot, title, resolvedTitle, artist, newDuration, pkg, lyricSource, artFrom, songSwitched,
        )
        // 换歌 → 重置停滞检测锚点（在 transport 变化时重置
        // lastObservedMediaPos/lastObservedMediaPosAt，否则新歌会被误判成「卡住」
        if (last?.mediaId != next.mediaId || last?.title != next.title) {
            lastObservedMediaPos = -1L
            lastObservedMediaPosAt = SystemClock.uptimeMillis()
        }
        current = next
        // —— 真的放起来了 → 暂停态快照作废（它只是「暂停时的备份」）
        if (playing && hasMetadata(meta)) {
            pausedSnapshot = null
            pausedSnapshotPkg = ""
        }
        listeners.forEach { it.onTrackChanged(next) }
        listeners.forEach { it.onPlaybackChanged(playing, position) }
    }

    /**
     * 记录元数据取值链的结果。
     *
     * 排障场景：车机上「歌名不对 / 歌手不对 / 封面不出来」时，
     * 要能看到最终值是从 ART、ALBUM_ARTIST、DISPLAY_TITLE 还是描述里的副标题取来的。
     */
    private fun logMetadataChain(
        snapshot: MediaText.Meta,
        title: String,
        resolvedTitle: String,
        artist: String,
        durationMs: Long,
        pkg: String,
        lyricSource: Boolean,
        artFrom: String,
        songSwitched: Boolean,
    ) {
        val fields: Array<Pair<String, Any?>> = arrayOf(
            "pkg" to pkg,
            "title" to title,
            "titleFrom" to titleSourceOf(snapshot, lyricSource, title),
            "resolvedTitle" to resolvedTitle,
            "rawTitle" to snapshot.title,
            "displayTitle" to snapshot.displayTitle,
            "descTitle" to snapshot.descriptionTitle,
            "artist" to artist,
            "artistFrom" to artistSourceOf(snapshot, lyricSource, artist),
            "rawArtist" to snapshot.artist,
            "albumArtist" to snapshot.albumArtist,
            "subtitle" to snapshot.subtitle,
            "durationMs" to durationMs,
            "mediaId" to snapshot.mediaId,
            "artFrom" to artFrom,
            "artUri" to snapshot.artworkUri,
            "lyricSource" to lyricSource,
        )
        if (songSwitched) {
            Logx.i(LogTags.MEDIA, "元数据取值链（换歌）", *fields)
        } else {
            Logx.d(LogTags.MEDIA, "元数据取值链", *fields)
        }
    }

    /** 歌名最终取自哪个字段（仅用于日志定位「歌名不对」）。 */
    private fun titleSourceOf(snapshot: MediaText.Meta, lyricSource: Boolean, resolved: String): String {
        if (resolved.isEmpty()) return "NONE"
        if (!lyricSource) {
            return when {
                snapshot.descriptionTitle.isNotEmpty() && snapshot.descriptionTitle == resolved -> "DESCRIPTION_TITLE"
                snapshot.title.isNotEmpty() && snapshot.title == resolved -> "TITLE"
                else -> "FALLBACK"
            }
        }
        return when {
            snapshot.displayTitle.isNotEmpty() && snapshot.displayTitle == resolved -> "DISPLAY_TITLE"
            snapshot.title.isNotEmpty() && snapshot.title == resolved -> "TITLE"
            else -> "FALLBACK"
        }
    }

    /** 歌手最终取自哪个字段（仅用于日志定位「歌手不对」）。 */
    private fun artistSourceOf(snapshot: MediaText.Meta, lyricSource: Boolean, resolved: String): String {
        if (resolved.isEmpty()) return "NONE"
        if (lyricSource) {
            return when {
                snapshot.artist.isNotEmpty() && snapshot.artist == resolved -> "ARTIST"
                snapshot.albumArtist.isNotEmpty() && snapshot.albumArtist == resolved -> "ALBUM_ARTIST"
                else -> "COMBO"
            }
        }
        return when {
            snapshot.subtitle.isNotEmpty() && snapshot.subtitle == resolved -> "SUBTITLE"
            snapshot.artist.isNotEmpty() && snapshot.artist == resolved -> "ARTIST"
            snapshot.albumArtist.isNotEmpty() && snapshot.albumArtist == resolved -> "ALBUM_ARTIST"
            else -> "COMBO"
        }
    }

    // ---------------------------------------------------------------- progress

    /**
     * 播放停滞检测。
     *
     * 播放状态报 PLAYING、但进度超过 [PLAYBACK_STALL_MS] 没前进 [STALL_ADVANCE_MS] 以上，
     * 就认为实际没在播放，UI 要显示成暂停。
     *
     * @return true 表示「卡住了」
     */
    private fun isStalled(statePlaying: Boolean, positionMs: Long, nowMs: Long): Boolean {
        if (!statePlaying) return false
        val advanced = lastObservedMediaPos < 0 || positionMs > lastObservedMediaPos + STALL_ADVANCE_MS
        if (advanced) {
            lastObservedMediaPos = positionMs
            lastObservedMediaPosAt = nowMs
            stallReported = false
            return false
        }
        val stalled = nowMs - lastObservedMediaPosAt >= PLAYBACK_STALL_MS
        if (stalled && !stallReported) {
            stallReported = true
            // 排障场景：状态报「在放」但进度不动 —— 真机「显示播放却没声音」的头号原因
            Logx.i(
                LogTags.MEDIA, "播放停滞检测触发",
                "pkg" to controller?.packageName,
                "positionMs" to positionMs,
                "lastAdvanceAt" to lastObservedMediaPosAt,
                "stallMs" to (nowMs - lastObservedMediaPosAt),
            )
        }
        return stalled
    }

    private fun pushProgress() {
        val c = controller ?: return
        val state = c.playbackState ?: return
        val now = SystemClock.uptimeMillis()
        val observed = state.position
        val observedPlaying = state.state == PlaybackState.STATE_PLAYING
        val observedPaused = state.state == PlaybackState.STATE_PAUSED ||
            state.state == PlaybackState.STATE_STOPPED ||
            state.state == PlaybackState.STATE_NONE

        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        // 位置解析（第三十三轮抽成纯逻辑 `PlaybackPositionTracker`）：
        // 「会话上报的位置 + 从锚点经过的时间」，并处理**会话没给位置**（-1）的情况。
        val position = positionTracker.resolve(observed, now, observedPlaying, duration)
        if (observed != lastObservedPosition || observedPlaying != lastObservedPlaying) {
            lastObservedPosition = observed
            lastObservedPlaying = observedPlaying
            lastPositionAt = now
        }
        // 排障场景：进度条上的位置是「外推」出来的，和会话上报的真实位置可能对不上。
        // 第三十三轮多带一个 `unknown`：会话没给位置时（position = -1）能一眼看出来。
        if (observedPlaying) {
            Logx.d(
                LogTags.MEDIA, "进度外推",
                "pkg" to c.packageName,
                "observedMs" to observed,
                "positionMs" to position,
                "deltaMs" to (now - lastPositionAt),
                "unknown" to positionTracker.positionUnknown,
                "durationMs" to duration,
            )
        }
        // —— 状态说在放、但进度不前进 = 停滞
        val stalled = isStalled(observedPlaying, observed, now)
        val playing = resolvePlaying(observedPlaying && !stalled, observedPaused)
        // 播放 / 暂停切换：只在切换时落盘（200ms 一次 tick 不打 INFO）
        if (lastReportedPlaying != playing) {
            lastReportedPlaying = playing
            Logx.i(
                LogTags.MEDIA, "播放状态变化",
                "pkg" to c.packageName,
                "playing" to playing,
                "positionMs" to position,
                "stalled" to stalled,
            )
        }
        listeners.forEach { it.onPlaybackChanged(playing, position) }
        current = current?.copy(
            positionMs = position,
            playing = playing,
        )
    }

    // ---------------------------------------------------------------- transport

    private fun applyOptimisticTransport(playing: Boolean) {
        userTransportIntentPlaying = playing
        userTransportIntentUntil = System.currentTimeMillis() + USER_TRANSPORT_INTENT_MS
        // 暂停意图记在**会话记录**上（`Record.userPauseRequested`）
        // 有了它，播放状态变成 STOPPED 时也不会把暂停态元数据丢掉。
        if (!playing) currentRecord()?.userPauseRequested = true
        // —— 乐观操作后重置停滞检测的时间锚
        lastObservedMediaPosAt = if (playing) SystemClock.uptimeMillis() else 0L
        applyPlayingUi(playing)
    }

    /** 当前绑定会话的记录。 */
    private fun currentRecord(): Record? {
        val c = controller ?: return null
        return records[c.tokenKey()]
    }

    /**
     * 记下「用户主动按了暂停」。
     *
     * 必须在**下发暂停之前**调用：暂停态元数据要在这刻先存下来，
     * 否则会话把元数据撤掉之后就再也找不回来了（真机现象：暂停后歌名消失）。
     *
     * ## 第六十七轮：不按包拦了
     *
     * 原来这里有一道包白名单（只有车机多媒体才记），
     * 于是拿 QQ音乐车机版 / 网易云 / 酷狗 / 酷我 / 汽水 播歌时，
     * **暂停一下就掉元数据** → 歌名、歌词一起没。
     *
     * 现在任何包都记 —— 「用户自己按了暂停」本身就是最强的信号。
     */
    fun prepareUserPause() {
        val rec = currentRecord() ?: return
        rec.userPauseRequested = true
        if (hasMetadata(rec.metadata)) {
            rec.pausedMetadata = rec.metadata
            // —— 会话之外的备份：会话被销毁也留着（见字段注释）
            pausedSnapshot = rec.metadata
            pausedSnapshotPkg = rec.pkg
            pausedSnapshotAt = SystemClock.elapsedRealtime()
        }
        Logx.i(
            LogTags.MEDIA,
            "记录用户暂停意图（保留暂停态元数据）",
            "pkg" to rec.pkg,
            "hasMeta" to hasMetadata(rec.metadata),
        )
    }

    private fun applyPlayingUi(playing: Boolean) {
        val cur = current ?: return
        current = cur.copy(playing = playing)
        listeners.forEach { it.onPlaybackChanged(playing, cur.positionMs) }
    }

    private fun hasUserTransportIntent(): Boolean {
        if (userTransportIntentPlaying == null) return false
        if (System.currentTimeMillis() <= userTransportIntentUntil) return true
        clearUserTransportIntent()
        return false
    }

    private fun clearUserTransportIntent() {
        userTransportIntentPlaying = null
        userTransportIntentUntil = 0L
    }

    /**
     *
     * 意图与实际状态一致 → 采纳实际状态；不一致 → 保留乐观 UI，只同步位置。
     */
    private fun resolvePlaying(observedPlaying: Boolean, observedPaused: Boolean): Boolean {
        if (!hasUserTransportIntent()) return observedPlaying
        val intentPlaying = userTransportIntentPlaying == true
        if ((intentPlaying && observedPlaying) || (!intentPlaying && observedPaused)) {
            clearUserTransportIntent()
            return observedPlaying
        }
        return intentPlaying
    }

    fun playPause() {
        val c = controller
        if (c == null) {
            playWithoutSession()
            return
        }
        val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
        //
        // `isPlaying || state == PLAYING(3) || state == BUFFERING(6)` 都算「在放」→ 按下就是暂停。
        // 之前只看 PLAYING，缓冲中(6)会被误判成「暂停中」→ 按下反而是继续播放。
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        Logx.i(
            LogTags.MEDIA, "播放/暂停指令",
            "pkg" to c.packageName, "from" to playing, "to" to !playing, "state" to state,
        )
        if (playing) {
            //
            prepareUserPause()
            applyOptimisticTransport(false)
            if (isDmtPkg(c.packageName)) {
                // 车机多媒体暂停：记下「用户自己按过」
                // 下次点播放会走 DMT 专用恢复路径，而不是普通 play()。
                customPlayFlag = true
                dmtResumeRequested = false
            }
            pauseTransport(c)
        } else {
            applyOptimisticTransport(true)
            if (isDmtPkg(c.packageName)) dmtResumeRequested = true
            playTransport(c)
        }
    }

    /**
     * 没有绑定会话时的播放。
     *
     * 分支
     * - 不是「用户自己按过播放」且记住的包也不是爱趣听类（打开默认音乐 App）；
     * - 否则 → 乐观 UI + `dmtResumeRequested = true` +
     *   两次兜底重试。
     *
     * 之前我们只发了一个**全局** `KEYCODE_MEDIA_PLAY_PAUSE`：
     * 车机音乐应用（爱趣听）收不到按键 → 会话被 stop 掉之后就再也播不回来
     * （真机现象：暂停后点播放没反应）。
     *
     * ## 第六十八轮：所有分支都补上「定向播放事件」
     *
     * 两条路都先给**上一次使用的播放器**发一个定向 `MEDIA_BUTTON(PLAY)`：
     * - 暂停态恢复 → 由 [tryResumeDmtPlayback] 在「没有绑定会话」时发；
     * - 普通无会话 → 在打开 App 之前先发（[sendTargetedPlayEvent]）。
     *
     * 全局键只有「当前正在出声的 App」会接，App 把会话销毁之后全局键就是空放；
     * 定向广播才是「明确叫醒那一个 App」。
     */
    private fun playWithoutSession() {
        val pkg = rememberedMusicPkg()
        // —— 暂停态恢复（第六十七轮）
        //
        // 会话没了（车机 App 暂停时把会话一起销毁）但**歌还在**：
        // 先试着恢复，而不是直接「打开音乐 App」。
        // 「打开 App」的现象是「按了播放没反应」——只是把 App 拉到前台，歌不接着放。
        val resumePkg = pausedSnapshotPkg.ifEmpty { pkg }
        if (pausedSnapshot != null) {
            Logx.i(
                LogTags.MEDIA,
                "播放指令（无会话）：从暂停态恢复",
                "pkg" to resumePkg,
                "title" to metaSnapshot(pausedSnapshot).title,
            )
            applyOptimisticTransport(true)
            dmtResumeRequested = true
            // —— 重新扫一遍会话，把目标包重新绑上
            rescanNow()
            tryResumeDmtPlayback(resumePkg)
            main.postDelayed({ tryResumeDmtPlayback(resumePkg) }, DMT_RESUME_RETRY_MS)
            main.postDelayed({ tryResumeDmtPlayback(resumePkg) }, DMT_RESUME_RETRY_MS * 2)
            // —— 兜底：上面这些都没能把会话拉回来 → 把音乐 App 拉起来（总比毫无反应强）
            main.postDelayed({ fallbackLaunchMusicApp(resumePkg) }, RESUME_LAUNCH_FALLBACK_MS)
            return
        }
        if (!customPlayFlag && !isAiqutingPkg(pkg)) {
            Logx.i(LogTags.MEDIA, "播放指令（无会话）：打开默认音乐应用", "pkg" to pkg)
            // —— 第六十八轮：先给「上一次使用的播放器」发一个**定向**播放事件。
            // 有些 App 收到 MEDIA_BUTTON(PLAY) 就会接着放，比把它拉到前台更贴近「按播放」的意思。
            sendTargetedPlayEvent(pkg, "无会话：上次播放器")
            openDefaultMusicApp()
            return
        }
        Logx.i(
            LogTags.MEDIA, "播放指令（无会话，走 DMT 恢复路径）",
            "pkg" to pkg, "customPlay" to customPlayFlag,
        )
        applyOptimisticTransport(true)
        dmtResumeRequested = true
        // —— 重新扫一遍会话，把目标包重新绑上
        rescanNow()
        tryResumeDmtPlayback(pkg)
        main.postDelayed({ tryResumeDmtPlayback(pkg) }, DMT_RESUME_RETRY_MS)
        main.postDelayed({ tryResumeDmtPlayback(pkg) }, DMT_RESUME_RETRY_MS * 2)
    }

    /**
     * DMT 恢复播放。
     *
     * 语义：`dmtResumeRequested` 为真时
     * `dispatchMediaKey(126)` → 若目标是爱趣听类，再补一路**定向** MEDIA_BUTTON(126)。
     *
     * 目标包优先用当前绑定的会话，否则用「上次记住的音乐包」。
     *
     * ## 第六十八轮：没找到活动会话 → 定向发给「上一次使用的播放器」
     *
     * 原来「定向 MEDIA_BUTTON」只发给爱趣听类（`isAiqutingPkg`），
     * 于是 QQ音乐车机版这类 App 在「暂停 → 会话被销毁 → 按播放」时收不到任何事件 → **按播放没反应**。
     *
     * 现在：**没有绑定会话**时一律给目标包（= 上一次使用的播放器）补一路定向播放事件；
     * **有绑定会话**时保持原语义（只对爱趣听类补），避免给正在正常播放的 App 塞多余广播。
     */
    private fun tryResumeDmtPlayback(fallbackPkg: String) {
        if (!dmtResumeRequested) return
        val bound = controller?.packageName?.takeIf { it.isNotEmpty() }
        val target = MediaResume.targetPackage(bound, fallbackPkg, rememberedMusicPkg())
        controller?.let { c ->
            runCatching { c.transportControls.play() }
                .onFailure { Logx.w(LogTags.MEDIA, "DMT 恢复播放失败", it, "pkg" to target) }
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "DMT 恢复播放")
        if (MediaResume.shouldSendTargetedPlay(bound, target, isAiqutingPkg(target))) {
            sendTargetedPlayEvent(target, if (bound == null) "无会话：上次播放器" else "爱趣听类")
        }
        Logx.i(
            LogTags.MEDIA, "DMT 恢复播放已下发",
            "pkg" to target, "bound" to (bound != null),
        )
    }

    /**
     * 给目标包发一路**定向**播放事件（`MEDIA_BUTTON` + `setPackage`）。
     *
     * 广播只送到目标 App 的 `MediaButtonReceiver`，别的 App 收不到 ——
     * 这就是「没找到活动会话时，明确叫醒上一次使用的播放器」那条路。
     *
     * 目标包为空就**不发**（空包名广播没有意义，还白耗一次广播）。
     *
     * @param reason 为什么发（落进日志，排障时一眼看出走的是哪条路）
     */
    private fun sendTargetedPlayEvent(pkg: String, reason: String) {
        if (pkg.isEmpty()) return
        Logx.i(LogTags.MEDIA, "定向播放事件", "pkg" to pkg, "reason" to reason)
        sendOrderedMediaButton(pkg, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    /**
     * 打开默认音乐应用。
     *
     * 包候选顺序：`com.tencent.qqmusiccar` → `com.tencent.wecarflow`
     * `cn.kuwo.kwmusiccar` → `cn.kuwo.kwmusiccarclone` → `com.netease.cloudmusic.iot`，
     * 取第一个**装了且有启动入口**的。
     *
     * @param hint 优先使用的包（暂停态恢复时就是刚才那个包）
     */
    private fun openDefaultMusicApp(hint: String = "") {
        val ctx = appContext ?: return
        var pkg = controller?.packageName?.takeIf { it.isNotEmpty() }
            ?: hint.takeIf { it.isNotEmpty() }
            ?: rememberedMusicPkg()
        if (pkg.isEmpty()) {
            pkg = MediaSources.DEFAULT_MUSIC_PACKAGES.firstOrNull { candidate ->
                runCatching { ctx.packageManager.getLaunchIntentForPackage(candidate) != null }
                    .getOrDefault(false)
            } ?: ""
        }
        if (pkg.isEmpty()) {
            Logx.w(LogTags.MEDIA, "没有可用的默认音乐应用")
            return
        }
        runCatching {
            // appContext 是 Application 上下文 —— 从它启动 Activity 必须带 NEW_TASK，
            // 否则系统抛「Calling startActivity() from outside of an Activity context」。
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.onFailure {
            Logx.w(LogTags.MEDIA, "默认音乐应用启动失败", it, "pkg" to pkg)
        }
        Logx.i(LogTags.MEDIA, "已打开默认音乐应用", "pkg" to pkg)
    }

    /**
     * 暂停态恢复的兜底：恢复没成功就把音乐 App 拉到前台。
     *
     * 判据：
     * - 会话已经回来了 → 不用管；
     * - 不是暂停态恢复 → 不用管。
     */
    private fun fallbackLaunchMusicApp(pkg: String) {
        if (controller != null) return
        if (pausedSnapshot == null) return
        Logx.i(LogTags.MEDIA, "暂停态恢复没成功，拉起音乐应用", "pkg" to pkg)
        openDefaultMusicApp(pkg)
    }

    /**
     * 播放下发。
     *
     * 播放分支与
     *
     *
     * - 车机多媒体（`com.tencent.wecarflow` 一类）**不认 transportControls**，
     *   只认媒体按键 → 之后还要补 `dispatchMediaKey(126)`，
     *   并且**只对 `isAiqutingPkg` 的包**再补一路定向 `MEDIA_BUTTON` 广播；
     * -还会在 400ms / 1000ms 后各兜底一次（`RunnableC0766q4(this, 3/4)`）
     *   防止第一次按键没被车机应用接住。
     */
    private fun playTransport(c: MediaController) {
        val pkg = c.packageName
        val dmt = isDmtPkg(pkg)
        runCatching { c.transportControls.play() }
            .onFailure { Logx.w(LogTags.MEDIA, "播放下发失败", it, "pkg" to pkg) }
        if (!dmt) return
        Logx.i(LogTags.MEDIA, "车机多媒体播放：补发媒体按键", "pkg" to pkg)
        // 1) AudioManager 媒体按键（KEYCODE_MEDIA_PLAY = 126，幂等键，不会误触「切换」）
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "车机多媒体补发")
        // 2) 定向 MEDIA_BUTTON 广播
        //    只在 isAiqutingPkg（wecarflow/aiquting/qqmusictv）时才发这一路
        if (isAiqutingPkg(pkg)) {
            sendOrderedMediaButton(pkg, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        // 3)两个兜底重试（400ms / 1000ms）
        main.postDelayed({ retryDmtResume(c, "dmt-resume+400") }, DMT_RESUME_RETRY_MS)
        main.postDelayed({ retryDmtResume(c, "dmt-resume+1000") }, DMT_RESUME_RETRY_MS * 2)
    }

    /** 播放兜底重试（`RunnableC0766q4(this, 3/4)` → `tryResumeDmtPlayback`）。 */
    private fun retryDmtResume(c: MediaController, reason: String) {
        if (controller?.packageName != c.packageName) return
        val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
        if (state == PlaybackState.STATE_PLAYING) return
        Logx.i(LogTags.MEDIA, "车机多媒体播放兜底重试", "pkg" to c.packageName, "reason" to reason)
        runCatching { c.transportControls.play() }
            .onFailure { Logx.w(LogTags.MEDIA, "播放兜底下发失败", it, "pkg" to c.packageName) }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "车机多媒体兜底")
        if (isAiqutingPkg(c.packageName)) {
            sendOrderedMediaButton(c.packageName, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    /**
     * 暂停下发。
     *
     *
     * - 普通来源：`transportControls.pause()`，失败再 `dispatchMediaKey(127)`；
     * - **车机多媒体： 优先、 兜底**，
     *   而且**不发**定向 广播。
     *
     * 之前我们改成了「pause() 优先 + 额外补一路定向 MEDIA_PAUSE」，
     * 真机上那一路额外广播把车机音乐应用的会话搞没了
     * （诊断包：暂停 → 播放 → 会话列表里 `com.tencent.wecarflow` 消失 → 界面全清）。
     * 这里改回语义。
     */
    private fun pauseTransport(c: MediaController) {
        val pkg = c.packageName
        if (isDmtPkg(pkg)) {
            Logx.i(LogTags.MEDIA, "车机多媒体暂停：stop 优先、pause 兜底", "pkg" to pkg)
            val stopped = runCatching {
                c.transportControls.stop()
                true
            }.getOrElse {
                Logx.w(LogTags.MEDIA, "车机多媒体 stop 下发失败，改用 pause()", it, "pkg" to pkg)
                runCatching { c.transportControls.pause(); true }.getOrDefault(false)
            }
            if (!stopped) {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "transportControls 失败回退")
            }
            return
        }
        val ok = runCatching {
            c.transportControls.pause()
            true
        }.getOrElse {
            Logx.w(LogTags.MEDIA, "暂停下发失败", it, "pkg" to pkg)
            false
        }
        if (!ok) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "transportControls 失败回退")
        }
    }

    /**
     * 车机多媒体类 app。
     *
     * = `DMT_MUSIC_CLASS` 精确匹配 **或** [isAiqutingPkg]。
     */
    private fun isDmtPkg(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return p == DMT_MUSIC_CLASS || isAiqutingPkg(pkg)
    }

    /**
     * 「爱趣听」类 app。
     *
     * 只有这些包在恢复播放时才会额外收到一路**定向** `MEDIA_BUTTON` 广播
     *
     */
    private fun isAiqutingPkg(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return p.contains("wecarflow") || p.contains("aiquting") || p.contains("qqmusictv")
    }

    /**
     * 定向 MEDIA_BUTTON 广播。
     *
     * `setPackage(pkg)` 让广播只发给目标 app 的 MediaButtonReceiver。
     */
    private fun sendOrderedMediaButton(pkg: String?, keyCode: Int) {
        if (pkg.isNullOrEmpty()) return
        val ctx = appContext ?: return
        runCatching {
            val up = SystemClock.uptimeMillis()
            val down = Intent("android.intent.action.MEDIA_BUTTON")
                .setPackage(pkg)
                .putExtra(
                    "android.intent.extra.KEY_EVENT",
                    KeyEvent(up, up, KeyEvent.ACTION_DOWN, keyCode, 0),
                )
            ctx.sendOrderedBroadcast(down, null)
            val release = Intent("android.intent.action.MEDIA_BUTTON")
                .setPackage(pkg)
                .putExtra(
                    "android.intent.extra.KEY_EVENT",
                    KeyEvent(up, up + 1, KeyEvent.ACTION_UP, keyCode, 0),
                )
            ctx.sendOrderedBroadcast(release, null)
            Logx.i(LogTags.MEDIA, "定向 MEDIA_BUTTON 已发送", "pkg" to pkg, "key" to keyName(keyCode))
        }.onFailure {
            Logx.w(LogTags.MEDIA, "定向 MEDIA_BUTTON 发送失败", it, "pkg" to pkg, "key" to keyName(keyCode))
        }
    }

    fun next() {
        // —— 切歌前丢弃「暂停态元数据」
        discardPausedMetadata()
        val c = controller
        if (c == null) {
            Logx.i(LogTags.MEDIA, "切歌指令：下一首（无会话，走按键回退）", "pkg" to "")
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            return
        }
        // 标记成「用户主动切歌」，后面元数据变化时就能和「自动切歌」区分开
        userSkipPending = true
        Logx.i(LogTags.MEDIA, "切歌指令：下一首", "pkg" to c.packageName)
        stopDmtIfPaused(c)
        runCatching { c.transportControls.skipToNext() }
            .onFailure { Logx.w(LogTags.MEDIA, "下一首下发失败", it, "pkg" to c.packageName) }
    }

    fun previous() {
        // —— 切歌前丢弃「暂停态元数据」
        discardPausedMetadata()
        val c = controller
        if (c == null) {
            Logx.i(LogTags.MEDIA, "切歌指令：上一首（无会话，走按键回退）", "pkg" to "")
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            return
        }
        userSkipPending = true
        Logx.i(LogTags.MEDIA, "切歌指令：上一首", "pkg" to c.packageName)
        stopDmtIfPaused(c)
        runCatching { c.transportControls.skipToPrevious() }
            .onFailure { Logx.w(LogTags.MEDIA, "上一首下发失败", it, "pkg" to c.packageName) }
    }

    /**
     * 车机多媒体在「暂停态」切歌前先 stop 一下。
     *
     *
     * `if (playbackState.state == PAUSED(2) && isDmtBound()) transportControls.stop();`
     * —— 车机多媒体在暂停态下 不生效，得先把会话「停」掉。
     */
    private fun stopDmtIfPaused(c: MediaController) {
        if (!isDmtPkg(c.packageName)) return
        if (c.playbackState?.state != PlaybackState.STATE_PAUSED) return
        Logx.i(LogTags.MEDIA, "车机多媒体暂停态切歌：先 stop 再切", "pkg" to c.packageName)
        runCatching { c.transportControls.stop() }
            .onFailure { Logx.w(LogTags.MEDIA, "stop 下发失败", it, "pkg" to c.packageName) }
    }

    fun seekTo(positionMs: Long) {
        val c = controller
        if (c == null) {
            Logx.w(LogTags.MEDIA, "seek 失败（没有会话）", "positionMs" to positionMs)
            return
        }
        Logx.i(LogTags.MEDIA, "seek 指令", "pkg" to c.packageName, "positionMs" to positionMs)
        runCatching { c.transportControls.seekTo(positionMs) }
            .onFailure { Logx.w(LogTags.MEDIA, "seek 下发失败", it, "pkg" to c.packageName) }
    }

    /**
     * 丢弃暂停态元数据（`MusicSessionTracker.discardPausedMetadata`）。
     *
     * 切歌前调用：上一首的暂停态元数据不能带到下一首上。
     */
    fun discardPausedMetadata() {
        val rec = currentRecord() ?: return
        rec.pausedMetadata = null
        rec.userPauseRequested = false
    }

    /** 无会话时走 AudioManager。 */
    private fun dispatchMediaKey(keyCode: Int, reason: String = "无会话回退") {
        // 排障场景：车机上按了没反应 —— 这里能看到是不是走了「按键接管」这条路
        Logx.i(
            LogTags.MEDIA, "按键接管（AudioManager）",
            "key" to keyName(keyCode), "reason" to reason,
        )
        runCatching {
            val ctx = appContext ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val up = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(up, up, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(up, up + 1, KeyEvent.ACTION_UP, keyCode, 0))
        }.onFailure { Logx.w(LogTags.MEDIA, "按键接管失败", it, "key" to keyName(keyCode)) }
    }

    /** 按键名（便于在真机日志里直接看出按了哪个键）。 */
    private fun keyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY_PAUSE"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREVIOUS"
        KeyEvent.KEYCODE_MEDIA_STOP -> "STOP"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
        else -> keyCode.toString()
    }

    companion object {
        const val RESCAN_MS = 2_500L

        /**
         * 播放位置的夹取（纯函数，可单测）。
         *
         * `max(0, min(position, duration))`。
         *
         * 为什么要夹：会话卡住不动（上报位置不再前进）而状态还报「在放」时，
         * 进度是按时间**外推**的，会一直往上涨 —— 进度条会走出总时长
         * （实测显示 6:12 / 总长 3:59）。
         *
         * @param durationMs 总时长；≤0 表示不知道，此时只做下限保护。
         */
        fun clampPosition(positionMs: Long, durationMs: Long): Long =
            PlaybackPositionTracker.clamp(positionMs, durationMs)

        const val DMT_MUSIC_CLASS = "com.tencent.wecarflow"

        /** 进度刷新 200ms 一次。 */
        const val PROGRESS_TICK_MS = 200L

        const val USER_TRANSPORT_INTENT_MS = 4_000L

        /**
         * 车机多媒体恢复播放的兜底重试间隔（400L / 1000L 两个兜底）。
         */
        const val DMT_RESUME_RETRY_MS = 400L

    /**
     * 暂停态恢复的兜底延迟（毫秒）：这么长时间还没把会话拉回来，就把音乐 App 拉起来。
     */
    const val RESUME_LAUNCH_FALLBACK_MS = 2_000L

        /**
         * 唤醒上次音乐包之后的刷新间隔（`MusicSessionTracker` 的 400L / 1200L 两次刷新）。
         */
        const val WAKE_REFRESH_MS = 400L

        /**
         * 第三十七轮：唤醒后的刷新节奏。
         *
         * 只刷两次（+400ms / +1200ms）；自启播放场景里车机多媒体起得慢
         * 这里多刷几轮（400 / 1200 / 2500 / 5000 / 8000ms）。
         */
        val WAKE_REFRESH_DELAYS_MS = longArrayOf(400L, 1200L, 2500L, 5000L, 8000L)

        /** 唤醒后仍未接管到会话时的重发间隔。 */
        const val WAKE_RETRY_MS = 8000L

        /** 最多重发几次（避免无限循环）。 */
        const val WAKE_RETRY_MAX = 3

        /**
         * 播放停滞判定窗口。
         */
        const val PLAYBACK_STALL_MS = 2_500L

        /**
         * 判定「进度有前进」的阈值
         * 里的 `max > lastObserved + 200`。
         */
        const val STALL_ADVANCE_MS = 200L

        const val NO_PLAYBACK = "暂无播放"

        private val instance = MusicSessionTracker()

        fun get(): MusicSessionTracker = instance

        /**
         * 9 个固定来源的包名映射。
         * 统一由 [MediaSources] 提供，避免多处各写一份导致文案/包名漂移。
         */
        val SOURCE_PACKAGES: LinkedHashMap<String, String> =
            LinkedHashMap(MediaSources.PRIMARY_PACKAGES)

        /**  9 个固定来源及菜单文案（顺序即菜单顺序）。 */
        val SOURCE_LABELS: LinkedHashMap<String, String> =
            LinkedHashMap(MediaSources.LIST.associate { it.id to it.label })

        fun sourceLabel(sourceId: String): String = SOURCE_LABELS[sourceId] ?: sourceId

        /** 包名 → 来源 id（映射规则）。 */
        fun sourceIdForPackage(pkg: String): String = MediaSources.idForPackage(pkg)

        /** 包名 → 来源文案。 */
        fun sourceLabelForPackage(pkg: String): String = MediaSources.labelForPackage(pkg)

        fun packageForSource(sourceId: String): String? = SOURCE_PACKAGES[sourceId]
    }
}

/**
 * 会话唯一标识。
 *
 * AOSP 的 的 `equals` / `hashCode` 都基于
 * `serviceName.hashCode() ^ serviceId.hashCode()`， 把它渲染成
 * `MediaSession.Token {hashCode=…}` —— 所以用 当 map key
 * 与用 Token 本身做 key **等价**，而且能让纯逻辑层（[SessionChooser]）
 * 不依赖 Android 类型、可直接单测。
 */
fun MediaController.tokenKey(): String = sessionToken.toString()
