package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryVoltageProbeTest {

    @Test
    fun `normalizes volts, decivolts, and millivolts accurately`() {
        assertEquals(12.6f, BatteryVoltageProbe.normalizeVoltage(12.6f)!!, 0.001f)
        assertEquals(14.2f, BatteryVoltageProbe.normalizeVoltage(142f)!!, 0.001f)
        assertEquals(12.45f, BatteryVoltageProbe.normalizeVoltage(12450f)!!, 0.001f)
        // 过滤无效值、0、手机锂电模拟电压（3.7V~4.2V）及异常大值
        assertNull(BatteryVoltageProbe.normalizeVoltage(0f))
        assertNull(BatteryVoltageProbe.normalizeVoltage(-1f))
        assertNull(BatteryVoltageProbe.normalizeVoltage(3.8f))
        assertNull(BatteryVoltageProbe.normalizeVoltage(4.2f))
        assertNull(BatteryVoltageProbe.normalizeVoltage(4200f))
        assertNull(BatteryVoltageProbe.normalizeVoltage(Float.NaN))
    }

    @Test
    fun `formats 820A event sensor labels accurately`() {
        assertEquals("行驶中", AdaptapiSensors.ignitionLabel(AdaptapiSensors.IGNITION_DRIVING))
        assertEquals("已通电", AdaptapiSensors.ignitionLabel(AdaptapiSensors.IGNITION_ACTIVE))
        assertEquals("便捷模式", AdaptapiSensors.ignitionLabel(AdaptapiSensors.IGNITION_CONVENIENCE))
        assertEquals("已熄火", AdaptapiSensors.ignitionLabel(AdaptapiSensors.IGNITION_INACTIVE))
        assertEquals("离车", AdaptapiSensors.ignitionLabel(AdaptapiSensors.IGNITION_ABANDON))
        assertEquals("--", AdaptapiSensors.ignitionLabel(null))

        assertEquals("正常", AdaptapiSensors.oilLevelLabel(AdaptapiSensors.OIL_LEVEL_OK))
        assertEquals("偏低", AdaptapiSensors.oilLevelLabel(AdaptapiSensors.OIL_LEVEL_LOW1))
        assertEquals("过低", AdaptapiSensors.oilLevelLabel(AdaptapiSensors.OIL_LEVEL_LOW2))
        assertEquals("偏高", AdaptapiSensors.oilLevelLabel(AdaptapiSensors.OIL_LEVEL_HIGH))

        assertEquals("正常", AdaptapiSensors.brakeFluidLabel(AdaptapiSensors.BRAKE_FLUID_OK))
        assertEquals("液位偏低", AdaptapiSensors.brakeFluidLabel(AdaptapiSensors.BRAKE_FLUID_LOW))

        assertEquals("正常", AdaptapiSensors.coolantWarnLabel(AdaptapiSensors.WARN_NORMAL))
        assertEquals("温度/液位告警", AdaptapiSensors.coolantWarnLabel(AdaptapiSensors.WARN_ACTIVE))

        assertEquals("正常", AdaptapiSensors.fuelLowWarnLabel(AdaptapiSensors.WARN_NORMAL))
        assertEquals("油量偏低", AdaptapiSensors.fuelLowWarnLabel(AdaptapiSensors.WARN_ACTIVE))

        assertTrue(AdaptapiSensors.LISTENED_SENSORS.contains(AdaptapiSensors.SENSOR_MAINTENANCE_KM))
        assertTrue(AdaptapiSensors.LISTENED_SENSORS.contains(AdaptapiSensors.SENSOR_OIL_LEVEL))
        assertTrue(AdaptapiSensors.LISTENED_SENSORS.contains(AdaptapiSensors.SENSOR_BRAKE_FLUID))
    }
}
