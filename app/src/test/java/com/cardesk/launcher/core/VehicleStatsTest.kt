package com.cardesk.launcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 车身属性值的解释与文案格式化单测。
 *
 * 第三十六轮新增：接 `CarPropertyManager` 通道（车门/车窗/总里程）后，
 * 「读到的数怎么解释」必须有确定口径——这部分没法在真机上反复试，
 * 所以全部定死在这里测。
 */
class VehicleStatsTest {

    // ---------------------------------------------------------------- 门

    /** 0 = 关（口径同`EcarxSignals.doorsClosed()`）。 */
    @Test
    fun doorZeroIsClosed() {
        assertEquals(true, VehicleStats.interpretDoorClosed(0.0))
    }

    /** 1 = 开；2/3（关闭中/开启中）也按「不是全关」处理。 */
    @Test
    fun doorNonZeroIsOpen() {
        assertEquals(false, VehicleStats.interpretDoorClosed(1.0))
        assertEquals(false, VehicleStats.interpretDoorClosed(2.0))
        assertEquals(false, VehicleStats.interpretDoorClosed(3.0))
    }

    /** 读不出来（null / NaN）必须与「关」区分开。 */
    @Test
    fun doorUnreadableIsNull() {
        assertNull(VehicleStats.interpretDoorClosed(null))
        assertNull(VehicleStats.interpretDoorClosed(Double.NaN))
    }

    // ---------------------------------------------------------------- 窗

    /** 全关：四个都是 0 → 四扇都关（true = 开，所以全是 false）。 */
    @Test
    fun windowsAllClosed() {
        val flags = VehicleStats.windowOpenFlags(doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        assertArrayEquals(booleanArrayOf(false, false, false, false), flags)
    }

    /** AOSP 比例语义（0.0–1.0）：0.5 表示半开。 */
    @Test
    fun windowsFractionSemantics() {
        val flags = VehicleStats.windowOpenFlags(doubleArrayOf(0.0, 0.5, 1.0, 0.0))
        assertArrayEquals(booleanArrayOf(false, true, true, false), flags)
    }

    /** ECARX 百分比语义（0–100）：50 表示打开。 */
    @Test
    fun windowsPercentSemantics() {
        val flags = VehicleStats.windowOpenFlags(doubleArrayOf(0.0, 50.0, 100.0, 0.0))
        assertArrayEquals(booleanArrayOf(false, true, true, false), flags)
    }

    /** 混有百分比时按百分比解释（有一扇 > 1.0 即整体按百分比）。 */
    @Test
    fun windowsMixedUsesPercentMode() {
        val flags = VehicleStats.windowOpenFlags(doubleArrayOf(0.0, 1.0, 50.0, 0.0))
        // 1.0 在百分比语义下是 1%（当作全关），50.0 是全开
        assertArrayEquals(booleanArrayOf(false, false, true, false), flags)
    }

    /** 读不出来的那一扇按「关」处理，但不能因为它就整组作废。 */
    @Test
    fun windowsPartialReadable() {
        val flags = VehicleStats.windowOpenFlags(doubleArrayOf(Double.NaN, 50.0, Double.NaN, 0.0))
        assertArrayEquals(booleanArrayOf(false, true, false, false), flags)
    }

    /** 全部读不出来 / 数组长度不对 → null。 */
    @Test
    fun windowsUnreadableIsNull() {
        assertNull(VehicleStats.windowOpenFlags(null))
        assertNull(VehicleStats.windowOpenFlags(doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)))
        assertNull(VehicleStats.windowOpenFlags(doubleArrayOf(0.0, 0.0)))
    }

    // ---------------------------------------------------------------- 区域号

    /**
     * 位掩码口径（AOSP）：`{2,4,8,16,32,64}`
     * 左前 2 / 右前 4 / 左后 8 / 右后 16 / 引擎盖 32 / 后备箱 64。
     */
    @Test
    fun areaRolesBitmask() {
        val roles = VehicleStats.areaRoles(
            intArrayOf(2, 4, 8, 16, 32, 64),
            VehicleStats.DOOR_AREA_MASKS,
        )
        assertArrayEquals(intArrayOf(2, 4, 8, 16, 32, 64), roles)
    }

    /** 位掩码口径里带 MASTER(1) 也不能错位。 */
    @Test
    fun areaRolesBitmaskWithMaster() {
        val roles = VehicleStats.areaRoles(
            intArrayOf(1, 2, 4, 8, 16),
            VehicleStats.DOOR_AREA_MASKS,
        )
        assertArrayEquals(intArrayOf(2, 4, 8, 16, -1, -1), roles)
    }

    /** 顺序索引口径（`{0,1,2,3,4,5}`）按下标解释。 */
    @Test
    fun areaRolesIndexMode() {
        val roles = VehicleStats.areaRoles(
            intArrayOf(0, 1, 2, 3, 4, 5),
            VehicleStats.DOOR_AREA_MASKS,
        )
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), roles)
    }

    /** 索引口径只注册了四个门位 → 引擎盖 / 后备箱不可用（-1）。 */
    @Test
    fun areaRolesIndexModeMissingHoodTrunk() {
        val roles = VehicleStats.areaRoles(
            intArrayOf(0, 1, 2, 3),
            VehicleStats.DOOR_AREA_MASKS,
        )
        assertArrayEquals(intArrayOf(0, 1, 2, 3, -1, -1), roles)
    }

    /** 车窗位掩码口径。 */
    @Test
    fun areaRolesWindows() {
        val roles = VehicleStats.areaRoles(
            intArrayOf(2, 4, 8, 16),
            VehicleStats.WINDOW_AREA_MASKS,
        )
        assertArrayEquals(intArrayOf(2, 4, 8, 16), roles)
    }

    /** 读不到区域 → 全部角色不可用。 */
    @Test
    fun areaRolesUnreadable() {
        assertArrayEquals(intArrayOf(-1, -1, -1, -1, -1, -1), VehicleStats.areaRoles(null, VehicleStats.DOOR_AREA_MASKS))
        assertArrayEquals(intArrayOf(-1, -1, -1, -1), VehicleStats.areaRoles(intArrayOf(), VehicleStats.WINDOW_AREA_MASKS))
    }

    // ---------------------------------------------------------------- 总里程

    /** 小于 10 万 → 当作公里。 */
    @Test
    fun odometerInKm() {
        assertEquals(12480, VehicleStats.odometerKm(12480.0))
    }

    /** 大于 10 万 → 当作米，换算成公里。 */
    @Test
    fun odometerInMeters() {
        assertEquals(12480, VehicleStats.odometerKm(12_480_000.0))
    }

    /** 读不到 / 非法值 → null。 */
    @Test
    fun odometerUnreadableIsNull() {
        assertNull(VehicleStats.odometerKm(null))
        assertNull(VehicleStats.odometerKm(0.0))
        assertNull(VehicleStats.odometerKm(-1.0))
        assertNull(VehicleStats.odometerKm(Double.NaN))
    }

    // ---------------------------------------------------------------- 文案

    /** 总里程带千分位。 */
    @Test
    fun odometerText() {
        assertEquals("12,480 km", VehicleStats.formatOdometer(12480))
        assertEquals("860 km", VehicleStats.formatOdometer(860))
        assertEquals("--", VehicleStats.formatOdometer(null))
        assertEquals("--", VehicleStats.formatOdometer(-1))
    }

    /** 平均油耗一位小数。 */
    @Test
    fun fuelText() {
        assertEquals("6.8 L/100km", VehicleStats.formatFuelConsumption(6.8))
        assertEquals("6.0 L/100km", VehicleStats.formatFuelConsumption(6.0))
        assertEquals("--", VehicleStats.formatFuelConsumption(null))
        assertEquals("--", VehicleStats.formatFuelConsumption(-1.0))
    }

    /** 行程里程。 */
    @Test
    fun tripDistanceText() {
        assertEquals("128.4 km", VehicleStats.formatTripDistanceKm(128.4))
        assertEquals("0.0 km", VehicleStats.formatTripDistanceKm(0.01))
        assertEquals("--", VehicleStats.formatTripDistanceKm(null))
    }

    /** 行程时长：不足 1 分钟 / 分钟 / 小时+分钟。 */
    @Test
    fun tripDurationText() {
        assertEquals("不到 1 分钟", VehicleStats.formatTripDuration(30_000.0))
        assertEquals("30 分钟", VehicleStats.formatTripDuration(30 * 60_000.0))
        assertEquals("1 小时 23 分", VehicleStats.formatTripDuration(83 * 60_000.0))
        assertEquals("2 小时 0 分", VehicleStats.formatTripDuration(120 * 60_000.0))
        assertEquals("--", VehicleStats.formatTripDuration(null))
    }

    /** 千分位分组。 */
    @Test
    fun digitGrouping() {
        assertEquals("999", VehicleStats.groupDigits(999))
        assertEquals("1,000", VehicleStats.groupDigits(1000))
        assertEquals("12,480", VehicleStats.groupDigits(12480))
        assertEquals("1,234,567", VehicleStats.groupDigits(1234567))
    }

    /** 燃油剩余量百分比。 */
    @Test
    fun fuelPercentText() {
        assertEquals("58%", VehicleStats.formatFuelPercent(58))
        assertEquals("0%", VehicleStats.formatFuelPercent(0))
        assertEquals("100%", VehicleStats.formatFuelPercent(100))
        assertEquals("--", VehicleStats.formatFuelPercent(null))
        assertEquals("--", VehicleStats.formatFuelPercent(-1))
        assertEquals("--", VehicleStats.formatFuelPercent(101))
    }
}
