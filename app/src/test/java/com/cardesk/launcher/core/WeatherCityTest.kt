package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气「城市名 ↔ 经纬度」换算的纯逻辑单测。
 *
 * 背景：真机上系统定位长期拿不到位置（provider 只有 `passive,gps` 且都没缓存位置），
 * 我们改用高德的城市名换算经纬度。城市名的规范化规则照抄高德
 *
 */
class WeatherCityTest {

    @Test
    fun pickCityFromAmapFile_takesMiddleSegmentAndStripsSuffix() {
        // 模拟器实测高德写出来的内容
        assertEquals("北京", WeatherCity.pickCityFromAmapFile("北京市-北京市-东城区"))
        assertEquals("上海", WeatherCity.pickCityFromAmapFile("上海市-上海市-浦东新区"))
        assertEquals("杭州", WeatherCity.pickCityFromAmapFile("浙江省-杭州市-西湖区"))
    }

    @Test
    fun pickCityFromAmapFile_singleSegmentStillWorks() {
        assertEquals("北京", WeatherCity.pickCityFromAmapFile("北京市"))
        assertEquals("深圳", WeatherCity.pickCityFromAmapFile("深圳"))
    }

    @Test
    fun pickCityFromAmapFile_blankReturnsNull() {
        assertNull(WeatherCity.pickCityFromAmapFile(null))
        assertNull(WeatherCity.pickCityFromAmapFile(""))
        assertNull(WeatherCity.pickCityFromAmapFile("   "))
    }

    @Test
    fun normalizeCityName_matchesAmapRules() {
        // 高德 City.processLocationName：去「市」「地区」「盟」
        assertEquals("北京", WeatherCity.normalizeCityName("北京市"))
        assertEquals("深圳", WeatherCity.normalizeCityName("深圳市"))
        assertEquals("大兴安岭", WeatherCity.normalizeCityName("大兴安岭地区"))
        assertEquals("锡林郭勒", WeatherCity.normalizeCityName("锡林郭勒盟"))
        // 自治区保留主体
        assertEquals("内蒙古", WeatherCity.normalizeCityName("内蒙古自治区"))
        // 特别行政区
        assertEquals("香港", WeatherCity.normalizeCityName("香港特别行政区"))
        assertEquals("澳门", WeatherCity.normalizeCityName("澳门特别行政区"))
        // 空 / 纯后缀
        assertNull(WeatherCity.normalizeCityName(null))
        assertNull(WeatherCity.normalizeCityName(""))
        assertNull(WeatherCity.normalizeCityName("市"))
    }

    @Test
    fun cityGeocodeUrl_containsEncodedCity() {
        val url = WeatherCity.cityGeocodeUrl("北京")
        assertTrue(url.startsWith(WeatherCity.GEOCODE_BASE))
        assertTrue(url.contains("name=%E5%8C%97%E4%BA%AC"))
        assertTrue(url.contains("count=1"))
        assertTrue(url.contains("language=zh"))
    }

    @Test
    fun parseCityGeocode_readsLatLon() {
        val json = """
            {"results":[{"name":"北京","latitude":39.9075,"longitude":116.39723,
            "country":"中国","admin1":"北京市"}],"generationtime_ms":0.19}
        """.trimIndent()
        val geo = WeatherCity.parseCityGeocode(json)
        assertEquals(39.9075, geo!!.first, 1e-6)
        assertEquals(116.39723, geo.second, 1e-6)
    }

    @Test
    fun parseCityGeocode_invalidReturnsNull() {
        assertNull(WeatherCity.parseCityGeocode("{}"))
        assertNull(WeatherCity.parseCityGeocode("{\"results\":[]}"))
        assertNull(WeatherCity.parseCityGeocode("not json"))
        // 纬度越界
        assertNull(WeatherCity.parseCityGeocode("{\"results\":[{\"latitude\":999,\"longitude\":10}]}"))
    }
}
