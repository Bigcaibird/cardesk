package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VinReader] 的 JVM 单测。
 *
 * 只测纯逻辑（清洗 / 校验 / PA 消息里的 string 字段），不碰 Android 运行时，
 * 也不真去读车机 —— 真机读取的返回值无法在 JVM 里复现，能钉死的是口径：
 * 合法 VIN 必须原样通过；I/O/Q、长度不对、空串必须是「读不到」；
 * `PublicAttrValue` 的 field 6（string）要能取出 VIN。
 */
class VinReaderTest {

    /** 一个形状合法的 VIN（上汽大众示例，字母里没有 I/O/Q）。 */
    private val validVin = "LSVAU2180N2123456"

    // ---------------------------------------------------------------- 清洗/校验

    @Test
    fun normalizeAcceptsLegalVin() {
        assertEquals(validVin, VinReader.normalize(validVin))
    }

    @Test
    fun normalizeTrimsWhitespaceAndInvisibleChars() {
        // 真机上 byte[] 转出来的串经常带 NUL 填充 / 换行 / 前后空格。
        assertEquals(validVin, VinReader.normalize("  $validVin \u0000\r\n\t"))
        assertEquals(validVin, VinReader.normalize("\uFEFF$validVin"))
    }

    @Test
    fun normalizeUppercases() {
        assertEquals(validVin, VinReader.normalize(validVin.lowercase()))
    }

    @Test
    fun normalizeRejectsIllegalLetters() {
        // VIN 标准禁用 I / O / Q（避免与 1 / 0 混淆）。
        assertNull(VinReader.normalize("LSVAU2180N21234I6"))
        assertNull(VinReader.normalize("LSVAU2180N21234O6"))
        assertNull(VinReader.normalize("LSVAU2180N21234Q6"))
    }

    @Test
    fun normalizeRejectsWrongLength() {
        assertNull(VinReader.normalize("LSVAU2180N212345"))   // 16 位
        assertNull(VinReader.normalize("LSVAU2180N21234567")) // 18 位
        assertNull(VinReader.normalize(validVin + validVin))
    }

    @Test
    fun normalizeRejectsNullOrBlank() {
        assertNull(VinReader.normalize(null))
        assertNull(VinReader.normalize(""))
        assertNull(VinReader.normalize("   "))
        assertNull(VinReader.normalize("\u0000\u0000"))
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertNull(VinReader.normalize("NO_VIN"))
        assertNull(VinReader.normalize("LSVAU2180N21234é6"))
        assertNull(VinReader.normalize("LSVAU2180N21234-6"))
    }

    // ---------------------------------------------------------------- PA 消息 field 6

    @Test
    fun parsesVinFromPublicAttrValueStringField() {
        val bytes = concat(
            fieldVarint(1, 1L), // availability = 1（原车 getVin 不依赖它，这里顺带钉住 active）
            fieldBytes(6, validVin.toByteArray(Charsets.UTF_8)), // VIN 在 field 6（string）
        )
        val payload = PublicAttrValue.parse(bytes)
        assertEquals(validVin, payload?.stringData)
        assertTrue(payload?.active() == true)
        // 走 VinReader 的同一套清洗后仍是同一个 VIN。
        assertEquals(validVin, payload?.stringData?.let { VinReader.normalize(it) })
    }

    @Test
    fun parsesVinWhenStringFieldComesAfterAvailability() {
        // 字段顺序不固定：field 6 在前也能解（线格式是 tag 驱动的）。
        val bytes = concat(
            fieldBytes(6, validVin.toByteArray(Charsets.UTF_8)),
            fieldVarint(3, 0L), // status = 0
            fieldVarint(1, 1L), // availability = 1
        )
        assertEquals(validVin, PublicAttrValue.parse(bytes)?.stringData)
    }

    @Test
    fun messageWithoutStringFieldHasNoVin() {
        // 只有 availability（没有 field 6）⇒ stringData 为 null，VIN 读不到。
        assertNull(PublicAttrValue.parse(fieldVarint(1, 1L))?.stringData)
    }

    // ---------------------------------------------------------------- 测试用线格式

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
        concat(varint((field.toLong() shl 3)), varint(value))

    /** wire type 2：长度前缀 + 字节负载。 */
    private fun fieldBytes(field: Int, payload: ByteArray): ByteArray =
        concat(varint((field.toLong() shl 3) or 2L), varint(payload.size.toLong()), payload)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }
}
