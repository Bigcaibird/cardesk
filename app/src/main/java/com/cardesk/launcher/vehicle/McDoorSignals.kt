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
 *
 * ## 第八十轮：真凶是「解析器只认一个 varint」
 *
 * 上面几轮一直在查「属性通道 / 值类型」，方向对但都不是根因。
 * 真机回来的是 `byte[]` 没错，问题出在**把字节解成数值这一步**：
 * 这五个属性的值是 `GeelyProto.PublicAttrValue` 消息，通常同时带
 * `availability`（field 1）、`status`（field 3）、`data`（field 4）**三个 varint**；
 * 而旧实现走的是 [ProtoLite.singleVarint] ——
 * 它的口径是「整包恰好一个 varint」，**见到第二个就返回 null**。
 *
 * 于是「读到字节了，但解出来恒 null」，表现就是车门状态一直读不到。
 *
 * 现在这一步换成 [PublicAttrValue.parse]：按 protobuf 公开线格式扫完整条消息，
 * 并且 **`availability == 1` 才算有效**（不能把「没数据」当成「值 = 0」，
 * 否则车门会凭空变成「关」）。读取本身要求 `CarPropertyValue.getStatus() == 0`
 * （见 [CarProperties.readBytesWithStatus]）—— 与 UNI Launcher 跑通的那条读法一致。
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
            val sample = readValue(prop)
            val closed = interpretData(sample.value?.asDoorData())
            if (closed != null) any = true
            doors.add(closed)
            logSample(DOOR_NAMES[i], prop, sample, closed)
        }
        val trunkSample = readValue(PROP_TRUNK)
        val trunk = interpretData(trunkSample.value?.asDoorData())
        if (trunk != null) any = true
        logSample("尾门", PROP_TRUNK, trunkSample, trunk)

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
     * 读一个属性，返回**带证据的读取结果**（第八十轮）。
     *
     * ## 为什么不再只返回 `Long?`
     *
     * 「车门读不到」在真机上只能靠日志定位，而旧签名把「为什么读不到」全丢了：
     * 通道没起来、HAL 说无值、解析失败，最后都是一个 `null`。
     * 现在把消息本体、HAL 状态、命中路径一起带出来，诊断日志一次打全。
     *
     * ## 读取顺序
     *
     * 1. **PA 消息（首选）**：按 `byte[]` 读 → 查 `getStatus() == 0`（见
     *    [CarProperties.readBytesWithStatus]）→ [PublicAttrValue.parse] 完整解析 →
     *    `availability == 1` 且 `data` 是整数才算有效；
     * 2. 解析失败时退回旧的「恰好一个 varint」口径，尽量不丢原有能力；
     * 3. 再退回原有的数值 / 多类型 / CEvt 兜底 —— 一条都不删。
     *
     * 区域号：整车级属性传 `0`；属性清单里有区域就按区域逐个试。
     */
    private fun readValue(propId: Int): Sample {
        val areas = CarProperties.availableAreas(propId)
        val zones = if (areas != null && areas.isNotEmpty()) areas.toList() else listOf(0)

        // —— ① 首选：Geely PA 消息（`PublicAttrValue`）——
        //
        // 根因就在这一步：旧实现用 ProtoLite.singleVarint 解这条消息，
        // 而消息里 availability/status/data 有多个 varint，singleVarint 恒返回 null。
        var lastHalStatus: Int? = null
        var lastArea = 0
        for (z in zones) {
            val read = CarProperties.readBytesWithStatus(propId, z)
            lastArea = read.returnedArea ?: z
            if (read.halStatus != null) lastHalStatus = read.halStatus
            // HAL 明确说「当前无值」：不能拿它冒充一个门状态（对车门来说 0 就是「关」）
            if (!read.usable) continue
            val bytes = read.bytes ?: continue
            val pa = PublicAttrValue.parse(bytes)
            if (pa != null) {
                // 消息解析成功 ⇒ PA 的 availability 是权威口径
                if (!pa.active()) {
                    return Sample(null, pa, lastHalStatus, lastArea, "PA(availability=${pa.availability})")
                }
                pa.integerData()?.let {
                    return Sample(it.toLong(), pa, lastHalStatus, lastArea, "PA")
                }
                // availability=1 但 data 不是整数（double/string）：
                // 车门属性不该是这种形状，按「读不到」处理，不猜
                return Sample(null, pa, lastHalStatus, lastArea, "PA(noInt)")
            }
            // 解析失败：退回旧口径，兼容「裸 varint」形态的其它来源
            ProtoLite.singleVarint(bytes)?.let {
                return Sample(it, null, lastHalStatus, lastArea, "varint")
            }
        }

        // —— ② 保留原有兜底 ——
        // 分派口径抽在 [CarSignalMath.propReadKind]（纯函数，可单测）。
        when (CarSignalMath.propReadKind(CarProperties.propertyTypeOf(propId))) {
            CarSignalMath.PropReadKind.BYTES -> Unit // ① 已经按 PA 消息读过
            CarSignalMath.PropReadKind.NUMERIC ->
                for (z in zones) {
                    readNumericZone(propId, z)
                        ?.let { return Sample(it, null, lastHalStatus, z, "numeric") }
                }
            CarSignalMath.PropReadKind.UNKNOWN -> Unit
        }

        // —— ③ **多类型试读**（第七十八轮新增，保留）——
        for (z in zones) {
            interpretAny(CarProperties.readAnyViaClass(propId, z))
                ?.let { return Sample(it, null, lastHalStatus, z, "any") }
        }

        // —— ④ 老路兜底：字节优先（保留）——
        for (z in zones) {
            decodeVarint(CarProperties.readBytesViaClass(propId, z))
                ?.let { return Sample(it, null, lastHalStatus, z, "bytes") }
        }
        for (z in zones) {
            readNumericZone(propId, z)
                ?.let { return Sample(it, null, lastHalStatus, z, "numeric") }
        }

        // —— ⑤ CEvt 平台形态的最后一搏（保留）——
        decodeVarint(CarProperties.readBytes(propId))
            ?.let { return Sample(it, null, null, 0, "cevt") }
        return Sample(null, null, lastHalStatus, lastArea, "none")
    }

    /**
     * 一次属性读取的完整证据。
     *
     * @param value 最终数值（解释成关/开之前的原始值）；null = 没读到
     * @param payload PA 消息解析结果；null = 这条路没走通 / 线格式不合法
     * @param halStatus `CarPropertyValue.getStatus()`；null = 没取到
     * @param area 实际命中的区域号
     * @param via 命中的读取路径（PA / varint / numeric / any / bytes / cevt / none）
     */
    private class Sample(
        val value: Long?,
        val payload: PublicAttrValue?,
        val halStatus: Int?,
        val area: Int,
        val via: String,
    )

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

    /**
     * 探一个属性：PA 消息字段、HAL 状态、命中的类型与值。
     *
     * 第八十轮重写：先走与读值完全相同的 `byte[]` + `getStatus()` 路径，
     * 把 availability / format / status / data / int32Wire / halStatus 全部打出来；
     * 这条不通时再退回原来的「多类型试读 / 无类型快照」。
     */
    private fun describeProp(label: String, propId: Int): String {
        val zones = CarProperties.availableAreas(propId)?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOf(0)

        // —— ① PA 消息这条路：真机上车门属性就是这条 ——
        for (z in zones) {
            val read = CarProperties.readBytesWithStatus(propId, z)
            val pa = read.bytes?.let { PublicAttrValue.parse(it) }
            if (read.bytes != null || read.halStatus != null || read.failure != null) {
                return buildString {
                    append(label).append(" 0x%08X".format(propId))
                    append(" area=").append(z)
                    append(" halStatus=").append(statusText(read.halStatus))
                    append(" availability=").append(pa?.availability)
                    append(" format=").append(pa?.format)
                    append(" status=").append(pa?.status)
                    append(" data=").append(pa?.data)
                    append(" int32Wire=").append(pa?.int32Wire)
                    append(" level=").append(pa?.level)
                    append(" active=").append(pa?.active())
                    append(" bytes=").append(read.bytes?.size ?: 0).append("B")
                    if (pa == null) {
                        append(" parse=fail(").append(read.failure ?: "线格式不合法").append(")")
                        read.bytes?.let { append(" hex=").append(hexOf(it)) }
                    } else {
                        append(" parse=ok")
                    }
                }
            }
        }

        // —— ② 退回原来的多类型试读 ——
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

    /** 前 16 字节十六进制（诊断原始线格式用）。 */
    private fun hexOf(bytes: ByteArray): String {
        val n = minOf(bytes.size, 16)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append("%02X".format(bytes[i]))
        if (bytes.size > n) sb.append("…")
        return sb.toString()
    }

    /** HAL 状态的人类可读文本（`CarPropertyValue.getStatus()`）。 */
    private fun statusText(status: Int?): String = when (status) {
        null -> "未取到"
        0 -> "0 AVAILABLE"
        1 -> "1 UNAVAILABLE"
        2 -> "2 ERROR"
        else -> status.toString()
    }

    /**
     * 把一次读取的证据打全（第八十轮）。
     *
     * 为什么打这么多字段：真机上「车门读不到」只靠「raw=null」根本没法定位 ——
     * 分不清是属性通道没起来（halStatus）、车机说当前无值（availability ≠ 1）、
     * 还是解析口径不对（format / status / data / int32Wire）。
     * 这些字段就是下一次诊断包的答案。
     */
    private fun logSample(label: String, prop: Int, sample: Sample, closed: Boolean?) {
        if (sample.value == null && sample.payload == null && sample.halStatus == null) return
        val pa = sample.payload
        Logx.d(
            LogTags.SIGNAL,
            "MC 车门属性",
            "label" to label,
            "prop" to "0x%08X".format(prop),
            "raw" to sample.value,
            "closed" to closed,
            "via" to sample.via,
            "area" to sample.area,
            "halStatus" to statusText(sample.halStatus),
            "availability" to pa?.availability,
            "format" to pa?.format,
            "status" to pa?.status,
            "data" to pa?.data,
            "int32Wire" to pa?.int32Wire,
            "level" to pa?.level,
            "active" to pa?.active(),
        )
    }

    /** 数值属性：先按类型要 `Integer`，再退通用数值读法。 */
    private fun readNumericZone(propId: Int, zone: Int): Long? =
        CarProperties.readIntViaClass(propId, zone)?.toLong()
            ?: CarProperties.readInt(propId, zone)?.toLong()

    /** protobuf 字节 → 唯一的 varint 字段值。 */
    private fun decodeVarint(bytes: ByteArray?): Long? =
        bytes?.let { ProtoLite.singleVarint(it) }

    /**
     * 把原始值解释成「关 / 开」（**旧 IBcm 口径**，保留原样）。
     *
     * 只有 `0` 和 `1` 有明确含义，其它一律「读不到」。
     * 之所以不改它：既有单测（[McDoorSignalsTest]、[PaCpDoorPropsTest]）与
     * 其它来源都按这个口径断言；PA 这条路改用 [interpretData]。
     */
    fun interpret(raw: Long?): Boolean? = when (raw) {
        null -> null
        0L -> true
        1L -> false
        else -> null
    }

    /**
     * 把 PA 消息里的整数 `data` 解释成「关 / 开」（**合并口径**，第八十轮）。
     *
     * 为什么 `0` / `1` / `2` 三个值都算数：
     * PA 消息的 `data` 不是布尔，而是**门状态枚举**：
     *
     * | data | 含义 |
     * | --- | --- |
     * | `0` | 关 |
     * | `1` | 开 |
     * | `2` | 关（带状态位的关闭口径） |
     *
     * 旧实现只认 0/1，把 2 也当「读不到」—— 这是除解析根因之外，
     * 另一部分车门恒为 `?` 的原因。其它值（含 negative）仍按「读不到」处理，
     * 不猜。
     */
    fun interpretData(data: Int?): Boolean? = when (data) {
        null -> null
        0 -> true
        1 -> false
        2 -> true
        else -> null
    }

    /** 读取链路里的数值 → 门状态用的 Int；超出 Int 范围的直接丢（不截断猜值）。 */
    private fun Long.asDoorData(): Int? =
        if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null
}
