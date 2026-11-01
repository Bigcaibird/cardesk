package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.ProtoLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PA 属性值（`GeelyProto.PublicAttrValue`）解析的 JVM 单测。
 *
 * 核心回归：「车门状态读不到」的根因是旧解析器
 * [ProtoLite.singleVarint] 只认**恰好一个** varint，而 PA 消息通常有
 * `availability`（field 1）/ `status`（field 3）/ `data`（field 4）**三个** varint，
 * 于是恒返回 null。这里用同一条字节流同时证明「旧实现确实解不出来」
 * 与「新实现能完整解出来」。
 *
 * 不依赖 Android 运行时：只拼字节、调纯解析器。
 */
class PublicAttrValueTest {

    // —— protobuf 线格式拼装（测试专用）——

    /** 编一个 varint（wire 上的无符号值）。 */
    private fun varint(value: Long): ByteArray {
        var v = value
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val low = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(low)
                return out.toByteArray()
            }
            out.write(low or 0x80)
        }
    }

    /** wire type 0：varint 字段。 */
    private fun fieldVarint(field: Int, value: Long): ByteArray =
        varint(field.toLong() shl 3) + varint(value)

    /** wire type 1：64 位定长字段（field 5 的 double 用这个）。 */
    private fun field64(field: Int, bits: Long): ByteArray {
        val head = varint((field.toLong() shl 3) or 1L)
        val body = ByteArray(8) { i -> ((bits ushr (8 * i)) and 0xFF).toByte() }
        return head + body
    }

    /** wire type 2：长度分隔字段（field 6 的 string 用这个）。 */
    private fun fieldBytes(field: Int, payload: ByteArray): ByteArray =
        varint((field.toLong() shl 3) or 2L) + varint(payload.size.toLong()) + payload

    private fun concat(vararg parts: ByteArray): ByteArray =
        parts.reduce { a, b -> a + b }

    // —— 回归：多 varint 消息 ——

    @Test
    fun `多 varint 的 PA 消息能完整解析（旧 singleVarint 恒 null 的回归）`() {
        // 真机上的形状：availability=1、status=0、data 的 wire 值 2（ZigZag 后 = 1）
        val bytes = concat(fieldVarint(1, 1), fieldVarint(3, 0), fieldVarint(4, 2))

        // 根因证据：旧解析器见到第二个 varint 就放弃（这条断言失败说明回归前提变了）
        assertNull("旧解析器必须解不出来", ProtoLite.singleVarint(bytes))

        val pa = PublicAttrValue.parse(bytes)
        assertTrue("新解析器必须能解出来", pa != null)
        assertEquals(1, pa!!.availability)
        assertEquals(0, pa.status)
        assertEquals(1, pa.integerData())
        assertTrue("availability == 1 才算有效", pa.active())
    }

    @Test
    fun `完整 PA 消息能解释成开与关`() {
        // data wire=2 → ZigZag 1 → 开
        val open = PublicAttrValue.parse(
            concat(fieldVarint(1, 1), fieldVarint(3, 0), fieldVarint(4, 2)),
        )!!
        assertEquals(false, McDoorSignals.interpretData(open.integerData()))

        // data wire=4 → ZigZag 2 → 关（合并口径：2 也算关）
        val closed = PublicAttrValue.parse(
            concat(fieldVarint(1, 1), fieldVarint(3, 0), fieldVarint(4, 4)),
        )!!
        assertEquals(true, McDoorSignals.interpretData(closed.integerData()))
    }

    // —— field 4：sint32 / ZigZag ——

    @Test
    fun `field4 是 sint32 走 ZigZag`() {
        assertEquals(0, PublicAttrValue.parse(fieldVarint(4, 0))!!.integerData())
        assertEquals(1, PublicAttrValue.parse(fieldVarint(4, 2))!!.integerData())
        assertEquals(-1, PublicAttrValue.parse(fieldVarint(4, 1))!!.integerData())
        // 负数在 wire 上是 u32：-1 记作 0xFFFFFFFF；ZigZag 后是 Int.MIN_VALUE
        assertEquals(
            Int.MIN_VALUE,
            PublicAttrValue.parse(fieldVarint(4, 0xFFFFFFFFL))!!.integerData(),
        )
        // int32Wire 保留 wire 上的无符号原值，诊断用
        assertEquals(2L, PublicAttrValue.parse(fieldVarint(4, 2))!!.int32Wire)
    }

    // —— availability ——

    @Test
    fun `availability 不等于 1 时 active 为 false`() {
        assertFalse(PublicAttrValue.parse(fieldVarint(1, 0))!!.active())
        assertFalse(PublicAttrValue.parse(fieldVarint(1, 2))!!.active())
        assertTrue(PublicAttrValue.parse(fieldVarint(1, 1))!!.active())
        // 没带 availability 字段：不能当成有效（否则「没数据」会被当成值 0 = 关）
        assertFalse("缺 availability 就是无效", PublicAttrValue.parse(fieldVarint(4, 2))!!.active())
    }

    @Test
    fun `空消息线格式合法但没有有效值`() {
        val pa = PublicAttrValue.parse(ByteArray(0))!!
        assertFalse(pa.active())
        assertNull(pa.integerData())
    }

    // —— field 5：double ——

    @Test
    fun `field5 的 double 值能解出来`() {
        val bits = java.lang.Double.doubleToRawLongBits(23.5)
        val pa = PublicAttrValue.parse(concat(fieldVarint(1, 1), field64(5, bits)))!!
        assertEquals(23.5, pa.doubleData()!!, 1e-9)
        assertNull("double 没有整数口径", pa.integerData())
        assertNull("double 分支要把 int32Wire 清空", pa.int32Wire)
        assertTrue(pa.active())
    }

    // —— 其它字段 / 未知字段 ——

    @Test
    fun `format status level 与字符串字段按 UNI 口径处理`() {
        val bytes = concat(
            fieldVarint(1, 1),
            fieldVarint(2, 3),
            fieldVarint(3, 0),
            fieldBytes(6, "PAYLOAD".toByteArray()),
            fieldVarint(8, 5),
        )
        val pa = PublicAttrValue.parse(bytes)!!
        assertEquals(3, pa.format)
        assertEquals(0, pa.status)
        assertEquals(5, pa.level)
        assertNull("string 分支 data 置空", pa.data)
    }

    @Test
    fun `未知字段按 wire type 跳过`() {
        val bytes = concat(
            fieldVarint(1, 1),
            fieldBytes(7, byteArrayOf(0x11, 0x22, 0x33)),
            fieldVarint(4, 2),
        )
        val pa = PublicAttrValue.parse(bytes)!!
        assertEquals(1, pa.integerData())
        assertTrue(pa.active())
    }

    // —— 非法输入 ——

    @Test
    fun `截断与非法线格式返回 null`() {
        assertNull("null 输入", PublicAttrValue.parse(null))
        assertNull("tag = 0", PublicAttrValue.parse(byteArrayOf(0x00)))
        assertNull("field1 缺值", PublicAttrValue.parse(byteArrayOf(0x08)))
        assertNull("varint 没有终止字节", PublicAttrValue.parse(byteArrayOf(0x08, 0x80.toByte())))
        assertNull("field5 的 wire type 不对", PublicAttrValue.parse(byteArrayOf(0x28, 0x00)))
        assertNull("field4 sint32 超出 u32", PublicAttrValue.parse(fieldVarint(4, 0x1_0000_0000L)))
        assertNull(
            "枚举字段超出 Int.MAX_VALUE",
            PublicAttrValue.parse(fieldVarint(1, Int.MAX_VALUE.toLong() + 1)),
        )
        assertNull("超长消息", PublicAttrValue.parse(ByteArray(8193)))
    }

    // —— 车门合并口径 ——

    @Test
    fun `车门合并口径 data 0 关 1 开 2 关 其它读不到`() {
        assertEquals(true, McDoorSignals.interpretData(0))
        assertEquals(false, McDoorSignals.interpretData(1))
        assertEquals(true, McDoorSignals.interpretData(2))
        assertNull(McDoorSignals.interpretData(3))
        assertNull(McDoorSignals.interpretData(null))
    }
}
