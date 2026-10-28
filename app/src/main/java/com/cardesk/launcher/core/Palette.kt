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
    // —— 「浮屿 Flat Isle」新增 token（第四十九轮）——
    /** 强调色上的文字色（accent 底上的字）。 */
    val accentInk: Int = 0xFFFFFFFF.toInt(),
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
        // 第四十九轮「浮屿」：**纯色底 + 极淡径向渐变**（不再是拟物山峦的冷调渐变）
        bgTop = 0xFF0F1116.toInt(),
        bgBottom = 0xFF0F1116.toInt(),
        bgGlow = 0xFF191D26.toInt(),
        // 卡片面：全部**不透明**，层次靠明度差 + 1px 描边（不再有玻璃拟态）
        surface = 0xFF1F232D.toInt(),
        surfaceStrong = 0xFF2A2F3B.toInt(),
        surfaceSoft = 0xFF171A22.toInt(),
        stroke = 0xFF333947.toInt(),
        text = 0xFFEDEFF3.toInt(),
        textDim = 0xFFA2AAB8.toInt(),
        textFaint = 0xFF757E8E.toInt(),
        accent = 0xFF33C6B4.toInt(),
        accentSoft = 0x2933C6B4,
        ok = 0xFF4CC38A.toInt(),
        warn = 0xFFE0A340.toInt(),
        danger = 0xFFE2574C.toInt(),
        accentInk = 0xFF0B1413.toInt(),
        dock = 0xFF1C2029.toInt(),
        scrim = 0xA808090C.toInt(),
        carBody = 0xFF3D465E.toInt(),
        road = 0xFF181B23.toInt(),
    )

    val day = Palette(
        night = false,
        // 第五十六轮：日间「背景 vs 卡片」原来太接近（#F1F3F7 vs #FFFFFF），
        // 白卡片浮不出来、看着像一块糊在一起的底。
        // 现在把**背景压深一档**、卡片保持纯白，卡内嵌块再单独提一档，
        // 四层（背景 / 内嵌 / 卡片 / 选中槽）各自能一眼分开。
        bgTop = 0xFFE9EDF4.toInt(),
        bgBottom = 0xFFE9EDF4.toInt(),
        bgGlow = 0xFFF8FAFD.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceStrong = 0xFFDDE2EB.toInt(),
        surfaceSoft = 0xFFF2F5F9.toInt(),
        stroke = 0xFFD5DBE5.toInt(),
        text = 0xFF191C23.toInt(),
        textDim = 0xFF565D6B.toInt(),
        textFaint = 0xFF8B92A0.toInt(),
        accent = 0xFF0F9C8E.toInt(),
        accentSoft = 0x1F0F9C8E,
        ok = 0xFF1F9D5F.toInt(),
        warn = 0xFFB7791F.toInt(),
        danger = 0xFFC8413A.toInt(),
        accentInk = 0xFFFFFFFF.toInt(),
        dock = 0xFFFFFFFF.toInt(),
        scrim = 0x61181A20.toInt(),
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
