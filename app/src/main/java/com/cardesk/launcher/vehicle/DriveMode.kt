package com.cardesk.launcher.vehicle

/**
 * 驾驶模式。
 *
 * 从车机的「驾驶模式选择」功能号 `570491136`（zone = 2）读当前模式
 * 然后用 `shortLabelForValue` 映射成两字短标签，状态胶囊显示
 * 「驾驶模式 · &lt;短标签&gt;」。
 *
 * 读不到时短标签是「待命」。
 *
 * 这里是纯 Kotlin（不碰 Android），可在 JVM 单测里覆盖。
 */
object DriveMode {

    /** 驾驶模式选择功能号。 */
    const val FUNCTION_DRIVE_MODE_SELECT = 570491136

    /** 驾驶模式读取的 zone（= 2）。 */
    const val ZONE = 2

    /** 读不到模式时的短标签（`currentShortLabel` 的兜底）。 */
    const val UNKNOWN_LABEL = "待命"

    // ——`DriveModeController.SELECTION_*` ——
    const val SELECTION_NONE = 0
    const val SELECTION_ECO = 570491137
    const val SELECTION_COMFORT = 570491138
    const val SELECTION_SPORT = 570491139
    const val SELECTION_XC = 570491140
    const val SELECTION_HDC = 570491141
    const val SELECTION_PURE = 570491142
    const val SELECTION_HYBRID = 570491143
    const val SELECTION_POWER = 570491144
    const val SELECTION_SNOW = 570491145
    const val SELECTION_ECO_PHEV = 570491152
    const val SELECTION_TRACK = 570491157
    const val SELECTION_CUSTOM = 570491200

    /** 设置页里「目标驾驶模式」的完整名称。 */
    val UI_MODE_LABELS = arrayOf(
        "经济模式", "舒适模式", "运动模式", "赛道模式", "个性化模式", "电动模式",
        "混动模式", "动力模式", "越野模式", "陡坡缓降模式", "经济模式PHEV",
    )

    /** 设置页里「目标驾驶模式」对应的功能号。 */
    val UI_MODE_VALUES = intArrayOf(
        SELECTION_ECO, SELECTION_COMFORT, SELECTION_SPORT, SELECTION_TRACK, SELECTION_CUSTOM,
        SELECTION_PURE, SELECTION_HYBRID, SELECTION_POWER, SELECTION_XC, SELECTION_HDC,
        SELECTION_ECO_PHEV,
    )

    /**
     * 把功能号归一到标准模式号。
     */
    fun normalizeModeId(value: Int): Int = when (value) {
        SELECTION_NONE -> SELECTION_NONE
        SELECTION_ECO, SELECTION_COMFORT, SELECTION_SPORT, SELECTION_XC, SELECTION_HDC,
        SELECTION_PURE, SELECTION_HYBRID, SELECTION_POWER, SELECTION_SNOW,
        SELECTION_ECO_PHEV, SELECTION_TRACK, SELECTION_CUSTOM -> value
        else -> SELECTION_NONE
    }

    /**
     * 短标签。
     *
     * 另有 1..11「界面序号」形式（同方法开头的 `i3 <= 11` 分支）
     * 那是旧协议的枚举序号，也要认。
     *
     * @return null 表示这个值没有对应模式
     */
    fun shortLabelForValue(value: Int): String? {
        if (value <= 0) return null
        if (value in 1..11) {
            return when (value) {
                1 -> "经济"
                2 -> "舒适"
                3 -> "运动"
                4 -> "越野"
                5 -> "陡坡缓降"
                6 -> "电动"
                7 -> "混动"
                8 -> "动力"
                9 -> "赛道"
                10 -> "经济PHEV"
                11 -> "个性"
                else -> null
            }
        }
        return when (normalizeModeId(value)) {
            SELECTION_ECO -> "经济"
            SELECTION_COMFORT -> "舒适"
            SELECTION_SPORT -> "运动"
            SELECTION_XC -> "越野"
            SELECTION_HDC -> "陡坡缓降"
            SELECTION_PURE -> "电动"
            SELECTION_HYBRID -> "混动"
            SELECTION_POWER -> "动力"
            SELECTION_SNOW -> "雪地"
            SELECTION_ECO_PHEV -> "经济PHEV"
            SELECTION_TRACK -> "赛道"
            SELECTION_CUSTOM -> "个性"
            else -> null
        }
    }

    /**
     * 短标签，带「待命」兜底。
     */
    fun currentShortLabel(value: Int): String = shortLabelForValue(value) ?: UNKNOWN_LABEL

    /** 设置页下拉里的完整名称。 */
    fun fullLabel(value: Int): String {
        val idx = UI_MODE_VALUES.indexOf(normalizeModeId(value))
        return if (idx >= 0) UI_MODE_LABELS[idx] else UI_MODE_LABELS[0]
    }
}
