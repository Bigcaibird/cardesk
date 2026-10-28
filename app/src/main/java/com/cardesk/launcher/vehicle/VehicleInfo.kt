package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 桌面需要展示的车辆概览信息。真车通道读不到时保留上一次值并标记来源。
 */
data class VehicleInfo(
    val ambientTemp: Float = 26.5f,
    val rangeKm: Int = 386,
    /** 总里程（公里）；读不到时为 -1。第三十六轮起读 `PERF_ODOMETER` / `ODOMETER` 属性。 */
    val odometerKm: Int = -1,
    val speedKmh: Int = 0,
    val gear: String = "P",
    /** 驾驶模式短标签；读不到时为 null。 */
    val driveModeLabel: String? = null,
    /** 四轮胎压（**kPa**，顺序 FL/FR/RL/RR）。第三十七轮起单位确认为 kPa。 */
    val tires: FloatArray = floatArrayOf(240f, 240f, 238f, 235f),
    /** 油量百分比（0..100）；读不到时为 null。。 */
    val fuelPercent: Int? = null,
    val source: String = "模拟车辆",
    /**
     * 四门是否关闭（主驾/副驾/左后/右后）；真车读不到的那一扇是 null。
     *
     * 第四十五轮起改成**逐门独立**（`List<Boolean?>`，长度 4）：
     * 之前是「四门全读到才算数」的 `BooleanArray`，
     * 少一个门位区域就整组作废，连另外三扇门的正确状态一起丢掉。
     */
    val doorsClosed: List<Boolean?>? = null,
    /**
     * 四门是否**全部**关闭（返回值）。
     *
     *  UI 只有一个「车门关/车门开」的二态，所以这个才是与一一对应的量
     * [doorsClosed] 是我们车辆页用的四门明细。
     */
    val doorsAllClosed: Boolean? = null,
    val hoodClosed: Boolean? = null,
    val trunkClosed: Boolean? = null,
    /**
     * 四扇车窗是否**打开**（左前/右前/左后/右后）；真车读不到时为 null。
     *
     * 第三十六轮新增：之前桌面上的「车窗关/开」是**纯本地乐观值**，
     * 车外用钥匙开窗、车机拒绝关窗都不会反映到桌面。
     * 现在改读 `WINDOW_POS` 属性（`0x13400BC0`）。
     */
    val windowsOpen: BooleanArray? = null,
    /** 四扇车窗是否**全部关闭**；读不到时为 null。 */
    val windowsAllClosed: Boolean? = null,
    /** 平均油耗（L/100km）；读不到时为 null。 */
    val avgFuelConsumption: Double? = null,
    /**
     * 本次行程油耗（L/100km）；读不到时为 null。
     *
     * 第三十七轮新增：之前它和平均油耗混在一条兜底里
     * （`avgFuelConsumption ?: tripFuelConsumption`），
     * 导致「显示的平均油耗」和「自检的平均油耗」是两个口径。
     */
    val tripFuelConsumption: Double? = null,
    /** 本次行程里程（公里）；读不到时为 null。 */
    val tripKm: Double? = null,
    /** 本次行程时长（毫秒）；读不到时为 null。 */
    val tripDurationMs: Double? = null,
)

/**
 * 车辆信号的上一次取值。
 *
 * 桌面每秒刷新一次（[currentVehicleInfo]），如果每次刷新都写一条日志会直接把日志文件刷爆，
 * 所以这里只保留上一轮的快照，**只在数值变化时**才落一条日志。
 */
private object SignalTrace {
    var source: String? = null
    var rangeKm: Int? = null
    var doors: List<Boolean?>? = null
    var doorsAll: Boolean? = null
    var hood: Boolean? = null
    var trunk: Boolean? = null
    var driveModeLabel: String? = null
    var driveModeRaw: Int? = null
    var tires: FloatArray? = null
    var fuelPercent: Int? = null
    var odometerKm: Int? = null
    var windows: BooleanArray? = null
    var avgFuel: Double? = null
    var tripFuel: Double? = null
}

/** 当前网关能给出的车辆概览；真车通道暂时只提供温度/续航这类基础项。 */
fun currentVehicleInfo(): VehicleInfo {
    val gw = try {
        VehicleController.gateway
    } catch (e: Throwable) {
        return VehicleInfo(source = "未连接")
    }
    // 驾驶模式原始值：日志里要同时给出「模式值」和「短标签」。
    var driveModeRaw: Int? = null
    val info = when (gw) {
        is MockVehicleGateway -> VehicleInfo(
            ambientTemp = gw.ambientTempC() ?: 26.5f,
            rangeKm = gw.rangeKm() ?: 386,
            odometerKm = 12480,
            speedKmh = gw.speedKmh,
            gear = gw.gear,
            tires = gw.tirePressures() ?: floatArrayOf(240f, 240f, 238f, 235f),
            fuelPercent = gw.fuelPercent(),
            source = gw.displayName,
            avgFuelConsumption = 6.8,
            tripKm = 128.4,
            tripDurationMs = 83 * 60_000L.toDouble(),
            // 模拟车辆：四门 / 四窗 / 两个盖默认全关
            doorsClosed = gw.simDoorsClosed.map { it },
            doorsAllClosed = true,
            windowsOpen = gw.simWindowsOpen.copyOf(),
            windowsAllClosed = true,
            hoodClosed = gw.simHoodClosed,
            trunkClosed = gw.simTrunkClosed,
        )
        else -> {
            val ctx = VehicleController.appContext()
            // 车身属性通道（车门/车窗/总里程）：`android.car` 的 CarPropertyManager。
            // 第三十六轮新增——之前车窗完全没有回读来源，桌面显示的是本地乐观值。
            if (ctx != null) CarBodySignals.attach(ctx)
            val signalsReady = ctx != null && EcarxSignals.connect(ctx)
            // 续航：优先走 AdaptAPI 的 ISensor 通道
            // 读不到再退回 ECarXCar 的 SignalManager（老路）。
            val sensorRange = gw.rangeKm()
            val range = sensorRange ?: (if (signalsReady) EcarxSignals.rangeKm() ?: -1 else -1)
            // 驾驶模式：从功能号 570491136（zone 2）读
            driveModeRaw = try {
                gw.readValue(DriveMode.FUNCTION_DRIVE_MODE_SELECT, DriveMode.ZONE)
            } catch (e: Throwable) {
                null
            }
            val driveMode = driveModeRaw?.let { DriveMode.currentShortLabel(it) }
            // 胎压：AdaptAPI 的 TPMS 通道
            val tires = gw.tirePressures()
            // 车身状态（车门 / 引擎盖 / 后备箱）：按三条来源顺序编排
            // （car_signal → BCM_DOOR_POS → DOOR_POS 属性），见 `BodyState.resolve`。
            val body = BodyState.resolve(gw, signalsReady)
            // 车窗：CarPropertyManager 的 WINDOW_POS（第三十六轮新增）
            val windowsOpen = CarBodySignals.readWindows()
            // 行程与油耗：AdaptAPI 的 ITripData
            val trip = TripDataReader.stats()
            VehicleInfo(
                rangeKm = range,
                odometerKm = CarBodySignals.readOdometerKm() ?: -1,
                ambientTemp = gw.ambientTempC() ?: Float.NaN,
                driveModeLabel = driveMode,
                tires = tires ?: floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN),
                fuelPercent = gw.fuelPercent(),
                source = if (range >= 0) "${gw.displayName} + ISensor" else gw.displayName,
                doorsClosed = body.doors,
                doorsAllClosed = if (body.valid) body.allClosed else null,
                hoodClosed = body.hood,
                trunkClosed = body.trunk,
                windowsOpen = windowsOpen,
                windowsAllClosed = windowsOpen?.let { arr -> !arr.any { it } },
                // 平均油耗优先用**真正的平均值**（onAvgEnergyInfoUpdate 回调），
                // 拿不到才退回本行程油耗（原值透传）。
                avgFuelConsumption = trip.avgFuelConsumption ?: trip.tripFuelConsumption,
                tripFuelConsumption = trip.tripFuelConsumption,
                tripKm = trip.tripKm,
                tripDurationMs = trip.tripDurationMs,
            )
        }
    }
    logSignalChanges(info, driveModeRaw)
    return info
}

/**
 * 车辆信号变化日志：**只在数值变化时**记一条。
 *
 * 排障场景：续航/车门/胎压/驾驶模式这些值在真机上要么读不到、要么一直不变，
 * 出问题时需要知道「到底是读不到，还是读到了但没变」——
 * 所以变化记 INFO，读不到的变化也记一条 WARN。
 */
private fun logSignalChanges(info: VehicleInfo, driveModeRaw: Int?) {
    val trace = SignalTrace

    if (trace.source != info.source) {
        Logx.i(LogTags.SIGNAL, "车辆数据源变化", "from" to trace.source, "to" to info.source)
        trace.source = info.source
    }

    if (trace.rangeKm != info.rangeKm) {
        val from = trace.rangeKm
        if (info.rangeKm < 0) {
            // 排障场景：续航从有值变成读不到，说明信号链路掉线了。
            Logx.w(LogTags.SIGNAL, "续航变为不可读", "from" to from)
        } else {
            Logx.i(LogTags.SIGNAL, "续航变化", "from" to from, "to" to info.rangeKm)
        }
        trace.rangeKm = info.rangeKm
    }

    val doors = info.doorsClosed
    if (!sameDoors(trace.doors, doors)) {
        Logx.i(
            LogTags.SIGNAL,
            "车门状态变化",
            "from" to (trace.doors?.joinToString(",") ?: "无"),
            "to" to (doors?.joinToString(",") ?: "不可读"),
        )
        trace.doors = doors?.toList()
    }

    if (trace.doorsAll != info.doorsAllClosed) {
        Logx.i(
            LogTags.SIGNAL,
            "车门整体状态变化",
            "from" to (trace.doorsAll?.toString() ?: "无"),
            "to" to (info.doorsAllClosed?.toString() ?: "不可读"),
        )
        trace.doorsAll = info.doorsAllClosed
    }

    if (trace.hood != info.hoodClosed) {
        Logx.i(LogTags.SIGNAL, "前舱盖状态变化", "from" to trace.hood, "to" to info.hoodClosed)
        trace.hood = info.hoodClosed
    }

    if (trace.trunk != info.trunkClosed) {
        Logx.i(LogTags.SIGNAL, "后备箱状态变化", "from" to trace.trunk, "to" to info.trunkClosed)
        trace.trunk = info.trunkClosed
    }

    if (trace.driveModeLabel != info.driveModeLabel) {
        // 驾驶模式切换：短标签 + 模式值一起给出，方便对照固件的模式枚举。
        Logx.i(
            LogTags.SIGNAL,
            "驾驶模式变化",
            "from" to trace.driveModeLabel,
            "to" to info.driveModeLabel,
            "prevMode" to trace.driveModeRaw,
            "mode" to driveModeRaw,
            "label" to info.driveModeLabel,
        )
        trace.driveModeLabel = info.driveModeLabel
    }
    if (driveModeRaw != null) trace.driveModeRaw = driveModeRaw

    if (trace.fuelPercent != info.fuelPercent) {
        val from = trace.fuelPercent
        if (info.fuelPercent == null) {
            Logx.w(LogTags.SIGNAL, "油量变为不可读", "from" to from)
        } else {
            Logx.i(LogTags.SIGNAL, "油量变化", "from" to from, "to" to info.fuelPercent)
        }
        trace.fuelPercent = info.fuelPercent
    }

    if (!sameTires(trace.tires, info.tires)) {
        Logx.i(
            LogTags.SIGNAL,
            "胎压变化",
            "from" to (trace.tires?.joinToString(",") ?: "无"),
            "to" to info.tires.joinToString(","),
        )
        trace.tires = info.tires.copyOf()
    }

    // 第三十六轮新增：总里程 / 车窗 / 平均油耗
    if (trace.odometerKm != info.odometerKm) {
        Logx.i(LogTags.SIGNAL, "总里程变化", "from" to trace.odometerKm, "to" to info.odometerKm)
        trace.odometerKm = info.odometerKm
    }

    val windows = info.windowsOpen
    if (!sameWindows(trace.windows, windows)) {
        Logx.i(
            LogTags.SIGNAL,
            "车窗状态变化",
            "from" to (trace.windows?.joinToString(",") ?: "无"),
            "to" to (windows?.joinToString(",") ?: "不可读"),
        )
        trace.windows = windows?.copyOf()
    }

    if (trace.avgFuel != info.avgFuelConsumption) {
        Logx.i(
            LogTags.SIGNAL,
            "平均油耗变化",
            "from" to trace.avgFuel,
            "to" to info.avgFuelConsumption,
        )
        trace.avgFuel = info.avgFuelConsumption
    }

    // 第三十七轮：把「本行程油耗」单独记一条，
    // 这样下一轮诊断能直接对账「平均值」和「行程值」是不是同一个口径。
    if (trace.tripFuel != info.tripFuelConsumption) {
        Logx.i(
            LogTags.SIGNAL,
            "本行程油耗变化",
            "from" to trace.tripFuel,
            "to" to info.tripFuelConsumption,
        )
        trace.tripFuel = info.tripFuelConsumption
    }
}

/** 车门状态比较（逐门独立；null 项按「不可读」原样比较，避免每次刷新都记）。 */
private fun sameDoors(a: List<Boolean?>?, b: List<Boolean?>?): Boolean = a == b

/** 车窗状态比较（null = 不可读，与 null 视为相同，避免每次刷新都记）。 */
private fun sameWindows(a: BooleanArray?, b: BooleanArray?): Boolean {
    if (a == null || b == null) return a == null && b == null
    return a.contentEquals(b)
}

/** 胎压比较：NaN 与 NaN 视为相同（真车读不到时胎压是 NaN，不能每次都当成变化）。 */
private fun sameTires(a: FloatArray?, b: FloatArray): Boolean {
    if (a == null) return false
    if (a.size != b.size) return false
    for (i in b.indices) {
        if (a[i].isNaN() && b[i].isNaN()) continue
        if (a[i] != b[i]) return false
    }
    return true
}
