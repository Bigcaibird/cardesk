package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车身状态（车门开闭）——走 AdaptAPI 的 **ICarFunction** 通道。
 *
 * ## 这一档在第六十六轮之前为什么**整条失效**
 *
 * 真机（领克 dcy11 / Android 9）诊断包里，四个门位全部返回 `raw=255`：
 *
 * ```
 * 车门状态探针值无法解释 label=主驾 zone=1 raw=255
 * 车门状态探针值无法解释 label=副驾 zone=4 raw=255
 * 车门状态探针值无法解释 label=左后 zone=16 raw=255
 * 车门状态探针值无法解释 label=右后 zone=64 raw=255
 * ```
 *
 * `255` 就是 `-1`，jar 里带着这句话：
 * `is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`。
 *
 * **根因是 zone 用错了**：我们用的是君君那台车的 `{1, 4, 16, 64}`，
 * 而这台车机 jar 里的门区符号名是
 * `DOOR_ROW_1_LEFT` / `DOOR_ROW_1_RIGHT` / `DOOR_ROW_2_LEFT` / `DOOR_ROW_2_RIGHT`
 * （另有 `DOOR_HOOD` / `DOOR_REAR`），**编号是另一套**。
 *
 * ## 现在的做法：**按名字找 zone，不猜数值**
 *
 * 常量就在我们**已经拿在手里的** `ICarFunction` 句柄的同一个 jar 里，
 * 所以由 [AdaptapiConstants] 从句柄的类出发按名字解析：
 *
 * 1. 解析出来的 zone **优先**；
 * 2. 解析不到（或读不出值）时**退回**老的 `{1, 4, 16, 64}`；
 * 3. 固件若提供 `isFunctionSupported` 探针，**先问一句再读**，
 *    把「这一档这台车没有」与「读到了但值解释不出来」分开。
 *
 * 功能号 `BCM_FUNC_DOOR_POS = 553779968` 是 jar 里的确定值，
 * 仍会按名解析一次做交叉校验（解析出不同的值以解析结果为准）。
 *
 * ## 降级链
 *
 * 1. —— ECarXCar 的 SignalManager
 *    （`getDoorDrvrSts/getDoorPassSts/...`），这台车上**那个类不存在** → 跳过；
 * 2. —— `IDoorAndWindow.isDoorOpened`（车机原生 BCM 布尔）→ 见 [OemDoorWindowSignals]；
 * 3. **`adaptPosAllClosed(BCM_DOOR_POS, zone)`** —— AdaptAPI 的属性通道 ← 我们走这条；
 * 4. `aggregatePosClosed(373295872)` —— `car_service` 的 property 通道（另一套 AIDL）。
 *
 * ## 与一处刻意差异
 *
 * `getFuncVal(func, 0)` 在**读取失败**时返回默认值 `0`
 * 而 `interpretClosed(0)` = 「关闭」—— 也就是说 zone 0 读不出来时，
 * 会直接给出「车门全关」，根本走不到逐门位那一段。
 * 我们把「读取失败」当成「没有值」（null），继续往下走逐门位聚合。
 *
 * 理由：那正是真机上「车门状态一直显示关」的表现 —— 读不出来 ≠ 关着。
 * 差别只在「读取失败」时生效；读取成功时与完全一致。
 */
object AdaptapiBody {

    /** 车门位置功能号（jar 里的 `BCM_FUNC_DOOR_POS`）。 */
    const val BCM_DOOR_POS = 553779968

    /**
     * 四个门位对应的 zone（**君君那台车**的约定）。
     *
     * 现在只作为**兜底**：按名解析出来的 zone 优先（见 [AdaptapiConstants]）。
     */
    val DOOR_ZONES = intArrayOf(1, 4, 16, 64)

    /** 门位名（按 [DOOR_ZONES] 顺序），只用于日志与车辆页展示。 */
    val DOOR_NAMES = arrayOf("主驾", "副驾", "左后", "右后")

    /** 「无 zone」的探针值（用 `Integer.MIN_VALUE`）。 */
    const val ZONE_NONE = Int.MIN_VALUE

    /** 整体值探针的 zone（用 `0`）。 */
    const val ZONE_AGGREGATE = 0

    // ------------------------------------------------------------ 功能号 / zone

    /**
     * 车门位置功能号。
     *
     * 按名解析优先（这台车机上 jar 里的值可能与君君那台不同），
     * 解析不到就用 [BCM_DOOR_POS]。
     */
    fun doorFunc(): Int =
        AdaptapiConstants.funcId(AdaptapiConstants.FUNC_DOOR_POS, BCM_DOOR_POS) ?: BCM_DOOR_POS

    /**
     * 车门功能号（**车窗**用；jar 里的 `BCM_FUNC_WINDOW`）。
     *
     * 我们**不知道**这个功能号的数值，只能按名解析。
     * 解析不到就返回 null —— 不猜一个数值去读别人的功能。
     */
    fun windowFunc(): Int? = AdaptapiConstants.funcId(AdaptapiConstants.FUNC_WINDOW)

    /**
     * 某个门位要试的 zone 列表（**按优先级**）。
     *
     * - 按名解析出来的 zone 在前；
     * - 老约定 `{1, 4, 16, 64}` 在后（兜底）；
     * - 固件支持探针说「不支持」的直接剔除；
     * - 探针判不了（固件没这个方法）就照常都试。
     */
    internal fun doorZoneProbes(gw: VehicleGateway, func: Int, position: Int): List<Int> {
        val legacy = DOOR_ZONES[position]
        val resolved = AdaptapiConstants.doorZones()?.getOrNull(position)
        val candidates = LinkedHashSet<Int>()
        resolved?.let { candidates.add(it) }
        candidates.add(legacy)

        val verdicts = candidates.map { it to supported(gw, func, it) }
        // 固件明确说「支持」→ 只读这些
        val supported = verdicts.filter { it.second == true }.map { it.first }
        if (supported.isNotEmpty()) return supported
        // 全都判不出来 → 读那些判不出来的（还有机会）
        val unknown = verdicts.filter { it.second == null }.map { it.first }
        return if (unknown.isNotEmpty()) unknown else emptyList()
    }

    /**
     * 「固件是否支持这个功能号 / 区域」的**结论缓存**。
     *
     * 固件能力在一次运行里不会变，所以只问一次。
     * 按**通道实例**分开存（不是按通道 id）——
     * 换通道（真车 ↔ 模拟）时结论不会串。
     */
    private val supportCaches = java.util.IdentityHashMap<VehicleGateway, HashMap<String, Boolean?>>()

    private fun supported(gw: VehicleGateway, func: Int, zone: Int): Boolean? {
        val cache = supportCaches.getOrPut(gw) { HashMap() }
        val key = "$func#$zone"
        if (cache.containsKey(key)) return cache[key]
        val v = gw.isFunctionSupported(func, zone)
        cache[key] = v
        return v
    }

    /** 清掉支持结论缓存（通道被重建时调）。 */
    fun clearSupportCache() {
        supportCaches.clear()
    }

    // ------------------------------------------------------------ 结果

    /**
     * 车门状态的一次读取结果。
     *
     * @param eachClosed 四门各自的开闭（顺序同 [DOOR_ZONES]）；
     *   **每一项独立** —— 某一扇读不出来时那一项是 null，其余照常（第四十五轮改）
     * @param allClosed 四门是否全部关闭（返回值）；null = 读不出来
     * @param hood 引擎盖（true = 关）；null = 读不到
     * @param trunk 后备箱（true = 关）；null = 读不到
     */
    data class DoorState(
        val eachClosed: List<Boolean?>?,
        val allClosed: Boolean?,
        val hood: Boolean? = null,
        val trunk: Boolean? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DoorState) return false
            if (allClosed != other.allClosed) return false
            if (hood != other.hood) return false
            if (trunk != other.trunk) return false
            return eachClosed == other.eachClosed
        }

        override fun hashCode(): Int {
            var h = (eachClosed?.hashCode() ?: 0)
            h = h * 31 + (allClosed?.hashCode() ?: 0)
            h = h * 31 + (hood?.hashCode() ?: 0)
            h = h * 31 + (trunk?.hashCode() ?: 0)
            return h
        }
    }

    // ------------------------------------------------------------ 车门

    /**
     * 读一次车门状态（一次通道调用里把整体值与四个门位都读出来）。
     *
     * @return 完全读不到时 `eachClosed` 与 `allClosed` 都是 null
     */
    fun readDoors(gw: VehicleGateway): DoorState {
        val func = doorFunc()
        // —— 逐门位（每一扇独立：读不到的那一扇留 null，不作废整组）——
        val perZone = MutableList<Boolean?>(DOOR_ZONES.size) { null }
        var anyZone = false
        var allZoneClosed = true
        for (i in DOOR_ZONES.indices) {
            val closed = readDoorZone(gw, func, i)
            if (closed == null) continue
            perZone[i] = closed
            anyZone = true
            if (!closed) allZoneClosed = false
        }

        // —— 引擎盖 / 后备箱（`DOOR_HOOD` / `DOOR_REAR` 两个区域）——
        val hood = readAuxZone(gw, func, AdaptapiConstants.hoodZone(), "引擎盖")
        val trunk = readAuxZone(gw, func, AdaptapiConstants.trunkZone(), "后备箱")

        // —— 整体值（探测顺序：无 zone → zone 0 → 逐门位聚合）——
        val rawNone = gw.readRawFunctionValue(func, ZONE_NONE)
        if (rawNone != null) {
            val closed = CarSignalMath.interpretClosed(rawNone)
            if (closed != null) {
                logDoorProbe("整体(无zone)", ZONE_NONE, rawNone, closed)
                return DoorState(perZone, closed, hood = hood, trunk = trunk)
            }
        }
        val rawAgg = gw.readRawFunctionValue(func, ZONE_AGGREGATE)
        if (rawAgg != null) {
            val closed = CarSignalMath.interpretClosed(rawAgg)
            if (closed != null) {
                logDoorProbe("整体(zone0)", ZONE_AGGREGATE, rawAgg, closed)
                return DoorState(perZone, closed, hood = hood, trunk = trunk)
            }
        }
        if (!anyZone) {
            // —— 第三档：CarPropertyManager 的 DOOR_POS 属性（降级链的最后一档）——
            // 第三十六轮核对《车辆运行数据 API 对照表》时补齐：
            // `aggregatePosClosed(373295872)` 走的就是
            // `DOOR_POS`（`0x16400B00`，AOSP 标准属性 id），我们之前没接这一档。
            val viaProp = CarBodySignals.readDoorsViaProperty()
            if (viaProp != null) {
                Logx.i(LogTags.SIGNAL, "车门状态走 DOOR_POS 属性", "closed" to viaProp.joinToString(","))
                return DoorState(viaProp, BodyState.aggregateAllClosed(viaProp), hood = hood, trunk = trunk)
            }
            Logx.w(
                LogTags.SIGNAL,
                "车门状态读不到（BCM_DOOR_POS 所有探针都没有有效值）",
                "func" to func,
            )
            return DoorState(null, null, hood = hood, trunk = trunk)
        }
        return DoorState(perZone, allZoneClosed, hood = hood, trunk = trunk)
    }

    /**
     * 读一个「非门位」的区域（引擎盖 / 后备箱）。
     *
     * 区域号按名解析（`DOOR_HOOD` / `DOOR_REAR`），解析不到就返回 null ——
     * 不拿别的区域号顶替。
     */
    private fun readAuxZone(gw: VehicleGateway, func: Int, zone: Int?, label: String): Boolean? {
        if (zone == null) return null
        val raw = gw.readRawFunctionValue(func, zone) ?: return null
        if (CarSignalMath.isUnsupportedRaw(raw)) {
            Logx.d(LogTags.SIGNAL, "车身区域不被固件支持", "label" to label, "zone" to zone, "raw" to raw)
            return null
        }
        val closed = CarSignalMath.interpretClosed(raw) ?: return null
        Logx.d(LogTags.SIGNAL, "车身区域", "label" to label, "zone" to zone, "raw" to raw, "closed" to closed)
        return closed
    }

    /**
     * 读一个门位（**逐扇独立**）。
     *
     * 按 [doorZoneProbes] 的顺序逐个 zone 试：
     * - 读不到 → 试下一个 zone；
     * - 读到但固件说**不支持**（`-1` / `255`）→ 记一条 DEBUG，换下一个 zone；
     * - 读到但值**解释不出来** → 记一条 DEBUG，换下一个 zone；
     * - 读到且能解释 → 这一扇有值了。
     */
    private fun readDoorZone(gw: VehicleGateway, func: Int, position: Int): Boolean? {
        val label = DOOR_NAMES[position]
        for (zone in doorZoneProbes(gw, func, position)) {
            val raw = gw.readRawFunctionValue(func, zone) ?: continue
            if (CarSignalMath.isUnsupportedRaw(raw)) {
                // 「不支持」与「值解释不出来」是两回事：前者是 zone/功能号不对，
                // 后者是读到了但语义不明 —— 日志里必须分开，否则排障会被带偏。
                Logx.d(
                    LogTags.SIGNAL,
                    "车门功能号/区域不被固件支持",
                    "label" to label,
                    "zone" to zone,
                    "raw" to raw,
                )
                continue
            }
            val closed = CarSignalMath.interpretClosed(raw)
            if (closed == null) {
                Logx.d(
                    LogTags.SIGNAL,
                    "车门状态探针值无法解释",
                    "label" to label,
                    "zone" to zone,
                    "raw" to raw,
                )
                continue
            }
            logDoorProbe(label, zone, raw, closed)
            return closed
        }
        return null
    }

    /** 探针日志（DEBUG 不落盘，这里用 INFO 只在值变化时才有意义 —— 探针每次都打会刷屏）。 */
    private fun logDoorProbe(label: String, zone: Int, raw: Int, closed: Boolean) {
        Logx.d(
            LogTags.SIGNAL,
            "车门状态探针",
            "label" to label,
            "zone" to zone,
            "raw" to raw,
            "closed" to closed,
        )
    }

    // ------------------------------------------------------------ 车窗

    /**
     * 四扇车窗的开闭（**true = 开**），顺序 FL/FR/RL/RR；读不到返回 null。
     *
     * 走 AdaptAPI 的 **`BCM_FUNC_WINDOW`**（布尔开合，不是百分比）：
     * - 功能号按名解析（`BCM_FUNC_WINDOW`）—— 数值我们不知道，**不猜**；
     * - 区域号按名解析（`WINDOW_ROW_1_LEFT` / `_RIGHT` / `WINDOW_ROW_2_LEFT` / `_RIGHT`）；
     * - 两者任一解析不到就返回 null（这一档就当没有）。
     *
     * 口径见 [CarSignalMath.interpretWindowOpen]：
     * 只认 `0`（关）/ `1`（开）/ BCM 的「开」枚举，**不臆造开合百分比**。
     */
    fun readWindowsEach(gw: VehicleGateway): List<Boolean?>? {
        val func = windowFunc() ?: run {
            Logx.d(LogTags.SIGNAL, "车窗功能号按名解析不到", "name" to AdaptapiConstants.FUNC_WINDOW)
            return null
        }
        val zones = AdaptapiConstants.windowZones()
        if (zones == null) {
            Logx.d(LogTags.SIGNAL, "车窗区域号按名解析不到")
            return null
        }
        val out = MutableList<Boolean?>(DOOR_ZONES.size) { null }
        var any = false
        for (i in DOOR_ZONES.indices) {
            val zone = zones.getOrNull(i) ?: continue
            val raw = gw.readRawFunctionValue(func, zone) ?: continue
            if (CarSignalMath.isUnsupportedRaw(raw)) {
                Logx.d(
                    LogTags.SIGNAL,
                    "车窗功能号/区域不被固件支持",
                    "zone" to zone,
                    "raw" to raw,
                )
                continue
            }
            val open = CarSignalMath.interpretWindowOpen(raw) ?: continue
            out[i] = open
            any = true
        }
        if (!any) {
            Logx.d(LogTags.SIGNAL, "车窗状态读不到（BCM_FUNC_WINDOW）", "func" to func)
            return null
        }
        Logx.d(
            LogTags.SIGNAL,
            "车窗状态（BCM_FUNC_WINDOW）",
            "open" to out.joinToString(",") { c -> c?.let { if (it) "开" else "关" } ?: "?" },
        )
        return out
    }
}
