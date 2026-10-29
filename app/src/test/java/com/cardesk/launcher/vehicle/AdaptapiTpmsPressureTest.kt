package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 胎压无效值过滤的回归单测。
 *
 * ## 厂商的无效值约定（`TireStateManager`）
 *
 * - `INVALID_PRESSURE = Float.MIN_VALUE`（1.4E-45，`TireStateManager.java:42`）：
 *   单位取不到（`PaDTpmsUnitSetting` 为空 / 单位不认识）或 availability 不是 `ACTIVE`
 *   时车端返回它；
 * - `UPPER_LIMIT = 637.5f`（`TireStateManager.java:46`）：
 *   压力 `>=` 它会被归一成上面那个无效值；
 * - `THRESHOLD = 1.0E-4f`（`TireStateManager.java:45`）：厂商判断「值没变」的阈值；
 * - `TireState` 的初值就是 `mPressure = Float.MIN_VALUE`（`TireState.java:20`）——
 *   从没更新过的轮子也是这个值。
 *
 * 之前我们**原样返回**，桌面就会把 `1.4E-45` 当成一个真实的压力显示出来。
 */
class AdaptapiTpmsPressureTest {

    @Test
    fun `厂商的无效压力标记被过滤`() {
        assertNull(AdaptapiTpms.validPressure(Float.MIN_VALUE))
        assertNull(AdaptapiTpms.validPressure(0f))
        assertNull(AdaptapiTpms.validPressure(-1f))
        assertNull(AdaptapiTpms.validPressure(1e-5f))
    }

    @Test
    fun `超过物理上限的压力被过滤`() {
        assertNull(AdaptapiTpms.validPressure(AdaptapiTpms.PRESSURE_UPPER_LIMIT))
        assertNull(AdaptapiTpms.validPressure(700f))
        assertNull(AdaptapiTpms.validPressure(1000f))
    }

    @Test
    fun `正常压力原样返回`() {
        assertEquals(240.0, AdaptapiTpms.validPressure(240f)!!.toDouble(), 0.001)
        assertEquals(227.5, AdaptapiTpms.validPressure(227.5f)!!.toDouble(), 0.001)
        // 刚好卡在上限之下 → 认
        assertEquals(637.4, AdaptapiTpms.validPressure(637.4f)!!.toDouble(), 0.001)
    }

    @Test
    fun `读不到和 NaN 都算读不到`() {
        assertNull(AdaptapiTpms.validPressure(null))
        assertNull(AdaptapiTpms.validPressure(Float.NaN))
    }

    @Test
    fun `阈值与厂商一致`() {
        // TireStateManager.THRESHOLD = 1.0E-4f
        assertEquals(1e-4f, AdaptapiTpms.PRESSURE_EPSILON, 0f)
        // TireStateManager.UPPER_LIMIT = 637.5f
        assertEquals(637.5f, AdaptapiTpms.PRESSURE_UPPER_LIMIT, 0f)
    }
}
