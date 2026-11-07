package com.cardesk.launcher.cluster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.R
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.MusicController
import java.lang.ref.WeakReference

/**
 * 把歌词画到**另一块屏**上（车机上的仪表、副驾屏……）。
 *
 * ## 它到底是怎么做到的
 *
 * **不是**「把歌词发给仪表」，也**不需要**车厂接口、另一台设备或投屏协议。
 * 做法和参考应用一样：把一个窗口加到**那块屏自己的窗口管理器**上。
 *
 * - [android.content.Context.createDisplayContext] 给出一个 `Context`，
 *   它的 `WINDOW_SERVICE` 属于**那块屏**；
 * - 往那个 WindowManager 里 `addView`，窗口就落在那块屏上；
 * - `TYPE_APPLICATION_OVERLAY` 让面板叠在仪表自己的画面上方，
 *   `FLAG_NOT_FOCUSABLE` 不抢焦点，`FLAG_NOT_TOUCHABLE` 让手指直接穿过去。
 *
 * 两个坑，别的方案基本都死在这两条上：
 *
 * 1. **必须用 `createDisplayContext`**，不能用 Activity 自己的 WindowManager ——
 *    用主 Context 加窗口，窗口永远落在主屏上，`params.x/y` 怎么调都只是
 *    在主屏上乱跑。
 * 2. **必须查那块屏自己的 density** —— 主屏 240 dpi、仪表 320 dpi 很常见，
 *    用错密度字号会差三成。
 *
 * ## 数据从哪来
 *
 * 面板画在另一块屏上，它没有自己的媒体会话订阅通道。与其再教一个消费者去订会话，
 * 不如让服务在固定节奏上从 [MusicController] 取一次，冻结成一份
 * [ClusterLyricSnapshot] —— 桌面卡片和仪表因此用的是**同一份歌词、
 * 同一个擦除比例**，不会出现「两边显示的不是同一句」。
 *
 * ## 失败是预期内的
 *
 * 副屏可能是别的进程的私有屏、可能在休眠时消失、可能在重连后换了 id。
 * 所以：加窗失败按指数退避重试；屏幕增删改都**重新按存下来的身份解析**
 * （不信旧 id）；最后一次结果留在 [report] 里，设置页能直接说为什么没出来。
 *
 * 移植自 UNI 桌面（`ClusterLyricsService`），口径一致。
 */
class ClusterLyricsService : Service() {

    companion object {
        /** 首次失败的退避基数。 */
        private const val RETRY_MIN_MS = 1000L

        /** 退避上限。 */
        private const val RETRY_MAX_MS = 30_000L

        /** 帧间隔（40ms ≈ 25fps）。面板只有三行字，25fps 足够且省一半绘制。 */
        private const val FRAME_MS = 40L

        /** 取歌词快照的节奏（700ms）。 */
        private const val FEED_MS = 700L

        /** 附着探针的节流（5s）。 */
        private const val HEALTH_RETRY_MS = 5_000L

        /** 样式：胶囊底板。 */
        const val STYLE_PILL = ClusterLyricsView.STYLE_PILL

        /** 样式：纯文字。 */
        const val STYLE_PLAIN = ClusterLyricsView.STYLE_PLAIN

        /** 样式：发光。 */
        const val STYLE_GLOW = ClusterLyricsView.STYLE_GLOW

        /** 默认强调色（青绿）。 */
        const val DEFAULT_ACCENT = 0xFF7FE3D4.toInt()

        /** 默认弱文字色。 */
        const val DEFAULT_MUTED = 0xFF9FB4BB.toInt()

        /** 通知栏「停止投射」的 action。 */
        const val ACTION_STOP = "com.cardesk.launcher.action.CLUSTER_LYRICS_STOP"

        /** 前台服务通知的 channel id（低优先级，不出声）。 */
        private const val CHANNEL_ID = "cardesk_cluster_lyrics"

        /** 前台服务通知 id。 */
        private const val NOTIFICATION_ID = 754

        /** 最近一次加窗/断窗的结果（设置页直接显示）。 */
        @Volatile
        var lastReport: String = "未启用"
            private set

        /** 活着的实例：设置项改了可以**原地**改样式，不用重连窗口。 */
        private var live: WeakReference<ClusterLyricsService>? = null

        /**
         * 一块可投递的屏。
         *
         * @param presentation 是否是 `DISPLAY_CATEGORY_PRESENTATION` 里的屏
         */
        data class DisplayRow(
            val id: Int,
            val name: String,
            val presentation: Boolean,
            val width: Int,
            val height: Int,
            val densityDpi: Int,
        ) {
            /** 一行说明：`仪表 · ID 1 · 1920×720 · 240 dpi`。 */
            val label: String get() = DisplayPicker.label(name, id, width, height, densityDpi)
        }

        /**
         * 这台车机向应用暴露了哪些屏。
         *
         * **上车的第一步就看这个**：列表为空 ⇒ 仪表不是 Android 屏，
         * 第三方窗口根本画不上去（仪表多由独立 SoC / 总线驱动）。
         */
        fun displays(context: Context): List<DisplayRow> {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return emptyList()
            val presentationIds = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .map { it.displayId }.toSet()
            val out = ArrayList<DisplayRow>()
            for (display in manager.getDisplays()) {
                if (!display.isValid()) continue
                // 主屏不是投射目标：单屏设备上把它列出来，
                // 用户一点就会把歌词窗口盖在桌面自己身上。
                if (display.displayId <= 0) continue
                try {
                    val metrics = DisplayMetrics()
                    display.getRealMetrics(metrics)
                    out.add(
                        DisplayRow(
                            id = display.displayId,
                            name = display.name ?: "",
                            presentation = presentationIds.contains(display.displayId),
                            width = metrics.widthPixels,
                            height = metrics.heightPixels,
                            densityDpi = metrics.densityDpi,
                        ),
                    )
                } catch (ignored: Throwable) {
                    // 枚举到一半消失的屏，就不给它一行。
                }
            }
            return out
        }

        /** 当前状态（设置页用）。 */
        fun status(context: Context): Map<String, Any> {
            val prefs = Prefs(context.applicationContext)
            val mc = MusicController.get()
            val timeline = mc.lyric
            return mapOf(
                "enabled" to prefs.clusterOverlayEnabled,
                "displayId" to prefs.clusterDisplayId,
                "displayName" to prefs.clusterDisplayName,
                "report" to reportForDisplay(),
                "track" to (mc.track?.title ?: ""),
                "artist" to (mc.track?.artist ?: ""),
                "lyricLines" to (timeline?.lines?.size ?: 0),
                "playing" to (mc.track?.playing ?: false),
                "displays" to displays(context).map { row ->
                    mapOf(
                        "id" to row.id,
                        "name" to row.name,
                        "label" to row.label,
                        "presentation" to row.presentation,
                    )
                },
            )
        }

        /**
         * 把当前状态**重新应用**一次（设置页改了参数、或服务刚被拉起时）。
         *
         * 幂等：已经在跑的服务是**原地**改样式，不重连窗口。
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val service = live?.get()
            if (service != null) {
                service.handler.post { service.reconcile("refresh") }
                return
            }
            if (Prefs(app).clusterOverlayEnabled) {
                start(app)
            }
        }

        /** 开关变了：开就起服务，关就停。 */
        fun apply(context: Context) {
            val app = context.applicationContext
            if (Prefs(app).clusterOverlayEnabled) {
                start(app)
            } else {
                app.stopService(Intent(app, ClusterLyricsService::class.java))
            }
        }

        /**
         * 起服务。
         *
         * ## 为什么是**前台**服务
         *
         * 这个服务的存在意义恰恰是「用户去别的 App 听歌时，仪表上还有词」——
         * 而用户一去音乐 App，桌面（HOME）就转入后台。
         *
         * Android 8+ 的后台执行限制：前台应用起的普通 Service，
         * 在应用转入后台约一分钟后会被系统**停掉**。
         * 也就是说：开着音乐听两首歌，仪表上的歌词自己就没了。
         *
         * 所以走 `startForegroundService` + `startForeground`（低优先级通知，不出声）。
         *
         * ## 为什么把异常就地吃掉
         *
         * Android 8+ 起，后台 `startService` 会抛 `IllegalStateException`
         * （"Not allowed to start service Intent ... app is in background"）。
         * 这个异常如果漏出去，调用方（设置页 / 开机广播）会直接崩，
         * 而现象只是「歌词没投出来」—— 排查时完全看不出是系统拦的。
         *
         * 所以这里**就地吃掉**并把原因写进 [lastReport]，
         * 设置页能直接说「后台启动被系统拒绝，回到桌面再试」，
         * 而不是让用户对着一个没反应的开关发呆。
         */
        private fun start(app: Context) {
            val intent = Intent(app, ClusterLyricsService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (denied: IllegalStateException) {
                lastReport = "后台启动被系统拒绝，回到桌面再试"
                Logx.w(LogTags.CLUSTER, "投射服务启动被系统拒绝", denied)
            } catch (failed: Throwable) {
                lastReport = "投射服务启动失败"
                Logx.w(LogTags.CLUSTER, "投射服务启动失败", failed)
            }
        }

        /**
         * 把歌词部分**重新算一遍**再拼进报告。
         *
         * [lastReport] 是加窗那一刻写下的 —— 那一刻歌词往往还在取，
         * 于是报告 froze 在「尚未拿到曲目」，而面板上已经在滚词了。
         * 窗口那一半是历史、歌词那一半是当前，所以在这里重算而不是烤进字符串里。
         */
        private fun reportForDisplay(): String {
            val report = lastReport
            if (!report.startsWith("已投射")) return report
            val marker = report.lastIndexOf(" · ")
            val head = if (marker > 0) report.substring(0, marker) else report
            val mc = MusicController.get()
            val timeline = mc.lyric
            val lines = timeline?.lines?.size ?: 0
            val lyricPart = when {
                lines > 0 -> (timelineSourceLabel(mc) + " " + lines + " 行")
                (mc.track?.title ?: "").isEmpty() -> "尚未拿到曲目"
                else -> "正在匹配歌词"
            }
            return head + " · " + lyricPart
        }

        /** 歌词来源说明（哪个 App 提供的）。 */
        private fun timelineSourceLabel(mc: MusicController): String {
            val pkg = mc.track?.pkg ?: return "歌词"
            return pkg.substringAfterLast('.').ifEmpty { "歌词" }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var displayManager: DisplayManager? = null

    /** 目标屏自己的 WindowManager（**不是**我们自己的）。 */
    private var displayWindows: WindowManager? = null

    private var panel: ClusterLyricsView? = null

    private var attachedDisplayId = -1

    private var failures = 0

    private var frameScheduled = false

    /** 上一帧画过的快照（变了才重绘）。 */
    private var lastDrawn: ClusterLyricSnapshot? = null

    private var feedRunning = false

    /** 上一次做「附着探针」的时间戳（毫秒）。 */
    private var lastHealthRetry = 0L

    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        tick()
    }

    private val feed = object : Runnable {
        override fun run() {
            if (!feedRunning) return
            pump()
            handler.postDelayed(this, FEED_MS)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            reconcile("display-added")
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == attachedDisplayId) detach("display-removed")
            reconcile("display-removed")
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == attachedDisplayId) reattach("display-changed")
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        live = WeakReference(this)
        // **必须先 startForeground 再做任何判断**：
        // `startForegroundService()` 之后 5 秒内不调 `startForeground`，
        // 系统会抛 `RemoteServiceException`（Android 8+）—— 那是「启动即崩」，
        // 比「静默退出」难排查得多。
        startForeground(NOTIFICATION_ID, buildNotification())
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, handler)
        reconcile("service-created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏上的「停止投射」：把开关也关掉，免得下次开机又被拉起来。
        if (intent?.action == ACTION_STOP) {
            Prefs(applicationContext).clusterOverlayEnabled = false
            lastReport = "已关闭"
            stopSelf()
            return START_NOT_STICKY
        }
        // 被系统重启（START_STICKY）时也要保证前台通知在位。
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        reconcile("start-command")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        stopFeed()
        handler.removeCallbacksAndMessages(null)
        detach("service-destroyed")
        // 收掉前台通知，否则状态栏会留一条常驻。
        runCatching { stopForeground(true) }
        live = null
        super.onDestroy()
    }

    /**
     * 前台服务通知（低优先级，不出声）。
     *
     * 用一条**常驻但安静**的通知把服务「钉住」——
     * 否则用户切到音乐 App 后桌面转后台，服务一分钟后就被系统停掉，
     * 仪表上的歌词会自己消失。
     */
    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "仪表歌词投射",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "把歌词投到副屏时保持服务常驻"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ClusterLyricsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_cardesk)
            .setContentTitle("CarDesk 仪表歌词投射")
            .setContentText("正在把歌词投到副屏，点此停止")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止投射", stop)
            .build()
    }

    // ------------------------------------------------------------ 歌词喂数

    private fun startFeed() {
        if (feedRunning) return
        feedRunning = true
        handler.post(feed)
    }

    private fun stopFeed() {
        if (!feedRunning) return
        feedRunning = false
        handler.removeCallbacks(feed)
    }

    /**
     * 每 700ms 做四件事。
     *
     * 1. 取一次歌词快照；
     * 2. **状态看门狗**：快照变了就重绘 —— 换歌那一瞬间拿到的是新歌名 + 空时间轴，
     *    面板画完「正在获取歌词」就不再排下一帧了；时间轴后到时没人叫醒它，
     *    窗口会一直停在那句话上，直到用户把开关关一次再开一次；
     * 3. **心跳**：每两次 pump 重绘一次，防止换歌期间上一首的词留在玻璃上；
     * 4. **附着探针**：车机重连、副屏热插、系统收回悬浮窗，都会留下一个
     *    「对象里还握着 view，但画在一个已经死掉的 WindowManager 上」的状态 ——
     *    状态页写着已开启，屏上什么都没有，日志里也没有。
     */
    private fun pump() {
        val snapshot = readSnapshot()
        if (panel != null && snapshot != lastDrawn) {
            lastDrawn = snapshot
            panel?.snapshot = snapshot
            startFrames()
        }
        if (panel != null) {
            handler.postDelayed({ startFrames() }, FEED_MS)
        }
        if (panel != null && !healthy(panel!!) && now() - lastHealthRetry > HEALTH_RETRY_MS) {
            lastHealthRetry = now()
            reattach("health:" + describe(panel!!))
        }
    }

    /**
     * 从 [MusicController] 取一份歌词快照。
     *
     * 面板与桌面卡片读的是**同一个** [MusicController]，
     * 所以两边的当前行、擦除比例天然一致。
     */
    private fun readSnapshot(): ClusterLyricSnapshot {
        val mc = MusicController.get()
        val track = mc.track
        val timeline = mc.lyric
        val index = mc.lyricIndex
        val current = timeline?.lineAt(index).orEmpty()
        val prev = if (index > 0) timeline?.lineAt(index - 1).orEmpty() else ""
        val next = if (timeline != null && index >= 0 && index < timeline.lines.size - 1) {
            timeline.lineAt(index + 1)
        } else {
            ""
        }
        return ClusterLyricSnapshot(
            title = track?.title ?: "",
            artist = track?.artist ?: "",
            sourceLabel = timelineSourceLabel(mc),
            playing = track?.playing ?: false,
            previousText = prev,
            currentText = current,
            nextText = next,
            wipe = mc.lyricWipe,
            lyricLines = timeline?.lines?.size ?: 0,
        )
    }

    // ------------------------------------------------------------ 帧循环

    private fun startFrames() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun tick() {
        val view = panel ?: return
        // 每帧重画当前快照：擦除进度是连续量，不重画就停在原地。
        val current = lastDrawn
        view.snapshot = current ?: ClusterLyricSnapshot()
        view.invalidate()
        // 还在播就继续排帧；停了就不再唤醒 CPU。
        val mc = MusicController.get()
        if (mc.track?.playing == true) {
            handler.postDelayed({ startFrames() }, FRAME_MS)
        }
    }

    // ------------------------------------------------------------ 加窗 / 断窗

    private fun reconcile(reason: String) {
        val prefs = Prefs(applicationContext)
        if (!prefs.clusterOverlayEnabled) {
            stopFeed()
            detach("$reason:disabled")
            // 开关关着还留着一个前台服务，只会让状态栏多一条常驻通知。
            // 这里主动收掉（`stopService` 也会走到这里，但那条路不保证）。
            if (reason != "service-created") stopSelf()
            return
        }
        // Android 6+ 光在清单里声明 SYSTEM_ALERT_WINDOW 不够，
        // 用户还得在系统里授「显示在其他应用上层」。
        if (!Settings.canDrawOverlays(this)) {
            detach("$reason:no-overlay-permission")
            lastReport = "缺少「显示在其他应用上层」权限"
            Logx.w(
                LogTags.CLUSTER,
                "缺少悬浮窗权限，投射不了",
                "reason" to reason,
                "package" to packageName,
            )
            return
        }
        val dm = displayManager ?: (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
        if (dm == null) {
            lastReport = "取不到屏幕服务"
            return
        }
        displayManager = dm

        val all = dm.getDisplays()
        val ids = IntArray(all.size) { all[it].displayId }
        // 用 `Array<String?>` 而不是 `arrayOfNulls`：后者在 Kotlin 里会撞上
        // `kotlin.collections` 里的内部重载，编译期报 "it is internal"。
        val names: Array<String> = Array(all.size) { all[it].name }

        val picked = DisplayPicker.pick(
            ids, names,
            prefs.clusterDisplayName, prefs.clusterDisplayId,
        )
        if (picked < 0) {
            detach("$reason:no-target")
            // 两种「投不出去」要分开说：一种是没屏，一种是有屏但没选中。
            val noScreen = ids.none { it > 0 }
            lastReport = if (noScreen) {
                "这台车机没有向应用暴露副屏（仪表可能由独立 SoC 驱动）"
            } else {
                "没有选中目标屏"
            }
            Logx.i(
                LogTags.CLUSTER,
                "没有可投的目标屏",
                "reason" to reason,
                "displays" to ids.joinToString(","),
                "wantId" to prefs.clusterDisplayId,
                "wantName" to prefs.clusterDisplayName,
                "cause" to if (noScreen) "no-secondary-display" else "not-selected",
            )
            return
        }
        val target = all[picked]
        if (target.displayId == attachedDisplayId && panel != null) {
            restyle()
            return
        }
        attach(target, reason)
    }

    /** 窗口已经在了：**原地**改尺寸/位置/样式（拖动预览时不用重连）。 */
    private fun restyle() {
        val view = panel ?: return
        val windows = displayWindows ?: return
        val dm = displayManager ?: return
        val display = dm.getDisplay(attachedDisplayId) ?: return
        if (!display.isValid()) return
        try {
            val prefs = Prefs(applicationContext)
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val density = if (metrics.density <= 0f) 1f else metrics.density
            val mainPx = prefs.overlayMainSp * density
            val subPx = prefs.overlaySubSp * density
            val rows = ClusterPanelGeometry.rows(prefs.clusterRows)
            view.configure(
                mainPx, subPx, rows,
                accentOf(prefs), mutedOf(prefs),
                backgroundOf(prefs),
                styleOf(prefs),
            )
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params == null) {
                attach(display, "restyle")
                return
            }
            val width = ClusterPanelGeometry.width(metrics.widthPixels, prefs.overlayWidthPct / 100f)
            val height = ClusterPanelGeometry.height(mainPx, subPx, rows)
            val origin = ClusterPanelGeometry.origin(
                metrics.widthPixels, metrics.heightPixels, width, height,
                prefs.overlayXPct / 100f, prefs.overlayYPct / 100f,
            )
            params.width = width
            params.height = height
            params.x = origin[0]
            params.y = origin[1]
            windows.updateViewLayout(view, params)
            view.invalidate()
            lastReport = "已投射 · " + display.name + " · ID " + display.displayId
        } catch (gone: Throwable) {
            // 屏或窗口在检查与更新之间没了。
            detach("restyle-failed")
            scheduleRetry("restyle-failed")
        }
    }

    private fun reattach(reason: String) {
        val dm = displayManager ?: return
        val display = dm.getDisplay(attachedDisplayId)
        if (display == null || !display.isValid()) {
            detach("$reason:gone")
            reconcile(reason)
            return
        }
        attach(display, reason)
    }

    /**
     * 加窗。
     *
     * 核心就是两行：`createDisplayContext` 拿到**那块屏**的 Context，
     * 再把 View 加到**那块屏**的 WindowManager 上。
     */
    private fun attach(target: Display, reason: String) {
        detach("$reason:reattach")
        try {
            val displayContext = createDisplayContext(target)
            val windows = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windows == null) {
                scheduleRetry("$reason:no-window-manager")
                return
            }
            val metrics = DisplayMetrics()
            target.getRealMetrics(metrics)
            val density = if (metrics.density <= 0f) 1f else metrics.density
            val prefs = Prefs(applicationContext)
            val mainPx = prefs.overlayMainSp * density
            val subPx = prefs.overlaySubSp * density
            val rows = ClusterPanelGeometry.rows(prefs.clusterRows)

            val view = ClusterLyricsView(displayContext)
            view.configure(
                mainPx, subPx, rows,
                accentOf(prefs), mutedOf(prefs),
                backgroundOf(prefs),
                styleOf(prefs),
            )

            val width = ClusterPanelGeometry.width(metrics.widthPixels, prefs.overlayWidthPct / 100f)
            val height = ClusterPanelGeometry.height(mainPx, subPx, rows)
            val origin = ClusterPanelGeometry.origin(
                metrics.widthPixels, metrics.heightPixels, width, height,
                prefs.overlayXPct / 100f, prefs.overlayYPct / 100f,
            )

            val params = WindowManager.LayoutParams(
                width,
                height,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    // Android 7 及更早没有 TYPE_APPLICATION_OVERLAY，只能用 TYPE_PHONE。
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
            // 绝对定位：存的是面板中心，几何已经把它夹回屏内。
            params.gravity = Gravity.TOP or Gravity.START
            params.x = origin[0]
            params.y = origin[1]
            params.title = "CarDesk 歌词投屏"
            windows.addView(view, params)

            panel = view
            displayWindows = windows
            attachedDisplayId = target.displayId
            failures = 0
            lastReport = "已投射 · " + target.name + " · ID " + target.displayId
            Logx.i(
                LogTags.CLUSTER,
                "歌词已投到副屏",
                "display" to target.name,
                "displayId" to target.displayId,
                "size" to "${metrics.widthPixels}x${metrics.heightPixels}",
                "density" to density,
                "panel" to "${width}x$height",
            )
            val initial = readSnapshot()
            lastDrawn = initial
            view.snapshot = initial
            startFrames()
            startFeed()
        } catch (failed: Throwable) {
            // 私有屏、悬浮窗权限被收、窗口管理器已经没了。
            // 保持状态干净并退避，别在紧循环里重试。
            panel = null
            displayWindows = null
            attachedDisplayId = -1
            Logx.w(
                LogTags.CLUSTER,
                "加窗失败",
                "reason" to reason,
                "error" to failed.javaClass.simpleName,
                "message" to (failed.message ?: ""),
            )
            scheduleRetry("$reason:" + failed.javaClass.simpleName)
        }
    }

    private fun detach(reason: String) {
        val windows = displayWindows
        val view = panel
        if (windows != null && view != null) {
            runCatching { windows.removeViewImmediate(view) }
        }
        panel = null
        displayWindows = null
        attachedDisplayId = -1
        lastDrawn = null
        if (lastReport.startsWith("已投射")) lastReport = "已断开 · " + reason
    }

    private fun scheduleRetry(reason: String) {
        failures++
        if (failures <= 1 || reason.startsWith("retry")) {
            lastReport = "投射失败 · " + reason
        }
        val delay = minOf(RETRY_MAX_MS, RETRY_MIN_MS shl minOf(5, maxOf(0, failures - 1)))
        handler.postDelayed({ reconcile("retry") }, delay)
    }

    private fun healthy(view: ClusterLyricsView): Boolean = try {
        view.isAttachedToWindow && view.parent != null
    } catch (gone: Throwable) {
        false
    }

    private fun describe(view: ClusterLyricsView): String = try {
        if (view.isAttachedToWindow) "detached-from-parent" else "detached-from-window"
    } catch (gone: Throwable) {
        "unreadable"
    }

    // ------------------------------------------------------------ 配色

    private fun accentOf(prefs: Prefs): Int =
        if (prefs.clusterLyricMainColor != 0) prefs.clusterLyricMainColor else DEFAULT_ACCENT

    private fun mutedOf(prefs: Prefs): Int =
        if (prefs.clusterLyricSubColor != 0) prefs.clusterLyricSubColor else DEFAULT_MUTED

    /** 底板颜色：alpha 由「仪表背景透明度」百分比换算。 */
    private fun backgroundOf(prefs: Prefs): Int {
        if (prefs.overlayBgAlpha <= 0) return 0x00000000
        val alpha = (255 * prefs.overlayBgAlpha / 100f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or 0x00101820
    }

    private fun styleOf(prefs: Prefs): Int = when (prefs.clusterStyle) {
        "plain" -> STYLE_PLAIN
        "glow" -> STYLE_GLOW
        else -> STYLE_PILL
    }

    private fun now(): Long = System.currentTimeMillis()
}
