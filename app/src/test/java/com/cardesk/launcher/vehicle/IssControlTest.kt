package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发动机启停（ISS）的单测。
 *
 * ## 验的是什么
 *
 * 1. **功能号**：`537002240`（`0x20020100`，
 *    `IVehicle.SETTING_FUNC_ENGINE_STOP_START`），与 jar 和文档一致；
 * 2. **写**：只有 0 / 1 有效（厂商 `getMappedCbSsActivationValue` 只认这两个），
 *    且走的是**两参** `setFunctionValue(func, value)`（无 zone）；
 * 3. **读**：厂商在信号缺失时返回**默认值 1**，
 *    所以「读到 1」分不清「开着」和「读不到」——
 *    只有配合支持探针才能下结论。
 *
 * 第 3 条是这一页最容易写错的地方：UI 上把「读到 1」显示成「启停开着」，
 * 就是在把「读不到」讲成「车机说开着」。
 *
 * 厂商侧对照：
 *
 * | 东西 | 位置 | 值 |
 * | --- | --- | --- |
 * | 读 | `CarFunctionValueImpl.java:429` | `getPaSsActivation` → `getMappedFunctionValue` |
 * | 写 | `CarFunctionValueImpl.java:1247` | `setCbSsActivation` ← `getMappedCbSsActivationValue` |
 * | 信号缺失时的读值 | `CarFunctionValueUtil.java:347` | **返回 1**（默认值） |
 * | 支持状态 | `CarFunctionSupportedUtil.java:356` | 由 `PaSsActivation.availability` 决定 |
 */
class IssControlTest {

    /** 假通道：记录写入、返回预设的读值与支持状态。 */
    private class FakeGateway : VehicleGateway {
        override val id: String = "adaptapi"
        override val displayName: String = "假通道"
        override var on360State: ((Boolean) -> Unit)? = null

        /** 每一次写入：功能号 → 值。 */
        val writes = mutableListOf<Pair<Int, Int>>()

        /** 读回来的原始值（null = 读不到）。 */
        var raw: Int? = null

        /** 固件是否提供这条信号（null = 判不了）。 */
        var supported: Boolean? = true

        /** 写入是否被固件接受。 */
        var acceptWrites: Boolean = true

        override fun connect(onResult: (Boolean) -> Unit) = onResult(true)
        override fun disconnect() = Unit
        override fun isConnected(): Boolean = true
        override fun setHvac(func: Int, zone: Int, value: Int): Boolean = false
        override fun setTemp(zone: Int, celsius: Float): Boolean = false
        override fun readAll(): Map<Int, Int> = emptyMap()
        override fun registerListener(listener: (Int, Int, Int) -> Unit) = Unit
        override fun unregisterListener(listener: (Int, Int, Int) -> Unit) = Unit

        override fun writeFunction(func: Int, value: Int): Boolean {
            writes.add(func to value)
            return acceptWrites
        }

        override fun readFunction(func: Int): Int? = raw

        override fun isFunctionSupported(func: Int, zone: Int): Boolean? = supported
    }

    // ------------------------------------------------------------ 功能号

    @Test
    fun `功能号是 537002240 也就是 0x20020100`() {
        assertEquals(537002240, IssControl.FUNCTION_ENGINE_STOP_START)
        assertEquals(0x20020100, IssControl.FUNCTION_ENGINE_STOP_START)
    }

    @Test
    fun `开与关的取值就是 1 和 0`() {
        assertEquals(1, IssControl.ON)
        assertEquals(0, IssControl.OFF)
    }

    // ------------------------------------------------------------ 写

    @Test
    fun `只有 0 和 1 是可写值`() {
        assertTrue(IssControl.isWritableValue(IssControl.OFF))
        assertTrue(IssControl.isWritableValue(IssControl.ON))
        // 厂商 getMappedCbSsActivationValue 对其它值返回 null → 写直接 false
        assertFalse(IssControl.isWritableValue(2))
        assertFalse(IssControl.isWritableValue(-1))
        assertFalse(IssControl.isWritableValue(253))
    }

    @Test
    fun `关闭启停写的是 0`() {
        val gw = FakeGateway()
        assertTrue(IssControl.write(gw, on = false))
        assertEquals(listOf(IssControl.FUNCTION_ENGINE_STOP_START to 0), gw.writes)
    }

    @Test
    fun `开启启停写的是 1`() {
        val gw = FakeGateway()
        assertTrue(IssControl.write(gw, on = true))
        assertEquals(listOf(IssControl.FUNCTION_ENGINE_STOP_START to 1), gw.writes)
    }

    @Test
    fun `固件拒收时写入返回 false`() {
        val gw = FakeGateway()
        gw.acceptWrites = false
        assertFalse(IssControl.write(gw, on = false))
    }

    // ------------------------------------------------------------ 读：默认值 1 那个坑

    @Test
    fun `读到 0 是确凿的关闭`() {
        // 信号缺失时厂商只会返回 1，能读到 0 就说明真的关了
        assertEquals(false, IssControl.State(supported = true, raw = 0).isOn)
        assertEquals(false, IssControl.State(supported = false, raw = 0).isOn)
        assertEquals(false, IssControl.State(supported = null, raw = 0).isOn)
    }

    @Test
    fun `读到 1 且固件确认有这条信号 才算确凿的开启`() {
        assertEquals(true, IssControl.State(supported = true, raw = 1).isOn)
    }

    @Test
    fun `读到 1 但固件说没这条信号 判不了`() {
        // 这一条最容易写错：厂商 getMappedFunctionValue 在 pa == null 时返回默认值 1，
        // 把它当成「开着」就是把「读不到」讲成了「开着」。
        assertNull(IssControl.State(supported = false, raw = 1).isOn)
    }

    @Test
    fun `读到 1 但探针判不了 也不能算开着`() {
        assertNull(IssControl.State(supported = null, raw = 1).isOn)
    }

    @Test
    fun `读不到就是判不了`() {
        assertNull(IssControl.State(supported = true, raw = null).isOn)
        assertNull(IssControl.State(supported = null, raw = null).isOn)
    }

    @Test
    fun `读到的不是 0 或 1 也判不了`() {
        assertNull(IssControl.State(supported = true, raw = 253).isOn)
        assertNull(IssControl.State(supported = true, raw = 255).isOn)
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `判不了的时候文案不能说成开着`() {
        assertEquals("启停已关闭", IssControl.State(supported = true, raw = 0).label())
        assertEquals("启停开着", IssControl.State(supported = true, raw = 1).label())
        assertEquals("启停状态读不到", IssControl.State(supported = false, raw = 1).label())
        assertEquals("启停状态读不到", IssControl.State(supported = true, raw = null).label())
    }

    // ------------------------------------------------------------ 读：走通道

    @Test
    fun `读的时候支持探针与原始值一起拿`() {
        val gw = FakeGateway()
        gw.supported = true
        gw.raw = 0
        val state = IssControl.read(gw)
        assertEquals(true, state.supported)
        assertEquals(0, state.raw)
        assertEquals(false, state.isOn)
    }

    @Test
    fun `读不到原始值时状态是判不了`() {
        val gw = FakeGateway()
        gw.supported = true
        gw.raw = null
        val state = IssControl.read(gw)
        assertNull(state.raw)
        assertNull(state.isOn)
    }
}
