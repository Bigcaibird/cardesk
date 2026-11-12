package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「封面做卡片背景」的规格单测（**纯逻辑**，不碰 Android 图形对象）。
 *
 * 这里锁住的是这个功能**唯一不能退让的东西**：文字压在封面上还读得清。
 *
 * 口径见 [CoverBackdrop] 的 KDoc：以**最弱那档文字**（`Skin.TEXT_DIM`）
 * 与背景的对比度 ≥ **3:1** 为底线，背景的亮度区间由此反推。
 */
class CoverBackdropTest {

    // —— 夜间（默认主题）——
    /** 卡面 `#1F232D`。 */
    private val nightSurface = 0xFF1F232D.toInt()

    /** 最弱那档文字 `#757E8E`（= `Skin.TEXT_DIM` = `pal.textFaint`）。 */
    private val nightWeakText = 0xFF757E8E.toInt()

    /** 次要文字 `#A2AAB8`（= `Skin.TEXT_SOFT` = `pal.textDim`）。 */
    private val nightSoftText = 0xFFA2AAB8.toInt()

    /** 主文字 `#EDEFF3`（= `Skin.TEXT`）。 */
    private val nightPrimaryText = 0xFFEDEFF3.toInt()

    // —— 日间 ——
    /** 卡面纯白。 */
    private val daySurface = 0xFFFFFFFF.toInt()

    /** 最弱那档文字 `#8B92A0`。 */
    private val dayWeakText = 0xFF8B92A0.toInt()

    private fun pixels(vararg colors: Int): IntArray = IntArray(colors.size) { colors[it] }

    /** 夜间该用的亮度区间。 */
    private fun nightRange(): DoubleArray = CoverBackdrop.targetRange(nightSurface, nightWeakText)

    // ------------------------------------------------------------------ 亮度基础

    @Test
    fun 相对亮度_纯黑是0纯白是1() {
        assertEquals(0.0, CoverBackdrop.relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, CoverBackdrop.relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test
    fun 对比度_黑白是21比1() {
        assertEquals(21.0, CoverBackdrop.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun 对比度_同色是1比1() {
        assertEquals(1.0, CoverBackdrop.contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-9)
    }

    @Test
    fun 混合_半透明时正好落在中间() {
        val mixed = CoverBackdrop.mixColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 128)
        assertEquals(128, mixed and 0xFF)
        assertEquals(0xFF, mixed ushr 24)
    }

    // ------------------------------------------------------------------ 亮度区间

    @Test
    fun 目标区间_夜间是上限而不是下限() {
        val range = nightRange()
        assertEquals("夜间文字比卡面亮，底必须压暗 → 下限是 0", 0.0, range[0], 1e-9)
        // (0.2065 + 0.05) / 3.05 − 0.05 = 0.0341
        assertEquals("夜间背景亮度上限", 0.0341, range[1], 0.0005)
    }

    @Test
    fun 目标区间_日间是下限() {
        val range = CoverBackdrop.targetRange(daySurface, dayWeakText)
        assertEquals("日间文字比卡面暗，底必须提亮 → 上限是 1", 1.0, range[1], 1e-9)
        assertTrue("日间背景亮度下限应在 0.97 以上，实际 ${range[0]}", range[0] > 0.97)
    }

    // ------------------------------------------------------------------ 亮度映射

    @Test
    fun 亮度映射_最亮的像素落到上限() {
        val range = nightRange()
        val cover = pixels(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val mapped = CoverBackdrop.toneMap(cover, range[0], range[1])
        var max = 0.0
        for (p in mapped) {
            val l = CoverBackdrop.relativeLuminance(p)
            if (l > max) max = l
        }
        assertTrue("映射之后最亮的像素亮度是 $max，应 ≤ ${range[1]}", max <= range[1] + 1e-6)
    }

    @Test
    fun 亮度映射_压完之后三档文字都读得清() {
        val covers = listOf(
            0xFF000000.toInt(),
            0xFF1E90FF.toInt(), // 纯蓝
            0xFFFF2D2D.toInt(), // 纯红
            0xFFFFE100.toInt(), // 纯黄
            0xFF808080.toInt(),
            0xFFC0C0C0.toInt(),
            0xFFFFFFFF.toInt(), // 最坏情况
        )
        val range = nightRange()
        for (cover in covers) {
            val mapped = CoverBackdrop.toneMap(pixels(cover), range[0], range[1])
            for (p in mapped) {
                val weak = CoverBackdrop.contrastRatio(nightWeakText, p)
                val soft = CoverBackdrop.contrastRatio(nightSoftText, p)
                val primary = CoverBackdrop.contrastRatio(nightPrimaryText, p)
                assertTrue(
                    "封面 ${Integer.toHexString(cover)}：最弱文字对比度只有 $weak",
                    weak >= CoverBackdrop.MIN_CONTRAST,
                )
                assertTrue(
                    "封面 ${Integer.toHexString(cover)}：次要文字对比度只有 $soft",
                    soft >= 4.5,
                )
                assertTrue(
                    "封面 ${Integer.toHexString(cover)}：主文字对比度只有 $primary",
                    primary >= 7.0,
                )
            }
        }
    }

    @Test
    fun 亮度映射_色相不变() {
        // 蓝封面压完应该仍然是「偏蓝的深灰」，不能变成纯灰 ——
        // 这是「在线性光空间缩放」而不是「混向中性灰」的意义所在。
        val range = nightRange()
        val blue = 0xFF1E90FF.toInt()
        val mapped = CoverBackdrop.toneMap(pixels(blue), range[0], range[1])[0]
        val r = (mapped shr 16) and 0xFF
        val b = mapped and 0xFF
        assertTrue("蓝封面映射之后 b=$b r=$r，蓝色分量应该明显更高", b > r + 8)
    }

    @Test
    fun 亮度映射_暗部被提起来() {
        // 这是「亮度映射」相对「整体乘一个系数」的关键差别：
        // gamma < 1 会把暗部抬起来，整张图的结构（渐变、亮斑）才看得出来。
        val range = nightRange()
        val cover = pixels(0xFF101010.toInt(), 0xFFFFFFFF.toInt())
        val lifted = CoverBackdrop.toneMap(cover, range[0], range[1])
        val flat = CoverBackdrop.toneMap(cover, range[0], range[1], gamma = 1.0)
        val lLifted = CoverBackdrop.relativeLuminance(lifted[0])
        val lFlat = CoverBackdrop.relativeLuminance(flat[0])
        assertTrue(
            "暗部应该被提起来：映射后 $lLifted vs 线性 $lFlat",
            lLifted > lFlat * 2.0,
        )
    }

    @Test
    fun 亮度映射_结构还在_不是一片均匀色() {
        // 「整体乘系数」会把所有像素压成同一个亮度（封面就等于没画）；
        // 亮度映射必须保留明暗差别 —— 这条锁的就是它。
        val range = nightRange()
        val cover = pixels(0xFF101010.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())
        val mapped = CoverBackdrop.toneMap(cover, range[0], range[1])
        val ls = mapped.map { CoverBackdrop.relativeLuminance(it) }
        assertTrue("三个像素的亮度应该拉开差距，实际 ${ls.toList()}", ls[2] - ls[0] > ls[2] * 0.5)
    }

    @Test
    fun 亮度映射_空像素数组原样返回() {
        assertEquals(0, CoverBackdrop.toneMap(IntArray(0), 0.0, 0.0341).size)
    }

    @Test
    fun 亮度映射_全黑原样返回() {
        val black = pixels(0xFF000000.toInt())
        val mapped = CoverBackdrop.toneMap(black, 0.0, 0.0341)
        assertEquals(0xFF000000.toInt(), mapped[0])
    }

    @Test
    fun 亮度映射_变亮方向也不会削顶() {
        // 日间模式要把底**提亮**（白底深字）。
        // 如果用「乘系数」提亮，深蓝封面的蓝通道一乘就到 255 削顶，
        // 实际亮度掉回目标以下 ——「文字读得清」这条保证就破了。
        val range = CoverBackdrop.targetRange(daySurface, dayWeakText)
        val dark = 0xFF120C48.toInt() // 很深的蓝：线性光里 b 通道很小，乘系数必然削顶
        val mapped = CoverBackdrop.toneMap(pixels(dark), range[0], range[1])[0]
        val l = CoverBackdrop.relativeLuminance(mapped)
        assertTrue("映射之后亮度是 $l，应落在 [${range[0]}, ${range[1]}] 里", l >= range[0] && l <= range[1])
        val c = CoverBackdrop.contrastRatio(dayWeakText, mapped)
        assertTrue(
            "日间最弱文字的对比度只有 $c，应 ≥ ${CoverBackdrop.MIN_CONTRAST}",
            c >= CoverBackdrop.MIN_CONTRAST,
        )
    }

    // ------------------------------------------------------------------ 日间

    @Test
    fun 日间_深色文字要求底是亮的() {
        // 日间是白底深字，和夜间刚好相反：底必须够亮，深色文字才读得清。
        val range = CoverBackdrop.targetRange(daySurface, dayWeakText)
        val cover = 0xFF202020.toInt()
        val mapped = CoverBackdrop.toneMap(pixels(cover), range[0], range[1])[0]
        val c = CoverBackdrop.contrastRatio(dayWeakText, mapped)
        assertTrue(
            "日间最弱文字的对比度只有 $c，应 ≥ ${CoverBackdrop.MIN_CONTRAST}",
            c >= CoverBackdrop.MIN_CONTRAST,
        )
        // 白底深字是对称的：底只要有一点可见的深色，深字就读不出来。
        // 所以日间里封面背景基本看不出颜色（只剩极淡的色偏），这是物理限制。
        val gray = (mapped shr 16) and 0xFF
        assertTrue("日间映射之后灰度只有 $gray，应该还是接近白的", gray >= 200)
    }

    // ------------------------------------------------------- 回归：量化台阶

    @Test
    fun 日间_每个像素都要够亮_不能有像素卡在量化台阶上() {
        // ## 这条用例守的是什么
        //
        // 日间模式对背景的要求是**亮度下限**（`L_bg ≥ 0.9743`，白底深字）：
        // 任何比下限暗的像素，都会让最弱那档文字的对比度掉到 3:1 以下。
        //
        // 之前「夹回区间」的循环用的是**固定 5% 混白**：
        // 灰度 251 混 5% 白，算完还是 251 —— 8bit 的量化台阶比步长还大，
        // 固定比例的步子**永远跨不过去**，像素就卡死在规格线以下。
        //
        // 真实封面（QQ 音乐《青花瓷》）实测就有一小撮像素卡在灰度 240~253，
        // 其中灰度 240 那个对最弱文字的对比度只有 2.89:1 —— 低于 3.05 底线。
        //
        // 所以这里断言：**输出里每一个像素**都要达到下限。
        val range = CoverBackdrop.targetRange(daySurface, dayWeakText)
        val lo = range[0]
        val hi = range[1]

        // 故意混一批「映射后会落在 251 附近」的灰度 —— 那是 8bit 台阶最容易卡住的地方
        val src = intArrayOf(
            0xFF000000.toInt(),
            0xFF404040.toInt(),
            0xFF7F7F7F.toInt(),
            0xFF808080.toInt(),
            0xFFC0C0C0.toInt(),
            0xFFFCFCFC.toInt(),
            0xFFFFFFFF.toInt(),
        )
        val out = CoverBackdrop.toneMap(src, lo, hi)

        out.forEachIndexed { i, p ->
            val l = CoverBackdrop.relativeLuminance(p)
            assertTrue(
                "第 $i 个像素亮度只有 $l（下限 $lo），卡在 8bit 量化台阶上没爬上来",
                l >= lo - 1e-9,
            )
            assertTrue("第 $i 个像素亮度 $l 超过上限 $hi", l <= hi + 1e-9)
            val c = CoverBackdrop.contrastRatio(dayWeakText, p)
            assertTrue(
                "第 $i 个像素对最弱文字的对比度只有 $c，应 ≥ ${CoverBackdrop.MIN_CONTRAST}",
                c >= CoverBackdrop.MIN_CONTRAST,
            )
        }
    }

    @Test
    fun 夜间_每个像素都要够暗_不能有像素卡在量化台阶上() {
        // 夜间是**亮度上限**（`L_bg ≤ 0.0341`），同理不能有像素压不下去。
        val range = CoverBackdrop.targetRange(nightSurface, nightWeakText)
        val lo = range[0]
        val hi = range[1]
        val src = intArrayOf(
            0xFF000000.toInt(),
            0xFF303030.toInt(),
            0xFF7F7F7F.toInt(),
            0xFFB4B4B4.toInt(),
            0xFFCCCCCC.toInt(),
            0xFFFFFFFF.toInt(),
        )
        val out = CoverBackdrop.toneMap(src, lo, hi)
        out.forEachIndexed { i, p ->
            val l = CoverBackdrop.relativeLuminance(p)
            assertTrue("第 $i 个像素亮度 $l 超过上限 $hi", l <= hi + 1e-9)
            val c = CoverBackdrop.contrastRatio(nightWeakText, p)
            assertTrue(
                "第 $i 个像素对最弱文字的对比度只有 $c，应 ≥ ${CoverBackdrop.MIN_CONTRAST}",
                c >= CoverBackdrop.MIN_CONTRAST,
            )
        }
    }
}
