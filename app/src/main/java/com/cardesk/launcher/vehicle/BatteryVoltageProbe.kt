package com.cardesk.launcher.vehicle

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File
import java.util.Locale

/**
 * 电瓶电压候选读取与实车诊断探测。
 *
 * 领克 820A（`geely/dcy11_a1`）上不存在旧代 E03/GKUI 的 `ecarx.car.ECarXCar`（`SIG_BATTERY_VOLTAGE=29386`），
 * 因此这里按优先级尝试多条候选通道，并在诊断快照（`summary.txt`）里一次性输出全部底层通道的实测结果，
 * 以便实车抓一次诊断包即可确认哪条通道有真实 12V 电瓶电压。
 *
 * 候选读取优先级：
 * 1. `EcarxSignals`（`car_signal` 29386，E03/GKUI 车型）
 * 2. `CarDiagnosticManager`（AAOS 标准 `OBD2_LIVE_FRAME` → `IntegerSensorIndex.CONTROL_MODULE_VOLTAGE = 12`）
 * 3. `ecarx_canbus_service`（旧代 CANBus 服务 `BatteryState.mVoltageValue`，仅在服务存在时尝试）
 *
 * 注意：`Intent.ACTION_BATTERY_CHANGED`（`EXTRA_VOLTAGE`）在车机上可能是 PMIC/模拟假值（如 3.7V/4.2V/0V），
 * 未经实车证实前不直接作为 UI 电压显示，仅在诊断快照中如实汇报。
 */
object BatteryVoltageProbe {

    /** `android.car.diagnostic.IntegerSensorIndex.CONTROL_MODULE_VOLTAGE`（OBD-II PID 0x42，控制模块供电电压）。 */
    const val OBD_CONTROL_MODULE_VOLTAGE = 12

    /** `GeelyVehicleProperty.Vehicle_Energy_Level`（1 = 正常 `VEL_HIGH`，0 = 电量偏低 `VEL_LOW`）。 */
    const val PROP_VEHICLE_ENERGY_LEVEL = 0x21404303

    /** `GeelyVehicleProperty.Shutdown_Popup`（1 = 低电关机提示 `SP_LOWBATT`）。 */
    const val PROP_SHUTDOWN_POPUP = 0x21704304

    /** `Vehicle_Energy_Level` 枚举值：电量正常。 */
    const val ENERGY_LEVEL_HIGH = 1

    /** `Vehicle_Energy_Level` 枚举值：电量偏低。 */
    const val ENERGY_LEVEL_LOW = 0

    private const val MANAGER_DIAGNOSTIC = "diagnostic"
    private const val CLS_SERVICE_MANAGER = "android.os.ServiceManager"
    private const val CACHE_TTL_MS = 5_000L

    @Volatile
    private var cachedObdVoltage: Float? = null

    @Volatile
    private var lastObdProbeAt = 0L

    @Volatile
    private var cachedCanbusVoltage: Float? = null

    @Volatile
    private var lastCanbusProbeAt = 0L

    @Volatile
    private var cachedEnergyLevel: Int? = null

    @Volatile
    private var lastEnergyProbeAt = 0L

    /**
     * 把不同通道返回的电压原始值归一化到「伏特（V）」。
     *
     * - `5..30`：本身就是伏特（如 `12.6` / `14.2`）
     * - `50..300`：十分之一伏（如 `126` → `12.6 V`）
     * - `5000..30000`：毫伏（如 OBD2 / BatteryManager 的 `12600 mV` → `12.6 V`）
     * - 其余一律判为无效（过滤 0 / 负值 / 3.7V~4.2V 锂电模拟值 / 异常极大值）
     */
    fun normalizeVoltage(raw: Float): Float? {
        if (raw.isNaN() || raw.isInfinite()) return null
        return when {
            raw in 5f..30f -> raw
            raw in 50f..300f -> raw / 10f
            raw in 5000f..30000f -> raw / 1000f
            else -> null
        }
    }

    /**
     * 按候选顺序读取电瓶电压（V）；全读不到返回 null。
     */
    fun readVoltage(context: Context?, signalsReady: Boolean): Float? {
        if (signalsReady) {
            EcarxSignals.batteryVoltage()?.let { return it }
        }
        readObdVoltageCached()?.let { return it }
        readCanbusVoltageCached()?.let { return it }
        return null
    }

    /**
     * 读取 `GeelyVehicleProperty.Vehicle_Energy_Level`（0x21404303）。
     *
     * @return `1`（正常）/ `0`（电量偏低）/ `null`（读不到）
     */
    fun readEnergyLevel(): Int? {
        val now = System.currentTimeMillis()
        if (now - lastEnergyProbeAt < CACHE_TTL_MS) return cachedEnergyLevel
        lastEnergyProbeAt = now
        val raw = runCatching { CarProperties.readNumber(PROP_VEHICLE_ENERGY_LEVEL, 0)?.toInt() }.getOrNull()
        cachedEnergyLevel = when (raw) {
            ENERGY_LEVEL_LOW, ENERGY_LEVEL_HIGH -> raw
            else -> null
        }
        return cachedEnergyLevel
    }

    /** 读取 OBD2 `CarDiagnosticManager` 控制模块电压（带 5s 缓存）。 */
    private fun readObdVoltageCached(): Float? {
        val now = System.currentTimeMillis()
        if (now - lastObdProbeAt < CACHE_TTL_MS) return cachedObdVoltage
        lastObdProbeAt = now
        cachedObdVoltage = probeObdDiagnostic().voltage
        return cachedObdVoltage
    }

    /** 尝试读取旧代 `ecarx_canbus_service` 的 `BatteryState.mVoltageValue`（带 5s 缓存）。 */
    private fun readCanbusVoltageCached(): Float? {
        val now = System.currentTimeMillis()
        if (now - lastCanbusProbeAt < CACHE_TTL_MS) return cachedCanbusVoltage
        lastCanbusProbeAt = now
        cachedCanbusVoltage = probeCanbusService().voltage
        return cachedCanbusVoltage
    }

    private data class ObdProbeResult(
        val status: String,
        val voltage: Float? = null,
        val detail: String = "",
    )

    private fun probeObdDiagnostic(): ObdProbeResult {
        val car = CarProperties.carHandle()
            ?: return ObdProbeResult("Car句柄未就绪")
        val diagMgr = runCatching {
            car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, MANAGER_DIAGNOSTIC)
        }.getOrElse {
            return ObdProbeResult("getCarManager异常:${it.javaClass.simpleName}:${it.message}")
        } ?: return ObdProbeResult("不可用(返回null)")

        val liveSupported = runCatching {
            diagMgr.javaClass.getMethod("isLiveFrameSupported").invoke(diagMgr) as? Boolean
        }.getOrNull()

        val frame = runCatching {
            diagMgr.javaClass.getMethod("getLatestLiveFrame").invoke(diagMgr)
        }.getOrElse {
            return ObdProbeResult("liveSupported=$liveSupported getLatestLiveFrame异常:${it.javaClass.simpleName}:${it.message}")
        }
        if (frame == null) {
            return ObdProbeResult("liveSupported=$liveSupported liveFrame=null")
        }

        val intClass = Int::class.javaPrimitiveType ?: Int::class.java
        val rawInt = runCatching {
            frame.javaClass.getMethod("getSystemIntegerSensor", intClass)
                .invoke(frame, OBD_CONTROL_MODULE_VOLTAGE) as? Number
        }.getOrNull()
        val rawFloat = runCatching {
            frame.javaClass.getMethod("getSystemFloatSensor", intClass)
                .invoke(frame, OBD_CONTROL_MODULE_VOLTAGE) as? Number
        }.getOrNull()
        val voltage = (rawInt?.toFloat()?.let(::normalizeVoltage))
            ?: (rawFloat?.toFloat()?.let(::normalizeVoltage))

        val summary = runCatching { frame.toString().replace('\n', ' ').take(240) }.getOrDefault("")
        return ObdProbeResult(
            status = "liveSupported=$liveSupported ctrlVoltInt=$rawInt ctrlVoltFlt=$rawFloat norm=${voltage ?: "无"}",
            voltage = voltage,
            detail = summary,
        )
    }

    private data class CanbusProbeResult(
        val status: String,
        val voltage: Float? = null,
    )

    private fun probeCanbusService(): CanbusProbeResult {
        val binder = getSystemServiceBinder("ecarx_canbus_service")
            ?: return CanbusProbeResult("服务不存在")
        val stub = runCatching {
            Class.forName("ecarx.canbus.ICANBusService\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrElse {
            return CanbusProbeResult("服务存在但类不可反射:${it.javaClass.simpleName}")
        } ?: return CanbusProbeResult("asInterface=null")

        // XSFConfigService 中 BatteryState getter 经混淆为 o() 或 getBatteryState()
        val state = runCatching {
            val m = stub.javaClass.methods.firstOrNull {
                (it.name == "getBatteryState" || it.name == "o") && it.parameterCount == 0 &&
                    it.returnType.name.contains("BatteryState")
            }
            m?.invoke(stub)
        }.getOrElse {
            return CanbusProbeResult("调用失败:${it.javaClass.simpleName}:${it.message}")
        } ?: return CanbusProbeResult("BatteryState=null")

        val rawStr = runCatching { state.toString() }.getOrDefault("")
        val valNum = runCatching {
            val f = state.javaClass.declaredFields.firstOrNull { it.name == "mVoltageValue" || it.name == "b" }
            f?.isAccessible = true
            (f?.get(state) as? Number)?.toFloat()
        }.getOrNull()
        val norm = valNum?.let(::normalizeVoltage)
        return CanbusProbeResult("state=$rawStr raw=$valNum norm=$norm", norm)
    }

    private fun getSystemServiceBinder(name: String): IBinder? = runCatching {
        Class.forName(CLS_SERVICE_MANAGER)
            .getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder
    }.getOrNull()

    /**
     * 输出「电瓶电压与扩展传感器」只读诊断行，写入诊断包 `summary.txt`。
     */
    fun diagnosticLines(context: Context?): List<String> {
        val lines = ArrayList<String>(10)

        // 1. 候选电压汇总 + OBD2 CarDiagnosticManager
        val obd = probeObdDiagnostic()
        val canbus = probeCanbusService()
        val ecarxV = if (EcarxSignals.isAvailable()) EcarxSignals.batteryVoltage() else null
        lines += "电瓶电压探测：ECarXCar=${ecarxV ?: "不可用"} | OBD2(${obd.status}) | CANBus(${canbus.status})"
        if (obd.detail.isNotEmpty()) {
            lines += "OBD2 LiveFrame 原始：${obd.detail}"
        }

        // 2. GeelyVehicleProperty 电源组属性（0x21404303 / 0x21704304）
        val energy = runCatching { CarProperties.readNumber(PROP_VEHICLE_ENERGY_LEVEL, 0)?.toInt() }.getOrNull()
        val energyLabel = when (energy) {
            ENERGY_LEVEL_HIGH -> "1(VEL_HIGH 正常)"
            ENERGY_LEVEL_LOW -> "0(VEL_LOW 偏低)"
            null -> "读不到"
            else -> "$energy"
        }
        val shutdownPopup = runCatching { CarProperties.readNumber(PROP_SHUTDOWN_POPUP, 0)?.toInt() }.getOrNull()
        lines += "蓄电池电量属性：Vehicle_Energy_Level(0x21404303)=$energyLabel | Shutdown_Popup(0x21704304)=${shutdownPopup ?: "读不到"}"

        // 3. Android ACTION_BATTERY_CHANGED 广播
        if (context != null) {
            val batLine = runCatching {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (intent == null) {
                    "无粘性广播"
                } else {
                    val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                    val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
                    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    "voltage=${mv}mV level=$level/$scale status=$status plugged=$plugged present=$present temp=$temp"
                }
            }.getOrElse { "读取异常:${it.javaClass.simpleName}:${it.message}" }
            lines += "Android 电池广播：$batLine"
        }

        // 4. 内核 /sys/class/power_supply/* 与 iio 电压节点（只读）
        lines += "内核电源节点：${probeSysfsPower()}"

        // 5. 车机残余系统服务探测
        val svcList = listOf("ecarx_canbus_service", "ecarx_car_signal", "ecarx_diagnosis_service", "car_service")
            .joinToString(", ") { name -> "$name=${if (getSystemServiceBinder(name) != null) "存在" else "无"}" }
        lines += "系统车控服务：$svcList"

        // 6. car_service 属性清单中的标准 AOSP VHAL（非 0x21 厂商段）及电源组属性
        val cfgs = CarProperties.propertyConfigs()
        if (cfgs != null) {
            val stdProps = cfgs.filter { (it.propertyId ushr 24) != 0x21 }
            val stdText = if (stdProps.isEmpty()) {
                "无"
            } else {
                stdProps.take(40).joinToString(",") {
                    String.format(Locale.US, "0x%08X(%s)", it.propertyId, it.typeName)
                } + if (stdProps.size > 40) " ...(+${stdProps.size - 40})" else ""
            }
            lines += "car_service 标准VHAL属性(${stdProps.size}个)：$stdText"
        }

        // 7. 扩展 ISensor 读数汇总（与车况详情页一致）
        lines += "扩展 ISensor：" +
            "车速=${AdaptapiSensors.carSpeedKmh().takeIf { it >= 0 } ?: "--"}km/h " +
            "PM2.5=${AdaptapiSensors.pm25Indoor().takeIf { it >= 0 } ?: "--"} " +
            "AQI=${AdaptapiSensors.aqiIndoor().takeIf { it >= 0 } ?: "--"} " +
            "保养=${AdaptapiSensors.maintenanceKm().takeIf { it >= 0 } ?: "--"}km " +
            "点火=${AdaptapiSensors.ignitionLabel(AdaptapiSensors.ignitionEvent())} " +
            "机油=${AdaptapiSensors.oilLevelLabel(AdaptapiSensors.oilLevelEvent())} " +
            "制动液=${AdaptapiSensors.brakeFluidLabel(AdaptapiSensors.brakeFluidEvent())} " +
            "冷却液=${AdaptapiSensors.coolantWarnLabel(AdaptapiSensors.coolantWarnEvent())} " +
            "低油量=${AdaptapiSensors.fuelLowWarnLabel(AdaptapiSensors.fuelLowWarnEvent())}"

        Logx.d(LogTags.SIGNAL, "电瓶电压诊断探测完成", "lines" to lines.size)
        return lines
    }

    /** 只读探测 `/sys/class/power_supply/` 与 `/sys/bus/iio/devices/` 下的电压/电源文件。 */
    private fun probeSysfsPower(): String = runCatching {
        val parts = ArrayList<String>()
        val psDir = File("/sys/class/power_supply")
        val children = psDir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (children.isEmpty()) {
            parts += "power_supply=空/不可读"
        } else {
            for (sub in children.take(6)) {
                val fields = listOf("type", "voltage_now", "voltage_ocv", "capacity", "status")
                    .mapNotNull { key ->
                        val v = readSmallSysfs(File(sub, key)) ?: return@mapNotNull null
                        "$key=$v"
                    }
                parts += "${sub.name}[${if (fields.isEmpty()) "无可读项" else fields.joinToString(",")}]"
            }
        }
        val iioDir = File("/sys/bus/iio/devices")
        val iioDevs = iioDir.listFiles()?.sortedBy { it.name }.orEmpty()
        val iioHits = ArrayList<String>()
        for (dev in iioDevs.take(4)) {
            val files = dev.listFiles { _, name ->
                name.contains("volt", ignoreCase = true) || name.contains("vbat", ignoreCase = true)
            }?.sortedBy { it.name }.orEmpty()
            for (f in files.take(4)) {
                val v = readSmallSysfs(f) ?: continue
                iioHits += "${dev.name}/${f.name}=$v"
            }
        }
        if (iioHits.isNotEmpty()) {
            parts += "iio[${iioHits.joinToString(",")}]"
        }
        parts.joinToString(" ; ")
    }.getOrElse { "读取异常:${it.javaClass.simpleName}" }

    private fun readSmallSysfs(file: File): String? = runCatching {
        if (!file.exists() || !file.canRead()) return@runCatching null
        file.bufferedReader().use { it.readLine()?.trim()?.take(48) }?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
