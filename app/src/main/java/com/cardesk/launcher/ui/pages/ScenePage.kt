package com.cardesk.launcher.ui.pages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.BottomCardOrder
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.CarStatus
import com.cardesk.launcher.core.CarStatusItem
import com.cardesk.launcher.core.CarTopGeometryFactory
import com.cardesk.launcher.core.CarTopPainter
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.VehicleStats
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.CardSnapshot
import com.cardesk.launcher.core.car3d.Car3dSettings
import com.cardesk.launcher.core.car3d.Car3dPainter
import com.cardesk.launcher.core.car3d.CarPose
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.CoverAccent
import com.cardesk.launcher.core.media.MediaSources
import com.cardesk.launcher.core.media.MusicCardVisuals
import com.cardesk.launcher.core.nav.AmapTrafficLight
import com.cardesk.launcher.core.nav.DesktopGuide
import com.cardesk.launcher.core.nav.LaneShape
import com.cardesk.launcher.core.nav.LaneShapes
import com.cardesk.launcher.core.nav.NavGuideText
import com.cardesk.launcher.ui.widget.BackgroundView
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.TireOrder
import com.cardesk.launcher.vehicle.VehicleInfo
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 首页 / 场景页（1920×1080）。
 *
 * 版式（全部布局）
 * - 左上导航引导卡 520 宽（**没有导航时整卡隐藏**，见）
 * - 右上天气
 * - 底部一排信息卡：快捷入口(1 张玻璃卡 4 个应用位) / 媒体卡(400) /
 *   空调快捷卡(348) / 胎压卡(350) / 续航卡(剩余宽度)，卡间距 20
 * - 底部居中的壁纸提示（名称 + 9 个圆点）
 *
 * 整页 Canvas 自绘，点击区域在 onMeasure 后按尺寸计算。
 */
class ScenePage(context: Context) : View(context) {

    var onOpenVehicle: (() -> Unit)? = null
    var onOpenNav: (() -> Unit)? = null
    var onOpenPhone: (() -> Unit)? = null
    var onOpenDrawer: (() -> Unit)? = null
    var onOpenMusic: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onSeatHeat: (() -> Unit)? = null
    var onSeatVent: (() -> Unit)? = null
    var onMaxFan: (() -> Unit)? = null
    var onOpen360: (() -> Unit)? = null
    var onOpenHvac: (() -> Unit)? = null
    var onWallpaperDot: ((Int) -> Unit)? = null
    /** 点音乐卡上的「来源行」：打开媒体来源切换菜单（sceneSource 的点击）。 */
    var onOpenSourceMenu: (() -> Unit)? = null

    /** 点快捷入口里的真实应用。 */
    var onOpenApp: ((pkg: String, label: String) -> Unit)? = null

    /** 底部卡片布局变化（`scene_card_layout_v2`）。 */
    var onCardLayoutChanged: ((List<com.cardesk.launcher.core.CardLayout.Slot>) -> Unit)? = null

    // 媒体（MusicController 推送）
    var mediaTitle: String = "暂无播放"

    /** 艺术家：默认是**空串**。 */
    var mediaArtist: String = ""
    var mediaSource: String = "通用音乐"

    /**
     * 媒体源 id（第 125 轮）—— 决定卡片上画哪个图标。
     *
     * 文案（[mediaSource]）给人看，id 才是机器认的键。
     */
    var mediaSourceId: String = "generic"

    /**
     * 媒体源的**真实 App 图标**（第 125 轮）；null = 用自绘符号。
     *
     * 由 [com.cardesk.launcher.ui.LauncherView] 在后台线程取好后塞进来。
     */
    var mediaSourceIcon: Bitmap? = null

    /** 真实图标取不到时画哪个自绘符号（蓝牙 / USB / 音符）。 */
    var mediaSourceIconKind: com.cardesk.launcher.core.media.MediaSourceIconKind =
        com.cardesk.launcher.core.media.MediaSourceIconKind.NOTE

    var mediaPlaying: Boolean = false
    var mediaArtwork: Bitmap? = null

    /**
     * 播放位置（毫秒）—— 音乐卡进度环用。
     */
    var mediaPositionMs: Long = 0L

    /** 曲目总时长（毫秒）—— 音乐卡进度环用；0 = 不知道总时长（不画进度）。 */
    var mediaDurationMs: Long = 0L

    /** 封面主色 —— 封面光晕用。null = 自动从 [mediaArtwork] 采样。 */
    var mediaAccent: Int? = null

    /** 自动采样封面主色时缓存的位图（按引用比较，位图换了才重算）。 */
    private var accentArtwork: Bitmap? = null

    /** 自动采样封面主色的结果。 */
    private var accentSampled: Int = CoverAccent.DEFAULT_ACCENT

    /**
     * 封面光晕用的主色。
     *
     * 从封面像素里取平均色
     * 我们已有 [CoverAccent.sample]
     * 这里只补「按位图缓存」这一层，避免每秒 ticker 都重采样整张封面。
     */
    private fun accentColor(): Int {
        mediaAccent?.let { return it }
        val art = mediaArtwork
        if (art !== accentArtwork) {
            accentArtwork = art
            accentSampled = CoverAccent.sample(art)
        }
        return accentSampled
    }

    // ------------------------------------------------------------------
    // 中央大字歌词
    //
    // 场景页**没有**歌词（里只有壁纸 / 引导卡 / 天气 /
    // 底部卡片 / 来源菜单 / 壁纸提示，歌词出现在 Dock 媒体槽、导航页组合卡、
    // 音乐页三处。
    //
    // 这里按用户要求在首页中部加**中央大字歌词**填掉留白（超出增强）
    // 并把 Dock 的两行小字隐藏 —— 全车只保留一处歌词，避免信息重复。
    // ------------------------------------------------------------------

    /** 上一句歌词（淡显）。 */
    var lyricPrev: String = ""

    // 第117轮：首页中央从「五行」改成「三行 / KTV 两行」两种布局（见 [lyricLayout]），
    // 所以原来「上 2 句 / 下 2 句」那两个字段连同它们的绘制一起去掉了。
    // （导航页组合卡还是 5 句，那是 `NavPage` 自己的字段，没动。）

    /**
     * 胎压**数值**字号（第155轮）。
     *
     * 设置页「车辆信息 → 胎压数字字号」滑杆 → `prefs.deskTpmsValueSp` → 这里
     * → [drawTireCard] 里那四个胎压数字。
     *
     * ## 之前为什么写死
     *
     * 这个字号以前在渲染里是写死的 `28f`，设置页那个滑杆只写盘没人读 ——
     * 拖了不生效（第154轮审计标成「暂不可用」）。
     *
     * ## 为什么单位标签不跟着缩
     *
     * 「kPa」单位是 14px 的弱文字，它是**标注**不是数据；
     * 跟着数字一起放大，数字一大单位就糊成一团。
     * 数字大了以后单位的位置仍然按「量出来的实际宽度 + 8px」算，
     * 所以绝不会盖住数字（这是第三十七轮修过的问题，不能退回去）。
     */
    var tpmsValueSp: Int = DEFAULT_TPMS_VALUE_SP
        set(value) {
            val next = value.coerceIn(TPMS_VALUE_SP_MIN, TPMS_VALUE_SP_MAX)
            if (field == next) return
            field = next
            invalidate()
        }

    /**
     * 车型编号。
     *
     * 第三十三轮起**接到第一页底部的胎压卡**上（`drawTireCard` → `drawCarTop`），
     * 按 [com.cardesk.launcher.core.CarTopGeometryFactory] 的参数画出自绘俯视车模。
     */
    var carModel: String = com.cardesk.launcher.core.CarModels.MODEL_03
        set(value) {
            val next = com.cardesk.launcher.core.CarModels.normalize(value)
            if (field == next) return
            field = next
            invalidate()
        }

    /** 第183轮：最近一次画出的四颗胎压中心（画布坐标）。测试/验收用来核对「与门态无关」。 */
    internal var lastTirePillCenters: List<PointF> = emptyList()

    /** 第184轮：最近一次绘制车模用的图区（画布坐标），供测试把 mask 像素映射回来。 */
    internal var lastCarImageRect: RectF? = null

    /** 临时诊断：胶囊布局用到的 r / image / ref。 */
    internal var debugPillGeom: String = ""

    /**
     * 当前主题下的车模配色（夜 / 日两套）。
     *
     * 第五十一轮：车模颜色原来写死成夜间的深蓝，日间白底上就是「夜里的车」；
     * 现在按 [Skin.isNight] 选 [com.cardesk.launcher.core.CarColors] 里对应的一套。
     */
    private val carColors: com.cardesk.launcher.core.CarColors
        get() = if (Skin.isNight) {
            com.cardesk.launcher.core.CarColors.night
        } else {
            com.cardesk.launcher.core.CarColors.day
        }

    /**
     * 写实车模（第163轮）：胎压卡 / 车况卡两个槽位共用一个状态机。
     *
     * 同一台车、同一套动画，只是位图尺寸不同（72×144 / 88×176），
     * 尺寸差异由 [Car3dAssets] 的 LRU 分别缓存。
     */
    private val car3d = Car3dPainter.Scene(this)

    /** 当前句歌词（大号高亮）。 */
    var lyricCurrent: String = ""

    /**
     * 是否显示**中央大字歌词**（当前句大号 + 上下句淡出）。
     *
     * 默认 true —— 这是第二十四轮按用户要求加的首页视觉重心。
     *
     * 但导航栏「槽位条」形态下，是把歌词放在**导航栏歌词槽**里的
     *
     * 同一首歌在屏上出现两处歌词反而是错的 ——
     * 所以那种形态下这个开关置 false，由导航栏歌词槽独揽歌词显示。
     */
    var showCenterLyric: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 当前句的**擦除比例** 0..1（已唱到的部分）。
     *
     * 当前行不是纯色，而是「已唱部分强调色 + 未唱部分暗色」。
     * 比例由播放位置算出来（`LyricTimeline.wipeFraction`）。
     */
    var lyricWipe: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            lyricWipeState.setTarget(field)
            invalidate()
        }

    /** 擦除动画状态（指数逼近目标值）。 */
    private val lyricWipeState = com.cardesk.launcher.core.media.LyricWipeState()

    /**
     * **直接**把当前句的擦除比例落到指定值（不走指数逼近动画）。
     *
     * 场景：播放进度被外部直接设定（跳歌 / seek），或者渲染夹具要还原设计板
     * 里「当前句已经唱到 38%」的那一帧 —— 都需要立刻落位而不是慢慢追。
     */
    fun setLyricWipeImmediate(fraction: Float) {
        lyricWipe = fraction
        lyricWipeState.jumpTo(fraction)
        invalidate()
    }

    /** 下一句歌词（淡显）。 */
    var lyricNext: String = ""

    // 第117轮：「下 2 句」那个字段跟 [lyricPrev2] 一起删掉了（首页没有五行布局了）。

    /**
     * 首页中央大字歌词的**布局**（第117轮）。
     *
     * 三行（默认，上 1 / 当前 / 下 1）或 KTV 式两行（**当前句在上、下一句在下**）。
     * 清单在 [com.cardesk.launcher.core.media.LyricLayout]（纯 Kotlin，可单测）。
     */
    var lyricLayout: com.cardesk.launcher.core.media.LyricLayout =
        com.cardesk.launcher.core.media.LyricLayout.THREE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 歌词**整体缩放**（0.7 ~ 1.4，1 = 设计原值）。
     *
     * 第 114 轮：字号做成了设置里可调（`Prefs.sceneLyricScalePct`）。
     * 缩放同时作用于三个字号和两个行距；
     * 歌词块**始终居中**（见 [LYRIC_BLOCK_CENTER_Y]），
     * 放大时上下均匀生长，不会往一边偏。
     */
    var lyricScale: Float = 1f
        set(value) {
            val next = value.coerceIn(0.7f, 1.4f)
            if (field == next) return
            field = next
            invalidate()
        }

    /**
     * 一次性刷入歌词窗口（**上一句 / 当前句 / 下一句**）。
     *
     * 第117轮：首页中央改成「三行 / KTV 两行」两种布局，
     * 但**取句的口径不变**（还是「离当前句最近的三句」）——
     * 布局只决定「画哪几行、画成多大」，
     * 画不画下一句由 [lyricLayout] 决定，句子的来源仍然是同一份。
     *
     * 取句逻辑复用 [com.cardesk.launcher.core.media.LyricWindow.lineAt] ——
     * 跟导航页组合卡是同一份实现，两边不会出现「首页比导航页少两句」的错位。
     *
     * @param lines 全部歌词行
     * @param index 当前行下标（< 0 表示没有歌词，整窗清空）
     */
    fun setLyricWindow(lines: List<String>, index: Int) {
        val w = com.cardesk.launcher.core.media.LyricWindow.window(
            lines,
            index,
            intArrayOf(-1, 0, 1),
        )
        lyricPrev = w.getOrElse(0) { "" }
        lyricCurrent = w.getOrElse(1) { "" }
        lyricNext = w.getOrElse(2) { "" }
    }

    // 天气（`WeatherDesk`）
    /** 天气城市（`WeatherDesk.KEY_CITY`）。 */
    var weatherCity: String = ""
        set(value) {
            field = value
            // 排障场景：「天气一直显示获取中 / 城市不对」——每次刷新都打一条，落盘可回溯。
            logWeatherRefresh()
        }

    /** 天气描述（`WeatherDesk.KEY_SKY`），决定天气图标。 */
    var weatherSky: String = ""
        set(value) {
            field = value
            logWeatherRefresh()
        }

    /** 天气温度（整数，`String.valueOf(num)`）；null = 用车外温度兜底。 */
    var weatherTemp: Int? = null
        set(value) {
            field = value
            logWeatherRefresh()
        }

    /** 上一次已落盘的天气快照（用于去重，避免车辆信号刷新时重复打日志）。 */
    private var lastWeatherLog: Triple<String, String, Int?>? = null

    /**
     * 天气数据刷新落盘日志（城市 / 天气 / 温度）。
     *
     * 排障场景：「桌面右上角天气不显示或一直显示获取中」——
     * 这里只在三项数据整体变化时记一条，能看出天气到底有没有拉到。
     */
    private fun logWeatherRefresh() {
        val snapshot = Triple(weatherCity, weatherSky, weatherTemp)
        if (snapshot == lastWeatherLog) return
        lastWeatherLog = snapshot
        Logx.i(
            LogTags.SCENE,
            "天气数据刷新",
            "city" to weatherCity,
            "sky" to weatherSky,
            "temp" to weatherTemp,
        )
    }

    /**
     * 燃油百分比：续航卡的进度条按**真实油量**走
     * （`SceneDesktop.paintRangeProgress(fuelPercent)`）。
     * null = 没有油量数据，进度条为空。
     */
    var fuelPercent: Int? = null

    private var info: VehicleInfo? = null
    private var state: HvacState? = null
    private var wallpaperIndex = 0

    /**
     * 车窗的**本地乐观状态**（里车窗图标是「点击关窗」的入口
     * 显示的是本地取反后的值，不等车端回读。
     *
     * 第三十六轮：车端回读（`WINDOW_POS` 属性）已经接上了，
     * 这个字段降级成**「读不到车端状态时的兜底」**，以及「刚下发完还没回读」时的过渡值。
     */
    var windowClosed: Boolean = true
        set(value) { field = value; invalidate() }

    /**
     * 「刚下发完、还没等到车端回读」的保护期截止时刻（`SystemClock.uptimeMillis`）。
     *
     * 点车窗图标后立刻回读，车窗是**机械动作**，通常要几百毫秒才有回读值；
     * 这段时间如果直接用回读值覆盖，会出现「图标闪一下又跳回去」。
     * 所以下发后 [WINDOW_PENDING_MS] 毫秒内以本地值为准，之后交给回读值。
     */
    private var windowPendingUntil = 0L

    /** 保护期内显示的本地乐观值（true = 关）。 */
    private var windowPendingClosed = true

    /** 行程卡「四项都读不到」的状态是否已经记过日志（避免每秒刷屏）。 */

    /** 车况卡「全读不到」的状态是否已经记过日志（避免每秒刷屏）。 */
    private var carStatusEmptyLogged = false

    /** 点击车窗图标的回调（用于下发关窗指令 0/50）。 */
    var onToggleWindow: ((Boolean) -> Unit)? = null

    /**
     * 车况卡整卡点击（第166轮 C 版）：`LauncherView` 弹「车辆状态」面板。
     */
    var onOpenVehicleStatus: (() -> Unit)? = null

    fun bind(info: VehicleInfo, state: HvacState, wallpaper: Int) {
        this.info = info
        this.state = state
        wallpaperIndex = wallpaper
        // 排障场景：「场景页数据不刷新」——DEBUG 级，只进 Logcat，不占车机存储。
        Logx.d(LogTags.SCENE, "场景页数据绑定", "wallpaper" to wallpaper)
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val hits = LinkedHashMap<String, RectF>()

    /** 音乐卡来源行的设计坐标矩形（来源菜单锚点）；未绘制时为 null。 */
    val sourceAnchorRect: RectF? get() = hits["source"]

    // 几何常量
    private val left = 44f
    private val rightEnd = Design.W - 44f
    private val bottomMargin = 166f
    private val cardH = 150f
    private val cardBottom = Design.H - bottomMargin
    private val cardTop = cardBottom - cardH

    // ---------------------------------------------------------------- 导航引导

    /**
     * 当前导航引导数据；null 时引导卡**整体隐藏**。
     *
     * 模型来自 `core/nav/NavGuideModel`（已按
     * 把文案算好，布局 的 初始
     * `visibility="gone"`，没有导航时不占位。
     */
    var navGuide: DesktopGuide? = null
        set(value) { field = value; invalidate() }

    /** 设置导航引导数据（null = 隐藏引导卡）。 */
    fun setGuide(guide: DesktopGuide?) {
        navGuide = guide
    }

    // ---------------------------------------------------------------- 快捷入口

    /** 快捷入口位。 */
    data class QuickApp(val pkg: String, val label: String, val icon: Drawable?)

    private var quickApps: List<QuickApp> = emptyList()

    /** 直接指定快捷入口（测试 / 外部注入用）。 */
    fun setQuickApps(apps: List<QuickApp>) {
        quickApps = apps
        invalidate()
    }

    /**
     * 读取真实可启动应用作为快捷入口
     * 取前 4 个，显示真实图标与名称。
     */
    fun refreshQuickApps() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            // 排障场景：「快捷入口是空的」——查询失败必须留痕，否则看不出是没权限还是没应用。
            Logx.w(LogTags.SCENE, "快捷入口列表查询失败", t, "reason" to t.message)
            emptyList()
        }
        quickApps = resolved.take(QUICK_SLOTS).map { r ->
            QuickApp(
                r.activityInfo?.packageName.orEmpty(),
                r.loadLabel(pm)?.toString().orEmpty(),
                try { r.loadIcon(pm) } catch (t: Throwable) { null },
            )
        }
        // 排障场景：「快捷入口图标不刷新 / 数量不对」。
        Logx.i(
            LogTags.SCENE,
            "快捷入口列表加载",
            "total" to resolved.size,
            "shown" to quickApps.size,
            "pkgs" to quickApps.joinToString(",") { it.pkg },
        )
        invalidate()
    }

    /**
     * 行程卡（第十二轮）：2×2 紧凑版。
     *
     * | 格子 | 数据源 |
     * |---|---|
     * | 里程 | `ITripData.getLatestDrivingInfo()` 的本次行程里程 |
     * | 时长 | `ITripData.getLatestDrivingInfo()` 的本次行程时长 |
     * | 平均油耗 | VHAL `TS_TripAverageConsumption`（读不到时回落 `ITripData`） |
     * | 总里程 | 里程表 `ISensor` / `PERF_ODOMETER` |
     *
     * ## 里程 / 时长为什么换源（第十二轮）
     *
     * 原来这两项读 VHAL `TS_TripDistance / TS_TripTime`，
     * 而真车上那两个属性**读不到**（诊断包实测：
     * `行程1 原始信号 distanceRaw=null distanceVia=- format=-1 timeRaw=null`）——
     * 桌面上一路显示「--」。
     *
     * 现在改读 `ITripData` 那条路（**原来行程2 卡用的就是它**），
     * 真车上是通的（诊断包：`行程里程/时长：8.5 km / 31.0 分钟`）。
     */
    private fun drawTripCard(canvas: Canvas, rect: RectF) {
        card(canvas, rect)
        val i = info
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        // 第189轮：标题字号 22 → 24（[TRIP_TITLE_SP]）。
        text.textSize = TRIP_TITLE_SP
        text.color = Skin.TEXT_DIM
        canvas.drawText("行程", rect.left + 20f, rect.top + TRIP_TITLE_BASE, text)

        val pad = 20f
        val cw = (rect.width() - pad * 2f) / 2f
        val colX = floatArrayOf(rect.left + pad, rect.left + pad + cw)
        val rowTop = floatArrayOf(rect.top + TRIP_CELL_TOP_1, rect.top + TRIP_CELL_TOP_2)
        val cellW = cw - 6f
        // 第189轮：四个数值**一起**量，取都放得下的最大字号（见 [fitValueSize]）——
        // 「平均油耗 6.8 L/100km」是最长的那个，其它三格跟着降到同一档，
        // 免得同一张卡里两个格子的数字一大一小。
        val values = listOf(
            VehicleStats.formatTripDistanceKm(i?.tripKm),
            VehicleStats.formatTripDuration(i?.tripDurationMs),
            VehicleStats.formatFuelConsumption(i?.trip1AvgFuel),
            VehicleStats.formatOdometer(i?.odometerKm?.takeIf { it > 0 }),
        )
        val narrow = cellW < 150f
        val valueSp = fitValueSize(
            values,
            cellW,
            if (narrow) TRIP_VALUE_SP_NARROW else TRIP_VALUE_SP,
            TRIP_VALUE_SP_MIN,
        )
        val labelSp = if (narrow) TRIP_LABEL_SP_NARROW else TRIP_LABEL_SP
        drawTripCell(canvas, colX[0], rowTop[0], "里程", values[0], cellW, labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[0], "时长", values[1], cellW, labelSp, valueSp)
        drawTripCell(canvas, colX[0], rowTop[1], "平均油耗", values[2], cellW, labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[1], "总里程", values[3], cellW, labelSp, valueSp)
    }

    /**
     * 卡片库新增的「车门车窗」卡（第九十五轮 C2 迭代 2）：四门/车窗/后备箱/引擎盖汇总。
     */
    private fun drawStatusBottomCard(canvas: Canvas, rect: RectF) {
        card(canvas, rect)
        val i = info
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        // 第189轮：和行程卡同一档字号（两个卡在底排并排，字号不一致会很扎眼）。
        text.textSize = TRIP_TITLE_SP
        text.color = Skin.TEXT_DIM
        canvas.drawText("车门车窗", rect.left + 20f, rect.top + TRIP_TITLE_BASE, text)

        val doors = i?.doorsClosed
        val doorText = when {
            doors == null -> "—"
            doors.any { it == null } -> "部分不可读"
            doors.all { it == true } -> "全关"
            else -> "${doors.count { it == false }} 扇未关"
        }
        val windows = i?.windowsOpen
        val windowText = when {
            windows == null -> "—"
            windows.isEmpty() -> "—"
            windows.none { it } -> "全关"
            else -> "${windows.count { it }} 扇开"
        }
        val trunkText = when (i?.trunkClosed) {
            null -> "—"
            true -> "关"
            false -> "开"
        }
        val hoodText = when (i?.hoodClosed) {
            null -> "—"
            true -> "关"
            false -> "开"
        }
        val pad = 20f
        val cw = (rect.width() - pad * 2f) / 2f
        val colX = floatArrayOf(rect.left + pad, rect.left + pad + cw)
        val rowTop = floatArrayOf(rect.top + TRIP_CELL_TOP_1, rect.top + TRIP_CELL_TOP_2)
        val cellW = cw - 6f
        // 第189轮：和行程卡一样，四个值一起量字号（这里的最长值是「部分不可读」）。
        val values = listOf(doorText, windowText, trunkText, hoodText)
        val narrow = cellW < 150f
        val valueSp = fitValueSize(
            values,
            cellW,
            if (narrow) TRIP_VALUE_SP_NARROW else TRIP_VALUE_SP,
            TRIP_VALUE_SP_MIN,
        )
        val labelSp = if (narrow) TRIP_LABEL_SP_NARROW else TRIP_LABEL_SP
        drawTripCell(canvas, colX[0], rowTop[0], "车门", values[0], cellW, labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[0], "车窗", values[1], cellW, labelSp, valueSp)
        drawTripCell(canvas, colX[0], rowTop[1], "后备箱", values[2], cellW, labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[1], "引擎盖", values[3], cellW, labelSp, valueSp)
    }

    // ---------------------------------------------------------------- 底部卡片顺序

    /** 底部卡片布局：顺序 + 尺寸档位 + 显隐（`scene_card_layout_v2`）。 */
    var cardLayout: List<com.cardesk.launcher.core.CardLayout.Slot> =
        com.cardesk.launcher.core.CardLayout.DEFAULT
        set(value) {
            val next = com.cardesk.launcher.core.CardLayout.normalize(value)
            if (next == field) return
            field = next
            // 排障场景：「拖拽/改尺寸没生效」——布局变化点用 DEBUG（拖动过程中每帧都会变）。
            Logx.d(LogTags.SCENE, "桌面卡片布局变化", "layout" to com.cardesk.launcher.core.CardLayout.encode(next))
            invalidate()
        }

    /** 布局持久化通道（由宿主注入，例如 `CardLayout.PrefsStore(prefs)`）。 */
    var layoutStore: com.cardesk.launcher.core.CardLayout.Store? = null

    // ---------------------------------------------------------------- 右侧车况卡

    /**
     * **右侧固定车况卡**是否显示（车门 / 车窗 / 引擎盖 / 后备箱俯视车模）。
     *
     * ## 它和底部卡片行是两套东西
     *
     * - 底部卡片行：由 [cardLayout] 管顺序 / 尺寸 / 显隐，行内可拖拽；
     * - 车况卡：位置固定（右侧）、宽度固定（`CAR_STATUS_CARD_W`）、
     *   **不进底部卡片行**（不进 `CardLayout.ALL`）。
     *
     * 所以它有自己的开关（`Prefs.carStatusCardVisible`，默认 true），
     * 由宿主（`LauncherView`）从偏好同步过来。
     *
     * ## 第111轮
     *
     * 以前这张卡是**无条件**画的，而设置页「桌面卡片 → 车况」的开关
     * 控制的是底部行里那张**续航卡**（id = `car`，画里程 + 油量条）——
     * 两张卡被同一个名字混起来了。本轮把车况卡补上开关，
     * 底部行那张改叫「续航」，两者各管各的。
     */
    var showCarStatusCard: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // ---------------------------------------------------------------- 壁纸提示

    private val main = Handler(Looper.getMainLooper())
    private var hintVisible = false
    private val hideHintRunnable = Runnable {
        hintVisible = false
        invalidate()
    }

    /** 显示壁纸提示条（1.6s 后自动隐藏）。 */
    fun showWallpaperHint() {
        hintVisible = true
        main.removeCallbacks(hideHintRunnable)
        main.postDelayed(hideHintRunnable, HINT_AUTO_HIDE_MS)
        // 排障场景：「壁纸名字提示条不出现」——DEBUG 级（拖动时会反复触发）。
        Logx.d(LogTags.SCENE, "壁纸提示条显示", "wallpaper" to wallpaperIndex)
        invalidate()
    }

    /** 隐藏壁纸提示条。 */
    fun hideWallpaperHint() {
        hintVisible = false
        main.removeCallbacks(hideHintRunnable)
        invalidate()
    }

    init {
        refreshQuickApps()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        layoutStore?.load()?.let { cardLayout = it }
        // 排障场景：「开机后卡片顺序/尺寸和上次不一样」——把实际读到的布局落盘。
        Logx.i(LogTags.SCENE, "桌面卡片布局加载", "layout" to com.cardesk.launcher.core.CardLayout.encode(cardLayout))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        com.cardesk.launcher.core.StartupTrace.markOnce("draw:scene")
        hits.clear()
        drawGuide(canvas)
        drawWeather(canvas)
        // 第九十六轮：天气下面那张「行程数据」卡挪到底部卡片行，这里不再画。
        // 第111轮：右侧车况卡有自己的开关（`showCarStatusCard`），关掉时整张卡不画。
        drawCarStatusCard(canvas)
        drawCenterLyric(canvas)
        drawBottomCards(canvas)
        drawWallpaperHint(canvas)
    }

    // ------------------------------------------------------------------ 中央大字歌词

    /**
     * 中央大字歌词（超出增强）。
     *
     * 场景页中部是**刻意留白**的（车机桌面把中部留给壁纸），歌词只出现在
     * Dock 媒体槽 / 导航页组合卡 / 音乐页三处。这里按用户要求在首页加一处
     * 视觉重心。
     *
     * 第117轮：从**五行**改成**两种可切布局**（[lyricLayout]）：
     *
     * ```
     * 三行（默认）              KTV 式两行
     *   上 1 句（40px，SOFT）      当前 句（40px 粗体）  ←
     *   当前 句（56px 粗体）  ←    下 1 句（40px，SOFT）
     *   下 1 句（40px，SOFT）
     * ```
     *
     * **KTV 两行两句一样大**（都是 40px）——
     * 用户要求「前后句保持大小一样，不需要区分前后句大小」。
     * 当前句靠**粗体 + 青色逐字擦除**认出来，不靠字号。
     *
     * **为什么当前行不总在中间**：三行是「上 1 / 当前 / 下 1」（当前句夹在中间），
     * KTV 式是「**当前句在上、下一句在下**」（唱过的那句往上走掉，
     * 屏幕上永远是「正在唱的 + 马上要唱的」这一对）。
     * 所以当前行下标由布局给（三种含义互不相同：三行 = 1 且在中间、
     * KTV = 0 且在最上）。
     *
     * **为什么字号分档**：同字号会糊成一片，扫一眼看不出哪句是「正在唱」。
     * 现在「字号越大越靠近当前句 + 颜色越亮越靠近当前句」，
     * 两层线索叠在一起，视线自然落到当前那一句。
     * （第117轮按用户要求**只减行数、不改字号**：三行沿用原来的 40 / 56 / 40。）
     *
     * **为什么当前行不一定用最大档**：KTV 两行是用户明确要求
     * 「前后句保持大小一样」的 —— 那种布局里**不靠字号区分前后句**，
     * 改靠颜色（当前句亮 / 衬句暗）+ 逐字擦除。
     * 所以字号档也由布局清单给，绘制代码不假设「当前行最大」。
     *
     * **为什么整块居中而不是写死某一行**：第 114 轮把字号做成了设置里可调，
     * 字号一变，整块的高度就变 —— 如果锚在「当前行基线」上，
     * 放大时整块会往上顶、缩小时会往下坠，位置跟着字号漂。
     * 所以锚点改成**块中心**（[LYRIC_BLOCK_CENTER_Y]）：
     * 先按字号算出各条基线的相对位置，再把整块平移到中心。
     * 这样不管用户把字号调到 70% 还是 140%，歌词块都在同一个位置居中。
     *
     * **为什么基线算出来而不是写死**：行距是**像素**，但一行文字的上下留白
     * （ascent / descent）取决于字号和字体。写死基线一旦改字号，
     * 就会「两行贴在一起」或「中间裂开一大段」；
     * 所以这里按 Paint 的**真实度量** + 行距常量推基线，
     * 以后再调字号不用重新量坐标。
     *
     * 位置避开左上引导卡（x 44~664, y 73~387）、右上天气（y 75~223），
     * 也不压到底部卡片区（y 764~914）。
     *
     * 所有行都为空 → 整块不画（没有歌词时不占位、不显示占位文案）。
     */
    private fun drawCenterLyric(canvas: Canvas) {
        if (!showCenterLyric) return
        val layout = lyricLayout
        val rows = layout.rows
        val current = layout.currentIndex
        // 锚定布局（KTV 两行）= 每行一个定死的锚点；居中块布局（三行）= null。
        val anchors = layout.anchors
        // 行序 = 最上 → 最下；每行是什么由布局的 slots 决定。
        val contents = arrayOf(lyricPrev, lyricCurrent, lyricNext)
        // 当前布局用不到的行不画（KTV 两行不用「上一句」）。
        val used = Array(rows) { contents[layout.slots[it] + 1] }
        if (used.all { it.isEmpty() }) return
        val cx = Design.W / 2f
        text.textAlign = Paint.Align.CENTER

        // 擦除动画：只要这块在画就推进（当前句为空时推进也无害）。
        val moving = lyricWipeState.advance(System.currentTimeMillis())
        if (moving) postInvalidateOnAnimation()

        // 字号与行距一起按设置里的缩放放大/缩小（第 114 轮）。
        val k = lyricScale
        // 每行字号：按布局给的**档位**取（远 / 近 / 当前）。
        val sizes = FloatArray(rows) { lyricSizeOf(layout.sizeTiers[it]) * k }
        // 行距：基础行距 × 布局的行距倍数 × 用户的缩放。
        // KTV 式两行是「分开的两块」，倍数大于 1（见 LyricLayout.gapScale）。
        val gap = LYRIC_GAP_MAIN * layout.gapScale * k
        // 每行颜色：越靠近当前行越亮。
        val colors = IntArray(rows) { lyricColorOf(layout.sizeTiers[it]) }

        // —— 按真实字体度量推各行基线 ——
        // 相邻两行的基线间距 = 下行的 ascent + 行距 + 上行的 descent。
        // 每行按**自己**的 typeface 量（当前句是粗体，字形的上下边界略有不同），
        // 不能沿用上一次绘制残留的 typeface —— 否则量出来的间距会飘。
        val asc = FloatArray(rows)
        val desc = FloatArray(rows)
        for (i in 0 until rows) {
            text.typeface = if (i == current && layout.boldCurrent) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
            text.textSize = sizes[i]
            val fm = text.fontMetrics
            asc[i] = -fm.ascent
            desc[i] = fm.descent
        }
        // 基线：两种布局两套算法。
        //
        // - **居中块**（三行）：按字号算相对基线，再把整块平移到中心；
        // - **锚定**（KTV 两行）：每行一个定死的锚点（错落阶梯），不算、不平移。
        //
        // 基线规则走**共用**的排版（跟设置页里的预览条同一份）——
        // 两边各写一套的话，预览跟实际画出来的东西会对不上。
        val base = if (anchors != null) {
            com.cardesk.launcher.core.media.LyricBlockLayout.anchoredBaselines(anchors)
        } else {
            val b = com.cardesk.launcher.core.media.LyricBlockLayout.baselines(
                asc,
                desc,
                gap,
                rows,
                current,
            )
            val shift = com.cardesk.launcher.core.media.LyricBlockLayout.centerShift(
                asc,
                desc,
                b,
                LYRIC_BLOCK_CENTER_Y,
                rows,
            )
            for (i in 0 until rows) b[i] += shift
            b
        }

        // —— 衬句（当前行以外的那些）——
        // 有导航引导卡时，**够高**（字顶在引导卡下沿之上）的行要收窄宽度：
        // 歌词居中在 x=960，太宽就会伸到引导卡底下、被卡片压住半行字。
        // 宁可截断加省略号，也不让两行字叠在一张卡上。
        val guideOn = navGuide != null
        for (i in 0 until rows) {
            if (i == current) continue
            val s = used[i]
            if (s.isEmpty()) continue
            text.typeface = Typeface.DEFAULT
            text.textSize = sizes[i]
            text.color = colors[i]
            val inkTop = base[i] - asc[i]
            val leftX = anchors?.get(i)?.leftX
            val w = if (guideOn && inkTop < LYRIC_GUIDE_CARD_BOTTOM) {
                LYRIC_GUIDE_CLEAR_W
            } else {
                lyricMaxWidth(leftX)
            }
            if (leftX != null) {
                // 锚定布局：这一行从自己的左边缘起排（不居中）。
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(clipLabel(s, w), leftX, base[i], text)
            } else {
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(clipLabel(s, w), cx, base[i], text)
            }
        }

        // —— 当前句（视觉焦点）—— 带**擦除高亮** ——
        // 已唱到的部分用强调色，还没唱到的部分保持暗色，比例随播放位置往前推。
        if (used[current].isNotEmpty()) {
            text.typeface = if (layout.boldCurrent) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
            text.textSize = sizes[current]
            val curLeftX = anchors?.get(current)?.leftX
            val mainText = clipLabel(used[current], lyricMaxWidth(curLeftX))
            com.cardesk.launcher.ui.widget.LyricWipeText.draw(
                canvas = canvas,
                text = mainText,
                cx = cx,
                baseline = base[current],
                paint = text,
                activeColor = Skin.ACCENT,
                inactiveColor = Skin.TEXT,
                fraction = lyricWipeState.display,
                edgePx = com.cardesk.launcher.core.media.LyricWipe.edgeWidth(resources.displayMetrics.density),
                maxWidth = lyricMaxWidth(curLeftX),
                leftX = curLeftX ?: 0f,
            )
        }

        // 还原画笔状态（`text` 是整页共用的，别把「粗体 56px」留给后面的绘制）。
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = LYRIC_FAR_SIZE
        text.color = Skin.TEXT_DIM
    }

    /**
     * 一行歌词能占多宽（设计 px）。
     *
     * - 居中布局：固定 [LYRIC_MAX_W]（歌词居中在 x=960，再宽就伸到车况卡底下）；
     * - **锚定布局**（KTV 两行）：那一行从自己的左边缘起排，
     *   右边界的**安全线是车况卡的左沿**（[LYRIC_SAFE_RIGHT]）——
     *   下一句错开到 x=776 之后，能用的宽度只剩那么多。
     *
     * @param leftX 这一行的左边缘 x；null = 居中布局
     */
    private fun lyricMaxWidth(leftX: Float?): Float =
        com.cardesk.launcher.core.media.LyricBlockLayout.rowMaxWidth(
            leftX = leftX,
            maxWidth = LYRIC_MAX_W,
            safeRight = LYRIC_SAFE_RIGHT,
        )

    /**
     * 字号档 → 实际字号（设计原值，缩放由 [lyricScale] 统一乘）。
     *
     * 档位定义在 [com.cardesk.launcher.core.media.LyricLayout]（纯 Kotlin），
     * 实际像素值留在这里 —— 布局清单不该知道 View 的设计坐标。
     */
    private fun lyricSizeOf(tier: Int): Float = when (tier) {
        com.cardesk.launcher.core.media.LyricSizeTier.MAIN -> LYRIC_MAIN_SIZE
        com.cardesk.launcher.core.media.LyricSizeTier.NEAR -> LYRIC_NEAR_SIZE
        else -> LYRIC_FAR_SIZE
    }

    /** 字号档 → 颜色（越靠近当前句越亮）。 */
    private fun lyricColorOf(tier: Int): Int = when (tier) {
        com.cardesk.launcher.core.media.LyricSizeTier.MAIN -> Skin.TEXT
        com.cardesk.launcher.core.media.LyricSizeTier.NEAR -> Skin.TEXT_SOFT
        else -> Skin.TEXT_DIM
    }

    // ---------------------------------------------------------------- 导航引导

    /**
     * 导航引导卡。
     *
     * 版式（**设计坐标 1920×1080**）：
     * `
     * (padding 26, 宽 520, marginTop 73, marginStart 44)
     *  ├── 行1：88×88 图标底（bg_seat_on）+ 右侧竖排（70px 距离 + 24px 单位 / 24px 道路）
     *  ├── 行2：车道线 chip（bg_scene_chip，高 70，marginTop 22）—— 没有车道线时不画
     *  └── 行3：剩余 X 公里 · Y 分 …… 预计到达（marginTop 20）
     * `
     *
     * 图标是 PNG 资源（/ /
     * 车道线是 84 张 `hud_lanes/landr_*.png` —— 都是**自己的美术资源**
     * 不能复制，所以这里全部 Canvas 自绘（见 [drawGuideIcon] / [drawLaneGlyph]）。
     */
    private fun drawGuide(canvas: Canvas) {
        val guide = navGuide ?: return
        // 第九十九轮：巡航态单独一张窄卡（当前道路 + 车道线 + 多方向红绿灯）。
        if (guide.cruise) {
            drawCruiseGuide(canvas, guide)
            return
        }
        val x = left
        // 第五十二轮：状态栏缩到 2/3，内容基线 110 → 73
        val y = 73f
        // 第 106 轮：底部「剩余 距离/时间 + 到达时间」字号调大，卡宽从 520 放到 620 以容纳。
        val w = 620f
        val pad = 26f
        val rowTop = y + pad
        // 行1 的高度不是 88（图标盒高），而是**文字列高**：
        //  第一行是 `wrap_content`，右列 = 70px 距离 + 6 间距 + 24px 道路 ≈ 110
        // 比 88 的图标盒高，图标盒在这行里**垂直居中**（`gravity="center_vertical"`）。
        val rowH = 110f
        val hasLanes = guide.lanes.isNotEmpty()
        // 车道线行（高 70，marginTop 22）
        val lanesTop = rowTop + rowH + 22f
        // 底部行：与车道块之间留 18px 间距（第 106 轮用户要求：剩余距离/到达时间往下一点）
        val bottomTop = if (hasLanes) lanesTop + 70f + 18f else rowTop + rowH + 20f
        val bottomBaseline = bottomTop + 20f
        // 设计板 s01：带车道线时引导卡高 **296**（底部文字基线之下留 51 的呼吸区）
        val h = bottomTop - y + 42f + pad

        val r = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(r, GLASS_RADIUS, GLASS_RADIUS, Paint(fill).apply { color = Skin.GLASS_STRONG_FILL })
        canvas.drawRoundRect(r, GLASS_RADIUS, GLASS_RADIUS, Paint(stroke).apply {
            color = Skin.GLASS_STROKE
            strokeWidth = 1.4f
        })

        // —— 行1 左：88×88 圆角底（= #243d7bff）+ 转向箭头（ACCENT）——
        val iconCx = x + pad + 44f
        val iconCy = rowTop + (rowH - 88f) / 2f + 44f
        canvas.drawRoundRect(
            RectF(iconCx - 44f, iconCy - 44f, iconCx + 44f, iconCy + 44f), 26f, 26f,
            Paint(fill).apply { color = Skin.ACCENT_SOFT },
        )
        drawGuideIcon(canvas, guide.icon, iconCx, iconCy)

        // —— 行1 右：距离（70px）+ 单位（24px，间距 6）——
        text.color = Skin.TEXT
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 70f
        text.textAlign = Paint.Align.LEFT
        val tx = x + pad + 88f + 22f
        canvas.drawText(guide.segmentText, tx, rowTop + 62f, text)
        val distW = text.measureText(guide.segmentText)
        text.typeface = Typeface.DEFAULT
        text.textSize = 24f
        text.color = Skin.TEXT_SOFT
        canvas.drawText(guide.segmentUnit, tx + distW + 6f, rowTop + 62f, text)
        val unitW = text.measureText(guide.segmentUnit)

        // —— 行1 右上角：红绿灯 chip（**最近路口**）——
        // 与分段距离同一水平带，右对齐到卡片内容右缘。
        // 左边界取「距离 + 单位」的右缘：放不下就整个不画（不挤掉距离）。
        drawTrafficLight(
            canvas = canvas,
            guide = guide,
            right = x + w - pad,
            rowTop = rowTop,
            leftLimit = tx + distW + 6f + unitW + 12f,
        )

        // —— 行1 右下：道路（24px，marginTop 6）——
        text.textSize = 24f
        text.color = Skin.TEXT
        canvas.drawText(guide.road, tx, rowTop + 70f + 6f + 24f, text)

        // —— 行2：车道线 chip（最多 8 条）——
        if (hasLanes) {
            drawGuideLanes(canvas, guide, x + pad, lanesTop, w - pad * 2f, 70f)
        }

        // —— 行3：剩余 X 公里 · Y 分 …… 预计到达 ——
        // 第 106 轮：这一行整体调大（弱标签 18→20、数值 21→25、到达 18→20/21→25）。
        // 极端长文案（里程 + 小时级剩余）放不下时按比例收一点，避免和右侧 ETA 撞上。
        val dimLabel = "剩余 "
        val midLabel = " 公里 · "
        val labelSize = 20f
        val valueSize = 25f
        val etaSuffixSize = 20f
        val etaTimeSize = 25f
        var leftW = 0f
        text.typeface = Typeface.DEFAULT
        text.textSize = labelSize
        leftW += text.measureText(dimLabel)
        text.textSize = valueSize
        leftW += text.measureText(guide.remainKm)
        text.textSize = labelSize
        leftW += text.measureText(midLabel)
        text.textSize = valueSize
        leftW += text.measureText(guide.remainTime)
        var etaW = 0f
        if (guide.etaTime.isNotEmpty()) {
            text.typeface = Typeface.DEFAULT
            text.textSize = etaTimeSize
            etaW += text.measureText(guide.etaTime)
            text.textSize = etaSuffixSize
            etaW += text.measureText(NavGuideText.ETA_SUFFIX)
        }
        val avail = w - pad * 2f
        val fit = if (leftW + 16f + etaW > avail) (avail / (leftW + 16f + etaW)).coerceIn(0.86f, 1f) else 1f
        val ls = labelSize * fit
        val vs = valueSize * fit
        text.textSize = ls
        text.color = Skin.TEXT_DIM
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(dimLabel, x + pad, bottomBaseline, text)
        var cx2 = x + pad + text.measureText(dimLabel)
        text.textSize = vs
        text.color = Skin.TEXT
        canvas.drawText(guide.remainKm, cx2, bottomBaseline, text)
        cx2 += text.measureText(guide.remainKm)
        text.textSize = ls
        text.color = Skin.TEXT_DIM
        canvas.drawText(midLabel, cx2, bottomBaseline, text)
        cx2 += text.measureText(midLabel)
        text.textSize = vs
        text.color = Skin.TEXT
        canvas.drawText(guide.remainTime, cx2, bottomBaseline, text)

        // 右侧 ETA：时间 + 「到达」，右对齐（分段着色）
        if (guide.etaTime.isNotEmpty()) {
            val suffix = NavGuideText.ETA_SUFFIX
            text.textSize = etaSuffixSize * fit
            text.color = Skin.TEXT_DIM
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(suffix, x + w - pad, bottomBaseline, text)
            text.textAlign = Paint.Align.LEFT
            val suffixW = text.measureText(suffix)
            text.textSize = etaTimeSize * fit
            text.color = Skin.TEXT
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(guide.etaTime, x + w - pad - suffixW, bottomBaseline, text)
            text.textAlign = Paint.Align.LEFT
        }

        hits["nav"] = r
    }

    /**
     * 巡航卡（第九十九轮）。
     *
     * 巡航不是导航：没有分段距离 / 剩余里程 / ETA，只需要三样东西：
     *
     * - 当前道路名（`巡航中` 兜底）
     * - 车道线（最近一次 `EXTRA_DRIVE_WAY`，没有就不画）
     * - 多方向红绿灯（巡航协议一次可能带左/直/右好几个方向，**并排**展示）
     *
     * 版式：与导航卡同宽 520、同起点 (44,73)；高度随「有没有车道线 / 有没有灯」自适应，
     * 没有信息时不出卡（由 `NavGuideModel.buildCruise` + `AmapDesktopGuide.cruise()` 控制）。
     */
    private fun drawCruiseGuide(canvas: Canvas, guide: DesktopGuide) {
        val x = left
        val y = 73f
        val w = 520f
        val pad = 26f
        val headerTop = y + pad
        val headerH = 110f
        val hasLanes = guide.lanes.isNotEmpty()
        val lights = guide.cruiseLights.take(3)
        val hasLights = lights.isNotEmpty()

        var bottom = headerTop + headerH
        val lanesTop = if (hasLanes) bottom + 22f else bottom
        if (hasLanes) bottom = lanesTop + 70f
        val lightsTop = if (hasLights) bottom + 16f else bottom
        if (hasLights) bottom = lightsTop + 76f
        val h = bottom - y + pad

        val r = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(r, GLASS_RADIUS, GLASS_RADIUS, Paint(fill).apply { color = Skin.GLASS_STRONG_FILL })
        canvas.drawRoundRect(r, GLASS_RADIUS, GLASS_RADIUS, Paint(stroke).apply {
            color = Skin.GLASS_STROKE
            strokeWidth = 1.4f
        })

        // —— 行1：转向图标（88×88） + 当前道路 + 「巡航」——
        val iconCx = x + pad + 44f
        val iconCy = headerTop + headerH / 2f
        canvas.drawRoundRect(
            RectF(iconCx - 44f, iconCy - 44f, iconCx + 44f, iconCy + 44f), 26f, 26f,
            Paint(fill).apply { color = Skin.ACCENT_SOFT },
        )
        drawGuideIcon(canvas, guide.icon, iconCx, iconCy)

        val tx = x + pad + 88f + 22f
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 34f
        text.color = Skin.TEXT
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(fitCruiseText(guide.road, x + w - pad - tx), tx, headerTop + 52f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f
        text.color = Skin.TEXT_DIM
        canvas.drawText("巡航", tx, headerTop + 84f, text)

        // —— 行2：车道线 ——
        if (hasLanes) {
            drawGuideLanes(canvas, guide, x + pad, lanesTop, w - pad * 2f, 70f)
        }

        // —— 行3：多方向红绿灯（左/直/右并排，最多 3 枚）——
        if (hasLights) {
            val gap = 9f
            val chipW = (w - pad * 2f - gap * (lights.size - 1)) / lights.size
            lights.forEachIndexed { i, light ->
                val chipLeft = x + pad + i * (chipW + gap)
                val chip = RectF(chipLeft, lightsTop, chipLeft + chipW, lightsTop + 76f)
                canvas.drawRoundRect(chip, 18f, 18f, Paint(fill).apply { color = Skin.CHIP_FILL })
                canvas.drawRoundRect(chip, 18f, 18f, Paint(stroke).apply {
                    color = Skin.CHIP_STROKE
                    strokeWidth = 1.2f
                })
                // 方向箭头（左/直/右）
                val shape = cruiseDirectionShape(light.direction)
                if (shape != null) {
                    drawLaneGlyph(canvas, shape, chipLeft + 16f, lightsTop + 23f, 30f, Skin.TEXT_SOFT)
                }
                // 灯态圆点
                val lampColor = when (light.lamp) {
                    AmapTrafficLight.Lamp.RED -> Skin.DANGER
                    AmapTrafficLight.Lamp.GREEN -> Skin.OK
                    AmapTrafficLight.Lamp.AMBER -> Skin.WARN
                    AmapTrafficLight.Lamp.UNKNOWN -> Skin.TEXT_DIM
                }
                val circleCx = chipLeft + 74f
                val circleCy = lightsTop + 38f
                canvas.drawCircle(circleCx, circleCy, 19f, Paint(fill).apply { color = lampColor })
                // 读秒
                text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                text.textSize = 26f
                text.color = lampColor
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(
                    NavGuideText.trafficCountdownText(light.countdownSeconds),
                    circleCx + 28f,
                    lightsTop + 48f,
                    text,
                )
                text.typeface = Typeface.DEFAULT
            }
        }
        text.textAlign = Paint.Align.LEFT
        hits["nav"] = r
    }

    /** 巡航方向码 → 车道箭头图形（识别不出返回 null，不画方向箭头）。 */
    private fun cruiseDirectionShape(direction: Int?): LaneShape? = when (direction) {
        AmapTrafficLight.DIR_UTURN -> LaneShape.UTURN
        AmapTrafficLight.DIR_LEFT -> LaneShape.LEFT
        AmapTrafficLight.DIR_STRAIGHT -> LaneShape.STRAIGHT
        AmapTrafficLight.DIR_RIGHT -> LaneShape.RIGHT
        else -> null
    }

    /** 巡航卡道路名按可用宽度裁剪（超宽加「…」）。 */
    private fun fitCruiseText(raw: String, maxWidth: Float): String {
        if (text.measureText(raw) <= maxWidth) return raw
        var end = raw.length
        while (end > 1 && text.measureText(raw.substring(0, end) + "…") > maxWidth) end--
        return raw.substring(0, end) + "…"
    }

    /**
     * 红绿灯 chip（**最近路口**）。
     *
     * 位置：引导卡的**右上角**，与「分段距离」同一水平带，右对齐到卡片内容右缘。
     *
     * ```
     *      ●        ← 大圆（46px），灯色实心
     *     12 秒     ← 读秒，在圆的**下方**
     * ```
     *
     * ## 第七十九轮重画：为什么从「横排」改成「大圆 + 下方读秒」
     *
     * 第七十六轮刚接上高德红绿灯时画的是**横排**：
     * `● 14px 小圆点 + 「红灯」20px + 「12」34px 粗体 + 「秒」16px`。
     * 四个元素挤在 46 高的一条横带里 —— 读秒是大号粗体、灯态是小号常规字，
     * 两套字号并排，看着像两件不相干的东西拼在一起，而且左侧那颗小圆点
     * 跟「红灯」两个字表达的是同一件事（颜色 + 文字），冗余。
     *
     * 用户要求：**红黄绿用一个大一点的圆表示即可，读秒放在圆的下方**。
     * 于是改成**竖排**：一个大圆单独表示灯态，读秒（小一号粗体）居中放在圆的下面，
     * 整块右对齐到卡片内容右缘。
     *
     * ## 灯态怎么表达
     *
     * 圆的颜色**就是**灯态（红 / 黄 / 绿）。
     * 第七十六轮原本还有一行「红灯 / 黄灯 / 绿灯」文字兜底，
     * 第七十九轮按用户要求**只留一个圆** —— 颜色成了唯一的灯态线索。
     * `UNKNOWN`（信号在、灯态枚举没验证过）画成弱文字色，
     * 和「没有信号」（不画）区分开。
     *
     * ## 为什么还留着「秒」字
     *
     * 光一个「12」到底是米还是秒会有歧义（引导卡左下角又写着「公里 / 分」），
     * 所以补一个 15px 的「秒」。
     *
     * ## 为什么放在右上角而不是新开一行
     *
     * 路口才有信号，信号一来一走很频繁。
     * 新开一行会让引导卡在有/无信号之间**反复长高变矮**（整张卡跟着跳），
     * 而右上角是**画在空位上**的 —— 有信号才画，没信号什么都不画，
     * 卡片高度**恒定**（与「没有歌词不占位」同一口径）。
     *
     * ## 画不出来就不画
     *
     * 右上角放不下时（分段距离数字太长）直接**不画**这一块，
     * 宁可少一个信息，也不去挤掉已经在显示的距离。
     */
    private fun drawTrafficLight(
        canvas: Canvas,
        guide: DesktopGuide,
        right: Float,
        rowTop: Float,
        leftLimit: Float,
    ) {
        val signal = guide.trafficLight ?: return
        val lampColor = when (signal.lamp) {
            AmapTrafficLight.Lamp.RED -> Skin.DANGER
            AmapTrafficLight.Lamp.GREEN -> Skin.OK
            AmapTrafficLight.Lamp.AMBER -> Skin.WARN
            AmapTrafficLight.Lamp.UNKNOWN -> Skin.TEXT_DIM
        }
        val seconds = signal.countdownSeconds
        val number = NavGuideText.trafficCountdownText(seconds)

        // —— 量尺寸（先量后画，避免画到一半发现放不下）——
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = TRAFFIC_SECONDS_SIZE
        val numW = text.measureText(number)
        text.typeface = Typeface.DEFAULT
        text.textSize = TRAFFIC_UNIT_SIZE
        val hasUnit = seconds != null && seconds > 0
        val unitW = if (hasUnit) text.measureText("秒") else 0f
        // 读秒那行（数字 + 单位）的宽度，圆至少要一样宽才不显得歪
        val textRowW = numW + (if (hasUnit) 4f + unitW else 0f)
        val contentW = maxOf(TRAFFIC_LAMP_D, textRowW)

        val chipW = contentW + TRAFFIC_PAD_X * 2f
        val chipLeft = right - chipW
        // 放不下就不画（不挤掉已有的距离信息）
        if (chipLeft < leftLimit) return

        val chipTop = rowTop + 8f
        val chipH = TRAFFIC_PAD_TOP + TRAFFIC_LAMP_D + TRAFFIC_GAP + TRAFFIC_SECONDS_SIZE + TRAFFIC_PAD_BOTTOM

        // —— 底：与卡片同族的扁平面 + 1px 描边 ——
        canvas.drawRoundRect(
            RectF(chipLeft, chipTop, right, chipTop + chipH), 20f, 20f,
            Paint(fill).apply { color = Skin.CHIP_FILL },
        )
        canvas.drawRoundRect(
            RectF(chipLeft, chipTop, right, chipTop + chipH), 20f, 20f,
            Paint(stroke).apply {
                color = Skin.CHIP_STROKE
                strokeWidth = 1.4f
            },
        )

        // —— 灯态：一个大圆（**唯一**的灯态线索）——
        val circleCx = chipLeft + chipW / 2f
        val circleCy = chipTop + TRAFFIC_PAD_TOP + TRAFFIC_LAMP_D / 2f
        canvas.drawCircle(circleCx, circleCy, TRAFFIC_LAMP_D / 2f, Paint(fill).apply { color = lampColor })

        // —— 读秒：在圆的**下方**，居中 ——
        //
        // 第八十轮修了一个排版缺陷：数字原来按 `Align.CENTER` 画在圆心，
        // 而「秒」按 `Align.LEFT` 画在 `numLeft + numW + 4`，
        // 而 `numLeft` 又是按「整行左对齐」算的 —— 两个基准不一致，
        // 结果「秒」压在第二个数字上（模拟器截图里能直接看到）。
        //
        // 现在**整行（数字 +「秒」）作为一个整体以圆心居中**：
        // 数字左对齐画在 numLeft，「秒」紧跟其后。
        val baseline = circleCy + TRAFFIC_LAMP_D / 2f + TRAFFIC_GAP + TRAFFIC_SECONDS_TEXT_ASCENT
        val numLeft = circleCx - textRowW / 2f
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = TRAFFIC_SECONDS_SIZE
        text.color = lampColor
        canvas.drawText(number, numLeft, baseline, text)
        if (hasUnit) {
            // 「秒」跟着数字走：数字左对齐起笔，秒紧跟在右侧，整体仍以圆心为准
            text.typeface = Typeface.DEFAULT
            text.textSize = TRAFFIC_UNIT_SIZE
            text.color = Skin.TEXT_DIM
            canvas.drawText("秒", numLeft + numW + 4f, baseline - 6f, text)
        }
        // 交还画笔：读秒用的是粗体 + 居中对齐，不能漏给后面的绘制（道路名等）。
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * 转向图标。
     *
     * 用的是 4 张 PNG/矢量资源（直行 / 左转 / 右转 / 掉头，54×64）
     * 这里是**自绘的同形箭头**，不复制资源。
     */
    private fun drawGuideIcon(canvas: Canvas, icon: NavGuideText.GuideIcon, cx: Float, cy: Float) {
        // 图标盒是 54×64（的 viewportWidth/Height）
        val w = 54f
        val h = 64f
        val ox = cx - w / 2f
        val oy = cy - h / 2f
        fun px(v: Float) = ox + v
        fun py(v: Float) = oy + v

        val solid = Paint(fill).apply {
            color = Skin.ACCENT
            style = Paint.Style.FILL
        }
        // 描边件（转弯的杆 + 掉头的 U 形）
        val solidStroke = Paint(stroke).apply {
            color = Skin.ACCENT
            strokeWidth = 8f
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

        when (icon) {
            // 直行：实心上箭头（尖端在上 + 两侧箭头肩 + 竖杆）
            NavGuideText.GuideIcon.STRAIGHT -> {
                val p = Path().apply {
                    moveTo(px(27f), py(4f))
                    lineTo(px(50f), py(30f))
                    lineTo(px(36f), py(30f))
                    lineTo(px(36f), py(60f))
                    lineTo(px(18f), py(60f))
                    lineTo(px(18f), py(30f))
                    lineTo(px(4f), py(30f))
                    close()
                }
                canvas.drawPath(p, solid)
            }

            // 左转：**右竖杆**向上 → 向左弯 → **左侧实心箭头**
            // 右转：左转的镜像
            NavGuideText.GuideIcon.LEFT, NavGuideText.GuideIcon.RIGHT -> {
                val left = icon == NavGuideText.GuideIcon.LEFT
                // 竖杆中心线：左转在右（x=30），右转在左（x=24）
                val stemX = if (left) 30f else 24f
                // 弯到水平段：左转向左弯到 x=19，右转向右弯到 x=35
                val bendX = if (left) 19f else 35f
                val neckX = if (left) 13f else 41f
                val rod = Path().apply {
                    moveTo(px(stemX), py(60f))
                    lineTo(px(stemX), py(30f))
                    quadTo(px(stemX), py(18f), px(bendX), py(18f))
                    lineTo(px(neckX), py(18f))
                }
                canvas.drawPath(rod, solidStroke)
                // 箭头：左转尖头在 x=2，右转尖头在 x=52；根部竖边 12 高
                val baseX = if (left) 14f else 40f
                val tipX = if (left) 2f else 52f
                val head = Path().apply {
                    moveTo(px(baseX), py(6f))
                    lineTo(px(tipX), py(18f))
                    lineTo(px(baseX), py(30f))
                    close()
                }
                canvas.drawPath(head, solid)
            }

            // 掉头：描边的倒 U + 底部实心下箭头
            NavGuideText.GuideIcon.UTURN -> {
                val u = Paint(stroke).apply {
                    color = Skin.ACCENT
                    strokeWidth = 10f
                    strokeCap = Paint.Cap.BUTT
                    strokeJoin = Paint.Join.ROUND
                    style = Paint.Style.STROKE
                }
                val path = Path().apply {
                    moveTo(px(39f), py(58f))
                    lineTo(px(39f), py(29f))
                    cubicTo(px(39f), py(11f), px(15f), py(11f), px(15f), py(29f))
                    lineTo(px(15f), py(35f))
                }
                canvas.drawPath(path, u)
                val head = Path().apply {
                    moveTo(px(3f), py(31f))
                    lineTo(px(15f), py(52f))
                    lineTo(px(27f), py(31f))
                    close()
                }
                canvas.drawPath(head, solid)
            }
        }
    }

    /**
     * 车道线 chip 行。
     *
     * - 外框：（圆角 22，#14ffffff 填充 + 1px #33ffffff 描边）
     * - 最多 8 条，每条等宽（`LinearLayout.LayoutParams(0, 58, 1.0f)`）
     * - 高亮（`on`）的条用 ACCENT 色画箭头，其余用暗色
     *
     * **与唯一差异**：有 84 张 `hud_lanes/landr_*.png` 资源
     * 高亮条是「PNG 染成 ACCENT 色、无底色」；我们没有资源（不能复制），
     * 所以照**有资源**那条路径画：只染色、不加 蓝底
     * （缺图时才会加那个底，而它自己是「蓝底蓝箭头」根本看不见）。
     */
    private fun drawGuideLanes(
        canvas: Canvas,
        guide: DesktopGuide,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) {
        val chip = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(chip, 22f, 22f, Paint(fill).apply { color = Skin.GLASS_FILL })
        canvas.drawRoundRect(chip, 22f, 22f, Paint(stroke).apply {
            color = Skin.GLASS_STROKE
            strokeWidth = 1f
        })

        val tiles = guide.lanes.take(MAX_GUIDE_LANES)
        val pad = 6f
        val innerW = w - pad * 2f
        val cellW = innerW / tiles.size
        val gap = 4f
        // 缺图分支：ImageView 高 58、padding 10 → 实际箭头 38（是 30×30 的 24 viewport）
        val glyphSize = 38f
        for ((i, tile) in tiles.withIndex()) {
            val cellLeft = x + pad + i * cellW
            val contentLeft = cellLeft + (if (i > 0) gap else 0f)
            val contentW = cellW - (if (i > 0) gap else 0f)
            val cy = y + h / 2f
            val color = if (tile.on) Skin.ACCENT else Skin.TEXT_DIM
            drawLaneGlyph(
                canvas,
                LaneShapes.of(tile.icon),
                contentLeft + (contentW - glyphSize) / 2f,
                cy - glyphSize / 2f,
                glyphSize,
                color,
            )
        }
    }

    /**
     * 车道线箭头（自绘，24×24 局部坐标系，描边风格）。
     *
     * `hud_lanes/landr_*.png` 是它自己的美术资源，不能复制
     * 这里按 [LaneShape] 画等价形状，认不出来的编码回退成「直行」——
     * 与缺图时回退 的行为一致。
     */
    private fun drawLaneGlyph(
        canvas: Canvas,
        shape: LaneShape,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
    ) {
        val s = size / 24f
        fun px(v: Float) = x + v * s
        fun py(v: Float) = y + v * s
        val p = Paint(stroke).apply {
            this.color = color
            strokeWidth = 2.2f * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        val f = Paint(fill).apply { this.color = color }

        fun arrowUp(cx: Float, top: Float, bottom: Float) {
            canvas.drawLine(px(cx), py(bottom), px(cx), py(top), p)
            canvas.drawPath(
                Path().apply {
                    moveTo(px(cx - 6f), py(top + 6f))
                    lineTo(px(cx), py(top))
                    lineTo(px(cx + 6f), py(top + 6f))
                },
                p,
            )
        }

        // 组合车道里的转弯箭头：独立的小「L」形箭头，和直行箭头不连线，保证两者之间有可见空隙。
        fun turnArrow(left: Boolean, shaftX: Float, armTo: Float) {
            canvas.drawPath(
                Path().apply {
                    moveTo(px(shaftX), py(21f))
                    lineTo(px(shaftX), py(15.5f))
                    lineTo(px(armTo), py(15.5f))
                },
                p,
            )
            val tip = if (left) armTo - 2.4f else armTo + 2.4f
            canvas.drawPath(
                Path().apply {
                    moveTo(px(armTo), py(13f))
                    lineTo(px(tip), py(15.5f))
                    lineTo(px(armTo), py(18f))
                },
                p,
            )
        }

        when (shape) {
            LaneShape.STRAIGHT -> arrowUp(12f, 5f, 20f)
            LaneShape.LEFT -> {
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(14f), py(20f))
                        lineTo(px(14f), py(12f))
                        cubicTo(px(14f), py(7f), px(11f), py(5f), px(6f), py(5f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(10f), py(1f))
                        lineTo(px(3f), py(5f))
                        lineTo(px(10f), py(9f))
                    },
                    p,
                )
            }
            LaneShape.RIGHT -> {
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(10f), py(20f))
                        lineTo(px(10f), py(12f))
                        cubicTo(px(10f), py(7f), px(13f), py(5f), px(18f), py(5f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(14f), py(1f))
                        lineTo(px(21f), py(5f))
                        lineTo(px(14f), py(9f))
                    },
                    p,
                )
            }
            LaneShape.UTURN -> {
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(16f), py(20f))
                        lineTo(px(16f), py(9f))
                        cubicTo(px(16f), py(4f), px(8f), py(4f), px(8f), py(9f))
                        lineTo(px(8f), py(12f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(4.5f), py(11f))
                        lineTo(px(8f), py(16f))
                        lineTo(px(11.5f), py(11f))
                    },
                    p,
                )
            }
            LaneShape.STRAIGHT_LEFT -> {
                // 直行箭头靠右；左转箭头独立画在左下，两者之间留出明显空隙（不再连一根分叉线）。
                arrowUp(17f, 3.5f, 20.5f)
                turnArrow(left = true, shaftX = 8.5f, armTo = 4.5f)
            }
            LaneShape.STRAIGHT_RIGHT -> {
                arrowUp(7f, 3.5f, 20.5f)
                turnArrow(left = false, shaftX = 15.5f, armTo = 19.5f)
            }
            LaneShape.STRAIGHT_BOTH -> {
                arrowUp(12f, 3.5f, 20.5f)
                turnArrow(left = true, shaftX = 6f, armTo = 2.6f)
                turnArrow(left = false, shaftX = 18f, armTo = 21.4f)
            }
            LaneShape.BUS -> {
                // 公交专属道（图形是「公交」车头剪影）
                canvas.drawRoundRect(
                    RectF(px(3f), py(6f), px(21f), py(17f)), 2f * s, 2f * s,
                    Paint(fill).apply { this.color = color },
                )
                canvas.drawCircle(px(8f), py(18.5f), 1.6f * s, f)
                canvas.drawCircle(px(16f), py(18.5f), 1.6f * s, f)
            }
            LaneShape.VARIABLE -> {
                // 可变车道（图形里就是「可变」两个字）
                text.color = color
                text.typeface = Typeface.DEFAULT
                text.textSize = 9f * s
                text.textAlign = Paint.Align.CENTER
                canvas.drawText("可变", px(12f), py(16f), text)
                text.textAlign = Paint.Align.LEFT
            }
        }
    }

    // ---------------------------------------------------------------- 天气

    /**
     * 右上角天气大字（第五十轮对齐设计板 s01）。
     *
     * 设计板版式（1920 设计坐标）：
     * `
     * 容器 left=1488 top=112 宽=388，右对齐（右边缘 1876 = 右列卡片同一右边界）
     * 26° ← 96px 粗体 + 38px 的「°」，同一基线
     * [☀] 晴 · 空气优 ← 20px 次文字，距上 12
     * `
     */
    private fun drawWeather(canvas: Canvas) {
        val end = rightEnd
        // 第五十二轮：天气块随内容基线上移（112 → 75）
        val top = 75f
        text.textAlign = Paint.Align.RIGHT
        // 天气温度是**整数**，没有天气数据时用车外温度兜底
        val ambient = info?.ambientTemp?.takeIf { !it.isNaN() }
        val temp = weatherTemp ?: ambient?.toInt()
        val shown = temp?.toString() ?: "--"

        // 度数符号（38px）先画，主数字（96px 粗体）贴着它的左边
        text.typeface = Typeface.DEFAULT
        text.textSize = 38f
        text.color = Skin.TEXT
        val degW = text.measureText("°")
        canvas.drawText("°", end, top + 77f, text)

        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 96f
        canvas.drawText(shown, end - degW, top + 77f, text)

        // 图标（20px 太阳）+ 详情（20px 次文字，与上排距 12）
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f
        text.color = Skin.TEXT_SOFT
        val detail = weatherDetailText()
        val detailW = text.measureText(detail)
        canvas.drawText(detail, end, top + 124f, text)
        drawWeatherIcon(canvas, end - detailW - 5f - 10f, top + 114f, weatherSky)
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * 天气详情文案：`TextUtils.join(" • ",...)`
     * 以及 把 ` · ` 换成 ` • `。
     */
    private fun weatherDetailText(): String {
        val parts = ArrayList<String>(2)
        if (weatherCity.isNotEmpty()) parts.add(weatherCity)
        if (weatherSky.isNotEmpty()) parts.add(weatherSky)
        if (parts.isEmpty()) return "天气获取中"
        return parts.joinToString(" • ").replace(" · ", " • ")
    }

    /** 天气图标。 */
    private fun drawWeatherIcon(canvas: Canvas, cx: Float, cy: Float, sky: String) {
        // 第四轮起与状态栏共用 [com.cardesk.launcher.ui.WeatherGlyphs]，避免两边画法分叉。
        com.cardesk.launcher.ui.WeatherGlyphs.draw(canvas, cx, cy, sky, Skin.TEXT_SOFT, 2f)
    }

    // ---------------------------------------------------------------- 行程卡

    /**
     * 行程卡（**第三十六轮新增**，按用户要求加在天气下方）。
     *
     * 版式（设计坐标 1920×1080）：
     * `
     * 贴右缘（右 = rightEnd，与状态栏、底部卡片行同一条右边界），上 = 292
     * ┌──────────────────────────────┐
     * │ 总里程 平均油耗 │ 标签 15px / 数值 24px
     * │ 12,480 km 6.8 L/100km │
     * │ 行程里程 行程时长 │
     * │ 128.4 km 1 小时 23 分 │
     * └──────────────────────────────┘
     * `
     *
     * 数据来源：
     * - 总里程 → **AdaptAPI `ISensor` 的 `SENSOR_TYPE_ODOMETER`（1050368，单位 km）**，
     *   属性通道 `PERF_ODOMETER` / `ODOMETER` 退成兜底
     *   （第七十五轮：属性通道在这台车机上从来没读到过值）；
     * - 平均油耗 → `ITripData.IAvgEnergyInfo.getAvgFuelConsumption()`
     * - 行程里程/时长 → `ITripData.IDrivingInfo.getTripDistance()` /
     *
     * **无数据不占位**：四项全读不到时整张卡不画（与「没有歌词不占位」同一口径）。
     */
    private fun drawTripCell(
        canvas: Canvas,
        x: Float,
        top: Float,
        label: String,
        value: String,
        maxWidth: Float = 300f,
        labelSp: Float = TRIP_LABEL_SP,
        valueSp: Float = TRIP_VALUE_SP,
    ) {
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        // 第189轮：标签 19 → 21（[TRIP_LABEL_SP]），数值 24 → 27（[TRIP_VALUE_SP]）。
        text.textSize = labelSp
        text.color = Skin.TEXT_SOFT
        canvas.drawText(fitText(label, maxWidth), x, top + TRIP_LABEL_BASE, text)

        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // 卡被压窄时（卡片库加卡 / 小档）数值跟着缩，避免压到相邻单元格。
        text.textSize = valueSp
        text.color = Skin.TEXT
        canvas.drawText(fitText(value, maxWidth), x, top + TRIP_VALUE_BASE, text)
    }

    /**
     * 一张卡里的几个数值**一起**量，取**都放得下**的最大字号。
     *
     * ## 为什么不每格各缩各的
     *
     * 「平均油耗 6.8 L/100km」比「里程 128.4 km」长一截。
     * 以前是每格自己 `fitText` —— 放不下就截断成「6.8 L/100…」，数字看着像坏了。
     * 现在改成整卡取同一档：最长的那个放不下就**整卡降一档**，
     * 四格字号一样大，数字也完整。
     */
    private fun fitValueSize(
        values: List<String>,
        maxWidth: Float,
        target: Float,
        min: Float,
    ): Float {
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        var size = target
        while (size > min) {
            text.textSize = size
            if (values.all { text.measureText(it) <= maxWidth }) break
            size -= 1f
        }
        text.textSize = size
        return size
    }

    // ---------------------------------------------------------------- 底部卡片

    /** 底部卡片的布局（顺序/尺寸/显隐由 [cardLayout] 决定）。 */
    private fun layoutBottomCards(): LinkedHashMap<String, RectF> {
        val out = LinkedHashMap<String, RectF>()
        val widths = com.cardesk.launcher.core.CardLayout.widths(
            com.cardesk.launcher.core.CardLayout.active(cardLayout),
            rightEnd - left,
            CARD_GAP,
            naturalWidth = { id -> cardWidth(id) },
            minWidth = 160f,
        )
        var x = left
        for ((id, w) in widths) {
            out[id] = RectF(x, cardTop, x + w, cardTop + cardH)
            x += w + CARD_GAP
        }
        return out
    }

    private fun cardWidth(id: String): Float = when (id) {
        com.cardesk.launcher.core.CardLayout.QUICK -> QUICK_CARD_W
        com.cardesk.launcher.core.CardLayout.MUSIC -> MUSIC_CARD_W
        com.cardesk.launcher.core.CardLayout.CLIMATE -> CLIMATE_CARD_W
        com.cardesk.launcher.core.CardLayout.TIRE -> TIRE_CARD_W
        com.cardesk.launcher.core.CardLayout.RANGE -> RANGE_CARD_W
        com.cardesk.launcher.core.CardLayout.LEGACY_TRIP -> TRIP_BOTTOM_CARD_W
        com.cardesk.launcher.core.CardLayout.TRIP_CARD -> TRIP_BOTTOM_CARD_W
        com.cardesk.launcher.core.CardLayout.STATUS -> STATUS_BOTTOM_CARD_W
        else -> 0f
    }

    private fun drawBottomCards(canvas: Canvas) {
        val layout = layoutBottomCards()
        cardRects.clear()
        cardRects.putAll(layout)
        for ((id, rect) in layout) {
            val drawRect = if (id == dragId) {
                RectF(rect.left + dragDx, rect.top, rect.right + dragDx, rect.bottom)
            } else {
                rect
            }
            when (id) {
                com.cardesk.launcher.core.CardLayout.QUICK -> drawQuick(canvas, drawRect)
                com.cardesk.launcher.core.CardLayout.MUSIC -> drawMusicCard(canvas, drawRect)
                com.cardesk.launcher.core.CardLayout.CLIMATE -> drawClimateCard(canvas, drawRect)
                com.cardesk.launcher.core.CardLayout.TIRE -> drawTireCard(canvas, drawRect)
                com.cardesk.launcher.core.CardLayout.RANGE -> drawRangeCard(canvas, drawRect)
                com.cardesk.launcher.core.CardLayout.LEGACY_TRIP,
                com.cardesk.launcher.core.CardLayout.TRIP_CARD -> drawTripCard(canvas, drawRect)
                com.cardesk.launcher.core.CardLayout.STATUS -> drawStatusBottomCard(canvas, drawRect)
            }
        }
    }

    private fun card(canvas: Canvas, r: RectF, radius: Float = GLASS_RADIUS, strong: Boolean = false) {
        canvas.drawRoundRect(r, radius, radius, Paint(fill).apply {
            color = if (strong) Skin.surfaceStrong else Skin.surface
        })
        canvas.drawRoundRect(r, radius, radius, Paint(stroke).apply {
            color = Skin.GLASS_STROKE
            strokeWidth = 1.2f
        })
    }

    /**
     * 快捷入口卡（设计板 s01 左下第 1 张，338×150）。
     *
     * 版式：标题「快捷应用」21px 弱文字（左 18 / 上 10），
     * 下面一排 68×68 圆角 18 的图标底板（surfaceSoft），图标居中，
     * 名称 17px 次文字贴在底板下方 8px。
     */
    private fun drawQuick(canvas: Canvas, rect: RectF) {
        card(canvas, rect)
        cardTitle(canvas, rect, "快捷应用")
        val apps = quickApps
        if (apps.isEmpty()) {
            drawLegacyQuick(canvas, rect)
            return
        }
        for (i in apps.indices) {
            val slotLeft = rect.left + QUICK_PAD + i * (QUICK_SLOT_W + QUICK_SLOT_GAP)
            val cx = slotLeft + QUICK_SLOT_W / 2f
            val iconTop = rect.top + QUICK_CONTENT_TOP
            val app = apps[i]
            // 图标底板：68×68 圆角 18 的 surfaceSoft 方块（设计板 `.appicon`）
            canvas.drawRoundRect(
                RectF(slotLeft, iconTop, slotLeft + QUICK_SLOT_W, iconTop + QUICK_SLOT_W),
                18f, 18f,
                Paint(fill).apply { color = Skin.surfaceSoft },
            )
            if (app.icon != null) {
                drawIcon(canvas, app.icon, cx, iconTop + QUICK_SLOT_W / 2f, QUICK_GLYPH)
            } else {
                fill.color = Skin.TEXT_SOFT
                canvas.drawCircle(cx, iconTop + QUICK_SLOT_W / 2f, 13f, fill)
            }
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.textSize = 17f
            text.color = Skin.TEXT_SOFT
            canvas.drawText(clipLabel(app.label, QUICK_SLOT_W + 16f), cx, iconTop + QUICK_SLOT_W + 8f + 13f, text)
            hits["quick$i"] = RectF(slotLeft, rect.top, slotLeft + QUICK_SLOT_W, rect.bottom)
        }
    }

    /**
     * 底部功能卡的统一标题（设计板：21px 弱文字，距左 18 / 距上 10）。
     */
    private fun cardTitle(canvas: Canvas, rect: RectF, title: String) {
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 21f
        text.color = Skin.TEXT_DIM
        canvas.drawText(title, rect.left + 18f, rect.top + 27f, text)
    }

    /** 没有可启动应用时的兜底：原来的「车辆 / 导航 / 电话 / 全部」四个入口。 */
    private fun drawLegacyQuick(canvas: Canvas, rect: RectF) {
        val labels = listOf("车辆", "导航", "电话", "全部")
        for (i in 0..3) {
            val slotLeft = rect.left + QUICK_PAD + i * (QUICK_SLOT_W + QUICK_SLOT_GAP)
            val cx = slotLeft + QUICK_SLOT_W / 2f
            val iconCy = rect.top + QUICK_CONTENT_TOP + QUICK_SLOT_W / 2f
            fill.color = Skin.TEXT
            when (i) {
                0 -> { // 车
                    stroke.color = Skin.TEXT
                    stroke.strokeWidth = 2.6f
                    val carR = RectF(cx - 20f, iconCy - 13f, cx + 20f, iconCy + 15f)
                    canvas.drawRoundRect(carR, 10f, 10f, stroke)
                    canvas.drawCircle(cx - 13f, iconCy + 17f, 4f, fill)
                    canvas.drawCircle(cx + 13f, iconCy + 17f, 4f, fill)
                }
                1 -> { // 导航箭头
                    val path = Path().apply {
                        moveTo(cx, iconCy - 20f)
                        lineTo(cx + 16f, iconCy + 18f)
                        lineTo(cx, iconCy + 10f)
                        lineTo(cx - 16f, iconCy + 18f)
                        close()
                    }
                    canvas.drawPath(path, fill)
                }
                2 -> { // 电话
                    stroke.color = Skin.TEXT
                    stroke.strokeWidth = 3f
                    val path = Path().apply {
                        moveTo(cx - 16f, iconCy - 16f)
                        lineTo(cx - 6f, iconCy - 6f)
                        lineTo(cx - 12f, iconCy + 2f)
                        quadTo(cx + 2f, iconCy + 16f, cx + 14f, iconCy + 10f)
                        lineTo(cx + 18f, iconCy - 2f)
                        lineTo(cx + 6f, iconCy - 6f)
                    }
                    canvas.drawPath(path, stroke)
                }
                else -> { // 4 格
                    for (row in 0..1) for (col in 0..1) {
                        canvas.drawRoundRect(
                            RectF(cx - 16f + col * 18f, iconCy - 16f + row * 18f, cx - 2f + col * 18f, iconCy - 2f + row * 18f),
                            4f, 4f, fill,
                        )
                    }
                }
            }
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.textSize = 16f
            text.color = Skin.TEXT_SOFT
            canvas.drawText(labels[i], cx, rect.top + cardH - 22f, text)
            hits["quick$i"] = RectF(slotLeft, rect.top, slotLeft + QUICK_SLOT_W, rect.bottom)
        }
    }

    /** 画应用图标：等比缩放到 [size]（70×70 显示位）。 */
    private fun drawIcon(canvas: Canvas, icon: Drawable, cx: Float, cy: Float, size: Float) {
        val iw = icon.intrinsicWidth
        val ih = icon.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            icon.setBounds((cx - size / 2f).toInt(), (cy - size / 2f).toInt(), (cx + size / 2f).toInt(), (cy + size / 2f).toInt())
            icon.draw(canvas)
            return
        }
        val scale = min(size / iw, size / ih)
        val w = iw * scale
        val h = ih * scale
        icon.setBounds(
            (cx - w / 2f).toInt(), (cy - h / 2f).toInt(),
            (cx + w / 2f).toInt(), (cy + h / 2f).toInt(),
        )
        icon.draw(canvas)
    }

    /** 媒体卡：来源行在最上，然后标题、艺术家。 */
    /**
     * 通用音乐卡（设计板 s01 左下第 2 张，338×150）。
     *
     * 版式：
     * `
     * 通用音乐 ▾ ← 21px 弱文字（左 18 / 上 10），可点（换来源）
     * ┌────┐ 歌名 ← 封面 68×68 圆角 18（左 18 / 上 42），标题 26px 粗体
     * │封面│ 歌手 ← 18px 次文字
     * └────┘ ▶ ← 进度环 + 播放键靠右
     * `
     */
    private fun drawMusicCard(canvas: Canvas, rect: RectF) {
        val x = rect.left
        val top = rect.top
        val w = rect.width()
        val h = rect.height()
        card(canvas, rect)
        // 卡被压窄时（卡片库加卡 / 小档）整体按比例缩，避免控件跑出卡片。
        val k = (w / 338f).coerceIn(0.62f, 1.15f)
        // 封面光晕。
        // 里它是音乐卡的**背景层**（marginStart/End = -24 → 铺满整张卡）
        // 画在封面 / 文字 / 进度环**之下**。
        drawCoverGlow(canvas, rect)

        // ① 来源行（图标 + 名称 + 箭头）—— 设计板放在卡片**左上角**
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = (21f * k).coerceAtLeast(12f)
        text.color = Skin.TEXT_DIM
        val srcY = top + 10f
        // 第125轮：来源图标（真实 App 图标优先，取不到用自绘符号）。
        // 图标与原来的品牌色圆点**同中心**（x+22k / srcY+12k），文字起点也不变，
        // 所以只是把那个小圆点换成了图标，版式没有漂。
        val iconSize = 16f * k
        val iconCx = x + 22f * k
        val iconCy = srcY + 12f
        val appIcon = mediaSourceIcon
        if (appIcon != null) {
            val half = iconSize / 2f
            val box = RectF(iconCx - half, iconCy - half, iconCx + half, iconCy + half)
            // 圆角矩形裁剪（与卡片里的封面图一个风格）。
            val clip = Path().apply {
                addRoundRect(box, iconSize * 0.22f, iconSize * 0.22f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(appIcon, null, box, fill)
            canvas.restore()
        } else {
            fill.color = MediaSources.brandColor(mediaSource)
            com.cardesk.launcher.ui.widget.MediaSourceIcons.drawFallback(
                canvas, mediaSourceIconKind, iconCx, iconCy, iconSize * 0.5f, fill.color,
            )
        }
        text.color = Skin.TEXT_DIM
        canvas.drawText(mediaSource, x + 34f * k, srcY + 20f, text)
        val srcNameW = text.measureText(mediaSource)
        stroke.color = Skin.TEXT_DIM
        stroke.strokeWidth = 1.6f
        val chx = x + 34f * k + srcNameW + 6f * k
        val chy = srcY + 12f
        canvas.drawLine(chx, chy, chx + 5f, chy + 5f, stroke)
        canvas.drawLine(chx + 5f, chy + 5f, chx + 10f * k, chy, stroke)
        // 来源行可点（sceneSource 的独立点击区）
        hits["source"] = RectF(x + 14f * k, srcY, chx + 14f * k, srcY + 30f)

        // ② 封面 68×68 圆角 18（设计板 `.appicon`）
        val coverSize = 68f * k
        val cover = RectF(x + 18f * k, top + 42f, x + 18f * k + coverSize, top + 42f + coverSize)
        val art = mediaArtwork
        if (art != null) {
            val path = Path().apply { addRoundRect(cover, 18f, 18f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(art, null, cover, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            // 第五十一轮：设计板 s01 的通用音乐卡封面是**纯色 `--surfaceSoft` 圆角块**，
            // 之前画的是「强调色 → surfaceStrong」的渐变，日间模式下和设计板差一截。
            canvas.drawRoundRect(cover, 18f, 18f, Paint(fill).apply {
                color = Skin.surfaceSoft
            })
        }
        stroke.color = Skin.ACCENT
        stroke.strokeWidth = 3f
        canvas.drawLine(cover.centerX() + 4f, cover.centerY() - 14f, cover.centerX() + 4f, cover.centerY() + 6f, stroke)
        canvas.drawCircle(cover.centerX() - 2f, cover.centerY() + 11f, 5.5f, stroke)
        canvas.drawLine(cover.centerX() + 4f, cover.centerY() - 14f, cover.centerX() + 13f, cover.centerY() - 10f, stroke)

        // ③ 文本列（封面右边距 14 → x = 18 + 68 + 14 = 100）
        val tx = cover.right + 14f * k
        val ringR = MusicCardVisuals.ringRadius(MUSIC_RING_SIZE, MUSIC_RING_STROKE) * k
        val textColW = (x + w - 18f * k - ringR * 2f - 10f * k - tx).coerceAtLeast(24f)
        // 标题 26px 粗体
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = (26f * k).coerceAtLeast(16f)
        text.color = Skin.TEXT
        canvas.drawText(clipLabel(mediaTitle, textColW), tx, top + 70f, text)

        // ④ 艺术家 18px（没有就留空，默认空串）
        text.typeface = Typeface.DEFAULT
        text.textSize = (18f * k).coerceAtLeast(12f)
        text.color = Skin.TEXT_SOFT
        canvas.drawText(clipLabel(mediaArtist, textColW), tx, top + 98f, text)

        // 进度环 + 播放按钮
        // 两者同心，都在一个 64×64 的 FrameLayout 里，播放按钮 `layout_gravity="center"`。
        // 64×64 容器的右边缘 = 卡片右 − paddingEnd 18。
        val bx = x + w - 18f * k - (MUSIC_RING_SIZE / 2f) * k
        val by = top + h / 2f
        // 进度环在播放按钮**之下**（里 是 sceneMusicPlay 的前一个兄弟节点）。
        drawMusicRing(canvas, bx, by, k)
        canvas.drawCircle(bx, by, 28f * k, Paint(fill).apply { color = Skin.surfaceStrong })
        fill.color = Skin.TEXT
        if (mediaPlaying) {
            canvas.drawRoundRect(RectF(bx - 9f * k, by - 9f * k, bx - 3f * k, by + 9f * k), 2f, 2f, fill)
            canvas.drawRoundRect(RectF(bx + 3f * k, by - 9f * k, bx + 9f * k, by + 9f * k), 2f, 2f, fill)
        } else {
            val tri = Path().apply {
                moveTo(bx - 6f * k, by - 10f * k)
                lineTo(bx + 10f * k, by)
                lineTo(bx - 6f * k, by + 10f * k)
                close()
            }
            canvas.drawPath(tri, fill)
        }
        hits["play"] = RectF(bx - 28f * k, by - 28f * k, bx + 28f * k, by + 28f * k)
        // 封面可点：打开音乐页
        hits["musicCover"] = cover
    }

    /**
     * 封面光晕。
     *
     *  [] 是音乐卡里的**背景层**
     * `layout_marginStart/-End = -24` 让它比卡片内容区（352）宽出两侧各 24，
     * 于是**铺满整张卡**（400×150）；径向渐变规格：
     * `
     * 中心 = (width * 0.1, height * 0.5) → 卡片左侧封面附近（封面从 +24 开始）
     * 半径 = max(width, height) → 400
     * 颜色 = argb(128, 主色) → 0，位置 0 → 0.55
     * 裁剪 = 圆角 34
     * `
     * 没有封面时用默认主色。
     */
    private fun drawCoverGlow(canvas: Canvas, rect: RectF) {
        val accent = accentColor()
        val shader = glowShaderFor(rect, accent) ?: return
        canvas.save()
        canvas.clipPath(
            Path().apply { addRoundRect(rect, GLASS_RADIUS, GLASS_RADIUS, Path.Direction.CW) },
        )
        canvas.drawRect(rect, Paint(fill).apply { this.shader = shader })
        canvas.restore()
    }

    /** 缓存好的封面光晕渐变（RadialGradient 是原生对象，别每帧重建）。 */
    private var glowShader: RadialGradient? = null
    private var glowAccent: Int = Int.MIN_VALUE
    private var glowRect: RectF? = null

    /**
     * 取封面光晕渐变（主色或卡片几何变了才重建）。
     *
     * 规格
     * `
     * 中心 = (width * 0.1f, height * 0.5f)
     * 半径 = max(width, height)
     * 颜色 = argb(128, 主色) → 0，位置 0 → 0.55
     * `
     */
    private fun glowShaderFor(rect: RectF, accent: Int): RadialGradient? {
        val cached = glowShader
        if (cached != null && glowAccent == accent && glowRect?.equals(rect) == true) return cached
        val cx = MusicCardVisuals.glowCenterX(rect.left, rect.width())
        val cy = MusicCardVisuals.glowCenterY(rect.top, rect.height())
        val radius = MusicCardVisuals.glowRadius(rect.width(), rect.height())
        if (radius <= 0f) return null
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                MusicCardVisuals.glowColor(accent, MUSIC_GLOW_ALPHA),
                0x00000000,
            ),
            floatArrayOf(0f, MUSIC_GLOW_END),
            Shader.TileMode.CLAMP,
        )
        glowShader = shader
        glowAccent = accent
        glowRect = RectF(rect)
        return shader
    }

    /**
     * 音乐卡进度环。
     *
     *
     * `
     * 半径 = min(w/2, h/2) − max(1, 描边) = 40 − 2 = 38
     * 轨道 = （夜间 0x1FFFFFFF）实心描边圆
     * 进度 = （夜间 0xFFEEF3FA = Skin.TEXT），ROUND 端点，
     *       起始角 −90°，扫过 fraction × 360°
     * `
     * fraction = 播放位置 / 总时长
     * 总时长不可用（≤0）时置 0 —— 只画轨道。
     */
    private fun drawMusicRing(canvas: Canvas, cx: Float, cy: Float, scale: Float = 1f) {
        val r = MusicCardVisuals.ringRadius(MUSIC_RING_SIZE, MUSIC_RING_STROKE) * scale
        if (r <= 0f) return
        stroke.strokeWidth = MUSIC_RING_STROKE * scale
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = MUSIC_RING_TRACK
        canvas.drawCircle(cx, cy, r, stroke)
        val fraction = MusicCardVisuals.ringFraction(mediaPositionMs, mediaDurationMs)
        if (fraction > 0f) {
            stroke.color = Skin.TEXT
            stroke.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(
                RectF(cx - r, cy - r, cx + r, cy + r),
                -90f, fraction * 360f, false, stroke,
            )
            stroke.strokeCap = Paint.Cap.BUTT
        }
    }

    /**
     * 快捷车控卡（设计板 s01 左下第 3 张，338×150）。
     *
     * 版式：标题「快捷车控」21px 弱文字（左 18 / 上 10），下面三个位（主驾加热 / 主驾通风 / 最大风挡）。
     *
     * 第九十五轮 C2 修复：卡片宽度不再固定 338——卡片库/尺寸档位/加满 7 张卡时会被压窄，
     * 所以这里按 `rect.width()` 等比缩放内边距、三个位的宽度、图标与文字，
     * 再窄也不会画到卡片外面（[k] 是相对 338 的缩放系数）。
     */
    private fun drawClimateCard(canvas: Canvas, rect: RectF) {
        val x = rect.left
        val top = rect.top
        card(canvas, rect)
        cardTitle(canvas, rect, "快捷车控")
        val k = (rect.width() / CLIMATE_CARD_W).coerceIn(0.55f, 1.25f)
        val pad = CLIMATE_CARD_PAD * k
        val gap = CLIMATE_SEAT_GAP * k
        val seatW = ((rect.width() - pad * 2f - gap * 2f) / 3f).coerceAtLeast(24f)
        val s = state
        val seatTop = top + 34f * k
        val seatH = (cardH - 34f * k - 8f).coerceAtLeast(50f)
        seat(
            canvas, x + pad, seatTop, seatW, seatH,
            "主驾加热", s?.seatHeatDriver ?: 0, Skin.WARN, k,
        )
        seat(
            canvas, x + pad + seatW + gap, seatTop, seatW, seatH,
            "主驾通风", s?.seatVentDriver ?: 0, Skin.ACCENT_LIGHT, k,
        )
        // MAX 风量：`HvacController.isPower() && getFanSpeed() >= 7`
        val mx = x + pad + (seatW + gap) * 2f
        val mxR = RectF(mx, seatTop, mx + seatW, seatTop + seatH)
        val maxOn = s?.power == true && (s.fanLevel ?: 0) >= 7
        canvas.drawRoundRect(mxR, 18f * k, 18f * k, Paint(fill).apply { color = Skin.surfaceSoft })
        val mxColor = if (maxOn) Skin.ACCENT else Skin.TEXT_SOFT
        stroke.color = mxColor
        stroke.strokeWidth = (3f * k).coerceAtLeast(1.5f)
        val fcx = mxR.centerX()
        val fcy = seatTop + SEAT_CONTENT_TOP * k + 15f * k
        canvas.drawCircle(fcx, fcy, 4f * k, Paint(fill).apply { color = mxColor })
        for (i in 0..2) {
            val a = Math.toRadians((i * 120).toDouble())
            canvas.drawCircle(
                fcx + (Math.cos(a) * 15f * k).toFloat(),
                fcy + (Math.sin(a) * 15f * k).toFloat(),
                10f * k, stroke,
            )
        }
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = (15f * k).coerceAtLeast(10f)
        text.color = mxColor
        val fanLabelY = seatTop + SEAT_CONTENT_TOP * k + 30f * k + 4f * k + 15f * k
        canvas.drawText(fitText("最大风挡", seatW - 8f * k), mxR.centerX(), fanLabelY, text)
        // 进度条（22×4）
        fill.color = if (maxOn) Skin.ACCENT else Skin.surfaceStrong
        val maxBarY = fanLabelY + 6f * k
        canvas.drawRoundRect(
            RectF(mxR.centerX() - 11f * k, maxBarY, mxR.centerX() + 11f * k, maxBarY + 4f * k),
            2f * k, 2f * k, fill,
        )
        hits["seatHeat"] = RectF(x + pad, seatTop, x + pad + seatW, seatTop + seatH)
        hits["seatVent"] = RectF(x + pad + seatW + gap, seatTop, x + pad + seatW * 2f + gap, seatTop + seatH)
        hits["maxFan"] = mxR
    }

    /** 座椅位：图标 + 文案 + 3 段进度；[k] 是卡片宽度不足时的等比缩放。 */
    private fun seat(
        canvas: Canvas,
        x: Float,
        top: Float,
        w: Float,
        h: Float,
        label: String,
        level: Int,
        tint: Int,
        k: Float = 1f,
    ) {
        val r = RectF(x, top, x + w, top + h)
        canvas.drawRoundRect(r, 18f * k, 18f * k, Paint(fill).apply { this.color = Skin.GLASS_FILL })
        // 座椅轮廓（图标区 30px）
        stroke.color = if (level > 0) tint else Skin.TEXT_SOFT
        stroke.strokeWidth = (3f * k).coerceAtLeast(1.5f)
        val sw = 30f * k
        val sh = 34f * k
        val cx = r.centerX()
        val sy = top + SEAT_CONTENT_TOP * k
        canvas.drawRoundRect(RectF(cx - sw / 2f, sy, cx + sw / 2f, sy + sh), 9f * k, 9f * k, stroke)
        canvas.drawLine(cx - sw / 2f - 7f * k, sy + 7f * k, cx - sw / 2f, sy + sh - 5f * k, stroke)
        canvas.drawLine(cx + sw / 2f + 7f * k, sy + 7f * k, cx + sw / 2f, sy + sh - 5f * k, stroke)
        // 文案（15px，marginTop 4）
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = (15f * k).coerceAtLeast(10f)
        text.color = if (level > 0) tint else Skin.TEXT_SOFT
        val labelY = sy + sh + 4f * k + 15f * k
        // 卡片被压得很窄时，长标签按卡宽裁剪，避免文字跑出卡外。
        canvas.drawText(fitText(label, w - 8f * k), cx, labelY, text)
        // 3 段进度（14×4，间距 4）
        val segTop = labelY + 6f * k
        val segW = 14f * k
        val segGap = 4f * k
        val totalW = 3 * segW + 2 * segGap
        var sx = cx - totalW / 2f
        for (i in 0..2) {
            fill.color = if (i < level) tint else Skin.surfaceStrong
            canvas.drawRoundRect(RectF(sx, segTop, sx + segW, segTop + 4f * k), 2f * k, 2f * k, fill)
            sx += segW + segGap
        }
    }

    /** 按给定最大宽度裁剪文字（超出时加省略号）。 */
    private fun fitText(s: String, maxWidth: Float): String {
        if (s.isEmpty()) return s
        if (text.measureText(s) <= maxWidth) return s
        var out = s
        while (out.isNotEmpty() && text.measureText("$out…") > maxWidth) out = out.dropLast(1)
        return if (out.isEmpty()) "" else "$out…"
    }

    private fun drawTireCard(canvas: Canvas, rect: RectF) {
        val x = rect.left
        val top = rect.top
        val w = rect.width()
        card(canvas, rect)
        // 数据下标语义固定为 FL / FR / RL / RR（见 `vehicle/TireOrder`）
        val tires = info?.tires
        val values = tires?.takeIf { it.size >= 4 } ?: FloatArray(4) { Float.NaN }
        text.typeface = Typeface.DEFAULT

        // 中间的俯视车模：**竖置、车头朝上**，按车型（`CarTopGeometryFactory`）画。
        // 设计空间是 120×240（1:2），这里给 72×144 的框等比缩放（卡高 150，留 3px 边）。
        drawCarTop(canvas, x + w / 2f, top + cardH / 2f - 2f, 72f, 144f)

        // 左列：左前 FL（上）/ 左后 RL（下）；右列：右前 FR（上）/ 右后 RR（下），右对齐。
        // **与第二页（导航页 的）完全一致**——
        // 第一页用的是横置车模（车头朝左，FR/FL | RR/RL）
        // 与第二页天然对不上，这里统一到第二页的顺序。
        val lx = x + 18f
        val rx = x + w - 18f
        val rows = listOf(top + 46f, top + 104f)
        // 第155轮：数值字号跟着设置走（`prefs.deskTpmsValue_sp` → `tpmsValueSp`）。
        // 单位「kPa」还是 14px 的弱文字 —— 它是标注，跟着放大就糊了。
        val valueSp = tpmsValueSp.toFloat()
        text.textSize = valueSp
        for (i in 0..1) {
            text.color = Skin.TEXT
            text.textAlign = Paint.Align.LEFT
            val v = fmtTire(values[TireOrder.leftIndex(i)])    // 左列：FL, RL
            canvas.drawText(v, lx, rows[i], text)
            // 第三十七轮：标签位置按**数值实际宽度**算，不再写死 52f ——
            // 之前写死 52f 时，「240.0」这种 5 个字符的数字（28px 字号约 77px 宽）
            // 会把「kPa」盖掉（真机反馈：首页胎压卡左边的 kPa 被胎压数字覆盖）。
            // 现在数值与标签之间永远留 8px 间隙，位数再多也不会压住。
            //
            // 注意：**必须在数值字号下量宽度** —— 先量再把字号切成 14px，
            // 否则量到的是 14px 的宽度，标签又会压回数字上。
            val valueW = text.measureText(v)
            text.textSize = 14f
            text.color = Skin.TEXT_DIM
            canvas.drawText(TIRE_UNIT, lx + valueW + 8f, rows[i], text)
            text.textSize = valueSp
        }
        for (i in 0..1) {
            text.color = Skin.TEXT
            text.textAlign = Paint.Align.RIGHT
            val v = fmtTire(values[TireOrder.rightIndex(i)])   // 右列：FR, RR
            text.textSize = 14f
            val unitW = text.measureText(TIRE_UNIT)
            text.textSize = valueSp
            canvas.drawText(v, rx - unitW - 8f, rows[i], text)
            text.textSize = 14f
            text.color = Skin.TEXT_DIM
            canvas.drawText(TIRE_UNIT, rx, rows[i], text)
            text.textSize = valueSp
        }
        // `SceneDesktop.disableShortClick(sceneTireCard)`（:702）：胎压卡不可点
    }

    /**
     * 胎压数值：读不到时显示「--」（初值），不显示 NaN。
     *
     * 第三十七轮：**整数**显示，单位 kPa。
     *
     * 真机 4383 诊断包里 TPMS 报的是 `240.00,227.50,237.50,235.00` —— 这是 **kPa**
     * （2.40 bar），之前我们按 bar 标注还带一位小数，「240.0」在 28px 字号下有 5 个字符，
     * 把左边的「kPa」标签整个盖住了。
     *
     * 在单位为 kPa 时用的是
     * `String.valueOf((int) f3)` —— **取整、不带小数**；自己的「设计态」也是显示
     * `Math.round(toKpa(pressure, unitString))`，同样是整数 kPa。
     * 这里：显示 `240`，不显示 `240.0`。
     */
    private fun fmtTire(v: Float): String =
        if (v.isNaN() || v <= 0f) "--" else String.format(java.util.Locale.US, "%d", Math.round(v))

    /**
     * 胎压卡中间的**俯视车模**。
     *
     * 第四轮重做：坐标全部来自 [com.cardesk.launcher.core.CarTopGeometryFactory]
     * （车型 → 轮廓站点 / 玻璃 / 轮子 / 灯组），这里只负责**画**：
     *
     * 1. **四个轮子垫底**（也是先画轮子）——只探出车身 6px
     *    第三十三轮把轮子画在车身上层，变成贴在车身上的白方块，是「车模很丑」的主因；
     * 2. **车身**：横向渐变（中间亮、两侧暗）+ 细描边，轮廓用均匀 Catmull-Rom
     *    三次贝塞尔（曲线真的穿过站点，不会出现胶囊形）；
     * 3. **三段玻璃**：前风挡（上沿鼓起）、车顶、后风挡（下沿鼓起），深色玻璃压深；
     * 4. **灯组**：前灯冷白、尾灯红，让它一眼看出是「车」而不是「胶囊」。
     *
     * 坐标一律在设计空间（120 × 240，车头朝上，中心线 x = 60）里算，最后整体缩放到目标框。
     */
    /**
     * 俯视车模（第136轮起委托给 [com.cardesk.launcher.core.CarTopPainter]）。
     *
     * 车模原来只住在本页（胎压卡 / 车况卡各画一次），
     * 第136轮「车门未关」弹窗也要画同一台车模 → 提取成公共组件，
     * 避免两处画法各改各的、慢慢长得不一样。
     */
    private fun drawCarTop(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        CarTopPainter.draw(
            canvas,
            CarTopPainter.frame(cx, cy, w, h),
            CarTopGeometryFactory.of(carModel),
            carColors,
        )
    }

    /**
     * 当前车控信号 → car3d 的策展姿态。
     *
     * 只读 [info]（每秒由 `LauncherView` 刷新一次），**不会额外触发任何通道读取**；
     * 车窗沿用本页的乐观值合并（[resolveWindowOpenFlags]）。
     */
    /** 当前车型对应的资产目录（null = 没烘焙，走 2D 回退）。 */
    private fun assetDirOrNull(): String? = CarModels.assetDir(carModel)

    private fun car3dPose(): CarPose {
        // 调试注入优先（仅 debuggable 变体，见 Car3dDebugPose）：模拟器截图用。
        com.cardesk.launcher.core.car3d.Car3dDebugPose.pose?.let { return it }
        val i = info
        return CarPose.of(
            i?.doorsClosed,
            resolveWindowOpenFlags(),
            i?.hoodClosed,
            i?.trunkClosed,
        )
    }

    /**
     * 续航卡（设计板 s01 右下，388×150）。
     *
     * 版式：
     * `
     * 续航 ← 21px 次文字（左 22 / 上 14）
     * 386 km ← 50px 粗体（左 22 / 上 40），单位 20px 弱文字
     * ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░ ← 12px 油量条（左 22 / 上 100）
     * 燃料剩余 · 62% ← 17px 弱文字（左 22 / 上 118）
     * `
     *
     * `paintRangeProgress(fuelPercent)` 那条进度条**本来就是油量条**
     * 这里补一个显式百分比，让「油还剩多少」不用去猜条的长度。
     */
    private fun drawRangeCard(canvas: Canvas, rect: RectF) {
        val x = rect.left
        val top = rect.top
        val w = rect.width()
        card(canvas, rect)

        // 标题「续航」
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 21f
        text.color = Skin.TEXT_SOFT
        canvas.drawText("续航", x + 22f, top + 31f, text)

        // 数值 + 单位（卡被压窄时整体缩小，避免数字压到右边缘）
        val k = (w / 338f).coerceIn(0.7f, 1.1f)
        val range = info?.rangeKm?.takeIf { it >= 0 }
        val shown = range?.toString() ?: "--"
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 50f * k
        // 第136轮：续航按剩余里程分级着色（>100 常态 / ≤100 黄 / ≤50 红）。
        text.color = rangeValueColor(range)
        val shownW = text.measureText(shown)
        canvas.drawText(shown, x + 22f * k, top + 80f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f * k
        text.color = Skin.TEXT_DIM
        canvas.drawText("km", x + 22f * k + shownW + 8f, top + 80f, text)

        // 油量条 12px
        val barY = top + 100f
        val barX = x + 22f * k
        val barW = w - 44f * k
        fill.color = Skin.surfaceStrong
        canvas.drawRoundRect(RectF(barX, barY, barX + barW, barY + 12f), 6f, 6f, fill)
        val fuel = fuelPercent
        if (fuel != null && barW > 0f) {
            val ratio = (fuel.coerceIn(0, 100) / 100f)
            fill.color = Skin.ACCENT
            canvas.drawRoundRect(RectF(barX, barY, barX + barW * ratio, barY + 12f), 6f, 6f, fill)
        }

        // 油量文案
        text.typeface = Typeface.DEFAULT
        text.textSize = 17f
        text.color = Skin.TEXT_DIM
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(
            // 第136轮：除了百分比，再给出**具体剩余升数**（`燃料剩余 · 34.1 L · 62%`）。
            fitText(fuelSummaryText(info?.fuelLiters, fuel), w - 44f * k),
            x + 22f * k, top + 131f, text,
        )

        // `SceneDesktop.disableShortClick(sceneCarCard)`（:703）：续航卡不可点
    }

    /**
     * 续航数值的颜色（第136轮）。
     *
     * 口径见 [com.cardesk.launcher.core.RangeLevel]：
     * - `> 100 km` → 常态色；
     * - `50 < r <= 100` → **黄**（[Skin.WARN]，项目的「注意」色）；
     * - `r <= 50` → **红**（[Skin.DANGER]）；
     * - 读不到 → 常态色（配 `--`，不靠颜色表达「没有数据」）。
     */
    private fun rangeValueColor(rangeKm: Int?): Int =
        when (com.cardesk.launcher.core.RangeLevel.levelOf(rangeKm)) {
            com.cardesk.launcher.core.RangeLevel.Level.WARN -> Skin.WARN
            com.cardesk.launcher.core.RangeLevel.Level.DANGER -> Skin.DANGER
            else -> Skin.TEXT
        }

    /**
     * 续航卡底部的油量文案（第136轮）。
     *
     * `燃料剩余 · 34.1 L · 62%`
     *
     * 升数来自 [com.cardesk.launcher.vehicle.VehicleInfo.fuelLiters]
     * （= 百分比 × 油箱容量，读不到容量就没有升数）。
     */
    private fun fuelSummaryText(liters: Float?, percent: Int?): String =
        "燃料剩余 · ${VehicleStats.formatFuelLiters(liters)} · ${VehicleStats.formatFuelPercent(percent)}"

    // ---------------------------------------------------------------- 车况卡

    /**
     * 车况卡「快照版」（第167轮，按用户参考图放大为 388×450）。
     *
     * ```
     * 车况 · 快照                        ← 15px dim
     * 386  km 续航                       ← 44px + 14px
     * ▓▓▓▓▓▓▓░░░░░░░░░                  ← 260×6 进度条
     * 油量 62% · 预估到达有余            ← 12px dim
     * ┌───────────────────────────┐
     * │ 车模 356×250（三视角可选）  │      ← 车身上浮着胶囊 + 引线
     * │ (门/窗/尾门只报异常；四轮胎压常驻) │
     * └───────────────────────────┘
     * 平均油耗  本行程  总里程  保养     ← 11px 标签 / 15px 值
     * 6.8L     128.4km  12480km  --      （没数据显 --，不编）
     * ```
     *
     * 胶囊位置来自资产 `anchors.<view>`，缺锚点时按车模框均匀排布；
     * 整卡命中 → [onOpenVehicleStatus]（车辆状态面板 10 项全列表）。
     */
    private fun drawCarStatusCard(canvas: Canvas) {
        if (!showCarStatusCard) return
        val i = info ?: return
        val doors = i.doorsClosed
        val windows = resolveWindowOpenFlags()
        val hood = i.hoodClosed
        val trunk = i.trunkClosed
        // 调试姿态注入（仅 debuggable 变体收到广播时非 null）：卡面文字与车模保持一致；
        // 真实车控链路不受影响，只是让模拟器截图不自相矛盾。
        val dp = com.cardesk.launcher.core.car3d.Car3dDebugPose.pose
        val effDoors: List<Boolean?>? = dp?.doors?.map { !it } ?: doors
        val effWindows: BooleanArray? = dp?.windows ?: windows
        val effTrunk: Boolean? = dp?.trunk?.let { !it } ?: trunk
        if (doors == null && windows == null && hood == null && trunk == null) {
            if (!carStatusEmptyLogged) {
                carStatusEmptyLogged = true
                Logx.w(LogTags.SIGNAL, "车况卡全部读不到（车门/车窗/盖）")
            }
        } else {
            carStatusEmptyLogged = false
        }

        val r = RectF(
            rightEnd - CAR_STATUS_CARD_RIGHT_INSET - CAR_STATUS_CARD_W,
            CAR_STATUS_CARD_TOP,
            rightEnd - CAR_STATUS_CARD_RIGHT_INSET,
            CAR_STATUS_CARD_TOP + CAR_STATUS_CARD_H,
        )
        card(canvas, r)
        // 整卡可点：点开「车辆状态」面板。
        hits[CAR_STATUS_CARD_HIT_KEY] = RectF(r)

        // —— 标题 + 详情入口（整卡可点，详情只是可发现性）——
        //
        // 第189轮：这两个字**再放大一倍**（13/12 → 26/24）。
        // 以前 13px 的「车况」在 388 宽的卡上几乎是个角标，模拟器实拍看着像
        // 「卡片自带的一行小字」，用户直接说「太小了」。
        // 放大后标题和胎压数值（22px）同一档，状态行（26px）也比标题醒目。
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = CAR_STATUS_TITLE_SP
        text.color = Skin.TEXT_DIM
        canvas.drawText("车况", r.left + 16f, r.top + CAR_STATUS_TITLE_TOP + CAR_STATUS_TITLE_SP, text)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = CAR_STATUS_LINK_SP
        text.color = Skin.ACCENT
        canvas.drawText("详情 ›", r.right - 16f, r.top + CAR_STATUS_TITLE_TOP + CAR_STATUS_TITLE_SP, text)
        text.textAlign = Paint.Align.LEFT

        val assetDir = CarModels.assetDir(carModel)
        val dir = if (Car3dSettings.enabled) assetDir else null
        // 第179轮：改成资产驱动 —— 只声明 cardtop 的车型走俯视方案（01 已迁，03/05 资产到了自动生效）；
        // 三视角齐全的老资产继续走原逻辑。布局与是否开启 3D 无关，所以用 assetDir 而不是 dir。
        val topDown = Car3dAssets.topDownOnly(context, assetDir)
        val cardView = if (topDown) Car3dAssets.VIEW_CARDTOP else Car3dSettings.cardView

        // —— 车模区（第182轮竖放）：俯视资产已是竖版，图区改 246×300、水平居中；
        // 03/05 的旧 3/4 视角仍用 356×292 的旧区。比例一律按 model.json 的 slot 算，不硬编码。——
        val boxW = if (topDown) CAR_TOP_BOX_W else CAR_STATUS_CAR_W
        val boxH = if (topDown) CAR_TOP_BOX_H else CAR_STATUS_CAR_H_LEGACY
        val boxTop = if (topDown) CAR_TOP_BOX_TOP else CAR_STATUS_CAR_TOP_LEGACY
        val boxX = if (topDown) r.left + (CAR_STATUS_CARD_W - boxW) / 2f else r.left + CAR_STATUS_CAR_X
        val carArea = RectF(boxX, r.top + boxTop, boxX + boxW, r.top + boxTop + boxH)
        car3d.setPose(context, dir, car3dPose(), rich = true, view = cardView)
        // 降级链可能落到不同比例的视角（如 card 缺失→hero），按**实际会用**的视角算比例。
        val usedView = Car3dAssets.usedView(context, dir, cardView)
        val slot = Car3dAssets.slotOf(context, dir, usedView)
        val image = if (slot != null && slot.second > 0) {
            fitIn(carArea, slot.first.toFloat() / slot.second)
        } else {
            carArea
        }
        // 第181轮（选型 A）：俯视车模**整体下沉 [CAR_TOP_SHIFT_Y] + 缩到 [CAR_TOP_SCALE]**，
        // 让四角胎压有呼吸感；宽高比不变，中心仍是图区中心（只加垂直位移）。
        // 03/05 的旧 3/4 视角不走这段（它们没有俯视资产，保持原尺寸）。
        val imageRect = if (topDown) {
            val cx = image.centerX()
            val cy = image.centerY()
            val hw = image.width() / 2f * CAR_TOP_SCALE
            val hh = image.height() / 2f * CAR_TOP_SCALE
            RectF(cx - hw, cy - hh + CAR_TOP_SHIFT_Y, cx + hw, cy + hh + CAR_TOP_SHIFT_Y)
        } else {
            image
        }
        val car3dPainted = car3d.paint(canvas, context, cardView, imageRect, fit = false)
        if (!car3dPainted) {
            val frame = CarTopPainter.frame(carArea.centerX(), carArea.centerY(), CAR_STATUS_MODEL_W, CAR_STATUS_MODEL_H)
            CarTopPainter.draw(canvas, frame, CarTopGeometryFactory.of(carModel), carColors)
        }

        // —— 一行状态文字（最多两行）：只有车门/车窗，车窗黄色 ——
        val items = CarStatus.items(effDoors, effWindows, hood, effTrunk)
        drawSnapshotStatusText(canvas, r, items)

        // —— 胎压：四个小胶囊贴到各自的轮子旁（锚点来自资产）——
        lastCarImageRect = RectF(imageRect)
        drawTirePills(canvas, r, i, imageRect, closedRefBounds(context, assetDirOrNull(), imageRect), topDown, context)
    }

    /**
     * 车况卡状态文字（第174轮）：车窗也进文字，按「处数」决定展开还是合并。
     *
     * - 0 处 → 「全车已关」（暗色）；
     * - 1~2 处（车门/车窗/尾门各算一处）→ 逐个列位置，车门橙、车窗黄、尾门橙；
     * - ≥3 处 → 合并一句（「车门、窗未关」/只门/只窗的措辞由
     *   [CardSnapshot.statusParts] 给出）。
     *
     * 车窗同时仍然在车模玻璃上叠黄（winpatches），两条表达互不冲突。
     * 引擎盖没有数据通道，不参与。
     */
    private fun drawSnapshotStatusText(canvas: Canvas, r: RectF, items: List<CarStatusItem>) {
        // 车门 0..3、尾门 4、车窗 5..8；三处以上合并成一句（见 CardSnapshot.statusParts）。
        val doors = ArrayList<String>(4)
        val windows = ArrayList<String>(4)
        var trunkOpen = false
        items.forEachIndexed { idx, it ->
            if (it.open != true) return@forEachIndexed
            when (idx) {
                in CAR_STATUS_DOOR_ITEMS -> doors.add(it.label)
                CAR_STATUS_TRUNK_ITEM -> trunkOpen = true
                in CAR_STATUS_WINDOW_ITEMS -> windows.add(it.label)
            }
        }
        val parts = CardSnapshot.statusParts(doors, windows, trunkOpen)
        text.typeface = Typeface.DEFAULT
        // 第189轮：状态行也**放大一倍**（13 → 26）。最长的一句是
        // 「左前门、右后门 未关」（约 10 个汉字 ≈ 260px），卡内可用宽 388-32=356px，
        // 放得下；≥3 处未关时会合并成「车门未关」这种短句，更没问题。
        text.textSize = CAR_STATUS_STATUS_SP
        text.textAlign = Paint.Align.LEFT
        val y = r.top + CAR_STATUS_STATUS_Y
        var x = r.left + 16f
        parts.forEachIndexed { i, part ->
            if (i > 0) {
                text.color = Skin.TEXT_DIM
                canvas.drawText("、", x, y, text)
                x += text.measureText("、")
            }
            text.color = when (part.kind) {
                // 第180轮：告警色也跟着日夜走 —— 亮橙 / 亮黄落在白底上读不出来。
                CardSnapshot.StatusKind.WINDOW -> Skin.WINDOW_OPEN
                CardSnapshot.StatusKind.DOOR -> Skin.CAR_STATE_WARN
                CardSnapshot.StatusKind.GENERIC ->
                    if (doors.isNotEmpty() || trunkOpen) Skin.CAR_STATE_WARN else Skin.WINDOW_OPEN
                CardSnapshot.StatusKind.NONE -> Skin.TEXT_DIM
            }
            canvas.drawText(part.text, x, y, text)
            x += text.measureText(part.text)
        }
        // 0 处（全车已关）和 ≥3 处（合并句自带「未关」）都不补后缀；
        // 只有 1~2 处的「位置」列表需要统一补「 未关」。
        if (parts.isNotEmpty() &&
            parts[0].kind != CardSnapshot.StatusKind.NONE &&
            parts[0].kind != CardSnapshot.StatusKind.GENERIC
        ) {
            text.color = Skin.TEXT_DIM
            canvas.drawText(" 未关", x, y, text)
        }
    }

    /**
     * 第183轮：四颗胎压的**中心坐标**（画布坐标）。抽成纯函数是为了让测试/验收能拿到数字，
     * 也保证「开门态」与「全关态」用的是同一套位置（只依赖 [ref] 关车态参考框）。
     *
     * 俯视竖放时：k=0 左上前轮、1 右上前轮、2 左下后轮、3 右下后轮；
     * 后轮两颗在第184轮改为「卡内侧下方两角」（由 `drawTirePills` 覆盖），这里只给初值。
     */
    /** 胶囊矩形到「关车态车体像素」的最近距离（设计 px）；用来判断上移后会不会贴住车漆。 */
    private fun minDistanceToCar(mask: android.graphics.Bitmap, image: RectF, left: Float, top: Float): Float {
        val pillW = TIRE_PILL_W
        val pillH = TIRE_PILL_H
        val sx = mask.width / image.width()
        val sy = mask.height / image.height()
        val pad = 12f
        val x0 = ((left - pad - image.left) * sx).toInt().coerceAtLeast(0)
        val y0 = ((top - pad - image.top) * sy).toInt().coerceAtLeast(0)
        val x1 = ((left + pillW + pad - image.left) * sx).toInt().coerceAtMost(mask.width - 1)
        val y1 = ((top + pillH + pad - image.top) * sy).toInt().coerceAtMost(mask.height - 1)
        var best = Float.MAX_VALUE
        val r = RectF(left, top, left + pillW, top + pillH)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if ((mask.getPixel(x, y) ushr 24) < 200) continue
                val px = image.left + x / sx
                val py = image.top + y / sy
                val dx = when {
                    px < r.left -> r.left - px
                    px > r.right -> px - r.right
                    else -> 0f
                }
                val dy = when {
                    py < r.top -> r.top - py
                    py > r.bottom -> py - r.bottom
                    else -> 0f
                }
                val d = kotlin.math.hypot(dx, dy)
                if (d < best) best = d
            }
        }
        return if (best == Float.MAX_VALUE) 999f else best
    }

    internal fun tirePillCenters(r: RectF, image: RectF, ref: RectF?, topDown: Boolean): List<PointF> {
        val hw = TIRE_PILL_W / 2f
        val hh = TIRE_PILL_H / 2f
        val out = ArrayList<PointF>(4)
        for (k in 0 until 4) {
            if (!topDown) {
                val lx = (r.left + CAR_TIRE_LEGACY_X[k] * CAR_STATUS_CARD_W)
                    .coerceIn(r.left + 4f + hw, r.right - 4f - hw)
                val ly = (r.top + CAR_TIRE_LEGACY_Y[k] * CAR_STATUS_CARD_H)
                    .coerceIn(r.top + 4f + hh, r.top + CAR_STATUS_CARD_H - 4f - hh)
                out.add(PointF(lx, ly))
                continue
            }
            val insetX = hw + CAR_TIRE_INSET
            val insetY = hh + CAR_TIRE_INSET
            var x = if (k % 2 == 0) image.left + insetX else image.right - insetX
            var y = if (k < 2) image.top + insetY else image.bottom - insetY
            // 第184轮：前轮两颗贴「关车态车体框」左右两侧、上下留 6px（此前贴的是图区边，离车体可能不足 6px）。
            if (ref != null && k < 2) {
                x = if (k % 2 == 0) ref.left - 6f - hw else ref.right + 6f + hw
                y = ref.top - 6f - hh
            }
            if (ref != null) {
                val vy = y + hh > ref.top - 6f && y - hh < ref.bottom + 6f
                val vx = x + hw > ref.left - 6f && x - hw < ref.right + 6f
                if (vy) {
                    if (k % 2 == 0) {
                        if (x + hw > ref.left - 6f) x = ref.left - 6f - hw
                    } else if (x - hw < ref.right + 6f) {
                        x = ref.right + 6f + hw
                    }
                }
                if (vx) {
                    if (k < 2) {
                        if (y + hh > ref.top - 6f) y = ref.top - 6f - hh
                    } else {
                        val gap = (y - hh) - ref.bottom
                        if (gap < 6f) y = ref.bottom + 6f + hh
                    }
                }
            }
            x = x.coerceIn(r.left + 4f + hw, r.right - 4f - hw)
            y = y.coerceIn(r.top + 4f + hh, r.top + CAR_STATUS_CARD_H - 4f - hh)
            out.add(PointF(x, y))
        }
        return out
    }

    /**
     * 关车态（`closed`）车体实心外接框，换算成画布坐标。
     *
     * 关键：**只看 closed 一张图**，与当前门态无关 —— 这样开门时胶囊不会跟着车体框变大而外推
     * （用户第183轮反馈「开门后胎压离车更远」就是旧逻辑按当前状态算造成的）。
     */
    private fun closedRefBounds(context: Context, dir: String?, image: RectF): RectF? {
        if (dir == null || !Car3dSettings.enabled) return null
        val (slotW, slotH) = Car3dAssets.slotOf(context, dir, Car3dAssets.VIEW_CARDTOP) ?: return null
        val n = Car3dAssets.closedBoundsNorm(context, dir, Car3dAssets.VIEW_CARDTOP, slotW, slotH)
            ?: return null
        return RectF(
            image.left + n.left * image.width(),
            image.top + n.top * image.height(),
            image.left + n.right * image.width(),
            image.top + n.bottom * image.height(),
        )
    }

    /**
     * 胎压贴轮（第170轮）：四个小胶囊画在各自轮子旁边，不再排一排放在卡底。
     *
     * 锚点取 `anchors.<view>.tires.{fl,fr,rl,rr}`（兼容扁平写法 `tire_*`）；
     * 没锚点就用「图框四角偏内」兜底，不写死具体像素。异常（<2.0 / >2.8 bar）橙色。
     *
     * ## 第180轮：胶囊底色原来写死成深色（`0xE010141B`）
     *
     * 那个深色是给**夜间**配的：夜里卡片本身是深色，深胶囊 + 浅字才读得出。
     * 白天卡片是白的，而字色跟着主题走（`Skin.TEXT` = 白底上的深灰）——
     * **深底深字**叠在一起，四个胎压就成了四坨黑（真机反馈）。
     *
     * 现在底色也走主题 token（[Skin.surfaceStrong]）：
     * 夜里是深灰胶囊 + 浅字，白天是浅灰胶囊 + 深字，两套都读得出来。
     */
    private fun drawTirePills(
        canvas: Canvas,
        r: RectF,
        i: VehicleInfo,
        image: RectF,
        closedRef: RectF?,
        topDown: Boolean,
        context: Context,
    ) {
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.CENTER

        val pillW = TIRE_PILL_W
        val pillH = TIRE_PILL_H
        // 第178轮（俯视横置）：四颗胎压先按「图区四角、离车 12px」摆 ——
        // fl 左上 / fr 右上 / rl 左下 / rr 右下，不画引线；实拍后再按车轮廓微调。
        // 可调常量：胶囊中心离图区左右/上下边的距离（实拍后按车轮廓微调这里）。
        val tireInset = CAR_TIRE_INSET
        val insetX = pillW / 2f + tireInset
        val insetY = pillH / 2f + tireInset

        class Item(val value: String, val abnormal: Boolean, val cx: Float, val cy: Float)

        // 老资产（hero/card 视角）仍用第177轮用户手调的位置（卡片相对坐标）。
        val legacyX = CAR_TIRE_LEGACY_X
        val legacyY = CAR_TIRE_LEGACY_Y

        // 第183轮（用户反馈）：胶囊位置**只按关车态参考框**算 —— 开门时车体框会变大，
        // 旧逻辑会把胶囊往外推，看起来「开门后胎压离车更远」；现在位置与门态无关。
        // 第184轮：后轮两颗不能在车尾正下方（那里只有 380 卡高、会和状态文字打架），
        // 改到「卡内侧下方两角」：x 贴卡边 14px；y 让底边离状态文字顶边 6px。
        val ref = if (topDown) (closedRef ?: image) else null
        debugPillGeom = "r=$r image=$image ref=$ref top=$topDown"
        val centers = tirePillCenters(r, image, ref, topDown).toMutableList()
        if (topDown) {
            // 第185轮（用户澄清）：「胎压的位置和车轮在同一水平线」—— y 取竖版锚点 tires.*[1]，
            // x 仍贴卡边（锚点在左半走左列、右半走右列），保证不压车。
            val anchors = Car3dAssets.anchors(context, assetDirOrNull(), Car3dAssets.VIEW_CARDTOP)
            if (anchors != null) {
                val keys = arrayOf("tires.fl", "tires.fr", "tires.rl", "tires.rr")
                val body = ref ?: image
                for (k in 0 until 4) {
                    val a = anchors[keys[k]] ?: continue
                    // x：离「关车态车体框」左右边固定留 GAP（用户：稍微留点缝隙，不是贴卡边）。
                    val x = if (a.x < 0.5f) body.left - CAR_TIRE_GAP - pillW / 2f
                    else body.right + CAR_TIRE_GAP + pillW / 2f
                    centers[k] = PointF(
                        x.coerceIn(r.left + 4f + pillW / 2f, r.right - 4f - pillW / 2f),
                        (image.top + a.y * image.height())
                            .coerceIn(r.top + 4f + pillH / 2f, r.bottom - 4f - pillH / 2f),
                    )
                }
            }
        }
        val items = ArrayList<Item>(4)
        for (k in 0 until 4) {
            val px = centers[k].x
            val py = centers[k].y

            val raw = i.tires.getOrNull(k)?.takeIf { !it.isNaN() && it > 0f }
            val bad = raw == null
            val bar = raw?.let { it / 100f } ?: Float.NaN
            val abnormal = !bad && (bar < 2.0f || bar > 2.8f)
            val value = if (bad) "--" else String.format(java.util.Locale.US, "%.1f", bar)

            items.add(Item(value, abnormal, px, py))
        }

        // 简单避让：两两之间保持至少 4px 间距（只在真的重叠时沿 y 推开）。
        for (a in 0 until 4) for (b in a + 1 until 4) {
            val ia = items[a]
            val ib = items[b]
            val ox = kotlin.math.abs(ia.cx - ib.cx)
            val oy = kotlin.math.abs(ia.cy - ib.cy)
            if (ox < pillW + 4f && oy < pillH + 4f) {
                val push = (pillH + 4f - oy) / 2f
                val up = if (a == 0) -push else push
                val moved = Item(ia.value, ia.abnormal, ia.cx, ia.cy + up)
                items[a] = moved
            }
        }

        lastTirePillCenters = items.map { PointF(it.cx, it.cy) }

        for (it in items) {
            val left = it.cx - pillW / 2f
            val top = it.cy - pillH / 2f
            val right = left + pillW
            val bottom = top + pillH
            // 异常胎压用琥珀色（日夜各一套：夜里 #E0A340，白天 #B7791F —— 白底上亮琥珀也看不清）。
            val color = if (it.abnormal) Skin.WARN else Skin.ACCENT

            fill.style = Paint.Style.FILL
            // 底色跟着主题走（第180轮）：夜里深灰胶囊、白天浅灰胶囊，
            // 不再是写死的 `0xE010141B` —— 那个深色在白底上配深字就是「一坨黑」。
            fill.color = Skin.surfaceStrong
            canvas.drawRoundRect(left, top, right, bottom, TIRE_PILL_RADIUS, TIRE_PILL_RADIUS, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 1f
            fill.color = (color and 0x00FFFFFF) or (170 shl 24)
            canvas.drawRoundRect(left, top, right, bottom, TIRE_PILL_RADIUS, TIRE_PILL_RADIUS, fill)
            fill.style = Paint.Style.FILL

            // 数值 22px（原 11px 的 2 倍）、单位 14px（原 7px 的 2 倍）。
            text.textSize = TIRE_PILL_VALUE_SP
            text.color = if (it.abnormal) Skin.WARN else Skin.TEXT
            canvas.drawText(it.value, it.cx, top + TIRE_PILL_VALUE_BASELINE, text)
            text.textSize = TIRE_PILL_UNIT_SP
            text.color = Skin.TEXT_SOFT
            canvas.drawText("bar", it.cx, top + TIRE_PILL_UNIT_BASELINE, text)
        }
        text.textAlign = Paint.Align.LEFT
    }


    /** 在 [area] 里按 [aspect]（= 宽/高）等比居中。 */
    private fun fitIn(area: RectF, aspect: Float): RectF {
        if (aspect <= 0f) return RectF(area)
        val w = min(area.width(), area.height() * aspect)
        val h = w / aspect
        val cx = area.centerX()
        val cy = area.centerY()
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }



    /**
     * 车窗面板的点击热区。
     *
     * 第166轮 C 版后车况卡不再注册逐窗命中区（整卡可点开面板），
     * 这个函数只留给「以后别的卡要用」的历史实现，**当前无调用**。
     */
    @Suppress("unused")
    private fun expandWindowHit(pr: RectF, frame: CarTopPainter.Frame): RectF {
        val outward = 10f * frame.scale
        val inward = 4f * frame.scale
        val vertical = 8f * frame.scale
        val mid = (pr.left + pr.right) / 2f
        return if (mid < frame.centerX) {
            RectF(pr.left - outward, pr.top - vertical, pr.right + inward, pr.bottom + vertical)
        } else {
            RectF(pr.left - inward, pr.top - vertical, pr.right + outward, pr.bottom + vertical)
        }
    }

    /**
     * 四扇车窗的**每扇**开闭（顺序 FL/FR/RL/RR，**true = 开**）；读不到返回 null。
     *
     * 取值优先级：
     * 1. 刚下发完、保护期内 → 本地乐观值（四扇一起，机械动作还没回读）；
     * 2. 车端回读（`WINDOW_POS` 属性）→ 真值；
     * 3. 都没有 → null（UI 显示「--」，不拿本地值冒充真值）。
     */
    private fun resolveWindowOpenFlags(): BooleanArray? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < windowPendingUntil) return BooleanArray(4) { !windowPendingClosed }
        return info?.windowsOpen
    }

    /**
     * 车窗显示状态：**true = 关**。
     *
     * 取值优先级：
     * 1. 刚下发完、保护期内 → 本地乐观值（车窗是机械动作，回读要慢几百毫秒）；
     * 2. 车端回读（`WINDOW_POS` 属性）→ 真值；
     * 3. 都没有 → 本地乐观值兜底。
     */
    private fun resolveWindowClosed(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < windowPendingUntil) return windowPendingClosed
        val fromCar = info?.windowsAllClosed
        return fromCar ?: windowClosed
    }

    private fun drawWallpaperHint(canvas: Canvas) {
        if (!hintVisible) return
        val cy = Design.H - 340f
        val name = WALLPAPER_NAMES.getOrElse(wallpaperIndex) { WALLPAPER_NAMES[0] }
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f
        text.color = 0xFFFFFFFF.toInt()
        val nw = text.measureText(name) + 36f
        val cx = Design.W / 2f
        // #59000000、圆角 20
        canvas.drawRoundRect(RectF(cx - nw / 2f, cy - 20f, cx + nw / 2f, cy + 20f), 20f, 20f,
            Paint(fill).apply { color = 0x59000000 })
        canvas.drawText(name, cx, cy + 7f, text)

        // 9 个壁纸点 30×10 / 10×10
        val dotGap = 10f
        var dx = cx - (9 * 30f + 8 * dotGap) / 2f
        for (i in 0 until 9) {
            val on = i == wallpaperIndex
            fill.color = if (on) 0xFFFFFFFF.toInt() else 0x73FFFFFF
            canvas.drawRoundRect(RectF(dx, cy + 28f, dx + 30f, cy + 38f), 5f, 5f, fill)
            hits["wp$i"] = RectF(dx, cy + 20f, dx + 30f, cy + 46f)
            dx += 30f + dotGap
        }
    }

    // ---------------------------------------------------------------- 交互

    private val cardRects = LinkedHashMap<String, RectF>()
    private var dragId: String? = null
    private var dragMoved = false
    private var dragDx = 0f
    private var dragGrabX = 0f
    private var downX = 0f
    private var downY = 0f
    private var downCardId: String? = null
    private var longPressFired = false

    // ---------------------------------------------------------------- 壁纸横拖（已去掉）
    //
    // 原本是「场景页按住空白处横向拖动换壁纸」。
    // **第三十二轮按用户要求去掉**：场景页不再有左右滑换背景主题的手势，
    // 换页改由「左右三指滑动」承担（见 `LauncherView.onInterceptTouchEvent`）。
    //
    // 壁纸本身仍然保留：
    // - 提示条上的圆点仍可点选（`onWallpaperDot`）；
    // - 落盘键仍是 `design_wallpaper_index`。
    //
    // 保留的只是「不抢手势」：空白处的按下不再做轴向判定，直接走点击命中。

    /** 命中测试：按卡片矩形找离 x 最近的卡（拖拽排序用）。 */
    private fun cardIndexAt(x: Float, bounds: List<Pair<Float, Float>>): Int {
        for (i in bounds.indices) {
            val (l, r) = bounds[i]
            if (x in l..r) return i
        }
        if (bounds.isEmpty()) return -1
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in bounds.indices) {
            val (l, r) = bounds[i]
            val c = (l + r) / 2f
            val d = kotlin.math.abs(x - c)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /** 把可见卡的顺序 [visibleOrder] 写回完整布局；隐藏卡按原相对顺序追加到末尾。 */
    private fun reorderVisible(
        layout: List<com.cardesk.launcher.core.CardLayout.Slot>,
        visibleOrder: List<String>,
    ): List<com.cardesk.launcher.core.CardLayout.Slot> {
        val byId = layout.associateBy { it.id }
        val out = ArrayList<com.cardesk.launcher.core.CardLayout.Slot>(layout.size)
        for (id in visibleOrder) byId[id]?.let { out.add(it) }
        for (slot in layout) if (!slot.visible) out.add(slot)
        return com.cardesk.launcher.core.CardLayout.normalize(out)
    }

    private val longPressRunnable = Runnable {
        val id = downCardId ?: return@Runnable
        longPressFired = true
        dragId = id
        dragMoved = false
        dragGrabX = downX
        dragDx = 0f
        parent?.requestDisallowInterceptTouchEvent(true)
        // 排障场景：「底部卡片拖不动」——长按是否真的进入了拖拽态。
        Logx.event(
            LogTags.SCENE,
            "底部卡片长按开始拖拽",
            "card" to id,
            "x" to downX,
            "y" to downY,
        )
        invalidate()
    }

    private fun clipLabel(s: String, max: Float): String {
        if (s.isEmpty()) return ""
        if (text.measureText(s) <= max) return s
        var out = s
        while (out.isNotEmpty() && text.measureText("$out…") > max) out = out.dropLast(1)
        return "$out…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                longPressFired = false
                dragId = null
                dragDx = 0f
                downCardId = cardRects.entries.firstOrNull { it.value.contains(x, y) }?.key
                if (downCardId != null) {
                    main.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = dragId
                if (id != null) {
                    dragDx = x - dragGrabX
                    val bounds = cardRects.entries.map { it.value.left to it.value.right }
                    val hover = cardIndexAt(x, bounds)
                    if (hover >= 0) {
                        val visibleIds = cardRects.keys.toList()
                        val current = visibleIds.indexOf(id)
                        if (current >= 0 && hover != current) {
                            val reordered = visibleIds.toMutableList()
                            reordered.add(hover.coerceIn(0, reordered.size - 1), reordered.removeAt(current))
                            cardLayout = reorderVisible(cardLayout, reordered)
                            dragMoved = true
                            // 排障场景：「拖到某个位置没插进去」——记录每次经过的位置。
                            Logx.d(
                                LogTags.SCENE,
                                "底部卡片拖拽经过位置",
                                "card" to id,
                                "from" to current,
                                "to" to hover,
                            )
                            invalidate()
                        }
                    }
                    invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPressRunnable)
                val id = dragId
                if (id != null) {
                    dragId = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // 只有真的挪动了才落盘
                    if (dragMoved) {
                        layoutStore?.save(cardLayout)
                        onCardLayoutChanged?.invoke(cardLayout)
                    }
                    // 排障场景：「排序没保存」——松手时记录最终顺序与是否真的挪动过。
                    Logx.event(
                        LogTags.SCENE,
                        "底部卡片拖拽结束",
                        "card" to id,
                        "moved" to dragMoved,
                        "layout" to com.cardesk.launcher.core.CardLayout.encode(cardLayout),
                    )
                    dragMoved = false
                    invalidate()
                    performClick()
                    return true
                }
                if (longPressFired) {
                    longPressFired = false
                    return true
                }
                handleTap(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPressRunnable)
                dragId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    /**
     * 命中测试：点在卡片或任一可点区域上就返回 true。
     *
     * 第三十二轮去掉壁纸横拖后，空白处的按下不再需要这个判定 ——
     * 保留下来给后续命中区扩展用（点击分发走 [handleTap] 自己的命中表）。
     */
    private fun hitAt(x: Float, y: Float): Boolean {
        if (cardRects.values.any { it.contains(x, y) }) return true
        return hits.values.any { it.contains(x, y) }
    }

    private fun handleTap(x: Float, y: Float) {
        // 来源行在音乐卡内部，必须先于其它命中区判定
        hits["source"]?.let { r ->
            if (r.contains(x, y)) {
                // 排障场景：「点了音乐卡来源行没弹出来源菜单」。
                Logx.event(
                    LogTags.SCENE,
                    "音乐卡来源行点击",
                    "card" to "source",
                    "source" to mediaSource,
                    "x" to x,
                    "y" to y,
                )
                onOpenSourceMenu?.invoke()
                performClick()
                return
            }
        }
        // 车况卡（第166轮 C 版）：整卡点击 → 打开「车辆状态」面板。
        // 逐窗命中区（carWin0..3）随改版移除：车模不再兼任「车窗开关示意图」。
        hits[CAR_STATUS_CARD_HIT_KEY]?.let { r ->
            if (r.contains(x, y)) {
                Logx.event(
                    LogTags.SCENE,
                    "车况卡点击",
                    "card" to "carStatus",
                    "x" to x,
                    "y" to y,
                )
                onOpenVehicleStatus?.invoke()
                performClick()
                return
            }
        }
        // 播放键
        hits["play"]?.let { r ->
            if (r.contains(x, y)) {
                // 排障场景：「音乐卡播放键点了不动」——记下点击前的播放状态。
                Logx.event(
                    LogTags.SCENE,
                    "音乐卡播放暂停点击",
                    "card" to "music",
                    "playing" to mediaPlaying,
                    "title" to mediaTitle,
                    "x" to x,
                    "y" to y,
                )
                onPlayPause?.invoke()
                performClick()
                return
            }
        }
        // 封面：打开音乐页（sceneMusicArt → openDesignMusicApp）
        hits["musicCover"]?.let { r ->
            if (r.contains(x, y)) {
                Logx.event(
                    LogTags.SCENE,
                    "音乐卡封面点击",
                    "card" to "music",
                    "hasArtwork" to (mediaArtwork != null),
                    "x" to x,
                    "y" to y,
                )
                onOpenMusic?.invoke()
                performClick()
                return
            }
        }
        // 导航引导卡
        hits["nav"]?.let { r ->
            if (r.contains(x, y)) {
                Logx.event(
                    LogTags.SCENE,
                    "导航引导卡点击",
                    "card" to "nav",
                    "road" to (navGuide?.road ?: ""),
                    "x" to x,
                    "y" to y,
                )
                onOpenNav?.invoke()
                performClick()
                return
            }
        }
        // 空调快捷
        hits["seatHeat"]?.let { r ->
            if (r.contains(x, y)) {
                Logx.event(
                    LogTags.SCENE,
                    "空调快捷卡点击",
                    "card" to "climate",
                    "action" to "主驾加热",
                    "level" to (state?.seatHeatDriver ?: 0),
                    "x" to x,
                    "y" to y,
                )
                onSeatHeat?.invoke()
                performClick()
                return
            }
        }
        hits["seatVent"]?.let { r ->
            if (r.contains(x, y)) {
                Logx.event(
                    LogTags.SCENE,
                    "空调快捷卡点击",
                    "card" to "climate",
                    "action" to "主驾通风",
                    "level" to (state?.seatVentDriver ?: 0),
                    "x" to x,
                    "y" to y,
                )
                onSeatVent?.invoke()
                performClick()
                return
            }
        }
        hits["maxFan"]?.let { r ->
            if (r.contains(x, y)) {
                Logx.event(
                    LogTags.SCENE,
                    "空调快捷卡点击",
                    "card" to "climate",
                    "action" to "MAX 风挡",
                    "x" to x,
                    "y" to y,
                )
                onMaxFan?.invoke()
                performClick()
                return
            }
        }
        // 快捷入口
        for (i in 0 until QUICK_SLOTS) {
            val r = hits["quick$i"] ?: continue
            if (!r.contains(x, y)) continue
            val app = quickApps.getOrNull(i)
            if (app != null) {
                // 排障场景：「快捷入口图标点了没反应」——带上包名与槽位号。
                Logx.event(
                    LogTags.SCENE,
                    "快捷入口点击",
                    "card" to "quick",
                    "slot" to i,
                    "pkg" to app.pkg,
                    "label" to app.label,
                    "x" to x,
                    "y" to y,
                )
                if (onOpenApp != null) onOpenApp?.invoke(app.pkg, app.label) else launchApp(app.pkg)
            } else {
                Logx.event(
                    LogTags.SCENE,
                    "快捷入口点击（兜底入口）",
                    "card" to "quick",
                    "slot" to i,
                    "x" to x,
                    "y" to y,
                )
                when (i) {
                    0 -> onOpenVehicle?.invoke()
                    1 -> onOpenNav?.invoke()
                    2 -> onOpenPhone?.invoke()
                    else -> onOpenDrawer?.invoke()
                }
            }
            performClick()
            return
        }
        // 壁纸圆点
        for (i in 0 until WALLPAPER_NAMES.size) {
            val r = hits["wp$i"] ?: continue
            if (!r.contains(x, y)) continue
            // 排障场景：「点圆点壁纸没切」——记录点的是第几张与当前第几张。
            Logx.event(
                LogTags.SCENE,
                "壁纸圆点点击",
                "card" to "wallpaper",
                "target" to i,
                "current" to wallpaperIndex,
                "name" to WALLPAPER_NAMES.getOrElse(i) { "" },
                "x" to x,
                "y" to y,
            )
            onWallpaperDot?.invoke(i)
            showWallpaperHint()
            performClick()
            return
        }
        // 命中到「禁用点击」的卡片（胎压卡 / 续航卡）
        // `SceneDesktop.disableShortClick(sceneTireCard)`（:702）与
        // `SceneDesktop.disableShortClick(sceneCarCard)`（:703），这两张卡本来就没有点击响应，
        // 真车上「点了没反应」极易被当成 bug，这里明确说明是禁点。
        val disabledCard = cardRects.entries.firstOrNull { it.value.contains(x, y) }?.key
        if (disabledCard != null) {
            Logx.w(
                LogTags.SCENE,
                "卡片点击被忽略：该卡片未启用点击",
                "card" to disabledCard,
                "x" to x,
                "y" to y,
            )
        }
    }

    private fun launchApp(pkg: String) {
        if (pkg.isEmpty()) return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                context.startActivity(intent)
                Logx.i(LogTags.SCENE, "快捷入口启动应用", "pkg" to pkg)
            } else {
                // 排障场景：「快捷入口点了没反应」——包名查不到启动 Intent 就是根因。
                Logx.w(LogTags.SCENE, "快捷入口启动失败：没有启动 Intent", "pkg" to pkg)
            }
        } catch (t: Throwable) {
            // 启动失败不打断桌面
            Logx.w(LogTags.SCENE, "快捷入口启动异常", t, "pkg" to pkg)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Design.W.toInt(), Design.H.toInt())
    }

    companion object {
        /**
         * 9 张命名壁纸。
         * 与 [BackgroundView.NAMES] 共用同一份数据，避免两处名字对不上。
         */
        val WALLPAPER_NAMES: List<String> = BackgroundView.NAMES.toList()

        /** 快捷入口位数（4 个应用位）。 */
        const val QUICK_SLOTS = 4

        // -------------------------------------------------- 中央大字歌词（增强）

        /**
         * 当前句字号（设计 px）。
         *
         * 第 113 轮：52 → **56**。
         * 用户说「除了当前行之外的四行要放大一点」——
         * 当前行只跟着抬一点点，真正的大头在四行衬句上。
         */
        const val LYRIC_MAIN_SIZE = 56f

        /**
         * 紧挨当前句的衬句字号（设计 px）—— 上一句 / 下一句。
         *
         * 第 113 轮：原来只有「上一句 / 下一句」两行衬句，字号 30；
         * 现在衬句有四行，**近的两行给 40**（比原来大 1/3）。
         *
         * 第 117 轮：首页中央只剩「上一句 / 下一句」两行衬句
         * （三行布局）；KTV 两行布局**两行都用这一档**
         * （用户要求「前后句保持大小一样」）。
         */
        const val LYRIC_NEAR_SIZE = 40f

        /**
         * 「更远」那一档衬句的字号（设计 px）。
         *
         * 第 117 轮：首页中央没有五行布局了，**这一档暂时没有布局在用**；
         * 留着是因为 [com.cardesk.launcher.core.media.LyricLayout] 的档位定义里
         * 仍然有「远档」，以后加更长的布局（比如回到五行）直接能用。
         */
        const val LYRIC_FAR_SIZE = 34f

        /**
         * 歌词**块中心** y（设计坐标）。
         *
         * 第 114 轮：锚点从「当前句基线」改成**块中心** ——
         * 字号改成可调之后，整块高度会跟着变，锚在某一行的基线上，
         * 放大时整块往上顶、缩小时往下坠，位置跟着字号漂。
         * 锚在块中心上，字号怎么调，整块都在同一个位置居中。
         *
         * **为什么是 500**：桌面中间那段空区是「天气下沿 223」到
         * 「底部卡片区上沿 764」，中心 493；取 500 是为了跟第 106 轮
         * 那版三行歌词（墨迹 410~590，中心正好 500）对齐 ——
         * 用户看惯了那个位置，后面几版不该让他觉得「往下沉了」。
         */
        const val LYRIC_BLOCK_CENTER_Y = 500f

        /**
         * 相邻两行之间的行距（设计 px）。
         *
         * 当前句是焦点，跟衬句之间要留得住呼吸。
         *
         * 第 117 轮：原来还有一档「衬句与衬句之间」的更紧行距（12px），
         * 那是给五行布局用的；现在两种布局最多只有「当前句 + 一行衬句」，
         * 全部用这一个行距，所以那一档删掉了。
         */
        const val LYRIC_GAP_MAIN = 18f

        /**
         * 歌词右边的**安全线**（设计 px）—— 底排**胎压卡片的右缘**。
         *
         * 第119轮（用户：「下面一句的区域尾部对齐胎压卡片的右缘」）：
         * 原来是 1488（行程卡 / 车况卡那一列的左沿），这次往前收到
         * **1453** —— 默认卡片布局下，底排第 4 张胎压卡的右边框所在位置。
         *
         * 为什么收在这里：底排最右那张是行程卡（左缘 1488），
         * 歌词区域伸到 1488 会跟那一列的卡片**齐平**、看着像要撞上去；
         * 收到胎压卡的右缘，歌词区就跟内容列的边界对齐了。
         *
         * 注意：这个数是按**默认卡片布局**量的 ——
         * 用户在「桌面卡片」里换了卡片顺序 / 宽度，胎压卡的位置会跟着变。
         */
        const val LYRIC_SAFE_RIGHT = 1453f

        /**
         * 歌词最大宽度（超出加省略号）。
         *
         * 第三十六轮第三轮改造从 1200 收到 **960**：
         * 车况卡改成和行程卡同宽后，左边界落在 `1456`，
         * 歌词居中（中心 960）再宽就会伸到车况卡底下被盖住。
         * 960 / 56px ≈ 17 个汉字，超过 17 字才截断，够用。
         */
        const val LYRIC_MAX_W = 960f

        /**
         * 左上引导卡的**下沿**（设计 px）—— 歌词整块不能压到它。
         *
         * 引导卡最高的一种形态（带车道线）：`73 + 314 = 387`。
         */
        const val LYRIC_GUIDE_CARD_BOTTOM = 387f

        /**
         * 有引导卡时，歌词**够高**的行的最大宽度（设计 px）。
         *
         * 引导卡右缘 664；歌词居中在 960，
         * 宽度 576 → 左缘 = `960 - 288 = 672`，离卡片右缘留 8px。
         *
         * **为什么只收窄「够高」的行**：正常字号下只有最上面一行够高，
         * 放大字号后第二行也可能够高 —— 其余行离卡片很远，不该被牵连截断。
         */
        const val LYRIC_GUIDE_CLEAR_W = 576f

        /**
         * 底部卡片区的**上沿**（设计 px）—— 歌词整块不能压到它。
         *
         * 卡片区：`1080 - 166（bottomMargin） - 150（cardH） = 764`。
         */
        const val LYRIC_BOTTOM_CARD_TOP = 764f

        // -------------------------------------------------- 音乐卡进度环 / 封面光晕

        /**
         * 进度环容器尺寸（`FrameLayout 80×80`）。
         */
        const val MUSIC_RING_SIZE = 64f

        /**
         * 进度环描边宽度（`m1183df(2.0f)`
         * 在 1920 画布上 1dp = 1px，所以是 2px。
         */
        const val MUSIC_RING_STROKE = 2f

        /**
         * 进度环轨道色。
         *
         * 用的是 （半透明白）
         * 第四十九轮「浮屿」改成**不透明**的 `--surfaceStrong`（见 `Skin.surfaceStrong`）。
         */
        val MUSIC_RING_TRACK: Int get() = Skin.surfaceStrong

        /**
         * 封面光晕的横向起点比例（`width * 0.1f`）。
         * 具体几何见 `MusicCardVisuals.glowCenterX`。
         */
        const val MUSIC_GLOW_CX_RATIO = 0.16f

        /**
         * 封面光晕渐变终点（`0.55f`）。
         */
        const val MUSIC_GLOW_END = 0.55f

        /**
         * 封面光晕起始透明度（`Color.argb(128, …)`）。
         */
        const val MUSIC_GLOW_ALPHA = 128

        /**
         * 引导卡里最多画几条车道线
         * `Math.min(8, driveWayTiles.length)`。
         */
        const val MAX_GUIDE_LANES = 8

        // -------------------------------------------------- 红绿灯 chip（第七十九轮重画）

        /**
         * 灯圆的直径。
         *
         * 第七十六轮是 14px 的小圆点（跟「红灯」两个字并排），
         * 第七十九轮按用户要求改成**一个大一点的圆**单独表示灯态。
         */
        const val TRAFFIC_LAMP_D = 46f

        /** chip 左右内边距。 */
        const val TRAFFIC_PAD_X = 14f

        /** chip 上内边距（圆顶到 chip 顶）。 */
        const val TRAFFIC_PAD_TOP = 10f

        /** chip 下内边距。 */
        const val TRAFFIC_PAD_BOTTOM = 8f

        /** 圆底到读秒文字顶的间距。 */
        const val TRAFFIC_GAP = 5f

        /** 读秒字号（粗体，比数字大一号但比以前的 34 收小）。 */
        const val TRAFFIC_SECONDS_SIZE = 30f

        /**
         * 读秒的**基线上提量**。
         *
         * 用字号推基线在不同 ROM 上会有几像素出入，
         * 这里按「30px 粗体的上伸部 ≈ 24px」取整，
         * 视觉上文字正好贴着圆的下方（见 `drawTrafficLight`）。
         */
        const val TRAFFIC_SECONDS_TEXT_ASCENT = 24f

        /** 「秒」单位字号。 */
        const val TRAFFIC_UNIT_SIZE = 15f

        /**
         * 场景页卡片圆角。
         *
         * 第四十九轮「浮屿」：卡片圆角统一 28（见 `Shape.CARD`），
         * 不再是默认 34 ——
         * 新视觉是**纯扁平 + 大圆角**，34 在 1920 宽下显得偏钝。
         */
        val GLASS_RADIUS: Float get() = com.cardesk.launcher.core.Shape.CARD

        // -------------------------------------------------- 车况卡（第三十六轮第三轮改造）

        /**
         * 车况卡宽 —— **与行程卡同宽**（第四十九轮「浮屿」统一 388），
         * 三张卡（行程 / 车况 / 续航）在右缘形成一条对齐的竖列。
         */
        const val CAR_STATUS_CARD_W = 388f

        /** 车况卡高（第167轮快照版：388×450）。 */
        const val CAR_STATUS_CARD_H = 380f

        /**
         * 车况卡上沿（第167轮快照版）。
         *
         * 设计规范给的 460 是按「底部卡行在 960」估的；实测底部卡行上沿
         * `LYRIC_BOTTOM_CARD_TOP = 764`，460+450 = 910 会**压下 146px**。
         * 按「不许压底部卡行」的硬约束，上沿取 `764 - 450 - 8 = 306`，
         * 卡片实际落位 **306..756**（右列 306 以上是空的，不压天气/行程卡）。
         */
        /** 卡底 756，底部卡行上沿 764（[LYRIC_BOTTOM_CARD_TOP]），留 8px。 */
        const val CAR_STATUS_CARD_TOP = 376f

        /** 车况卡右缘相对 `rightEnd` 的内缩（0 = 与行程卡同一条右边界）。 */
        const val CAR_STATUS_CARD_RIGHT_INSET = 0f

        /** 车况卡内边距。 */
        const val CAR_STATUS_PAD = 12f

        /** 车况卡里车模的显示宽（第四十九轮「浮屿」从 100 收到 88，给左右两列文字留位置）。 */
        const val CAR_STATUS_MODEL_W = 88f

        /** 车况卡里车模的显示高（设计空间 240 高 × 0.3667）。 */
        const val CAR_STATUS_MODEL_H = 176f

        /** 文字块与车模之间的间隙。 */
        const val CAR_STATUS_GAP = 26f

        /** 图例行数（每侧 5 行）。 */
        const val CAR_STATUS_ROWS = 5

        /** 图例行距。 */
        const val CAR_STATUS_ROW_H = 38f

        /**
         * 车况条目里**车窗**的下标区间（`CarStatus.items` 的顺序）。
         *
         * 顺序：左前门 / 右前门 / 左后门 / 右后门 / 后备箱 ‖ 左前窗 / 右前窗 / 左后窗 / 右后窗 / 引擎盖。
         */
        val CAR_STATUS_WINDOW_ITEMS = 5..8

        /** `CarStatus.items` 里**后备箱**的下标（0..3 门、4 后备箱、5..8 窗、9 引擎盖）。 */
        const val CAR_STATUS_TRUNK_ITEM = 4

        /** 车窗面板的点击命中键（与 `CarStatus.WINDOW_LABELS` 同序）。 */
        /** 车况卡整卡命中键（第166轮 C 版：点整张卡开「车辆状态」面板）。 */
        const val CAR_STATUS_CARD_HIT_KEY = "carStatusCard"

        /** 第178轮：01 的俯视横置图区 356×246，水平居中、比原 3/4 区略偏上。 */
        const val CAR_STATUS_CAR_X = 16f
        /** 第181轮选型 A：俯视车模整体下移量（设计 px，卡内坐标）。 */
        const val CAR_TOP_SHIFT_Y = 6f

        /** 第181轮选型 A：俯视车模相对原图区的缩放（92%，保持宽高比）。 */
        const val CAR_TOP_SCALE = 0.97f

        /** 第182轮：竖放车模图区（设计 px）——宽 246、高 300，水平居中。 */
        const val CAR_TOP_BOX_W = 250f
        const val CAR_TOP_BOX_H = 312f
        const val CAR_TOP_BOX_TOP = 30f

        /** 旧 3/4 视角（03/05 legacy）仍用 356×246 的横区起点。 */
        const val CAR_STATUS_CAR_TOP = 34f
        const val CAR_STATUS_CAR_W = 356f
        const val CAR_STATUS_CAR_H = 246f

        /**
         * 第181轮选型 A：俯视模式下胎压胶囊**内缘离车体边界**的目标距离（设计 px）。
         *
         * 起点仍是「图区四角 + 本值」，但若离车体不足 6px 会自动再往外让到 6px
         * （见 `drawTirePills`），所以换车型/门态也不会压到车。
         */
        const val CAR_TIRE_INSET = 14f

        /** 第184轮：后轮两颗胶囊贴卡边的外边距（设计 px）。 */
        const val CAR_TIRE_EDGE_MARGIN = 14f

        /** 第185轮：胎压胶囊与「关车态车体框」固定留缝（设计 px）。 */
        const val CAR_TIRE_GAP = 24f

        /** 第184轮：后轮两颗胶囊中心 y（卡内设计 px，底边离状态文字顶边 6px）。 */
        const val CAR_TIRE_REAR_Y = 310f

        // -------------------------------------------------- 胎压胶囊（第180轮：字号 ×2；第189轮：再放大）
        //
        // 原来胶囊是 30×23，数值 11px、单位「bar」7px —— 远看就是一排小点，
        // 真机反馈要求「放大到 2 倍」。几何跟着字号一起放大（宽高都 ×2 附近），
        // 否则 22px 的数字会顶出胶囊。
        //
        // 胶囊**左/上边缘**离图区边的距离只由 [CAR_TIRE_INSET] 决定
        // （中心 = inset + 胶囊半径），所以放大后胶囊右边会多吃进车模一点，
        // 这是「字号 ×2」必然的代价 —— 位置参数保持不变，视觉上仍是贴角。
        //
        // 第189轮：卡片自带文字（标题/详情/状态行）整体翻倍之后，
        // 胎压数字还是 22px 就显得**不成比例**（用户：「折中：胎压数字 22→32」）。
        // 胶囊几何跟着放大到 72×62：数值 32px、单位 20px。
        // 数字宽度要放得下：「2.4」三个字符约 54px，胶囊 72px 够。

        /** 胎压胶囊宽（第189轮：52 → 72）。 */
        const val TIRE_PILL_W = 72f

        /** 胎压胶囊高（第189轮：46 → 62）。 */
        const val TIRE_PILL_H = 62f

        /** 胎压胶囊圆角（第189轮：17 → 22，约为胶囊高的一半）。 */
        const val TIRE_PILL_RADIUS = 22f

        /** 胎压数值字号（第180轮 11 → 22；第189轮 22 → 32）。 */
        const val TIRE_PILL_VALUE_SP = 32f

        /** 胎压单位「bar」字号（第180轮 7 → 14；第189轮 14 → 20）。 */
        const val TIRE_PILL_UNIT_SP = 20f

        /** 数值基线（相对胶囊上沿；第189轮：24 → 31）。 */
        const val TIRE_PILL_VALUE_BASELINE = 31f

        /** 单位基线（相对胶囊上沿；第189轮：39 → 54）。 */
        const val TIRE_PILL_UNIT_BASELINE = 54f

        /** 老视角（hero/card）胎压胶囊位置，卡片相对坐标（fl/fr/rl/rr），沿用第177轮手调值。 */
        val CAR_TIRE_LEGACY_X = floatArrayOf(0.435f, 0.156f, 0.845f, 0.541f)
        val CAR_TIRE_LEGACY_Y = floatArrayOf(0.735f, 0.371f, 0.590f, 0.193f)

        /** 03/05 暂时保持旧图区（40..332 = 356×292）。 */
        const val CAR_STATUS_CAR_TOP_LEGACY = 40f
        const val CAR_STATUS_CAR_H_LEGACY = 292f

        /**
         * 车模左右为胎压胶囊预留的沟槽宽度（第176轮）：胎压不再压车身、也不画引线，
         * 胶囊放进这两条沟槽里（左侧前/后、右侧前/后），靠纵向位置与轮子对应。
         */
        const val CAR_STATUS_TIRE_GUTTER = 32f

        /** 状态文字基线（相对卡顶，第一行）。 */
        const val CAR_STATUS_STATUS_Y = 352f

        // -------------------------------------------------- 车况卡字号（第189轮：×2）
        //
        // 用户反馈：「车况卡所有字体再放大一倍」。
        //
        // 这里只放大**卡片自带的三处文字**（标题 / 详情入口 / 状态行）：
        // 胎压胶囊里的数值（22px）和单位（14px）**不动** —— 它们在第180轮
        // 已经从 11/7 整翻过一倍，再翻一倍的话胶囊得撑到 104×92，
        // 388 宽的卡里四颗大胶囊会把车模挤没，得不偿失。
        //
        // 放大后的层级：状态行 26 = 标题 26 > 详情入口 24 > 胎压数值 22 > 单位 14。

        /** 车况卡标题（「车况」）字号（第189轮：13 → 26）。 */
        const val CAR_STATUS_TITLE_SP = 26f

        /** 车况卡标题基线相对「卡顶 + [CAR_STATUS_TITLE_TOP]」的偏移（= 字号本身）。 */
        const val CAR_STATUS_TITLE_TOP = 12f

        /** 车况卡「详情 ›」字号（第189轮：12 → 24，比标题小一档）。 */
        const val CAR_STATUS_LINK_SP = 24f

        /** 车况卡状态行字号（第189轮：13 → 26）。 */
        const val CAR_STATUS_STATUS_SP = 26f

        // -------------------------------------------------- 底部卡片字号（第189轮）
        //
        // 用户反馈：「行程卡的字号也整体调大一点，现在太小了」。
        //
        // 底排卡片高只有 150px，而一行要放「标题 + 标签 + 数值 + 标签 + 数值」五行字，
        // 所以放大不是无脑乘 2 —— 按**可见字形高度**重新排了一遍：
        //
        //   标题 24 + 标签 21 + 数值 27 + 标签 21 + 数值 27 = 120
        //   上下留白 10 + 4，四道行间距各 4  →  合计 150，一像素不浪费。
        //
        // 「车门车窗」卡和行程卡在底排并排，字号必须一致，否则一眼就看出来不对。

        /** 底部卡片标题字号（第189轮：22 → 24）。 */
        const val TRIP_TITLE_SP = 24f

        /** 底部卡片标题基线（相对卡顶）。 */
        const val TRIP_TITLE_BASE = 31f

        /** 单元格标签字号（第189轮：19 → 21）。 */
        const val TRIP_LABEL_SP = 21f

        /** 单元格标签基线（相对单元格顶）。 */
        const val TRIP_LABEL_BASE = 20f

        /** 单元格数值字号（第189轮：24 → 27；放不下会整卡一起降档）。 */
        const val TRIP_VALUE_SP = 27f

        /** 单元格数值字号下限。 */
        const val TRIP_VALUE_SP_MIN = 20f

        /** 卡片被压窄时的数值字号（原来是 24）。 */
        const val TRIP_VALUE_SP_NARROW = 24f

        /** 卡片被压窄时的标签字号（原来是 19）。 */
        const val TRIP_LABEL_SP_NARROW = 19f

        /** 数值基线（相对单元格顶）。 */
        const val TRIP_VALUE_BASE = 51f

        /** 第一行单元格顶（相对卡顶）。 */
        const val TRIP_CELL_TOP_1 = 36f

        /** 第二行单元格顶（相对卡顶）。 */
        const val TRIP_CELL_TOP_2 = 92f

        /**
         * 车窗打开 = 黄色（用户指定）。
         *
         * 第180轮：颜色搬到 `Palette.windowOpen`（日夜两套），
         * 这里只留个说明，不再有第二个颜色来源。
         */

        /**
         * 图例**左列**取 `CarStatus.items` 的哪几项。
         *
         * 文字分到车模两侧后，左列全是车身上**左边**的部件、右列全是**右边**的，
         * 同一行左右两侧一一对应（行 1 = 两个前门，行 2 = 两个前窗……）。
         */


        /** 面板「开」：橙色半透明填充（= `0xFFFF9F43`）。 */

        /** 面板「开」：橙色描边。 */

        /** 面板「关」：极淡填充（只让面板的位置看得出来）。 */

        /** 面板「关」：淡描边（只勾个轮廓，不抢注意力）。 */

        /** 面板「读不到」：中性描边（灰色 = 没有结论，不是「关」）。 */

        /**
         * 车况条目里**车门**的下标区间（`CarStatus.items` 的顺序）。
         *
         * 顺序：左前门 / 右前门 / 左后门 / 右后门 / 后备箱 ‖ 左前窗 / 右前窗 / 左后窗 / 右后窗 / 引擎盖。
         */
        val CAR_STATUS_DOOR_ITEMS = 0..3

        /** 车门条目数（[CAR_STATUS_DOOR_ITEMS] 的长度）。 */
        const val CAR_STATUS_DOOR_COUNT = 4

        /**
         * 门开着时，原位那个「开口」的填充色（暗色 —— 读起来像车身上缺了一块）。
         */

        /** 门开着时，原位那个「开口」的描边色（很淡，只是让缺口轮廓清楚）。 */

        /** 图例里标签与状态文字的间距（所有标签都是 3 个汉字，间距可以定死）。 */
        const val CAR_STATUS_STATE_OFFSET = 50f

        /** 车况图例字号（设计 px）—— 设计板 是 21px。 */
        const val CAR_STATUS_LEGEND_SIZE = 21f

        /** 车况图例「标签 ↔ 状态」间距（设计 px）—— 设计板是 22px。 */
        const val CAR_STATUS_LEGEND_GAP = 22f

        /** 车况图例两列离卡沿的距离（设计 px）—— 设计板是 24px。 */
        const val CAR_STATUS_LEGEND_INSET = 24f

        /**
         * 一个图例文字块的宽（标签 3 个汉字 42px + 间距 8px + 状态 1 个字 15px）。
         */
        const val CAR_STATUS_BLOCK_W = 65f

        /**
         * 车窗下发后的乐观保护期（毫秒）。
         *
         * 车窗是机械动作，从下发到车端回读有几百毫秒延迟；
         * 这段时间用本地值显示，避免图标「闪一下又跳回去」。
         */
        const val WINDOW_PENDING_MS = 3_000L

        // -------------------------------------------------- 俯视车模配色

        /**
         * 车模配色（第四轮重做）。
         *
         * 用的是「银色车身 + 深色玻璃」
         * 车身横向渐变 `#9AB8D8 → #E6ECF4 → #9AB8D8`，玻璃 `#1A2536`，描边白 9%。
         * 但银色是**浅色主题**下的值，直接搬到我们的深色桌面上会刺眼
         * 所以这里按同样的「中间亮两侧暗 + 深玻璃」思路换成深色系：
         *
         * | token | 值 | 用途 |
         * | --- | --- | --- |
         * | `CarColors.night.bodyEdge` | `#33405F` | 车身边缘（渐变两端） |
         * | `CarColors.night.bodyMid` | `#5A6EA6` | 车身中线（渐变中间，像车顶高光） |
         * | `CarColors.night.bodyEdgeLine` | `#93A9D4` 55% | 车身描边 |
         * | `CarColors.night.glassTop` | `#233152` | 玻璃上沿 |
         * | `CarColors.night.glassBottom` | `#121A2A` | 玻璃下沿 |
         * | `CarColors.night.roof` | `#263352` 90% | 车顶天幕 |
         * | `CarColors.night.tire` | `#0F141F` | 轮胎 |
         * | `CarColors.night.headLight` | `#D9E7FF` 90% | 前灯 |
         * | `CarColors.night.tailLight` | `#FF7A7A` 90% | 尾灯 |
         * | `CarColors.day.*` | 车漆 `#8C97B0` / 玻璃 `#C9D2E4` / 轮胎 `#2A2F3A` | **日间一套**（设计板 `s01-home-day`） |
         */
        // 车模颜色不再写死在这里 —— 夜/日两套见 `core/CarColors.kt`
        // （第五十一轮：日间白底上摆一辆夜里的深蓝车，是明显的日间 bug）。

        /**
         * 胎压单位（第三十七轮）。
         *
         * 真机 TPMS 报的是 **kPa**（诊断包：`240.00,227.50,237.50,235.00`），
         * 在 unit=0 时也返回 `kPa`。
         */
        const val TIRE_UNIT = "kPa"

        /**
         * 胎压数值字号：默认 28、范围 10..48（第155轮）。
         *
         * 默认值跟「实现之前渲染里写死的那个值」一致 ——
         * 接上开关不能让没动过设置的用户看到画面变化。
         * 范围跟 `Prefs.deskTpmsValueSp` 一致（那边是权威）。
         */
        const val DEFAULT_TPMS_VALUE_SP = 28
        const val TPMS_VALUE_SP_MIN = 10
        const val TPMS_VALUE_SP_MAX = 48

        /** 快捷入口卡左边距（设计板：图标底板从卡内 24 开始）。 */
        const val QUICK_PAD = 24f

        /**
         * 续航卡（底部卡片行里那张，id = `car`）在固定档位下的自然宽度
         * （自适应时由 `CardLayout.widths` 吃掉行尾剩余宽度）。
         *
         * 第111轮：原来这个常量叫 `CAR_PRESET_W`、注释还写着「车况卡」——
         * 但它管的是画「续航」的那张卡（`drawRangeCard`）。
         * 名字跟着显示名一起改掉，免得下次又被当成车况卡。
         */
        const val RANGE_CARD_W = 311f

        /** 卡片库新增：行程卡 / 车门车窗卡的自然宽度。 */
        const val TRIP_BOTTOM_CARD_W = 388f
        const val STATUS_BOTTOM_CARD_W = 380f

        /** 快捷入口卡宽（第五十轮对齐设计板 s01：与通用音乐 / 快捷车控同宽 338）。 */
        const val QUICK_CARD_W = 338f

        /** 媒体卡宽。第四十九轮「浮屿」从 400 收到 338，与快捷卡 / 车控卡同宽。 */
        const val MUSIC_CARD_W = 338f

        /**
         * 媒体卡里标题 / 歌手那一列的宽度。
         *
         * 横向 LinearLayout
         * 卡内容宽 = 400 − padding 24×2 = 352；
         * 减去封面 96、标题列左边距 20、进度环容器 80 → 标题列 = **156**。
         * 标题/歌手是 `match_parent` + `ellipsize=end` + `maxLines=1`
         * 所以必须按这宽度截断，否则长歌名会压到进度环上。
         */
        val MUSIC_TEXT_COL_W = MusicCardVisuals.textColumnWidth()

        /** 空调快捷卡左右内边距。 */
        const val CLIMATE_CARD_PAD = 15f

        /** 空调快捷卡里每个座位位的宽。 */
        const val CLIMATE_SEAT_W = 100f

        /** 座位位之间的间隙。 */
        const val CLIMATE_SEAT_GAP = 4f

        /** 空调快捷卡宽 = padding 15*2 + 100*3 + 4*2 = 338（第四十九轮「浮屿」统一宽度）。 */
        const val CLIMATE_CARD_W = 15f + 100f * 3 + 4f * 2 + 15f

        /** 胎压卡宽。 */
        const val TIRE_CARD_W = 350f

        /** 卡片间距（等的 marginStart 20px）。 */
        const val CARD_GAP = 20f

        /** 壁纸提示条自动隐藏时长（1600ms）。 */
        const val HINT_AUTO_HIDE_MS = 1600L

        /** 长按拖拽排序的触发时长（系统长按默认 500ms）。 */
        const val LONG_PRESS_MS = 500L

        /** 快捷入口内容组的顶部偏移（第五十轮对齐设计板：标题 27 之后，图标底板从 42 开始）。 */
        const val QUICK_CONTENT_TOP = 42f

        /** 快捷入口图标底板边长（设计板 `.appicon` 68×68 圆角 18）。 */
        const val QUICK_SLOT_W = 68f

        /** 快捷入口图标底板之间的间隙（底板 68 + 间隙 8 = 步进 76）。 */
        const val QUICK_SLOT_GAP = 8f

        /** 快捷入口图标**字形**尺寸（画在 68×68 底板正中）。 */
        const val QUICK_GLYPH = 40f

        /** 媒体卡文字列顶部偏移（`gravity="center_vertical"` 居中）。 */
        const val MUSIC_CONTENT_TOP = 22f

        /** 空调位内容顶部偏移（第五十轮：标题行之下，槽位高 104，内容 8~96）。 */
        const val SEAT_CONTENT_TOP = 8f
    }
}
