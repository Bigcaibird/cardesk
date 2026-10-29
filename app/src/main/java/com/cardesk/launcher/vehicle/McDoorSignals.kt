package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.ProtoLite
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车门状态的**第四档来源**：MC / Geely 事件属性（用的那一套）。
 *
 * ## 两套并行模型
 *
 * 车门状态有**两套并行模型**：
 *
 * - 主通道，字段顺序是 `doorBack / doorFl / doorFr / doorBl / doorBr`；
 * - 车门弹窗用的那一套，字段相同。
 *
 * 它们的**唯一构造点**是
 * （type 101 / CEVT 路线专用）。它读的是 `CarPropertyManager` 上的**五个独立属性**：
 *
 * | propertyId | 字段 | 语义 |
 * | --- | --- | --- |
 * | `560993300` | | 左前门 |
 * | `560993301` | | 左后门 |
 * | `560993302` | | 右前门 |
 * | `560993303` | | 右后门 |
 * | `560993304` | | 后备箱 |
 *
 * 属性值是 **protobuf 字节**，
 * 用 `C3280i.m14114b / m14115c` 解成数值，再按「**等于 1 就是开**」解释。
 *
 * ## 我们为什么要接这一档
 *
 * 车门模型有一个我们缺的特性：**每一扇门独立可读、独立更新**。
 * 任何一个属性变化就整包重新发布。
 * 我们之前是「四门全读到才算数」，一台车只注册了三个门位区域就整组作废。
 * 第四十五轮按把「逐门独立」的口径补上（见 [BodyState]）
 * 这里是**多出来的一条来源**：
 *
 * - 属性 id 与完全一致，**万一这台车确实注册了 MC 事件属性，就能多读到一档**
 * - 读不到就**安静跳过** —— 绝不因为「属性不存在」而影响已有的三档。
 *
 * ## 平台限制（必须知道）
 *
 * 原始线索是 **CEVT 平台**的类（`vendor.cevt.hardware.automotive.vehicle.geely.V2_0`），
 * 所以早先的判断是「我们是 ECARX 平台，**大概率没有这些属性**」。
 *
 * ## 第七十二轮：这条路其实该通
 *
 * 车机端 `ecarx.adapterapi.jar` 里 `GeelyVehicleProperty` 明确定义了这五个属性：
 *
 * | 属性 | id | 车辆页上的位置 |
 * | --- | --- | --- |
 * | `PA_CP_DoorDrvrSts` | `560993300` (`0x21701414`) | 主驾 |
 * | `PA_CP_DoorDrvrReSts` | `560993301` (`0x21701415`) | 左后 |
 * | `PA_CP_DoorPassSts` | `560993302` (`0x21701416`) | 副驾 |
 * | `PA_CP_DoorPassReSts` | `560993303` (`0x21701417`) | 右后 |
 * | `PA_CP_TrSts` | `560993304` (`0x21701418`) | 尾门 |
 *
 * 之前读不到不是因为「这台车没有」，而是**属性通道压根没建起来** ——
 * `Car.createCar` 的重载调错了（见 [CarProperties]）。
 *
 * 解析不依赖 CEVT 的类（那个类我们进程里根本没有），
 * 只按 protobuf 的**公开线格式**扫出唯一一个 varint 字段（见 [ProtoLite]）。
 *
 * ## 第七十四轮：按**车机自报的类型**读，不再靠猜
 *
 * 上一轮这里写的是「ECARX 上这五个是数值属性」——
 * **那个推断是错的**，推理链断在「缺重载 ⇒ 换类型」：
 *
 * 1. ECARX 那份 `CarPropertyManager` 没有 `getProperty(int[], Class)`（AOSP 的形状）；
 * 2. 于是第七十二轮判「这些属性在 ECARX 上必然是数值属性」；
 * 3. 但**缺一个重载只能说明厂商不这么读**，推不出属性类型 ——
 *    类型是写在**属性 id 位域**里的（`type << 16`），跟 SDK 怎么读无关。
 *
 * 事实是：这两个推断导致**两条路同时是死路** ——
 * 字节路匹配不到重载（恒 null），数值路对 `byte[]` 恒 null。
 * 于是「四门+尾门读不到」一直找不到真凶。
 *
 * 现在改成**让车机自己说**：
 *
 * 1. `CarPropertyConfig.getPropertyType()` → 属性类型名（`byte[]` / `Integer` / …）；
 * 2. 按类型走 `getProperty(Class, propId, area)`；
 * 3. 问不出类型时，两种都试（字节优先）。
 *
 * 属性 id 的类型位指向 `VehiclePropertyType.BYTES`（`0x70 << 16`），
 * 也就是说**大概率是字节**；但这是按位域推断，
 * 真正说了算的是车机登记的类型 —— 诊断日志里会带出来（见 [CarProperties.propertyConfigs]）。
 */
object McDoorSignals {

    /** 左前门（`560993300`）。 */
    const val PROP_DOOR_FL = PaCpDoorProps.DOOR_DRIVER

    /** 左后门（`560993301`）。 */
    const val PROP_DOOR_BL = PaCpDoorProps.DOOR_DRIVER_REAR

    /** 右前门（`560993302`）。 */
    const val PROP_DOOR_FR = PaCpDoorProps.DOOR_PASSENGER

    /** 右后门（`560993303`）。 */
    const val PROP_DOOR_BR = PaCpDoorProps.DOOR_PASSENGER_REAR

    /** 后备箱（`560993304`）。 */
    const val PROP_TRUNK = PaCpDoorProps.TAILGATE

    /**
     * 四个门对应的属性 id，顺序同我们全局的 **FL / FR / RL / RR**。
     *
     * 注意 id 顺序是 FL / BL / FR / BR，这里按我们的顺序重排。
     */
    val DOOR_PROPS = PaCpDoorProps.DOORS_IN_FL_FR_RL_RR

    /** 门属性名（只用于日志）。 */
    private val DOOR_NAMES = arrayOf("左前门", "右前门", "左后门", "右后门")

    /** 缓存时长（毫秒）。与其它属性通道同一档节奏。 */
    const val CACHE_MS = 900L

    /**
     * 「这一档不存在」的**负缓存**时长（毫秒）。
     *
     * 这组属性在车机的 `car_service` 清单里是**只读**的，真机上确实存在。
     * 但如果通道没起来，每秒去问 5 次「这个属性在不在」是 5 次 binder 调用，纯浪费。
     * 所以一旦确认读不到，就安静待 [NEGATIVE_CACHE_MS] 再问一次。
     */
    const val NEGATIVE_CACHE_MS = 60_000L

    /**
     * **通道还没起来**时的负缓存时长（毫秒）。
     *
     * 通道是异步连上的（`Car.connect()` → `onServiceConnected`），
     * 刚启动的几秒里通道多半还没就绪。这里用短一点的冷却，
     * 免得通道刚连上还要再等一分钟才生效。
     */
    const val NEGATIVE_CACHE_WHEN_CHANNEL_DOWN_MS = 5_000L

    private var cached: McDoorState? = null
    private var cachedAt = 0L

    /** 确认「读不到」的时刻；在这之前不再试。 */
    private var unavailableUntil = 0L

    /**
     * 一次读取的结果。
     *
     * @param doors 四门（true = 关）；长度 4，每项 null = 这一扇读不到
     * @param trunk 后备箱（true = 关）；null = 读不到
     * @param readable 整组里**至少有一项**读到了吗
     */
    data class McDoorState(
        val doors: List<Boolean?>,
        val trunk: Boolean?,
    ) {
        /** 至少有一项读到了。 */
        val readable: Boolean get() = trunk != null || doors.any { it != null }
    }

    /** 清缓存。 */
    fun invalidate() {
        cached = null
        cachedAt = 0L
        unavailableUntil = 0L
    }

    /**
     * 读一次 MC 事件属性。
     *
     * @return 完全读不到时返回 null（这一档就当不存在）
     */
    fun read(): McDoorState? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_MS) return it }
        // 负缓存：这一档不存在时不要每秒问 5 次
        if (now < unavailableUntil) return null

        if (!CarProperties.isAvailable()) {
            unavailableUntil = now + NEGATIVE_CACHE_WHEN_CHANNEL_DOWN_MS
            return null
        }

        val doors = ArrayList<Boolean?>(4)
        var any = false
        for ((i, prop) in DOOR_PROPS.withIndex()) {
            val raw = readValue(prop)
            val closed = interpret(raw)
            if (closed != null) any = true
            doors.add(closed)
            if (raw != null) {
                Logx.d(
                    LogTags.SIGNAL,
                    "MC 车门属性",
                    "label" to DOOR_NAMES[i],
                    "prop" to prop,
                    "raw" to raw,
                    "closed" to closed,
                )
            }
        }
        val trunkRaw = readValue(PROP_TRUNK)
        val trunk = interpret(trunkRaw)
        if (trunk != null) any = true

        if (!any) {
            Logx.d(LogTags.SIGNAL, "MC 车门属性读不到（属性通道上没有这组属性）")
            // 负缓存：别每秒再问一遍
            unavailableUntil = now + NEGATIVE_CACHE_MS
            return null
        }
        val state = McDoorState(doors, trunk)
        cached = state
        cachedAt = now
        Logx.i(
            LogTags.SIGNAL,
            "MC 车门属性可用",
            "doors" to doors.joinToString(",") { c -> c?.let { if (it) "关" else "开" } ?: "?" },
            "trunk" to trunk,
        )
        return state
    }

    /**
     * 读一个属性的原始数值。
     *
     * ## 第七十四轮：先问类型，再按类型读
     *
     * 三段式：
     *
     * 1. **类型问出来了** → 按类型走：
     *    - `byte[]` → `getProperty(ByteArray.class, id, area)`，再解 varint；
     *    - 数值型 → `getProperty(Integer.class, id, area)`，退 `getProperty(int, area)`；
     * 2. **类型没问出来**（属性清单读不到）→ 两种都试，**字节优先**
     *    （属性 id 的类型位指向 BYTES，字节的可能性更大）；
     * 3. 都不行 → 最后一搏 CEvt 平台形态（`getProperty(int[], Class)`，
     *    这台车机上通常不存在，见 [CarProperties.readBytes]）。
     *
     * 区域号：整车级属性传 `0`；属性清单里有区域就按区域逐个试。
     *
     * @return 读不到返回 null
     */
    private fun readValue(propId: Int): Long? {
        val areas = CarProperties.availableAreas(propId)
        val zones = if (areas != null && areas.isNotEmpty()) areas.toList() else listOf(0)

        // —— ① 车机登记了类型：按类型走 ——
        // 分派口径抽在 [CarSignalMath.propReadKind]（纯函数，可单测）。
        when (CarSignalMath.propReadKind(CarProperties.propertyTypeOf(propId))) {
            CarSignalMath.PropReadKind.BYTES ->
                for (z in zones) {
                    decodeVarint(CarProperties.readBytesViaClass(propId, z))?.let { return it }
                }
            CarSignalMath.PropReadKind.NUMERIC ->
                for (z in zones) {
                    readNumericZone(propId, z)?.let { return it }
                }
            CarSignalMath.PropReadKind.UNKNOWN -> Unit
        }

        // —— ② **多类型试读**（第七十八轮新增）——
        //
        // 上面那一档是「按属性 id 的类型位推断」来的（BYTES → 只要 byte[]），
        // 但那只是推断：真机上这组 `PA_CP_Door*Sts` 到底返回什么类型，
        // 之前谁也不知道 —— 因为读失败时**一条日志都没有**。
        //
        // 现在把常见类型挨个试一遍（byte[] / Integer / Long / Float / String /
        // int[] / Boolean），谁对拿谁；拿到的原始值也会被记进日志，
        // 下一次诊断包就能直接看出「这组属性到底是什么形状」。
        for (z in zones) {
            interpretAny(CarProperties.readAnyViaClass(propId, z))?.let { return it }
        }

        // —— ③ 老路兜底：字节优先 ——
        for (z in zones) {
            decodeVarint(CarProperties.readBytesViaClass(propId, z))?.let { return it }
        }
        for (z in zones) {
            readNumericZone(propId, z)?.let { return it }
        }

        // —— ④ CEvt 平台形态的最后一搏 ——
        return decodeVarint(CarProperties.readBytes(propId))
    }

    /**
     * 把「类型不定」的原始值解释成数值。
     *
     * 第七十八轮新增。
     *
     * | 原始类型 | 解释 |
     * | --- | --- |
     * | `byte[]` | 按 protobuf varint 解（这组属性是 BYTES 类型，最常见） |
     * | 数值 | 直接用 |
     * | `int[]` / `Integer[]` | 取第一个元素（位图类属性的常见形状） |
     * | `String` | 能解析成数字就用，否则记一笔原始文本 |
     *
     * 解释不出来时**记一笔原始值** —— 这是标定「0 是关还是开」的依据，
     * 之前连原始值都看不到。
     */
    private fun interpretAny(raw: Any?): Long? = when (raw) {
        null -> null
        is ByteArray -> decodeVarint(raw)
        is Number -> raw.toLong()
        is IntArray -> raw.firstOrNull()?.toLong()
        is Array<*> -> (raw.firstOrNull() as? Number)?.toLong()
        is String -> raw.trim().toLongOrNull()
            ?: run {
                Logx.d(LogTags.SIGNAL, "车门属性返回字符串但不是数字", "text" to raw.take(64))
                null
            }
        else -> {
            Logx.d(LogTags.SIGNAL, "车门属性返回了未知类型", "type" to raw.javaClass.name)
            null
        }
    }

    /**
     * 逐个探一次这组属性，把**原始值**与**命中的类型**列出来（给诊断包用）。
     *
     * 第七十八轮新增。
     *
     * ## 为什么需要它
     *
     * 「车门读不到」这件事上，之前的诊断包只能回答
     * 「这 5 个属性在清单里、类型是 byte[]」，
     * 却回答不了**最关键**的问题：**车机到底返回了什么**。
     *
     * 原因很简单：读失败时**一条日志都没有** ——
     * `getProperty` 抛的类型不符异常被 `runCatching` 吞成了 null。
     *
     * 这一行就是来补这个洞的：下次诊断包里直接能看到
     * 「主驾 0x21701414 area=0 类型=byte[] 值=1」这样的结论。
     */
    fun probeRaw(): List<String> {
        val out = ArrayList<String>(DOOR_PROPS.size + 1)
        for ((i, prop) in DOOR_PROPS.withIndex()) {
            out += describeProp(DOOR_NAMES[i], prop)
        }
        out += describeProp("尾门", PROP_TRUNK)
        return out
    }

    /** 探一个属性：命中哪个类型、值是多少。 */
    private fun describeProp(label: String, propId: Int): String {
        val zones = CarProperties.availableAreas(propId)?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOf(0)
        for (z in zones) {
            val raw = CarProperties.readAnyViaClass(propId, z) ?: continue
            return "$label 0x%08X area=%d 类型=%s 值=%s".format(
                propId, z, raw.javaClass.simpleName, interpretAny(raw)?.toString() ?: "?",
            )
        }
        // 第七十九轮：**带类型这条路全失败时，用「无类型快照」问真相**。
        //
        // 车机的 `getProperty(int propId, int area)` 不做类型校验，
        // 一定能拿回值 —— 哪怕值是 `byte[]`。
        // 快照里带 status / 真实类名 / 字节长度 / 前若干字节，
        // 这是判断「这组属性到底怎么编码」的唯一依据。
        val snap = CarProperties.probeSnapshot(propId, zones.first())
        return "$label 0x%08X 快照 %s".format(propId, snap.describe())
    }

    /** 数值属性：先按类型要 `Integer`，再退通用数值读法。 */
    private fun readNumericZone(propId: Int, zone: Int): Long? =
        CarProperties.readIntViaClass(propId, zone)?.toLong()
            ?: CarProperties.readInt(propId, zone)?.toLong()

    /** protobuf 字节 → 唯一的 varint 字段值。 */
    private fun decodeVarint(bytes: ByteArray?): Long? =
        bytes?.let { ProtoLite.singleVarint(it) }

    /**
     * 把原始值解释成「关 / 开」。
     *
     * 口径一致（把「等于 1」当成开，其余当关）
     * 但我们**更保守**：只有 `0` 和 `1` 有明确含义，其它一律「读不到」。
     */
    fun interpret(raw: Long?): Boolean? = when (raw) {
        null -> null
        0L -> true
        1L -> false
        else -> null
    }
}
