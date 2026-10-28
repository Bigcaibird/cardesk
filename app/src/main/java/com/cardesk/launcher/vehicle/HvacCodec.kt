package com.cardesk.launcher.vehicle

import kotlin.math.roundToInt

/**
 * 车控值的编解码：把「人话」转成属性号能接受的值，并把旧固件的怪编码归一化。
 *
 * 所有映射都逐条
 *  - `clampTemp`（夹范围后吸附到 0.5℃）
 *  -（支持 `base+level` 与裸 `1..7` 两套编码）
 *  -（旧编码 0..6 是**另一套顺序**）
 *  - `normalizeCirculation`
 *  -（越界值归 0，不归最大档）
 */
object HvacCodec {

    fun tempRaw(celsius: Float): Int = (celsius * 2f).roundToInt()

    fun tempFromRaw(raw: Int): Float = raw / 2f

    /**
     * 夹到 [HvacIds.TEMP_MIN]..[HvacIds.TEMP_MAX] 并**吸附到 0.5℃**。
     *
     * `Math.round(f3 * 2.0f) / 2.0f`。
     * 少了这步，车机回报 23.3℃ 会原样显示，而显示 23.5℃。
     */
    fun clampTemp(celsius: Float): Float {
        val c = celsius.coerceIn(HvacIds.TEMP_MIN, HvacIds.TEMP_MAX)
        return (c * 2f).roundToInt() / 2f
    }

    // ---- 风量 ----

    /**
     * 风量档位解码。
     *
     * 支持两套编码：
     *  - `FAN_SPEED + level`（0x10020101..0x10020107）
     *  - 裸 `1..7`
     * 以及 `FAN_SPEED_AUTO` → 7、`0` / `FAN_SPEED` → 0。
     */
    fun fanLevel(raw: Int): Int = when {
        raw == HvacIds.FAN_SPEED_AUTO -> HvacIds.FAN_MAX
        raw == 0 || raw == HvacIds.FAN_SPEED -> 0
        raw in 1..7 -> raw
        raw in (HvacIds.FAN_SPEED + 1)..(HvacIds.FAN_SPEED + 7) -> raw - HvacIds.FAN_SPEED
        else -> 0
    }

    /**
     * 风量写入值（`setFuncVal(268566784, 1, level + 268566784)`）。
     *
     * **关风量时写 0**，不是 `FAN_SPEED + 0`——在关机的分支里
     * 写的是 `setFuncVal(268566784, 1, 0)`。
     */
    fun fanRaw(level: Int): Int =
        if (level <= 0) 0 else HvacIds.FAN_SPEED + level.coerceIn(HvacIds.FAN_MIN_STEP, HvacIds.FAN_MAX)

    // ---- 循环 ----

    /**
     * 循环模式归一。
     *
     * 内循环 = `CIRC_INNER` 或裸 `1`；自动 = `CIRC_AUTO`；其余归外循环。
     */
    fun normalizeCirculation(raw: Int): Int = when (raw) {
        HvacIds.CIRC_INNER, 1 -> 0
        HvacIds.CIRC_AUTO, 3 -> 2
        HvacIds.CIRC_OUTSIDE, 2, 0 -> 1
        else -> 1
    }

    /**
     * 循环模式写入值。
     *
     * **只有内/外两态**
     * 永远不会写 （0x10030103）。
     */
    fun circulationRaw(index: Int): Int = when (index) {
        0 -> HvacIds.CIRC_INNER
        else -> HvacIds.CIRC_OUTSIDE
    }

    // ---- 吹风模式 ----

    /**
     * 吹风模式归一。
     *
     * 固件回报的 `0x1007010x` 直接映射成内部下标；
     * 旧固件回报的**裸 0..6 是另一套顺序**：
     * `0→吹脚 1→吹脸 2→吹窗 3→脚+窗 4→脸+脚 5→脸+窗 6→全吹`。
     */
    fun normalizeBlowing(raw: Int): Int = when (raw) {
        HvacIds.BLOWING_FACE -> 0
        HvacIds.BLOWING_LEG -> 1
        HvacIds.BLOWING_FACE_LEG -> 2
        HvacIds.BLOWING_WINDOW -> 3
        HvacIds.BLOWING_FACE_WINDOW -> 4
        HvacIds.BLOWING_LEG_WINDOW -> 5
        HvacIds.BLOWING_ALL -> 6
        // 旧固件的裸 0..6 —— 顺序与内部下标**不同**
        0 -> 1
        1 -> 0
        2 -> 3
        3 -> 5
        4 -> 2
        5 -> 4
        6 -> 6
        else -> 2
    }

    /** 内部下标 → 写入车机的完整 id（`0x10070101`..`0x10070107`）。 */
    fun blowingRaw(index: Int): Int = HvacIds.BLOWING_FACE + index.coerceIn(0, 6)

    /**
     * 内部下标 →**位掩码**（face=1 / leg=2 / window=4）。
     *
     * 把吹风模式拆成三个方向位
     * 气候页的「吹面/吹脚/吹窗」按钮就是按位异或切换的。
     */
    fun blowingMask(index: Int): Int = when (index.coerceIn(0, 6)) {
        0 -> 1  // 吹脸
        1 -> 2  // 吹脚
        2 -> 3  // 脸+脚
        3 -> 4  // 吹窗
        4 -> 5  // 脸+窗
        5 -> 6  // 脚+窗
        else -> 7  // 全吹
    }

    /** 位掩码 → 内部下标。 */
    fun blowingIndex(mask: Int): Int = (mask - 1).coerceIn(0, 6)

    // ---- 座椅档位（兼容「裸 0..3」与「base+档位」两种固件返回） ----

    /**
     * 座椅档位解码。
     *
     * 只有 `base+1..base+3` 才是有效档位；其余越界值一律归 **0**（不是最大档）。
     */
    fun seatLevel(base: Int, raw: Int): Int = when {
        raw <= 0 -> 0
        raw in (base + 1)..(base + 3) -> raw - base
        raw in 1..3 -> raw
        else -> 0
    }

    fun seatRaw(base: Int, level: Int): Int =
        if (level <= 0) 0 else base + level.coerceIn(1, 3)

    // ---- AOSP Car 通道的值翻译 ----

    /**
     * 把 AdaptAPI 编码的值翻译成 AOSP `android.car` 期望的原生值。
     *
     * 两条通道对同一个物理量的表示完全不同：
     *  - AdaptAPI 用「功能号 + 档位」偏移编码（风量 = FAN_SPEED + level，循环/吹风 = 完整功能 id）；
     *  - AOSP 用纯档位（风量 0..7、循环 = RECIRC 布尔、方向 = 枚举下标、温度 = 摄氏度）。
     *
     * 直接把 AdaptAPI 的编码写进 AOSP 通道会被固件拒绝——表现同样是「点了没反应」。
     * 返回类型按 AOSP `VehiclePropertyIds` 的定义给出（Boolean / Int / Float）。
     */
    fun toCarLibValue(func: Int, value: Int): Any = when (func) {
        HvacIds.POWER, HvacIds.AC, HvacIds.AUTO, HvacIds.TEMP_DUAL -> value != 0
        // AOSP 只有「内循环 / 外循环」两态：AUTO 无对应属性，先按外循环处理
        HvacIds.CIRCULATION -> normalizeCirculation(value) == 0
        HvacIds.FAN_SPEED -> fanLevel(value)
        HvacIds.DEFROST_FRONT, HvacIds.DEFROST_FRONT_MAX, HvacIds.DEFROST_REAR -> value != 0
        HvacIds.TEMP -> tempFromRaw(value)
        HvacIds.SEAT_HEAT, HvacIds.SEAT_VENT -> seatLevel(func, value)
        HvacIds.BLOWING -> normalizeBlowing(value)
        HvacIds.FUNC_360 -> value != 0
        else -> value
    }

    /**
     * 把功能号归一到「读属性」用的 id。
     *
     * 前除霜读写都用 `DEFROST_FRONT_MAX`（0x10040200，里另一个
     * `DEFROST_FRONT` 常量只声明未使用；AOSP 通道里两者对应同一个属性，
     * 所以这里统一映射到 `DEFROST_FRONT`。
     */
    fun canonicalFunc(func: Int): Int = when (func) {
        HvacIds.DEFROST_FRONT_MAX -> HvacIds.DEFROST_FRONT
        else -> func
    }

    /**
     * 把 AOSP Car 通道读到的原生值翻译回内部统一编码。
     *
     * 内部编码沿用 AdaptAPI 那一套（功能号 + 档位偏移），这样 [HvacLogic]
     * 镜像逻辑不需要知道数据来自哪条通道；每条通道只在边界上做一次翻译。
     *
     * @param value AOSP 侧的值：Boolean（开关）/ Int（档位）/ Float（温度）
     */
    fun fromCarLibValue(func: Int, value: Any): Int = when (func) {
        HvacIds.POWER, HvacIds.AC, HvacIds.AUTO, HvacIds.TEMP_DUAL -> asOnOff(value)
        // AOSP 只有「内循环 / 外循环」两态：true = RECIRC on = 内循环
        HvacIds.CIRCULATION -> if (asOnOff(value) == 0) HvacIds.CIRC_OUTSIDE else HvacIds.CIRC_INNER
        HvacIds.FAN_SPEED -> fanRaw(asInt(value))
        HvacIds.DEFROST_FRONT, HvacIds.DEFROST_FRONT_MAX, HvacIds.DEFROST_REAR -> asOnOff(value)
        HvacIds.TEMP -> when (value) {
            is Float -> tempRaw(value)
            is Int -> value
            else -> 0
        }
        HvacIds.SEAT_HEAT, HvacIds.SEAT_VENT -> seatRaw(func, seatLevel(func, asInt(value)))
        HvacIds.BLOWING -> blowingRaw(normalizeBlowing(asInt(value)))
        HvacIds.FUNC_360 -> asOnOff(value)
        else -> asInt(value)
    }

    private fun asOnOff(value: Any): Int = when (value) {
        is Boolean -> if (value) 1 else 0
        is Int -> if (value != 0) 1 else 0
        else -> 0
    }

    private fun asInt(value: Any): Int = when (value) {
        is Int -> value
        is Float -> value.toInt()
        is Boolean -> if (value) 1 else 0
        else -> 0
    }
}
