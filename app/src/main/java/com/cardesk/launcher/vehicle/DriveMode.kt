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
 * ## 读失败的哨兵值
 *
 * 车端读失败时返回的是 **253 / 254 / 255**（厂商的
 * `ICarFunction.COMMON_VALUE_ERROR` / `COMMON_VALUE_NONE` /
 * `IDriveMode.DRIVE_MODE_SELECTION_UNKNOWN`），
 * **不是模式号** —— 详见 [isReadError]。
 * 原始值从 `VehicleInfo` 那边读进来后，第一个进本对象的就是
 * [normalizeModeId] / [shortLabelForValue]，所以过滤放在这里，
 * 读到的值无论走哪条出口都不会被当成有效模式。
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

    /**
     * 舒适 HEV 模式（`IDriveMode.DRIVE_MODE_SELECTION_COMFORT_HEV`）。
     *
     * 厂商对**同一个值**给了两个名字：
     * `IDriveMode.DRIVE_MODE_SELECTION_COMFORT_HEV`（`IDriveMode.java:81`）
     * 与 `IDriveMode.DRIVE_MODE_SELECTION_NORMAL`（`IDriveMode.java:89`），
     * 都是 `570491153`。
     *
     * 车端读回的路径：`CarFunctionValueImpl.getFunctionValue` 里
     * `case COMFORT_HEV: return 570491153;`（`CarFunctionValueImpl.java:669-670`）——
     * 注意厂商在那一行写的是**字面量**，没有用常量名。
     *
     * 之前 [normalizeModeId] 里漏了它 → 被归 0 → 状态胶囊显示「待命」，
     * 实际上这台车（HEV）读回来的就是这一个值。
     *
     * 为什么是写死的值、而不是按名字反射解析：
     * `IDriveMode`（`com.ecarx.xui.adaptapi.car.vehicle.IDriveMode`）
     * 既不在 `AdaptapiConstants` 的候选类名单里，也不在 `ICarFunction` 的接口链上
     * （没有任何接口 `extends IDriveMode`），
     * 所以 `AdaptapiConstants.resolve("DRIVE_MODE_SELECTION_COMFORT_HEV")`
     * 在真机上**解析不到**。
     * 这个值跟同文件里其它 `SELECTION_*` 一样是厂商的固定枚举值，
     * 照同一套写法写死，并在上面注明了厂商的常量名与出处。
     */
    const val SELECTION_COMFORT_HEV = 570491153

    // —— 厂商的「读失败」哨兵值（**不是模式号**）——

    /**
     * `ICarFunction.COMMON_VALUE_ERROR` = 253（`ICarFunction.java:30`）。
     *
     * 驾驶模式读不到时车端就是返回它：
     * `CarFunctionValueImpl` 读 `DID_DM_Selected` 为空或值不支持时
     * 打 `"DID_DM_Selected is ..., Returning Error"` 然后 `return 253`
     * （`CarFunctionValueImpl.java:675-681`）。
     */
    const val READ_ERROR = 253

    /** `ICarFunction.COMMON_VALUE_NONE` = 254（`ICarFunction.java:31`）。 */
    const val READ_NONE = 254

    /**
     * `IDriveMode.DRIVE_MODE_SELECTION_UNKNOWN` = 255（`IDriveMode.java:98`）。
     *
     * 厂商专门为「驾驶模式未知」留的常量。
     */
    const val READ_UNKNOWN = 255

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
     *
     * 厂商的读失败哨兵（253 / 254 / 255）**不是模式号**：
     * 它们以前会落到 `else` 分支被当成「一个未知的模式号」，
     * 现在单独挡在前面 → 归到 [SELECTION_NONE]（= 没有模式）。
     */
    fun normalizeModeId(value: Int): Int = when (value) {
        in READ_ERROR..READ_UNKNOWN -> SELECTION_NONE
        SELECTION_NONE -> SELECTION_NONE
        SELECTION_ECO, SELECTION_COMFORT, SELECTION_SPORT, SELECTION_XC, SELECTION_HDC,
        SELECTION_PURE, SELECTION_HYBRID, SELECTION_POWER, SELECTION_SNOW,
        SELECTION_ECO_PHEV, SELECTION_TRACK, SELECTION_CUSTOM,
        SELECTION_COMFORT_HEV -> value
        else -> SELECTION_NONE
    }

    /**
     * 这个值是不是厂商的「读失败」哨兵（253 / 254 / 255）。
     *
     * 车端读失败时返回的是这些值，**不是模式号** —— 认出来才能判「读不到」。
     */
    fun isReadError(value: Int): Boolean = value in READ_ERROR..READ_UNKNOWN

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
        // 厂商的读失败哨兵（253/254/255）不是模式号 → 没有对应模式
        if (isReadError(value)) return null
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
            // 570491153：厂商同一个值有两个名字（COMFORT_HEV / NORMAL）。
            // 标签取「标准」而不是「舒适」——「舒适」已经是
            // `DRIVE_MODE_SELECTION_COMFORT`(570491138) 的标签了，两个模式不能同名。
            SELECTION_COMFORT_HEV -> "标准"
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
