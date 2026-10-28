package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车身状态（车门开闭）——走 AdaptAPI 的 **ICarFunction** 通道。
 *
 * ## 降级链
 *
 * 具体链路是
 *
 * 1. —— ECarXCar 的 SignalManager
 *    （`getDoorDrvrSts/getDoorPassSts/...`），这台车上**那个类不存在** → 跳过；
 * 2. **`adaptPosAllClosed(BCM_DOOR_POS, DOOR_ZONES)`** —— AdaptAPI 的属性通道 ← 我们走这条；
 * 3. `aggregatePosClosed(373295872)` —— `car_service` 的 property 通道（另一套 AIDL，先不接）。
 *
 * 第 2 条的算法：
 *
 * `
 * interpretClosed(getFuncVal(BCM_DOOR_POS, Integer.MIN_VALUE)) // 整体值（无 zone）
 * interpretClosed(getFuncVal(BCM_DOOR_POS, 0)) // 整体值（zone 0）
 * // 上面两个都读不出来时，逐个门位读 {1, 4, 16, 64}，全部能解释才返回聚合结果
 * `
 *
 * 值的解释见 （已落到 [CarSignalMath.interpretClosed]）。
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

    const val BCM_DOOR_POS = 553779968

    /**
     * 四个门位对应的 zone（`VehicleSignals.DOOR_ZONES = {1, 4, 16, 64}`）。
     *
     * 沿用车机的 zone 约定（与空调一致，`HvacIds.ZONE_DRIVER = 1`、
     * 主驾 / 副驾 / 后左 / 后右。
     */
    val DOOR_ZONES = intArrayOf(1, 4, 16, 64)

    /** 门位名（按 [DOOR_ZONES] 顺序），只用于日志与车辆页展示。 */
    val DOOR_NAMES = arrayOf("主驾", "副驾", "左后", "右后")

    /** 「无 zone」的探针值（用 `Integer.MIN_VALUE`）。 */
    const val ZONE_NONE = Int.MIN_VALUE

    /** 整体值探针的 zone（用 `0`）。 */
    const val ZONE_AGGREGATE = 0

    /**
     * 车门状态的一次读取结果。
     *
     * @param eachClosed 四门各自的开闭（顺序同 [DOOR_ZONES]）；
     *   **每一项独立** —— 某一扇读不出来时那一项是 null，其余照常（第四十五轮改）
     * @param allClosed 四门是否全部关闭（返回值）；null = 读不出来
     */
    data class DoorState(
        val eachClosed: List<Boolean?>?,
        val allClosed: Boolean?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DoorState) return false
            if (allClosed != other.allClosed) return false
            return eachClosed == other.eachClosed
        }

        override fun hashCode(): Int =
            (eachClosed?.hashCode() ?: 0) * 31 + (allClosed?.hashCode() ?: 0)
    }

    /**
     * 读一次车门状态（一次通道调用里把整体值与四个门位都读出来）。
     *
     * @return 完全读不到时 `eachClosed` 与 `allClosed` 都是 null
     */
    fun readDoors(gw: VehicleGateway): DoorState {
        // —— 逐门位（每一扇独立：读不到的那一扇留 null，不作废整组）——
        val perZone = MutableList<Boolean?>(DOOR_ZONES.size) { null }
        var anyZone = false
        var allZoneClosed = true
        for ((i, zone) in DOOR_ZONES.withIndex()) {
            val raw = gw.readRawFunctionValue(BCM_DOOR_POS, zone)
            if (raw == null) {
                Logx.d(
                    LogTags.SIGNAL,
                    "车门状态探针读不到",
                    "label" to DOOR_NAMES[i],
                    "zone" to zone,
                )
                continue
            }
            val closed = CarSignalMath.interpretClosed(raw)
            if (closed == null) {
                Logx.d(
                    LogTags.SIGNAL,
                    "车门状态探针值无法解释",
                    "label" to DOOR_NAMES[i],
                    "zone" to zone,
                    "raw" to raw,
                )
                continue
            }
            perZone[i] = closed
            anyZone = true
            if (!closed) allZoneClosed = false
            logDoorProbe(DOOR_NAMES[i], zone, raw, closed)
        }

        // —— 整体值（探测顺序：无 zone → zone 0 → 逐门位聚合）——
        val rawNone = gw.readRawFunctionValue(BCM_DOOR_POS, ZONE_NONE)
        if (rawNone != null) {
            val closed = CarSignalMath.interpretClosed(rawNone)
            if (closed != null) {
                logDoorProbe("整体(无zone)", ZONE_NONE, rawNone, closed)
                return DoorState(perZone, closed)
            }
        }
        val rawAgg = gw.readRawFunctionValue(BCM_DOOR_POS, ZONE_AGGREGATE)
        if (rawAgg != null) {
            val closed = CarSignalMath.interpretClosed(rawAgg)
            if (closed != null) {
                logDoorProbe("整体(zone0)", ZONE_AGGREGATE, rawAgg, closed)
                return DoorState(perZone, closed)
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
                return DoorState(viaProp, BodyState.aggregateAllClosed(viaProp))
            }
            Logx.w(
                LogTags.SIGNAL,
                "车门状态读不到（BCM_DOOR_POS 所有探针都没有有效值）",
                "func" to BCM_DOOR_POS,
            )
            return DoorState(null, null)
        }
        return DoorState(perZone, allZoneClosed)
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
}
