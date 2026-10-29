package com.cardesk.launcher.core.ambient

import com.cardesk.launcher.vehicle.AdaptapiConstants
import com.cardesk.launcher.vehicle.VehicleGateway
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdaptapiAmbientLightApi] 单测（第七十一轮）。
 *
 * 验的是三件事：
 * - 颜色按 `0xRRGGBB` 打包（厂商那边就是拿 `Color.red/green/blue` 拆的）；
 * - 亮度折算成厂商的三档（高 / 低 / 关）；
 * - 写频有节流（频谱每帧都来，不能一帧一写）。
 */
class AdaptapiAmbientLightApiTest {

    /** 记录每一次写入的假通道。 */
    private class FakeGateway : VehicleGateway {
        override val id: String = "adaptapi"
        override val displayName: String = "假通道"
        override var on360State: ((Boolean) -> Unit)? = null
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

    private val colorFunc = 0x200A0900
    private val mainColorFunc = 0x200A0200
    private val effectFunc = 0x200A0800
    private val brightnessFunc = 0x200A0700
    private val zonesFunc = 0x200A0500

    @After
    fun tearDown() {
        AdaptapiConstants.resetForTest()
    }

    private fun setup(): FakeGateway {
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_AMBIENT_COLOR_SET to colorFunc,
            AdaptapiConstants.FUNC_AMBIENT_MAIN_COLOR to mainColorFunc,
            AdaptapiConstants.FUNC_AMBIENT_INTERACTIVE_EFFECT to effectFunc,
            AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_DRIVING to brightnessFunc,
            AdaptapiConstants.FUNC_AMBIENT_MAIN_ZONES to zonesFunc,
            AdaptapiConstants.AMBIENT_MAIN_COLOR_SET_COLOR to 0x200A0203,
            AdaptapiConstants.AMBIENT_BRIGHTNESS_HIGH to 0x200A0702,
            AdaptapiConstants.AMBIENT_BRIGHTNESS_LOW to 0x200A0701,
        )
        return FakeGateway()
    }

    @Test
    fun `颜色按 RRGGBB 打包下发`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.apply(AmbientColor(0x12, 0x34, 0x56, 80)))
        assertEquals(1, gw.writes.size)
        val (func, zone, value) = gw.writes[0]
        assertEquals(colorFunc, func)
        assertEquals(0, zone)
        assertEquals(0x123456, value)
    }

    @Test
    fun `颜色分量越界会被夹住`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.apply(AmbientColor(300, -5, 0x56, 50))
        assertEquals(0xFF0056, gw.writes[0].third)
    }

    @Test
    fun `亮度折算成高低温关三档`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setBrightness(0)
        api.setBrightness(30)
        api.setBrightness(90)
        assertEquals(listOf(0, 0x200A0701, 0x200A0702), gw.writes.map { it.third })
    }

    @Test
    fun `亮度同一档不重复写`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setBrightness(80)
        api.setBrightness(85)
        assertEquals(1, gw.writes.size)
    }

    @Test
    fun `开灯会写主色与音乐律动`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.setEnabled(true))
        val values = gw.writes.map { it.first to it.third }
        // 主色 = 自定义色
        assertTrue(values.contains(mainColorFunc to 0x200A0203))
        // 主区域点亮
        assertTrue(values.contains(zonesFunc to 1))
        // 互动效果（音乐律动）= 开
        assertTrue(values.contains(effectFunc to 1))
    }

    @Test
    fun `关灯只关互动效果`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.setEnabled(false))
        assertEquals(listOf(effectFunc to 0), gw.writes.map { it.first to it.third })
    }

    @Test
    fun `通道没就绪就什么都不写`() {
        val api = AdaptapiAmbientLightApi { null }
        assertFalse(api.isAvailable())
        assertFalse(api.apply(AmbientColor(1, 2, 3, 50)))
        assertFalse(api.setEnabled(true))
        assertFalse(api.setBrightness(50))
    }

    @Test
    fun `功能号按名解析不到就不写`() {
        val gw = FakeGateway()
        // 不给任何覆盖值 → 按名解析不到 → 不写
        AdaptapiConstants.testOverride = emptyMap()
        val api = AdaptapiAmbientLightApi { gw }
        assertFalse(api.apply(AmbientColor(1, 2, 3, 50)))
        assertTrue(gw.writes.isEmpty())
    }
}
