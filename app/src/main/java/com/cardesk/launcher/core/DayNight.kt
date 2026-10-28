package com.cardesk.launcher.core

/**
 * 日夜模式的时段判定与模式解析。
 *
 * 键
 * - `daynight_enabled`：总开关，默认 **false**
 * - `daynight_mode`：0=跟随高德 / 1=跟随时间 / 2=跟随光感
 * - `daynight_day_start_min`：白天开始（默认 360 = 06:00）
 * - `daynight_night_start_min`：夜间开始（默认 1110 = 18:30）
 *
 * 白天区间是 `[dayStart, nightStart)`，其余算夜间。
 * 纯 Kotlin，可在 JVM 单测里覆盖（含跨零点的情形）。
 */
object DayNight {

    /** 默认值：白天 06:00 开始。 */
    const val DEFAULT_DAY_START_MIN = 360

    /** 默认值：夜间 18:30 开始。 */
    const val DEFAULT_NIGHT_START_MIN = 1110

    /** （:13）= 0：跟随高德。 */
    const val MODE_FOLLOW_AMAP = 0

    /** （:15）= 1：跟随本地时间。 */
    const val MODE_FOLLOW_TIME = 1

    /** （:14）= 2：跟随系统光感。 */
    const val MODE_FOLLOW_SENSOR = 2

    /** （:95-100）：只认 1 / 2，其余都当跟随高德。 */
    fun normalizeMode(mode: Int): Int = if (mode == MODE_FOLLOW_TIME || mode == MODE_FOLLOW_SENSOR) mode else MODE_FOLLOW_AMAP

    fun clampMinute(value: Int): Int = value.coerceIn(0, 1439)

    /** 把「小时 + 分钟」折算成当天分钟数。 */
    fun minuteOfDay(hour: Int, minute: Int): Int = clampMinute(hour * 60 + minute)

    /**
     * 判断某个时刻是不是夜间。
     *
     * 逐条（:553-561）
     * - `dayStart == nightStart` → 恒为夜间
     * - `dayStart < nightStart` → 夜间 = `[0, dayStart) ∪ [nightStart, 1440)`
     * - `dayStart > nightStart` → 夜间 = `[nightStart, dayStart)`（跨零点）
     *
     * @param minuteOfDay 当天分钟数（0..1439）
     * @param dayStartMin 白天开始
     * @param nightStartMin 夜间开始
     */
    fun isNight(minuteOfDay: Int, dayStartMin: Int, nightStartMin: Int): Boolean {
        val s = clampMinute(dayStartMin)
        val e = clampMinute(nightStartMin)
        val m = clampMinute(minuteOfDay)
        if (s == e) return true
        return if (s < e) {
            m < s || m >= e
        } else {
            m >= e && m < s
        }
    }

    /** 把分钟数格式化成 "HH:mm"。 */
    fun formatMinute(value: Int): String {
        val m = clampMinute(value)
        return String.format("%02d:%02d", m / 60, m % 60)
    }

    /**
     * 桌面配色该用夜间还是日间 —— 逐条（:328-399）。
     *
     * 判定顺序
     * 1. 总开关关闭（`daynight_enabled=false`）→ 回落到 `daynight_applied_night_ui`；
     *    没有这个键时按 **夜间** 处理（返回 null → `true`）。
     * 2. `mode == 1`（跟随时间）→ 按本地时段表判定。
     * 3. `mode == 2`（跟随光感）→ 用系统光感值；取不到时回落到上次应用值 / 夜间。
     * 4. `mode == 0`（跟随高德，**默认**）→ 优先用高德下发的夜间信号；
     *    没有信号时用系统值；再没有就回落到上次应用值 / 夜间。
     *
     * @param enabled `daynight_enabled`
     * @param mode `daynight_mode`
     * @param minuteOfDay 当天分钟数（跟随时间时用）
     * @param dayStartMin 白天开始分钟
     * @param nightStartMin 夜间开始分钟
     * @param amapNightUi 高德下发的夜间态（null = 没有信号）
     * @param sensorNightUi 系统光感夜间态（null = 取不到）
     * @param lastAppliedNightUi 上次实际应用的夜间态（null = 还没应用过）
     */
    fun resolveNightUi(
        enabled: Boolean,
        mode: Int,
        minuteOfDay: Int,
        dayStartMin: Int,
        nightStartMin: Int,
        amapNightUi: Boolean? = null,
        sensorNightUi: Boolean? = null,
        lastAppliedNightUi: Boolean? = null,
    ): Boolean {
        if (!enabled) return lastAppliedNightUi ?: true
        return when (normalizeMode(mode)) {
            MODE_FOLLOW_TIME -> isNight(minuteOfDay, dayStartMin, nightStartMin)
            MODE_FOLLOW_SENSOR -> sensorNightUi ?: (lastAppliedNightUi ?: true)
            else -> amapNightUi ?: sensorNightUi ?: (lastAppliedNightUi ?: true)
        }
    }
}
