package com.cardesk.launcher.vehicle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdaptapiBody] 单测（纯逻辑，不依赖 Android 运行时）。
 *
 *
 * - （整体值 → 逐门位聚合）
 * - （原始值 → 开闭）
 * - `DOOR_ZONES = {1, 4, 16, 64}`
 */
class AdaptapiBodyTest {

    @After
    fun tearDown() {
        // 单测里塞的「按名解析」覆盖值不能漏到别的用例去
        AdaptapiConstants.resetForTest()
    }

    /** 假的属性通道：按 (func, zone) 返回预设值。 */
    private class FakeGateway(
        private val values: Map<Pair<Int, Int>, Int>,
        /** 固件支持探针的预设（null = 固件没有这个方法，判不了）。 */
        private val supported: Map<Pair<Int, Int>, Boolean>? = null,
        /** 浮点通道（`getCustomizeFunctionValue`）的预设；null = 浮点通道不可用。 */
        private val customizeValues: Map<Pair<Int, Int>, Int>? = null,
    ) : VehicleGateway {
        override val id: String = "fake"
        override val displayName: String = "假通道"
        override var on360State: ((Boolean) -> Unit)? = null
        val readZones = mutableListOf<Int>()
        val readCustomizeZones = mutableListOf<Int>()

        override fun connect(onResult: (Boolean) -> Unit) = onResult(true)
        override fun disconnect() = Unit
        override fun isConnected(): Boolean = true
        override fun setHvac(func: Int, zone: Int, value: Int): Boolean = false
        override fun setTemp(zone: Int, celsius: Float): Boolean = false
        override fun readAll(): Map<Int, Int> = emptyMap()
        override fun registerListener(listener: (Int, Int, Int) -> Unit) = Unit
        override fun unregisterListener(listener: (Int, Int, Int) -> Unit) = Unit

        override fun readRawFunctionValue(func: Int, zone: Int): Int? {
            readZones.add(zone)
            return values[func to zone]
        }

        override fun readCustomizeFunctionValue(func: Int, zone: Int): Int? {
            readCustomizeZones.add(zone)
            return customizeValues?.get(func to zone)
        }

        override fun isFunctionSupported(func: Int, zone: Int): Boolean? =
            supported?.get(func to zone)
    }

    private fun gw(vararg pairs: Pair<Int, Int>): FakeGateway =
        FakeGateway(pairs.map { (z, v) -> (AdaptapiBody.BCM_DOOR_POS to z) to v }.toMap())

    // ------------------------------------------------------------ interpretClosed

    @Test
    fun `interpretClosed 逐条对齐`() {
        // 0 → 关；1 → 开
        assertEquals(true, CarSignalMath.interpretClosed(0))
        assertEquals(false, CarSignalMath.interpretClosed(1))
        // 里明确列出的三个「开」
        assertEquals(false, CarSignalMath.interpretClosed(553844994))
        assertEquals(false, CarSignalMath.interpretClosed(553844993))
        assertEquals(false, CarSignalMath.interpretClosed(553779457))
        // (1, 100] → <= 5 关，其余开
        assertEquals(true, CarSignalMath.interpretClosed(2))
        assertEquals(true, CarSignalMath.interpretClosed(5))
        assertEquals(false, CarSignalMath.interpretClosed(6))
        assertEquals(false, CarSignalMath.interpretClosed(100))
        // 读不出来
        assertNull(CarSignalMath.interpretClosed(-1))
        assertNull(CarSignalMath.interpretClosed(101))
        assertNull(CarSignalMath.interpretClosed(553779968))
    }

    // ------------------------------------------------------------ adaptPosAllClosed

    @Test
    fun `整体值优先于逐门位`() {
        // adaptPosAllClosed:180-185 —— 整体值（zone 0）能解释时**直接返回**
        // 根本不再看逐门位。
        val g = gw(
            0 to 0,              // 整体值 = 0 → 关闭
            1 to 0, 4 to 0, 16 to 1, 64 to 0,   // 但 16 号门位是「开」
        )
        val state = AdaptapiBody.readDoors(g)
        assertEquals(true, state.allClosed)
        // 明细仍然是四个门各自的值（车辆页显示用）
        assertEquals(4, state.eachClosed?.size)
        assertEquals(false, state.eachClosed!![2])
    }

    @Test
    fun `整体值说是开着就整体算开`() {
        val g = gw(
            0 to 1,              // 整体值 = 1 → 打开
            1 to 0, 4 to 0, 16 to 0, 64 to 0,
        )
        val state = AdaptapiBody.readDoors(g)
        assertEquals(false, state.allClosed)
    }

    @Test
    fun `整体值读不出来时用逐门位聚合`() {
        // zone 0 读不到、zone MIN_VALUE 读不到 → 走逐门位
        val g = gw(1 to 0, 4 to 0, 16 to 0, 64 to 0)
        val state = AdaptapiBody.readDoors(g)
        assertEquals(true, state.allClosed)
        assertEquals(true, state.eachClosed!!.all { it == true })
    }

    @Test
    fun `逐门位里有一个开着就整体算开`() {
        val g = gw(1 to 0, 4 to 1, 16 to 0, 64 to 0)
        val state = AdaptapiBody.readDoors(g)
        assertEquals(false, state.allClosed)
        assertEquals(false, state.eachClosed!![1])
    }

    @Test
    fun `读不到就是读不到 不当成关着`() {
        // 这是真机「车门状态一直显示关」的关键：读不出来必须返回 null
        val g = FakeGateway(emptyMap())
        val state = AdaptapiBody.readDoors(g)
        assertNull(state.allClosed)
        assertNull(state.eachClosed)
    }

    @Test
    fun `门位明细有一个读不出来 只丢那一扇`() {
        // 第四十五轮：改成**逐门独立**（对齐另一个）。
        // 之前是「缺一个就整组作废」，连另外三扇门的正确状态一起丢掉。
        val g = gw(1 to 0, 4 to 0, 16 to 0)   // 缺 64
        val state = AdaptapiBody.readDoors(g)
        assertEquals(listOf(true, true, true, null), state.eachClosed)
        // 聚合仍然可用（三个门位都能解释）
        assertEquals(true, state.allClosed)
    }

    @Test
    fun `无法解释的原始值不参与判定`() {
        // 101 不在可解释范围内 → 这个门位读不出来
        val g = gw(1 to 0, 4 to 0, 16 to 101, 64 to 0)
        val state = AdaptapiBody.readDoors(g)
        // 逐门独立：只有那一扇是「读不到」，其余三扇照常
        assertEquals(listOf(true, true, null, true), state.eachClosed)
        assertEquals(true, state.allClosed)
    }

    @Test
    fun `门位 zone 顺序符合预期`() {
        assertEquals(listOf(1, 4, 16, 64), AdaptapiBody.DOOR_ZONES.toList())
        assertEquals(553779968, AdaptapiBody.BCM_DOOR_POS)
        assertEquals(4, AdaptapiBody.DOOR_NAMES.size)
    }

    @Test
    fun `整体值探针用的两个 zone 符合预期`() {
        assertEquals(0, AdaptapiBody.ZONE_AGGREGATE)
        assertEquals(Int.MIN_VALUE, AdaptapiBody.ZONE_NONE)
        val g = gw()
        AdaptapiBody.readDoors(g)
        assertTrue(g.readZones.contains(AdaptapiBody.ZONE_NONE))
        assertTrue(g.readZones.contains(AdaptapiBody.ZONE_AGGREGATE))
        assertFalse(g.readZones.isEmpty())
    }

    // ------------------------------------------------------------ 按名解析的 zone（第六十六轮）

    @Test
    fun `按名解析出的 zone 优先于老约定`() {
        // 真机根因：老约定 {1,4,16,64} 在这台车上全部返回 255（不支持），
        // 而 jar 里的门区是另一套编号 —— 所以按名解析出来的 zone 要**先试**。
        AdaptapiConstants.testOverride = mapOf(
            "DOOR_ROW_1_LEFT" to 9,
            "DOOR_ROW_1_RIGHT" to 10,
            "DOOR_ROW_2_LEFT" to 11,
            "DOOR_ROW_2_RIGHT" to 12,
        )
        val g = FakeGateway(
            mapOf(
                (AdaptapiBody.BCM_DOOR_POS to 9) to 0,
                (AdaptapiBody.BCM_DOOR_POS to 10) to 1,
                (AdaptapiBody.BCM_DOOR_POS to 11) to 0,
                (AdaptapiBody.BCM_DOOR_POS to 12) to 0,
            )
        )
        val state = AdaptapiBody.readDoors(g)
        assertEquals(listOf(true, false, true, true), state.eachClosed)
        assertEquals(false, state.allClosed)
    }

    @Test
    fun `按名解析出的 zone 读不到时退回老约定`() {
        AdaptapiConstants.testOverride = mapOf("DOOR_ROW_1_LEFT" to 9)
        val g = gw(1 to 0)
        val state = AdaptapiBody.readDoors(g)
        // 老约定 zone 1 仍能读到 → 不因为新 zone 读不到就整扇作废
        assertEquals(true, state.eachClosed?.get(0))
    }

    @Test
    fun `固件说不支持就不读那个 zone`() {
        AdaptapiConstants.testOverride = mapOf("DOOR_ROW_1_LEFT" to 9)
        val g = FakeGateway(
            values = mapOf((AdaptapiBody.BCM_DOOR_POS to 1) to 0),
            supported = mapOf((AdaptapiBody.BCM_DOOR_POS to 9) to false),
        )
        AdaptapiBody.readDoors(g)
        // 明确不支持的 zone 不该去读
        assertFalse(g.readZones.contains(9))
        // 老约定仍然读
        assertTrue(g.readZones.contains(1))
    }

    @Test
    fun `255 判为不支持 不当成门开着`() {
        val g = gw(1 to 255, 4 to 0, 16 to 0, 64 to 0)
        val state = AdaptapiBody.readDoors(g)
        // 左前门读不出来，其余三扇照常
        assertEquals(listOf(null, true, true, true), state.eachClosed)
        assertEquals(true, state.allClosed)
    }

    // ------------------------------------------------------------ 车窗（第七十一轮改口径）

    @Test
    fun `车窗走浮点通道 逐扇读开度`() {
        // 第七十一轮：逐扇开度只在 `getCustomizeFunctionValue` 里实现，
        // 整型通道对同一个功能号返回 -1。
        val func = 777_000
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_WINDOW_POS to func,
            "WINDOW_ROW_1_LEFT" to 21,
            "WINDOW_ROW_1_RIGHT" to 22,
            "WINDOW_ROW_2_LEFT" to 23,
            "WINDOW_ROW_2_RIGHT" to 24,
        )
        val g = FakeGateway(
            values = emptyMap(),
            customizeValues = mapOf(
                (func to 21) to 60,   // 开度 60 → 开
                (func to 22) to 0,    // 开度 0  → 关
                (func to 23) to 1,    // 开度 1  → 开
                (func to 24) to 0,    // 开度 0  → 关
            ),
        )
        val flags = AdaptapiBody.readWindowsEach(g)
        assertEquals(listOf(true, false, true, false), flags)
        // 只走浮点通道，不碰整型通道
        assertTrue(g.readZones.isEmpty())
        assertEquals(listOf(21, 22, 23, 24), g.readCustomizeZones)
    }

    @Test
    fun `车窗功能号解析不到就用 jar 实测值兜底`() {
        // 「不猜数值」：兜底值是车机端 jar 里的**确定值**（`BCM_FUNC_WINDOW_POS`），
        // 不是猜的 —— 猜一个数去读别的功能比读不到更糟。
        AdaptapiConstants.testOverride = mapOf(
            "WINDOW_ROW_1_LEFT" to 21,
        )
        val g = FakeGateway(
            values = emptyMap(),
            customizeValues = mapOf(
                (AdaptapiBody.BCM_WINDOW_POS to 21) to 30,
            ),
        )
        val flags = AdaptapiBody.readWindowsEach(g)
        assertEquals(listOf(true, null, null, null), flags)
    }

    @Test
    fun `车窗区域号解析不到就用 jar 实测值兜底`() {
        // 兜底区号 = `VehicleWindow` 的确定值 16/32/256/512
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_WINDOW_POS to 777_000,
        )
        val g = FakeGateway(
            values = emptyMap(),
            customizeValues = mapOf(
                (777_000 to 16) to 10,
                (777_000 to 32) to 0,
            ),
        )
        val flags = AdaptapiBody.readWindowsEach(g)
        assertEquals(listOf(true, false, null, null), flags)
        assertEquals(listOf(16, 32, 256, 512), g.readCustomizeZones)
    }

    @Test
    fun `车窗读不到的那一扇留 null 其余照常`() {
        val func = 777_000
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_WINDOW_POS to func,
            "WINDOW_ROW_1_LEFT" to 21,
            "WINDOW_ROW_1_RIGHT" to 22,
            "WINDOW_ROW_2_LEFT" to 23,
            "WINDOW_ROW_2_RIGHT" to 24,
        )
        val g = FakeGateway(
            values = emptyMap(),
            customizeValues = mapOf((func to 21) to 40),
        )
        val flags = AdaptapiBody.readWindowsEach(g)
        assertEquals(listOf(true, null, null, null), flags)
    }

    @Test
    fun `车窗异常值 -1 算读不到 不算开`() {
        // 厂商把「异常（ABNORMAL）」也翻译成 -1 —— 那是「读不到」，不是「关着」。
        val func = 777_000
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_WINDOW_POS to func,
            "WINDOW_ROW_1_LEFT" to 21,
        )
        val g = FakeGateway(
            values = emptyMap(),
            customizeValues = mapOf((func to 21) to -1),
        )
        assertNull(AdaptapiBody.readWindowsEach(g))
    }

    // ------------------------------------------------------------ 车门探针（第七十一轮）

    @Test
    fun `固件说整档都不支持时一次都不探`() {
        // 这台车机（领克 dcy11）的固件没实现 `BCM_FUNC_DOOR_POS`，
        // 支持探针会判成「不支持」—— 这时不该再去探（诊断包里那 3536 条日志的来源）。
        val g = FakeGateway(
            values = mapOf(
                (AdaptapiBody.BCM_DOOR_POS to 1) to 0,
                (AdaptapiBody.BCM_DOOR_POS to 4) to 0,
                (AdaptapiBody.BCM_DOOR_POS to 16) to 0,
                (AdaptapiBody.BCM_DOOR_POS to 64) to 0,
            ),
            supported = mapOf(
                (AdaptapiBody.BCM_DOOR_POS to 1) to false,
                (AdaptapiBody.BCM_DOOR_POS to 4) to false,
                (AdaptapiBody.BCM_DOOR_POS to 16) to false,
                (AdaptapiBody.BCM_DOOR_POS to 64) to false,
            ),
        )
        val state = AdaptapiBody.readDoors(g)
        assertNull(state.eachClosed)
        assertNull(state.allClosed)
        // 四个门位一个都没探
        assertFalse(g.readZones.contains(1))
        assertFalse(g.readZones.contains(4))
        assertFalse(g.readZones.contains(16))
        assertFalse(g.readZones.contains(64))
    }

    // ------------------------------------------------------------ 口径

    @Test
    fun `interpretWindowPosition 开度口径`() {
        // 0 = 关；> 0 = 开；< 0 = 读不到（厂商把「异常」也翻译成 -1）
        assertEquals(false, CarSignalMath.interpretWindowPosition(0))
        assertEquals(true, CarSignalMath.interpretWindowPosition(1))
        assertEquals(true, CarSignalMath.interpretWindowPosition(100))
        assertEquals(true, CarSignalMath.interpretWindowPosition(127))
        assertNull(CarSignalMath.interpretWindowPosition(-1))
    }

    @Test
    fun `interpretLatchClosed 只认 0 关 1 开`() {
        // 厂商把后备箱的 IDLE / STOP 也映射到 553844993 —— 那是「不确定」，不是「关」
        assertEquals(true, CarSignalMath.interpretLatchClosed(0))
        assertEquals(false, CarSignalMath.interpretLatchClosed(1))
        assertNull(CarSignalMath.interpretLatchClosed(553844993))
        assertNull(CarSignalMath.interpretLatchClosed(-1))
        assertNull(CarSignalMath.interpretLatchClosed(2))
    }
}
