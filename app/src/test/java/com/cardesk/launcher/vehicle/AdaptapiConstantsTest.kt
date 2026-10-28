package com.cardesk.launcher.vehicle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdaptapiConstants] 单测。
 *
 * 这一层的意义是：**按名字找常量，解析不到就返回 null，绝不猜数值**。
 * 真机上这些常量来自车机 jar（单测里造不出来），所以用 [AdaptapiConstants.testOverride]
 * 作为接缝，同时测「解析器本身」的行为。
 */
class AdaptapiConstantsTest {

    @After
    fun tearDown() {
        AdaptapiConstants.resetForTest()
    }

    // ------------------------------------------------------------ 覆盖接缝

    @Test
    fun `覆盖值优先于真机解析`() {
        AdaptapiConstants.testOverride = mapOf(
            "DOOR_ROW_1_LEFT" to 11,
            "DOOR_ROW_1_RIGHT" to 12,
        )
        assertEquals(11, AdaptapiConstants.resolve("DOOR_ROW_1_LEFT"))
        assertEquals(12, AdaptapiConstants.resolve("DOOR_ROW_1_RIGHT"))
    }

    @Test
    fun `覆盖表里没有的名字解析不到`() {
        AdaptapiConstants.testOverride = mapOf("DOOR_ROW_1_LEFT" to 11)
        assertNull(AdaptapiConstants.resolve("DOOR_ROW_1_RIGHT"))
    }

    // ------------------------------------------------------------ 按名解析

    @Test
    fun `门区按名解析 顺序是 前左 前右 后左 后右 机盖 后备箱`() {
        AdaptapiConstants.testOverride = mapOf(
            "DOOR_ROW_1_LEFT" to 1,
            "DOOR_ROW_1_RIGHT" to 2,
            "DOOR_ROW_2_LEFT" to 4,
            "DOOR_ROW_2_RIGHT" to 8,
            "DOOR_HOOD" to 16,
            "DOOR_REAR" to 32,
        )
        assertEquals(
            listOf("DOOR_ROW_1_LEFT", "DOOR_ROW_1_RIGHT", "DOOR_ROW_2_LEFT", "DOOR_ROW_2_RIGHT", "DOOR_HOOD", "DOOR_REAR"),
            AdaptapiConstants.DOOR_ZONE_NAMES.toList(),
        )
        assertEquals(listOf(1, 2, 4, 8, 16, 32), AdaptapiConstants.doorZones())
        assertEquals(16, AdaptapiConstants.hoodZone())
        assertEquals(32, AdaptapiConstants.trunkZone())
    }

    @Test
    fun `窗区按名解析 只有四扇侧窗`() {
        AdaptapiConstants.testOverride = mapOf(
            "WINDOW_ROW_1_LEFT" to 3,
            "WINDOW_ROW_1_RIGHT" to 6,
            "WINDOW_ROW_2_LEFT" to 12,
            "WINDOW_ROW_2_RIGHT" to 24,
        )
        assertEquals(listOf(3, 6, 12, 24), AdaptapiConstants.windowZones())
    }

    @Test
    fun `全部解析不到时整组返回 null`() {
        AdaptapiConstants.testOverride = emptyMap()
        assertNull(AdaptapiConstants.doorZones())
        assertNull(AdaptapiConstants.windowZones())
        assertNull(AdaptapiConstants.hoodZone())
        assertNull(AdaptapiConstants.trunkZone())
    }

    @Test
    fun `部分解析不到时 那一项留 null 其余照常`() {
        // 「逐项独立」：一台车只注册了三个门位区域时，第四个是「读不到」，
        // 而不是把整组作废。
        AdaptapiConstants.testOverride = mapOf(
            "DOOR_ROW_1_LEFT" to 1,
            "DOOR_ROW_1_RIGHT" to 2,
            "DOOR_ROW_2_LEFT" to 4,
        )
        val zones = AdaptapiConstants.doorZones()
        assertEquals(6, zones!!.size)
        assertEquals(1, zones[0])
        assertEquals(4, zones[2])
        assertNull(zones[3])
        assertNull(zones[4])
        assertNull(zones[5])
    }

    @Test
    fun `功能号按名解析 解析不到时不猜`() {
        AdaptapiConstants.testOverride = mapOf(AdaptapiConstants.FUNC_WINDOW to 553_780_000)
        assertEquals(553_780_000, AdaptapiConstants.funcId(AdaptapiConstants.FUNC_WINDOW))
        // 没有兜底 → null（不是 0、也不是猜一个）
        assertNull(AdaptapiConstants.funcId(AdaptapiConstants.FUNC_WINDOW_POS))
        // 有兜底 → 用兜底
        assertEquals(553779968, AdaptapiConstants.funcId(AdaptapiConstants.FUNC_DOOR_POS, 553779968))
    }

    // ------------------------------------------------------------ 解析器本身

    @Test
    fun `注册根类后能按名解析到常量`() {
        // 真机路径：`AdaptapiGateway` 拿到 `ICarFunction` 句柄时注册它的类，
        // 常量就在同一个 jar 里 —— 从根类出发按名字找。
        AdaptapiConstants.registerRoot(FakeConstants::class.java)
        assertEquals(7, AdaptapiConstants.resolve("DOOR_ROW_1_LEFT"))
    }

    @Test
    fun `找不到的常量返回 null 而不是 0`() {
        assertNull(AdaptapiConstants.lookupIn(FakeConstants::class.java, "NOT_EXIST"))
        assertNull(AdaptapiConstants.resolve("NOT_EXIST"))
    }

    @Test
    fun `名字表与文档口径一致`() {
        assertEquals("BCM_FUNC_DOOR_POS", AdaptapiConstants.FUNC_DOOR_POS)
        assertEquals("BCM_FUNC_WINDOW", AdaptapiConstants.FUNC_WINDOW)
        assertEquals("BCM_FUNC_WINDOW_POS", AdaptapiConstants.FUNC_WINDOW_POS)
        assertTrue(AdaptapiConstants.DOOR_ZONE_NAMES.contains("DOOR_HOOD"))
        assertTrue(AdaptapiConstants.DOOR_ZONE_NAMES.contains("DOOR_REAR"))
    }

    /** 假常量类（用来测解析器）。 */
    private object FakeConstants {
        const val DOOR_ROW_1_LEFT = 7
    }
}
