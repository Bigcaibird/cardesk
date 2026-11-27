package com.cardesk.launcher.core

import android.graphics.drawable.GradientDrawable

/**
 * 桌面视觉基准（1920×1080 设计稿）。
 *
 * 第四十九轮起采用 **「浮屿 Flat Isle」** 设计语言：
 * - **纯扁平**：零投影、零玻璃拟态、零发光；层次只靠「留白 + 明度差 + 1px 描边」表达；
 * - **大圆角**：卡片 28 / 内嵌 18 / 图标底 16 / 胶囊 999（见 [Shape]）；
 * - **单一强调色**：青绿（夜 `#33C6B4` / 日 `#0F9C8E`），刻意与蓝区分；
 * - **日夜同一套 token、两套值**（见 [Palettes]）。
 *
 * 所有图形由本项目自绘，不使用任何图片资源。
 */
object Skin {

    /** 当前主题是否为夜间（由 [apply] 更新，供不变更构造参数的绘制代码使用）。 */
    var isNight: Boolean = true
        private set

    /**
     * 把日/夜 Palette 映射到绘制 token。
     *
     * 桌面主体（场景页/Dock/状态栏/槽位条）在自己 onDraw 里写死颜色
     * 这里把 token 变成可切换变量，切换主题时统一刷新，避免每个绘制函数都改签名。
     *
     * 第四十九轮：材质常量**全部换成不透明纯色**（不再有 `0x12FFFFFF` 这类半透明玻璃色）。
     */
    fun apply(pal: Palette) {
        isNight = pal.night
        TEXT = pal.text
        TEXT_SOFT = pal.textDim
        TEXT_DIM = pal.textFaint
        ACCENT = pal.accent
        ACCENT_SOFT = pal.accentSoft
        ACCENT_INK = pal.accentInk
        // 「单一强调色」：只用青绿一个色相，激活态只是**提亮一档**，不换色相。
        ACCENT_LIGHT = pal.accentLight
        OK = pal.ok
        WARN = pal.warn
        DANGER = pal.danger
        WINDOW_OPEN = pal.windowOpen
        CAR_STATE_WARN = pal.carStateWarn
        NAV_BG = pal.dock
        NAV_STROKE = pal.stroke
        GLASS_FILL = pal.surfaceSoft
        GLASS_STRONG_FILL = pal.surface
        GLASS_STROKE = pal.stroke
        CHIP_FILL = pal.surfaceSoft
        CHIP_STROKE = pal.stroke
        SHEET_FILL = pal.surface
        SHEET_FILL_SOFT = pal.surfaceSoft
        SCRIM = pal.scrim
        surface = pal.surface
        surfaceStrong = pal.surfaceStrong
        surfaceSoft = pal.surfaceSoft
        // 换了日夜 → 浮层墨色也要跟着重算（壁纸亮度可能没变，但兜底的那一套变了）。
        applyOverlayInk()
    }

    // ------------------------------------------------------------------
    // 浮层墨色（第222轮）：压在**壁纸**上的文字 / 图标
    // ------------------------------------------------------------------

    /**
     * 顶部浮层（状态栏右侧的裸元素 + 右上天气块 + **状态栏胶囊**）的墨色。
     *
     * ## 为什么单独一套
     *
     * 日夜模式管的是「卡片 / 面板 / 胶囊」这一层；
     * 而**直接压在壁纸上**的那一层能不能看清，取决于**壁纸本身有多亮**。
     * 典型翻车：**日间模式 + 暗色照片壁纸** ⇒ 深色字压在暗色照片上，对比度只有 1.5~2.0。
     *
     * ## 怎么定的
     *
     * 按**壁纸实际亮度**选整套（深底用夜间那套文字色 / 浅底用日间那套），
     * 由对比度决定而不是拍阈值 ⇒ 默认场景（夜间主题 + 深壁纸 / 日间主题 + 浅壁纸）
     * **一个像素都不变**。详见 [WallpaperContrast]。
     *
     * ## 第223轮：胶囊底板也跟着切
     *
     * [overlayTop] 里带着 `surface` / `stroke` / `surfaceStrong` 三个**面**的 token，
     * 供**状态栏胶囊**（驾驶模式 / 位置 / 天气）和状态栏正中的翻页圆点条使用。
     *
     * ⚠️ **全局的 [CHIP_FILL] / [CHIP_STROKE] 不动** ——
     * 那两个 token 被卡片、抽屉、设置弹层、浮窗（`RecentTasksPage` / `DrawerPage` /
     * `SettingsOverlay` / `FloatWindowLayer` / `ClimatePage` …）大量复用，
     * 一改就是**满屏卡片**跟着变。只有**压在壁纸上**的那几个胶囊才用浮层那套。
     */
    var overlayTop: WallpaperContrast.Ink = WallpaperContrast.ON_DARK
        private set

    /**
     * 屏幕中部（中心歌词）的墨色。
     *
     * 与 [overlayTop] **分开**：壁纸可能「上亮下暗」——
     * 顶部按亮底选深字，中部就该按暗底选浅字，合成一套会有一半看不清。
     */
    var overlayCenter: WallpaperContrast.Ink = WallpaperContrast.ON_DARK
        private set

    /** 壁纸在顶部浮层区的平均颜色（null = 还不知道）。 */
    private var backdropTop: Int? = null

    /** 壁纸在屏幕中部的平均颜色（null = 还不知道）。 */
    private var backdropCenter: Int? = null

    /**
     * 记下壁纸在浮层区的平均颜色，并重算浮层墨色。
     *
     * 由 [com.cardesk.launcher.ui.widget.BackgroundView] 在**壁纸 / 日夜变化**时调用
     * （不是每帧 —— 采样要读位图像素，每帧读会拖慢桌面）。
     *
     * @param top 顶部浮层区的平均颜色；null = 不知道（跟着主题走）
     * @param center 屏幕中部的平均颜色；null = 不知道
     */
    fun setBackdrop(top: Int?, center: Int?) {
        backdropTop = top
        backdropCenter = center
        // 静态壁纸 / 主题兜底这一路接管 ⇒ 之前的视频分布作废（第231轮）。
        videoTop = null
        videoCenter = null
        videoTopDark = null
        videoCenterDark = null
        applyOverlayInk()
    }

    /**
     * 视频壁纸在顶部 / 中部的**亮度分布**（第231轮）。
     *
     * 与静态壁纸的「平均色」并存：谁最后写谁算数 ——
     * 视频壁纸播放时由 `LauncherView` 每 3 秒写一次；
     * 视频退出 / 换成静态壁纸时，`setBackdrop` 会把这两份清掉。
     */
    private var videoTop: WallpaperSampler.Tone? = null
    private var videoCenter: WallpaperSampler.Tone? = null
    private var videoTopDark: Boolean? = null
    private var videoCenterDark: Boolean? = null

    /** 当前壁纸在浮层区的平均颜色（诊断 / 测试用）。 */
    val backdrop: List<Int?> get() = listOf(backdropTop, backdropCenter)

    /**
     * 记下**视频壁纸**的亮度分布并重算浮层墨色（第231轮）。
     *
     * 视频那一层只能靠逐帧采样，采到的不是「平均色」而是分布，
     * 所以走 [WallpaperContrast.inkForTone]（按最坏情况拉够对比度）。
     *
     * @param top 顶部浮层区的分布；null = 采不到（跟着主题走）
     * @param center 屏幕中部的分布；null = 采不到
     */
    fun setVideoBackdrop(top: WallpaperSampler.Tone?, center: WallpaperSampler.Tone?) {
        videoTop = top
        videoCenter = center
        backdropTop = top?.average
        backdropCenter = center?.average
        applyOverlayInk()
    }

    /** 视频壁纸的亮度分布（诊断 / 测试用；没有数字键可吐，给个是否已知）。 */
    val videoBackdropKnown: List<Boolean> get() = listOf(videoTop != null, videoCenter != null)

    /** 重算两套浮层墨色。 */
    private fun applyOverlayInk() {
        overlayTop = overlayInkFor(backdropTop, videoTop, videoTopDark).also { ink ->
            if (videoTop != null) videoTopDark = (ink.surface == WallpaperContrast.ON_DARK.surface)
        }
        overlayCenter = overlayInkFor(backdropCenter, videoCenter, videoCenterDark).also { ink ->
            if (videoCenter != null) videoCenterDark = (ink.surface == WallpaperContrast.ON_DARK.surface)
        }
    }

    /**
     * 按背景色算一套墨色（强调色单独处理）。
     *
     * 强调色要**保住用户选的色相**：
     * 用户自定义过强调色时不用另一套的青绿，只把它往「看得清」的方向挪一点
     * （[WallpaperContrast.ensureContrast]）；没自定义过就用整套里配套的那一档。
     *
     * 第223轮：**胶囊的面（`surface` / `surfaceStrong` / `stroke`）原样带走** ——
     * 它们必须和文字成套切换（深底必须配浅字），不能单独调。
     */
    private fun overlayInkFor(
        backdrop: Int?,
        tone: WallpaperSampler.Tone? = null,
        prevDark: Boolean? = null,
    ): WallpaperContrast.Ink {
        // 有分布（视频壁纸）⇒ 按最坏情况算并带极性迟滞；没有 ⇒ 老口径（平均色）。
        val ink = if (tone != null) {
            WallpaperContrast.inkForTone(tone, isNight, prevDark)
        } else {
            WallpaperContrast.inkFor(backdrop, isNight)
        }
        val themedAccent = if (isNight) Palettes.night.accent else Palettes.day.accent
        val accent = if (ACCENT != themedAccent && backdrop != null) {
            WallpaperContrast.ensureContrast(
                ACCENT,
                backdrop,
                WallpaperContrast.ACCENT_MIN_CONTRAST,
            )
        } else {
            ink.accent
        }
        val accentLight = if (ACCENT != themedAccent && backdrop != null) {
            WallpaperContrast.ensureContrast(
                if (isNight) Palettes.night.accentLight else Palettes.day.accentLight,
                backdrop,
                WallpaperContrast.ACCENT_MIN_CONTRAST,
            )
        } else {
            if (isNight) Palettes.night.accentLight else Palettes.day.accentLight
        }
        return ink.copy(accent = accent, accentLight = accentLight)
    }

    /** 浮层/对话框主体填充：纯色面（不再是半透明玻璃）。 */
    var SHEET_FILL = 0xFF1F232D.toInt()
    var SHEET_FILL_SOFT = 0xFF171A22.toInt()

    /** 弹层遮罩。 */
    var SCRIM = 0xA808090C.toInt()

    // —— 三档面（第四十九轮「浮屿」的层次全靠这三档明度差）——
    /** 卡片面。 */
    var surface = 0xFF1F232D.toInt()

    /** 更强一档的面：进度条槽 / 圆环轨道 / 关闭态开关。 */
    var surfaceStrong = 0xFF2A2F3B.toInt()

    /** 更弱一档的面：内嵌块 / chip / 图标底。 */
    var surfaceSoft = 0xFF171A22.toInt()

    // —— 文字 ——
    var TEXT = 0xFFEDEFF3.toInt()
    var TEXT_SOFT = 0xFFA2AAB8.toInt()
    var TEXT_DIM = 0xFF757E8E.toInt()
    const val TEXT_DARK = 0xFF191C23.toInt()

    // —— 强调色 ——
    var ACCENT = 0xFF33C6B4.toInt()
    var ACCENT_LIGHT = 0xFF5AD8C8.toInt()
    var ACCENT_SOFT = 0x2933C6B4
    /** 强调色底上的文字色。 */
    var ACCENT_INK = 0xFF0B1413.toInt()
    var OK = 0xFF4CC38A.toInt()
    var WARN = 0xFFE0A340.toInt()
    var DANGER = 0xFFE2574C.toInt()

    /**
     * 车窗打开的告警色（黄）。
     *
     * 第180轮新增：日间是**白底**，亮黄落在上面看不见，
     * 所以日间用压深一档的版本（见 `Palette.windowOpen`）。
     */
    var WINDOW_OPEN = 0xFFF5C542.toInt()

    /**
     * 车门 / 尾门打开的告警色（橙）。
     *
     * 夜间 = 参考包的 `#FF9F43`，日间压深一档（白底上亮橙看不清）。
     */
    var CAR_STATE_WARN = 0xFFFF9F43.toInt()

    // —— 面（第四十九轮起全部不透明纯色 + 1px 描边）——
    /** 底栏/导航栏底色。 */
    var NAV_BG = 0xFF1C2029.toInt()
    var NAV_STROKE = 0xFF333947.toInt()
    /** 通用面填充/描边（保留旧名，语义已从「玻璃」变为「扁平面」）。 */
    var GLASS_FILL = 0xFF171A22.toInt()
    var GLASS_STRONG_FILL = 0xFF1F232D.toInt()
    var GLASS_STROKE = 0xFF333947.toInt()
    var CHIP_FILL = 0xFF171A22.toInt()
    var CHIP_STROKE = 0xFF333947.toInt()

    // —— 形状 ——
    /**
     * 扁平面（不透明 + 1px 描边）。
     *
     * 第五十轮起默认底色改回 **卡片面 `--surface`**（设计板 `.card { background: var(--surface) }`），
     * `strong = true` 才是 `--surfaceStrong`；**内嵌块 / chip / 图标底**要用 `--surfaceSoft`，
     * 请显式传 `Skin.surfaceSoft`（或用 [chip]）。
     */
    /**
     * 给 View 用的**玻璃**背景（第243轮）。
     *
     * 以前这里是 `GradientDrawable`（一块纯色 + 1px 描边）。设计稿 v3.3 的 L1/L2
     * 是「模糊背景 + 半透明面 + 内高光 + 柔阴影」，纯色面在深壁纸上会变成一块灰板，
     * 与自绘卡片（[Glass.card]）明显不是一种材质。
     *
     * 现在统一走 [GlassDrawable]：它从 [Glass.backdrop]（整屏背景的模糊缩略图）里取
     * 与本 View 位置对应的一块，再叠填充色 / 描边 / 内高光 —— 与 Canvas 上的卡片同一套。
     *
     * 小圆角（< 20，如内嵌小块、进度槽）不画阴影：那种尺寸下阴影只会糊成一圈灰。
     */
    fun flat(radius: Float, strong: Boolean = false, fill: Int = if (strong) surfaceStrong else GLASS_STRONG_FILL): GlassDrawable =
        GlassDrawable(
            radius = radius,
            fill = fill,
            strokeColor = GLASS_STROKE,
            shadow = radius >= 20f,
            sheen = radius >= 12f,
        )

    /** @see flat */
    fun glass(radius: Float, strong: Boolean = false, fill: Int = if (strong) surfaceStrong else GLASS_STRONG_FILL): GlassDrawable =
        flat(radius, strong, fill)

    fun navBar(corner: Float = Shape.CARD): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(NAV_BG)
            cornerRadius = corner
            setStroke(1, NAV_STROKE)
        }

    fun chip(radius: Float = Shape.PILL): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(CHIP_FILL)
            cornerRadius = radius
            setStroke(1, CHIP_STROKE)
        }

    fun solid(color: Int, radius: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }

    fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
}
