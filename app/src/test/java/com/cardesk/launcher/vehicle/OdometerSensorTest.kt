package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 总里程读取口径单测（第七十五轮）。
 *
 * ## 背景
 *
 * 总里程原来只有属性通道一条路（`PERF_ODOMETER` / `ODOMETER`），
 * 那条路在这台车机上**从来没读到过值** —— 桌面行程卡一直是 `--`。
 *
 * 现在第一顺位换成 AdaptAPI `ISensor` 的 `SENSOR_TYPE_ODOMETER = 1050368`，
 * 厂商源码里取值 / 支持判定 / 监听注册 / 变化回调**四样都有**。
 *
 * ## 口径要点
 *
 * 1. **单位确定是公里** —— `SensorPaUtil.getValue(PaDiDstTrVld)` 的值域判定是
 *    `> 2000000 || < 0` 即越界；200 万公里是合理车龄上限，200 万米只有 2000 公里。
 *    所以**不能**套用属性通道那套「大于 10 万就当米、除以 1000」的猜测换算
 *    —— 那会让 15 万公里的车显示成 150 km。
 * 2. **`0` 判为无效** —— 厂商在「信号为 null」和「值越界」时都返回 `0.0f`，
 *    和真正的「0 公里」没法区分，宁可显示 `--`。
 */
class OdometerSensorTest {

    @Test
    fun `总里程：普通公里数原样返回`() {
        assertEquals(12480, CarSignalMath.odometerKmFromSensor(12480f))
        assertEquals(1, CarSignalMath.odometerKmFromSensor(1f))
        assertEquals(386, CarSignalMath.odometerKmFromSensor(386f))
    }

    @Test
    fun `总里程：十万公里以上不除以 1000`() {
        // 这条是本轮修复的核心：老的那套「> 10 万当米」的猜测换算
        // 会把 15 万公里显示成 150 km。
        assertEquals(150000, CarSignalMath.odometerKmFromSensor(150000f))
        assertEquals(250000, CarSignalMath.odometerKmFromSensor(250000f))
        assertEquals(1999999, CarSignalMath.odometerKmFromSensor(1999999f))
    }

    @Test
    fun `总里程：厂商的零值占位判为读不到`() {
        // 厂商在信号为 null / 值越界时都返回 0.0f，
        // 和真正的「0 公里」没法区分 —— 宁可显示 --。
        assertNull(CarSignalMath.odometerKmFromSensor(0f))
        assertNull(CarSignalMath.odometerKmFromSensor(-1f))
        assertNull(CarSignalMath.odometerKmFromSensor(-0.5f))
    }

    @Test
    fun `总里程：越界值判为读不到`() {
        // 厂商值域上限 2,000,000，超出说明车端给的不是公里数。
        assertNull(CarSignalMath.odometerKmFromSensor(2000001f))
        assertNull(CarSignalMath.odometerKmFromSensor(3000000f))
    }

    @Test
    fun `总里程：NaN 与 MIN_VALUE 判为读不到`() {
        assertNull(CarSignalMath.odometerKmFromSensor(Float.NaN))
        assertNull(CarSignalMath.odometerKmFromSensor(Float.MIN_VALUE))
        assertNull(CarSignalMath.odometerKmFromSensor(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `总里程：小数按四舍五入取整`() {
        assertEquals(12480, CarSignalMath.odometerKmFromSensor(12480.4f))
        assertEquals(12481, CarSignalMath.odometerKmFromSensor(12480.6f))
    }

    @Test
    fun `传感器常量与厂商 SDK 一致`() {
        // ISensor.SENSOR_TYPE_ODOMETER = 1050368（ISensor.java:47）
        assertEquals(1050368, AdaptapiSensors.SENSOR_ODOMETER_KM)
        // 0x00100700
        assertEquals(0x00100700, AdaptapiSensors.SENSOR_ODOMETER_KM)
    }
}
