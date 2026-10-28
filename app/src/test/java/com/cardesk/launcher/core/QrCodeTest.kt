package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码编码器的单测。
 *
 * ## 为什么这个测试要自己再写一个**解码器**
 *
 * 如果拿 [QrCode] 自己的内部函数去验 [QrCode] 自己的输出，那等于**自己给自己发合格证**：
 * 编码器哪一步写错了，解码器就跟着错在哪边，测试永远是绿的。
 *
 * 所以这里从**原始矩阵**出发，独立重写了一遍完整的读码流程：
 *
 * 1. 按标准位置读**格式信息**，异或 `0x5412` 后做 BCH 校验，解出**纠错等级**和**掩码号**；
 * 2. 按解出的掩码**取消掩码**（只动数据区）；
 * 3. 按标准**之字形**顺序把码字读回来；
 * 4. **反交错**，还原出每块的数据码字和纠错码字；
 * 5. 对每块算 **syndromes**，必须全为 0（这一步证明纠错码字确实算对了）；
 * 6. 解析数据码字：模式指示符必须是 `0100`，长度字段要对得上，字节内容必须等于原文。
 *
 * 两边独立写，才算真的验过。
 */
class QrCodeTest {

    // ------------------------------------------------------------------ 用例

    /** 典型的局域网传输地址（单测的主场景）。 */
    private val url = "http://192.168.43.123:18765/?t=a1b2c3d4"

    /** 各版本的**字节模式容量**（纠错等级 M）：版本 n 能装多少字节。 */
    private fun capacityOf(version: Int): Int = when (version) {
        1 -> 14
        2 -> 26
        3 -> 42
        4 -> 62
        5 -> 84
        6 -> 106
        7 -> 122
        8 -> 152
        9 -> 180
        // 版本 10 的字符计数是 16 bit，比版本 9 多占 1 个字节，所以容量不是简单 +34
        10 -> 213
        else -> -1
    }

    // ------------------------------------------------------------------ 端到端

    @Test
    fun `典型 URL 能编码并被独立解码器还原`() {
        val matrix = QrCode.encode(url)
        val decoded = decode(matrix)
        assertEquals(url, decoded.text)
        assertEquals("M", decoded.ecc)
        assertEquals(3, decoded.version)          // 37 字节 → 版本 3
        assertEquals(29, matrix.size)             // 17 + 4 × 3
    }

    @Test
    fun `空串能编码并被还原`() {
        val matrix = QrCode.encode("")
        val decoded = decode(matrix)
        assertEquals("", decoded.text)
        assertEquals(1, decoded.version)
        assertEquals(21, matrix.size)
    }

    @Test
    fun `单字符能编码并被还原`() {
        val matrix = QrCode.encode("A")
        val decoded = decode(matrix)
        assertEquals("A", decoded.text)
        assertEquals(1, decoded.version)
    }

    @Test
    fun `中文文本能编码并被还原`() {
        val text = "车机桌面 · 局域网传输"
        val matrix = QrCode.encode(text)
        val decoded = decode(matrix)
        assertEquals(text, decoded.text)
        // 字节数 ≠ 字符数：这里正好能验出「字符计数填的是字节数」
        assertEquals(text.toByteArray(Charsets.UTF_8).size, decoded.payloadSize)
    }

    @Test
    fun `版本 3 与版本 4 的边界两侧都能正确往返`() {
        val v3 = "a".repeat(capacityOf(3))          // 正好装满版本 3
        val v4 = "a".repeat(capacityOf(3) + 1)      // 多一个字节 → 落到版本 4
        assertEquals(3, decode(QrCode.encode(v3)).version)
        assertEquals(4, decode(QrCode.encode(v4)).version)
        assertEquals(v3, decode(QrCode.encode(v3)).text)
        assertEquals(v4, decode(QrCode.encode(v4)).text)
    }

    @Test
    fun `版本 9 与版本 10 的边界两侧都能正确往返`() {
        val v9 = "b".repeat(capacityOf(9))          // 正好装满版本 9
        val v10 = "b".repeat(capacityOf(9) + 1)     // 多一个字节 → 落到版本 10
        assertEquals(9, decode(QrCode.encode(v9)).version)
        assertEquals(10, decode(QrCode.encode(v10)).version)
        assertEquals(v9, decode(QrCode.encode(v9)).text)
        assertEquals(v10, decode(QrCode.encode(v10)).text)
    }

    @Test
    fun `版本 10 用 16 位字符计数 依然能正确往返`() {
        // 版本 10 起字符计数从 8 bit 变成 16 bit，这一条专门盯这个切换点
        val text = "c".repeat(capacityOf(10))
        val matrix = QrCode.encode(text)
        assertEquals(57, matrix.size)               // 17 + 4 × 10
        val decoded = decode(matrix)
        assertEquals(10, decoded.version)
        assertEquals(text, decoded.text)
    }

    @Test
    fun `超长数据抛 IllegalArgumentException`() {
        val tooLong = "d".repeat(capacityOf(10) + 1)
        assertThrows(IllegalArgumentException::class.java) {
            QrCode.encode(tooLong)
        }
    }

    @Test
    fun `只支持纠错等级 M 其余等级明确报错`() {
        for (ecc in listOf(QrCode.Ecc.L, QrCode.Ecc.Q, QrCode.Ecc.H)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                QrCode.encode(url, ecc)
            }
            assertTrue("报错信息要说明只支持 M：${e.message}", e.message!!.contains("M"))
        }
    }

    @Test
    fun `掩码不总是 0 至少有一个用例走了非 0 掩码`() {
        // 8 种掩码都会算罚分，但要确认「选掩码」这一步真的不是恒等于 0
        val masks = listOf(
            url, "", "A", "车机桌面 · 局域网传输",
            "a".repeat(capacityOf(3)), "b".repeat(capacityOf(9) + 1),
            "HELLO WORLD", "0123456789",
        ).map { decode(QrCode.encode(it)).mask }
        assertTrue("这些用例应该至少命中一个非 0 掩码，实际=$masks", masks.any { it != 0 })
    }

    // ------------------------------------------------------------------ 结构性断言

    @Test
    fun `矩阵尺寸等于 17 加 4 倍版本`() {
        assertEquals(21, QrCode.encode("").size)
        assertEquals(29, QrCode.encode("a".repeat(capacityOf(3))).size)
        assertEquals(33, QrCode.encode("a".repeat(capacityOf(3) + 1)).size)
        assertEquals(45, QrCode.encode("a".repeat(capacityOf(7))).size)
        assertEquals(57, QrCode.encode("a".repeat(capacityOf(9) + 1)).size)
    }

    @Test
    fun `三个定位图形的形状正确`() {
        val m = QrCode.encode(url)
        val size = m.size
        assertFinder(m, 0, 0)                 // 左上
        assertFinder(m, size - 7, 0)          // 右上
        assertFinder(m, 0, size - 7)          // 左下
    }

    @Test
    fun `定位图形四周的分隔符必须是浅色`() {
        // 分隔符 = 定位图形内侧那圈 1 个模块宽的白边。
        // 画错了扫码照样能读（解码器对分隔符不敏感），但图形不合规 ——
        // 所以这条必须单独盯住。
        for (version in 1..10) {
            val m = QrCode.encode("s".repeat(capacityOf(version)))
            val size = m.size
            // 左上：右边一列 + 下边一行
            for (i in 0..7) {
                assertFalse("左上分隔符列 ($i) 应为浅色", m[7, i])
                assertFalse("左上分隔符行 ($i) 应为浅色", m[i, 7])
            }
            // 右上：左边一列 + 下边一行
            for (i in 0..7) {
                assertFalse("右上分隔符列 ($i) 应为浅色", m[size - 8, i])
                assertFalse("右上分隔符行 ($i) 应为浅色", m[size - 8 + i, 7])
            }
            // 左下：右边一列 + 上边一行
            for (i in 0..7) {
                assertFalse("左下分隔符列 ($i) 应为浅色", m[7, size - 8 + i])
                assertFalse("左下分隔符行 ($i) 应为浅色", m[i, size - 8])
            }
        }
    }

    @Test
    fun `时序图形第 6 行和第 6 列交替`() {
        for (version in 1..10) {
            val m = QrCode.encode("f".repeat(capacityOf(version)))
            assertEquals(17 + 4 * version, m.size)
            assertTiming(m, version)
        }
    }

    @Test
    fun `右下角固定黑模块是深色`() {
        for (version in 1..10) {
            val m = QrCode.encode("g".repeat(capacityOf(version)))
            assertTrue("版本 $version 的固定黑模块应该是深色", m[8, m.size - 8])
        }
    }

    @Test
    fun `格式信息两份副本内容一致`() {
        val m = QrCode.encode(url)
        val d = Decoder(m)
        assertTrue("两份格式信息副本必须一样", d.formatCopiesAgree())
    }

    // ------------------------------------------------------------------ BCH 自测

    @Test
    fun `格式信息 M 等级加掩码 0 等于 0x5412`() {
        assertEquals(0x5412, formatBits(0b00, 0))
    }

    @Test
    fun `版本信息的已知值对得上`() {
        // 标准里公开的 18 bit 值：版本号 + BCH(18,6)
        assertEquals(0x07C94, versionBits(7))
        assertEquals(0x085BC, versionBits(8))
        assertEquals(0x09A99, versionBits(9))
        assertEquals(0x0A4D3, versionBits(10))
    }

    @Test
    fun `版本信息在矩阵里的位置和位序正确`() {
        // 读回矩阵里的 18 bit，和上面的已知值逐个版本比对
        assertEquals(0x07C94, Decoder(QrCode.encode("h".repeat(capacityOf(7)))).readVersionInfo())
        assertEquals(0x085BC, Decoder(QrCode.encode("h".repeat(capacityOf(8)))).readVersionInfo())
        assertEquals(0x09A99, Decoder(QrCode.encode("h".repeat(capacityOf(9)))).readVersionInfo())
        assertEquals(0x0A4D3, Decoder(QrCode.encode("h".repeat(capacityOf(9) + 1))).readVersionInfo())
    }

    // ------------------------------------------------------------------ 独立解码器

    /** 解码结果。 */
    private class Decoded(
        val text: String,
        val ecc: String,
        val mask: Int,
        val version: Int,
        val payloadSize: Int,
    )

    /** 完整解码：格式信息 → 取消掩码 → 读码字 → 反交错 → RS 校验 → 解析数据。 */
    private fun decode(matrix: QrCode.Matrix): Decoded {
        val d = Decoder(matrix)
        val (eccBits, mask) = d.readFormat()
        d.unmask(mask)

        val spec = BLOCKS[d.version - 1]
        val total = spec.dataSizes.sum() + spec.eccPerBlock * spec.dataSizes.size
        val codewords = d.readCodewords(total)
        val blocks = d.deinterleave(spec, codewords)

        // Reed-Solomon 自检：每块的 syndromes 必须全为 0
        for (block in blocks) {
            val syndromes = d.syndromes(block, spec.eccPerBlock)
            for (i in syndromes.indices) {
                assertEquals("第 ${i + 1} 个 syndrome 必须为 0", 0, syndromes[i])
            }
        }

        // 拼回数据码字流
        val stream = IntArray(spec.dataSizes.sum())
        var p = 0
        for (block in blocks) for (v in block.data) stream[p++] = v

        // 解析
        var bitPos = 0
        fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                val bit = (stream[bitPos / 8] shr (7 - bitPos % 8)) and 1
                v = (v shl 1) or bit
                bitPos++
            }
            return v
        }

        assertEquals("模式指示符必须是字节模式 0100", 0b0100, readBits(4))
        val countBits = if (d.version <= 9) 8 else 16
        val count = readBits(countBits)
        val payload = ByteArray(count)
        for (i in 0 until count) payload[i] = readBits(8).toByte()

        val eccName = when (eccBits) {
            0b01 -> "L"
            0b00 -> "M"
            0b11 -> "Q"
            0b10 -> "H"
            else -> "?"
        }
        return Decoded(
            text = String(payload, Charsets.UTF_8),
            ecc = eccName,
            mask = mask,
            version = d.version,
            payloadSize = count,
        )
    }

    /** 分块规格（纠错等级 M，版本 1..10）。 */
    private class BlockSpec(val eccPerBlock: Int, val dataSizes: IntArray)

    /** 一个纠错块：数据码字 + 纠错码字。 */
    private class Block(val data: IntArray, val ecc: IntArray)

    /**
     * 规格表放在伴生对象里：嵌套类 [Decoder] 也要用，
     * 而嵌套类访问不到外部类的实例属性。
     */
    private companion object {
        /** 纠错等级 M、版本 1..10 的分块表。 */
        val BLOCKS = arrayOf(
            BlockSpec(10, intArrayOf(16)),
            BlockSpec(16, intArrayOf(28)),
            BlockSpec(26, intArrayOf(44)),
            BlockSpec(18, intArrayOf(32, 32)),
            BlockSpec(24, intArrayOf(43, 43)),
            BlockSpec(16, intArrayOf(27, 27, 27, 27)),
            BlockSpec(18, intArrayOf(31, 31, 31, 31)),
            BlockSpec(22, intArrayOf(38, 38, 39, 39)),
            BlockSpec(22, intArrayOf(36, 36, 36, 37, 37)),
            BlockSpec(26, intArrayOf(43, 43, 43, 43, 44)),
        )

        /** 校正图形中心坐标（版本 1..10）。 */
        val ALIGN_CENTERS = arrayOf(
            intArrayOf(),
            intArrayOf(6, 18),
            intArrayOf(6, 22),
            intArrayOf(6, 26),
            intArrayOf(6, 30),
            intArrayOf(6, 34),
            intArrayOf(6, 22, 38),
            intArrayOf(6, 24, 42),
            intArrayOf(6, 26, 46),
            intArrayOf(6, 28, 50),
        )

        /** 多项式取余（GF(2) 上的长除法）：格式信息的 BCH 校验用。 */
        fun polyMod(value: Int, generator: Int): Int {
            var v = value
            val gDeg = 31 - Integer.numberOfLeadingZeros(generator)
            while (v != 0) {
                val vDeg = 31 - Integer.numberOfLeadingZeros(v)
                if (vDeg < gDeg) break
                v = v xor (generator shl (vDeg - gDeg))
            }
            return v
        }

        /** 格式信息的 15 bit 值（BCH(15,5) + 异或 `0x5412`）。 */
        fun formatBits(eccBits: Int, mask: Int): Int {
            val data = (eccBits shl 3) or (mask and 0x07)
            var rem = data
            repeat(10) {
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            }
            return ((data shl 10) or rem) xor 0x5412
        }

        /** 版本信息的 18 bit 值（BCH(18,6)，生成多项式 `0x1F25`）。 */
        fun versionBits(version: Int): Int {
            var rem = version
            repeat(12) {
                rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            }
            return (version shl 12) or rem
        }
    }

    /**
     * 独立实现的解码器。
     *
     * 只依赖 [QrCode.Matrix] 的公开接口（size + get），
     * 里面所有的位置表、掩码判定、GF 运算都是**另写一遍**的。
     */
    private class Decoder(matrix: QrCode.Matrix) {
        val size: Int = matrix.size
        val version: Int = (size - 17) / 4

        /** 深色模块表。 */
        private val dark = BooleanArray(size * size) { i -> matrix[i % size, i / size] }

        /** 功能区（不参与数据填充、不参与掩码）。 */
        private val func = BooleanArray(size * size)

        init {
            // 三个定位图形 + 分隔符
            markFinder(0, 0)
            markFinder(size - 7, 0)
            markFinder(0, size - 7)
            // 时序图形
            for (i in 8 until size - 8) {
                mark(6, i)
                mark(i, 6)
            }
            // 校正图形
            val centers = ALIGN_CENTERS[version - 1]
            if (centers.isNotEmpty()) {
                val last = centers[centers.size - 1]
                for (row in centers) {
                    for (col in centers) {
                        if (row == 6 && col == 6) continue
                        if (row == 6 && col == last) continue
                        if (row == last && col == 6) continue
                        for (dy in -2..2) for (dx in -2..2) mark(col + dx, row + dy)
                    }
                }
            }
            // 固定黑模块
            mark(8, size - 8)
            // 格式信息区
            for (copy in formatPositions()) for (p in copy) mark(p[0], p[1])
            // 版本信息区
            if (version >= 7) for (copy in versionPositions()) for (p in copy) mark(p[0], p[1])
        }

        private fun mark(x: Int, y: Int) {
            if (x in 0 until size && y in 0 until size) func[y * size + x] = true
        }

        private fun markFinder(left: Int, top: Int) {
            for (dy in -1..7) for (dx in -1..7) mark(left + dx, top + dy)
        }

        /**
         * 格式信息的位置表：两份副本，每份 15 项 `[列, 行]`。
         *
         * 下标 0 = 15 bit 数的**最低位**。
         */
        private fun formatPositions(): List<List<IntArray>> {
            val copy1 = ArrayList<IntArray>()
            val copy2 = ArrayList<IntArray>()
            for (i in 0 until 15) {
                copy1.add(
                    when {
                        i < 6 -> intArrayOf(8, i)
                        i == 6 -> intArrayOf(8, 7)
                        i == 7 -> intArrayOf(8, 8)
                        i == 8 -> intArrayOf(7, 8)
                        else -> intArrayOf(14 - i, 8)
                    }
                )
                copy2.add(
                    when {
                        i < 8 -> intArrayOf(size - 1 - i, 8)
                        else -> intArrayOf(8, size - 15 + i)
                    }
                )
            }
            return listOf(copy1, copy2)
        }

        /** 版本信息的位置表：两份 3×6 副本，下标 0 = 最低位。 */
        private fun versionPositions(): List<List<IntArray>> {
            val a = ArrayList<IntArray>()
            val b = ArrayList<IntArray>()
            for (i in 0 until 18) {
                a.add(intArrayOf(size - 11 + i % 3, i / 3))
                b.add(intArrayOf(i / 3, size - 11 + i % 3))
            }
            return listOf(a, b)
        }

        /** 读格式信息：返回 `(纠错等级编码, 掩码号)`，同时做 BCH 自校验。 */
        fun readFormat(): Pair<Int, Int> {
            val copies = formatPositions()
            val bits = readBitsAt(copies[0])
            // 两份副本必须一致，否则说明写格式信息时就已经错了
            assertEquals("两份格式信息副本不一致", bits, readBitsAt(copies[1]))
            val raw = bits xor 0x5412
            assertEquals("格式信息 BCH 校验失败", 0, polyMod(raw, 0x537))
            val data = raw ushr 10
            return ((data ushr 3) and 0x03) to (data and 0x07)
        }

        /** 两份格式信息副本是否一致。 */
        fun formatCopiesAgree(): Boolean {
            val copies = formatPositions()
            return readBitsAt(copies[0]) == readBitsAt(copies[1])
        }

        /** 读回矩阵里的 18 bit 版本信息（版本 < 7 返回 0）。 */
        fun readVersionInfo(): Int {
            if (version < 7) return 0
            val copies = versionPositions()
            val a = readBitsAt(copies[0])
            val b = readBitsAt(copies[1])
            assertEquals("两份版本信息副本不一致", a, b)
            return a
        }

        private fun readBitsAt(positions: List<IntArray>): Int {
            var bits = 0
            for (i in positions.indices) {
                val p = positions[i]
                if (dark[p[1] * size + p[0]]) bits = bits or (1 shl i)
            }
            return bits
        }

        /** 取消掩码（只动数据区）。 */
        fun unmask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (func[y * size + x]) continue
                    if (maskHit(mask, x, y)) dark[y * size + x] = !dark[y * size + x]
                }
            }
        }

        /** 8 种掩码的判定式（x = 列，y = 行）。 */
        private fun maskHit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        }

        /**
         * 按标准之字形顺序把数据区读回码字。
         *
         * 列对从右往左取，每组两列（右列在前）；
         * 第 6 列是时序图形，整列跳过，所以 (8,7) 之后直接是 (5,4)。
         */
        fun readCodewords(count: Int): IntArray {
            val out = IntArray(count)
            val pairs = ArrayList<IntArray>()
            var c = size - 1
            while (c > 0) {
                val right = if (c <= 6) c - 1 else c
                pairs.add(intArrayOf(right, right - 1))
                c -= 2
            }

            var bitPos = 0
            var upward = true
            for (pair in pairs) {
                val rows = if (upward) (size - 1 downTo 0) else (0 until size)
                for (r in rows) {
                    for (x in pair) {
                        if (func[r * size + x]) continue
                        val idx = bitPos / 8
                        if (idx < count && dark[r * size + x]) {
                            out[idx] = out[idx] or (1 shl (7 - bitPos % 8))
                        }
                        bitPos++
                    }
                }
                upward = !upward
            }
            return out
        }

        /** 反交错：还原每块的数据码字与纠错码字。 */
        fun deinterleave(spec: BlockSpec, codewords: IntArray): List<Block> {
            val blocks = spec.dataSizes.map { Block(IntArray(it), IntArray(spec.eccPerBlock)) }
            var idx = 0
            var maxData = 0
            for (s in spec.dataSizes) if (s > maxData) maxData = s
            for (i in 0 until maxData) {
                for (b in blocks) if (i < b.data.size) b.data[i] = codewords[idx++]
            }
            for (i in 0 until spec.eccPerBlock) {
                for (b in blocks) b.ecc[i] = codewords[idx++]
            }
            assertEquals("码字总数应该刚好用完", codewords.size, idx)
            return blocks
        }

        /**
         * Reed-Solomon 校验：把整块（数据 + 纠错）当成码字多项式，
         * 在 α^0..α^(n-1) 上求值，**必须全是 0**。
         */
        fun syndromes(block: Block, eccLen: Int): IntArray {
            val codeword = IntArray(block.data.size + block.ecc.size)
            System.arraycopy(block.data, 0, codeword, 0, block.data.size)
            System.arraycopy(block.ecc, 0, codeword, block.data.size, block.ecc.size)

            val out = IntArray(eccLen)
            for (i in 0 until eccLen) {
                var root = 1
                for (k in 0 until i) root = Gf.mul(root, 2)   // α^i
                var r = 0
                for (b in codeword) r = Gf.mul(r, root) xor b
                out[i] = r
            }
            return out
        }
    }

    /** GF(256)：本原多项式 `0x11D`，生成元 α = 2。 */
    private object Gf {
        val exp = IntArray(512)
        val log = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
            }
            for (i in 255 until 512) exp[i] = exp[i - 255]
        }

        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    }

    /** 定位图形（7×7 回字形）的形状断言。 */
    private fun assertFinder(m: QrCode.Matrix, left: Int, top: Int) {
        for (dy in 0 until 7) {
            for (dx in 0 until 7) {
                val expected = dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)
                assertEquals(
                    "定位图形 ($left,$top) 的模块 ($dx,$dy)",
                    expected,
                    m[left + dx, top + dy]
                )
            }
        }
    }

    /**
     * 时序图形断言：第 6 行 / 第 6 列交替。
     *
     * 版本 ≥ 7 时有一个校正图形的中心正好落在第 6 行 / 第 6 列上，
     * 那 5 个模块**以校正图形为准**，所以要跳过。
     */
    private fun assertTiming(m: QrCode.Matrix, version: Int) {
        val centers = ALIGN_CENTERS[version - 1]
        val rowSpans = ArrayList<IntRange>()
        val colSpans = ArrayList<IntRange>()
        if (centers.isNotEmpty()) {
            val last = centers[centers.size - 1]
            for (row in centers) {
                for (col in centers) {
                    if (row == 6 && col == 6) continue
                    if (row == 6 && col == last) continue
                    if (row == last && col == 6) continue
                    if (row == 6) rowSpans.add((col - 2)..(col + 2))
                    if (col == 6) colSpans.add((row - 2)..(row + 2))
                }
            }
        }
        for (i in 8 until m.size - 8) {
            if (rowSpans.none { i in it }) {
                assertEquals("第 6 行 ($i) 应为深色", i % 2 == 0, m[i, 6])
            }
            if (colSpans.none { i in it }) {
                assertEquals("第 6 列 ($i) 应为深色", i % 2 == 0, m[6, i])
            }
        }
    }
}
