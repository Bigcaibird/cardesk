package com.cardesk.launcher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.cardesk.launcher.core.StartupTrace
import com.cardesk.launcher.core.dock.GlobalDockService
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.NotificationAccess
import com.cardesk.launcher.ui.LaunchSplashView
import com.cardesk.launcher.ui.LauncherView

/**
 * 唯一的 Activity：承载整个桌面。
 *
 * 作为 HOME 应用被系统拉起（Manifest 里声明了 MAIN/LAUNCHER/HOME），
 * 不主动 finish，返回键交给 [LauncherView] 处理。
 *
 * ## 冷启动：先出画面，再建桌面（第九十三轮）
 *
 * 旧流程是 `LauncherView(this)` 直接在 `onCreate` 里同步构造 —— 车控绑定、
 * 天气/定位、媒体会话、广播注册、整棵树 build() 全压在主线程，
 * 首帧要等这些做完才出。系统 starting window 又是近黑色，
 * 用户看到的就是「车机开机黑屏一段时间才加载到桌面」。
 *
 * 现在的顺序（每一段都有 [StartupTrace] 计时）：
 *
 * 1. `onCreate`：只初始化日志/崩溃兜底/全屏，然后 **`setContentView(启动页)`**；
 * 2. 启动页画出第一帧（`splashFirstFrame`）后，才 `post` 去构造 `LauncherView`；
 * 3. 桌面自己画出第一帧（`launcherFirstFrame`）后，调 `startServices()` 拉起天气 /
 *    定时刷新 / 导航引导，再启动媒体、氛围灯、任务调度与权限延迟检查；
 * 4. 启动页淡出（[SPLASH_FADE_MS]），并打一行 `启动完成` 汇总。
 *
 * 期间 `onResume` / `onNewIntent` / 返回键等回调可能早于桌面就绪，
 * 统一走 [whenLauncherReady] 排队，绝不 NPE。
 */
class MainActivity : Activity() {

    /** 根容器：启动页与桌面都挂在它上面（启动页在上层）。 */
    private var root: FrameLayout? = null

    /** 启动页（桌面首帧后淡出移除）。 */
    private var splash: LaunchSplashView? = null

    /**
     * 桌面。第九十三轮改成可空：
     * 启动页先出第一帧，[LauncherView] 是之后才构造的。
     */
    private var launcher: LauncherView? = null

    /**
     * 桌面还没就绪时排队的动作（全局 Dock 带回的 Intent、onResume 里的重排等）。
     *
     * 桌面首帧后由 [runPendingReadyActions] 按顺序执行；每个都用 `runCatching` 包住，
     * 一个失败不影响后面的。
     */
    private val pendingReadyActions = ArrayList<(LauncherView) -> Unit>()

    /**
     * 已经因为「没通知使用权」跳过一次系统设置页。
     *
     * 防止从设置页返回后又跳一次（用户明明点了拒绝）。
     */
    private var notificationGuideShown = false

    private val notificationCheck = Runnable { checkNotificationPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志系统要先于一切初始化（后面的每一步埋点都依赖它）
        Logx.init(applicationContext)
        // 启动分段计时从这里开始（total 从进程启动算，不依赖这里）；
        // install 让它能把「上次冷启动」持久化，诊断包 summary.txt 里直接能看。
        StartupTrace.install(applicationContext)
        StartupTrace.mark("onCreate")
        // 崩溃捕获（第七十九轮 P0）：**必须在日志系统之后、其它初始化之前**装上，
        // 否则「一进某个页就崩」时我们自己的文件日志里一条线索都没有。
        installCrashHandler()
        Logx.i(
            LogTags.APP,
            "桌面启动",
            "sdk" to Build.VERSION.SDK_INT,
            "pid" to Process.myPid(),
            "restored" to (savedInstanceState != null),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        // 隐藏 API 豁免：车机的天气 SDK / TBox 都是 ROM 私有类，
        // 没有豁免就反射不到（走的是 targetSdk 29 的口径，我们已对齐）。
        // 只影响本进程，失败也不影响任何功能 —— 见 `core/HiddenApiCompat`。
        com.cardesk.launcher.core.HiddenApiCompat.applyOnStart(applicationContext)

        // 第九十三轮：先只挂启动页。**不要**在这里构造 LauncherView ——
        // 那是整条启动链里最重的一步，必须等启动页画出第一帧之后再做。
        showSplash()

        // 全局 Dock（第八十七轮）：服务本身很轻（只是 ensureStarted），
        // 不占首帧；带动作的 Intent 由 whenLauncherReady 排队到桌面就绪后执行。
        ensureGlobalDockStarted()
        handleGlobalDockIntent(intent)
    }

    // ------------------------------------------------------------------
    // 冷启动分段（第九十三轮）
    // ------------------------------------------------------------------

    /**
     * 挂上启动页（纯 Canvas 视图，第一帧毫秒级）。
     *
     * 为什么用 `addOnPreDrawListener` 而不是 `postDelayed`：
     * preDraw 是**这一帧真正要画之前**的回调，用它可以精确知道启动页已经上屏；
     * 固定延时在不同车机上快慢不一，快的白等、慢的提前开始卡主线程。
     */
    private fun showSplash() {
        val container = FrameLayout(this)
        container.setBackgroundColor(0xFF05080F.toInt())
        val splashView = LaunchSplashView(this)
        container.addView(
            splashView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root = container
        splash = splashView
        setContentView(container)
        StartupTrace.mark("window")

        container.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    // 还没量出尺寸的 preDraw 不算：继续等下一帧。
                    if (container.width == 0 || container.height == 0) return false
                    container.viewTreeObserver.removeOnPreDrawListener(this)
                    StartupTrace.mark("splashFirstFrame")
                    container.post { createLauncher() }
                    return true
                }
            },
        )
    }

    /**
     * 构造桌面并加入根容器（此刻启动页还在上面盖着，用户看不到它）。
     *
     * `LauncherView` 的构造里仍有 `build()`（整棵 UI 树），会在主线程上跑一会儿；
     * 但此时屏幕上已经有启动页，观感是「启动中」而不是黑屏。
     */
    private fun createLauncher() {
        if (isFinishing || isDestroyed) return
        val container = root ?: return
        val view = LauncherView(this)
        launcher = view
        // 加在最底层：启动页（后加的那个）盖在它上面。
        container.addView(
            view,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        StartupTrace.mark("launcherCreated")

        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    StartupTrace.mark("launcherFirstFrame")
                    view.post { onLauncherFirstFrame() }
                    return true
                }
            },
        )
    }

    /**
     * 桌面画出第一帧：现在才做「重活」，然后淡出启动页。
     */
    private fun onLauncherFirstFrame() {
        if (isFinishing || isDestroyed) return
        val view = launcher ?: return
        StartupTrace.mark("launcherReady")

        // 天气 / 定时刷新 / 导航引导（见 LauncherView.startServices）。
        runCatching { view.startServices() }
            .onFailure { Logx.w(LogTags.APP, "启动桌面服务失败", it) }
        StartupTrace.mark("servicesStarted")

        // 媒体 / 氛围灯 / 任务调度 / 权限延迟检查。
        startLaunchServices(view)
        StartupTrace.mark("launchServicesStarted")

        // 桌面就绪前排队下来的动作（全局 Dock Intent 等）。
        runPendingReadyActions()

        fadeOutSplash()
    }

    /**
     * 媒体、氛围灯、任务调度、权限检查这些可以在桌面首帧之后再做。
     *
     * 以前它们都在 `onCreate` 里同步/半同步启动；现在统一收在这里，
     * 延迟检查仍是原来的时间点（从桌面首帧算起，而不是从 `onCreate` 算起）。
     */
    private fun startLaunchServices(view: LauncherView) {
        runCatching {
            com.cardesk.launcher.core.TaskScheduler.scheduleLaunchTasks(
                applicationContext,
                com.cardesk.launcher.core.Prefs(applicationContext),
            )
        }.onFailure { Logx.w(LogTags.TASK, "桌面内任务调度失败", it) }

        runCatching { com.cardesk.launcher.core.media.MusicController.get().start(applicationContext) }
            .onFailure { Logx.w(LogTags.MEDIA, "媒体会话启动失败", it) }

        // 氛围灯（第七十轮）：绑上下文；上次是开着的就把频谱采集拉起来。
        // 第七十一轮接上了真实通道：车机端 AdaptAPI 的
        // `SETTING_FUNC_AMBIENCE_LIGHT_*` 一组设置类功能号
        // （颜色 / 亮度 / 互动效果=音乐律动）。
        runCatching {
            val ambient = com.cardesk.launcher.core.ambient.AmbientLightController
            ambient.attach(applicationContext)
            ambient.setApi(com.cardesk.launcher.core.ambient.AdaptapiAmbientLightApi())
            if (ambient.isEnabled(applicationContext)) ambient.start(applicationContext)
        }.onFailure { Logx.w(LogTags.AMBIENT, "氛围灯启动失败", it) }

        // MainActivity.checkNotificationPermission():1297-1305 —— 没通知使用权就跳系统设置页。
        // 这里延后一点再判：通知监听服务是**异步绑定**的，立刻判会把「还没绑上」误判成「没授权」，
        // 白白把用户弹到系统设置页。
        view.removeCallbacks(notificationCheck)
        view.postDelayed(notificationCheck, NOTIFICATION_CHECK_DELAY_MS)
        // 定位权限（天气要用）。真机上「权限显示已授予但一个位置都拿不到」——
        // 根因是 AppOps 没打开（pm grant 只写 permission grant）。
        // 这里走一次正常授权流程：system app 直接写 AppOps，普通安装走系统弹窗。
        view.postDelayed(locationCheck, LOCATION_CHECK_DELAY_MS)
        // 录音权限（氛围灯频谱要用）。
        //
        // ## 为什么启动时**不弹窗**
        //
        // 弹窗会打断首屏观感，而频谱是「氛围灯」这个可选功能的附属品。
        // 所以启动时只走**不打扰用户**的特权路径（system app 直接开 AppOps）；
        // 系统弹窗留到用户进「氛围灯」设置页时再要 —— 那时用户正要开这个功能，
        // 弹窗最自然（见 `ui/SettingsOverlay.pageAmbient`）。
        view.postDelayed(recordAudioCheck, RECORD_AUDIO_CHECK_DELAY_MS)
        // 恢复上次开着的氛围灯（排在录音权限检查之后 —— 采集要权限）。
        view.removeCallbacks(ambientRestore)
        view.postDelayed(ambientRestore, AMBIENT_RESTORE_DELAY_MS)
    }

    /** 启动页淡出，随后移除自己（桌面已经完整可交互）。 */
    private fun fadeOutSplash() {
        val current = splash ?: return
        splash = null
        current.animate()
            .alpha(0f)
            .setDuration(SPLASH_FADE_MS)
            .withEndAction {
                (current.parent as? ViewGroup)?.removeView(current)
                current.post {
                    StartupTrace.mark("desktopReady")
                    StartupTrace.summary()
                }
            }
            .start()
    }

    /**
     * 桌面就绪前执行 [action]，就绪后立即执行。
     *
     * 为什么需要：`onCreate` 里可能已经收到 `onNewIntent` 的动作、
     * `onResume` 也可能先于桌面首帧到达 —— 那时 [launcher] 还是 null。
     */
    private fun whenLauncherReady(action: (LauncherView) -> Unit) {
        val view = launcher
        if (view != null) action(view) else pendingReadyActions.add(action)
    }

    private fun runPendingReadyActions() {
        if (pendingReadyActions.isEmpty()) return
        val actions = ArrayList(pendingReadyActions)
        pendingReadyActions.clear()
        for (action in actions) {
            runCatching { action(launcher ?: return@runCatching) }
                .onFailure { Logx.w(LogTags.APP, "启动就绪动作失败", it) }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期 / 输入
    // ------------------------------------------------------------------

    /**
     * 装上**未捕获异常**的兜底（第七十九轮 P0）。
     *
     * ## 为什么必须有
     *
     * 以前本项目**没有**自定义 `UncaughtExceptionHandler`：
     * 未捕获异常走系统默认流程 → FATAL 只进 logcat →
     * 我们导出的诊断包里**只有我们自己的 `Logx` 输出**，崩溃栈一条都没有。
     *
     * 第七十八轮「氛围灯一打开就黑屏、过一会儿闪退」就是这么变成一笔糊涂账的：
     * 诊断包里没有任何异常记录，只能靠代码审查反推。
     *
     * ## 现在
     *
     * 崩溃时先把**线程名 + 完整堆栈**落进文件日志，再交给系统原来的处理流程
     * （系统该弹框还是弹框、该杀进程还是杀进程 —— 我们不改变系统的行为，
     * 只是**在进程消失之前把证据留下来**）。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString().replace("\n", " <- ")
                Logx.e(
                    LogTags.APP,
                    "未捕获异常（进程即将退出）",
                    error,
                    "thread" to thread.name,
                    "stack" to trace,
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 录音权限的**静默**检查（不弹窗）。
     *
     * system app 有 `SET_APPOP` 时直接把自己的录音 AppOps 打开；
     * 拿不到也不影响任何功能（真正弹窗的时机在设置页）。
     */
    private val recordAudioCheck = Runnable {
        runCatching {
            val granted = com.cardesk.launcher.core.Permissions.hasRecordAudioAppOp(applicationContext)
            if (!granted) {
                com.cardesk.launcher.core.Permissions.tryGrantRecordAudioAppOps(applicationContext)
            }
            Logx.i(
                LogTags.AMBIENT,
                "录音权限检查（不弹窗）",
                "granted" to granted,
                "state" to com.cardesk.launcher.core.Permissions.describeRecordAudioState(this@MainActivity),
            )
        }.onFailure { Logx.w(LogTags.AMBIENT, "录音权限检查失败", it) }
    }

    /**
     * 恢复**上次开着的氛围灯**（第八十五轮新增）。
     *
     * ## 为什么要有这一步
     *
     * 以前 `ambient_enabled` 这个开关只写不读：
     * 没有任何地方在进程启动时把它读回来 ⇒
     * 用户开了氛围灯，桌面一重启（或车机重启），
     * 设置页里的开关**还显示「开」**，但采集与写灯**根本没启动** ——
     * 灯就是再也不亮了，而界面上看不出任何异常。
     *
     * ## 为什么延后一点
     *
     * 排在录音权限检查之后：频谱采集要 `RECORD_AUDIO`，
     * 权限还没到位就起采集只会拿到「不可用」状态。
     */
    private val ambientRestore = Runnable {
        runCatching {
            if (!com.cardesk.launcher.core.ambient.AmbientLightController.isEnabled(applicationContext)) return@runCatching
            Logx.i(LogTags.AMBIENT, "恢复上次开着的氛围灯")
            com.cardesk.launcher.core.ambient.AmbientLightController.start(applicationContext)
        }.onFailure { Logx.w(LogTags.AMBIENT, "恢复氛围灯失败", it) }
    }

    /** 申请定位权限（延后一点，别和桌面首帧抢）。 */
    private val locationCheck = Runnable {
        runCatching { com.cardesk.launcher.core.Permissions.ensureLocation(this) }
            .onFailure { Logx.w(LogTags.WEATHER, "定位权限处理失败", it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 录音权限（频谱采集用）。批下来了就立刻重启采集 ——
        // 采集器停在「缺 RECORD_AUDIO」的死状态里，不重启就永远起不来。
        if (requestCode == com.cardesk.launcher.core.Permissions.REQUEST_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            Logx.i(
                LogTags.AMBIENT,
                "录音权限申请结果",
                "granted" to granted,
                "state" to com.cardesk.launcher.core.Permissions.describeRecordAudioState(this),
            )
            if (granted) {
                runCatching {
                    com.cardesk.launcher.core.ambient.AmbientLightController.restartSpectrum(applicationContext)
                }.onFailure { Logx.w(LogTags.AMBIENT, "录音授权后重启频谱失败", it) }
            }
            return
        }
        if (requestCode != com.cardesk.launcher.core.Permissions.REQUEST_LOCATION) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        Logx.i(
            LogTags.WEATHER,
            "定位权限申请结果",
            "granted" to granted,
            "state" to com.cardesk.launcher.core.Permissions.describeLocationState(this),
        )
        // 授权完成后立刻重跑一轮天气取数。
        if (granted) {
            runCatching { launcher?.refreshWeather() }
                .onFailure { Logx.w(LogTags.WEATHER, "定位授权后刷新天气失败", it) }
        }
    }

    override fun onResume() {
        super.onResume()
        Logx.i(LogTags.APP, "onResume")
        hideSystemUi()
        // 从系统「通知使用权」页返回时再判一次：授权成功就不该再跳
        // 从系统「应用权限」页返回时也再判一次定位。
        whenLauncherReady { view ->
            view.removeCallbacks(notificationCheck)
            view.postDelayed(notificationCheck, NOTIFICATION_CHECK_DELAY_MS)
            view.removeCallbacks(locationCheck)
            view.postDelayed(locationCheck, LOCATION_CHECK_DELAY_MS)
            // 授权 / 开关状态可能变了：重算「其它应用的沉浸策略」。
            runCatching { view.syncGlobalDockPolicy() }
                .onFailure { Logx.w(LogTags.DOCK, "同步全局 Dock 策略失败", it) }
        }
        // 全局 Dock（第八十七轮）：桌面在前台 → 全局窗口隐身。
        // 用自家 Activity 的生命周期做前台判定，**不申请** REAL_GET_TASKS
        // （那要多一个系统权限，而且车机上拿不到）。
        GlobalDockService.onHomeForeground(true)
        // 用户可能刚在系统设置里授了悬浮窗权限（pageDock 引导跳转），
        // 回到桌面时补一次启动；没有开关 / 没授权时 ensureStarted 自己会退。
        ensureGlobalDockStarted()
    }

    override fun onPause() {
        Logx.i(LogTags.APP, "onPause")
        // 桌面退到后台 → 全局窗口该出来了（别的应用现在是前台）。
        GlobalDockService.onHomeForeground(false)
        super.onPause()
    }

    /**
     * `singleTask` 的桌面已经活着时，从全局 Dock / 通知栏带动作拉起来走这里。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGlobalDockIntent(intent)
    }

    /**
     * 把 [EXTRA_ACTION] 转给 [LauncherView.handleGlobalDockAction]。
     *
     * 为什么要 `post`：`onNewIntent` 可能在 `onPause→onResume` 之前到达，
     * 那时抽屉 / 空调浮层的容器状态还没稳；排到消息队列末尾保证
     * 「桌面已经在前台且完成一帧」后再动页面，动画才不会跳。
     *
     * 第九十三轮：桌面可能还没构造（启动页阶段），此时先排队，首帧后执行。
     */
    private fun handleGlobalDockIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return
        // 动作是一次性的：同一个 Intent 再被系统复用时不该重复执行。
        runCatching { intent.removeExtra(EXTRA_ACTION) }
        Logx.event(LogTags.DOCK, "全局 Dock 带回桌面动作", "action" to action)
        whenLauncherReady { view ->
            view.post {
                runCatching { view.handleGlobalDockAction(action) }
                    .onFailure { Logx.w(LogTags.DOCK, "全局 Dock 动作执行失败", it, "action" to action) }
            }
        }
    }

    private fun ensureGlobalDockStarted() {
        runCatching { GlobalDockService.ensureStarted(applicationContext) }
            .onFailure { Logx.w(LogTags.DOCK, "拉起全局 Dock 失败", it) }
    }

    override fun onDestroy() {
        runCatching { com.cardesk.launcher.core.media.MusicController.get().stop() }
            .onFailure { Logx.w(LogTags.MEDIA, "媒体会话停止失败", it) }
        pendingReadyActions.clear()
        launcher = null
        splash = null
        root = null
        Logx.i(LogTags.APP, "onDestroy")
        super.onDestroy()
    }

    /**
     * 通知使用权检查 + 引导。
     *
     * **判定条件一致**：系统白名单里没有我们的通知监听组件 → 跳
     * `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`。
     *
     * 只有这一条通路（只看
     * `enabled_notification_listeners` 白名单串，所以它**总是弹**。
     * 之前我们把条件放宽成「会话枚举有没有通路」（`MEDIA_CONTENT_CONTROL`
     * 也算，于是平台签名的 system 包（自带该权限）在真机上永远不弹 ——
     * 与行为不符，用户反馈「版是会弹的」。
     *
     * 与差异只有一处：**本次进程内只引导一次**
     * 用户拒绝后不会被反复弹走（每次 onCreate 都会弹）。
     */
    private fun checkNotificationPermission() {
        if (NotificationAccess.isReferenceGranted(this)) {
            if (notificationGuideShown) {
                Logx.i(LogTags.APP, "通知使用权已授予", "state" to NotificationAccess.describeEnumeration(this))
            }
            notificationGuideShown = false
            return
        }
        if (notificationGuideShown) return
        notificationGuideShown = true
        Logx.event(
            LogTags.APP,
            "未授予通知使用权，跳系统设置页引导",
            "component" to NotificationAccess.referenceListenerComponent(this),
        )
        val opened = NotificationAccess.openSettings(this)
        if (opened) {
            // 没有这一步；只是给用户一个「为什么要跳」的提示，不改变任何行为
            Toast.makeText(
                this,
                "请在系统设置中授予「通知使用权」，否则桌面无法读取音乐信息",
                Toast.LENGTH_LONG,
            ).show()
        }
        Logx.i(
            LogTags.APP, "通知使用权引导结束",
            "opened" to opened,
            "state" to NotificationAccess.describeEnumeration(this),
        )
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 返回键永远不退出桌面：先关浮层，再回首页，最后停在首页。
        // 作为 HOME 应用不调用 super.onBackPressed()，否则会把桌面自己 finish 掉。
        // 第九十三轮：桌面可能还在构造中（启动页阶段），此时按键应当无效而不是崩。
        launcher?.onBack()
    }

    /**
     * 画中画全屏时**拦下返回键**。
     *
     * ## 为什么必须在这里拦
     *
     * AOSP 的 `ActivityView` 会把 key event **转发给 VirtualDisplay 里的应用**
     * （`ActivityView.dispatchKeyEvent`），所以全屏时按返回键，
     * 事件被承载应用（高德）吃掉 —— 桌面这边根本收不到，
     * 结果「画中画全屏」变成**单向门**：进得去、出不来。
     *
     * `Activity.dispatchKeyEvent` 比 View 树的派发更早，
     * 在这里吃掉 BACK，全屏就能用返回键还原。
     *
     * 做法是全屏时露出 `navSplit` 玻璃条当还原按钮
     * ——
     * 我们画中画全屏时是提到根容器上的，`navSplit` 被盖住，
     * 所以这里用返回键兜底。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            val view = launcher
            if (view != null && view.isPipFullscreen()) {
                Logx.event(LogTags.NAV_UI, "画中画全屏：返回键还原")
                view.exitPipFullscreen()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 打开系统相册选壁纸（第 105 轮：自定义壁纸）。
     *
     * 用 `ACTION_OPEN_DOCUMENT`（不需要存储权限），结果在 [onActivityResult] 里回到
     * [LauncherView.onWallpaperPicked]。
     */
    fun startWallpaperImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, REQ_WALLPAPER)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "没有可用的图片选择器", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WALLPAPER) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            launcher?.onWallpaperPicked(uri)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        /** 系统相册选壁纸的请求码（第 105 轮）。 */
        private const val REQ_WALLPAPER = 0x5101

        /**
         * 全局 Dock 的「回桌面后要做什么」（第八十七轮）。
         *
         * 全局窗口没有 `LauncherView` 的页面上下文：抽屉、分屏、空调浮层、
         * 360 环影这些都得回到桌面再执行。于是用 Intent extra 把动作带过来，
         * 由 [handleGlobalDockIntent] 转发给 [LauncherView.handleGlobalDockAction]。
         */
        const val EXTRA_ACTION = "com.cardesk.launcher.extra.GLOBAL_DOCK_ACTION"

        /** 回桌面并打开应用抽屉（`onApps` / `onAppsDrawer`）。 */
        const val ACTION_DRAWER = "drawer"

        /** 回桌面并打开应用抽屉（契约里的 `apps` 别名，语义与 [ACTION_DRAWER] 相同）。 */
        const val ACTION_APPS = "apps"

        /** 回桌面并打开空调浮层（契约 `climate`；全局 Dock 目前的风量键直调车控，不跳页）。 */
        const val ACTION_CLIMATE = "climate"

        /** 回桌面并切到下一页（`onSplit`）。 */
        const val ACTION_SPLIT = "split"

        /** 回桌面并切单/双画中画（`onSplitLongPress`）。 */
        const val ACTION_SPLIT_LONG = "split_long"

        /** 回桌面并打开 360 环影（`on360`）。 */
        const val ACTION_CAMERA360 = "camera360"

        /** 回桌面并打开媒体页（`onMediaTap` / `onMediaLongPress`）。 */
        const val ACTION_MEDIA = "media"

        /** 回桌面并打开最近任务（Dock 主页键长按 / 导航栏「最近任务」槽）。 */
        const val ACTION_RECENTS = "recents"

        /** 启动页淡出时长（毫秒）。要短到不挡操作，长到不闪。 */
        const val SPLASH_FADE_MS = 220L

        /**
         * 延迟多久再判通知使用权。
         *
         * 通知监听服务是系统异步绑定的，刚启动时通常还没绑上；
         * 太早判会把「还没绑上」误判成「没授权」，白把用户弹到系统设置页。
         */
        const val NOTIFICATION_CHECK_DELAY_MS = 2000L

        /**
         * 延迟多久再申请定位权限。
         *
         * 定位权限是天气的前提，但弹窗会打断首屏观感 —— 延后一点再问。
         * 用户拒绝后不会重复弹（系统行为），只在 `onResume` 再判一次状态。
         */
        const val LOCATION_CHECK_DELAY_MS = 3500L

        /**
         * 延迟多久做**录音权限的静默检查**（只开 AppOps，不弹窗）。
         *
         * 跟定位一样延后一点，别和桌面首帧抢；
         * 真正的系统弹窗在用户进「氛围灯」设置页时才发起。
         */
        const val RECORD_AUDIO_CHECK_DELAY_MS = 4000L

        /**
         * 延迟多久恢复上次开着的氛围灯。
         *
         * 要**晚于** [RECORD_AUDIO_CHECK_DELAY_MS]：
         * 频谱采集需要 `RECORD_AUDIO`，权限还没到位就起采集只会拿到「不可用」。
         */
        const val AMBIENT_RESTORE_DELAY_MS = 5200L
    }
}
