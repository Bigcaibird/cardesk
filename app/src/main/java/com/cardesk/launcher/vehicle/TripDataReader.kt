package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

/**
 * AdaptAPI 的 **`ITripData`** 通道：行程与油耗。
 *
 * ##依据
 *
 *
 *
 * `java
 * IHev hevManager = iCar.getHevManager(); //:71
 * ITripData tripData = hevManager.getTripData(); //:74
 * ITripData.IDrivingInfo info = tripData.getLatestDrivingInfo(); //:114
 * info.getTripDistance(); // 米
 * info.getTripDuration(); // 毫秒
 * info.getTripFuelConsumption();
 * info.getTripEleConsumption();
 * tripData.registerTripListener(5000, listener); //:65
 * listener.onAvgEnergyInfoUpdate(avg) → avg.getAvgFuelConsumption() / getAvgEleConsumption();
 * `
 *
 * 单位换算照抄
 * `tripDistance = getTripDistance() / 1000.0f`（米 → 公里）、
 * `tripDuration = (int)(getTripDuration() / 1000)`（毫秒 → 秒）。
 * **油耗两个值原值透传**（没有做任何倍率换算）。
 *
 * ## 前提
 *
 *  只有 **HEV / PHEV 车型**才有；纯燃油车这条通道整体不存在，
 * 所以这里任何一步拿不到都只返回 null，不影响别的通道。
 *
 * ## 第三十七轮修正（真机 4383 诊断包）
 *
 * 诊断快照里出现了一个自相矛盾的组合：
 *
 * `
 * 行程 ITripData：可用
 * 平均油耗：读不到
 * `
 *
 * 而同一份日志里明确有：
 * `平均油耗变化 from=null to=0.7989999651908875`。
 *
 * 原因是**两个值来自两条不同的路**：
 *
 * - `avgFuelConsumption` 只能由 `onAvgEnergyInfoUpdate` 回调填，
 *   回调是**异步**的，而且 `TripDataReader.detach()` 会把它清空；
 * - 自检（`VehicleController`）只读 `avgFuelConsumption`，所以一旦通道被重建
 *   （`AdaptapiGateway.disconnect()`）而回调还没再回来，就显示「读不到」；
 * - 而桌面卡片当时显示的 0.799 走的是 `?: tripFuelConsumption` 兜底 —— **两个口径混在一起了**。
 *
 * 这一轮做了三件事：
 *
 * 1. **照注册两个监听**：500ms与 5000ms
 *    平均能耗到达更快也更稳
 * 2. ** 不再清空已知的平均能耗** ——
 *    同样不清（音乐那边保留 `pausedMetadata`），这样通道重建后自检不会突然变「读不到」；
 * 3. **平均油耗与本行程油耗分开记录、分开打日志**，不再用兜底把两者混成一个数。
 */
object TripDataReader {

    /** 行程数据快照。 */
    data class TripStats(
        /** 行程里程（公里）。 */
        val tripKm: Double? = null,
        /** 行程时长（毫秒）。 */
        val tripDurationMs: Double? = null,
        /** 平均油耗（L/100km）。 */
        val avgFuelConsumption: Double? = null,
        /** 平均电耗（kWh/100km）。 */
        val avgEleConsumption: Double? = null,
        /** 当前行程油耗（L/100km）。 */
        val tripFuelConsumption: Double? = null,
    )

    private val tripDistanceM = AtomicReference<Double?>(null)
    private val tripDurationMs = AtomicReference<Double?>(null)
    private val tripFuel = AtomicReference<Double?>(null)
    private val tripEle = AtomicReference<Double?>(null)
    private val avgFuel = AtomicReference<Double?>(null)
    private val avgEle = AtomicReference<Double?>(null)

    private var tripData: Any? = null

    /** 注册出去的两个监听代理（500ms / 5000ms），断开时都要注销。 */
    private val listeners = ArrayList<Any>(2)
    private var started = false
    private var lastError: String? = null

    /**
     * 挂上 `ITripData`（幂等）。
     *
     * @param carHandle `Car.create(context)` 的返回值（本身就是 `ICar`）
     */
    fun attach(carHandle: Any?) {
        if (carHandle == null) return
        if (started) return
        val hev = runCatching {
            carHandle.javaClass.getMethod("getHevManager").invoke(carHandle)
        }.getOrElse {
            // 纯燃油车没有这个 manager —— 属正常情况，记 DEBUG 不刷屏。
            lastError = "getHevManager(): ${it.javaClass.simpleName}: ${it.message}"
            Logx.d(LogTags.SIGNAL, "ITripData 通道不可用（无 getHevManager）", "reason" to lastError)
            return
        }
        if (hev == null) {
            lastError = "getHevManager() 返回 null"
            Logx.d(LogTags.SIGNAL, "ITripData 通道不可用", "reason" to lastError)
            return
        }
        val td = runCatching {
            hev.javaClass.getMethod("getTripData").invoke(hev)
        }.getOrElse {
            lastError = "getTripData(): ${it.javaClass.simpleName}: ${it.message}"
            Logx.d(LogTags.SIGNAL, "ITripData 通道不可用", "reason" to lastError)
            return
        }
        if (td == null) {
            lastError = "getTripData() 返回 null"
            Logx.d(LogTags.SIGNAL, "ITripData 通道不可用", "reason" to lastError)
            return
        }
        tripData = td
        started = true
        Logx.i(LogTags.SIGNAL, "ITripData 通道就绪", "class" to td.javaClass.name)
        readLatestDrivingInfo()
        readLatestAvgEnergy()
        registerListener(td)
    }

    /**
     * 断开。
     *
     * 第三十七轮：**不再清空已知的平均油耗 / 平均电耗**。
     *
     * 在通知监听断开时（`MusicSessionTracker.resetAfterListenerDisconnectInternal`）
     * 也保留了已缓存的元数据 —— 通道重建的那几秒里，自检不应该突然变成「读不到」。
     * 行程里程/时长会随 重新读到，这里也不清，
     * 免得两个口径在重建窗口里对不上。
     */
    fun detach() {
        val td = tripData
        if (td != null) {
            val unregister = td.javaClass.methods.firstOrNull {
                it.name == "unregisterTripListener" && it.parameterCount == 1
            }
            if (unregister != null) {
                for (l in listeners) {
                    runCatching { unregister.invoke(td, l) }
                }
            }
        }
        started = false
        tripData = null
        listeners.clear()
    }

    /** 主动拉一次最新行程数据。 */
    fun readLatestDrivingInfo() {
        val td = tripData ?: return
        val info = runCatching {
            td.javaClass.getMethod("getLatestDrivingInfo").invoke(td)
        }.getOrNull()
        if (info == null) {
            // 读不到时返回空对象，不是错误。
            Logx.d(LogTags.SIGNAL, "ITripData 读不到最新行程")
            return
        }
        tripDistanceM.set(callDouble(info, "getTripDistance"))
        tripDurationMs.set(callDouble(info, "getTripDuration"))
        tripFuel.set(callDouble(info, "getTripFuelConsumption"))
        tripEle.set(callDouble(info, "getTripEleConsumption"))
    }

    /**
     * 主动拉一次最新的平均能耗。
     *
     * 正常路径是 `onAvgEnergyInfoUpdate` 回调（异步）；这里再补一条**主动读**，
     * 让「通道刚就绪」和「诊断快照」这两个时刻都能拿到值。
     *
     * 方法名在不同固件上不一致，按多个候选名试；都试不到就安静跳过
     * （回调那条路仍然有效）。
     */
    private fun readLatestAvgEnergy() {
        val td = tripData ?: return
        for (name in AVG_INFO_GETTERS) {
            val info = runCatching { td.javaClass.getMethod(name).invoke(td) }.getOrNull() ?: continue
            val f = callDouble(info, "getAvgFuelConsumption")
            val e = callDouble(info, "getAvgEleConsumption")
            if (f != null) avgFuel.set(f)
            if (e != null) avgEle.set(e)
            Logx.i(
                LogTags.SIGNAL,
                "主动读取平均能耗",
                "method" to name,
                "avgFuel" to f,
                "avgEle" to e,
            )
            return
        }
        Logx.d(LogTags.SIGNAL, "没有主动读取平均能耗的方法（等回调）")
    }

    /**
     * 注册平均能耗监听。
     *
     * 注册**两个**周期
     * （= 500ms）与 5000ms。
     * 500ms 那个负责行程信息，5000ms 那个负责平均能耗。
     * 这里照抄：两个都注册，取值时以**后到**的为准。
     *
     * `ITripData.ITripListener` 是**接口** → 动态代理即可。
     */
    private fun registerListener(td: Any) {
        val iface = runCatching {
            Class.forName("com.ecarx.xui.adaptapi.car.hev.ITripData\$ITripListener")
        }.getOrNull()
        if (iface == null) {
            lastError = "找不到 ITripData\$ITripListener"
            Logx.w(LogTags.SIGNAL, "注册 ITripData 监听失败", "reason" to lastError)
            return
        }
        val register = td.javaClass.methods.firstOrNull {
            it.name == "registerTripListener" && it.parameterCount == 2
        }
        if (register == null) {
            lastError = "没有 registerTripListener"
            Logx.w(LogTags.SIGNAL, "注册 ITripData 监听失败", "reason" to lastError)
            return
        }
        // 注册的是**两个不同的**监听对象。
        // 这里也用两个不同的代理 —— 同一个实例注册两次可能被实现方当成同一个覆盖掉，
        // 结果只剩 5000ms 那一路。
        val fast = newListener(iface)
        val slow = newListener(iface)
        val periods = intArrayOf(LISTENER_FAST_MS, LISTENER_SLOW_MS)
        val proxies = arrayOf(fast, slow)
        var ok = 0
        for (i in periods.indices) {
            val r = runCatching { register.invoke(td, periods[i], proxies[i]) }
            if (r.isSuccess) ok++
        }
        listeners.clear()
        listeners.add(fast)
        listeners.add(slow)
        if (ok > 0) {
            Logx.i(
                LogTags.SIGNAL,
                "ITripData 监听已注册",
                "periods" to periods.joinToString(","),
                "count" to ok,
            )
        } else {
            lastError = "registerTripListener 调用失败"
            Logx.w(LogTags.SIGNAL, "注册 ITripData 监听失败", "reason" to lastError)
        }
    }

    /** 造一个 `ITripListener` 代理。 */
    private fun newListener(iface: Class<*>): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            when (method.name) {
                "onAvgEnergyInfoUpdate" -> {
                    val avg = args?.getOrNull(0)
                    if (avg != null) {
                        val f = callDouble(avg, "getAvgFuelConsumption")
                        val e = callDouble(avg, "getAvgEleConsumption")
                        if (f != null) avgFuel.set(f)
                        if (e != null) avgEle.set(e)
                        Logx.d(
                            LogTags.SIGNAL,
                            "平均能耗更新",
                            "avgFuel" to f,
                            "avgEle" to e,
                        )
                    }
                }
                "onDrivingInfoUpdate" -> {
                    val d = args?.getOrNull(0)
                    if (d != null) {
                        callDouble(d, "getTripDistance")?.let { tripDistanceM.set(it) }
                        callDouble(d, "getTripDuration")?.let { tripDurationMs.set(it) }
                        callDouble(d, "getTripFuelConsumption")?.let { tripFuel.set(it) }
                        callDouble(d, "getTripEleConsumption")?.let { tripEle.set(it) }
                    }
                }
            }
            null
        }

    /** 取当前快照。 */
    fun stats(): TripStats = TripStats(
        tripKm = tripDistanceM.get()?.let { it / 1000.0 },
        tripDurationMs = tripDurationMs.get(),
        avgFuelConsumption = avgFuel.get(),
        avgEleConsumption = avgEle.get(),
        tripFuelConsumption = tripFuel.get(),
    )

    /** 是否可用。 */
    fun isAvailable(): Boolean = tripData != null

    /** 最近一次失败原因。 */
    fun lastError(): String? = lastError

    /** 调一个返回数值的方法（`Float` / `Integer` / `Double` 都认）。 */
    private fun callDouble(target: Any, name: String): Double? = runCatching {
        val m = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        } ?: return null
        (m.invoke(target) as? Number)?.toDouble()
    }.getOrNull()

    /**  500ms 周期。 */
    private const val LISTENER_FAST_MS = 500

    private const val LISTENER_SLOW_MS = 5000

    /** 主动读平均能耗时试的方法名（不同固件命名不一）。 */
    private val AVG_INFO_GETTERS = arrayOf(
        "getLatestAvgEnergyInfo",
        "getLatestAvgEnergy",
        "getLatestEnergyInfo",
        "getAvgEnergyInfo",
    )
}
