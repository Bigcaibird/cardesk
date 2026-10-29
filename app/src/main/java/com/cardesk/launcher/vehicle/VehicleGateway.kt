package com.cardesk.launcher.vehicle

/**
 * 车辆访问通道抽象。
 *
 * 桌面 UI 只依赖这个接口；真车通道（AdaptAPI / 私有 ECarXCar）与模拟通道
 * 可以互换，探测失败时自动降级为模拟数据，UI 不需要感知。
 */
interface VehicleGateway {

    val id: String

    val displayName: String

    /** 360 状态变化（可选回调，模拟通道也会回调，方便 UI 联动） */
    var on360State: ((Boolean) -> Unit)?

    fun connect(onResult: (Boolean) -> Unit)

    fun disconnect()

    fun isConnected(): Boolean

    /** 写一个功能属性，成功返回 true。 */
    fun setHvac(func: Int, zone: Int, value: Int): Boolean

    /**
     * 写除霜。
     *
     * 除霜走特殊回退：先试「无 zone」的两参重载，再按 `{2,1,0}` 试三参重载
     * ——不同固件对除霜的 zone 约定不一致。
     */
    fun setDefrost(func: Int, on: Boolean): Boolean =
        setHvac(func, HvacIds.ZONE_GLOBAL, if (on) 1 else 0)

    /** 写温度（优先走 float 通道）。 */
    fun setTemp(zone: Int, celsius: Float): Boolean

    /** 全量读当前属性，读不到的项不出现在 map 里（区域相关项按主驾返回）。 */
    fun readAll(): Map<Int, Int>

    /** 按区域读取单个属性；通道不支持时返回 null。 */
    fun readValue(func: Int, zone: Int): Int? = null

    /**
     * 读一个功能属性的**原始值**。
     *
     * 与 [readValue] 的区别：
     * - 只用**两参** `getFunctionValue(func, zone)`，不退回一参重载
     *   （一参拿到的是全局值，逐 zone 读时会四个门位全读成同一个值）；
     * - **原样返回**，不做 `INVALID` / `Int.MIN_VALUE` 的过滤，
     *   `0` 也要如实返回（`0` 在车门语义里是「关闭」）。
     *
     *
     */
    fun readRawFunctionValue(func: Int, zone: Int): Int? = null

    /**
     * 读一个功能属性的**自定义（浮点）值**。
     *
     * ## 为什么要有这一条（第七十一轮）
     *
     * 车机端 `ICarFunction` 有两条取值通道：
     *
     * - `getFunctionValue(func, zone)` → `int` —— **整型**通道；
     * - `getCustomizeFunctionValue(func, zone)` → `float` —— **自定义**通道。
     *
     * 逐扇车窗的开度**只在浮点通道里实现**：
     * 整型通道对 `BCM_FUNC_WINDOW_POS` 直接返回 `-1`
     * （jar 里的原话：`is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`），
     * 而浮点通道按 `zone` 分派到四个逐窗信号
     * （`PaSpeechWinCtrlFrntLe` / `FrntRi` / `ReLe` / `ReRi`）。
     *
     * 整型通道读不到的东西，浮点通道能读到 —— 这就是必须分两条路的原因。
     *
     * @return null = 通道不可用 / 方法不存在 / 调用失败
     */
    fun readCustomizeFunctionValue(func: Int, zone: Int): Int? = null

    /**
     * 固件**是否支持**这个功能号 / 区域。
     *
     * ## 为什么要有这一条（第六十六轮）
     *
     * 真机上四个门位全部返回 `raw=255`（= `-1`），jar 里的提示串是
     * `is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`。
     * 也就是说「读到了」不等于「这一档存在」。
     *
     * 有这个探针就能**先问一句再读**，把「不支持」与「读不到值」分开：
     * - `false` → 这一档这台车没有，直接跳过（省一次调用，日志也清楚）；
     * - `true` → 支持，正常读；
     * - `null` → **固件没有这个方法**，判不了 → 按「支持」继续读（老口径）。
     */
    fun isFunctionSupported(func: Int, zone: Int): Boolean? = null

    // -------------------------------------------------- 车辆概览信号

    /**
     * 剩余续航（km）；读不到返回 null。
     *
     * 来源依次是
     * `carSignal(DST_TO_EMPTY)` → PHEV DTE → `ISensor` 的 4 个续航传感器 → `car.RANGE_REMAINING`。
     */
    fun rangeKm(): Int? = null

    /**
     * 油量百分比（0..100）；读不到返回 null。
     *
     * `ISensor` `SENSOR_FUEL_LEVEL` 原始值
     * 换算（换算要用油箱容量 `INFO_FUEL_CAPACITY`）。
     */
    fun fuelPercent(): Int? = null

    /** 车外温度（℃）；读不到返回 null。`ISensor` `SENSOR_TEMP_AMBIENT`。 */
    fun ambientTempC(): Float? = null

    /**
     * 四轮胎压（**kPa**），顺序 **FL / FR / RL / RR**；读不到返回 null。
     *
     * `TPMS.getTireState(pos)`，轮位映射 1→FL、3→FR、2→RL、4→RR。
     */
    fun tirePressures(): FloatArray? = null

    /**
     * 写四扇车窗的开闭（true = 开）。
     *
     * 真车通道**不实现**这条：原厂车窗走 `VfmiscManager`（见
     * [OemWindowController]，不是属性通道，所以默认返回 false，
     * 由调用方转去走 `OemWindowController`。
     *
     * 模拟通道实现它，是为了在**没有真车**的环境里也能演示
     * 「点一下车窗 → 桌面状态跟着变」的完整链路。
     */
    fun setWindowsOpen(open: Boolean): Boolean = false

    fun registerListener(listener: (func: Int, zone: Int, value: Int) -> Unit)

    fun unregisterListener(listener: (func: Int, zone: Int, value: Int) -> Unit)

    /**
     * 最近一次通道级错误（连接失败、写入被拒、反射异常……），供设置页与 Toast 使用。
     *
     * 通道级错误不会自己冒泡——用户看到的只是「点了没反应」，
     * 所以必须显式暴露出来。
     */
    fun lastError(): String? = null

    /**
     * 车机服务是否真的连上。
     *
     * 区别于 [isConnected]（「本地句柄已创建」）：真车通道上句柄可以先建好，
     * 但要等服务真正连上才接受属性写入。
     */
    fun isServiceConnected(): Boolean = isConnected()

    /**
     * 打开/关闭 360 全景。
     *
     * 用的是**两参**重载
     * `setFunctionValue(FUNCTION_360, value)`——360 属性不带 zone。
     */
    fun set360(on: Boolean): Boolean = setHvac(HvacIds.FUNC_360, HvacIds.ZONE_GLOBAL, if (on) 1 else 0)

    /** 切换 360 视角。 */
    fun select360View(view: Int): Boolean = setHvac(HvacIds.FUNC_360_VIEW, HvacIds.ZONE_GLOBAL, view)

    companion object {
        const val CHANNEL_ADAPTAPI = "adaptapi"
        const val CHANNEL_ECARX = "ecarx"
        const val CHANNEL_CARLIB = "carlib"
        const val CHANNEL_MOCK = "mock"
    }
}
