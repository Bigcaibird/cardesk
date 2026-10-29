package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.ambient.AdaptapiAmbientLightApi
import com.cardesk.launcher.core.ambient.AmbientColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdaptapiAmbientLightApi] 的**写入侧**单测。
 *
 * 验的是三件事（都对着车机端 jar 的证据）：
 *
 * 1. **分区**：主 / 上 / 下是三个独立功能号，开灯时**三个都要写**；
 * 2. **亮度**：行车 / 静止是两套功能号各一套档位常量，
 *    且 **`0` 是 OFF（关灯），不是最低档**；
 * 3. **体验模式**：写自定义色之前先置 `CUSTOM`（顺序）。
 *
 * 厂商侧对照（`com.ecarx.xui.adaptapi.car.vehicle.IAmbienceLight` / `IVehicle`）：
 *
 * | 东西 | 值 |
 * | --- | --- |
 * | `SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES` | `537527552`（`0x200A0500`） |
 * | `SETTING_FUNC_AMBIENCE_LIGHT_TOPZONES` | `537527296`（`0x200A0400`） |
 * | `SETTING_FUNC_AMBIENCE_LIGHT_BOTZONES` | `537527040`（`0x200A0300`） |
 * | `SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_DRIVING` | `537528064`（`0x200A0700`） |
 * | `BRIGHTNESS_DRIVING_LOW` / `HIGH` | `537528065` / `537528066` |
 * | `SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_STATIONARY` | `537527808`（`0x200A0600`） |
 * | `BRIGHTNESS_STATIONARY_LOW` / `HIGH` | `537527809` / `537527810` |
 * | `SETTING_FUNC_AMBIENCE_LIGHT_EXPERIENCE` | `537526528`（`0x200A0100`） |
 * | `AMBIENCE_LIGHT_EXPERIENCE_CUSTOM` | `537526530`（`0x200A0102`） |
 */
class AdaptapiAmbientLightWriteTest {

    /** 记录每一次写入的假通道（**按顺序**，用来验先后关系）。 */
    private class FakeGateway : VehicleGateway {
        override val id: String = "adaptapi"
        override val displayName: String = "假通道"
        override var on360State: ((Boolean) -> Unit)? = null

        /** 每一次写入：功能号 → 区域 → 值（顺序即写入顺序）。 */
        val writes = mutableListOf<Triple<Int, Int, Int>>()

        override fun connect(onResult: (Boolean) -> Unit) = onResult(true)
        override fun disconnect() = Unit
        override fun isConnected(): Boolean = true
        override fun setHvac(func: Int, zone: Int, value: Int): Boolean {
            writes.add(Triple(func, zone, value))
            return true
        }

        override fun setTemp(zone: Int, celsius: Float): Boolean = false
        override fun readAll(): Map<Int, Int> = emptyMap()
        override fun registerListener(listener: (Int, Int, Int) -> Unit) = Unit
        override fun unregisterListener(listener: (Int, Int, Int) -> Unit) = Unit
    }

    // —— jar 里的确定值（这里当「覆盖值」用，真机上它们是反射解析出来的）——
    private val mainZonesFunc = 0x200A0500
    private val topZonesFunc = 0x200A0400
    private val botZonesFunc = 0x200A0300
    private val drivingBrightnessFunc = 0x200A0700
    private val stationaryBrightnessFunc = 0x200A0600
    private val experienceFunc = 0x200A0100
    private val colorFunc = 0x200A0900
    private val effectFunc = 0x200A0800

    @After
    fun tearDown() {
        AdaptapiConstants.resetForTest()
    }

    /** 给一份「全都解析得到」的覆盖值。 */
    private fun setup(): FakeGateway {
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_AMBIENT_MAIN_ZONES to mainZonesFunc,
            AdaptapiConstants.FUNC_AMBIENT_TOP_ZONES to topZonesFunc,
            AdaptapiConstants.FUNC_AMBIENT_BOT_ZONES to botZonesFunc,
            AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_DRIVING to drivingBrightnessFunc,
            AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_STATIONARY to stationaryBrightnessFunc,
            AdaptapiConstants.FUNC_AMBIENT_EXPERIENCE to experienceFunc,
            AdaptapiConstants.FUNC_AMBIENT_COLOR_SET to colorFunc,
            AdaptapiConstants.FUNC_AMBIENT_INTERACTIVE_EFFECT to effectFunc,
            AdaptapiConstants.AMBIENT_MAIN_COLOR_SET_COLOR to 0x200A0203,
            AdaptapiConstants.AMBIENT_BRIGHTNESS_LOW to 0x200A0701,
            AdaptapiConstants.AMBIENT_BRIGHTNESS_HIGH to 0x200A0702,
            AdaptapiConstants.AMBIENT_BRIGHTNESS_STATIONARY_LOW to 0x200A0601,
            AdaptapiConstants.AMBIENT_BRIGHTNESS_STATIONARY_HIGH to 0x200A0602,
            AdaptapiConstants.AMBIENT_EXPERIENCE_CUSTOM to 0x200A0102,
            AdaptapiConstants.AMBIENT_EXPERIENCE_FULL to 0x200A0101,
        )
        return FakeGateway()
    }

    // ------------------------------------------------------------ 分区（三档映射）

    @Test
    fun `三个分区的功能号按名解析 顺序是 主 上 下`() {
        val gw = setup()
        // 触发一次解析即可（ambientZoneFuncs 走的就是按名解析）
        AdaptapiAmbientLightApi { gw }.setEnabled(true)
        assertEquals(
            listOf(mainZonesFunc, topZonesFunc, botZonesFunc),
            AdaptapiConstants.ambientZoneFuncs(),
        )
        assertEquals(
            listOf(
                AdaptapiConstants.FUNC_AMBIENT_MAIN_ZONES,
                AdaptapiConstants.FUNC_AMBIENT_TOP_ZONES,
                AdaptapiConstants.FUNC_AMBIENT_BOT_ZONES,
            ),
            AdaptapiConstants.AMBIENT_ZONE_FUNC_NAMES.toList(),
        )
    }

    @Test
    fun `开灯时主区上区下区都写`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.setEnabled(true))
        val written = gw.writes.map { it.first to it.third }
        // 三个分区都写了，且写的都是 1（开）
        assertTrue(written.contains(mainZonesFunc to 1))
        assertTrue(written.contains(topZonesFunc to 1))
        assertTrue(written.contains(botZonesFunc to 1))
        // 分区一共三条（外加主色与互动效果）
        val zoneWrites = gw.writes.filter { it.first in setOf(mainZonesFunc, topZonesFunc, botZonesFunc) }
        assertEquals(3, zoneWrites.size)
    }

    @Test
    fun `某个分区解析不到就跳过那一个 其余照常写`() {
        // 不猜数值：上区解析不到（覆盖表里没有它）→ 只写主区与下区
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_AMBIENT_MAIN_ZONES to mainZonesFunc,
            AdaptapiConstants.FUNC_AMBIENT_BOT_ZONES to botZonesFunc,
            AdaptapiConstants.FUNC_AMBIENT_INTERACTIVE_EFFECT to effectFunc,
        )
        val gw = FakeGateway()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.setEnabled(true))
        val zoneWrites = gw.writes.filter { it.first in setOf(mainZonesFunc, topZonesFunc, botZonesFunc) }
        assertEquals(listOf(mainZonesFunc, botZonesFunc), zoneWrites.map { it.first })
    }

    @Test
    fun `关灯不动分区`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setEnabled(true)
        gw.writes.clear()
        assertTrue(api.setEnabled(false))
        // 关灯只关互动效果
        assertEquals(listOf(effectFunc to 0), gw.writes.map { it.first to it.third })
    }

    // ------------------------------------------------------------ 亮度（两套 + 0 是 OFF）

    @Test
    fun `行车亮度走行车功能号 LOW 用行车档位常量`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setBrightness(30)
        assertEquals(listOf(drivingBrightnessFunc to 0x200A0701), gw.writes.map { it.first to it.third })
    }

    @Test
    fun `静止亮度走静止功能号 LOW 用静止档位常量`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setStationaryBrightness(30)
        // 必须是静止功能号 + 静止 LOW 常量（不是行车那套）
        assertEquals(listOf(stationaryBrightnessFunc to 0x200A0601), gw.writes.map { it.first to it.third })
    }

    @Test
    fun `静止亮度折算成高低温关三档`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setStationaryBrightness(0)
        api.setStationaryBrightness(30)
        api.setStationaryBrightness(90)
        // 0 → OFF（把灯关掉）；LOW / HIGH 必须是**静止**那一套常量
        assertEquals(listOf(0, 0x200A0601, 0x200A0602), gw.writes.map { it.third })
    }

    @Test
    fun `亮度 0 是 OFF 不是最低档`() {
        // 厂商 `getMappedCbAmlBriStatSettingValue` 把 0 映射成 OFF，
        // LOW 必须传 BRIGHTNESS_STATIONARY_LOW —— 传 0 会把氛围灯关掉。
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setStationaryBrightness(1)
        // 1 不是 0 —— 写下去的必须是 LOW 常量，不能是 0
        assertEquals(0x200A0601, gw.writes[0].third)
        assertFalse(gw.writes.any { it.third == 0 })
    }

    @Test
    fun `行车与静止亮度各写各的功能号 互不覆盖`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setBrightness(90)
        api.setStationaryBrightness(90)
        assertEquals(
            listOf(drivingBrightnessFunc to 0x200A0702, stationaryBrightnessFunc to 0x200A0602),
            gw.writes.map { it.first to it.third },
        )
    }

    @Test
    fun `静止亮度同一档不重复写`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setStationaryBrightness(80)
        api.setStationaryBrightness(85)
        assertEquals(1, gw.writes.size)
    }

    @Test
    fun `静止亮度功能号按名解析不到就不写`() {
        // 覆盖表里没有静止亮度功能号 → 解析不到 → 不写
        AdaptapiConstants.testOverride = emptyMap()
        val gw = FakeGateway()
        val api = AdaptapiAmbientLightApi { gw }
        assertFalse(api.setStationaryBrightness(50))
        assertTrue(gw.writes.isEmpty())
    }

    // ------------------------------------------------------------ 体验模式（CUSTOM 优先于写色）

    @Test
    fun `写自定义色之前先置体验模式 CUSTOM`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.apply(AmbientColor(0x12, 0x34, 0x56, 80)))
        // 体验模式必须在颜色之前
        assertEquals(experienceFunc, gw.writes[0].first)
        assertEquals(0x200A0102, gw.writes[0].third)
        assertEquals(colorFunc, gw.writes[1].first)
        assertEquals(0x123456, gw.writes[1].third)
    }

    @Test
    fun `体验模式一次开灯周期只写一次`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.apply(AmbientColor(0x10, 0x00, 0x00, 80))
        api.apply(AmbientColor(0x20, 0x00, 0x00, 80))
        api.apply(AmbientColor(0x30, 0x00, 0x00, 80))
        assertEquals(1, gw.writes.count { it.first == experienceFunc })
    }

    @Test
    fun `体验模式常量解析不到就不写 但不拦下颜色`() {
        // 覆盖表里没有体验模式的功能号 / 档位常量 → 解析不到 → 不写
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_AMBIENT_COLOR_SET to colorFunc,
        )
        val gw = FakeGateway()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.apply(AmbientColor(0x12, 0x34, 0x56, 80)))
        // 没写体验模式，但颜色照写（颜色才是主功能）
        assertEquals(1, gw.writes.size)
        assertEquals(colorFunc, gw.writes[0].first)
    }
}
