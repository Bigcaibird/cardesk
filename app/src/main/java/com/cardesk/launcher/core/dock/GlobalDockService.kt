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
import android.graphics.PixelFormat
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
import com.cardesk.launcher.core.OemDockPolicy
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.LyricTimeline
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.core.media.TrackInfo
import com.cardesk.launcher.ui.shell.DockBarView
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
    private var params: WindowManager.LayoutParams? = null

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
     * 第180轮：前台是沉浸型播放器（汽水音乐 / 网易云车机版）时为 true，
     * 让全局 Dock 暂时让位 —— 它们自己 hideNavigationBars、画面铺到屏幕最底，
     * 我们的悬浮窗会压住它们底部的内容（用户反馈）。
     */
    private var yieldToApp = false

    /** 调试强制让位（null=按前台判断；见 [installDebugYieldHook]）。 */
    private var debugYield: Boolean? = null

    /**
     * 让位状态下的「上滑唤出把手」（第181轮）。
     *
     * 用户要的是「Dock 让位但别彻底消失」：沉浸型播放器在前台时，
     * Dock 收起，只在屏幕底部留一条 6px 细条；点一下/上滑 → Dock 临时弹出
     * [HANDLE_REVEAL_MS] 毫秒，到点自动收回。
     */
    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var temporaryReveal = false

    private val collapseRunnable = Runnable {
        temporaryReveal = false
        updateVisibility()
    }

    /** 这次窗口用的是不是 2024（导航栏面板）。 */
    private var nativeInsets = false
    private var insetsAttemptRunning = false

    private var tick = 0

    /** 媒体状态变化 → 立刻重绑 Dock 的媒体卡（进度条另外靠 ticker 拉）。 */
    private val mediaListener = object : MusicController.Listener {
        override fun onMediaChanged(track: TrackInfo?) {
            dock?.refreshFromController()
        }

        override fun onLyricChanged(timeline: LyricTimeline?, index: Int, wipe: Float) = Unit
    }

    /** 空调状态变化 → 重绑温度 / 风量 / 模式。 */
    private val hvacObserver: (HvacState) -> Unit = { state -> dock?.bind(state) }

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
            // 空调状态平时靠 [hvacObserver] 推，但服务可能比 VehicleController
            // 先起来（开机广播先于桌面），那一次 observer 还没注册上 —— 低频再拉一遍兜底。
            if (tick % HVAC_REFRESH_TICKS == 0) bindHvac()
            // 快捷应用的图标 / 应用名偶尔会变（装/卸应用），低频重绑即可。
            if (tick % QUICK_APP_REFRESH_TICKS == 0) bindQuickApps()
            // 第159轮：底部占位采样（**只读**，不改任何运行时行为）。
            // 诊断页在桌面进程、服务在服务进程，所以采样结果落盘给诊断页读。
            if (tick % BOTTOM_PROBE_TICKS == 0) writeBottomProbe()
            // 第180轮：沉浸型播放器在前台时让位（每秒判一次，切前台/退出的延迟 ≤1s）。
            if (tick % YIELD_CHECK_TICKS == 0) checkYieldToApp()
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
     * 第180轮：调试旁路 —— 强制让位 / 恢复。
     *
     * 真机上要验「汽水音乐 / 网易云被 Dock 挡住」必须真装那两个 App；
     * 模拟器上没有，所以留这条广播给 QA 直接验显隐时序：
     * `am broadcast -a com.cardesk.launcher.DEBUG_DOCK_YIELD --es on 1` / `--es on 0`。
     * 仅 debuggable 变体注册。
     */
    private fun installDebugYieldHook() {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val on = intent?.getStringExtra("on")
                debugYield = on == "1" || on.equals("true", ignoreCase = true)
                yieldToApp = debugYield == true
                updateVisibility()
                Logx.i(LogTags.DOCK, "全局 Dock 让位（调试注入）", "yield" to yieldToApp)
            }
        }
        runCatching {
            registerReceiver(r, android.content.IntentFilter("com.cardesk.launcher.DEBUG_DOCK_YIELD"))
        }

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
        installDebugYieldHook()
        if (root != null) return
        nativeInsets = runCatching { NavInsets.applied(this) }.getOrDefault(false)

        val layer = FrameLayout(this).apply {
            // 设计层自己不画东西，Dock 的坐标直接按 1920×112 板子摆。
            clipChildren = false
        }
        val bar = DockBarView(this).apply {
            wireCallbacks(this)
        }
        layer.addView(
            bar,
            FrameLayout.LayoutParams(DESIGN_DOCK_WIDTH, DESIGN_DOCK_HEIGHT).apply {
                leftMargin = DOCK_LEFT_MARGIN
                topMargin = 0
            },
        )

        val host = object : FrameLayout(this) {
            private var downY = 0f
            private var downX = 0f

            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                // 第182轮：临时展开时「下滑收起」。
                // 只在下滑超过 24px 且横向没怎么动时接管，避免误伤 Dock 按钮的点击。
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downY = ev.rawY
                        downX = ev.rawX
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - downY
                        val dx = kotlin.math.abs(ev.rawX - downX)
                        if (temporaryReveal && dy > SWIPE_COLLAPSE_PX && dx < SWIPE_COLLAPSE_MAX_DX) {
                            collapseNow()
                            return true
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            // 不透明底：原车 Dock / 系统导航栏还在窗口下面，
            // Dock 自带的玻璃底是半透明的（#6b162e2c），不铺不透明底会透出来，
            // 看起来就像「两个 Dock 叠在一起」。这里用同色系的不透明值。
            setBackgroundColor(OPAQUE_BAR_COLOR)
            addView(layer, FrameLayout.LayoutParams(DESIGN_WIDTH, DESIGN_BAR_HEIGHT))
        }

        dock = bar
        designLayer = layer
        root = host
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
        )
        bindHvac()
        bindQuickApps()
        startHandleWindow()
        updateVisibility()
    }

    /**
     * 让位把手（第181轮）：屏幕底部正中一条 6px 细条，点/上滑临时唤出 Dock。
     *
     * 为什么要单独一个窗口而不是把 Dock 窗口压扁：Dock 的内容是按 1920×112
     * 设计稿等比缩放的，压到 6px 会把整条 Dock 压成一条乱线；单独窗口更干净，
     * 也不影响 Dock 自己的可见性逻辑。
     */
    private fun startHandleWindow() {
        if (handle != null) return
        val (w, h) = realSize()
        val handleH = (h * HANDLE_H_RATIO).roundToInt().coerceAtLeast(6)
        val v = object : View(this) {
            // 深色 + 浅描边：浅色 App（网易云的白底歌单页）上也看得见。
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99000000.toInt()
                style = android.graphics.Paint.Style.FILL
            }
            private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x88FFFFFF.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1f
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                val cw = width.toFloat()
                val ch = height.toFloat()
                val halfW = cw * 0.22f
                val barH = ch * 0.42f
                val l = (cw - halfW) / 2f
                val t = (ch - barH) / 2f
                val r = (cw + halfW) / 2f
                val b = (ch + barH) / 2f
                canvas.drawRoundRect(l, t, r, b, barH / 2f, barH / 2f, p)
                canvas.drawRoundRect(l, t, r, b, barH / 2f, barH / 2f, edge)
            }
        }
        val lp = WindowManager.LayoutParams().apply {
            type = if (nativeInsets) TYPE_NAVIGATION_BAR_PANEL else TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            @Suppress("DEPRECATION")
            setTitle("CarDesk Dock 让位把手")
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            width = (w * HANDLE_W_RATIO).roundToInt().coerceAtLeast(60)
            height = handleH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        var downY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> downY = e.rawY
                android.view.MotionEvent.ACTION_UP -> {
                    // 点一下（|dy| 很小）或上滑（dy <= -8）都唤出；
                    // 下滑不动它（收起草在 Dock 侧，避免误触）。
                    val dy = e.rawY - downY
                    if (dy <= SWIPE_REVEAL_PX || kotlin.math.abs(dy) < TAP_SLOP_PX) revealTemporarily()
                }
            }
            true
        }
        v.visibility = View.GONE
        if (addViewWithFallback(v, lp)) {
            handle = v
            handleParams = lp
        }
        Logx.i(LogTags.DOCK, "让位把手已创建", "w" to lp.width, "h" to lp.height)
    }

    /** 下滑收起（第182轮）：立即收回，不等 [HANDLE_REVEAL_MS]。 */
    private fun collapseNow() {
        if (!temporaryReveal) return
        temporaryReveal = false
        main.removeCallbacks(collapseRunnable)
        updateVisibility()
        Logx.i(LogTags.DOCK, "Dock 下滑收起")
    }

    /** 点/上滑把手后临时展开 Dock（[HANDLE_REVEAL_MS] 后自动收回）。 */
    private fun revealTemporarily() {
        temporaryReveal = true
        updateVisibility()
        main.removeCallbacks(collapseRunnable)
        main.postDelayed(collapseRunnable, HANDLE_REVEAL_MS)
        Logx.i(LogTags.DOCK, "Dock 临时展开", "ms" to HANDLE_REVEAL_MS)
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
        removeHandleWindow()
        val view = root ?: return
        if (nativeInsets) return
        Logx.i(LogTags.DOCK, "预留区生效，按导航栏面板重建 Dock 窗口")
        runCatching { windowManager.removeViewImmediate(view) }
        root = null
        designLayer = null
        dock = null
        params = null
        showing = false
        startWindow()
        applyGeometry()
    }

    private fun updateVisibility() {
        val view = root ?: return
        // 预留区生效时，Dock 就是系统导航栏的替代品：桌面页也要保持可见，
        // 否则桌面自己会空出一条原车导航栏（或黑边）。
        // 让位时：Dock 收起，只留把手；点把手后临时显示（§181）。
        val want = (nativeInsets || !homeForeground) && (!yieldToApp || temporaryReveal)
        val handleWant = yieldToApp && !temporaryReveal && !homeForeground
        handle?.visibility = if (handleWant) View.VISIBLE else View.GONE
        if (showing == want) return
        showing = want
        view.visibility = if (want) View.VISIBLE else View.INVISIBLE
        Logx.i(
            LogTags.DOCK,
            "全局 Dock 可见性",
            "visible" to want,
            "homeForeground" to homeForeground,
        )
    }

    /**
     * 第180轮：沉浸型播放器让位。
     *
     * 为什么放在 1s ticker 里：全局 Dock 是**服务进程**里的悬浮窗，
     * 拿不到别的 App 的窗口焦点回调；`getRunningTasks(1)` 是最便宜的前台探测
     * （这个包是 system 变体，权限够）。判到变化才改窗口，平时只读一个字符串。
     */
    private fun checkYieldToApp() {
        // 调试注入过就让它保持，别再被前台判断覆盖（否则 QA 一秒钟就被打回去）。
        if (debugYield != null) return
        val fg = foregroundPackage()
        // 名单命中 → 立刻让位（不依赖探测）；
        // 否则"运行时探测"：读系统窗口策略的 mLastSystemUiFlags，看当前焦点窗口
        // 是否设置了 HIDE_NAVIGATION / IMMERSIVE（= 任意 App 自己 hideNavigationBars）。
        // 探测不可用（没 shell 权限 / 解析失败）时回退 false，绝不乱让位。
        val known = runCatching { OemDockPolicy.hidesGlobalDockFor(fg) }.getOrDefault(false)
        // 桌面自己会隐藏状态栏（immersive.status），mLastSystemUiFlags 里有 IMMERSIVE 位，
        // 直接探测会把"桌面"当成沉浸 App —— 桌面必须永远显示 Dock，所以桌面上只认名单。
        val detected = when {
            known -> true
            homeForeground -> false
            else -> runCatching {
                ImmersiveDetector.navBarHidden(this, fg) ?: false
            }.getOrDefault(false)
        }
        val yield = known || detected
        if (yield == yieldToApp) return
        yieldToApp = yield
        if (!yield) {
            // 退出沉浸 App：把手/临时展开都清掉，回到常规 Dock。
            temporaryReveal = false
            main.removeCallbacks(collapseRunnable)
            ImmersiveDetector.clear()
        }
        updateVisibility()
        Logx.i(
            LogTags.DOCK,
            "全局 Dock 让位",
            "yield" to yield,
            "foreground" to (fg ?: "-"),
            "名单命中" to known,
            "探测沉浸" to detected,
        )
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
        bar.activePage = 0
        bar.onHome = { goHome() }
        // 第九十五轮 C3：主页键长按 = 最近任务（回桌面打开我们自己的最近任务页）。
        bar.onHomeLongPress = { toLauncher(MainActivity.ACTION_RECENTS) }

        bar.onApps = { toLauncher(MainActivity.ACTION_DRAWER) }
        bar.onAppsDrawer = { toLauncher(MainActivity.ACTION_DRAWER) }
        bar.onSplit = { toLauncher(MainActivity.ACTION_SPLIT) }
        bar.onSplitLongPress = { toLauncher(MainActivity.ACTION_SPLIT_LONG) }
        bar.on360 = { toLauncher(MainActivity.ACTION_CAMERA360) }
        bar.onMediaTap = { toLauncher(MainActivity.ACTION_MEDIA) }
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

        bar.onQuickApp = { _, pkg -> launchQuickApp(pkg) }
        bar.onQuickAppLongPress = { toast("需回桌面配置") }
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
        Logx.event(LogTags.DOCK, "全局 Dock 主页长按：系统返回")
        val worker = Thread {
            val result = runCatching { Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
            if (result.isFailure) {
                val error = result.exceptionOrNull() ?: RuntimeException("sendKeyDownUpSync failed")
                Logx.w(LogTags.DOCK, "注入返回键失败（缺 INJECT_EVENTS？）", error)
                main.post { toast("缺少按键注入权限，无法返回") }
            }
        }
        worker.name = "cardesk-global-dock-back"
        worker.isDaemon = true
        worker.start()
    }

    /** 发 HOME intent 回桌面。 */
    private fun goHome() {
        Logx.event(LogTags.DOCK, "全局 Dock：回桌面")
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
        runCatching { MusicController.get().addListener(mediaListener) }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 订阅媒体状态失败", it) }
        runCatching { VehicleController.addObserver(hvacObserver) }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 订阅空调状态失败", it) }
        main.postDelayed(refreshTicker, TICK_MS)
    }

    private fun bindHvac() {
        if (!VehicleController.initialized) return
        runCatching { dock?.bind(VehicleController.state) }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 绑定空调状态失败", it) }
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
    private fun removeHandleWindow() {
        val v = handle ?: return
        handle = null
        handleParams = null
        runCatching { windowManager.removeViewImmediate(v) }
            .onFailure { Logx.w(LogTags.DOCK, "移除让位把手失败", it) }
    }

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
        runCatching { MusicController.get().removeListener(mediaListener) }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 取消媒体订阅失败", it) }
        runCatching { VehicleController.removeObserver(hvacObserver) }
            .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 取消空调订阅失败", it) }
        removeHandleWindow()
        if (::windowManager.isInitialized) {
            root?.let { view ->
                runCatching { windowManager.removeViewImmediate(view) }
                    .onFailure { Logx.w(LogTags.DOCK, "移除全局 Dock 窗口失败", it) }
            }
        }
        dock = null
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
        private const val DESIGN_DOCK_HEIGHT = 104

        /** Dock 设计坐标左内边距（第九十四轮铺满整屏宽后不再留边）。 */
        private const val DOCK_LEFT_MARGIN = 0

        /** 不透明底栏颜色：Dock 玻璃底 `#6b162e2c` 的同色系不透明值。 */
        private const val OPAQUE_BAR_COLOR = 0xFF162E2C.toInt()

        private const val TICK_MS = 1_000L
        private const val QUICK_APP_REFRESH_TICKS = 30

        /**
         * 第180轮：几 tick 判一次「前台是不是沉浸型播放器、Dock 要不要让位」。
         *
         * 1s 一次：切到汽水音乐/网易云后 ≤1s 就隐藏 Dock，退出后 ≤1s 恢复；
         * 成本只有 `getRunningTasks(1)` 取一个包名。
         */
        const val YIELD_CHECK_TICKS = 1

        /** 让位把手的屏幕占比：宽 22%、高 6px（1080p 下约 422×6）。 */
        const val HANDLE_W_RATIO = 0.22f
        const val HANDLE_H_RATIO = 0.009f

        /** 上滑唤出阈值（负值=向上）；下滑收起阈值；横向容差。 */
        const val SWIPE_REVEAL_PX = -8f
        const val SWIPE_COLLAPSE_PX = 24f
        const val SWIPE_COLLAPSE_MAX_DX = 90f

        /** 点击判定的纵向容差（小于它算"点一下"，不是滑动）。 */
        const val TAP_SLOP_PX = 12f

        /** 点把手后 Dock 临时展开的时长。 */
        const val HANDLE_REVEAL_MS = 8000L

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
