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
 * 只按 protobuf 的**公开线格式**扫出唯一一个 varint 字段（见 [ProtoLite]）；
 * ECARX 上这五个是**数值属性**，走普通数值读法。
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
     * ## 第七十二轮：加了「数值」这条读法
     *
     * 原来只有一条路：`getProperty(int[], Class<byte[]>)` 取 protobuf 字节再解 varint。
     * 那是 **CEVT 平台**的属性形态（MC 事件属性是字节）。
     *
     * 但这台车机上跑的 `android.car` 是 ECARX 自己的一份，
     * 它的 `CarPropertyManager` **没有** `getProperty(int[], Class)` 这个重载，
     * 只有 `getProperty(int, int)` / `getProperty(Class, int, int)`，
     * 也就是说这五个属性在 ECARX 上是**数值属性**（0 / 1 的门状态），
     * 得按普通数值属性去读。
     *
     * 读法顺序：
     *
     * 1. 先试字节（CEVT 形态，保持原有行为）；
     * 2. 再试数值：区域号先用属性清单里查到的（ECARX 的 `CarPropertyValue`
     *    只有 `getAreaId()`，没有 AOSP 的 `getAreaIds()`，区域号得从清单拿），
     *    查不到就用 `0`（全局属性）。
     *
     * @return 读不到返回 null
     */
    private fun readValue(propId: Int): Long? {
        CarProperties.readBytes(propId)?.let { bytes ->
            ProtoLite.singleVarint(bytes)?.let { return it }
        }
        return readNumeric(propId)
    }

    /** 数值读法：区域号从属性清单里来，没有就用 `0`（全局属性）。 */
    private fun readNumeric(propId: Int): Long? {
        val areas = CarProperties.availableAreas(propId)
        if (areas != null && areas.isNotEmpty()) {
            for (area in areas) {
                val v = CarProperties.readInt(propId, area)
                if (v != null) return v.toLong()
            }
            return null
        }
        return CarProperties.readInt(propId, 0)?.toLong()
    }

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
