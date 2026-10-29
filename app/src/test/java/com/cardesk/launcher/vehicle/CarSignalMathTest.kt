package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarSignalMath] 单测（纯逻辑，不依赖 Android）。
 *
 * 口径
 * - 续航
 * - 油量
 * - 环温
 */
class CarSignalMathTest {

    @Test
    fun `续航：小于等于 3000 直接当公里数`() {
        assertEquals(386, CarSignalMath.rangeKm(386f))
        assertEquals(1, CarSignalMath.rangeKm(1f))
        // 要求结果严格小于 3000
        assertNull(CarSignalMath.rangeKm(3000f))
    }

    @Test
    fun `续航：大于 3000 先除以 1000`() {
        assertEquals(386, CarSignalMath.rangeKm(386000f))
        assertEquals(451, CarSignalMath.rangeKm(450500f))
    }

    @Test
    fun `续航：非法值一律读不出来`() {
        assertNull(CarSignalMath.rangeKm(0f))
        assertNull(CarSignalMath.rangeKm(-5f))
        assertNull(CarSignalMath.rangeKm(Float.NaN))
        assertNull(CarSignalMath.rangeKm(Float.MIN_VALUE))
        // 结果必须落在 1..2999
        assertNull(CarSignalMath.rangeKm(3000000f))
    }

    @Test
    fun `油量：容量已知时按容量换算百分比`() {
        // 油箱 45L（45000ml），剩 22.5L（22500ml）→ 50%
        assertEquals(50, CarSignalMath.fuelPercent(22500f, 45000f))
        assertEquals(100, CarSignalMath.fuelPercent(45000f, 45000f))
        assertEquals(0, CarSignalMath.fuelPercent(0f, 45000f))
    }

    @Test
    fun `油量：容量未知时原始值当百分比`() {
        assertEquals(62, CarSignalMath.fuelPercent(62f, 0f))
        // 超出 0..100 → 读不出来
        assertNull(CarSignalMath.fuelPercent(120f, 0f))
        assertNull(CarSignalMath.fuelPercent(-1f, 0f))
    }

    @Test
    fun `油量：容量很小时先除以 1000 再当百分比`() {
        // 容量 0.05，原始值 30 → 30/1000 = 0.03 → 0%
        assertEquals(0, CarSignalMath.fuelPercent(30f, 0.05f))
    }

    @Test
    fun `油量：容量小时原始值也直接当百分比`() {
        // 容量 <= 200 且 raw <= 2*capacity 时，raw 就是百分比
        assertEquals(23, CarSignalMath.fuelPercent(22.5f, 45f))
    }

    @Test
    fun `油量：结果夹在 0 到 100`() {
        assertEquals(100, CarSignalMath.fuelPercent(90000f, 45000f))
        assertEquals(0, CarSignalMath.fuelPercent(-13500f, 45000f))
    }

    // ---------------------------------------------------------------
    // 第六十四轮：车端 `ISensor` 的占位值口径
    // ---------------------------------------------------------------

    @Test
    fun `油量：车端占位值 0 不当成 0 百分比`() {
        // 实测：这台车机开机 / 车机服务重连时先推 0.0，真实值约 5 分钟后才推来。
        // 把占位值发布成「0%」会让桌面显示一个假的 0%。
        assertNull(CarSignalMath.fuelPercentFromSensor(0f, 55263f))
        assertNull(CarSignalMath.fuelPercentFromSensor(0f, 0f))
        // 负值（传感器不可用时车端返回 -1）同样当「没有数据」
        assertNull(CarSignalMath.fuelPercentFromSensor(-1f, 55263f))
    }

    @Test
    fun `油量：真实原始值照常换算成百分比`() {
        // 真机实测：容量 55263（0.001L → 55.26L），原始值 21000 → 38%
        assertEquals(38, CarSignalMath.fuelPercentFromSensor(21000f, 55263f))
        assertEquals(36, CarSignalMath.fuelPercentFromSensor(20000f, 55263f))
        assertEquals(35, CarSignalMath.fuelPercentFromSensor(19400f, 55263f))
    }

    @Test
    fun `环温：只在 -40 到 80 之间有效`() {
        assertEquals(19, CarSignalMath.ambientTempC(19.4f))
        assertEquals(-40, CarSignalMath.ambientTempC(-40f))
        assertEquals(80, CarSignalMath.ambientTempC(80f))
        assertNull(CarSignalMath.ambientTempC(81f))
        assertNull(CarSignalMath.ambientTempC(-41f))
        assertNull(CarSignalMath.ambientTempC(Float.NaN))
    }

    // ------------------------------------------------------------ 不支持值（第六十六轮）

    @Test
    fun `255 与 -1 判定为固件不支持`() {
        // 真机实测：四个门位全部返回 255（= -1），
        // jar 里的提示串是「is not supported for getFunctionValue ..., returns -1」。
        assertTrue(CarSignalMath.isUnsupportedRaw(255))
        assertTrue(CarSignalMath.isUnsupportedRaw(-1))
        assertTrue(CarSignalMath.isUnsupportedRaw(Int.MIN_VALUE))
    }

    @Test
    fun `正常的开闭值不算不支持`() {
        assertFalse(CarSignalMath.isUnsupportedRaw(0))
        assertFalse(CarSignalMath.isUnsupportedRaw(1))
        assertFalse(CarSignalMath.isUnsupportedRaw(50))
        assertFalse(CarSignalMath.isUnsupportedRaw(553779968))
    }

    // ------------------------------------------------------------ 车窗归一化（第六十六轮）

    @Test
    fun `车窗：0 是关 1 是开`() {
        assertEquals(false, CarSignalMath.interpretWindowOpen(0))
        assertEquals(true, CarSignalMath.interpretWindowOpen(1))
    }

    @Test
    fun `车窗：BCM 的开枚举也算开`() {
        assertEquals(true, CarSignalMath.interpretWindowOpen(553844994))
        assertEquals(true, CarSignalMath.interpretWindowOpen(553844993))
        assertEquals(true, CarSignalMath.interpretWindowOpen(553779457))
    }

    @Test
    fun `车窗：不套用车门的位置百分比表`() {
        // 车窗这一档返回的是**开 / 关**，不是开度。
        // 把 50 解释成「开了一半」属于凭空捏造 —— 一律读不出来。
        assertNull(CarSignalMath.interpretWindowOpen(50))
        assertNull(CarSignalMath.interpretWindowOpen(2))
        assertNull(CarSignalMath.interpretWindowOpen(5))
        assertNull(CarSignalMath.interpretWindowOpen(100))
    }

    @Test
    fun `车窗：不支持值读不出来`() {
        assertNull(CarSignalMath.interpretWindowOpen(255))
        assertNull(CarSignalMath.interpretWindowOpen(-1))
    }

    // ------------------------------------------------------------ 第七十一轮

    @Test
    fun `逐扇车窗开度口径`() {
        // 0 = 关；> 0 = 开；< 0 = 读不到（厂商把「异常」也翻译成 -1）
        assertEquals(false, CarSignalMath.interpretWindowPosition(0))
        assertEquals(true, CarSignalMath.interpretWindowPosition(1))
        assertEquals(true, CarSignalMath.interpretWindowPosition(100))
        assertEquals(true, CarSignalMath.interpretWindowPosition(127))
        assertNull(CarSignalMath.interpretWindowPosition(-1))
    }

    @Test
    fun `后备箱只认 0 关 1 开`() {
        assertEquals(true, CarSignalMath.interpretLatchClosed(0))
        assertEquals(false, CarSignalMath.interpretLatchClosed(1))
        // 厂商把 IDLE / STOP 也映射到 553844993 —— 不确定，不是「关」
        assertNull(CarSignalMath.interpretLatchClosed(553844993))
        assertNull(CarSignalMath.interpretLatchClosed(-1))
    }

    @Test
    fun `固件支持状态按名字判 不看 ordinal`() {
        assertEquals(true, CarSignalMath.functionSupported("active"))
        assertEquals(false, CarSignalMath.functionSupported("notavailable"))
        // 判不了的两种
        assertNull(CarSignalMath.functionSupported("notactive"))
        assertNull(CarSignalMath.functionSupported("error"))
        // 认不出来 / null → 判不了
        assertNull(CarSignalMath.functionSupported(null))
        assertNull(CarSignalMath.functionSupported("whatever"))
        // 大小写不敏感
        assertEquals(true, CarSignalMath.functionSupported("ACTIVE"))
    }

    @Test
    fun `属性号取车机真注册的那个`() {
        val candidates = intArrayOf(0x16400B00, 0x16401000)
        // 车机注册的是 ECARX 那套 → 用它
        assertEquals(
            0x16401000,
            CarSignalMath.resolvePropertyId(candidates, setOf(0x16401000, 560993300)),
        )
        // 车机注册的是 AOSP 那套 → 用它
        assertEquals(
            0x16400B00,
            CarSignalMath.resolvePropertyId(candidates, setOf(0x16400B00)),
        )
        // 两个都注册 → 候选顺序里靠前的优先
        assertEquals(
            0x16400B00,
            CarSignalMath.resolvePropertyId(candidates, setOf(0x16400B00, 0x16401000)),
        )
    }

    @Test
    fun `属性号一个都没命中就用第一个候选`() {
        val candidates = intArrayOf(0x16400B00, 0x16401000)
        assertEquals(0x16400B00, CarSignalMath.resolvePropertyId(candidates, setOf(1, 2, 3)))
        // 清单读不到（空集合）也是同一个口径
        assertEquals(0x16400B00, CarSignalMath.resolvePropertyId(candidates, emptySet()))
    }

    @Test
    fun `候选为空返回 0`() {
        assertEquals(0, CarSignalMath.resolvePropertyId(intArrayOf(), setOf(1)))
    }
}
