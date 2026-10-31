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
    private val topZonesFunc = 0x200A0400
    private val botZonesFunc = 0x200A0300

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
            AdaptapiConstants.FUNC_AMBIENT_TOP_ZONES to topZonesFunc,
            AdaptapiConstants.FUNC_AMBIENT_BOT_ZONES to botZonesFunc,
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

    // ------------------------------------------------------------ 分区开关（V2）

    @Test
    fun `分区可以单独开关`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        assertTrue(api.setZoneEnabled(AmbientLightApi.ZONE_TOP, true))
        assertEquals(listOf(topZonesFunc to 1), gw.writes.map { it.first to it.third })
    }

    @Test
    fun `分区同一状态不重复写`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.setZoneEnabled(AmbientLightApi.ZONE_BOTTOM, true)
        api.setZoneEnabled(AmbientLightApi.ZONE_BOTTOM, true)
        assertEquals(1, gw.writes.size)
    }

    @Test
    fun `分区功能号解析不到就不写`() {
        val gw = FakeGateway()
        AdaptapiConstants.testOverride = emptyMap()
        val api = AdaptapiAmbientLightApi { gw }
        assertFalse(api.setZoneEnabled(AmbientLightApi.ZONE_TOP, true))
        assertTrue(gw.writes.isEmpty())
    }

    @Test
    fun `越界的分区号被拒绝`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        assertFalse(api.setZoneEnabled(-1, true))
        assertFalse(api.setZoneEnabled(99, true))
        assertTrue(gw.writes.isEmpty())
    }

    // ------------------------------------------------------------ ΔE00 限速（V2）

    @Test
    fun `颜色跳变很大时会被 ΔE00 限速拉回`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi(maxDeltaE = 1.8f) { gw }
        val first = AmbientColor(100, 100, 100, 50)
        val candidate = AmbientColor(200, 200, 200, 50)
        assertTrue(api.apply(first))
        // 写入节流是 50ms —— 得等一下才能写第二帧
        Thread.sleep(80)
        assertTrue(api.apply(candidate))

        assertEquals(2, gw.writes.size)
        val written = gw.writes[1].third
        val writtenColor = AmbientColor(
            (written shr 16) and 0xFF,
            (written shr 8) and 0xFF,
            written and 0xFF,
            50,
        )
        // 原始色差（第一帧 → 候选色）
        val rawDe = AmbientColorSpace.deltaE00(
            AmbientColorSpace.rgbToLab(first.r, first.g, first.b),
            AmbientColorSpace.rgbToLab(candidate.r, candidate.g, candidate.b),
        )
        // 实际写下去的色差（第一帧 → 写下去的颜色）
        val writtenDe = AmbientColorSpace.deltaE00(
            AmbientColorSpace.rgbToLab(first.r, first.g, first.b),
            AmbientColorSpace.rgbToLab(writtenColor.r, writtenColor.g, writtenColor.b),
        )
        assertTrue(
            "限速后色差该远小于原始色差（$writtenDe vs $rawDe）",
            writtenDe < rawDe / 4,
        )
    }

    @Test
    fun `限速后的颜色与上一帧的色差不超过上限`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi(maxDeltaE = 1.8f) { gw }
        var prev: Triple<Float, Float, Float>? = null
        val steps = listOf(0, 60, 120, 180, 240, 300, 255)
        for (v in steps) {
            Thread.sleep(80)
            api.apply(AmbientColor(v, v, v, 50))
            val color = gw.writes.lastOrNull() ?: continue
            val lab = AmbientColorSpace.rgbToLab(
                (color.third shr 16) and 0xFF,
                (color.third shr 8) and 0xFF,
                color.third and 0xFF,
            )
            prev?.let {
                val de = AmbientColorSpace.deltaE00(it, lab)
                assertTrue("色差 $de 超过上限", de <= 1.8f + 0.05f)
            }
            prev = lab
        }
        assertTrue("限速器该介入过", api.deltaELimitedCount > 0)
    }

    @Test
    fun `限速器介入次数可统计`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi(maxDeltaE = 1.8f) { gw }
        assertEquals(0, api.deltaELimitedCount)
        api.apply(AmbientColor(0, 0, 0, 50))
        Thread.sleep(80)
        api.apply(AmbientColor(255, 255, 255, 50))
        assertTrue("该介入过（实际 ${api.deltaELimitedCount}）", api.deltaELimitedCount >= 1)
    }

    // ------------------------------------------------------------ 写入节流（V2）

    @Test
    fun `写入节流是 50ms`() {
        // 10Hz（100ms）会把平滑重新切成台阶 —— V2 改成 20Hz。
        assertEquals(50L, AdaptapiAmbientLightApi.MIN_INTERVAL_MS)
    }

    // ------------------------------------------------------------ 限速开关（V2 / 旧版）

    @Test
    fun `旧版链路关掉限速后颜色原样写入`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi(maxDeltaE = 1.8f) { gw }
        // 旧版是现网验证过的链路（10Hz 硬跳变），限速对它要关掉 ——
        // 否则旧版会被压成一条几乎不动的慢坡，A/B 就失去意义了。
        api.setDeltaELimitEnabled(false)
        assertTrue(api.apply(AmbientColor(100, 100, 100, 50)))
        Thread.sleep(80)
        assertTrue(api.apply(AmbientColor(200, 200, 200, 50)))
        assertEquals(2, gw.writes.size)
        val written = gw.writes[1].third
        assertEquals(200, (written shr 16) and 0xFF)
        assertEquals(200, (written shr 8) and 0xFF)
        assertEquals(200, written and 0xFF)
        assertEquals("限速器不该介入", 0, api.deltaELimitedCount)
    }

    @Test
    fun `关掉限速再打开后基准被清掉`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi(maxDeltaE = 1.8f) { gw }
        assertTrue(api.apply(AmbientColor(100, 100, 100, 50)))
        // 切引擎时基准要清掉 —— 两条链路的颜色不是一个量纲。
        api.setDeltaELimitEnabled(false)
        api.setDeltaELimitEnabled(true)
        Thread.sleep(80)
        assertTrue(api.apply(AmbientColor(255, 255, 255, 50)))
        // 基准被清过 ⇒ 这一帧不受上一帧的约束，可以原样写入。
        val written = gw.writes.last().third
        assertEquals(255, (written shr 16) and 0xFF)
    }

    @Test
    fun `默认是开限速的`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi(maxDeltaE = 1.8f) { gw }
        assertTrue(api.apply(AmbientColor(100, 100, 100, 50)))
        Thread.sleep(80)
        assertTrue(api.apply(AmbientColor(200, 200, 200, 50)))
        assertTrue("默认该限速", api.deltaELimitedCount > 0)
    }

    @Test
    fun `节流窗口内的第二次写入被跳过`() {
        val gw = setup()
        val api = AdaptapiAmbientLightApi { gw }
        api.apply(AmbientColor(0, 0, 0, 50))
        // 立刻再写一次 —— 应该被节流挡掉
        api.apply(AmbientColor(255, 255, 255, 50))
        assertEquals(1, gw.writes.size)
    }
}
