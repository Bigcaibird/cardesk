package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AltitudeFormat] 单测（纯逻辑，不依赖 Android）。
 *
 * 采集部分依赖 `LocationManager`，只能在设备上验；这里锁住的是**页面文案契约**：
 * 取整规则（42.4 → 42 m / -3.6 → -4 m）、NMEA `GGA` 的字段位置，
 * 以及「30 分钟新鲜期」的边界 —— 这三处一旦改错，现象分别是
 * 「海拔数字四舍五入不对」「GPS 有海拔但页面一直 --」「旧值被当成实时值」。
 */
class AltitudeFormatTest {

    // ------------------------------------------------------------------ 文案

    @Test
    fun `没有值时显示两个短横`() {
        assertEquals("--", AltitudeFormat.text(null))
    }

    @Test
    fun `42点4米取整为42米`() {
        assertEquals("42 m", AltitudeFormat.text(42.4))
    }

    @Test
    fun `负海拔按四舍五入取整`() {
        // -3.6 → -4（向绝对值更大的方向取整），页面不能出现 "-3 m" 这种截断值。
        assertEquals("-4 m", AltitudeFormat.text(-3.6))
    }

    @Test
    fun `海平面0米是合法值_不能当成没有值`() {
        assertEquals("0 m", AltitudeFormat.text(0.0))
    }

    @Test
    fun `NaN按没有值处理`() {
        assertEquals("--", AltitudeFormat.text(Double.NaN))
    }

    // ------------------------------------------------------------------ NMEA GGA

    /** 一条带海拔 545.4m 的完整 GGA（GPS）。 */
    private val ggaGps =
        "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"

    /** 北斗/多系统混合输出的 GGA（GNGGA），海拔为负。 */
    private val ggaGnss =
        "\$GNGGA,123520,4807.038,N,01131.000,E,1,10,0.8,-3.6,M,46.9,M,,*4A"

    @Test
    fun `GGA正常语句取第9字段的海拔`() {
        val m = AltitudeFormat.parseGgaAltitude(ggaGps) ?: Double.NaN
        assertEquals(545.4, m, 1e-6)
    }

    @Test
    fun `GNGGA语句同样能解析_负海拔保留符号`() {
        val m = AltitudeFormat.parseGgaAltitude(ggaGnss) ?: Double.NaN
        assertEquals(-3.6, m, 1e-6)
    }

    @Test
    fun `GGA没有高度字段时返回null_不能当0米`() {
        // "...0.9,,M,,M..." ← 第 9 字段为空：有定位但没有高度。
        val noAltitude = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,,M,,M,,*47"
        assertNull(AltitudeFormat.parseGgaAltitude(noAltitude))
    }

    @Test
    fun `定位质量为0的GGA不采用`() {
        // 第 6 字段 = 0 表示无效定位，此时后面的海拔没有意义。
        val invalidFix = "\$GPGGA,123519,4807.038,N,01131.000,E,0,00,,,M,,M,,*47"
        assertNull(AltitudeFormat.parseGgaAltitude(invalidFix))
    }

    @Test
    fun `非GGA语句返回null`() {
        val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
        assertNull(AltitudeFormat.parseGgaAltitude(rmc))
    }

    @Test
    fun `空串与残缺语句返回null`() {
        assertNull(AltitudeFormat.parseGgaAltitude(""))
        assertNull(AltitudeFormat.parseGgaAltitude("   "))
        assertNull(AltitudeFormat.parseGgaAltitude("\$GPGGA"))
        assertNull(AltitudeFormat.parseGgaAltitude("GPGGA,123519,1,N,2,E,1,08,0.9,100.0,M,0,M,,"))
    }

    // ------------------------------------------------------------------ 新鲜期

    @Test
    fun `29点9分钟仍算新鲜`() {
        val now = 1_700_000_000_000L
        val lastAt = now - (29.9 * 60 * 1000).toLong()
        assertTrue(AltitudeFormat.isFresh(now, lastAt))
    }

    @Test
    fun `30点1分钟算过期`() {
        val now = 1_700_000_000_000L
        val lastAt = now - (30.1 * 60 * 1000).toLong()
        assertFalse(AltitudeFormat.isFresh(now, lastAt))
    }

    @Test
    fun `恰好30分钟算新鲜_边界包含在内`() {
        val now = 1_700_000_000_000L
        assertTrue(AltitudeFormat.isFresh(now, now - AltitudeDesk.FRESH_MS))
    }

    @Test
    fun `从没落过盘的时间戳0算过期`() {
        assertFalse(AltitudeFormat.isFresh(1_700_000_000_000L, 0L))
    }

    @Test
    fun `未来时间戳算过期_不信任被回拨的时钟`() {
        val now = 1_700_000_000_000L
        assertFalse(AltitudeFormat.isFresh(now, now + 60_000L))
    }
}
