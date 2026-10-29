package com.cardesk.launcher.vehicle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdaptapiBody.writeTrunk] 单测（尾门写入）。
 *
 * 厂商侧证据（`ecarx.adapterapi.jar` 反编译源码）：
 *
 * - 功能号 `IBcm.BCM_FUNC_DOOR` = `553779456`（`0x21020100`）；
 * - 区域 `VehicleDoor.DOOR_REAR` = `536870912`
 *   —— 厂商 `CarFunctionValueImpl` 的 set 分支**写死** `zone == 536870912`，
 *   别的区域直接 `returns false`；
 * - 写信号 `setCbSpeechTargetTrCtrl`
 *   （`CarFunctionValueUtil.getMappedCbSpeechTargetTrCtrlValue`）：
 *   `0` → `CLOSE`、`1` → `OPEN`，**其余一律 `null`**（写不进去），
 *   其中 `553779457`（`IBcm.DOOR_PAUSE`）单独映射成 `STOP`（**暂停**，不是「开」）。
 *
 * 所以这里验三件事：
 * - 区域号**按名解析**，解析不到就**不写**（不猜 `536870912`）；
 * - 写值只有 `0` / `1`；
 * - `553779457` 不在可写值域里（是「暂停」）。
 */
class AdaptapiBodyTrunkWriteTest {

    /** 记录每一次写入的假通道。 */
    private class FakeGateway : VehicleGateway {
        override val id: String = "fake"
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

    /** jar 里的确定值：`VehicleDoor.DOOR_REAR`。 */
    private val doorRearZone = 536870912

    @After
    fun tearDown() {
        AdaptapiConstants.resetForTest()
    }

    private fun setup(): FakeGateway {
        AdaptapiConstants.testOverride = mapOf(
            "DOOR_REAR" to doorRearZone,
            AdaptapiConstants.FUNC_DOOR to AdaptapiBody.BCM_DOOR,
        )
        return FakeGateway()
    }

    // ------------------------------------------------------------ 写入

    @Test
    fun `开尾门写 1 关闭写 0`() {
        val gw = setup()
        assertTrue(AdaptapiBody.writeTrunk(gw, open = true))
        assertTrue(AdaptapiBody.writeTrunk(gw, open = false))
        assertEquals(
            listOf(
                AdaptapiBody.BCM_DOOR to (doorRearZone to 1),
                AdaptapiBody.BCM_DOOR to (doorRearZone to 0),
            ),
            gw.writes.map { it.first to (it.second to it.third) },
        )
        // 常量就是 0 / 1
        assertEquals(1, AdaptapiBody.TRUNK_OPEN)
        assertEquals(0, AdaptapiBody.TRUNK_CLOSE)
    }

    @Test
    fun `写的是 DOOR_REAR 区域 不是别的门区`() {
        val gw = setup()
        AdaptapiBody.writeTrunk(gw, open = true)
        assertEquals(doorRearZone, gw.writes[0].second)
    }

    @Test
    fun `区域号按名解析不到就不写`() {
        // 「不猜数值」：解析不到 `DOOR_REAR` 就不写，不能拿 536870912 顶替
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_DOOR to AdaptapiBody.BCM_DOOR,
        )
        val gw = FakeGateway()
        assertFalse(AdaptapiBody.writeTrunk(gw, open = true))
        assertTrue(gw.writes.isEmpty())
    }

    @Test
    fun `功能号按名解析不到时用 jar 实测值兜底`() {
        // `BCM_FUNC_DOOR` = 553779456 是 jar 里的确定值，不是猜的
        AdaptapiConstants.testOverride = mapOf(
            "DOOR_REAR" to doorRearZone,
        )
        val gw = FakeGateway()
        assertTrue(AdaptapiBody.writeTrunk(gw, open = true))
        assertEquals(AdaptapiBody.BCM_DOOR, gw.writes[0].first)
        assertEquals(553779456, gw.writes[0].first)
    }

    // ------------------------------------------------------------ 值域

    @Test
    fun `553779457 是暂停不是开 不在可写值域里`() {
        // 厂商 `getMappedCbSpeechTargetTrCtrlValue`：
        // 0 → CLOSE、1 → OPEN，553779457 → STOP（暂停），其余 → null（写不进去）。
        // 我们只写 0 / 1 —— 553779457 一次都不该出现。
        val gw = setup()
        AdaptapiBody.writeTrunk(gw, open = true)
        AdaptapiBody.writeTrunk(gw, open = false)
        assertFalse(gw.writes.any { it.third == 553779457 })
        assertEquals(listOf(1, 0), gw.writes.map { it.third })
    }

    @Test
    fun `读侧暂停值与写侧暂停值不是同一个数`() {
        // 读侧把 IDLE / STOP 翻译成 `IBcm.WINDOW_PAUSE` = 553844993，
        // 写侧的 STOP 是 `IBcm.DOOR_PAUSE` = 553779457 —— 厂商两处用了不同常量，
        // 所以不要把读到的值原样回写。
        assertFalse(553844993 == 553779457)
    }
}
