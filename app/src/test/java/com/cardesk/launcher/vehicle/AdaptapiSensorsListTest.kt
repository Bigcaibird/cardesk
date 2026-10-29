package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传感器列表收缩的回归单测。
 *
 * 厂商的 `SensorImpl.getSensorLatestValue` 只实现了 8 个传感器
 * （1048832 车速 / 1049344 PM2.5 / 1049856 AQI / 1050112 油量 /
 * 1050368 总里程 / 1050624 续航 / 4206592 保养里程 / 4210688 SOC%），
 * 其余 id 一律落 `default: return -1.0f`（`SensorImpl.java:250-251`）；
 * `SensorListener.mListenableSensors` 里也没有那些 id，
 * `registerListener` 会直接 `return false`（`SensorListener.java:271-274`）。
 *
 * 之前探针列表里挂了几个**只定义了常量、没实现取值**的传感器，
 * 每轮都白读一次 `-1.0f`。
 */
class AdaptapiSensorsListTest {

    @Test
    fun `续航探针只探 SDK 真正实现的那个`() {
        assertEquals(
            listOf(AdaptapiSensors.SENSOR_ENDURANCE_KM),
            AdaptapiSensors.RANGE_SENSORS.toList(),
        )
    }

    @Test
    fun `没实现的三个续航传感器不再进列表`() {
        val ids = AdaptapiSensors.RANGE_SENSORS.toList()
        assertFalse(ids.contains(AdaptapiSensors.SENSOR_POTENTIAL_ENDURANCE))
        assertFalse(ids.contains(AdaptapiSensors.SENSOR_DISCHARGE_ENDURANCE))
        assertFalse(ids.contains(AdaptapiSensors.SENSOR_CHARGE_ENDURANCE))
    }

    @Test
    fun `环温传感器不再进列表`() {
        assertFalse(AdaptapiSensors.RANGE_SENSORS.contains(AdaptapiSensors.SENSOR_TEMP_AMBIENT))
    }

    @Test
    fun `油量与续航这两个在用的 id 没被改掉`() {
        // 油量走自己的探针（probeFuel），不在 RANGE_SENSORS 里，但 id 不能动
        assertEquals(1050112, AdaptapiSensors.SENSOR_FUEL_LEVEL)
        assertEquals(1050624, AdaptapiSensors.SENSOR_ENDURANCE_KM)
        assertTrue(AdaptapiSensors.RANGE_SENSORS.contains(AdaptapiSensors.SENSOR_ENDURANCE_KM))
    }

    @Test
    fun `环温在 ISensor 这条通道上恒为不可读`() {
        // 环温传感器在 SDK 里没实现 → 不再当环温来源展示
        assertEquals(Int.MIN_VALUE, AdaptapiSensors.ambientTempC())
    }

    @Test
    fun `总里程传感器进了监听列表`() {
        // 第七十五轮：总里程改走 ISensor 的 ODOMETER（1050368）。
        // SDK 里取值 / 支持判定 / 注册 / 变化回调四样都有，所以它能注册成功。
        assertTrue(AdaptapiSensors.LISTENED_SENSORS.contains(AdaptapiSensors.SENSOR_ODOMETER_KM))
    }

    @Test
    fun `监听列表里只有 SDK 真正实现了取值的传感器`() {
        val ids = AdaptapiSensors.LISTENED_SENSORS.toList()
        assertTrue(ids.contains(AdaptapiSensors.SENSOR_FUEL_LEVEL))
        assertTrue(ids.contains(AdaptapiSensors.SENSOR_ENDURANCE_KM))
        // 没实现的不能混进来
        assertFalse(ids.contains(AdaptapiSensors.SENSOR_TEMP_AMBIENT))
        assertFalse(ids.contains(AdaptapiSensors.SENSOR_POTENTIAL_ENDURANCE))
        assertFalse(ids.contains(AdaptapiSensors.SENSOR_DISCHARGE_ENDURANCE))
        assertFalse(ids.contains(AdaptapiSensors.SENSOR_CHARGE_ENDURANCE))
    }
}
