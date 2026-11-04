package com.cardesk.launcher.vehicle

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 行程1 多通道读取的健壮性回归。
 *
 * 真车（4204 诊断包）上 `TS_TripDistance` / `TS_TripTime` 不是 byte[]，
 * 旧实现按 `PublicAttrValue` 读必然 null。这里保证：
 * 没有车辆通道时（Robolectric）多通道读只是返回 null，不抛异常；
 * 诊断探针始终能给出文本（哪怕全是「读不到」）。
 */
@RunWith(RobolectricTestRunner::class)
class TripSignalsProbeTest {

    @Test
    fun 没有车辆通道时读行程1返回null而不崩溃() {
        assertNull(TripSignals.readTrip1())
        assertNull(TripSignals.readTrip2())
    }

    @Test
    fun 探针在无通道时也给出可读文本() {
        val text = TripSignals.probe()
        assertNotNull(text)
        assertTrue(text.contains("0x21701071"))
        assertTrue(text.contains("0x21701072"))
        assertTrue(text.contains("0x21701070"))
        assertTrue(text.contains("PA="))
    }

    @Test
    fun 行程2时间字符串解析保持原口径() {
        assertNull(TripSignals.parseHourMinuteMs(null))
        assertNull(TripSignals.parseHourMinuteMs("bad"))
        assertNull(TripSignals.parseHourMinuteMs("12:99"))
        assertTrue(TripSignals.parseHourMinuteMs("1:30") == 5_400_000L)
    }
}
