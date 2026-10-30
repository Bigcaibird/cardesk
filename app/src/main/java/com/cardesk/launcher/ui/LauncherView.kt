package com.cardesk.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.DockQuickApps
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.NavSlot
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.PageGesture
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.media.LyricTimeline
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.core.media.TrackInfo
import com.cardesk.launcher.ui.pages.NavPage
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.ui.pip.PipSurface
import com.cardesk.launcher.ui.shell.DockBarView
import com.cardesk.launcher.ui.shell.EnhancedNavBarView
import com.cardesk.launcher.ui.shell.NavSlotBarView
import com.cardesk.launcher.ui.shell.PageDotsView
import com.cardesk.launcher.ui.shell.ShellStatusBar
import com.cardesk.launcher.ui.widget.BackgroundView
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.VehicleController
import com.cardesk.launcher.vehicle.currentVehicleInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 桌面根视图（结构 activity_main）
 *
 * `
 * FrameLayout(1920×1080，等比缩放居中)
 *  ├── 壁纸 BackgroundView
 *  ├── pageHost 首页场景 / 车辆 / 应用（满屏 1920×1080）
 *  ├── PageDotsView 右侧页面指示器（三个点）
 *  ├── ShellStatusBar 顶部状态栏（左头像+状态胶囊，右图标+时间）
 *  ├── DockBarView 底部一体化控制栏 1864×104（入口/媒体/空调）
 *  └── overlayHost 空调浮层 / 360 / 设置面板
 * `
 */
class LauncherView(context: Context) : FrameLayout(context) {

    companion object {
        /**
         * 页面编号。
         *
         * 是三页（：0=场景页、1=导航页、2=音乐页）。
         * **第三十二轮按用户要求去掉第三页（播放器页）**：
         * 播放器搬到了**第二页组合卡的右半屏**（见 `NavPage.buildCombinedCard`），
         * 所以桌面现在只剩两页。
         *
         * 翻页用取模循环，不是夹紧。
         */
        const val PAGE_SCENE = 0
        const val PAGE_NAV = 1

        /** 页面总数（第三十二轮：3 → 2）。 */
        const val PAGE_COUNT = 2

        /**
         * 天气刷新周期：30 分钟
         *
         */
        const val WEATHER_REFRESH_MS = 1_800_000L

        /**
         * 车身状态变化后的立即刷新**节流**（毫秒）。
         *
         * 车端一次开门会连着回调好几条（每个区域一条），不节流会连刷好几次。
         */
        const val BODY_REFRESH_MIN_INTERVAL_MS = 400L

        /** 右缘竖滑命中区宽度。 */
        private const val EDGE_WIDTH = 120f

        /** Dock 距屏幕底的设计像素（设计板 s01：底边 1072 → 距底 8）。 */
        private const val DOCK_BOTTOM_MARGIN = 8

        /** 右缘竖滑的接管阈值。 */
        private const val EDGE_SLOP = 40f

        /** 右缘竖滑的翻页阈值。 */
        private const val EDGE_TRIGGER = PageGesture.EDGE_TRIGGER

        /**
         * **三指横滑**换页的位移阈值（设计坐标 px）。
         *
         * `m1183df(160f)`
         * 只是方向从「竖滑」改成「横滑」（第三十二轮用户要求）。
         */
        private const val THREE_FINGER_PX = PageGesture.THREE_FINGER_PX

        /**
         * **单指横滑**换页的位移阈值（设计坐标 px，第三十七轮用户要求）。
         *
         * 与三指横滑同一个数量级（`m1183df(160f)`）
         * 这样「轻轻一划」不会误触发，「明确往一边划」才会翻页。
         */
        private const val SINGLE_FINGER_PX = PageGesture.SINGLE_FINGER_PX

        /**
         * 单指横滑的接管阈值（设计坐标 px）。
         *
         * 超过这个位移才把事件从子视图抢回来 —— 比翻页阈值小，
         * 这样手势过程中页面不会「先卡住再翻」。
         */
        private const val SINGLE_FINGER_SLOP = 24f

        /** 滚轮翻页的冷却时间（700ms）。 */
        private const val SCROLL_LOCK_MS = 700L
    }

    private val prefs = Prefs(context.applicationContext)
    private var pal: Palette = resolvePalette().also { Skin.apply(it) }
    private val background = BackgroundView(context)
    private val content = FrameLayout(context)

    private lateinit var statusBar: ShellStatusBar
    private lateinit var dock: DockBarView
    private lateinit var bottomBar: android.view.View
    private var slotsBar: NavSlotBarView? = null

    /**
     * 叠在「Dock 快捷应用配置面板」之上的**应用选择器**（第五十四轮）。
     *
     * 它不走 `showOverlayDialog`（那个会摘掉上一个弹窗），所以单独记一份：
     * 返回键要优先收它，不能把配置面板先关掉、把选择器留在屏幕上。
     */
    private var quickAppPicker: View? = null

    /**
     * 最近一次构建底栏时用的**导航栏样式**（0 = 设计 Dock，1 = 槽位条）。
     *
     * 设置里改样式要整层重建（底栏形态不同），用这个字段判断要不要重建。
     */
    private var builtNavStyle = -1

    /**
     * 最近一次构建底栏时的**增强导航栏开关**。
     *
     * 增强导航栏开/关换的是底栏的**形态**（贴底 pill ↔ 独立 Dock），
     * 光改 visibility 换不过来 —— 必须整层重建。
     */
    private var builtEnhancedNav = false
    private var navSlotDialog: NavSlotDialog? = null
    private var taskList: com.cardesk.launcher.ui.dialogs.TaskListDialog? = null
    private var taskEditor: com.cardesk.launcher.ui.dialogs.TaskEditorDialog? = null
    private var overlayDialog: View? = null
    private var floatLayer: com.cardesk.launcher.ui.floats.FloatWindowLayer? = null
    private var navSlots: MutableList<NavSlot> = mutableListOf()
    private lateinit var dots: PageDotsView
    private lateinit var floats: FloatBubbleView
    private lateinit var pageHost: FrameLayout
    private lateinit var scenePage: ScenePage
    private lateinit var navPage: NavPage
    private lateinit var drawerPage: DrawerPage

    private var climate: ClimatePage? = null
    private var hvac: HvacPanelView? = null
    private var settings: SettingsOverlay? = null
    private lateinit var sourceMenu: SourceMenuLayer

    /**
     * 天气（`WeatherDesk` +）。
     *
     * 场景页的天气卡要显示「城市 • 天气 · 温度」，数据来自网络 + 定位；
     * 拿不到时用车外温度兜底（`ambient` 分支）。
     */
    private var weatherDesk: com.cardesk.launcher.core.WeatherDesk? = null
    private var weatherCity = ""
    private var weatherSky = ""
    private var weatherTemp: Int? = null

    /** 导航引导数据源。 */
    private var navGuideSource: com.cardesk.launcher.core.nav.AmapDesktopGuide? = null

    /** 最近一条引导数据。 */
    private var navGuideRaw: com.cardesk.launcher.core.nav.AmapDesktopGuide.Guide? = null

    /** 最近一次收到的车道线原文。 */
    private var navLanesRaw: String? = null

    private var page = PAGE_SCENE
    /** 画中画是否已被放大到全屏。 */
    private var pipFullscreen = false
    // 右缘竖滑
    private var edgeTracking = false
    private var edgeIntercepted = false
    private var edgeDownY = 0f

    // 三指横滑换页（第三十二轮：由「三指竖滑」改成「三指横滑」）
    private var threeFinger = false

    // 单指横滑换页（第三十七轮：主手势）
    private var singleTracking = false
    private var singleIntercepted = false
    private var singleDownX = 0f
    private var singleDownY = 0f
    private var singleDx = 0f
    private var singleDy = 0f

    /** 第三指按下时的三指中位点 X（设计坐标）——位移的起点。 */
    private var threeFingerX = 0f

    /** 最后一次 MOVE 时的三指中位点 X（设计坐标）——位移的终点。 */
    private var threeFingerMid = 0f

    // 滚轮翻页冷却
    private var scrollLockUntil = 0L
    private var playing = false
    private var mediaTrack: TrackInfo? = null
    private var mediaLyric: LyricTimeline? = null
    private var mediaLyricIndex = -1
    private var mediaLyricWipe = 0f
    private var mediaLyricFallback = ""

    /**
     * 切页动画：alpha 600ms、位移/缩放 700ms，插值器 (0.2,0.8,0.2,1)。
     * 必须在 init 之前初始化——init 里就会调用 showPage。
     */
    private val pageAnimators = arrayOfNulls<Animator>(PAGE_COUNT)
    private var pageGen = 0

    private val handler = Handler(Looper.getMainLooper())

    /** 上一次「车身状态变化」触发的立即刷新时刻（`SystemClock.uptimeMillis()`）。 */
    private var lastBodyRefreshAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    /**
     * 天气定时刷新。
     *
     * 每 **30 分钟**调一次
     * 自己续期。在 `weatherDesk == null` 时直接返回（不再续期）
     * 这里照抄 —— `weatherDesk` 为 null 说明桌面接管没起来。
     */
    private val weatherRefresh = object : Runnable {
        override fun run() {
            val desk = weatherDesk ?: return
            Logx.d(LogTags.WEATHER, "天气定时刷新", "periodMs" to WEATHER_REFRESH_MS)
            desk.requestFresh()
            handler.postDelayed(this, WEATHER_REFRESH_MS)
        }
    }

    /**
     * 立刻重跑一轮天气取数。
     *
     * 定位权限刚授权完（或刚从系统权限页返回）时调用 ——
     * 否则要等满 30 分钟的定时刷新才看得到天气。
     */
    fun refreshWeather() {
        val desk = weatherDesk ?: return
        Logx.i(LogTags.WEATHER, "主动刷新天气", "state" to com.cardesk.launcher.core.Permissions.describeLocationState(context))
        desk.requestFresh()
    }

    init {
        // 第五十八轮：卡片圆角是**全局可变 token**，启动时先按偏好摆好。
        com.cardesk.launcher.core.CardStyle.applyRadius(prefs.cardRadius)
        setBackgroundColor(0xFF05080F.toInt())
        addView(background, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.layoutParams = LayoutParams(Design.W.toInt(), Design.H.toInt())
        addView(content, content.layoutParams)

        VehicleController.init(context.applicationContext, prefs)
        VehicleController.addObserver { refresh() }
        // 360 状态回调：只做三件事 ——
        // 刷新导航栏 360 图标、标记「本桌面开启」、按设置切视角；
        // **不隐藏桌面、不缩放、不打开任何自有浮窗**
        // （画面由车机厂商的 AVM/PAC 窗口盖在 launcher 之上）。
        VehicleController.on360State = { on ->
            Logx.i(LogTags.CAM360, "360 状态变化（桌面不弹任何浮层）", "on" to on)
            refresh()
        }
        // 车身状态（车门 / 引擎盖 / 后备箱）变化：立刻刷新车况卡。
        //
        // 是事件驱动的（信号一变就 publishBodyState）
        // 我们桌面每秒轮询一次 —— 有了这个回调，车门一开就显示，不用等下一秒。
        VehicleController.onBodyStateChanged = { refreshBodyState() }

        page = prefs.lastPage.coerceIn(PAGE_SCENE, PAGE_NAV)
        build()

        // 天气：—— 桌面起来就拉一次
        Logx.i(LogTags.WEATHER, "启动天气子系统")
        weatherDesk = com.cardesk.launcher.core.WeatherDesk(
            context.applicationContext,
            object : com.cardesk.launcher.core.WeatherDesk.Listener {
                override fun onWeather(city: String, sky: String, temp: Int?) {
                    weatherCity = city
                    weatherSky = sky
                    weatherTemp = temp
                    Logx.i(LogTags.WEATHER, "天气更新", "city" to city, "sky" to sky, "temp" to temp)
                    refresh()
                }
            },
        ).also { it.start() }
        // 天气定时刷新（`postDelayed(runnable, 1800000)`）
        handler.removeCallbacks(weatherRefresh)
        handler.postDelayed(weatherRefresh, WEATHER_REFRESH_MS)

        handler.post(ticker)
        // 导航引导数据源
        // 场景页建好就 new 一个 `AmapDesktopGuide` 并 start()。
        // 没有导航时不显示任何东西（`visibility="gone"`）。
        navGuideSource = com.cardesk.launcher.core.nav.AmapDesktopGuide(
            context.applicationContext,
            object : com.cardesk.launcher.core.nav.AmapDesktopGuide.Listener {
                override fun onGuide(guide: com.cardesk.launcher.core.nav.AmapDesktopGuide.Guide) {
                    navGuideRaw = guide
                    if (guide.lanes.isNotEmpty()) navLanesRaw = guide.lanes
                    applyNavGuide()
                }

                override fun onLanes(raw: String) {
                    navLanesRaw = raw
                    // 只有引导卡已经显示才重画
                    if (navGuideRaw != null) applyNavGuide()
                }

                override fun onTrafficLight(signal: com.cardesk.launcher.core.nav.AmapTrafficLight.Signal?) {
                    // 红绿灯是「最近路口」的瞬时信号：
                    // 有就画，没有就不画（不占位）。
                    // 收到新帧后立刻重画一次 —— 读秒要马上从车机给的值开始。
                    //
                    // 真正的信号状态放在 `AmapDesktopGuide` 里（那里判有效期、减读秒），
                    // 这里不另存一份，免得两处状态打架。
                    if (navGuideRaw != null) applyNavGuide()
                }

                override fun onStopped() {
                    navGuideRaw = null
                    navLanesRaw = null
                    scenePage.setGuide(null)
                }

                override fun onCityChanged(city: String) {
                    // 高德换了城市 → 重跑一轮天气。
                    //
                    // 真机上系统定位常常拿不到位置（provider 只有 passive,gps 且都没 fix），
                    // 高德广播的城市是我们唯一的坐标来源（见 `core/AmapCitySource`）——
                    // 城市一变就得立刻用新城市再取一次天气，不能等 30 分钟的定时刷新。
                    Logx.i(LogTags.WEATHER, "高德城市变化，重跑天气", "city" to city)
                    weatherDesk?.requestFresh()
                }
            },
        )
    }

    /**
     * 把当前引导数据刷到首页引导卡。
     *
     * 生效的车道线 = 引导数据自带的（非空优先），否则用最近一次单独收到的车道线
     * —— 与 `paintGuideLanes(guide.lanes.isNotEmpty() ? guide.lanes: lastGuideLanes)` 一致。
     */
    private fun applyNavGuide() {
        val guide = navGuideRaw ?: return
        val lanes = guide.lanes.ifEmpty { navLanesRaw.orEmpty() }
        // 红绿灯读秒按本机单调时钟往下走，所以**每次重画都要重新取一次**。
        // `AmapDesktopGuide.trafficLight()` 内部会按已过去的秒数把读秒减掉。
        val light = navGuideSource?.trafficLight()
        scenePage.setGuide(
            com.cardesk.launcher.core.nav.NavGuideModel.build(
                icon = guide.icon,
                segmentMeters = guide.segmentMeters,
                currentRoad = guide.currentRoad,
                nextRoad = guide.nextRoad,
                remainSeconds = guide.remainSeconds,
                remainMeters = guide.remainMeters,
                lanesRaw = lanes,
                trafficLight = light,
            ),
        )
    }

    // --------------------------------------------------------------- 构建 / 换肤

    private fun build() {
        setBackgroundColor(pal.bgTop)
        background.palette = pal
        background.wallpaper = prefs.wallpaper
        // 壁纸被拖拽切到上一张 / 下一张时落盘
        background.onWallpaperChanged = { index ->
            prefs.wallpaper = index
            scenePage.showWallpaperHint()
        }
        content.removeAllViews()

        // 这一轮把 content 整个重建了，上面挂着的浮层（设置 / 空调 / 抽屉 / 槽位编辑 /
        // 最近任务…也一并被摘掉了 —— 引用必须一起清掉。
        //
        // 不清的话：切日夜主题（`onSettingsApplied` → `applyTheme` → `build`）之后，
        // `settings` 还指着那个已经被摘掉的视图， 的
        // `if (settings != null) return` 会让**设置页再也打不开**。
        settings = null
        climate = null
        hvac = null
        navSlotDialog = null
        taskList = null
        taskEditor = null

        // 页面层（满屏，含状态栏/底栏区域，activity_main 的各个 canvas）
        pageHost = FrameLayout(context)
        content.addView(pageHost, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        val wallpaper = { index: Int ->
            applyWallpaper(index)
        }
        scenePage = ScenePage(context).apply {
            onOpenVehicle = { toast("车辆详情（模拟）") }
            onOpenNav = { showPage(PAGE_NAV) }
            onOpenPhone = { toast("蓝牙电话（模拟）") }
            onOpenDrawer = { showDrawer() }
            onOpenMusic = { showPage(PAGE_NAV) }
            // 第三十二轮：**去掉**「左右滑换背景主题」手势
            // （原）。
            // 换壁纸仍可通过提示条上的圆点（onWallpaperDot）完成。
            // —— 非车机应用默认开浮窗
            onOpenApp = { pkg, label ->
                if (!showFloat(pkg, label)) launchFullscreen(pkg, label)
            }
            onPlayPause = { MusicController.get().playPause(); refresh() }
            onSeatHeat = { VehicleController.cycleSeat(HvacIds.ZONE_DRIVER, true) }
            onSeatVent = { VehicleController.cycleSeat(HvacIds.ZONE_DRIVER, false) }
            onMaxFan = { VehicleController.toggleMaxFan() }
            onOpenHvac = { showClimate() }
            onOpen360 = { showCam() }
            onWallpaperDot = wallpaper
            onOpenSourceMenu = { openSourceMenu(scenePage.sourceAnchorRect) }
            // 底部卡片长按拖拽排序的持久化（键 scene_bottom_card_order_v1）
            orderStore = com.cardesk.launcher.core.BottomCardOrder.PrefsStore(prefs)
            onToggleWindow = { closed ->
                // 模拟通道自己消化车窗开闭（这样桌面上的状态会跟着变）；
                // 真车通道走原厂的 VfmiscManager，车窗只有下发、没有车况回读。
                val handled = try {
                    com.cardesk.launcher.vehicle.VehicleController.gateway
                        .setWindowsOpen(!closed)
                } catch (e: Throwable) {
                    false
                }
                if (!handled) {
                    val ok = com.cardesk.launcher.vehicle.OemWindowController.setWindowsClosed(context, closed)
                    if (!ok) toast("车窗控制通道不可用")
                }
            }
        }
        // 导航页（navCanvas / desktopNavPage）：左地图卡（承载画中画）+ 右组合卡（胎压/音乐）
        navPage = NavPage(context, pal).apply {
            // 双画 / 全屏态同步
            syncPipState(prefs.dualPip, pipFullscreen)
            onPlayToggle = { MusicController.get().playPause(); refresh() }
            onPrev = { MusicController.get().previous() }
            onNext = { MusicController.get().next() }
            onSource = { openSourceMenu(navPage.sourceAnchor) }
            onPipFullscreen = { togglePipFullscreen() }
            onSplitToggle = { togglePipFullscreen() }
            pip.onOpenFullscreen = { pkg -> openAppFullscreen(pkg) }
            // 副画的降级卡片同样走全屏打开
            pipSecondary.onOpenFullscreen = { pkg -> openAppFullscreen(pkg) }
            // 占位可点 → 打开画中画应用选择器（可点）
            pip.onPickApp = { showPipPicker(false) }
            pipSecondary.onPickApp = { showPipPicker(true) }
            // 画中画上方三个点
            // 单击 → 左右换位并保存；长按 → 打开画中画应用选择器。
            onPipSwapSides = { navPage.toggleSidesSwapped() }
            onPipPickApp = { showPipPicker(false) }
        }
        // 抽屉是**浮层**，不是整页
        drawerPage = DrawerPage(context, pal).apply {
            onClose = { closeDrawer() }
            onOpenApp = { pkg, name, mini ->
                if (mini) {
                    // 979 —— 非桌面首页静默不打开
                    // 超过 2 个时关掉最老的腾位（不是「已达上限」）。
                    // 全程没有 Toast，这里也不弹，失败原因由 FLOAT 域日志给出。
                    showFloat(pkg, name)
                } else {
                    prefs.lastAppPkg = pkg
                    Logx.event(LogTags.DRAWER, "全屏启动应用", "pkg" to pkg, "label" to name)
                    runCatching {
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (intent != null) context.startActivity(intent)
                    }.onFailure { Logx.w(LogTags.DRAWER, "启动失败", it, "pkg" to pkg) }
                }
            }
        }
        pageHost.addView(scenePage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pageHost.addView(navPage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 右侧页面指示器（view_page_dots：3 个点）
        dots = PageDotsView(context).apply {
            count = PAGE_COUNT
            onDot = { showPage(it) }
        }
        content.addView(dots, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        // 悬浮窗气泡列：位于页面之上、状态栏/Dock 之下。
        // 第六十三轮按用户要求去掉了桌面常驻的「音乐 / 空调」两颗气泡，
        // 这里只剩**被最小化的浮窗**的气泡（点一下还原、长按关闭）。
        floats = FloatBubbleView(context).apply {
            // 最小化浮窗的气泡：点一下恢复，长按关闭（交互）
            onRestoreFloat = { box -> floatLayer?.restore(box) }
            onCloseFloat = { box -> floatLayer?.close(box) }
        }
        content.addView(floats, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        // 浮窗层（page_floats 的窗口集合）：最多 2 个，默认 920×676，可拖动/缩放
        floatLayer = com.cardesk.launcher.ui.floats.FloatWindowLayer(context, pal).apply {
            onChanged = { }
            // 最小化后的浮窗气泡留在气泡列
            onBubbleState = { boxes ->
                floats.setFloatWindows(if (prefs.floatBubbles) boxes else emptyList())
            }
        }
        content.addView(floatLayer, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        // 顶部状态栏
        statusBar = ShellStatusBar(context).apply {
            onAvatar = { showSettings() }
            onAssist = { showHvac() }
        }
        content.addView(statusBar, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        // 底部一体化控制栏：1864×104，距底 8，水平居中
        dock = DockBarView(context).apply {
            // 单击回首页（dockHome「单击返回首页」）
            // 第五十六轮：**先把盖在上面的浮层收掉** —— 抽屉开着的时候点 home，
            // 以前只切页面、抽屉还盖在上面，看起来就是「home 键不生效」。
            onHome = { goHome() }
            // 长按打开原车多任务（dockHome「长按打开原车多任务」）
            onHomeLongPress = { showRecents() }
            // 单击开 / 关抽屉**浮层**（dockApps）
            // 第五十六轮：做成**开关** —— 以前只会「开」，抽屉开着再点没反应，
            // 只能点抽屉以外的地方关。
            onApps = { toggleDrawer() }
            onAppsDrawer = { toggleDrawer() }
            // 单击切换页面（dockSplit「单击切换页面」= showPage((page + 1) % 3)）
            onSplit = { showPage(nextPage(page)) }
            // 长按切换单画 / 双画（dockSplit「长按切换单画和双画」）
            onSplitLongPress = {
                // —— 开双画时主副画不能是同一个应用
                // 否则直接拒绝并提示。
                //
                // 为什么必须拦：同一个应用不能同时挂到两个 VirtualDisplay ——
                // 副画再 new 一个承载面时，`startActivity` 会把应用的 task
                // 从**主画**拉到副画，主画当场黑屏（模拟器已复现）。
                val nextDual = !prefs.dualPip
                val sameApp = nextDual && prefs.pipApp.isNotBlank() && prefs.pipApp == prefs.pip2App
                if (sameApp) {
                    toast("双画中画请选择不同应用")
                    Logx.w(
                        LogTags.NAV_UI,
                        "开启双画中画被拒：主副画是同一个应用",
                        "pkg" to prefs.pipApp,
                    )
                } else {
                    prefs.dualPip = nextDual
                    navPage.syncPipState(prefs.dualPip, pipFullscreen)
                    // 双画开关会决定副画承载面建不建（MainActivity:924-946 走 applyDesktopLayout）
                    refreshPip()
                    toast(if (prefs.dualPip) "已切换双画中画" else "已切换单画中画")
                }
            }
            // 媒体卡左右滑动切歌
            onMediaNext = { MusicController.get().next(); refresh() }
            onMediaPrev = { MusicController.get().previous(); refresh() }
            on360 = { showCam() }
            onMediaTap = { showPage(PAGE_NAV) }
            // 媒体卡长按 → 打开**媒体浮窗**（设计板 s13-float 的 460×250 小播放器）
            onMediaLongPress = { openMediaFloat() }
            onPlay = { MusicController.get().playPause(); refresh() }
            onPower = { VehicleController.togglePower() }
            onTemp = { zone, delta -> VehicleController.stepTemp(zone, delta.toFloat()) }
            onFan = { showClimate() }
            onAc = { VehicleController.toggleAc() }
            onAuto = { VehicleController.toggleAuto() }
            onCirc = { VehicleController.toggleCirculation() }
            onDefrost = { front ->
                if (front) VehicleController.toggleDefrostFront() else VehicleController.toggleDefrostRear()
            }
            // 快捷应用槽位（第五十四轮）：点一下启动，空槽位点一下去配置，长按去配置。
            onQuickApp = { index, pkg -> handleQuickAppTap(index, pkg) }
            onQuickAppLongPress = { index -> showDockQuickApps(index) }
        }
        buildBottomBar()
        refreshDockQuickApps()

        // 媒体来源菜单（musicSourceMenu/sceneSourceMenu）：放在最上层，遮罩可点空白收起
        sourceMenu = SourceMenuLayer(context, pal).apply {
            onPick = { id -> applySource(id) }
            onToggle = { open -> navPage.setSourceMenuOpen(open) }
        }
        content.addView(sourceMenu, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        showPage(page)
        refresh()
        // 第五十六轮：启动就把「沉浸系统导航栏」写进 `policy_control` ——
        // 否则系统自带 Dock 平时还是那个状态，点桌面 Dock 就会把它唤出来。
        applyOemPolicy()
    }

    /** 全屏启动一个应用（画中画降级卡片的「全屏打开」）。 */
    private fun openAppFullscreen(pkg: String) {
        launchFullscreen(pkg, null)
    }

    /**
     * 全屏启动应用。
     *
     * 顺序是：先关掉同包的浮窗
     * 再全屏启动；启动失败弹「无法打开{应用名}」。
     */
    private fun launchFullscreen(pkg: String, label: String?) {
        Logx.event(LogTags.APP, "全屏启动应用", "pkg" to pkg, "label" to label)
        if (pkg.isBlank()) {
            toast("无法打开" + (label ?: ""))
            return
        }
        prefs.lastAppPkg = pkg
        // 506-512 —— 同包全屏时关掉它的浮窗
        floatLayer?.closeForFullscreen(pkg)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("无法打开" + (label ?: pkg))
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { toast("无法打开" + (label ?: pkg)) }
    }

    /**
     * 按设置刷新画中画：主画 / 副画各一个承载面。
     *
     * 画中画挂在导航页的地图卡里（主画 `mapCard` / 副画），
     * 场景页没有画中画。
     *
     * ## 生命周期
     *
     * 在 **Activity onCreate** 就把持久化的
     * `pip_app` 读出来直接 —— 也就是**桌面一起来就承载**，不用等用户翻到导航页；
     * 而 只在两处调用：Activity onDestroy、
     * 双画关闭时。
     *
     * 所以**切页不销毁画中画**（切走时应用继续跑，回来还是原来那个画面）——
     * 这点我们之前做反了：离开导航页就 把承载的应用也一起杀了。
     */
    private fun refreshPip() {
        if (!::navPage.isInitialized) return
        if (pipFullscreen) return
        val dual = prefs.dualPip
        val mainPkg = prefs.pipApp
        val subPkg = prefs.pip2App
        Logx.d(
            LogTags.NAV_UI, "刷新画中画",
            "pkg" to mainPkg, "pkg2" to subPkg,
            "dual" to dual,
        )
        // 默认就是 true ——
        // 主画**只要选了应用**就在导航页地图卡承载，没有额外的「画中画可见」开关。
        // （我们之前多了一个自造的可见开关，导致选完应用画中画不显示。）
        if (mainPkg.isNotBlank()) {
            navPage.pip.show(mainPkg)
        } else {
            // 没选应用 → 显示占位图，不是一块黑屏
            navPage.pip.hide()
        }
        // 副画（主画之外的第二个 → mapCard2，键 pip2_app）
        // 双画关闭时会拆掉承载面，这里对应 `PipSurface.hide()`（内部会 release）。
        when {
            !dual -> navPage.pipSecondary.hide()
            subPkg.isBlank() -> navPage.pipSecondary.hide()
            subPkg == mainPkg -> {
                // **同一个应用不能同时挂到两个 VirtualDisplay。**
                //
                // 副画再 new 一个 ActivityView 承载同一个应用时，`startActivity` 会把
                // 它的 task 从**主画**的 VirtualDisplay 拉到副画去 → 主画直接黑屏。
                // 靠设置页拦截，但「单画时选副画
                // 之后才开双画」这条路会绕过拦截，所以承载时再兜一次。
                Logx.w(
                    LogTags.NAV_UI,
                    "副画与主画是同一个应用，改为显示占位",
                    "pkg" to subPkg,
                )
                navPage.pipSecondary.showPlaceholder(PipSurface.TEXT_SAME_AS_MAIN)
            }
            else -> navPage.pipSecondary.show(subPkg)
        }
    }

    /**
     * 画中画全屏 / 还原（`pipDivider` 单击「主画全屏」）。
     *
     * 全屏时把画中画提到最上层并铺满设计画布；再点一次还原到导航页的地图卡。
     */
    private fun togglePipFullscreen() {
        if (!::navPage.isInitialized) return
        Logx.event(LogTags.NAV_UI, "画中画全屏切换", "to" to if (pipFullscreen) "还原" else "全屏", "pkg" to prefs.pipApp)
        if (pipFullscreen) {
            exitPipFullscreen()
            return
        }
        if (prefs.pipApp.isBlank()) {
            toast("请先在设置里选择画中画应用")
            return
        }
        pipFullscreen = true
        navPage.pip.visibility = android.view.View.VISIBLE
        navPage.syncPipState(prefs.dualPip, true)
        // 全屏：画中画铺满整块画布（不留白）
        navPage.applyPipContentInset(true)
        // **必须先从原父容器摘出来** ——
        // `ViewGroup.addView` 在 child 已有 parent 时会抛
        // `IllegalStateException("The specified child already has a parent")`，
        // 直接崩桌面（模拟器实测：`LauncherView.togglePipFullscreen` → addViewInner）。
        (navPage.pip.parent as? android.view.ViewGroup)?.removeView(navPage.pip)
        content.addView(
            navPage.pip,
            Ui.frame(Design.W.toInt(), Design.H.toInt(), Gravity.TOP or Gravity.START),
        )
        content.bringChildToFront(navPage.pip)
        // 玻璃条一起提到最上层 —— 全屏时它就是「还原」按钮
        // （全屏时 navSplit 可见、点击还原）。
        // 画中画的 ActivityView 会把返回键转发给承载应用，
        // 所以全屏必须有个**看得见的**还原入口。
        navPage.detachNavSplit()?.let { split ->
            navSplitInFullscreen = split
            content.addView(
                split,
                Ui.frame(36, 110, Gravity.START or Gravity.CENTER_VERTICAL),
            )
            content.bringChildToFront(split)
        }
    }

    /**
     * 退出画中画全屏 —— 挂回导航页的地图卡并恢复内容留白。
     *
     * 分隔条单击和返回键都走这里（两条路径必须完全一致：
     * 之前返回键那条只切了页面，**没把画中画挂回地图卡、也没恢复留白**，
     * 结果画中画继续盖在导航页上、而且是全屏尺寸。
     */
    fun exitPipFullscreen() {
        pipFullscreen = false
        // 还原：画中画要挂回导航页的地图卡，不能留在根容器里
        navPage.restorePipToMapCard()
        // 玻璃条也要放回导航页
        // 全屏时它浮在最上层当还原按钮，还原后回导航页
        (navSplitInFullscreen?.parent as? android.view.ViewGroup)?.removeView(navSplitInFullscreen)
        navSplitInFullscreen = null
        navPage.attachNavSplit()
        navPage.syncPipState(prefs.dualPip, false)
        // 还原后恢复内容留白（导航页里不能被 Dock 压住）
        navPage.applyPipContentInset(false)
        showPage(PAGE_NAV)
        refreshPip()
        // 尺寸从「全屏」变回「分屏」后，重新给承载应用发一次启动 ——
        // 否则应用会按全屏尺寸绘制（被放大裁切），或者地图图层直接黑掉。
        // （resize 稳定后重新加载）。
        navPage.pip.relaunchAfterResize()
    }

    /** 全屏时提到根容器上的玻璃条（还原时放回导航页）。 */
    private var navSplitInFullscreen: android.view.View? = null

    /** 当前是不是画中画全屏态（供 Activity 拦截返回键用）。 */
    fun isPipFullscreen(): Boolean = pipFullscreen

    /** 下一个页面（：取模循环）。 */
    private fun nextPage(from: Int): Int = PageGesture.wrapIndex(from, 1, PAGE_COUNT)

    /** 上一个页面（取模循环）。 */
    private fun prevPage(from: Int): Int = PageGesture.wrapIndex(from, -1, PAGE_COUNT)

    // --------------------------------------------------------------- 抽屉浮层

    /**
     * 打开应用抽屉浮层
     * 抽屉是盖在桌面之上的浮层，不是整页；打开时抑制画中画触摸。
     */
    private fun showDrawer() {
        if (::drawerPage.isInitialized && drawerPage.parent != null) return
        drawerPage.reload()
        // 第四十九轮：抽屉改成设计板 `s03-apps` 的**页面级卡片**
        // （left 44 / top 73 / 宽 1876 / 高 867），状态栏和 Dock 仍然可见 ——
        // 所以它插在**状态栏之前**，不能 bringToFront。
        val index = if (::statusBar.isInitialized) content.indexOfChild(statusBar) else content.childCount
        content.addView(drawerPage, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        Logx.event(LogTags.DRAWER, "打开应用抽屉")
        // 面板上滑 + 遮罩淡入（200ms / 180ms）。
        drawerPage.playOpen()
        // —— 抽屉打开时「应用」键高亮
        dock.appsOpen = true
    }

    private fun closeDrawer() {
        if (!::drawerPage.isInitialized) return
        if (drawerPage.parent == null) {
            dock.appsOpen = false
            return
        }
        Logx.event(LogTags.DRAWER, "关闭应用抽屉")
        // 面板下滑 56px + 淡出（240ms），结束后摘掉。
        drawerPage.playClose {
            content.removeView(drawerPage)
            // —— 抽屉关闭时「应用」键取消高亮
            dock.appsOpen = false
        }
    }

    /**
     * Dock 的「应用」键：**开着就关，关着就开**（第五十六轮）。
     *
     * 以前这个键只会「开」—— 抽屉已经开着时再点， 开头
     * 「已经在显示就直接返回」，于是点多少次都没反应，只能点抽屉以外的地方关。
     */
    private fun toggleDrawer() {
        if (drawerShowing()) {
            Logx.event(LogTags.DRAWER, "Dock 应用键：关闭抽屉")
            closeDrawer()
        } else {
            Logx.event(LogTags.DRAWER, "Dock 应用键：打开抽屉")
            showDrawer()
        }
    }

    /**
     * Dock 的 home 键：回**主页面**（第五十六轮）。
     *
     * 关键是要先把盖在主页面上的浮层收掉：
     * 抽屉开着、画中画全屏着的时候点 home，以前只切页面，
     * 浮层还盖在上面 —— 用户看到的就是「home 键不生效」。
     */
    private fun goHome() {
        if (drawerShowing()) closeDrawer()
        if (pipFullscreen) {
            exitPipFullscreen()
            return
        }
        showPage(PAGE_SCENE)
    }

    /**
     * 底部导航栏：有两套承载（06-F01/F02/F03）——
     * 普通模式直接放设计版 Dock（1864×104，距底 8）；
     * 增强导航栏模式把同一个 Dock 塞进贴底子窗的 pill（FitStrip 缩放），或换成槽位条（NavSlotBarView）。
     */
    private fun buildBottomBar() {
        slotsBar = null
        builtNavStyle = prefs.navStyle
        builtEnhancedNav = prefs.enhancedNav
        navSlots = NavSlots.load(prefs)
        if (prefs.enhancedNav) {
            val pill = EnhancedNavBarView(context).apply { palette = pal }
            // —— 宽屏（≥1800px）整层 152px
            // 里面是 124px 的 navBarPill，pill 距底 28px。
            val bandHeight = NavSlotBarView.resolveHeightPx(
                context.resources.displayMetrics.widthPixels,
                124,
            )
            val band = FrameLayout(context)
            band.addView(
                pill,
                LayoutParams(LayoutParams.MATCH_PARENT, 124, Gravity.BOTTOM).apply {
                    bottomMargin = 28
                    leftMargin = 28
                    rightMargin = 28
                },
            )
            content.addView(
                band,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, bandHeight, Gravity.BOTTOM),
            )
            if (prefs.navStyle == EnhancedNavBarView.MODE_SLOTS) {
                val bar = NavSlotBarView(context).apply {
                    palette = this@LauncherView.pal
                    slots = this@LauncherView.navSlots
                    // 槽位条形态下，歌词由**导航栏歌词槽**独揽
                    // ），此时首页中央大字歌词会让位（见
                    // [slotBarShowsLyric]—— 同一首歌不在屏上出现两处歌词。
                    showLyric = true
                    onSlot = { handleSlot(it) }
                    onTemp = { which, delta ->
                        val zone = if (which == "passenger") HvacIds.ZONE_PASSENGER else HvacIds.ZONE_DRIVER
                        if (delta == 0) showClimate() else VehicleController.stepTemp(zone, delta.toFloat())
                    }
                    // —— 应用列表槽走最近任务
                    onRecents = { showRecents() }
                    // —— 媒体槽三块独立热区
                    onMediaCover = { showPage(PAGE_NAV) }
                    onMediaTitle = { showPage(PAGE_NAV) }
                    onMediaPlay = { MusicController.get().playPause(); refresh() }
                }
                slotsBar = bar
                pill.mountSlots(bar)
            } else {
                pill.mountDock(dock)
            }
            bottomBar = pill
        } else {
            content.addView(
                dock,
                // 第五十轮对齐设计板 s01：Dock 1872 宽（左右各留 24），顶边 968。
                FrameLayout.LayoutParams(1872, 104, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = DOCK_BOTTOM_MARGIN
                },
            )
            bottomBar = dock
        }
    }

    /**
     * 当前是不是「导航栏槽位条 + 槽位里配了歌词槽」。
     *
     * 这种形态下歌词由**导航栏歌词槽**显示
     * 首页中央大字歌词要让位，
     * 否则同一首歌会在屏上出现两处歌词。
     *
     * 规则本身在 [NavSlots.lyricBelongsToNavBar]（纯逻辑，可 JVM 单测）。
     * 这里用已经加载好的槽位表（[navSlots]），不要在热路径上反复读盘 ——
     * `NavSlots.load` 读的是带副作用的（布局版本过期会把槽位重置回默认 15 槽）。
     */
    private fun slotBarShowsLyric(): Boolean = NavSlots.lyricBelongsToNavBar(
        enhancedNav = prefs.enhancedNav,
        navStyle = prefs.navStyle,
        slots = navSlots,
    )

    /**
     * 「最近任务」——增强导航栏的「应用列表槽走最近任务」。
     * 本应用没有独立的最近任务页，用系统最近任务兜底。
     */
    private fun showRecents() {
        Logx.event(LogTags.APP, "打开最近任务")
        val ok = runCatching {
            context.startActivity(
                android.content.Intent("android.intent.action.MAIN")
                    .addCategory("android.intent.category.HOME")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (!ok) toast("无法打开最近任务")
    }

    /** 槽位条点击：动作按 ACTION_* 语义分发。 */
    private fun handleSlot(slot: NavSlot) {
        Logx.event(LogTags.DOCK, "点击导航栏槽位", "type" to slot.type, "action" to slot.actionId, "label" to slot.label)
        when {
            slot.type == NavSlot.TYPE_MEDIA -> showPage(PAGE_NAV)
            slot.type == NavSlot.TYPE_LYRIC -> showPage(PAGE_NAV)
            slot.type == NavSlot.TYPE_DATETIME -> Unit // 纯显示
            slot.type == NavSlot.TYPE_APP -> {
                // 主画 / 副画 / 全屏
                when {
                    slot.isPip1Launch -> launchInPip(slot.pkg, first = true)
                    slot.isPip2Launch -> launchInPip(slot.pkg, first = false)
                    else -> {
                        val intent = context.packageManager.getLaunchIntentForPackage(slot.pkg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        } else {
                            toast("${slot.label} 未安装")
                        }
                    }
                }
            }
            else -> when (slot.actionId) {
                "back" -> onBack()
                "home" -> showPage(PAGE_SCENE)
                "tasks" -> showDrawer()
                "settings" -> showSettings()
                "split" -> {
                    val pkg = prefs.lastAppPkg
                    if (pkg.isBlank()) {
                        toast("先打开一个应用，再点分屏")
                        showDrawer()
                    } else {
                        prefs.lastAppPkg = pkg
                        val ok = com.cardesk.launcher.core.SystemOps.startInSplit(context, pkg)
                        toast(if (ok) "已请求分屏启动" else "分屏启动失败：应用不可用")
                    }
                }
                "camera_360" -> showCam()
                "media" -> showPage(PAGE_NAV)
                "lyric" -> showPage(PAGE_NAV)
                "hvac_power" -> VehicleController.togglePower()
                "hvac_ac" -> VehicleController.toggleAc()
                "hvac_auto" -> VehicleController.toggleAuto()
                "hvac_circ" -> VehicleController.toggleCirculation()
                "hvac_fan" -> showClimate()
                "hvac_defrost_front" -> VehicleController.toggleDefrostFront()
                "hvac_defrost_rear" -> VehicleController.toggleDefrostRear()
                "hvac_temp_driver", "hvac_temp_passenger" -> showClimate()
                else -> Unit
            }
        }
    }

    /** 打开槽位管理面板（对应 dialog_nav_slot_manage）。 */
    fun showNavSlots() {
        if (navSlotDialog != null) return
        val dialog = NavSlotDialog(context, pal, navSlots)
        dialog.onChange = { list ->
            navSlots = list.toMutableList()
            NavSlots.save(prefs, list)
            slotsBar?.slots = list
            // 槽位里加/删歌词槽会改变「歌词归导航栏还是归首页中央」的归属，需要重算。
            refresh()
        }
        dialog.onClose = {
            content.removeView(dialog)
            navSlotDialog = null
            if (prefs.enhancedNav && prefs.navStyle == EnhancedNavBarView.MODE_SLOTS) build()
        }
        navSlotDialog = dialog
        content.addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 解析当前配色。
     *
     * - `daynight_enabled` 默认**关闭** → 关闭时固定用日间配色；
     * - 打开后按 `daynight_mode` 选来源：0=跟随高德（默认）/ 1=跟随时间 / 2=跟随光感；
     * - 每个来源读不到时按优先级回落到下一个。
     */
    private fun resolvePalette(): Palette {
        val night = resolveNight()
        val base = if (night) Palettes.night else Palettes.day
        val accent = prefs.accentColor
        val themed = if (accent == 0) base else base.copy(
            accent = accent,
            accentSoft = base.withAlpha(accent, 0x40),
        )
        // 第五十八轮：卡片风格（透明度 / 颜色 / 圆角）叠在主题之上。
        return com.cardesk.launcher.core.CardStyle.apply(
            themed,
            prefs.cardColor,
            prefs.cardOpacity,
            prefs.cardRadius,
        )
    }

    private fun applyTheme() {
        val old = pal
        pal = resolvePalette().also { Skin.apply(it) }
        Logx.i(LogTags.THEME, "主题切换", "night" to pal.night, "wasNight" to old.night, "accent" to prefs.accentColor)
        build()
    }

    /**
     * 当前的夜间态
     *
     *
     * - `daynight_enabled` 默认**关闭** → 关闭时沿用「上次应用的夜间态」，
     *   从没应用过时按兜底值 **夜间**（`lastAppliedNightUi ?: true`）
     * - 打开后按 `daynight_mode` 选来源：0=跟随高德（默认）/ 1=跟随时间 / 2=跟随光感；
     * - 每个来源读不到时按优先级回落到下一个。
     */
    private fun resolveNight(): Boolean {
        val cal = Calendar.getInstance()
        val night = com.cardesk.launcher.core.DayNight.resolveNightUi(
            enabled = prefs.dayNightEnabled,
            mode = prefs.dayNightMode,
            minuteOfDay = com.cardesk.launcher.core.DayNight.minuteOfDay(
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
            ),
            dayStartMin = prefs.dayStartMin,
            nightStartMin = prefs.nightStartMin,
            amapNightUi = systemNightMode(),
            sensorNightUi = carDayModeNight(),
            lastAppliedNightUi = prefs.lastAppliedNight(),
        )
        val last = prefs.lastAppliedNight()
        if (last != night) {
            Logx.i(
                LogTags.THEME,
                "日夜态变化",
                "night" to night,
                "was" to last,
                "enabled" to prefs.dayNightEnabled,
                "mode" to prefs.dayNightMode,
                "amap" to systemNightMode(),
                "sensor" to carDayModeNight(),
            )
            prefs.setLastAppliedNight(night)
        }
        return night
    }

    /** 系统 UI 的夜间态（车机上由高德/系统昼夜信号驱动）。 */
    private fun systemNightMode(): Boolean? = try {
        val um = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        when (um?.nightMode) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
            android.content.res.Configuration.UI_MODE_NIGHT_NO -> false
            else -> null
        }
    } catch (e: Throwable) {
        null
    }

    /** 车机日间模式功能值。 */
    private fun carDayModeNight(): Boolean? = try {
        val gw = VehicleController.gateway
        val value = gw.readValue(
            com.cardesk.launcher.core.DayNightSource.FUNCTION_DAYMODE_SETTING,
            com.cardesk.launcher.vehicle.HvacIds.ZONE_GLOBAL,
        )
        value?.let { com.cardesk.launcher.core.DayNightSource.nightFromCarValue(it, systemNightMode()) }
    } catch (e: Throwable) {
        null
    }

    // --------------------------------------------------------------- 媒体来源

    /** 打开来源菜单（锚点是真实 View，如音乐页的来源行）。 */
    private fun openSourceMenu(anchor: View?) {
        if (anchor == null) {
            toast("来源菜单暂不可用")
            return
        }
        sourceMenu.toggle(anchor)
    }

    /** 打开来源菜单（锚点是设计坐标矩形，如自绘的场景页来源行）。 */
    private fun openSourceMenu(rect: android.graphics.RectF?) {
        if (rect == null) {
            toast("来源菜单暂不可用")
            return
        }
        sourceMenu.toggle(rect)
    }

    /**
     * 选中一个来源
     * 通用音乐只切换显示；其余来源若目标已安装则锁定会话并启动，否则给提示。
     */
    private fun applySource(sourceId: String) {
        val source = com.cardesk.launcher.core.media.MediaSources.byId(sourceId) ?: return
        when (val result = MusicController.get().selectSource(context, sourceId)) {
            is com.cardesk.launcher.core.media.MediaSources.Pick.Missing -> toast(result.message)
            else -> Unit
        }
        prefs.musicSource = sourceId
        refresh()
    }

    /**
     * 当前显示的来源文案。
     *
     * 规则：用户显式选过来源就以选择为准
     * **只有**停在「通用音乐」时才跟随当前会话自动变化。
     */
    private fun currentSourceLabel(track: com.cardesk.launcher.core.media.TrackInfo?): String {
        val selected = com.cardesk.launcher.core.media.MediaSources.byId(prefs.musicSource)
        if (selected != null && selected.id != com.cardesk.launcher.core.media.MediaSources.GENERIC_ID) {
            return selected.label
        }
        return com.cardesk.launcher.core.media.MediaSources.byId(track?.sourceId.orEmpty())?.label
            ?: com.cardesk.launcher.core.media.MediaSources.byId("generic")!!.label
    }

    // --------------------------------------------------------------- 页面切换

    private fun showPage(target: Int) {
        showPage(target, animate = true)
    }

    private fun showPage(target: Int, animate: Boolean) {
        Logx.i(LogTags.APP, "切页", "from" to page, "to" to target, "animate" to animate)
        closeClimate()
        if (sourceMenu.isOpen) sourceMenu.hide()
        val old = page
        page = target
        prefs.lastPage = target
        dots.index = target
        dock.activePage = target
        // 切页后自绘状态栏的显隐要跟着变（syncJunjunStatusBar：导航页隐藏）
        applyDockVisibility()

        val views = arrayOf<View>(scenePage, navPage)
        val gen = ++pageGen
        for (i in pageAnimators.indices) pageAnimators[i]?.cancel()

        // 画中画只挂在导航页的地图卡里（PIP 在 navCanvas 的 mapCard 里）
        refreshPip()

        // 浮窗只在桌面首页可用；切走时全部最小化（:965-977）
        floatLayer?.onPageChanged(target)

        val canAnimate = animate && old != target && views.all { it.width > 0 && it.height > 0 }
        if (!canAnimate) {
            views.forEachIndexed { i, v -> restPage(v, i == target) }
            return
        }

        // 向前翻时旧页上移、新页下移进入；向后翻反之
        val forward = target > old
        pageHost.bringChildToFront(views[target])
        slidePage(views[old], old, entering = false, translation = if (forward) -60f else 60f, gen = gen)
        slidePage(views[target], target, entering = true, translation = if (forward) 60f else -60f, gen = gen)
    }

    /** 无动画时把页面归位。 */
    private fun restPage(view: View, current: Boolean) {
        view.animate().cancel()
        view.visibility = if (current) View.VISIBLE else View.GONE
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /**
     * 单页进出动画
     * 旧页 alpha→0、translationY→±60、scale→0.98；新页反向；alpha 600ms、其余 700ms。
     */
    private fun slidePage(view: View, index: Int, entering: Boolean, translation: Float, gen: Int) {
        if (view.width > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
        val alphaFrom: Float
        val alphaTo: Float
        val transFrom: Float
        val transTo: Float
        val scaleFrom: Float
        val scaleTo: Float
        if (entering) {
            alphaFrom = 0f; alphaTo = 1f
            transFrom = translation; transTo = 0f
            scaleFrom = 0.98f; scaleTo = 1f
        } else {
            alphaFrom = view.alpha; alphaTo = 0f
            transFrom = view.translationY; transTo = translation
            scaleFrom = if (view.scaleX <= 0f) 1f else view.scaleX; scaleTo = 0.98f
        }
        view.visibility = View.VISIBLE
        view.alpha = alphaFrom
        view.translationY = transFrom
        view.scaleX = scaleFrom
        view.scaleY = scaleFrom

        val alphaAnim = ObjectAnimator.ofFloat(view, View.ALPHA, alphaFrom, alphaTo).apply { duration = 600L }
        val transAnim = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, transFrom, transTo).apply { duration = 700L }
        val scaleXAnim = ObjectAnimator.ofFloat(view, View.SCALE_X, scaleFrom, scaleTo).apply { duration = 700L }
        val scaleYAnim = ObjectAnimator.ofFloat(view, View.SCALE_Y, scaleFrom, scaleTo).apply { duration = 700L }

        val set = AnimatorSet().apply {
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            playTogether(alphaAnim, transAnim, scaleXAnim, scaleYAnim)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(a: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (pageAnimators[index] === a) pageAnimators[index] = null
                    // 动画期间又切了页：丢弃这次的结果，交给新一轮动画
                    if (cancelled || gen != pageGen) return
                    if (!entering && page != index) view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            })
        }
        pageAnimators[index] = set
        set.start()
    }

    // --------------------------------------------------------------- 浮层

    private fun showClimate() {
        if (climate != null) return
        Logx.event(LogTags.HVAC, "打开空调浮层")
        val page = ClimatePage(context, pal)
        page.onClose = { closeClimate() }
        climate = page
        // 第四十九轮：空调浮层改成**整屏浮层**（设计板 `s04-hvac`）——
        // 遮罩盖住状态栏和 Dock，面板是居中的 1640×830 卡片。
        // 所以它必须加在 content 的**最上层**（不再插到状态栏之前）。
        content.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 面板上滑 + 遮罩淡入。
        page.playOpen()
    }

    private fun closeClimate() {
        val page = climate ?: return
        climate = null
        // 面板下滑 56px + 淡出（240ms），结束后摘掉。
        page.playClose { content.removeView(page) }
    }

    private fun showHvac() {
        if (hvac != null) return
        Logx.event(LogTags.HVAC, "打开空调面板")
        val panel = HvacPanelView(context, pal)
        panel.bind(VehicleController.state, VehicleController.sourceLabel)
        panel.onDismiss = {
            content.removeView(panel)
            hvac = null
        }
        hvac = panel
        content.addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
 * 打开 360 环影。
 *
 * **不打开任何自有浮层**：360 的画面是车机厂商的 AVM/PAC 窗口盖在 launcher 之上
 * （本应用是 HOME，没有 Activity 跳转 / WindowManager 操作 /
 * 画中画调用，全 APK 也 grep 不到 Camera2 / SurfaceTexture。
 * 所以桌面这边只做两件事：
 *
 * - 「启用 360 环影增强」开着 → 打开 PAC，并按设置里的「按键视角」切视角
 *   （→ `openOrSwitch(keyView, true)`）
 * - 增强关着 → 只打开 PAC，不切视角（feature off 分支）。
 *
 * 注意那个开关的语义是「**增强**（按键与档位联动环影视角）」，
 * 不是 360 的总开关 —— 关掉它 360 依然能开，只是不再联动切视角。
 */
private fun showCam() {
    Logx.event(LogTags.CAM360, "打开 360 环影")
    val ok = VehicleController.set360(true)
    if (!ok) {
        // 写入失败时 VehicleController 内部已经 Toast + 记日志了，这里不重复提示
        return
    }
    if (!prefs.camera360Enabled) {
        Logx.i(LogTags.CAM360, "360 增强未启用：只打开环影，不切视角")
        return
    }
    val view = prefs.camera360KeyView
    // 只接受 0..9（-1 = 不设置）
    if (view < 0 || view > 9) {
        Logx.i(LogTags.CAM360, "按键视角未设置：不切视角", "view" to view)
        return
    }
    VehicleController.schedule360View(view, prefs.camera360DelayMs)
}

    private fun showOverlayDialog(dialog: View) {
        overlayDialog?.let { content.removeView(it) }
        overlayDialog = dialog
        content.addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun closeOverlayDialog(dialog: View) {
        content.removeView(dialog)
        if (overlayDialog === dialog) overlayDialog = null
    }

    // --------------------------------------------------------------- Dock 快捷应用（第五十四轮）

    /**
     * 刷新 Dock 最右边的**快捷应用槽位**。
     *
     * 配置存在 [Prefs.dockQuickApps]（逗号分隔的包名），这里把它还原成
     * 「包名 + 应用名 + 图标」再绑给 [DockBarView]。
     * 取不到图标 / 应用被卸载时**不崩**：画成空槽位。
     */
    private fun refreshDockQuickApps() {
        if (!::dock.isInitialized) return
        val pm = context.packageManager
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
        dock.bindQuickApps(apps)
    }

    /**
     * 点快捷应用槽位：配了应用就**启动**，空槽位直接去配置。
     */
    private fun handleQuickAppTap(index: Int, pkg: String) {
        if (pkg.isEmpty()) {
            showDockQuickApps(index)
            return
        }
        launchQuickApp(pkg)
    }

    /** 启动一个快捷应用。 */
    private fun launchQuickApp(pkg: String) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                toast("无法启动该应用")
                Logx.w(LogTags.DOCK, "快捷应用没有启动入口", "pkg" to pkg)
                return
            }
            context.startActivity(intent)
            Logx.event(LogTags.DOCK, "快捷应用启动", "pkg" to pkg)
        }.onFailure {
            Logx.w(LogTags.DOCK, "快捷应用启动失败", it, "pkg" to pkg)
            toast("无法启动该应用")
        }
    }

    /**
     * 打开 Dock 快捷应用**配置面板**（第五十四轮）。
     *
     * @param focusSlot 定位的槽位（-1 = 不定位）；从 Dock 长按进来时传槽位下标。
     */
    fun showDockQuickApps() = showDockQuickApps(-1)

    /** @param focusSlot 定位的槽位（-1 = 不定位）。 */
    fun showDockQuickApps(focusSlot: Int) {
        val config = com.cardesk.launcher.ui.dialogs.DockQuickAppsDialog(
            context,
            pal,
            com.cardesk.launcher.core.DockQuickApps.PrefsStore(prefs),
            focusSlot,
        )
        config.onRequestPicker = { index, current -> showQuickAppPicker(index, current, config) }
        config.onChanged = { list ->
            prefs.dockQuickApps = list
            refreshDockQuickApps()
        }
        config.onClose = { closeOverlayDialog(config) }
        showOverlayDialog(config)
    }

    /**
     * 弹应用选择器（单选）给某个槽位。
     *
     * 注意：这里**叠加**在配置面板之上（不能走 [showOverlayDialog]，
     * 那个函数会先把上一个弹窗摘掉。
     */
    private fun showQuickAppPicker(
        index: Int,
        current: String,
        config: com.cardesk.launcher.ui.dialogs.DockQuickAppsDialog,
    ) {
        val picker = com.cardesk.launcher.ui.dialogs.AppPickerDialog(
            context,
            pal,
            multiSelect = false,
            preselected = if (current.isEmpty()) emptySet() else setOf(current),
            title = "选择快捷应用",
            hintText = "点一下即把这个应用放进 Dock 的第 ${index + 1} 个快捷槽位",
        )
        picker.onPicked = { set ->
            val pkg = set.firstOrNull().orEmpty()
            config.applyPicked(index, pkg)
            // 单选完就收起来，直接回到配置面板
            closeQuickAppPicker(picker)
        }
        picker.onClose = { closeQuickAppPicker(picker) }
        quickAppPicker = picker
        content.addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 关掉叠在配置面板之上的应用选择器（第五十四轮）。
     *
     * 这个选择器**不走** [showOverlayDialog]（那个函数会摘掉上一个弹窗），
     * 所以要单独记一份，返回键也优先收它。
     */
    private fun closeQuickAppPicker(picker: View) {
        content.removeView(picker)
        if (quickAppPicker === picker) quickAppPicker = null
    }

    /** 主题色选择（dialog_color_picker 等价物）。 */
    fun showAccentPicker() {        showOverlayDialog(
            com.cardesk.launcher.ui.dialogs.ColorPickerDialog(
                context, pal, if (prefs.accentColor == 0) pal.accent else prefs.accentColor,
            ).apply {
                onPicked = { color ->
                    prefs.accentColor = color
                    closeOverlayDialog(this)
                    applyTheme()
                }
                onClose = { closeOverlayDialog(this) }
            },
        )
    }

    /**
     * 打开一个应用浮窗。
     *
     * @return false 表示浮窗没打开（当前不在桌面首页 / 浮窗层未创建）；
     *   里这两种情况都是静默的，第三个浮窗会关掉最老的腾位
     *   所以**不存在**「已达上限」这个返回语义。
     */
    /**
     * 打开**媒体浮窗**（设计板 `s13-float` 的 460×250 小播放器）。
     *
     * 入口：Dock 媒体卡**长按**。内容取当前媒体会话的曲目 / 歌手 / 进度。
     */
    fun openMediaFloat(): Boolean {
        val layer = floatLayer ?: run {
            Logx.w(LogTags.FLOAT, "媒体浮窗打开失败：浮窗层未创建")
            return false
        }
        val track = mediaTrack
        val box = layer.openMediaFloat(
            track = track?.title?.ifBlank { "暂无播放" } ?: "暂无播放",
            artist = track?.artist.orEmpty(),
            positionMs = track?.positionMs ?: 0L,
            durationMs = track?.durationMs ?: 0L,
        ) ?: return false
        Logx.event(LogTags.FLOAT, "打开媒体浮窗", "track" to (track?.title ?: ""))
        return box != null
    }

    fun showFloat(pkg: String, name: String? = null): Boolean {
        val layer = floatLayer ?: run {
            Logx.w(LogTags.FLOAT, "浮窗打开失败：浮窗层未创建", "pkg" to pkg)
            return false
        }
        return layer.open(pkg, name) != null
    }

    /**
     * 按槽位的 launchMode 启动画中画（「主画 / 副画」）。
     *
     *
     * - 副画：先写 `pip2_app`，**双画没开就直接返回**（不显示、不切页）；
     * - 主画：写 `pip_app` 并切换承载的应用。
     */
    private fun launchInPip(pkg: String, first: Boolean) {
        if (pkg.isBlank()) {
            toast("应用包名为空")
            return
        }
        if (!first) {
            // 副画（MainActivity:2021-2026）
            // 同一个应用不能同时挂到两个 VirtualDisplay
            // 副画再 new 一个承载面时，应用会被从主画拉走 → 主画黑屏。
            if (pkg == prefs.pipApp) {
                toast("双画中画请选择不同应用")
                Logx.w(
                    LogTags.NAV_UI,
                    "副画槽位启动被拒：与主画相同",
                    "pkg" to pkg,
                )
                return
            }
            prefs.pip2App = pkg
            Logx.event(LogTags.NAV_UI, "画中画槽位启动（副画）", "pkg" to pkg, "dual" to prefs.dualPip)
            if (!prefs.dualPip) {
                // 双画没开时副画槽位只存包名，不切页、不显示
                toast("双画中画未开启，副画已记住该应用")
                return
            }
            showPage(PAGE_NAV)
            refreshPip()
            return
        }
        // 主画
        prefs.pipApp = pkg
        Logx.event(LogTags.NAV_UI, "画中画槽位启动（主画）", "pkg" to pkg)
        showPage(PAGE_NAV)
        refreshPip()
    }

    /**
     * 打开画中画应用选择器。
     *
     * @param secondary true = 选**副画**的应用（`pip2_app`），false = 主画（`pip_app`）。
     */
    private fun showPipPicker(secondary: Boolean) {
        val current = if (secondary) prefs.pip2App else prefs.pipApp
        val other = if (secondary) prefs.pipApp else prefs.pip2App
        val pre = if (current.isBlank()) emptySet() else setOf(current)
        val dialog = com.cardesk.launcher.ui.dialogs.AppPickerDialog(
            context, pal, multiSelect = false, preselected = pre, panelHeight = 700,
        )
        dialog.onPicked = { set ->
            val picked = set.firstOrNull().orEmpty()
            if (picked.isNotBlank() && prefs.dualPip && picked == other) {
                // —— 双画中画请选择不同应用
                toast("双画中画请选择不同应用")
                Logx.w(
                    LogTags.SETTING,
                    "画中画应用选择被拒：与另一路相同",
                    "secondary" to secondary, "pkg" to picked, "other" to other,
                )
            } else {
                if (secondary) prefs.pip2App = picked else prefs.pipApp = picked
                Logx.event(
                    LogTags.SETTING,
                    "选择画中画应用",
                    "secondary" to secondary, "pkg" to picked,
                )
                refreshPip()
            }
        }
        dialog.onClose = { closeOverlayDialog(dialog) }
        showOverlayDialog(dialog)
    }

    /** Dock 生效应用选择（`MainActivity.openDesignDockAppPicker`）。 */
    fun showDockApps() {
        val dialog = com.cardesk.launcher.ui.dialogs.AppPickerDialog(
            context, pal,
            multiSelect = true,
            preselected = prefs.hideDockApps,
            title = "指定隐藏原车 Dock 的应用",
        )
        // 勾选即生效，没有确认按钮
        dialog.onChanged = { set ->
            prefs.hideDockApps = set
            applyOemPolicy()
        }
        dialog.onClose = { closeOverlayDialog(dialog) }
        showOverlayDialog(dialog)
    }

    /**
     * 应用原车 Dock / 状态栏的隐藏策略。
     *
     * - `policy_control` 写 `immersive.navigation=` / `immersive.status=` 两个策略；
     * - 「下移 Dock」用 overscan 把底部裁掉 50px；
     * - 隐藏状态栏时反射调 `StatusBarManager.disable`。
     */
    private fun applyOemPolicy() {
        // 第五十六轮：把自己也放进 `immersive.navigation` 名单。
        //
        // `SYSTEM_UI_FLAG_HIDE_NAVIGATION` 只是把系统导航栏「画掉」，
        // 它的窗口还在、**照常吃底部触摸** —— 我们的 Dock 正好压在它上面，
        // 于是点 Dock 的按钮会顺带把系统自带 Dock 唤出来。
        //
        // `immersive.navigation` 是把导航栏**从窗口布局里摘掉**：
        // 不再收触摸，只有「从底部向上滑」的系统手势能临时把它叫出来。
        val immersivePackages = if (prefs.oemNavImmersive) listOf(context.packageName) else emptyList()
        val value = com.cardesk.launcher.core.OemDockPolicy.buildPolicyValue(
            hideDock = prefs.hideDock,
            hideStatus = prefs.hideStatus,
            hideDockPackages = prefs.hideDockApps,
            hideStatusPackages = prefs.hideStatusApps,
            immersivePackages = immersivePackages,
        )
        Logx.i(
            LogTags.SYS,
            "应用原车 Dock/状态栏策略",
            "policy" to value,
            "hideDock" to prefs.hideDock,
            "hideStatus" to prefs.hideStatus,
            "oemNavImmersive" to prefs.oemNavImmersive,
        )
        val applied = com.cardesk.launcher.core.SystemOps.applyPolicyControl(context, value)
        if (!applied) {
            Logx.w(LogTags.SYS, "policy_control 写入失败（已用 overscan 兜底）", "policy" to value)
            // 没有系统权限时至少把状态栏开关用 overscan 兜住，避免桌面被原车栏压住
            if (prefs.hideDock) com.cardesk.launcher.core.SystemOps.setOverscan(context, 0, 0, 0, 0)
        }
        val overscan = if (prefs.lowerDock && !prefs.hideDock) -50 else 0
        com.cardesk.launcher.core.SystemOps.setOverscan(context, 0, 0, 0, overscan)
        if (prefs.hideStatus) {
            com.cardesk.launcher.core.SystemOps.setStatusBarDisabled(context, 0x00000008 or 0x00000004)
        }
    }

    /** 按键映射（dialog_custom_keys 等价物）。 */
    fun showKeys() {
        val dialog = com.cardesk.launcher.ui.dialogs.KeysDialog(context, pal, prefs)
        dialog.onChanged = { refresh() }
        dialog.onClose = { closeOverlayDialog(dialog) }
        showOverlayDialog(dialog)
    }

    /** 新建计划任务（dialog_task_editor 等价物）。 */
    fun newTask() = showTaskEditor(null)

    /** 计划任务列表（dialog_task_list 等价物）。 */
    fun showTasks() {
        if (taskList != null) return
        val dialog = com.cardesk.launcher.ui.dialogs.TaskListDialog(context, pal, prefs)
        dialog.onEdit = { task -> showTaskEditor(task) }
        dialog.onClose = {
            content.removeView(dialog)
            taskList = null
        }
        taskList = dialog
        content.addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showTaskEditor(task: com.cardesk.launcher.core.ScheduledTask?) {
        if (taskEditor != null) return
        val dialog = com.cardesk.launcher.ui.dialogs.TaskEditorDialog(context, pal, prefs, task)
        dialog.onSaved = {
            content.removeView(dialog)
            taskEditor = null
            taskList?.reload()
        }
        dialog.onClose = {
            content.removeView(dialog)
            taskEditor = null
        }
        taskEditor = dialog
        content.addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showSettings() {
        if (settings != null) return
        Logx.event(LogTags.SETTING, "打开设置页")
        val overlay = SettingsOverlay(context, prefs, pal)
        overlay.onClose = { closeSettings() }
        overlay.onApply = { onSettingsApplied() }
        overlay.onEditNavSlots = {
            closeSettings()
            showNavSlots()
        }
        overlay.onVehicleSource = {
            VehicleController.reconnect(context.applicationContext, prefs)
            refresh()
        }
        overlay.onShowTasks = {
            closeSettings()
            showTasks()
        }
        overlay.onShowLayoutDialog = {
            val dialog = com.cardesk.launcher.ui.dialogs.DesktopLayoutDialog(context, pal, prefs)
            dialog.onApplied = { onSettingsApplied() }
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        overlay.onShowUpdate = {
            val dialog = com.cardesk.launcher.ui.dialogs.UpdateDialog(context, pal)
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        overlay.onShowRestart = {
            val dialog = com.cardesk.launcher.ui.dialogs.RestartDialog(context, pal)
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        overlay.onShowAccent = { showAccentPicker() }
        overlay.onShowKeys = {
            val dialog = com.cardesk.launcher.ui.dialogs.KeysDialog(context, pal, prefs)
            dialog.onChanged = { refresh() }
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        overlay.onShowDockApps = {
            val dialog = com.cardesk.launcher.ui.dialogs.AppPickerDialog(
                context, pal,
                multiSelect = true,
                preselected = prefs.hideDockApps,
                title = "指定隐藏原车 Dock 的应用",
            )
            dialog.onChanged = { set ->
                prefs.hideDockApps = set
                applyOemPolicy()
            }
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        overlay.onShowStatusApps = {
            val dialog = com.cardesk.launcher.ui.dialogs.AppPickerDialog(
                context, pal,
                multiSelect = true,
                preselected = prefs.hideStatusApps,
                title = "指定隐藏状态栏的应用",
            )
            dialog.onChanged = { set ->
                prefs.hideStatusApps = set
                applyOemPolicy()
            }
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        // Dock 快捷应用配置（第五十四轮）：与「长按 Dock 槽位」进同一个面板。
        overlay.onShowDockQuickApps = { showDockQuickApps() }
        // 画中画应用选择器
        // 主画 / 副画各一个选择器；双画已开时**不能选同一个应用**。
        overlay.onShowPipPicker = { secondary -> showPipPicker(secondary) }
        // 壁纸（第五十七轮）：设置页里换壁纸 → 桌面立刻生效。
        overlay.onWallpaperChange = { index -> applyWallpaper(index) }
        // 卡片风格（第五十八轮）：透明度 / 颜色 / 圆角 → 桌面重算 token 并刷新。
        overlay.onCardStyleChange = { applyCardStyle() }
        overlay.onShowCardColor = { showCardColorPicker() }
        settings = overlay
        content.addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 面板上滑 + 遮罩淡入（200ms / 180ms）。
        overlay.playOpen()
    }

    private fun closeSettings() {
        val overlay = settings ?: return
        settings = null
        // 面板下滑 56px + 淡出（240ms），结束后摘掉这层。
        overlay.playClose { content.removeView(overlay) }
    }

    /**
     * 换壁纸（第五十七轮）。
     *
     * 落盘键 `design_wallpaper_index`
     * 画面立刻更新（不等重建），并弹一下壁纸提示条（1.6s 自动隐藏）。
     *
     * 入口有两个：场景页提示条上的圆点、设置页「壁纸」页里的缩略图。
     */
    private fun applyWallpaper(index: Int) {
        val next = com.cardesk.launcher.core.Wallpapers.normalize(index)
        prefs.wallpaper = next
        background.wallpaper = next
        if (::scenePage.isInitialized) {
            scenePage.invalidate()
            scenePage.showWallpaperHint()
        }
    }

    /**
     * 应用卡片风格（第五十八轮）：透明度 / 颜色 / 圆角。
     *
     * 走的是 **token 层**，所以不用整层重建：
     * - 首页卡片是**每帧画**的（`ScenePage.card()` 读 `Skin.surface`）→ 立刻变；
     * - 圆角是 `Shape.CARD` 这类**可变 token**，绘制时读 → 立刻变；
     * - 只有「构建时设了背景」的那几张卡（导航页合并卡）要单独刷一下。
     */
    fun applyCardStyle() {
        com.cardesk.launcher.core.CardStyle.applyRadius(prefs.cardRadius)
        pal = resolvePalette()
        Skin.apply(pal)
        if (::navPage.isInitialized) navPage.applyCardStyle()
        refresh()
        invalidate()
        Logx.i(
            LogTags.SETTING,
            "卡片风格已应用",
            "opacity" to prefs.cardOpacity,
            "color" to String.format("#%06X", prefs.cardColor and 0xFFFFFF),
            "radius" to prefs.cardRadius,
        )
    }

    /**
     * 卡片颜色选择器（第五十八轮）。
     *
     * 复用现成的 [com.cardesk.launcher.ui.dialogs.ColorPickerDialog]，
     * 「恢复默认」= **跟随主题**（写 0）。
     */
    fun showCardColorPicker() {
        showOverlayDialog(
            com.cardesk.launcher.ui.dialogs.ColorPickerDialog(
                context,
                pal,
                if (prefs.cardColor == 0) pal.surface else prefs.cardColor,
                title = "卡片颜色",
                defaultColor = 0,
            ).apply {
                onPicked = { color ->
                    prefs.cardColor = color
                    closeOverlayDialog(this)
                    applyCardStyle()
                }
                onClose = { closeOverlayDialog(this) }
            },
        )
    }

    private fun onSettingsApplied() {
        Logx.i(LogTags.SETTING, "设置已应用")
        // 底栏形态变了要整层重建（增强导航栏开/关、设计 Dock ↔ 槽位条）。
        // 光改 visibility 换不过来：pill 和 Dock 是两套不同的 View 树。
        // build() 会把 content 整个重建，设置层也跟着被摘掉 ——
        // 和切日夜主题（applyTheme → build）是同一条路径。
        if (builtEnhancedNav != prefs.enhancedNav || builtNavStyle != prefs.navStyle) {
            Logx.i(
                LogTags.DOCK, "底栏形态变化，重建",
                "enhancedNav" to prefs.enhancedNav, "style" to prefs.navStyle,
            )
            build()
            return
        }
        background.wallpaper = prefs.wallpaper
        applyDockVisibility()
        applyOemPolicy()
        // 双画 / 全屏状态要同步给导航页
        if (::navPage.isInitialized) {
            navPage.syncPipState(prefs.dualPip, pipFullscreen)
            navPage.secondaryPackage = prefs.pip2App.ifBlank { prefs.pipApp }
        }
        refreshPip()
        refresh()
        if (resolvePalette().night != pal.night) applyTheme()
    }

    private fun applyDockVisibility() {
        if (!::dock.isInitialized) return
        // 第五十六轮：这里用的是**桌面自己**的隐藏开关（`hideHomeDock`），
        // 不再是「隐藏原车 Dock」那个 —— 之前两者共用一个键，
        // 结果勾「隐藏原车 Dock」消失的是桌面自己的 Dock。
        val hide = prefs.hideHomeDock
        Logx.d(
            LogTags.DOCK,
            "Dock 可见性",
            "hideHomeDock" to hide,
            "hideOemDock" to prefs.hideDock,
            "lowerDock" to prefs.lowerDock,
            "page" to page,
        )
        if (prefs.enhancedNav) {
            bottomBar.visibility = if (hide) View.GONE else View.VISIBLE
        } else {
            dock.visibility = if (hide) View.GONE else View.VISIBLE
            val lp = dock.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.bottomMargin = if (prefs.lowerDock) 0 else DOCK_BOTTOM_MARGIN
                dock.layoutParams = lp
            }
        }
        // 自绘状态栏的显隐：——
        // 「隐藏君君状态栏」打开时，**只在导航页（page==1）隐藏**，第一页仍显示
        // （第三十二轮起桌面只有两页，没有第三页了）。
        // （原车状态栏的隐藏走 `prefs.hideStatus` → policy_control，不影响这一条。）
        // 第三十七轮：「隐藏君君状态栏」这个开关的**默认值改成 false**（见 `Prefs.hideJunjunStatus`），
        // 所以现在两页都显示状态栏 —— 用户要求「桌面第二页也需要显示状态栏」。
        val hideBar = page == PAGE_NAV && prefs.hideJunjunStatus
        statusBar.visibility = if (hideBar) View.GONE else View.VISIBLE
        // 第八十二轮：翻页圆点搬进了**状态栏那一行**的中间，
        // 所以状态栏被隐藏时圆点也要一起隐藏 —— 否则它会孤零零浮在屏幕顶部。
        if (::dots.isInitialized) {
            dots.visibility = statusBar.visibility
        }
        // —— 顶部让位（第四十四轮：紧凑）——
        //
        // 是「导航页整体下移 110px 给状态栏让位」
        // （用在 的 `topMargin`）。
        //
        // 第四十三轮改成了「导航页顶到屏幕顶 + 状态栏透明浮层」，
        // 用户反馈「内容不要顶到屏幕顶」，第四十四轮选定**紧凑**形态：
        // 导航页整体下移到**状态栏那一行的底部**（`ROW_TOP + ROW_H` = 17 + 43 = 60），
        // 状态栏正好贴着内容的上沿 —— 既不顶到屏幕顶，中间也没有空白带。
        //
        // 状态栏**隐藏**时回到 8（贴顶，完全沉浸，与 一致）。
        //
        // 第四十九轮按设计板 `s02-nav` 重排：导航页内容是一张 **left 44 / top 73 /
        // 宽 1832** 的卡片（底边贴 Dock 顶下沿 940，距 Dock 顶 28）。
        //
        // 第五十二轮：状态栏缩到 2/3，内容基线 110 → 73，卡片高度跟着长高。
        val navTop = if (hideBar) {
            com.cardesk.launcher.ui.pages.NavPage.NAV_CONTENT_TOP_NO_STATUS
        } else {
            com.cardesk.launcher.ui.pages.NavPage.NAV_CONTENT_TOP
        }
        // 高度跟着顶部一起算：底部对齐 Dock 上沿（1080 − 8 − 104 = 968）。
        val navHeight = 968 - navTop
        (navPage.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            if (lp.topMargin != navTop || lp.height != navHeight) {
                lp.topMargin = navTop
                lp.height = navHeight
                navPage.layoutParams = lp
            }
        }
        // 排障场景：状态栏为什么没了 / 内容为什么让了那么多 —— 一眼看出来。
        Logx.d(
            LogTags.APP, "状态栏显隐",
            "page" to page, "hideJunjunStatus" to prefs.hideJunjunStatus, "visible" to !hideBar,
            "navContentTop" to navTop,
        )
    }

    // --------------------------------------------------------------- 刷新

    /**
     * 车身状态变化后的**立即**刷新（带节流）。
     *
     * 车端的信号回调可能连着来好几条（一次开门会带多个区域的变化），
     * 所以这里限流：最多 [BODY_REFRESH_MIN_INTERVAL_MS] 毫秒刷一次。
     */
    private fun refreshBodyState() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBodyRefreshAt < BODY_REFRESH_MIN_INTERVAL_MS) return
        lastBodyRefreshAt = now
        Logx.d(LogTags.SIGNAL, "车身状态变化，立即刷新")
        refresh()
    }

    private fun refresh() {
        if (!::statusBar.isInitialized) return
        if (resolvePalette().night != pal.night) {
            Logx.i(LogTags.THEME, "日夜状态变化，重新应用主题")
            applyTheme()
            return
        }
        val now = Date()
        val info = currentVehicleInfo()
        val state = VehicleController.state
        // 时钟： `H:mm`（单小时不补零）
        statusBar.setClock(now)
        // 状态胶囊显示的是**驾驶模式**
        statusBar.assistText = "驾驶模式 · ${info.driveModeLabel ?: com.cardesk.launcher.vehicle.DriveMode.UNKNOWN_LABEL}"
        statusBar.rangeText = if (info.rangeKm >= 0) "${info.rangeKm} km" else ""
        // 燃油百分比没有车端数据源，交给状态栏自己显示默认值 `--%`

        val track = mediaTrack
        playing = track?.playing ?: false
        val sourceLabel = currentSourceLabel(track)
        scenePage.mediaTitle = track?.title?.ifBlank { "暂无播放" } ?: "暂无播放"
        // 艺术家行默认值是空串，不要塞自造占位文案
        scenePage.mediaArtist = track?.artist?.ifBlank { "" } ?: ""
        scenePage.mediaSource = sourceLabel
        scenePage.mediaArtwork = track?.artwork
        // 场景页底部那张音乐卡的 ▶/⏸ 按钮读的是 scenePage.mediaPlaying
        // （场景页音乐卡）。
        // 之前这里漏了赋值 —— 按钮永远停在 false（显示 ▶），暂停/播放都不变。
        scenePage.mediaPlaying = playing
        // 进度环
        // fraction = 播放位置 / 总时长，总时长不可用时画 0（只留轨道）。
        scenePage.mediaPositionMs = track?.positionMs ?: 0L
        scenePage.mediaDurationMs = track?.durationMs ?: 0L
        // 媒体浮窗（设计板 s13-float）开着的时候，卡片内容跟着会话走。
        floatLayer?.updateMediaFloat(
            track = scenePage.mediaTitle,
            artist = scenePage.mediaArtist,
            positionMs = scenePage.mediaPositionMs,
            durationMs = scenePage.mediaDurationMs,
        )
        // 天气卡
        scenePage.weatherCity = weatherCity
        scenePage.weatherSky = weatherSky
        scenePage.weatherTemp = weatherTemp
        // 状态栏中置的「位置 + 天气」（第四轮新增）：
        // 城市优先用天气链路的实时值，没有就回落到定位链路落盘的城市（`weather_fix_city`）；
        // 温度没有天气数据时用车外温度兜底（与场景页天气卡同一口径）。
        statusBar.setLocationWeather(
            location = weatherCity.ifBlank { prefs.weatherCity },
            sky = weatherSky,
            temp = weatherTemp ?: info.ambientTemp.takeIf { !it.isNaN() }?.toInt(),
        )
        // 燃油：语义是「无数据时显示 --%」（/ ShellStatusBar）。
        // 现在接上了 AdaptAPI 的 ISensor 油量通道，
        // 有真值就显示真值，没有才回落到 `--%`。
        scenePage.fuelPercent = info.fuelPercent
        statusBar.fuelPercent = info.fuelPercent ?: ShellStatusBar.FUEL_UNKNOWN_VALUE
        scenePage.bind(info, state, prefs.wallpaper)
        // 引导卡每秒重算一次 —— 红绿灯读秒要按秒往下走，
        // 信号超时（超过 TTL 没收到新帧）也要在这一轮里消失。
        if (navGuideRaw != null) applyNavGuide()
        // 车模编号跟着设置走（第三十三轮：**接到第一页胎压卡的自绘图模**上）。
        scenePage.carModel = prefs.carModel
        navPage.carModel = prefs.carModel
        // 首页中央大字歌词 vs 导航栏歌词槽：**同一首歌只显示一处**。
        // 槽位条形态且槽位里确实配了歌词槽 → 歌词归导航栏，中央大字让位。
        scenePage.showCenterLyric = !slotBarShowsLyric()
        navPage.sourceLabel = sourceLabel
        navPage.musicState = if (playing) "播放中" else "已暂停"
        // 无会话时歌名显示「暂无播放」，歌手保持空串（renderNoPlaybackUi:1999）
        navPage.trackTitle = track?.title?.ifBlank { "暂无播放" } ?: "暂无播放"
        navPage.trackArtist = track?.artist?.ifBlank { "" } ?: ""
        // 导航页组合卡的专辑图
        navPage.artwork = track?.artwork
        navPage.playing = playing
        navPage.positionText = formatTime(track?.positionMs ?: 0L)
        navPage.durationText = formatTime(track?.durationMs ?: 0L)
        dock.bind(state)
        slotsBar?.let {
            it.state = state
            it.palette = pal
            it.mediaPlaying = playing
            // 媒体槽标题 / 歌手（：空标题显示「暂无播放」
            // 歌手为空时是空串
            it.mediaTitle = track?.title?.ifBlank { "暂无播放" } ?: "暂无播放"
            it.mediaArtist = track?.artist?.ifBlank { "" } ?: ""
            // 歌词槽：当前行 / 下一行
            // 槽位条形态下歌词槽是要显示的
            // 此时首页中央大字歌词让位（见 [slotBarShowsLyric]）——
            // 同一首歌不在屏上出现两处歌词（场景页本来就是没有歌词的）。
            val timeline = mediaLyric
            val idx = mediaLyricIndex
            it.lyricCurrent = if (timeline != null && idx >= 0) timeline.lineAt(idx) else mediaLyricFallback
            it.lyricNext = if (timeline != null && idx >= 0) timeline.lineAt(idx + 1) else ""
            // 歌词槽的当前行也带擦除高亮。
            it.lyricWipe = if (timeline != null && idx >= 0) mediaLyricWipe else 0f
            // 媒体槽封面
            it.mediaArtwork = track?.artwork
            it.showLyric = true
            it.invalidate()
        }
        val progress = if ((track?.durationMs ?: 0L) > 0) {
            (track!!.positionMs.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        navPage.progress = progress
        dock.bindMedia(
            track?.title?.ifBlank { "暂无播放" } ?: "暂无播放",
            track?.artist?.ifBlank { "" } ?: "",
            playing,
            progress,
            track?.artwork,
        )
        floats.palette = pal
        hvac?.bind(state, VehicleController.sourceLabel)
        climate?.refresh()
        applyDockVisibility()
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    /** 毫秒 → "m:ss"（格式）。 */
    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val total = ms / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    // --------------------------------------------------------------- 尺寸 / 手势 / 返回

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = Design.scaleFor(w, h)
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale
        content.translationX = (w - Design.W * scale) / 2f
        content.translationY = (h - Design.H * scale) / 2f
    }

    /** 抽屉浮层是否正开着。 */
    private fun drawerShowing(): Boolean = ::drawerPage.isInitialized && drawerPage.parent != null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 右缘竖滑
                // 命中区是最右 120px，滑动超过 40px 就接管
                edgeTracking = designX(ev.x) >= Design.W - EDGE_WIDTH
                edgeIntercepted = false
                edgeDownY = designY(ev.y)
                threeFinger = false
                // 第三十七轮：单指横滑的起点
                singleTracking = true
                singleIntercepted = false
                singleDownX = designX(ev.x)
                singleDownY = designY(ev.y)
                singleDx = 0f
                singleDy = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 三指横滑换页（与
                // ；第三十二轮把方向改成**横向**
                //
                // 第八十二轮：**多指一律作废单指判定**。
                // 单指横滑的位移是按「第 1 根手指的落点」算的，
                // 一旦变成双指/三指，位移会跳变，容易误判成一次翻页。
                singleTracking = false
                if (ev.pointerCount >= 3) {
                    threeFinger = true
                    threeFingerX = midX(ev)
                    threeFingerMid = threeFingerX
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (threeFinger) return true
                // 右缘竖滑
                if (edgeTracking && abs(designY(ev.y) - edgeDownY) > EDGE_SLOP) {
                    edgeIntercepted = true
                    singleTracking = false
                    return true
                }
                // 第三十七轮：**单指横滑**换页（主手势）。
                //
                // 判定放在 `onInterceptTouchEvent` 里：位移超过接管阈值后才把事件抢回来，
                // 子视图（底部卡片拖拽、画中画点位、Dock 媒体卡滑动）在此之前都还能正常响应。
                //
                // —— 但这条**只是「抢事件」**，不是翻页的最终判定（见 [onTouchEvent]）：
                // Android 只有在「有子视图消费了 ACTION_DOWN」时才会继续回调
                // `onInterceptTouchEvent` 处理后续 MOVE；没有任何子视图消费按下时
                // （例如导航页右侧音乐卡的空白自绘区）这一层**根本收不到 MOVE**，
                // 翻页就永远不触发。第八十二轮把最终判定挪到 [onTouchEvent]，
                // 两条路径（子视图消费 / 没人消费）就都能翻页了。
                if (singleTracking && ev.pointerCount == 1) {
                    singleDx = designX(ev.x) - singleDownX
                    singleDy = designY(ev.y) - singleDownY
                    val horizontal = abs(singleDx) > SINGLE_FINGER_SLOP &&
                        abs(singleDx) > abs(singleDy) * PageGesture.SINGLE_FINGER_AXIS_RATIO
                    if (horizontal) {
                        singleIntercepted = true
                        return true
                    }
                }
            }
            else -> Unit
        }
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * 根视图自己的触摸处理：单指横滑翻页的**最终判定**在这里。
     *
     * **第八十二轮改这里的原因**（模拟器实测出来的）：
     * 以前翻页判定挂在 `singleIntercepted` 上，而 `singleIntercepted`
     * 只在 [onInterceptTouchEvent] 里才可能被置位。可 Android 只有在
     * **有子视图消费了 ACTION_DOWN** 时才会继续把 MOVE 派发到
     * `onInterceptTouchEvent`；一旦没人消费按下（导航页右侧音乐卡那种
     * 纯自绘的空白区就是），这一层就再也收不到 MOVE，
     * `singleIntercepted` 永远是 false → 那一块区域怎么滑都不翻页。
     *
     * 现象就是「翻到某一页就卡住了、不能循环」——
     * 其实是**某些起点位置整片失灵**，用户很容易把它当成「到头了」。
     *
     * 现在两条路径都收敛到本方法：
     * - **子视图消费了按下**（场景页、地图卡…）：MOVE 时被 [onInterceptTouchEvent]
     *   抢走，子视图收到 CANCEL，本方法收到后续 MOVE/UP → 正常翻页；
     * - **没人消费按下**（音乐卡空白区…）：本方法从 DOWN 一路收到 UP，
     *   自己记录位移并判定 → 也能翻页。
     *
     * 两种路径**不会重复翻页**：本方法是唯一的判定点，
     * [onInterceptTouchEvent] 只负责「抢事件」，不直接翻页。
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 能收到 DOWN，说明**没有任何子视图消费了这次按下**
                // （否则事件根本到不了根视图），这次横滑由自己从头到尾处理。
                singleTracking = true
                singleIntercepted = false
                singleDownX = designX(ev.x)
                singleDownY = designY(ev.y)
                singleDx = 0f
                singleDy = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多指一律作废单指判定（同 [onInterceptTouchEvent]）。
                singleTracking = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 三指横滑：持续记录三指中位点的当前位置。
                // ACTION_UP 时只有最后一根手指还在事件里，直接用 UP 的坐标算位移会少算，
                // 所以位移以「最后一次 MOVE 的中位点」为准。
                if (threeFinger) threeFingerMid = midX(ev)
                // 单指横滑：同理由「最后一次 MOVE」记录位移。
                if (singleTracking) {
                    singleDx = designX(ev.x) - singleDownX
                    singleDy = designY(ev.y) - singleDownY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 第三十七轮：单指横滑换页收尾
                if (singleTracking) {
                    singleTracking = false
                    val step = PageGesture.singleFingerStep(singleDx, singleDy, SINGLE_FINGER_PX)
                    if (step != 0) {
                        Logx.event(
                            LogTags.APP, "手势：单指横滑翻页",
                            "dx" to singleDx, "dy" to singleDy,
                            "抢事件" to singleIntercepted,
                        )
                        swipePage(step)
                    }
                    singleIntercepted = false
                    return true
                }
                // 三指横滑换页收尾（第三十二轮：竖滑 → 横滑）
                if (threeFinger) {
                    threeFinger = false
                    val dx = threeFingerMid - threeFingerX
                    val step = PageGesture.threeFingerStep(dx, THREE_FINGER_PX)
                    if (step != 0) {
                        // 左滑 → 下一页；右滑 → 上一页
                        Logx.event(LogTags.APP, "手势：三指横滑翻页", "dx" to dx)
                        swipePage(step)
                    }
                    return true
                }
                // 右缘竖滑收尾
                if (edgeTracking || edgeIntercepted) {
                    edgeTracking = false
                    edgeIntercepted = false
                    val dy = designY(ev.y) - edgeDownY
                    val step = PageGesture.edgeSwipeStep(dy, EDGE_TRIGGER)
                    if (step != 0) {
                        Logx.event(LogTags.APP, "手势：右缘竖滑翻页", "dy" to dy)
                        swipePage(step)
                    }
                    return true
                }
            }
            else -> Unit
        }
        return true
    }

    /**
     * 滚轮翻页。
     *
     * - 取 |AXIS_VSCROLL| 与 |AXIS_HSCROLL| 中更大的那个；
     * - 幅度 < 0.01 不处理；
     * - 700ms 内只响应一次（防滚轮连发翻页）。
     */
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL) {
            var axis = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (abs(h) > abs(axis)) axis = h
            if (abs(axis) < 0.01f) return false
            val now = android.os.SystemClock.uptimeMillis()
            if (now < scrollLockUntil) return true
            scrollLockUntil = now + SCROLL_LOCK_MS
            Logx.event(LogTags.APP, "手势：滚轮翻页", "axis" to axis)
            swipePage(if (axis > 0f) -1 else 1)
            return true
        }
        return super.onGenericMotionEvent(ev)
    }

    /** 翻页步进（：取模循环）。 */
    private fun swipePage(step: Int) {
        showPage(if (step >= 0) nextPage(page) else prevPage(page))
    }

    /** 三指手势用的中间 X（第三十二轮：换页改成左右三指滑动）。 */
    private fun midX(ev: MotionEvent): Float {
        val n = ev.pointerCount.coerceAtMost(3)
        if (n <= 0) return designX(ev.x)
        var sum = 0f
        for (i in 0 until n) sum += designX(ev.getX(i))
        return sum / n
    }

    /** 屏幕坐标 → 设计坐标（根视图收到的是屏幕坐标，内容层做了等比缩放）。 */
    private fun designX(raw: Float): Float {
        val s = content.scaleX.takeIf { it > 0f } ?: 1f
        return (raw - content.translationX) / s
    }

    private fun designY(raw: Float): Float {
        val s = content.scaleY.takeIf { it > 0f } ?: 1f
        return (raw - content.translationY) / s
    }

    /** 由 Activity 转发返回键：先关浮层，再回首页；都没得关时返回 false。 */
    fun onBack(): Boolean {
        Logx.event(LogTags.APP, "返回键")
        if (sourceMenu.isOpen) {
            sourceMenu.hide()
            return true
        }
        if (drawerShowing()) {
            closeDrawer()
            return true
        }
        if (pipFullscreen) {
            // 画中画全屏 → 返回键还原（要挂回地图卡 + 恢复留白）
            exitPipFullscreen()
            return true
        }
        taskEditor?.let {
            content.removeView(it)
            taskEditor = null
            return true
        }
        taskList?.let {
            content.removeView(it)
            taskList = null
            return true
        }
        // 第五十四轮：叠在配置面板上的应用选择器要先收
        quickAppPicker?.let {
            content.removeView(it)
            quickAppPicker = null
            return true
        }
        overlayDialog?.let {
            content.removeView(it)
            overlayDialog = null
            return true
        }
        navSlotDialog?.let {
            content.removeView(it)
            navSlotDialog = null
            return true
        }
        if (settings != null) {
            closeSettings()
            return true
        }
        hvac?.let { it.dismiss(); return true }
        if (climate != null) {
            closeClimate()
            return true
        }
        if (page != PAGE_SCENE) {
            showPage(PAGE_SCENE)
            return true
        }
        return false
    }

    private val mediaListener = object : MusicController.Listener {
        override fun onMediaChanged(track: TrackInfo?) {
            mediaTrack = track
            refresh()
        }

        override fun onLyricChanged(timeline: LyricTimeline?, index: Int, wipe: Float) {
            mediaLyric = timeline
            mediaLyricIndex = index
            mediaLyricWipe = wipe
            mediaLyricFallback = MusicController.get().lyricState
            if (timeline != null && index >= 0) {
                // 第三十三轮：第二页组合卡的播放器显示 **5 句**（上 2 + 当前 + 下 2），
                // 整窗居中（expandedLyricWindow）。
                navPage.setLyricWindow(timeline.lines.map { it.text }, index)
                // 场景页中央大字歌词（当前句大号高亮 + 上下句淡出）—— 超出增强。
                scenePage.lyricPrev = navPage.lyricPrev
                scenePage.lyricCurrent = navPage.lyricCurrent
                scenePage.lyricNext = navPage.lyricNext
                // 第四轮：当前句带**擦除高亮**
                // 比例随播放位置往前推（`wipe` 由 `LyricTimeline.wipeFraction` 算出）。
                navPage.lyricWipe = wipe
                scenePage.lyricWipe = wipe
                slotsBar?.lyricWipe = wipe
            } else {
                navPage.setLyricWindow(emptyList(), -1)
                // 空闲/加载中/纯音乐：直接用控制器的状态文案
                navPage.lyricCurrent = mediaLyricFallback
                scenePage.lyricPrev = ""
                scenePage.lyricCurrent = mediaLyricFallback
                scenePage.lyricNext = ""
                navPage.lyricWipe = 0f
                scenePage.lyricWipe = 0f
                slotsBar?.lyricWipe = 0f
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MusicController.get().addListener(mediaListener)
        // 导航引导数据源跟着窗口生命周期起停
        navGuideSource?.start()
        // 接收器注册好之后，再向高德**索要区域信息**（第三十一轮新增）。
        //
        // 高德收到 的广播后会去向车机（ecarx.naviservice）要区域信息，
        // 车机回包后高德把城市**全局广播**出来——
        // 真机上系统定位长期没有位置时，这是我们唯一的坐标来源入口。
        //
        // 已经有城市（本次进程拿到过 / 上次落盘过）就不再重复要。
        val prefs = com.cardesk.launcher.core.Prefs(context)
        if (com.cardesk.launcher.core.AmapCitySource.broadcastCity.isNullOrBlank() &&
            prefs.weatherCity.isBlank()
        ) {
            Logx.i(LogTags.WEATHER, "启动时向高德索要区域信息")
            com.cardesk.launcher.core.AmapCitySource.requestAreaInfo(context.applicationContext)
        }
    }

    override fun onDetachedFromWindow() {
        MusicController.get().removeListener(mediaListener)
        handler.removeCallbacksAndMessages(null)
        weatherDesk?.stop()
        navGuideSource?.stop()
        VehicleController.removeObserver { }
        super.onDetachedFromWindow()
    }
}
