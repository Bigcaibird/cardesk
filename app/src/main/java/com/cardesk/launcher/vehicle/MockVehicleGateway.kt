package com.cardesk.launcher.vehicle

import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 模拟车辆：自己维护一份"车端状态"，接收属性写入后延迟 120ms 回显回调，
 * 用来在没有真车/没有系统权限的开发机上完整演示交互。
 */
class MockVehicleGateway(
    private val main: Handler = Handler(Looper.getMainLooper()),
) : VehicleGateway {

    override val id: String = "mock"
    override val displayName: String = "模拟车辆"
    override var on360State: ((Boolean) -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<(Int, Int, Int) -> Unit>()
    private var car = HvacState()
    private var connected = false
    private var pacOn = false

    /** 模拟环境数据，供桌面卡片使用 */
    var simAmbientTemp: Float = 26.5f
    var simRangeKm: Int = 386
    var speedKmh: Int = 0
    var gear: String = "P"
    /**
     * 模拟胎压（**kPa**）。
     *
     * 第三十七轮：单位从 bar 改成 kPa —— 真车 TPMS 报的就是 kPa
     * （诊断包：`240.00,227.50,237.50,235.00`），
     * 模拟值对齐真车，模拟器上看到的效果与真机一致。
     */
    val simTirePressure = floatArrayOf(240f, 240f, 238f, 235f)

    // -------------------------------------------------- 车辆概览信号（模拟值）

    override fun rangeKm(): Int? = simRangeKm

    override fun fuelPercent(): Int? = 62

    override fun ambientTempC(): Float? = simAmbientTemp

    override fun tirePressures(): FloatArray? = simTirePressure.copyOf()

    // 车身状态：模拟环境里四门 / 四窗 / 两个盖**默认全关**
    // （真车通道读不到时是 null，UI 显示「--」；这里给的是「模拟车辆确实关着」）。
    val simDoorsClosed: BooleanArray = booleanArrayOf(true, true, true, true)

    /**
     * 四窗开闭（顺序 FL / FR / RL / RR，true = 开）。
     *
     * **可变**：模拟通道收到「开窗 / 关窗」指令时直接改这里，
     * 桌面上的车况卡与续航卡就能像真车一样跟着变——
     * 否则模拟环境里点车窗只会闪一下又被「读回来的常量」覆盖回去。
     */
    @Volatile
    var simWindowsOpen: BooleanArray = booleanArrayOf(false, false, false, false)
        private set

    val simHoodClosed: Boolean = true
    val simTrunkClosed: Boolean = true

    /** 模拟通道自己消化车窗开闭（真车通道不走这里）。 */
    override fun setWindowsOpen(open: Boolean): Boolean {
        simWindowsOpen = BooleanArray(4) { open }
        Logx.event(
            LogTags.WINDOW,
            if (open) "模拟车辆开窗" else "模拟车辆关窗",
            "open" to open,
        )
        return true
    }

    override fun connect(onResult: (Boolean) -> Unit) {
        connected = true
        // 模拟通道只记 DEBUG：真机上「车控没反应」时要能一眼区分「走的是模拟车」。
        Logx.d(LogTags.VEHICLE, "模拟车辆通道连接", "channel" to id)
        main.post { onResult(true) }
    }

    override fun disconnect() {
        connected = false
        Logx.d(LogTags.VEHICLE, "模拟车辆通道断开", "channel" to id)
    }

    override fun isConnected(): Boolean = connected

    override fun registerListener(listener: (Int, Int, Int) -> Unit) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: (Int, Int, Int) -> Unit) {
        listeners.remove(listener)
    }

    override fun setHvac(func: Int, zone: Int, value: Int): Boolean {
        if (!connected) {
            Logx.w(LogTags.VEHICLE, "模拟车辆未连接，写入被拒绝", "func" to VehicleLog.hex(func), "zone" to zone, "value" to value)
            return false
        }
        Logx.d(
            LogTags.VEHICLE,
            "模拟车辆写入",
            "func" to VehicleLog.hex(func),
            "name" to VehicleLog.funcName(func),
            "zone" to zone,
            "value" to value,
        )
        car = reduce(car, func, zone, value)
        if (func == HvacIds.FUNC_360) {
            pacOn = value != 0
            main.post { on360State?.invoke(pacOn) }
            return true
        }
        // 模拟总线延迟后回显给订阅方
        main.postDelayed({ listeners.forEach { it(func, zone, value) } }, 120L)
        return true
    }

    override fun setTemp(zone: Int, celsius: Float): Boolean {
        if (!connected) {
            Logx.w(LogTags.VEHICLE, "模拟车辆未连接，温度写入被拒绝", "zone" to zone, "celsius" to celsius)
            return false
        }
        Logx.d(LogTags.VEHICLE, "模拟车辆写入温度", "zone" to zone, "celsius" to celsius)
        val c = HvacCodec.clampTemp(celsius)
        car = if (car.tempSynced) {
            car.copy(tempDriver = c, tempPassenger = c)
        } else if (zone == HvacIds.ZONE_DRIVER) {
            car.copy(tempDriver = c)
        } else {
            car.copy(tempPassenger = c)
        }
        val raw = HvacCodec.tempRaw(c)
        main.postDelayed({ listeners.forEach { it(HvacIds.TEMP, zone, raw) } }, 120L)
        return true
    }

    override fun readAll(): Map<Int, Int> {
        val c = car
        return buildMap {
            put(HvacIds.POWER, if (c.power) 1 else 0)
            put(HvacIds.AC, if (c.ac) 1 else 0)
            put(HvacIds.AUTO, if (c.auto) 1 else 0)
            put(HvacIds.CIRCULATION, HvacCodec.circulationRaw(c.circulation))
            put(HvacIds.FAN_SPEED, if (c.fanAuto) HvacIds.FAN_SPEED_AUTO else HvacCodec.fanRaw(c.fanLevel))
            put(HvacIds.DEFROST_FRONT, if (c.defrostFront) 1 else 0)
            put(HvacIds.DEFROST_REAR, if (c.defrostRear) 1 else 0)
            put(HvacIds.BLOWING, HvacCodec.blowingRaw(c.blowing))
            put(HvacIds.TEMP_DUAL, if (c.tempSynced) 1 else 0)
            put(HvacIds.TEMP, HvacCodec.tempRaw(c.tempDriver))
            put(HvacIds.SEAT_HEAT, HvacCodec.seatRaw(HvacIds.SEAT_HEAT, c.seatHeatDriver))
            put(HvacIds.SEAT_VENT, HvacCodec.seatRaw(HvacIds.SEAT_VENT, c.seatVentDriver))
        }
    }

    override fun readValue(func: Int, zone: Int): Int? {
        val c = car
        return when (func) {
            HvacIds.TEMP -> HvacCodec.tempRaw(c.tempOf(zone))
            HvacIds.SEAT_HEAT -> HvacCodec.seatRaw(HvacIds.SEAT_HEAT, c.seatHeatOf(zone))
            HvacIds.SEAT_VENT -> HvacCodec.seatRaw(HvacIds.SEAT_VENT, c.seatVentOf(zone))
            else -> null
        }
    }

    private fun reduce(s: HvacState, func: Int, zone: Int, value: Int): HvacState = when (func) {
        HvacIds.POWER -> if (value != 0) {
            s.copy(power = true, fanLevel = maxOf(s.fanLevel, 2))
        } else {
            s.copy(power = false, ac = false, auto = false, fanLevel = 0)
        }
        HvacIds.AC -> if (value != 0) s.copy(ac = true, power = true, fanLevel = maxOf(s.fanLevel, 1)) else s.copy(ac = false)
        HvacIds.AUTO -> if (value != 0) s.copy(auto = true, power = true) else s.copy(auto = false)
        HvacIds.FAN_SPEED -> if (value == HvacIds.FAN_SPEED_AUTO) {
            s.copy(fanAuto = true, fanLevel = HvacIds.FAN_MAX, power = true)
        } else {
            s.copy(fanLevel = HvacCodec.fanLevel(value), fanAuto = false, power = true)
        }
        HvacIds.CIRCULATION -> s.copy(circulation = HvacCodec.normalizeCirculation(value))
        HvacIds.DEFROST_FRONT, HvacIds.DEFROST_FRONT_MAX -> if (value != 0) {
            s.copy(defrostFront = true, power = true, fanLevel = HvacIds.FAN_MAX)
        } else {
            s.copy(defrostFront = false)
        }
        HvacIds.DEFROST_REAR -> s.copy(defrostRear = value != 0)
        HvacIds.BLOWING -> s.copy(blowing = HvacCodec.normalizeBlowing(value))
        HvacIds.TEMP_DUAL -> if (value != 0) s.copy(tempSynced = true, tempPassenger = s.tempDriver) else s.copy(tempSynced = false)
        HvacIds.TEMP -> {
            val c = HvacCodec.clampTemp(HvacCodec.tempFromRaw(value))
            if (zone == HvacIds.ZONE_PASSENGER && !s.tempSynced) s.copy(tempPassenger = c)
            else if (s.tempSynced) s.copy(tempDriver = c, tempPassenger = c)
            else s.copy(tempDriver = c)
        }
        HvacIds.SEAT_HEAT -> if (zone == HvacIds.ZONE_DRIVER) {
            s.copy(seatHeatDriver = HvacCodec.seatLevel(HvacIds.SEAT_HEAT, value))
        } else {
            s.copy(seatHeatPassenger = HvacCodec.seatLevel(HvacIds.SEAT_HEAT, value))
        }
        HvacIds.SEAT_VENT -> if (zone == HvacIds.ZONE_DRIVER) {
            s.copy(seatVentDriver = HvacCodec.seatLevel(HvacIds.SEAT_VENT, value))
        } else {
            s.copy(seatVentPassenger = HvacCodec.seatLevel(HvacIds.SEAT_VENT, value))
        }
        else -> s
    }
}
