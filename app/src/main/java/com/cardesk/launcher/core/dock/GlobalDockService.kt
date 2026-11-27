package com.cardesk.launcher.core.dock

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.R
import com.cardesk.launcher.core.DockQuickApps
import com.cardesk.launcher.core.HomeForeground
import com.cardesk.launcher.core.NavSlot
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.OemDockPolicy
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.DockMediaLaunch
import com.cardesk.launcher.core.media.LyricTimeline
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.core.media.TrackInfo
import com.cardesk.launcher.ui.shell.DockBarView
import com.cardesk.launcher.ui.shell.EnhancedNavBarView
import com.cardesk.launcher.ui.shell.NavSlotBarView
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 全局 Dock 的宿主前台服务（第八十七轮）。
 *
 * ## 它解决什么问题
 *
 * 桌面自己的 [DockBarView] 只活在 `LauncherView` 的窗口里：一旦切到别的应用
 * （导航、音乐、设置…）就消失，原车 Dock 又冒出来。用户要的是
 * 「Dock 栏顶替原车 Dock、在其它应用里也在」。
 *
 * 所以这里起一个**前台服务**，用 `WindowManager` 往屏幕上挂一个
 * **常驻的、贴底的** `DockBarView` 窗口：
 *
 * - 桌面（HOME）在前台时不显示：桌面自己就画着同一套 Dock，重复画既没价值
 *   还会闪（详见 `work/r87-design.md`「二、全局 Dock 工作流」的模型说明）。
 * - 其它应用在前台时显示：窗口正好压在系统导航栏 / 原车 Dock 的位置上，
 *   用**不透明的底**把它完全遮住，看起来就是 Dock 被替换了。
 *
 * ## 为什么窗口要「只 addView 一次」
 *
 * 每次 show/hide 都 `addView`/`removeView` 会**重建** `DockBarView`：
 * 媒体封面重新解码（闪一下）、动画状态归零、触摸态丢失。
 * 所以整棵视图只在服务启动时挂一次，之后只改 `visibility`。
 *
 * ## 为什么用容器 + 等比缩放，而不是直接把 `DockBarView` 做成屏宽
 *
 * `DockBarView` 是按 1920×1080 设计板画的（1864×104，距底 8），内部坐标全是
 * 设计像素。不同车机屏宽不一样，直接按真实像素画会让 Dock 只占中间一截。
 * 这里放一个 1920×112 的「设计层」，按 `真实屏宽 / 1920` 等比缩放 ——
 * 与 `LauncherView` 的 `Design.scaleFor()` 是同一套思路。
 *
 * ## 窗口类型（两条路都必须能跑）
 *
 * - `NavInsets.applied()` 为真：系统已经给原车导航栏预留了 112px，
 *   用 `TYPE_NAVIGATION_BAR_PANEL`(2024) 把 Dock 画进预留区。
 * - 否则（**默认路径**，也是模拟器/普通安装包上唯一能跑的路径）：
 *   `TYPE_APPLICATION_OVERLAY`(2038)，需要 `SYSTEM_ALERT_WINDOW`。
 *   拿不到 2024（普通签名的包会抛 `SecurityException`）时这里会自动退回 2038。
 *
 * ## 第三百零五轮：让位不能让窗口
 *
 * 设置页 / 启动页整屏覆盖时，这条窗口**不能**跟着隐身 —— 实车 m01073 的证据是：
 * 预留区路径下原车 `CarNavigationBar` 和我们这个 2024 面板占同一段
 * `[0,968][1920,1080]`，它全靠面板的**不透明底**盖着；旧逻辑一 `INVISIBLE`，
 * 底部 112px 立刻露出原车 Dock（用户第 2 点）。
 *
 * 所以让位改成了「空白占位」（[applyBlank]）：窗口留 VISIBLE 继续盖住原车栏，
 * 只把 Dock 内容 `GONE`，底图由调用方用 `PixelCopy` 拷「接缝上方那条真实像素」
 * 进来（`LauncherView.captureSettingsBottomCover`），与上面的画面逐像素连续。
 */
class GlobalDockService : Service() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var windowManager: WindowManager

    /** 窗口根：整条 112px 的底栏条（不透明底，负责遮住原车 Dock）。 */
    private var root: FrameLayout? = null

    /** 设计层：固定 1920×112，按屏宽等比缩放；`DockBarView` 挂在它里面。 */
    private var designLayer: FrameLayout? = null

    private var dock: DockBarView? = null
    private var slotBar: NavSlotBarView? = null
    private var currentNavStyle = EnhancedNavBarView.MODE_DOCK
    private var params: WindowManager.LayoutParams? = null

    /** 当前挂载在设计层里的有效底栏（设计 Dock 或槽位条）。 */
    private val activeBarView: View? get() = dock ?: slotBar

    /**
     * 桌面是否在前台（**焦点**口径：`onResume`/`onPause`）。
     *
     * 默认 `true`：服务多数是从桌面的设置页拉起来的，此刻 HOME 就在前台，
     * 先当「桌面在前台」处理能避免窗口在桌面上一闪。
     */
    private var homeForeground = true

    /**
     * 桌面窗口是否**还在屏幕上**（第144轮：`onStart`/`onStop` 口径）。
     *
     * 为什么要跟 [homeForeground] 分开：桌面把画中画承载面里的高德拉起来时，
     * 焦点会被抢走 → 桌面走 `onPause`，**但桌面窗口还亮着**（用户眼前就是桌面）。
     * 风量键的语义要按「用户眼前是不是桌面」判，不能按「谁有焦点」判，
     * 否则桌面上点风量键会退化成「风量 +1」（真机实测，2026-11-07）。
     */
    private var homeOnScreen = false

    /** 当前是不是可见（`root.visibility` 的缓存，避免重复 setVisibility）。 */
    private var showing = false

    /**
     * 第三百零五轮：「空白占位」态 —— 让位但**窗口仍然盖住原车导航栏**。
     *
     * 只在「预留区生效（2024 面板）」时才可能为真，见 [updateVisibility]。
     */
    private var blank = false

    /**
     * 当前**已经贴在窗口上**的那张占位底图（[blank] 态用）。
     *
     * 换图 / 退出占位时用它把旧 Bitmap 回收掉 —— `PixelCopy` 每开一次设置页
     * 就产出一张 1920×112 的图（约 860KB），不回收会一直涨。
     */
    private var appliedCoverBitmap: Bitmap? = null
    private var lookReceiver: android.content.BroadcastReceiver? = null

    /** 这次窗口用的是不是 2024（导航栏面板）。 */
    private var nativeInsets = false
    private var insetsAttemptRunning = false

    private var tick = 0

    /** 媒体状态变化 → 立刻重绑 Dock / 槽位条的媒体卡（进度条另外靠 ticker 拉）。 */
    private val mediaListener = object : MusicController.Listener {
        override fun onMediaChanged(track: TrackInfo?) {
            dock?.refreshFromController()
            bindSlotBarMedia()
        }

        override fun onLyricChanged(timeline: LyricTimeline?, index: Int, wipe: Float) {
            slotBar?.let { bar ->
                bar.lyricCurrent = if (timeline != null && index >= 0) timeline.lineAt(index) else ""
                bar.lyricNext = if (timeline != null && index >= 0) timeline.lineAt(index + 1) else ""
                bar.lyricWipe = if (timeline != null && index >= 0) wipe else 0f
                bar.invalidate()
            }
        }
    }

    /** 空调状态变化 → 重绑温度 / 风量 / 模式。 */
    private val hvacObserver: (HvacState) -> Unit = { state ->
        dock?.bind(state)
        slotBar?.state = state
    }

    /**
     * 1s 刷一次媒体进度。
     *
     * 为什么不能只靠监听：播放器不一定持续推 `positionMs`（尤其是暂停时），
     * 进度条光靠回调会停在原地。
     */
    private val refreshTicker = object : Runnable {
        override fun run() {
            if (root == null) return
            tick++
            dock?.refreshFromController()
            if (slotBar != null) bindSlotBarMedia()
            // 第204轮：前台切到沉浸型 App 时让位（每 tick 判一次，getRunningTasks 很便宜）
            refreshImmersiveForeground()
            // 空调状态平时靠 [hvacObserver] 推，但服务可能比 VehicleController
            // 先起来（开机广播先于桌面），那一次 observer 还没注册上 —— 低频再拉一遍兜底。
            if (tick % HVAC_REFRESH_TICKS == 0) bindHvac()
            // 快捷应用的图标 / 应用名偶尔会变（装/卸应用），低频重绑即可。
            if (tick % QUICK_APP_REFRESH_TICKS == 0) bindQuickApps()
            // 第159轮：底部占位采样（**只读**，不改任何运行时行为）。
            // 诊断页在桌面进程、服务在服务进程，所以采样结果落盘给诊断页读。
            if (tick % BOTTOM_PROBE_TICKS == 0) writeBottomProbe()
            main.postDelayed(this, TICK_MS)
        }
    }

    /**
     * 写一次底部占位采样（第159轮）。
     *
     * ## 这一段存在的意义
     *
     * 「别的 App 会不会被全局 Dock 盖住底部」以前**说不清**：
     * 覆盖包 enabled ≠ framework-res 真的改了导航栏高度（假阳性），
     * 而诊断页里也没有任何 Dock 占位信息，现场只能靠猜。
     *
     * 这里把能测的都测出来落盘：窗口类型、是否可见、导航栏实测高度、
     * Dock 占哪一段、前台是谁。**纯只读**，不参与任何显隐决策。
     */
    private fun writeBottomProbe() {
        val (w, h) = DockBottomProbe.screenSize(this) ?: return
        val view = root
        DockBottomProbe.writeSnapshot(
            this,
            mapOf(
                "采样时刻" to SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                "窗口类型" to if (nativeInsets) "2024 导航栏面板" else "2038 普通悬浮窗",
                "窗口可见" to (view?.visibility == View.VISIBLE),
                "覆盖包enabled" to runCatching { NavInsets.applied(this) }.getOrDefault(false),
                "导航栏实测px" to DockBottomProbe.systemNavBarHeightPx(this),
                "屏幕" to "${w}x$h",
                "Dock顶边y" to DockBottomProbe.dockTopY(h, w),
                "Dock高px" to DockBottomProbe.dockBarHeightPx(w),
                "桌面在前台" to homeForeground,
                "前台应用包名" to foregroundPackage(),
            ),
        )
    }

    /**
     * 当前前台应用包名（尽力而为）。
     *
     * 拿不到就返回 null —— 不影响任何功能，只是诊断里少一行。
     */
    private fun foregroundPackage(): String? = runCatching {
        val am = getSystemService(android.app.ActivityManager::class.java) ?: return@runCatching null
        am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
    }.getOrNull()

    /**
     * 调试旁路（仅 debuggable 变体注册）。
     *
     * 第201轮起 Dock **不再让位**，原来的强制让位广播随让位逻辑一起删掉，
     * 只剩 Dock 外观调试通道（圆角）。
     */
    private fun installDebugHooks() {
        if (lookReceiver != null) return
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return

        // 第183轮：Dock 外观调试通道（圆角）。设置页滑杆不方便脚本化验证时用它。
        val look = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val radius = intent?.getIntExtra("radius", -1) ?: -1
                if (radius >= 0) {
                    prefs.dockCornerRadius = radius
                    DockBarView.refreshAll(this@GlobalDockService)
                    Logx.i(LogTags.DOCK, "Dock 圆角（调试注入）", "radius" to prefs.dockCornerRadius)
                }
            }
        }
        runCatching {
            registerReceiver(look, android.content.IntentFilter("com.cardesk.launcher.DEBUG_DOCK_LOOK"))
            lookReceiver = look
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        Logx.init(applicationContext)
        prefs = Prefs(applicationContext)

        // 必须先 startForeground 再判断退出：`startForegroundService()` 之后
        // 5 秒内不调 startForeground，系统会抛 RemoteServiceException（Android 8+），
        // 那是**启动即崩**，比「静默退出」难排查得多。
        startForeground(NOTIFICATION_ID, buildNotification())

        // 第144轮：服务起来时先对齐桌面的「窗口是否还在屏幕上」状态 ——
        // 服务可能在桌面 `onStart` 之前就起来了（开机广播），也可能之后才起来。
        homeOnScreen = HomeForeground.isOnScreen

        val overlay = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        val navInsetsSupported = runCatching { NavInsets.supported(this) }.getOrDefault(false)
        if (!prefs.globalDockEnabled || !(overlay || navInsetsSupported)) {
            Logx.i(
                LogTags.DOCK,
                "全局 Dock 服务退出：开关关闭或缺权限",
                "enabled" to prefs.globalDockEnabled,
                "overlay" to overlay,
                "navInsetsSupported" to navInsetsSupported,
            )
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Logx.i(
            LogTags.DOCK,
            "全局 Dock 服务启动",
            "overlay" to overlay,
            "pid" to android.os.Process.myPid(),
        )
        startWindow()
        attachRefresh()
        // 服务一起来就把 policy 写成「全局 Dock 模式」：如果用户是先装好 Dock、
        // 桌面进程还没跑起来（比如冷启动直接开导航），policy 也得是全局 Dock 的那套，
        // 否则原车 Dock 会先露出来一会儿。
        applyPolicyAsync("服务启动")
        ensureNavInsets()    }

    // ---------------------------------------------------------------- 窗口

    /**
     * 建窗口：整棵视图只 `addView` 这一次。
     */
    private fun startWindow() {
        installDebugHooks()
        if (root != null) return
        nativeInsets = runCatching { NavInsets.applied(this) }.getOrDefault(false)

        val layer = FrameLayout(this).apply {
            // 设计层自己不画东西，Dock / 槽位条的坐标直接按 1920×112 板子摆。
            clipChildren = false
        }
        designLayer = layer
        mountCurrentStyle(layer)

        val host = FrameLayout(this).apply {
            addView(layer, FrameLayout.LayoutParams(DESIGN_WIDTH, DESIGN_BAR_HEIGHT))
        }

        host.setOnTouchListener { _, ev -> handleCollapsedTouch(ev) }

        root = host
        applyRootBackground(null, OPAQUE_BAR_COLOR)
        val lp = buildParams()
        params = lp
        applyGeometry()
        host.visibility = View.INVISIBLE
        showing = false

        if (!addViewWithFallback(host, lp)) {
            stopSelf()
            return
        }
        Logx.i(
            LogTags.DOCK,
            "全局 Dock 窗口已创建",
            "type" to lp.type,
            "w" to lp.width,
            "h" to lp.height,
            "y" to lp.y,
            "navStyle" to currentNavStyle,
        )
        bindHvac()
        bindQuickApps()
        bindSlotBarMedia()
        updateVisibility()
    }

    /** 在设计层统一挂载「设计 Dock」（导航栏样式与槽位条设置已下线，忽略旧偏好残留）。 */
    private fun mountCurrentStyle(layer: FrameLayout) {
        layer.removeAllViews()
        dock = null
        slotBar = null
        currentNavStyle = EnhancedNavBarView.MODE_DOCK
        val lp = FrameLayout.LayoutParams(DESIGN_DOCK_WIDTH, DESIGN_DOCK_HEIGHT).apply {
            leftMargin = DOCK_LEFT_MARGIN
            topMargin = 0
        }
        val bar = DockBarView(this).apply {
            wireCallbacks(this)
        }
        dock = bar
        layer.addView(bar, lp)
        Logx.i(LogTags.DOCK, "全局 Dock 挂载设计 Dock")
    }

    /** 设置页切换导航栏样式或修改槽位后，实时刷新全局 Dock 内容。 */
    private fun reloadStyleAndSlots() {
        val layer = designLayer ?: return
        val targetStyle = prefs.navStyle
        if (targetStyle != currentNavStyle || (targetStyle == EnhancedNavBarView.MODE_SLOTS && slotBar == null)) {
            mountCurrentStyle(layer)
        } else if (targetStyle == EnhancedNavBarView.MODE_SLOTS) {
            slotBar?.let { bar ->
                bar.palette = if (Skin.isNight) Palettes.night else Palettes.day
                bar.slots = NavSlots.load(prefs)
                bar.cornerRadius = prefs.dockCornerRadius.toFloat()
                bar.invalidate()
            }
        }
        bindHvac()
        bindQuickApps()
        bindSlotBarMedia()
        updateVisibility()
    }

    /** 绑定槽位条媒体信息与歌词。 */
    private fun bindSlotBarMedia() {
        val bar = slotBar ?: return
        val controller = runCatching { MusicController.get() }.getOrNull() ?: return
        val track = controller.track
        bar.palette = if (Skin.isNight) Palettes.night else Palettes.day
        bar.mediaPlaying = track?.playing == true
        bar.mediaTitle = track?.title?.ifBlank { "暂无播放" } ?: "暂无播放"
        bar.mediaArtist = track?.artist?.ifBlank { "" } ?: ""
        bar.mediaArtwork = track?.artwork
        bar.invalidate()
    }

    /** 给全局 Dock 的槽位条接上所有动作回调。 */
    private fun wireSlotBarCallbacks(bar: NavSlotBarView) {
        bar.onSlot = { slot -> handleGlobalSlot(slot) }
        bar.onTemp = { which, delta ->
            val zone = if (which == NavSlotBarView.ZONE_PASSENGER_KEY) HvacIds.ZONE_PASSENGER else HvacIds.ZONE_DRIVER
            if (delta == 0) toLauncher(MainActivity.ACTION_CLIMATE)
            else VehicleController.stepTemp(zone, delta.toFloat())
        }
        bar.onRecents = { toLauncher(MainActivity.ACTION_RECENTS) }
        bar.onMediaCover = { toLauncher(MainActivity.ACTION_MEDIA) }
        bar.onMediaTitle = {
            val track = MusicController.get().track
            val action = DockMediaLaunch.openCurrentApp(
                context = this,
                trackPkg = track?.pkg,
            )
            DockMediaLaunch.messageFor(action)?.let { toast(it) }
        }
        bar.onMediaPlay = { togglePlayback() }
    }

    /** 全局 Dock 槽位条点击分发。 */
    private fun handleGlobalSlot(slot: NavSlot) {
        Logx.event(LogTags.DOCK, "全局 Dock 点击槽位", "type" to slot.type, "action" to slot.actionId, "label" to slot.label)
        when {
            slot.type == NavSlot.TYPE_MEDIA -> toLauncher(MainActivity.ACTION_MEDIA)
            slot.type == NavSlot.TYPE_LYRIC -> toLauncher(MainActivity.ACTION_MEDIA)
            slot.type == NavSlot.TYPE_DATETIME -> Unit
            slot.type == NavSlot.TYPE_APP -> {
                when {
                    slot.isPip1Launch -> toLauncher("${MainActivity.ACTION_PIP1}:${slot.pkg}")
                    slot.isPip2Launch -> toLauncher("${MainActivity.ACTION_PIP2}:${slot.pkg}")
                    else -> launchQuickApp(slot.pkg)
                }
            }
            else -> when (slot.actionId) {
                NavSlot.ACTION_BACK -> sendSystemBack()
                NavSlot.ACTION_HOME -> {
                    if (homeOnScreen || pipFullscreen) {
                        runCatching { MainActivity.onExternalHome() }
                            .onFailure { Logx.w(LogTags.DOCK, "外部 HOME 处理失败", it) }
                    } else {
                        goHome()
                    }
                }
                NavSlot.ACTION_TASKS -> toLauncher(MainActivity.ACTION_DRAWER)
                NavSlot.ACTION_SETTINGS -> toLauncher(MainActivity.ACTION_SETTINGS)
                NavSlot.ACTION_SPLIT -> toLauncher(MainActivity.ACTION_SPLIT)
                NavSlot.ACTION_CAMERA_360 -> toLauncher(MainActivity.ACTION_CAMERA360)
                NavSlot.ACTION_MEDIA, NavSlot.ACTION_LYRIC -> toLauncher(MainActivity.ACTION_MEDIA)
                NavSlot.ACTION_HVAC_POWER -> VehicleController.togglePower()
                NavSlot.ACTION_HVAC_AC -> VehicleController.toggleAc()
                NavSlot.ACTION_HVAC_AUTO -> VehicleController.toggleAuto()
                NavSlot.ACTION_HVAC_CIRC -> VehicleController.toggleCirculation()
                NavSlot.ACTION_HVAC_DEFROST_FRONT -> VehicleController.toggleDefrostFront()
                NavSlot.ACTION_HVAC_DEFROST_REAR -> VehicleController.toggleDefrostRear()
                NavSlot.ACTION_HVAC_FAN -> {
                    val action = DockFanPolicy.decide(homeOnScreen)
                    when (action) {
                        DockFanPolicy.ACTION_OPEN_CLIMATE -> toLauncher(MainActivity.ACTION_CLIMATE)
                        else -> VehicleController.stepFan(1)
                    }
                }
                NavSlot.ACTION_HVAC_TEMP_DRIVER, NavSlot.ACTION_HVAC_TEMP_PASSENGER ->
                    toLauncher(MainActivity.ACTION_CLIMATE)
                else -> Unit
            }
        }
    }

    /**
     * 加窗口；2024 失败时退回 2038。
     *
     * 2024（`TYPE_NAVIGATION_BAR_PANEL`）需要平台签名 / 系统应用，
     * 普通安装包里 `addView` 会直接抛 `SecurityException` / `BadTokenException`。
     * 契约要求「拿不到 RRO 时一切照常」，所以这里必须能退。
     */
    private fun addViewWithFallback(view: View, lp: WindowManager.LayoutParams): Boolean {
        val first = runCatching { windowManager.addView(view, lp) }
        if (first.isSuccess) return true
        Logx.w(
            LogTags.DOCK,
            "全局 Dock 窗口（2024 导航栏面板）添加失败，退回 2038 悬浮窗",
            first.exceptionOrNull() ?: Throwable("addView failed"),
        )
        nativeInsets = false
        lp.type = TYPE_APPLICATION_OVERLAY
        if (!runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)) {
            Logx.w(LogTags.DOCK, "没有 SYSTEM_ALERT_WINDOW，全局 Dock 无法显示")
            toast("没有悬浮窗权限，无法显示全局 Dock")
            return false
        }
        val second = runCatching { windowManager.addView(view, lp) }
        second.onFailure { Logx.e(LogTags.DOCK, "全局 Dock 窗口创建失败", it) }
        return second.isSuccess
    }

    /**
     * 窗口参数。
     *
     * flags 照参考工程 UNI 的 `GlobalDockService`：
     * 不抢焦点（否则会顶掉输入法 / 游戏）、铺满全屏、不吃背后应用的手势、
     * 硬件加速。`PixelFormat.TRANSLUCENT` 让圆角之外透明。
     */
    private fun buildParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply {
            type = if (nativeInsets) TYPE_NAVIGATION_BAR_PANEL else TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.LEFT
            @Suppress("DEPRECATION")
            setTitle("CarDesk 全局 Dock")
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    /**
     * 算尺寸 / 位置并等比缩放设计层。
     *
     * 高度 = `round(屏宽 * 112f / 1920f)`：Dock 设计高 104，底边距 8，
     * 合起来 112；按屏宽等比换算保证任何分辨率下都「贴着底、和桌面里一样大」。
     */
    private fun applyGeometry() {
        val lp = params ?: return
        val (w, h) = realSize()
        val barHeight = (w * DESIGN_BAR_HEIGHT.toFloat() / DESIGN_WIDTH.toFloat())
            .roundToInt()
            .coerceAtLeast(1)
        lp.width = w
        lp.height = barHeight
        lp.x = 0
        lp.y = h - barHeight

        designLayer?.let { layer ->
            layer.pivotX = 0f
            layer.pivotY = 0f
            layer.scaleX = w / DESIGN_WIDTH.toFloat()
            // 高度用真实条高反推：round() 会让条高偏离 112×scale 一点点，
            // 用实际条高做 Y 缩放，边缘不会露出 1px 缝。
            layer.scaleY = barHeight / DESIGN_BAR_HEIGHT.toFloat()
        }

        val view = root ?: return
        if (view.parent != null) {
            runCatching { windowManager.updateViewLayout(view, lp) }
                .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 尺寸更新失败", it) }
        }
    }

    /** 真实屏尺寸（含系统栏），用 `getRealMetrics` 拿全屏而不是应用可用区。 */
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 切可见性 —— 全服务唯一的「显示 / 隐藏」入口，`addView` 不在这里。
     */
    /**
     * 让系统真正给 Dock 预留一条 112px 的导航栏区域。
     *
     * 全局 Dock 想要「其它应用只显示在 Dock 上方」，只能靠系统导航栏 inset：
     * 用 RRO 覆盖包把导航栏高度改成 112px，再用 `TYPE_NAVIGATION_BAR_PANEL`
     * 占据这块区域。反之写 `immersive.navigation=*` 会让应用全屏、底部被悬浮
     * Dock 盖住（用户反馈的问题）。平台签名包才有 INSTALL_PACKAGES /
     * CHANGE_OVERLAY_PACKAGES / STATUS_BAR_SERVICE 能力；能力不足时安静降级。
     */
    private fun ensureNavInsets() {
        if (nativeInsets || insetsAttemptRunning) return
        if (runCatching { NavInsets.applied(this) }.getOrDefault(false)) {
            main.post { rebuildWindowWithInsets() }
            return
        }
        insetsAttemptRunning = true
        Logx.i(LogTags.DOCK, "尝试启用系统预留区")
        NavInsets.prepare(this) {
            val ok = runCatching { NavInsets.activate(this) }.getOrDefault(false)
            insetsAttemptRunning = false
            Logx.i(
                LogTags.DOCK,
                "系统预留区启用结果",
                "ok" to ok,
                "supported" to runCatching { NavInsets.supported(this) }.getOrDefault(false),
                "applied" to runCatching { NavInsets.applied(this) }.getOrDefault(false),
            )
            if (ok) {
                main.post {
                    rebuildWindowWithInsets()
                    applyPolicyAsync("预留区已启用")
                }
            }
        }
    }

    /** 预留区生效后，把悬浮 Dock 换成导航栏面板窗口（正好占据系统预留的 112px）。 */
    private fun rebuildWindowWithInsets() {
        val view = root ?: return
        if (nativeInsets) return
        Logx.i(LogTags.DOCK, "预留区生效，按导航栏面板重建 Dock 窗口")
        runCatching { windowManager.removeViewImmediate(view) }
        root = null
        designLayer = null
        dock = null
        slotBar = null
        params = null
        showing = false
        startWindow()
        applyGeometry()
    }

    private fun updateVisibility() {
        val view = root ?: return
        // 第201轮：Dock **永不让位**。
        //
        // 原车 CarNavigationBar 与我们这个 2024 面板窗口占的是同一段
        // [0,968][1920,1080]，且面板在它上面 —— 只要我们 INVISIBLE，
        // 原车导航栏就会露出来（第193轮实测）。policy_control 的
        // immersive.navigation/full 也压不住这台车的定制导航栏，
        // 所以唯一可靠的做法是：Dock 始终在，用不透明底把它盖住。
        //
        // 代价：自己申请沉浸全屏的 App（网易云 / 汽水音乐）底部 112px
        // 会被 Dock 压住，和原车导航栏本来的占位一样。
        //
        // 预留区生效时，Dock 就是系统导航栏的替代品：桌面页也要保持可见，
        // 否则桌面自己会空出一条原车导航栏（或黑边）。
        //
        // 第204轮：对沉浸名单里的 App 让位。原车栏这时由 policy_control 的
        // `immersive.navigation=<包名>` 一起藏住（见 [GlobalDockPolicyGate]），
        // 所以不会再出现「我们让位、原车栏冒出来」。
        val want = !suppressed && DockVisibility.want(
            nativeInsets = nativeInsets,
            homeForeground = homeForeground,
            immersiveAppForeground = immersiveAppForeground,
            hideHomeDock = prefs.hideHomeDock && !DockBarView.reorderMode,
        )
        // 第205轮：沉浸 App 前台时不再彻底隐藏，而是「收起」——
        // 窗口留着接上滑手势，内容藏起来、底透明，画面仍然全屏。
        val collapseNow = !want && immersiveAppForeground && !homeForeground
        // 第三百零五轮：让位（设置页 / 启动页）时**不能让窗口消失**。
        //
        // 实车 m01073：设置页打开时原车 Dock 会露出来。根因就是这里 ——
        // 预留区路径下原车 `CarNavigationBar` 与我们这个 2024 面板占同一段
        // [0,968][1920,1080]，它靠面板的**不透明底**盖住；而 [suppressed]
        // 一开，旧逻辑直接 `INVISIBLE` ⇒ 底部 112px 立即露出原车 Dock。
        // 现在改成「空白占位」：窗口留 VISIBLE、铺一层与背景同色的不透明底、
        // Dock 内容 GONE（不画按钮、也不吃按键）。
        val blankNow = !want && !collapseNow && suppressed && nativeInsets
        when {
            want -> {
                blank = false
                applyDockVisible(true)
            }
            blankNow -> applyBlank()
            collapseNow -> {
                blank = false
                if (!collapsed) applyCollapsed()
            }
            else -> {
                blank = false
                applyDockVisible(false)
            }
        }
        // 空白占位虽然不画 Dock，但**确实盖在原车栏上** ——
        // `isShowing()` 要对桌面报「盖着」，否则桌面会把自绘 Dock 又放出来。
        showing = want || blankNow
        Logx.i(
            LogTags.DOCK,
            "全局 Dock 可见性",
            "visible" to want,
            "blank" to blankNow,
            "collapsed" to collapsed,
            "homeForeground" to homeForeground,
            "immersiveApp" to immersiveAppForeground,
        )
    }

    /** 正常态：窗口 + 内容都显示，不透明底盖住原车栏。 */
    private fun applyDockVisible(visible: Boolean) {
        val view = root ?: return
        val wasCollapsed = collapsed
        cancelCollapse()
        collapsed = false
        if (wasCollapsed && visible) applyGeometry()
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        applyRootBackground(null, if (visible) OPAQUE_BAR_COLOR else android.graphics.Color.TRANSPARENT)
        activeBarView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 给窗口（root）铺背景，并回收上一张占位底图。
     *
     * 必须先换背景再回收：`BitmapDrawable` 被替换掉之后旧 Bitmap 才没人引用。
     * 全程在 UI 线程（[root] 的绘制线程）上做，中间不会插进一次 draw。
     */
    private fun applyRootBackground(bitmap: Bitmap?, color: Int) {
        val view = root ?: return
        val vg = view as? android.view.ViewGroup ?: return
        if (bitmap != null && !bitmap.isRecycled) {
            vg.background = BitmapDrawable(view.resources, bitmap)
        } else if (color == OPAQUE_BAR_COLOR) {
            val radiusDesign = runCatching { prefs.dockCornerRadius.toFloat() }.getOrDefault(0f)
            val (w, _) = runCatching { realSize() }.getOrDefault(DESIGN_WIDTH to 1080)
            val radiusPx = radiusDesign * (w / DESIGN_WIDTH.toFloat())
            if (radiusPx > 0.5f) {
                vg.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(color)
                    cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
                }
            } else {
                vg.setBackgroundColor(color)
            }
        } else {
            vg.setBackgroundColor(color)
        }
        val old = appliedCoverBitmap
        appliedCoverBitmap = bitmap
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
    }

    /**
     * 空白占位态（第三百零五轮）：让位但**仍然盖住原车导航栏**。
     *
     * 设置页 / 启动页整屏覆盖时，画板里底部那条是「背景」，没有 Dock；
     * 但实车上原车 `CarNavigationBar` 就画在下面 —— 我们一让位它就露出来
     * （用户 m01073 第 2 点）。所以这里不退场，只把**内容**藏掉：
     *
     * | 项 | 值 |
     * |---|---|
     * | 窗口 | 保持 `VISIBLE`、整条 112px（收起态要先还原几何） |
     * | 底图 | 调用方给的「背景图」（[suppressedCoverBitmap]）优先；没有就用底色（[suppressedCoverColor]），再缺省 [OPAQUE_BAR_COLOR] |
     * | Dock 内容 | `GONE` —— 不画按钮，也不响应按键 |
     *
     * 底图由 `LauncherView.showSettings()` 用 **`PixelCopy`** 从窗口合成结果里拷
     * 「接缝上方那条真实像素」送进来（[LauncherView.captureSettingsBottomCover]）——
     * 和上面的画面逐像素一致，接缝彻底看不到。拿不到时退回单色
     * （[suppressedCoverColor]，实测与接缝上方差 10~13/255，只作兜底）。
     */
    private fun applyBlank() {
        val view = root ?: return
        val wasCollapsed = collapsed
        cancelCollapse()
        collapsed = false
        if (wasCollapsed) applyGeometry()
        blank = true
        view.visibility = View.VISIBLE
        applyRootBackground(
            suppressedCoverBitmap,
            if (suppressedCoverColor != 0) suppressedCoverColor else OPAQUE_BAR_COLOR,
        )
        activeBarView?.visibility = View.GONE
    }

    /**
     * 收起态：窗口缩成屏幕最底部的一条**窄透明条**，只用来接「上滑唤出」。
     *
     * 第205轮第一版把整条 112px 都留成透明窗口，结果 App 底部那 112px
     * 看得见、摸不着（触摸全被我们吃掉）—— 用户实测反馈。现在只留
     * [COLLAPSED_STRIP_PX] 高的一条，其余区域交还给 App。
     */
    private fun applyCollapsed() {
        val view = root ?: return
        val lp = params ?: return
        collapsed = true
        cancelCollapse()
        view.visibility = View.VISIBLE
        val (_, h) = realSize()
        val strip = COLLAPSED_STRIP_PX
        if (lp.height != strip || lp.y != h - strip) {
            lp.height = strip
            lp.y = h - strip
            if (view.parent != null) {
                runCatching { windowManager.updateViewLayout(view, lp) }
                    .onFailure { Logx.w(LogTags.DOCK, "收起态窗口缩放失败", it) }
            }
        }
        applyRootBackground(null, android.graphics.Color.TRANSPARENT)
        activeBarView?.visibility = View.GONE
    }

    /** 从底部上滑时展开我们自己的 Dock（几秒后自动收起）。 */
    private fun revealFromCollapsed() {
        val view = root ?: return
        if (!collapsed) return
        Logx.i(LogTags.DOCK, "Dock 从收起态展开（底部上滑）")
        collapsed = false
        view.visibility = View.VISIBLE
        applyGeometry()
        applyRootBackground(null, OPAQUE_BAR_COLOR)
        activeBarView?.visibility = View.VISIBLE
        scheduleCollapse()
    }

    private fun scheduleCollapse() {
        cancelCollapse()
        val runnable = Runnable {
            if (immersiveAppForeground && !homeForeground) {
                Logx.i(LogTags.DOCK, "Dock 自动收起（沉浸 App 仍在前台）")
                applyCollapsed()
            }
        }
        collapseRunnable = runnable
        main.postDelayed(runnable, REVEAL_AUTO_COLLAPSE_MS)
    }

    private fun cancelCollapse() {
        collapseRunnable?.let { main.removeCallbacks(it) }
        collapseRunnable = null
    }

    /**
     * 收起态下接管底部触摸：上滑 → 展开我们的 Dock。
     *
     * 这是第204轮「让位」之后的关键一环——让位让原车导航栏也有了被唤出的机会，
     * 用户上滑唤出的是原车栏；这里改成由我们接住手势、展开自己的 Dock。
     */
    private fun handleCollapsedTouch(ev: android.view.MotionEvent): Boolean {
        if (!collapsed) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                collapsedDownY = ev.rawY
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dy = collapsedDownY - ev.rawY
                if (dy > REVEAL_SWIPE_PX) {
                    revealFromCollapsed()
                    return true
                }
                collapsedDownY = 0f
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                collapsedDownY = 0f
                return true
            }
        }
        return true
    }

    /**
     * 第204轮：前台 App 是不是「我们让位」的沉浸型应用（网易云 / 汽水音乐）。
     *
     * 只认名单（[OemDockPolicy.hidesGlobalDockFor]），不再做 dumpsys 沉浸探测 ——
     * 那套探测在全局沉浸策略下会误判所有 App（第193轮的坑）。
     */
    private var immersiveAppForeground = false

    /**
     * 第205轮：沉浸 App 前台时 Dock 的「收起」态。
     *
     * 收起 ≠ 隐藏：窗口仍然 VISIBLE（透明、内容 GONE），
     * 目的是**占住底部 112px 的触摸区**——用户从底部上滑时由我们接住，
     * 展开我们自己的 Dock，而不是让系统把原车导航栏唤出来。
     */
    private var collapsed = false

    /** 从底部上滑展开后，多久自动收起。 */
    private var collapseRunnable: Runnable? = null

    /** 收起态下按下的 y（用于判上滑）。 */
    private var collapsedDownY = 0f

    /** 每个 tick 重新判一次前台应用（拿不到就保持原状）。 */
    private fun refreshImmersiveForeground() {
        val fg = if (homeForeground) null else foregroundPackage()
        val immersive = fg != null && OemDockPolicy.hidesGlobalDockFor(fg)
        if (immersive == immersiveAppForeground) return
        immersiveAppForeground = immersive
        Logx.i(LogTags.DOCK, "全局 Dock 让位判定", "immersiveApp" to immersive, "foreground" to fg)
        updateVisibility()
    }

    private fun setHomeForeground(value: Boolean) {
        if (homeForeground == value) return
        homeForeground = value
        updateVisibility()
    }

    /**
     * 第144轮：桌面窗口在不在屏幕上（`onStart`/`onStop` 口径）。
     *
     * 只影响**风量键的语义**，不影响全局 Dock 窗口自己的显隐：
     * 显隐必须留在焦点口径 [homeForeground] 上 —— 用户可能自己把桌面那条 Dock
     * 隐藏了（`hideHomeDock`，这台车就是），这时若按窗口口径把全局 Dock 藏掉，
     * 桌面上会**一条 Dock 都不剩**。
     */
    private fun setHomeOnScreen(value: Boolean) {
        if (homeOnScreen == value) return
        homeOnScreen = value
        Logx.i(
            LogTags.DOCK,
            "桌面窗口可见性",
            "onScreen" to value,
            "focused" to homeForeground,
        )
    }

    // ---------------------------------------------------------------- Dock 回调

    /**
     * 桌面切页 → 同步全局 Dock 的页号。
     *
     * ## 为什么需要
     *
     * 全局 Dock 是**另一个 [DockBarView] 实例**（悬浮窗里画的那条），
     * 页内 Dock 的 `activePage` 更新（`LauncherView.showPage`）**不会**传到它。
     * 以前这里写死 0 —— 于是站在画中画页上，全局 Dock 的主页键
     * 仍然高亮成「当前页」，指示本身就是错的。
     *
     * 服务与桌面**同进程**（manifest 里没有 `android:process`），
     * 所以直接走静态引用即可，不需要广播。
     */
    private fun setActivePage(page: Int) {
        dock?.activePage = page
        Logx.i(LogTags.DOCK, "全局 Dock 页号同步", "page" to page)
    }

    /**
     * 接上 Dock 的按钮。
     *
     * 与桌面里的语义差别（全局窗口没有页面上下文）：
     * - 主页短按 = 回桌面；
     * - **主页长按 = 系统返回**（第八十七轮 UX 要求）：全局 Dock 盖住原车导航栏后
     *   系统返回键就没了，不加第 5 个键（那会动 `DockLayout` 设计板），
     *   于是借用「本来在桌面里就没有实际作用」的主页长按。
     * - 需要页面的动作（抽屉 / 分屏 / 360 / 媒体）先回桌面，由
     *   [MainActivity.EXTRA_ACTION] 带过去再执行。
     * - 空调类动作直接调 [VehicleController]：进程内、全局有效、点了立即生效。
     */
    private fun wireCallbacks(bar: DockBarView) {
        // 页号由桌面切页时同步过来（[onActivePage]）——
        // 写死 0 的话，站在画中画页上 home 键还会高亮成「当前页」。
        bar.activePage = lastActivePage
        bar.onBack = {
            if (homeOnScreen || pipFullscreen) {
                if (!MainActivity.onExternalBack()) sendSystemBack()
            } else {
                sendSystemBack()
            }
        }
        bar.onHome = {
            // 画中画全屏时，主页键 = **退出全屏**。
            //
            // 为什么不能只发 HOME intent：
            // - 画中画全屏时承载面是独立图层，会盖住桌面窗口 → 桌面走 `onStop`，
            //   HOME intent 只是「把桌面拉到前台」，**碰不到画中画的全屏层**；
            // - 而全屏层还在 → 用户眼前什么都没变，就是「主页键没反应」。
            //
            // 所以直接让桌面处理（[MainActivity.onExternalHome]），把全屏层收掉。
            // 桌面窗口还亮在屏幕上（或在画中画全屏里）→ **直接调桌面**：
            // 桌面已经在前台时，HOME intent 只会把已有 task 挪到前面，
            // 不会重新进 `onNewIntent`，抽屉 / 设置 / 车辆页 / 分屏页都收不掉
            // —— 这就是独立验收判 P0 的「全局 Dock 首页键失效」。
            // 只有桌面确实不在屏幕上（别的应用盖着）时才走 HOME intent 把它拉到前台。
            if (homeOnScreen || pipFullscreen) {
                runCatching { MainActivity.onExternalHome() }
                    .onFailure { Logx.w(LogTags.DOCK, "外部 HOME 处理失败", it) }
            } else {
                goHome()
            }
        }
        // 第九十五轮 C3：主页键长按 = 最近任务（回桌面打开我们自己的最近任务页）。
        bar.onHomeLongPress = { toLauncher(MainActivity.ACTION_RECENTS) }

        bar.onApps = { toLauncher(MainActivity.ACTION_DRAWER) }
        bar.onAppsDrawer = { toLauncher(MainActivity.ACTION_DRAWER) }
        bar.onSplit = { toLauncher(MainActivity.ACTION_SPLIT) }
        bar.onSplitLongPress = { toLauncher(MainActivity.ACTION_SPLIT_LONG) }
        bar.on360 = { toLauncher(MainActivity.ACTION_CAMERA360) }
        // 第197轮：点媒体卡的封面 / 歌名区 → 直接拉起**当前正在使用的**音乐 App。
        //
        // 全局 Dock 浮在别的 App 之上，这时拉起音乐 App 正是用户想要的
        // （「回到我正在听的那个 App」），不用先把桌面拉回前台。
        bar.onMediaOpenApp = {
            val track = MusicController.get().track
            val action = com.cardesk.launcher.core.media.DockMediaLaunch.openCurrentApp(
                context = this,
                trackPkg = track?.pkg,
            )
            com.cardesk.launcher.core.media.DockMediaLaunch.messageFor(action)?.let { toast(it) }
        }
        bar.onMediaLongPress = { toLauncher(MainActivity.ACTION_MEDIA) }

        bar.onMediaNext = {
            runCatching { MusicController.get().next() }
                .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 下一首失败", it) }
            dock?.refreshFromController()
        }
        bar.onMediaPrev = {
            runCatching { MusicController.get().previous() }
                .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 上一首失败", it) }
            dock?.refreshFromController()
        }
        bar.onPlay = { togglePlayback() }

        bar.onPower = { VehicleController.togglePower() }
        bar.onTemp = { zone, delta -> VehicleController.stepTemp(zone, delta.toFloat()) }
        // 第144轮：风量键**按「桌面窗口还在不在屏幕上」分流**。
        //
        // 第101轮起全局 Dock 用 RRO 预留区占住系统导航栏，桌面上画的那条 Dock
        // 其实就是**这个窗口**（桌面自己的 Dock 在 `globalReserved` 时被 GONE 掉了），
        // 于是风量键的语义被这条窗口接管了：
        // - 桌面窗口还在屏幕上 → 和桌面里的 Dock 一致：弹**空调浮层**（回桌面执行，见 `toLauncher`）
        // - 别的应用盖上来了（桌面 `onStop`）→ 维持原状：直接调车控把风量 +1，不打断当前应用
        //
        // **判据不能用焦点**（第142~143轮真机没生效的原因）：
        // 桌面把画中画承载面里的高德拉起来时，系统把焦点抢走 → 桌面走 `onPause`，
        // 可桌面窗口还亮在屏幕上，用户眼前就是桌面。焦点口径会误判成「不在桌面上」，
        // 于是桌面上点风量键还是「风量 +1」。窗口口径（`onStart`/`onStop`）才对。
        //
        // 预留区没生效时桌面自己的 Dock 在显示，它的 `onFan` 本来就是 `showClimate()`，
        // 不走这里 —— 所以两条路径的语义最终一致。
        bar.onFan = {
            val action = DockFanPolicy.decide(homeOnScreen)
            Logx.event(
                LogTags.DOCK,
                "Dock 风量键",
                "action" to action,
                "desktopOnScreen" to homeOnScreen,
                "desktopFocused" to homeForeground,
            )
            when (action) {
                DockFanPolicy.ACTION_OPEN_CLIMATE -> toLauncher(MainActivity.ACTION_CLIMATE)
                else -> VehicleController.stepFan(1)
            }
        }
        bar.onAc = { VehicleController.toggleAc() }
        bar.onAuto = { VehicleController.toggleAuto() }
        bar.onCirc = { VehicleController.toggleCirculation() }
        bar.onDefrost = { front ->
            if (front) VehicleController.toggleDefrostFront() else VehicleController.toggleDefrostRear()
        }

        bar.onQuickApp = { index, pkg ->
            if (pkg.isEmpty()) toLauncher("${MainActivity.ACTION_QUICK_APP_PICK}:$index")
            else launchQuickApp(pkg)
        }
        bar.onQuickAppLongPress = { index ->
            toLauncher("${MainActivity.ACTION_QUICK_APP_CONFIG}:$index")
        }
    }

    /**
     * 播放 / 暂停。
     *
     * **第八十八轮：改走 [MusicController.playPause]**，不再自己算 `!playing` 再
     * `setPlaying(...)`。原因：
     * 1. 桌面（HOME）的播放键走的也是 `playPause()`，两条路径算法不一致就会出现
     *    「桌面上能恢复、全局 Dock 上恢复不了」这种差异；
     * 2. 原实现 `setPlaying(!playing)` 的 `force` 恒为 false，一旦会话实测态与界面态
     *    不一致就会被幂等闸门判「已满足」吞掉（真车表现就是「按播放没反应」）。
     * 统一的 `playPause()` 内部以**界面显示态**为切换基准，实测冲突时强推。
     */
    private fun togglePlayback() {
        val controller = runCatching { MusicController.get() }.getOrNull() ?: return
        val playing = controller.track?.playing == true
        runCatching { controller.playPause() }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 播放键下发失败", it) }
        Logx.event(LogTags.DOCK, "全局 Dock 播放键", "wasPlaying" to playing)
        dock?.refreshFromController()
        bindSlotBarMedia()
    }

    /**
     * 主页长按 = 系统返回。
     *
     * 用 `Instrumentation.sendKeyDownUpSync(KEYCODE_BACK)`，需要
     * `android.permission.INJECT_EVENTS`（平台签名才给）。所以：
     * **必须**在后台线程（反射注入会阻塞到事件派发完，主线程调用会 ANR），
     * 失败就 toast 说明，绝不崩。
     */
    private fun sendSystemBack() {
        Logx.event(LogTags.DOCK, "全局 Dock 返回键：系统返回")
        val worker = Thread {
            val result = runCatching { Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
            if (result.isFailure) {
                val fallback = com.cardesk.launcher.core.SystemOps.injectKey(this, KeyEvent.KEYCODE_BACK) ||
                    com.cardesk.launcher.core.SystemOps.injectKeyViaShell(KeyEvent.KEYCODE_BACK)
                if (!fallback) {
                    val error = result.exceptionOrNull() ?: RuntimeException("sendKeyDownUpSync failed")
                    Logx.w(LogTags.DOCK, "注入返回键失败（缺 INJECT_EVENTS？）", error)
                    main.post { toast("缺少按键注入权限，无法返回") }
                }
            }
        }
        worker.name = "cardesk-global-dock-back"
        worker.isDaemon = true
        worker.start()
    }

    /** 发 HOME intent 回桌面。 */
    private fun goHome() {
        Logx.event(LogTags.DOCK, "全局 Dock：回桌面")
        // 第三百零五轮：桌面已经在前台时不要再发 HOME intent ——
        // 系统会把桌面走一轮 onPause→onResume（实车 08:44 日志里成对出现），
        // 用户看到的就是「按一下闪一下」。画中画全屏的还原走进程内直调。
        if (homeForeground && MainActivity.onExternalHomeInProcess()) return
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        runCatching { startActivity(intent) }
            .onFailure { Logx.w(LogTags.DOCK, "回桌面失败", it) }
    }

    /**
     * 回桌面并让 [`LauncherView.handleGlobalDockAction`] 执行动作。
     *
     * 为什么不直接在这里开抽屉 / 空调浮层：那些浮层属于 `LauncherView`，
     * 在别的应用前台时把浮层开出来，用户回到桌面会看到一个「凭空打开」的层；
     * 统一「先把桌面拉到前台，再执行」时序才可控。
     */
    private fun toLauncher(action: String) {
        Logx.event(LogTags.DOCK, "全局 Dock：回桌面执行动作", "action" to action)
        // 第三百零五轮：桌面**已经在前台**时不要再 startActivity。
        //
        // 实车 m01073 的证据：每按一次 Dock 键都是
        // `[EVT] 回桌面执行动作` → 50ms 后 `onPause` → **2ms 后 `onResume`**
        // （08:44:20.478/.480 起连续 8 次）。窗口并没有真的被别的 App 盖住，
        // 就是这次多余的 startActivity 让系统重排了一轮窗口 ——
        // 用户说的「操作时闪一下」正是它。改成进程内直调后这一轮彻底消失。
        if (homeForeground && MainActivity.dispatchDockAction(action)) {
            Logx.event(LogTags.DOCK, "全局 Dock：桌面已在前台，进程内分发", "action" to action)
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_ACTION, action)
        }
        runCatching { startActivity(intent) }
            .onFailure { Logx.w(LogTags.DOCK, "拉起桌面失败", it) }
    }

    /** 快捷应用槽位：配了就直接启动，空槽位提示「需回桌面配置」。 */
    private fun launchQuickApp(pkg: String) {
        if (pkg.isEmpty()) {
            toast("需回桌面配置")
            return
        }
        val intent = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent == null) {
            Logx.w(LogTags.DOCK, "快捷应用没有启动入口", "pkg" to pkg)
            toast("无法启动该应用")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Logx.w(LogTags.DOCK, "启动快捷应用失败", it) }
    }

    // ---------------------------------------------------------------- 状态刷新

    /**
     * 订阅媒体 / 空调状态，并起 1s ticker。
     *
     * 没有这层刷新，全局 Dock 的媒体卡和温度会停在服务启动那一刻
     * （`DockBarView` 自己不会去拉数据）。
     */
    private fun attachRefresh() {
        runCatching {
            MusicController.get().retain(applicationContext, "GlobalDockService")
            MusicController.get().addListener(mediaListener)
        }.onFailure { Logx.w(LogTags.DOCK, "全局 Dock 订阅媒体状态失败", it) }
        runCatching { VehicleController.addObserver(hvacObserver) }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 订阅空调状态失败", it) }
        main.postDelayed(refreshTicker, TICK_MS)
    }

    private fun bindHvac() {
        if (!VehicleController.initialized) return
        runCatching {
            dock?.bind(VehicleController.state)
            slotBar?.state = VehicleController.state
        }.onFailure { Logx.w(LogTags.DOCK, "全局 Dock 绑定空调状态失败", it) }
    }

    private fun bindQuickApps() {
        val bar = dock ?: return
        val pm = packageManager
        val apps = DockQuickApps.pad(prefs.dockQuickApps).map { pkg ->
            if (pkg.isEmpty()) {
                DockBarView.QuickApp("", "", null)
            } else {
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                DockBarView.QuickApp(pkg, label, icon)
            }
        }
        bar.bindQuickApps(apps)
    }

    // ---------------------------------------------------------------- 服务生命周期

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logx.event(LogTags.DOCK, "通知栏关闭全局 Dock")
            prefs.globalDockEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY：Dock 是常驻界面，被系统回收后希望它自己回来。
        // 权限 / 开关在 onCreate 里双重校验过，条件不满足会自我退出。
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGeometry()
    }

    /** 移除让位把手窗口（重建 / 销毁时调用）。 */
    override fun onDestroy() {
        Logx.i(LogTags.DOCK, "全局 Dock 服务销毁")
        main.removeCallbacksAndMessages(null)
        // 用户关掉全局 Dock 时把导航栏高度还给系统，避免关了开关、导航栏还停在 112px。
        if (runCatching { prefs.globalDockEnabled }.getOrDefault(true).not()) {
            runCatching {
                if (NavInsets.applied(this)) {
                    Logx.i(LogTags.DOCK, "全局 Dock 关闭，恢复系统预留区")
                    NavInsets.restore(this)
                }
            }
        }
        // 关掉全局 Dock（通知栏关闭 / 设置页关开关 / 权限被收 / 被系统杀掉）时，
        // 必须把 `policy_control` 恢复到「普通桌面」应有的状态：
        // 否则用户会得到「既没有原车 Dock、也没有 CarDesk Dock」的空白底边。
        // 这里**无条件**恢复（不读开关）：窗口都没了，先把原车导航栏还回去；
        // 若开关还开着，START_STICKY 重建服务时 onCreate 会再写回全局 Dock 状态。
        restorePolicyAsync("服务销毁")
        lookReceiver?.let { r ->
            runCatching { unregisterReceiver(r) }
            lookReceiver = null
        }
        appliedCoverBitmap?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
            appliedCoverBitmap = null
        }
        runCatching {
            MusicController.get().removeListener(mediaListener)
            MusicController.get().release("GlobalDockService")
        }.onFailure { Logx.w(LogTags.DOCK, "全局 Dock 取消媒体订阅失败", it) }
        runCatching { VehicleController.removeObserver(hvacObserver) }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 取消空调订阅失败", it) }
        if (::windowManager.isInitialized) {
            root?.let { view ->
                runCatching { windowManager.removeViewImmediate(view) }
                    .onFailure { Logx.w(LogTags.DOCK, "移除全局 Dock 窗口失败", it) }
            }
        }
        dock = null
        slotBar = null
        designLayer = null
        root = null
        params = null
        showing = false
        active = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 后台线程写 `policy_control`。
     *
     * 那是写系统设置（还要先 reset 再 verify），主线程同步写会卡住服务启动
     * / 销毁那几帧。写的是系统设置，线程走完进程也不依赖它。
     */
    private fun applyPolicyAsync(reason: String) {
        val app = applicationContext
        val worker = Thread {
            val applied = runCatching { GlobalDockPolicyGate.apply(app) }.getOrDefault(false)
            Logx.i(LogTags.DOCK, "全局 Dock 策略已同步", "reason" to reason, "applied" to applied)
        }
        worker.name = "cardesk-global-dock-policy"
        worker.isDaemon = true
        worker.start()
    }

    /** [GlobalDockPolicyGate.restore] 的后台版本：销毁时无条件撤掉导航栏沉浸。 */
    private fun restorePolicyAsync(reason: String) {
        val app = applicationContext
        val worker = Thread {
            val applied = runCatching { GlobalDockPolicyGate.restore(app) }.getOrDefault(false)
            Logx.i(LogTags.DOCK, "全局 Dock 策略已恢复", "reason" to reason, "applied" to applied)
        }
        worker.name = "cardesk-global-dock-restore"
        worker.isDaemon = true
        worker.start()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "全局 Dock",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Dock 栏常驻在其他应用底部时显示的前台服务通知"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GlobalDockService::class.java).setAction(ACTION_STOP),
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
            .setContentTitle("CarDesk 全局 Dock")
            .setContentText("Dock 栏常驻在其他应用底部，点此关闭")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "关闭全局 Dock", stopIntent)
            .build()
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /** 通知栏「关闭全局 Dock」的 action。 */
        const val ACTION_STOP = "com.cardesk.launcher.action.GLOBAL_DOCK_STOP"

        private const val CHANNEL_ID = "cardesk_global_dock"
        private const val NOTIFICATION_ID = 753

        /** 设计板宽 / 高（= 1920×1080 的设计稿坐标系）。 */
        private const val DESIGN_WIDTH = DockBottomProbe.DESIGN_WIDTH

        /**
         * 底栏条设计高：Dock 104 + 底边距 8。
         *
         * 第159轮起与 [DockBottomProbe] 共用同一份常量 ——
         * 诊断页算「Dock 占哪一段」时用的就是它，两边一旦漂移诊断就会说谎。
         */
        private const val DESIGN_BAR_HEIGHT = DockBottomProbe.DESIGN_BAR_HEIGHT

        /** Dock 自身设计尺寸（与 `LauncherView.buildBottomBar` 一致，第九十四轮起铺满全宽）。 */
        private const val DESIGN_DOCK_WIDTH = DESIGN_WIDTH

        /**
         * Dock 本体在设计层里的高度 —— 第192轮：104 → 112。
         *
         * **必须等于 [DESIGN_BAR_HEIGHT]**：Dock 要**铺满**整个设计层，
         * 否则底边会露出一条宿主的不透明底 ——
         * 那正是「系统预留了 112px、Dock 只画 104px」留下的 8px 缝。
         */
        private const val DESIGN_DOCK_HEIGHT = DESIGN_BAR_HEIGHT

        /** Dock 设计坐标左内边距（第九十四轮铺满整屏宽后不再留边）。 */
        private const val DOCK_LEFT_MARGIN = 0

        /** 不透明底栏颜色：Dock 玻璃底 `#6b162e2c` 的同色系不透明值。 */
        private const val OPAQUE_BAR_COLOR = 0xFF162E2C.toInt()

        private const val TICK_MS = 1_000L

        /** 收起态下多少 px 的上滑算「唤出 Dock」（设计画布像素，屏宽 1920）。 */
        private const val REVEAL_SWIPE_PX = 24f

        /** 展开后多久自动收起。 */
        private const val REVEAL_AUTO_COLLAPSE_MS = 5_000L

        /** 收起态只占屏幕最底部这么高的一条（px）：其余高度还回给 App。 */
        private const val COLLAPSED_STRIP_PX = 24
        private const val QUICK_APP_REFRESH_TICKS = 30

        /** 每 5s 兜底重绑一次空调状态（observer 可能晚于服务注册）。 */
        private const val HVAC_REFRESH_TICKS = 5

        /**
         * 每 10s 采一次底部占位（第159轮，纯诊断用）。
         *
         * 采样要读系统导航栏实测高度 + 屏宽，都是很轻的操作；
         * 但没必要 1s 一次 —— 诊断是用户主动触发的，10s 足够新。
         */
        private const val BOTTOM_PROBE_TICKS = 10

        /**
         * `TYPE_NAVIGATION_BAR_PANEL`（导航栏面板，画在系统导航栏**之上**）。
         *
         * 这是 `@hide` 常量，public SDK 里没有，只能写数值 2024。
         * 需要平台签名 / INTERNAL_SYSTEM_WINDOW，普通安装包里
         * `addView` 会抛异常 —— 所以 [addViewWithFallback] 必须能退回 2038。
         */
        private const val TYPE_NAVIGATION_BAR_PANEL = 2024

        /** `TYPE_APPLICATION_OVERLAY`（API 26+ 的普通悬浮窗类型）。 */
        private const val TYPE_APPLICATION_OVERLAY = 2038

        @Volatile
        private var active: GlobalDockService? = null

        /**
         * 是否**让位**给整屏覆盖页（设置页 `s01..s25`）。
         *
         * 画板里设置页是整屏覆盖、底部**没有** Dock（面板下沿就是背景），
         * 而全局 Dock 是另一个窗口（`CarDesk 全局 Dock`），压在桌面窗口之上，
         * 桌面里 `dock.visibility = GONE` 根本管不到它 —— 实测设置页底部那条
         * 一直是全局 Dock 窗口画出来的。
         *
         * 所以开关由 [LauncherView.showSettings] / [closeSettings] 显式设置：
         * 返回 [updateVisibility] 的 `want`，只影响可见性，不动窗口本身
         * （窗口留着，`policy_control` 也不改，关掉设置页一调就回来）。
         */
        @Volatile
        private var suppressed = false

        /**
         * 空白占位（[suppressed]）时铺的底色 —— 0 表示用 [OPAQUE_BAR_COLOR]。
         *
         * 第三百零五轮：设置页 / 启动页都会传各自的背景色进来，
         * 让底部这条和上面的画面接得上（见 [applyBlank]）。
         */
        @Volatile
        private var suppressedCoverColor = 0

        /**
         * 空白占位时铺的**底图**（优先于 [suppressedCoverColor]）。
         *
         * 第三百零五轮：由 `LauncherView.captureSettingsBottomCover()` 用
         * `PixelCopy` 从窗口合成结果里拷「接缝上方那条真实像素」进来 ——
         * 和上面的画面逐像素一致，比单色彻底（壁纸底部有渐变 / 暗角）。
         */
        @Volatile
        private var suppressedCoverBitmap: Bitmap? = null

        /**
         * 设置页 / 启动页打开时调用：让全局 Dock **让出内容**（而不是让出窗口）。
         *
         * 预留区生效（2024 面板）时窗口会退回「空白占位」态继续盖住原车导航栏，
         * 见 [applyBlank]；没有预留区（2038 悬浮窗）时才真的隐身。
         *
         * @param coverColor 空白占位的底色；0 = 用默认不透明底栏色。
         * @param coverBitmap 空白占位的底图（优先于 [coverColor]）。所有权交给服务：
         *   被下一张替换 / 退出占位时由服务回收，调用方不要再碰它。
         *
         * 服务没在跑（开关关闭 / 没权限）时是空操作。
         */
        fun setSuppressed(
            on: Boolean,
            coverColor: Int = 0,
            coverBitmap: Bitmap? = null,
        ) {
            if (suppressed == on) {
                // 已让位时只更新底图/底色（设置页与启动页颜色不同，但通常不会同时开）
                if (!on || (suppressedCoverColor == coverColor && suppressedCoverBitmap === coverBitmap)) return
                suppressedCoverColor = coverColor
                suppressedCoverBitmap = coverBitmap
                val running = active ?: return
                running.main.post { running.updateVisibility() }
                return
            }
            suppressed = on
            suppressedCoverColor = if (on) coverColor else 0
            suppressedCoverBitmap = if (on) coverBitmap else null
            val svc = active
            Logx.i(
                LogTags.DOCK,
                "全局 Dock 抑制切换",
                "suppressed" to on,
                "active" to (svc != null),
                "coverColor" to if (on) Integer.toHexString(coverColor) else "-",
                "coverBitmap" to (if (on && coverBitmap != null) "${coverBitmap.width}x${coverBitmap.height}" else "-"),
            )
            if (svc == null) return
            svc.main.post { svc.updateVisibility() }
        }

        /** 设计层宽度（全局 Dock 的坐标系宽 = 1920），给调用方裁底图用。 */
        fun designBarWidth(): Int = DESIGN_WIDTH

        /** 设计层高度（全局 Dock 的高 = 112），给调用方裁底图用。 */
        fun designBarHeight(): Int = DESIGN_BAR_HEIGHT

        /** 全局 Dock 当前是否真的画在屏幕上（[showing] 是 updateVisibility 的结论）。 */
        fun isShowing(): Boolean = active?.showing == true

        /**
         * 按当前开关 / 权限拉起或停掉服务。
         *
         * **唯一入口**：设置页开关、开机广播、桌面 `onResume` 都走这里，
         * 避免各处自己拼 `startForegroundService` 忘判权限。
         *
         * - 开关关着：无条件 `stopService`（幂等），残留服务会被收掉。
         * - 开关开着但没有悬浮窗权限、也拿不到导航栏预留区：**不启动**。
         *   此时设置页会引导用户去授权，授权回来 `onResume` 再调一次这里。
         */
        fun ensureStarted(context: Context) {
            val app = context.applicationContext
            val enabled = runCatching { Prefs(app).globalDockEnabled }.getOrDefault(false)
            if (!enabled) {
                runCatching { app.stopService(Intent(app, GlobalDockService::class.java)) }
                return
            }
            val overlay = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)
            val navInsetsSupported = runCatching { NavInsets.supported(app) }.getOrDefault(false)
            if (!overlay && !navInsetsSupported) {
                Logx.i(LogTags.DOCK, "全局 Dock 未启动：缺少悬浮窗授权，且无法接管原车导航栏")
                return
            }
            if (active?.root != null) {
                Logx.i(LogTags.DOCK, "全局 Dock 已在运行，跳过重复启动")
                return
            }
            runCatching { app.startForegroundService(Intent(app, GlobalDockService::class.java)) }
                .onFailure { Logx.w(LogTags.DOCK, "启动全局 Dock 服务失败", it) }
        }

        /** 桌面 onResume / onPause 通知：HOME 前台时全局窗口要隐身。 */
        fun onHomeForeground(homeForeground: Boolean) {
            active?.setHomeForeground(homeForeground)
        }

        /**
         * 桌面切页通知：把当前页号同步给全局 Dock。
         *
         * 全局 Dock 是独立的 [DockBarView] 实例，它的 `activePage` 决定
         * 主页键的高亮（当前页 = 高亮）。不同步的话它永远停在 0。
         */
        fun onActivePage(page: Int) {
            lastActivePage = page
            active?.setActivePage(page)
        }

        /** 快捷应用配置变更后立即同步到全局 Dock 悬浮窗。 */
        fun refreshQuickApps() {
            val svc = active ?: return
            svc.main.post { svc.bindQuickApps() }
        }

        /** 导航栏样式或槽位列表变更后立即同步到全局 Dock 悬浮窗。 */
        fun refreshStyleAndSlots() {
            val svc = active ?: return
            svc.main.post { svc.reloadStyleAndSlots() }
        }

        /** 「隐藏桌面 Dock」等开关变更后立即重新计算全局 Dock 可见性。 */
        fun refreshVisibility() {
            val svc = active ?: return
            svc.main.post { svc.updateVisibility() }
        }

        /** Dock 圆角变更后同步刷新全局 Dock 宿主圆角底板。 */
        fun refreshCornerRadius() {
            val svc = active ?: return
            svc.main.post {
                if (svc.showing && !svc.blank && !svc.collapsed) {
                    svc.applyRootBackground(null, OPAQUE_BAR_COLOR)
                }
            }
        }

        /**
         * 上一次已知的桌面页号。
         *
         * 放在 companion 里（而不是服务实例字段）：服务可能晚于切页才起来
         * （[ensureStarted] 被设置页 / 开机广播拉起），起来时要把页号补上。
         */
        @Volatile
        private var lastActivePage = 0

        /**
         * 桌面那边是不是**画中画全屏**态。
         *
         * 放 companion 里（服务与桌面同进程，静态引用直连）。
         * 全局 Dock 的主页键靠它决定「退出全屏」还是「回桌面」。
         */
        @Volatile
        private var pipFullscreen = false

        /**
         * 桌面进 / 出画中画全屏时通知全局 Dock。
         *
         * 全局 Dock 是**另一个窗口**，它的主页键看不见桌面里的画中画状态；
         * 桌面切页 / 进全屏这些状态变化都要主动同步过来。
         */
        fun onPipFullscreen(state: Boolean) {
            pipFullscreen = state
            Logx.i(LogTags.DOCK, "画中画全屏态同步", "fullscreen" to state)
        }

        /**
         * 第144轮：桌面 `onStart` / `onStop` 通知 —— 桌面窗口还在不在屏幕上。
         *
         * 只用来决定**风量键的语义**（弹空调浮层 / 风量 +1），
         * 不影响全局 Dock 窗口自己的显隐。
         */
        fun onHomeOnScreen(onScreen: Boolean) {
            active?.setHomeOnScreen(onScreen)
        }

        /** 服务是否已经挂上窗口（给设置页 / 排障用）。 */
        fun isRunning(): Boolean = active?.root != null
    }
}
