package com.cardesk.launcher.cluster

/**
 * 仪表歌词面板的**纯排版数学**（无 Android 类型）。
 *
 * ## 为什么单独一个类
 *
 * 有两处必须对同一组数字给出同一个答案：
 *
 * 1. 服务把窗口加到副屏上时，算的是「窗口左上角在屏幕像素里的位置」；
 * 2. 设置页里那个「位置与大小」预览框，算的是「预览里那个块该画在哪」。
 *
 * 两边只要有一处算错，用户在预览里摆好的位置落到仪表上就不是那个位置。
 * 把数学抽到**不依赖任何 Android 类型**的地方，这条约定才有办法被单测钉住。
 *
 * ## 坐标口径
 *
 * 存的是**面板中心**在屏幕上的比例（`xRatio` / `yRatio`），不是左上角。
 * 这是拖动时最直观的口径：把块拖到中间，不管它多宽都还是居中。
 * 结果会被夹回屏内，面板永远不可能被拖出屏幕。
 *
 * 移植自 UNI 桌面（`ClusterPanelGeometry`），口径一致；差别是这里主/副歌词
 * **各有一个字号**，高度按两档分别算（UNI 只有一档）。
 */
object ClusterPanelGeometry {

    /**
     * 窄于屏幕的 1/10 就不再是歌词面板了。
     *
     * 第149轮：下限从 **1/5 放宽到 1/10** —— 用户要求「仪表歌词的最小宽度限制
     * 从 30% 调整到 10%」。10% 在仪表（1920 宽）上是 192px，够放一行短歌词。
     */
    const val MIN_WIDTH_RATIO = 0.1f

    /** 最宽就是整屏。 */
    const val MAX_WIDTH_RATIO = 1f

    /** 行高 = 字号 × 这个系数。 */
    const val ROW_SPACING = 1.62f

    /** 最少一行（当前句）。 */
    const val MIN_ROWS = 1

    /** 最多三行（上一句 / 当前句 / 下一句）。 */
    const val MAX_ROWS = 3

    /** 默认水平位置：正中。 */
    const val DEFAULT_X_RATIO = 0.5f

    /** 默认垂直位置：偏下（仪表的下半区放歌词不挡车速表）。 */
    const val DEFAULT_Y_RATIO = 0.62f

    /** 默认宽度：几乎占满。 */
    const val DEFAULT_WIDTH_RATIO = 0.94f

    /** 默认行数：三行。 */
    const val DEFAULT_ROWS = 3

    /**
     * 把比例夹进 `[0,1]`。
     *
     * NaN / 无穷大退回 [fallback] —— NaN 往下游传会让整个布局算成 NaN，
     * 窗口尺寸就是 0，表现为「加了窗口但什么都没画」。
     */
    fun clampRatio(value: Float, fallback: Float): Float {
        if (value.isNaN() || value.isInfinite()) return fallback
        return value.coerceIn(0f, 1f)
    }

    /** 宽度比例：夹进 `[MIN_WIDTH_RATIO, MAX_WIDTH_RATIO]`，非法值退回默认。 */
    fun widthRatio(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT_WIDTH_RATIO
        return clampRatio(value, DEFAULT_WIDTH_RATIO).coerceIn(MIN_WIDTH_RATIO, MAX_WIDTH_RATIO)
    }

    /** 行数：夹进 `[MIN_ROWS, MAX_ROWS]`。 */
    fun rows(value: Int): Int = value.coerceIn(MIN_ROWS, MAX_ROWS)

    /** 整数夹取（`max < min` 时取下限）。 */
    fun clamp(value: Int, min: Int, max: Int): Int {
        if (max < min) return min
        return value.coerceIn(min, max)
    }

    /** 面板宽度（像素）：屏幕宽 × 宽度比例。 */
    fun width(displayWidth: Int, widthRatio: Float): Int {
        val available = displayWidth.coerceAtLeast(1)
        val w = Math.round(available * widthRatio(widthRatio))
        return clamp(w, 1, available)
    }

    /**
     * 面板高度（像素）。
     *
     * 当前句用 [mainSizePx]，其余行用 [subSizePx]：
     *
     * - 3 行 = 上一句(副) + 当前句(主) + 下一句(副)
     * - 2 行 = 当前句(主) + 下一句(副)
     * - 1 行 = 当前句(主)
     */
    fun height(mainSizePx: Float, subSizePx: Float, rows: Int): Int {
        val main = mainSizePx.coerceAtLeast(8f)
        val sub = subSizePx.coerceAtLeast(8f)
        val n = rows(rows)
        var total = main
        if (n >= 2) total += sub
        if (n >= 3) total += sub
        return (total * ROW_SPACING).toInt().coerceAtLeast(1)
    }

    /**
     * 面板左上角在屏幕像素里的位置。
     *
     * @return `[x, y]`
     */
    fun origin(
        displayWidth: Int,
        displayHeight: Int,
        panelWidth: Int,
        panelHeight: Int,
        xRatio: Float,
        yRatio: Float,
    ): IntArray {
        val screenW = displayWidth.coerceAtLeast(1)
        val screenH = displayHeight.coerceAtLeast(1)
        val w = clamp(panelWidth, 1, screenW)
        val h = clamp(panelHeight, 1, screenH)
        val centreX = Math.round(screenW * clampRatio(xRatio, DEFAULT_X_RATIO))
        val centreY = Math.round(screenH * clampRatio(yRatio, DEFAULT_Y_RATIO))
        val x = clamp(centreX - w / 2, 0, screenW - w)
        val y = clamp(centreY - h / 2, 0, screenH - h)
        return intArrayOf(x, y)
    }
}
