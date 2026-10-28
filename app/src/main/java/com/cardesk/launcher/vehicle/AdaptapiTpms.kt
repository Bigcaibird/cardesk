package com.cardesk.launcher.vehicle

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Proxy

/**
 * AdaptAPI 的 **TPMS** 通道：四轮胎压。
 *
 * ## 取值通道
 *
 * 胎压**不是**从属性通道读的，而是走独立的 `TPMS` 接口。
 *
 * - 句柄是**单独 connect 的**：`TPMS.create(context)` → `registerConnectWatcher` → `connect()`，
 *   **不是** `iCar.getTpms()`；
 * - 模式判定：`tpms.isTirePressureCalibrationSupported(0|1)`
 *   —— 0 = ITPMS（间接胎压），1 = DTPMS（直接胎压）；
 * - 取值：`tpms.getTireState(pos)` → `getPressure()` / `getTemperature()`，
 *   以及 `tpms.registerTireStateMonitor(...)` 回调；
 * - **轮位映射**：
 *
 *   | 轮位 | TPMS pos | 我们的下标 |
 *   | --- | --- | --- |
 *   | 左前 FL | 1 | 0 |
 *   | 右前 FR | 3 | 1 |
 *   | 左后 RL | 2 | 2 |
 *   | 右后 RR | 4 | 3 |
 *
 *   （下标语义是 0=FL、1=FR、2=RL、3=RR。）
 *
 * ## 我们之前的问题
 *
 * 之前胎压是**写死 NaN** 的（`VehicleInfo.currentVehicleInfo` 里
 * `tires = floatArrayOf(NaN, NaN, NaN, NaN)`，根本没接任何数据源
 * 真机胎压卡永远是 `NaN`。这次把真正用的 TPMS 通道补上。
 */
object AdaptapiTpms {

    /** TPMS 位置 → 我们数组下标（0=FL、1=FR、2=RL、3=RR）。 */
    private val POS_TO_INDEX = mapOf(1 to 0, 3 to 1, 2 to 2, 4 to 3)

    private var tpms: Any? = null
    private var connected = false
    private var monitor: Any? = null

    /** 模式：0 = ITPMS（间接），1 = DTPMS（直接），-1 = 未知。 */
    private var mode = -1

    /**
     * 四轮胎压（**kPa**）；null = 不可读。
     *
     * 第三十七轮更正：真机 4383 诊断包里 TPMS 报的是 `240.00,227.50,237.50,235.00`，
     * 那是 **kPa**（2.40 bar），之前我们按 bar 标注是错的。
     * 也印证
     * kPa ↔ bar 的换算系数是 0.01、kPa ↔ PSI 是 0.145，**底层存的就是 kPa**。
     */
    private var pressures: FloatArray? = null

    /** 上次一次的模式（用于日志）。 */
    private var lastError: String? = null

    /**
     * 连接 TPMS（幂等）。
     *
     * `TPMS.create(context)` 返回的是
     * `IConnectable`，需要自己注册连接监听并。
     */
    fun connect(context: Context) {
        if (connected) return
        val instance = runCatching {
            Class.forName("com.ecarx.xui.adaptapi.tpms.TPMS")
                .getMethod("create", Context::class.java)
                .invoke(null, context)
        }.getOrElse {
            lastError = "TPMS.create(): ${it.javaClass.simpleName}: ${it.message}"
            // 排障场景：真机胎压读不到时，要能一眼看出「是没这个类」还是「连不上」。
            Logx.w(LogTags.SIGNAL, "TPMS 通道不可用", it, "reason" to lastError)
            return
        }
        if (instance == null) {
            lastError = "TPMS.create() 返回 null"
            Logx.w(LogTags.SIGNAL, "TPMS 通道不可用", "reason" to lastError)
            return
        }
        tpms = instance
        registerConnectWatcher(instance)
        val ok = runCatching {
            instance.javaClass.getMethod("connect").invoke(instance)
        }.onFailure {
            lastError = "TPMS connect(): ${it.message}"
            Logx.w(LogTags.SIGNAL, "TPMS connect() 调用失败", it, "reason" to lastError)
        }.isSuccess
        connected = true
        Logx.i(LogTags.SIGNAL, "TPMS 通道已建立", "connectOk" to ok, "class" to instance.javaClass.name)
    }

    /** 注册连接监听（`IConnectable.IConnectWatcher` 是接口 → 动态代理）。 */
    private fun registerConnectWatcher(instance: Any) {
        runCatching {
            val watcherIface = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val proxy = Proxy.newProxyInstance(watcherIface.classLoader, arrayOf(watcherIface)) { _, method, _ ->
                when (method.name) {
                    "onConnected" -> {
                        Logx.i(LogTags.SIGNAL, "TPMS 车机服务已连接")
                        init()
                    }
                    "onDisConnected" -> Logx.w(LogTags.SIGNAL, "TPMS 车机服务断开")
                }
                null
            }
            instance.javaClass.methods.firstOrNull {
                it.name == "registerConnectWatcher" && it.parameterCount == 1
            }?.invoke(instance, proxy)
        }.onFailure {
            Logx.w(LogTags.SIGNAL, "注册 TPMS 连接监听失败", it)
        }
    }

    /**
     * 初始化：判模式 + 读一次 + 注册变化监听。
     *
     *
     */
    private fun init() {
        val t = tpms ?: return
        mode = detectMode(t)
        Logx.i(LogTags.SIGNAL, "TPMS 模式", "mode" to mode, "name" to modeName(mode))
        readAll(t)
        registerMonitor(t)
    }

    /**
     * 模式判定。
     *
     * 0 = ITPMS、1 = DTPMS； 表示不支持。
     * 优先级是「DTPMS 覆盖 ITPMS」（后判的会覆盖前面的）。
     */
    private fun detectMode(t: Any): Int {
        var m = -1
        for (candidate in intArrayOf(0, 1)) {
            val status = runCatching {
                t.javaClass.methods.firstOrNull {
                    it.name == "isTirePressureCalibrationSupported" && it.parameterCount == 1
                }?.invoke(t, candidate)
            }.getOrNull() ?: continue
            val name = status?.toString()?.lowercase() ?: continue
            if (!name.contains("notavailable")) {
                m = candidate
                Logx.i(
                    LogTags.SIGNAL,
                    "TPMS 支持状态",
                    "mode" to candidate,
                    "status" to status,
                )
            }
        }
        return m
    }

    /** 读一次四个轮子的胎压。 */
    private fun readAll(t: Any) {
        val getState = t.javaClass.methods.firstOrNull {
            it.name == "getTireState" && it.parameterCount == 1
        } ?: run {
            lastError = "TPMS 没有 getTireState(pos)"
            Logx.w(LogTags.SIGNAL, "TPMS 取值接口缺失", "reason" to lastError)
            return
        }
        val out = FloatArray(4) { Float.NaN }
        var any = false
        for ((pos, index) in POS_TO_INDEX) {
            val state = runCatching { getState.invoke(t, pos) }.getOrNull() ?: continue
            val pressure = readPressure(state) ?: continue
            out[index] = pressure
            any = true
        }
        if (!any) {
            Logx.w(LogTags.SIGNAL, "TPMS 读取胎压失败", "mode" to mode)
            return
        }
        publish(out)
    }

    private fun readPressure(state: Any): Float? = runCatching {
        state.javaClass.methods.firstOrNull {
            it.name == "getPressure" && it.parameterCount == 0
        }?.invoke(state) as? Float
    }.getOrNull()

    /** 注册胎压变化监听。 */
    private fun registerMonitor(t: Any) {
        runCatching {
            val monitorIface = Class.forName("com.ecarx.xui.adaptapi.tpms.TPMS\$ITireStateMonitor")
            val proxy = Proxy.newProxyInstance(monitorIface.classLoader, arrayOf(monitorIface)) { _, method, args ->
                if (method.name == "onTireStateChanged") {
                    val pos = args?.getOrNull(0) as? Int ?: 0
                    val state = args?.getOrNull(1)
                    val index = POS_TO_INDEX[pos]
                    if (index != null && state != null) {
                        readPressure(state)?.let { p ->
                            val cur = pressures?.copyOf() ?: FloatArray(4) { Float.NaN }
                            cur[index] = p
                            publish(cur)
                        }
                    }
                }
                null
            }
            t.javaClass.methods.firstOrNull {
                it.name == "registerTireStateMonitor" && it.parameterCount == 1
            }?.invoke(t, proxy)
            monitor = proxy
            Logx.i(LogTags.SIGNAL, "TPMS 变化监听已注册")
        }.onFailure {
            Logx.w(LogTags.SIGNAL, "注册 TPMS 变化监听失败", it)
        }
    }

    /** 发布胎压（只在变化时记一条 INFO）。 */
    private fun publish(next: FloatArray) {
        val old = pressures
        pressures = next
        if (old != null && old.contentEquals(next)) return
        Logx.i(
            LogTags.SIGNAL,
            "胎压变化",
            "from" to (old?.joinToString(",") ?: "无"),
            "to" to next.joinToString(","),
            "unit" to "kPa",
        )
    }

    /** 断开（切通道时调用）。 */
    fun disconnect() {
        val t = tpms
        val m = monitor
        if (t != null && m != null) {
            runCatching {
                t.javaClass.methods.firstOrNull {
                    it.name == "unregisterTireStateMonitor" && it.parameterCount == 1
                }?.invoke(t, m)
            }
        }
        connected = false
        tpms = null
        monitor = null
        mode = -1
        pressures = null
    }

    // ------------------------------------------------------------ 对外取值

    /** 四轮胎压（**kPa**，顺序 FL/FR/RL/RR）；null = 不可读。 */
    fun tirePressures(): FloatArray? = pressures?.copyOf()

    /** 是否已经连上 TPMS。 */
    fun isAvailable(): Boolean = tpms != null

    /** 当前模式。 */
    fun tpmsMode(): Int = mode

    /** 最近一次失败原因。 */
    fun lastError(): String? = lastError

    private fun modeName(m: Int): String = when (m) {
        0 -> "ITPMS（间接胎压）"
        1 -> "DTPMS（直接胎压）"
        else -> "未知"
    }
}
