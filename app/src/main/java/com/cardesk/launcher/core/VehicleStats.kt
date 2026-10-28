package com.cardesk.launcher.core

/**
 * 车辆统计量的**纯逻辑**部分：属性值的解释与文案格式化。
 *
 * ## 为什么单独放 core
 *
 * 车机属性（`WINDOW_POS` / `DOOR_POS` / `PERF_ODOMETER` …）的读取要靠反射 + 车机服务，
 * 没法在 JVM 上跑；但**值的解释**（0 是不是「关」、12480 是米还是公里）是可以定死的，
 * 单独放这里就能单测，也避免「车上表现不对、但本地测不出来」。
 *
 * ## 口径来源
 *
 * - 门/窗属性 id 与名称属性名表
 *   （第三十六轮《车辆运行数据 API 对照表》核对时补齐，文档本身没写这条通道）。
 * - 门状态取值口径`EcarxSignals.doorsClosed()`（`v == 0` 即关闭）。
 * - 车窗开度：ECARX 的语音通道用**百分比**（`CB_Speech_TargetWinCtrlFrntLe`，0 = 全关、
 *   50 = 打开，见，而 AOSP `WINDOW_POS` 用 **0.0–1.0 的比例**。
 *   两种都兼容，见 [windowOpenFlags]。
 */
object VehicleStats {

    // ---------------------------------------------------------------- 门

    /**
     * 解释一个门位属性值：**true = 关闭**。
     *
     * 口径与 `EcarxSignals.doorsClosed()` 一致（`v == 0` 即关）。
     * AOSP 的枚举也是 `0 = CLOSED / 1 = OPEN / 2 = CLOSING / 3 = OPENING`，
     * 两边对得上，所以「非 0 即开」是安全的。
     *
     * @return null 表示这个值读不出来（不是「关」）
     */
    fun interpretDoorClosed(value: Double?): Boolean? {
        if (value == null) return null
        if (value.isNaN()) return null
        return value == 0.0
    }

    // ---------------------------------------------------------------- 窗

    /**
     * 从四个车窗的开度原始值判断每扇窗的开闭。
     *
     * **单位自适应**：AOSP `WINDOW_POS` 是 `0.0–1.0` 的比例，ECARX 原生是 `0–100` 的百分比。
     * 两者在「全关」(0) 上重合，在「全开」上分别是 `1.0` 与 `100`，所以：
     *
     * - 只要有一个值 `> 1.0` → 判定为**百分比**语义，`> 1` 即视为有缝；
     * - 否则按**比例**语义，`> 0.01` 即视为有缝（1% 以下当作全关，避免浮点噪声）。
     *
     * @param values 四扇窗的原始值，顺序 FL/FR/RL/RR；元素为 null 表示那一扇读不到
     * @return 长度 4 的数组，**true = 开**；整组读不到时返回 null
     */
    fun windowOpenFlags(values: DoubleArray?): BooleanArray? {
        if (values == null) return null
        if (values.size < 4) return null
        val readable = values.take(4).filter { !it.isNaN() }
        if (readable.isEmpty()) return null
        val percentMode = readable.any { it > 1.0 }
        val out = BooleanArray(4)
        for (i in 0 until 4) {
            val v = values[i]
            out[i] = when {
                v.isNaN() -> false
                percentMode -> v > 1.0
                else -> v > 0.01
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 区域号

    /**
     * AOSP 的**位掩码**。
     *
     * 顺序即下标顺序：左前 / 右前 / 左后 / 右后 / 引擎盖 / 后备箱。
     *
     * | 角色 | 掩码 |
     * | --- | --- |
     * | 左前门 | 2 |
     * | 右前门 | 4 |
     * | 左后门 | 8 |
     * | 右后门 | 16 |
     * | 引擎盖 | 32 |
     * | 后备箱 | 64 |
     *
     * 另有一个 = 128（尾门），轿车用不上，这里不列。
     */
    val DOOR_AREA_MASKS = intArrayOf(2, 4, 8, 16, 32, 64)

    /**
     * AOSP 的**位掩码**：左前 2 / 右前 4 / 左后 8 / 右后 16。
     *
     * 与 [DOOR_AREA_MASKS] 是同一套编码（那套也是）。
     */
    val WINDOW_AREA_MASKS = intArrayOf(2, 4, 8, 16)

    /**
     * 把属性返回的**区域号数组**映射成「角色下标 → 区域号」。
     *
     * ## 为什么需要这层
     *
     * `CarPropertyManager.getProperty(id)` 返回的 是这台车**实际注册**的区域号，
     * 有两种可能：
     *
     * 1. **位掩码**（AOSP 规范）：`{2, 4, 8, 16, 32, 64}` —— 每个区域号本身就是语义；
     * 2. **顺序索引**（不少厂商 HAL 的做法）：`{0, 1, 2, 3, 4, 5}` —— 按下标当语义。
     *
     * 自己也没区分： 只是把
     * 所有区域**聚合**成「全关 / 不全关」，从不区分是哪一扇门。
     * 我们要显示**每一扇**门的状态，就必须先把区域号翻译成角色。
     *
     * ## 判别规则
     *
     * - 数组里出现 `32 / 64 / 128` → 位掩码（索引模式下不会有这么大的数）；
     * - 或者数组里**没有 0** → 位掩码（位掩码永远不为 0）；
     * - 否则按顺序索引处理（下标 0/1/2/3/4/5 = 左前/右前/左后/右后/盖/箱）。
     *
     * @param areas 属性实际注册的区域号；null / 空 → 全部角色都标成「不可用」
     * @param masks 各角色的位掩码（顺序即角色下标）
     * @return 长度 = `masks.size` 的数组，元素是区域号，**-1 = 该角色不可用**
     */
    fun areaRoles(areas: IntArray?, masks: IntArray): IntArray {
        val out = IntArray(masks.size) { -1 }
        if (areas == null || areas.isEmpty()) return out
        val bitmaskMode = areas.any { it == 32 || it == 64 || it == 128 } || !areas.contains(0)
        if (bitmaskMode) {
            for (i in masks.indices) {
                val m = masks[i]
                if (areas.contains(m)) out[i] = m
            }
        } else {
            for (i in masks.indices) {
                val a = areas.getOrNull(i) ?: continue
                out[i] = a
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 总里程

    /**
     * 总里程归一化到**公里**。
     *
     * AOSP `ODOMETER` 的单位是**米**，ECARX 的 `PERF_ODOMETER` 未知（这台车上没实测过）。
     * 这里按量级判定：
     *
     * - `raw > 100_000` → 当作**米**，除以 1000；
     * - 否则当作**公里**。
     *
     * 阈值取 10 万：任何量产车的总里程都不可能到 10 万公里，所以「米」的读数一定远大于它，
     * 而「公里」的读数一定远小于它，两边不会歧义。
     *
     * @return 公里数（整数）；读不到或明显非法时返回 null
     */
    fun odometerKm(raw: Double?): Int? {
        if (raw == null) return null
        if (raw.isNaN() || raw.isInfinite()) return null
        if (raw < 0) return null
        val km = if (raw > 100_000.0) raw / 1000.0 else raw
        if (km <= 0.0) return null
        if (km > 2_000_000.0) return null
        return km.toInt()
    }

    // ---------------------------------------------------------------- 文案

    /**
     * 燃油剩余量文案：`58%`。
     *
     * 油量百分比的口径见 [com.cardesk.launcher.vehicle.CarSignalMath.fuelPercent]
     *
     *
     * @param pct 百分比（0..100）；null / 越界 → `--`
     */
    fun formatFuelPercent(pct: Int?): String =
        if (pct == null || pct < 0 || pct > 100) "--" else "$pct%"

    /**
     * 总里程文案：`12480 km`。
     *
     * 超过 1 万公里时加千分位（`12,480 km`），续航文案没这个规则
     * 但里程数位多，加了更易读；这是我们自选，不影响与一致性。
     */
    fun formatOdometer(km: Int?): String {
        if (km == null || km <= 0) return "--"
        return groupDigits(km) + " km"
    }

    /**
     * 平均油耗文案：`6.8 L/100km`。
     *
     * 单位按 ECARX 的惯例取 **L/100km**。
     * 真机上车若给的是别的单位，这里只影响后缀，不影响数值本身。
     */
    fun formatFuelConsumption(per100km: Double?): String {
        if (per100km == null || per100km.isNaN() || per100km < 0) return "--"
        return formatOneDecimal(per100km) + " L/100km"
    }

    /**
     * 行程里程文案（单位**公里**）。
     *
     * `ITripData.IDrivingInfo.getTripDistance()` 的原始单位是**米**，
     * 米 → 公里的换算在 [com.cardesk.launcher.vehicle.TripDataReader] 里做完了。
     */
    fun formatTripDistanceKm(km: Double?): String {
        if (km == null || km.isNaN() || km < 0) return "--"
        if (km < 0.05) return "0.0 km"
        return formatOneDecimal(km) + " km"
    }

    /** 行程时长文案（的单位是**毫秒**）：`1 小时 23 分`。 */
    fun formatTripDuration(millis: Double?): String {
        if (millis == null || millis.isNaN() || millis < 0) return "--"
        val totalMinutes = (millis / 60_000.0).toInt()
        if (totalMinutes < 1) return "不到 1 分钟"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "$h 小时 $m 分" else "$m 分钟"
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 一位小数，统一补 `.0`（避免「6 L/100km」和「6.8 L/100km」两种版式）。
     */
    private fun formatOneDecimal(v: Double): String =
        String.format(java.util.Locale.US, "%.1f", Math.round(v * 10.0) / 10.0)

    /** 千分位：`12480` → `12,480`。 */
    fun groupDigits(v: Int): String {
        val s = v.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder()
        val first = s.length % 3
        if (first > 0) sb.append(s, 0, first)
        var i = first
        while (i < s.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(s, i, i + 3)
            i += 3
        }
        return sb.toString()
    }
}
