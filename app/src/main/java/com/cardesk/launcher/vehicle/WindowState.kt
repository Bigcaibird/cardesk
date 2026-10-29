package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车窗开闭的**统一口径**（与 [BodyState] 同构）。
 *
 * ## 为什么车窗要单独一个口径（第六十六轮）
 *
 * 车门有五档来源，车窗**原本只有一档**：
 * `CarPropertyManager` 的 `WINDOW_POS` 属性。
 * 而真机诊断包里这一档是**整条不可用**的：
 *
 * ```
 * CarPropertyManager 通道不可用 path=A reason=Car.createCar 返回 null
 * 车窗 WINDOW_POS：读不到
 * ```
 *
 * 所以桌面上「车窗关 / 开」永远读不到真值。
 *
 * 文档（`vehicle-body-signals-read.md` §2 / §3.3）给出了另外两条路：
 *
 * 1. **ECARX `VehicleAPI` → `getBcm()` → `getDoorAndWindowState()` → `IDoorAndWindow`**
 *    —— `isWindowOpened(int)`，**布尔**（不是百分比），类型号 `1..4`；
 * 2. **AdaptAPI `BCM_FUNC_WINDOW`** —— 名字确定、数值未知（按名解析）。
 *
 * ## 降级链
 *
 * **源1 `IDoorAndWindow` → 源2 `BCM_FUNC_WINDOW` → 源3 `WINDOW_POS` 属性**，
 * 逐扇合并（某一扇读不到时，那一扇留 null，其余照常）。
 *
 * ## 一条不做的事
 *
 * **不臆造车窗开合百分比。**
 * 文档明确写了：车窗的「开合百分比」是**弱证据**
 * （`BCM_FUNC_WINDOW_POS` 在部分车型上整条不支持，jar 里的提示串是
 * `is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`），
 * 而且**禁止**把手机 BLE 的 `raw 2..26 → (raw-1)*4` 编码套到车机上。
 * 我们只做**布尔**。
 */
object WindowState {

    /** 源1 的名字（诊断用）。 */
    const val SOURCE_OEM = "IDoorAndWindow"

    /** 源2 的名字（诊断用）。 */
    const val SOURCE_ADAPTAPI = "BCM_FUNC_WINDOW_POS"

    /** 源3 的名字（诊断用）。 */
    const val SOURCE_PROPERTY = "WINDOW_POS"

    /**
     * 一次车窗读取的结果。
     *
     * @param eachOpen 四扇窗（**true = 开**）；每项 null = 这一扇读不到；整组 null = 全读不到
     * @param allClosed 四扇是否**全部关闭**；null = 读不出来（含「有一扇读不到」）
     * @param source 最终生效的来源（诊断用）
     */
    data class Result(
        val eachOpen: List<Boolean?>?,
        val allClosed: Boolean?,
        val source: String,
    ) {
        /**
         * 每扇开闭的**布尔数组**（UI 协议）。
         *
         * 读不到的那一扇按 `false` 处理 —— UI 只有布尔数组这一种载体，
         * 所以真正的「不确定」由 [allClosed] 承担。
         */
        val open: BooleanArray? get() = eachOpen?.map { it == true }?.toBooleanArray()
    }

    /**
     * 按来源顺序编排一次读取。
     *
     * @param gw AdaptAPI 的车辆网关（源2 的读取走它）
     * @param context 车机 Context；给了就顺带把 `IDoorAndWindow` 通道挂上（源1）
     */
    fun resolve(gw: VehicleGateway, context: Context? = null): Result {
        // —— 源1：IDoorAndWindow（车机原生 BCM 布尔）——
        if (context != null) OemDoorWindowSignals.attach(context)
        val oem = OemDoorWindowSignals.windowsOpenEach()

        // —— 源2：Adapt 的 BCM_FUNC_WINDOW ——
        val adapt = AdaptapiBody.readWindowsEach(gw)

        // —— 源3：AOSP 的 WINDOW_POS 属性 ——
        val prop = CarBodySignals.readWindowsEach()

        val result = compose(oem, adapt, prop)

        Logx.d(
            LogTags.SIGNAL,
            "车窗状态",
            "source" to result.source,
            "allClosed" to result.allClosed,
            "open" to (result.eachOpen?.joinToString(",") { c -> c?.let { if (it) "开" else "关" } ?: "?" } ?: "不可读"),
        )

        return result
    }

    /**
     * **纯口径**：把三个来源合并成最终结果（不碰 Android，可单测）。
     *
     * - 逐扇合并：每一扇按「源1 → 源2 → 源3」取**第一个能解释这一扇**的来源；
     * - 「全关」：**有一扇读不到就不能说全关**（读不到 ≠ 关着）。
     */
    internal fun compose(
        oem: List<Boolean?>?,
        adapt: List<Boolean?>?,
        prop: List<Boolean?>?,
    ): Result {
        val each = BodyState.mergeItems(oem, adapt, prop)

        val allClosed = when {
            each == null -> null
            each.any { it == null } -> null
            else -> each.none { it == true }
        }

        val source = when {
            oem != null -> SOURCE_OEM
            adapt != null -> SOURCE_ADAPTAPI
            prop != null -> SOURCE_PROPERTY
            else -> "无"
        }

        return Result(eachOpen = each, allClosed = allClosed, source = source)
    }
}
