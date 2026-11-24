package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.Camera360Policy
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 360 环影联动的**挡位来源**。
 *
 * ## 为什么要有这一层
 *
 * 挡位在同一台车上有好几条通道，可靠性各不相同，而联动决策只关心
 * 「现在是什么挡、刚才是什么挡」。把「从哪读」收在这里，执行端
 * （`vehicle/Camera360Link.kt`）和决策层（`core/Camera360Policy.kt`）就不必关心通道细节，
 * 换车/换固件时也只改这一个文件。
 *
 * ## 优先级（[current] 的顺序，命中即返回）
 *
 * 1. **模拟车辆通道**：当前车控网关是 [MockVehicleGateway] 时，直接读它的 `gear` 文本。
 *    模拟器上的验证就走这条 —— 它必须是第一优先，否则会被真实车机通道的残留值盖掉。
 * 2. **`car_signal` 信号 29693**（[EcarxSignals.gear]）：实车最直接的一条，事件回调 + 轮询双保险。
 * 3. **AOSP `CURRENT_GEAR` 属性**（[VehicleIds.CURRENT_GEAR]）：最后兜底。
 *    不同 HAL 的编码与车厂信号不一致，所以这一级读到的**原始值一定落日志**，
 *    实车校准时直接看日志改 `Camera360Policy.gearFromAospProperty` 的映射即可。
 *
 * 三级都读不到就返回 `null`；上层收到 null 不会做任何联动动作，
 * 宁可不联动，也不要拿猜出来的挡位去开环影。
 *
 * ## 为什么还要 1 秒轮询
 *
 * `car_signal` 的回调依赖固件把 29693 加进 `SignalFilter` 白名单并真的派发；
 * 实车上偶尔会丢回调。轮询是兜底：值变了才回调，不变不打扰上层。
 * 模拟车辆通道的 `gear` 不会被回调，所以它也靠轮询发现变化。
 *
 * ## 待接通道（本轮不实现）
 *
 * 车机还有一条**厂商属性字节串**通道（属性 id 560993536 = `0x21701500`），
 * 返回值形如 `"08012002"`（D）/ `"08012006"`（N）/`"08012004"`（R）。
 * 目前 `AdaptapiGateway` 只有数字属性读写，没有字符串/字节属性通路，
 * 为它大改网关不划算，因此本轮不接；将来接入时在 [current] 里加一级，
 * 并在 `Camera360Policy` 里加一个「字节串 → 挡位」的纯函数（可单测）。
 */
object GearSource {

    /** 轮询间隔：1 秒足够（挡位变化到环影打开本来就是百毫秒级的体验要求，不是为了竞速）。 */
    private const val POLL_MS = 1_000L

    /** 通道缺失类日志的限频（毫秒），避免每秒轮询把日志刷爆。 */
    private const val MISSING_LOG_INTERVAL_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())

    /**
     * 挡位变化回调（只在值**真的变了**的时候触发；第一次读到有效挡位也算变化）。
     *
     * 回调在主线程；参数是 `D` / `R` / `N` / `P`，读不到时为 `null`。
     */
    @Volatile
    var onGearChanged: ((Char?) -> Unit)? = null

    private var started = false
    private var appContext: Context? = null
    private var poller: Runnable? = null

    /** 上一次通知出去的值（用来判断「变了没有」）。 */
    private var lastPublished: Char? = null
    private var hasPublished = false

    /** 上一份 `CURRENT_GEAR` 原始值（只在变化时落日志）。 */
    private var lastPropRaw: Int? = null

    /** 两路「通道缺失」日志各自的限频时间戳。 */
    private var lastGearMissingLogAt = 0L
    private var lastPropMissingLogAt = 0L

    private val gearBridge: (Char?) -> Unit = { gear ->
        main.post { publish(gear, "信号回调") }
    }

    /**
     * 读当前挡位（按 [GearSource] 的优先级降级）。
     *
     * @param context 用于初始化 AOSP 属性兜底通道（[CarProperties.attach] 是幂等的）。
     */
    fun current(context: Context): Char? {
        val mock = mockGateway()
        if (mock != null) {
            val gear = Camera360Policy.gearFromMockText(mock.gear)
            if (gear == null) {
                Logx.dThrottled(
                    LogTags.VEHICLE, "gear-mock-unmapped", MISSING_LOG_INTERVAL_MS,
                    "模拟车辆挡位文本无法识别", "text" to mock.gear,
                )
            }
            return gear
        }
        EcarxSignals.gear()?.let { return it }
        return fromCarProperty(context)
    }

    /** 启动：订阅信号回调 + 启动 1 秒轮询。幂等。 */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext ?: context
        appContext = app
        EcarxSignals.onGearChanged = gearBridge
        Logx.i(
            LogTags.CAM360, "挡位来源启动",
            "mock" to (mockGateway() != null),
            "signalAvailable" to EcarxSignals.isAvailable(),
            "propAvailable" to CarProperties.isAvailable(),
        )
        // 先立刻读一次：不依赖轮询的首个 1 秒，挂挡后尽快出画面。
        main.post { publish(runCatching { current(app) }.getOrNull(), "启动读取") }
        val r = object : Runnable {
            override fun run() {
                if (!started) return
                publish(runCatching { current(app) }.getOrNull(), "轮询")
                main.postDelayed(this, POLL_MS)
            }
        }
        poller = r
        main.postDelayed(r, POLL_MS)
    }

    /** 停止轮询与回调订阅。幂等。 */
    fun stop() {
        if (!started) return
        started = false
        poller?.let { main.removeCallbacks(it) }
        poller = null
        if (EcarxSignals.onGearChanged === gearBridge) EcarxSignals.onGearChanged = null
        onGearChanged = null
        appContext = null
        hasPublished = false
        lastPublished = null
        lastPropRaw = null
        Logx.i(LogTags.CAM360, "挡位来源已停止")
    }

    /**
     * 调试用：注入一次挡位事件（模拟器/设置页调试入口）。
     *
     * **只在模拟车辆通道下生效**：把 [MockVehicleGateway] 的 `gear` 文本一起改掉
     * （后续轮询保持一致），再触发一次挡位事件。
     *
     * 其它通道一律不注入：真实车机上挡位只能来自车机信号，
     * 用一个假挡位去驱动环影开关会让人误以为联动真的在工作。
     */
    fun simulate(context: Context, gear: Char) {
        val normalized = Camera360Policy.gearFromMockText(gear.toString()) ?: run {
            Logx.w(LogTags.CAM360, "模拟挡位参数不合法（只接受 D/R/N/P）", "gear" to gear)
            return
        }
        val mock = mockGateway()
        if (mock == null) {
            Logx.w(
                LogTags.CAM360, "模拟挡位：当前不是模拟车辆通道，忽略（真实挡位只能来自车机信号）",
                "gear" to normalized,
            )
            return
        }
        mock.gear = normalized.toString()
        Logx.event(LogTags.CAM360, "模拟挡位切换（模拟车辆通道）", "gear" to normalized)
        main.post { publish(normalized, "调试注入") }
    }

    // ------------------------------------------------------------ 内部实现

    /** 当前网关是不是模拟车辆通道（不是就返回 null，让调用方继续降级）。 */
    private fun mockGateway(): MockVehicleGateway? {
        if (!VehicleController.initialized) return null
        val gw = runCatching { VehicleController.gateway }.getOrNull() ?: return null
        return gw as? MockVehicleGateway
    }

    /**
     * AOSP `CURRENT_GEAR` 属性兜底。
     *
     * 每次取到新原始值都落一条日志（只在变化时），实车校准时对照
     * [Camera360Policy.gearFromAospProperty] 的口径就能发现偏差。
     */
    private fun fromCarProperty(context: Context): Char? {
        if (!CarProperties.isAvailable()) {
            val attached = runCatching { CarProperties.attach(context) }.getOrDefault(false)
            if (!attached) {
                lastPropMissingLogAt = logMissingPropChannel(lastPropMissingLogAt)
                return null
            }
        }
        val raw = runCatching { CarProperties.readInt(VehicleIds.CURRENT_GEAR, 0) }.getOrNull()
        if (raw == null) {
            lastPropMissingLogAt = logMissingPropChannel(lastPropMissingLogAt)
            return null
        }
        val gear = Camera360Policy.gearFromAospProperty(raw)
        if (raw != lastPropRaw) {
            lastPropRaw = raw
            Logx.i(
                LogTags.VEHICLE, "CURRENT_GEAR 原始值（实车校准用）",
                "propId" to "0x%08X".format(VehicleIds.CURRENT_GEAR),
                "raw" to raw,
                "gear" to (gear?.toString() ?: "未识别"),
            )
        }
        return gear
    }

    private fun logMissingPropChannel(lastAt: Long): Long {
        val now = System.currentTimeMillis()
        if (now - lastAt < MISSING_LOG_INTERVAL_MS) return lastAt
        Logx.d(LogTags.VEHICLE, "挡位兜底通道不可用（CarProperties 未就绪或 CURRENT_GEAR 无值）")
        return now
    }

    private fun publish(gear: Char?, source: String) {
        if (!started) return
        if (gear == null && !hasPublished) {
            // 首次就读不到：只落日志，不当成「挡位变化」通知上层（上层会继续等）。
            lastGearMissingLogAt = logMissingGear(lastGearMissingLogAt, source)
            return
        }
        if (hasPublished && gear == lastPublished) return
        val prev = lastPublished
        lastPublished = gear
        hasPublished = true
        Logx.i(
            LogTags.CAM360, "挡位更新",
            "from" to (prev?.toString() ?: "未知"),
            "to" to (gear?.toString() ?: "未知"),
            "source" to source,
        )
        onGearChanged?.invoke(gear)
    }

    private fun logMissingGear(lastAt: Long, source: String): Long {
        val now = System.currentTimeMillis()
        if (now - lastAt < MISSING_LOG_INTERVAL_MS) return lastAt
        Logx.d(LogTags.CAM360, "挡位暂不可用（等待车控通道就绪）", "source" to source)
        return now
    }
}
