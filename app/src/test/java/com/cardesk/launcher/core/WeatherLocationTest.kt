package com.cardesk.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气链路的坐标合法性判定单测。
 *
 * 真机上系统定位长期拿不到位置，我们用各种兜底坐标（高德缓存 / 自己持久化的 /
 * 城市地理编码出来的—— 这些坐标必须先过合法性判定，
 * 否则一个 (0,0) 就会把天气查到几内亚湾。
 */
class WeatherLocationTest {

    @Test
    fun isValidLatLon_acceptsRealCoordinates() {
        assertTrue(WeatherLocation.isValidLatLon(39.90923, 116.39742))
        assertTrue(WeatherLocation.isValidLatLon(-33.8688, 151.2093))
        assertTrue(WeatherLocation.isValidLatLon(0.0, 0.0))
        assertTrue(WeatherLocation.isValidLatLon(90.0, 180.0))
        assertTrue(WeatherLocation.isValidLatLon(-90.0, -180.0))
    }

    @Test
    fun isValidLatLon_rejectsOutOfRange() {
        assertFalse(WeatherLocation.isValidLatLon(90.1, 0.0))
        assertFalse(WeatherLocation.isValidLatLon(-90.1, 0.0))
        assertFalse(WeatherLocation.isValidLatLon(0.0, 180.1))
        assertFalse(WeatherLocation.isValidLatLon(0.0, -180.1))
    }

    @Test
    fun isValidLatLon_rejectsNaNAndInfinity() {
        assertFalse(WeatherLocation.isValidLatLon(Double.NaN, 0.0))
        assertFalse(WeatherLocation.isValidLatLon(0.0, Double.NaN))
        assertFalse(WeatherLocation.isValidLatLon(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(WeatherLocation.isValidLatLon(0.0, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun isFixUsable_acceptsFreshAndRejectsStale() {
        val max = 6 * 60 * 60 * 1000L
        assertTrue(WeatherLocation.isFixUsable(0L, max))
        assertTrue(WeatherLocation.isFixUsable(max, max))
        assertFalse(WeatherLocation.isFixUsable(max + 1, max))
        assertFalse(WeatherLocation.isFixUsable(-1L, max))
    }
}
