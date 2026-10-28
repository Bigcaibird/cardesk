package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 空调业务逻辑层：把「用户意图」翻译成属性写入，并维护一份本地镜像。
 *
 * 设计要点：
 *  1. 乐观更新：先改本地镜像再写车辆，UI 立即响应；
 *  2. 防回弹：写完 [HOLD_MS] 内丢弃车辆迟到的旧回调；
 *  3. 纯逻辑：时间源、写入通道与读通道都由外部注入，因此可以在 JVM 上直接单测。
 *
 * ## 与对齐的联动规则
 *
 * | 操作 |行为 |
 * |---|---|
 * | 开机 | 若风量 < 1 → 风量置 1 **并真的写一次风量** |
 * | 开 A/C | 若未上电 → 上电 + 风量最低 1（只改本地，不写风量） |
 * | 开 AUTO | 同 A/C |
 * | 循环模式 | **只有内/外两态**，永不写 AUTO |
 * | 前除霜 | 写除霜 → 本地 power=true、fanSpeed=7（只改本地） |
 * | 开双区同步 | 先把主驾温度写回车端，再写 |
 * | 同步下调温 | 写温度后**必定**再写一次 |
 * | hold 窗口 | 只在 6 个开关类操作里设置，风量/吹风/座椅/温度**不设** |
 */
class HvacLogic(
    private val clock: () -> Long,
    private val write: (func: Int, zone: Int, value: Int) -> Boolean,
    private val writeTempCelsius: ((zone: Int, celsius: Float) -> Boolean)? = null,
    private val writeDefrost: ((func: Int, on: Boolean) -> Boolean)? = null,
    /** 写前从车端同步真值（每个 toggle 前先 sync*FromVehicle）；没有则用本地镜像。 */
    private val readSync: ((func: Int, zone: Int) -> Int?)? = null,
) {

    companion object {
        /** 写后屏蔽窗口：车机总线通常比 UI 慢，1.2s 内的回读一律视为旧值 */
        const val HOLD_MS = 1200L

        /**
         * 只有这 6 个开关类属性受 hold 保护（设置 hold 的 6 处）。
         * 风量/吹风/座椅/温度不设 hold —— 否则车机的归一化回声会被压掉 1.2 秒。
         */
        private val HOLDABLE = setOf(
            HvacIds.POWER, HvacIds.AC, HvacIds.AUTO, HvacIds.CIRCULATION,
            HvacIds.DEFROST_FRONT, HvacIds.DEFROST_REAR,
        )
    }

    var state = HvacState()
        private set

    var lastWriteOk = true
        private set

    var writeCount = 0
        private set

    /** 累计写入尝试次数（含失败）。用来判断「这次操作到底有没有写过车」。 */
    var writeAttempts = 0
        private set

    private var holdUntil = 0L

    fun isHolding(): Boolean = clock() < holdUntil

    // ------------------------------------------------------------------ 上行

    /** 收到车辆回调。返回本地镜像是否被更新。 */
    fun onRemote(func: Int, zone: Int, value: Int): Boolean {
        if (clock() < holdUntil && func in HOLDABLE) {
            // 排障场景：hold 窗口内的回调按设计会被丢弃，
            // 但现场表现是「车机变了 UI 没变」——这里留一条 DEBUG 说明原因。
            Logx.d(
                LogTags.HVAC,
                "hold 窗口内丢弃车辆回调",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "value" to value,
            )
            return false
        }
        val before = state
        applyRemote(func, zone, value)
        return state != before
    }

    /** 用一次全量读取的结果对齐镜像（不受 hold 限制，调用方负责在 hold 之后调用）。 */
    fun applySnapshot(snapshot: Map<Int, Int>, zone: Int = HvacIds.ZONE_DRIVER): Boolean {
        if (snapshot.isEmpty()) return false
        val before = state
        for ((func, value) in snapshot) applyRemote(func, zone, value)
        return state != before
    }

    /** 对齐单个区域属性（双区温度 / 座椅需要按 zone 单独读回时使用）。 */
    fun applyZone(func: Int, zone: Int, value: Int): Boolean {
        val before = state
        applyRemote(func, zone, value)
        return state != before
    }

    private fun applyRemote(func: Int, zone: Int, value: Int) {
        state = when (func) {
            HvacIds.POWER -> state.copy(power = value != 0)
            HvacIds.AC -> state.copy(ac = value != 0)
            HvacIds.AUTO -> state.copy(auto = value != 0)
            HvacIds.CIRCULATION -> state.copy(circulation = HvacCodec.normalizeCirculation(value))
            HvacIds.FAN_SPEED -> state.copy(
                fanLevel = HvacCodec.fanLevel(value),
                fanAuto = value == HvacIds.FAN_SPEED_AUTO,
            )
            HvacIds.DEFROST_FRONT -> state.copy(defrostFront = value != 0)
            HvacIds.DEFROST_REAR -> state.copy(defrostRear = value != 0)
            HvacIds.BLOWING -> state.copy(blowing = HvacCodec.normalizeBlowing(value))
            HvacIds.TEMP_DUAL -> if (value != 0) {
                state.copy(tempSynced = true, tempPassenger = state.tempDriver)
            } else {
                state.copy(tempSynced = false)
            }
            HvacIds.TEMP -> {
                val celsius = HvacCodec.clampTemp(HvacCodec.tempFromRaw(value))
                // handleCustomizeValueChanged:244-252 —— 同步态下两区永远相等
                when {
                    state.tempSynced -> state.copy(tempDriver = celsius, tempPassenger = celsius)
                    zone == HvacIds.ZONE_PASSENGER -> state.copy(tempPassenger = celsius)
                    else -> state.copy(tempDriver = celsius)
                }
            }
            HvacIds.SEAT_HEAT -> {
                val level = HvacCodec.seatLevel(HvacIds.SEAT_HEAT, value)
                if (zone == HvacIds.ZONE_DRIVER) state.copy(seatHeatDriver = level) else state.copy(seatHeatPassenger = level)
            }
            HvacIds.SEAT_VENT -> {
                val level = HvacCodec.seatLevel(HvacIds.SEAT_VENT, value)
                if (zone == HvacIds.ZONE_DRIVER) state.copy(seatVentDriver = level) else state.copy(seatVentPassenger = level)
            }
            else -> state
        }
    }

    // ------------------------------------------------------------------ 下行

    fun togglePower() {
        syncFromVehicle(HvacIds.POWER, HvacIds.AC, HvacIds.AUTO)
        val on = !state.power
        // 用户操作留痕：真机排查时要能回答「用户到底按了哪个键、按完车机变了没有」。
        Logx.event(LogTags.HVAC, "空调电源", "to" to if (on) "开" else "关")
        state = if (on) {
            state.copy(power = true)
        } else {
            state.copy(power = false, ac = false, auto = false, fanLevel = 0)
        }
        send(HvacIds.POWER, HvacIds.ZONE_GLOBAL, if (on) 1 else 0, op = "电源", hold = true)
        // togglePower:734-737 —— 开机且风量 < 1 时，把风量写成 1 档
        if (on && state.fanLevel < HvacIds.FAN_MIN_STEP) {
            val before = state.fanLevel
            state = state.copy(fanLevel = HvacIds.FAN_MIN_STEP)
            // 级联动作单独记一条：用户只按了电源，风量是系统自己抬上去的。
            Logx.i(
                LogTags.HVAC,
                "级联动作：开机后把风量抬到 1 档",
                "from" to before,
                "to" to HvacIds.FAN_MIN_STEP,
            )
            send(HvacIds.FAN_SPEED, HvacIds.ZONE_GLOBAL, HvacCodec.fanRaw(HvacIds.FAN_MIN_STEP), op = "级联-开机风量")
        }
    }

    fun toggleAc() {
        syncFromVehicle(HvacIds.POWER, HvacIds.AC, HvacIds.AUTO)
        val on = !state.ac
        Logx.event(LogTags.HVAC, "A/C", "to" to if (on) "开" else "关")
        state = if (on) {
            // toggleAc:929-935 —— 未上电则上电，风量最低 1（只改本地，不写风量）
            state.copy(ac = true, power = true, fanLevel = maxOf(state.fanLevel, HvacIds.FAN_MIN_STEP))
        } else {
            state.copy(ac = false)
        }
        send(HvacIds.AC, HvacIds.ZONE_GLOBAL, if (on) 1 else 0, op = "A/C", hold = true)
    }

    fun toggleAuto() {
        syncFromVehicle(HvacIds.POWER, HvacIds.AC, HvacIds.AUTO)
        val on = !state.auto
        Logx.event(LogTags.HVAC, "AUTO", "to" to if (on) "开" else "关")
        state = if (on) {
            // toggleAuto:944-950 —— 同 toggleAc
            state.copy(auto = true, power = true, fanLevel = maxOf(state.fanLevel, HvacIds.FAN_MIN_STEP))
        } else state.copy(auto = false)
        send(HvacIds.AUTO, HvacIds.ZONE_GLOBAL, if (on) 1 else 0, op = "AUTO", hold = true)
    }

    /**
     * 循环模式：**内 / 外两态**。
     *
     * 永远不会写 所以这里不做三态循环。
     */
    fun toggleCirculation() {
        syncFromVehicle(HvacIds.CIRCULATION)
        val next = if (state.circulation == 0) 1 else 0
        Logx.event(LogTags.HVAC, "循环模式", "to" to if (next == 0) "内循环" else "外循环")
        state = state.copy(circulation = next)
        send(HvacIds.CIRCULATION, HvacIds.ZONE_GLOBAL, HvacCodec.circulationRaw(next), op = "循环模式", hold = true)
    }

    /**
     * 风量加减
     * - 档位夹在 1..7；未开机且目标档位 ≥1 时先上电再写风量；
     * - 已经到最低档再往下走 = 关闭风量（连带 AC/AUTO 清零，见）。
     */
    fun stepFan(delta: Int) {
        if (delta < 0 && state.fanLevel <= HvacIds.FAN_MIN_STEP) {
            // stepFanSpeed 的 else 分支：关机 + 风量 0 + AC/AUTO 清零
            Logx.event(LogTags.HVAC, "风量", "action" to "减到底→关机", "delta" to delta)
            state = state.copy(power = false, ac = false, auto = false, fanLevel = 0)
            // 级联动作：关风量会连带把空调电源关掉，必须单独记，否则现场分不清是谁关的电。
            Logx.i(LogTags.HVAC, "级联动作：风量减到底 → 关空调电源", "delta" to delta)
            send(HvacIds.POWER, HvacIds.ZONE_GLOBAL, 0, op = "级联-风量归零关机")
            send(HvacIds.FAN_SPEED, HvacIds.ZONE_GLOBAL, HvacCodec.fanRaw(0), op = "级联-风量归零")
            return
        }
        val next = (state.fanLevel + delta).coerceIn(HvacIds.FAN_MIN_STEP, HvacIds.FAN_MAX)
        if (next == state.fanLevel && !state.fanAuto) {
            Logx.d(LogTags.HVAC, "风量操作被忽略（已到边界）", "delta" to delta, "level" to state.fanLevel)
            return
        }
        Logx.event(LogTags.HVAC, "风量", "from" to state.fanLevel, "to" to next, "delta" to delta)
        powerOnIfNeeded(next)
        state = state.copy(fanLevel = next, fanAuto = false, power = true)
        send(HvacIds.FAN_SPEED, HvacIds.ZONE_GLOBAL, HvacCodec.fanRaw(next), op = "风量")
    }

    /**
     * MAX 风量（MAX 键）
     * 「未开机先 `setPower(true)`，再 」——
     * 是 7↔3 两档的开关式，不是单步加减。
     */
    fun toggleMaxFan() {
        Logx.event(
            LogTags.HVAC,
            "MAX 风量",
            "from" to state.fanLevel,
            "to" to if (state.fanLevel >= HvacIds.FAN_MAX) HvacIds.FAN_MID else HvacIds.FAN_MAX,
        )
        powerOnIfNeeded(HvacIds.FAN_MAX)
        val target = if (state.fanLevel >= HvacIds.FAN_MAX) HvacIds.FAN_MID else HvacIds.FAN_MAX
        state = state.copy(fanLevel = target, fanAuto = false, power = true)
        send(HvacIds.FAN_SPEED, HvacIds.ZONE_GLOBAL, HvacCodec.fanRaw(target), op = "MAX风量")
    }

    /** 未上电时先写一次电源（stepFanSpeed / MAX 键的隐式上电）。 */
    private fun powerOnIfNeeded(targetFan: Int) {
        if (!state.power && targetFan >= 1) {
            state = state.copy(power = true)
            // 级联动作：用户没按电源，但系统替他把空调打开了。
            Logx.i(LogTags.HVAC, "级联动作：调风量前先上电", "targetFan" to targetFan)
            send(HvacIds.POWER, HvacIds.ZONE_GLOBAL, 1, op = "级联-上电")
        }
    }

    fun stepTemp(zone: Int, delta: Float) {
        if (state.tempSynced) {
            val next = HvacCodec.clampTemp(state.tempDriver + delta)
            if (next == state.tempDriver) {
                Logx.d(LogTags.HVAC, "温度操作被忽略（已到上下限）", "zone" to zone, "delta" to delta)
                return
            }
            Logx.event(
                LogTags.HVAC,
                "温度",
                "zone" to VehicleLog.zoneName(zone),
                "from" to state.tempDriver,
                "to" to next,
                "synced" to true,
            )
            state = state.copy(tempDriver = next, tempPassenger = next)
            sendTemp(HvacIds.ZONE_DRIVER, next, op = "温度")
            // stepSyncedTemp:851-858 —— 同步态下调温后必定再写一次双区标志
            send(HvacIds.TEMP_DUAL, HvacIds.ZONE_GLOBAL, 1, op = "级联-同步双区标志")
            return
        }
        val current = state.tempOf(zone)
        val next = HvacCodec.clampTemp(current + delta)
        if (next == current) {
            Logx.d(LogTags.HVAC, "温度操作被忽略（已到上下限）", "zone" to zone, "delta" to delta)
            return
        }
        Logx.event(
            LogTags.HVAC,
            "温度",
            "zone" to VehicleLog.zoneName(zone),
            "from" to current,
            "to" to next,
            "synced" to false,
        )
        state = if (zone == HvacIds.ZONE_DRIVER) state.copy(tempDriver = next) else state.copy(tempPassenger = next)
        sendTemp(zone, next, op = "温度")
    }

    /**
     * 双区温度同步。
     *
     * 开启时**先把主驾温度写回车端**再写双区标志
     * 否则两区实际不同温。
     */
    fun toggleSync() {
        val on = !state.tempSynced
        Logx.event(LogTags.HVAC, "双区同步", "to" to if (on) "开" else "关")
        state = if (on) {
            state.copy(tempSynced = true, tempPassenger = state.tempDriver)
        } else {
            state.copy(tempSynced = false)
        }
        if (on) {
            // 级联动作：开同步前必须把主驾温度写回车端，否则两区实际不同温。
            Logx.i(LogTags.HVAC, "级联动作：开双区同步前先回写主驾温度", "celsius" to state.tempDriver)
            sendTemp(HvacIds.ZONE_DRIVER, state.tempDriver, op = "级联-同步前回写主驾温度")
        }
        send(HvacIds.TEMP_DUAL, HvacIds.ZONE_GLOBAL, if (on) 1 else 0, op = "双区同步")
    }

    fun toggleDefrostFront() {
        syncFromVehicle(HvacIds.DEFROST_FRONT)
        val on = !state.defrostFront
        Logx.event(LogTags.HVAC, "前除霜", "to" to if (on) "开" else "关")
        state = if (on) {
            // toggleDefrostFront:970-975 —— 本地置 power/fan=7，但**不写**风量
            state.copy(defrostFront = true, power = true, fanLevel = HvacIds.FAN_MAX)
        } else {
            state.copy(defrostFront = false)
        }
        // 写前除霜用，不是读用的 0x10040200
        sendDefrost(HvacIds.DEFROST_FRONT_MAX, on, op = "前除霜")
        hold()
    }

    fun toggleDefrostRear() {
        val on = !state.defrostRear
        Logx.event(LogTags.HVAC, "后除霜", "to" to if (on) "开" else "关")
        state = state.copy(defrostRear = on)
        sendDefrost(HvacIds.DEFROST_REAR, on, op = "后除霜")
        hold()
    }

    fun setBlowing(index: Int) {
        val i = index.coerceIn(0, 6)
        if (i == state.blowing) {
            Logx.d(LogTags.HVAC, "吹风模式未变化", "index" to i)
            return
        }
        Logx.event(LogTags.HVAC, "吹风模式", "from" to state.blowing, "to" to i)
        state = state.copy(blowing = i)
        send(HvacIds.BLOWING, HvacIds.ZONE_GLOBAL, HvacCodec.blowingRaw(i), op = "吹风模式")
    }

    /**
     * 按**位掩码**设置吹风模式（face=1 / leg=2 / window=4）。
     *
     * 是 `applyMask(bit ^ shownMask())`
     *  把掩码映射回完整 id（等价 `0x10070100 + mask`）。
     */
    fun setBlowingMask(mask: Int) {
        val m = mask.coerceIn(1, 7)
        Logx.event(
            LogTags.HVAC,
            "吹风模式(位掩码)",
            "mask" to m,
            "from" to state.blowing,
            "to" to HvacCodec.blowingIndex(m),
        )
        setBlowing(HvacCodec.blowingIndex(m))
    }

    /** 座椅档位 0→1→2→3→0 循环；加热与通风互斥。 */
    fun cycleSeat(zone: Int, heat: Boolean) {
        val current = if (heat) state.seatHeatOf(zone) else state.seatVentOf(zone)
        val next = if (current >= 3) 0 else current + 1
        Logx.event(
            LogTags.HVAC,
            if (heat) "座椅加热" else "座椅通风",
            "zone" to VehicleLog.zoneName(zone),
            "from" to current,
            "to" to next,
        )
        state = when {
            heat && zone == HvacIds.ZONE_DRIVER ->
                state.copy(seatHeatDriver = next, seatVentDriver = if (next > 0) 0 else state.seatVentDriver)
            heat ->
                state.copy(seatHeatPassenger = next, seatVentPassenger = if (next > 0) 0 else state.seatVentPassenger)
            zone == HvacIds.ZONE_DRIVER ->
                state.copy(seatVentDriver = next, seatHeatDriver = if (next > 0) 0 else state.seatHeatDriver)
            else ->
                state.copy(seatVentPassenger = next, seatHeatPassenger = if (next > 0) 0 else state.seatHeatPassenger)
        }
        send(if (heat) HvacIds.SEAT_HEAT else HvacIds.SEAT_VENT, zone,
            HvacCodec.seatRaw(if (heat) HvacIds.SEAT_HEAT else HvacIds.SEAT_VENT, next),
            op = if (heat) "座椅加热" else "座椅通风")
        if (next > 0) {
            // 互斥：把另一功能写 0（失败也不影响主写入的本地状态）
            // 级联动作单独记：座椅互斥是系统自己补的写，失败时也要能看到。
            Logx.i(
                LogTags.HVAC,
                "级联动作：座椅加热/通风互斥清零",
                "zone" to VehicleLog.zoneName(zone),
                "cleared" to if (heat) "座椅通风" else "座椅加热",
            )
            write(if (heat) HvacIds.SEAT_VENT else HvacIds.SEAT_HEAT, zone, 0)
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 写前从车端同步真值。
     *
     * 每个开关类操作前都先 `sync*FromVehicle()`
     * 保证取反是基于车端真值而不是可能过期的本地镜像。
     */
    private fun syncFromVehicle(vararg funcs: Int) {
        val read = readSync ?: return
        for (f in funcs) {
            val v = runCatching { read(f, HvacIds.ZONE_GLOBAL) }.getOrNull() ?: continue
            applyRemote(f, HvacIds.ZONE_GLOBAL, v)
        }
    }

    /**
     * 写一个空调属性。
     *
     * [op] 是这次写入对应的操作名（电源 / A-C / AUTO / 风量 / 循环 / 吹风 / 除霜 / 温度 / 座椅…），
     * 真机上「点了没反应」时，要能一眼看出是**哪个操作的哪一次写**没进去。
     */
    private fun send(func: Int, zone: Int, raw: Int, op: String, hold: Boolean = false) {
        writeAttempts++
        val ok = write(func, zone, raw)
        lastWriteOk = ok
        if (ok) {
            writeCount++
            if (hold) hold()
            Logx.i(
                LogTags.HVAC,
                "写入空调属性",
                "op" to op,
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "zoneName" to VehicleLog.zoneName(zone),
                "value" to raw,
                "ok" to true,
            )
        } else {
            Logx.w(
                LogTags.HVAC,
                "写入空调属性失败",
                "op" to op,
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "zoneName" to VehicleLog.zoneName(zone),
                "value" to raw,
                "ok" to false,
            )
        }
    }

    /** 写温度（优先走 float 通道 [writeTempCelsius]）。 */
    private fun sendTemp(zone: Int, celsius: Float, op: String) {
        writeAttempts++
        val ok = writeTempCelsius?.invoke(zone, celsius)
            ?: write(HvacIds.TEMP, zone, HvacCodec.tempRaw(celsius))
        lastWriteOk = ok
        if (ok) writeCount++
        if (ok) {
            Logx.i(
                LogTags.HVAC,
                "写入空调温度",
                "op" to op,
                "func" to VehicleLog.hex(HvacIds.TEMP),
                "zone" to zone,
                "zoneName" to VehicleLog.zoneName(zone),
                "celsius" to celsius,
                "raw" to HvacCodec.tempRaw(celsius),
                "ok" to true,
            )
        } else {
            Logx.w(
                LogTags.HVAC,
                "写入空调温度失败",
                "op" to op,
                "func" to VehicleLog.hex(HvacIds.TEMP),
                "zone" to zone,
                "zoneName" to VehicleLog.zoneName(zone),
                "celsius" to celsius,
                "raw" to HvacCodec.tempRaw(celsius),
                "ok" to false,
            )
        }
    }

    /**
     * 写除霜。
     *
     * 除霜在不同固件上的 zone 约定不一致，走的是特殊回退
     * 先试「无 zone」的两参重载，
     * 再按 `{2, 1, 0}` 的顺序试三参重载。所以这里走 [writeDefrost] 专用通道，
     * 而不是通用的 [write]。
     */
    private fun sendDefrost(func: Int, on: Boolean, op: String) {
        writeAttempts++
        val ok = writeDefrost?.invoke(func, on)
            ?: write(func, HvacIds.ZONE_GLOBAL, if (on) 1 else 0)
        lastWriteOk = ok
        if (ok) {
            writeCount++
            hold()
            Logx.i(
                LogTags.HVAC,
                "写入空调除霜",
                "op" to op,
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "on" to on,
                "ok" to true,
            )
        } else {
            Logx.w(
                LogTags.HVAC,
                "写入空调除霜失败",
                "op" to op,
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "on" to on,
                "ok" to false,
            )
        }
    }

    /** 开启 hold 窗口（只有 6 个开关类操作会调）。 */
    private fun hold() {
        holdUntil = clock() + HOLD_MS
    }
}
