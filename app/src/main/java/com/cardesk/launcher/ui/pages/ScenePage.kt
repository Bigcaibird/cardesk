package com.cardesk.launcher.ui.pages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.VehicleStats
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.CoverAccent
import com.cardesk.launcher.core.media.MediaSources
import com.cardesk.launcher.core.media.MusicCardVisuals
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

    /** 底部卡片顺序变化（`scene_bottom_card_order_v1`）。 */
    var onBottomCardOrderChanged: ((List<String>) -> Unit)? = null

    // 媒体（MusicController 推送）
    var mediaTitle: String = "暂无播放"

    /** 艺术家：默认是**空串**。 */
    var mediaArtist: String = ""
    var mediaSource: String = "通用音乐"
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
    private var tripCardEmptyLogged = false

    /** 车况卡「全读不到」的状态是否已经记过日志（避免每秒刷屏）。 */
    private var carStatusEmptyLogged = false

    /** 点击车窗图标的回调（用于下发关窗指令 0/50）。 */
    var onToggleWindow: ((Boolean) -> Unit)? = null

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

    // ---------------------------------------------------------------- 底部卡片顺序

    /** 底部卡片顺序（`scene_bottom_card_order_v1`）。 */
    var bottomCardOrder: List<String> = BottomCardOrder.DEFAULT
        set(value) {
            val next = BottomCardOrder.normalize(value)
            val changed = next != field
            field = next
            // 排障场景：「拖拽排序没生效」——顺序变化点用 DEBUG（拖动过程中每帧都会变）。
            if (changed) {
                Logx.d(LogTags.SCENE, "底部卡片顺序变化", "order" to next.joinToString(","))
            }
            invalidate()
        }

    /** 顺序持久化通道（由宿主注入，例如 `BottomCardOrder.PrefsStore(prefs)`）。 */
    var orderStore: BottomCardOrder.Store? = null

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
        orderStore?.load()?.let { bottomCardOrder = it }
        // 排障场景：「开机后卡片顺序和上次不一样」——把实际读到的顺序落盘。
        Logx.i(LogTags.SCENE, "底部卡片顺序加载", "order" to bottomCardOrder.joinToString(","))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear()
        drawGuide(canvas)
        drawWeather(canvas)
        drawTripCard(canvas)
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
     * 视觉重心，版式：
     * `
     *            上一句（24px，TEXT_DIM）
     *            当前句（52px 粗体，TEXT） ← 视觉焦点
     *            下一句（24px，TEXT_DIM）
     * `
     *
     * 位置避开左上引导卡（x 44~564, y 73~）与右上天气（y 75~223），
     * 也不压到底部卡片区（y 774~924）。
     *
     * 三行都为空 → 整块不画（没有歌词时不占位、不显示占位文案）。
     */
    private fun drawCenterLyric(canvas: Canvas) {
        if (!showCenterLyric) return
        if (lyricPrev.isEmpty() && lyricCurrent.isEmpty() && lyricNext.isEmpty()) return
        val cx = Design.W / 2f
        text.textAlign = Paint.Align.CENTER

        // 上一句
        text.typeface = Typeface.DEFAULT
        text.textSize = LYRIC_SIDE_SIZE
        text.color = Skin.TEXT_DIM
        canvas.drawText(clipLabel(lyricPrev, LYRIC_MAX_W), cx, LYRIC_PREV_Y, text)

        // 当前句（视觉焦点）—— 带**擦除高亮**
        // 已唱到的部分用强调色，还没唱到的部分保持暗色，比例随播放位置往前推。
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = LYRIC_MAIN_SIZE
        val mainText = clipLabel(lyricCurrent, LYRIC_MAX_W)
        val moving = lyricWipeState.advance(System.currentTimeMillis())
        if (moving) postInvalidateOnAnimation()
        com.cardesk.launcher.ui.widget.LyricWipeText.draw(
            canvas = canvas,
            text = mainText,
            cx = cx,
            baseline = LYRIC_MAIN_Y,
            paint = text,
            activeColor = Skin.ACCENT,
            inactiveColor = Skin.TEXT,
            fraction = lyricWipeState.display,
            edgePx = com.cardesk.launcher.core.media.LyricWipe.edgeWidth(resources.displayMetrics.density),
            maxWidth = LYRIC_MAX_W,
        )

        // 下一句
        text.typeface = Typeface.DEFAULT
        text.textSize = LYRIC_SIDE_SIZE
        text.color = Skin.TEXT_DIM
        canvas.drawText(clipLabel(lyricNext, LYRIC_MAX_W), cx, LYRIC_NEXT_Y, text)

        text.textAlign = Paint.Align.LEFT
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
        val x = left
        // 第五十二轮：状态栏缩到 2/3，内容基线 110 → 73
        val y = 73f
        val w = 520f
        val pad = 26f
        val rowTop = y + pad
        // 行1 的高度不是 88（图标盒高），而是**文字列高**：
        //  第一行是 `wrap_content`，右列 = 70px 距离 + 6 间距 + 24px 道路 ≈ 110
        // 比 88 的图标盒高，图标盒在这行里**垂直居中**（`gravity="center_vertical"`）。
        val rowH = 110f
        val hasLanes = guide.lanes.isNotEmpty()
        // 车道线行（高 70，marginTop 22）
        val lanesTop = rowTop + rowH + 22f
        // 底部行（marginTop 20）
        val bottomTop = if (hasLanes) lanesTop + 70f else rowTop + rowH + 20f
        val bottomBaseline = bottomTop + 18f
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

        // —— 行1 右下：道路（24px，marginTop 6）——
        text.textSize = 24f
        text.color = Skin.TEXT
        canvas.drawText(guide.road, tx, rowTop + 70f + 6f + 24f, text)

        // —— 行2：车道线 chip（最多 8 条）——
        if (hasLanes) {
            drawGuideLanes(canvas, guide, x + pad, lanesTop, w - pad * 2f, 70f)
        }

        // —— 行3：剩余 X 公里 · Y 分 …… 预计到达 ——
        text.textSize = 18f
        text.color = Skin.TEXT_DIM
        val dimLabel = "剩余 "
        canvas.drawText(dimLabel, x + pad, bottomBaseline, text)
        var cx2 = x + pad + text.measureText(dimLabel)
        text.textSize = 21f
        text.color = Skin.TEXT
        canvas.drawText(guide.remainKm, cx2, bottomBaseline, text)
        cx2 += text.measureText(guide.remainKm)
        text.textSize = 18f
        text.color = Skin.TEXT_DIM
        val midLabel = " 公里 · "
        canvas.drawText(midLabel, cx2, bottomBaseline, text)
        cx2 += text.measureText(midLabel)
        text.textSize = 21f
        text.color = Skin.TEXT
        canvas.drawText(guide.remainTime, cx2, bottomBaseline, text)

        // 右侧 ETA：时间 21px + 「到达」18px，右对齐（分段着色）
        if (guide.etaTime.isNotEmpty()) {
            text.textSize = 18f
            text.color = Skin.TEXT_DIM
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(NavGuideText.ETA_SUFFIX, x + w - pad, bottomBaseline, text)
            text.textAlign = Paint.Align.LEFT
            val suffixW = text.measureText(NavGuideText.ETA_SUFFIX)
            text.textSize = 21f
            text.color = Skin.TEXT
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(guide.etaTime, x + w - pad - suffixW, bottomBaseline, text)
            text.textAlign = Paint.Align.LEFT
        }

        hits["nav"] = r
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
                arrowUp(14f, 5f, 20f)
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(14f), py(11f))
                        lineTo(px(14f), py(9f))
                        cubicTo(px(14f), py(6f), px(12f), py(5f), px(9f), py(5f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(11f), py(2f))
                        lineTo(px(6f), py(5f))
                        lineTo(px(11f), py(8f))
                    },
                    p,
                )
            }
            LaneShape.STRAIGHT_RIGHT -> {
                arrowUp(10f, 5f, 20f)
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(10f), py(11f))
                        lineTo(px(10f), py(9f))
                        cubicTo(px(10f), py(6f), px(12f), py(5f), px(15f), py(5f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(13f), py(2f))
                        lineTo(px(18f), py(5f))
                        lineTo(px(13f), py(8f))
                    },
                    p,
                )
            }
            LaneShape.STRAIGHT_BOTH -> {
                arrowUp(12f, 5f, 20f)
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(12f), py(10f))
                        cubicTo(px(12f), py(6f), px(9f), py(5f), px(6f), py(5f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(8f), py(2f))
                        lineTo(px(3f), py(5f))
                        lineTo(px(8f), py(8f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(12f), py(10f))
                        cubicTo(px(12f), py(6f), px(15f), py(5f), px(18f), py(5f))
                    },
                    p,
                )
                canvas.drawPath(
                    Path().apply {
                        moveTo(px(16f), py(2f))
                        lineTo(px(21f), py(5f))
                        lineTo(px(16f), py(8f))
                    },
                    p,
                )
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
    private fun drawTripCard(canvas: Canvas) {
        val i = info ?: return
        val odo = i.odometerKm.takeIf { it > 0 }
        val fuel = i.avgFuelConsumption
        val tripKm = i.tripKm
        val tripMs = i.tripDurationMs
        if (odo == null && fuel == null && tripKm == null && tripMs == null) {
            // 只在「从有值变成没值」时记一条，避免每秒刷屏。
            if (tripCardEmptyLogged) return
            tripCardEmptyLogged = true
            Logx.w(LogTags.SIGNAL, "行程卡无数据（总里程/油耗/行程都读不到）")
            return
        }
        tripCardEmptyLogged = false

        val r = RectF(
            rightEnd - TRIP_CARD_RIGHT_INSET - TRIP_CARD_W,
            TRIP_CARD_TOP,
            rightEnd - TRIP_CARD_RIGHT_INSET,
            TRIP_CARD_TOP + TRIP_CARD_H,
        )
        card(canvas, r)

        // —— 标题（设计板：22px 弱文字，距左 18 / 距上 14）——
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 22f
        text.color = Skin.TEXT_DIM
        canvas.drawText("行程数据", r.left + 18f, r.top + 32f, text)

        // —— 2×2 KV：标签 19px 次文字 + 数值 24px 粗体（设计板 `.kv` / `.kv.sm b`）——
        val pad = 18f
        val cw = (TRIP_CARD_W - pad * 2) / 2f
        val colX = floatArrayOf(r.left + pad, r.left + pad + cw)
        val rowTop = floatArrayOf(r.top + 44f, r.top + 96f)

        text.textAlign = Paint.Align.LEFT
        drawTripCell(canvas, colX[0], rowTop[0], "总里程", VehicleStats.formatOdometer(odo))
        drawTripCell(canvas, colX[1], rowTop[0], "平均油耗", VehicleStats.formatFuelConsumption(fuel))
        drawTripCell(canvas, colX[0], rowTop[1], "行程里程", VehicleStats.formatTripDistanceKm(tripKm))
        drawTripCell(canvas, colX[1], rowTop[1], "行程时长", VehicleStats.formatTripDuration(tripMs))
    }

    /**
     * 行程卡里的一个「标签 + 数值」单元。
     *
     * 设计板版式：标签 19px 次文字，数值 24px 粗体，两行之间由 `.kv b` 的
     *  隔开 —— 标签基线 = 行顶 + 15，数值基线 = 行顶 + 44。
     */
    private fun drawTripCell(canvas: Canvas, x: Float, top: Float, label: String, value: String) {
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 19f
        text.color = Skin.TEXT_SOFT
        canvas.drawText(label, x, top + 15f, text)

        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 24f
        text.color = Skin.TEXT
        canvas.drawText(value, x, top + 44f, text)
    }

    // ---------------------------------------------------------------- 底部卡片

    /** 底部 5 张卡的布局（顺序由 [bottomCardOrder] 决定）。 */
    private fun layoutBottomCards(): LinkedHashMap<String, RectF> {
        val out = LinkedHashMap<String, RectF>()
        val order = bottomCardOrder
        var fixed = 0f
        for (id in order) if (id != BottomCardOrder.CAR) fixed += cardWidth(id)
        val gaps = CARD_GAP * (order.size - 1).coerceAtLeast(0)
        val carW = (rightEnd - left - fixed - gaps).coerceAtLeast(160f)
        var x = left
        for (id in order) {
            val w = if (id == BottomCardOrder.CAR) carW else cardWidth(id)
            out[id] = RectF(x, cardTop, x + w, cardTop + cardH)
            x += w + CARD_GAP
        }
        return out
    }

    private fun cardWidth(id: String): Float = when (id) {
        BottomCardOrder.QUICK -> QUICK_CARD_W
        BottomCardOrder.MUSIC -> MUSIC_CARD_W
        BottomCardOrder.CLIMATE -> CLIMATE_CARD_W
        BottomCardOrder.TIRE -> TIRE_CARD_W
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
                BottomCardOrder.QUICK -> drawQuick(canvas, drawRect)
                BottomCardOrder.MUSIC -> drawMusicCard(canvas, drawRect)
                BottomCardOrder.CLIMATE -> drawClimateCard(canvas, drawRect)
                BottomCardOrder.TIRE -> drawTireCard(canvas, drawRect)
                BottomCardOrder.CAR -> drawCarCard(canvas, drawRect)
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
        // 封面光晕。
        // 里它是音乐卡的**背景层**（marginStart/End = -24 → 铺满整张卡）
        // 画在封面 / 文字 / 进度环**之下**。
        drawCoverGlow(canvas, rect)

        // ① 来源行（dot + 名称 + 箭头）—— 设计板放在卡片**左上角**
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 21f
        text.color = Skin.TEXT_DIM
        val srcY = top + 10f
        fill.color = MediaSources.brandColor(mediaSource)
        canvas.drawCircle(x + 22f, srcY + 12f, 4f, fill)
        text.color = Skin.TEXT_DIM
        canvas.drawText(mediaSource, x + 34f, srcY + 20f, text)
        val srcNameW = text.measureText(mediaSource)
        stroke.color = Skin.TEXT_DIM
        stroke.strokeWidth = 1.6f
        val chx = x + 34f + srcNameW + 6f
        val chy = srcY + 12f
        canvas.drawLine(chx, chy, chx + 5f, chy + 5f, stroke)
        canvas.drawLine(chx + 5f, chy + 5f, chx + 10f, chy, stroke)
        // 来源行可点（sceneSource 的独立点击区）
        hits["source"] = RectF(x + 14f, srcY, chx + 14f, srcY + 30f)

        // ② 封面 68×68 圆角 18（设计板 `.appicon`）
        val cover = RectF(x + 18f, top + 42f, x + 18f + 68f, top + 42f + 68f)
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
        val tx = cover.right + 14f
        // 标题 26px 粗体
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 26f
        text.color = Skin.TEXT
        canvas.drawText(clipLabel(mediaTitle, MUSIC_TEXT_COL_W), tx, top + 70f, text)

        // ④ 艺术家 18px（没有就留空，默认空串）
        text.typeface = Typeface.DEFAULT
        text.textSize = 18f
        text.color = Skin.TEXT_SOFT
        canvas.drawText(clipLabel(mediaArtist, MUSIC_TEXT_COL_W), tx, top + 98f, text)

        // 进度环 + 播放按钮
        // 两者同心，都在一个 64×64 的 FrameLayout 里，播放按钮 `layout_gravity="center"`。
        // 64×64 容器的右边缘 = 卡片右 − paddingEnd 18。
        val bx = x + w - 18f - MUSIC_RING_SIZE / 2f
        val by = top + h / 2f
        // 进度环在播放按钮**之下**（里 是 sceneMusicPlay 的前一个兄弟节点）。
        drawMusicRing(canvas, bx, by)
        canvas.drawCircle(bx, by, 28f, Paint(fill).apply { color = Skin.surfaceStrong })
        fill.color = Skin.TEXT
        if (mediaPlaying) {
            canvas.drawRoundRect(RectF(bx - 9f, by - 9f, bx - 3f, by + 9f), 2f, 2f, fill)
            canvas.drawRoundRect(RectF(bx + 3f, by - 9f, bx + 9f, by + 9f), 2f, 2f, fill)
        } else {
            val tri = Path().apply {
                moveTo(bx - 6f, by - 10f)
                lineTo(bx + 10f, by)
                lineTo(bx - 6f, by + 10f)
                close()
            }
            canvas.drawPath(tri, fill)
        }
        hits["play"] = RectF(bx - 28f, by - 28f, bx + 28f, by + 28f)
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
    private fun drawMusicRing(canvas: Canvas, cx: Float, cy: Float) {
        val r = MusicCardVisuals.ringRadius(MUSIC_RING_SIZE, MUSIC_RING_STROKE)
        if (r <= 0f) return
        stroke.strokeWidth = MUSIC_RING_STROKE
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
     * 版式：标题「快捷车控」21px 弱文字（左 18 / 上 10），
     * 下面三个位（主驾加热 / 主驾通风 / 最大风挡）各占 100 宽。
     */
    private fun drawClimateCard(canvas: Canvas, rect: RectF) {
        val x = rect.left
        val top = rect.top
        card(canvas, rect)
        cardTitle(canvas, rect, "快捷车控")
        val s = state
        val seatTop = top + 34f
        val seatH = 104f
        seat(
            canvas, x + CLIMATE_CARD_PAD, seatTop, CLIMATE_SEAT_W, seatH,
            "主驾加热", s?.seatHeatDriver ?: 0, Skin.WARN,
        )
        seat(
            canvas, x + CLIMATE_CARD_PAD + CLIMATE_SEAT_W + CLIMATE_SEAT_GAP, seatTop, CLIMATE_SEAT_W, seatH,
            "主驾通风", s?.seatVentDriver ?: 0, Skin.ACCENT_LIGHT,
        )
        // MAX 风量：`HvacController.isPower() && getFanSpeed() >= 7`
        val mx = x + CLIMATE_CARD_PAD + (CLIMATE_SEAT_W + CLIMATE_SEAT_GAP) * 2
        val mxR = RectF(mx, seatTop, mx + CLIMATE_SEAT_W, seatTop + seatH)
        val maxOn = s?.power == true && (s.fanLevel ?: 0) >= 7
        canvas.drawRoundRect(mxR, 18f, 18f, Paint(fill).apply { color = Skin.surfaceSoft })
        val mxColor = if (maxOn) Skin.ACCENT else Skin.TEXT_SOFT
        stroke.color = mxColor
        stroke.strokeWidth = 3f
        val fcx = mxR.centerX()
        val fcy = seatTop + SEAT_CONTENT_TOP + 15f
        canvas.drawCircle(fcx, fcy, 4f, Paint(fill).apply { color = mxColor })
        for (i in 0..2) {
            val a = Math.toRadians((i * 120).toDouble())
            canvas.drawCircle(
                fcx + (Math.cos(a) * 15f).toFloat(),
                fcy + (Math.sin(a) * 15f).toFloat(),
                10f, stroke,
            )
        }
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = 15f
        text.color = mxColor
        canvas.drawText("最大风挡", mxR.centerX(), seatTop + SEAT_CONTENT_TOP + 30f + 4f + 15f, text)
        // 进度条（22×4）
        fill.color = if (maxOn) Skin.ACCENT else Skin.surfaceStrong
        val maxBarY = seatTop + SEAT_CONTENT_TOP + 30f + 4f + 15f + 6f
        canvas.drawRoundRect(RectF(mxR.centerX() - 11f, maxBarY, mxR.centerX() + 11f, maxBarY + 4f), 2f, 2f, fill)
        hits["seatHeat"] = RectF(x + CLIMATE_CARD_PAD, seatTop, x + CLIMATE_CARD_PAD + CLIMATE_SEAT_W, seatTop + seatH)
        hits["seatVent"] = RectF(
            x + CLIMATE_CARD_PAD + CLIMATE_SEAT_W + CLIMATE_SEAT_GAP, seatTop,
            x + CLIMATE_CARD_PAD + (CLIMATE_SEAT_W + CLIMATE_SEAT_GAP) * 2, seatTop + seatH,
        )
        hits["maxFan"] = mxR
    }

    /** 座椅位：图标 + 文案 + 3 段进度。 */
    private fun seat(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, label: String, level: Int, tint: Int) {
        val r = RectF(x, top, x + w, top + h)
        canvas.drawRoundRect(r, 18f, 18f, Paint(fill).apply { this.color = Skin.GLASS_FILL })
        // 座椅轮廓（图标区 30px）
        stroke.color = if (level > 0) tint else Skin.TEXT_SOFT
        stroke.strokeWidth = 3f
        val sw = 30f
        val sh = 34f
        val cx = r.centerX()
        val sy = top + SEAT_CONTENT_TOP
        canvas.drawRoundRect(RectF(cx - sw / 2f, sy, cx + sw / 2f, sy + sh), 9f, 9f, stroke)
        canvas.drawLine(cx - sw / 2f - 7f, sy + 7f, cx - sw / 2f, sy + sh - 5f, stroke)
        canvas.drawLine(cx + sw / 2f + 7f, sy + 7f, cx + sw / 2f, sy + sh - 5f, stroke)
        // 文案（15px，marginTop 4）
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = 15f
        text.color = if (level > 0) tint else Skin.TEXT_SOFT
        canvas.drawText(label, cx, sy + 30f + 4f + 15f, text)
        // 3 段进度（14×4，间距 4）
        val segTop = sy + 30f + 4f + 15f + 6f
        val segW = 14f
        val segGap = 4f
        val totalW = 3 * segW + 2 * segGap
        var sx = cx - totalW / 2f
        for (i in 0..2) {
            fill.color = if (i < level) tint else Skin.surfaceStrong
            canvas.drawRoundRect(RectF(sx, segTop, sx + segW, segTop + 4f), 2f, 2f, fill)
            sx += segW + segGap
        }
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
        text.textSize = 28f
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
            // 注意：**必须在 28px 字号下量宽度** —— 先量再把字号切成 14px，
            // 否则量到的是 14px 的宽度，标签又会压回数字上。
            val valueW = text.measureText(v)
            text.textSize = 14f
            text.color = Skin.TEXT_DIM
            canvas.drawText(TIRE_UNIT, lx + valueW + 8f, rows[i], text)
            text.textSize = 28f
        }
        for (i in 0..1) {
            text.color = Skin.TEXT
            text.textAlign = Paint.Align.RIGHT
            val v = fmtTire(values[TireOrder.rightIndex(i)])   // 右列：FR, RR
            text.textSize = 14f
            val unitW = text.measureText(TIRE_UNIT)
            text.textSize = 28f
            canvas.drawText(v, rx - unitW - 8f, rows[i], text)
            text.textSize = 14f
            text.color = Skin.TEXT_DIM
            canvas.drawText(TIRE_UNIT, rx, rows[i], text)
            text.textSize = 28f
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
    private fun drawCarTop(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        drawCarTop(canvas, carTopFrame(cx, cy, w, h))
    }

    /**
     * 俯视车模的**画布变换**（设计空间 120×240 → 目标框）。
     *
     * 单独抽出来是因为车况卡要在车模上叠加「每个面板的开闭状态」，
     * 叠加层必须用**和车模完全一致**的变换，否则面板会整体错位。
     */
    private data class CarTopFrame(
        val centerX: Float,
        val centerY: Float,
        val scale: Float,
        val offX: Float,
        val offY: Float,
    ) {
        /** 设计空间 x → 画布 x。 */
        fun px(x: Float): Float = offX + x * scale

        /** 设计空间 y → 画布 y。 */
        fun py(y: Float): Float = offY + y * scale
    }

    /** 目标框 → 车模变换（等比缩放，取宽高两个比例里小的那个）。 */
    private fun carTopFrame(cx: Float, cy: Float, w: Float, h: Float): CarTopFrame {
        val scale = minOf(
            w / com.cardesk.launcher.core.CarModels.DESIGN_W,
            h / com.cardesk.launcher.core.CarModels.DESIGN_H,
        )
        return CarTopFrame(
            centerX = cx,
            centerY = cy,
            scale = scale,
            offX = cx - com.cardesk.launcher.core.CarModels.DESIGN_W * scale / 2f,
            offY = cy - com.cardesk.launcher.core.CarModels.DESIGN_H * scale / 2f,
        )
    }

    private fun drawCarTop(canvas: Canvas, frame: CarTopFrame) {
        val geo = com.cardesk.launcher.core.CarTopGeometryFactory.of(carModel)
        val scale = frame.scale
        fun px(x: Float) = frame.px(x)
        fun py(y: Float) = frame.py(y)

        // —— 1. 轮子（垫在车身下面，只探出 6px） ——
        val tirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = carColors.tire
            style = Paint.Style.FILL
        }
        val tireEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = carColors.tireEdge
            style = Paint.Style.STROKE
            strokeWidth = 1f * scale
        }
        for (wheel in geo.wheels) {
            val r = RectF(px(wheel.left), py(wheel.top), px(wheel.right), py(wheel.bottom))
            canvas.drawRoundRect(r, 5f * scale, 5f * scale, tirePaint)
            canvas.drawRoundRect(r, 5f * scale, 5f * scale, tireEdge)
        }

        // —— 2. 车身 ——
        //
        // 路径按分段构造
        // 鼻尖 ─贝塞尔─▶ 肩线 ─直线─▶ 最宽处 ─直线─▶ 后翼子板 ─贝塞尔─▶ 尾端（再镜像回车头）。
        // 侧腰是**直线**，和车头贝塞尔之间留一个硬肩线 —— 这才是「像车」的关键。
        val bodyPath = android.graphics.Path()
        val noseC = geo.noseCubic
        val tailC = geo.tailCubic
        val cx = com.cardesk.launcher.core.CarTopGeometryFactory.CENTER_X
        fun mirrorX(x: Float) = 2f * cx - x
        bodyPath.moveTo(px(geo.nose.x), py(geo.nose.y))
        bodyPath.cubicTo(
            px(noseC.c1.x), py(noseC.c1.y),
            px(noseC.c2.x), py(noseC.c2.y),
            px(geo.shoulder.x), py(geo.shoulder.y),
        )
        bodyPath.lineTo(px(geo.midTop.x), py(geo.midTop.y))
        bodyPath.lineTo(px(geo.midBottom.x), py(geo.midBottom.y))
        bodyPath.cubicTo(
            px(tailC.c1.x), py(tailC.c1.y),
            px(tailC.c2.x), py(tailC.c2.y),
            px(geo.tail.x), py(geo.tail.y),
        )
        // 左半（关于中心线镜像）
        bodyPath.cubicTo(
            px(mirrorX(tailC.c2.x)), py(tailC.c2.y),
            px(mirrorX(tailC.c1.x)), py(tailC.c1.y),
            px(mirrorX(geo.midBottom.x)), py(geo.midBottom.y),
        )
        bodyPath.lineTo(px(mirrorX(geo.midTop.x)), py(geo.midTop.y))
        bodyPath.lineTo(px(mirrorX(geo.shoulder.x)), py(geo.shoulder.y))
        bodyPath.cubicTo(
            px(mirrorX(noseC.c2.x)), py(noseC.c2.y),
            px(mirrorX(noseC.c1.x)), py(noseC.c1.y),
            px(geo.nose.x), py(geo.nose.y),
        )
        bodyPath.close()
        val half = geo.maxHalf
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                px(com.cardesk.launcher.core.CarTopGeometryFactory.CENTER_X - half),
                0f,
                px(com.cardesk.launcher.core.CarModels.DESIGN_W / 2f + half),
                0f,
                intArrayOf(carColors.bodyEdge, carColors.bodyMid, carColors.bodyEdge),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(bodyPath, bodyPaint)
        bodyPaint.shader = null
        bodyPaint.style = Paint.Style.STROKE
        bodyPaint.strokeWidth = 1.5f * scale
        bodyPaint.color = carColors.bodyEdgeLine
        canvas.drawPath(bodyPath, bodyPaint)

        // —— 3. 玻璃（前风挡 / 车顶 / 后风挡） ——
        val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                0f,
                py(geo.noseY),
                0f,
                py(geo.tailY),
                intArrayOf(carColors.glassTop, carColors.glassBottom),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(glassPath(geo.windshield, true, ::px, ::py), glassPaint)
        canvas.drawPath(glassPath(geo.rearGlass, false, ::px, ::py), glassPaint)

        // 车顶：玻璃色半透叠在车身上（`setAlpha(128)`）
        // 让车漆的中线高光透上来，形成「全景天幕」而不是一块死黑。
        val roofPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = carColors.roof
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(px(geo.roof.left), py(geo.roof.top), px(geo.roof.right), py(geo.roof.bottom)),
            geo.roofRadius * scale,
            geo.roofRadius * scale,
            roofPaint,
        )
        val roofEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = carColors.roofEdge
            style = Paint.Style.STROKE
            strokeWidth = 1f * scale
        }
        canvas.drawRoundRect(
            RectF(px(geo.roof.left), py(geo.roof.top), px(geo.roof.right), py(geo.roof.bottom)),
            geo.roofRadius * scale,
            geo.roofRadius * scale,
            roofEdge,
        )

        // —— 4. 灯组 ——
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = carColors.headLight }
        for (l in geo.headLights) {
            canvas.drawRoundRect(
                RectF(px(l.left), py(l.top), px(l.right), py(l.bottom)),
                2f * scale, 2f * scale, headPaint,
            )
        }
        val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = carColors.tailLight }
        for (l in geo.tailLights) {
            canvas.drawRoundRect(
                RectF(px(l.left), py(l.top), px(l.right), py(l.bottom)),
                2f * scale, 2f * scale, tailPaint,
            )
        }
    }

    /**
     * 把一块玻璃四边形变成 `Path`。
     *
     * 前风挡**上沿是弧**（`cubicTo`），后风挡**下沿是弧**
     * 这里用 [bulge] 控制鼓出的高度。
     *
     * @param bulgeTop true = 上沿鼓（风挡），false = 下沿鼓（后风挡）
     */
    private fun glassPath(
        quad: com.cardesk.launcher.core.GlassQuad,
        bulgeTop: Boolean,
        px: (Float) -> Float,
        py: (Float) -> Float,
    ): android.graphics.Path {
        val p = android.graphics.Path()
        p.moveTo(px(quad.topLeft.x), py(quad.topLeft.y))
        if (bulgeTop) {
            val span = quad.topRight.x - quad.topLeft.x
            p.cubicTo(
                px(quad.topLeft.x + span * 0.33f), py(quad.topLeft.y - quad.topBulge),
                px(quad.topLeft.x + span * 0.67f), py(quad.topRight.y - quad.topBulge),
                px(quad.topRight.x), py(quad.topRight.y),
            )
            p.lineTo(px(quad.bottomRight.x), py(quad.bottomRight.y))
            p.lineTo(px(quad.bottomLeft.x), py(quad.bottomLeft.y))
        } else {
            p.lineTo(px(quad.topRight.x), py(quad.topRight.y))
            p.lineTo(px(quad.bottomRight.x), py(quad.bottomRight.y))
            val span = quad.bottomRight.x - quad.bottomLeft.x
            p.cubicTo(
                px(quad.bottomRight.x - span * 0.33f), py(quad.bottomRight.y + quad.bottomBulge),
                px(quad.bottomRight.x - span * 0.67f), py(quad.bottomLeft.y + quad.bottomBulge),
                px(quad.bottomLeft.x), py(quad.bottomLeft.y),
            )
        }
        p.close()
        return p
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
    private fun drawCarCard(canvas: Canvas, rect: RectF) {
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

        // 数值 + 单位
        val range = info?.rangeKm?.takeIf { it >= 0 }
        val shown = range?.toString() ?: "--"
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 50f
        text.color = Skin.TEXT
        val shownW = text.measureText(shown)
        canvas.drawText(shown, x + 22f, top + 80f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f
        text.color = Skin.TEXT_DIM
        canvas.drawText("km", x + 22f + shownW + 8f, top + 80f, text)

        // 油量条 12px
        val barY = top + 100f
        val barX = x + 22f
        val barW = w - 44f
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
        canvas.drawText("燃料剩余 · ${VehicleStats.formatFuelPercent(fuel)}", x + 22f, top + 131f, text)

        // `SceneDesktop.disableShortClick(sceneCarCard)`（:703）：续航卡不可点
    }

    // ---------------------------------------------------------------- 车况卡

    /**
     * 车况卡（**第三十六轮第三轮改造新增**）：基于俯视车模显示
     * 每一个车门 / 车窗 / 后备箱盖 / 引擎盖的开闭状态。
     *
     * 版式（设计坐标 1920×1080，**与行程卡同宽**，左右边界都是 `1456 / 1876`）：
     * `
     * 行程卡 (1456..1876, 292..404)
     * 车况卡 (1456..1876, 424..654)
     * ┌──────────────────────────────────────────────────┐
     * │ 左前门 关 ╭─────╮ 右前门 关 │
     * │ 左前窗 关 │ 车 │ 右前窗 关 │
     * │ 左后门 关 │ 模 │ 右后门 关 │
     * │ 左后窗 关 ╰─────╯ 右后窗 关 │
     * │ 后备箱 关 引擎盖 关 │
     * └──────────────────────────────────────────────────┘
     * `
     *
     * 文字提示**分到车模左右两侧**：左列 = 车身上左边的部件、右列 = 右边的部件，
     * 同一行左右一一对应（行 1 = 两个前门、行 2 = 两个前窗……），
     * 所以看一眼文字就知道说的是车身上哪一块。
     *
     * ## 面板画法
     *
     * 车模上每一块门 / 窗 / 盖对应**一个矩形**（几何见
     * [com.cardesk.launcher.core.CarStatusGeometryFactory]：
     *
     * | 状态 | 画法 |
     * | --- | --- |
     * | 开 | 橙色实心高亮 |
     * | 关 | 淡描边（勾个轮廓，不抢注意力） |
     * | 读不到 | 中性灰描边 —— **不猜**，读不到 ≠ 关着 |
     *
     * ## 读不到怎么显示
     *
     * 这张卡**始终画**（它有车模这个固定视觉主体，而且用户明确要求要有）：
     * 读不到的那几项显示「--」，**不猜**——
     * 「读不到」和「关着」是两回事（这条口径与 `VehicleStats.interpretDoorClosed` 一致）。
     */
    private fun drawCarStatusCard(canvas: Canvas) {
        val i = info ?: return
        val doors = i.doorsClosed
        val windows = resolveWindowOpenFlags()
        val hood = i.hoodClosed
        val trunk = i.trunkClosed
        if (doors == null && windows == null && hood == null && trunk == null) {
            // 只在「从有值变成没值」时记一条，避免每秒刷屏。
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

        // —— 标题（第四十九轮「浮屿」加的）——
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 21f
        text.color = Skin.TEXT_DIM
        canvas.drawText("车况", r.left + 24f, r.top + 33f, text)

        // —— 1. 车模（与胎压卡同一套自绘车模，按车型出几何）——
        //
        // 车模**居中**在卡里，左右两侧各放一列文字提示，
        // 所以文字和它在车身上的位置能直接对上（左边那一列 = 车左边那几个面）。
        val geo = com.cardesk.launcher.core.CarTopGeometryFactory.of(carModel)
        val statusGeo = com.cardesk.launcher.core.CarStatusGeometryFactory.of(geo)
        val modelCx = (r.left + r.right) / 2f
        val frame = carTopFrame(
            modelCx,
            r.top + 40f + CAR_STATUS_MODEL_H / 2f,
            CAR_STATUS_MODEL_W,
            CAR_STATUS_MODEL_H,
        )
        drawCarTop(canvas, frame)

        // —— 2. 面板叠加（顺序与右侧图例一一对应）——
        val items = com.cardesk.launcher.core.CarStatus.items(doors, windows, hood, trunk)
        val rects = statusGeo.rects()
        for (k in items.indices) {
            val g = rects[k]
            val pr = RectF(frame.px(g.left), frame.py(g.top), frame.px(g.right), frame.py(g.bottom))
            when (items[k].open) {
                true -> {
                    if (k < CAR_STATUS_DOOR_COUNT) {
                        // —— 门开着：门板绕铰链「摆」出去（第四十五轮）——
                        //
                        // 车门示意是一张俯视车模，
                        // 每一扇门是一块**能绕铰链翻开**的板，
                        // 开门时旋转 ±45°。
                        //
                        // 我们先在**原位**画一个暗色「开口」（门原来的位置），
                        // 再把门板绕铰链转出去画在橙色 —— 一眼就能看出是哪扇门开着。
                        fill.color = carColors.panelGapFill
                        canvas.drawRoundRect(pr, 2f, 2f, fill)
                        stroke.color = carColors.panelGapLine
                        stroke.strokeWidth = 1.2f
                        canvas.drawRoundRect(pr, 2f, 2f, stroke)

                        val pose = com.cardesk.launcher.core.DoorSwing.pose(k, g)
                        canvas.save()
                        canvas.rotate(pose.angleDeg, frame.px(pose.hingeX), frame.py(pose.hingeY))
                        fill.color = carColors.panelOpenFill
                        canvas.drawRoundRect(pr, 2f, 2f, fill)
                        stroke.color = carColors.panelOpenLine
                        stroke.strokeWidth = 1.6f
                        canvas.drawRoundRect(pr, 2f, 2f, stroke)
                        canvas.restore()
                    } else {
                        fill.color = carColors.panelOpenFill
                        canvas.drawRoundRect(pr, 2f, 2f, fill)
                        stroke.color = carColors.panelOpenLine
                        stroke.strokeWidth = 1.6f
                        canvas.drawRoundRect(pr, 2f, 2f, stroke)
                    }
                }
                false -> {
                    fill.color = carColors.panelClosedFill
                    canvas.drawRoundRect(pr, 2f, 2f, fill)
                    stroke.color = carColors.panelClosedLine
                    stroke.strokeWidth = 1.2f
                    canvas.drawRoundRect(pr, 2f, 2f, stroke)
                }
                null -> {
                    stroke.color = carColors.panelUnknownLine
                    stroke.strokeWidth = 1.2f
                    canvas.drawRoundRect(pr, 2f, 2f, stroke)
                }
            }
            // 车窗面板可点：车机侧只有「全车窗」这一个命令（`OemWindowController`），
            // 所以点任意一扇窗 = 切换全部车窗。
            if (k in CAR_STATUS_WINDOW_ITEMS) {
                val slot = k - CAR_STATUS_WINDOW_ITEMS.first
                hits[CAR_STATUS_WINDOW_HIT_KEYS[slot]] = expandWindowHit(pr, frame)
            }
        }

        // —— 3. 文字提示：分到车模**左右两侧** ——
        //
        // 左列 = 车身上左边的部件、右列 = 右边的部件，同一行左右一一对应
        // （行 1 = 两个前门、行 2 = 两个前窗、行 3 = 两个后门、行 4 = 两个后窗、
        //   行 5 = 后备箱 / 引擎盖。
        //
        // 标签统一是 3 个汉字，所以「标签 → 状态」的间距可以定死；
        // 状态**左对齐**跟在标签后面（不右对齐到列尾），两列才不会糊在一起。
        // 第五十一轮按设计板 重排两列：
        // 左列贴卡左沿（inset 24）、右列**整体右对齐**到卡右沿，
        // 且右列是「状态 + 标签」（设计板 `关 右前门`，与左列 `左前门 关` 相反），
        // 字号 14/15 → **21**（设计板就是 21px）。
        val leftColX = r.left + CAR_STATUS_LEGEND_INSET
        val rightEdge = r.right - CAR_STATUS_LEGEND_INSET
        for (row in 0 until CAR_STATUS_ROWS) {
            val baseline = r.top + 65f + row * CAR_STATUS_ROW_H
            drawCarStatusLegend(canvas, items[CAR_STATUS_LEFT_ROWS[row]], leftColX, baseline)
            drawCarStatusLegend(canvas, items[CAR_STATUS_RIGHT_ROWS[row]], rightEdge, baseline, rightAligned = true)
        }
    }

    /**
     * 车况卡图例里的一行。
     *
     * - 左列：`标签 空格 状态`（`左前门 关`），左对齐到卡左沿；
     * - 右列：`状态 空格 标签`（`关 右前门`），**整体右对齐**到卡右沿。
     *
     * 字号 21px、字色 `--textDim`（设计板）。
     */
    private fun drawCarStatusLegend(
        canvas: Canvas,
        item: com.cardesk.launcher.core.CarStatusItem,
        x: Float,
        baseline: Float,
        rightAligned: Boolean = false,
    ) {
        val state = com.cardesk.launcher.core.CarStatus.stateText(item.open)
        text.typeface = Typeface.DEFAULT
        text.textSize = CAR_STATUS_LEGEND_SIZE
        text.textAlign = Paint.Align.LEFT
        text.color = Skin.TEXT_SOFT
        val labelW = text.measureText(item.label)
        val stateW = text.measureText(state)
        val stateColor = when (item.open) {
            true -> Palettes.CAR_STATE_WARN
            false -> Skin.TEXT_SOFT
            null -> Skin.TEXT_DIM
        }
        if (rightAligned) {
            // 「状态 + 标签」整体右对齐到 x
            val startX = x - stateW - CAR_STATUS_LEGEND_GAP - labelW
            text.color = stateColor
            canvas.drawText(state, startX, baseline, text)
            text.color = Skin.TEXT_SOFT
            canvas.drawText(item.label, startX + stateW + CAR_STATUS_LEGEND_GAP, baseline, text)
        } else {
            text.color = Skin.TEXT_SOFT
            canvas.drawText(item.label, x, baseline, text)
            text.color = stateColor
            canvas.drawText(state, x + labelW + CAR_STATUS_LEGEND_GAP, baseline, text)
        }
    }

    /**
     * 车窗面板的点击热区。
     *
     * 面板在设计空间里只有 9px 宽（折算到画布约 7px），手指根本点不准，
     * 所以往外多扩一圈（车体外侧扩得多、内侧扩得少），保证有个能点到的区域。
     */
    private fun expandWindowHit(pr: RectF, frame: CarTopFrame): RectF {
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
                    val hover = BottomCardOrder.indexAt(x, bounds)
                    if (hover >= 0) {
                        val current = bottomCardOrder.indexOf(id)
                        val target = hover
                        if (target != current) {
                            bottomCardOrder = BottomCardOrder.move(bottomCardOrder, current, target)
                            dragMoved = true
                            // 排障场景：「拖到某个位置没插进去」——记录每次经过的位置。
                            Logx.d(
                                LogTags.SCENE,
                                "底部卡片拖拽经过位置",
                                "card" to id,
                                "from" to current,
                                "to" to target,
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
                        orderStore?.save(bottomCardOrder)
                        onBottomCardOrderChanged?.invoke(bottomCardOrder)
                    }
                    // 排障场景：「排序没保存」——松手时记录最终顺序与是否真的挪动过。
                    Logx.event(
                        LogTags.SCENE,
                        "底部卡片拖拽结束",
                        "card" to id,
                        "moved" to dragMoved,
                        "order" to bottomCardOrder.joinToString(","),
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
        // 车窗面板（车况卡上的车模）：点击切换车窗。
        // 车机侧只有「全车窗」这一个命令（`OemWindowController.setWindowsClosed`），
        // 所以点任意一扇窗 = 切换**全部**车窗。
        for (slot in CAR_STATUS_WINDOW_HIT_KEYS.indices) {
            val key = CAR_STATUS_WINDOW_HIT_KEYS[slot]
            hits[key]?.let { r ->
                if (r.contains(x, y)) {
                    val next = !resolveWindowClosed()
                    windowClosed = next
                    // 下发后进入保护期：先用本地值显示，等车端回读到了再交给回读值。
                    windowPendingClosed = next
                    windowPendingUntil = android.os.SystemClock.uptimeMillis() + WINDOW_PENDING_MS
                    // 排障场景：「点了车窗面板车没反应 / 状态反了」——记下点的是哪扇窗、目标状态。
                    Logx.event(
                        LogTags.SCENE,
                        "车窗开关点击",
                        "card" to "carStatus",
                        "panel" to com.cardesk.launcher.core.CarStatus.WINDOW_LABELS[slot],
                        "closed" to next,
                        "x" to x,
                        "y" to y,
                    )
                    onToggleWindow?.invoke(next)
                    performClick()
                    return
                }
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

        /** 当前句字号。 */
        const val LYRIC_MAIN_SIZE = 52f

        /** 上一句 / 下一句字号。 */
        const val LYRIC_SIDE_SIZE = 24f

        /** 上一句基线 y（设计坐标）。 */
        const val LYRIC_PREV_Y = 434f

        /** 当前句基线 y（设计坐标）。 */
        const val LYRIC_MAIN_Y = 516f

        /** 下一句基线 y（设计坐标）。 */
        const val LYRIC_NEXT_Y = 582f

        /**
         * 歌词最大宽度（超出加省略号）。
         *
         * 第三十六轮第三轮改造从 1200 收到 **960**：
         * 车况卡改成和行程卡同宽后，左边界落在 `1456`，
         * 歌词居中（中心 960）再宽就会伸到车况卡底下被盖住。
         * 960 / 52px ≈ 18 个汉字，超过 18 字才截断，够用。
         */
        const val LYRIC_MAX_W = 960f

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

        /**
         * 场景页卡片圆角。
         *
         * 第四十九轮「浮屿」：卡片圆角统一 28（见 `Shape.CARD`），
         * 不再是默认 34 ——
         * 新视觉是**纯扁平 + 大圆角**，34 在 1920 宽下显得偏钝。
         */
        val GLASS_RADIUS: Float get() = com.cardesk.launcher.core.Shape.CARD

        // -------------------------------------------------- 行程卡（第三十六轮新增）

        /** 行程卡宽。第四十九轮「浮屿」：与车况卡 / 续航卡**统一 388**，右边缘同一条 1876。 */
        const val TRIP_CARD_W = 388f

        /** 行程卡高（标题 + 两行 × 52）。 */
        const val TRIP_CARD_H = 160f

        /** 行程卡上沿（第四十九轮与设计板一致：292；第五十二轮内容上移 37 → 255）。 */
        const val TRIP_CARD_TOP = 255f

        /** 行程卡右缘相对 `rightEnd` 的内缩。0 = 贴右缘，与状态栏、底部卡片行同一条右边界。 */
        const val TRIP_CARD_RIGHT_INSET = 0f

        // -------------------------------------------------- 车况卡（第三十六轮第三轮改造）

        /**
         * 车况卡宽 —— **与行程卡同宽**（第四十九轮「浮屿」统一 388），
         * 三张卡（行程 / 车况 / 续航）在右缘形成一条对齐的竖列。
         */
        const val CAR_STATUS_CARD_W = 388f

        /** 车况卡高。 */
        const val CAR_STATUS_CARD_H = 250f

        /**
         * 车况卡上沿。
         *
         * 行程卡底边 452 → 留 14px 呼吸 → 466；
         * 第五十二轮内容基线上移 37 → 429。
         */
        const val CAR_STATUS_CARD_TOP = 429f

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

        /** 车窗面板的点击命中键（与 `CarStatus.WINDOW_LABELS` 同序）。 */
        val CAR_STATUS_WINDOW_HIT_KEYS = arrayOf("carWin0", "carWin1", "carWin2", "carWin3")

        /**
         * 图例**左列**取 `CarStatus.items` 的哪几项。
         *
         * 文字分到车模两侧后，左列全是车身上**左边**的部件、右列全是**右边**的，
         * 同一行左右两侧一一对应（行 1 = 两个前门，行 2 = 两个前窗……）。
         */
        val CAR_STATUS_LEFT_ROWS = intArrayOf(0, 5, 2, 7, 4)

        /** 图例**右列**取 `CarStatus.items` 的哪几项（与 [CAR_STATUS_LEFT_ROWS] 逐行对应）。 */
        val CAR_STATUS_RIGHT_ROWS = intArrayOf(1, 6, 3, 8, 9)

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

        /** 快捷入口卡左边距（设计板：图标底板从卡内 24 开始）。 */
        const val QUICK_PAD = 24f

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
