package com.cardesk.launcher.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferFormatTest {

    @Test
    fun bytes_usesReadableUnits() {
        assertEquals("900 B", TransferFormat.bytes(900))
        assertEquals("1 KB", TransferFormat.bytes(1024))
        assertEquals("1.5 MB", TransferFormat.bytes(1_572_864))
        assertEquals("2.00 GB", TransferFormat.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun speed_handlesUnknownAndZero() {
        assertEquals("—", TransferFormat.speed(0.0))
        assertEquals("—", TransferFormat.speed(-1.0))
        assertEquals("1.0 MB/s", TransferFormat.speed(1048576.0))
    }

    @Test
    fun percent_isClamped() {
        assertEquals("0%", TransferFormat.percent(-5))
        assertEquals("45%", TransferFormat.percent(45))
        assertEquals("100%", TransferFormat.percent(180))
    }

    @Test
    fun speedOf_usesByteAndTimeDelta() {
        assertEquals(1048576.0, TransferFormat.speedOf(1048576L, 1000L), 0.001)
        assertEquals(0.0, TransferFormat.speedOf(0L, 1000L), 0.0)
        assertEquals(0.0, TransferFormat.speedOf(1024L, 0L), 0.0)
    }
}
