package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [AmbientColorSpace] 单测（纯函数，不依赖 Android）。
 *
 * 验的是三件事：
 * 1. **CIEDE2000 算得对** —— 用 Sharma-Wu-Dalal (2005) 的标准测试数据对；
 * 2. **OKLCH 往返不丢信息** —— 转成 RGB 再转回来，L 应该基本不变；
 * 3. **色域收敛不偏色** —— 越界时压彩度，而不是裁 RGB。
 */
class AmbientColorSpaceTest {

    // ------------------------------------------------------------ CIEDE2000

    @Test
    fun `同色差为零`() {
        val lab = Triple(50f, 2.5f, 0f)
        assertEquals(0f, AmbientColorSpace.deltaE00(lab, lab), 0.0001f)
    }

    @Test
    fun `CIEDE2000 与标准测试数据一致`() {
        // Sharma, Wu & Dalal (2005) 论文附带的测试数据。
        // 这几个是「彩度 / 色相」都不同的组合，最容易暴露公式写错。
        // 每一项是 (lab1, lab2, 期望 ΔE00)。
        val cases = listOf(
            Triple(
                Triple(50.0000f, 2.6772f, -79.7751f),
                Triple(50.0000f, 0.0000f, -82.7485f),
                2.0425f,
            ),
            Triple(
                Triple(50.0000f, -1.3802f, -84.2814f),
                Triple(50.0000f, 0.0000f, -82.7485f),
                1.0000f,
            ),
            Triple(
                Triple(50.0000f, 2.5000f, 0.0000f),
                Triple(50.0000f, 0.0000f, -2.5000f),
                4.3065f,
            ),
            Triple(
                Triple(50.0000f, 2.5000f, 0.0000f),
                Triple(73.0000f, 25.0000f, -18.0000f),
                27.1492f,
            ),
            Triple(
                Triple(50.0000f, 2.5000f, 0.0000f),
                Triple(50.0000f, 3.1736f, 0.5854f),
                1.0000f,
            ),
        )
        for ((lab1, lab2, expected) in cases) {
            val got = AmbientColorSpace.deltaE00(lab1, lab2)
            assertEquals("ΔE00 应为 $expected，实际 $got", expected, got, 0.0001f)
        }
    }

    @Test
    fun `CIEDE2000 是对称的`() {
        val a = Triple(40f, 12f, -30f)
        val b = Triple(70f, -5f, 20f)
        assertEquals(
            AmbientColorSpace.deltaE00(a, b),
            AmbientColorSpace.deltaE00(b, a),
            0.0001f,
        )
    }

    // ------------------------------------------------------------ OKLCH 往返

    @Test
    fun `OKLCH 转 RGB 再转回来 明度基本不变`() {
        val hues = listOf(0f, 45f, 120f, 200f, 270f, 330f)
        for (h in hues) {
            for (l in listOf(0.3f, 0.5f, 0.7f)) {
                val (r, g, b) = AmbientColorSpace.oklchToRgb(l, 0.12f, h)
                val back = AmbientColorSpace.linearRgbToOklab(
                    AmbientColorSpace.srgbToLinear(r / 255f),
                    AmbientColorSpace.srgbToLinear(g / 255f),
                    AmbientColorSpace.srgbToLinear(b / 255f),
                )
                assertTrue(
                    "色相 $h 明度 $l 往返后偏了 ${abs(back.first - l)}",
                    abs(back.first - l) < 0.03f,
                )
            }
        }
    }

    @Test
    fun `色域收敛时压彩度而不是裁 RGB`() {
        // 彩度 0.4 远超 sRGB 能表达的量级 —— 必然要收敛。
        // 收敛后的颜色应该**仍在色域内**，而且不是被夹成某个诡异的暗色。
        val (r, g, b) = AmbientColorSpace.oklchToRgb(0.6f, 0.4f, 30f)
        assertTrue("r 应在 0..255（实际 $r）", r in 0..255)
        assertTrue("g 应在 0..255（实际 $g）", g in 0..255)
        assertTrue("b 应在 0..255（实际 $b）", b in 0..255)
        // 收敛后仍然是一个有彩度的颜色（不是灰）
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        assertTrue("不该被压成灰色（实际 $r/$g/$b）", maxC - minC > 30)
    }

    @Test
    fun `彩度为 0 时就是灰阶`() {
        val (r, g, b) = AmbientColorSpace.oklchToRgb(0.5f, 0f, 200f)
        assertTrue("灰阶三通道应该接近（实际 $r/$g/$b）", maxOf(r, g, b) - minOf(r, g, b) <= 1)
    }

    // ------------------------------------------------------------ 插值

    @Test
    fun `插值两端就是原色`() {
        val a = AmbientColorSpace.lerpRgb(10, 20, 30, 200, 100, 50, 0f)
        assertEquals(10, a.first)
        assertEquals(20, a.second)
        assertEquals(30, a.third)
        val b = AmbientColorSpace.lerpRgb(10, 20, 30, 200, 100, 50, 1f)
        assertEquals(200, b.first)
        assertEquals(100, b.second)
        assertEquals(50, b.third)
    }

    @Test
    fun `插值中途不偏色`() {
        // 两个色相差别很大的颜色之间插值，
        // 中间帧应该仍然是「两者之间」的颜色 —— 不能灰掉。
        val mid = AmbientColorSpace.lerpRgb(255, 0, 0, 0, 0, 255, 0.5f)
        val lab = AmbientColorSpace.rgbToLab(mid.first, mid.second, mid.third)
        val chroma = kotlin.math.sqrt(lab.second * lab.second + lab.third * lab.third)
        assertTrue("中点不该是灰（彩度 $chroma）", chroma > 30f)
    }

    // ------------------------------------------------------------ ΔE00 限速

    @Test
    fun `限速后与上一帧的色差不超过上限`() {
        val maxDe = 1.8f
        var prev = Triple(0, 0, 0)
        var prevLab = AmbientColorSpace.rgbToLab(0, 0, 0)
        // 从纯黑一路走到纯白 —— 每一帧都该被限住
        for (i in 0..255) {
            val cur = Triple(i, i, i)
            val limited = AmbientColorSpace.limitByDeltaE(
                prev.first, prev.second, prev.third,
                cur.first, cur.second, cur.third,
                maxDe,
            )
            val lab = AmbientColorSpace.rgbToLab(limited.first, limited.second, limited.third)
            val de = AmbientColorSpace.deltaE00(prevLab, lab)
            assertTrue("第 $i 帧色差 $de 超过上限 $maxDe", de <= maxDe + 0.05f)
            prev = limited
            prevLab = lab
        }
    }

    @Test
    fun `色差没超限时原样返回`() {
        val same = AmbientColorSpace.limitByDeltaE(10, 20, 30, 10, 20, 30, 1.8f)
        assertEquals(10, same.first)
        assertEquals(20, same.second)
        assertEquals(30, same.third)
    }

    // ------------------------------------------------------------ 抖动

    @Test
    fun `抖动在 ±1 个色阶以内`() {
        // 三角分布噪声：r() + r() − 1 ∈ (−1, 1)
        repeat(500) {
            val out = AmbientColorSpace.dither(128f) { Math.random().toFloat() }
            assertTrue("抖动后应仍在 127..129（实际 $out）", out in 127..129)
        }
    }

    @Test
    fun `抖动会把台阶打散`() {
        // 同一个输入值，抖动后应该产出多个不同的输出（否则台阶还在）。
        // 三角分布 + 四舍五入 ⇒ 产出 v−1 / v / v+1 三个值，概率 1:2:1。
        val outs = HashSet<Int>()
        repeat(500) { outs.add(AmbientColorSpace.dither(100f) { Math.random().toFloat() }) }
        assertTrue("抖动应产出 3 个取值（实际 ${outs.size} 个：$outs）", outs.size == 3)
    }

    @Test
    fun `抖动是无偏的`() {
        // 整体偏 0.5 个色阶在黑色附近是能看出来的 ——
        // 所以均值必须落在输入值上，而不是低半格。
        var sum = 0L
        val n = 2000
        repeat(n) { sum += AmbientColorSpace.dither(100f) { Math.random().toFloat() } }
        val mean = sum.toDouble() / n
        assertTrue("抖动均值该无偏（实际 $mean）", kotlin.math.abs(mean - 100.0) < 0.15)
    }
}
