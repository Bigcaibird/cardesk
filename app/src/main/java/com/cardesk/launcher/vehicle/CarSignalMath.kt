package com.cardesk.launcher.vehicle

/**
 * 车辆信号的**纯数学**换算（不碰 Android，可单测）。
 *
 * 口径逐条
 * - 续航：
 * - 油量：
 * - 环温：
 */
object CarSignalMath {

    /**
     * 续航原始值 → 公里数。
     *
     *
     * - `raw > 0` 才认；
     * - `raw <= 3000` 直接当公里数，否则除以 1000（毫米的单位）；
     * - 结果必须落在 `1..2999`。
     *
     * @return null 表示这个原始值不是有效续航
     */
    fun rangeKm(raw: Float): Int? {
        if (raw.isNaN() || raw == Float.MIN_VALUE || raw <= 0f) return null
        val km = Math.round(if (raw <= 3000f) raw else raw / 1000f)
        if (km <= 0 || km >= 3000) return null
        return km
    }

    /**
     * 车门位置原始值 → 是否关闭。
     *
     *
     * - `< 0` → 读不出来（null）；
     * - `0` → 关闭；
     * - `1` → 打开；
     * - `553844994 / 553844993 / 553779457` → 打开（车端这三个值表示「开」）；
     * - `(1, 100]` → `<= 5` 关闭，否则打开（车端用 0..100 表示开度）；
     * - 其它 → 读不出来（null）。
     *
     * @return null 表示这个原始值不是有效的开闭状态
     */
    fun interpretClosed(raw: Int): Boolean? {
        if (raw < 0) return null
        if (raw == 0) return true
        if (raw == 1) return false
        if (raw == 553844994 || raw == 553844993 || raw == 553779457) return false
        if (raw <= 1 || raw > 100) return null
        return raw <= 5
    }

    /**
     * 这个原始值是不是「**固件不支持这个功能号 / 区域**」。
     *
     * ## 为什么单独拎出来
     *
     * 真机（领克 dcy11 / Android 9）诊断包里，Adapt 的四个门位全部返回 `raw=255`。
     * `255` 就是 `-1` 的无符号写法，jar 里带着这句话：
     * `is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`。
     *
     * 也就是说 `255` **不是**「门开着」，而是「**这台车不支持这么读**」。
     * 把它当成「读不出来」是对的，但**日志里要能区分**：
     * - 「不支持」→ zone / 功能号不对，要换读法；
     * - 「值解释不出来」→ 读到了但语义不明，要换映射表。
     *
     * 两种情况在桌面上的表现一样（显示 `--`），但在排障时必须分开。
     */
    fun isUnsupportedRaw(raw: Int): Boolean =
        raw == 255 || raw == -1 || raw == Int.MIN_VALUE

    /**
     * 车窗开闭原始值 → 是否打开（**true = 开**）。
     *
     * ## 口径
     *
     * `BCM_FUNC_WINDOW` 是**布尔**功能（不是百分比），所以只认：
     *
     * - `0` → 关（false）；
     * - `1` → 开（true）；
     * - `553844994` / `553844993` / `553779457` → 开（BCM 的「开」枚举，与车门同源）。
     *
     * 其它一律**读不出来**（null）。
     *
     * ## 为什么不套用车门那张表
     *
     * [interpretClosed] 里的 `2..5` 关闭 / `6..100` 打开是**位置百分比**的语义
     * （`BCM_FUNC_DOOR_POS` 返回的是开度）。车窗这一档返回的是**开 / 关**，
     * 把 `50` 解释成「开了一半」属于凭空捏造，所以不做。
     *
     * 文档也明确写了：车窗**开合百分比**是弱证据（`BCM_FUNC_WINDOW_POS` 可能整条
     * 不支持），**禁止**套用手机 BLE 的 `raw 2..26 → (raw-1)*4` 编码。
     * 真要拿百分比，等真机探测出 `BCM_FUNC_WINDOW_POS` 的实际值域再说。
     */
    fun interpretWindowOpen(raw: Int): Boolean? = when (raw) {
        0 -> false
        1 -> true
        553844994, 553844993, 553779457 -> true
        else -> null
    }

    /**
     * 油量原始值 → 百分比。
     *
     *
     * - 油箱容量未知（`<= 0`）→ `raw` 在 `0..100` 就当百分比直接用，否则读不出来；
     * - 油箱容量很小（`0 < capacity <= 200`）→ `raw > 2 * capacity` 时先除以 1000，
     *   然后当百分比；
     * - 油箱容量已知（`> 200`）→ `raw` 在 `0..200` 且 `raw * 1000 <= 1.2 * capacity`
     *   时先乘 1000，再 `raw / capacity * 100`；
     * - 结果统一夹到 `0..100`。
     *
     * @return null 表示换算不出百分比
     */
    fun fuelPercent(raw: Float, capacity: Float): Int? {
        val cap = if (capacity == Float.MIN_VALUE) 0f else capacity
        return if (cap <= 200f) {
            if (cap <= 0f) {
                if (raw < 0f || raw > 100f) null else Math.round(raw).coerceIn(0, 100)
            } else {
                val v = if (raw > 2f * cap) raw / 1000f else raw
                Math.round(v).coerceIn(0, 100)
            }
        } else {
            var r = raw
            if (r > 0f && r <= 200f) {
                val scaled = 1000f * r
                if (scaled <= 1.2f * cap) r = scaled
            }
            Math.round((r / cap) * 100f).coerceIn(0, 100)
        }
    }

    /**
     * 车端 `ISensor` 推来的油量原始值 → 百分比（**含占位值口径**）。
     *
     * 与 [fuelPercent] 只有一处不同：**`raw <= 0` 一律当「没有数据」（null）**。
     *
     * 原因（真机实测，见 `analysis/findings/09-fuel-sensor-fix.md`）：
     * 这台车机（领克 dcy11 / Android 9）的油量传感器
     * 在**开机**和**车机服务重连**时会先推一个 `0.0` 的**占位值**，
     * 真实值（实测 `21000.0`，约 5 分钟后）才会推来。
     * 把占位值当成「油箱空了」会让桌面显示一个**假的 0%**。
     *
     * 口径取「宁可显示 `--%`，也不显示一个假的 0%」。
     */
    fun fuelPercentFromSensor(raw: Float, capacity: Float): Int? {
        if (raw <= 0f) return null
        return fuelPercent(raw, capacity)
    }

    /**
     * 环温原始值 → 摄氏度。
     *
     * 四舍五入后必须落在 `-40..80`，否则丢弃。
     *
     * @return null 表示这个原始值不是有效环温
     */
    fun ambientTempC(raw: Float): Int? {
        if (raw.isNaN() || raw == Float.MIN_VALUE) return null
        val t = Math.round(raw)
        return if (t in -40..80) t else null
    }
}
