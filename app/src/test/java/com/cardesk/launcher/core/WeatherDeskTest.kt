package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气子系统纯逻辑单测。
 *
 * 只测不依赖 Android 的部分：WMO 码映射、弱城市判定、两套反查字段挑选、
 * 温度四舍五入与接口响应解析。线程/回调/定位属于设备侧行为，不在 JVM 单测里覆盖。
 */
class WeatherDeskTest {

    // ---------------------------------------------------------------- WMO 码映射

    @Test
    fun wmo_zeroIsSunny() {
        assertEquals("晴", wmoLabel(0))
    }

    @Test
    fun wmo_cloudyRange() {
        assertEquals("多云", wmoLabel(1))
        assertEquals("多云", wmoLabel(2))
        assertEquals("多云", wmoLabel(3))
    }

    @Test
    fun wmo_fogRange() {
        assertEquals("雾", wmoLabel(4))
        assertEquals("雾", wmoLabel(48))
    }

    @Test
    fun wmo_drizzleRange() {
        assertEquals("毛毛雨", wmoLabel(49))
        assertEquals("毛毛雨", wmoLabel(57))
    }

    @Test
    fun wmo_rainRange() {
        assertEquals("雨", wmoLabel(58))
        assertEquals("雨", wmoLabel(67))
    }

    @Test
    fun wmo_snowRange() {
        assertEquals("雪", wmoLabel(68))
        assertEquals("雪", wmoLabel(77))
    }

    @Test
    fun wmo_showersRange() {
        assertEquals("阵雨", wmoLabel(78))
        assertEquals("阵雨", wmoLabel(82))
    }

    @Test
    fun wmo_snowShowersRange() {
        assertEquals("阵雪", wmoLabel(83))
        assertEquals("阵雪", wmoLabel(86))
    }

    @Test
    fun wmo_thunderstormRange() {
        assertEquals("雷阵雨", wmoLabel(87))
        assertEquals("雷阵雨", wmoLabel(99))
    }

    @Test
    fun wmo_unknownCodeIsNull() {
        assertNull(wmoLabel(100))
        assertNull(wmoLabel(123))
        assertNull(wmoLabel(-1))
    }

    // ---------------------------------------------------------------- 弱城市判定

    @Test
    fun weakCity_blankIsWeak() {
        assertTrue(isWeakCity(null))
        assertTrue(isWeakCity(""))
        assertTrue(isWeakCity("   "))
    }

    @Test
    fun weakCity_nonChineseIsWeak() {
        assertTrue(isWeakCity("Hangzhou"))
        assertTrue(isWeakCity("Shanghai"))
        assertTrue(isWeakCity("Tokyo"))
    }

    @Test
    fun weakCity_chineseIsStrong() {
        assertFalse(isWeakCity("杭州"))
        assertFalse(isWeakCity("上海"))
        assertFalse(isWeakCity("北京市"))
    }

    @Test
    fun cleanCity_trimsAndDropsEmpty() {
        assertEquals("杭州", cleanCity("  杭州 "))
        assertNull(cleanCity(""))
        assertNull(cleanCity("   "))
        assertNull(cleanCity(null))
    }

    // ---------------------------------------------------------------- bigdatacloud 反查

    @Test
    fun bigDataCloud_prefersCityOverLocality() {
        // 顺序是 city → locality → principalSubdivision
        val map = mapOf(
            "city" to "杭州市",
            "locality" to "拱墅区",
            "principalSubdivision" to "浙江省",
        )
        assertEquals("杭州市", pickBigDataCloudCity(map))
    }

    @Test
    fun bigDataCloud_fallsBackToLocalityThenSubdivision() {
        assertEquals("拱墅区", pickBigDataCloudCity(mapOf("locality" to "拱墅区")))
        assertEquals("浙江省", pickBigDataCloudCity(mapOf("principalSubdivision" to "浙江省")))
    }

    @Test
    fun bigDataCloud_skipsBlankValues() {
        val map = mapOf("city" to "   ", "locality" to "", "principalSubdivision" to "浙江省")
        assertEquals("浙江省", pickBigDataCloudCity(map))
    }

    @Test
    fun bigDataCloud_noFieldIsEmpty() {
        assertEquals("", pickBigDataCloudCity(emptyMap()))
    }

    @Test
    fun bigDataCloud_parseFromJson() {
        val json = """{"city":"杭州市","locality":"拱墅区","continent":"亚洲"}"""
        assertEquals("杭州市", parseBigDataCloudCity(json))
    }

    @Test
    fun bigDataCloud_brokenJsonIsEmpty() {
        assertEquals("", parseBigDataCloudCity("not-json"))
    }

    // ---------------------------------------------------------------- nominatim 反查

    @Test
    fun nominatim_prefersCity() {
        val map = mapOf(
            "address" to mapOf("city" to "杭州市", "town" to "余杭区", "state" to "浙江省"),
        )
        assertEquals("杭州市", pickNominatimCity(map))
    }

    @Test
    fun nominatim_fallsBackToTownThenCountyThenState() {
        // 顺序是 city → town → county → state
        assertEquals("余杭区", pickNominatimCity(mapOf("address" to mapOf("town" to "余杭区"))))
        assertEquals("余杭区", pickNominatimCity(mapOf("address" to mapOf("county" to "余杭区"))))
        assertEquals("浙江省", pickNominatimCity(mapOf("address" to mapOf("state" to "浙江省"))))
    }

    @Test
    fun nominatim_skipsBlankValues() {
        val map = mapOf("address" to mapOf("city" to "  ", "town" to "", "state" to "浙江省"))
        assertEquals("浙江省", pickNominatimCity(map))
    }

    @Test
    fun nominatim_missingAddressIsEmpty() {
        assertEquals("", pickNominatimCity(emptyMap()))
        assertEquals("", pickNominatimCity(mapOf("address" to "not-an-object")))
    }

    @Test
    fun nominatim_parseFromJson() {
        val json = """{"address":{"city":"杭州市","town":"余杭区"}}"""
        assertEquals("杭州市", parseNominatimCity(json))
    }

    // ---------------------------------------------------------------- 天气响应解析

    @Test
    fun currentWeather_roundsTemperatureAndMapsCode() {
        val json = """{"current":{"temperature_2m":23.6,"weather_code":3}}"""
        val now = parseCurrentWeather(json)
        assertEquals(24, now?.temp)
        assertEquals("多云", now?.sky)
    }

    @Test
    fun currentWeather_roundsNegativeTemperature() {
        val json = """{"current":{"temperature_2m":-3.6,"weather_code":0}}"""
        val now = parseCurrentWeather(json)
        assertEquals(-4, now?.temp)
        assertEquals("晴", now?.sky)
    }

    @Test
    fun currentWeather_unknownCodeHasNoSky() {
        val json = """{"current":{"temperature_2m":20.0,"weather_code":150}}"""
        assertNull(parseCurrentWeather(json)?.sky)
    }

    @Test
    fun currentWeather_missingTemperatureIsNull() {
        assertNull(parseCurrentWeather("""{"current":{"weather_code":0}}"""))
        assertNull(parseCurrentWeather("""{"current":null}"""))
        assertNull(parseCurrentWeather("not-json"))
    }

    @Test
    fun roundTemp_usesHalfUp() {
        assertEquals(24, roundTemp(23.5))
        assertEquals(23, roundTemp(23.4))
        assertEquals(23, roundTemp(23.49))
    }

    // ---------------------------------------------------------------- JsonLite.double

    @Test
    fun jsonLite_double_readsNumbers() {
        assertEquals(23.6, JsonLite.double(mapOf("t" to 23.6), "t"), 0.0001)
        assertEquals(24.0, JsonLite.double(mapOf("t" to 24), "t"), 0.0001)
        assertEquals(23.6, JsonLite.double(mapOf("t" to "23.6"), "t"), 0.0001)
        assertEquals(1.5, JsonLite.double(emptyMap(), "t", 1.5), 0.0001)
        assertEquals(1.5, JsonLite.double(mapOf("t" to "abc"), "t", 1.5), 0.0001)
    }

    // ---------------------------------------------------------------- 接口 URL

    @Test
    fun forecastUrl_carriesCoordinatesAndFields() {
        val url = forecastUrl(30.25, 120.15)
        assertTrue(url.contains("latitude=30.25"))
        assertTrue(url.contains("longitude=120.15"))
        assertTrue(url.contains("current=temperature_2m,weather_code"))
        assertTrue(url.contains("timezone=auto"))
    }

    @Test
    fun reverseUrls_carryCoordinates() {
        assertTrue(bigDataCloudUrl(30.25, 120.15).contains("latitude=30.25"))
        assertTrue(bigDataCloudUrl(30.25, 120.15).contains("localityLanguage=zh"))
        assertTrue(nominatimUrl(30.25, 120.15).contains("lat=30.25"))
        assertTrue(nominatimUrl(30.25, 120.15).contains("accept-language=zh-CN,zh"))
    }

    // ---------------------------------------------------------------- 持久化键名

    @Test
    fun keys_matchReference() {
        assertEquals("weather_last_city", WEATHER_KEY_CITY)
        assertEquals("weather_last_sky", WEATHER_KEY_SKY)
        assertEquals("weather_last_temp", WEATHER_KEY_TEMP)
        assertEquals("cardesk", WEATHER_PREFS_NAME)
    }

    // ---------------------------------------------------------------- 空气质量

    @Test
    fun airQuality_goodRange() {
        assertEquals("空气优", airQualityLabel(0))
        assertEquals("空气优", airQualityLabel(50))
        assertEquals("空气良", airQualityLabel(51))
        assertEquals("空气良", airQualityLabel(100))
    }

    @Test
    fun airQuality_pollutedRange() {
        assertEquals("轻度污染", airQualityLabel(101))
        assertEquals("轻度污染", airQualityLabel(150))
        assertEquals("中度污染", airQualityLabel(151))
        assertEquals("中度污染", airQualityLabel(200))
        assertEquals("重度污染", airQualityLabel(201))
    }

    @Test
    fun airQuality_parseFromJson() {
        val json = """{"current":{"us_aqi":73.4}}"""
        assertEquals(73, parseAirQuality(json))
    }

    @Test
    fun airQuality_missingOrBrokenIsNull() {
        assertNull(parseAirQuality("""{"current":{}}"""))
        assertNull(parseAirQuality("""{"current":null}"""))
        assertNull(parseAirQuality("not-json"))
    }

    @Test
    fun airQualityUrl_pointsToAirQualityEndpoint() {
        val url = airQualityUrl(30.25, 120.15)
        assertTrue(url.contains("air-quality-api.open-meteo.com"))
        assertTrue(url.contains("latitude=30.25"))
        assertTrue(url.contains("longitude=120.15"))
        assertTrue(url.contains("current=us_aqi"))
    }

    // ---------------------------------------------------------------- 天气 + 空气质量拼接

    @Test
    fun combineSky_joinsWithBullet() {
        // `天气 • 空气质量`
        assertEquals("晴 • 空气优", combineSky("晴", "空气优"))
    }

    @Test
    fun combineSky_singleValueKeepsAsIs() {
        assertEquals("晴", combineSky("晴", null))
        assertEquals("空气优", combineSky(null, "空气优"))
        assertEquals("晴", combineSky("晴", ""))
    }

    @Test
    fun combineSky_bothEmptyIsNull() {
        assertNull(combineSky(null, null))
        assertNull(combineSky("", ""))
    }

    // ---------------------------------------------------------------- 定位候选挑选

    @Test
    fun pickLocation_takesNewestValid() {
        val picked = pickFreshestLocation(
            listOf(
                LocationCandidate(timeMillis = 100L, ageNanos = 1_000_000_000L, accuracyMeters = 10f),
                LocationCandidate(timeMillis = 300L, ageNanos = 2_000_000_000L, accuracyMeters = 20f),
                LocationCandidate(timeMillis = 200L, ageNanos = 3_000_000_000L, accuracyMeters = 30f),
            )
        )
        assertEquals(300L, picked?.timeMillis)
    }

    @Test
    fun pickLocation_rejectsStale() {
        // age ≤ 600s 才算新鲜
        val picked = pickFreshestLocation(
            listOf(
                LocationCandidate(timeMillis = 100L, ageNanos = 601_000_000_000L, accuracyMeters = 10f),
            )
        )
        assertNull(picked)
    }

    @Test
    fun pickLocation_acceptsExactlyMaxAge() {
        val picked = pickFreshestLocation(
            listOf(
                LocationCandidate(
                    timeMillis = 100L,
                    ageNanos = WEATHER_MAX_LOCATION_AGE_NANOS,
                    accuracyMeters = 10f,
                )
            )
        )
        assertEquals(100L, picked?.timeMillis)
    }

    @Test
    fun pickLocation_rejectsNegativeAge() {
        // `jElapsedRealtimeNanos >= 0` 才接受
        val picked = pickFreshestLocation(
            listOf(LocationCandidate(timeMillis = 100L, ageNanos = -1L, accuracyMeters = 10f))
        )
        assertNull(picked)
    }

    @Test
    fun pickLocation_rejectsTooInaccurate() {
        // `accuracy <= 5000f` 才接受
        val picked = pickFreshestLocation(
            listOf(LocationCandidate(timeMillis = 100L, ageNanos = 1_000_000L, accuracyMeters = 5001f))
        )
        assertNull(picked)
    }

    @Test
    fun pickLocation_acceptsExactlyMaxAccuracy() {
        val picked = pickFreshestLocation(
            listOf(LocationCandidate(timeMillis = 100L, ageNanos = 1_000_000L, accuracyMeters = 5000f))
        )
        assertEquals(100L, picked?.timeMillis)
    }

    @Test
    fun pickLocation_acceptsUnknownAccuracy() {
        // `!hasAccuracy()` 时不过滤精度
        val picked = pickFreshestLocation(
            listOf(LocationCandidate(timeMillis = 100L, ageNanos = 1_000_000L, accuracyMeters = -1f))
        )
        assertEquals(100L, picked?.timeMillis)
    }

    @Test
    fun pickLocation_emptyIsNull() {
        assertNull(pickFreshestLocation(emptyList()))
    }

    // ---------------------------------------------------------------- 降级取位置

    @Test
    fun pickAny_takesNewestRegardlessOfFreshness() {
        //  600s/5000m 过滤在车机上可能过严 → 滤空时降级取任意最新位置
        val picked = pickAnyLocation(
            listOf(
                LocationCandidate(timeMillis = 100L, ageNanos = 900_000_000_000L, accuracyMeters = 9000f),
                LocationCandidate(timeMillis = 300L, ageNanos = 900_000_000_000L, accuracyMeters = 9000f),
                LocationCandidate(timeMillis = 200L, ageNanos = 1_000_000L, accuracyMeters = 10f),
            )
        )
        assertEquals(300L, picked?.timeMillis)
    }

    @Test
    fun pickAny_staleAndInaccurateAreStillAccepted() {
        val picked = pickAnyLocation(
            listOf(LocationCandidate(timeMillis = 100L, ageNanos = 900_000_000_000L, accuracyMeters = 9000f))
        )
        assertEquals(100L, picked?.timeMillis)
    }

    @Test
    fun pickAny_emptyIsNull() {
        assertNull(pickAnyLocation(emptyList()))
    }
}
