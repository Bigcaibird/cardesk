package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 真车通道：反射调用 ECARX AdaptAPI（com.ecarx.xui.adaptapi.*）。
 *
 * 这是系统层才有的接口：类只存在于车机 /system 分区，普通第三方 APK 里没有实现。
 * 因此这里全部用反射 + 动态代理，缺失任何一环都只意味着「降级到模拟车」，
 * 不会让桌面崩溃。属性号见 [HvacIds]。
 *
 * ## 连接链路
 *
 * `java
 * IConnectable car = Car.create(context);
 * car.registerConnectWatcher(new IConnectable.IConnectWatcher() {... });
 * car.connect();
 * `
 *
 * 注意：`Car.create()` 的**返回值本身就是 ICar**，句柄是 `car.getICarFunction()`；
 * 里没有多一层。早期版本这里多跳了一次，导致真机上
 * 通道连接失败 → 静默回退模拟车辆 → 「车控点了没反应」。
 *
 * ## 写入链路
 *
 * 编译期 `ICarFunction` 只声明 2 参重载，多参/float 重载存在于不同固件的实现类上，
 * 所以按 **3 参 → 2 参 → 接口 2 参** 逐级降级，而不是只试一种签名。
 */
class AdaptapiGateway(private val context: Context) : VehicleGateway {

    override val id: String = "adaptapi"
    override val displayName: String = "ECARX AdaptAPI"
    override var on360State: ((Boolean) -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<(Int, Int, Int) -> Unit>()
    private var car: Any? = null
    private var function: Any? = null
    private var connected = false
    private var lastError: String? = null

    /** 值变化回调是否已注册（句柄与监听者都到位后才注册一次）。 */
    private var watcherRegistered = false

    /** 最近一次已知的 360 开关状态（用于只记录「变化」而不是每次回调都记）。 */
    private var last360State: Boolean? = null

    /**
     * 反射方法的缓存（第七十九轮 P0）。
     *
     * `Class.getMethod()` 每次都要拷参数数组、跑权限校验、新建一个 `Method` 对象。
     * 氛围灯是 10Hz 一帧，每帧 2 次 `findMethod` ⇒ 每秒 20 次反射，全在调用线程上。
     * 缓存之后只在**第一次**（或句柄换了）才反射。
     */
    private val methodCache = java.util.Collections.synchronizedMap(HashMap<String, Method?>())

    /** 缓存属于哪个句柄的类（句柄换了要清空 —— 不同固件的方法集合不一样）。 */
    private var methodCacheOwner: Class<*>? = null

    /** 「探测 setFunctionValue 重载」这条日志是不是已经记过了（只记首次）。 */
    private val overloadProbed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 「写入属性成功」这条 INFO 的节流表（功能号 → 上次记录的时刻）。 */
    private val successLogAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** 车机服务是否已连上（来自 `IConnectWatcher.onConnected`）。 */
    @Volatile
    var carServiceConnected: Boolean = false
        private set

    override fun connect(onResult: (Boolean) -> Unit) {
        // 排障场景：真机上「车控点了没反应」时，先要确认到底连上了哪个通道、
        // 是哪一步失败的——这里把尝试、成功、失败都留痕。
        Logx.i(LogTags.VEHICLE, "连接车控通道", "channel" to id, "impl" to displayName)
        try {
            val carClass = Class.forName(CLS_CAR)
            val instance = carClass.getMethod("create", Context::class.java).invoke(null, context)
                ?: throw IllegalStateException("Car.create() 返回 null")
            car = instance
            // 先注册连接监听再 connect()；连上之后车机才真正接受属性写入
            registerConnectWatcher(instance)
            // connect() 本身失败 ≠ 整条通道作废（车机服务的真实状态由
            // IConnectWatcher.onConnected 决定，但**绝不能在这里报「连接成功」**——
            // 那会让日志把失败伪装成成功，排查时直接被带偏。
            val connectCallOk = runCatching {
                instance.javaClass.getMethod("connect").invoke(instance)
            }.onFailure {
                lastError = "connect(): ${it.javaClass.simpleName}: ${it.message}"
                Logx.w(LogTags.VEHICLE, "AdaptAPI connect() 调用失败", it, "channel" to id, "reason" to lastError)
            }.isSuccess
            connected = true
            // 句柄可能在 connect() 之后才拿得到（getCarFunction() 是「用时才取」）
            // 这里先试一次，后续读写路径还会懒解析（见 ensureFunction）
            ensureFunction()
            // 监听者可能早于 connect() 注册（见 registerListener），这里补一次
            ensureWatcher()
            Logx.i(
                LogTags.VEHICLE,
                if (connectCallOk) "车控通道连接调用成功" else "车控通道已建立（connect() 调用失败，等待车机服务回调）",
                "channel" to id,
                "connectCallOk" to connectCallOk,
                "serviceConnected" to carServiceConnected,
                "functionReady" to (function != null),
                "watcherRegistered" to watcherRegistered,
            )
            // 注意：这里仍然回调 true —— 通道**选择**不应该因为 connect() 抛异常就放弃
            // AdaptAPI（车机服务的真实状态由 IConnectWatcher.onConnected 决定，
            // 放弃通道会直接让真机退化成模拟车。失败信息由上面的 WARN + lastError 承担。
            onResult(true)
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Logx.w(LogTags.VEHICLE, "AdaptAPI 通道连接失败", t, "channel" to id, "reason" to lastError)
            connected = false
            onResult(false)
        }
    }

    /**
     * 懒解析 `ICarFunction` 句柄。
     *
     * 里 是在**每次使用时**才调的
     * 因为车机服务连接完成前
     * 这个句柄可能还拿不到。早期版本只在 connect() 时取一次，取不到就整条通道作废，
     * 于是真机上表现为「连上了但车控没反应」。
     */
    private fun ensureFunction(): Any? {
        // 续航/油量/胎压走的是**另外两条通道**（ISensor / TPMS），
        // 与。
        // 这里每次都试一次（各自内部有幂等保护），车机服务连上的那一刻就能挂上。
        ensureSignalChannels()
        function?.let { return it }
        val instance = car ?: run {
            // 排障场景：句柄拿不到时，「点空调没反应」的第一嫌疑就是这里——
            // 记下具体原因（还没 connect / 车机服务没连上），不要只记「失败」。
            Logx.d(LogTags.VEHICLE, "拿不到 ICarFunction 句柄", "channel" to id, "reason" to "car 句柄为空（尚未 connect）")
            return null
        }
        val fn = resolveCarFunction(instance)
        if (fn == null) {
            Logx.d(LogTags.VEHICLE, "拿不到 ICarFunction 句柄", "channel" to id, "reason" to "getICarFunction() 返回 null（车机服务未连上或无权限）")
            return null
        }
        function = fn
        Logx.i(LogTags.VEHICLE, "ICarFunction 句柄就绪", "channel" to id, "class" to fn.javaClass.name)
        // 第六十六轮：把句柄的类注册成「常量根类」。
        // Adapt 的 `BCM_FUNC_*` / `DOOR_ROW_*` / `WINDOW_ROW_*` 常量就在**同一个 jar** 里，
        // 从句柄的类出发按名字找，比猜候选类名准得多。
        AdaptapiConstants.registerRoot(fn.javaClass)
        instance.javaClass.let { AdaptapiConstants.registerRoot(it) }
        // 句柄到位后补注册值变化回调
        ensureWatcher()
        return fn
    }

    /**
     * 取 `ICarFunction` 句柄。
     *
     * 正常路径：`Car.create()` 的返回值就是 ICar，直接；
     * 兼容个别固件多包一层。
     */
    private fun resolveCarFunction(instance: Any): Any? {
        findMethod(instance, "getICarFunction")?.let { m ->
            runCatching { m.invoke(instance) }.getOrNull()?.let {
                Logx.d(LogTags.VEHICLE, "解析 ICarFunction", "channel" to id, "path" to "getICarFunction()")
                return it
            }
        }
        val iCar = runCatching {
            instance.javaClass.getMethod("getICar").invoke(instance)
        }.getOrNull() ?: return null
        findMethod(iCar, "getICarFunction")?.let { m ->
            runCatching { m.invoke(iCar) }.getOrNull()?.let {
                Logx.d(LogTags.VEHICLE, "解析 ICarFunction", "channel" to id, "path" to "getICar().getICarFunction()")
                return it
            }
        }
        return null
    }

    override fun disconnect() {
        // 排障场景：通道被重建（旧通道断开 → 新通道连接）时，要能确认断开时机。
        Logx.d(LogTags.VEHICLE, "断开车控通道", "channel" to id, "serviceConnected" to carServiceConnected)
        connected = false
        carServiceConnected = false
        watcherRegistered = false
        function = null
        car = null
        // 通道重建了，固件支持结论要重新问
        AdaptapiBody.clearSupportCache()
        AdaptapiSensors.detach()
        AdaptapiTpms.disconnect()
        TripDataReader.detach()
    }

    /**
     * 挂上**信号通道**（续航/油量/环温走 ISensor，胎压走 TPMS）。
     *
     * 这两条都不在 `ICarFunction` 的属性通道里：
     * - `ISensor`：`iCar.getSensorManager()`
     * - `TPMS`：**独立 connect** `TPMS.create(context)`
     */
    private fun ensureSignalChannels() {
        AdaptapiSensors.attach(car)
        AdaptapiTpms.connect(context)
        // 行程/油耗：AdaptAPI 的 ITripData（走 `iCar.getHevManager()`）
        TripDataReader.attach(car)
    }

    /** 剩余续航（km）；读不到返回 null。 */
    override fun rangeKm(): Int? = AdaptapiSensors.rangeKm().takeIf { it > 0 }

    /** 油量百分比；读不到返回 null。 */
    override fun fuelPercent(): Int? = AdaptapiSensors.fuelPercent().takeIf { it >= 0 }

    /** 环温（℃）；读不到返回 null。 */
    override fun ambientTempC(): Float? =
        AdaptapiSensors.ambientTempC().takeIf { it != Int.MIN_VALUE }?.toFloat()

    /** 四轮胎压（bar，顺序 FL/FR/RL/RR）；读不到返回 null。 */
    override fun tirePressures(): FloatArray? = AdaptapiTpms.tirePressures()

    override fun isConnected(): Boolean = connected

    override fun registerListener(listener: (Int, Int, Int) -> Unit) {
        listeners.add(listener)
        Logx.d(LogTags.VEHICLE, "注册值变化监听者", "channel" to id, "count" to listeners.size)
        ensureWatcher()
    }

    override fun unregisterListener(listener: (Int, Int, Int) -> Unit) {
        listeners.remove(listener)
        Logx.d(LogTags.VEHICLE, "注销值变化监听者", "channel" to id, "count" to listeners.size)
    }

    /** 车机服务是否真的连上（`IConnectWatcher.onConnected`）。区别于「本地句柄已创建」。 */
    override fun isServiceConnected(): Boolean = carServiceConnected

    /**
     * 写一个功能属性。
     *
     * 逐级降级：**3 参 (func, zone, value) → 2 参 (func, value) → 接口 2 参**。
     * 只有明确返回 true 才算成功，避免把「方法不存在 / 被固件拒绝」当成写成功。
     */
    override fun setHvac(func: Int, zone: Int, value: Int): Boolean {
        val f = ensureFunction() ?: run {
            lastError = "拿不到 ICarFunction 句柄（车机服务未连接或当前应用无权限）"
            Logx.w(
                LogTags.VEHICLE,
                "写入属性失败（拿不到句柄）",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "value" to value,
                "reason" to lastError,
            )
            return false
        }
        val m3 = findMethod(f, "setFunctionValue", INT, INT, INT)
        val m2 = findMethod(f, "setFunctionValue", INT, INT)
        // 排障场景：固件重载不同会导致「方法不存在」而被误判成「写入失败」，
        // 这里把当前句柄上到底有哪些重载记下来，方便对照固件版本。
        // 第七十九轮：只在**首次**探测时记（每帧都记会把日志刷满）。
        if (overloadProbed.compareAndSet(false, true)) {
            Logx.d(
                LogTags.VEHICLE,
                "探测 setFunctionValue 重载",
                "func" to VehicleLog.hex(func),
                "has3" to (m3 != null),
                "has2" to (m2 != null),
            )
        }
        if (m3 != null && invokeBool(m3, f, func, zone, value) == true) {
            logWriteSuccess(func, zone, value, "3参")
            return true
        }
        if (m2 != null && invokeBool(m2, f, func, value) == true) {
            logWriteSuccess(func, zone, value, "2参")
            return true
        }
        lastError = "setFunctionValue 全部重载都失败 func=$func zone=$zone value=$value"
        Logx.w(
            LogTags.VEHICLE,
            "写入属性失败",
            "func" to VehicleLog.hex(func),
            "name" to VehicleLog.funcName(func),
            "zone" to zone,
            "value" to value,
            "reason" to lastError,
        )
        return false
    }

    /**
     * 「写入属性成功」这条日志（**节流**）。
     *
     * ## 第七十九轮 P0：为什么要节流
     *
     * 这条是 **INFO**（落盘），而 `setHvac` 是**氛围灯每帧都要调**的（10Hz）。
     * 落盘是「开文件 → 写 → 关文件」，而且是在**调用线程**上做的 ——
     * 也就是说每秒 10 次同步落盘全在主线程上，主线程一被拖住，
     * 窗口就只剩 `windowBackground`（黑），再久就被看门狗当 ANR 杀掉。
     *
     * 所以：**同一个功能号最多每 [SUCCESS_LOG_INTERVAL_MS] 记一条**，
     * 其余的走 DEBUG（只在 Logcat 里，不落盘）。
     */
    private fun logWriteSuccess(func: Int, zone: Int, value: Int, overload: String) {
        val now = System.currentTimeMillis()
        val last = successLogAt[func] ?: 0L
        if (now - last < SUCCESS_LOG_INTERVAL_MS) {
            Logx.d(
                LogTags.VEHICLE,
                "写入属性成功",
                "func" to VehicleLog.hex(func),
                "zone" to zone,
                "value" to value,
                "overload" to overload,
            )
            return
        }
        successLogAt[func] = now
        Logx.i(
            LogTags.VEHICLE,
            "写入属性成功",
            "func" to VehicleLog.hex(func),
            "name" to VehicleLog.funcName(func),
            "zone" to zone,
            "value" to value,
            "overload" to overload,
            "ok" to true,
        )
    }

    /**
     * 写温度：走 float 通道 `setCustomizeFunctionValue(func, zone, celsius)`。
     *
     * ## 第七十四轮：删掉整型回退
     *
     * 之前浮点写失败会退到 `setFunctionValue(FUNC_TEMP, zone, round(celsius*2))`。
     * 但厂商的**整型写对 `HVAC_FUNC_TEMP` 是 unsupported** ——
     * `CarFunctionValueImpl.java:1137` 原文：
     * `"Set function by integer is not supported for IHvac.HVAC_FUNC_TEMP, please use setCustomizeFunctionValue"`
     * 然后直接 `return false`。
     *
     * 也就是说这条回退**注定失败**：白跑一次 binder 事务，
     * 还会把「写入失败」这个结论报给上层（用户看到「点了没反应」）。
     *
     * 另外浮点写**必须带 zone**（1 = 主驾 / 4 = 副驾）：
     * 两参重载会把 zone 设成 0，厂商那边判「not a valid zone」直接 false。
     */
    override fun setTemp(zone: Int, celsius: Float): Boolean {
        val f = ensureFunction() ?: run {
            lastError = "拿不到 ICarFunction 句柄（车机服务未连接或当前应用无权限）"
            Logx.w(
                LogTags.VEHICLE,
                "写入温度失败（拿不到句柄）",
                "zone" to zone,
                "celsius" to celsius,
                "reason" to lastError,
            )
            return false
        }
        val customize = findMethod(f, "setCustomizeFunctionValue", INT, INT, FLOAT)
        if (customize != null && invokeBool(customize, f, HvacIds.TEMP, zone, celsius) == true) {
            Logx.i(
                LogTags.VEHICLE,
                "写入温度成功",
                "func" to VehicleLog.hex(HvacIds.TEMP),
                "name" to VehicleLog.funcName(HvacIds.TEMP),
                "zone" to zone,
                "celsius" to celsius,
                "channel" to "float",
                "ok" to true,
            )
            return true
        }
        // 不再退回整型通道：厂商那边整型写恒 false（见上面的源码依据）。
        lastError = "温度 float 通道写入被拒（zone=$zone）"
        Logx.w(
            LogTags.VEHICLE,
            "写入温度失败",
            "zone" to zone,
            "celsius" to celsius,
            "channel" to "float",
            "reason" to lastError,
        )
        return false
    }

    /**
     * 写除霜：特殊策略——
     * 先试「无 zone」的两参重载，再按 `{2, 1, 0}` 的顺序试三参重载。
     */
    override fun setDefrost(func: Int, on: Boolean): Boolean {
        val f = ensureFunction() ?: run {
            lastError = "拿不到 ICarFunction 句柄（车机服务未连接或当前应用无权限）"
            Logx.w(
                LogTags.VEHICLE,
                "写入除霜失败（拿不到句柄）",
                "func" to VehicleLog.hex(func),
                "on" to on,
                "reason" to lastError,
            )
            return false
        }
        val v = if (on) 1 else 0
        val m2 = findMethod(f, "setFunctionValue", INT, INT)
        if (m2 != null && invokeBool(m2, f, func, v) == true) {
            Logx.i(
                LogTags.VEHICLE,
                "写入除霜成功",
                "func" to VehicleLog.hex(func),
                "on" to on,
                "overload" to "2参",
                "ok" to true,
            )
            return true
        }
        val m3 = findMethod(f, "setFunctionValue", INT, INT, INT)
        if (m3 != null) {
            for (z in HvacIds.DEFROST_ZONES) {
                if (invokeBool(m3, f, func, z, v) == true) {
                    Logx.i(
                        LogTags.VEHICLE,
                        "写入除霜成功",
                        "func" to VehicleLog.hex(func),
                        "on" to on,
                        "zone" to z,
                        "overload" to "3参",
                        "ok" to true,
                    )
                    return true
                }
            }
        }
        lastError = "setDefrostVal 失败 func=$func on=$on"
        Logx.w(
            LogTags.VEHICLE,
            "写入除霜失败",
            "func" to VehicleLog.hex(func),
            "on" to on,
            "reason" to lastError,
        )
        return false
    }

    /**
     * 打开/关闭 360 全景。
     *
     * 用的是**两参**重载
     * `setFunctionValue(FUNCTION_360, value)`——360 不带 zone，
     * 带 zone 的三参调用在这台车机上不生效。
     */
    override fun set360(on: Boolean): Boolean {
        val f = ensureFunction() ?: run {
            lastError = "拿不到 ICarFunction 句柄（车机服务未连接或当前应用无权限）"
            Logx.w(LogTags.CAM360, "360 写入失败（拿不到句柄）", "on" to on, "reason" to lastError)
            return false
        }
        val v = if (on) 1 else 0
        findMethod(f, "setFunctionValue", INT, INT)?.let { m ->
            if (invokeBool(m, f, HvacIds.FUNC_360, v) == true) {
                Logx.d(LogTags.CAM360, "360 写入成功", "on" to on, "value" to v, "overload" to "2参")
                return true
            }
        }
        return setHvac(HvacIds.FUNC_360, HvacIds.ZONE_GLOBAL, v)
    }

    /**
     * 切换 360 视角。
     *
     *
     * 先试两参 `setFunctionValue(FUNCTION_VIEW, view)`，
     * 再试旧固件的 `setFunctionValue(view + FUNCTION_VIEW, 1)`。
     */
    override fun select360View(view: Int): Boolean {
        val f = ensureFunction() ?: run {
            lastError = "拿不到 ICarFunction 句柄（车机服务未连接或当前应用无权限）"
            Logx.w(LogTags.CAM360, "360 视角切换失败（拿不到句柄）", "view" to view, "reason" to lastError)
            return false
        }
        findMethod(f, "setFunctionValue", INT, INT)?.let { m ->
            if (invokeBool(m, f, HvacIds.FUNC_360_VIEW, view) == true) {
                Logx.d(LogTags.CAM360, "360 视角切换成功", "view" to view, "overload" to "2参")
                return true
            }
        }
        findMethod(f, "setFunctionValue", INT, INT)?.let { m ->
            if (invokeBool(m, f, view + HvacIds.FUNC_360_VIEW, 1) == true) {
                Logx.d(LogTags.CAM360, "360 视角切换成功", "view" to view, "overload" to "旧固件偏移")
                return true
            }
        }
        return setHvac(HvacIds.FUNC_360_VIEW, HvacIds.ZONE_GLOBAL, view)
    }

    /**
     * 全量读当前属性。
     *
     * 逐项读取顺序与回退
     *  - 电源 / A/C / AUTO / 风量 / 双区 / 吹风：`(func, 1)` → `(func, 0)`
     *  - 循环：**先**一参 `(func)` → `(func, 1)` → `(func, 0)`
     *  - 温度：float 通道 `(func, 1)` → `(func, 0)`（副驾 `(func, 4)` → `(func, 0)`）
     *  - 除霜：一参 `(func)` → `(func, 1)` → `(func, 0)`
     *  - 座椅：主驾 `(func, 1)`、副驾 `(func, 4)`
     */
    override fun readAll(): Map<Int, Int> {
        val f = ensureFunction() ?: run {
            Logx.w(LogTags.VEHICLE, "全量读取失败（拿不到句柄）", "channel" to id)
            return emptyMap()
        }
        val out = HashMap<Int, Int>()

        // 开关类：zone 1 → zone 0
        for (func in intArrayOf(HvacIds.POWER, HvacIds.AC, HvacIds.AUTO, HvacIds.FAN_SPEED, HvacIds.TEMP_DUAL, HvacIds.BLOWING)) {
            readFunc(f, func, HvacIds.ZONE_DRIVER)?.let { out[func] = it }
        }

        // 循环：先一参
        readCirculation(f)?.let { out[HvacIds.CIRCULATION] = it }

        // 温度：主驾 zone 1 → zone 0
        readCustomizeTemp(f, HvacIds.ZONE_DRIVER)?.let { out[HvacIds.TEMP] = HvacCodec.tempRaw(it) }

        // 除霜：前除霜读 （refreshDefrostFrontFromVehicle:548）
        readDefrost(f, HvacIds.DEFROST_FRONT_MAX)?.let { out[HvacIds.DEFROST_FRONT] = it }
        readDefrost(f, HvacIds.DEFROST_REAR)?.let { out[HvacIds.DEFROST_REAR] = it }

        // 座椅：主驾 zone 1
        readFunc(f, HvacIds.SEAT_HEAT, HvacIds.ZONE_DRIVER)?.let { out[HvacIds.SEAT_HEAT] = it }
        readFunc(f, HvacIds.SEAT_VENT, HvacIds.ZONE_DRIVER)?.let { out[HvacIds.SEAT_VENT] = it }

        // 排障场景：「读到的是空」和「只读到一部分」是两回事，
        // 这里把读到的项与读不到的项都列出来，方便对比固件支持度。
        if (out.isEmpty()) {
            Logx.w(LogTags.VEHICLE, "全量读取结果为空", "channel" to id)
        } else {
            val missing = intArrayOf(
                HvacIds.POWER, HvacIds.AC, HvacIds.AUTO, HvacIds.FAN_SPEED, HvacIds.CIRCULATION,
                HvacIds.TEMP, HvacIds.DEFROST_FRONT, HvacIds.DEFROST_REAR,
                HvacIds.SEAT_HEAT, HvacIds.SEAT_VENT,
            ).filter { out[it] == null }
            Logx.d(
                LogTags.VEHICLE,
                "全量读取完成",
                "channel" to id,
                "readCount" to out.size,
                "missing" to missing.joinToString(",") { VehicleLog.hex(it) },
            )
        }
        return out
    }

    /** 按区域读单个属性。 */
    override fun readValue(func: Int, zone: Int): Int? {
        val f = ensureFunction() ?: run {
            Logx.d(
                LogTags.VEHICLE,
                "读取属性失败（拿不到句柄）",
                "func" to VehicleLog.hex(func),
                "zone" to zone,
                "reason" to "ICarFunction 句柄不可用",
            )
            return null
        }
        val value = if (func == HvacIds.TEMP) {
            readCustomizeTemp(f, zone)?.let { HvacCodec.tempRaw(it) }
        } else if (func == HvacIds.CIRCULATION) {
            readCirculation(f)
        } else if (func == HvacIds.DEFROST_FRONT || func == HvacIds.DEFROST_FRONT_MAX || func == HvacIds.DEFROST_REAR) {
            readDefrost(f, func)
        } else {
            readFunc(f, func, zone)
        }
        // 排障场景：写之前会先读一次真值，读不到就会「按过期的本地镜像取反」，
        // 所以这里把每次读的 func/zone/结果都记下来。
        if (value == null) {
            Logx.d(
                LogTags.VEHICLE,
                "读取属性失败",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "reason" to "固件返回不可读或方法不存在",
            )
        } else {
            Logx.d(
                LogTags.VEHICLE,
                "读取属性",
                "func" to VehicleLog.hex(func),
                "name" to VehicleLog.funcName(func),
                "zone" to zone,
                "value" to value,
            )
        }
        return value
    }

    /**
     * 读一个整型属性：先两参 `(func, zone)`，失败退一参 `(func)`
     *
     *
     * 固件用 `-1` / `Integer.MIN_VALUE` 表示「该属性不可读」，此时继续往下回退。
     */
    /**
     * 读一个功能属性的**原始值**（只用两参 `getFunctionValue(func, zone)`）。
     *
     *
     *
     * 与 [readValue] 的两处关键差别：
     * - **不**退回一参 `getFunctionValue(func)` —— 逐 zone 读时那会把全局值
     *   当成每个门位的值，四个门全读成同一个；
     * - **不**过滤 `0` / `INVALID` / `Int.MIN_VALUE`，原样返回。
     *   车门语义里 `0` 就是「关闭」，过滤掉就永远读不到「关着」。
     */
    override fun readRawFunctionValue(func: Int, zone: Int): Int? {
        val f = ensureFunction() ?: return null
        val m = findMethod(f, "getFunctionValue", INT, INT) ?: return null
        val v = runCatching { m.invoke(f, func, zone) }.getOrNull() ?: return null
        return when (v) {
            is Int -> v
            is Float -> v.toInt()
            is Double -> v.toInt()
            is Boolean -> if (v) 1 else 0
            else -> null
        }
    }

    /**
     * 读一个功能属性的**自定义（浮点）值**（第七十一轮）。
     *
     * 逐扇车窗的开度只在 `getCustomizeFunctionValue(func, zone)`（浮点）里实现，
     * 整型通道对同一个功能号直接返回 `-1`。
     *
     * 固件返回的是 `float`（开度百分比），这里取整成 `Int`；
     * `-1`（异常 / 无数据）原样带出去，由调用方判定「读不到」。
     */
    override fun readCustomizeFunctionValue(func: Int, zone: Int): Int? {
        val f = ensureFunction() ?: return null
        val m = findMethod(f, "getCustomizeFunctionValue", INT, INT) ?: return null
        val v = runCatching { m.invoke(f, func, zone) }.getOrNull() ?: return null
        return when (v) {
            is Float -> v.toInt()
            is Double -> v.toInt()
            is Int -> v
            else -> null
        }
    }

    /**
     * 固件是否支持这个功能号 / 区域。
     *
     * 按 `(func, zone)` → `(func)` 两档找 `isFunctionSupported`：
     * - 两参版本能区分**区域**级不支持（这正是门位 zone 读出 255 的原因）；
     * - 只有一参版本就退到功能号级判断。
     *
     * 找不到这个方法 → 返回 null（判不了，按「支持」继续读）。
     *
     * ## 第七十一轮：返回值不是 boolean
     *
     * 车机端 `ICarFunction.isFunctionSupported(int, int)` 返回的是
     * **`FunctionStatus` 枚举**，不是 `boolean`：
     *
     * ```
     * active        支持且当前可用
     * notactive     支持但当前不可用（判不了）
     * notavailable  这台车不支持这一档
     * error         出错（判不了）
     * ```
     *
     * 之前这里直接 `as? Boolean` 强转，**永远拿到 null** ——
     * 也就是说「固件支持探针」从来没生效过（它一直走的是「判不了」那条路）。
     * 现在按枚举名解析：`active` → true，`notavailable` → false，其余 → null。
     */
    override fun isFunctionSupported(func: Int, zone: Int): Boolean? {
        val f = ensureFunction() ?: return null
        findMethod(f, "isFunctionSupported", INT, INT)?.let { m ->
            runCatching { m.invoke(f, func, zone) }.getOrNull()?.let { return functionStatusToBool(it) }
        }
        findMethod(f, "isFunctionSupported", INT)?.let { m ->
            runCatching { m.invoke(f, func) }.getOrNull()?.let { return functionStatusToBool(it) }
        }
        return null
    }

    /**
     * `FunctionStatus` → 三态结论。
     *
     * @return true = 支持；false = 这台车不支持；null = 判不了
     */
    private fun functionStatusToBool(status: Any?): Boolean? {
        if (status == null) return null
        if (status is Boolean) return status
        // 枚举：按**名字**判定，不依赖 ordinal（ordinal 顺序不是契约）。
        val name = runCatching {
            (status as? Enum<*>)?.name ?: status.javaClass.methods
                .firstOrNull { it.name == "name" && it.parameterCount == 0 }
                ?.invoke(status) as? String
        }.getOrNull() ?: return null
        return CarSignalMath.functionSupported(name)
    }

    /**
     * 读一个功能号（整型通道）。
     *
     * ## 第七十四轮：把厂商的三个错误码一起挡掉
     *
     * 之前只挡 `-1` 和 `Int.MIN_VALUE`，于是 `253` / `254` 会流进业务逻辑 ——
     * 最要命的是 `HVAC_FUNC_TEMP` 的整型读**恒返回 253**
     * （厂商源码 `CarFunctionValueImpl.java:399`）。
     * 现在统一走 [CarSignalMath.isUnsupportedRaw]：`253`/`254`/`255`/`-1` 一律当「读不到」。
     */
    private fun readFunc(f: Any, func: Int, zone: Int): Int? {
        findMethod(f, "getFunctionValue", INT, INT)?.let { m ->
            val v = runCatching { m.invoke(f, func, zone) as? Int }.getOrNull()
            if (v != null && !CarSignalMath.isUnsupportedRaw(v)) return v
        }
        findMethod(f, "getFunctionValue", INT)?.let { m ->
            val v = runCatching { m.invoke(f, func) as? Int }.getOrNull()
            if (v != null && !CarSignalMath.isUnsupportedRaw(v)) return v
        }
        return null
    }

    /**
     * 读除霜：一参 `(func)` → `(func, 1)` → `(func, 0)`
     *
     */
    private fun readDefrost(f: Any, func: Int): Int? {
        findMethod(f, "getFunctionValue", INT)?.let { m ->
            val v = runCatching { m.invoke(f, func) as? Int }.getOrNull()
            if (v != null && !CarSignalMath.isUnsupportedRaw(v)) return v
        }
        readFunc(f, func, HvacIds.ZONE_DRIVER)?.let { return it }
        readFunc(f, func, 0)?.let { return it }
        return null
    }

    /**
     * 读循环模式：一参 `(func)` → `(func, 1)` → `(func, 0)`
     *
     */
    private fun readCirculation(f: Any): Int? {
        findMethod(f, "getFunctionValue", INT)?.let { m ->
            val v = runCatching { m.invoke(f, HvacIds.CIRCULATION) as? Int }.getOrNull()
            if (v != null && !CarSignalMath.isUnsupportedRaw(v)) return v
        }
        readFunc(f, HvacIds.CIRCULATION, HvacIds.ZONE_DRIVER)?.let { return it }
        readFunc(f, HvacIds.CIRCULATION, 0)?.let { return it }
        return null
    }

    /**
     * 读温度：走 `getCustomizeFunctionValue(func, zone)`（返回 Float）。
     *
     * ## 第七十四轮：删掉整型回退
     *
     * 之前浮点通道失败会退到 `getFunctionValue(func, zone) / 2`。
     * 但厂商的**整型**读对 `HVAC_FUNC_TEMP` 是**恒返回 253**
     * （`CarFunctionValueImpl.java:399`：`case 268828928: … return 253;`）——
     * 于是 `253 / 2 = 126.5`，被 clamp 成 **32℃**：
     * **「读不到」被伪装成「车机说 32 度」**。
     *
     * 厂商自己在 `getFunctionValue` 的注释里也写了
     * `Set function by integer is not supported for IHvac.HVAC_FUNC_TEMP, please use setCustomizeFunctionValue`。
     *
     * 现在浮点通道失败就是**读不到**（null），上层保留上一次的镜像值，
     * 宁可显示旧值，也不显示一个假的温度。
     *
     * @param zone 主驾 1 / 副驾 4
     */
    private fun readCustomizeTemp(f: Any, zone: Int): Float? {
        findMethod(f, "getCustomizeFunctionValue", INT, INT)?.let { m ->
            val v = runCatching { m.invoke(f, HvacIds.TEMP, zone) as? Float }.getOrNull()
            // 接受窗就是厂商的 [TEMP_MIN, TEMP_MAX]，不再放宽到 +1。
            if (v != null && v >= HvacIds.TEMP_MIN && v <= HvacIds.TEMP_MAX) return v
        }
        return null
    }

    /** 最近的反射失败原因，便于设置页/日志展示。 */
    override fun lastError(): String? = lastError

    /** 记录一次失败原因（供上层展示）。 */
    fun noteError(message: String) {
        lastError = message
    }

    // ------------------------------------------------------------ 反射工具

    /**
     * 找方法：找不到重载返回 null（不同固件的重载集合不一样）。
     *
     * ## 第七十九轮 P0：加缓存
     *
     * 以前每次写都要反射两次（`Class.getMethod`），
     * 而氛围灯是 10Hz 一帧 ⇒ 每秒 20 次反射。
     * 现在按「句柄的类 + 方法名 + 参数签名」缓存，
     * 句柄换了（重连）就清空重来 —— 不同固件的方法集合确实不一样。
     */
    private fun findMethod(target: Any, name: String, vararg params: Class<*>): Method? {
        val owner = target.javaClass
        synchronized(methodCache) {
            if (owner !== methodCacheOwner) {
                methodCache.clear()
                methodCacheOwner = owner
            }
            val key = name + params.joinToString(prefix = "(", postfix = ")")
            if (methodCache.containsKey(key)) return methodCache[key]
            val m = runCatching { owner.getMethod(name, *params) }.getOrNull()
            methodCache[key] = m
            return m
        }
    }

    /**
     * 调用并取布尔结果。
     *
     * 返回 null 表示「方法不存在 / 抛异常 / 返回 void」——都不能当成成功，
     * 要继续往下试下一个重载（用 `Boolean.TRUE.equals(...)` 判断）。
     */
    private fun invokeBool(method: Method, target: Any, vararg args: Any?): Boolean? = try {
        method.invoke(target, *args) as? Boolean
    } catch (t: Throwable) {
        lastError = "${method.name}: ${t.javaClass.simpleName}: ${t.message}"
        // 排障场景：反射异常是「写不进车」的头号原因，这里留一条明细（DEBUG 级避免刷屏）。
        Logx.d(
            LogTags.VEHICLE,
            "反射调用失败",
            "method" to method.name,
            "reason" to "${t.javaClass.simpleName}: ${t.message}",
        )
        null
    }

    /** 注册车机连接监听（`IConnectable.registerConnectWatcher`）。 */
    private fun registerConnectWatcher(instance: Any) {
        try {
            val watcherClass = Class.forName(CLS_CONNECT_WATCHER)
            val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
                when (method.name) {
                    "onConnected" -> {
                        carServiceConnected = true
                        // 服务连上后句柄才可能拿得到，这时再解析一次
                        if (ensureFunction() == null) {
                            lastError = "车机服务已连上，但 getICarFunction() 仍拿不到句柄"
                            Logx.w(LogTags.VEHICLE, "车机服务已连接但拿不到句柄", "channel" to id, "reason" to lastError)
                        }
                        Logx.i(LogTags.VEHICLE, "车机服务已连接", "channel" to id)
                    }
                    "onDisConnected" -> {
                        carServiceConnected = false
                        Logx.w(LogTags.VEHICLE, "车机服务断开", "channel" to id)
                    }
                }
                null
            }
            val watcher = Proxy.newProxyInstance(watcherClass.classLoader, arrayOf(watcherClass), handler)
            instance.javaClass.getMethod("registerConnectWatcher", watcherClass).invoke(instance, watcher)
            Logx.d(LogTags.VEHICLE, "注册连接监听成功", "channel" to id)
        } catch (t: Throwable) {
            // 里，若 Car.create() 返回的对象不是 IConnectable，直接当作已连接
            // ——这里同样处理，
            // 否则连接状态会永远停在「未连接」，让用户误以为车控没生效。
            carServiceConnected = true
            // 排障场景：这条降级会让「服务到底连上没有」变得不可知，必须留痕。
            Logx.w(
                LogTags.VEHICLE,
                "注册连接监听失败（按已连接处理）",
                t,
                "channel" to id,
                "reason" to "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    private fun registerWatcher(fn: Any?) {
        val f = fn ?: return
        if (listeners.isEmpty()) return
        if (watcherRegistered) return
        try {
            val watcherClass = Class.forName(CLS_WATCHER)
            val handler = java.lang.reflect.InvocationHandler { _, method, args ->
                when (method.name) {
                    "onFunctionValueChanged" -> {
                        val func = args?.getOrNull(0) as? Int ?: -1
                        val zone = args?.getOrNull(1) as? Int ?: 0
                        val value = args?.getOrNull(2) as? Int ?: 0
                        if (func == HvacIds.FUNC_360) {
                            // PAC 回调：只在 360 开关状态变化时记一条，
                            // 排障场景是「360 到底有没有被车机接受/打开」。
                            val on = value != 0
                            if (last360State != on) {
                                Logx.i(LogTags.CAM360, "360 状态回调", "on" to on, "value" to value)
                                last360State = on
                            }
                            on360State?.invoke(on)
                        } else {
                            Logx.d(
                                LogTags.VEHICLE,
                                "属性变化回调",
                                "func" to VehicleLog.hex(func),
                                "name" to VehicleLog.funcName(func),
                                "zone" to zone,
                                "value" to value,
                            )
                            listeners.forEach { it(func, zone, value) }
                        }
                        null
                    }
                    "onCustomizeFunctionValueChanged" -> {
                        val func = args?.getOrNull(0) as? Int ?: -1
                        val zone = args?.getOrNull(1) as? Int ?: 0
                        val value = args?.getOrNull(2) as? Float ?: 0f
                        if (func == HvacIds.TEMP) {
                            Logx.d(
                                LogTags.VEHICLE,
                                "属性变化回调（温度）",
                                "func" to VehicleLog.hex(HvacIds.TEMP),
                                "zone" to zone,
                                "celsius" to value,
                                "raw" to HvacCodec.tempRaw(value),
                            )
                            listeners.forEach { it(HvacIds.TEMP, zone, HvacCodec.tempRaw(value)) }
                        }
                        null
                    }
                    else -> null
                }
            }
            val watcher = Proxy.newProxyInstance(watcherClass.classLoader, arrayOf(watcherClass), handler)
            f.javaClass.getMethod("registerFunctionValueWatcher", watcherClass).invoke(f, watcher)
            watcherRegistered = true
            // 排障场景：注册失败时车机侧变化永远回不来（表现为 UI 不刷新），
            // 这里记下失败原因，否则现场根本无从判断。
            Logx.i(LogTags.VEHICLE, "注册属性变化回调成功", "channel" to id, "listeners" to listeners.size)
        } catch (t: Throwable) {
            // 回调订阅不是硬需求：注册失败时只做读/写与全量刷新
            Logx.w(
                LogTags.VEHICLE,
                "注册属性变化回调失败",
                t,
                "channel" to id,
                "reason" to "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    /**
     * 幂等地注册值变化回调。
     *
     * 监听者可能早于句柄就绪（`registerListener` 在 之前调用），
     * 也可能晚于句柄就绪——两个方向都要覆盖，否则车机侧的变化永远回不来。
     */
    private fun ensureWatcher() {
        if (listeners.isEmpty()) return
        val f = function ?: return
        if (watcherRegistered) return
        registerWatcher(f)
    }

    companion object {
        private val INT: Class<*> = Integer.TYPE
        private val FLOAT: Class<*> = java.lang.Float.TYPE
        private const val CLS_CAR = "com.ecarx.xui.adaptapi.car.Car"
        private const val CLS_ICAR_FUNCTION = "com.ecarx.xui.adaptapi.car.base.ICarFunction"
        private const val CLS_WATCHER = "com.ecarx.xui.adaptapi.car.base.ICarFunction\$IFunctionValueWatcher"
        private const val CLS_CONNECT_WATCHER = "com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher"

        /** 固件用 -1 表示「该属性不可读」（-1 / Integer.MIN_VALUE 判定）。 */
        private const val INVALID = -1

        /**
         * 「写入属性成功」这条 INFO 的节流间隔（毫秒）。
         *
         * 氛围灯每帧都要写一次车控（10Hz），这条日志是 INFO（**落盘**），
         * 不节流的话每秒 10 次「开文件→写→关文件」全在调用线程上 ——
         * 主线程被拖住，窗口只剩背景色（黑），再久就被看门狗杀进程
         * （第七十九轮 P0）。详见 [logWriteSuccess]。
         */
        private const val SUCCESS_LOG_INTERVAL_MS = 3000L

        /** 当前设备是否存在 AdaptAPI（即是否运行在 ECARX 车机上）。 */
        fun probe(): Boolean = try {
            Class.forName(CLS_ICAR_FUNCTION)
            true
        } catch (t: Throwable) {
            // 排障场景：非 ECARX 车机上这必然失败，记一笔说明「为什么没走 AdaptAPI 通道」。
            Logx.d(
                LogTags.VEHICLE,
                "AdaptAPI 探测失败（不是 ECARX 车机或系统类不可见）",
                "reason" to "${t.javaClass.simpleName}: ${t.message}",
            )
            false
        }
    }
}
