package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 行程卡「平均油耗」兜底口径的单测（第七十五轮）。
 *
 * ## 背景
 *
 * 厂商口径（`DrivingInfoManager`）：
 * - `getAvgFuelConsumption()` → **L/100km**（每百公里油耗）；
 * - `getTripFuelConsumption()` → **L**（本行程总共烧了多少升），
 *   换算是 `avgFuel × odo / 100`。
 *
 * 之前平均油耗拿不到时**直接**顶上行程油耗，两者差 100 倍：
 * 128 km 跑掉 12.3 L 的车会显示成「12.3 L/100km」，
 * 而正确值是 `12.3 × 100 / 128 = 9.6 L/100km`。
 *
 * 现在兜底改成按厂商公式反解：`avgFuel = tripFuel × 100 / tripKm`。
 */
class AvgFuelFromTripTest {

    @Test
    fun `行程油耗与里程都在时按厂商公式反解`() {
        // 128 km 烧了 12.3 L → 12.3 × 100 / 128 = 9.609375 L/100km
        assertEquals(9.609375, CarSignalMath.avgFuelConsumptionFromTrip(12.3, 128.0)!!, 1e-6)
    }

    @Test
    fun `典型往返的数值`() {
        // 50 km 烧 3.3 L → 6.6 L/100km
        assertEquals(6.6, CarSignalMath.avgFuelConsumptionFromTrip(3.3, 50.0)!!, 1e-6)
        // 100 km 烧 6.8 L → 6.8 L/100km
        assertEquals(6.8, CarSignalMath.avgFuelConsumptionFromTrip(6.8, 100.0)!!, 1e-6)
    }

    @Test
    fun `行程油耗为零时反解出零`() {
        // 0 升是合法值（刚出发还没消耗）→ 平均油耗 0，不是「读不到」
        assertEquals(0.0, CarSignalMath.avgFuelConsumptionFromTrip(0.0, 128.0)!!, 1e-6)
    }

    @Test
    fun `任一输入缺失就反解不出来`() {
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(null, 128.0))
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(12.3, null))
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(null, null))
    }

    @Test
    fun `行程里程为零时反解不出来`() {
        // 行程刚开始、里程还是 0 时，除法没有意义 —— 宁可显示 -- 也不给一个假值
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(12.3, 0.0))
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(12.3, -5.0))
    }

    @Test
    fun `非法数值反解不出来`() {
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(Double.NaN, 128.0))
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(12.3, Double.NaN))
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(Double.POSITIVE_INFINITY, 128.0))
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(12.3, Double.POSITIVE_INFINITY))
        assertNull(CarSignalMath.avgFuelConsumptionFromTrip(-1.0, 128.0))
    }
}
