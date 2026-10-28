package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 第二优先的真车通道：通过 AOSP `android.car.Car` 的 CarPropertyManager 读写空调属性。
 *
 * 与 AdaptAPI 的差别：
 *  - AdaptAPI 是 ECARX 私有接口，属性号是 0x1001xxxx 这类厂商 id；
 *  - AOSP car 是标准 VehiclePropertyIds，属性 id 与区域号都不同（区域 1/4 = 前排左/右）。
 *
 * 因为 `android.car` 在普通 SDK 里只是 stub（调用即抛异常），这里同样全反射：
 * 类存在与否、方法签名差异都只影响本通道，失败就交给下一档或模拟车。
 * 这样同一份桌面可以在 E03、820A、AAOS 以及第三方车机上跑。
 */
class CarLibGateway(private val context: Context) : VehicleGateway {

    override val id: String = VehicleGateway.CHANNEL_CARLIB
    override val displayName: String = "AOSP Car 属性通道"
    override var on360State: ((Boolean) -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<(Int, Int, Int) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private var manager: Any? = null
    private var connected = false
    private var lastError: String? = null
    private val propertyIds = HashMap<Int, Int>()
    private var lastSnapshot: Map<Int, Int> = emptyMap()

    private val poll = object : Runnable {
        override fun run() {
            if (!connected) return
            val snapshot = readAll()
            if (snapshot.isNotEmpty()) {
                for ((func, value) in snapshot) {
                    val old = lastSnapshot[func]
                    if (old != value) {
                        // 排障场景：AOSP 通道靠轮询取变化，日志要能看出「车端什么时候变的」。
                        Logx.d(
                            LogTags.VEHICLE,
                            "轮询到属性变化",
                            "func" to VehicleLog.hex(func),
                            "name" to VehicleLog.funcName(func),
                            "from" to old,
                            "to" to value,
                        )
                        listeners.forEach { it(func, HvacIds.ZONE_DRIVER, value) }
                    }
                }
                lastSnapshot = snapshot
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun connect(onResult: (Boolean) -> Unit) {
        // 排障场景：这条通道是「真车但非 ECARX 固件」时的备胎，连接失败必须留痕，
        // 否则用户只看到「回退模拟车」，不知道 AOSP 通道为什么没起来。
        Logx.i(LogTags.VEHICLE, "连接车控通道", "channel" to id, "impl" to displayName)
        try {
            // 连接由 [CarProperties] 统一持有：`android.car.Car` 在进程内只能建一次
            // （重复 createCar 会抛 "already connected or connecting"），
            // 而车门/车窗/总里程那条属性通道也要用它，所以共用同一连接。
            check(CarProperties.attach(context)) { "CarPropertyManager 通道不可用" }
            manager = CarProperties.manager()
            checkNotNull(manager) { "getCarManager(\"property\") 返回 null" }
            resolvePropertyIds()
            connected = true
            lastSnapshot = readAll()
            main.postDelayed(poll, POLL_MS)
            Logx.i(
                LogTags.VEHICLE,
                "车控通道连接成功",
                "channel" to id,
                "propertyCount" to propertyIds.size,
                "initialSnapshotSize" to lastSnapshot.size,
            )
            onResult(true)
        } catch (t: Throwable) {
            connected = false
            lastError = "AOSP Car 通道连接失败：${t.javaClass.simpleName}: ${t.message}"
            Logx.w(LogTags.VEHICLE, "AOSP Car 通道连接失败", t, "channel" to id, "reason" to lastError)
            onResult(false)
        }
    }

    override fun disconnect() {
        Logx.d(LogTags.VEHICLE, "断开车控通道", "channel" to id)
        connected = false
        main.removeCallbacks(poll)
        manager = null
        // 注意：**不**断开 CarProperties 的连接 —— 车门/车窗/总里程那条属性通道还在用它，
        // 而且 `android.car.Car` 重复建连会抛异常。
    }

    override fun isConnected(): Boolean = connected

    override fun setHvac(func: Int, zone: Int, value: Int): Boolean {
        val m = manager ?: run {
            Logx.w(
                LogTags.VEHICLE,
                "写入属性失败（无 CarPropertyManager）",
                "func" to VehicleLog.hex(func),
                "zone" to zone,
                "value" to value,
                "reason" to "getCarManager(\"property\") 不可用",
            )
            return false
        }
        // 写前除霜用 DEFROST_FRONT_MAX，读属性却是 DEFROST_FRONT —— 这里归一
        val canonical = HvacCodec.canonicalFunc(func)
        val propId = propertyIds[canonical] ?: run {
            lastError = "AOSP 通道不支持属性 ${VehicleLog.hex(canonical)}"
            Logx.w(
                LogTags.VEHICLE,
                "写入属性失败（属性未映射）",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "value" to value,
                "reason" to lastError,
            )
            return false
        }
        val area = areaFor(zone)
        // 值要翻译：AdaptAPI 用「功能号 + 档位」编码，AOSP 用纯档位/布尔/摄氏度
        val native = HvacCodec.toCarLibValue(canonical, value)
        val ok = tryWrite(m, propId, area, native)
        if (ok) {
            Logx.i(
                LogTags.VEHICLE,
                "写入属性成功",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "value" to value,
                "propId" to propId,
                "area" to area,
                "native" to native,
                "ok" to true,
            )
        } else {
            Logx.w(
                LogTags.VEHICLE,
                "写入属性失败",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "value" to value,
                "propId" to propId,
                "area" to area,
                "native" to native,
                "reason" to lastError,
            )
        }
        return ok
    }

    override fun setTemp(zone: Int, celsius: Float): Boolean {
        val m = manager ?: run {
            Logx.w(
                LogTags.VEHICLE,
                "写入温度失败（无 CarPropertyManager）",
                "zone" to zone,
                "celsius" to celsius,
                "reason" to "getCarManager(\"property\") 不可用",
            )
            return false
        }
        val propId = propertyIds[HvacIds.TEMP] ?: run {
            lastError = "AOSP 通道不支持温度属性"
            Logx.w(
                LogTags.VEHICLE,
                "写入温度失败（属性未映射）",
                "zone" to zone,
                "celsius" to celsius,
                "reason" to lastError,
            )
            return false
        }
        val area = areaFor(zone)
        val ok = tryWrite(m, propId, area, celsius)
        if (ok) {
            Logx.i(
                LogTags.VEHICLE,
                "写入温度成功",
                "zone" to zone,
                "celsius" to celsius,
                "propId" to propId,
                "area" to area,
                "ok" to true,
            )
        } else {
            Logx.w(
                LogTags.VEHICLE,
                "写入温度失败",
                "zone" to zone,
                "celsius" to celsius,
                "propId" to propId,
                "area" to area,
                "reason" to lastError,
            )
        }
        return ok
    }

    /** AOSP 车机没有 360 属性（`HVAC_360_VIEW_ENABLE` 不在标准 VehiclePropertyIds 里）。 */
    override fun set360(on: Boolean): Boolean {
        // 排障场景：360 在这条通道上永远不可用，要明确记下来，
        // 免得误以为「写入失败」是车机的问题。
        Logx.w(LogTags.CAM360, "AOSP 通道不支持 360 开关", "channel" to id, "on" to on)
        return false
    }

    override fun select360View(view: Int): Boolean {
        Logx.w(LogTags.CAM360, "AOSP 通道不支持 360 视角切换", "channel" to id, "view" to view)
        return false
    }

    override fun lastError(): String? = lastError

    override fun isServiceConnected(): Boolean = connected

    override fun readAll(): Map<Int, Int> {
        val m = manager ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for ((func, propId) in propertyIds) {
            try {
                val value = m.javaClass.getMethod("getProperty", INT_CLASS, INT_CLASS)
                    .invoke(m, propId, areaFor(HvacIds.ZONE_DRIVER))
                val raw = value?.javaClass?.getMethod("getValue")?.invoke(value) ?: continue
                // AOSP 原生值 → 内部统一编码（功能号 + 档位偏移）
                out[func] = HvacCodec.fromCarLibValue(func, raw)
            } catch (t: Throwable) {
                // 该属性在当前车机不存在或无读权限
                Logx.d(
                    LogTags.VEHICLE,
                    "读取属性失败",
                    "func" to VehicleLog.hex(func),
                    "name" to VehicleLog.funcName(func),
                    "propId" to propId,
                    "reason" to "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
        // 排障场景：「读到空」说明整条 AOSP 读链路有问题（权限/服务未起），
        // 「只读到一部分」说明是某个属性不支持——两者要区分开。
        if (out.isEmpty()) {
            Logx.w(LogTags.VEHICLE, "全量读取结果为空", "channel" to id, "propertyCount" to propertyIds.size)
        } else {
            Logx.d(LogTags.VEHICLE, "全量读取完成", "channel" to id, "readCount" to out.size)
        }
        return out
    }

    override fun readValue(func: Int, zone: Int): Int? {
        val m = manager ?: return null
        val canonical = HvacCodec.canonicalFunc(func)
        val propId = propertyIds[canonical] ?: run {
            Logx.d(
                LogTags.VEHICLE,
                "读取属性失败（属性未映射）",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
            )
            return null
        }
        val value = runCatching {
            val v = m.javaClass.getMethod("getProperty", INT_CLASS, INT_CLASS).invoke(m, propId, areaFor(zone))
            val raw = v?.javaClass?.getMethod("getValue")?.invoke(v) ?: return null
            HvacCodec.fromCarLibValue(canonical, raw)
        }.getOrNull()
        if (value == null) {
            Logx.d(
                LogTags.VEHICLE,
                "读取属性失败",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "propId" to propId,
            )
        } else {
            Logx.d(
                LogTags.VEHICLE,
                "读取属性",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "propId" to propId,
                "value" to value,
            )
        }
        return value
    }

    override fun registerListener(listener: (Int, Int, Int) -> Unit) {
        listeners.add(listener)
        Logx.d(LogTags.VEHICLE, "注册值变化监听者", "channel" to id, "count" to listeners.size)
    }

    override fun unregisterListener(listener: (Int, Int, Int) -> Unit) {
        listeners.remove(listener)
        Logx.d(LogTags.VEHICLE, "注销值变化监听者", "channel" to id, "count" to listeners.size)
    }

    // ------------------------------------------------------------ 反射细节

    private fun resolvePropertyIds() {
        propertyIds.clear()
        val ids = runCatching { Class.forName(CLS_PROPERTY_IDS) }.getOrNull() ?: run {
            // 排障场景：属性 id 表都拿不到，这条通道必然写不进任何东西。
            Logx.w(LogTags.VEHICLE, "AOSP VehiclePropertyIds 不可用", "channel" to id)
            return
        }
        val missing = ArrayList<String>()
        for ((func, name) in PROP_NAMES) {
            val id = runCatching { ids.getField(name).getInt(null) }.getOrNull()
            if (id == null) {
                missing.add(name)
                continue
            }
            propertyIds[func] = id
        }
        // 排障场景：哪些属性这条车机不支持，直接决定「哪些按钮点了没反应」。
        Logx.i(
            LogTags.VEHICLE,
            "解析 AOSP 属性映射",
            "channel" to id,
            "resolved" to propertyIds.size,
            "missing" to missing.joinToString(","),
        )
    }

    private fun tryWrite(manager: Any, propId: Int, area: Int, value: Any): Boolean {
        val valueClass = Class.forName(CLS_PROPERTY_VALUE)
        // 候选顺序按 AOSP 属性类型排：Float 属性先试 Float，Boolean 属性只试 Boolean
        val candidates: List<Any> = when (value) {
            is Boolean -> listOf(value)
            is Float -> listOf(value, value.toInt())
            is Int -> listOf(value, value != 0)
            else -> listOf(value)
        }
        var lastFailure: Throwable? = null
        for (candidate in candidates) {
            val result = runCatching {
                val propertyValue = valueClass
                    .getConstructor(INT_CLASS, INT_CLASS, Any::class.java)
                    .newInstance(propId, area, candidate)
                manager.javaClass.getMethod("setProperty", valueClass).invoke(manager, propertyValue)
                true
            }
            if (result.isSuccess) return true
            lastFailure = result.exceptionOrNull()
        }
        lastError = "setProperty 失败 prop=$propId area=$area value=$value（${lastFailure?.javaClass?.simpleName}: ${lastFailure?.message}）"
        return false
    }

    private fun areaFor(zone: Int): Int = when (zone) {
        HvacIds.ZONE_PASSENGER -> 4 // VehicleAreaSeat.SEAT_ROW_1_RIGHT
        else -> 1 // SEAT_ROW_1_LEFT
    }

    companion object {
        private const val CLS_CAR = "android.car.Car"
        private const val CLS_PROPERTY_IDS = "android.car.VehiclePropertyIds"
        private const val CLS_PROPERTY_VALUE = "android.car.hardware.CarPropertyValue"
        private val INT_CLASS: Class<*> = Integer.TYPE
        private const val POLL_MS = 1200L

        private val PROP_NAMES: Map<Int, String> = linkedMapOf(
            HvacIds.POWER to "HVAC_POWER_ON",
            HvacIds.AC to "HVAC_AC_ON",
            HvacIds.AUTO to "HVAC_AUTO_ON",
            HvacIds.CIRCULATION to "HVAC_RECIRC_ON",
            HvacIds.FAN_SPEED to "HVAC_FAN_SPEED",
            HvacIds.DEFROST_FRONT to "HVAC_DEFROSTER",
            HvacIds.DEFROST_REAR to "HVAC_REAR_DEFROSTER",
            HvacIds.TEMP to "HVAC_TEMPERATURE_SET",
            HvacIds.SEAT_HEAT to "HVAC_SEAT_TEMPERATURE",
            HvacIds.SEAT_VENT to "HVAC_SEAT_VENTILATION",
            HvacIds.BLOWING to "HVAC_FAN_DIRECTION",
            HvacIds.FUNC_360 to "HVAC_360_VIEW_ENABLE",
        )

        /** 当前固件是否带 android.car（AAOS / 多数国产车机都有）。 */
        fun probe(): Boolean = runCatching {
            Class.forName(CLS_CAR)
            Class.forName(CLS_PROPERTY_IDS)
            true
        }.getOrElse {
            // 排障场景：探测失败说明这条通道在这台车机上不可用。
            Logx.d(
                LogTags.VEHICLE,
                "AOSP Car 通道探测失败",
                "reason" to "${it.javaClass.simpleName}: ${it.message}",
            )
            false
        }
    }
}
