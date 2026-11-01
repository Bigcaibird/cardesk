package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 行程原始信号（VHAL `TS_*` 属性，值是 `byte[]`，即 `GeelyProto.PublicAttrValue`）。
 *
 * ## 为什么直接读 VHAL
 *
 * 文档 `docs/ecarx-adapterapi/15-energy-trip-tpms.md` 里 `ITripData` 只有
 * `getLatestDrivingInfo()` 是可用的（由 `DrivingInfoManager` 用 TS 信号拼装，
 * 语义就是「行程2」）；**行程1（总行程）没有任何 API**，只能读底层属性：
 *
 * | 属性 | id | 类型 | 用途 |
 * | --- | --- | --- | --- |
 * | `TS_TripDistance` | 560992369 / 0x21701071 | Integer + Format(METERS/YARDS) | 行程1 里程 |
 * | `TS_TripTime` | 560992370 / 0x21701072 | Integer | 行程1 时间（**文档未标单位**，按分钟解释） |
 * | `TS_TripAverageConsumption` | 560992368 / 0x21701070 | Double L/100km | 平均油耗 |
 * | `TS_OdometerTripMeter2` | 560992363 / 0x2170106B | Double km | 行程2 里程（兜底） |
 * | `TS_EDT_time2` | 560992362 / 0x2170106A | String "HH:mm" | 行程2 时间（兜底） |
 *
 * 这些属性在厂商 jar 里**有 getter、但没有消费点**（除 `OdometerTripMeter2`
 * 被 `DrivingInfoManager` 用掉），所以桌面按文档的线格式自己解析。
 *
 * ## 口径
 *
 * - `availability != 1` 视为读不到（不能把「没数据」当成 0）；
 * - 单位换算照文档 §3.1：`TripMeter2` 是 km（DrivingInfo 里 ×1000 变米），
 *   `TS_TripDistance` 是米（format=1 时是码），`TS_EDT_time2` 是 "HH:mm"；
 * - 读不到返回 null，不影响 `ITripData` 那条主路。
 */
object TripSignals {

    const val PROP_TRIP1_DISTANCE = 560992369
    const val PROP_TRIP1_TIME = 560992370
    const val PROP_AVG_FUEL = 560992368
    const val PROP_TRIP2_DISTANCE = 560992363
    const val PROP_TRIP2_TIME = 560992362

    /**
     * 行程2 复位（事件属性）：`GeelyProto.ManualResetTripValues`，
     * 文档里 0 = 未触发 / 1 = 触发。写入走 [CarProperties.writeInt]。
     */
    const val PROP_TRIP2_RESET = 559951906

    /** 手动复位回调 DID：同上枚举，作为复位第二条尝试。 */
    const val PROP_MANUAL_RESET_CALLBACK = 557850711

    /** 文档化的复位功能号（`ITripData.TRIP_FUNC_RESET`，jar 里没有实现）。 */
    const val FUNCTION_TRIP_RESET = 612368896

    /** 行程1（总行程）。 */
    data class Trip1(
        /** 里程（公里）；读不到为 null。 */
        val distanceKm: Double?,
        /** 时长（分钟）；读不到为 null。 */
        val durationMinutes: Int?,
        /** 平均油耗（L/100km）。 */
        val avgFuelL100: Double?,
    )

    /** 行程2（自复位行程）的原始兜底值。 */
    data class Trip2(
        val distanceKm: Double?,
        val durationMs: Long?,
        val avgFuelL100: Double?,
    )

    /** 读行程1；三项都读不到返回 null。 */
    fun readTrip1(): Trip1? {
        val distance = readProp(PROP_TRIP1_DISTANCE)
        val time = readProp(PROP_TRIP1_TIME)
        val avg = readProp(PROP_AVG_FUEL)
        if (distance == null && time == null && avg == null) return null
        val meters = distance?.integerData()?.toDouble()?.let { raw ->
            if (distance.format == 1) raw * YARDS_TO_METERS else raw
        }
        Logx.d(
            LogTags.VEHICLE,
            "行程1 原始信号",
            "distanceRaw" to (distance?.integerData() ?: "null"),
            "format" to (distance?.format ?: -1),
            "timeRaw" to (time?.integerData() ?: "null"),
            "avg" to (avg?.doubleData() ?: "null"),
        )
        return Trip1(meters?.div(1000.0), time?.integerData(), avg?.doubleData())
    }

    /** 读行程2 的兜底值（ITripData 读不到时用）。 */
    fun readTrip2(): Trip2? {
        val distance = readProp(PROP_TRIP2_DISTANCE)
        val time = readProp(PROP_TRIP2_TIME)
        val avg = readProp(PROP_AVG_FUEL)
        if (distance == null && time == null) return null
        return Trip2(
            distanceKm = distance?.doubleData(),
            durationMs = parseHourMinuteMs(time?.stringData),
            avgFuelL100 = avg?.doubleData(),
        )
    }

    /**
     * `TS_EDT_time2` 的 "HH:mm" → 毫秒。
     *
     * 文档给的就是 `((h×60)+m)×60×1000`；格式不合法返回 null。
     */
    fun parseHourMinuteMs(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h < 0 || m !in 0..59) return null
        return (h * 60L + m) * 60_000L
    }

    /** 读一条属性并按 `PublicAttrValue` 校验可用性。 */
    private fun readProp(propId: Int): PublicAttrValue? {
        val bytes = CarProperties.readBytesWithStatus(propId, 0).bytes ?: return null
        val parsed = PublicAttrValue.parse(bytes) ?: return null
        return if (parsed.active()) parsed else null
    }

    /** 文档里 `TsTripDistanceFormat`：0 = 米，1 = 码。 */
    private const val YARDS_TO_METERS = 0.9144
}
