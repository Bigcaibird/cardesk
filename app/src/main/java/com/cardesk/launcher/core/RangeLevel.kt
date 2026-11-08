package com.cardesk.launcher.core

/**
 * 续航分级（第136轮，**纯逻辑**，可 JVM 单测）。
 *
 * ## 分级口径（用户第136轮需求）
 *
 * | 剩余续航 | 级别 | 卡片上的表现 |
 * | --- | --- | --- |
 * | `> 100 km` | [Level.NORMAL] | 常态色（`Skin.TEXT`） |
 * | `50 km < r <= 100 km` | [Level.WARN] | **黄色**（`Skin.WARN`） |
 * | `r <= 50 km` | [Level.DANGER] | **红色**（`Skin.DANGER`） |
 * | 读不到 | [Level.UNKNOWN] | 常态色 + `--`（**不猜**） |
 *
 * ## 为什么把阈值写成常量而不是散在绘制代码里
 *
 * 阈值是「产品口径」，不是绘制细节：
 * 同一个口径以后还要用于**语音播报 / 弹窗提醒 / 设置页阈值**，
 * 散在 `drawRangeCard` 里就会出现第二处、第三处不同口径。
 * 所以这里单独一个纯函数，绘制层只负责「按级别取颜色」。
 *
 * ## 为什么 `50` 归红色而不是黄色
 *
 * 用户给的区间是「小于等于 100KM **且大于** 50KM 用黄色；小于等于 50KM 用红色」——
 * 50 落在红色那一侧（`<= 50`）。所以 [WARN_KM] 是**开区间下界**：
 * `50 < r <= 100` 才是黄色。
 */
object RangeLevel {

    /** 黄色上限（km）：`r <= 100` 且 `r > 50`。 */
    const val WARN_KM: Int = 100

    /** 红色上限（km）：`r <= 50`。 */
    const val DANGER_KM: Int = 50

    /** 续航分级。 */
    enum class Level {
        /** 读不到。 */
        UNKNOWN,

        /** 正常（> 100 km）。 */
        NORMAL,

        /** 偏低（50 < r <= 100 km）—— 黄色。 */
        WARN,

        /** 很低（r <= 50 km）—— 红色。 */
        DANGER,
    }

    /**
     * 剩余续航 → 级别。
     *
     * @param rangeKm 剩余续航（km）；`null` / 负数 = 读不到
     */
    fun levelOf(rangeKm: Int?): Level = when {
        rangeKm == null || rangeKm < 0 -> Level.UNKNOWN
        rangeKm <= DANGER_KM -> Level.DANGER
        rangeKm <= WARN_KM -> Level.WARN
        else -> Level.NORMAL
    }

    /** 级别的一句话说明（设置页 / 诊断用）。 */
    fun labelOf(level: Level): String = when (level) {
        Level.UNKNOWN -> "读不到"
        Level.NORMAL -> "正常"
        Level.WARN -> "偏低"
        Level.DANGER -> "很低"
    }
}
