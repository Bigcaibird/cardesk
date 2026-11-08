package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 发动机启停（ISS，Idle Start Stop）。
 *
 * ## 这个功能号是哪来的
 *
 * 文档 `docs/ecarx-adapterapi/data/func-map.csv` 里
 * `IVehicle.SETTING_FUNC_ENGINE_STOP_START = 537002240`（`0x20020100`）
 * 是**读、写都实现**的一条：
 *
 * - 读 → `getPaSsActivation`（`CarFunctionValueImpl.java:429`）
 * - 写 → `setCbSsActivation`（`CarFunctionValueImpl.java:1247`）
 *
 * 参考 App（君君桌面）（`com.claunchercard`）的 `DriveModeController` 用的就是它：
 * `FUNCTION_ENGINE_STOP_START = 537002240`，读 `getFunctionValue(...) == 1`
 * 判「开着」，写 `setFunctionValue(..., z ? 1 : 0)`。
 *
 * ## 读回来的值有坑：默认值是 1
 *
 * 厂商的 `CarFunctionValueUtil.getMappedFunctionValue(PaSsActivation)`
 * 在**信号缺失**时返回的是 **1**，不是错误哨兵：
 *
 * ```
 * if (pa == null || pa.data == null) { return 1; }   // 默认值 1
 * ```
 *
 * 所以「读到 1」是**二义的**：可能确实开着，也可能根本没这条信号、
 * 厂商拿默认值 1 兜住了。只有配合 [VehicleGateway.isFunctionSupported]
 * （走 `PaSsActivation.availability` → `FunctionStatus`）才能把这两种情况分开：
 *
 * | raw | supported | 结论 |
 * | --- | --- | --- |
 * | 0 | 任意 | **确凿关闭**（信号缺失只会返回 1，能读到 0 就是真的关了） |
 * | 1 | true | **确凿开启** |
 * | 1 | false / null | **判不了**（要么读不到，要么信号缺失被默认成 1） |
 *
 * 这一条决定了 UI 上不能把「读到 1」直接显示成「启停开着」。
 *
 * ## 写只有 0 / 1 有效
 *
 * `getMappedCbSsActivationValue(int)` 只认 0 和 1，其它值返回 null，
 * 厂商在 `setFunctionValue` 里直接 `return false` 并打
 * `Function value 'x' is not supported for function SETTING_FUNC_ENGINE_STOP_START`。
 *
 * ## 另一条路走不通
 *
 * 驾驶模式里的 `IDriveMode.DM_FUNC_DM_CUSTOM_EN_START_STOP`（`0x22030E00`）
 * 在厂商 jar 里**没有实现**（`func-map.csv` 里 0 行），不能用。
 */
object IssControl {

    /**
     * 发动机启停功能号（**文档口径**）。
     *
     * `IVehicle.SETTING_FUNC_ENGINE_STOP_START` = 537002240 = `0x20020100`。
     */
    const val FUNCTION_ENGINE_STOP_START = 537002240

    /** 关闭启停（`CbSsActivationValue.OFF`）。 */
    const val OFF = 0

    /** 开启启停（`CbSsActivationValue.ON`）。 */
    const val ON = 1

    /**
     * 读这个功能号用的 zone。
     *
     * 厂商的 `CarFunctionSupported.isFunctionSupported(int)` 对**不是**空调那两条
     * 的功能号统一用 zone = 0，所以读写都按 0 走。
     */
    const val ZONE = 0

    /**
     * 写入口径：只有 0 / 1 会被厂商接受。
     *
     * 其它值一律 `return false`，写之前先过这一道，省一次无效 binder 调用。
     */
    fun isWritableValue(value: Int): Boolean = value == OFF || value == ON

    /**
     * 读回来的一次完整判定。
     *
     * @param supported 固件**是否提供**这条信号；`null` = 判不了（通道没这个方法）。
     * @param raw 车端读回的原始值；`null` = 读不到。
     */
    data class State(
        val supported: Boolean?,
        val raw: Int?,
    ) {
        /**
         * 启停是不是确实开着。
         *
         * - `false` = 确凿关闭（读到 0）；
         * - `true` = 确凿开启（读到 1 **且**固件确认有这条信号）；
         * - `null` = **判不了**（读不到 / 读到的是信号缺失时的默认值 1）。
         */
        val isOn: Boolean?
            get() = when {
                raw == OFF -> false
                raw == ON && supported == true -> true
                else -> null
            }

        /**
         * 人话版本的状态文案。
         *
         * 判不了的时候**不能**说成「开着」——那会把「读不到」讲成「车机说开着」。
         */
        fun label(): String = when (isOn) {
            true -> "启停开着"
            false -> "启停已关闭"
            null -> "启停状态读不到"
        }
    }

    /**
     * 读一次启停状态（支持探针 + 原始值）。
     *
     * 读法按文档口径：先一参 `getFunctionValue(func)`，读不到再退两参
     * （部分固件只实现带 zone 的读取；两者都在文档的方法族里）。
     */
    fun read(gw: VehicleGateway): State {
        val supported = runCatching { gw.isFunctionSupported(FUNCTION_ENGINE_STOP_START, ZONE) }
            .getOrNull()
        val raw = runCatching { gw.readFunction(FUNCTION_ENGINE_STOP_START) }.getOrNull()
            ?: runCatching { gw.readValue(FUNCTION_ENGINE_STOP_START, ZONE) }.getOrNull()
        Logx.d(
            LogTags.VEHICLE,
            "读取启停状态",
            "func" to VehicleLog.hex(FUNCTION_ENGINE_STOP_START),
            "supported" to supported,
            "raw" to raw,
            "isOn" to State(supported, raw).isOn,
        )
        return State(supported, raw)
    }

    /**
     * 写一次启停。
     *
     * 走**两参** `setFunctionValue(func, value)`（文档口径，无 zone）——
     * 厂商的 `setFunctionValue` 对这个功能号是
     * `case IVehicle.SETTING_FUNC_ENGINE_STOP_START: getMappedCbSsActivationValue(value)`，
     * 认 0 / 1，不认别的。
     *
     * @return `true` = 车机收下了这条指令；`false` = 通道没接上 / 固件拒收。
     */
    fun write(gw: VehicleGateway, on: Boolean): Boolean {
        val value = if (on) ON else OFF
        if (!isWritableValue(value)) {
            Logx.w(LogTags.VEHICLE, "启停写入值非法", "value" to value)
            return false
        }
        val ok = runCatching { gw.writeFunction(FUNCTION_ENGINE_STOP_START, value) }
            .getOrDefault(false)
        Logx.i(
            LogTags.VEHICLE,
            if (ok) "写入启停状态" else "写入启停状态失败",
            "func" to VehicleLog.hex(FUNCTION_ENGINE_STOP_START),
            "value" to value,
            "target" to if (on) "开启" else "关闭",
            "ok" to ok,
            "reason" to runCatching { gw.lastError() }.getOrNull(),
        )
        return ok
    }
}
