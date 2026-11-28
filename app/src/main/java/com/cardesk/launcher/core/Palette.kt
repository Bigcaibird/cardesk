package com.cardesk.launcher.core

import android.graphics.Color

/**
 * 日间 / 夜间两套配色。数值逐个 ARGB。
 *
 * 出处
 * - → `0xFF3D7BFF`（:21）—— 强调色，日夜一致
 * - → `0xFF0F1724`（:22）—— 日间主文字
 * - → `0xFFEEF3FA`（:23）—— 夜间主文字
 * - → 夜间 `0x9ECFCFFA` / 日间 `0x9E0F1724`（:43-45）—— 次文字
 * - → 夜间 `0x61EEF3FA` / 日间 `0x610F1724`（:47-49）—— 三级文字
 * - → 夜间 `0x12FFFFFF` / 日间 `0x0D0F1724`（:51-53）—— 玻璃填充
 * - → 夜间 `0x1FFFFFFF` / 日间 `0x170F1724`（:55-57）—— 玻璃填充（强）
 * - → 夜间 `0xFF0C121C` / 日间 `0xFFFFFFFF`（:174-176）—— 播放图标
 * - → 夜间 `0xFF121926` / 日间 `0xFFF4F7FB`（:178-180）—— 浮层底色
 *
 * 注意：这些是**设计稿坐标系**下的 ARGB，不要改成 6 位十六进制（Kotlin 里 alpha 会变 0）。
 */
data class Palette(
    val night: Boolean,
    val bgTop: Int,
    val bgBottom: Int,
    val bgGlow: Int,
    val surface: Int,
    val surfaceStrong: Int,
    val surfaceSoft: Int,
    val stroke: Int,
    val text: Int,
    val textDim: Int,
    val textFaint: Int,
    val accent: Int,
    val accentSoft: Int,
    val ok: Int,
    val warn: Int,
    val danger: Int,
    /**
     * 车窗打开的告警色（黄）。
     *
     * 第180轮新增：白天是**白底**，原来那个亮黄 `#F5C542` 落在白底上基本看不见
     * （对比度只有 1.4:1）—— 和「深底深字」是同一类毛病。
     * 日间压深一档到 `#B07A00`（对比度 ~3.8:1），仍然是黄的。
     */
    val windowOpen: Int = 0xFFF5C542.toInt(),
    /**
     * 车门 / 尾门打开的告警色（橙）。
     *
     * 夜间沿用参考包的 `#FF9F43`（`Palettes.CAR_STATE_WARN`）；
     * 日间压深到 `#B7791F` —— 亮橙落在白底上同样读不出来。
     */
    val carStateWarn: Int = 0xFFFF9F43.toInt(),
    // —— 「浮屿 Flat Isle」新增 token（第四十九轮）——
    /** 强调色上的文字色（accent 底上的字）。 */
    val accentInk: Int = 0xFFFFFFFF.toInt(),
    /**
     * 强调色的「**提亮一档**」版本（同一色相，只提亮 —— 见 [Skin.ACCENT_LIGHT]）。
     *
     * 第222轮：从 [Skin] 里写死的字面量搬进来 ——
     * 浮层墨色要按壁纸亮度整套切换，这两档强调色也得跟着成套走，
     * 写死在 [Skin] 里就没法成套引用了。
     */
    val accentLight: Int = 0xFF5AD8C8.toInt(),
    /** Dock / 底栏底色（不透明）。 */
    val dock: Int = 0xFF1C2029.toInt(),
    /** 弹层遮罩色。 */
    val scrim: Int = 0xA808090C.toInt(),
    /** 俯视车模车身色。 */
    val carBody: Int = 0xFF3D465E.toInt(),
    /** 地图道路色。 */
    val road: Int = 0xFF181B23.toInt(),
) {
    fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

object Palettes {

    /**
     * （:21）= -12747777 = `0xFF3D7BFF`。
     * 这里直接用十进制字面量，便于和反编译产物逐字对照。
     */
    const val ACCENT = -12747777

    /** （:23）= -1117190 = `0xFFEEF3FA`。 */
    const val NIGHT_FG = -1117190

    /** （:22）= -15788252 = `0xFF0F1724`。 */
    const val DAY_FG = -15788252

    /** 夜间（:44）= -1628507142 = `0x9ECFCFFA`。 */
    const val NIGHT_FG2 = -1628507142

    /** 日间（:44）= -1643178204 = `0x9E0F1724`。 */
    const val DAY_FG2 = -1643178204

    /** 夜间（:48）= 1643049978 = `0x61EEF3FA`。 */
    const val NIGHT_FG3 = 1643049978

    /** 日间（:48）= 1628378916 = `0x610F1724`。 */
    const val DAY_FG3 = 1628378916

    /** 夜间（:52）= 318767103 = `0x12FFFFFF`。 */
    const val NIGHT_FILL = 318767103

    /** 日间（:52）= 219092772 = `0x0D0F1724`。 */
    const val DAY_FILL = 219092772

    /** 夜间（:56）= 536870911 = `0x1FFFFFFF`。 */
    const val NIGHT_FILL2 = 536870911

    /** 日间（:56）= 386864932 = `0x170F1724`。 */
    const val DAY_FILL2 = 386864932

    /** 夜间（:175）= -15986148 = `0xFF0C121C`。 */
    const val NIGHT_PLAY_GLYPH = -15986148

    /** 日间（:175）= -1 = `0xFFFFFFFF`。 */
    const val DAY_PLAY_GLYPH = -1

    /** 夜间（:179）= -15591130 = `0xFF121926`。 */
    const val NIGHT_SHEET = -15591130

    /** 日间（:179）= -722949 = `0xFFF4F7FB`。 */
    const val DAY_SHEET = -722949

    /** （:75）= -24765 = `0xFFFF9F43`（车门/车窗告警色）。 */
    const val CAR_STATE_WARN = -24765

    /** 指示点选中色 `#3d7bff` = `0xFF3D7BFF`。 */
    const val DOT_ON = -12747777

    /** 指示点未选中色 `#66ffffff` = `0x66FFFFFF`。 */
    const val DOT_OFF = 1627389951

    val night = Palette(
        night = true,
        // 第242轮「液态玻璃」：底色是**深墨蓝黑**，卡片是压在模糊壁纸上的 66% 墨玻璃。
        bgTop = 0xFF0B0F14.toInt(),
        bgBottom = 0xFF0B0F14.toInt(),
        bgGlow = 0xFF12212A.toInt(),
        // L1 卡 / L2 浮层 / 内嵌块：全部半透明（真实观感由 [com.cardesk.launcher.core.Glass] 补模糊）
        surface = 0xA8161B22.toInt(),
        surfaceStrong = 0xCC1B212A.toInt(),
        surfaceSoft = 0x5C161B22.toInt(),
        // 1px 白描边 12%
        stroke = 0x1FFFFFFF,
        text = 0xFFEAF0F5.toInt(),
        textDim = 0xFF9AA7B4.toInt(),
        textFaint = 0xFF6B7885.toInt(),
        accent = 0xFF16C2B2.toInt(),
        accentSoft = 0x2E16C2B2,
        accentLight = 0xFF3FD8C8.toInt(),
        ok = 0xFF34D399.toInt(),
        warn = 0xFFF5A623.toInt(),
        danger = 0xFFFF6B6B.toInt(),
        windowOpen = 0xFFF5C542.toInt(),
        carStateWarn = 0xFFFFB020.toInt(),
        accentInk = 0xFF06201D.toInt(),
        dock = 0xB3161B22.toInt(),
        scrim = 0x9906090C.toInt(),
        carBody = 0xFF8FA0B8.toInt(),
        road = 0xFF1B2530.toInt(),
    )

    val day = Palette(
        night = false,
        // L0：冷调浅灰蓝底，柔和光斑由 WallpaperRenderer 画。
        bgTop = 0xFFF3F7FA.toInt(),
        bgBottom = 0xFFE6EDF5.toInt(),
        bgGlow = 0xFFD3EEF0.toInt(),
        // L1 = rgba(255,255,255,.73) / L2 = .86 / 内嵌块 = .55
        // 日间提高磨砂面不透明度并压深次/弱级文字，避免在深色壁纸或高亮背景下
        // 卡片标题、续航 km/燃料剩余、车况「全车已关」等与底色过于接近导致看不清。
        surface = 0xBAFFFFFF.toInt(),
        surfaceStrong = 0xDBFFFFFF.toInt(),
        surfaceSoft = 0x8CFFFFFF.toInt(),
        stroke = 0x59FFFFFF,
        text = 0xFF101418.toInt(),
        textDim = 0xFF3A4652.toInt(),
        textFaint = 0xFF475462.toInt(),
        accent = 0xFF00857E.toInt(),
        accentSoft = 0x1F00857E,
        accentLight = 0xFF0A7D76.toInt(),
        ok = 0xFF18864B.toInt(),
        warn = 0xFFC47A00.toInt(),
        danger = 0xFFD9383E.toInt(),
        windowOpen = 0xFFA66F00.toInt(),
        carStateWarn = 0xFFCC7000.toInt(),
        accentInk = 0xFFFFFFFF.toInt(),
        dock = 0xBAFFFFFF.toInt(),
        scrim = 0x42000000,
        carBody = 0xFF8C97B0.toInt(),
        road = 0xFFE6E9EF.toInt(),
    )

    /** 播放按钮底色（= `#eef3fa`）：近白实心圆。 */
    const val DOCK_PLAY_BG = -1117190

    /** 浮层底色：夜间 日间。 */
    fun sheet(night: Boolean): Int = if (night) NIGHT_SHEET else DAY_SHEET

    /** 播放图标颜色：夜间 日间。 */
    fun playGlyph(night: Boolean): Int = if (night) NIGHT_PLAY_GLYPH else DAY_PLAY_GLYPH
}
