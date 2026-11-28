package com.cardesk.launcher.ui.pages

import android.content.Context
import android.content.Intent
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
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
import com.cardesk.launcher.core.CardStyle
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
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
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
/**
 * cardtop 资产右侧烘焙柔影的裁切线（占车模图宽度的比例）。
 *
 * `car3d/<model>/cardtop/<color>/closed.webp` 在 x210..222 有一条 alpha 16..35 的竖向柔影
 * （车侧/后视镜投影，出图时被裁成硬边）。车况卡把车模缩到 190px 宽，它只剩 5px，
 * 但在纯白卡上仍是一根硬边细线 —— 车身右缘在 78.5% 处，从这里裁掉。
 */
private const val CAR3D_CLIP_RIGHT = 0.785f

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

    /** 标记首页非 bind 属性是否有变化，供 [bind] 在稳态下跳过无意义全屏重绘。 */
    private var sceneDirty: Boolean = true

    // 媒体（MusicController 推送）
    var mediaTitle: String = "暂无播放"
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /** 艺术家：默认是**空串**。 */
    var mediaArtist: String = ""
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /**
     * 媒体卡副标题：有歌手显示歌手，没有则退回来源名（画板 `Main.png` 的「通用音乐」）。
     *
     * 不直接改 [mediaArtist] 的语义 —— 它来自播放器，空就是空；
     * 版式上的兜底放在这里。
     */
    private val mediaSubtitle: String
        get() = mediaArtist.ifEmpty { mediaSource }
    var mediaSource: String = "通用音乐"
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /**
     * 媒体源 id（第 125 轮）—— 决定卡片上画哪个图标。
     *
     * 文案（[mediaSource]）给人看，id 才是机器认的键。
     */
    var mediaSourceId: String = "generic"
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /**
     * 媒体源的**真实 App 图标**（第 125 轮）；null = 用自绘符号。
     *
     * 由 [com.cardesk.launcher.ui.LauncherView] 在后台线程取好后塞进来。
     */
    var mediaSourceIcon: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            sourceIconShaderBmp = null
            sourceIconShader = null
            sceneDirty = true
        }

    /** 真实图标取不到时画哪个自绘符号（蓝牙 / USB / 音符）。 */
    var mediaSourceIconKind: com.cardesk.launcher.core.media.MediaSourceIconKind =
        com.cardesk.launcher.core.media.MediaSourceIconKind.NOTE
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    var mediaPlaying: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }
    var mediaArtwork: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            coverArtShaderBmp = null
            coverArtShader = null
            sceneDirty = true
        }

    /**
     * 播放位置（毫秒）—— 音乐卡进度环用。
     */
    var mediaPositionMs: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /** 曲目总时长（毫秒）—— 音乐卡进度环用；0 = 不知道总时长（不画进度）。 */
    var mediaDurationMs: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /** 封面主色 —— 封面光晕用。null = 自动从 [mediaArtwork] 采样。 */
    var mediaAccent: Int? = null
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    // -------------------------------------------------- 收藏（第207轮）
    //
    // 第207轮：**首页的音乐卡片也加上收藏按钮**（原来只有导航页组合卡有）。
    // 首页音乐卡是 Canvas 自绘的，所以这里的收藏按钮也是**自绘**：
    // 心形图标（复用 [com.cardesk.launcher.ui.widget.MediaActionIcons]）+ 一个命中区。

    /**
     * 当前会话的**收藏态**（三态：已收藏 / 未收藏 / 不知道）。
     *
     * 与导航页组合卡同一个数据源（`MusicController.get().favoriteState`），
     * 所以两处显示的收藏态永远一致。
     */
    var favoriteState: com.cardesk.launcher.core.media.MediaActions.FavoriteState =
        com.cardesk.launcher.core.media.MediaActions.FavoriteState.UNKNOWN
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /**
     * 当前会话所在的**播放器包名** —— 用来查这个播放器支不支持收藏。
     *
     * 未知包 / 不支持收藏 → **不画收藏按钮**（亮一个点了没反应的按钮更糟）。
     */
    var musicPackage: String? = null
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /** 未知播放器的运行时能力探测（会话发了心形评分 → 会支持收藏）。 */
    var actionProbe: com.cardesk.launcher.core.media.MediaActions.Probe? = null
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    /** 点收藏按钮。 */
    var onFavoriteToggle: (() -> Unit)? = null

    /**
     * 第209轮：音乐卡里**歌名/歌手实际占位的右缘**（px，卡片坐标系）。
     *
     * 收藏心形要摆在「歌名」和「播放键」中间，位置跟着它走，
     * 所以每帧画的时候记下来（给单测看）。
     */
    private var musicTextRight = 0f

    /**
     * 收藏按钮显不显示。
     *
     * 判据与导航页一致：[com.cardesk.launcher.core.media.MediaActions.capsOf]。
     */
    private fun favoriteSupported(): Boolean =
        com.cardesk.launcher.core.media.MediaActions.capsOf(musicPackage, actionProbe).favorite

    /** 收藏按钮的命中区 key（见 companion 里的 [FAVORITE_HIT_KEY]）。 */

    /**
     * 收藏图标缓存（第207轮）。
     *
     * [com.cardesk.launcher.ui.widget.MediaActionIcons.heart] 每次都会 new 一个 Drawable，
     * 而音乐卡是**每帧重画**的（进度环 / 歌词都在动）——
     * 每帧 new 一个 Drawable 会白白制造垃圾。
     * 所以按「实心与否 + 颜色」缓存，状态不变就复用同一个。
     */
    private var favoriteIconCache: android.graphics.drawable.Drawable? = null
    private var favoriteIconFilled = false
    private var favoriteIconColor = 0

    private fun favoriteIcon(
        filled: Boolean,
        color: Int,
    ): android.graphics.drawable.Drawable {
        val cached = favoriteIconCache
        if (cached != null && favoriteIconFilled == filled && favoriteIconColor == color) {
            return cached
        }
        val created = com.cardesk.launcher.ui.widget.MediaActionIcons.heart(filled, color)
        favoriteIconCache = created
        favoriteIconFilled = filled
        favoriteIconColor = color
        return created
    }

    // ------------------------------------------------------------------
    // 第207轮：给单测开的观察口（只读，不改行为）
    // ------------------------------------------------------------------

    /** 收藏命中区（没画出来时是 null）。 */
    fun favoriteHitForTest(): RectF? = hits[FAVORITE_HIT_KEY]

    /** 音乐卡播放键命中区（没画出来时是 null）。 */
    fun playHitForTest(): RectF? = hits[PLAY_HIT_KEY]

    /** 音乐卡里歌名/歌手实际占位的右缘（第209轮：心形摆在它和播放键中间）。 */
    fun musicTextRightForTest(): Float = musicTextRight

    /** 收藏命中区是否存在。 */
    fun hasFavoriteHitForTest(): Boolean = hits.containsKey(FAVORITE_HIT_KEY)

    /** 模拟一次点击（走真实的 [handleTap] 命中判定）。 */
    fun dispatchTapForTest(x: Float, y: Float) {
        handleTap(x, y)
    }

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
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

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
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

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
            val clamped = value.coerceIn(0f, 1f)
            if (kotlin.math.abs(field - clamped) < 0.0005f) return
            field = clamped
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
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

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
            if (field == value) return
            field = value
            sceneDirty = true
            // 排障场景：「天气一直显示获取中 / 城市不对」——每次刷新都打一条，落盘可回溯。
            logWeatherRefresh()
        }

    /** 天气描述（`WeatherDesk.KEY_SKY`），决定天气图标。 */
    var weatherSky: String = ""
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
            logWeatherRefresh()
        }

    /** 天气温度（整数，`String.valueOf(num)`）；null = 用车外温度兜底。 */
    var weatherTemp: Int? = null
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
            logWeatherRefresh()
        }

    /**
     * 海拔展示文案（如 `42 m`；空或 `--` 不画）。
     *
     * 海拔来自定位通道（[com.cardesk.launcher.core.AltitudeDesk]），异步到达，
     * 由宿主把 `AltitudeDesk.text()` 注入；没有值时天气块下方不占位。
     */
    var altitudeText: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
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
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
        }

    private var info: VehicleInfo? = null
    private var state: HvacState? = null

    /**
     * 当前壁纸序号。
     *
     * 换壁纸时圆点的颜色要**渐变**过去（见 [animateWallpaperDots]），
     * 所以这里换成带 setter 的属性 —— 只在**真的换了**时才播动画。
     */
    private var wallpaperIndex = 0
        set(v) {
            if (field == v) return
            field = v
            animateWallpaperDots(v)
        }

    /**
     * 壁纸圆点**当前显示中**的颜色。
     *
     * 圆点是自绘的，没有 View 可以反查颜色 —— 自己记一份，
     * 渐变才从「现在显示的色」出发，而不是每次都从半透明重新开始。
     */
    private val wallpaperDotColors = IntArray(WALLPAPER_NAMES.size) { i ->
        if (i == 0) 0xFFFFFFFF.toInt() else 0x73FFFFFF
    }

    /**
     * 车窗的**本地乐观状态**（里车窗图标是「点击关窗」的入口
     * 显示的是本地取反后的值，不等车端回读。
     *
     * 第三十六轮：车端回读（`WINDOW_POS` 属性）已经接上了，
     * 这个字段降级成**「读不到车端状态时的兜底」**，以及「刚下发完还没回读」时的过渡值。
     */
    var windowClosed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

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

    private fun sameVehicleInfo(a: VehicleInfo?, b: VehicleInfo?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.rangeKm == b.rangeKm &&
            a.odometerKm == b.odometerKm &&
            a.speedKmh == b.speedKmh &&
            a.gear == b.gear &&
            a.ambientTemp == b.ambientTemp &&
            a.fuelPercent == b.fuelPercent &&
            a.fuelLiters == b.fuelLiters &&
            a.doorsClosed == b.doorsClosed &&
            a.doorsAllClosed == b.doorsAllClosed &&
            a.hoodClosed == b.hoodClosed &&
            a.trunkClosed == b.trunkClosed &&
            a.windowsAllClosed == b.windowsAllClosed &&
            a.avgFuelConsumption == b.avgFuelConsumption &&
            a.tripFuelConsumption == b.tripFuelConsumption &&
            a.tripKm == b.tripKm &&
            a.tripDurationMs == b.tripDurationMs &&
            a.trip1Km == b.trip1Km &&
            a.trip1DurationMin == b.trip1DurationMin &&
            a.trip1AvgFuel == b.trip1AvgFuel &&
            a.tires.contentEquals(b.tires) &&
            (a.windowsOpen === b.windowsOpen ||
                (a.windowsOpen != null && b.windowsOpen != null && a.windowsOpen.contentEquals(b.windowsOpen)))
    }

    fun bind(info: VehicleInfo, state: HvacState, wallpaper: Int) {
        val infoChanged = !sameVehicleInfo(this.info, info)
        val stateChanged = this.state != state
        val wallpaperChanged = wallpaperIndex != wallpaper
        this.info = info
        this.state = state
        wallpaperIndex = wallpaper
        if (sceneDirty || infoChanged || stateChanged || wallpaperChanged) {
            sceneDirty = false
            // 排障场景：「场景页数据不刷新」——DEBUG 级，只进 Logcat，不占车机存储。
            Logx.d(LogTags.SCENE, "场景页数据绑定", "wallpaper" to wallpaper)
            invalidate()
        }
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

    /**
     * 媒体卡矩形（第244轮）：首页的来源弹层按「卡片上方」定位。
     *
     * 弹层不贴在来源图标上 —— 画板 11 的弹层是 361×625 的大列表，
     * 贴着图标会把卡片和歌词全盖住；弹在卡片**上方**才不挡住正在看的内容。
     */
    val musicCardRect: RectF? get() = hits["musicCard"]

    // 几何常量
    private val left = 44f
    private val rightEnd = Design.W - 44f
    /**
     * 底部卡片行距屏幕底的高度。
     *
     * 第242轮按设计板 Main 实测：卡片 803..953，Dock 顶 968 ——
     * 即「卡底 = 1080 - 127」。旧的 166 会让整行比设计稿高 39px。
     */
    private val bottomMargin = 127f
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
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 设置导航引导数据（null = 隐藏引导卡）。 */
    fun setGuide(guide: DesktopGuide?) {
        navGuide = guide
    }

    // ---------------------------------------------------------------- 快捷入口

    /** 快捷入口位（pkg 为空串时表示空槽，卡片上画「+ 添加」）。 */
    data class QuickApp(val pkg: String, val label: String, val icon: Drawable?)

    /** 点击快捷应用卡右上角「编辑 ›」回调（slotIndex = -1 打开完整配置面板）。 */
    var onConfigureQuickApps: ((slotIndex: Int) -> Unit)? = null

    /** 点击快捷应用卡空槽「+」回调：直接打开该槽位的应用选择器。 */
    var onPickQuickAppSlot: ((slotIndex: Int) -> Unit)? = null

    /** 点击快捷车控卡右上角「编辑 ›」回调。 */
    var onConfigureQuickControls: (() -> Unit)? = null

    /** 点击快捷车控卡中任意一键车控项回调（参数为 [com.cardesk.launcher.core.SceneQuickControls] 中的 ID）。 */
    var onQuickControl: ((id: String) -> Unit)? = null

    /** 快捷车控卡的实时附加状态（非 HvacState 覆盖的 360 / 氛围灯 / 热点 / 启停）。 */
    var quickCam360On: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
            invalidate()
        }
    var quickAmbientOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
            invalidate()
        }
    var quickHotspotOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
            invalidate()
        }
    var quickIssOn: Boolean? = null
        set(value) {
            if (field == value) return
            field = value
            sceneDirty = true
            invalidate()
        }

    /** 当前快捷车控卡配置的动作 ID 列表（1..4 项）。 */
    var quickControls: List<String> = com.cardesk.launcher.core.SceneQuickControls.DEFAULT_SLOTS
        set(value) {
            val norm = com.cardesk.launcher.core.SceneQuickControls.normalize(value)
            if (field == norm) return
            field = norm
            sceneDirty = true
            invalidate()
        }

    /** 从偏好重新加载快捷车控槽位列表。 */
    fun refreshQuickControls() {
        val prefs = com.cardesk.launcher.core.Prefs(context.applicationContext)
        quickControls = prefs.sceneQuickControls
    }

    private var quickApps: List<QuickApp> = emptyList()

    /** 当前卡片展示的包名列表（配置面板在未自定义时用于预览当前默认槽位）。 */
    fun currentQuickAppPackages(): List<String> = quickApps.map { it.pkg }

    /** 直接指定快捷入口（测试 / 外部注入用）。 */
    fun setQuickApps(apps: List<QuickApp>) {
        quickApps = apps
        invalidate()
    }

    /**
     * 刷新快捷入口列表：
     * - 用户自定义过（`Prefs.sceneQuickAppsConfigured == true`）→ 严格按 4 个槽位加载（空槽画「+」）；
     * - 未自定义 → 自动取前 4 个可用可启动应用（优先排除桌面自身）。
     */
    fun refreshQuickApps() {
        val pm = context.packageManager
        val prefs = com.cardesk.launcher.core.Prefs(context.applicationContext)
        if (prefs.sceneQuickAppsConfigured) {
            val slots = com.cardesk.launcher.core.SceneQuickApps.normalize(prefs.sceneQuickApps)
            quickApps = slots.map { pkg ->
                if (pkg.isEmpty()) {
                    QuickApp("", "", null)
                } else {
                    val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                    if (info == null || !info.enabled) {
                        QuickApp("", "", null)
                    } else {
                        val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
                        val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                        QuickApp(pkg, label, icon)
                    }
                }
            }
            Logx.i(
                LogTags.SCENE,
                "快捷入口列表加载（自定义）",
                "shown" to quickApps.count { it.pkg.isNotEmpty() },
                "pkgs" to quickApps.joinToString(",") { it.pkg },
            )
            invalidate()
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo?.packageName.orEmpty() }
                .filter { it.activityInfo?.applicationInfo?.enabled != false }
        } catch (t: Throwable) {
            // 排障场景：「快捷入口是空的」——查询失败必须留痕，否则看不出是没权限还是没应用。
            Logx.w(LogTags.SCENE, "快捷入口列表查询失败", t, "reason" to t.message)
            emptyList()
        }
        val nonSelf = resolved.filter { it.activityInfo?.packageName != context.packageName }
        val pool = if (nonSelf.size >= QUICK_SLOTS) nonSelf else resolved
        quickApps = pool.take(QUICK_SLOTS).map { r ->
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
        canvas.save()
        canvas.clipRect(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.bottom - 2f)
        val i = info
        val padScale = (rect.width() / 360f).coerceIn(0.75f, 1f)
        val leftX = rect.left + TRIP_COL_LEFT * padScale
        val trailing = TRIP_CELL_TRAILING * padScale
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        // 第189轮：标题字号 22 → 24（[TRIP_TITLE_SP]）。
        text.textSize = TRIP_TITLE_SP
        text.color = Skin.TEXT_DIM
        canvas.drawText("行程", leftX, rect.top + TRIP_TITLE_BASE, text)

        // 第244轮（用户反馈「留白太多，时长和总里程可以往右一点」）：
        // 按画板实测把两列改成 左列起点 14、右列起点 51.8% 卡宽（429 卡 ⇒ 222），
        // 行距收紧成 标签基线 51/104、数值基线 77/130（原来数值贴到卡底 143）。
        val splitX = rect.left + rect.width() * TRIP_COL_SPLIT
        val colX = floatArrayOf(leftX, splitX)
        val rowTop = floatArrayOf(rect.top + TRIP_CELL_TOP_1, rect.top + TRIP_CELL_TOP_2)
        val cellW1 = (splitX - leftX - trailing).coerceAtLeast(24f)
        val cellW2 = (rect.right - trailing - splitX).coerceAtLeast(24f)
        val cellWS = floatArrayOf(cellW1, cellW2)
        val cellW = minOf(cellW1, cellW2)
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
        drawTripCell(canvas, colX[0], rowTop[0], "里程", values[0], cellWS[0], labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[0], "时长", values[1], cellWS[1], labelSp, valueSp)
        drawTripCell(canvas, colX[0], rowTop[1], "平均油耗", values[2], cellWS[0], labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[1], "总里程", values[3], cellWS[1], labelSp, valueSp)
        canvas.restore()
    }

    /**
     * 卡片库新增的「车门车窗」卡（第九十五轮 C2 迭代 2）：四门/车窗/后备箱/引擎盖汇总。
     */
    private fun drawStatusBottomCard(canvas: Canvas, rect: RectF) {
        card(canvas, rect)
        canvas.save()
        canvas.clipRect(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.bottom - 2f)
        val i = info
        val padScale = (rect.width() / 360f).coerceIn(0.75f, 1f)
        val leftX = rect.left + TRIP_COL_LEFT * padScale
        val trailing = TRIP_CELL_TRAILING * padScale
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        // 第189轮：和行程卡同一档字号（两个卡在底排并排，字号不一致会很扎眼）。
        text.textSize = TRIP_TITLE_SP
        text.color = Skin.TEXT_DIM
        canvas.drawText("车门车窗", leftX, rect.top + TRIP_TITLE_BASE, text)

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
        // 第244轮（用户反馈「留白太多，时长和总里程可以往右一点」）：
        // 按画板实测把两列改成 左列起点 14、右列起点 51.8% 卡宽（429 卡 ⇒ 222），
        // 行距收紧成 标签基线 51/104、数值基线 77/130（原来数值贴到卡底 143）。
        val splitX = rect.left + rect.width() * TRIP_COL_SPLIT
        val colX = floatArrayOf(leftX, splitX)
        val rowTop = floatArrayOf(rect.top + TRIP_CELL_TOP_1, rect.top + TRIP_CELL_TOP_2)
        val cellW1 = (splitX - leftX - trailing).coerceAtLeast(24f)
        val cellW2 = (rect.right - trailing - splitX).coerceAtLeast(24f)
        val cellWS = floatArrayOf(cellW1, cellW2)
        val cellW = minOf(cellW1, cellW2)
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
        drawTripCell(canvas, colX[0], rowTop[0], "车门", values[0], cellWS[0], labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[0], "车窗", values[1], cellWS[1], labelSp, valueSp)
        drawTripCell(canvas, colX[0], rowTop[1], "后备箱", values[2], cellWS[0], labelSp, valueSp)
        drawTripCell(canvas, colX[1], rowTop[1], "引擎盖", values[3], cellWS[1], labelSp, valueSp)
        canvas.restore()
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

    /** 车况卡小尺寸样式（与底部卡片等高、车模头朝左、仅显车门与胎压）。 */
    var carStatusCardCompact: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 车况卡自定义横/纵坐标（<0 表示使用默认右对齐位置）。 */
    var carStatusCardPosX: Float = -1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var carStatusCardPosY: Float = -1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 车况卡被拖拽到新位置时回调持久化。 */
    var onCarStatusCardMoved: ((Float, Float) -> Unit)? = null

    /** 车况卡是否拖入底部卡片行及其位置索引。 */
    var carStatusInBottomRow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var carStatusBottomIndex: Int = -1

    /** 首页底排卡片的自由摆放坐标（id -> (x, y)）。 */
    private val freeCardPos = LinkedHashMap<String, Pair<Float, Float>>()

    var cardFreePositions: Map<String, Pair<Float, Float>>
        get() = freeCardPos.toMap()
        set(value) {
            if (freeCardPos == value) return
            freeCardPos.clear()
            freeCardPos.putAll(value)
            invalidate()
        }

    /** 底排卡片自由摆放位置变化时回调持久化。 */
    var onCardFreePosChanged: ((Map<String, Pair<Float, Float>>) -> Unit)? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 车况卡拖入/拖出底部行或行内换位时回调持久化。 */
    var onCarStatusRowChanged: ((Boolean, Int) -> Unit)? = null

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
        refreshQuickControls()
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
            val label = clipLabel(s, w)
            if (leftX != null) {
                // 锚定布局：这一行从自己的左边缘起排（不居中）。
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(label, leftX, base[i], text)
            } else {
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(label, cx, base[i], text)
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
        // 第222轮：中心歌词**直接压在壁纸上** ⇒ 颜色按壁纸明暗走
        // （见 [com.cardesk.launcher.core.WallpaperContrast]）。
        com.cardesk.launcher.core.media.LyricSizeTier.MAIN -> Skin.overlayCenter.text
        com.cardesk.launcher.core.media.LyricSizeTier.NEAR -> Skin.overlayCenter.soft
        else -> Skin.overlayCenter.faint
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
        // 整张引导卡按下时轻微缩小（场景页是自绘的，没有子 View 能挂按压反馈）。
        val navPressed = beginPress(canvas, "nav", r)
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
        if (navPressed) canvas.restore()
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
        // 巡航卡同样是整卡可点 —— 按下时轻微缩小。
        val navPressed = beginPress(canvas, "nav", r)
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
        if (navPressed) canvas.restore()
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
        // 天气温度是**整数**，没有天气数据时用车外温度兜底
        val ambient = info?.ambientTemp?.takeIf { !it.isNaN() }
        val temp = weatherTemp ?: ambient?.toInt()
        val shown = temp?.toString() ?: "--"

        // 第222轮：天气块**直接压在壁纸上**（没有底板）⇒ 颜色按壁纸明暗走。
        // 日间模式 + 暗色壁纸时，深色字压在深色照片上几乎看不见 —— 就是这一处。
        val ink = Skin.overlayTop

        // 度数符号（38px）先画，主数字（96px 粗体）贴着它的左边
        text.typeface = Typeface.DEFAULT
        text.textSize = 38f
        text.color = ink.text
        val degW = text.measureText("°")
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 96f
        val numW = text.measureText(shown)

        // 图标（20px 太阳）+ 详情（20px 次文字，与上排距 12）
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f
        text.color = ink.soft
        val detail = weatherDetailText()
        val detailW = text.measureText(detail)

        // 度数符号（38px）先画，主数字（96px 粗体）贴着它的左边
        text.textAlign = Paint.Align.RIGHT
        text.typeface = Typeface.DEFAULT
        text.textSize = 38f
        text.color = ink.text
        canvas.drawText("°", end, top + 77f, text)

        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 96f
        canvas.drawText(shown, end - degW, top + 77f, text)

        // 图标（20px 太阳）+ 详情（20px 次文字，与上排距 12）
        text.typeface = Typeface.DEFAULT
        text.textSize = 20f
        text.color = ink.soft
        canvas.drawText(detail, end, top + 124f, text)
        drawWeatherIcon(canvas, end - detailW - 5f - 10f, top + 114f, weatherSky)

        // 海拔：天气详情下方再一行（基线 top + 152 ≈ 227，20px 次文字，右对齐 end）。
        // 只有拿到值才画：空 / `--` 不占位，也压不到下面 y=292 起的行程卡。
        // 字号/字型/颜色与上面 detail 行一致，画完不用回滚绘制状态。
        val altitude = altitudeText.trim()
        if (altitude.isNotEmpty() && altitude != "--") {
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText("海拔 $altitude", end, top + 152f, text)
        }
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
        // 第222轮：颜色跟天气块一起走浮层墨色（压在壁纸上的图标）。
        com.cardesk.launcher.ui.WeatherGlyphs.draw(canvas, cx, cy, sky, Skin.overlayTop.soft, 2f)
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
        val rowW = rightEnd - left
        val baseWidths = com.cardesk.launcher.core.CardLayout.widths(
            com.cardesk.launcher.core.CardLayout.active(cardLayout).filterNot { it.id in freeCardPos },
            rowW,
            CARD_GAP,
            naturalWidth = { id -> cardWidth(id) },
            minWidth = 160f,
        ).toMutableList()
        if (carStatusInBottomRow && showCarStatusCard) {
            val idx = (if (carStatusBottomIndex < 0) baseWidths.size else carStatusBottomIndex)
                .coerceIn(0, baseWidths.size)
            baseWidths.add(idx, com.cardesk.launcher.core.CardLayout.CAR_STATUS to CAR_STATUS_CARD_W)
            val totalGap = CARD_GAP * (baseWidths.size - 1).coerceAtLeast(0)
            val sumW = baseWidths.sumOf { it.second.toDouble() }.toFloat()
            val avail = (rowW - totalGap).coerceAtLeast(1f)
            if (sumW > avail && sumW > 0f) {
                val scale = avail / sumW
                for (i in baseWidths.indices) {
                    baseWidths[i] = baseWidths[i].first to (baseWidths[i].second * scale)
                }
            }
        }
        var x = left
        for ((id, w) in baseWidths) {
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
        com.cardesk.launcher.core.CardLayout.CAR_STATUS -> CAR_STATUS_CARD_W
        else -> 0f
    }

    /** 自由摆放模式下单张卡片的宽度：严格遵循该卡片在设置里的尺寸档位（小 / 中 / 自适应 / 大）。 */
    private fun freeCardWidth(id: String): Float {
        val rowW = (rightEnd - left).coerceAtLeast(160f)
        val slotScale = com.cardesk.launcher.core.CardLayout.active(cardLayout)
            .firstOrNull { it.id == id }?.size?.scale ?: 1f
        return (cardWidth(id) * slotScale).coerceIn(160f, rowW)
    }

    private fun drawCardContent(canvas: Canvas, id: String, drawRect: RectF) {
        when (id) {
            com.cardesk.launcher.core.CardLayout.QUICK -> drawQuick(canvas, drawRect)
            com.cardesk.launcher.core.CardLayout.MUSIC -> drawMusicCard(canvas, drawRect)
            com.cardesk.launcher.core.CardLayout.CLIMATE -> drawClimateCard(canvas, drawRect)
            com.cardesk.launcher.core.CardLayout.TIRE -> drawTireCard(canvas, drawRect)
            com.cardesk.launcher.core.CardLayout.RANGE -> drawRangeCard(canvas, drawRect)
            com.cardesk.launcher.core.CardLayout.LEGACY_TRIP,
            com.cardesk.launcher.core.CardLayout.TRIP_CARD -> drawTripCard(canvas, drawRect)
            com.cardesk.launcher.core.CardLayout.STATUS -> drawStatusBottomCard(canvas, drawRect)
            com.cardesk.launcher.core.CardLayout.CAR_STATUS -> {
                val i = info
                if (i != null) {
                    val dp = com.cardesk.launcher.core.car3d.Car3dDebugPose.pose
                    val effDoors = dp?.doors?.map { !it } ?: i.doorsClosed
                    val effTrunk = dp?.trunk?.let { !it } ?: i.trunkClosed
                    card(canvas, drawRect)
                    hits[CAR_STATUS_CARD_HIT_KEY] = RectF(drawRect)
                    drawCarStatusCardCompact(canvas, drawRect, effDoors, i.hoodClosed, effTrunk, i)
                }
            }
        }
    }

    private fun drawBottomCards(canvas: Canvas) {
        val layout = layoutBottomCards()
        cardRects.clear()
        cardRects.putAll(layout)
        val rowW = (rightEnd - left).coerceAtLeast(160f)
        for ((id, rect) in layout) {
            val drawRect = if (id == dragId && !dragFreeCard) {
                RectF(rect.left + dragDx, rect.top, rect.right + dragDx, rect.bottom)
            } else {
                rect
            }
            val lifting = id == dragId && dragLift > 0f
            if (lifting) {
                canvas.save()
                val s = 1f + (MotionSpec.STATE_BUMP - 1f) * dragLift
                canvas.scale(s, s, drawRect.centerX(), drawRect.centerY())
            }
            drawCardContent(canvas, id, drawRect)
            if (id == dragId) {
                drawDragOutline(canvas, drawRect)
            }
            if (lifting) canvas.restore()
        }
        // 绘制已拖出底部行、自由摆放在首页任意位置的底排卡片（正在被拖的那张最后画以浮在最上层）
        val activeSlots = com.cardesk.launcher.core.CardLayout.active(cardLayout).filter { it.visible }
        val slotById = activeSlots.associateBy { it.id }
        val freeEntries = freeCardPos.entries.filter { it.key in slotById }
            .sortedBy { if (dragFreeCard && it.key == dragId) 1 else 0 }
        for ((id, savedPos) in freeEntries) {
            val w = freeCardWidth(id)
            val isDragging = dragFreeCard && dragId == id
            val posX = if (isDragging) dragFreeX else savedPos.first.coerceIn(left, (rightEnd - w).coerceAtLeast(left))
            val posY = if (isDragging) dragFreeY else savedPos.second.coerceIn(84f, cardTop.coerceAtLeast(84f))
            val drawRect = RectF(posX, posY, posX + w, posY + cardH)
            cardRects[id] = drawRect
            val lifting = isDragging && dragLift > 0f
            if (lifting) {
                canvas.save()
                val s = 1f + (MotionSpec.STATE_BUMP - 1f) * dragLift
                canvas.scale(s, s, drawRect.centerX(), drawRect.centerY())
            }
            drawCardContent(canvas, id, drawRect)
            if (isDragging) {
                drawDragOutline(canvas, drawRect)
            }
            if (lifting) canvas.restore()
        }
    }

    /**
     * 统一卡片底（第242轮「液态玻璃」）：模糊背景 + 半透明面 + 内高光 + 柔阴影。
     *
     * 所有卡片的观感都由这一处决定 —— 设计稿 L1/L2 的差别只在于 [strong]。
     */
    private fun card(canvas: Canvas, r: RectF, radius: Float = GLASS_RADIUS, strong: Boolean = false) {
        com.cardesk.launcher.core.Glass.card(
            canvas = canvas,
            rect = r,
            radius = radius,
            fill = if (strong) Skin.surfaceStrong else Skin.surface,
            stroke = Skin.GLASS_STROKE,
        )
    }

    /** 不画阴影的卡片底（贴在别的卡里的小块用）。 */
    private fun cardFlat(canvas: Canvas, r: RectF, radius: Float = GLASS_RADIUS, strong: Boolean = false) {
        com.cardesk.launcher.core.Glass.card(
            canvas = canvas,
            rect = r,
            radius = radius,
            fill = if (strong) Skin.surfaceStrong else Skin.surface,
            shadow = false,
            sheen = false,
            stroke = Skin.GLASS_STROKE,
        )
    }

    /**
     * 快捷应用卡：自适应所有卡片尺寸（极窄 160px / 小 / 中 / 自适应 / 大），严禁图标与文字溢出。
     *
     * - 顶栏与「快捷车控」/「车况」卡统一：左侧 16px 标题「快捷应用」（配置入口统一收口到设置页）；
     * - 宽 ≥ 220px：单行均分 N 个圆角槽位（图标底板 + 缩放图标 + 单行省略文案，或空槽「+ 添加」）；
     * - 宽 < 220px（极窄小卡）：自动切换 2×2 紧凑图标网格，避免 4 列挤爆导致溢出或裁字。
     */
    private fun drawQuick(canvas: Canvas, rect: RectF) {
        card(canvas, rect)
        canvas.save()
        canvas.clipRect(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.bottom - 2f)
        cardTitle(canvas, rect, "快捷应用", size = 16f, baseline = 23f)

        val apps = quickApps
        if (apps.isEmpty()) {
            drawLegacyQuick(canvas, rect)
            canvas.restore()
            return
        }

        val n = apps.size.coerceIn(1, QUICK_SLOTS)
        val contentTop = rect.top + 34f
        val compactGrid = rect.width() < 220f && n > 2
        if (compactGrid) {
            val cols = 2
            val rows = (n + 1) / 2
            val padX = 10f
            val padBottom = 10f
            val gapX = 8f
            val gapY = 8f
            val cellW = ((rect.width() - padX * 2f - gapX * (cols - 1)) / cols).coerceAtLeast(24f)
            val cellH = ((rect.height() - 34f - padBottom - gapY * (rows - 1)) / rows).coerceAtLeast(24f)
            val chipRadius = (minOf(cellW, cellH) * 0.28f).coerceIn(8f, 14f)
            val glyphSize = (minOf(cellW, cellH) * 0.64f).coerceIn(18f, 34f)
            for (i in 0 until n) {
                val col = i % cols
                val row = i / cols
                val left = rect.left + padX + col * (cellW + gapX)
                val top = contentTop + row * (cellH + gapY)
                val cell = RectF(left, top, left + cellW, top + cellH)
                hits["quick$i"] = cell
                val app = apps[i]
                withPress(canvas, "quick$i") {
                    canvas.drawRoundRect(cell, chipRadius, chipRadius, Paint(fill).apply { color = chipFill() })
                    if (app.pkg.isEmpty()) {
                        drawQuickPlus(canvas, cell.centerX(), cell.centerY(), glyphSize * 0.42f)
                    } else if (app.icon != null) {
                        drawIcon(canvas, app.icon, cell.centerX(), cell.centerY(), glyphSize)
                    } else {
                        drawQuickFallbackGlyph(canvas, app.label, cell.centerX(), cell.centerY(), glyphSize * 0.5f)
                    }
                }
            }
        } else {
            val padX = (rect.width() * 0.038f).coerceIn(10f, 18f)
            val padBottom = 12f
            val availW = (rect.width() - padX * 2f).coerceAtLeast(60f)
            val availH = (rect.height() - 34f - padBottom).coerceAtLeast(56f)
            val gap = (availW * 0.03f).coerceIn(6f, 16f)
            val slotW = ((availW - gap * (n - 1)) / n).coerceAtLeast(24f)
            val chipRadius = (slotW * 0.22f).coerceIn(10f, 18f)
            val labelSize = (slotW * 0.20f).coerceIn(11f, 14f)
            val labelBand = labelSize + 10f
            val iconBox = minOf(slotW - 12f, availH - labelBand - 10f).coerceIn(26f, 56f)
            val iconBoxRadius = (iconBox * 0.28f).coerceIn(8f, 16f)
            val glyphSize = (iconBox * 0.72f).coerceIn(20f, 44f)
            val iconTop = contentTop + ((availH - labelBand) - iconBox) / 2f + 2f
            val labelY = contentTop + availH - 8f

            for (i in 0 until n) {
                val slotLeft = rect.left + padX + i * (slotW + gap)
                val cell = RectF(slotLeft, contentTop, slotLeft + slotW, contentTop + availH)
                val cx = cell.centerX()
                val iconCy = iconTop + iconBox / 2f
                val app = apps[i]
                hits["quick$i"] = cell
                withPress(canvas, "quick$i") {
                    canvas.drawRoundRect(cell, chipRadius, chipRadius, Paint(fill).apply { color = chipFill() })
                    val boxRect = RectF(cx - iconBox / 2f, iconTop, cx + iconBox / 2f, iconTop + iconBox)
                    canvas.drawRoundRect(boxRect, iconBoxRadius, iconBoxRadius, Paint(fill).apply { color = Skin.surfaceSoft })
                    if (app.pkg.isEmpty()) {
                        drawQuickPlus(canvas, cx, iconCy, iconBox * 0.26f)
                    } else if (app.icon != null) {
                        drawIcon(canvas, app.icon, cx, iconCy, glyphSize)
                    } else {
                        drawQuickFallbackGlyph(canvas, app.label, cx, iconCy, iconBox * 0.36f)
                    }
                    text.textAlign = Paint.Align.CENTER
                    text.typeface = Typeface.DEFAULT
                    text.textSize = labelSize
                    text.color = if (app.pkg.isEmpty()) Skin.TEXT_DIM else Skin.TEXT_SOFT
                    val caption = if (app.pkg.isEmpty()) "添加" else app.label
                    canvas.drawText(clipLabel(caption, (slotW - 6f).coerceAtLeast(16f)), cx, labelY, text)
                }
            }
        }
        canvas.restore()
    }

    /** 快捷应用空槽「+」图标。 */
    private fun drawQuickPlus(canvas: Canvas, cx: Float, cy: Float, half: Float) {
        stroke.color = Skin.TEXT_DIM
        stroke.strokeWidth = 2.4f
        canvas.drawLine(cx - half, cy, cx + half, cy, stroke)
        canvas.drawLine(cx, cy - half, cx, cy + half, stroke)
    }

    /** 应用取不到图标时的首字占位。 */
    private fun drawQuickFallbackGlyph(canvas: Canvas, label: String, cx: Float, cy: Float, textSp: Float) {
        val ch = label.trim().take(1)
        if (ch.isEmpty()) {
            fill.color = Skin.TEXT_SOFT
            canvas.drawCircle(cx, cy, (textSp * 0.5f).coerceAtLeast(6f), fill)
            return
        }
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = textSp.coerceIn(13f, 22f)
        text.color = Skin.TEXT_SOFT
        canvas.drawText(ch, cx, cy + text.textSize * 0.35f, text)
    }

    /**
     * 底部功能卡的统一标题（设计板：21px 弱文字，距左 18 / 距上 10）。
     */
    private fun cardTitle(
        canvas: Canvas,
        rect: RectF,
        title: String,
        size: Float = 21f,
        baseline: Float = 27f,
    ) {
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = size
        text.color = Skin.TEXT_DIM
        // 左内边固定 18；字号变小后不再需要额外缩进。
        canvas.drawText(title, rect.left + 18f, rect.top + baseline, text)
    }

    /** 没有可启动应用时的兜底：自适应铺满「车辆 / 导航 / 电话 / 全部」四个入口，绝不溢出。 */
    private fun drawLegacyQuick(canvas: Canvas, rect: RectF) {
        val labels = listOf("车辆", "导航", "电话", "全部")
        val padX = (rect.width() * 0.038f).coerceIn(10f, 18f)
        val padBottom = 12f
        val contentTop = rect.top + 34f
        val availW = (rect.width() - padX * 2f).coerceAtLeast(60f)
        val availH = (rect.height() - 34f - padBottom).coerceAtLeast(56f)
        val gap = (availW * 0.03f).coerceIn(6f, 16f)
        val slotW = ((availW - gap * 3f) / 4f).coerceAtLeast(24f)
        val chipRadius = (slotW * 0.22f).coerceIn(10f, 18f)
        val labelSize = (slotW * 0.20f).coerceIn(11f, 14f)
        val iconScale = (minOf(slotW, availH - labelSize - 18f) / 56f).coerceIn(0.45f, 1f)
        val iconCy = contentTop + (availH - labelSize - 10f) / 2f + 2f
        val labelY = contentTop + availH - 8f

        for (i in 0..3) {
            val slotLeft = rect.left + padX + i * (slotW + gap)
            val cell = RectF(slotLeft, contentTop, slotLeft + slotW, contentTop + availH)
            val cx = cell.centerX()
            hits["quick$i"] = cell
            withPress(canvas, "quick$i") {
                canvas.drawRoundRect(cell, chipRadius, chipRadius, Paint(fill).apply { color = chipFill() })
                canvas.save()
                canvas.translate(cx, iconCy)
                canvas.scale(iconScale, iconScale)
                fill.color = Skin.TEXT
                when (i) {
                    0 -> { // 车
                        stroke.color = Skin.TEXT
                        stroke.strokeWidth = 2.6f
                        val carR = RectF(-20f, -13f, 20f, 15f)
                        canvas.drawRoundRect(carR, 10f, 10f, stroke)
                        canvas.drawCircle(-13f, 17f, 4f, fill)
                        canvas.drawCircle(13f, 17f, 4f, fill)
                    }
                    1 -> { // 导航箭头
                        val path = Path().apply {
                            moveTo(0f, -20f)
                            lineTo(16f, 18f)
                            lineTo(0f, 10f)
                            lineTo(-16f, 18f)
                            close()
                        }
                        canvas.drawPath(path, fill)
                    }
                    2 -> { // 电话
                        stroke.color = Skin.TEXT
                        stroke.strokeWidth = 3f
                        val path = Path().apply {
                            moveTo(-16f, -16f)
                            lineTo(-6f, -6f)
                            lineTo(-12f, 2f)
                            quadTo(2f, 16f, 14f, 10f)
                            lineTo(18f, -2f)
                            lineTo(6f, -6f)
                        }
                        canvas.drawPath(path, stroke)
                    }
                    else -> { // 4 格
                        for (row in 0..1) for (col in 0..1) {
                            canvas.drawRoundRect(
                                RectF(-16f + col * 18f, -16f + row * 18f, -2f + col * 18f, -2f + row * 18f),
                                4f, 4f, fill,
                            )
                        }
                    }
                }
                canvas.restore()
                text.textAlign = Paint.Align.CENTER
                text.typeface = Typeface.DEFAULT
                text.textSize = labelSize
                text.color = Skin.TEXT_SOFT
                canvas.drawText(clipLabel(labels[i], (slotW - 6f).coerceAtLeast(16f)), cx, labelY, text)
            }
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
        canvas.save()
        canvas.clipRect(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.bottom - 2f)
        // 卡被压窄时（卡片库加卡 / 小档）整体按比例缩，避免控件跑出卡片。
        val k = (w / MUSIC_CARD_W).coerceIn(0.55f, 1f)

        // ① 来源行（图标 + 名称 + 箭头）—— 卡片左上角
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = (14f * k).coerceAtLeast(11f)
        text.color = Skin.TEXT_DIM
        val srcY = top + 8f
        val iconSize = 18f * k
        val iconCx = x + 26f * k
        val iconCy = srcY + 12f
        val appIcon = mediaSourceIcon
        if (appIcon != null && appIcon.width > 0 && appIcon.height > 0) {
            val half = iconSize / 2f
            val box = RectF(iconCx - half, iconCy - half, iconCx + half, iconCy + half)
            val shader = if (sourceIconShaderBmp === appIcon && sourceIconShader != null) {
                sourceIconShader!!
            } else {
                BitmapShader(appIcon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    sourceIconShaderBmp = appIcon
                    sourceIconShader = it
                }
            }
            bitmapMatrix.reset()
            bitmapMatrix.setScale(box.width() / appIcon.width.toFloat(), box.height() / appIcon.height.toFloat())
            bitmapMatrix.postTranslate(box.left, box.top)
            shader.setLocalMatrix(bitmapMatrix)
            bitmapPaint.shader = shader
            canvas.drawRoundRect(box, iconSize * 0.22f, iconSize * 0.22f, bitmapPaint)
        } else {
            fill.color = MediaSources.brandColor(mediaSource)
            com.cardesk.launcher.ui.widget.MediaSourceIcons.drawFallback(
                canvas, mediaSourceIconKind, iconCx, iconCy, iconSize * 0.5f, fill.color,
            )
        }
        text.color = Skin.TEXT_DIM
        val srcMaxW = (w - 64f * k).coerceAtLeast(36f)
        val shownSource = clipLabel(mediaSource, srcMaxW)
        canvas.drawText(shownSource, x + 40f * k, srcY + 18f, text)
        val srcNameW = text.measureText(shownSource)
        stroke.color = Skin.TEXT_DIM
        stroke.strokeWidth = 1.6f
        val chx = x + 40f * k + srcNameW + 6f * k
        val chy = srcY + 12f
        hits["source"] = RectF(x + 14f * k, srcY, (chx + 14f * k).coerceAtMost(rect.right - 8f), srcY + 30f)
        hits["musicCard"] = RectF(rect)
        withPress(canvas, "source") {
            canvas.drawLine(chx, chy, chx + 5f, chy + 5f, stroke)
            canvas.drawLine(chx + 5f, chy + 5f, chx + 10f * k, chy, stroke)
        }

        // ② 封面与正文区在来源行下方垂直居中（小尺寸变窄时封面不再偏上、底部不再空出一大截）
        val bodyTop = top + 34f
        val bodyBottom = (top + h - 12f).coerceAtLeast(bodyTop + 48f)
        val bodyH = bodyBottom - bodyTop
        val coverSize = minOf(84f * k, bodyH - 4f).coerceIn(48f, 84f)
        val coverTop = bodyTop + (bodyH - coverSize) / 2f
        val padX = (22f * k).coerceIn(12f, 22f)
        val cover = RectF(x + padX, coverTop, x + padX + coverSize, coverTop + coverSize)
        val coverRadius = (20f * k).coerceIn(12f, 22f)
        val playR = MUSIC_PLAY_RADIUS * k
        val bx = x + w - padX - playR
        val by = cover.centerY()

        val art = mediaArtwork
        hits["musicCover"] = cover
        withPress(canvas, "musicCover") {
            if (art != null && art.width > 0 && art.height > 0) {
                val shader = if (coverArtShaderBmp === art && coverArtShader != null) {
                    coverArtShader!!
                } else {
                    BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                        coverArtShaderBmp = art
                        coverArtShader = it
                    }
                }
                bitmapMatrix.reset()
                bitmapMatrix.setScale(cover.width() / art.width.toFloat(), cover.height() / art.height.toFloat())
                bitmapMatrix.postTranslate(cover.left, cover.top)
                shader.setLocalMatrix(bitmapMatrix)
                bitmapPaint.shader = shader
                canvas.drawRoundRect(cover, coverRadius, coverRadius, bitmapPaint)
            } else {
                canvas.drawRoundRect(cover, coverRadius, coverRadius, Paint(fill).apply {
                    color = chipFill()
                })
                val noteScale = (coverSize / 84f).coerceIn(0.65f, 1f)
                stroke.color = Skin.TEXT_DIM
                stroke.strokeWidth = (3f * noteScale).coerceAtLeast(2f)
                canvas.drawLine(
                    cover.centerX() + 4f * noteScale, cover.centerY() - 14f * noteScale,
                    cover.centerX() + 4f * noteScale, cover.centerY() + 6f * noteScale,
                    stroke,
                )
                canvas.drawCircle(
                    cover.centerX() - 2f * noteScale,
                    cover.centerY() + 11f * noteScale,
                    5.5f * noteScale,
                    stroke,
                )
                canvas.drawLine(
                    cover.centerX() + 4f * noteScale, cover.centerY() - 14f * noteScale,
                    cover.centerX() + 13f * noteScale, cover.centerY() - 10f * noteScale,
                    stroke,
                )
            }
        }

        // ④ 文本列
        val tx = cover.right + (12f * k).coerceAtLeast(8f)
        val ringR = MusicCardVisuals.ringRadius(MUSIC_RING_SIZE, MUSIC_RING_STROKE) * k
        val textColWMax = (bx - ringR - (10f * k).coerceAtLeast(6f) - tx).coerceAtLeast(24f)

        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = (22f * k).coerceIn(15f, 22f)
        val titleText = clipLabel(mediaTitle, textColWMax)
        val titleW = text.measureText(titleText)
        text.typeface = Typeface.DEFAULT
        text.textSize = (14f * k).coerceIn(11f, 14f)
        val artistText = clipLabel(mediaSubtitle, textColWMax)
        val artistW = text.measureText(artistText)
        val textEdge = tx + maxOf(titleW, artistW)

        // ⑤ 收藏心形（极窄卡避免压住封面与标题）
        val favSize = MUSIC_FAV_ICON_SIZE * k
        val favRightBound = bx - playR - MUSIC_FAV_GAP * k
        val favRect = if (favoriteSupported() && favRightBound - tx >= favSize + 36f) {
            val leftBound = textEdge + MUSIC_FAV_TEXT_GAP * k
            val avail = (favRightBound - leftBound).coerceAtLeast(0f)
            val actual = minOf(favSize, avail)
            val fl = (if (avail >= favSize * 0.6f) {
                leftBound + (avail - actual) / 2f
            } else {
                favRightBound - favSize
            }).coerceAtLeast(tx + 32f)
            val minTop = bodyTop + 2f
            val maxTop = (bodyBottom - favSize).coerceAtLeast(minTop)
            val ft = (by - favSize / 2f).coerceIn(minTop, maxTop)
            RectF(fl, ft, fl + favSize, ft + favSize)
        } else {
            null
        }

        val textColW = if (favRect != null) {
            minOf(textColWMax, favRect.left - MUSIC_FAV_TEXT_GAP * k - tx).coerceAtLeast(24f)
        } else {
            textColWMax
        }

        // 标题与副标题围绕封面中线对称排版
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = (22f * k).coerceIn(15f, 22f)
        text.color = Skin.TEXT
        val titleFinal = clipLabel(mediaTitle, textColW)
        val titleWFinal = text.measureText(titleFinal)
        canvas.drawText(titleFinal, tx, by - 5f, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = (14f * k).coerceIn(11f, 14f)
        text.color = Skin.TEXT_SOFT
        val artistFinal = clipLabel(mediaSubtitle, textColW)
        val artistWFinal = text.measureText(artistFinal)
        canvas.drawText(artistFinal, tx, by + 19f, text)

        musicTextRight = tx + maxOf(titleWFinal, artistWFinal)

        // ⑥ 收藏心形
        favRect?.let { r ->
            hits[FAVORITE_HIT_KEY] = r
            val filled = favoriteState.known && favoriteState.isFavorited
            val color = when {
                filled -> accentColor()
                favoriteState.known -> Skin.TEXT_DIM
                else -> Skin.TEXT_SOFT
            }
            val icon = favoriteIcon(filled, color)
            withPress(canvas, FAVORITE_HIT_KEY) {
                icon.setBounds(
                    r.left.toInt(), r.top.toInt(),
                    r.right.toInt(), r.bottom.toInt(),
                )
                icon.draw(canvas)
            }
        }

        // 进度环 + 播放按钮
        drawMusicRing(canvas, bx, by, k)
        hits[PLAY_HIT_KEY] = RectF(bx - playR, by - playR, bx + playR, by + playR)
        withPress(canvas, PLAY_HIT_KEY) {
            canvas.drawCircle(
                bx, by, playR,
                Paint(fill).apply { color = if (Skin.isNight) Skin.surfaceStrong else 0xFFFFFFFF.toInt() },
            )
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
        }
        canvas.restore()
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
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapMatrix = Matrix()
    private var sourceIconShaderBmp: Bitmap? = null
    private var sourceIconShader: BitmapShader? = null
    private var coverArtShaderBmp: Bitmap? = null
    private var coverArtShader: BitmapShader? = null
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawCoverGlow(canvas: Canvas, rect: RectF) {
        val accent = accentColor()
        val shader = glowShaderFor(rect, accent) ?: return
        glowPaint.shader = shader
        canvas.drawRoundRect(rect, GLASS_RADIUS, GLASS_RADIUS, glowPaint)
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
     * 快捷车控卡（设计板 s01 左下第 3 张，自然宽 338×150）。
     *
     * 支持用户通过设置页自定义 1~4 个一键车控槽位（默认：主驾加热 / 主驾通风 / 最大风挡）。
     */
    private fun drawClimateCard(canvas: Canvas, rect: RectF) {
        val x = rect.left
        val top = rect.top
        card(canvas, rect)
        canvas.save()
        canvas.clipRect(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.bottom - 2f)
        cardTitle(canvas, rect, "快捷车控", size = 16f, baseline = 23f)

        val controls = com.cardesk.launcher.core.SceneQuickControls.normalize(quickControls)
        val n = controls.size
        val k = (rect.width() / CLIMATE_CARD_W).coerceIn(0.55f, 1.25f)
        val pad = (CLIMATE_CARD_PAD * k).coerceIn(10f, CLIMATE_CARD_PAD)
        val gap = (CLIMATE_SEAT_GAP * k).coerceIn(6f, CLIMATE_SEAT_GAP)
        val contentTop = 34f
        val seatTop = top + contentTop
        val availH = (rect.height() - contentTop - pad).coerceAtLeast(56f)

        // 4 项且卡片极窄（< 260px）时自动切 2×2 网格，避免单行挤爆溢出。
        val grid2x2 = n == 4 && rect.width() < 260f
        if (grid2x2) {
            val gPad = 10f
            val gGap = 6f
            val cellW = ((rect.width() - gPad * 2f - gGap) / 2f).coerceAtLeast(32f)
            val cellH = ((rect.height() - contentTop - gPad - gGap) / 2f).coerceAtLeast(36f)
            val iconK = minOf(cellW / 80f, cellH / 54f).coerceIn(0.45f, 0.78f)
            val chipRadius = 10f
            controls.forEachIndexed { idx, id ->
                val col = idx % 2
                val row = idx / 2
                val cx = x + gPad + col * (cellW + gGap)
                val cy = seatTop + row * (cellH + gGap)
                val r = RectF(cx, cy, cx + cellW, cy + cellH)
                val hitKey = com.cardesk.launcher.core.SceneQuickControls.hitKeyFor(id)
                hits[hitKey] = r
                withPress(canvas, hitKey) {
                    drawQuickControlChip(canvas, r, id, k * 0.82f, iconK, chipRadius, compact = true)
                }
            }
        } else {
            val seatW = ((rect.width() - pad * 2f - gap * (n - 1)) / n.toFloat()).coerceAtLeast(24f)
            val iconK = minOf(k.coerceAtLeast(0.76f), availH / 96f, seatW / 64f).coerceIn(0.54f, 1.15f)
            val chipRadius = (18f * k).coerceIn(10f, 18f)
            controls.forEachIndexed { idx, id ->
                val cx = x + pad + idx * (seatW + gap)
                val r = RectF(cx, seatTop, cx + seatW, seatTop + availH)
                val hitKey = com.cardesk.launcher.core.SceneQuickControls.hitKeyFor(id)
                hits[hitKey] = r
                withPress(canvas, hitKey) {
                    drawQuickControlChip(canvas, r, id, k, iconK, chipRadius, compact = false)
                }
            }
        }
        canvas.restore()
    }

    /** 绘制单个自定义快捷车控按钮（含全部 19 项一键车控的图标、激活态与三档指示）。 */
    private fun drawQuickControlChip(
        canvas: Canvas,
        r: RectF,
        id: String,
        k: Float,
        iconK: Float,
        chipRadius: Float,
        compact: Boolean,
    ) {
        val s = state
        when (id) {
            com.cardesk.launcher.core.SceneQuickControls.SEAT_HEAT_DRIVER -> {
                seat(canvas, r.left, r.top, r.width(), r.height(), "主驾加热", s?.seatHeatDriver ?: 0, Skin.WARN, k, iconK, chipRadius, compact)
                return
            }
            com.cardesk.launcher.core.SceneQuickControls.SEAT_VENT_DRIVER -> {
                seat(canvas, r.left, r.top, r.width(), r.height(), "主驾通风", s?.seatVentDriver ?: 0, Skin.ACCENT_LIGHT, k, iconK, chipRadius, compact)
                return
            }
            com.cardesk.launcher.core.SceneQuickControls.SEAT_HEAT_PASSENGER -> {
                seat(canvas, r.left, r.top, r.width(), r.height(), "副驾加热", s?.seatHeatPassenger ?: 0, Skin.WARN, k, iconK, chipRadius, compact)
                return
            }
            com.cardesk.launcher.core.SceneQuickControls.SEAT_VENT_PASSENGER -> {
                seat(canvas, r.left, r.top, r.width(), r.height(), "副驾通风", s?.seatVentPassenger ?: 0, Skin.ACCENT_LIGHT, k, iconK, chipRadius, compact)
                return
            }
        }

        // 通用开关 / 动作按钮
        val active: Boolean
        val activeColor: Int
        val label: String
        when (id) {
            com.cardesk.launcher.core.SceneQuickControls.MAX_FAN -> {
                active = s?.power == true && (s.fanLevel ?: 0) >= 7
                activeColor = Skin.ACCENT
                label = "最大风挡"
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_POWER -> {
                active = s?.power == true
                activeColor = Skin.ACCENT
                label = "空调电源"
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_AC -> {
                active = s?.ac == true
                activeColor = Skin.ACCENT
                label = "A/C 制冷"
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_AUTO -> {
                active = s?.auto == true
                activeColor = Skin.ACCENT
                label = "AUTO 自动"
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_CIRC -> {
                val inner = (s?.circulation ?: 1) == 0
                active = inner
                activeColor = Skin.ACCENT
                label = if (inner) "内循环" else "外循环"
            }
            com.cardesk.launcher.core.SceneQuickControls.DEFROST_FRONT -> {
                active = s?.defrostFront == true
                activeColor = Skin.WARN
                label = "前除霜"
            }
            com.cardesk.launcher.core.SceneQuickControls.DEFROST_REAR -> {
                active = s?.defrostRear == true
                activeColor = Skin.WARN
                label = "后除霜"
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_SYNC -> {
                active = s?.tempSynced == true
                activeColor = Skin.ACCENT
                label = "双区同步"
            }
            com.cardesk.launcher.core.SceneQuickControls.CAM_360 -> {
                active = quickCam360On
                activeColor = Skin.ACCENT
                label = "360 全景"
            }
            com.cardesk.launcher.core.SceneQuickControls.WINDOWS_CLOSE -> {
                active = false
                activeColor = Skin.ACCENT
                label = "一键关窗"
            }
            com.cardesk.launcher.core.SceneQuickControls.WINDOWS_OPEN -> {
                active = false
                activeColor = Skin.ACCENT
                label = "一键透气"
            }
            com.cardesk.launcher.core.SceneQuickControls.ISS_TOGGLE -> {
                active = quickIssOn == true
                activeColor = Skin.ACCENT
                label = if (quickIssOn == false) "启停已关" else "自动启停"
            }
            com.cardesk.launcher.core.SceneQuickControls.DRIVE_MODE_CYCLE -> {
                val raw = info?.driveModeRaw
                val modeShort = (info?.driveModeLabel
                    ?: raw?.let { com.cardesk.launcher.vehicle.DriveMode.shortLabelForValue(it) })
                    ?.takeIf { it.isNotBlank() && it != com.cardesk.launcher.vehicle.DriveMode.UNKNOWN_LABEL }
                active = modeShort != null
                activeColor = Skin.ACCENT
                label = if (modeShort != null) "驾驶·$modeShort" else "驾驶模式"
            }
            com.cardesk.launcher.core.SceneQuickControls.AMBIENT_TOGGLE -> {
                active = quickAmbientOn
                activeColor = Skin.ACCENT
                label = "氛围灯"
            }
            com.cardesk.launcher.core.SceneQuickControls.HOTSPOT_TOGGLE -> {
                active = quickHotspotOn
                activeColor = Skin.ACCENT
                label = "车载热点"
            }
            else -> {
                active = false
                activeColor = Skin.ACCENT
                label = com.cardesk.launcher.core.SceneQuickControls.defOf(id)?.label ?: id
            }
        }

        val bg = if (active) Skin.ACCENT_SOFT else chipFill()
        canvas.drawRoundRect(r, chipRadius, chipRadius, Paint(fill).apply { color = bg })
        val tint = if (active) activeColor else Skin.TEXT_SOFT
        val cx = r.centerX()
        val iconCy = r.top + (if (compact) r.height() * 0.38f else r.height() * 0.30f + 2f)
        drawQuickControlIcon(canvas, id, cx, iconCy, iconK, tint, active)

        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = (if (compact) 11.5f else 15f * k).coerceIn(10.5f, 15f)
        text.color = tint
        val labelY = r.top + (if (compact) r.height() * 0.82f else r.height() * 0.73f)
        canvas.drawText(fitText(label, r.width() - 8f * k.coerceAtLeast(0.7f)), cx, labelY, text)
    }

    /** 绘制非座椅类 15 项一键车控的矢量线稿图标（风格与座椅/风挡图标统一）。 */
    private fun drawQuickControlIcon(
        canvas: Canvas,
        id: String,
        cx: Float,
        cy: Float,
        s: Float,
        color: Int,
        active: Boolean,
    ) {
        stroke.color = color
        stroke.strokeWidth = (2f * s).coerceAtLeast(1.3f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        when (id) {
            com.cardesk.launcher.core.SceneQuickControls.MAX_FAN -> {
                val top = cy - 14.5f * s
                canvas.drawRoundRect(RectF(cx - 16f * s, top, cx + 16f * s, top + 13f * s), 3f * s, 3f * s, stroke)
                for (i in 0..2) {
                    val y = top + 19f * s + i * 5f * s
                    canvas.drawLine(cx - 13f * s, y, cx + 13f * s, y, stroke)
                }
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_POWER -> {
                val r = 13f * s
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -55f, 290f, false, stroke)
                canvas.drawLine(cx, cy - r - 2f * s, cx, cy - 1f * s, stroke)
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_AC -> {
                text.textAlign = Paint.Align.CENTER
                text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                text.textSize = (18f * s).coerceAtLeast(11f)
                text.color = color
                canvas.drawText("A/C", cx, cy + 6.5f * s, text)
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_AUTO -> {
                val rw = 24f * s
                val rh = 12f * s
                canvas.drawRoundRect(RectF(cx - rw, cy - rh, cx + rw, cy + rh), 6f * s, 6f * s, stroke)
                text.textAlign = Paint.Align.CENTER
                text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                text.textSize = (12.5f * s).coerceAtLeast(9.5f)
                text.color = color
                canvas.drawText("AUTO", cx, cy + 4.5f * s, text)
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_CIRC -> {
                // 简笔车廓 + 内/外循环箭头
                val bw = 18f * s
                val bh = 10f * s
                canvas.drawRoundRect(RectF(cx - bw, cy - bh * 0.4f, cx + bw, cy + bh), 4f * s, 4f * s, stroke)
                if (active) {
                    // 内循环 U 形回流箭
                    val ar = 6f * s
                    canvas.drawArc(RectF(cx - ar, cy - 3f * s, cx + ar, cy + 7f * s), -90f, 240f, false, stroke)
                    canvas.drawLine(cx - 2f * s, cy - 3f * s, cx + 2f * s, cy - 6f * s, stroke)
                } else {
                    // 外循环由外向内箭
                    canvas.drawLine(cx - 10f * s, cy - 13f * s, cx + 4f * s, cy + 3f * s, stroke)
                    canvas.drawLine(cx + 4f * s, cy + 3f * s, cx - 2f * s, cy + 3f * s, stroke)
                    canvas.drawLine(cx + 4f * s, cy + 3f * s, cx + 4f * s, cy - 3f * s, stroke)
                }
            }
            com.cardesk.launcher.core.SceneQuickControls.DEFROST_FRONT -> {
                // 弧形前挡 + 三道热浪
                val p = Path().apply {
                    moveTo(cx - 16f * s, cy + 7f * s)
                    lineTo(cx - 11f * s, cy - 10f * s)
                    quadTo(cx, cy - 15f * s, cx + 11f * s, cy - 10f * s)
                    lineTo(cx + 16f * s, cy + 7f * s)
                    quadTo(cx, cy + 3f * s, cx - 16f * s, cy + 7f * s)
                }
                canvas.drawPath(p, stroke)
                for (dx in -1..1) {
                    val wx = cx + dx * 6.5f * s
                    canvas.drawLine(wx, cy + 13f * s, wx + 1.5f * s, cy - 2f * s, stroke)
                }
            }
            com.cardesk.launcher.core.SceneQuickControls.DEFROST_REAR -> {
                canvas.drawRoundRect(RectF(cx - 15f * s, cy - 10f * s, cx + 15f * s, cy + 8f * s), 3f * s, 3f * s, stroke)
                for (dx in -1..1) {
                    val wx = cx + dx * 6.5f * s
                    canvas.drawLine(wx, cy + 13f * s, wx + 1.5f * s, cy - 3f * s, stroke)
                }
            }
            com.cardesk.launcher.core.SceneQuickControls.HVAC_SYNC -> {
                val r = 11f * s
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -35f, 150f, false, stroke)
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 145f, 150f, false, stroke)
                canvas.drawLine(cx + r - 1f * s, cy - 5f * s, cx + r + 3f * s, cy - 1f * s, stroke)
                canvas.drawLine(cx - r + 1f * s, cy + 5f * s, cx - r - 3f * s, cy + 1f * s, stroke)
            }
            com.cardesk.launcher.core.SceneQuickControls.CAM_360 -> {
                canvas.drawRoundRect(RectF(cx - 17f * s, cy - 11f * s, cx + 17f * s, cy + 11f * s), 6f * s, 6f * s, stroke)
                text.textAlign = Paint.Align.CENTER
                text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                text.textSize = (13f * s).coerceAtLeast(10f)
                text.color = color
                canvas.drawText("360", cx, cy + 4.8f * s, text)
            }
            com.cardesk.launcher.core.SceneQuickControls.WINDOWS_CLOSE,
            com.cardesk.launcher.core.SceneQuickControls.WINDOWS_OPEN -> {
                // 车窗梯形轮廓 + 升/降箭头
                val p = Path().apply {
                    moveTo(cx - 14f * s, cy + 11f * s)
                    lineTo(cx - 14f * s, cy - 2f * s)
                    lineTo(cx - 4f * s, cy - 12f * s)
                    lineTo(cx + 14f * s, cy - 12f * s)
                    lineTo(cx + 14f * s, cy + 11f * s)
                    close()
                }
                canvas.drawPath(p, stroke)
                val up = id == com.cardesk.launcher.core.SceneQuickControls.WINDOWS_CLOSE
                canvas.drawLine(cx + 1f * s, cy - 5f * s, cx + 1f * s, cy + 6f * s, stroke)
                if (up) {
                    canvas.drawLine(cx - 3.5f * s, cy - 0.5f * s, cx + 1f * s, cy - 5f * s, stroke)
                    canvas.drawLine(cx + 5.5f * s, cy - 0.5f * s, cx + 1f * s, cy - 5f * s, stroke)
                } else {
                    canvas.drawLine(cx - 3.5f * s, cy + 1.5f * s, cx + 1f * s, cy + 6f * s, stroke)
                    canvas.drawLine(cx + 5.5f * s, cy + 1.5f * s, cx + 1f * s, cy + 6f * s, stroke)
                }
            }
            com.cardesk.launcher.core.SceneQuickControls.ISS_TOGGLE -> {
                val r = 13f * s
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -40f, 280f, false, stroke)
                text.textAlign = Paint.Align.CENTER
                text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                text.textSize = (14f * s).coerceAtLeast(10f)
                text.color = color
                canvas.drawText("A", cx, cy + 5f * s, text)
            }
            com.cardesk.launcher.core.SceneQuickControls.DRIVE_MODE_CYCLE -> {
                val r = 14f * s
                canvas.drawArc(RectF(cx - r, cy - r + 3f * s, cx + r, cy + r + 3f * s), 180f, 180f, false, stroke)
                canvas.drawLine(cx, cy + 5f * s, cx + 7.5f * s, cy - 5.5f * s, stroke)
                canvas.drawCircle(cx, cy + 5f * s, 2.2f * s, Paint(fill).apply { this.color = color })
            }
            com.cardesk.launcher.core.SceneQuickControls.AMBIENT_TOGGLE -> {
                val r = 8f * s
                canvas.drawCircle(cx, cy, r, stroke)
                for (deg in 0 until 360 step 60) {
                    val rad = Math.toRadians(deg.toDouble())
                    val cos = kotlin.math.cos(rad).toFloat()
                    val sin = kotlin.math.sin(rad).toFloat()
                    canvas.drawLine(cx + cos * 11f * s, cy + sin * 11f * s, cx + cos * 15f * s, cy + sin * 15f * s, stroke)
                }
            }
            com.cardesk.launcher.core.SceneQuickControls.HOTSPOT_TOGGLE -> {
                canvas.drawCircle(cx, cy + 6f * s, 2.5f * s, Paint(fill).apply { this.color = color })
                val r1 = 8f * s
                val r2 = 14f * s
                canvas.drawArc(RectF(cx - r1, cy + 6f * s - r1, cx + r1, cy + 6f * s + r1), 215f, 110f, false, stroke)
                canvas.drawArc(RectF(cx - r2, cy + 6f * s - r2, cx + r2, cy + 6f * s + r2), 215f, 110f, false, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    /**
     * 快捷车控 chip 的底色：主文字色 9.4%。
     *
     * 画板 `Main.png` 的 chip 是 #E7EAEA（卡面 #FFFFFF），
     * 用 `Skin.GLASS_FILL`（近白）会读成「又一块白板」，与画板的灰底不一致。
     */
    private fun chipFill(): Int = (Skin.TEXT and 0x00FFFFFF) or (0x12 shl 24)

    /** 座椅位：图标 + 文案 + 3 段进度；[k] 是卡片宽度系数，[iconK] 控制图标等比缩放。 */
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
        iconK: Float = k,
        chipRadius: Float = (18f * k).coerceIn(12f, 18f),
        compact: Boolean = false,
    ) {
        val r = RectF(x, top, x + w, top + h)
        // 浅灰底（画板 chip 比卡面暗一档），不是白玻璃。
        canvas.drawRoundRect(r, chipRadius, chipRadius, Paint(fill).apply { this.color = chipFill() })
        // 座椅轮廓：居中摆在按钮上半区，避免变窄时图标挤到顶端。
        stroke.color = if (level > 0) tint else Skin.TEXT_SOFT
        stroke.strokeWidth = (2f * iconK).coerceAtLeast(1.3f)
        val sw = 26f * iconK
        val sh = 30f * iconK
        val cx = r.centerX()
        val sy = top + ((h * (if (compact) 0.48f else 0.56f)) - sh) / 2f + 2f
        canvas.drawRoundRect(RectF(cx - sw / 2f, sy, cx + sw / 2f, sy + sh), 8f * iconK, 8f * iconK, stroke)
        canvas.drawLine(cx - sw / 2f - 5f * iconK, sy + 6f * iconK, cx - sw / 2f, sy + sh - 4f * iconK, stroke)
        canvas.drawLine(cx + sw / 2f + 5f * iconK, sy + 6f * iconK, cx + sw / 2f, sy + sh - 4f * iconK, stroke)
        // 文案按按钮实际高度落基线，与「最大风挡」平齐。
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = (if (compact) 11.5f else 15f * k).coerceIn(10.5f, 15f)
        text.color = if (level > 0) tint else Skin.TEXT_SOFT
        val labelY = top + h * (if (compact) 0.78f else 0.73f)
        // 卡片被压得很窄时，长标签按卡宽裁剪，避免文字跑出卡外。
        canvas.drawText(fitText(label, w - 8f * k.coerceAtLeast(0.7f)), cx, labelY, text)
        val dashW = (12f * iconK).coerceAtMost((w - 16f) / 3.6f).coerceAtLeast(5f)
        val dashH = (3.2f * iconK).coerceAtLeast(2.2f)
        val dashGap = (4f * iconK).coerceAtLeast(2.2f)
        val dashCy = top + h - (if (compact) 6f else 12f)
        val totalW = 3 * dashW + 2 * dashGap
        for (i in 0 until 3) {
            fill.color = if (i < level) tint else CardStyle.scaleAlpha(Skin.TEXT_SOFT, 0.45f)
            val left = cx - totalW / 2f + i * (dashW + dashGap)
            canvas.drawRoundRect(left, dashCy - dashH / 2f, left + dashW, dashCy + dashH / 2f,
                dashH / 2f, dashH / 2f, fill)
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
        canvas.save()
        canvas.clipRect(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.bottom - 2f)

        val k = (w / 266f).coerceIn(0.62f, 1.1f)
        val padX = (22f * k).coerceIn(14f, 22f)
        val innerW = (w - padX * 2f).coerceAtLeast(48f)

        // 标题「续航」—— 与下方数值、油量条左缘严格对齐
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 16f
        text.color = Skin.TEXT_DIM
        canvas.drawText("续航", x + padX, top + 30f, text)

        // 数值 + 单位（窄卡或四位数续航时自动降字号，确保 km 不压右边缘）
        val range = info?.rangeKm?.takeIf { it >= 0 }
        val shown = range?.toString() ?: "--"
        var valueSp = (56f * k).coerceIn(30f, 56f)
        val unitSp = (18f * k).coerceIn(12f, 18f)
        text.typeface = Typeface.DEFAULT
        text.textSize = unitSp
        val unitW = text.measureText("km")
        val maxValueW = (innerW - unitW - 6f).coerceAtLeast(24f)
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        while (valueSp > 24f) {
            text.textSize = valueSp
            if (text.measureText(shown) <= maxValueW) break
            valueSp -= 2f
        }
        text.textSize = valueSp
        text.color = rangeValueColor(range)
        val shownW = text.measureText(shown)
        canvas.drawText(shown, x + padX, top + 80f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = unitSp
        text.color = Skin.TEXT_DIM
        canvas.drawText("km", x + padX + shownW + 6f, top + 80f, text)

        // 油量条 6px（未填充轨道走 chipFill 浅灰底，避免日间白底上画近白轨道看不见）
        val barY = top + 100f
        val barX = x + padX
        val barW = innerW
        fill.color = chipFill()
        canvas.drawRoundRect(RectF(barX, barY, barX + barW, barY + 6f), 3f, 3f, fill)
        val fuel = fuelPercent
        if (fuel != null && barW > 0f) {
            val ratio = (fuel.coerceIn(0, 100) / 100f)
            fill.color = Skin.ACCENT
            canvas.drawRoundRect(RectF(barX, barY, barX + barW * ratio, barY + 6f), 3f, 3f, fill)
        }

        // 油量文案
        text.typeface = Typeface.DEFAULT
        text.textSize = (14f * k).coerceIn(11f, 14f)
        text.color = Skin.TEXT_DIM
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(
            fitText(fuelSummaryText(info?.fuelLiters, fuel), innerW),
            x + padX, top + 131f, text,
        )
        canvas.restore()
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
        if (carStatusInBottomRow) return
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

        val targetW = CAR_STATUS_CARD_W
        val targetH = if (carStatusCardCompact) cardH else CAR_STATUS_CARD_H
        val defaultLeft = rightEnd - CAR_STATUS_CARD_RIGHT_INSET - targetW
        val defaultTop = if (carStatusCardCompact) (cardTop - 8f - targetH) else CAR_STATUS_CARD_TOP
        val rawLeft = if (dragCarStatus) dragCarStatusX else if (carStatusCardPosX >= 0f) carStatusCardPosX else defaultLeft
        val rawTop = if (dragCarStatus) dragCarStatusY else if (carStatusCardPosY >= 0f) carStatusCardPosY else defaultTop
        val clampedLeft = rawLeft.coerceIn(left, (rightEnd - targetW).coerceAtLeast(left))
        val clampedTop = rawTop.coerceIn(84f, (cardTop - 8f - targetH).coerceAtLeast(84f))
        val r = RectF(clampedLeft, clampedTop, clampedLeft + targetW, clampedTop + targetH)
        // 整卡可点：点开「车辆状态」面板。
        val cardPressed = if (dragCarStatus) false else beginPress(canvas, CAR_STATUS_CARD_HIT_KEY, r)
        val liftingCarStatus = dragCarStatus && dragLift > 0f
        if (liftingCarStatus) {
            canvas.save()
            val s = 1f + (MotionSpec.STATE_BUMP - 1f) * dragLift
            canvas.scale(s, s, r.centerX(), r.centerY())
        }
        card(canvas, r)
        hits[CAR_STATUS_CARD_HIT_KEY] = RectF(r)

        if (carStatusCardCompact) {
            drawCarStatusCardCompact(canvas, r, effDoors, hood, effTrunk, i)
            if (dragCarStatus) drawDragOutline(canvas, r)
            if (liftingCarStatus) canvas.restore()
            if (cardPressed) canvas.restore()
            return
        }

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

        // —— 车模区（第182轮竖放）：俯视资产已是竖版，图区改 246×300、**水平居中**；
        // 03/05 的旧 3/4 视角仍用 356×292 的旧区。比例一律按 model.json 的 slot 算，不硬编码。——
        // 第244轮（用户反馈）：车模**居中**，四轮胎压贴到各自车轮旁边，
        // 「全车已关」挪到卡片下部居中 —— 之前车模靠左、胎压挤在底部一行，
        // 看着像「左边一张图 + 底下一排数」，和画板的「整车为中心」不符。
        val boxW = if (topDown) CAR_STATUS_CAR_BOX_W else CAR_STATUS_CAR_W
        val boxH = if (topDown) CAR_STATUS_CAR_BOX_H else CAR_STATUS_CAR_H_LEGACY
        val boxTop = if (topDown) CAR_STATUS_CAR_BOX_TOP else CAR_STATUS_CAR_TOP_LEGACY
        val boxX = r.centerX() - boxW / 2f
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
        // cardtop 资产右侧有一条烘焙柔影（资产 x210..222、alpha 16..35，见
        // VehiclePage.CAR3D_CLIP_RIGHT 注释）。车况卡里它只有 5px 宽，但在白卡上仍是一条
        // 硬边细线；裁掉车身右缘以外那一条，和车辆页保持同一处理。
        val clipSave = canvas.save()
        canvas.clipRect(imageRect.left, imageRect.top, imageRect.left + imageRect.width() * CAR3D_CLIP_RIGHT, imageRect.bottom)
        val car3dPainted = car3d.paint(canvas, context, cardView, imageRect, fit = false)
        canvas.restoreToCount(clipSave)
        if (!car3dPainted) {
            val frame = CarTopPainter.frame(carArea.centerX(), carArea.centerY(), CAR_STATUS_MODEL_W, CAR_STATUS_MODEL_H)
            CarTopPainter.draw(canvas, frame, CarTopGeometryFactory.of(carModel), carColors)
        }

        // —— 状态文字：卡片下部居中（全关时是「✓ 全车已关」）——
        val items = CarStatus.items(effDoors, effWindows, hood, effTrunk)
        drawCarStatusBottomText(canvas, r, items)

        lastCarImageRect = RectF(imageRect)
        drawTireBesideWheels(canvas, r, imageRect, i)
        if (dragCarStatus) drawDragOutline(canvas, r)
        if (liftingCarStatus) canvas.restore()
        if (cardPressed) canvas.restore()
    }

    /**
     * 车况卡小尺寸样式（与底部卡片等高 388×150）：
     * - 车模旋转 -90° 朝向左侧，仅展示车门/盖开关状态，不显示车窗开关；
     * - 四轮胎压分别显示在对应车轮位置旁（头朝左：右上=右后、右下=左后、左上=右前、左下=左前）。
     */
    private fun drawCarStatusCardCompact(
        canvas: Canvas,
        r: RectF,
        effDoors: List<Boolean?>?,
        hood: Boolean?,
        effTrunk: Boolean?,
        i: VehicleInfo,
    ) {
        canvas.save()
        canvas.clipRect(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f)
        val scale = (r.width() / CAR_STATUS_CARD_W).coerceIn(0.58f, 1.05f)
        val padX = (16f * scale).coerceIn(10f, 16f)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 14f
        text.color = Skin.TEXT_DIM
        canvas.drawText("车况", r.left + padX, r.top + 24f, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = Skin.ACCENT
        canvas.drawText("详情 ›", r.right - padX, r.top + 24f, text)
        text.textAlign = Paint.Align.LEFT

        val valueSp = (26f * scale).coerceIn(15f, 26f)
        val unitSp = (14f * scale).coerceIn(10f, 14f)
        val gap = (4f * scale).coerceAtLeast(2f)
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = valueSp
        val sampleValW = text.measureText("2.4")
        text.typeface = Typeface.DEFAULT
        text.textSize = unitSp
        val sampleUnitW = text.measureText("bar")
        val tireColW = sampleValW + gap + sampleUnitW + (8f * scale).coerceAtLeast(4f)

        val assetDir = CarModels.assetDir(carModel)
        val dir = if (Car3dSettings.enabled) assetDir else null
        val topDown = Car3dAssets.topDownOnly(context, assetDir)
        val cardView = if (topDown) Car3dAssets.VIEW_CARDTOP else Car3dSettings.cardView
        val compactPose = com.cardesk.launcher.core.car3d.Car3dDebugPose.pose?.let { p ->
            p.copy(windows = BooleanArray(4) { false })
        } ?: CarPose.of(effDoors, BooleanArray(4) { false }, hood, effTrunk)
        car3d.setPose(context, dir, compactPose, rich = true, view = cardView)

        val carCx = r.centerX()
        val carCy = r.centerY() + 8f
        val availCarW = (r.width() - padX * 2f - tireColW * 2f).coerceIn(68f, 196f)
        val maxBoxH = availCarW
        val maxBoxW = (104f * (availCarW / 150f).coerceIn(0.62f, 1f)).coerceIn(58f, 104f)
        val baseArea = RectF(carCx - maxBoxW / 2f, carCy - maxBoxH / 2f, carCx + maxBoxW / 2f, carCy + maxBoxH / 2f)
        val usedView = Car3dAssets.usedView(context, dir, cardView)
        val slot = Car3dAssets.slotOf(context, dir, usedView)
        val unrotRect = if (slot != null && slot.second > 0) {
            fitIn(baseArea, slot.first.toFloat() / slot.second)
        } else {
            baseArea
        }
        val save = canvas.save()
        canvas.rotate(-90f, carCx, carCy)
        val clipSave = canvas.save()
        canvas.clipRect(
            unrotRect.left,
            unrotRect.top,
            unrotRect.left + unrotRect.width() * CAR3D_CLIP_RIGHT,
            unrotRect.bottom,
        )
        val painted = car3d.paint(canvas, context, cardView, unrotRect, fit = false)
        canvas.restoreToCount(clipSave)
        if (!painted) {
            val frame = CarTopPainter.frame(carCx, carCy, maxBoxW * 0.62f, maxBoxH * 0.85f)
            CarTopPainter.draw(canvas, frame, CarTopGeometryFactory.of(carModel), carColors)
        }
        canvas.restoreToCount(save)

        val visualW = unrotRect.height()
        val visualH = unrotRect.width()
        val carVisualRect = RectF(carCx - visualW / 2f, carCy - visualH / 2f, carCx + visualW / 2f, carCy + visualH / 2f)
        lastCarImageRect = RectF(carVisualRect)

        // 车头朝左时四轮方位：左前(0)=左下，右前(1)=左上，左后(2)=右下，右后(3)=右上。
        val topRowY = r.top + 58f
        val botRowY = r.bottom - 26f
        val sideGap = (8f * scale).coerceAtLeast(4f)
        val wheelSlots = arrayOf(
            Triple(carVisualRect.left - sideGap, botRowY, -1),  // 0 左前
            Triple(carVisualRect.left - sideGap, topRowY, -1),  // 1 右前
            Triple(carVisualRect.right + sideGap, botRowY, +1), // 2 左后
            Triple(carVisualRect.right + sideGap, topRowY, +1), // 3 右后
        )
        val minLeft = r.left + padX
        val maxRight = r.right - padX
        for (k in 0 until 4) {
            val raw = i.tires.getOrNull(k)?.takeIf { !it.isNaN() && it > 0f }
            val bar = raw?.let { it / 100f }
            val abnormal = bar != null && (bar < 2.0f || bar > 2.8f)
            val value = if (bar == null) "--" else String.format(java.util.Locale.US, "%.1f", bar)
            val (edge, cy, dirSide) = wheelSlots[k]

            text.textAlign = Paint.Align.LEFT
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textSize = valueSp
            val vw = text.measureText(value)
            text.typeface = Typeface.DEFAULT
            text.textSize = unitSp
            val uw = text.measureText("bar")
            val totalW = vw + gap + uw
            val rawLeft = if (dirSide < 0) edge - totalW else edge
            val startX = rawLeft.coerceIn(minLeft, (maxRight - totalW).coerceAtLeast(minLeft))

            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textSize = valueSp
            text.color = if (abnormal) Skin.WARN else Skin.TEXT
            canvas.drawText(value, startX, cy, text)

            text.typeface = Typeface.DEFAULT
            text.textSize = unitSp
            text.color = Skin.TEXT_SOFT
            canvas.drawText("bar", startX + vw + gap, cy, text)
        }
        canvas.restore()
    }

    /** 拖拽高亮描边。 */
    private fun drawDragOutline(canvas: Canvas, r: RectF) {
        val prevStyle = text.style
        val prevStroke = text.strokeWidth
        val prevColor = text.color
        text.style = Paint.Style.STROKE
        text.strokeWidth = 2.5f
        text.color = Skin.ACCENT
        canvas.drawRoundRect(r, GLASS_RADIUS, GLASS_RADIUS, text)
        text.style = prevStyle
        text.strokeWidth = prevStroke
        text.color = prevColor
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

    /**
     * 车况卡右侧状态文字（第243轮，按设计板 Main.png 车况卡）。
     *
     * 画板里这块是车模右侧的一列：`✓ 全车已关` / 未关时逐条列位置。
     * 列宽只有 ~146px（卡 388 − 左侧车模 226 − 右内边距 16），
     * 所以按**字符折行**（中文逐字可断行），最多三行，整体在车模中线附近垂直居中。
     */
    /**
     * 车况卡状态文字（第244轮：**卡片下部居中**）。
     *
     * 第174轮时它贴在车模右侧，第243轮按画板改成右半边一列；用户实测反馈
     * 「车模该居中、状态该在下面」，所以现在：车模占卡片中央，
     * 这行字落在卡片下部（胎压之上、卡片下内边之上）水平居中。
     *
     * - 0 处 → 「✓ 全车已关」（弱化色，不加绿）；
     * - 1~2 处（车门/车窗/尾门各算一处）→ 逐个列位置，车门橙、车窗黄、尾门橙；
     * - ≥3 处 → 合并一句（措辞由 [CardSnapshot.statusParts] 给出）。
     *
     * 最多两行：一行放得下就一行，放不下折第二行，两行都居中。
     */
    private fun drawCarStatusBottomText(canvas: Canvas, r: RectF, items: List<CarStatusItem>) {
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

        // 拼成「带颜色的碎片」列表：位置名（告警色）+ 分隔符/后缀（弱化色）。
        class Run(val text: String, val color: Int)
        val runs = ArrayList<Run>(8)
        val allClosed = parts.size == 1 && parts[0].kind == CardSnapshot.StatusKind.NONE
        if (allClosed) {
            runs.add(Run("✓ 全车已关", Skin.TEXT_DIM))
        } else {
            parts.forEachIndexed { i, part ->
                val color = when (part.kind) {
                    CardSnapshot.StatusKind.WINDOW -> Skin.WINDOW_OPEN
                    CardSnapshot.StatusKind.DOOR -> Skin.CAR_STATE_WARN
                    CardSnapshot.StatusKind.GENERIC ->
                        if (doors.isNotEmpty() || trunkOpen) Skin.CAR_STATE_WARN else Skin.WINDOW_OPEN
                    CardSnapshot.StatusKind.NONE -> Skin.TEXT_DIM
                }
                if (i > 0) runs.add(Run("、", Skin.TEXT_DIM))
                runs.add(Run(part.text, color))
            }
            if (parts.isNotEmpty() &&
                parts[0].kind != CardSnapshot.StatusKind.NONE &&
                parts[0].kind != CardSnapshot.StatusKind.GENERIC
            ) {
                runs.add(Run(" 未关", Skin.TEXT_DIM))
            }
        }

        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT
        text.textSize = CAR_STATUS_BOTTOM_TEXT_SP
        val maxW = (r.width() - 36f).coerceAtLeast(40f)
        val lineH = CAR_STATUS_BOTTOM_TEXT_SP + 6f

        // 按字符折行（保留每段颜色）。
        val lines = ArrayList<MutableList<Run>>()
        var line = ArrayList<Run>()
        var lineW = 0f
        for (run in runs) {
            var buf = StringBuilder()
            for (ch in run.text) {
                val cw = text.measureText(ch.toString())
                if (lineW + cw > maxW && lineW > 0f) {
                    if (buf.isNotEmpty()) {
                        line.add(Run(buf.toString(), run.color))
                        buf = StringBuilder()
                    }
                    lines.add(line)
                    line = ArrayList()
                    lineW = 0f
                }
                buf.append(ch)
                lineW += cw
            }
            if (buf.isNotEmpty()) line.add(Run(buf.toString(), run.color))
        }
        if (line.isNotEmpty()) lines.add(line)

        val shown = lines.take(2)
        // 底边基线：卡片下内边往上 [CAR_STATUS_BOTTOM_TEXT_FROM_BOTTOM]；两行时向上让一行。
        var y = r.bottom - CAR_STATUS_BOTTOM_TEXT_FROM_BOTTOM - (shown.size - 1) * lineH
        for (l in shown) {
            val totalW = l.sumOf { text.measureText(it.text).toDouble() }.toFloat()
            var x = r.centerX() - totalW / 2f
            for (run in l) {
                text.color = run.color
                canvas.drawText(run.text, x, y, text)
                x += text.measureText(run.text)
            }
            y += lineH
        }
    }

    /**
     * 车况卡胎压（第244轮：**贴到各自车轮旁边**）。
     *
     * 四个标签按车轮方位摆：左前/右前在车头两侧，左后/右后在车尾两侧；
     * 标签 12px 弱文字在上、数值 22px 粗体 + 单位 11px 在下；
     * 异常胎压只把数值染成琥珀色，不加底色块。
     */
    private fun drawTireBesideWheels(canvas: Canvas, r: RectF, car: RectF, i: VehicleInfo) {
        // 第248轮：用户意见「四块胎压样式不统一 → 去掉位置描述、数字放大、再靠近车模」；
        // 第249轮：「胎压字体再放大 1.5 倍」「单位也要放大，现在比例失调」；
        // 第250轮：「车模适当放大、整体下移」—— 数字 39px 与单位 21px 并排时整组要 107px 宽，
        // 卡宽 388 撑不下更大的车模，所以单位改成**放在数字下方**（同一列内两行）：
        // 组宽只有数字宽度（约 68px），车模就能从 150 放大到 210，也不再顶到卡边。
        val gapX = CAR_STATUS_TIRE_GAP_X
        // 第251轮：用户澄清「前排两个胎压要往上挪一点，不是要贴到车胎上」。
        // 所以横向仍按 rect 边缘摆（离车体留白、左右对称），只把**前**轴比例上调：
        //   前轴 0.18（用户第 251 轮再要求「往上挪一个身位」，从 0.35 再抬 50px）、后轴 0.69。
        // 纵向比例是相对 rect 高度的，车模尺寸再变也不用重算像素。
        val frontY = car.top + car.height() * 0.18f + 14f
        val rearY = car.top + car.height() * 0.69f + 14f
        val anchors = arrayOf(
            Triple(car.left - gapX, frontY, -1),   // 左前
            Triple(car.right + gapX, frontY, +1),  // 右前
            Triple(car.left - gapX, rearY, -1),    // 左后
            Triple(car.right + gapX, rearY, +1),
        )
        for (k in 0 until 4) {
            val raw = i.tires.getOrNull(k)?.takeIf { !it.isNaN() && it > 0f }
            val bar = raw?.let { it / 100f }
            val abnormal = bar != null && (bar < 2.0f || bar > 2.8f)
            val value = if (bar == null) "--" else String.format(java.util.Locale.US, "%.1f", bar)
            val (edge, cy, dir) = anchors[k]

            text.textAlign = Paint.Align.LEFT
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textSize = 39f
            val vw = text.measureText(value)
            text.typeface = Typeface.DEFAULT
            text.textSize = 21f
            val uw = text.measureText("bar")
            // 两行一组：数字在上、单位在下，整组按「数字宽」对齐到贴车侧，并钳制在卡片内边距内防止越界。
            val cardPadX = 16f
            val minLeft = r.left + cardPadX
            val maxRight = r.right - cardPadX
            val groupW = maxOf(vw, uw)
            val rawLeft = if (dir < 0) edge - groupW else edge
            val groupLeft = rawLeft.coerceIn(minLeft, (maxRight - groupW).coerceAtLeast(minLeft))
            val startX = if (dir < 0) groupLeft + (groupW - vw) else groupLeft

            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textSize = 39f
            text.color = if (abnormal) Skin.WARN else Skin.TEXT
            canvas.drawText(value, startX, cy, text)

            text.typeface = Typeface.DEFAULT
            text.textSize = 21f
            text.color = Skin.TEXT_SOFT
            val unitLeft = if (dir < 0) groupLeft + (groupW - uw) else groupLeft
            canvas.drawText("bar", unitLeft, cy + 24f, text)
        }
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
    }

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
            // 颜色取「当前显示中」的值 —— 换选时是**渐变**过去的（见 animateWallpaperDots）。
            fill.color = wallpaperDotColors.getOrElse(i) { 0x73FFFFFF }
            // 命中区**先登记再画**（`hits` 每帧开头是清空的）。
            hits["wp$i"] = RectF(dx, cy + 20f, dx + 30f, cy + 46f)
            // 按下时圆点自己缩一点。
            withPress(canvas, "wp$i") {
                canvas.drawRoundRect(RectF(dx, cy + 28f, dx + 30f, cy + 38f), 5f, 5f, fill)
            }
            dx += 30f + dotGap
        }
    }

    // ---------------------------------------------------------------- 交互

    // -------------------------------------------------------------- 按压反馈
    //
    // 场景页是**一张画布**画完所有东西的 —— 没有子 View 可以挂按压反馈
    // （`Motion.press` 是给 View 用的）。
    // 所以这里照 `ui/shell/DockBarView.kt` 的做法：
    // 自己记「现在按着哪一块」，画的时候把**那一块**缩小，其余照常。

    /** 现在按着的命中区（null = 没按着）。 */
    private var pressedHit: String? = null

    /** 按压的缩放（1 = 没缩）。 */
    private var pressScale = 1f

    /** 按压动画（同一时刻只会有一个）。 */
    private var pressAnimator: ValueAnimator? = null

    /**
     * 按下时命中的块（没有就 null）。
     *
     * 判定顺序必须和 [handleTap] **完全一致** ——
     * 按下的那块要**就是会被点到**的那块，否则会出现「按 A 缩 B」的错位。
     */
    private fun pressKeyAt(x: Float, y: Float): String? {
        for (key in PRESS_HIT_KEYS) {
            val r = hits[key] ?: continue
            if (r.contains(x, y)) return key
        }
        return null
    }

    /** 按下：把按着的那块缩下去。 */
    private fun startPress(key: String?) {
        pressedHit = key
        pressAnimator?.cancel()
        if (key == null) return
        pressAnimator = ValueAnimator.ofFloat(pressScale, MotionSpec.PRESS_SCALE).apply {
            duration = MotionSpec.PRESS_DOWN_DURATION
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    /** 松手：弹回原位。 */
    private fun endPress() {
        pressedHit = null
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, 1f).apply {
            duration = MotionSpec.PRESS_UP_DURATION
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    /**
     * 画一块**会响应按压**的内容。
     *
     * 中心点取命中区的中心 —— 命中区由各绘制函数**先登记再画**
     * （`hits` 每帧开头是清空的，画的时候得先有值）。
     */
    private fun withPress(canvas: Canvas, key: String, block: () -> Unit) {
        val r = hits[key]
        if (pressedHit != key || r == null || pressScale == 1f) {
            block()
            return
        }
        canvas.save()
        canvas.scale(pressScale, pressScale, r.centerX(), r.centerY())
        block()
        canvas.restore()
    }

    /**
     * 把按压缩放**套到画布上**，返回 true 表示结束时需要 `canvas.restore()`。
     *
     * 整张卡那种上百行的内容用它 —— 把上百行包进 lambda 容易改错，
     * 而这里只要在开头套一次、结尾还原一次。
     */
    private fun beginPress(canvas: Canvas, key: String, r: RectF): Boolean {
        if (pressedHit != key || pressScale == 1f) return false
        canvas.save()
        canvas.scale(pressScale, pressScale, r.centerX(), r.centerY())
        return true
    }

    /**
     * 壁纸圆点换选：颜色**渐变**过去。
     *
     * 圆点只有 30×10，颜色一换就是「啪」地跳一下 ——
     * 渐变过去才看得出选中了哪一个。
     */
    private fun animateWallpaperDots(selected: Int) {
        val from = wallpaperDotColors.copyOf()
        Motion.animateProgress(this, MotionSpec.STATE_BUMP_DURATION) { t ->
            for (i in wallpaperDotColors.indices) {
                val target = if (i == selected) 0xFFFFFFFF.toInt() else 0x73FFFFFF
                wallpaperDotColors[i] = MotionSpec.lerpColor(from.getOrElse(i) { target }, target, t)
            }
            invalidate()
        }
    }

    private val cardRects = LinkedHashMap<String, RectF>()
    private var dragId: String? = null
    private var dragMoved = false
    private var dragDx = 0f

    /**
     * 拖起来的卡片「抬起」的程度（0..1）。
     *
     * 以前卡片一被拎起来就是**原样平移**的 —— 看着像贴纸在滑，
     * 不知道是「拿起来了」还是「自己动了」。补一点放大就清楚了。
     */
    private var dragLift = 0f

    /** 抬起动画。 */
    private var dragLiftAnimator: ValueAnimator? = null

    /** 拖拽开始 / 结束时卡片「抬起 / 落下」的动画。 */
    private fun animateDragLift(lifted: Boolean) {
        dragLiftAnimator?.cancel()
        dragLiftAnimator = ValueAnimator.ofFloat(dragLift, if (lifted) 1f else 0f).apply {
            duration = if (lifted) MotionSpec.STATE_BUMP_DURATION else MotionSpec.PRESS_UP_DURATION
            addUpdateListener {
                dragLift = it.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }
    private var dragGrabX = 0f
    private var dragGrabY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downCardId: String? = null
    private var downCarStatusCard = false
    private var dragCarStatus = false
    private var dragCarStatusStartX = 0f
    private var dragCarStatusStartY = 0f
    private var dragCarStatusX = 0f
    private var dragCarStatusY = 0f
    private var dragFreeCard = false
    private var dragFreeStartX = 0f
    private var dragFreeStartY = 0f
    private var dragFreeX = 0f
    private var dragFreeY = 0f
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
        if (downCarStatusCard) {
            val r = hits[CAR_STATUS_CARD_HIT_KEY] ?: return@Runnable
            longPressFired = true
            dragCarStatus = true
            dragGrabX = downX
            dragGrabY = downY
            dragCarStatusStartX = r.left
            dragCarStatusStartY = r.top
            dragCarStatusX = r.left
            dragCarStatusY = r.top
            animateDragLift(true)
            endPress()
            parent?.requestDisallowInterceptTouchEvent(true)
            Logx.event(LogTags.SCENE, "车况卡长按开始拖拽", "x" to downX, "y" to downY)
            invalidate()
            return@Runnable
        }
        val id = downCardId ?: return@Runnable
        longPressFired = true
        dragId = id
        dragMoved = false
        dragGrabX = downX
        dragGrabY = downY
        dragDx = 0f
        if (id in freeCardPos) {
            val rect = cardRects[id]
            dragFreeCard = true
            dragFreeStartX = rect?.left ?: (downX - freeCardWidth(id) / 2f)
            dragFreeStartY = rect?.top ?: (downY - cardH / 2f)
            dragFreeX = dragFreeStartX
            dragFreeY = dragFreeStartY
        } else {
            dragFreeCard = false
        }
        // 卡片被拎起来了 —— 放大一点，让人看出「拿起来了」。
        animateDragLift(true)
        // 拖拽接管了这只手 —— 按压反馈收掉，别让被按的那块一直缩着。
        endPress()
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
                dragCarStatus = false
                dragFreeCard = false
                dragFreeCard = false
                dragDx = 0f
                // 按下反馈：记住按着的是哪一块，画的时候把它缩小。
                startPress(pressKeyAt(x, y))
                downCardId = cardRects.entries.firstOrNull { it.value.contains(x, y) }?.key
                downCarStatusCard = downCardId == null && (hits[CAR_STATUS_CARD_HIT_KEY]?.contains(x, y) == true)
                if (downCardId != null || downCarStatusCard) {
                    main.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCarStatus) {
                    dragCarStatusX = dragCarStatusStartX + (x - dragGrabX)
                    dragCarStatusY = dragCarStatusStartY + (y - dragGrabY)
                    val targetH = if (carStatusCardCompact) cardH else CAR_STATUS_CARD_H
                    // 拖入底部卡片行区域：手指进入热区或卡片主体跨入底部行即并入底部排布
                    if (y >= cardTop - 50f || dragCarStatusY + targetH * 0.65f >= cardTop) {
                        val bounds = cardRects.entries.map { it.value.left to it.value.right }
                        val hover = cardIndexAt(x, bounds).coerceIn(0, bounds.size)
                        carStatusInBottomRow = true
                        carStatusBottomIndex = hover
                        dragCarStatus = false
                        dragId = com.cardesk.launcher.core.CardLayout.CAR_STATUS
                        dragGrabX = x
                        dragDx = 0f
                        dragMoved = true
                    }
                    invalidate()
                    return true
                }
                val id = dragId
                if (id != null && dragFreeCard) {
                    dragFreeX = dragFreeStartX + (x - dragGrabX)
                    dragFreeY = dragFreeStartY + (y - dragGrabY)
                    dragMoved = true
                    if (y >= cardTop - 50f || dragFreeY + cardH * 0.65f >= cardTop) {
                        freeCardPos.remove(id)
                        onCardFreePosChanged?.invoke(freeCardPos.toMap())
                        dragFreeCard = false
                        dragGrabX = x
                        dragDx = 0f
                    }
                    invalidate()
                    return true
                }
                if (id != null) {
                    // 车况卡从底部行拖出回到上方区域（留足滞回带，避免松手微抖弹回原位）
                    if (id == com.cardesk.launcher.core.CardLayout.CAR_STATUS && y < cardTop - 130f) {
                        val prevRect = cardRects[id]
                        carStatusInBottomRow = false
                        carStatusBottomIndex = -1
                        onCarStatusRowChanged?.invoke(false, -1)
                        dragId = null
                        dragCarStatus = true
                        dragGrabX = x
                        dragGrabY = y
                        dragCarStatusStartX = x - CAR_STATUS_CARD_W / 2f
                        dragCarStatusStartY = y - cardH / 2f
                        dragCarStatusX = dragCarStatusStartX
                        dragCarStatusY = dragCarStatusStartY
                        dragMoved = false
                        invalidate()
                        return true
                    }
                    // 普通底排卡片拖出底部行 → 转为首页全屏自由摆放
                    if (id != com.cardesk.launcher.core.CardLayout.CAR_STATUS && y < cardTop - 110f) {
                        val w = freeCardWidth(id)
                        dragFreeCard = true
                        dragGrabX = x
                        dragGrabY = y
                        dragFreeStartX = x - w / 2f
                        dragFreeStartY = y - cardH / 2f
                        dragFreeX = dragFreeStartX
                        dragFreeY = dragFreeStartY
                        freeCardPos[id] = dragFreeX to dragFreeY
                        dragMoved = true
                        invalidate()
                        return true
                    }
                    dragDx = x - dragGrabX
                    val bounds = cardRects.entries.map { it.value.left to it.value.right }
                    val hover = cardIndexAt(x, bounds)
                    if (hover >= 0) {
                        val visibleIds = cardRects.keys.toList()
                        val current = visibleIds.indexOf(id)
                        if (current >= 0 && hover != current) {
                            if (id == com.cardesk.launcher.core.CardLayout.CAR_STATUS) {
                                carStatusBottomIndex = hover.coerceIn(0, visibleIds.size - 1)
                                dragMoved = true
                            } else {
                                val reordered = visibleIds
                                    .filterNot { it == com.cardesk.launcher.core.CardLayout.CAR_STATUS }
                                    .toMutableList()
                                val targetIdx = hover.coerceIn(0, reordered.size - 1)
                                val curIdx = reordered.indexOf(id)
                                if (curIdx >= 0 && targetIdx != curIdx) {
                                    reordered.add(targetIdx, reordered.removeAt(curIdx))
                                    cardLayout = reorderVisible(cardLayout, reordered)
                                    dragMoved = true
                                }
                            }
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
                if ((downCardId != null || downCarStatusCard) &&
                    (kotlin.math.abs(x - downX) > 20f || kotlin.math.abs(y - downY) > 20f)
                ) {
                    main.removeCallbacks(longPressRunnable)
                    downCardId = null
                    downCarStatusCard = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPressRunnable)
                // 松手：按压缩放弹回去。
                endPress()
                if (dragCarStatus) {
                    dragCarStatus = false
                    val targetW = CAR_STATUS_CARD_W
                    val targetH = if (carStatusCardCompact) cardH else CAR_STATUS_CARD_H
                    if (y >= cardTop - 50f || dragCarStatusY + targetH * 0.65f >= cardTop) {
                        val bounds = cardRects.entries.map { it.value.left to it.value.right }
                        val hover = cardIndexAt(x, bounds).coerceIn(0, bounds.size)
                        carStatusInBottomRow = true
                        carStatusBottomIndex = hover
                        onCarStatusRowChanged?.invoke(true, hover)
                        animateDragLift(false)
                        parent?.requestDisallowInterceptTouchEvent(false)
                        Logx.event(LogTags.SCENE, "车况卡落入底部行", "index" to hover)
                        invalidate()
                        performClick()
                        return true
                    }
                    val finalX = dragCarStatusX.coerceIn(left, (rightEnd - targetW).coerceAtLeast(left))
                    val finalY = dragCarStatusY.coerceIn(84f, (cardTop - 8f - targetH).coerceAtLeast(84f))
                    carStatusCardPosX = finalX
                    carStatusCardPosY = finalY
                    onCarStatusCardMoved?.invoke(finalX, finalY)
                    animateDragLift(false)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    Logx.event(LogTags.SCENE, "车况卡拖拽结束", "x" to finalX, "y" to finalY)
                    invalidate()
                    performClick()
                    return true
                }
                val id = dragId
                if (id != null && dragFreeCard) {
                    dragFreeCard = false
                    dragId = null
                    val w = freeCardWidth(id)
                    val finalX = dragFreeX.coerceIn(left, (rightEnd - w).coerceAtLeast(left))
                    val finalY = dragFreeY.coerceIn(84f, cardTop.coerceAtLeast(84f))
                    if (y >= cardTop - 50f || finalY + cardH * 0.65f >= cardTop) {
                        freeCardPos.remove(id)
                    } else {
                        freeCardPos[id] = finalX to finalY
                    }
                    onCardFreePosChanged?.invoke(freeCardPos.toMap())
                    animateDragLift(false)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragMoved = false
                    invalidate()
                    performClick()
                    return true
                }
                if (id != null) {
                    dragId = null
                    // 放下：卡片落回原位。
                    animateDragLift(false)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // 只有真的挪动了才落盘
                    if (dragMoved) {
                        if (id == com.cardesk.launcher.core.CardLayout.CAR_STATUS) {
                            onCarStatusRowChanged?.invoke(carStatusInBottomRow, carStatusBottomIndex)
                        } else {
                            layoutStore?.save(cardLayout)
                            onCardLayoutChanged?.invoke(cardLayout)
                        }
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
                endPress()
                dragId = null
                dragCarStatus = false
                dragFreeCard = false
                downCardId = null
                downCarStatusCard = false
                animateDragLift(false)
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
        // 收藏按钮（第207轮）
        hits[FAVORITE_HIT_KEY]?.let { r ->
            if (r.contains(x, y)) {
                Logx.event(
                    LogTags.SCENE,
                    "音乐卡收藏按钮点击",
                    "card" to "music",
                    "favorited" to favoriteState.isFavorited,
                    "known" to favoriteState.known,
                    "x" to x,
                    "y" to y,
                )
                onFavoriteToggle?.invoke()
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
        hits[PLAY_HIT_KEY]?.let { r ->
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
        // 快捷车控自定义按钮
        for (id in com.cardesk.launcher.core.SceneQuickControls.normalize(quickControls)) {
            val hitKey = com.cardesk.launcher.core.SceneQuickControls.hitKeyFor(id)
            val r = hits[hitKey] ?: continue
            if (!r.contains(x, y)) continue
            val def = com.cardesk.launcher.core.SceneQuickControls.defOf(id)
            Logx.event(
                LogTags.SCENE,
                "快捷车控卡点击",
                "card" to "climate",
                "id" to id,
                "action" to (def?.label ?: id),
                "x" to x,
                "y" to y,
            )
            if (onQuickControl != null) {
                onQuickControl?.invoke(id)
            } else {
                when (id) {
                    com.cardesk.launcher.core.SceneQuickControls.SEAT_HEAT_DRIVER -> onSeatHeat?.invoke()
                    com.cardesk.launcher.core.SceneQuickControls.SEAT_VENT_DRIVER -> onSeatVent?.invoke()
                    com.cardesk.launcher.core.SceneQuickControls.MAX_FAN -> onMaxFan?.invoke()
                }
            }
            performClick()
            return
        }
        // 快捷入口
        for (i in 0 until QUICK_SLOTS) {
            val r = hits["quick$i"] ?: continue
            if (!r.contains(x, y)) continue
            val app = quickApps.getOrNull(i)
            if (app != null && app.pkg.isNotEmpty()) {
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
            } else if (app != null) {
                // 自定义模式下的空槽：直接弹应用选择器往这一格填应用
                Logx.event(LogTags.SCENE, "快捷入口空槽点击", "slot" to i, "x" to x, "y" to y)
                if (onPickQuickAppSlot != null) {
                    onPickQuickAppSlot?.invoke(i)
                } else {
                    onConfigureQuickApps?.invoke(i)
                }
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

        // ------------------------------ 音乐卡播放键 / 收藏心形（第208轮重新排版）

        /**
         * 音乐卡**播放键**的圆半径（设计坐标）。
         *
         * 播放键的圆底、命中区都按它算；
         * 第208轮收藏心形要贴在它**左边**，所以也用它来定位。
         */
        const val MUSIC_PLAY_RADIUS = 28f

        /**
         * 音乐卡**收藏心形**的边长（设计坐标）。
         *
         * 第207轮是 26（放在卡片右上角）；
         * 第208轮按用户要求挪到**播放键左侧**并**放大一倍** → 52；
         * 第209轮再往左挪 —— 摆在**歌名和播放键中间**（不再是贴着播放键）。
         */
        const val MUSIC_FAV_ICON_SIZE = 52f

        /**
         * 收藏心形与**播放键圆**之间的**最小**间隙（设计坐标）。
         *
         * 第209轮起心形不再贴着播放键，而是摆在歌名和播放键**中间**；
         * 这个值只是**兜底** —— 万一空间不够，至少别和播放键圆碰上。
         */
        const val MUSIC_FAV_GAP = 6f

        /**
         * 收藏心形与**歌名文本列**之间的**最小**间隙（设计坐标）。
         *
         * 心形往左挪之后，歌名列的右边界要跟着让开 ——
         * 否则歌名会从心形底下穿过去。
         */
        const val MUSIC_FAV_TEXT_GAP = 8f

        /**
         * 进度环轨道色：夜间沿用深底高一层 `--surfaceStrong`，日间改用主字 14% 浅灰避免白底白圈不可见。
         */
        val MUSIC_RING_TRACK: Int
            get() = if (Skin.isNight) Skin.surfaceStrong else (Skin.TEXT and 0x00FFFFFF) or (0x24 shl 24)

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

        /** 音乐卡**收藏心形**的命中键（第207轮新增，第208轮挪到播放键左侧）。 */
        const val FAVORITE_HIT_KEY = "favorite"

        /** 音乐卡**播放键**的命中键。 */
        const val PLAY_HIT_KEY = "play"

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

        // ── 第243轮（设计板 Main.png 车况卡特写实测）──────────────
        // 卡 1488..1876 / 380..760；车模 1600..1700（卡内 112..212）、440..660（卡内 64..284）；
        // 「全车已关」在卡内 x≈232；四轮胎压一行贴在卡底（标签 680、数值 700..730）。
        //
        // 第244轮（用户反馈）：车模改**水平居中**，四轮胎压**贴到各自车轮旁边**，
        // 「全车已关」挪到**卡片下部居中**。CAR_STATUS_CAR_BOX_X / CAR_STATUS_TEXT_X
        // 随之作废（不再按卡左内边定位），常量保留供历史对照。
        /** 车模图区：上沿 / 宽 / 高（图按资产宽高比在框内居中；x 由卡片中心算）。 */
        const val CAR_STATUS_CAR_BOX_TOP = 30f
        const val CAR_STATUS_CAR_BOX_W = 210f
        const val CAR_STATUS_CAR_BOX_H = 290f

        /** 胎压文字锚点：距车模 rect 边缘 [CAR_STATUS_TIRE_GAP_X]；纵向按车高比例落前后轴（0.18 / 0.69）。 */
        const val CAR_STATUS_TIRE_GAP_X = 6f

        /** 胎压标签基线相对数值基线的抬升（标签在上、数值在下）。 */
        const val CAR_STATUS_TIRE_LABEL_GAP = 20f

        /** 状态文字（「✓ 全车已关」）字号 / 基线相对卡底的距离。 */
        const val CAR_STATUS_BOTTOM_TEXT_SP = 18f
        const val CAR_STATUS_BOTTOM_TEXT_FROM_BOTTOM = 22f

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
        const val CAR_STATUS_TITLE_SP = 20f

        /** 车况卡标题基线相对「卡顶 + [CAR_STATUS_TITLE_TOP]」的偏移（= 字号本身）。 */
        const val CAR_STATUS_TITLE_TOP = 10f

        /** 车况卡「详情 ›」字号（第189轮：12 → 24，比标题小一档）。 */
        const val CAR_STATUS_LINK_SP = 16f

        /** 车况卡状态行字号（第189轮：13 → 26）。 */
        const val CAR_STATUS_STATUS_SP = 26f

        /** 车况卡右侧状态文字字号（画板 `✓ 全车已关` 约 20px）。 */
        const val CAR_STATUS_RIGHT_TEXT_SP = 20f

        /** 底部四轮胎压的标签顺序（画板：左前/右前/左后/右后）。 */
        val CAR_TIRE_LABELS = arrayOf("左前", "右前", "左后", "右后")

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

        /** 底部卡片标题字号：与「续航 / 快捷应用 / 快捷车控」统一为 16px，避免压到第一行标签。 */
        const val TRIP_TITLE_SP = 16f

        /** 底部卡片标题基线（相对卡顶，与快捷车控/快捷应用齐平）。 */
        const val TRIP_TITLE_BASE = 23f

        /** 单元格标签字号。 */
        const val TRIP_LABEL_SP = 14f

        /** 单元格标签基线（相对单元格顶）。 */
        const val TRIP_LABEL_BASE = 13f

        /** 单元格数值字号（放不下会整卡一起降档）。 */
        const val TRIP_VALUE_SP = 26f

        /** 单元格数值字号下限。 */
        const val TRIP_VALUE_SP_MIN = 18f

        /** 卡片被压窄时的数值字号。 */
        const val TRIP_VALUE_SP_NARROW = 22f

        /** 卡片被压窄时的标签字号。 */
        const val TRIP_LABEL_SP_NARROW = 13f

        /** 数值基线（相对单元格顶）。 */
        const val TRIP_VALUE_BASE = 39f

        /** 第一行单元格顶（相对卡顶，与 23px 标题基线留出呼吸间距）。 */
        const val TRIP_CELL_TOP_1 = 41f

        /** 第二行单元格顶（相对卡顶，底部留出 ~18px 余白）。 */
        const val TRIP_CELL_TOP_2 = 93f

        /** 行程/车门车窗卡的列位置（第244轮按画板实测）：左列距卡左、右列起点占比。 */
        const val TRIP_COL_LEFT = 14f
        const val TRIP_COL_SPLIT = 0.518f

        /** 单元格右侧留给「相邻列」的空隙（单元格裁字按这个宽度算）。 */
        const val TRIP_CELL_TRAILING = 8f

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
        const val RANGE_CARD_W = 300f

        /** 卡片库新增：行程卡 / 车门车窗卡的自然宽度。 */
        const val TRIP_BOTTOM_CARD_W = 440f
        const val STATUS_BOTTOM_CARD_W = 380f

        /** 快捷入口卡宽（第五十轮对齐设计板 s01：与通用音乐 / 快捷车控同宽 338）。 */
        const val QUICK_CARD_W = 338f

        /** 媒体卡基准宽：与车门车窗（380）/ 车况卡（388）同档，小/中/大（312/380/475）紧凑均衡、无多余留白。 */
        const val MUSIC_CARD_W = 380f

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
        const val CLIMATE_CARD_PAD = 14f

        /** 空调快捷卡里每个座位位的宽。 */
        const val CLIMATE_SEAT_W = 100f

        /** 座位位之间的间隙。 */
        const val CLIMATE_SEAT_GAP = 8f

        /** 空调快捷卡宽 = padding 15*2 + 100*3 + 4*2 = 338（第四十九轮「浮屿」统一宽度）。 */
        const val CLIMATE_CARD_W = 420f

        /** 胎压卡宽。 */
        const val TIRE_CARD_W = 350f

        /** 卡片间距（等的 marginStart 20px）。 */
        const val CARD_GAP = 24f

        /** 壁纸提示条自动隐藏时长（1600ms）。 */
        const val HINT_AUTO_HIDE_MS = 1600L

        /** 长按拖拽排序的触发时长（系统长按默认 500ms）。 */
        const val LONG_PRESS_MS = 300L

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
        const val SEAT_CONTENT_TOP = 10f

        /**
         * 快捷车控 chip 里图标的顶边（距 chip 顶）。
         *
         * 第244轮：用户反馈「快捷操作按钮大小与卡片不匹配、留白过多」——
         * 原来 chip 112×70 摆在卡顶 30、下面空 50px。现在 chip 高 100、卡内上下各留 ~26，
         * 图标与文案随之放大并整体下移，chip 与卡片的比例接近画板那种「几乎撑满」。
         */
        const val CHIP_ICON_TOP = 18f

        /** 快捷车控 chip 文案基线（距 chip 顶）。 */
        const val CHIP_LABEL_BASELINE = 72f

        /**
         * 有按压反馈的命中区（**判定顺序** = [handleTap] 的顺序，先命中的赢）。
         *
         * 顺序必须和点击分发完全一致 ——
         * 按下的那块要**就是会被点到**的那块，否则会出现「按 A 缩 B」。
         */
        private val PRESS_HIT_KEYS: List<String> = listOf(
            "source",
            FAVORITE_HIT_KEY,
            CAR_STATUS_CARD_HIT_KEY,
            PLAY_HIT_KEY,
            "musicCover",
            "nav",
        ) + com.cardesk.launcher.core.SceneQuickControls.ALL.map {
            com.cardesk.launcher.core.SceneQuickControls.hitKeyFor(it.id)
        } + (0 until QUICK_SLOTS).map { "quick$it" } +
            (0 until WALLPAPER_NAMES.size).map { "wp$it" }
    }
}
