package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 极简 protobuf 扫描的单测。
 *
 * 背景：另一个读 MC / Geely 事件属性时
 * 属性值是一段 protobuf 字节。
 * 那个类是 **CEVT 平台**的类，我们进程里没有，
 * 但 protobuf 的**线格式**是公开的 —— 所以只扫「唯一一个 varint 字段」。
 */
class ProtoLiteTest {

    @Test
    fun `单个 varint 字段能被扫出来`() {
        // field 1, varint, value = 1
        assertEquals(1L, ProtoLite.singleVarint(byteArrayOf(0x08, 0x01)))
        // field 1, varint, value = 0
        assertEquals(0L, ProtoLite.singleVarint(byteArrayOf(0x08, 0x00)))
    }

    @Test
    fun `多字节 varint 也能解出来`() {
        // 300 = 0xAC 0x02 (varint: 0x2C | 0x02<<7 = 44 + 256 = 300)
        assertEquals(300L, ProtoLite.singleVarint(byteArrayOf(0x08, 0xAC.toByte(), 0x02)))
    }

    @Test
    fun `前面有非 varint 字段时仍能找到那个 varint`() {
        // field 2, length-delimited, 3 bytes "abc" → 0x12 0x03 'a' 'b' 'c'
        // field 1, varint, value = 1 → 0x08 0x01
        val bytes = byteArrayOf(0x12, 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x08, 0x01)
        assertEquals(1L, ProtoLite.singleVarint(bytes))
    }

    @Test
    fun `fixed64 和 fixed32 会被跳过`() {
        // field 1, fixed64 → 0x09 + 8 bytes
        // field 2, varint, value = 1 → 0x10 0x01
        val bytes = byteArrayOf(
            0x09, 0, 0, 0, 0, 0, 0, 0, 0,
            0x10, 0x01,
        )
        assertEquals(1L, ProtoLite.singleVarint(bytes))
    }

    @Test
    fun `有两个 varint 字段时判定为歧义 不猜`() {
        // field 1 varint = 1, field 2 varint = 0
        assertNull(ProtoLite.singleVarint(byteArrayOf(0x08, 0x01, 0x10, 0x00)))
    }

    @Test
    fun `没有 varint 字段时返回 null`() {
        // field 1, length-delimited, 0 bytes
        assertNull(ProtoLite.singleVarint(byteArrayOf(0x0A, 0x00)))
        // 空消息
        assertNull(ProtoLite.singleVarint(ByteArray(0)))
    }

    @Test
    fun `数据不完整时返回 null 不抛异常`() {
        // 声明了 varint 但数据被截断
        assertNull(ProtoLite.singleVarint(byteArrayOf(0x08)))
        // length-delimited 的长度超出了实际数据
        assertNull(ProtoLite.singleVarint(byteArrayOf(0x12, 0x7F, 0x01)))
    }

    @Test
    fun `group 写法不支持 直接放弃`() {
        // wire type 3 (group start)
        assertNull(ProtoLite.singleVarint(byteArrayOf(0x0B, 0x00)))
    }
}
