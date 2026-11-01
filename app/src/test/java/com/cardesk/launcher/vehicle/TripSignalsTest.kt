package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行程信号解析（第九十六轮）：`PublicAttrValue` 的 int32/double/string 三支 + "HH:mm" 换算。
 *
 * 参考文档 `docs/ecarx-adapterapi/15-energy-trip-tpms.md` §3.1 / §5.1：
 * `TsTripDistance` 是 Integer、`TsOdometerTripMeter2` 是 Double、
 * `TsEdtTime2` 是 String("HH:mm")。
 */
class TripSignalsTest {

    /** protobuf varint 编码。 */
    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        while (true) {
            val b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun bytes(vararg parts: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (p in parts) for (b in p) out.add(b)
        return out.toByteArray()
    }

    /** tag：field<<3 | wire。 */
    private fun tag(field: Int, wire: Int): ByteArray = varint((field shl 3 or wire).toLong())

    private fun zigZag(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL

    private fun availability(): ByteArray = bytes(tag(1, 0), varint(1))

    @Test
    fun int32字段按ZigZag解码() {
        val raw = bytes(availability(), tag(2, 0), varint(0), tag(4, 0), varint(zigZag(12345)))
        val parsed = PublicAttrValue.parse(raw)!!
        assertTrue(parsed.active())
        assertEquals(12345, parsed.integerData())
        assertEquals(12345L * 2, parsed.int32Wire)
    }

    @Test
    fun double字段读得出行程2里程() {
        val bits = java.lang.Double.doubleToLongBits(128.4)
        val le = ByteArray(8) { i -> ((bits ushr (8 * i)) and 0xFFL).toByte() }
        val raw = bytes(availability(), tag(5, 1), le)
        val parsed = PublicAttrValue.parse(raw)!!
        assertEquals(128.4, parsed.doubleData()!!, 0.0001)
    }

    @Test
    fun string字段读得出行程2时间() {
        val text = "01:23".toByteArray(Charsets.UTF_8)
        val raw = bytes(availability(), tag(6, 2), varint(text.size.toLong()), text)
        val parsed = PublicAttrValue.parse(raw)!!
        assertEquals("01:23", parsed.stringData)
        assertEquals(83L * 60_000L, TripSignals.parseHourMinuteMs(parsed.stringData))
    }

    @Test
    fun availability不为1时不算有效值() {
        val raw = bytes(tag(4, 0), varint(zigZag(7)))
        val parsed = PublicAttrValue.parse(raw)!!
        assertFalse(parsed.active())
    }

    @Test
    fun hhmm换算按文档口径() {
        assertEquals(0L, TripSignals.parseHourMinuteMs("00:00"))
        assertEquals(83L * 60_000L, TripSignals.parseHourMinuteMs("01:23"))
        assertEquals(600L * 60_000L, TripSignals.parseHourMinuteMs("10:00"))
        assertNull(TripSignals.parseHourMinuteMs(""))
        assertNull(TripSignals.parseHourMinuteMs("abc"))
        assertNull(TripSignals.parseHourMinuteMs("10:60"))
        assertNull(TripSignals.parseHourMinuteMs("10-30"))
    }
}
