package com.cardesk.launcher.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志格式化与文件命名（纯逻辑，不碰 Android）。
 */
class LogFormatTest {

    // ------------------------------------------------------------------ 消息拼装

    @Test
    fun message_withoutFieldsIsUnchanged() {
        assertEquals("写入空调", LogFormat.message("写入空调", emptyArray()))
    }

    @Test
    fun message_appendsKeyValuePairs() {
        val fields = arrayOf<Pair<String, Any?>>("func" to 0x10010300, "zone" to 1)
        // 0x10010300 = 268501760（十进制原样输出，不转 hex，方便 grep）
        assertEquals("写入空调 func=268501760 zone=1", LogFormat.message("写入空调", fields))
    }

    @Test
    fun message_writesNullValueAsNull() {
        val fields = arrayOf<Pair<String, Any?>>("reason" to null)
        assertEquals("失败 reason=null", LogFormat.message("失败", fields))
    }

    @Test
    fun message_keepsZeroAndFalse() {
        val fields = arrayOf<Pair<String, Any?>>("value" to 0, "ok" to false)
        assertEquals("写入 value=0 ok=false", LogFormat.message("写入", fields))
    }

    // ------------------------------------------------------------------ 行格式

    @Test
    fun line_containsTimeLevelTagAndMessage() {
        val line = LogFormat.line("10-22 23:45:01.123", LogLevel.WARN, "CarDesk/Hvac", "写入被拒")
        assertEquals("10-22 23:45:01.123 W CarDesk/Hvac : 写入被拒", line)
    }

    @Test
    fun line_appendsExceptionTypeAndMessage() {
        val line = LogFormat.line(
            "10-22 23:45:01.123",
            LogLevel.ERROR,
            "CarDesk/Vehicle",
            "连接失败",
            IllegalStateException("通道未就绪"),
        )
        assertEquals(
            "10-22 23:45:01.123 E CarDesk/Vehicle : 连接失败 | IllegalStateException: 通道未就绪",
            line,
        )
    }

    @Test
    fun line_withoutExceptionHasNoSeparator() {
        val line = LogFormat.line("t", LogLevel.INFO, "CarDesk/App", "启动")
        assertFalse(line.contains("|"))
    }

    // ------------------------------------------------------------------ 级别门槛

    @Test
    fun level_reached_followsThreshold() {
        assertFalse(LogLevel.DEBUG.reached(LogLevel.INFO))
        assertTrue(LogLevel.INFO.reached(LogLevel.INFO))
        assertTrue(LogLevel.WARN.reached(LogLevel.INFO))
        assertTrue(LogLevel.ERROR.reached(LogLevel.INFO))
        assertTrue(LogLevel.DEBUG.reached(LogLevel.DEBUG))
    }

    @Test
    fun level_labelsMatchLogcatConvention() {
        assertEquals("D", LogLevel.DEBUG.label)
        assertEquals("I", LogLevel.INFO.label)
        assertEquals("W", LogLevel.WARN.label)
        assertEquals("E", LogLevel.ERROR.label)
    }

    /**
     * 落盘级别是持久化的（车机重启后不能丢），所以要能从名字解析回来。
     *
     * 认不出来时必须返回 null 而不是抛异常 —— 持久化的值可能来自旧版本或被手改。
     */
    @Test
    fun level_byNameOrNull_roundTrips() {
        assertEquals(LogLevel.DEBUG, LogLevel.byNameOrNull("DEBUG"))
        assertEquals(LogLevel.INFO, LogLevel.byNameOrNull("INFO"))
        assertEquals(LogLevel.WARN, LogLevel.byNameOrNull("WARN"))
        assertEquals(LogLevel.ERROR, LogLevel.byNameOrNull("ERROR"))
    }

    @Test
    fun level_byNameOrNull_returnsNullForUnknown() {
        assertNull(LogLevel.byNameOrNull(null))
        assertNull(LogLevel.byNameOrNull(""))
        assertNull(LogLevel.byNameOrNull("   "))
        assertNull(LogLevel.byNameOrNull("VERBOSE"))
    }

    // ------------------------------------------------------------------ 文件命名

    @Test
    fun fileName_usesDateAndIndex() {
        assertEquals("cardesk-20261022-0.txt", LogFormat.fileName("20261022", 0))
        assertEquals("cardesk-20261022-3.txt", LogFormat.fileName("20261022", 3))
    }

    @Test
    fun isSameDay_matchesPrefix() {
        assertTrue(LogFormat.isSameDay("cardesk-20261022-0.txt", "20261022"))
        assertFalse(LogFormat.isSameDay("cardesk-20261023-0.txt", "20261022"))
    }

    @Test
    fun toRemove_keepsNewestFiles() {
        val names = listOf("a", "b", "c", "d", "e")
        // 5 个文件、最多留 3 个 → 删掉最老的两个
        assertEquals(listOf("a", "b"), LogFormat.toRemove(names, 3))
    }

    @Test
    fun toRemove_noopWhenWithinLimit() {
        val names = listOf("a", "b")
        assertTrue(LogFormat.toRemove(names, 12).isEmpty())
    }

    // ------------------------------------------------------------------ 按字节裁剪

    /**
     * 车机存储紧张，份数上限之外还要有**总字节上限**：
     * 份数一样、每份大小不同的话占用差别很大。
     */
    @Test
    fun toRemoveByBytes_dropsOldestUntilWithinLimit() {
        // 从最老的开始删：a(300KB) + b(300KB) 超过 500KB → 删 a 后剩 300KB
        val files = listOf("a" to 300L * 1024, "b" to 300L * 1024)
        assertEquals(listOf("a"), LogFormat.toRemoveByBytes(files, 500L * 1024))
    }

    @Test
    fun toRemoveByBytes_noopWhenWithinLimit() {
        val files = listOf("a" to 100L * 1024, "b" to 100L * 1024)
        assertTrue(LogFormat.toRemoveByBytes(files, 4L * 1024 * 1024).isEmpty())
    }

    @Test
    fun toRemoveByBytes_dropsAllWhenEvenOneTooBig() {
        val files = listOf("a" to 100L * 1024, "b" to 5L * 1024 * 1024)
        // a 删掉后还超 → b 也要删
        assertEquals(listOf("a", "b"), LogFormat.toRemoveByBytes(files, 1024L * 1024))
    }

    @Test
    fun toRemoveByBytes_handlesEmptyAndZeroLimit() {
        assertTrue(LogFormat.toRemoveByBytes(emptyList(), 1024L).isEmpty())
        // 上限 <= 0 表示「不限制」——一个都不删
        assertTrue(LogFormat.toRemoveByBytes(listOf("a" to 10L), 0L).isEmpty())
        assertTrue(LogFormat.toRemoveByBytes(listOf("a" to 10L), -1L).isEmpty())
    }

    /**
     * 落盘占用必须封顶：单文件 × 份数不能超过总字节上限。
     * （这三个常量改了，这里会红。）
     */
    @Test
    fun storageCaps_areConsistent() {
        val worstCase = FileLogSink.MAX_FILE_BYTES * FileLogSink.MAX_FILES
        assertTrue(
            "最坏占用 $worstCase 超过总上限 ${FileLogSink.MAX_TOTAL_BYTES}",
            worstCase <= FileLogSink.MAX_TOTAL_BYTES,
        )
        // 车机存储紧张：整体占用必须压在 8MB 以内
        assertTrue(
            "日志总上限 ${FileLogSink.MAX_TOTAL_BYTES} 太大",
            FileLogSink.MAX_TOTAL_BYTES <= 8L * 1024 * 1024,
        )
    }

    /**
     * 每条落盘记录必须自带换行。
     *
     * 之前 [FileLogSink] 直接 `write(line)` 没带分隔符，
     * 结果整个日志文件变成**一行**（`wc -l` = 0），
     * 车机上看日志和「最近日志预览」全是挤在一起的。
     */
    @Test
    fun record_appendsLineSeparator() {
        assertEquals("写入空调\n", LogFormat.record("写入空调"))
        assertEquals("a\nb\n", LogFormat.record("a\nb"))
    }

    @Test
    fun record_keepsLinesSeparated() {
        val file = (1..3).joinToString("") { LogFormat.record("第 $it 行") }
        // Kotlin 的 lines() 会把结尾换行后的空串也算一行，所以这里只数非空行
        val lines = file.lines().filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertEquals("第 1 行", lines[0])
        assertEquals("第 3 行", lines[2])
    }
}
