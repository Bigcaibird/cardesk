package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车身状态（车门 / 引擎盖 / 后备箱）的**统一口径**。
 *
 * ##规格
 *
 * 用户给的图
 *
 * `
 * publishBodyState
 *   ├─ 源1 ecarx.car.hardware.signal.CarSignalManager 的反射 getter
 *   │ getDoorDrvSts / getDoorPassSts / getDoorDrvReSts / getDoorPassReSts
 *   │ getHoodSts / getTrSts
 *   │ 归一化：0 = 关、1 = 开（interpretDoorSignal）
 *   │ 关联 signal id：29207 / 29211 / 29206 / 29210 / 29222 / 29268
 *   ├─ 源2 VehicleAPI → getBcm() → getDoorAndWindowState() → IDoorAndWindow（第六十六轮新增）
 *   │ isDoorOpened(int)：1..4 = 前左 / 前右 / 后左 / 后右
 *   ├─ 源3 adaptapi 的 BCM_DOOR_POS
 *   │ funId = 553779968；zone **按名解析**（DOOR_ROW_1/2_LEFT/RIGHT），兜底 {1, 4, 16, 64}
 *   │ getFunctionValue + interpretClosed（含百分比阈值）
 *   ├─ 源4 官方的 CarPropertyManager
 *   │ propertyId = 373295872（DOOR_POS）
 *   └─ 源5 MC / Geely 事件属性（第四十五轮新增，另一个 App 的做法）
 *            560993300 / 301 / 302 / 303 / 304
 *                ↓
 *           allClosed: bool
 *                ↓
 *           onDoorState(valid, allClosed)
 * `
 *
 * ## 降级链顺序
 *
 * 与**逐档一致**：**源1 → 源2 → 源3 → 源4 → 源5**，第一个拿到非空值的就说了算。
 *
 * （第四十三轮之前我们的顺序是反的：adaptapi 优先、car_signal 兜底。
 * 把 car_signal 放在第一档，因为它给的是车端**最直接**的开闭语义 —— `0`/`1`
 * 没有百分比、没有区域翻译。
 *
 * 第六十六轮把 `IDoorAndWindow` 插在第二档：它给的是**车机原生的布尔**，
 * 语义最干净；而当时的 `BCM_DOOR_POS` 在这台车上四个 zone 全部返回 `-1`
 * （jar 里的提示串：`is not supported for getFunctionValue ..., returns -1`）。
 *
 * ## 逐门独立（第四十五轮）
 *
 * 第四十四轮只把「全关」的聚合做成了逐项可读，**明细**还是「四门整体来自某一个来源」。
 *
 * 另一个的做法是**每一扇门独立维护**
 * 五个属性各自更新，谁变了就整包重新发布。
 * 这样一台车只注册了三个门位区域时，第四扇门是「读不到」，
 * 而不是把**整组**作废、连另外三扇门的正确状态一起丢掉。
 *
 * 现在我们也是**逐门**合并：
 * 每一扇门按「源1 → 源2 → 源3 → 源4」的顺序，取**第一个能解释这一扇**的来源。
 *
 * ## 整体「全关」的口径
 *
 * `onDoorState(valid, allClosed)` 是**整体粒度**的。
 * 我们在它之上加了一条更严的规则：
 * **只要有一扇门明确是「开」，整体就不是全关** ——
 * 这条规则本来就在**单档内部**（逐项遍历 6 项）
 * 我们把它从「档内」推广到「跨档」，避免出现「左前门显示开、整体却显示全关」的矛盾。
 *
 * ## 与一处刻意差异
 *
 * `onDoorState(valid, allClosed)` 在**读不到**时给的是
 * `valid = false`、`allClosed = true`（默认值）。
 * 我们把「读不到」整体表示成 `null`（[Result.valid] = false 且整体值为 null），
 * 桌面上显示「--」而不是猜一个「关」。
 * 「读不到」与「真的关着」是两回事 —— 这条口径与 `VehicleStats.interpretDoorClosed` 一致。
 */
object BodyState {

    /** 车身项的总数：4 门 + 引擎盖 + 后备箱。 */
    const val ITEM_COUNT = 6

    /** 四门的数量（[ITEM_COUNT] 项里的前 4 项）。 */
    const val DOOR_COUNT = 4

    /** 引擎盖在 [ITEM_COUNT] 项里的下标。 */
    const val HOOD_INDEX = 4

    /** 后备箱在 [ITEM_COUNT] 项里的下标。 */
    const val TRUNK_INDEX = 5

    /** 源1 的名字（诊断用）。 */
    const val SOURCE_CAR_SIGNAL = "car_signal"

    /**
     * 源2 的名字（诊断用）：车机原生的 `IDoorAndWindow`（第六十六轮新增）。
     *
     * `VehicleAPI` → `getBcm()` → `getDoorAndWindowState()` → `IDoorAndWindow`，
     * 给的是**四门开闭 / 上锁**与**四窗开闭**（布尔）。
     */
    const val SOURCE_OEM = "IDoorAndWindow"

    /** 源3 的名字（诊断用）。 */
    const val SOURCE_ADAPTAPI = "BCM_DOOR_POS"

    /** 源4 的名字（诊断用）。 */
    const val SOURCE_PROPERTY = "DOOR_POS"

    /** 源5 的名字（诊断用）。 */
    const val SOURCE_MC = "MC_DOOR"

    /**
     * 归一化
     * `0` = 关、`1` = 开、其它 = 读不出来。
     *
     * @return true = 关；false = 开；null = 这个原始值不是有效的开闭状态
     */
    fun interpretDoorSignal(raw: Int): Boolean? = when (raw) {
        0 -> true
        1 -> false
        else -> null
    }

    /**
     * 聚合
     *
     * - 只要有**一项**能解释就算「有效」；
     * - 其中任何一项是「开」→ 整体就不是全关。
     *
     * @return null = 一项都读不出来
     */
    fun aggregateAllClosed(values: List<Boolean?>): Boolean? {
        var valid = false
        var allClosed = true
        for (v in values) {
            if (v == null) continue
            valid = true
            if (!v) allClosed = false
        }
        return if (valid) allClosed else null
    }

    /**
     * **逐项**合并多个来源的同一个量。
     *
     * 每一项按来源顺序取**第一个非 null**。
     * 全部来源都读不到这一项时留 null（「读不到」不猜）。
     *
     * @param sources 按优先级排列的来源
     * @return 长度 = 最长来源的长度；所有来源都是 null 时返回 null
     */
    fun mergeItems(vararg sources: List<Boolean?>?): List<Boolean?>? {
        var len = 0
        for (s in sources) if (s != null && s.size > len) len = s.size
        if (len == 0) return null
        val out = ArrayList<Boolean?>(len)
        for (i in 0 until len) {
            var v: Boolean? = null
            for (s in sources) {
                val item = s?.getOrNull(i) ?: continue
                v = item
                break
            }
            out.add(v)
        }
        // 一项都没解释出来 → 整组读不到
        return if (out.any { it != null }) out else null
    }

    /**
     * 一次车身状态读取的结果。
     *
     * @param valid第一个参数：这一组数据**是不是有效**的
     * @param allClosed第二个参数：是否**全部关闭**
     * @param doors 四门（true = 关）；每项 null = 这一扇读不到；整组 null = 全读不到
     * @param hood 引擎盖（true = 关）；null = 读不到
     * @param trunk 后备箱（true = 关）；null = 读不到
     * @param source 最终生效的来源（诊断用）
     */
    data class Result(
        val valid: Boolean,
        val allClosed: Boolean,
        val doors: List<Boolean?>?,
        val hood: Boolean?,
        val trunk: Boolean?,
        val source: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            if (valid != other.valid) return false
            if (allClosed != other.allClosed) return false
            if (hood != other.hood) return false
            if (trunk != other.trunk) return false
            if (source != other.source) return false
            return doors == other.doors
        }

        override fun hashCode(): Int {
            var h = valid.hashCode()
            h = h * 31 + allClosed.hashCode()
            h = h * 31 + (doors?.hashCode() ?: 0)
            h = h * 31 + (hood?.hashCode() ?: 0)
            h = h * 31 + (trunk?.hashCode() ?: 0)
            h = h * 31 + source.hashCode()
            return h
        }
    }

    /**
     * 按来源顺序编排一次读取。
     *
     * @param gw AdaptAPI 的车辆网关（源3 的读取走它）
     * @param signalsReady `car_signal` 通道是否可用（源1）
     * @param context 车机 Context；给了就顺带把 `IDoorAndWindow` 通道挂上（源2）
     */
    fun resolve(gw: VehicleGateway, signalsReady: Boolean, context: Context? = null): Result {
        // —— 源1：car_signal 的 6 项（第一档）——
        val items = if (signalsReady) EcarxSignals.bodyItems() else emptyList()
        val allClosedFromSignal = aggregateAllClosed(items)

        // —— 源2：IDoorAndWindow（车机原生 BCM 布尔，第六十六轮新增）——
        if (context != null) OemDoorWindowSignals.attach(context)
        val oem = OemDoorWindowSignals.doorsClosed()
        val allClosedFromOem = aggregateAllClosed(oem ?: emptyList())

        // —— 源3：BCM_DOOR_POS（AdaptAPI 属性通道）——
        val adapt = AdaptapiBody.readDoors(gw)

        // —— 源4：DOOR_POS 属性（propertyId 373295872）——
        val prop = CarBodySignals.readDoorsViaProperty()
        val hoodTrunk = CarBodySignals.readHoodTrunkViaProperty()

        // —— 源5：MC / Geely 事件属性（第四十五轮新增）——
        val mc = McDoorSignals.read()

        // —— 明细：逐门跨来源合并（源1 → 源2 → 源3 → 源4 → 源5）——
        val doors = mergeItems(
            doorItemsFromSignal(items),
            oem,
            adapt.eachClosed,
            prop,
            mc?.doors,
        )

        // 引擎盖：car_signal → Adapt 的 DOOR_HOOD 区域 → DOOR_POS 的 HOOD 区域
        // （MC 那一组没有引擎盖；IDoorAndWindow 也没有）
        val hood = mergeItems(
            listOfNotNull(items.getOrNull(HOOD_INDEX)),
            listOfNotNull(adapt.hood),
            listOfNotNull(hoodTrunk?.first),
        )?.getOrNull(0)
        // 后备箱：car_signal → Adapt 的 DOOR_REAR 区域 → DOOR_POS 的 TRUNK 区域 → MC 的 560993304
        val trunk = mergeItems(
            listOfNotNull(items.getOrNull(TRUNK_INDEX)),
            listOfNotNull(adapt.trunk),
            listOfNotNull(hoodTrunk?.second),
            listOfNotNull(mc?.trunk),
        )?.getOrNull(0)

        // 整体「全关」：按顺序取第一个非空
        val chained = allClosedFromSignal
            ?: allClosedFromOem
            ?: adapt.allClosed
            ?: aggregateProperty(prop, hoodTrunk?.first, hoodTrunk?.second)
            ?: aggregateAllClosed(listOfNotNull(mc?.trunk))

        // 明细里只要有一扇明确是「开」，整体就不能算全关
        // （机盖 / 后备箱同样算 —— 第六十六轮起把这条规则从「四门」推广到「全部开口」）
        val anyOpen = doors?.any { it == false } == true ||
            hood == false || trunk == false
        val allClosed = when {
            anyOpen -> false
            chained != null -> chained
            else -> null
        }

        val source = when {
            allClosedFromSignal != null -> SOURCE_CAR_SIGNAL
            allClosedFromOem != null -> SOURCE_OEM
            adapt.allClosed != null -> SOURCE_ADAPTAPI
            chained != null -> SOURCE_PROPERTY
            mc?.readable == true -> SOURCE_MC
            else -> "无"
        }

        Logx.d(
            LogTags.SIGNAL,
            "车身状态",
            "source" to source,
            "valid" to (allClosed != null),
            "allClosed" to allClosed,
            "items" to items.joinToString(",") { c -> c?.let { if (it) "关" else "开" } ?: "?" },
            "doors" to (doors?.joinToString(",") { c -> c?.let { if (it) "关" else "开" } ?: "?" } ?: "不可读"),
        )

        return Result(
            valid = allClosed != null,
            allClosed = allClosed == true,
            doors = doors,
            hood = hood,
            trunk = trunk,
            source = source,
        )
    }

    /**
     * 源3 的整体聚合。
     *
     * 是**遍历属性的全部可用区域**再聚合
     * 所以引擎盖 / 后备箱（如果这台车注册了这两个区域）也参与聚合。
     * 这里照做：四门全读到了才谈聚合，盖 / 箱读不到就**跳过**（与
     * 「解释不出来的值不参与」一致。
     */
    private fun aggregateProperty(
        doors: List<Boolean?>?,
        hood: Boolean?,
        trunk: Boolean?,
    ): Boolean? {
        if (doors == null) return null
        val all = ArrayList<Boolean?>(doors.size + 2)
        all.addAll(doors)
        all.add(hood)
        all.add(trunk)
        return aggregateAllClosed(all)
    }

    /**
     * 源1 的四门明细：**逐扇**取（第四十五轮起不再要求四扇全读到）。
     */
    private fun doorItemsFromSignal(items: List<Boolean?>): List<Boolean?>? {
        if (items.size < DOOR_COUNT) return null
        return items.subList(0, DOOR_COUNT)
    }
}
