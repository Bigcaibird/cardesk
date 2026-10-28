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
        ACCENT_LIGHT = if (pal.night) 0xFF5AD8C8.toInt() else 0xFF1FB3A3.toInt()
        OK = pal.ok
        WARN = pal.warn
        DANGER = pal.danger
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
    fun flat(radius: Float, strong: Boolean = false, fill: Int = if (strong) surfaceStrong else GLASS_STRONG_FILL): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            setStroke(1, GLASS_STROKE)
        }

    /** @see flat */
    fun glass(radius: Float, strong: Boolean = false, fill: Int = if (strong) surfaceStrong else GLASS_STRONG_FILL): GradientDrawable =
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
