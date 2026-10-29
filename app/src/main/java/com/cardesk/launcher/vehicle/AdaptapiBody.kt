package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车身状态（车门开闭 / 后备箱）——走 AdaptAPI 的 **ICarFunction** 通道。
 *
 * ## 第七十一轮：按车机端 jar 的实测定案
 *
 * 拿到车机端 `ecarx.adapterapi.jar`（只含 `classes.dex`）反汇编后，
 * 「车门车窗为什么读不到」这件事有了**确定答案**，不再靠猜：
 *
 * | 通道 | 功能号 | 固件实现情况 | 结论 |
 * | --- | --- | --- | --- |
 * | 整型 `getFunctionValue` | `BCM_FUNC_DOOR_POS` | **分派表里没有** → 落默认分支返回 `255` | 四个侧门读不到 |
 * | 整型 `getFunctionValue` | `BCM_FUNC_WINDOW_POS` | 明确返回 `-1` | 整型读不到 |
 * | **浮点** `getCustomizeFunctionValue` | `BCM_FUNC_WINDOW_POS` | **按 zone 分派到四个逐窗信号** | **逐扇车窗可读** |
 * | 整型 `getFunctionValue` | `BCM_FUNC_DOOR` | 只支持 `DOOR_REAR` | 后备箱可读，引擎盖读不到 |
 *
 * 之前「车门车窗都读不到」的三个原因分别对应上表：
 *
 * 1. `BCM_FUNC_DOOR_POS` 本来就没实现 —— 探多少次都是 `255`；
 * 2. `BCM_FUNC_WINDOW_POS` 在**整型**通道没实现 —— 必须走浮点通道；
 * 3. `BCM_FUNC_WINDOW` / `WINDOW_ROW_*` 这两个常量**按名解析不到** ——
 *    因为它们住在 `com.ecarx.xui.adaptapi.car.vehicle.IBcm` 与
 *    `com.ecarx.xui.adaptapi.vehicle.VehicleWindow`，
 *    而我们的候选名单里只有 `car` 包下的猜测类名（漏了平级的 `vehicle` 包）。
 *
 * ## 现在的做法
 *
 * - **逐扇车窗**：浮点通道 + `VehicleWindow` 的窗区号（按名解析，兜底 `16/32/256/512`）；
 * - **四个侧门**：先问固件支持探针，说「不支持」就**不探**（省掉每秒 4 次无效调用），
 *   说「支持」就照读；判不了就退回老口径；
 * - **后备箱 / 引擎盖**：走 `BCM_FUNC_DOOR` + `DOOR_REAR` / `DOOR_HOOD` 区域，
 *   口径只认 `0`（关）/ `1`（开），厂商的 `IDLE` / `STOP`（映射到 `553844993`）**读不出来**；
 * - **尾门写入**：走 `BCM_FUNC_DOOR` + `DOOR_REAR` 区域 + `0` / `1`
 *   （厂商 `setCbSpeechTargetTrCtrl`，写侧映射只认这两个值），见 [writeTrunk]。
 *
 * ## 尾门能开能关，只是以前**只读不写**
 *
 * 厂商 jar 里 `BCM_FUNC_DOOR` 是**读、写都实现了**的一条功能号
 * （`case IBcm.BCM_FUNC_DOOR` 在 `getFunctionValue` 与 `setFunctionValue` 里各有一处），
 * 我们之前只用了读的那一半 —— 全项目没有任何写入尾门的调用。
 *
 * 写的时候有两处必须当心：
 *
 * - **区域号按名解析** `VehicleDoor.DOOR_REAR`：厂商 set 分支写死了
 *   `zone == 536870912`，别的区域直接 `returns false`；解析不到就**不写**。
 * - **值只有 `0` / `1`**：`getMappedCbSpeechTargetTrCtrlValue` 把其它值一律判为
 *   「不支持」，其中 `553779457` 是 `STOP`（**暂停**），不是「开」。
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
     * 车门开合功能号（jar 里的 `BCM_FUNC_DOOR`）。
     *
     * 固件的整型通道**只**为它实现了 `DOOR_REAR`（后备箱）一个区域，
     * 其它区域一律返回 `-1`。
     */
    const val BCM_DOOR = 553779456

    /**
     * 尾门写入值：**开**。
     *
     * 厂商 `CarFunctionValueUtil.getMappedCbSpeechTargetTrCtrlValue`：
     * `0` → `CbSpeechTargetTrCtrlValue.CLOSE`、`1` → `OPEN`，
     * **其余值一律 `null`**（写不进去）。
     */
    const val TRUNK_OPEN = 1

    /** 尾门写入值：**关**（厂商映射到 `CbSpeechTargetTrCtrlValue.CLOSE`）。 */
    const val TRUNK_CLOSE = 0

    /**
     * 车窗位置功能号（jar 里的 `BCM_FUNC_WINDOW_POS`）。
     *
     * 整型通道对它返回 `-1`；**逐扇开度只在浮点通道**
     * `getCustomizeFunctionValue(func, zone)` 里实现。
     */
    const val BCM_WINDOW_POS = 553845504

    /**
     * 四个门位对应的 zone（**君君那台车**的约定）。
     *
     * 现在只作为**兜底**：按名解析出来的 zone 优先（见 [AdaptapiConstants]）。
     */
    val DOOR_ZONES = intArrayOf(1, 4, 16, 64)

    /**
     * 四扇车窗对应的 zone 兜底值。
     *
     * 这四个数是车机端 jar 里 `VehicleWindow` 的确定值
     * （`WINDOW_ROW_1_LEFT=16` / `ROW_1_RIGHT=32` / `ROW_2_LEFT=256` / `ROW_2_RIGHT=512`），
     * 与厂商自己分派逐窗信号时用的 zone 完全一致。
     */
    val WINDOW_ZONES = intArrayOf(16, 32, 256, 512)

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
     * 车窗位置功能号（jar 里的 `BCM_FUNC_WINDOW_POS`）。
     *
     * 逐扇开度只在**浮点通道**实现，所以这个功能号必须配合
     * [VehicleGateway.readCustomizeFunctionValue] 用。
     */
    fun windowPosFunc(): Int =
        AdaptapiConstants.funcId(AdaptapiConstants.FUNC_WINDOW_POS, BCM_WINDOW_POS) ?: BCM_WINDOW_POS

    /**
     * 车门开合功能号（jar 里的 `BCM_FUNC_DOOR`）。
     *
     * 固件只实现了 `DOOR_REAR`（后备箱）一个区域 —— 引擎盖 / 侧门都读不到。
     */
    fun doorOpenFunc(): Int =
        AdaptapiConstants.funcId(AdaptapiConstants.FUNC_DOOR, BCM_DOOR) ?: BCM_DOOR

    /**
     * 某个门位要试的 zone 列表（**按优先级**）。
     *
     * - 按名解析出来的 zone 在前；
     * - 老约定 `{1, 4, 16, 64}` 在后（兜底）；
     * - 固件支持探针说「不支持」的直接剔除；
     * - 探针判不了（固件没这个方法）就照常都试。
     *
     * ## 第七十一轮：探针真的开始说话了
     *
     * 车机端 `isFunctionSupported` 返回的是 `FunctionStatus` 枚举，
     * 之前这里 `as? Boolean` 强转**永远拿到 null**，所以探针等于没有 ——
     * 四个门位每秒照探不误，每次都拿回 `255`。
     *
     * 现在 [AdaptapiGateway.isFunctionSupported] 按枚举名解析，
     * 这台车机上四个门位会判成「不支持」，
     * 于是这里返回**空列表** —— 一次都不探。
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
     * ## 第七十一轮：先问固件「支不支持」，再决定要不要探
     *
     * 车机端 jar（`ecarx.adapterapi.jar`）里 `CarFunctionValueImpl.getFunctionValue`
     * 的功能号分派表里**根本没有 `BCM_FUNC_DOOR_POS`** ——
     * 落进默认分支时厂商返回 `255`（= `-1`）并打一句
     * `getFunctionValue(...) This function is not implemented.`。
     *
     * 也就是说这台车机上**四个侧门 + 引擎盖没有信号**，
     * 每秒去探四次只会得到四个 `255`（诊断包里这两轮就是 3536 条
     * 「车门功能号/区域不被固件支持」）。
     *
     * 所以现在改成：
     * 1. 先问固件支持探针（`isFunctionSupported`）**这一档 / 这个区域**支不支持；
     * 2. 探针说「不支持」→ **不探**，这一扇直接判「读不到」；
     * 3. 探针说「支持」→ 照常读；
     * 4. 探针判不了（固件没有这个方法）→ 退回老口径（读了再说）。
     *
     * 引擎盖 / 后备箱走的是**另一个功能号** `BCM_FUNC_DOOR`，
     * 固件只为它实现了 `DOOR_REAR`（后备箱）一个区域。
     *
     * @return 完全读不到时 `eachClosed` 与 `allClosed` 都是 null
     */
    fun readDoors(gw: VehicleGateway): DoorState {
        val func = doorFunc()
        // —— 逐门位（每一扇独立：读不到的那一扇留 null，不作废整组）——
        // 剪枝在 [doorZoneProbes] 里做：固件支持探针说「不支持」的区域不会被读。
        // 探针修好之后（第七十一轮），这台车机上四个门位全部判为「不支持」，
        // 于是这里**一次都不会去探** —— 诊断包里那 3536 条无效日志就是这么来的。
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

        // —— 引擎盖 / 后备箱：走 `BCM_FUNC_DOOR`（固件只实现了 DOOR_REAR）——
        val latchFunc = doorOpenFunc()
        val hood = readAuxZone(gw, latchFunc, AdaptapiConstants.hoodZone(), "引擎盖")
        val trunk = readAuxZone(gw, latchFunc, AdaptapiConstants.trunkZone(), "后备箱")

        // —— 整体值（探测顺序：无 zone → zone 0 → 逐门位聚合）——
        // 探针明确说「不支持」的区域就不去读（省掉每秒两次无效调用）。
        if (supported(gw, func, ZONE_NONE) != false) {
            val rawNone = gw.readRawFunctionValue(func, ZONE_NONE)
            if (rawNone != null) {
                val closed = CarSignalMath.interpretClosed(rawNone)
                if (closed != null) {
                    logDoorProbe("整体(无zone)", ZONE_NONE, rawNone, closed)
                    return DoorState(perZone, closed, hood = hood, trunk = trunk)
                }
            }
        }
        if (supported(gw, func, ZONE_AGGREGATE) != false) {
            val rawAgg = gw.readRawFunctionValue(func, ZONE_AGGREGATE)
            if (rawAgg != null) {
                val closed = CarSignalMath.interpretClosed(rawAgg)
                if (closed != null) {
                    logDoorProbe("整体(zone0)", ZONE_AGGREGATE, rawAgg, closed)
                    return DoorState(perZone, closed, hood = hood, trunk = trunk)
                }
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
            // —— 这条日志**必须节流**（第七十八轮）——
            //
            // 之前它每次轮询都打一条 WARN，而轮询是**每秒约十次**的节奏 ——
            // 真机诊断包里「车门状态读不到」刷了 730 条，
            // 把整份日志里其它真正有用的信号全淹了。
            //
            // 而且这条路径**注定读不到**：
            // 厂商的功能号分派表（`CarFunctionValueImpl.getFunctionValue`）
            // 里根本没有 `BCM_FUNC_DOOR_POS` 这个 case，
            // 它落进 `default:` 分支 → 恒返回 `255`（不支持）。
            // （已核对 jar 反编译源码：该常量在 `CarFunctionValueImpl` 里出现 0 次。）
            //
            // 所以这里降级成「每分钟一条」，并把「固件没实现」这件事说清楚，
            // 别让排障的人以为还有希望。
            val now = System.currentTimeMillis()
            if (now - lastDoorFailLogAt >= DOOR_LOG_THROTTLE_MS) {
                lastDoorFailLogAt = now
                Logx.w(
                    LogTags.SIGNAL,
                    "车门状态读不到（BCM_DOOR_POS 固件未实现，改走 MC 属性通道）",
                    "func" to func,
                    "hint" to "厂商分派表无此 case，恒返回 255",
                )
            }
            return DoorState(null, null, hood = hood, trunk = trunk)
        }
        return DoorState(perZone, allZoneClosed, hood = hood, trunk = trunk)
    }

    /** 「车门读不到」日志的节流间隔（毫秒）。 */
    private const val DOOR_LOG_THROTTLE_MS = 60_000L

    /** 上次打「车门读不到」日志的时间。 */
    private var lastDoorFailLogAt = 0L

    /**
     * 读一个「非门位」的区域（引擎盖 / 后备箱）。
     *
     * 区域号按名解析（`DOOR_HOOD` / `DOOR_REAR`），解析不到就返回 null ——
     * 不拿别的区域号顶替。
     *
     * 口径用 [CarSignalMath.interpretLatchClosed]：`0` = 关、`1` = 开，
     * 其余（厂商把 `IDLE` / `STOP` 也映射到 `553844993`）一律**读不出来** ——
     * 「静止不动」说成「关着」是编的。
     */
    private fun readAuxZone(gw: VehicleGateway, func: Int, zone: Int?, label: String): Boolean? {
        if (zone == null) return null
        val raw = gw.readRawFunctionValue(func, zone) ?: return null
        if (CarSignalMath.isUnsupportedRaw(raw)) {
            Logx.d(LogTags.SIGNAL, "车身区域不被固件支持", "label" to label, "zone" to zone, "raw" to raw)
            return null
        }
        val closed = CarSignalMath.interpretLatchClosed(raw) ?: return null
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

    // ------------------------------------------------------------ 尾门写入

    /**
     * 写尾门（开 / 关）。
     *
     * ## 厂商侧对照
     *
     * | 东西 | 值 | 出处 |
     * | --- | --- | --- |
     * | 功能号 | `BCM_FUNC_DOOR`（`0x21020100`） | `com.ecarx.xui.adaptapi.car.vehicle.IBcm` |
     * | 区域 | `VehicleDoor.DOOR_REAR` | `com.ecarx.xui.adaptapi.vehicle.VehicleDoor` |
     * | 读信号 | `getPaSpeechTrCtrl` | `CarFunctionValueImpl` 的 `case IBcm.BCM_FUNC_DOOR` |
     * | 写信号 | `setCbSpeechTargetTrCtrl` | 同上（`setFunctionValue` 分支） |
     *
     * 厂商 jar 里这**一条**功能号是读、写**都实现了**的
     * （`case IBcm.BCM_FUNC_DOOR` 在 `getFunctionValue` 与 `setFunctionValue`
     * 两个 switch 里各有一处），
     * 且 set 分支**写死了** `zone == 536870912`
     * —— 与 `VehicleDoor.DOOR_REAR` 的值一致，
     * 所以「区域号 = `DOOR_REAR`」这条推断在源码里是**闭合**的
     * （其它门区写了会被固件直接拒绝：`returns false`）。
     *
     * ## 值：只有 `0` / `1`
     *
     * `getMappedCbSpeechTargetTrCtrlValue`：`0` → `CLOSE`、`1` → `OPEN`，
     * **其余一律 `null`**（写不进去）。
     *
     * ## `553779457` 是「暂停」，**不是「开」**
     *
     * 同一个映射函数把 `553779457`（`IBcm.DOOR_PAUSE`）单独映射成 `STOP`。
     * 把它当「开」写下去，厂商会当成「**停下**」。
     * 另外读侧把 `IDLE` / `STOP` 翻译成的是 `553844993`（`IBcm.WINDOW_PAUSE`），
     * 与写侧的 `553779457` **不是同一个数**（厂商两处用了不同常量）——
     * 所以**不要**把读到的值原样回写。
     *
     * ## 这个功能号是「动作状态」，不是「开闭布尔」
     *
     * `setCbSpeechTargetTrCtrl` 的语义是「**让尾门去做这个动作**」，
     * 不是「尾门现在处于这个状态」。
     * 写成功只说明**指令下发成功**，**不代表尾门已经开到目标位置** ——
     * 到位与否要看之后读回来的 `PaSpeechTrCtrl`。
     *
     * @param open true = 开，false = 关
     * @return false = 区域号按名解析不到（**不写**）或写入失败
     */
    fun writeTrunk(gw: VehicleGateway, open: Boolean): Boolean {
        // 区域号**按名解析** `VehicleDoor.DOOR_REAR`；
        // 解析不到就**不写** —— 不猜 536870912。
        val zone = AdaptapiConstants.trunkZone()
        if (zone == null) {
            Logx.w(LogTags.SIGNAL, "尾门区域号按名解析不到 不写", "name" to "DOOR_REAR")
            return false
        }
        val func = doorOpenFunc()
        val value = if (open) TRUNK_OPEN else TRUNK_CLOSE
        val ok = gw.setHvac(func, zone, value)
        if (ok) {
            Logx.i(
                LogTags.SIGNAL,
                "尾门动作已下发",
                "open" to open,
                "func" to func,
                "zone" to zone,
                "value" to value,
                "note" to "指令已下发，不代表已到位",
            )
        } else {
            Logx.w(LogTags.SIGNAL, "尾门动作下发失败", "open" to open, "func" to func, "zone" to zone, "value" to value)
        }
        return ok
    }

    // ------------------------------------------------------------ 车窗

    /**
     * 四扇车窗的开闭（**true = 开**），顺序 FL/FR/RL/RR；读不到返回 null。
     *
     * ## 第七十一轮：改走**浮点通道**，逐扇才有值
     *
     * 车机端 jar 里逐扇车窗只有一个信号：
     * `PaSpeechWinCtrlFrntLe` / `FrntRi` / `ReLe` / `ReRi`，
     * 厂商在 `CarFunctionCustomizedValueImpl.getFunctionValue(func, zone)` 里
     * **按 zone 分派**到这四个信号：
     *
     * ```
     * zone 16  (WINDOW_ROW_1_LEFT ) → PaSpeechWinCtrlFrntLe
     * zone 32  (WINDOW_ROW_1_RIGHT) → PaSpeechWinCtrlFrntRi
     * zone 256 (WINDOW_ROW_2_LEFT ) → PaSpeechWinCtrlReLe
     * zone 512 (WINDOW_ROW_2_RIGHT) → PaSpeechWinCtrlReRi
     * ```
     *
     * 而这条分派挂在 **`BCM_FUNC_WINDOW_POS`** 上，且只在**浮点通道**
     * `getCustomizeFunctionValue(func, zone)` 里实现 ——
     * 整型通道对它直接返回 `-1`
     * （jar 里的原话：`is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`）。
     *
     * 所以之前「车窗功能号按名解析不到」+「整型通道」这两处一起导致车窗永远是「读不到」。
     *
     * 口径见 [CarSignalMath.interpretWindowPosition]：
     * `0` = 关、`> 0` = 开、`< 0` = 读不出来。
     *
     * ## 第七十四轮：`0` 有歧义，要靠固件支持与否来分
     *
     * 厂商的**浮点**读失败时返回 **`0.0f` 而不是 `-1`**
     * （`status == ABNORMAL` / `data` 越界 / 信号为 null → 一律翻译成 `0.0f`）。
     * 而 `0.0f` 恰好又是「窗关着」的开度 ——
     * 不加区分的话，**读不到会伪装成「窗全关」**。
     *
     * 所以这里把 `isFunctionSupported(func, zone)` 的结论一起传下去：
     * 固件明确说「不支持这个区域」时，`0` 也按「读不到」处理。
     */
    fun readWindowsEach(gw: VehicleGateway): List<Boolean?>? {
        val func = windowPosFunc()
        val zones = AdaptapiConstants.windowZones()
        val zoneOf = { i: Int -> zones?.getOrNull(i) ?: WINDOW_ZONES[i] }
        val out = MutableList<Boolean?>(WINDOW_ZONES.size) { null }
        var any = false
        for (i in WINDOW_ZONES.indices) {
            val zone = zoneOf(i)
            val raw = gw.readCustomizeFunctionValue(func, zone)
            if (raw == null) {
                Logx.d(
                    LogTags.SIGNAL,
                    "车窗读不到（浮点通道无返回）",
                    "zone" to zone,
                    "func" to func,
                )
                continue
            }
            val supported = supported(gw, func, zone)
            val open = CarSignalMath.interpretWindowPosition(raw, supported)
            if (open == null) {
                Logx.d(
                    LogTags.SIGNAL,
                    "车窗开度值解释不出来",
                    "zone" to zone,
                    "raw" to raw,
                    "supported" to supported,
                )
                continue
            }
            out[i] = open
            any = true
        }
        if (!any) {
            Logx.d(
                LogTags.SIGNAL,
                "车窗状态读不到（BCM_FUNC_WINDOW_POS 浮点通道）",
                "func" to func,
                "zones" to WINDOW_ZONES.joinToString(","),
            )
            return null
        }
        Logx.d(
            LogTags.SIGNAL,
            "车窗状态（BCM_FUNC_WINDOW_POS）",
            "open" to out.joinToString(",") { c -> c?.let { if (it) "开" else "关" } ?: "?" },
        )
        return out
    }
}
