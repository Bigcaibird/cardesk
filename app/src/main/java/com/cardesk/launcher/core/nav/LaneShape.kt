package com.cardesk.launcher.core.nav

/**
 * 车道线的**图形种类**。
 *
 * ## 为什么不是「一编码一张图」
 *
 * 用 84 张 PNG 资源表达车道线（`assets/hud_lanes/landr_<code>.png`
 * 见。那是**自己的美术资源**，不能复制
 * 所以我们改成 Canvas 自绘箭头。
 *
 * 但高德的编码本身是有规律的：`0~14` 是**后端**（底图）那一组，
 * `15+` 是**前端**（高亮）那一组，同一形状在两组里各出现一次
 * （例：`0` 与 `15` 都是直行、`1` 与 `16` 都是左转）。
 *
 * [LaneShapes.of] 把编码归并到 9 种图形；**认不出来的编码回退成「直行」**——
 * 这正好缺图时的兜底 （直行箭头
 *
 */
enum class LaneShape {
    /** 直行。 */
    STRAIGHT,

    /** 左转。 */
    LEFT,

    /** 右转。 */
    RIGHT,

    /** 掉头。 */
    UTURN,

    /** 直行 + 左转。 */
    STRAIGHT_LEFT,

    /** 直行 + 右转。 */
    STRAIGHT_RIGHT,

    /** 直行 + 左转 + 右转。 */
    STRAIGHT_BOTH,

    /** 公交专用道。 */
    BUS,

    /** 可变车道。 */
    VARIABLE,
}

/**
 * 车道线编码 → 图形种类。
 *
 * 映射表来自**读那 84 张图之后的形态归纳**（不复制资源本身）
 *
 * | 图形 | 编码 |
 * |---|---|
 * | 直行 | 0 / 15 / 28 / 51 / 63 / 68 / 79 |
 * | 左转 | 1 / 16 / 64 |
 * | 右转 | 3 / 11 / 12 / 18 / 43 / 65 |
 * | 掉头 | 6 / 9 / 20 / 22 / 66 / 67 |
 * | 直行+左转 | 2 / 10 / 17 / 24 / 25 / 26 / 31 / 39 / 40 / 41 / 48 / 49 / 50 / 56 / 71 / 73 / 75 / 81 |
 * | 直行+右转 | 4 / 5 / 7 / 13 / 14 / 19 / 27 / 29 / 32 / 33 / 35 / 42 / 44 / 45 / 46 / 47 / 52 / 53 / 57 / 58 / 59 / 60 / 72 / 74 / 76 / 77 / 78 / 80 / 82 / 83 |
 * | 直行+左右 | 8 / 21 / 23 / 30 / 34 / 36 / 37 / 38 |
 * | 公交 | 54 / 61 / 69 |
 * | 可变 | 55 / 62 / 70 |
 */
object LaneShapes {

    private val MAP: Map<Int, LaneShape> = buildMap {
        fun put(shape: LaneShape, codes: IntArray) = codes.forEach { put(it, shape) }
        put(LaneShape.STRAIGHT, intArrayOf(0, 15, 28, 51, 63, 68, 79))
        put(LaneShape.LEFT, intArrayOf(1, 16, 64))
        put(LaneShape.RIGHT, intArrayOf(3, 11, 12, 18, 43, 65))
        put(LaneShape.UTURN, intArrayOf(6, 9, 20, 22, 66, 67))
        put(
            LaneShape.STRAIGHT_LEFT,
            intArrayOf(2, 10, 17, 24, 25, 26, 31, 39, 40, 41, 48, 49, 50, 56, 71, 73, 75, 81),
        )
        put(
            LaneShape.STRAIGHT_RIGHT,
            intArrayOf(
                4, 5, 7, 13, 14, 19, 27, 29, 32, 33, 35, 42, 44, 45, 46, 47, 52, 53,
                57, 58, 59, 60, 72, 74, 76, 77, 78, 80, 82, 83,
            ),
        )
        put(LaneShape.STRAIGHT_BOTH, intArrayOf(8, 21, 23, 30, 34, 36, 37, 38))
        put(LaneShape.BUS, intArrayOf(54, 61, 69))
        put(LaneShape.VARIABLE, intArrayOf(55, 62, 70))
    }

    /** 用到的最大编码。 */
    const val MAX_CODE = 83

    /**
     * 编码 → 图形种类；**认不出来一律回退直行**。
     *
     * 回退直行 =缺图时的兜底。
     */
    fun of(code: Int): LaneShape = MAP[code] ?: LaneShape.STRAIGHT

    /** 该编码是否被映射（用于自检 / 日志）。 */
    fun isKnown(code: Int): Boolean = MAP.containsKey(code)

    /** 全部已知编码（升序），用于自检。 */
    val knownCodes: List<Int> get() = MAP.keys.sorted()
}
