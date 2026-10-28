package com.cardesk.launcher.core

/**
 * 卡片风格（第五十八轮）：**透明度 / 颜色 / 圆角** 三件套。
 *
 * ## 为什么放在 core
 *
 * 「卡片风格」是**纯逻辑**：给定（基础配色、颜色、透明度、圆角），
 * 算出该用哪套面、该用多大圆角 —— 不依赖任何 Android 类，
 * 所以能在 JVM 上直接单测（`core/CardStyleTest.kt`）。
 *
 * ## 三件套的作用范围
 *
 * 「卡片」= 桌面上的**卡片面**（`Palette.surface`）以及卡内的两层面
 * （`surfaceStrong` 选中槽 / `surfaceSoft` 内嵌块）。
 *
 * - **透明度**（[clampOpacity]）：按比例缩放这三层面的 alpha ——
 *   卡面半透明时，内嵌块也一起半透明（不然会像「白卡片里贴了不透明贴纸」）。
 * - **颜色**（用户自选，0 = 跟随主题）：换成自选色后，
 *   两层内嵌面按「浮屿」的明度差规则从卡面色**派生**：
 *   夜间强面提亮一档、弱面压暗一档；日间反过来。这样换了颜色层级关系还在。
 * - **圆角**（[applyRadius]）：卡片圆角可调，内嵌块（18/28）和图标底（16/28）
 *   **等比缩放**，保持「浮屿」那套 28/18/16 的比例关系。
 *
 * ## 描边不变
 *
 * 1px 描边（`Palette.stroke`）**不跟着透明度变**：
 * 卡面半透明时，正是这条描边把卡片的边界勾出来。
 */
object CardStyle {

    /** 透明度默认 100（完全不透明，即「浮屿」原本的样子）。 */
    const val DEFAULT_OPACITY = 100

    /** 透明度下限（再低就看不出卡片了）。 */
    const val MIN_OPACITY = 20

    /** 透明度上限。 */
    const val MAX_OPACITY = 100

    /** 圆角默认 28（= [Shape.CARD] 的出厂值）。 */
    const val DEFAULT_RADIUS = 28

    /** 圆角下限（0 = 直角）。 */
    const val MIN_RADIUS = 0

    /** 圆角上限（再大就接近胶囊了，卡片会失去「块」的感觉）。 */
    const val MAX_RADIUS = 48

    /** 透明度规整。 */
    fun clampOpacity(value: Int): Int = value.coerceIn(MIN_OPACITY, MAX_OPACITY)

    /** 圆角规整（设计坐标 px）。 */
    fun clampRadius(value: Int): Int = value.coerceIn(MIN_RADIUS, MAX_RADIUS)

    /**
     * 把卡片风格套到基础配色上，返回新的 [Palette]。
     *
     * @param base 主题基础配色（日间 / 夜间）
     * @param color 用户自选卡片色（0 = 跟随主题）
     * @param opacity 卡片透明度（0..100）
     * @param radius 卡片圆角（设计坐标 px）
     */
    fun apply(base: Palette, color: Int, opacity: Int, radius: Int): Palette {
        val scale = clampOpacity(opacity) / 100f
        val face: Int
        val strong: Int
        val soft: Int
        if (color == 0) {
            // 跟随主题：三层面的色相全留主题的，只统一缩放 alpha。
            face = base.surface
            strong = base.surfaceStrong
            soft = base.surfaceSoft
        } else {
            face = color
            // 从卡面色派生两层内嵌面，保持「浮屿」的明度差层级：
            // 夜间强面提亮、弱面压暗；日间强面压暗、弱面只轻微压暗。
            strong = if (base.night) {
                mix(face, WHITE, 0.08f)
            } else {
                mix(face, BLACK, 0.10f)
            }
            soft = if (base.night) {
                mix(face, BLACK, 0.18f)
            } else {
                mix(face, BLACK, 0.05f)
            }
        }
        return base.copy(
            surface = scaleAlpha(face, scale),
            surfaceStrong = scaleAlpha(strong, scale),
            surfaceSoft = scaleAlpha(soft, scale),
        )
    }

    /**
     * 应用卡片圆角：卡片圆角本身 + 内嵌块 / 图标底**等比缩放**。
     *
     * 出厂比例：卡片 28 / 内嵌 18 / 图标底 16 —— 这里保持这个比例不变。
     */
    fun applyRadius(radius: Int) {
        val r = clampRadius(radius).toFloat()
        Shape.CARD = r
        Shape.INNER = r * (18f / 28f)
        Shape.TILE = r * (16f / 28f)
    }

    /** 当前生效的卡片圆角（供 UI 读回显示）。 */
    fun currentRadius(): Float = Shape.CARD

    /**
     * 透明度作用在 alpha 上（RGB 不变）。
     *
     * 纯 Kotlin 实现（不碰 `android.graphics.Color`），保证能 JVM 单测。
     */
    fun scaleAlpha(color: Int, scale: Float): Int {
        val k = scale.coerceIn(0f, 1f)
        // 四舍五入（不截断）：50% 透明度要正好是 0x80，而不是 0x7F。
        val a = ((color ushr 24) and 0xFF) * k + 0.5f
        return ((a.toInt() and 0xFF) shl 24) or (color and 0x00FFFFFF)
    }

    /**
     * 两个 ARGB 按比例混合（纯 Kotlin）。
     *
     * @param t 0 = 全取 [a]，1 = 全取 [b]
     */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val inv = 1f - k
        // 四舍五入（不截断）：白黑各半要正好是 0x808080。
        val aa = ((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * k + 0.5f
        val rr = ((a ushr 16) and 0xFF) * inv + ((b ushr 16) and 0xFF) * k + 0.5f
        val gg = ((a ushr 8) and 0xFF) * inv + ((b ushr 8) and 0xFF) * k + 0.5f
        val bb = (a and 0xFF) * inv + (b and 0xFF) * k + 0.5f
        return ((aa.toInt() and 0xFF) shl 24) or
            ((rr.toInt() and 0xFF) shl 16) or
            ((gg.toInt() and 0xFF) shl 8) or
            (bb.toInt() and 0xFF)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
}
