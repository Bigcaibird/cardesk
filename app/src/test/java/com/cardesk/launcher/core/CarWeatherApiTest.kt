package com.cardesk.launcher.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 车机天气 SDK 反射封装的单测。
 *
 * 用假类模拟车机 SDK 的对象与方法形态，覆盖：
 * 字段读取（字符串/温度/城市）、harvest、回调参数遍历、
 * 创建方法/查询方法查找、以及**实参组装顺序**（经度在前、纬度在后）。
 *
 * 真正的 SDK 只在车机 `/system` 上存在，模拟器上没有 —— 所以这里只测「怎么用」，
 * 不测「车机上有没有」。
 */
class CarWeatherApiTest {

    // ---------------------------------------------------------------- 假类

    private interface FakeCallback {
        fun onWeather(data: Any?)
    }

    /** 字段齐全的天气对象（车机 SDK 的回调对象形态）。 */
    private class FullWeather {
        fun getWeatherPhenomena(): String = "晴"
        fun getTemperature(): Int = 23
        fun getCity(): String = "杭州市"
    }

    /** 只有备用字段名（`getTemp` / `getCityName`）的天气对象。 */
    private class AltWeather {
        fun getWeatherPhenomena(): String = "多云"
        fun getTemp(): String = "18.6"
        fun getCityName(): String = "上海市"
    }

    /** 只有天气文案、没有温度的城市（城市要用反查补）。 */
    private class SkyOnlyWeather {
        fun getWeatherPhenomena(): String = "雨"
        fun getCity(): String = "北京市"
    }

    /** 什么都没有 —— harvest 应该放弃。 */
    private class EmptyWeather {
        fun getSomethingElse(): String = "x"
    }

    /** 空字符串与字面量 "null" 都要当没有值。 */
    private class BlankWeather {
        fun getWeatherPhenomena(): String = "   "
        fun getCity(): String = "null"
        fun getTemperature(): Int = 20
    }

    /** 模拟车机 SDK 的 API 类。 */
    private class FakeApi {
        fun getWeatherByLocation(lon: Double, lat: Double, cb: FakeCallback) {
            // 只为满足「参数 ≥ 2 个」的查找条件
        }

        companion object {
            @JvmStatic
            fun createWeatherAPI(ctx: Context): FakeApi = FakeApi()

            /** 参数不是 Context —— 查找时**不该**选中这个。 */
            @JvmStatic
            fun create(x: Int): FakeApi = FakeApi()
        }
    }

    // ---------------------------------------------------------------- 字符串字段

    @Test
    fun readText_readsFirstAvailableMethod() {
        assertEquals("晴", CarWeatherApi.readText(FullWeather(), CarWeatherApi.SKY_METHODS))
        assertEquals("杭州市", CarWeatherApi.readText(FullWeather(), CarWeatherApi.CITY_METHODS))
    }

    @Test
    fun readText_fallsBackToLaterMethodName() {
        // 没有 getCity，只有 getCityName
        assertEquals("上海市", CarWeatherApi.readText(AltWeather(), CarWeatherApi.CITY_METHODS))
    }

    @Test
    fun readText_missingMethodIsNull() {
        assertNull(CarWeatherApi.readText(AltWeather(), CarWeatherApi.SKY_METHODS.let { listOf("getNope") }))
    }

    @Test
    fun readText_blankAndLiteralNullAreTreatedAsMissing() {
        // 空串与 "null" 都当没有值
        assertNull(CarWeatherApi.readText(BlankWeather(), listOf("getCity")))
        assertNull(CarWeatherApi.readText(BlankWeather(), listOf("getWeatherPhenomena")))
    }

    // ---------------------------------------------------------------- 温度字段

    @Test
    fun readNumber_readsIntValue() {
        assertEquals(23, CarWeatherApi.readNumber(FullWeather(), CarWeatherApi.TEMP_METHODS))
    }

    @Test
    fun readNumber_roundsNumericString() {
        // 字符串温度按 double 解析再 Math.round
        assertEquals(19, CarWeatherApi.readNumber(AltWeather(), CarWeatherApi.TEMP_METHODS))
    }

    @Test
    fun readNumber_stripsNonNumericCharacters() {
        val w = object {
            fun getTemperature(): String = "23.4°C"
        }
        assertEquals(23, CarWeatherApi.readNumber(w, listOf("getTemperature")))
    }

    @Test
    fun readNumber_missingMethodIsNull() {
        assertNull(CarWeatherApi.readNumber(SkyOnlyWeather(), CarWeatherApi.TEMP_METHODS))
    }

    // ---------------------------------------------------------------- harvest

    @Test
    fun harvest_readsCitySkyAndTemp() {
        val raw = CarWeatherApi.harvest(FullWeather())
        assertNotNull(raw)
        assertEquals("杭州市", raw?.city)
        assertEquals("晴", raw?.sky)
        assertEquals(23, raw?.temp)
    }

    @Test
    fun harvest_withoutTempStillHasSky() {
        val raw = CarWeatherApi.harvest(SkyOnlyWeather())
        assertNotNull(raw)
        assertEquals("雨", raw?.sky)
        assertNull(raw?.temp)
        assertEquals("北京市", raw?.city)
    }

    @Test
    fun harvest_withoutSkyAndTempIsDropped() {
        // 天气文案与温度都取不到 → 放弃
        assertNull(CarWeatherApi.harvest(EmptyWeather()))
    }

    @Test
    fun harvestAll_skipsNullArgsAndHarvestsEach() {
        val raws = CarWeatherApi.harvestAll(arrayOf(FullWeather(), null, AltWeather()))
        assertEquals(2, raws.size)
        assertEquals("杭州市", raws[0].city)
        assertEquals("上海市", raws[1].city)
    }

    @Test
    fun harvestAll_nullArrayIsEmpty() {
        assertTrue(CarWeatherApi.harvestAll(null).isEmpty())
    }

    // ---------------------------------------------------------------- 方法查找

    @Test
    fun findCreateMethod_picksContextFactory() {
        val method = CarWeatherApi.findCreateMethod(FakeApi::class.java)
        assertNotNull(method)
        assertEquals("createWeatherAPI", method?.name)
        assertEquals(1, method?.parameterTypes?.size)
    }

    @Test
    fun findQueryMethod_picksLocationQuery() {
        val method = CarWeatherApi.findQueryMethod(FakeApi())
        assertNotNull(method)
        assertEquals("getWeatherByLocation", method?.name)
        assertTrue((method?.parameterTypes?.size ?: 0) >= 2)
    }

    @Test
    fun findQueryMethod_missingIsNull() {
        assertNull(CarWeatherApi.findQueryMethod(object {}))
    }

    // ---------------------------------------------------------------- 实参组装

    @Test
    fun buildArgs_ordersLongitudeBeforeLatitude() {
        // 第一个 double 是**经度**，第二个是**纬度**
        val types = arrayOf<Class<*>>(
            Double::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            FakeCallback::class.java,
        )
        val args = CarWeatherApi.buildArgs(types, 120.15, 30.25) { iface ->
            CarWeatherApi.callbackProxy(iface) { }
        }
        assertEquals(120.15, args[0])
        assertEquals(30.25, args[1])
        assertTrue(args[2] is Proxy)
        assertTrue(FakeCallback::class.java.isInstance(args[2]))
    }

    @Test
    fun buildArgs_fillsInterfaceParamsWithProxy() {
        val types = arrayOf<Class<*>>(FakeCallback::class.java)
        var called = false
        val args = CarWeatherApi.buildArgs(types, 120.15, 30.25) { iface ->
            CarWeatherApi.callbackProxy(iface) { called = true }
        }
        val cb = FakeCallback::class.java.cast(args[0])!!
        cb.onWeather(null)
        assertTrue("回调代理应把调用转交给 onArgs", called)
    }

    @Test
    fun buildArgs_leavesOtherParamsNull() {
        val types = arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!)
        val args = CarWeatherApi.buildArgs(types, 120.15, 30.25) { null }
        assertNull(args[0])
        assertNull(args[1])
    }

    // ---------------------------------------------------------------- 可用性探测

    @Test
    fun isAvailable_isFalseWithoutCarSdk() {
        // 模拟器/普通手机上没有 com.ecarx.sdk.weather.WeatherAPI
        assertEquals(false, CarWeatherApi.isAvailable())
    }

    @Test
    fun className_matchesReference() {
        assertEquals("com.ecarx.sdk.weather.WeatherAPI", CarWeatherApi.WEATHER_API_CLASS)
    }

    // ---------------------------------------------------------------- 无位置入口

    /** 模拟车机 SDK 里「不需要经纬度、只带回调」的入口。 */
    private class NoLocationApi {
        fun getCurrentWeather(cb: FakeCallback) {
            cb.onWeather(FullWeather())
        }
    }

    /** 只有那条 `getWeatherByLocation` 路的 SDK。 */
    private class LocationOnlyApi {
        fun getWeatherByLocation(lon: Double, lat: Double, cb: FakeCallback) {
            cb.onWeather(FullWeather())
        }
    }

    /** 带字符串参数（城市名）但我们没有城市 → 不该被选中。 */
    private class CityOnlyApi {
        fun getWeatherByCity(city: String, cb: FakeCallback) {
            cb.onWeather(FullWeather())
        }
    }

    @Test
    fun findNoLocationQueryMethod_picksCallbackOnlyEntry() {
        val m = CarWeatherApi.findNoLocationQueryMethod(NoLocationApi())
        assertNotNull("应找到只带回调的入口", m)
        assertEquals("getCurrentWeather", m!!.name)
    }

    @Test
    fun findNoLocationQueryMethod_returnsNullWhenOnlyLocationEntryExists() {
        assertNull(CarWeatherApi.findNoLocationQueryMethod(LocationOnlyApi()))
    }

    @Test
    fun findNoLocationQueryMethod_returnsNullWhenEntryNeedsString() {
        assertNull(CarWeatherApi.findNoLocationQueryMethod(CityOnlyApi()))
    }

    @Test
    fun describeMethods_listsPublicEntries() {
        val text = CarWeatherApi.describeMethods(NoLocationApi())
        assertTrue("应包含方法名", text.contains("getCurrentWeather"))
    }
}
