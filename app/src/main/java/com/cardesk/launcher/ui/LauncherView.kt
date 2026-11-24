package com.cardesk.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.HomeForeground
import com.cardesk.launcher.core.DockQuickApps
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.NavSlot
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.PageGesture
import com.cardesk.launcher.core.PageLayoutRect
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.dock.GlobalDockService
import com.cardesk.launcher.core.media.LyricTimeline
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.core.media.TrackInfo
import com.cardesk.launcher.ui.dialogs.DialogSheet
import com.cardesk.launcher.ui.pages.NavPage
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.ui.pip.NavSplitPlacement
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
 *  ├── DockBarView 底部一体化控制栏 1920×112（铺满屏宽，入口/媒体/空调）
 *  └── overlayHost 空调浮层 / 360 / 设置面板
 * `
 */
class LauncherView(context: Context) : FrameLayout(context) {

    companion object {
        /**
         * 收起副画后延迟多久去要前台。
         *
         * 400ms 是实测值：VirtualDisplay.release → AMS 迁移 task → 窗口切换，
         * 模拟器上这段 200~350ms 走完；取 400ms 留余量，肉眼仍算「立刻」。
         */
        const val PIP_COLLAPSE_RECLAIM_MS = 400L

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

        /**
         * 诊断心跳间隔（毫秒，第三百零三轮实车闪屏诊断）。
         *
         * 30 秒一条 INFO「诊断心跳」（见 [maybeDiagHeartbeat]），
         * 把 refresh/状态栏重绘/玻璃重发布计数和日夜六源一次带出。
         */
        const val DIAG_HEARTBEAT_INTERVAL_MS = 30_000L

        /** 驾驶模式切换成功后，快捷面板停留一下再自动关闭。 */
        const val DRIVE_MODE_CLOSE_DELAY_MS = 700L

        /**
         * 视频壁纸亮度的采样间隔（毫秒，第222轮）。
         *
         * 一次 1920×1080 的 `PixelCopy` 不便宜，而视频一直在动 ——
         * 3 秒采一次，浮层墨色的切换肉眼跟得上，也不白烧 CPU。
         */
        const val VIDEO_BACKDROP_INTERVAL_MS = 3_000L

        /** 右缘竖滑命中区宽度。 */
        private const val EDGE_WIDTH = 120f

        /** Dock 距屏幕底的设计像素（设计板 s01：底边 1072 → 距底 8）。 */
        private const val DOCK_BOTTOM_MARGIN = 0

        /** 原厂设置包（画板 05「快捷入口 · 原厂设置」）。 */
        private const val NATIVE_SETTINGS_PACKAGE = "com.ecarx.settings"

        /**
         * 全局 Dock 的**顶边**（设计坐标 px）= 968（1080 − 112，第192轮）。
         *
         * = 屏幕底 1080 − Dock 高 112。
         *
         * ## 为什么它是公开的
         *
         * 导航页整块内容的高度就是按它算的（底边正好贴住 Dock 顶边），
         * 单测要拿它跟画中画卡的底边做比较 ——
         * 提出来才能让测试和代码**同源**，不会各写一个数字然后悄悄漂移。
         *
         * 第192轮：976 → 968 —— Dock 总高从 104 改成 112
         * （和系统导航预留带一样高），顶边相应上移 8px。
         */
        const val DOCK_TOP_Y = 968

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

    /** 双指横滑接管阈值（设计坐标 px）：先抢到事件，最终是否翻页看抬手时的位移。 */
    private const val TWO_FINGER_SLOP = 60f

        /** 滚轮翻页的冷却时间（700ms）。 */
        private const val SCROLL_LOCK_MS = 700L
    }

    private val prefs = Prefs(context.applicationContext)
    private var pal: Palette = resolvePalette().also { Skin.apply(it) }
    private val background = BackgroundView(context)

    /**
     * 当前壁纸在浮层区的平均颜色（第222轮）。
     *
     * - 顶部：状态栏右侧的裸元素 + 右上天气块；
     * - 中部：中心歌词。
     *
     * null = 还不知道 ⇒ 浮层跟着主题走（= 本轮之前的行为）。
     */
    private var backdropTop: Int? = null
    private var backdropCenter: Int? = null

    /**
     * **视频壁纸**的播放层（第207/208/214/225轮）。
     *
     * ## 第225轮：默认后端改回 `TextureView`（第213轮的「不通」结论是错的）
     *
     * 第213轮观察到「解码 53.4 帧/s、日志有第一帧、画面一个像素都没上屏」，
     * 当时归因为「`TextureView` 合成路径不通」。**真实原因是这里自己挖的坑**：
     * 为了诊断，代码给 `TextureView` 自己的 `SurfaceTexture` 装了
     * `setOnFrameAvailableListener` —— 那会把 `TextureView` **内部**的帧到达监听
     * 覆盖掉（它就是靠那个回调去 `updateTexImage()`），于是帧永远不上屏。
     * 「`SurfaceFlinger` 里没有 TextureView 层」是正常现象：
     * `TextureView` 画进窗口 buffer，本来就不该有独立层。
     *
     * 第225轮去掉那个监听（诊断改用 `onSurfaceTextureUpdated`），
     * 实测**画面正常上屏**，并把默认后端翻回 `TextureView`：窗口可以保持**不透明**，
     * 画中画的承载层重新按圆角裁（见 `PipSurface` 的圆角问题）。
     *
     * ## `SurfaceView` 这条路的代价（仍保留为备选，pref 可切）
     *
     * ## 为什么 `SurfaceView` 能「夹在壁纸与桌面卡片之间」
     *
     * `SurfaceView` 是**推模式**：它有自己独立的 SurfaceFlinger 层，默认 z 序在
     * **窗口 buffer 之下**。于是层级天然就是：
     *
     * ```
     * 系统壁纸层
     *   └─ cardesk 窗口 buffer   ← 桌面卡片 / 状态栏 / Dock 画在这里
     *        └─ SurfaceView 层    ← 视频在这（窗口 buffer 同一块区域被“挖空”）
     * ```
     *
     * 代价是**窗口 buffer 在视频那块区域必须透明** ⇒ 窗口要 `PixelFormat.TRANSLUCENT`，
     * 壁纸层要让路、桌面底色要透明（见 [applyVideoWindowFormat] / [applyDesktopBackground]）。
     *
     * 额外好处：视频能进 HWC 硬件叠加平面，**比 `TextureView` 每帧上传纹理省得多**。
     *
     * ## ⚠️ 两个必须绕开的坑（`SurfaceView` 侧）
     *
     * 1. **别设 `setZOrderOnTop(true)` / `setZOrderMediaOverlay(true)`** ——
     *    那两种会让视频盖在窗口 buffer 之上，桌面卡片全被遮住。
     *    就要默认 z 序（surface 在窗口之下）。
     * 2. **窗口 format 变化会重建 Surface** —— 半透明切换会让 `surfaceDestroyed` 到来，
     *    这时播放器必须放掉，等新 surface 再起播（见 [videoHolderCallback]）。
     *
     * 第225轮：这条路**半透明窗口**的副作用曾被证实 —— 窗口一旦 `TRANSLUCENT`，
     * 画中画承载层的四角会变直角、切页后还残留黑块。
     *
     * 第226轮把这两条都解掉了（窗口透明时画中画改走「自建虚拟屏 + `TextureView` 镜像」，
     * 承载画面回到窗口自己的缓冲里画 ⇒ 圆角、随页隐藏、裁剪全按 View 规则成立），
     * 第227轮因此把默认后端**改回** `SurfaceView`（HWC 直通、省电、不掉帧）。
     */
    private val videoSurfaceView = android.view.SurfaceView(context)

    /** 第208轮的老路径：`TextureView`（保留备选，见 [videoBackend]）。 */
    private val videoTextureView = android.view.TextureView(context)

    /**
     * 第214轮：视频壁纸的**合成后端**。
     *
     * 第227轮：默认值改回 `SurfaceView`（见 [core.Prefs.videoWallpaperBackend]），
     * 这里**只在启动时读一次** —— 后端是「装一次就不动」的设置，
     * 要换得改 `shared_prefs` 再重启桌面。
     */
    private val videoBackend: Int = prefs.videoWallpaperBackend

    /**
     * 第214轮：`SurfaceView` 的 Surface 是不是已经就绪。
     *
     * 必须在 [init] 之前声明 —— init 块里要用它。
     */
    private var videoSurfaceReady = false

    /**
     * 当前实际使用的视频层视图（按 [videoBackend] 选，启动时定死）。
     */
    private val videoWallpaperView: android.view.View =
        if (videoBackend == com.cardesk.launcher.core.VideoWallpaper.BACKEND_SURFACE_VIEW) {
            videoSurfaceView
        } else {
            videoTextureView
        }

    init {
        // 第214轮：`SurfaceView` 的 Surface 生命周期。
        //
        // ⚠️ `surfaceDestroyed` 一定要把播放器放掉：切半透明窗口会让窗口
        // 重建、Surface 跟着重建，旧播放器还攥着已经失效的 surface。
        //
        // 第227轮：**两条路的回调都装上**（不再按当前后端二选一）——
        // 设置页可以运行时切后端，切过去时那一路的回调必须已经在位。
        // 没被选中的那一路不会 attach（它不在视图树里），
        // 它的回调自然也不会触发（`TextureView` 没 attach 就没有 SurfaceTexture；
        // `SurfaceView` 没 attach 就没有 surface）。
        videoSurfaceView.holder.addCallback(
            object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    videoSurfaceReady = true
                    maybeStartVideoWallpaper()
                }

                override fun surfaceChanged(
                    holder: android.view.SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    videoSurfaceReady = true
                    maybeStartVideoWallpaper()
                }

                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    videoSurfaceReady = false
                    videoStarted = false
                    // 第227轮：**记下当前播放位置**，重建后接着播。
                    //
                    // 为什么 surface 会被换掉：
                    // 画中画那条「自建虚拟屏」路线建虚拟屏时，系统会给那块虚拟屏
                    // 下一份**配置覆盖**（`ActivityManager: Override config changes=… for displayId=NN`），
                    // 连带把**我们自己的窗口 surface** 重建一次
                    // （系统侧可见 `BufferQueueProducer: [SurfaceView - …] detachBuffer`）。
                    //
                    // 以前这里是直接 `releaseVideoPlayer()` ⇒ 新播放器**从第一帧开始**
                    // ⇒ 用户看到「开机后视频跳回开头」。
                    // 现在先把位置存下来，重建后 `seekTo` 回去 ⇒ 视频**接着播**。
                    //
                    // （试过「同一个播放器 `setSurface` 换输出」——
                    //  实测在模拟器上**画面会黑**（解码器没有真的换输出面），
                    //  那条路作废。）
                    videoResumePositionMs = runCatching {
                        (videoPlayer?.currentPosition ?: 0).toLong()
                    }.getOrDefault(0L)
                    releaseVideoPlayer()
                    videoSurfaceLost = true
                }
            },
        )
        videoTextureView.surfaceTextureListener = object :
            android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                // 第208轮：**只记下来，不在这里起播**。
                // `onSurfaceTextureAvailable` 是在 attach 阶段就回调的，
                // 那时 View 还没 layout、SurfaceTexture 的 buffer 尺寸还是 0×0 ——
                // 解码器配不出输出 buffer，`prepareAsync()` 必然失败
                // （`IOException: Prepare Async failed.: status=0x80000000`）。
                // 真正的起播条件是「SurfaceTexture 有了 **且** View 拿到真实尺寸」。
                videoSurfaceTexture = surface
                // 第225轮：**不要**给 TextureView 自己的 SurfaceTexture 装
                // `setOnFrameAvailableListener` —— 那会把 `TextureView` **内部**
                // 的帧到达监听覆盖掉（它正是靠那个回调去 `updateTexImage()`），
                // 结果就是「解码器一直在出帧、日志也打了『收到第一帧』，
                // 但屏幕上永远是黑的」。第213轮把这条路判成「TextureView 合成不通」，
                // 真正原因是这里（SurfaceFlinger 里没有 TextureView 层是正常的：
                // TextureView 画进窗口 buffer，本来就不该有独立层）。
                // 现在改用 [onSurfaceTextureUpdated] 做「帧真的画上去了」的诊断。
                maybeStartVideoWallpaper()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                // 第225轮：`onSurfaceTextureUpdated` 是「这一帧真的被
                // `TextureView.updateTexImage()` 取走并画进窗口」的信号 ——
                // 比「解码器出帧」更能证明画面到底上没上屏。
                if (!videoFrameSeen) {
                    videoFrameSeen = true
                    com.cardesk.launcher.core.log.Logx.i(
                        com.cardesk.launcher.core.log.LogTags.SETTING,
                        "视频壁纸：第一帧已上屏（TextureView）",
                    )
                }
            }
        }
    }

    /** 视频壁纸播放器（null = 没在播）。 */
    private var videoPlayer: android.media.MediaPlayer? = null

    /** 第208轮：视频壁纸层是不是已经挂进窗口了。 */
    private var videoLayerAttached = false

    /** 第208轮：视频层拿到的 `SurfaceTexture`（attach 之后才会有）。 */
    private var videoSurfaceTexture: android.graphics.SurfaceTexture? = null

    /**
     * 第208轮：**必须持有** `Surface` 对象的引用。
     *
     * `Surface(surfaceTexture)` 如果只是当临时值用（`mp.setSurface(Surface(st))`），
     * 它会被 GC 掉 → 析构函数里 `IGraphicBufferProducer::disconnect()`
     * → 生产者被断开 → 解码器再连这个 surface 直接 EPIPE：
     * ```
     * E/SurfaceUtils: Failed to connect to surface 0x..., err -32
     * W/NuPlayerDecoder: [c2.android.avc.decoder] failed to connect to native window, error=-32
     * ```
     * 表现是「播放器 state=STARTED、解码器也在跑，但一帧都出不来」。
     */
    private var videoSurface: android.view.Surface? = null

    /** 第208轮：视频层布局就绪（拿到真实宽高）了吗。 */
    private var videoLayoutReady = false

    /** 第208轮：视频层已经起播了吗（避免重复 prepare）。 */
    private var videoStarted = false

    /** 第208轮：有没有收到过视频帧（诊断用：区分「没解码」与「帧没进 TextureView」）。 */
    private var videoFrameSeen = false

    /**
     * 第208轮：**视频壁纸的 SurfaceTexture 还没就绪时，先把起播请求记下来**。
     *
     * `TextureView.surfaceTexture` 只有在 View attach 且完成布局之后才非 null；
     * 冷启动恢复视频壁纸时（`build()` 里 `post { startVideoWallpaper() }`）
     * 这个 Runnable 很可能**跑在 TextureView 拿到 SurfaceTexture 之前**，
     * 于是 `Surface(null)` 直接抛异常 → 视频壁纸起不来，还把开关自己关掉了。
     *
     * 改成：Surface 没就绪就挂起，等 [android.view.TextureView.SurfaceTextureListener]
     * 回调 `onSurfaceTextureAvailable` + 布局就绪之后再真的起播。
     */
    private var pendingVideoWallpaperStart = false

    /**
     * **静态壁纸轮播**的检查句柄（第207轮）。
     *
     * 每秒查一次「够不够时间切了」（见 [com.cardesk.launcher.core.WallpaperCarousel]）——
     * 用 1 秒的心跳而不是按间隔起定时器，改间隔时不用重建定时器。
     *
     * ## 第211轮：必须挂在**类里的 [handler]** 上
     *
     * 原来这里每次 `run()` 都 `new` 一个 `Handler(Looper.getMainLooper())`，
     * 带来两个问题：
     *
     * 1. `onDetachedFromWindow()` 里只有 `handler.removeCallbacksAndMessages(null)`
     *    （[handler] 是另一个实例）→ **清不掉这条心跳**，视图 detach 后它还在跑；
     * 2. `build()` 里 `postDelayed(carouselTicker, 1000)` **前面没有 `removeCallbacks`**，
     *    而 `build()` 在换肤（[applyTheme]）和改底栏形态（[onSettingsApplied]）时都会重跑
     *    → **每重建一次就多挂一条链**，1 秒检查跑 N 份。
     *
     * 现在统一走类里的 [handler]：post 前先 `removeCallbacks` 去重，
     * detach 时也被 `removeCallbacksAndMessages(null)` 一起收掉，
     * 重新 attach 时在 [onAttachedToWindow] 里再挂上。
     */
    private val carouselTicker = object : Runnable {
        override fun run() {
            carouselTickCount++
            checkWallpaperCarousel()
            handler.postDelayed(this, 1000L)
        }
    }

    /**
     * 第211轮：轮播心跳**执行过多少次**（只给单测看）。
     *
     * 用它来盯两件事：
     * 1. `build()` 重跑（换肤 / 改底栏形态）之后，心跳**不能叠加**；
     * 2. 视图 detach 之后，心跳**必须停**。
     */
    private var carouselTickCount = 0

    /** 轮播心跳的执行次数（单测观察口）。 */
    fun carouselTickCountForTest(): Int = carouselTickCount

    /** 距上次切壁纸过了多久（毫秒）。 */
    private var wallpaperSwitchedAtMs = 0L

    /** 自定义壁纸是否正在后台解码（避免重复起线程）。 */
    private var customLoading = false
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

    /**
     * 上一次写 policy 时的状态签名（全局 Dock 开关 + RRO 是否生效），第八十七轮。
     *
     * `policy_control` 是写系统设置：resume 一次写一次会拖慢首页恢复，
     * 而且真车上每次写都可能触发系统 UI 重排。只在签名变化时重写。
     */
    private var lastPolicySignature: String? = null

    /**
     * 策略写系统的**序号**（第191轮）。
     *
     * `applyOemPolicy()` 现在跑在后台线程（写 `policy_control` / overscan
     * 是跨进程调用，卡在首帧前面太贵）。
     *
     * 换肤重建会**再触发一次** —— 万一两次重叠着跑，
     * 用这个序号让**旧的那次**在动手写之前就作废，
     * 免得两个线程交叉写系统设置。
     */
    private val policyApplySeq = java.util.concurrent.atomic.AtomicInteger(0)
    private var taskList: com.cardesk.launcher.ui.dialogs.TaskListDialog? = null
    private var taskEditor: com.cardesk.launcher.ui.dialogs.TaskEditorDialog? = null
    private var overlayDialog: View? = null

    /**
     * **正在播退场动画**的浮层。
     *
     * 退场动画这段时间里，这一层还挂在树上（动画播完才摘），
     * 所以这期间它有可能被**再次要求关闭** ——
     * 用户连点两下「关闭」、或者「关闭」和返回键同时来。
     *
     * 同一层只能退场一次、也只能摘一次，
     * 这里用集合把重复的摘层请求挡掉。
     */
    private val closingOverlays = mutableSetOf<View>()

    /**
     * 启动分段打点（第191轮）。
     *
     * `build()` / `startServices()` 里的每一步都是「主线程同步做」，
     * 哪一步慢只能靠打点才看得出来 ——
     * 真车（领克 dcy11，Android 9）上一次冷启动里，
     * `build()` 自己就占了 4.6 秒。
     *
     * 全部打到 [com.cardesk.launcher.core.StartupTrace]，
     * 诊断包 `summary.txt` 的「冷启动」段能直接看到细到步的耗时。
     */
    private fun mark(label: String) = com.cardesk.launcher.core.StartupTrace.mark(label)

    /**
     * 「有车门未关」弹窗（第136轮）。
     *
     * 单独记一份，而不是靠 `overlayDialog` 的类型判断 ——
     * 因为这个弹窗会被**自动**关闭（门全关上时），而别的弹窗只会被用户关掉。
     */
    private var doorAlertDialog: com.cardesk.launcher.ui.dialogs.DoorAlertDialog? = null

    /** 「车辆状态」面板（第166轮 C 版车况卡点开）。 */
    private var vehicleStatusDialog: com.cardesk.launcher.ui.dialogs.VehicleStatusDialog? = null

    /** 车辆页（画板 05 / n04）；null = 没打开。 */
    private var vehiclePage: com.cardesk.launcher.ui.pages.VehiclePage? = null

    /** 最近一次的车况快照（面板打开时先用它，避免等下一轮 ticker）。 */
    private var lastVehicleInfo: com.cardesk.launcher.vehicle.VehicleInfo? = null

    /**
     * 用户**手动**关掉过车门告警（第136轮）。
     *
     * 手动关掉 = 「我现在知道了，别再弹」。
     * 这个标记一直有效到**所有部件都关上**为止 ——
     * 否则用户关掉弹窗后，另一扇门又开了，它会立刻弹回来（很烦）。
     */
    private var doorAlertDismissed = false

    /** 上一次记录的「开着的东西」（签名），只在变化时记日志。 */
    private var doorAlertSignature: String? = null
    private var floatLayer: com.cardesk.launcher.ui.floats.FloatWindowLayer? = null
    private var navSlots: MutableList<NavSlot> = mutableListOf()
    private lateinit var dots: PageDotsView
    private lateinit var floats: FloatBubbleView
    private lateinit var pageHost: FrameLayout
    private lateinit var scenePage: ScenePage
    private lateinit var navPage: NavPage
    private lateinit var drawerPage: DrawerPage

    /** 最近任务页（第九十五轮 C3）；null = 没打开。 */
    private var recentTasksPage: RecentTasksPage? = null

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

    /** 车机 Input 通道的 Home 键接管（第三百一十七轮）；null = 未启动。 */
    private var homeKeyInterceptor: com.cardesk.launcher.vehicle.HomeKeyInterceptor? = null

    /**
     * 状态栏连接状态采样器（第138轮）。
     *
     * 读真机的 WiFi / 热点 / 蓝牙 / 车机 4G 状态，
     * 按 [com.cardesk.launcher.core.SystemStatusPolicy] 算成图标槽喂给状态栏。
     */
    private var systemStatusSampler: com.cardesk.launcher.ui.shell.SystemStatusSampler? = null

    /**
     * 高德广播过来的日夜状态（null = 还没收到过高德的信号）。
     *
     * 这是「跟随高德」的**第一来源**：高德主题一变就会广播
     * `10019` + `EXTRA_STATE` 37（白天）/ 38（黑夜），见 `core/nav/AmapDayNight`。
     *
     * 以前桌面没有接这条信号，「跟随高德」读的是系统 uiMode ——
     * 那个值在这台车机上没人写（只有对标 App 会写），所以永远是个死值，
     * 表现就是「桌面切了、高德没切 / 高德切了、桌面没切」。
     */
    private var amapDayNightSignal: Boolean? = null

    /** 上一次推给高德的日夜态（null = 还没推过）。 */
    private var amapPushedNight: Boolean? = null

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

    // 双指横滑换页（第206轮：单指改成双指 —— 单指要留给画中画拖地图）
    private var twoFinger = false
    /** 双指按下时的中位点 X（设计坐标）——位移起点。 */
    private var twoFingerX = 0f
    /** 双指按下时的中位点 Y（设计坐标）——位移起点。 */
    private var twoFingerY = 0f
    /** 最新一次 MOVE 的两指中位点 X（设计坐标）——位移终点。 */
    private var twoFingerMid = 0f
    /**
     * 最新一次 MOVE 的两指中位点 Y（设计坐标）。
     *
     * 第207轮补：抬手那一刻（`ACTION_UP`）只剩**一根**手指，
     * 这时再算中位点就只剩单指的 Y，与按下时的中位点 Y 不同源 →
     * `dy` 虚高 → 本该判成横滑的手势被卡掉。
     * 所以纵向位移的终点必须在 **MOVE（两指都在）** 时就记下来。
     */
    private var twoFingerMidY = 0f
    /** 双指按下时的间距（设计坐标）。 */
    private var twoFingerSpan0 = 0f
    /** 当前间距 / 按下时的间距（1.0 = 没缩放）。 */
    private var twoFingerRatio = 1f
    private var twoFingerIntercepted = false

    /** 双指横滑是否已经接管（接管后子树收 CANCEL，后续事件由自己吞掉）。 */
    private var twoFingerTaken = false

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

    /**
     * 「重活」是否已经启动（天气 / 定时刷新 / 导航引导，见 [startServices]）。
     *
     * 第九十三轮冷启动修复：这些以前都在构造函数里同步做，把首帧堵住了；
     * 现在由 `MainActivity` 在桌面**画出第一帧之后**再调 [startServices]。
     */
    private var servicesStarted = false

    /**
     * 画中画承载（**= 唤起高德**）的闸门（第116轮）。
     *
     * 第112轮：桌面启动时**不**承载画中画。
     *
     * 承载 = `ActivityEmbed.embed` → `startActivity` —— 那就是**真的把高德这个
     * Activity 启动起来**。以前它在 `build()` 里就做了（桌面还在搭骨架），
     * 于是「开机时高德先跳出来」，跟桌面自己的首帧抢 CPU / 抢焦点。
     *
     * 现在推迟到桌面**完全启动之后**（[startDeferredAmapActions]）。
     *
     * 第116轮：那条链**静默断过一次** ——
     * `MainActivity` 淡出启动页时在**已脱离窗口**的视图上 `post`，
     * Runnable 永远不会执行 → `onDesktopReady()` 永不执行 → 承载永不开始 →
     * 用户看到「导航页画中画不显示应用」（只有占位图），而且**没有任何报错**。
     *
     * 所以「承载该开始了」这件事改由 [com.cardesk.launcher.core.DesktopReadyGate]
     * 判定：正常路径是 `markDesktopReady()`，兜底路径是「首帧之后等满 3 秒」。
     */
    private val pipHostingGate = com.cardesk.launcher.core.DesktopReadyGate()

    /**
     * 「桌面完全启动后的跨进程动作」是否已经做过（第112轮）。
     *
     * 幂等：重复调用直接返回。
     */
    private var deferredAmapStarted = false

    /** 上一次「车身状态变化」触发的立即刷新时刻（`SystemClock.uptimeMillis()`）。 */
    private var lastBodyRefreshAt = 0L

    // ------------------------------------------------------------------
    // 实车闪屏诊断（第三百零三轮）：30 秒一条心跳，全部候选根因一屏可读
    // ------------------------------------------------------------------

    /** refresh() 被执行的累计次数（诊断心跳用）。 */
    private var diagRefreshCount = 0L

    /** 上一次诊断心跳的时刻（`SystemClock.uptimeMillis()`）。 */
    private var diagHeartbeatAt = 0L

    /**
     * 诊断心跳（**30 秒一条**，INFO 落盘，上车导出诊断包直接可读）。
     *
     * 用户反馈：装了 UI 重设计版后实车屏幕右侧闪烁。候选根因四条链路
     * （日夜信号抖动 / 状态栏每秒重绘 / WiFi 档位抖动 / 玻璃底重发布）
     * 在一条日志里同时给出：
     *
     * | 字段 | 看什么 |
     * |---|---|
     * | `refresh` | refresh 累计次数（涨速应 ≈ 每秒 1 次） |
     * | `statusInvalidate` | 状态栏重绘次数（正常 ≈ refresh 同速；明显更快 = 状态栏在抖） |
     * | `glassPublish` | 玻璃底重发布次数（正常几乎不涨；高频涨 = 卡片玻璃在闪） |
     * | `glassBlur` / `glassDrop` | 后台模糊完成 / 被新请求作废的次数（第三百零四轮；`drop` 涨得快 = 壁纸/日夜在抖） |
     * | `rs` | 玻璃模糊是否用上了 RenderScript 真高斯（false = 老 ROM 回退盒式） |
     * | `night` / `amap` / `sensor` / `car` / `system` / `minute` | 日夜六源快照（两次心跳间 `night` 翻转 = 整桌重建实锤） |
     * | `page` / `videoPass` | 当前页 / 视频壁纸让路态（排除路线切换类闪） |
     *
     * 这条心跳本身**只写日志不重绘**，不会加剧闪烁。
     */
    private fun maybeDiagHeartbeat() {
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - diagHeartbeatAt < DIAG_HEARTBEAT_INTERVAL_MS) return
        val first = diagHeartbeatAt == 0L
        diagHeartbeatAt = nowMs
        if (first) return // 首轮只记基线，不打（桌面刚起来各计数都是 0）
        val extras = mutableListOf<Pair<String, Any?>>(
            "refresh" to diagRefreshCount,
            "statusInvalidate" to (if (::statusBar.isInitialized) statusBar.invalidateCount else -1L),
            "glassPublish" to com.cardesk.launcher.core.Glass.publishCount,
            "glassBlur" to com.cardesk.launcher.core.Glass.blurCount,
            "glassDrop" to com.cardesk.launcher.core.Glass.blurDropCount,
            "rs" to com.cardesk.launcher.core.Glass.rsAvailable,
            "page" to page,
            "videoPass" to background.videoPassthrough,
        )
        // 日夜六源快照（会多一次车控 binder 读，30 秒一次可接受）。
        runCatching {
            com.cardesk.launcher.core.NightResolver.snapshotSignals(prefs, context)
        }.onSuccess { sig -> extras.addAll(sig) }
        Logx.i(LogTags.APP, "诊断心跳", *extras.toTypedArray())
    }

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

    // ------------------------------------------------------------------
    // 媒体源图标（第125轮）的字段（第126轮：**必须声明在 init{} 之前**）
    //
    // ## 为什么位置这么重要
    //
    // Kotlin 的初始化顺序 = **声明顺序**。而 `init{}` 块（构造期）里就会
    // `build()` → `refresh()` → `updateMediaSourceIcon()` → **起后台线程取图标**。
    //
    // 这两个字段以前声明在 `init{}` **之后** → 构造期起的后台线程读到的
    // `iconHandler` 还是 **null** → 取完图标回来 `iconHandler.post{}` →
    // NullPointerException → **整个桌面进程挂掉**。
    //
    // 真机 / 模拟器实测（默认媒体源 = QQ音乐 时启动必崩）：
    // `FATAL EXCEPTION: Thread-3 … at LauncherView.updateMediaSourceIcon$lambda(LauncherView.kt:1765)`
    //
    // 改成 `by lazy` **也救不了**：Kotlin 的 `lazy` 委托本身也是一个字段，
    // 一样按声明顺序初始化 —— 构造期读到的会是「委托是 null」。
    // 所以唯一的办法是：**把声明挪到 `init{}` 之前**。
    // （回归测试：`LauncherSmokeTest.qqDefaultMediaSourceDoesNotCrashTheLauncher`）
    // ------------------------------------------------------------------

    /** 正在后台加载图标的来源 id（防重复加载：refresh 每秒都会调）。 */
    private var iconLoadingFor: String? = null

    /** 图标加载完回主线程用。 */
    private val iconHandler = Handler(Looper.getMainLooper())

    init {
        // 第208轮：视频壁纸的 Surface 就绪回调 ——
        // 冷启动恢复视频壁纸时 SurfaceTexture 可能还没建好，先挂起，等回调再起播。
        // 第五十八轮：卡片圆角是**全局可变 token**，启动时先按偏好摆好。
        com.cardesk.launcher.core.CardStyle.applyRadius(prefs.cardRadius)
        // 第163轮：写实车模（car3d）开关也按偏好摆好 —— 进程内副本，绘制时只读一个 boolean。
        com.cardesk.launcher.core.car3d.Car3dSettings.load(context, prefs)
        applyDesktopBackground(0xFF05080F.toInt())
        addView(background, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 第208轮：视频壁纸层**不常驻** —— 播的时候才挂进来（见 [attachVideoWallpaperLayer]）。
        //
        // 踩过的坑（都试过，别再犯）：
        // ① 常驻 + `visibility = GONE` → TextureView 的 SurfaceTexture 被销毁，
        //    重新 VISIBLE 时不一定重建 → 视频再也起不来；
        // ② 常驻 + `alpha = 0` → alpha=0 的 View 不参与渲染，SurfaceTexture 压根不
        //    创不出来，`MediaPlayer` 连不上（prepare 失败）；
        // ③ `setOpaque(false)` → SurfaceTexture 的 buffer 格式变 RGBA_8888，
        //    视频解码器输出的 YUV 协商不上格式 → prepare 失败。
        //
        // 所以：**要播就 attach（attach 必然重建 SurfaceTexture + 回调 available），
        // 不播就 remove（完全不参与渲染，静态壁纸照常）**。
        content.layoutParams = LayoutParams(Design.W.toInt(), Design.H.toInt())
        addView(content, content.layoutParams)

        VehicleController.init(context.applicationContext, prefs)
        // 第135轮：桌面启动时也补一次「开机自动切换驾驶模式」。
        //
        // 为什么不能只靠开机广播：有些车机不把 `BOOT_COMPLETED` 广播给我们
        // （真车诊断包 4231 里一条「收到开机广播」都没有）。
        // 两个触发点走同一个入口 + 同一套冷却（10 分钟）→ 一次开机只切一次。
        runCatching {
            com.cardesk.launcher.vehicle.DriveModeAutoStart.apply(context, "桌面启动")
        }.onFailure { Logx.w(LogTags.VEHICLE, "桌面启动自动切换驾驶模式失败", it) }
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

        // 第九十三轮冷启动修复：**天气 / 定时刷新 / 导航引导这些「重活」不在构造函数里做**。
        //
        // 以前它们都塞在 init 里同步跑：WeatherDesk.start() 要联网+定位、
        // handler.post(ticker) 立刻开始每秒一次的车控/媒体刷新、
        // AmapDesktopGuide 要注册广播并向高德索要数据 ——
        // 主线程被串起来占住，MainActivity.onCreate 迟迟返回不了，首帧自然出不来，
        // 车机上表现为「开机黑屏一段时间才加载到桌面」。
        //
        // 现在改为：构造只建 UI 骨架（build()），重活由 MainActivity 在桌面
        // **画出第一帧之后**调用 startServices() 再做 —— 先出画面，数据随后补齐。

        // 导航引导数据源同样延迟到 startServices()（见上）。
    }

    /**
     * 启动桌面「重活」（第九十三轮冷启动修复）。
     *
     * ## 为什么拆出来
     *
     * 这些以前全在 [init] 里同步做：`build()` 建完页面立刻 `WeatherDesk.start()`
     * （联网 + 定位）、`handler.post(ticker)`（马上开始每秒一次的车控/媒体刷新）、
     * `AmapDesktopGuide`（注册广播 + 向高德索要数据）。主线程被串起来占住，
     * `MainActivity.onCreate` 迟迟返回不了，首帧出不来 —— 车机开机时就是
     * 「黑屏一段时间才加载到桌面」。
     *
     * 现在 `MainActivity` 先 `setContentView` 一个轻量启动页抢到第一帧，
     * 再构造 `LauncherView`；等它画出自己的第一帧后，才调这个方法把服务拉起来：
     * **画面先出，数据随后补齐**。
     *
     * 幂等：重复调用直接返回（`onResume`、窗口重建都可能再叫一次）。
     */
    fun startServices() {
        if (servicesStarted) return
        servicesStarted = true
        com.cardesk.launcher.core.StartupTrace.mark("servicesBegin")

        // 车机昼夜（真·光感）通道：AdaptAPI 的 `Device.getDayNightMode()`。
        // 「跟随光感」要靠它；车机服务没连上时内部会按 5 秒节流重试。
        runCatching {
            com.cardesk.launcher.vehicle.AdaptapiDayNight.listener = { night ->
                // 车机推来的明暗变了 → 立刻重算配色（不用等 1 秒的定时刷新）。
                // 优先级仍由 resolveNight 决定（跟随高德时高德信号优先）。
                Logx.i(LogTags.THEME, "车机光感变化", "night" to night)
                applyDayNightIfChanged()
            }
            com.cardesk.launcher.vehicle.AdaptapiDayNight.attach(context.applicationContext)
        }.onFailure { Logx.w(LogTags.THEME, "启动车机昼夜通道失败", it) }
        mark("svc:dayNight")

        // 天气：桌面起来就拉一次
        runCatching {
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
        }.onFailure { Logx.w(LogTags.WEATHER, "启动天气子系统失败", it) }
        mark("svc:weather")

        // 海拔：定位通道（异步；冷启动会先回一次上次落盘的值，所以先挂回调再 start）。
        // 变化量小于阈值不回调（见 AltitudeDesk.MIN_DELTA_M），页面不会每秒抖一次。
        runCatching {
            com.cardesk.launcher.core.AltitudeDesk.onChanged = { _ ->
                // 用无参 text()：回调传 null 表示「不再新鲜」，但此时仍要显示上次数值，
                // 不能把页面刷成 `--`（否则进地库就开始闪）。
                val alt = com.cardesk.launcher.core.AltitudeDesk.text()
                if (::scenePage.isInitialized) scenePage.altitudeText = alt
                vehiclePage?.altitudeText = alt
            }
            com.cardesk.launcher.core.AltitudeDesk.start(context.applicationContext)
            Logx.i(LogTags.ALT, "启动海拔采集")
        }.onFailure { Logx.w(LogTags.ALT, "启动海拔采集失败", it) }
        mark("svc:altitude")

        // 360 环影联动：订阅挡位 / 雷达图形信号，按「设置 → 360 环影」里的配置
        // 自动开环影并切视角（倒车 / R→D / D 挡 / 转向灯 / N·P 关闭 / 雷达触发）。
        // 放在 startServices 而不是 init：它要往车机注册属性监听，属于「重活」。
        runCatching {
            com.cardesk.launcher.vehicle.Camera360Link.start(context.applicationContext, prefs)
            Logx.i(LogTags.CAM360, "启动 360 联动")
        }.onFailure { Logx.w(LogTags.CAM360, "启动 360 联动失败", it) }
        mark("svc:camera360")

        // 车模资源包自动补齐：本地已齐 ⇒ 一个请求都不发；不齐（首次安装 / 数据被清）才去
        // 远端一次性把全部车型拉下来，失败每 5 分钟静默重试，装好后让解码缓存失效。
        runCatching {
            com.cardesk.launcher.core.car3d.CarModelAutoSync.onUpdated = { dirs ->
                Logx.i(LogTags.CAR3D, "车模资源包已补齐，解码缓存失效", "dirs" to dirs)
                // `reset()` 会回收旧位图、清掉 model.json 与缺图记忆，桌面下一帧就用新图。
                com.cardesk.launcher.core.car3d.Car3dAssets.reset()
                refresh()
            }
            com.cardesk.launcher.core.car3d.CarModelAutoSync.start(context.applicationContext)
            Logx.i(LogTags.CAR3D, "启动车模资源包自动补齐")
        }.onFailure { Logx.w(LogTags.CAR3D, "启动车模资源包自动补齐失败", it) }
        mark("svc:carmodel")

        // 第三百一十五轮：方控广播动态注册。
        //
        // 车机把方向盘按键做成**隐式广播**，API 28 起隐式广播不再投递给后台应用的
        // 清单接收器（系统直接打 `Background execution not allowed`）。桌面在导航/
        // 音乐里通常是后台态 ⇒ 只靠清单收不到，必须进程还活着时再动态注册一份。
        runCatching { com.cardesk.launcher.ui.OemKeyRegistry.start(context.applicationContext) }
            .onFailure { Logx.w(LogTags.KEYS, "方控广播动态注册失败", it) }
        mark("svc:oemkey")

        // 第三百一十七轮：Home 键双击 → 本项目最近任务。
        //
        // 两条入口共用 HomeKeyRuntime 的手势机：
        //   1) 车机 Input 通道拦截（HomeKeyInterceptor，能看到并吃掉框架之前的按键）；
        //   2) HOME intent 兜底（MainActivity.onNewIntent，系统已经把桌面拉起来）。
        // 出口放在这里，所以「回桌面 / 打开最近任务」永远作用在活着的桌面上。
        runCatching {
            com.cardesk.launcher.core.HomeKeyRuntime.onGoHome = {
                runCatching {
                    bringHomeToFront()
                    handleExternalHome()
                }.onFailure { Logx.w(LogTags.HOME, "Home 键回桌面失败", it) }
            }
            com.cardesk.launcher.core.HomeKeyRuntime.onShowRecents = {
                runCatching {
                    bringHomeToFront()
                    showRecents()
                }.onFailure { Logx.w(LogTags.HOME, "Home 键打开最近任务失败", it) }
            }
            val interceptor = com.cardesk.launcher.vehicle.HomeKeyInterceptor(
                context.applicationContext,
                prefs,
            )
            homeKeyInterceptor = interceptor
            interceptor.start()
        }.onFailure { Logx.w(LogTags.HOME, "Home 键接管启动失败", it) }
        mark("svc:homekey")

        // 导航引导数据源：没有导航时不显示任何东西（`visibility="gone"`）。
        runCatching {
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
                        applyNavGuide()
                    }

                    override fun onCruise() {
                        applyNavGuide()
                    }

                    override fun onTrafficLight(signal: com.cardesk.launcher.core.nav.AmapTrafficLight.Signal?) {
                        // 红绿灯是「最近路口」的瞬时信号：
                        // 有就画，没有就不画（不占位）。
                        // 收到新帧后立刻重画一次 —— 读秒要马上从车机给的值开始。
                        //
                        // 真正的信号状态放在 `AmapDesktopGuide` 里（那里判有效期、减读秒），
                        // 这里不另存一份，免得两处状态打架。
                        // 第九十九轮：巡航态没有 navGuideRaw，但巡航卡也要跟着红绿灯帧刷新。
                        applyNavGuide()
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

                    override fun onDayNight(nightUi: Boolean) {
                        // 高德把自己切成了白天/黑夜 → 桌面立刻跟上（别等 1 秒的定时刷新）。
                        // 同时把信号写进共享的 [NightResolver] ——
                        // 仪表歌词投射服务也用它，两边不会一个日一个夜（第一百三十七轮）。
                        if (amapDayNightSignal == nightUi) return
                        amapDayNightSignal = nightUi
                        com.cardesk.launcher.core.NightResolver.onAmapDayNight(nightUi)
                        Logx.i(LogTags.THEME, "高德日夜状态变化", "night" to nightUi)
                        applyDayNightIfChanged()
                    }
                },
            )
            // 窗口已经挂上（MainActivity 是先 addView 再等首帧）才需要主动 start；
            // 否则交给 onAttachedToWindow()。
            if (isAttachedToWindow) navGuideSource?.start()
        }.onFailure { Logx.w(LogTags.NAV_UI, "启动导航引导数据源失败", it) }
        mark("svc:navGuide")

        // 「向高德索要区域信息」**挪到 [startDeferredAmapActions]**（第112轮）——
        // 那个广播带 `FLAG_INCLUDE_STOPPED_PACKAGES`，注释里写得很清楚：
        // 「才能把高德顺带拉起来」—— 所以它是「唤起高德」的一条路径，
        // 不能在桌面还没启动完的时候就发。

        // 每秒刷新：首帧已经画出来了，现在开始跑定时刷新。
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        refresh()
        com.cardesk.launcher.core.StartupTrace.mark("servicesDone")

        // 第九十八轮：桌面起来后检测一次在线更新。
        // 失败静默；查到新版弹「立即更新 / 稍后」的询问框（同一版本点过稍后不再问）。
        handler.removeCallbacks(updateCheckRunnable)
        handler.postDelayed(
            updateCheckRunnable,
            com.cardesk.launcher.core.update.UpdateStartupCheck.CHECK_DELAY_MS,
        )
    }

    /**
     * 桌面**完全启动之后**才做的跨进程动作（第112轮）。
     *
     * ## 背景：桌面启动时为什么要唤起高德
     *
     * 桌面启动时我们做两件**会唤起高德**的事：
     *
     * 1. **画中画承载**（[refreshPip] → `ActivityEmbed.embed` → `startActivity`）
     *    —— 那就是**真的把高德这个 Activity 启动起来**（启动在 VirtualDisplay 上）；
     * 2. **向高德索要区域信息**
     *    （[com.cardesk.launcher.core.AmapCitySource.requestAreaInfo]）
     *    —— 那个广播带 `FLAG_INCLUDE_STOPPED_PACKAGES`，
     *    `AmapAreaInfo` 的注释里写得很直白：「才能把高德顺带拉起来」。
     *
     * 这两件事以前都在桌面**还没画完**的时候做（画中画在 `build()` 里，
     * 索要区域信息在 `startServices()` 里），于是：
     *
     * - 开机时**高德先跳出来**，盖在还在加载的桌面上；
     * - 高德进程启动（地图初始化、网络、定位）跟桌面自己的首帧抢 CPU。
     *
     * ## 现在怎么做
     *
     * 统一推到**启动页淡出之后**（`MainActivity` 的 `desktopReady` 那一刻）：
     * 桌面这时已经完全可见、自己的活干完了，才去动高德。
     *
     * 幂等：重复调用直接返回。
     */
    fun startDeferredAmapActions() {
        if (deferredAmapStarted) return
        deferredAmapStarted = true
        Logx.i(LogTags.APP, "桌面就绪后开始唤起高德相关动作")

        // —— 1) 画中画承载（= 启动高德 Activity）——
        //
        // 走闸门：正常路径（桌面完全就绪）立刻开始；
        // 如果这条链断了，兜底看门狗会在首帧之后 3 秒接手。
        pipHostingGate.markDesktopReady()
        startPipHosting("桌面就绪")

        // —— 2) 向高德索要区域信息 ——
        //
        // 高德收到后会去向车机（`ecarx.naviservice`）要区域信息，
        // 车机回包后高德把城市**全局广播**出来 ——
        // 真机上系统定位长期没有位置时，这是我们唯一的坐标来源入口。
        // 已经有城市（本次进程拿到过 / 上次落盘过）就不再重复要。
        runCatching {
            if (com.cardesk.launcher.core.AmapCitySource.broadcastCity.isNullOrBlank() &&
                prefs.weatherCity.isBlank()
            ) {
                Logx.i(LogTags.WEATHER, "桌面就绪后向高德索要区域信息")
                com.cardesk.launcher.core.AmapCitySource.requestAreaInfo(context.applicationContext)
            }
        }.onFailure { Logx.w(LogTags.WEATHER, "向高德索要区域信息失败", it) }
    }

    // ------------------------------------------------------------------ 画中画承载的闸门（第116轮）

    /**
     * 桌面首帧画出来了（第116轮）。
     *
     * 由 `MainActivity.onLauncherFirstFrame()` 调用。
     *
     * ## 它解决什么
     *
     * 画中画承载的触发链是
     * `MainActivity.onDesktopReady()` → [startDeferredAmapActions]。
     * 第112轮到第115轮之间，这条链**静默断过一次**：
     * 启动页淡出时在**已脱离窗口**的视图上 `post`，
     * 那个 Runnable 永远不会执行 → 承载永远不开始 →
     * 用户看到「导航页画中画不显示应用」，而且没有任何报错。
     *
     * 光修那一行不够 —— 下次换个地方再断一次还是得靠用户报障。
     * 所以这里记下「桌面第一帧已经上屏」的时刻，
     * 兜底看门狗从这一刻开始计时（见 [pipHostingWatchdog]）：
     * 3 秒内没等到 desktopReady，就自己把承载启动起来。
     */
    fun notifyFirstFrame() {
        pipHostingGate.markLauncherReady(SystemClock.uptimeMillis())
        handler.removeCallbacks(pipHostingWatchdog)
        handler.postDelayed(
            pipHostingWatchdog,
            com.cardesk.launcher.core.DesktopReadyGate.DEFAULT_WATCHDOG_MS,
        )
        // 第191轮：首帧之后的**空闲补建**。
        //
        // 抽屉页这种「启动时不需要、但很快就会用到」的重 View 在这里补上，
        // 用户点「应用」键时就不会有延迟。
        //
        // 用 `post` 而不是直接调：此刻主线程正在收尾这一帧，
        // 直接调会把这一帧拖长（首帧本来就贵）。
        handler.removeCallbacks(deferredUiWarmup)
        handler.post(deferredUiWarmup)
    }

    /**
     * 空闲补建（延迟到首帧之后做，见 [notifyFirstFrame]）。
     *
     * 独立成字段是为了能 `removeCallbacks` 去重。
     */
    private val deferredUiWarmup = Runnable { warmupDeferredUi() }

    /**
     * 兜底看门狗：`desktopReady` 迟迟不来时，自己开始承载。
     *
     * 正常情况下它什么也不做（承载早就开始了，闸门的幂等锁会挡住）。
     * 只有「就绪链断了」时它才真正接管 —— 这正是第116轮要防的静默失效。
     */
    private val pipHostingWatchdog = Runnable {
        val reason = "兜底看门狗（桌面就绪未到达）"
        if (startPipHosting(reason)) {
            Logx.w(
                LogTags.APP,
                "画中画承载由兜底看门狗启动",
                "waitMs" to com.cardesk.launcher.core.DesktopReadyGate.DEFAULT_WATCHDOG_MS,
            )
        }
    }

    /**
     * 开始承载画中画（幂等）。
     *
     * @param reason 触发原因（只进日志，便于事后对账：
     *               「桌面就绪」= 正常路径，「兜底看门狗…」= 就绪链断了）。
     * @return true 表示这一次真的开始了。
     */
    private fun startPipHosting(reason: String): Boolean {
        if (!pipHostingGate.consume(SystemClock.uptimeMillis())) return false
        Logx.i(LogTags.APP, "开始承载画中画", "reason" to reason)
        refreshPip()
        return true
    }

    /**
     * 画中画承载是否已经开始（诊断 / 单测用）。
     *
     * 第116轮新增：以前这个状态是私有的布尔，
     * 外面既看不到、也测不了 —— 「画中画不显示」这种问题在车上只能靠日志猜。
     */
    fun isPipHostingStarted(): Boolean = pipHostingGate.isStarted()

    // ------------------------------------------------------------------ 启动更新检测（第九十八轮）

    private val updateCheckRunnable = Runnable { checkUpdateOnStartup() }

    private fun checkUpdateOnStartup() {
        // 用户手上已经有别的弹窗（设置/空调/驾驶模式…）时先不打扰，等下次
        if (overlayDialog != null) return
        com.cardesk.launcher.core.update.UpdateStartupCheck.maybeCheck(
            context.applicationContext,
            prefs,
        ) { release -> showUpdateAvailable(release) }
    }

    private fun showUpdateAvailable(release: com.cardesk.launcher.core.update.R2Release) {
        if (overlayDialog != null) return
        val dialog = com.cardesk.launcher.ui.dialogs.UpdateAvailableDialog(context, pal, release)
        dialog.onUpdate = {
            closeOverlayDialog(dialog)
            Logx.event(LogTags.SETTING, "启动更新：用户选择立即更新", "version" to release.version)
            val update = com.cardesk.launcher.ui.dialogs.UpdateDialog(
                context,
                pal,
                autoDownload = release,
                channel = prefs.updateChannel,
            )
            update.onClose = { closeOverlayDialog(update) }
            showOverlayDialog(update)
        }
        dialog.onLater = {
            prefs.updateSkippedVersionCode = release.versionCode
            Logx.event(LogTags.SETTING, "启动更新：用户选择稍后", "versionCode" to release.versionCode)
            closeOverlayDialog(dialog)
        }
        showOverlayDialog(dialog)
    }

    /**
     * 把当前引导数据刷到首页引导卡。
     *
     * 生效的车道线 = 引导数据自带的（非空优先），否则用最近一次单独收到的车道线
     * —— 与 `paintGuideLanes(guide.lanes.isNotEmpty() ? guide.lanes: lastGuideLanes)` 一致。
     */
    private fun applyNavGuide() {
        // 第 100 轮：先看 AmapDesktopGuide 当前的导航/巡航判定。
        // 导航卡只在「确实有导航帧」时显示；否则一律走巡航卡，
        // 避免导航结束后 navGuideRaw 还留着旧数据、把手动巡航时该显示的巡航卡挡住。
        val navigating = navGuideSource?.isNavigating() == true
        val guide = navGuideRaw
        if (navigating && guide != null) {
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
            return
        }
        // 第九十九轮：没有导航时，如果高德在巡航（收到了巡航红绿灯帧），
        // 首页也显示一张「巡航卡」：当前道路 + 车道线 + 多方向红绿灯。
        val cruise = navGuideSource?.cruise()
        scenePage.setGuide(
            if (cruise == null) {
                null
            } else {
                com.cardesk.launcher.core.nav.NavGuideModel.buildCruise(
                    icon = cruise.icon,
                    road = cruise.road,
                    lanesRaw = cruise.lanes,
                    lights = cruise.lights,
                )
            },
        )
    }

    // --------------------------------------------------------------- 构建 / 换肤

    private fun build() {
        mark("build:enter")
        applyDesktopBackground(pal.bgTop)
        background.palette = pal
        background.wallpaper = prefs.wallpaper
        // 自定义壁纸（第 105 轮）：有照片且选中时优先显示；位图异步解，先出底色。
        background.custom = prefs.wallpaperCustom &&
            com.cardesk.launcher.core.CustomWallpaper.has(context)
        background.customBitmap =
            if (background.custom) com.cardesk.launcher.core.CustomWallpaper.cachedBitmap() else null
        if (background.custom) ensureCustomWallpaperLoaded()
        // 第207轮：**视频壁纸**（导入的视频）。
        //
        // 视频壁纸要等 TextureView 的 SurfaceTexture 准备好才能播，
        // 所以这里只是把状态摆好，真正的播放交给 [startVideoWallpaper]。
        if (prefs.videoWallpaperEnabled && com.cardesk.launcher.core.VideoWallpaper.has(context)) {
            // TextureView 的 Surface 要等布局完才有 → 延后一拍再起播。
            post { startVideoWallpaper() }
        }
        // 轮播心跳（第207轮）：每秒查一次「够不够时间切」。
        //
        // 第211轮：**post 前先 removeCallbacks** ——
        // `build()` 在换肤 / 改底栏形态时会重跑，不去重就会一条条叠起来，
        // 1 秒检查会跑 N 份。
        wallpaperSwitchedAtMs = android.os.SystemClock.uptimeMillis()
        handler.removeCallbacks(carouselTicker)
        handler.postDelayed(carouselTicker, 1000L)
        // 壁纸被拖拽切到上一张 / 下一张时落盘
        background.onWallpaperChanged = { index ->
            prefs.wallpaper = index
            scenePage.showWallpaperHint()
        }
        // 第222轮：**壁纸亮度变了 → 重算浮层墨色**（状态栏 / 天气块 / 中心歌词
        // 这些直接压在壁纸上的元素，颜色跟着壁纸明暗走）。
        background.onBackdropChanged = { applyWallpaperBackdrop() }
        // 第三百零四轮：玻璃底图改为**后台线程**模糊，算完要显式把画玻璃的层标脏。
        background.onGlassChanged = { invalidateGlassLayers() }
        // 第139轮：换肤重建时，旧的画中画承载面**不拆** —— 先「摘」下来，交给新的承载面接着用。
        //
        // 第十二轮的修法是「重建前先 `release` 旧承载面」，它解决的是两件事：
        // 1. 旧的 `ActivityView` + `VirtualDisplay` 泄漏，反复切日夜会攒出一堆残留 task；
        // 2. 新承载面黑屏 —— 高德的 task 还挂在旧 VirtualDisplay 上，
        //    新承载面用同一套 `NEW_TASK | RESET_TASK_IF_NEEDED` 旗标再启动一次高德时，
        //    系统会把**旧 task 拉到前台**（旧 task 在旧 display 上）→ 新承载面只剩一块黑。
        //
        // 但 `release` 的代价是：**它会销毁旧的 `VirtualDisplay`**，
        // 而挂在它上面的承载应用 task 会被系统**搬到主显示器**
        // （Android 9 的 `ActivityStackManager` 就是这么处理的；
        // AOSP 的 VirtualDisplay Launcher 示例也是靠 `moveTaskToDisplay`
        // 把 task 从默认显示器搬回虚拟显示器）。
        //
        // → 高德的 task 被搬到 display 0 → **高德从画中画变成全屏抢屏**
        // （用户第139轮报的现象），画中画那边只剩一块黑。
        //
        // 现在改成「**摘走 → 释放外壳 → 交给新承载面**」：
        // `VirtualDisplay` 全程不死，高德的 task 一直在我们的 display 上，
        // 既不会被搬到主屏抢屏，也不会被重启（正在导航的路线不中断）。
        //
        // 处置方式由 [com.cardesk.launcher.core.PipRebuildPolicy] 定（纯逻辑，可单测）。
        //
        // **顺序不能反**：必须先「摘」再「释放」——
        // 先释放的话承载内容就没了，摘不出来，高德会被重启一遍（老毛病）。
        val carried = carryOutPipSurfaces()
        releasePipSurfaces("重建前")
        content.removeAllViews()

        // 这一轮把 content 整个重建了，上面挂着的浮层（设置 / 空调 / 抽屉 / 槽位编辑 /
        // 最近任务…也一并被摘掉了 —— 引用必须一起清掉。
        //
        // 不清的话：切日夜主题（`onSettingsApplied` → `applyTheme` → `build`）之后，
        // `settings` 还指着那个已经被摘掉的视图， 的
        // `if (settings != null) return` 会让**设置页再也打不开**。
        // ⚠️ 这里必须先「结束全局 Dock 的让位」再清引用：
        // 设置页会让全局 Dock 窗口隐藏（`setSuppressed(true)`），而 `settings = null`
        // 只是把引用丢掉、没有任何路径再去恢复它 —— 全局 Dock 会永远停在
        // 「窗口在、却 NO_SURFACE」，整屏底部一条 Dock 都没有。
        // 实测（第245轮）：切一次日夜 → `applyTheme()` → `build()` → 桌面 Dock 消失、
        // 全局 Dock 也不出，只有重开一次设置再关掉才恢复。
        if (settings != null) {
            com.cardesk.launcher.core.dock.GlobalDockService.setSuppressed(false)
        }
        settings = null
        climate = null
        hvac = null
        navSlotDialog = null
        taskList = null
        taskEditor = null
        // 退场中的浮层也被这一轮摘掉了 —— 登记一并清空，
        // 不然这些视图就再也摘不掉第二次了。
        closingOverlays.clear()

        // 页面层（满屏，含状态栏/底栏区域，activity_main 的各个 canvas）
        pageHost = FrameLayout(context)
        content.addView(pageHost, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        val wallpaper = { index: Int ->
            applyWallpaper(index)
        }
        scenePage = ScenePage(context).apply {
            // 第117轮：首页中央歌词的**布局**跟着偏好走。
            // 在这里就设好（而不是等 refresh 的第一轮），免得开机头一秒
            // 还画着默认的三行、过一会儿才跳成用户选的那一种。
            lyricLayout = com.cardesk.launcher.core.media.LyricLayout.fromKey(prefs.sceneLyricLayout)
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
            // 第207轮：**首页音乐卡也加收藏按钮**（原来只有导航页组合卡有）。
            //
            // 与导航页同一个数据源、同一个下发通道（`MusicController.toggleFavorite`），
            // 所以两处的收藏态永远一致，点哪边都是同一个效果。
            onFavoriteToggle = {
                val ok = MusicController.get().toggleFavorite()
                if (!ok) toast("当前播放器不支持收藏")
                refresh()
            }
            onSeatHeat = { VehicleController.cycleSeat(HvacIds.ZONE_DRIVER, true) }
            onSeatVent = { VehicleController.cycleSeat(HvacIds.ZONE_DRIVER, false) }
            onMaxFan = { VehicleController.toggleMaxFan() }
            onOpenHvac = { showClimate() }
            onOpen360 = { showCam() }
            onWallpaperDot = wallpaper
            // 首页：弹层在媒体卡**上方**（用户第10条）。
            onOpenSourceMenu = {
                openSourceMenu(scenePage.musicCardRect, SourceMenuLayer.Placement.ABOVE)
            }
            // 底部卡片布局（顺序 + 尺寸 + 显隐）的持久化（键 scene_card_layout_v2）
            layoutStore = com.cardesk.launcher.core.CardLayout.PrefsStore(prefs)
            // 第111轮：右侧固定「车况卡」的显隐（键 scene_car_status_card_visible）。
            // 场景页自己默认是 true（= 升级前的现状），这里按偏好覆盖，
            // 免得 build() 之后到 refresh() 之前闪一下。
            showCarStatusCard = prefs.carStatusCardVisible
            // 第166轮 C 版：车况卡整卡点击 → 车辆状态面板。
            onOpenVehicleStatus = { openVehiclePage() }
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
        mark("build:scenePage")
        navPage = NavPage(context, pal).apply {
            // 双画 / 全屏态同步
            syncPipState(prefs.dualPip, pipFullscreen)
            onPlayToggle = { MusicController.get().playPause(); refresh() }
            onPrev = { MusicController.get().previous() }
            onNext = { MusicController.get().next() }
            // 组合页：弹层**盖在音乐卡上**（用户第10条）。
            onSource = { openSourceMenu(navPage.musicCardRect, SourceMenuLayer.Placement.OVER) }
            // 第195轮：**收藏** / **播放模式**两个按钮。
            //
            // 下发失败（播放器不支持 / 通道不通）时返回 false —— 这时**不**改界面，
            // 免得出现「界面显示已收藏，播放器其实没收藏」的错位。
            onFavoriteToggle = {
                val ok = MusicController.get().toggleFavorite()
                if (!ok) toast("当前播放器不支持收藏")
                refresh()
            }
            onPlayModeToggle = {
                val ok = MusicController.get().cyclePlayMode()
                if (!ok) toast("当前播放器不支持切换播放模式")
                refresh()
            }
            // 第148轮：**点音乐卡上的封面图** = 切换「歌曲图片当卡片背景」。
            //
            // 桌面不是普通 Activity，点完必须立刻给一句话反馈 ——
            // 关掉背景后卡片只是变成纯色，不说的话用户会以为「点了没反应」。
            onCoverBackdropToggled = { enabled ->
                toast(if (enabled) "封面背景已开启" else "封面背景已关闭")
            }
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
        // 第139轮：把换肤前**摘出来**的承载面交给新的承载面。
        //
        // 这一步做完，承载应用（高德）的 task 一直挂在**原来那个 VirtualDisplay** 上
        // —— 它既不会被搬到主屏抢屏，也不会被重启一遍。
        // 交给新承载面之后，末尾的 `refreshPip()` 会看到「这个包名已经承载着」→ 直接复用，
        // 不会再走一遍「新建 VirtualDisplay + 重新启动应用」。
        carried.main?.let { carried ->
            navPage.pip.adoptContent(carried.view, carried.pkg, carried.host)
        }
        carried.secondary?.let { carried ->
            navPage.pipSecondary.adoptContent(carried.view, carried.pkg, carried.host)
        }
        // 抽屉是**浮层**，不是整页
        mark("build:navPage")
        // 第191轮：抽屉页**按需构造**（见 [ensureDrawerPage]）。
        //
        // 它只在用户点 Dock「应用」键时才需要 —— 启动这条最关键的路上
        // 不该背着一整页的 View 树（真机上光构造它就要 1 秒以上：
        // 抽屉页自己会建二十来个 View + 形状 drawable，还要过一遍 Prefs）。
        //
        // 换肤重建时如果它**已经存在**，用新配色重建一份 ——
        // 老实例已经被 `content.removeAllViews()` 摘掉了，重建是对的。
        if (::drawerPage.isInitialized) {
            drawerPage = createDrawerPage()
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
        mark("build:drawerPage")
        statusBar = ShellStatusBar(context).apply {
            onAvatar = { showSettings() }
            // 第九十五轮补丁：「驾驶模式 · xx」胶囊 → 驾驶模式页（以前误绑到空调页）。
            onAssist = { showDriveMode() }
        }
        content.addView(statusBar, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        // 底部一体化控制栏：铺满屏宽×112，贴底（底边距 0）
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
            onSplitLongPress = { toggleDualPip() }
            // 媒体卡左右滑动切歌
            onMediaNext = { MusicController.get().next(); refresh() }
            onMediaPrev = { MusicController.get().previous(); refresh() }
            on360 = { showCam() }
            // 第197轮：点媒体卡的封面 / 歌名区 → 拉起**当前正在使用的**音乐 App。
            //
            // 原来这里是 `onMediaTap = { showPage(PAGE_NAV) }`（跳我们自己的第二页音乐卡），
            // 用户要的是「打开那个正在出声的 App」，所以语义换成了拉起 App。
            // 想回自己的音乐卡可以长按（媒体浮窗）或从页面指示器进第二页。
            onMediaOpenApp = { openCurrentMusicApp() }
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
        mark("build:dock")

        // 媒体来源菜单（musicSourceMenu/sceneSourceMenu）：放在最上层，遮罩可点空白收起
        sourceMenu = SourceMenuLayer(context, pal).apply {
            onPick = { id -> applySource(id) }
            onToggle = { open -> navPage.setSourceMenuOpen(open) }
        }
        content.addView(sourceMenu, Ui.frame(Design.W.toInt(), Design.H.toInt()))

        // 第十二轮：画中画原来在**全屏态**的话，重建后要恢复。
        //
        // 顺序不能反：
        // - 先按普通态 `showPage` → `refreshPip()` 才会去承载应用
        //   （`refreshPip` 在 `pipFullscreen` 为真时是直接 return 的）；
        // - 再把它提到最上层（`enterPipFullscreen`）。
        val pipWasFullscreen = pipFullscreen
        pipFullscreen = false
        showPage(page)
        if (pipWasFullscreen) {
            pipFullscreen = true
            enterPipFullscreen()
        }
        mark("build:showPage")
        refresh()
        mark("build:refresh")
        // 第五十六轮：启动就把「沉浸系统导航栏」写进 `policy_control` ——
        // 否则系统自带 Dock 平时还是那个状态，点桌面 Dock 就会把它唤出来。
        applyOemPolicy()
        mark("build:oemPolicy")
        // 第九十三轮：UI 骨架构建完成（启动分段计时，见 core/StartupTrace）。
        com.cardesk.launcher.core.StartupTrace.mark("uiBuilt")
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
     * 窗口**实际**是不是半透明（第228轮）。
     *
     * ## 为什么不看 prefs
     *
     * prefs 说的是「**应该**透明」（视频壁纸开 + SurfaceView 后端），
     * 窗口**实际**的 format 是另一回事 ——
     * 视频壁纸的开关会在**运行中**改窗口 format（`applyVideoWindowFormat`），
     * 而承载面是「同包名复用」的 ⇒ 判据一变，承载面还挂着旧路线的内容。
     *
     * 真车实测（第228轮）：关掉视频壁纸 ⇒ 窗口不透明，
     * 但画中画还是镜像路线 ⇒ 全屏右侧缺一块、拖动很卡
     * ⇒ 用户反馈「以前不用视频壁纸一切正常」。
     *
     * ⇒ 路线判据只能问窗口本身。
     */
    private fun isWindowTranslucent(): Boolean {
        val win = (context as? android.app.Activity)?.window ?: return false
        val fmt = win.attributes?.format ?: return false
        // `PixelFormat.TRANSLUCENT` 与 `PixelFormat.TRANSPARENT` 是同一个值（-3）。
        return fmt == android.graphics.PixelFormat.TRANSLUCENT ||
            fmt == android.graphics.PixelFormat.TRANSPARENT
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
        // 第112轮：桌面启动时**不要**唤起高德。
        //
        // 承载画中画 = `ActivityEmbed.embed` → `startActivity` = 真的启动高德。
        // 以前这一步在 `build()` 里（桌面还在搭骨架）就做了，
        // 结果开机时高德先跳出来，跟桌面首帧抢 CPU / 抢焦点。
        // 现在推迟到桌面完全启动之后（[startDeferredAmapActions]）。
        //
        // 第116轮：这个闸门改由 [pipHostingGate] 判 ——
        // 除了「桌面就绪」这条正常路径，还多了一条兜底：
        // 桌面首帧之后 3 秒还没等到就绪信号，就自己开始承载。
        // （之前这里只有正常路径，而那条路径曾经静默断过 → 画中画永远停在占位。）
        if (!pipHostingGate.isStarted()) return
        // 第226/228轮：先告诉承载面「窗口是不是半透明的」，它据此选渲染路线。
        //
        // 半透明 = 视频壁纸走 SurfaceView（硬件图层在窗口之下，必须挖洞透出视频）。
        // 这种情况下承载层的图层不再被窗口裁剪（圆角失效/错位/切页残留），
        // 承载面会改走「自建虚拟屏 + TextureView 镜像」（见 core/PipRenderPlan.kt）。
        //
        // 第228轮：**按窗口的实际 format 判**，不再看 prefs ——
        //
        // prefs 说的是「**应该**透明」，窗口**实际**是不是透明是另一回事：
        // 视频壁纸的开关会**在运行中**改窗口 format（`applyVideoWindowFormat`），
        // 而 prefs 与窗口 format 可能短暂对不上。
        // 真车实测（第228轮）：用户在设置里关掉视频壁纸 ⇒ 窗口变不透明，
        // 但承载面还挂着**镜像路线**的内容 ⇒ 全屏 / 拖动跟「不用视频壁纸」时不一样
        // ⇒ 用户反馈「以前不用视频壁纸一切正常，现在有问题了」。
        //
        // ⇒ 判据必须是**窗口实际的 format**（唯一的真相）。
        val translucent = isWindowTranslucent()
        navPage.pip.translucentWindow = translucent
        navPage.pipSecondary.translucentWindow = translucent
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
            // 第225轮：切到**非导航页**时把承载层藏起来（不拆）。
            // 导航页被置 GONE 之后，承载层的 SurfaceView 图层仍会被合成，
            // 首页/空调页那个位置就残留一块黑卡（实测 22:17）。
            navPage.pip.setContentVisible(page == PAGE_NAV)
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
            else -> {
                navPage.pipSecondary.show(subPkg)
                // 第225轮：与主画一样，非导航页时藏起承载层（不拆）
                navPage.pipSecondary.setContentVisible(page == PAGE_NAV)
            }
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
        enterPipFullscreen()
    }

    /**
     * 进入画中画全屏态：把承载面提到最上层并铺满设计画布。
     *
     * 第十二轮：单独拆出来，是因为**换肤重建**（[build]）后也要走这一步
     * —— 重建时承载面被换成了新的，不重新提到最层就会掉回导航页的地图卡里。
     */
    private fun enterPipFullscreen() {
        // 全局 Dock（另一个窗口）也得知道进全屏了 —— 它的主页键靠这个判「退出全屏」。
        // 放在这里而不是调用点：换肤重建那条路也走这个函数。
        GlobalDockService.onPipFullscreen(true)
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
            // 第201轮：高度只铺到 Dock 顶边（968），不再用整块 1080 画布 ——
            // 否则底部 112px 正好压在 Dock 底下，高德全屏时那一截永远看不到
            // （用户反馈「切全屏没避让 Dock」）。
            Ui.frame(Design.W.toInt(), DOCK_TOP_Y, Gravity.TOP or Gravity.START),
        )
        content.bringChildToFront(navPage.pip)
        // 玻璃条一起提到最上层 —— 全屏时它就是「还原」按钮
        // （全屏时 navSplit 可见、点击还原）。
        // 画中画的 ActivityView 会把返回键转发给承载应用，
        // 所以全屏必须有个**看得见的**还原入口。
        navPage.detachNavSplit()?.let { split ->
            navSplitInFullscreen = split
            // 宽 / 高 / gravity 与「悬浮在画中画上」那态完全一致（第129轮）——
            // 两态取同一份 [NavSplitPlacement]，用户点全屏后按钮不会跑位。
            content.addView(
                split,
                Ui.frame(
                    NavSplitPlacement.WIDTH,
                    NavSplitPlacement.HEIGHT,
                    NavSplitPlacement.gravity(fullscreen = true),
                ),
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
     *
     * @param targetPage 还原后落在哪一页。默认导航页（分隔条「还原」和返回键
     *   都该回画中画那一页）；切页动作会把目标页带进来，
     *   免得「先还原再切页」变成两次动作、用户看到中间态。
     */
    fun exitPipFullscreen(targetPage: Int = PAGE_NAV) {
        pipFullscreen = false
        // 全局 Dock（另一个窗口）也得知道全屏态没了 —— 它的主页键靠这个判「退出全屏」。
        GlobalDockService.onPipFullscreen(false)
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
        showPage(targetPage)
        refreshPip()
        // 尺寸从「全屏」变回「分屏」后，重新给承载应用发一次启动 ——
        // 否则应用会按全屏尺寸绘制（被放大裁切），或者地图图层直接黑掉。
        // （resize 稳定后重新加载）。
        navPage.pip.relaunchAfterResize()
    }

    /**
     * 画中画全屏态是个**盖在根容器最上层**的层（[enterPipFullscreen] 把它
     * `bringChildToFront` 到 1920×1080 的设计画布）。
     *
     * ## 为什么需要一个统一的「先还原」闸口
     *
     * 全屏时它把整个屏幕盖住 —— 这时候切页、开抽屉、开最近任务，
     * **用户眼前一个像素都不会变**：页面索引变了、浮层也开了，
     * 只是全屏层还盖在上面。真机表现就是「Dock 切屏按钮点了没反应」
     * （诊断包 11-16 11:25~11:37 有 22 次切页全部无视觉反馈）。
     *
     * 所以凡是「要让别的东西露出来」的动作，都必须先过这个闸口。
     * 之前只有 `goHome()` 和返回键会还原（[exitPipFullscreen]），
     * 切页 / 抽屉 / 最近任务都不管 —— 这就是漏网的那几条路。
     *
     * @return true = 确实做过还原（调用方不要再重复处理）
     */
    private fun exitPipFullscreenIfNeeded(targetPage: Int): Boolean {
        if (!pipFullscreen) return false
        Logx.event(
            LogTags.NAV_UI,
            "动作前还原画中画全屏",
            "to" to targetPage,
            "pkg" to prefs.pipApp,
        )
        exitPipFullscreen(targetPage)
        return true
    }

    /** 全屏时提到根容器上的玻璃条（还原时放回导航页）。 */
    private var navSplitInFullscreen: android.view.View? = null

    /** 当前是不是画中画全屏态（供 Activity 拦截返回键用）。 */
    fun isPipFullscreen(): Boolean = pipFullscreen

    // ------------------------------------------------------- 全局 Dock（第八十七轮）

    /**
     * 全局 Dock 把用户拉回桌面后，执行对应动作。
     *
     * ## 为什么动作要「回桌面再做」
     *
     * 全局窗口（[com.cardesk.launcher.core.dock.GlobalDockService]）只负责画 Dock，
     * 抽屉 / 空调浮层 / 分屏页都属于本 `LauncherView`。在别的应用前台时直接把浮层
     * 开在桌面窗口里，用户看到的会是「一回到桌面就凭空多了一层」；先回前台再执行，
     * 动画和层级才是用户预期的顺序。
     *
     * 动作来自 `MainActivity.EXTRA_ACTION`（见 `MainActivity.onNewIntent`），
     * 取值与契约一致：`drawer` / `apps` / `climate` / `split` / `split_long` /
     * `camera360` / `media`。
     */
    fun handleGlobalDockAction(action: String) {
        Logx.event(LogTags.DOCK, "全局 Dock 回桌面动作", "action" to action)
        when (action) {
            com.cardesk.launcher.MainActivity.ACTION_DRAWER,
            com.cardesk.launcher.MainActivity.ACTION_APPS -> {
                // 抽屉是「开关」语义：先收掉再开，避免回到桌面时它已经开着、
                // 这里又当成「打开」空转一次（用户会以为点了没反应）。
                if (drawerShowing()) closeDrawer()
                showDrawer()
            }
            com.cardesk.launcher.MainActivity.ACTION_CLIMATE -> showClimate()
            com.cardesk.launcher.MainActivity.ACTION_MEDIA -> showPage(PAGE_NAV)
            com.cardesk.launcher.MainActivity.ACTION_SPLIT -> showPage(nextPage(page))
            com.cardesk.launcher.MainActivity.ACTION_SPLIT_LONG -> toggleDualPip()
            com.cardesk.launcher.MainActivity.ACTION_CAMERA360 -> showCam()
            com.cardesk.launcher.MainActivity.ACTION_RECENTS -> showRecents()
            else -> Logx.w(LogTags.DOCK, "未知的全局 Dock 动作", "action" to action)
        }
    }

    /**
     * 桌面回到前台时重算 policy。
     *
     * `NavInsets.applied()` 是 prefs 里的状态，用户可能刚在系统设置里授了悬浮窗、
     * 刚启用/停用 RRO，或者刚在设置页开关了全局 Dock —— 这些都不经过本页面，
     * 所以在 `MainActivity.onResume` 调这里按状态签名补齐。
     *
     * 只有签名变化才重写：`policy_control` 是写系统设置，每次 resume 都写会拖慢
     * 首页恢复（真车上是几百毫秒级的）。
     */
    fun syncGlobalDockPolicy() {
        val globalDock = prefs.globalDockEnabled
        val navInsets = runCatching {
            com.cardesk.launcher.core.dock.NavInsets.applied(context)
        }.getOrDefault(false)
        // 第九十四轮：全局 Dock 默认开启后，悬浮窗权限的有无也会改变 policy 分支，
        // 用户可能在系统设置里补授权限 —— 签名里必须带上它，否则桌面回前台不会重写。
        val overlay = runCatching {
            android.provider.Settings.canDrawOverlays(context)
        }.getOrDefault(false)
        val signature = policySignature(globalDock, navInsets, overlay)
        if (signature == lastPolicySignature) return
        Logx.i(
            LogTags.SYS,
            "全局 Dock 策略状态变化，重写 policy",
            "from" to lastPolicySignature,
            "to" to signature,
        )
        applyOemPolicy()
    }

    private fun policySignature(globalDock: Boolean, navInsets: Boolean, overlay: Boolean): String =
        "global=$globalDock/navInsets=$navInsets/overlay=$overlay"

    /** 下一个页面（：取模循环）。 */
    private fun nextPage(from: Int): Int = PageGesture.wrapIndex(from, 1, PAGE_COUNT)

    /** 上一个页面（取模循环）。 */
    private fun prevPage(from: Int): Int = PageGesture.wrapIndex(from, -1, PAGE_COUNT)

    /**
     * 单画 / 双画切换（Dock 分屏键长按、全局 Dock 的 `split_long` 动作）。
     *
     * ## 为什么主副画不能是同一个应用
     *
     * 同一个应用不能同时挂到两个 VirtualDisplay —— 副画再 new 一个承载面时，
     * `startActivity` 会把应用的 task 从**主画**拉到副画，主画当场黑屏
     * （模拟器已复现）。所以这里必须先拦。
     *
     * 第八十七轮把这段逻辑从 Dock 回调里抽成函数：全局 Dock 的窗口在别的进程
     * 场景下也会触发同一个动作（`MainActivity.EXTRA_ACTION = "split_long"`），
     * 两处必须走**同一份**判断，否则「桌面里点会拦、全局 Dock 点不拦」。
     */
    private fun toggleDualPip() {
        val nextDual = !prefs.dualPip
        val sameApp = nextDual && prefs.pipApp.isNotBlank() && prefs.pipApp == prefs.pip2App
        if (sameApp) {
            toast("双画中画请选择不同应用")
            Logx.w(
                LogTags.NAV_UI,
                "开启双画中画被拒：主副画是同一个应用",
                "pkg" to prefs.pipApp,
            )
            return
        }
        prefs.dualPip = nextDual
        navPage.syncPipState(prefs.dualPip, pipFullscreen)
        // 双画开关会决定副画承载面建不建（MainActivity 的 applyDesktopLayout）
        refreshPip()
        toast(if (prefs.dualPip) "已切换双画中画" else "已切换单画中画")
        if (!nextDual) reclaimHomeAfterPipCollapse()
    }

    /**
     * 双画 → 单画之后，把桌面重新拉回前台。
     *
     * ## 为什么需要这一步（终验 B-2）
     *
     * 收起副画 = 拆掉它那块 VirtualDisplay（`PipSurface.hide()` 里 release）。
     * 被托管的 task 一旦失去 display，系统会把它**迁回默认屏并抬到前台** ——
     * 实测：双画里副画是日历，长按分屏键切回单画后，日历整屏盖住桌面，
     * 只剩系统状态栏和全局 Dock，用户必须按 HOME 才回得去。
     *
     * 桌面是 HOME，收起画中画之后前台本来就该是桌面，所以这里在迁移完成后
     * 再要一次前台。**为什么要延迟**：task 迁移是 AMS 异步做的，
     * 立刻 `startActivity` 会在迁移前抢到前台、随后又被日历顶掉。
     */
    private fun reclaimHomeAfterPipCollapse() {
        handler.postDelayed({
            if (HomeForeground.isForeground) return@postDelayed
            Logx.event(LogTags.NAV_UI, "收起副画后要回桌面")
            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }
            runCatching { context.startActivity(intent) }
        }, PIP_COLLAPSE_RECLAIM_MS)
    }

    // --------------------------------------------------------------- 抽屉浮层

    /**
     * 抽屉页（`DrawerPage`）—— **按需构造**（第191轮）。
     *
     * ## 为什么不在 `build()` 里建
     *
     * 抽屉页是**浮层**：只有用户点 Dock「应用」键才会出现。
     * 但它自己要建二十来个 View + 一堆形状 drawable，还要过一遍 Prefs ——
     * 真机（领克 dcy11）上光构造就 1 秒以上，
     * 而启动这条路上每一秒都是用户盯着启动页的时间。
     *
     * 所以：启动时不建 → 桌面第一帧更快出来；
     * 第一帧之后由 [warmupDeferredUi] 在**空闲时**补建，
     * 用户真去点「应用」键时它已经就绪，不会有延迟。
     *
     * 兜底：万一用户比 [warmupDeferredUi] 更快，[showDrawer] 里当场建。
     */
    private fun ensureDrawerPage(): DrawerPage {
        if (!::drawerPage.isInitialized) {
            drawerPage = createDrawerPage()
        }
        return drawerPage
    }

    /** 新建一个抽屉页实例（回调都绑好）。 */
    private fun createDrawerPage(): DrawerPage = DrawerPage(context, pal).apply {
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

    /**
     * 桌面第一帧之后的**空闲补建**（第191轮）。
     *
     * 把「启动时不需要、但很快就会用到」的重 View 在第一帧之后补上，
     * 用户就感觉不到它们的构造耗时。
     *
     * 必须在**主线程**（View 不是线程安全的），但排在第一帧之后 ——
     * 那时桌面已经画出来了，即使这会儿卡一下，用户看到的也是**完整的桌面**，
     * 而不是「启动页还盖着、又不动了」。
     */
    fun warmupDeferredUi() {
        if (!::drawerPage.isInitialized) {
            mark("warmup:begin")
            runCatching { ensureDrawerPage() }
                .onFailure { Logx.w(LogTags.DRAWER, "预热抽屉页失败", it) }
        }
        mark("warmup:drawer")
    }

    /**
     * 打开应用抽屉浮层
     * 抽屉是盖在桌面之上的浮层，不是整页；打开时抑制画中画触摸。
     */
    private fun showDrawer() {
        if (::drawerPage.isInitialized && drawerPage.parent != null) return
        // 画中画全屏时它盖在根容器最上层，抽屉插在状态栏之前 → 会被压在下面看不见。
        // 先还原（留在当前页，不把用户拽走），抽屉才露得出来。
        exitPipFullscreenIfNeeded(page)
        // 第191轮：抽屉页是**按需构造**的 —— 这里可能还没建过。
        // 正常情况下 [warmupDeferredUi] 已经在第一帧之后补建好了，
        // 这里是「用户比补建更快」的兜底。
        val drawer = ensureDrawerPage()
        // 第152轮：打开前先清一遍浮层。
        // 抽屉页是**长期持有的同一个实例**，上一次没清干净的菜单 / 弹窗
        // 会跟着实例一起回到窗口上，盖住图标、把所有点击吃掉。
        drawer.clearOverlays()
        drawer.reload()
        // 第四十九轮：抽屉改成设计板 `s03-apps` 的**页面级卡片**
        // （left 44 / top 73 / 宽 1876 / 高 867），状态栏和 Dock 仍然可见 ——
        // 所以它插在**状态栏之前**，不能 bringToFront。
        val index = if (::statusBar.isInitialized) content.indexOfChild(statusBar) else content.childCount
        content.addView(drawer, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        // 第152轮：抽屉一关，浮层（长按弹出的应用菜单 / 重命名 / 禁用确认）**立刻**清掉。
        //
        // 抽屉页是**长期持有的同一个实例**，`removeView` 只是把它从窗口摘掉，
        // 实例内部的浮层还挂着 —— 下次打开抽屉它们又盖在上面，
        // 菜单的遮罩会把点击全吃掉，点图标一点反应都没有。
        //
        // 放在动画回调前面（而不是回调里）是有意的：浮层不该跟着抽屉
        // 在屏幕上多停留 240ms。
        drawerPage.clearOverlays()
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
     * 全局 Dock 的主页键 / 系统 HOME 键走这条路（由 [com.cardesk.launcher.MainActivity] 转过来）。
     *
     * ## 与 [goHome] 的区别
     *
     * [goHome] 是**页内 Dock** 的主页键，语义是「回桌面页」；
     * 全局 Dock 的主页键 / 系统 HOME 键的语义是「把桌面拉到前台」——
     * 非全屏时**不动页号**（老行为：用户可能就停在画中画页，别把他拽走）。
     *
     * 只有**画中画全屏**时才接管：全屏层盖住整个屏幕，
     * 不还原的话用户眼前什么都没变（真机：全屏下 home 键「没反应」，
     * 诊断包 11-16 11:27~11:37 点了 12 次 home 全部无反馈）。
     */
    fun handleExternalHome() {
        // 画中画全屏优先还原：全屏层是独立图层，盖在整个屏幕之上，
        // 光切页面用户眼前什么都不会变。
        if (pipFullscreen) {
            Logx.event(LogTags.DOCK, "外部 HOME：先还原画中画全屏", "pkg" to prefs.pipApp)
            goHome()
            return
        }
        // 非全屏：**真的回首页**。
        //
        // 老行为是「不动页号」，理由是「用户可能就停在画中画页」。但全局 Dock 的
        // 首页键（以及系统 HOME）在用户心里的语义就是「回首页」—— 独立验收里
        // 这条被判为 P0：在抽屉 / 分屏 / 导航页点首页键，画面逐字节不变。
        // 所以这里改成：先把所有浮层收掉，再切回 [PAGE_SCENE]。
        Logx.event(LogTags.DOCK, "外部 HOME：回首页", "page" to page, "settings" to (settings != null))
        if (drawerShowing()) closeDrawer()
        if (vehiclePage != null) closeVehiclePage()
        if (settings != null) closeSettings()
        if (climate != null) closeClimate()
        showPage(PAGE_SCENE)
    }

    /**
     * 底部导航栏：有两套承载（06-F01/F02/F03）——
     * 普通模式直接放设计版 Dock（第九十四轮起铺满整屏宽 1920×112，贴底）；
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
                // 第九十四轮：Dock 铺满整屏宽（以前 1872、左右各留 24，
                // 全局 Dock 窗口里会和底色的不透明条对不齐，其它应用里看就是两条缝）。
                // 第192轮：高 104 → 112（和系统导航预留带一致），底边距仍为 0（贴底）。
                FrameLayout.LayoutParams(Design.W.toInt(), 112, Gravity.BOTTOM).apply {
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
     * 「最近任务」——Dock 主页键长按、增强导航栏的主页槽双击/长按、应用列表槽长按。
     *
     * 第九十五轮 C3：不再退回 HOME（HOME 就是本桌面，等于「点了没反应」），
     * 改成打开本应用自己的最近任务页；平台签名不可用时给明确提示。
     */
    /**
     * 把桌面任务带到前台（Home 键接管时用）。
     *
     * 用**显式 intent + REORDER_TO_FRONT**，而不是发 HOME intent：
     * 发 HOME 会让 `MainActivity` 又收到一次 CATEGORY_HOME、被当成第二次按压，
     * 双击判定会自己把自己的动作当成新按键。
     */
    private fun bringHomeToFront() {
        runCatching {
            val intent = android.content.Intent(context, com.cardesk.launcher.MainActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            context.startActivity(intent)
        }.onFailure { Logx.w(LogTags.HOME, "桌面带到前台失败", it) }
    }

    /** 外部 HOME 键双击：打开本项目的最近任务页（MAIN 只做 recruiting，动作在这里）。 */
    fun handleExternalHomeDouble() {
        Logx.event(LogTags.HOME, "外部双击 Home：打开最近任务")
        runCatching { showRecents() }.onFailure { Logx.w(LogTags.HOME, "打开最近任务失败", it) }
    }

    private fun showRecents() {
        Logx.event(LogTags.APP, "打开最近任务")
        if (recentTasksPage != null) return
        // 同抽屉：最近任务页也插在状态栏之前，画中画全屏层会把它压在下面。
        exitPipFullscreenIfNeeded(page)
        val page = RecentTasksPage(context, pal).apply {
            onClose = { closeRecents() }
            onOpenApp = { app ->
                if (com.cardesk.launcher.core.RecentTasks(context).open(app)) {
                    closeRecents()
                } else {
                    toast("无法打开 " + app.label)
                }
            }
        }
        recentTasksPage = page
        // 与抽屉同一层：插在状态栏之前，状态栏和 Dock 仍然可见。
        val index = if (::statusBar.isInitialized) content.indexOfChild(statusBar) else content.childCount
        content.addView(page, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        page.playOpen()
    }

    private fun closeRecents() {
        val page = recentTasksPage ?: return
        recentTasksPage = null
        page.playClose { content.removeView(page) }
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
            // 走 [removeAnimated]：它不是 [DialogSheet]（自带外壳），所以今天还是同步摘层；
            // 等它也提供退场动画了，宿主这边不用再改。
            removeAnimated(dialog) {
                if (navSlotDialog === dialog) navSlotDialog = null
                if (prefs.enhancedNav && prefs.navStyle == EnhancedNavBarView.MODE_SLOTS) build()
            }
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
    /**
     * 「立即切换」按下的**强制日夜**（null = 不强制，按设置推导）。
     *
     * 画板 s06 的「立即切换」是一个**动作**：立刻把桌面切到另一个明暗。
     * 老实现只重放 `resolvePalette()` —— 跟随时间模式下当前是日间，重放还是日间，
     * 按钮看起来「点了没反应」（第 2 轮验收 N-1 实测三次点击画面逐字节不变）。
     */
    private var forcedNight: Boolean? = null

    private fun resolvePalette(): Palette {
        val night = forcedNight ?: resolveNight()
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

    /**
     * 「立即切换」：强制切到**当前明暗的反面**，并记住这次强制。
     *
     * 下一次「跟随时间 / 光感 / 高德」的信号变化（[resolveNight] 变化）会把
     * [forcedNight] 清掉，回到正常跟随 —— 画板没有常驻「手动锁定」态。
     */
    private fun toggleDayNightNow() {
        val next = !pal.night
        forcedNight = next
        Logx.event(LogTags.THEME, "立即切换日夜", "night" to next)
        applyTheme()
    }

    private fun applyTheme() {
        val old = pal
        pal = resolvePalette().also { Skin.apply(it) }
        Logx.i(LogTags.THEME, "主题切换", "night" to pal.night, "wasNight" to old.night, "accent" to prefs.accentColor)
        // 桌面换了日夜 → 告诉高德一声（跟随高德时除外，见 [pushDayNightToAmap]）。
        pushDayNightToAmap(pal.night)
        // 第三百一十八轮：日夜切换时换视频（有夜间版才换；没有就继续播日间那段）。
        if (old.night != pal.night || videoNightPlaying != pal.night) {
            maybeSwitchVideoForNight()
        }
        build()
    }

    /**
     * 把桌面的日夜状态推给高德，让地图跟着切。
     *
     * **只在桌面不是跟着高德走的时候才推**（对标 App 在「跟随高德」时同样不回写）：
     * 跟随高德时桌面就是高德的状态，回写等于把高德的「自动」锁死成固定白天/黑夜，
     * 高德以后自己就不切了，桌面也就永远停在旧值上。
     */
    private fun pushDayNightToAmap(nightUi: Boolean) {
        if (!prefs.dayNightEnabled) return
        if (prefs.dayNightMode == com.cardesk.launcher.core.DayNight.MODE_FOLLOW_AMAP) {
            // 回到「跟随高德」：清掉记录，等下次切到别的模式时再推。
            amapPushedNight = null
            return
        }
        if (amapPushedNight == nightUi) return
        // 数据源还没起来（首帧之前的启动阶段）就先不记，下次再推。
        val source = navGuideSource ?: return
        amapPushedNight = nightUi
        source.sendDayNight(nightUi)
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
        // 统一走 [NightResolver] —— 仪表歌词投射服务用的是同一个解析器，
        // 两边看到的高德信号、设置项完全一样，不会劈叉（第一百三十七轮）。
        val night = com.cardesk.launcher.core.NightResolver.resolve(prefs, context)
        val last = prefs.lastAppliedNight()
        if (last != night) {
            // 实车闪屏诊断（第三百零三轮）：翻转是**整桌重建**的触发点
            // （applyTheme → build），如果它高频发生就是闪屏根因。
            // 所以这条日志把**全部六源**一次带全（快照里 sensor/car/
            // system 都在），锁定是哪个源在临界值上抖。
            val sig = runCatching {
                com.cardesk.launcher.core.NightResolver.snapshotSignals(prefs, context)
            }.getOrDefault(emptyList())
            Logx.i(
                LogTags.THEME,
                "日夜态变化",
                "night" to night,
                "was" to last,
                *sig.toTypedArray(),
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

    /** 打开来源菜单（锚点是真实 View：取它在窗口里的矩形，按「盖在锚点上方」的矩形弹）。 */
    private fun openSourceMenu(anchor: View?) {
        if (anchor == null) {
            toast("来源菜单暂不可用")
            return
        }
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val rect = android.graphics.RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + anchor.width).toFloat(),
            (loc[1] + anchor.height).toFloat(),
        )
        openSourceMenu(rect, SourceMenuLayer.Placement.ABOVE)
    }

    /** 打开来源菜单（锚点 = 设计坐标矩形，如自绘的场景页/导航页媒体卡）。 */
    private fun openSourceMenu(
        rect: android.graphics.RectF?,
        placement: SourceMenuLayer.Placement = SourceMenuLayer.Placement.FIXED,
    ) {
        if (rect == null) {
            toast("来源菜单暂不可用")
            return
        }
        sourceMenu.currentId = prefs.musicSource ?: ""
        sourceMenu.toggle(rect, placement)
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
        // 第128轮：用户在**卡片来源行**选了 QQ 音乐 = 显式意图。
        // 「允许对外发送」的门要跟着这个选择开 —— 真车诊断包实测：
        // 用户只在 design_music_source_v1 上选过 QQ 音乐，
        // 设置页的 default_music_source_v1 压根没写过 →
        // 第126轮的开门广播一次都没发出去 → QQ 不往外挂曲目 → 方控拿不到东西。
        runCatching {
            com.cardesk.launcher.core.media.MusicController.get()
                .onCurrentMediaSourceChanged(sourceId)
        }
        refresh()
    }

    /**
     * 当前显示的来源文案（第202轮改规则）。
     *
     * **实际在放的那路优先**：卡面曲目来自哪路就标哪路，设置里的来源只在没有播放时当默认
     * （见 [com.cardesk.launcher.core.media.MediaSources.resolveSourceId]）。
     */
    private fun currentSourceLabel(track: com.cardesk.launcher.core.media.TrackInfo?): String {
        val id = com.cardesk.launcher.core.media.MediaSources.resolveSourceId(
            track?.sourceId, prefs.musicSource,
        )
        return com.cardesk.launcher.core.media.MediaSources.byId(id)?.label
            ?: com.cardesk.launcher.core.media.MediaSources.GENERIC_LABEL
    }

    /**
     * 当前生效的**来源 id**（第 125 轮 / 第202轮改规则）。
     *
     * 与 [currentSourceLabel] 同一套规则（实际在放的那路优先），
     * 只是返回 id 而不是文案 —— 卡片上要画哪个图标得按 id 查。
     */
    private fun currentSourceId(track: com.cardesk.launcher.core.media.TrackInfo?): String {
        return com.cardesk.launcher.core.media.MediaSources.resolveSourceId(
            track?.sourceId, prefs.musicSource,
        )
    }

    // --------------------------------------------------------------- 媒体源图标（第125轮）

    /**
     * 媒体源图标：真 App 图标优先，取不到用自绘符号。
     *
     * 取图标要读 APK 资源 → **后台线程**取，主线程只读缓存。
     *
     * 第138轮：**首页与第二页共用这一份图标**。
     *
     * 原来只有首页（`scenePage`）有图标，第二页（`navPage`）只有一个品牌色小圆点 ——
     * 同一个来源在两页画成两个样。现在两边拿的是同一个位图、同一个判定，
     * 不会出现「同屏不一致」。
     */
    private fun updateMediaSourceIcon(sourceId: String) {
        val kind = com.cardesk.launcher.core.media.MediaSources.iconKind(sourceId)
        scenePage.mediaSourceIconKind = kind
        // 第二页的来源行图标（同一套逻辑，见 [com.cardesk.launcher.ui.widget.MediaSourceIconView]）。
        if (::navPage.isInitialized) navPage.sourceId = sourceId
        if (kind != com.cardesk.launcher.core.media.MediaSourceIconKind.APP) {
            // 蓝牙 / USB / 通用音乐没有对应 App，直接用自绘符号。
            scenePage.mediaSourceIcon = null
            if (::navPage.isInitialized) navPage.sourceIcon = null
            scenePage.invalidate()
            return
        }
        val cached = com.cardesk.launcher.ui.widget.MediaSourceIcons.cached(sourceId)
        if (cached != null) {
            scenePage.mediaSourceIcon = cached
            if (::navPage.isInitialized) navPage.sourceIcon = cached
            scenePage.invalidate()
            return
        }
        if (iconLoadingFor == sourceId) return
        iconLoadingFor = sourceId
        val ctx = context.applicationContext
        Thread {
            val bmp = com.cardesk.launcher.ui.widget.MediaSourceIcons.load(ctx, sourceId)
            iconHandler.post {
                if (iconLoadingFor == sourceId) {
                    iconLoadingFor = null
                    scenePage.mediaSourceIcon = bmp
                    if (::navPage.isInitialized) navPage.sourceIcon = bmp
                    scenePage.invalidate()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 应用「默认媒体源」（第 125 轮）：锁定对应的包。
     *
     * - 目标已安装 → 锁定包，**不拉起 App**（桌面启动不该自己弹音乐）；
     * - 「通用音乐」或没装 → 释放锁定，回落到跟随当前会话。
     *
     * @return 实际锁定的包（null = 没锁定）
     */
    fun applyDefaultMediaSource(sourceId: String): String? {
        // 第126轮：默认源换成 QQ 音乐时，**立刻**把「允许对外发送」的门打开 ——
        // 不开门，QQ 音乐不会往外挂广播（歌名、歌词、方控全都拿不到）。
        runCatching {
            com.cardesk.launcher.core.media.MusicController.get().onDefaultMediaSourceChanged(sourceId)
        }
        val pkg = com.cardesk.launcher.core.media.MediaSources.resolveDefaultPackage(sourceId) { p ->
            runCatching { context.packageManager.getApplicationInfo(p, 0) != null }.getOrDefault(false)
        }
        if (pkg == null) {
            com.cardesk.launcher.core.media.MusicSessionTracker.get().clearPackageLock()
            Logx.i(LogTags.MEDIA, "默认媒体源：跟随当前会话", "sourceId" to sourceId)
            return null
        }
        com.cardesk.launcher.core.media.MusicSessionTracker.get().lockToPackage(pkg)
        Logx.i(LogTags.MEDIA, "默认媒体源：已锁定", "sourceId" to sourceId, "pkg" to pkg)
        return pkg
    }

    // --------------------------------------------------------------- 页面切换

    private fun showPage(target: Int) {
        showPage(target, animate = true)
    }

    private fun showPage(target: Int, animate: Boolean) {
        // 画中画全屏时它盖在根容器最上层，不还原就切页 = 用户眼前什么都没变。
        // 这条闸口必须排在切页日志**之前**，否则日志里只会看到「切页成功」，
        // 排查时完全看不出上面还压着一层全屏画中画。
        if (exitPipFullscreenIfNeeded(target)) return
        Logx.i(
            LogTags.APP,
            "切页",
            "from" to page,
            "to" to target,
            "animate" to animate,
            "fullscreen" to pipFullscreen,
        )
        closeClimate()
        if (sourceMenu.isOpen) sourceMenu.hide()
        val old = page
        page = target
        prefs.lastPage = target
        dots.index = target
        dock.activePage = target
        // 全局 Dock（悬浮窗）是**另一个 View 实例**，页号要单独同步过去 ——
        // 否则它永远停在 0，站在画中画页上 home 键还高亮成「当前页」。
        GlobalDockService.onActivePage(target)
        // 切页后自绘状态栏的显隐要跟着变（对标 App 的同步逻辑：导航页隐藏）
        applyDockVisibility()

        val views = arrayOf<View>(scenePage, navPage)
        val gen = ++pageGen
        for (i in pageAnimators.indices) pageAnimators[i]?.cancel()

        // 画中画只挂在导航页的地图卡里（PIP 在 navCanvas 的 mapCard 里）
        refreshPip()

        // 浮窗只在桌面首页可用；切走时全部最小化（:965-977）
        floatLayer?.onPageChanged(target)

        // 不切页（`old == target`）或者明确不要动画 → 老实归位。
        if (!animate || old == target) {
            views.forEachIndexed { i, v -> restPage(v, i == target) }
            return
        }

        // 第191轮：**横滑**，方向跟着页号走。
        //
        // 往下一页 → 新页从**右**边进、旧页往**左**退；
        // 往上一页 → 反过来。
        //
        // 跟主翻页手势（单指横滑）方向一致 —— 手指往左划，页面就往左走。
        //
        // ## 关键：进入页必须先量出尺寸（原来就是「硬切」的根因）
        //
        // 旧代码的动画门槛是 `views.all { it.width > 0 && it.height > 0 }`。
        // 而**离开**的那页永远是可见的、量过的，**进入**的那页却是 `GONE` ——
        // `GONE` 的视图不参与测量，它从开机起就没量过，`width` 永远是 0。
        //
        // 于是 `canAnimate` 永远是 false → `restPage()` 直接把两页一显一隐
        // → **切页其实一直是「硬切」，从来没有动画过**。
        // 这就是用户说的「切换太过僵硬」的真正原因。
        //
        // 现在：先按 [layoutPageForAnimation] 把进入页量一次，
        // 门槛也改成看 [pageHost]（两页的共同容器，一定有尺寸）。
        val forward = PageTransitionSpec.forward(old, target)
        val pageWidth = pageHost.width
        if (!layoutPageForAnimation(views[target])) {
            // 还是量不出来（容器还没尺寸）→ 老实归位，不演。
            views.forEachIndexed { i, v -> restPage(v, i == target) }
            return
        }
        pageHost.bringChildToFront(views[target])
        slidePage(
            views[old], old, entering = false,
            translation = PageTransitionSpec.exitTranslation(pageWidth, forward), gen = gen,
        )
        slidePage(
            views[target], target, entering = true,
            translation = PageTransitionSpec.enterTranslation(pageWidth, forward), gen = gen,
        )
    }

    /**
     * 把即将进入的页面**手动量一次**（第191轮）。
     *
     * ## 为什么需要
     *
     * 页面平时是 `GONE`（不画、不吃触摸）。`GONE` 的视图**不参与测量**，
     * 所以它从开机起就没量过 —— `width` / `height` 一直是 0。
     *
     * 而切页动画要按**页宽**算位移距离，还要靠尺寸把页面画出来。
     * 尺寸是 0 的话，位移就是 0，动画等于没动。
     *
     * 这里按 [pageHost] 的尺寸手动 `measure` + `layout` 一次 ——
     * 跟 `ViewGroup` 自己做的事一样，只是提前了一帧。
     * 下一帧正常的布局流程会再算一遍，结果一致。
     *
     * ## 第201轮修正：必须尊重页面自己的 LayoutParams
     *
     * 以前无条件按 `pageHost` 的整块 1920×1080 去量 / 摆。导航页的
     * LayoutParams 其实是 `topMargin=navTop`、`height=968-navTop`（避让状态栏和
     * Dock），手动一摆就变成 1080 高 —— 画中画承载面的 SurfaceView 高度
     * 跟着 1052 ↔ 867 跳一次，VirtualDisplay 的 resize 跟不上，
     * 底部就留下一条黑块（诊断包 2026-11-17 里 1052→867 的连续两次同步）。
     *
     * 所以这里按页面自己的 width/height/margins 算矩形，与父布局下一帧的结果一致。
     *
     * @return true = 量到了，可以演动画
     */
    private fun layoutPageForAnimation(page: View): Boolean {
        val lp = page.layoutParams as? ViewGroup.MarginLayoutParams
        val box = PageLayoutRect.of(
            hostW = pageHost.width,
            hostH = pageHost.height,
            lpWidth = lp?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            lpHeight = lp?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
            leftMargin = lp?.leftMargin ?: 0,
            topMargin = lp?.topMargin ?: 0,
            rightMargin = lp?.rightMargin ?: 0,
            bottomMargin = lp?.bottomMargin ?: 0,
        ) ?: return false
        page.measure(
            View.MeasureSpec.makeMeasureSpec(box.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(box.height, View.MeasureSpec.EXACTLY),
        )
        page.layout(box.left, box.top, box.left + box.width, box.top + box.height)
        return page.width > 0 && page.height > 0
    }

    /** 无动画时把页面归位。 */
    private fun restPage(view: View, current: Boolean) {
        view.animate().cancel()
        view.visibility = if (current) View.VISIBLE else View.GONE
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /**
     * 单页进出动画（第191轮：横滑 + 交叉淡入 + 轻微缩放）。
     *
     * - **横滑**：`translationX`，方向跟页号走（跟单指横滑手势一致）；
     * - **交叉淡入**：alpha，新页淡入比旧页淡出快一点；
     * - **轻微缩放**：0.96 ↔ 1.0，做纵深；
     * - **260ms** + 强减速曲线。
     *
     * 数值规格全在 [PageTransitionSpec]（纯 Kotlin，可 JVM 单测）。
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
            // 第191轮：**从当前画面接着走**，而不是从头开始。
            //
            // 用户连点两下切页时，第二轮动画会打断第一轮：
            // 那一瞬间页面正停在半路（比如 alpha 0.4、位移 100px）。
            // 如果这里一律从「alpha 0、位移 ±22% 页宽」重新开始，
            // 页面会**跳一下** —— 又变成用户说的「僵硬」。
            //
            // 判据：
            // - alpha 在 (0, 1) 之间 → 说明它正淡入/淡出到一半，接着走；
            // - alpha == 1 → 它是「停好的隐藏页」（`restPage` 归位时置 1），
            //   要从头淡入（alpha 0）；
            // - alpha == 0 → 它正淡出到一半，接着走（起点就是 0）。
            val cur = view.alpha
            alphaFrom = if (cur > 0.01f && cur < 0.99f) cur else 0f
            alphaTo = 1f
            // 位移同理：非零说明正飞到一半，从那儿接着飞。
            transFrom = if (view.translationX != 0f) view.translationX else translation
            transTo = 0f
            scaleFrom = PageTransitionSpec.ENTER_SCALE; scaleTo = 1f
        } else {
            alphaFrom = view.alpha; alphaTo = 0f
            transFrom = view.translationX; transTo = translation
            scaleFrom = if (view.scaleX <= 0f) 1f else view.scaleX
            scaleTo = PageTransitionSpec.EXIT_SCALE
        }
        view.visibility = View.VISIBLE
        view.alpha = alphaFrom
        view.translationX = transFrom
        // 竖向位移必须清干净：老版本留下的 translationY 不清的话，
        // 页面会带着一个上/下偏移做横滑，看着像「斜着飘过去」。
        view.translationY = 0f
        view.scaleX = scaleFrom
        view.scaleY = scaleFrom

        val alphaAnim = ObjectAnimator.ofFloat(view, View.ALPHA, alphaFrom, alphaTo).apply {
            duration = PageTransitionSpec.FADE_DURATION
        }
        val transAnim = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, transFrom, transTo).apply {
            duration = PageTransitionSpec.DURATION
        }
        val scaleXAnim = ObjectAnimator.ofFloat(view, View.SCALE_X, scaleFrom, scaleTo).apply {
            duration = PageTransitionSpec.DURATION
        }
        val scaleYAnim = ObjectAnimator.ofFloat(view, View.SCALE_Y, scaleFrom, scaleTo).apply {
            duration = PageTransitionSpec.DURATION
        }

        val set = AnimatorSet().apply {
            interpolator = PathInterpolator(
                PageTransitionSpec.INTERPOLATOR_X1,
                PageTransitionSpec.INTERPOLATOR_Y1,
                PageTransitionSpec.INTERPOLATOR_X2,
                PageTransitionSpec.INTERPOLATOR_Y2,
            )
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
                    view.translationX = 0f
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

    /**
     * 状态栏「驾驶模式 · xx」胶囊点击（第九十五轮补丁）：弹出**驾驶模式快捷面板**。
     *
     * 面板里直接切模式（写车 + 回读校验）；切不动就提示失败，
     * 并在面板底部保留「打开原厂驾驶模式页」兜底。
     */
    private fun showDriveMode() {
        // 第220轮：用后台采样线程的快照，**不再在主线程上同步读一遍车控**。
        //
        // 以前这里一次 `currentVehicleInfo()` 要 30~60 次同步 binder 往返 ——
        // 点一下状态栏的驾驶模式胶囊，卡 100ms 以上。
        // 快照最多旧 1 秒，对「当前是哪个模式」这个语义完全够用。
        val info = runCatching {
            com.cardesk.launcher.vehicle.VehicleSignalSampler.ensureSnapshot()
        }.getOrNull()
        val label = info?.driveModeLabel ?: com.cardesk.launcher.vehicle.DriveMode.UNKNOWN_LABEL
        Logx.event(LogTags.DOCK, "状态栏驾驶模式胶囊点击", "mode" to label, "raw" to (info?.driveModeRaw ?: -1))
        if (overlayDialog is com.cardesk.launcher.ui.dialogs.DriveModeDialog) return
        lateinit var dialog: com.cardesk.launcher.ui.dialogs.DriveModeDialog
        dialog = com.cardesk.launcher.ui.dialogs.DriveModeDialog(
            context,
            pal,
            info?.driveModeRaw,
            onSwitch = { value ->
                com.cardesk.launcher.vehicle.VehicleController.setDriveMode(value) { ok, message ->
                    dialog.showResult(ok, message)
                    if (ok) {
                        refresh()
                        handler.postDelayed({
                            if (overlayDialog === dialog) closeOverlayDialog(dialog)
                        }, DRIVE_MODE_CLOSE_DELAY_MS)
                    }
                }
            },
            onMoreSettings = {
                closeOverlayDialog(dialog)
                if (!com.cardesk.launcher.core.SystemSettingsCatalog.openDriveMode(context)) {
                    showSettings()
                    settings?.showPageKey("oemsettings")
                    toast("这台车机没有独立的驾驶模式页，已打开原厂设置")
                }
            },
        )
        dialog.onClose = { closeOverlayDialog(dialog) }
        showOverlayDialog(dialog)
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

/**
 * **切换** 360 环影（第218轮：按键增强的「360环影(开关)」动作）。
 *
 * 与 [showCam] 的区别：那个是「打开」（顺带按设置切视角），
 * 这个是「开↔关」—— 关的时候直接写 `set360(false)`，不再切视角。
 */
fun toggleCam360() {
    if (VehicleController.is360On) {
        Logx.event(LogTags.CAM360, "关闭 360 环影（按键增强）")
        VehicleController.set360(false)
        return
    }
    showCam()
}

    /**
     * 打开一个浮层弹窗（替换掉上一个）。
     *
     * 替换是**直接摘掉**上一个，不播退场动画 ——
     * 「换一个弹窗」不是「关掉一个弹窗」，播退场反而像是在关用户没关的东西。
     */
    private fun showOverlayDialog(dialog: View) {
        overlayDialog?.let {
            // 上一层可能正在退场（动画还没跑完）——
            // 直接摘掉，并把它的退场登记销掉，免得后面的摘层请求被误判成「重复」。
            closingOverlays.remove(it)
            content.removeView(it)
        }
        overlayDialog = dialog
        content.addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 关闭浮层弹窗。
     *
     * 现在是**先播退场动画、动画结束才摘层**（见 [removeAnimated]）——
     * 之前是同步 `removeView`，视图当场从树上消失，
     * 任何退场动画都没机会播（用户只看到「啪」地没了）。
     */
    private fun closeOverlayDialog(dialog: View) {
        removeAnimated(dialog) {
            if (overlayDialog === dialog) overlayDialog = null
        }
    }

    /**
     * 摘掉一层浮层。
     *
     * - [DialogSheet] 的浮层：先播退场动画，**动画结束后**才摘层；
     * - 自带外壳的浮层（不是 [DialogSheet]）：保持原来的同步摘除。
     *
     * 重复请求（连点两下关闭、关闭 + 返回键）会被 [closingOverlays] 挡掉，
     * 同一层只会退场一次、摘一次。
     */
    private fun removeAnimated(view: View, onRemoved: () -> Unit = {}) {
        val sheet = view as? DialogSheet
        if (sheet == null) {
            content.removeView(view)
            onRemoved()
            return
        }
        if (!closingOverlays.add(view)) return
        sheet.dismissAnimated {
            closingOverlays.remove(view)
            content.removeView(view)
            onRemoved()
        }
    }

    // --------------------------------------------------------------- 车门未关弹窗（第136轮）

    /**
     * 车门 / 引擎盖 / 后备箱未关 → 桌面弹窗；全关 → 自动关掉。
     *
     * ## 三条规则
     *
     * 1. **只提醒「开着」的**（[com.cardesk.launcher.core.DoorAlert.openItems]）：
     *    读不到（`null`）不算开着 —— 误报一次用户就不信这个提示了。
     * 2. **全关上自动关闭**：门都关好了还弹着就是噪音。
     * 3. **手动关闭后不再打扰**：直到所有部件都关上、再次打开才重新弹。
     *
     * ## 为什么不在这里直接 `showOverlayDialog`
     *
     * 桌面可能已经开着别的浮层（驾驶模式面板、设置页…）。
     * 那个浮层正开着的时候**不抢**它 —— 等它关掉、下一轮刷新再弹，
     * 否则用户正在操作的东西会被车门弹窗糊一脸。
     */
    private fun syncDoorAlert(info: com.cardesk.launcher.vehicle.VehicleInfo) {
        val items = com.cardesk.launcher.core.DoorAlert.openItems(
            info.doorsClosed,
            info.hoodClosed,
            info.trunkClosed,
        )
        val dialog = doorAlertDialog

        if (items.isEmpty()) {
            // 全关上 → 自动关闭弹窗，并允许下一次重新提醒。
            if (dialog != null) {
                Logx.event(LogTags.SIGNAL, "车门未关弹窗自动关闭")
                closeOverlayDialog(dialog)
                doorAlertDialog = null
            }
            doorAlertDismissed = false
            doorAlertSignature = null
            return
        }

        // 只在「开着的东西」变化时记日志（每秒都跑，不能刷屏）。
        val signature = items.joinToString(",") { it.kind.name + it.index }
        if (signature != doorAlertSignature) {
            doorAlertSignature = signature
            Logx.event(
                LogTags.SIGNAL, "检测到未关",
                "open" to com.cardesk.launcher.core.DoorAlert.summary(items),
                "count" to items.size,
            )
        }

        // 已经弹着 → 内容跟着变（又多开了一扇门 / 关掉了一扇）。
        if (dialog != null) {
            dialog.update(
                items,
                info.doorsClosed,
                info.windowsOpen,
                info.hoodClosed,
                info.trunkClosed,
                prefs.carModel,
            )
            return
        }

        // 用户手动关过 → 这一轮不再打扰。
        if (doorAlertDismissed) return

        // 别的浮层正开着 → 不抢，下一轮再试。
        if (overlayDialog != null) return

        val created = com.cardesk.launcher.ui.dialogs.DoorAlertDialog(
            context,
            pal,
            items,
            info.doorsClosed,
            info.windowsOpen,
            info.hoodClosed,
            info.trunkClosed,
            prefs.carModel,
        )
        created.onClose = {
            closeOverlayDialog(created)
            doorAlertDialog = null
            doorAlertDismissed = true
            Logx.event(LogTags.SIGNAL, "车门未关弹窗手动关闭")
        }
        showOverlayDialog(created)
        doorAlertDialog = created
    }

    /**
     * 车况卡「详情 ›」：打开**车辆页**（画板 `05-vehicle-day` / `n04-vehicle-night`）。
     *
     * 为什么换成整页而不是原来的 10 项状态弹窗：
     * 画板 05 是「续航与电量 / 胎压监测 / 车门与车窗 / 快捷入口」四块的真实信息架构，
     * 而旧弹窗只是 10 条状态文字的列表 —— 两者不是同一个东西，画板才是这一版的准绳。
     * 旧弹窗类 `VehicleStatusDialog` 保留（其它入口仍可能用到），这里只是不再从车况卡进来。
     *
     * 插在状态栏**之前**：画板里状态栏与 Dock 都可见。
     */
    private fun openVehiclePage() {
        if (vehiclePage != null) return
        val info = lastVehicleInfo
            ?: runCatching {
                com.cardesk.launcher.vehicle.VehicleSignalSampler.ensureSnapshot()
            }.getOrNull()
            ?: return
        val page = com.cardesk.launcher.ui.pages.VehiclePage(context, pal)
        // 车型和首页/车况卡保持一致（车辆页用同一套 cardtop 车模图）。
        page.carModel = prefs.carModel
        page.onOpenClimate = {
            closeVehiclePage()
            showClimate()
        }
        page.onOpen360 = {
            closeVehiclePage()
            showCam()
        }
        page.onOpenOem = {
            closeVehiclePage()
            openNativeSettings()
        }
        page.bind(info)
        val index = if (::statusBar.isInitialized) content.indexOfChild(statusBar) else content.childCount
        content.addView(
            page,
            index,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        vehiclePage = page
        page.altitudeText = com.cardesk.launcher.core.AltitudeDesk.text()
        Logx.event(LogTags.SCENE, "打开车辆页")
    }

    private fun closeVehiclePage() {
        val page = vehiclePage ?: return
        vehiclePage = null
        content.removeView(page)
    }

    /**
     * 「快捷入口 · 原厂设置」：优先拉起**原厂**设置包（`com.ecarx.settings`）。
     *
     * 模拟器/简陋车机上没有这个包 —— 画板副标题写的就是「原厂设置包存在时」，
     * 所以拿不到就退回桌面自己的原厂设置页，并如实提示，不假装成功。
     */
    private fun openNativeSettings() {
        val intent = context.packageManager
            .getLaunchIntentForPackage(NATIVE_SETTINGS_PACKAGE)
        val launched = runCatching {
            if (intent == null) false else {
                context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
        }.getOrDefault(false)
        if (launched) return
        showSettings()
        settings?.showPageKey("oemsettings")
        toast("这台车机没有原厂设置包，已打开 CarDesk 的原厂设置页")
    }

    /** 车况卡点击：打开「车辆状态」面板（10 项逐项状态 + 3/4 车模）。 */
    private fun openVehicleStatus() {
        if (vehicleStatusDialog != null) return
        if (overlayDialog != null) return
        // 第220轮：优先用后台采样线程的快照（不再在主线程同步读一轮车控）。
        val info = lastVehicleInfo
            ?: runCatching { com.cardesk.launcher.vehicle.VehicleSignalSampler.ensureSnapshot() }.getOrNull()
            ?: return
        val created = com.cardesk.launcher.ui.dialogs.VehicleStatusDialog(
            context,
            pal,
            info.doorsClosed,
            info.windowsOpen,
            info.hoodClosed,
            info.trunkClosed,
            prefs.carModel,
        )
        created.onClose = {
            closeOverlayDialog(created)
            vehicleStatusDialog = null
        }
        showOverlayDialog(created)
        vehicleStatusDialog = created
        Logx.event(LogTags.SCENE, "打开车辆状态面板")
    }

    /** 面板开着时每秒同步（门/窗/盖变了要跟着变）。 */
    private fun syncVehicleStatus(info: com.cardesk.launcher.vehicle.VehicleInfo) {
        val dialog = vehicleStatusDialog ?: return
        if (overlayDialog !== dialog) {
            vehicleStatusDialog = null
            return
        }
        dialog.update(
            info.doorsClosed,
            info.windowsOpen,
            info.hoodClosed,
            info.trunkClosed,
            prefs.carModel,
        )
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
     *
     * 摘层走 [removeAnimated]：退场动画播完才摘，
     * 和其它对话框一个节奏（直接摘的话退场动画看不见）。
     */
    private fun closeQuickAppPicker(picker: View) {
        removeAnimated(picker) {
            if (quickAppPicker === picker) quickAppPicker = null
        }
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

    /**
     * 拉起**当前正在使用的**音乐 App（第197轮）。
     *
     * 入口：Dock 媒体卡的**封面 / 歌名区**点击（上下曲 / 播放三个键不走这里）。
     *
     * 「正在使用」= **此刻正在出声的那个 App**（`MusicController.track?.pkg`），
     * 不是用户上次在设置里选的默认来源 —— 用户点这块的意图是
     * 「回到我正在听的那个 App」。
     *
     * 拉不起来时给一句提示，别让用户以为「点了没反应」。
     */
    private fun openCurrentMusicApp() {
        val track = MusicController.get().track
        val action = com.cardesk.launcher.core.media.DockMediaLaunch.openCurrentApp(
            context = context,
            trackPkg = track?.pkg,
        )
        com.cardesk.launcher.core.media.DockMediaLaunch.messageFor(action)?.let { toast(it) }
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

    /**
     * 状态栏生效应用选择（`AppPickerDialog`）。
     *
     * 第189轮：原来的「Dock 生效应用」选择器删掉了 ——
     * 「隐藏原车 Dock」改成全局生效，不再按名单挑应用；
     * 状态栏这一侧的名单还在用，所以保留这个入口（顺带被预览测试当通用选择器用）。
     */
    fun showStatusApps() {
        val dialog = com.cardesk.launcher.ui.dialogs.AppPickerDialog(
            context, pal,
            multiSelect = true,
            preselected = prefs.hideStatusApps,
            title = "指定隐藏状态栏的应用",
        )
        // 勾选即生效，没有确认按钮
        dialog.onChanged = { set ->
            prefs.hideStatusApps = set
            applyOemPolicy()
        }
        dialog.onClose = { closeOverlayDialog(dialog) }
        showOverlayDialog(dialog)
    }

    /**
     * 应用原车 Dock / 状态栏的隐藏策略。
     *
     * - 第八十七轮起，`policy_control` 的具体内容交给
     *   [com.cardesk.launcher.core.dock.GlobalDockPolicyGate] 统一计算：
     *   - 全局 Dock 关闭 → 原来的「隐藏原车 Dock / 状态栏 / 沉浸」逻辑；
     *   - 全局 Dock 开 && `NavInsets.applied()` → 只写状态栏沉浸，**不写**
     *     `immersive.navigation/full`（RRO 预留区必须保住）；
     *   - 全局 Dock 开 && 未 applied → 写 `immersive.navigation=*`，让原车导航栏
     *     整体退出窗口布局，2038 悬浮窗才能贴底把这块位置占住（详见闸口注释）。
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
        //
        // 第191轮：下面这些全是**跨进程写系统设置**
        // （`policy_control` / overscan / 状态栏开关），
        // 真机上每次写都可能触发系统 UI 重排、要几百毫秒到几秒。
        //
        // 以前它在 `build()` 里**主线程同步**做 —— 卡在首帧前面。
        // 现在放到后台线程：写完再回主线程记签名。
        //
        // 顺序上没问题：策略只影响「系统 Dock / 状态栏怎么避让」，
        // 晚几十毫秒生效用户看不出来；卡住首帧才看得出来。
        Thread {
            val seq = policyApplySeq.incrementAndGet()
            val globalDock = prefs.globalDockEnabled
            val navInsets = runCatching {
                com.cardesk.launcher.core.dock.NavInsets.applied(context)
            }.getOrDefault(false)
            val overlay = runCatching {
                android.provider.Settings.canDrawOverlays(context)
            }.getOrDefault(false)
            Logx.i(
                LogTags.SYS,
                "应用原车 Dock/状态栏策略",
                "globalDock" to globalDock,
                "navInsets" to navInsets,
                "overlay" to overlay,
                "hideDock" to prefs.hideDock,
                "hideStatus" to prefs.hideStatus,
                "oemNavImmersive" to prefs.oemNavImmersive,
            )
            // 已经有更新的一次在跑了 → 这一次作废（别两个线程交叉写系统设置）。
            if (policyApplySeq.get() != seq) return@Thread
            val applied = com.cardesk.launcher.core.dock.GlobalDockPolicyGate.apply(context)
            if (!applied) {
                Logx.w(
                    LogTags.SYS,
                    "policy_control 写入失败（已用 overscan 兜底）",
                    "globalDock" to globalDock,
                )
                // 没有系统权限时至少把状态栏开关用 overscan 兜住，避免桌面被原车栏压住
                if (prefs.hideDock) com.cardesk.launcher.core.SystemOps.setOverscan(context, 0, 0, 0, 0)
            }
            // 全局 Dock 模式下原车导航栏要么被 RRO 预留、要么被 immersive.navigation 摘掉，
            // 都不需要再靠 overscan 挪 Dock（lowerDock 与 globalDock 互斥，这里自然是 0）。
            val overscan = if (prefs.lowerDock && !prefs.hideDock) -50 else 0
            com.cardesk.launcher.core.SystemOps.setOverscan(context, 0, 0, 0, overscan)
            if (prefs.hideStatus) {
                com.cardesk.launcher.core.SystemOps.setStatusBarDisabled(context, 0x00000008 or 0x00000004)
            }
            // 签名回主线程记 —— 后面 `syncGlobalDockPolicy()` 靠它避免重复写。
            val signature = policySignature(globalDock, navInsets, overlay)
            handler.post { lastPolicySignature = signature }
        }.apply { name = "cardesk-oem-policy" }.start()
    }

    /** 新建计划任务（dialog_task_editor 等价物）。 */
    fun newTask() = showTaskEditor(null)

    /** 计划任务列表（dialog_task_list 等价物）。 */
    fun showTasks() {
        if (taskList != null) return
        val dialog = com.cardesk.launcher.ui.dialogs.TaskListDialog(context, pal, prefs)
        dialog.onEdit = { task -> showTaskEditor(task) }
        dialog.onClose = {
            // 走动画摘层：退场播完才真的移除
            removeAnimated(dialog) {
                if (taskList === dialog) taskList = null
            }
        }
        taskList = dialog
        content.addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showTaskEditor(task: com.cardesk.launcher.core.ScheduledTask?) {
        if (taskEditor != null) return
        val dialog = com.cardesk.launcher.ui.dialogs.TaskEditorDialog(context, pal, prefs, task)
        dialog.onSaved = {
            // 保存完也要退场动画：直接摘掉的话「保存」这个动作是看不见的
            removeAnimated(dialog) {
                if (taskEditor === dialog) taskEditor = null
                taskList?.reload()
            }
        }
        dialog.onClose = {
            removeAnimated(dialog) {
                if (taskEditor === dialog) taskEditor = null
            }
        }
        taskEditor = dialog
        content.addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 设置页改了桌面卡片布局后，让场景页立刻按新布局重排。 */
    private fun reloadSceneCardLayout() {
        val store = com.cardesk.launcher.core.CardLayout.PrefsStore(prefs)
        scenePage.cardLayout = store.load() ?: com.cardesk.launcher.core.CardLayout.DEFAULT
    }

    /**
     * 释放画中画承载面（主画 + 副画）。
     *
     * 第十二轮：换肤重建（`build`）前必须调用 —— 详见 [build] 里那段说明
     * 与 [com.cardesk.launcher.ui.pip.PipSurface.release]。
     */
    private fun releasePipSurfaces(reason: String) {
        if (!::navPage.isInitialized) return
        navPage.pip.release(reason)
        navPage.pipSecondary.release(reason)
    }

    /**
     * 换肤重建时**摘出来**的承载内容（第139轮）。
     *
     * @param view 承载面（`ActivityView` / 镜像路线的 `TextureView`）本身 —— 带着它的 `VirtualDisplay`
     * @param pkg 它承载的应用包名
     * @param host 镜像路线的宿主（`ActivityView` 路线是 null）—— 第228轮：
     *   镜像宿主也必须一起搬家，否则旧承载面 `release` 时会把虚拟屏销毁
     *   （承载应用的 task 被推回主屏 + 应用被重启一遍）。
     */
    private class CarriedPip(
        val view: View,
        val pkg: String,
        val host: com.cardesk.launcher.ui.pip.PipMirrorHost? = null,
    )

    /** 换肤重建时摘出来的主画 + 副画（没有的那块是 null）。 */
    private class CarriedPips(val main: CarriedPip?, val secondary: CarriedPip?)

    /**
     * 把旧承载面上的承载内容**摘出来**（**不释放** `VirtualDisplay`），交给新承载面接管。
     *
     * ## 为什么不是直接 `release`
     *
     * `release` 会**销毁 `VirtualDisplay`** ——
     * 挂在它上面的承载应用 task 会被系统**搬到主显示器**
     * → 高德从画中画变成**全屏抢屏**（用户第139轮报的现象）。
     *
     * 摘走则 `VirtualDisplay` 全程不死，承载应用的 task 一直在我们的 display 上。
     *
     * @param surface 旧承载面
     * @param action 处置方式（[com.cardesk.launcher.core.PipRebuildPolicy] 判定）
     * @return 摘出来了就返回它；按策略要释放 / 本来就没有内容 → null
     */
    private fun carryOutPipSurface(
        surface: com.cardesk.launcher.ui.pip.PipSurface,
        action: com.cardesk.launcher.core.PipRebuildPolicy.Action,
    ): CarriedPip? {
        // 策略说「释放」→ 不摘，交给 `releasePipSurfaces` 正常拆掉
        // （副画关掉了的时候就该拆 —— 留着就是泄漏）。
        if (action == com.cardesk.launcher.core.PipRebuildPolicy.Action.RELEASE) return null
        val pkg = surface.currentPackage
        val view = surface.detachContent() ?: return null
        // 第228轮：镜像宿主一起摘（不释放虚拟屏）。
        val host = surface.detachMirrorHost()
        return CarriedPip(view, pkg, host)
    }

    /**
     * 换肤重建：把主画 + 副画的承载内容**一起摘出来**（第139轮）。
     *
     * 必须在 `releasePipSurfaces("重建前")` **之前**调用 ——
     * 先释放的话承载内容已经被清掉了，摘不出来（高德会被重启一遍）。
     */
    private fun carryOutPipSurfaces(): CarriedPips {
        if (!::navPage.isInitialized) return CarriedPips(null, null)
        val main = carryOutPipSurface(
            navPage.pip,
            com.cardesk.launcher.core.PipRebuildPolicy.mainAction(navPage.pip.hasContent),
        )
        val secondary = carryOutPipSurface(
            navPage.pipSecondary,
            com.cardesk.launcher.core.PipRebuildPolicy.secondaryAction(
                navPage.pipSecondary.hasContent,
                prefs.dualPip,
                prefs.pip2App,
            ),
        )
        return CarriedPips(main, secondary)
    }

    /**
     * 设置页预览条用的歌词（顺序 = 布局的行序：最上 → 最下）。
     *
     * 第 115 轮：桌面正在放歌时用**真实歌词**，用户看到的就是自己那首歌变大的效果；
     * 没在放歌 / 歌词还没加载 → 返回 null，预览条用自带的示例文案。
     *
     * 第 117 轮：**按布局取** —— 三行显示三句、KTV 两行显示两句，
     * 预览必须跟桌面显示的那几行一致，否则预览就骗人了。
     */
    private fun previewLyricLines(layout: com.cardesk.launcher.core.media.LyricLayout): List<String>? {
        val timeline = mediaLyric ?: return null
        val index = mediaLyricIndex
        if (index < 0) return null
        val lines = timeline.lines.map { it.text }
        return com.cardesk.launcher.core.media.LyricWindow.window(lines, index, layout.slots)
    }

    /**
     * 设置页底部要空出来的高度（**设计 px**）—— 第三百零七轮。
     *
     * ## 问题（用户 m02052）
     *
     * 实车设置页里「ADB 调试」点不动、往下滑「诊断日志」也看不到。
     * 根因不是排版坏了，而是**屏幕最下面那一条是另一个窗口画的**：
     * `GlobalDockService` 的全局 Dock（type 2024 导航栏面板）占
     * `[0,968][1920,1080]`，盖在应用窗口之上 ——
     * 落进这条里的控件既看不见（被它不透明的底图盖住），
     * 也点不到（触摸事件被它吞掉）。
     *
     * 设置页原本按 1080 设计高度排版（面板 y=64..1056），
     * 左栏滚动区最后两行（ADB 调试 / 诊断日志）正好在 968 以下。
     *
     * ## 算法
     *
     * 1. 拿全局 Dock 窗口的高度（[GlobalDockService.designBarHeight]，设计单位）
     *    换算到实时屏幕像素（Dock 窗口按**屏宽的 1920 比例**算高）；
     * 2. 得到这条带子在屏幕上的顶边，再换算成「相对本 View」的像素；
     * 3. 用本 View 的设计缩放（[Design.scaleFor]，整层等比缩放 + 居中）
     *    反算这条带子在**设计坐标**里占多少；
     * 4. 夹在 `[0, 设计高/3]` 之间兜底（异常值不该把面板压扁）。
     *
     * 返回 0 的常见情况：窗口本来就比屏幕矮（预留区下应用窗口只有 968），
     * 这条带子根本不在窗口里；或者没开全局 Dock / 没走预留区。
     */
    private fun settingsBottomReserve(): Float {
        val globalDock = prefs.globalDockEnabled &&
            com.cardesk.launcher.core.dock.NavInsets.applied(context)
        if (!globalDock) return 0f
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return 0f
        val real = android.graphics.Point()
        runCatching {
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE)
                as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(real)
        }.onFailure { return 0f }
        if (real.x <= 0 || real.y <= 0) return 0f
        val screenW = real.x.toFloat()
        val screenH = real.y.toFloat()
        // Dock 窗口高度：`applyGeometry()` 里就是按「屏宽 / 1920」的比例算的。
        val bandPx = GlobalDockService.designBarHeight() * screenW / Design.W
        if (bandPx <= 0f) return 0f
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val bandTopInView = (screenH - bandPx) - loc[1]
        val scale = Design.scaleFor(width, height)
        if (scale <= 0f) return 0f
        // 整层内容在设计坐标里被居中放置，所以窗口顶边不一定对应设计 y=0。
        val contentTop = (vh - Design.H * scale) / 2f
        val bandTopDesign = (bandTopInView - contentTop) / scale
        return (Design.H - bandTopDesign).coerceIn(0f, Design.H / 3f)
    }

    private fun showSettings() {
        if (settings != null) return
        Logx.event(LogTags.SETTING, "打开设置页")
        // 画板 `s01..s25` 全是「设置覆盖整屏」：底部**没有 Dock**（只有状态栏）。
        // 之前 Dock 一直露在设置面板下面，面板底边与 Dock 之间还压着一条桌面内容。
        dock.visibility = View.GONE
        // 第三百零八轮（用户 m02164「设置页也需要显示我们的 dock 栏」）：
        // 设置页底部**不再**做空白占位，而是继续显示我们自己的 Dock —— 全局 Dock
        // 窗口（`GlobalDockService`）画的就是 `DockBarView`，让它照常显示即可。
        //
        // 历史上这里调过 `setSuppressed(true)` 让窗口隐身（第三百零五轮改成空白
        // 占位 + PixelCopy 底图），当时是画板口径「设置页底部没有 Dock」且要压住
        // 原车 `CarNavigationBar`；现在用户要求设置页也带 Dock，而「压住原车栏」
        // 本来就靠**我们自己的不透明 Dock 窗口**完成，不需要让位。
        //
        // 设置页面板高度已让出这条带子（[settingsBottomReserve]，第三百零七轮），
        // 所以两者不再互相遮挡：面板底边 ≈944，Dock 从 968 起。
        // 这里防御性地清一次抑制状态：启动页可能刚把它置 true
        // （`MainActivity.showSplash` → `setSuppressed(true, ...)`）。
        com.cardesk.launcher.core.dock.GlobalDockService.setSuppressed(false)
        val overlay = SettingsOverlay(
            context,
            prefs,
            pal,
            bottomReserve = settingsBottomReserve(),
        )
        overlay.onClose = { closeSettings() }
        overlay.onApply = { onSettingsApplied() }
        // 画板 s06「立即切换」：真的把当前明暗翻过来（不是重放当前 palette）。
        overlay.onToggleDayNight = { toggleDayNightNow() }
        overlay.onCardLayoutChanged = { reloadSceneCardLayout() }
        // 第125轮：默认媒体源 —— 设置里选了立刻锁定对应包，卡片上也切过去。
        overlay.onDefaultMediaSource = { id ->
            prefs.defaultMusicSource = id
            applyDefaultMediaSource(id)
            // 「当前生效的来源」也跟着走，卡片上立刻能看到新默认源。
            prefs.musicSource = id
            refresh()
        }
        // 第114轮：拖「首页中央歌词字号」滑杆时，桌面立刻跟着变。
        // 第117轮：改「歌词布局」时也一样立刻生效。
        overlay.onSceneLyricChanged = {
            scenePage.lyricScale = prefs.sceneLyricScalePct / 100f
            scenePage.lyricLayout = com.cardesk.launcher.core.media.LyricLayout.fromKey(prefs.sceneLyricLayout)
            scenePage.invalidate()
        }
        // 第115轮：预览条优先用**真实歌词**（正在放的那首歌），没有才用示例文案。
        // 第117轮：按布局取句（预览要跟桌面显示的那几行一致）。
        overlay.lyricPreviewLines = { layout -> previewLyricLines(layout) }
        overlay.onEditNavSlots = {
            closeSettings()
            showNavSlots()
        }
        // 第154轮：「恢复默认 15 槽位」一键重置后要**当场生效**。
        // 光落盘不够 —— onApply → onSettingsApplied() 只在「增强导航栏开关」或
        // 「导航栏样式」变了时才 build() 重建，重置槽位两个都不改 ⇒ 不重建，
        // 导航栏纹丝不动，重启才生效。
        //
        // 这里补的正是「编辑槽位…」面板 onChange 里那三步
        // （重新读盘 → 刷槽位条 → 重算歌词归属），两边行为就一致了。
        overlay.onNavSlotsChanged = {
            navSlots = NavSlots.load(prefs)
            slotsBar?.slots = navSlots
            refresh()
        }
        overlay.onVehicleSource = {
            VehicleController.reconnect(context.applicationContext, prefs)
            refresh()
        }
        overlay.onShowTasks = {
            closeSettings()
            showTasks()
        }
        overlay.onShowClusterLyricColor = { slot -> showClusterLyricColorPicker(slot) }
        overlay.onShowUpdate = {
            val dialog = com.cardesk.launcher.ui.dialogs.UpdateDialog(
                context,
                pal,
                channel = prefs.updateChannel,
            )
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        overlay.onShowRestart = {
            val dialog = com.cardesk.launcher.ui.dialogs.RestartDialog(context, pal)
            dialog.onClose = { closeOverlayDialog(dialog) }
            showOverlayDialog(dialog)
        }
        overlay.onShowAccent = { showAccentPicker() }
        overlay.onShowStatusApps = {
            showStatusApps()
        }
        // Dock 快捷应用配置（第五十四轮）：与「长按 Dock 槽位」进同一个面板。
        overlay.onShowDockQuickApps = { showDockQuickApps() }
        // 画中画应用选择器
        // 主画 / 副画各一个选择器；双画已开时**不能选同一个应用**。
        overlay.onShowPipPicker = { secondary -> showPipPicker(secondary) }
        // 壁纸（第五十七轮）：设置页里换壁纸 → 桌面立刻生效。
        overlay.onWallpaperChange = { index -> applyWallpaper(index) }
        // 自定义壁纸（第 105 轮）：导入照片 / 切回照片 / 删除。
        overlay.onImportWallpaper = { requestWallpaperImport() }
        overlay.onUseCustomWallpaper = { applyCustomWallpaper() }
        overlay.onDeleteCustomWallpaper = { deleteCustomWallpaper() }
        // 第207轮：壁纸轮播 / 视频壁纸。
        overlay.onWallpaperCarouselChange = { on ->
            // 开启时立刻重置计时，让用户马上看到第一轮切换的节奏。
            wallpaperSwitchedAtMs = android.os.SystemClock.uptimeMillis()
            if (on) toast("壁纸轮播已开启") else toast("壁纸轮播已关闭")
        }
        overlay.onVideoWallpaperChange = { on ->
            if (on) {
                startVideoWallpaper()
                toast("视频壁纸已启用")
            } else {
                stopVideoWallpaper()
                toast("视频壁纸已关闭")
            }
        }
        overlay.onImportVideoWallpaper = { requestVideoWallpaperImport() }
        overlay.onPickVideoWallpaper = { openVideoPicker() }
        overlay.onDeleteVideoWallpaper = { deleteVideoWallpaper() }
        // 第三百一十八轮：夜间版视频（可选）。
        overlay.onPickNightVideoWallpaper = { openVideoPicker(night = true) }
        overlay.onImportNightVideoWallpaper = { requestVideoWallpaperImport(night = true) }
        overlay.onDeleteNightVideoWallpaper = { deleteNightVideoWallpaper() }
        // 卡片风格（第五十八轮）：透明度 / 颜色 / 圆角 → 桌面重算 token 并刷新。
        overlay.onCardStyleChange = { applyCardStyle() }
        overlay.onShowCardColor = { showCardColorPicker() }
        settings = overlay
        // 画板 `s01..s25` 的状态栏**可见**、Dock **不可见**。
        //
        // 状态栏是 `content` 里的兄弟 View，设置层要是 add 在末尾（= 最上层）就会把它盖掉
        // （实测：不透明背景铺上去之后，顶部那一行齿轮/天气/时间全没了）。
        // 所以跟抽屉页同一套做法 —— 插在状态栏**之前**，让状态栏压在设置层上面。
        val index = if (::statusBar.isInitialized) content.indexOfChild(statusBar) else content.childCount
        content.addView(
            overlay,
            index,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        // Dock 的显隐由 `applyDockVisibility()` 统一决定（`hide` 里已经并进 `settings != null`）。
        // 这里必须**显式**调一次：打开设置不会触发 refresh()，否则 Dock 会一直留在屏幕上。
        applyDockVisibility()
        // 面板上滑 + 遮罩淡入（200ms / 180ms）。
        overlay.playOpen()
    }

    private fun closeSettings() {
        val overlay = settings ?: return
        settings = null
        // 全局 Dock 让位结束（窗口一直在，这里只是恢复可见）。
        com.cardesk.launcher.core.dock.GlobalDockService.setSuppressed(false)
        // 交给统一入口恢复（它同时管 `dock` 与增强导航的 `bottomBar`）。
        applyDockVisibility()
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
        // 选预设 = 关掉自定义壁纸（照片还留着，随时可以再切回去）。
        prefs.wallpaperCustom = false
        background.custom = false
        background.customBitmap = null
        background.wallpaper = next
        stopVideoWallpaper()
        wallpaperSwitchedAtMs = android.os.SystemClock.uptimeMillis()
        if (::scenePage.isInitialized) {
            scenePage.invalidate()
            scenePage.showWallpaperHint()
        }
    }

    // ---------------------------------------------------------- 壁纸轮播（第207轮）

    /**
     * 每秒检查一次「够不够时间切壁纸了」。
     *
     * 判据全在 [com.cardesk.launcher.core.WallpaperCarousel] 里（纯逻辑，可单测）：
     * 轮播开着 + 距上次切换够间隔 + 当前没有在拖壁纸。
     */
    private fun checkWallpaperCarousel() {
        val enabled = prefs.wallpaperCarousel
        // 视频壁纸开着时**不轮播**：一边放视频一边换静态纸没有意义，
        // 而且视频壁纸时静态壁纸根本不画。
        val video = prefs.videoWallpaperEnabled && isVideoWallpaperPlaying()
        if (!enabled || video) return
        val elapsed = android.os.SystemClock.uptimeMillis() - wallpaperSwitchedAtMs
        val should = com.cardesk.launcher.core.WallpaperCarousel.shouldSwitch(
            enabled = enabled,
            elapsedMs = elapsed,
            intervalSec = prefs.wallpaperCarouselIntervalSec,
            dragging = false,
        )
        if (!should) return
        // 自定义壁纸（一张照片）没有「下一张」可切 —— 直接跳过这一轮。
        if (background.custom) {
            wallpaperSwitchedAtMs = android.os.SystemClock.uptimeMillis()
            return
        }
        val next = com.cardesk.launcher.core.WallpaperCarousel.nextIndex(
            background.wallpaper,
            com.cardesk.launcher.core.Wallpapers.COUNT,
        )
        com.cardesk.launcher.core.log.Logx.event(
            com.cardesk.launcher.core.log.LogTags.SCENE,
            "壁纸轮播切换",
            "from" to background.wallpaper,
            "to" to next,
            "intervalSec" to prefs.wallpaperCarouselIntervalSec,
        )
        applyWallpaper(next)
    }

    // ---------------------------------------------------------- 视频壁纸（第207轮）

    /** 视频壁纸是不是正在播。 */
    private fun isVideoWallpaperPlaying(): Boolean = videoPlayer?.isPlaying == true

    /**
     * 第208轮：给单测开的观察口 ——
     * 「视频壁纸起播请求是不是正挂在等 TextureView 的 Surface 就绪」。
     */
    fun pendingVideoWallpaperStartForTest(): Boolean = pendingVideoWallpaperStart

    /**
     * 播放**视频壁纸**。
     *
     * 播不了（文件没了 / 解码失败）就静默退回静态壁纸 —— 桌面不能因为一段坏视频就黑屏。
     */
    fun startVideoWallpaper() {
        // 第208轮：旧版本把视频存在 `filesDir`（媒体服务读不到），先尝试迁移。
        com.cardesk.launcher.core.VideoWallpaper.migrateLegacyFile(context.applicationContext)
        // 第三百一十八轮：日/夜各一段视频（夜间版没导入就用日间版，不黑屏）。
        val nightNow = videoNightOverride ?: runCatching {
            com.cardesk.launcher.core.NightResolver.resolve(prefs, context)
        }.getOrDefault(false)
        val file = com.cardesk.launcher.core.VideoWallpaper.videoFile(context.applicationContext, nightNow)
        videoNightPlaying = nightNow
        if (!file.isFile || file.length() <= 0L) {
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SETTING,
                "视频壁纸：文件不存在，退回静态壁纸",
            )
            prefs.videoWallpaperEnabled = false
            prefs.videoWallpaper = false
            detachVideoWallpaperLayer()
            return
        }
        // 第三百一十八轮：**先把画面交回静态壁纸**（图层隐藏），等新播放器首帧就绪再显示。
        // 以前是直接释放旧播放器、图层保持可见 ⇒ 起播那一瞬露黑。
        concealVideoWallpaperLayer("准备起播")
        // 第208轮：先把上一个播放器释放掉（层先留着 —— 下面还要用它起播）。
        releaseVideoPlayer()
        // 第214轮：**先**把窗口切成半透明，再挂视频层。
        //
        // 顺序很关键：切 format 会重建窗口 Surface，`SurfaceView` 的 surface
        // 跟着重建；先挂层再切 format 会白起播一轮（surface 被换掉）。
        // 先切 format 再挂层，`surfaceCreated` 回调时窗口已经是半透明的了。
        //
        // 第227轮：
        // - 目标 format 由**当前后端**决定（`SurfaceView` ⇒ 半透明；`TextureView` ⇒ 不透明）；
        // - format 已经是对的时候 [applyVideoWindowFormat] 直接返回 ⇒ 不会重建窗口 surface
        //   ⇒ 视频**只起播一次**（修掉「开机后视频跳回开头」）。
        applyVideoWindowFormat(
            com.cardesk.launcher.core.VideoWallpaper.needsTranslucentWindow(videoBackend),
        )
        // 第208轮：视频层**按需挂进窗口**。
        // `TextureView` 只有 attach 之后才会创建 SurfaceTexture，
        // 也只有那时候才会回调 `onSurfaceTextureAvailable` ——
        // 所以「要播视频」= 「把层挂上」，「不播」= 「把层摘掉」。
        pendingVideoWallpaperStart = true
        attachVideoWallpaperLayer()
        // 第228轮：窗口已经切成半透明 ⇒ 画中画要换到**镜像**路线。
        // 不换的话，承载面还挂着 `ActivityView` 路线的内容 ⇒ 半透明窗口下圆角失效、
        // 画面与窗口坐标错位、切页残留黑卡（这正是第226轮引入镜像路线要解决的问题）。
        refreshPip()
    }

    /**
     * 第208轮：把视频壁纸层挂进窗口（夹在「壁纸」与「桌面内容」之间）。
     *
     * ## 挂层的时机很关键
     *
     * `TextureView` 在**拿到尺寸之前**被 draw 会直接崩：
     * `TextureView.draw()` → `getTextureLayer()` → `mLayer` 还是 null → NPE。
     * 所以视频层**不能**在布局阶段挂上去，要推到下一帧（`post`），
     * 让它先被 layout 一次、拿到 1920×1080，再被 draw。
     */
    private fun attachVideoWallpaperLayer() {
        if (videoLayerAttached) {
            maybeStartVideoWallpaper()
            return
        }
        // 推到下一帧再挂：这一帧的布局已经跑完了，新挂上去的子 View 拿不到尺寸，
        // 下一帧才会 layout，而「先 layout 再 draw」是 TextureView 不崩的前提。
        post {
            if (!pendingVideoWallpaperStart) return@post
            // 视频层挂在**壁纸与桌面内容之间**：
            // 视频在卡片下面、壁纸上面，桌面卡片照常可见。
            val idx = indexOfChild(content)
            if (idx >= 0) {
                addView(videoWallpaperView, idx, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } else {
                addView(videoWallpaperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            videoLayerAttached = true
            videoWallpaperView.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        if (right - left <= 0 || bottom - top <= 0) return
                        v.removeOnLayoutChangeListener(this)
                        videoLayoutReady = true
                        maybeStartVideoWallpaper()
                    }
                },
            )
            maybeStartVideoWallpaper()
        }
    }

    /**
     * 第208/214轮：兜底层的起播闸口。
     *
     * 必须同时满足：
     * 1. **Surface 已经就绪**
     *    - `SurfaceView` 后端：`holder.surface` 有效（`surfaceCreated`/`surfaceChanged` 回调过）；
     *    - `TextureView` 后端：`SurfaceTexture` 已经拿到（`onSurfaceTextureAvailable`）。
     * 2. **View 已经 layout 过**、拿到真实宽高（输出 buffer 尺寸要按它来）。
     *
     * 少任何一个，解码器都配不出输出 buffer，`prepareAsync()` 必失败。
     */
    private fun maybeStartVideoWallpaper() {
        if (videoStarted) return
        if (!videoLayoutReady) return
        val w = videoWallpaperView.width
        val h = videoWallpaperView.height
        if (w <= 0 || h <= 0) return
        if (videoBackend == com.cardesk.launcher.core.VideoWallpaper.BACKEND_SURFACE_VIEW) {
            if (!videoSurfaceReady) return
            if (!videoSurfaceView.holder.surface.isValid) return
        } else {
            val st = videoSurfaceTexture ?: return
            runCatching { st.setDefaultBufferSize(w, h) }
        }
        videoStarted = true
        pendingVideoWallpaperStart = false
        // 再推迟一帧：确保视频层真的被画过一次（消费端接上生产者了），
        // 否则解码器推帧过去也没有去处。
        post { prepareVideoWallpaper() }
    }

    /**
     * 第227轮：surface 被换掉时**记下当前播放位置**，重建后接着播。
     *
     * 见 [videoSurfaceView] 的 `surfaceDestroyed` 回调的说明。
     */
    private var videoResumePositionMs = 0L

    /**
     * 第227轮：surface 刚被换掉、还没接上新的那段窗口期。
     *
     * 这段时间里解码器可能报错（输出 surface 没了）。
     * 那时候**不能**按「播放失败」处理 —— 否则会把视频壁纸整个关掉。
     */
    private var videoSurfaceLost = false

    /**
     * 第214轮：视频壁纸的播放器启动。
     *
     * 两种后端给解码器的东西不一样：
     * - `SurfaceView`：`holder.surface`（**独立层**，解码器直接推到那个 SF 层）；
     * - `TextureView`：`Surface(surfaceTexture)`（**拉模式**，帧被画进窗口 buffer）。
     */
    private fun prepareVideoWallpaper() {
        // 第三百一十八轮：日/夜各一段视频 —— 这里和 [startVideoWallpaper] 必须用**同一套**
        // 选文件逻辑，否则「检查的是夜间版、播的还是日间版」。
        val nightNow = videoNightOverride ?: runCatching {
            com.cardesk.launcher.core.NightResolver.resolve(prefs, context)
        }.getOrDefault(false)
        // override 只作用于这一次切换，用完就清掉（否则之后按时间切回白天时会拿错版本）。
        videoNightOverride = null
        val file = com.cardesk.launcher.core.VideoWallpaper.videoFile(context.applicationContext, nightNow)
        videoNightPlaying = nightNow
        com.cardesk.launcher.core.log.Logx.i(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸：选文件",
            "night" to nightNow,
            "file" to file.name,
            "bytes" to file.length(),
        )
        if (!file.isFile || file.length() <= 0L) {
            detachVideoWallpaperLayer()
            return
        }
        releaseVideoPlayer()
        var mp: android.media.MediaPlayer? = null
        try {
            mp = android.media.MediaPlayer()
            // 第三百一十八轮：视频壁纸**必须静音**。
            // 导入的视频大多带音轨，不静音就会在车机上外放（壁纸不该发声）。
            runCatching { mp.setVolume(0f, 0f) }
            // 第208轮：直接给路径 —— 视频文件放在**外部应用目录**
            // （标签 sdcardfs），媒体服务读得到。
            // 放在 `filesDir` 的话，平台签名包的目录标签是 `system_app_data_file`，
            // SELinux 不允许 mediaserver 读 → prepare 直接失败（status=0x80000000）。
            mp.setDataSource(file.absolutePath)
            if (videoBackend == com.cardesk.launcher.core.VideoWallpaper.BACKEND_SURFACE_VIEW) {
                // 第214轮：`SurfaceView` 后端 —— 直接用 holder 的 surface。
                mp.setSurface(videoSurfaceView.holder.surface)
            } else {
                // 第208轮：Surface 要**持有住**（不能是临时值），原因见 [videoSurface] 的注释。
                val st = videoSurfaceTexture
                if (st == null) {
                    // SurfaceTexture 没了（层被摘掉/重建）→ 起不了播。
                    // ⚠️ 这里必须把播放器放掉：`MediaPlayer` 已经建好、setDataSource 也做过了，
                    // 直接 return 会把 native 资源一直占着。
                    runCatching { mp.release() }
                    detachVideoWallpaperLayer()
                    return
                }
                videoSurface = android.view.Surface(st)
                mp.setSurface(videoSurface!!)
            }
            // 第三百一十八轮：`WITH_CROPPING` 居中裁剪铺满（原来是 `SCALE_TO_FIT`，
            // 非 16:9 的视频会在左右/上下留黑边 ⇒ 居中裁剪铺满）。
            runCatching {
                mp.setVideoScalingMode(
                    android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
                )
            }
            // 第三百一十八轮：**首帧闸门** —— 解码器真的把第一帧推上屏（what==3）再显示图层。
            mp.setOnInfoListener { player, what, _ ->
                if (player === videoPlayer && what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    revealVideoWallpaper("首帧就绪")
                }
                false
            }
            // 第208轮：`setLooping` 挪到 prepare 成功之后。
            mp.setOnPreparedListener {
                // 第三百一十八轮：这里**只起播、不显示图层**。
                // 显示交给首帧闸门（`MEDIA_INFO_VIDEO_RENDERING_START` → [revealVideoWallpaper]），
                // 中途露黑的问题就是这样消掉的。
                it.isLooping = true
                // 第227轮：**接着上次的位置播**（surface 换过之后重建播放器时）。
                // 不 seek 回去的话，视频会「跳回开头」重播一遍。
                val resume = videoResumePositionMs
                if (resume > 0L) {
                    runCatching {
                        it.seekTo(resume, android.media.MediaPlayer.SEEK_CLOSEST)
                    }
                    com.cardesk.launcher.core.log.Logx.i(
                        com.cardesk.launcher.core.log.LogTags.SETTING,
                        "视频壁纸：surface 换新，从原位置接着播",
                        "positionMs" to resume,
                    )
                }
                videoResumePositionMs = 0L
                videoSurfaceLost = false
                it.start()
                // 第三百一十八轮：有的车机不发 `MEDIA_INFO_VIDEO_RENDERING_START`，
                // 2 秒兜底直接把图层显示出来（总比一直看不见强）。
                armVideoRevealFallback()
                // 第225轮（临时诊断，验证完就删）：视频层到底有没有挂上、窗口是不是不透明。
                com.cardesk.launcher.core.log.Logx.i(
                    com.cardesk.launcher.core.log.LogTags.SETTING,
                    "视频壁纸诊断",
                    "view" to videoWallpaperView.javaClass.simpleName,
                    "vis" to videoWallpaperView.visibility,
                    "size" to "${videoWallpaperView.width}x${videoWallpaperView.height}",
                    "parent" to (videoWallpaperView.parent?.javaClass?.simpleName ?: "null"),
                    "index" to ((videoWallpaperView.parent as? android.view.ViewGroup)
                        ?.indexOfChild(videoWallpaperView) ?: -1),
                    "alpha" to videoWallpaperView.alpha,
                    "winFmt" to ((context as? android.app.Activity)?.window?.attributes?.format ?: -1),
                    "passthrough" to background.videoPassthrough,
                )
                com.cardesk.launcher.core.log.Logx.i(
                    com.cardesk.launcher.core.log.LogTags.SETTING,
                    "视频壁纸已开始播放",
                    "bytes" to file.length(),
                    "size" to "${videoWallpaperView.width}x${videoWallpaperView.height}",
                    "backend" to videoBackendName(),
                )
            }
            mp.setOnErrorListener { _, what, extra ->
                // 第227轮：surface 刚被换掉、还没接上新的那段窗口期里，
                // 解码器可能报错（输出 surface 没了）。
                // 那**不是**「视频播不了」⇒ 不能把视频壁纸整个关掉
                // （等新 surface 到位就接回去了）。
                if (videoSurfaceLost) {
                    com.cardesk.launcher.core.log.Logx.w(
                        com.cardesk.launcher.core.log.LogTags.SETTING,
                        "视频壁纸：surface 丢失期间的报错，等新 surface 接上",
                        "what" to what,
                        "extra" to extra,
                    )
                    return@setOnErrorListener true
                }
                // 第三百一十八轮：先重试（解码器偶发错误很常见），重试完还不行才退回静态。
                handleVideoPlaybackError(what, extra)
                true
            }
            // 第三百一十八轮：**先登记播放器、再 prepare** —— `onInfo` 里要靠它判归属
            // （旧播放器的迟到回调不能把新图层显示出来）。
            videoPlayer = mp
            videoPreparedOnce = false
            videoFrameRevealed = false
            // 起播期间壁纸照常画（passthrough=false）⇒ 解码器还在预热时看到的是壁纸，不是黑。
            setVideoLayerVisibility(visible = false)
            background.videoPassthrough = false
            applyDesktopBackground(0xFF05080F.toInt())
            mp.prepareAsync()
            prefs.videoWallpaper = true
            prefs.videoWallpaperEnabled = true
            background.custom = false
            background.customBitmap = null
            wallpaperSwitchedAtMs = android.os.SystemClock.uptimeMillis()
        } catch (e: Throwable) {
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SETTING,
                "视频壁纸启动失败，退回静态壁纸",
                e,
                "size" to "${videoWallpaperView.width}x${videoWallpaperView.height}",
            )
            // 失败的这个播放器必须释放，否则 native 资源一直被占着。
            runCatching { mp?.release() }
            videoPlayer = null
            runCatching { videoSurface?.release() }
            videoSurface = null
            videoStarted = false
            prefs.videoWallpaperEnabled = false
            prefs.videoWallpaper = false
            detachVideoWallpaperLayer()
            background.videoPassthrough = false
            applyVideoWindowFormat(false)
            applyDesktopBackground(0xFF05080F.toInt())
        }
    }

    /** 第三百一十八轮：首帧闸门兜底（毫秒）。 */
    private val VIDEO_REVEAL_FALLBACK_MS = 2_000L

    /** 第三百一十八轮：解码失败的重试次数与间隔。 */
    private val VIDEO_MAX_RETRIES = 2
    private val VIDEO_RETRY_DELAYS_MS = longArrayOf(800L, 2_000L)

    /** 第214轮：后端名字（写进日志，方便实车复测时确认走的哪条路）。 */
    private fun videoBackendName(): String =
        if (videoBackend == com.cardesk.launcher.core.VideoWallpaper.BACKEND_SURFACE_VIEW) {
            "SurfaceView"
        } else {
            "TextureView"
        }

    /**
     * 第214轮：把 Activity 窗口在「半透明」与「不透明」之间切。
     *
     * ## 为什么 `SurfaceView` 后端必须要半透明窗口
     *
     * `SurfaceView` 的 surface 在**窗口 buffer 之下**（默认 z 序）。
     * 窗口 buffer 是不透明的话，它会把下层的视频**整块盖住** ——
     * 所以那一块区域必须是**透明像素**，视频才能透上来。
     *
     * ⇒ 窗口格式设成 `PixelFormat.TRANSLUCENT`。
     *
     * ## 为什么 `TextureView` 后端不用
     *
     * `TextureView` 是**拉模式**：帧被 `updateTexImage()` 画进窗口 buffer 本身，
     * 不需要窗口透明。
     *
     * ## ⚠️ 切 format 会重建窗口 Surface
     *
     * `SurfaceView` 的 surface 跟着一起重建 ⇒ 会来一轮
     * `surfaceDestroyed` → `surfaceCreated`。起播闸口会扛住这一轮
     * （见 [videoSurfaceReady] / `maybeStartVideoWallpaper`）。
     *
     * ## 第227轮：**format 已经对的时候不要再动它**
     *
     * 窗口 format 变了 ⇒ 窗口 surface 重建 ⇒ `SurfaceView` 的 surface 跟着重建
     * ⇒ 播放器被放掉再起一次 ⇒ **视频从第一帧重新开始**
     * （实测每次冷启动起播两次，间隔 652ms）。
     *
     * 所以：目标 format == 当前 format ⇒ **直接返回，什么都不做**。
     * 冷启动那次的 format 由 `MainActivity.applyWindowFormatBeforeCreate()`
     * 在**建窗之前**定好 ⇒ 这里每次都命中「已经对」⇒ **一次都不会重建**。
     */
    private fun applyVideoWindowFormat(transparent: Boolean) {
        val win = (context as? android.app.Activity)?.window ?: return
        val target = if (transparent) {
            android.graphics.PixelFormat.TRANSLUCENT
        } else {
            android.graphics.PixelFormat.OPAQUE
        }
        // 第227轮：已经是对的就别动（动了会重建窗口 surface ⇒ 视频重头再播一遍）。
        val current = win.attributes?.format ?: android.graphics.PixelFormat.OPAQUE
        if (current == target) return
        runCatching {
            win.setFormat(target)
            // 半透明窗口不能有窗口级的背景色，否则整屏都是那块底色。
            win.setBackgroundDrawable(null)
        }.onFailure {
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SETTING,
                "切换窗口 format 失败",
                it,
                "target" to target,
            )
        }
    }

    /**
     * 第214轮：桌面底色。
     *
     * 视频壁纸播放时，桌面底色必须**透明** ——
     * 它画在窗口 buffer 上，不透明就会盖住下层的视频。
     *
     * 其它时候照常上色（换肤、改设置都会走到这里）。
     */
    private fun applyDesktopBackground(color: Int) {
        val transparent = videoWallpaperActive &&
            com.cardesk.launcher.core.VideoWallpaper.needsTranslucentWindow(videoBackend)
        setBackgroundColor(if (transparent) android.graphics.Color.TRANSPARENT else color)
    }

    /**
     * 第214轮：视频壁纸是不是正在播（用来决定桌面底色透不透明）。
     *
     * 不用 `prefs.videoWallpaperEnabled` —— 那个 pref 在起播成功之前就被置真了，
     * 而此时窗口还没切成半透明，底色反而应该照常画。
     */
    private var videoWallpaperActive = false

    // ---------------------------------------------------------------- 第三百一十八轮：视频壁纸
    /** 图层是否已经「显示出来」（首帧闸门过了）。 */
    private var videoFrameRevealed = false

    /** 首帧闸门的超时兜底（有的车机不发 `MEDIA_INFO_VIDEO_RENDERING_START`）。 */
    private var videoRevealPost: Runnable? = null

    /** 本次播放器是否已经 prepared 过（诊断用）。 */
    private var videoPreparedOnce = false

    /** 当前播的是不是夜间版。 */
    private var videoNightPlaying = false

    /**
     * 第三百一十八轮：日夜切换时指定的版本（null = 按当前时间/光感重新判断）。
     *
     * 「立即切换」是**手动**改变外观，`NightResolver.resolve()` 仍按时间算（例如 19 点时
     * 算白天）⇒ 只用 resolver 会又选回日间片。这条 override 由 [maybeSwitchVideoForNight]
     * 写入，[prepareVideoWallpaper] 消费一次后清空。
     */
    private var videoNightOverride: Boolean? = null

    /** 解码失败重试次数（第三百一十八轮）。 */
    private var videoRetryCount = 0

    /** 是不是被 Activity 生命周期暂停的（前台恢复时只恢复这种情况）。 */
    private var videoPausedByLifecycle = false

    /**
     * 播放器释放的**后台线程**。
     *
     * `MediaPlayer.release()` 要等解码器/audio 线程收尾，在主线程做会卡几十毫秒
     * （切壁纸时能感觉到掉帧）。这里丢到单线程 executor 上做，代际号保证顺序。
     */
    private var videoCodecExecutor: java.util.concurrent.ExecutorService? = null

    /** 释放代际：每次释放 +1，防止「旧的释放」把新播放器弄坏。 */
    private var videoReleaseGeneration = 0

    /** 第208轮：把视频壁纸层摘掉（完全不参与渲染）。 */
    /**
     * 第三百一十八轮：控制视频图层的可见性 —— **两种后端语义不同**。
     *
     * - `SurfaceView`：一旦 `INVISIBLE`/`GONE`，系统就**不再为它创建 surface**
     *   ⇒ 解码器没有上屏的地方，`surfaceCreated` 永远不来，首帧闸门会卡死。
     *   所以它必须一直保持 `VISIBLE`，隐藏靠「把壁纸画上去盖住」（`passthrough=false`）。
     * - `TextureView`：帧是画进窗口 buffer 的（没有独立 surface），`INVISIBLE` 才是真的不画。
     */
    private fun setVideoLayerVisibility(visible: Boolean) {
        if (videoBackend == com.cardesk.launcher.core.VideoWallpaper.BACKEND_SURFACE_VIEW) {
            videoWallpaperView.visibility = View.VISIBLE
        } else {
            videoWallpaperView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * 第三百一十八轮：把视频图层**显示出来**（首帧闸门通过 / 超时兜底）。
     *
     * 幂等：已经显示过就直接返回。
     */
    private fun revealVideoWallpaper(reason: String) {
        if (videoFrameRevealed) return
        if (videoPlayer == null) return
        videoFrameRevealed = true
        cancelVideoRevealFallback()
        setVideoLayerVisibility(visible = true)
        // 视频层在「壁纸」之上、「桌面内容」之下 —— 壁纸层让路，桌面卡片照常画在视频之上。
        // `videoPassthrough` 的 setter 自带 `invalidate()`，改完这一帧就会真的把壁纸擦掉。
        background.videoPassthrough = true
        // `SurfaceView` 后端下，窗口 buffer 在视频那块区域必须透明（见 [applyVideoWindowFormat]）。
        videoWallpaperActive = true
        applyDesktopBackground(android.graphics.Color.TRANSPARENT)
        // 第231轮：起播就立刻采一次亮度，别等下一个 3 秒窗口 ——
        // 那 3 秒里日间主题的深字会直接压在暗色视频上。
        videoBackdropAtMs = 0L
        handler.post { sampleVideoBackdropIfNeeded() }
        com.cardesk.launcher.core.log.Logx.i(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸图层已显示",
            "reason" to reason,
            "night" to videoNightPlaying,
            "backend" to videoBackendName(),
        )
    }

    /** 第三百一十八轮：首帧闸门的超时兜底。 */
    private fun armVideoRevealFallback() {
        cancelVideoRevealFallback()
        val runnable = Runnable {
            videoRevealPost = null
            if (videoFrameRevealed) return@Runnable
            // 第三百一十八轮：等不到首帧时，先问一句「这台机器到底解不解得出这段视频」。
            // 解不出来的素材（例如宽度不是 16 的倍数、level 超出软解能力）一帧都不给，
            // 这时直接按兜底显示就是**永久黑背景** —— 比不显示视频更糟。
            val ctx = context.applicationContext
            val nightNow = videoNightPlaying
            val playing = videoPlayer
            Thread({
                val decodable = runCatching {
                    com.cardesk.launcher.core.VideoProbe.decodeCheck(
                        com.cardesk.launcher.core.VideoWallpaper.videoFile(ctx, nightNow),
                    )
                }.getOrDefault(false)
                handler.post {
                    // 探测期间可能已经换过视频/换过日夜/被关掉。
                    if (videoPlayer !== playing || videoFrameRevealed) return@post
                    if (decodable) {
                        com.cardesk.launcher.core.log.Logx.w(
                            com.cardesk.launcher.core.log.LogTags.SETTING,
                            "视频壁纸：没等到首帧回调（探测可解码），按兜底显示",
                        )
                        revealVideoWallpaper("首帧超时兜底")
                    } else {
                        handleUndecodableVideo()
                    }
                }
            }, "cardesk-video-probe").start()
        }
        videoRevealPost = runnable
        handler.postDelayed(runnable, VIDEO_REVEAL_FALLBACK_MS)
    }

    /**
     * 第三百一十八轮：视频解不出画面时退场（避免永久黑背景）。
     *
     * 与「解码器偶发报错」不同，这种是**素材本身解不出**，重试没有意义：
     * 直接停掉视频壁纸、切回静态壁纸，并把开关关掉（否则下次开机又黑一次）。
     */
    private fun handleUndecodableVideo() {
        com.cardesk.launcher.core.log.Logx.w(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸：这段视频解不出画面，切回静态壁纸",
            "night" to videoNightPlaying,
        )
        stopVideoWallpaper()
        Toast.makeText(context, "这段视频车机解不出画面，已切回静态壁纸", Toast.LENGTH_LONG).show()
    }

    private fun cancelVideoRevealFallback() {
        videoRevealPost?.let { handler.removeCallbacks(it) }
        videoRevealPost = null
    }

    /**
     * 第三百一十八轮：把视频图层**藏掉**，画面交回静态壁纸（切视频/切日夜时用）。
     *
     * 藏掉时必须把 `videoPassthrough` 关掉，否则壁纸层还在让路 ⇒ 露黑。
     */
    private fun concealVideoWallpaperLayer(reason: String) {
        cancelVideoRevealFallback()
        videoFrameRevealed = false
        // 第三百一十八轮：`SurfaceView` 后端不能 INVISIBLE（会掐掉 surface）⇒ 走辅助函数。
        setVideoLayerVisibility(visible = false)
        videoWallpaperActive = false
        // `background` 是 `val`（构造时就建好了）⇒ 直接改。
        background.videoPassthrough = false
        applyDesktopBackground(0xFF05080F.toInt())
        com.cardesk.launcher.core.log.Logx.i(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸图层已隐藏",
            "reason" to reason,
        )
    }

    /**
     * 第三百一十八轮：解码失败的处理 —— 先重试，重试完还失败才退回静态壁纸。
     *
     * 解码器偶发错误很常见（切 surface、解码器被别的应用抢走），
     * 以前第一次失败就把视频壁纸整个关掉、还要用户去设置里重新打开。
     */
    private fun handleVideoPlaybackError(what: Int, extra: Int) {
        if (videoSurfaceLost) {
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SETTING,
                "视频壁纸：surface 丢失期间的报错，等新 surface 接上",
                "what" to what,
                "extra" to extra,
            )
            return
        }
        val attempt = videoRetryCount
        if (attempt < VIDEO_MAX_RETRIES) {
            videoRetryCount = attempt + 1
            val delay = VIDEO_RETRY_DELAYS_MS[attempt]
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SETTING,
                "视频壁纸播放出错，稍后重试",
                "what" to what,
                "extra" to extra,
                "attempt" to videoRetryCount,
                "delayMs" to delay,
            )
            concealVideoWallpaperLayer("播放出错，等待重试")
            handler.postDelayed({
                if (prefs.videoWallpaperEnabled) {
                    videoStarted = false
                    videoResumePositionMs = 0L
                    startVideoWallpaper()
                }
            }, delay)
            return
        }
        com.cardesk.launcher.core.log.Logx.w(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸重试仍失败，退回静态壁纸",
            "what" to what,
            "extra" to extra,
            "attempts" to videoRetryCount,
        )
        videoRetryCount = 0
        prefs.videoWallpaperEnabled = false
        prefs.videoWallpaper = false
        detachVideoWallpaperLayer()
        background.videoPassthrough = false
        applyVideoWindowFormat(false)
        applyDesktopBackground(0xFF05080F.toInt())
    }

    /**
     * 第三百一十八轮：后台时暂停视频（以前全屏导航盖住桌面时仍在解码，白耗电+占解码器）。
     *
     * 只在视频**正在播**的时候暂停；不释放播放器（回来时直接 start 接着播，不重解码）。
     */
    fun pauseVideoWallpaper() {
        val mp = videoPlayer ?: return
        if (!videoWallpaperActive && !videoFrameRevealed) return
        runCatching { if (mp.isPlaying) mp.pause() }
        videoPausedByLifecycle = true
        com.cardesk.launcher.core.log.Logx.i(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸：后台暂停",
        )
    }

    /** 第三百一十八轮：回到前台时接着播。 */
    fun resumeVideoWallpaper() {
        if (!videoPausedByLifecycle) return
        videoPausedByLifecycle = false
        val mp = videoPlayer ?: return
        runCatching { if (!mp.isPlaying) mp.start() }
        com.cardesk.launcher.core.log.Logx.i(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸：回到前台继续播放",
        )
    }

    /**
     * 第三百一十八轮：日夜切换时换视频（有夜间版才换）。
     *
     * 做法：先隐藏图层（画面交回静态壁纸）→ 释放旧播放器 → 重新 prepare 另一段。
     * 不做双播放器交叉淡入（车机上双解码不划算）。
     */
    private fun maybeSwitchVideoForNight() {
        if (!prefs.videoWallpaperEnabled || videoPlayer == null) return
        val nightNow = pal.night
        if (nightNow == videoNightPlaying) return
        val hasNight = com.cardesk.launcher.core.VideoWallpaper.hasNight(context.applicationContext)
        // 没有夜间版 ⇒ 继续播那段日间视频，不用重启。
        if (!hasNight) {
            videoNightPlaying = nightNow
            return
        }
        com.cardesk.launcher.core.log.Logx.i(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸：日夜切换，换一段视频",
            "night" to nightNow,
        )
        concealVideoWallpaperLayer("日夜切换")
        detachVideoWallpaperLayer()
        releaseVideoPlayer()
        videoStarted = false
        videoResumePositionMs = 0L
        videoRetryCount = 0
        // 「立即切换」改的是外观本身，不能再用时间推出来的日夜去选片。
        videoNightOverride = nightNow
        startVideoWallpaper()
    }

    private fun detachVideoWallpaperLayer() {
        pendingVideoWallpaperStart = false
        if (!videoLayerAttached) return
        videoLayerAttached = false
        videoSurfaceTexture = null
        videoLayoutReady = false
        videoStarted = false
        // 第214轮：`SurfaceView` 的 surface 也要标记成不可用，
        // 否则下次挂层时闸口会被旧状态放行。
        videoSurfaceReady = false
        // 第208轮：摘层要**推到下一帧**。
        // 在当前这一帧的绘制过程中把 `TextureView` 摘掉，
        // 它下一帧还可能被 draw 到 → `getTextureLayer()` 里 `mLayer` 为 null → NPE 崩溃。
        //
        // 第227轮：**先把要摘的那个视图抓住** ——
        // 摘层是下一帧才执行的，先取一份引用再 `post`，
        // 免得回调执行时字段已经被别处改过（摘错视图：新的留下、旧的漏掉）。
        val target = videoWallpaperView
        post {
            runCatching {
                removeView(target)
                target.visibility = View.GONE
            }
        }
    }

    /** 停掉视频壁纸。 */
    fun stopVideoWallpaper() {
        // 第208轮：连同「等 Surface 就绪再起播」的挂起请求一起取消 ——
        // 否则用户关了视频壁纸，Surface 稍后就绪时又自己播起来。
        pendingVideoWallpaperStart = false
        videoNightOverride = null
        releaseVideoPlayer()
        detachVideoWallpaperLayer()
        // 恢复壁纸层与桌面底色（视频走的时候它们要让路）
        background.videoPassthrough = false
        // 第214轮：窗口恢复不透明、底色恢复上色。
        videoWallpaperActive = false
        applyVideoWindowFormat(false)
        applyDesktopBackground(0xFF05080F.toInt())
        prefs.videoWallpaperEnabled = false
        prefs.videoWallpaper = false
        // 第228轮：窗口已经变回**不透明** ⇒ 画中画要换回 `ActivityView` 路线。
        // 不换的话，承载面还挂着镜像路线的内容 ⇒ 全屏右侧缺一块、拖动很卡
        // （真车实测：用户关掉视频壁纸后画中画行为与「不用视频壁纸」时不一致）。
        refreshPip()
    }

    /**
     * 第208轮：只释放播放器，**不动视频壁纸层的 visibility**。
     *
     * `GONE` 会让 `TextureView` 销毁 SurfaceTexture ——
     * 起播流程里「先把旧播放器放掉、再拿 SurfaceTexture 起新播放器」，
     * 如果这里顺手把层关掉，SurfaceTexture 刚建好就被销毁，起播必然失败。
     */
    private fun releaseVideoPlayer() {
        // 第三百一十八轮：先把引用摘下来，真正的 stop/release 丢到后台线程做
        // （`MediaPlayer.release()` 会等解码线程收尾，主线程做会掉帧）。
        val old = videoPlayer
        videoPlayer = null
        if (old != null) {
            val generation = ++videoReleaseGeneration
            val executor = videoCodecExecutor
                ?: java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                    Thread(r, "cardesk-video-codec").also { it.isDaemon = true }
                }.also { videoCodecExecutor = it }
            runCatching {
                executor.execute {
                    if (generation <= videoReleaseGeneration) {
                        runCatching { old.stop() }
                        runCatching { old.release() }
                    }
                }
            }.onFailure {
                // executor 已经关了（Activity 销毁）⇒ 只能就地放掉。
                runCatching { old.stop() }
                runCatching { old.release() }
            }
        }
        // 第208轮：播放器放掉之后，Surface 才能放 ——
        // 顺序反了会让解码器正在用的 surface 提前断开。
        runCatching { videoSurface?.release() }
        videoSurface = null
        // 第227轮：播放器都没了 ⇒ 不存在「等新 surface 接上」这回事了。
        videoSurfaceLost = false
        // 第231轮：播放器没了 ⇒ 视频亮度分布作废（墨色回到静态壁纸 / 主题那套）。
        videoBackdropSampler?.release()
        videoBackdropSampler = null
        videoBackdropTopTone = null
        videoBackdropCenterTone = null
        videoBackdropAtMs = 0L
        videoBackdropFailures = 0
    }

    /** 视频壁纸选完后的结果：后台拷贝 + 落盘。 */
    fun onVideoWallpaperPicked(uri: android.net.Uri?, night: Boolean = false) {
        com.cardesk.launcher.core.log.Logx.i(
            com.cardesk.launcher.core.log.LogTags.SETTING,
            "视频壁纸：进入导入处理",
            "uri" to (uri?.toString() ?: "null"),
        )
        if (uri == null) {
            toast("已取消导入视频壁纸")
            return
        }
        toast("正在导入视频壁纸…")
        // 注意：Kotlin 的 `Thread { … }` 只是 **SAM 构造**，**不会自动 start()**
        // （自动启动的是 `kotlin.concurrent.thread { … }`）。
        // 以前漏了 `.start()`，导入线程从来没跑过 —— 选完视频毫无反应。
        Thread {
            val ctx = context.applicationContext
            // 第三百一十八轮：**先校验再导入**。
            // 以前是先覆盖目标文件、再校验、不合格再删 —— 用户原来那段能用的视频
            // 会被一起删掉（实测：导入一个解不出画面的文件，日间视频直接没了）。
            val temp = copyUriToCache(ctx, uri)
            val verdict = temp?.let { com.cardesk.launcher.core.VideoProbe.validate(it) }
            val ok = if (verdict is com.cardesk.launcher.core.VideoSpec.Verdict.Reject || temp == null) {
                false
            } else {
                com.cardesk.launcher.core.VideoWallpaper.importFromFile(ctx, temp, night)
            }
            temp?.let { runCatching { it.delete() } }
            val rejected = verdict is com.cardesk.launcher.core.VideoSpec.Verdict.Reject
            if (rejected) {
                com.cardesk.launcher.core.log.Logx.w(
                    com.cardesk.launcher.core.log.LogTags.SETTING,
                    "视频壁纸：导入被拒绝（这台车解不出画面）",
                    "reason" to (verdict as com.cardesk.launcher.core.VideoSpec.Verdict.Reject).reason,
                    "night" to night,
                )
            }
            post {
                if (ok && !rejected) {
                    startVideoWallpaper()
                    toast(when {
                        night -> "夜间视频已导入"
                        verdict is com.cardesk.launcher.core.VideoSpec.Verdict.Warn -> verdict.reason
                        else -> "视频壁纸已启用"
                    })
                } else {
                    toast((verdict as? com.cardesk.launcher.core.VideoSpec.Verdict.Reject)?.reason ?: "视频壁纸导入失败")
                }
                refreshSettingsWallpaper()
            }
        }.start()
    }

    /** 打开系统相册选视频（第218轮的降级链，仍保留作**备选**）。 */
    private fun requestVideoWallpaperImport(night: Boolean = false) {
        (context as? com.cardesk.launcher.MainActivity)?.startVideoWallpaperImport(night)
    }

    // ---------------------------------------------------------- 应用内视频库（第219轮）

    /** 应用内视频选择器（开着的时候只会有一个）。 */
    private var videoPicker: VideoPickerOverlay? = null

    /**
     * 打开**应用内**的视频选择器（第219轮）。
     *
     * ## 为什么不是系统选择器
     *
     * 实车（领克 AAOS 820A）上系统选择器秒退且不带 uri
     * （诊断包：`收到选视频结果 resultCode=0 uri=null`），
     * 系统里也查不到 `documentsui` ⇒ 这条路在车机上不可靠。
     *
     * ⇒ 我们自己列共享存储里的视频文件。
     *
     * ## 权限
     *
     * 读共享存储要 `READ_EXTERNAL_STORAGE`。
     * 车机上本包是 **uid 1000 系统应用** ⇒ 权限天然可用；
     * 普通安装（AVD 上就是这样）则要**运行时**申请。
     */
    fun openVideoPicker(night: Boolean = false) {
        if (videoPicker?.isOpen == true) return
        // 设置页先关掉，避免两层浮层叠着。
        closeSettings()

        val activity = context as? com.cardesk.launcher.MainActivity
        if (activity != null && !activity.hasStoragePermission()) {
            activity.requestStoragePermission()
            toast("需要存储权限才能列出车机里的视频")
            return
        }

        val overlay = videoPicker ?: VideoPickerOverlay(context).also { videoPicker = it }
        overlay.onPick = { item -> importVideoFromLibrary(item, night) }
        overlay.onUseSystemPicker = { requestVideoWallpaperImport(night) }
        if (overlay.parent == null) {
            content.addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        overlay.show()
    }

    /**
     * 从应用内视频列表导入（后台拷贝 + 落盘）。
     *
     * 与 [onVideoWallpaperPicked] 是同一套流程，只是数据来源是**文件**而不是 content Uri。
     */
    private fun importVideoFromLibrary(
        item: com.cardesk.launcher.core.VideoLibrary.Item,
        night: Boolean = false,
    ) {
        toast("正在导入视频壁纸…")
        val path = item.path
        Thread {
            val ctx = context.applicationContext
            val source = java.io.File(path)
            // 第三百一十八轮：**先校验源文件再导入** —— 不合格就不动已经能用的那一份。
            val verdict = com.cardesk.launcher.core.VideoProbe.validate(source)
            val rejected = verdict is com.cardesk.launcher.core.VideoSpec.Verdict.Reject
            if (rejected) {
                com.cardesk.launcher.core.log.Logx.w(
                    com.cardesk.launcher.core.log.LogTags.SETTING,
                    "视频壁纸：导入被拒绝（这台车解不出画面）",
                    "reason" to (verdict as com.cardesk.launcher.core.VideoSpec.Verdict.Reject).reason,
                    "night" to night,
                )
            }
            val ok = if (rejected) false else com.cardesk.launcher.core.VideoWallpaper.importFromFile(ctx, source, night)
            post {
                if (ok && !rejected) {
                    startVideoWallpaper()
                    toast(when {
                        night -> "夜间视频已导入"
                        verdict is com.cardesk.launcher.core.VideoSpec.Verdict.Warn -> verdict.reason
                        else -> "视频壁纸已启用"
                    })
                } else {
                    toast((verdict as? com.cardesk.launcher.core.VideoSpec.Verdict.Reject)?.reason ?: "视频壁纸导入失败")
                }
                refreshSettingsWallpaper()
            }
        }.start()
    }

    /** 删除已导入的视频壁纸（并停播）。 */
    private fun deleteVideoWallpaper() {
        stopVideoWallpaper()
        com.cardesk.launcher.core.VideoWallpaper.delete(context.applicationContext)
        toast("视频壁纸已删除")
        refreshSettingsWallpaper()
    }

    /** 第三百一十八轮：只删夜间版（日间那段继续用）。 */
    private fun deleteNightVideoWallpaper() {
        com.cardesk.launcher.core.VideoWallpaper.deleteNight(context.applicationContext)
        toast("夜间视频已删除")
        // 正在播夜间版 ⇒ 换回日间版。
        if (videoNightPlaying && prefs.videoWallpaperEnabled) {
            startVideoWallpaper()
        }
        refreshSettingsWallpaper()
    }

    /**
     * 第三百一十八轮：把 content Uri 抄进缓存目录（**只用于导入前校验**）。
     *
     * 校验要按路径读视频（`MediaCodec` 只吃文件/`FileDescriptor`），
     * 而系统选择器给的是 `content://`；先落一份临时文件，校验完就删。
     */
    private fun copyUriToCache(ctx: android.content.Context, uri: android.net.Uri): java.io.File? {
        val dir = java.io.File(ctx.cacheDir, "video-import")
        dir.mkdirs()
        val temp = java.io.File(dir, "probe-${System.currentTimeMillis()}.mp4")
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { src ->
                java.io.FileOutputStream(temp).use { sink ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                null
            } else {
                temp
            }
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SETTING,
                "视频壁纸：导入前拷贝失败",
                e,
            )
            null
        }
    }

    // ---------------------------------------------------------- 自定义壁纸（第 105 轮）

    /** 系统相册选图的结果：后台解码 + 落盘，成功后立刻切过去。 */
    fun onWallpaperPicked(uri: android.net.Uri?) {
        if (uri == null) {
            toast("已取消导入壁纸")
            return
        }
        toast("正在导入壁纸…")
        Thread {
            val ok = com.cardesk.launcher.core.CustomWallpaper
                .importFromUri(context.applicationContext, uri)
            post {
                if (ok) {
                    prefs.wallpaperCustom = true
                    background.custom = true
                    background.customBitmap = com.cardesk.launcher.core.CustomWallpaper.cachedBitmap()
                    ensureCustomWallpaperLoaded()
                    if (::scenePage.isInitialized) {
                        scenePage.invalidate()
                        scenePage.showWallpaperHint()
                    }
                    refreshSettingsWallpaper()
                    toast("壁纸已导入")
                } else {
                    toast("导入失败：这张图片解不出来")
                }
            }
        }.start()
    }

    /** 使用已经导入的自定义壁纸。 */
    fun applyCustomWallpaper() {
        val core = com.cardesk.launcher.core.CustomWallpaper
        if (!core.has(context)) {
            toast("还没有导入壁纸")
            return
        }
        prefs.wallpaperCustom = true
        background.custom = true
        background.customBitmap = core.cachedBitmap()
        ensureCustomWallpaperLoaded()
        if (::scenePage.isInitialized) {
            scenePage.invalidate()
            scenePage.showWallpaperHint()
        }
        refreshSettingsWallpaper()
        toast("已切换为自定义壁纸")
    }

    /** 删除自定义壁纸，回到预设（不会动预设下标）。 */
    fun deleteCustomWallpaper() {
        com.cardesk.launcher.core.CustomWallpaper.reset(context)
        prefs.wallpaperCustom = false
        background.custom = false
        background.customBitmap = null
        applyWallpaper(prefs.wallpaper)
        refreshSettingsWallpaper()
        toast("已删除自定义壁纸")
    }

    /** 通知设置页重建「壁纸」页（导入 / 删除后刷新预览与按钮）。 */
    private fun refreshSettingsWallpaper() {
        settings?.refreshWallpaperPage()
    }

    /** 打开系统相册选图（结果回到 MainActivity.onActivityResult）。 */
    private fun requestWallpaperImport() {
        (context as? com.cardesk.launcher.MainActivity)?.startWallpaperImport()
    }

    // ---------------------------------------------------------- 应用内图片库（第230轮）

    /** 应用内图片选择器（开着的时候只会有一个）。 */
    private var imagePicker: ImagePickerOverlay? = null

    /**
     * 打开**应用内**的图片选择器（第230轮）。
     *
     * ## 为什么不是系统选择器
     *
     * 用户反馈真车点「导入壁纸」只弹「没有可用的图片选择器」：
     * 车机没有相册 / DocumentsUI 接管 `ACTION_OPEN_DOCUMENT`。
     * 视频壁纸第219轮就是靠应用内列表解决的，图片照同一套做。
     *
     * ## 权限
     *
     * 读共享存储要 `READ_EXTERNAL_STORAGE`；车机上本包是 uid 1000 系统应用，
     * 权限天然可用；普通安装（AVD）则要运行时申请。
     */
    fun openImagePicker() {
        if (imagePicker?.isOpen == true) return
        // 设置页先关掉，避免两层浮层叠着。
        closeSettings()

        val activity = context as? com.cardesk.launcher.MainActivity
        if (activity != null && !activity.hasStoragePermission()) {
            activity.requestStoragePermission()
            toast("需要存储权限才能列出车机里的图片")
            return
        }

        val overlay = imagePicker ?: ImagePickerOverlay(context).also { imagePicker = it }
        overlay.onPick = { item -> importWallpaperFromLibrary(item) }
        overlay.onUseSystemPicker = { requestWallpaperImport() }
        if (overlay.parent == null) {
            content.addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        overlay.show()
    }

    /**
     * 从应用内图片列表导入壁纸。
     *
     * 直接复用 [onWallpaperPicked] 那条路（后台解码 + 落盘 + 切过去 + 刷新设置页），
     * 只是 uri 从 content Uri 换成 `file://` ——
     * `CustomWallpaper.importFromUri` 内部走 `contentResolver.openInputStream`，
     * 对 `file://` 同样有效。
     */
    private fun importWallpaperFromLibrary(item: com.cardesk.launcher.core.ImageLibrary.Item) {
        onWallpaperPicked(android.net.Uri.fromFile(java.io.File(item.path)))
    }

    /**
     * 把壁纸亮度交给主题层（第222轮）。
     *
     * ## 谁受影响
     *
     * 只有**直接压在壁纸上**的那两处：
     * - 状态栏右侧的裸元素（时钟 / 油量 / 连接图标）；
     * - 场景页右上角的天气块 + 屏幕中部的中心歌词。
     *
     * 带底板的（胶囊 / 卡片 / Dock）**不受影响** ——
     * 它们的底色是主题定的，跟壁纸明暗无关。
     */
    private fun applyWallpaperBackdrop() {
        // 第231轮：视频壁纸播放期间，`BackgroundView` 这一层是「让路」的
        // （`videoPassthrough` ⇒ 它抛 null,null）。但**日夜主题切换 / 尺寸变化**
        // 也会触发它 publish 一次 null,null —— 那一刻不能把视频采出来的墨色冲掉，
        // 否则日间深字会压回暗色视频上（正是用户报的「颜色太近、看不清」）。
        if (isVideoWallpaperPlaying() && videoBackdropTopTone != null) {
            // 这一次静态那一路想接管的，是「视频采不到亮度」的兜底；
            // 视频明明采到了 ⇒ 别让它冲掉（打一条日志，实车复测时能确认走到了这里）。
            com.cardesk.launcher.core.log.Logx.event(
                com.cardesk.launcher.core.log.LogTags.SCENE,
                "视频壁纸墨色保持（忽略静态壁纸的兜底亮度）",
                "top" to (videoBackdropTopTone?.average?.let {
                    com.cardesk.launcher.core.WallpaperContrast.luma(it)
                } ?: -1),
                "static" to (background.backdropTop?.let {
                    com.cardesk.launcher.core.WallpaperContrast.luma(it)
                } ?: -1),
            )
            applyBackdropTones(videoBackdropTopTone, videoBackdropCenterTone)
            return
        }
        applyBackdrop(background.backdropTop, background.backdropCenter)
    }

    /**
     * 第三百零四轮：玻璃底图（模糊背景）算完并发布之后，让所有**画玻璃的层**重画一次。
     *
     * 模糊挪到后台线程之后，[com.cardesk.launcher.core.Glass.publish] 不再发生在
     * 主线程的绘制流程里，所以必须显式标脏；否则卡片会继续用旧底图，
     * 直到下一次别的原因触发重绘（壁纸切换后看起来"半天不生效"）。
     *
     * 覆盖范围 = `Glass.card(` 的三个使用者所在的层：
     * 状态栏（含天气块）、底栏、翻页圆点条、场景页。
     */
    private fun invalidateGlassLayers() {
        if (::statusBar.isInitialized) statusBar.invalidate()
        if (::dock.isInitialized) dock.invalidate()
        if (::dots.isInitialized) dots.invalidate()
        if (::scenePage.isInitialized) scenePage.invalidate()
    }

    /**
     * 壁纸亮度的唯一入口（第222轮）。
     *
     * 静态 / 自定义壁纸由 [BackgroundView] 算完抛过来；
     * 视频壁纸由 [sampleVideoBackdropIfNeeded] 单独采（画面在独立图层上）。
     */
    private fun applyBackdrop(top: Int?, center: Int?) {
        if (top == backdropTop && center == backdropCenter) return
        backdropTop = top
        backdropCenter = center
        com.cardesk.launcher.core.Skin.setBackdrop(top, center)
        if (::statusBar.isInitialized) statusBar.invalidate()
        // 第223轮：状态栏正中的翻页圆点条也压在壁纸上（跟胶囊同一材质），
        // 壁纸明暗变了它得跟着重画。
        if (::dots.isInitialized) dots.invalidate()
        if (::scenePage.isInitialized) scenePage.invalidate()
    }

    /**
     * 视频壁纸的**亮度分布**（第231轮；比平均色多带暗端 / 亮端两簇）。
     *
     * 存下来有两个用处：① `Skin.setVideoBackdrop` 要它算墨色；
     * ② 日夜主题切换时 `BackgroundView` 会抛 null,null，
     * 得拿它把视频那套墨色**顶回去**（不然会被主题墨色冲掉）。
     */
    private var videoBackdropTopTone: com.cardesk.launcher.core.WallpaperSampler.Tone? = null
    private var videoBackdropCenterTone: com.cardesk.launcher.core.WallpaperSampler.Tone? = null

    /** 视频亮度采样连续失败次数（诊断日志按 10 次一条打，免得刷屏）。 */
    private var videoBackdropFailures = 0

    /**
     * 壁纸亮度的入口（视频版，第231轮）：给的是**亮度分布**而不是平均色。
     *
     * 走 `Skin.setVideoBackdrop` ⇒ `WallpaperContrast.inkForTone`
     * （按「浅字对亮端、深字对暗端」的最坏情况拉够对比度）。
     */
    private fun applyBackdropTones(
        top: com.cardesk.launcher.core.WallpaperSampler.Tone?,
        center: com.cardesk.launcher.core.WallpaperSampler.Tone?,
    ) {
        if (top === videoBackdropTopTone && center === videoBackdropCenterTone) return
        videoBackdropTopTone = top
        videoBackdropCenterTone = center
        backdropTop = top?.average
        backdropCenter = center?.average
        com.cardesk.launcher.core.Skin.setVideoBackdrop(top, center)
        if (::statusBar.isInitialized) statusBar.invalidate()
        if (::dots.isInitialized) dots.invalidate()
        if (::scenePage.isInitialized) scenePage.invalidate()
    }

    /** 视频壁纸亮度采样器（第222轮；第231轮起先解文件、再拷图层）。 */
    private var videoBackdropSampler: com.cardesk.launcher.ui.widget.VideoBackdropSampler? = null

    /** 上次采样视频亮度的时间（毫秒）。 */
    private var videoBackdropAtMs = 0L

    /**
     * 视频壁纸的亮度采样（第222轮）。
     *
     * 视频画面在**独立的 SurfaceFlinger 图层**上，窗口缓冲那块是透明的洞 ——
     * ⇒ 只能直接拷视频自己的 Surface（见 `ui/widget/VideoBackdropSampler`）。
     *
     * **每 [VIDEO_BACKDROP_INTERVAL_MS] 采一次**：视频一直在动，
     * 采太勤只是白费 CPU（一次 1920×1080 的拷贝不便宜）。
     *
     * 采不到（Surface 还没就绪 / 拷贝失败）⇒ 保持上一次的值，
     * 第一次就采不到 ⇒ 浮层跟着主题走（= 今天的行为）。
     */
    private fun sampleVideoBackdropIfNeeded() {
        if (!isVideoWallpaperPlaying()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - videoBackdropAtMs < VIDEO_BACKDROP_INTERVAL_MS) return
        videoBackdropAtMs = now
        val position = videoCurrentPositionMs()
        val sampler = videoBackdropSampler
            ?: com.cardesk.launcher.ui.widget.VideoBackdropSampler(
                videoWallpaperView,
                videoBackend,
                handler,
                // 第231轮：先解**视频文件**当前帧（跟合成方式无关）。
                // 真车上视频是硬件解码直通 HWC 图层，`PixelCopy` 读不到那块 Surface ——
                // 采样一直失败 ⇒ 墨色回落主题 ⇒ 日间深字压暗视频（用户报的那个病）。
                videoFile = {
                    runCatching { com.cardesk.launcher.core.VideoWallpaper.file(context) }.getOrNull()
                },
                positionMs = { videoCurrentPositionMs() },
            ).also { videoBackdropSampler = it }
        sampler.sample { top, center ->
            post {
                // 采样是异步的 ⇒ 回来时视频可能已经停了 ⇒ 这时别把旧值盖上去。
                if (!isVideoWallpaperPlaying()) {
                    applyWallpaperBackdrop()
                    return@post
                }
                if (top == null && center == null) {
                    videoBackdropFailures++
                    // 采不到 ⇒ 保持上一次的值（第一次就采不到 ⇒ 跟着主题走）。
                    // 每 10 次记一条，免得每 3 秒刷一行。
                    if (videoBackdropFailures % 10 == 1) {
                        com.cardesk.launcher.core.log.Logx.w(
                            com.cardesk.launcher.core.log.LogTags.SCENE,
                            "视频壁纸亮度采样失败（保持上一次的墨色）",
                            "failures" to videoBackdropFailures,
                            "pos" to position,
                            "backend" to videoBackendName(),
                        )
                    }
                    return@post
                }
                videoBackdropFailures = 0
                com.cardesk.launcher.core.log.Logx.event(
                    com.cardesk.launcher.core.log.LogTags.SCENE,
                    "视频壁纸亮度采样",
                    "src" to (sampler.lastSource ?: "?"),
                    "pos" to position,
                    // 打 luma 而不是 ARGB：排障时一眼能比「背景亮不亮、字该用什么颜色」。
                    "top" to (top?.let { com.cardesk.launcher.core.WallpaperContrast.luma(it.average) } ?: -1),
                    "topBright" to (top?.let { com.cardesk.launcher.core.WallpaperContrast.luma(it.bright) } ?: -1),
                    "center" to (center?.let { com.cardesk.launcher.core.WallpaperContrast.luma(it.average) } ?: -1),
                    "centerBright" to (center?.let { com.cardesk.launcher.core.WallpaperContrast.luma(it.bright) } ?: -1),
                )
                applyBackdropTones(top, center)
            }
        }
    }

    /** 视频播放器的当前位置（毫秒）；没准备好 / 出异常时按 0 算。 */
    private fun videoCurrentPositionMs(): Int = runCatching {
        videoPlayer?.currentPosition ?: 0
    }.getOrDefault(0)

    /**
     * 后台解码自定义壁纸（首帧之后，不阻塞启动）。
     *
     * 只保留一张全屏位图：解码结果直接塞给 BackgroundView，不进壁纸渲染器的 LRU。
     */
    private fun ensureCustomWallpaperLoaded() {
        if (!background.custom) return
        if (background.customBitmap != null) return
        if (customLoading) return
        customLoading = true
        Thread {
            val bmp = com.cardesk.launcher.core.CustomWallpaper.loadScaled(context.applicationContext)
            post {
                customLoading = false
                if (bmp != null && background.custom) {
                    background.customBitmap = bmp
                } else if (bmp == null) {
                    // 文件坏了/被删：安静退回预设，避免一直黑底。
                    prefs.wallpaperCustom = false
                    background.custom = false
                    background.customBitmap = null
                }
            }
        }.start()
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

    /**
     * 仪表歌词颜色选择器（第一百三十七轮）。
     *
     * 复用现成的 [com.cardesk.launcher.ui.dialogs.ColorPickerDialog]，
     * 「恢复默认」= **跟随日夜**（写 0）。
     *
     * 取色器打开时显示的是**当前实际会用的那个色** ——
     * 用户没挑过颜色时那套是跟着日夜的（夜间浅青 / 日间深青），
     * 直接把当前这套喂给取色器，滑杆的位置才落在真正的颜色上。
     *
     * @param slot 0 = 主歌词，1 = 副歌词。
     */
    fun showClusterLyricColorPicker(slot: Int) {
        val picked = if (slot == 0) prefs.clusterLyricMainColor else prefs.clusterLyricSubColor
        val current = if (picked != 0) {
            picked
        } else {
            // 跟投射服务用**同一个解析器**算日夜 ——
            // 取色器打开时显示的色，必须就是实际会投出去的那个色。
            val night = com.cardesk.launcher.core.NightResolver.resolve(prefs, context)
            if (slot == 0) {
                com.cardesk.launcher.cluster.ClusterLyricColors.main(night)
            } else {
                com.cardesk.launcher.cluster.ClusterLyricColors.sub(night)
            }
        }
        showOverlayDialog(
            com.cardesk.launcher.ui.dialogs.ColorPickerDialog(
                context,
                pal,
                current,
                title = if (slot == 0) "主歌词颜色" else "副歌词颜色",
                defaultColor = 0,
            ).apply {
                onPicked = { color ->
                    if (slot == 0) prefs.clusterLyricMainColor = color else prefs.clusterLyricSubColor = color
                    closeOverlayDialog(this)
                    // 颜色改了要立刻投出去（服务活着就原地改样式）。
                    com.cardesk.launcher.cluster.ClusterLyricsService.refresh(context)
                    // 设置页那两行的标签要跟着变（色值 / 「跟随日夜」）。
                    settings?.refreshClusterLyricColorLabels()
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
        // 自定义壁纸（第 105 轮）：设置页里导入/删除后同步到桌面。
        background.custom = prefs.wallpaperCustom &&
            com.cardesk.launcher.core.CustomWallpaper.has(context)
        background.customBitmap =
            if (background.custom) com.cardesk.launcher.core.CustomWallpaper.cachedBitmap() else null
        if (background.custom) ensureCustomWallpaperLoaded()
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
        // 设置里改了日夜来源（跟随高德 / 跟随光感 / 跟随时间）后，
        // 把当前状态推给高德，让地图跟桌面一致
        // （跟随高德模式下不推，见 [pushDayNightToAmap]）。
        pushDayNightToAmap(pal.night)
    }

    private fun applyDockVisibility() {
        if (!::dock.isInitialized) return
        // 第五十六轮：这里用的是**桌面自己**的隐藏开关（`hideHomeDock`），
        // 不再是「隐藏原车 Dock」那个 —— 之前两者共用一个键，
        // 结果勾「隐藏原车 Dock」消失的是桌面自己的 Dock。
        // 第九十四轮起全局 Dock 可以真正占用系统预留区；预留区生效时
        // 桌面自己的 Dock 必须让位（否则会出现「上一条+下一条」两条 Dock）。
        val globalReserved = prefs.globalDockEnabled && runCatching {
            com.cardesk.launcher.core.dock.NavInsets.applied(context)
        }.getOrDefault(false)
        // 设置页（画板 s01..s25）是**整屏**覆盖：底部没有 Dock。
        // 这里必须并进 `hide` —— 单在 showSettings() 里设 GONE 会被 refresh() 里的
        // applyDockVisibility() 立刻改回 VISIBLE（实测两次都被覆盖）。
        // 全局 Dock 只有在**它自己真的画出来**时才顶替桌面这一条。
        // 它因「桌面在前台」/设置页/沉浸 App 收起时如果不画，桌面这条又让位，
        // 整屏就一条 Dock 都没有（第245轮实机：点一次「立即切换」后底部全空）。
        val globalCovering = globalReserved &&
            runCatching { com.cardesk.launcher.core.dock.GlobalDockService.isShowing() }
                .getOrDefault(false)
        val hide = prefs.hideHomeDock || settings != null || globalCovering
        Logx.d(
            LogTags.DOCK,
            "Dock 可见性",
            "hideHomeDock" to hide,
            "hideHomeSwitch" to prefs.hideHomeDock,
            "globalReserved" to globalReserved,
            "globalCovering" to globalCovering,
            "settingsOpen" to (settings != null),
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
        // 「隐藏导航页状态栏」打开时，**只在导航页（page==1）隐藏**，第一页仍显示
        // （第三十二轮起桌面只有两页，没有第三页了）。
        // （原车状态栏的隐藏走 `prefs.hideStatus` → policy_control，不影响这一条。）
        // 第三十七轮：「隐藏导航页状态栏」这个开关的**默认值改成 false**（见 `Prefs.hideNavStatus`），
        // 所以现在两页都显示状态栏 —— 用户要求「桌面第二页也需要显示状态栏」。
        val hideBar = page == PAGE_NAV && prefs.hideNavStatus
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
        // 高度跟着顶部一起算：底部对齐 Dock 上沿（1080 − 112 = 968，第192轮 Dock 高 112 贴底）。
        val navHeight = DOCK_TOP_Y - navTop
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
            "page" to page, "hideNavStatus" to prefs.hideNavStatus, "visible" to !hideBar,
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

    /**
     * 日夜态变了就重新上主题。
     *
     * 只重算配色（`applyTheme` → `build`），不做整套车况/媒体刷新。
     *
     * @return true = 已经重新上过主题（调用方可以提前收工）
     */
    private fun applyDayNightIfChanged(): Boolean {
        if (!::statusBar.isInitialized) return false
        if (resolvePalette().night == pal.night) return false
        Logx.i(LogTags.THEME, "日夜状态变化，重新应用主题")
        applyTheme()
        return true
    }

    /**
     * 启动状态栏连接状态采样（第138轮）。
     *
     * 采样在**后台线程**（2 秒一轮），**变了才**回调主线程。
     * 第一次采样是立刻做的（不等 2 秒）—— 状态栏一上来就该是准的，
     * 而不是先显示 2 秒「什么都没连」。
     */
    private fun startSystemStatusSampling() {
        if (!::statusBar.isInitialized) return
        if (systemStatusSampler != null) return
        val sampler = com.cardesk.launcher.ui.shell.SystemStatusSampler(context)
        systemStatusSampler = sampler
        sampler.start { snapshot ->
            if (!::statusBar.isInitialized) return@start
            statusBar.systemStatus = snapshot
        }
    }

    private fun refresh() {
        if (!::statusBar.isInitialized) return
        // 实车闪屏诊断（第三百零三轮）：心跳放在日夜检查**之前**——
        // 即便这轮触发了整桌重建（applyDayNightIfChanged 返回 true），
        // 计数与心跳也已经发生，节奏不会被「return」吞掉。
        diagRefreshCount++
        maybeDiagHeartbeat()
        if (applyDayNightIfChanged()) return
        // 第222轮：视频壁纸的亮度（画面在独立图层上，只能直接拷视频的 Surface）。
        sampleVideoBackdropIfNeeded()
        val now = Date()
        // 第220轮：**车控信号不再在主线程上读**。
        //
        // 这一行以前是 `currentVehicleInfo()` ——
        // 它内部要走完 5 档车门降级链 + 3 档车窗降级链 + 行程 / 传感器 / 驾驶模式，
        // 一轮 **30~60 次同步 binder 往返**，全在主线程上。
        // 实车（高通 820A）一次往返 1~3ms ⇒ 主线程每秒被堵 50~150ms。
        //
        // 现在由 [com.cardesk.launcher.vehicle.VehicleSignalSampler] 在**后台线程**上采，
        // 主线程只读一份算好的不可变快照 —— 一帧的活变成纳秒级。
        // 口径完全不变：采样频率不变、读的通道不变、降级链顺序不变。
        val info = com.cardesk.launcher.vehicle.VehicleSignalSampler.ensureSnapshot()
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
        // 第125轮：来源图标（真实 App 图标优先，取不到用自绘符号）。
        scenePage.mediaSourceId = currentSourceId(track)
        updateMediaSourceIcon(scenePage.mediaSourceId)
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
        lastVehicleInfo = info
        scenePage.bind(info, state, prefs.wallpaper)
        syncVehicleStatus(info)
        // 车辆页开着时跟着每秒数据刷新（画板 05 的续航 / 胎压 / 门态都是活的）。
        vehiclePage?.let {
            it.carModel = prefs.carModel
            it.bind(info)
        }
        // 第136轮：车门 / 盖未关 → 桌面弹窗提示（全关自动关、可手动关）。
        syncDoorAlert(info)
        // 引导卡每秒重算一次 —— 红绿灯读秒要按秒往下走，
        // 信号超时（超过 TTL 没收到新帧）也要在这一轮里消失。
        // 第 100 轮：**每轮都刷**，巡航卡没有 navGuideRaw 也要能更新/过期。
        applyNavGuide()
        // 车模编号跟着设置走（第三十三轮：**接到第一页胎压卡的自绘图模**上）。
        scenePage.carModel = prefs.carModel
        navPage.carModel = prefs.carModel
        // 第155轮：胎压**数值字号**跟着设置走
        // （设置页滑杆 → `prefs.deskTpmsValueSp` → 场景页胎压卡）。
        // 这个滑杆在 `onProgressChanged` 里就会触发 `onApply` → `refresh()`，
        // 所以拖动过程中数字会实时变大变小。
        scenePage.tpmsValueSp = prefs.deskTpmsValueSp
        // 第111轮：右侧固定「车况卡」的显隐跟着偏好走。
        // 设置页开关 → `prefs.carStatusCardVisible` → 这里 → 场景页。
        scenePage.showCarStatusCard = prefs.carStatusCardVisible
        // 第148轮：「歌曲图片当卡片背景」的开关跟着偏好走。
        //
        // 设置页开关和「点封面图」操作的是**同一个键**（`prefs.navCoverBackdropEnabled`），
        // 所以这里每轮灌一遍就够 —— 谁改都行，下一轮立刻生效。
        navPage.coverBackdropEnabled = prefs.navCoverBackdropEnabled
        // 第114轮：首页中央大字歌词的**整体缩放**跟着设置走
        // （设置页滑杆 → `prefs.sceneLyricScalePct` → 这里 → 场景页）。
        scenePage.lyricScale = prefs.sceneLyricScalePct / 100f
        // 第117轮：布局同理（设置页「歌词布局」→ `prefs.sceneLyricLayout` → 场景页）。
        scenePage.lyricLayout = com.cardesk.launcher.core.media.LyricLayout.fromKey(prefs.sceneLyricLayout)
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
        // —— 第195轮：收藏按钮 / 播放模式按钮 ——
        //
        // `musicPackage` + `actionProbe` 一起决定这两个按钮**显不显示**：
        // 已知包查能力表，未知包看运行时探测（会话发了心形评分 / 暴露了播放模式）。
        // 播放器没有这两条通道时直接隐藏，不亮一个「点了没反应」的按钮。
        navPage.musicPackage = track?.pkg
        navPage.actionProbe = MusicController.get().actionProbe
        navPage.favoriteState = MusicController.get().favoriteState
        navPage.playModeState = MusicController.get().playModeState
        // 第207轮：首页音乐卡也用**同一份**收藏态与能力判据，
        // 两处显示必须一致（不然首页显示未收藏、导航页显示已收藏）。
        scenePage.musicPackage = track?.pkg
        scenePage.actionProbe = MusicController.get().actionProbe
        scenePage.favoriteState = MusicController.get().favoriteState
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

    /**
     * 第206轮：**双指横滑在 `dispatchTouchEvent` 这一层观察并接管**。
     *
     * 为什么不放在 [onInterceptTouchEvent]：模拟器实测（instrumentation）
     * 双指事件派发到 decorView 时，`onInterceptTouchEvent` 只收到 DOWN，
     * POINTER_DOWN/MOVE 被消费了按下的子视图（画中画承载面、地图卡…）
     * 连同它自己的 `requestDisallowInterceptTouchEvent(true)` 一起截走，
     * 手势状态永远是 `twoFinger=false` → 双指怎么滑都不翻页。
     *
     * `dispatchTouchEvent` 是整棵子树的入口，**每个事件都会经过这里**，
     * 不管子视图是否 disallow-intercept。判定成立后：
     * 1. 先给子树补一个 CANCEL（让地图/拖拽停在原地，不继续跟手）；
     * 2. 本次及后续事件全部由自己吞掉，UP 时统一判定翻页。
     *
     * 返回 true 表示「这次事件已经被自己处理掉」，不再向下派发。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (trackTwoFingerSwipe(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 双指手势跟踪。
     *
     * 第207轮修的三个问题（真机实测「双指滑不动」）：
     *
     * 1. **接管之后不再更新中位点** ——
     *    以前 [MotionEvent.ACTION_MOVE] 分支开头就 `if (twoFingerTaken) return`，
     *    于是接管那一刻之后的所有 MOVE 都被丢掉，`twoFingerMid` 永远停在
     *    「刚刚越过 60px 接管线」的位置。抬手时算出来的位移最多也就 60 多 px，
     *    够不到 [PageGesture.TWO_FINGER_PX]（140px）→ **双指怎么滑都不翻页**。
     *    现在 MOVE 无论是否已接管都持续更新中位点/间距。
     * 2. **捏合判定过严** ——
     *    以前只看「间距变化比例 > 18%」，两指初始靠得近时，
     *    手指抖动几个像素就是「20% 变化」→ 正常横滑被误判成捏合。
     *    现在改成绝对量 + 相对量两条线（见 [PageGesture.isTwoFingerPinch]）。
     * 3. **纵向位移用错了基准** ——
     *    以前 `dy` 用的是 `ev.y`（**第一根手指**的 Y），而 `dx` 用的是两指中位点，
     *    两者不同源；两指不在同一水平线上时 `dy` 会莫名很大，
     *    把本该判为横滑的手势卡掉。现在 `dy` 也用中位点。
     */
    private fun trackTwoFingerSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFingerTaken = false
                twoFinger = false
                // 不让父容器拦截；本视图自己的 onInterceptTouchEvent 不受影响。
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && !twoFingerTaken) {
                    twoFinger = true
                    twoFingerIntercepted = false
                    twoFingerX = midX(ev)
                    twoFingerY = midY(ev)
                    twoFingerMid = twoFingerX
                    twoFingerMidY = twoFingerY
                    twoFingerSpan0 = span(ev)
                    twoFingerRatio = 1f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!twoFinger) return twoFingerTaken
                if (ev.pointerCount < 2) return twoFingerTaken
                // ★ 第207轮：**接管之后也要继续更新**中位点与间距 ——
                //   否则抬手时算出来的位移永远停在「刚过接管线」那一点。
                twoFingerMid = midX(ev)
                twoFingerMidY = midY(ev)
                twoFingerRatio = if (twoFingerSpan0 > 1f) span(ev) / twoFingerSpan0 else 1f
                if (twoFingerTaken) return true
                val dx = twoFingerMid - twoFingerX
                val dy = twoFingerMidY - twoFingerY
                val horizontal = abs(dx) > TWO_FINGER_SLOP &&
                    abs(dx) > abs(dy) * PageGesture.TWO_FINGER_AXIS_RATIO &&
                    abs(twoFingerRatio - 1f) < PageGesture.TWO_FINGER_SCALE_TOLERANCE
                if (!horizontal) return false
                // 接管：先让子树收到 CANCEL（地图停止跟手），再吞掉后续事件。
                twoFingerTaken = true
                twoFingerIntercepted = true
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!twoFinger) return false
                val taken = twoFingerTaken
                val dx = twoFingerMid - twoFingerX
                // ★ 用 MOVE 时记下的两指中位点 Y，不是抬手这一刻的 midY(ev) ——
                //   抬手时只剩一根手指，midY(ev) 会退化成单指的 Y，与起点不同源。
                val dy = twoFingerMidY - twoFingerY
                val span = if (twoFingerSpan0 > 1f) twoFingerSpan0 * twoFingerRatio else twoFingerSpan0
                twoFinger = false
                twoFingerTaken = false
                twoFingerIntercepted = false
                if (taken) {
                    val step = PageGesture.twoFingerStep(dx, dy, twoFingerSpan0, span)
                    if (step != 0) {
                        Logx.event(
                            LogTags.APP, "手势：双指横滑翻页",
                            "dx" to dx, "dy" to dy, "span" to span,
                        )
                        swipePage(step)
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                val taken = twoFingerTaken
                twoFinger = false
                twoFingerTaken = false
                twoFingerIntercepted = false
                return taken
            }
        }
        return twoFingerTaken
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 右缘竖滑
                // 命中区是最右 120px，滑动超过 40px 就接管
                edgeTracking = designX(ev.x) >= Design.W - EDGE_WIDTH
                edgeIntercepted = false
                edgeDownY = designY(ev.y)
                threeFinger = false
                // 第206轮：**单指不再翻页**（单指留给画中画里拖地图），
                // 翻页主手势改成双指横滑，见 twoFinger。
                singleTracking = false
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
                    twoFinger = false
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
                // 第206轮：单指不再翻页，翻页走双指（见 intercept 的 twoFinger）。
                singleTracking = false
                singleIntercepted = false
                singleDownX = designX(ev.x)
                singleDownY = designY(ev.y)
                singleDx = 0f
                singleDy = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多指一律作废单指判定（同 [onInterceptTouchEvent]）。
                singleTracking = false
                // 三指横滑换页的**布防点之二**。
                //
                // 为什么 [onInterceptTouchEvent] 里已经布防还要再写一遍：
                // 当这一页没有任何子 View 消费 ACTION_DOWN 时，ViewGroup 的
                // [ViewGroup.mFirstTouchTarget] 为 null，系统此后**不再回调**
                // [onInterceptTouchEvent]（事件从一开始就直接进 onTouchEvent）。
                // 实测：翻到第二页后在同一位置三指按下，intercept 分支完全没执行，
                // threeFinger 恒为 false，三指右滑翻不回来。
                // 这正是第 206 轮双指手势搬进 dispatchTouchEvent 时修过的同一个坑，
                // 三指这条路径当时漏了。
                if (ev.pointerCount >= 3) {
                    twoFinger = false
                    threeFinger = true
                    threeFingerX = midX(ev)
                    threeFingerMid = threeFingerX
                }
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

    /** 两指间距（设计坐标 px）：两指之间的直线距离（捏合判定用）。 */
    private fun span(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = designX(ev.getX(1)) - designX(ev.getX(0))
        val dy = designY(ev.getY(1)) - designY(ev.getY(0))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 三指手势用的中间 X（第三十二轮：换页改成左右三指滑动）。 */
    private fun midX(ev: MotionEvent): Float {
        val n = ev.pointerCount.coerceAtMost(3)
        if (n <= 0) return designX(ev.x)
        var sum = 0f
        for (i in 0 until n) sum += designX(ev.getX(i))
        return sum / n
    }

    /**
     * 两指的中位点 Y（设计坐标）。
     *
     * 第207轮：纵向位移必须和横向位移**同源** ——
     * 横向用两指中位点，纵向也用两指中位点；
     * 以前纵向用的是第一根手指的 Y，两指不在同一水平线上时纵向分量会莫名很大。
     */
    private fun midY(ev: MotionEvent): Float {
        val n = ev.pointerCount.coerceAtMost(3)
        if (n <= 0) return designY(ev.y)
        var sum = 0f
        for (i in 0 until n) sum += designY(ev.getY(i))
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
            // 第152轮：抽屉里的浮层（长按弹出的应用菜单 / 重命名 / 禁用确认）
            // **优先于**抽屉本身关闭。
            //
            // 不这样分级的话，「长按打开菜单 → 按返回」会连抽屉一起关掉，
            // 菜单还残留在抽屉页实例里；下次打开抽屉菜单又盖在上面，
            // 它的遮罩把点击全吃掉，点图标一点反应都没有。
            if (drawerPage.closeTopOverlay()) return true
            closeDrawer()
            return true
        }
        if (pipFullscreen) {
            // 画中画全屏 → 返回键还原（要挂回地图卡 + 恢复留白）
            exitPipFullscreen()
            return true
        }
        // 下面这些浮层都走 [removeAnimated]：退场动画播完才摘层，
        // 否则返回键是「啪」地消失，和点「关闭」的手感对不上。
        taskEditor?.let { dialog ->
            removeAnimated(dialog) { if (taskEditor === dialog) taskEditor = null }
            return true
        }
        taskList?.let { dialog ->
            removeAnimated(dialog) { if (taskList === dialog) taskList = null }
            return true
        }
        // 第五十四轮：叠在配置面板上的应用选择器要先收
        quickAppPicker?.let { dialog ->
            removeAnimated(dialog) { if (quickAppPicker === dialog) quickAppPicker = null }
            return true
        }
        overlayDialog?.let { dialog ->
            removeAnimated(dialog) { if (overlayDialog === dialog) overlayDialog = null }
            return true
        }
        navSlotDialog?.let { dialog ->
            removeAnimated(dialog) { if (navSlotDialog === dialog) navSlotDialog = null }
            return true
        }
        if (vehiclePage != null) {
            closeVehiclePage()
            return true
        }
        if (settings != null) {
            closeSettings()
            return true
        }
        // 第九十五轮补丁：最近任务面板也是全屏浮层，BACK 要先收掉它（否则会一直叠在桌面/设置下面）。
        if (recentTasksPage != null) {
            closeRecents()
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

    /**
     * 收藏态 / 播放模式变了（第一九五轮）。
     *
     * 这两个状态**不是**曲目的一部分（歌没换，收藏态变了），
     * 所以不能塞进 `onMediaChanged` —— 单独一个监听，收到就整页刷新。
     */
    private val actionStateListener = object : MusicController.ActionStateListener {
        override fun onActionStateChanged(
            favorite: com.cardesk.launcher.core.media.MediaActions.FavoriteState,
            playMode: com.cardesk.launcher.core.media.MediaActions.PlayModeState,
        ) {
            refresh()
        }
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
                val lines = timeline.lines.map { it.text }
                // 第三十三轮：第二页组合卡的播放器显示 **5 句**（上 2 + 当前 + 下 2），
                // 整窗居中（expandedLyricWindow）。
                navPage.setLyricWindow(lines, index)
                // 第 113 轮：首页中央大字歌词也改成 **5 句**
                // （上 2 / 上 1 / 当前 / 下 1 / 下 2），字号分三档。
                // 取句跟导航页共用 `LyricWindow.window` —— 同一份实现，两边不会错位。
                scenePage.setLyricWindow(lines, index)
                // 导航页那三句可能被外部直接赋值（占位文案），首页以导航页为准。
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
                // 首页整窗清空（**上 2 / 下 2 也要清**，否则会留下上一首歌的残句）
                scenePage.setLyricWindow(emptyList(), -1)
                scenePage.lyricCurrent = mediaLyricFallback
                navPage.lyricWipe = 0f
                scenePage.lyricWipe = 0f
                slotsBar?.lyricWipe = 0f
            }
        }
    }

    /**
     * 桌面**第一帧**真正画出来的那一刻（启动分段打点，第191轮）。
     *
     * 为什么在这里打、而不是在某个子 View 的 `onDraw` 里：
     * `DockBarView` 是**共享**的 —— 全局 Dock（悬浮窗）是另一个实例，
     * 它先画的话就把打点消费掉了，看不出是桌面的哪一段慢。
     * [dispatchDraw] 只有桌面这一棵树上会走到。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // `markOnce` —— `dispatchDraw` 每帧都会走，不能每次都打点。
        com.cardesk.launcher.core.StartupTrace.markOnce("draw:launcher")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MusicController.get().addListener(mediaListener)        // 导航引导数据源跟着窗口生命周期起停
        // 第195轮：收藏态 / 播放模式变了也要刷（用户可能在播放器自己的界面里收藏了）。
        MusicController.get().addActionStateListener(actionStateListener)
        navGuideSource?.start()
        // 窗口重新 attach：海拔采集在 detach 时停掉了，这里恢复（start 幂等）。
        if (servicesStarted) {
            runCatching { com.cardesk.launcher.core.AltitudeDesk.start(context.applicationContext) }
        }
        // 360 联动同理（detach 时 stop 过；start 内部有幂等与在途去抖）。
        if (servicesStarted) {
            runCatching { com.cardesk.launcher.vehicle.Camera360Link.start(context.applicationContext, prefs) }
        }
        // 车模自动补齐同理（detach 时 stop 过；start 幂等、失败重试会重新挂上）。
        if (servicesStarted) {
            runCatching { com.cardesk.launcher.core.car3d.CarModelAutoSync.start(context.applicationContext) }
        }
        // 方控广播同理（detach 时注销过；start 幂等）。
        if (servicesStarted) {
            runCatching { com.cardesk.launcher.ui.OemKeyRegistry.start(context.applicationContext) }
        }
        // Home 键接管也跟窗口生命周期起停（幂等）。
        homeKeyInterceptor?.start()
        com.cardesk.launcher.core.HomeKeyRuntime.interceptorActive = homeKeyInterceptor?.active == true
        com.cardesk.launcher.core.HomeKeyRuntime.interceptorStatus =
            homeKeyInterceptor?.statusText ?: "未启动"
        // 第138轮：状态栏连接类图标（蜂窝 / WiFi / 热点 / 蓝牙）按**真实状态**显示。
        // 采样器跟窗口生命周期起停 —— 后台时不用采（车机本来就在省电）。
        startSystemStatusSampling()
        // 第220轮：**车控信号采样**也在后台线程上跑（主线程不再做 30~60 次同步 binder）。
        com.cardesk.launcher.vehicle.VehicleSignalSampler.start(context)
        // 第九十三轮：窗口重建（后台回来 / 重新 addView）时把定时刷新重新挂上 ——
        // onDetachedFromWindow() 里 removeCallbacksAndMessages(null) 会把它清掉。
        if (servicesStarted) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            refresh()
        }
        // 第211轮：**壁纸轮播心跳**也跟窗口生命周期走 ——
        // 它现在挂在类里的 `handler` 上，detach 时被 `removeCallbacksAndMessages(null)` 一起清掉了，
        // 所以重新 attach 时要再挂上（否则桌面切到后台再回来，轮播就不动了）。
        // 先 `removeCallbacks` 去重 —— attach 可能被调多次。
        handler.removeCallbacks(carouselTicker)
        handler.postDelayed(carouselTicker, 1000L)
        // 「向高德索要区域信息」也挪到 startServices()（第九十三轮冷启动修复）：
        // 这是要发广播去问车机/高德的跨进程动作，挂在 onAttachedToWindow 里
        // 就等于排在首帧前面。现在等桌面画出第一帧、服务启动时再要。
    }

    override fun onDetachedFromWindow() {
        // 第三百一十八轮：视频解码 executor 关掉（Activity 销毁，不需要再复用）。
        videoCodecExecutor?.shutdown()
        videoCodecExecutor = null
        MusicController.get().removeListener(mediaListener)
        MusicController.get().removeActionStateListener(actionStateListener)
        // 第211轮：这一句会**连壁纸轮播心跳一起清掉** ——
        // 心跳现在挂在类里的 `handler` 上（原来它自己 new Handler，清不掉）。
        handler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(carouselTicker)
        weatherDesk?.stop()
        navGuideSource?.stop()
        com.cardesk.launcher.core.AltitudeDesk.onChanged = null
        com.cardesk.launcher.core.AltitudeDesk.stop()
        com.cardesk.launcher.vehicle.Camera360Link.stop()
        com.cardesk.launcher.core.car3d.CarModelAutoSync.onUpdated = null
        com.cardesk.launcher.core.car3d.CarModelAutoSync.stop()
        com.cardesk.launcher.ui.OemKeyRegistry.stop(context.applicationContext)
        // Home 键接管一起收掉：进程还活着时不该继续替系统处理 Home。
        homeKeyInterceptor?.stop()
        homeKeyInterceptor = null
        com.cardesk.launcher.core.HomeKeyRuntime.onGoHome = null
        com.cardesk.launcher.core.HomeKeyRuntime.onShowRecents = null
        com.cardesk.launcher.core.HomeKeyRuntime.reset()
        // 第138轮：连接状态采样一起停（后台线程 + 定时器都收掉）。
        systemStatusSampler?.stop()
        systemStatusSampler = null
        // 第220轮：车控信号采样一起停（后台线程 + 定时器都收掉）。
        com.cardesk.launcher.vehicle.VehicleSignalSampler.stop()
        // 车机昼夜回调不再往已销毁的视图派发
        com.cardesk.launcher.vehicle.AdaptapiDayNight.detach()
        VehicleController.removeObserver { }
        super.onDetachedFromWindow()
    }
}
