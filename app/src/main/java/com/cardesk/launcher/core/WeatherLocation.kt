package com.cardesk.launcher.core

/**
 * 天气链路的**位置判定纯逻辑**（便于 JVM 单测）。
 *
 * 真机上系统定位长期没有位置（provider 只有 `passive,gps` 且都没 fix），
 * 于是我们要用各种兜底坐标：高德缓存的、自己持久化的、城市地理编码出来的。
 * 这些坐标都要先过一遍合法性判定，否则一个 (0,0) 就会把天气查到几内亚湾。
 */
object WeatherLocation {

    /** 纬度合法区间。 */
    const val MIN_LAT = -90.0

    /** 纬度合法区间。 */
    const val MAX_LAT = 90.0

    /** 经度合法区间。 */
    const val MIN_LON = -180.0

    /** 经度合法区间。 */
    const val MAX_LON = 180.0

    /**
     * 经纬度是否在合法范围内。
     *
     * `NaN` / 一律判为非法（`NaN` 的比较恒为 false，这里显式挡掉）。
     */
    fun isValidLatLon(lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN()) return false
        if (lat.isInfinite() || lon.isInfinite()) return false
        return lat in MIN_LAT..MAX_LAT && lon in MIN_LON..MAX_LON
    }

    /**
     * 一个「上次定位」现在还能不能用。
     *
     * @param ageMs 距今多久（毫秒）
     * @param maxAgeMs 最长可接受时长（毫秒）
     */
    fun isFixUsable(ageMs: Long, maxAgeMs: Long): Boolean = ageMs in 0..maxAgeMs
}
