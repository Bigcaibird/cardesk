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
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.LyricTimeline
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.core.media.TrackInfo
import com.cardesk.launcher.ui.shell.DockBarView
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleController
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
     * 桌面是否在前台。
     *
     * 默认 `true`：服务多数是从桌面的设置页拉起来的，此刻 HOME 就在前台，
     * 先当「桌面在前台」处理能避免窗口在桌面上一闪。
     */
    private var homeForeground = true

    /** 当前是不是可见（`root.visibility` 的缓存，避免重复 setVisibility）。 */
    private var showing = false

    /** 这次窗口用的是不是 2024（导航栏面板）。 */
    private var nativeInsets = false

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
            main.postDelayed(this, TICK_MS)
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
    }

    // ---------------------------------------------------------------- 窗口

    /**
     * 建窗口：整棵视图只 `addView` 这一次。
     */
    private fun startWindow() {
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

        val host = FrameLayout(this).apply {
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
        updateVisibility()
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
    private fun updateVisibility() {
        val view = root ?: return
        val want = !homeForeground
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

    private fun setHomeForeground(value: Boolean) {
        if (homeForeground == value) return
        homeForeground = value
        updateVisibility()
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
        bar.onHomeLongPress = { sendSystemBack() }

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
        bar.onFan = { VehicleController.stepFan(1) }
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

    override fun onDestroy() {
        Logx.i(LogTags.DOCK, "全局 Dock 服务销毁")
        main.removeCallbacksAndMessages(null)
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
        private const val DESIGN_WIDTH = 1920

        /** 底栏条设计高：Dock 104 + 底边距 8。 */
        private const val DESIGN_BAR_HEIGHT = 112

        /** Dock 自身设计尺寸（与 `LauncherView.buildBottomBar` 一致）。 */
        private const val DESIGN_DOCK_WIDTH = 1872
        private const val DESIGN_DOCK_HEIGHT = 104

        /** Dock 设计坐标左内边距（1920 板宽下 Dock 居中： (1920-1872)/2 = 24）。 */
        private const val DOCK_LEFT_MARGIN = 24

        /** 不透明底栏颜色：Dock 玻璃底 `#6b162e2c` 的同色系不透明值。 */
        private const val OPAQUE_BAR_COLOR = 0xFF162E2C.toInt()

        private const val TICK_MS = 1_000L
        private const val QUICK_APP_REFRESH_TICKS = 30

        /** 每 5s 兜底重绑一次空调状态（observer 可能晚于服务注册）。 */
        private const val HVAC_REFRESH_TICKS = 5

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

        /** 服务是否已经挂上窗口（给设置页 / 排障用）。 */
        fun isRunning(): Boolean = active?.root != null
    }
}
