package com.cardesk.launcher.vehicle

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

    /** 假的属性通道：按 (func, zone) 返回预设值。 */
    private class FakeGateway(
        private val values: Map<Pair<Int, Int>, Int>,
    ) : VehicleGateway {
        override val id: String = "fake"
        override val displayName: String = "假通道"
        override var on360State: ((Boolean) -> Unit)? = null
        val readZones = mutableListOf<Int>()

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
}
