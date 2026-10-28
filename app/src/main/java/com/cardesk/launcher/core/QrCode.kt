package com.cardesk.launcher.core

import kotlin.math.abs

/**
 * 纯 Kotlin 二维码编码器（ISO/IEC 18004）。
 *
 * ## 为什么要自己写一个
 *
 * 「设置 → 局域网传输」这一页现在只显示一行 `http://192.168.x.x:18765/?t=<token>`，
 * 用户得把这串地址**手动敲**到电脑浏览器里 —— 少一个字符就白等一趟。
 * 改成显示二维码、手机扫一下直接打开，是最省事、也最不容易出错的交互。
 *
 * 但这个项目**不允许新增任何运行时依赖**（没有 zxing，也没有别的三方库），
 * 所以只能自己实现一版**只覆盖我们需要的那一小块** ISO/IEC 18004：
 *
 * - 只做**字节模式**（内容是 URL / ASCII，偶尔带中文）；
 * - 只做**纠错等级 M**、**版本 1..10**（几十字节的 URL 根本用不到更大的版本）；
 * - 只输出**模块矩阵**，怎么画成 Bitmap 由调用方（`ui/widget/QrCodeView.kt`）负责。
 *
 * 本文件**不 import 任何 `android.*`**，所以能在 JVM 单测里直接跑。
 *
 * ## 编码流程
 *
 * 1. 数据编码 → **数据码字**（模式指示符 + 字符计数 + 数据 + 终止符 + 交替填充）；
 * 2. 分块 → 每块算 **Reed-Solomon 纠错码字**；
 * 3. **交错**（interleave）数据码字与纠错码字 → 最终码字序列；
 * 4. 画**功能图形**（定位 / 校正 / 时序 / 固定黑块 / 格式信息占位）；
 * 5. 码字按**之字形**填进数据区；
 * 6. 试 8 种**掩码**，选罚分最低的；
 * 7. 写**格式信息**（纠错等级 + 掩码号）与**版本信息**（版本 ≥ 7）。
 *
 * 坐标系统一为 `x = 列`（向右）、`y = 行`（向下），与 [Matrix.get] 一致。
 */
object QrCode {

    /** 错误纠正等级。 */
    enum class Ecc { L, M, Q, H }

    /**
     * 编码结果：一张 size × size 的模块矩阵。
     * get(x, y) 返回 true = **深色**模块。
     */
    class Matrix(val size: Int, private val modules: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
    }

    // ------------------------------------------------------------------ 规格表

    /**
     * 一个版本在纠错等级 **M** 下的分块规格。
     *
     * @param totalCodewords 该版本的总码字数（= 数据码字 + 纠错码字）
     * @param eccPerBlock 每块的纠错码字数
     * @param blockDataSizes 各块的数据码字数，**顺序即 ISO 表里的顺序（短块在前）**
     */
    private class VersionSpec(
        val totalCodewords: Int,
        val eccPerBlock: Int,
        val blockDataSizes: IntArray,
    ) {
        /** 数据码字总数 = 各块数据码字之和。 */
        val dataCodewords: Int get() = blockDataSizes.sum()
    }

    /**
     * 版本 1..10、纠错等级 M 的规格表。
     *
     * ## 坑：块的长度不总是一样
     *
     * 版本 8 起会出现「短块 + 长块」混排（8-M 是 2×38 + 2×39，10-M 是 4×43 + 1×44）。
     * **短块排在前面**，而且数据码字是**按这个顺序依次切给各块**的 ——
     * 顺序错了不会报任何错，只会扫出来一堆乱码。
     */
    private val SPECS = arrayOf(
        VersionSpec(26, 10, intArrayOf(16)),
        VersionSpec(44, 16, intArrayOf(28)),
        VersionSpec(70, 26, intArrayOf(44)),
        VersionSpec(100, 18, intArrayOf(32, 32)),
        VersionSpec(134, 24, intArrayOf(43, 43)),
        VersionSpec(172, 16, intArrayOf(27, 27, 27, 27)),
        VersionSpec(196, 18, intArrayOf(31, 31, 31, 31)),
        VersionSpec(242, 22, intArrayOf(38, 38, 39, 39)),
        VersionSpec(292, 22, intArrayOf(36, 36, 36, 37, 37)),
        VersionSpec(346, 26, intArrayOf(43, 43, 43, 43, 44)),
    )

    /**
     * 各版本的**校正图形中心坐标**（ISO/IEC 18004 表 E.1）。
     *
     * 版本 2..6 只有一个坐标（6 和 4v+10）；版本 7 起有 3 个坐标。
     * 第一个坐标 6 是「贴着时序图形那一列」，所以 (6, 6) 那个点和定位图形重叠，
     * 实际不会画。
     */
    private val ALIGN_CENTERS = arrayOf(
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

    // ------------------------------------------------------------------ 对外 API

    /**
     * 把文本编码成二维码矩阵。
     *
     * 字节模式（UTF-8）。
     *
     * @throws IllegalArgumentException 数据超出该版本容量，或纠错等级不是 M
     */
    fun encode(text: String, ecc: Ecc = Ecc.M): Matrix {
        // 只实现了 M：其余等级直接说清楚，别让调用方拿到一个「能编但扫出来是乱的」二维码。
        require(ecc == Ecc.M) { "本实现只支持纠错等级 M，收到 ${ecc.name}" }

        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = selectVersion(bytes.size)
        val spec = SPECS[version - 1]

        val dataCodewords = buildDataCodewords(bytes, version, spec)
        val codewords = interleave(dataCodewords, spec)

        val canvas = Canvas(17 + 4 * version)
        drawFunctionPatterns(canvas, version)
        placeCodewords(canvas, codewords)

        val mask = selectMask(canvas)
        applyMask(canvas, mask)

        // 格式信息 / 版本信息**不参与掩码**（它们不是数据区），且必须在掩码选完之后才写：
        // 格式信息里就带着掩码号本身。
        drawFormatInfo(canvas, ecc, mask)
        drawVersionInfo(canvas, version)

        return Matrix(canvas.size, canvas.modules)
    }

    // ------------------------------------------------------------------ 数据编码

    /**
     * 选版本：能放下就用**最小**的版本。
     *
     * 字节模式下每版多出来的只有几字节，所以版本号基本由**字节数**决定。
     *
     * ## 坑：版本 10 的字符计数是 16 bit
     *
     * 版本 1~9 的字符计数占 8 bit，版本 10 起占 16 bit ——
     * 也就是说版本 10 比版本 9 **多花掉 1 个字节**的计数位。
     * 别按「版本越大容量越大」的直觉想当然。
     */
    private fun selectVersion(byteLen: Int): Int {
        for (version in 1..SPECS.size) {
            val spec = SPECS[version - 1]
            val countBits = if (version <= 9) 8 else 16
            val neededBits = 4 + countBits + byteLen * 8
            if (neededBits <= spec.dataCodewords * 8) return version
        }
        throw IllegalArgumentException(
            "数据过长：${byteLen} 字节超出二维码版本 ${SPECS.size}（纠错等级 M）的容量"
        )
    }

    /**
     * 数据码字 = 模式指示符 + 字符计数 + 数据 + 终止符 + 交替填充。
     *
     * - 模式指示符 `0100` = 字节模式；
     * - 字符计数是**字节数**（不是字符数！中文一个字 3 个字节）；
     * - 终止符最多 4 个 0，不够 4 位就只补到填满；
     * - 之后补 0 到字节边界，再用 `0xEC` / `0x11` 交替填满。
     */
    private fun buildDataCodewords(bytes: ByteArray, version: Int, spec: VersionSpec): IntArray {
        val countBits = if (version <= 9) 8 else 16
        val capacityBits = spec.dataCodewords * 8

        val bits = IntArray(capacityBits)
        var len = 0

        fun put(value: Int, width: Int) {
            for (i in width - 1 downTo 0) {
                bits[len++] = (value shr i) and 1
            }
        }

        put(0b0100, 4)                     // 模式指示符：字节模式
        put(bytes.size, countBits)         // 字符计数 = 字节数
        for (b in bytes) put(b.toInt() and 0xFF, 8)

        // 终止符：最多 4 个 0
        val terminator = minOf(4, capacityBits - len)
        for (i in 0 until terminator) bits[len++] = 0
        // 补 0 到字节边界
        while (len % 8 != 0) bits[len++] = 0
        // 交替填充码字 0xEC / 0x11
        var pad = 0xEC
        while (len < capacityBits) {
            put(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }

        val out = IntArray(spec.dataCodewords)
        for (i in out.indices) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or bits[i * 8 + j]
            out[i] = v
        }
        return out
    }

    // ------------------------------------------------------------------ Reed-Solomon

    /** GF(256) 的指数表 / 对数表（本原多项式 `0x11D`，生成元 α = 2）。 */
    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        // 指数超过 255 就绕回来，方便直接用 GF_LOG[a] + GF_LOG[b] 查表
        for (i in 255 until 512) GF_EXP[i] = GF_EXP[i - 255]
    }

    /** GF(256) 乘法：α^i · α^j = α^(i+j)。 */
    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else GF_EXP[GF_LOG[a] + GF_LOG[b]]

    /** 生成多项式缓存：同一纠错长度只会算一次。 */
    private val generatorCache = HashMap<Int, IntArray>()

    /**
     * 生成多项式 `g(x) = ∏ (x - α^i)`，i = 0..n-1；系数按**高次在前**排列。
     *
     * 在 GF(2^8) 里减号等于加号，所以直接异或就行。
     */
    private fun rsGenerator(n: Int): IntArray {
        generatorCache[n]?.let { return it }
        var poly = intArrayOf(1)
        for (i in 0 until n) {
            val c = GF_EXP[i]                    // α^i
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]                       // 乘 x
                next[j + 1] = next[j + 1] xor gfMul(poly[j], c)      // 乘 α^i
            }
            poly = next
        }
        generatorCache[n] = poly
        return poly
    }

    /**
     * 求「数据多项式 × x^eccLen」除以生成多项式的余数 —— 那就是**纠错码字**。
     *
     * 用的是标准的「逐字节除法」：每进来一个数据字节，
     * 先把余数左移一字节，再用生成多项式（去掉首项）把最高位消掉。
     */
    private fun rsRemainder(data: IntArray, eccLen: Int): IntArray {
        val gen = rsGenerator(eccLen)
        val rem = IntArray(eccLen)
        for (b in data) {
            val factor = b xor rem[0]
            for (i in 0 until eccLen - 1) rem[i] = rem[i + 1]
            rem[eccLen - 1] = 0
            for (i in 0 until eccLen) rem[i] = rem[i] xor gfMul(factor, gen[i + 1])
        }
        return rem
    }

    /**
     * 分块 + 纠错 + **交错**。
     *
     * 交错顺序是标准规定的：**先按块轮转取数据码字，再按块轮转取纠错码字**（块内按顺序）。
     * 这一步是为了让「局部污损」均匀地摊到所有块上。
     */
    private fun interleave(data: IntArray, spec: VersionSpec): IntArray {
        val eccLen = spec.eccPerBlock
        val blockCount = spec.blockDataSizes.size
        val blocks = Array(blockCount) { IntArray(spec.blockDataSizes[it]) }
        val eccs = Array(blockCount) { IntArray(eccLen) }

        var pos = 0
        for (i in 0 until blockCount) {
            val len = spec.blockDataSizes[i]
            for (k in 0 until len) blocks[i][k] = data[pos++]
            eccs[i] = rsRemainder(blocks[i], eccLen)
        }

        val out = IntArray(spec.totalCodewords)
        var w = 0
        var maxData = 0
        for (len in spec.blockDataSizes) if (len > maxData) maxData = len
        for (i in 0 until maxData) {
            for (b in 0 until blockCount) if (i < blocks[b].size) out[w++] = blocks[b][i]
        }
        for (i in 0 until eccLen) {
            for (b in 0 until blockCount) out[w++] = eccs[b][i]
        }
        return out
    }

    // ------------------------------------------------------------------ 画布

    /**
     * 画布：模块颜色 + 「这是不是功能区」的标记。
     *
     * ## 为什么必须单独记功能区
     *
     * 数据码字是按之字形顺序「填满所有非功能模块」的，而掩码只允许作用在数据区上。
     * 没有这张表就分不清哪些格子该被填、哪些该被翻转 —— 而且这两种错都不会报错，
     * 只会让手机扫出来是乱码。
     */
    private class Canvas(val size: Int) {
        /** true = 深色。 */
        val modules = BooleanArray(size * size)

        /** true = 功能图形（不参与数据填充，也不参与掩码）。 */
        val isFunction = BooleanArray(size * size)

        /** 画一个功能模块。 */
        fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = dark
            isFunction[y * size + x] = true
        }

        /** 填一个数据模块。 */
        fun setData(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = dark
        }

        fun get(x: Int, y: Int): Boolean = modules[y * size + x]

        fun isFunctionAt(x: Int, y: Int): Boolean = isFunction[y * size + x]
    }

    /**
     * 画所有功能图形，并把**格式信息 / 版本信息区先占位**（浅色）。
     *
     * 占位是必须的：这两块区域不属于数据码字流，但**必须**在放数据之前就标成功能区，
     * 否则码字会填进去，格式信息就写不进去了。
     */
    private fun drawFunctionPatterns(canvas: Canvas, version: Int) {
        val size = canvas.size

        // 三个定位图形 + 分隔符
        drawFinder(canvas, 0, 0)
        drawFinder(canvas, size - 7, 0)
        drawFinder(canvas, 0, size - 7)

        // 时序图形：第 6 行 / 第 6 列，偶数坐标为深色
        for (i in 8 until size - 8) {
            canvas.setFunction(6, i, i % 2 == 0)
            canvas.setFunction(i, 6, i % 2 == 0)
        }

        // 校正图形
        drawAlignmentPatterns(canvas, version)

        // 固定黑模块：行 4v+9、列 8，恒为深色
        canvas.setFunction(8, size - 8, true)

        // 格式信息区（两份副本，共 30 个模块）先占位
        val format = formatPositions(size)
        for (copy in format) {
            for (p in copy) canvas.setFunction(p[0], p[1], false)
        }

        // 版本信息区（版本 ≥ 7 才存在，共 36 个模块）
        if (version >= 7) {
            for (copy in versionPositions(size)) {
                for (p in copy) canvas.setFunction(p[0], p[1], false)
            }
        }
    }

    /**
     * 定位图形（finder pattern）：7×7 的回字形 + 1 个模块宽的白色分隔符。
     *
     * 回字形 = 外圈深色 + 内圈深色（3×3）+ 其余浅色。
     *
     * ## 坑：分隔符必须是浅色
     *
     * 画的时候要多扫一格（dx/dy = -1 和 7），那几格是**分隔符**，必须留白。
     * 判定时每个条件都要带上「在 7×7 范围内」的约束 ——
     * 少带一个，分隔符就会被当成外圈画成深色：
     * 扫出来照样能读（解码器对分隔符不敏感），但**图形不合规**。
     */
    private fun drawFinder(canvas: Canvas, left: Int, top: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val x = left + dx
                val y = top + dy
                if (x < 0 || y < 0 || x >= canvas.size || y >= canvas.size) continue
                val dark = (dy in 0..6 && dx in setOf(0, 6)) ||      // 左右两条边
                    (dx in 0..6 && dy in setOf(0, 6)) ||          // 上下两条边
                    (dx in 2..4 && dy in 2..4)                     // 3×3 实心核心
                canvas.setFunction(x, y, dark)
            }
        }
    }

    /**
     * 校正图形（alignment pattern）：5×5，中心深色、内环浅色、外环深色。
     *
     * ## 坑：和时序图形打架时，校正图形赢
     *
     * 版本 ≥ 7 时有一个校正图形的中心落在第 6 行 / 第 6 列上，
     * 和时序图形重叠 —— 此时**以校正图形为准**（时序图形只画没被占用的那几格）。
     */
    private fun drawAlignmentPatterns(canvas: Canvas, version: Int) {
        val centers = ALIGN_CENTERS[version - 1]
        if (centers.isEmpty()) return
        val last = centers[centers.size - 1]
        for (row in centers) {
            for (col in centers) {
                // 跳过与三个定位图形重叠的那三个位置
                if (row == 6 && col == 6) continue
                if (row == 6 && col == last) continue
                if (row == last && col == 6) continue
                drawAlignment(canvas, col, row)
            }
        }
    }

    /** 画一个 5×5 校正图形，(centerX, centerY) 是中心。 */
    private fun drawAlignment(canvas: Canvas, centerX: Int, centerY: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val x = centerX + dx
                val y = centerY + dy
                if (x < 0 || y < 0 || x >= canvas.size || y >= canvas.size) continue
                val maxAbs = maxOf(abs(dx), abs(dy))
                val dark = maxAbs == 2 || (dx == 0 && dy == 0)
                canvas.setFunction(x, y, dark)
            }
        }
    }

    // ------------------------------------------------------------------ 数据放置

    /**
     * 把码字按**之字形**填进数据区。
     *
     * 规则（ISO/IEC 18004 §8.7）：
     *
     * - 从**右下角**开始，两列一组，**先右列后左列**；
     * - 每组内的行方向上下交替（第一组从下往上）；
     * - **第 6 列是时序图形，整列跳过** —— 所以 (8,7) 之后直接跳到 (5,4)；
     * - 功能区不填。
     */
    private fun placeCodewords(canvas: Canvas, codewords: IntArray) {
        val size = canvas.size
        var byteIndex = 0
        var bitIndex = 0
        var row = size - 1
        var dir = -1                       // -1 = 往上，+1 = 往下
        var col = size - 1

        while (col > 0) {
            // 第 6 列被时序图形占着：整组往左挪一列
            val right = if (col <= 6) col - 1 else col
            val left = right - 1

            var finished = false
            while (!finished) {
                for (x in intArrayOf(right, left)) {
                    if (canvas.isFunctionAt(x, row)) continue
                    val bit = if (byteIndex < codewords.size) {
                        (codewords[byteIndex] shr (7 - bitIndex)) and 1 == 1
                    } else {
                        false
                    }
                    canvas.setData(x, row, bit)
                    bitIndex++
                    if (bitIndex == 8) {
                        bitIndex = 0
                        byteIndex++
                    }
                }
                row += dir
                if (row < 0 || row >= size) {
                    row -= dir
                    dir = -dir
                    finished = true
                }
            }
            col -= 2
        }
    }

    // ------------------------------------------------------------------ 掩码

    /**
     * 选掩码：8 种全试一遍，取**罚分最低**的。
     *
     * 罚分是按 ISO/IEC 18004 §8.8.2 的四条规则算的，算的是**整张图**（含功能图形），
     * 因为掩码的目的是让整张图「看起来更随机」，不只是数据区。
     */
    private fun selectMask(canvas: Canvas): Int {
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            applyMask(canvas, mask)
            val score = penalty(canvas)
            applyMask(canvas, mask)      // 异或两次等于没异或：撤销
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
            }
        }
        return bestMask
    }

    /**
     * 施加 / 撤销掩码。
     *
     * 掩码只作用在**数据区**（功能区不动）—— 掩码号本身写在格式信息里，
     * 格式信息被自己的掩码翻掉的话，解码器就永远解不出来了。
     */
    private fun applyMask(canvas: Canvas, mask: Int) {
        val size = canvas.size
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (canvas.isFunctionAt(x, y)) continue
                if (maskApplies(mask, x, y)) {
                    val i = y * size + x
                    canvas.modules[i] = !canvas.modules[i]
                }
            }
        }
    }

    /** 8 种掩码的判定式（x = 列，y = 行）。 */
    private fun maskApplies(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        // 注意行、列不能互换：这里是「行号 / 2 + 列号 / 3」
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
    }

    /**
     * 掩码罚分（四条规则之和）。
     *
     * 1. 行/列里连续同色 ≥ 5：每个连续段罚 `3 + (长度 - 5)`；
     * 2. 2×2 同色方块：每个罚 3；
     * 3. 形如 `1011101` 且**前/后接 4 个浅色**：每个罚 40；
     * 4. 深色占比偏离 50%：每偏离 5% 罚 10。
     */
    private fun penalty(canvas: Canvas): Int {
        val size = canvas.size
        var score = 0

        // 规则 1：行、列里的连续同色段
        for (y in 0 until size) score += lineRunPenalty(canvas, y, true)
        for (x in 0 until size) score += lineRunPenalty(canvas, x, false)

        // 规则 2：2×2 同色方块
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = canvas.get(x, y)
                if (c == canvas.get(x + 1, y) && c == canvas.get(x, y + 1) && c == canvas.get(x + 1, y + 1)) {
                    score += 3
                }
            }
        }

        // 规则 3：1:1:3:1:1 图案（前后接 4 个浅色），行和列都查
        for (y in 0 until size) score += finderLikePenalty(canvas, y, true)
        for (x in 0 until size) score += finderLikePenalty(canvas, x, false)

        // 规则 4：深色模块占比偏离 50%
        var dark = 0
        for (i in canvas.modules.indices) if (canvas.modules[i]) dark++
        val percent = dark * 100.0 / (size * size)
        score += (abs(percent - 50.0) / 5.0).toInt() * 10

        return score
    }

    /**
     * 规则 1：一条线（行或列）里连续同色段的罚分。
     *
     * @param fixed 行号（isRow = true）或列号（isRow = false）
     */
    private fun lineRunPenalty(canvas: Canvas, fixed: Int, isRow: Boolean): Int {
        val size = canvas.size
        var score = 0
        var run = 0
        var previous = false
        for (i in 0 until size) {
            val current = if (isRow) canvas.get(i, fixed) else canvas.get(fixed, i)
            if (i == 0) {
                previous = current
                run = 1
            } else if (current == previous) {
                run++
            } else {
                if (run >= 5) score += 3 + (run - 5)
                previous = current
                run = 1
            }
        }
        if (run >= 5) score += 3 + (run - 5)
        return score
    }

    /**
     * 规则 3：找出 `1011101` 且前/后接 4 个浅色的图案，每处罚 40。
     *
     * 等价于查两个 11 格长的串：`10111010000` 与 `00001011101`。
     */
    private fun finderLikePenalty(canvas: Canvas, fixed: Int, isRow: Boolean): Int {
        val size = canvas.size
        val sb = StringBuilder(size)
        for (i in 0 until size) {
            sb.append(if (if (isRow) canvas.get(i, fixed) else canvas.get(fixed, i)) '1' else '0')
        }
        val line = sb.toString()
        var score = 0
        for (i in 0..size - 11) {
            if (line.startsWith("10111010000", i) || line.startsWith("00001011101", i)) score += 40
        }
        return score
    }

    // ------------------------------------------------------------------ 格式 / 版本信息

    /**
     * 格式信息的位置表（ISO/IEC 18004 图 25）。
     *
     * 返回**两份副本**，每份 15 项，每项是 `[列, 行]`。
     *
     * ## 坑：下标 0 是最低位（LSB）
     *
     * 这张表的下标对应 15 bit 数的 **bit0 = LSB**。
     * 记反了的话，扫出来的纠错等级和掩码号都是错的，而且 BCH 校验会直接失败。
     */
    private fun formatPositions(size: Int): Array<Array<IntArray>> {
        val copy1 = Array(15) { IntArray(2) }
        val copy2 = Array(15) { IntArray(2) }
        for (i in 0 until 15) {
            val a = when {
                i <= 5 -> intArrayOf(8, i)                 // 左上竖条（列 8，行 0..5）
                i == 6 -> intArrayOf(8, 7)                 // (列 8，行 7)
                i == 7 -> intArrayOf(8, 8)                 // (列 8，行 8)
                i == 8 -> intArrayOf(7, 8)                 // (列 7，行 8)
                else -> intArrayOf(14 - i, 8)             // 左上底边（列 5..0，行 8）
            }
            val b = when {
                i <= 7 -> intArrayOf(size - 1 - i, 8)      // 右上横条（列 size-1..size-8，行 8）
                else -> intArrayOf(8, size - 15 + i)       // 左下竖条（列 8，行 size-7..size-1）
            }
            copy1[i][0] = a[0]; copy1[i][1] = a[1]
            copy2[i][0] = b[0]; copy2[i][1] = b[1]
        }
        return arrayOf(copy1, copy2)
    }

    /**
     * 版本信息的位置表（ISO/IEC 18004 图 26），两份 3×6 的副本。
     *
     * 同样：下标 0 = 18 bit 数的 LSB。
     */
    private fun versionPositions(size: Int): Array<Array<IntArray>> {
        val a = Array(18) { IntArray(2) }
        val b = Array(18) { IntArray(2) }
        for (i in 0 until 18) {
            a[i][0] = size - 11 + i % 3
            a[i][1] = i / 3
            b[i][0] = i / 3
            b[i][1] = size - 11 + i % 3
        }
        return arrayOf(a, b)
    }

    /**
     * 算格式信息的 15 bit 值。
     *
     * - 5 bit 数据 = 2 bit 纠错等级 + 3 bit 掩码号；
     * - 等级编码：L=01、M=00、Q=11、H=10；
     * - BCH(15,5)，生成多项式 `0x537`，最后异或 `0x5412`。
     */
    private fun formatBits(ecc: Ecc, mask: Int): Int {
        val eccBits = when (ecc) {
            Ecc.L -> 0b01
            Ecc.M -> 0b00
            Ecc.Q -> 0b11
            Ecc.H -> 0b10
        }
        val data = (eccBits shl 3) or (mask and 0x07)
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        return ((data shl 10) or rem) xor 0x5412
    }

    /**
     * 算版本信息的 18 bit 值：6 bit 版本号 + BCH(18,6)，生成多项式 `0x1F25`。
     *
     * 已知值：v7 = 0x07C94、v8 = 0x085BC、v9 = 0x09A99、v10 = 0x0A4D3。
     */
    private fun versionBits(version: Int): Int {
        var rem = version
        repeat(12) {
            rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        }
        return (version shl 12) or rem
    }

    /** 把格式信息写进两份副本。 */
    private fun drawFormatInfo(canvas: Canvas, ecc: Ecc, mask: Int) {
        val bits = formatBits(ecc, mask)
        for (copy in formatPositions(canvas.size)) {
            for (i in copy.indices) {
                val dark = (bits shr i) and 1 == 1
                canvas.setFunction(copy[i][0], copy[i][1], dark)
            }
        }
    }

    /** 把版本信息写进两份副本（版本 ≥ 7）。 */
    private fun drawVersionInfo(canvas: Canvas, version: Int) {
        if (version < 7) return
        val bits = versionBits(version)
        for (copy in versionPositions(canvas.size)) {
            for (i in copy.indices) {
                val dark = (bits shr i) and 1 == 1
                canvas.setFunction(copy[i][0], copy[i][1], dark)
            }
        }
    }
}
