package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「向高德索要区域信息」协议的纯逻辑单测。
 *
 * 背景：真机（领克 dcy11 / Android 9）定位 provider 只有 `passive,gps` 且都没缓存位置，
 * 车机自己的位置只在**高德 → 车机（ecarx.naviservice）** 这条协议里，
 * 我们改为「向高德索要区域信息」拿城市（见 `core/AmapAreaInfo`）。
 */
class AmapAreaInfoTest {

    @Test
    fun parseArea_readsType10030Payload() {
        // 高德 KEY_TYPE=10030 回包：省 / 市 / 区 / 区划码
        val area = AmapAreaInfo.parseArea(
            mapOf(
                "CITY_NAME" to "杭州市",
                "PROVINCE_NAME" to "浙江省",
                "AREA_NAME" to "西湖区",
                "AREA_CODE" to "330106",
            ),
        )
        assertEquals("杭州市", area?.city)
        assertEquals("浙江省", area?.province)
        assertEquals("西湖区", area?.district)
        assertEquals("330106", area?.areaCode)
    }

    @Test
    fun parseArea_readsType10077Payload() {
        // 高德 KEY_TYPE=10077 回包：多带区县（DISTRICT_NAME）与国家
        val area = AmapAreaInfo.parseArea(
            mapOf(
                "CITY_NAME" to "苏州市",
                "PROVINCE_NAME" to "江苏省",
                "DISTRICT_NAME" to "工业园区",
                "AREA_CODE" to "320594",
                "COUNTRY_NAME" to "中国",
            ),
        )
        assertEquals("苏州市", area?.city)
        assertEquals("工业园区", area?.district)
        assertEquals("320594", area?.areaCode)
    }

    @Test
    fun parseArea_withoutCityOrProvinceReturnsNull() {
        assertNull(AmapAreaInfo.parseArea(emptyMap()))
        assertNull(AmapAreaInfo.parseArea(mapOf("AREA_CODE" to "330106")))
        assertNull(AmapAreaInfo.parseArea(mapOf("CITY_NAME" to "   ")))
    }

    @Test
    fun parseArea_blankValuesTreatedAsAbsent() {
        val area = AmapAreaInfo.parseArea(mapOf("CITY_NAME" to "  ", "PROVINCE_NAME" to "浙江省"))
        assertEquals("浙江省", area?.province)
        assertNull(area?.city)
    }

    @Test
    fun pickCity_prefersCityThenNormalizes() {
        val area = AmapAreaInfo.Area(province = "浙江省", city = "杭州市", district = "西湖区", areaCode = null)
        assertEquals("杭州", AmapAreaInfo.pickCity(area))
    }

    @Test
    fun pickCity_fallsBackToDistrictThenProvince() {
        // 区县 / 省份没有「市/地区/盟」后缀时按原样保留（高德 City.processLocationName 的规则）
        assertEquals(
            "西湖区",
            AmapAreaInfo.pickCity(AmapAreaInfo.Area(province = "浙江省", city = null, district = "西湖区", areaCode = null)),
        )
        assertEquals(
            "浙江省",
            AmapAreaInfo.pickCity(AmapAreaInfo.Area(province = "浙江省", city = null, district = null, areaCode = null)),
        )
        // 带后缀的才去后缀（高德只去「市/地区/盟/自治区/特别行政区」）
        assertEquals(
            "大兴安岭",
            AmapAreaInfo.pickCity(AmapAreaInfo.Area(province = null, city = null, district = "大兴安岭地区", areaCode = null)),
        )
    }

    @Test
    fun pickCity_nullAreaReturnsNull() {
        assertNull(AmapAreaInfo.pickCity(null))
    }

    @Test
    fun pickCity_keepsSpecialAdministrativeRegions() {
        // 高德 City.processLocationName：香港/澳门特别行政区 → 香港/澳门
        assertEquals(
            "香港",
            AmapAreaInfo.pickCity(AmapAreaInfo.Area(province = null, city = "香港特别行政区", district = null, areaCode = null)),
        )
    }
}
