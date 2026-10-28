package com.cardesk.launcher.vehicle

/**
 * 车控属性号。数值取自 ECARX AdaptAPI 的 function id 编码：
 * 0x10 | 功能组 | 子项 | 档位。
 *
 * 这套 ID 是与车机固件之间的约定，跨固件版本可能变化；本项目把所有 ID
 * 集中在这里，并保留「读回值归一化」，换车机时只改本文件即可。
 */
object HvacIds {
    // 电源簇
    const val POWER = 0x10010100
    const val AUTO = 0x10010200
    const val AC = 0x10010300
    const val AC_MAX = 0x10010400

    // 风量
    const val FAN_SPEED = 0x10020100
    const val FAN_SPEED_AUTO = 0x1002010A

    // 循环
    const val CIRCULATION = 0x10030100
    const val CIRC_INNER = 0x10030101
    const val CIRC_OUTSIDE = 0x10030102
    const val CIRC_AUTO = 0x10030103

    // 除霜
    /**
     * 读：前除霜状态（= 268697856 = 0x10040100）。
     *
     * 注意里这个常量只声明未使用——前除霜实际读写都用 [DEFROST_FRONT_MAX]。
     */
    const val DEFROST_FRONT = 0x10040100

    /**
     * 前除霜（= 268698112 = 0x10040200）。
     *
     * **读和写都用它**
     *  - 写：`setDefrostVal(FUNC_DEFROST_FRONT_MAX, on)`
     *  - 读：`getDefrostVal(carFunction, FUNC_DEFROST_FRONT_MAX)`
     */
    const val DEFROST_FRONT_MAX = 0x10040200

    /** 后除霜（= 268698368 = 0x10040300）。 */
    const val DEFROST_REAR = 0x10040300

    // 座椅
    const val SEAT_VENT = 0x10050100
    const val SEAT_HEAT = 0x10050200

    // 温度
    const val TEMP = 0x10060100
    const val TEMP_DUAL = 0x10060500

    // 吹风模式
    const val BLOWING = 0x10070100
    const val BLOWING_FACE = 0x10070101
    const val BLOWING_LEG = 0x10070102
    const val BLOWING_FACE_LEG = 0x10070103
    const val BLOWING_WINDOW = 0x10070104
    const val BLOWING_FACE_WINDOW = 0x10070105
    const val BLOWING_LEG_WINDOW = 0x10070106
    const val BLOWING_ALL = 0x10070107

    // 360 全景
    const val FUNC_360 = 0x23030100
    const val FUNC_360_VIEW = 0x23031100

    // 区域
    const val ZONE_DRIVER = 1
    const val ZONE_PASSENGER = 4

    /**
     * 全局空调属性（电源/AC/AUTO/风量/循环/吹风/双区）写入时使用的 zone。
     *
     * 每个全局写入都是 `setFuncVal(func, 1, value)`
     * 电源 A/C AUTO 风量 循环 吹风 双区，
     * **不是 0**。zone 传 0 在真机上会被固件拒绝（表现为「点了没反应」）。
     */
    const val ZONE_GLOBAL = ZONE_DRIVER

    /** 除霜写入时的 zone 候选顺序（`setDefrostVal` 的 {2, 1, 0} 回退）。 */
    val DEFROST_ZONES = intArrayOf(2, 1, 0)

    // 温度范围
    const val TEMP_MIN = 16f
    const val TEMP_MAX = 32f
    const val TEMP_STEP = 0.5f

    const val FAN_MIN = 0
    const val FAN_MAX = 7

    /** 风量加减时的下界：把档位夹在 1..7（0 表示关机）。 */
    const val FAN_MIN_STEP = 1

    /** MAX 风量的中间档（7↔3 两态切换的落点）。 */
    const val FAN_MID = 3

    /** 空调面板里的吹风模式顺序（UI 索引 -> 可读文案） */
    val BLOW_LABELS = arrayOf("吹脸", "吹脚", "脸+脚", "吹窗", "脸+窗", "脚+窗", "全吹")
}
