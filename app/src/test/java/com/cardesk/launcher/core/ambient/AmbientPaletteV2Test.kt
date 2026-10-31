package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [AmbientPalette] 的 **V2** 函数单测（质心色相 / 明度区间映射 / 彩度解耦）。
 *
 * 与旧的 [AmbientPaletteTest] 互补：那份测的是**旧版**链路
 * （离散挑段的 `bassHue`、线性映射的 `brightnessFor`），
 * 这份测的是 V2 用来替换它们的那几个函数。
 */
class AmbientPaletteV2Test {

    private val bandCount = 24

    /**
     * 造一帧频段：**只有** [loudBand] 这一段有能量，其余为 0。
     *
     * 其余段必须真的是 0 —— 留一点底噪的话，24 段的底噪总和会压过
     * 单段的峰值，质心就被拉回中间了（那是「有底噪的音乐」，
     * 不是「能量集中在某一段」）。
     */
    private fun frame(loudBand: Int, value: Float = 0.9f): FloatArray =
        FloatArray(bandCount) { if (it == loudBand) value else 0f }

    // ------------------------------------------------------------ 频谱质心

    @Test
    fun `各段能量相同时质心落在中间`() {
        val bands = FloatArray(bandCount) { 0.5f }
        assertEquals(0.5f, AmbientPalette.spectralCentroid(bands), 0.02f)
    }

    @Test
    fun `能量全在最低段时质心靠近 0`() {
        val bands = frame(0)
        assertTrue("质心该靠近 0（实际 ${AmbientPalette.spectralCentroid(bands)}）", AmbientPalette.spectralCentroid(bands) < 0.1f)
    }

    @Test
    fun `能量全在最高段时质心靠近 1`() {
        val bands = frame(bandCount - 1)
        assertTrue("质心该靠近 1（实际 ${AmbientPalette.spectralCentroid(bands)}）", AmbientPalette.spectralCentroid(bands) > 0.9f)
    }

    @Test
    fun `质心是连续量 不会跳段`() {
        // 旧版 `bassHue` 是「挑最响的那一段」⇒ 段一换色相就跳。
        // 质心必须是连续的：能量从第 0 段平滑移到第 1 段，质心只挪一点点。
        val a = frame(0)
        val b = frame(1)
        val d = abs(AmbientPalette.spectralCentroid(a) - AmbientPalette.spectralCentroid(b))
        assertTrue("质心该连续（相邻段差 $d）", d < 0.1f)
    }

    @Test
    fun `质心用的是频段中心不是序号`() {
        // 首段中心在 (0 + 0.5) / 24 = 0.0208
        val bands = frame(0)
        assertEquals(0.5f / bandCount, AmbientPalette.spectralCentroid(bands), 0.01f)
    }

    @Test
    fun `空频谱质心取中值`() {
        assertEquals(0.5f, AmbientPalette.spectralCentroid(FloatArray(0)), 0.001f)
    }

    // ------------------------------------------------------------ 质心 → 色相

    @Test
    fun `色相始终落在安全色带内`() {
        // 170°~320°（青→蓝→紫→品红）：避开刺眼的亮黄，还留 150° 行程。
        for (i in 0 until bandCount) {
            val hue = AmbientPalette.centroidHue(frame(i), 0f)
            var h = hue
            if (h < AmbientPalette.HUE_BAND_LOW - 1f) h += 360f
            assertTrue(
                "第 $i 段的色相 $hue 超出安全色带",
                h >= AmbientPalette.HUE_BAND_LOW - 1f && h <= AmbientPalette.HUE_BAND_HIGH + 1f,
            )
        }
    }

    @Test
    fun `低音偏品红 高音偏青`() {
        val low = AmbientPalette.centroidHue(frame(0), 0f)
        val high = AmbientPalette.centroidHue(frame(bandCount - 1), 0f)
        assertTrue("低音该偏品红（实际 $low）", low > 280f)
        assertTrue("高音该偏青（实际 $high）", high < 220f)
        assertTrue("低音色相该比高音大（$low vs $high）", low > high)
    }

    @Test
    fun `低频质心只取前几段`() {
        // 前 8 段里的变化 → 色相变化；后面的高频再响也不该带偏「跟随低频」模式。
        val a = frame(2)
        val b = frame(6)
        val hueA = AmbientPalette.lowCentroidHue(a, 0f)
        val hueB = AmbientPalette.lowCentroidHue(b, 0f)
        assertTrue("低频段内色相应有变化（$hueA vs $hueB）", abs(hueA - hueB) > 1f)
    }

    // ------------------------------------------------------------ 明度区间映射

    @Test
    fun `相对电平低于窗口下限时只剩底光`() {
        val floor = 8f
        val level = AmbientPalette.levelFromRelative(0.0f, 1.4f, floor)
        assertEquals("安静段落该只剩底光", floor / 100f, level, 0.001f)
    }

    @Test
    fun `相对电平到顶时是满`() {
        assertEquals(1f, AmbientPalette.levelFromRelative(1f, 1.4f, 8f), 0.001f)
    }

    @Test
    fun `明度随相对电平单调上升`() {
        var last = -1f
        var r = 0f
        while (r <= 1.0001f) {
            val v = AmbientPalette.levelFromRelative(r, 1.4f, 8f)
            assertTrue("相对电平 $r 处明度该单调上升（$last → $v）", v >= last - 0.0001f)
            last = v
            r += 0.05f
        }
    }

    @Test
    fun `典型电平的明度不贴顶`() {
        // 这是「明暗变化明显」的关键约束：
        // 旧版 `brightnessFor` 在典型电平下就顶到 0.9 以上，动态范围只剩 10%。
        val level = AmbientPalette.levelFromRelative(0.55f, 1.4f, 8f)
        assertTrue("典型电平的明度不该贴顶（实际 $level）", level < 0.6f)
        assertTrue("典型电平的明度该在中间（实际 $level）", level > 0.2f)
    }

    @Test
    fun `灵敏度越大弱音也越亮`() {
        val sensitive = AmbientPalette.levelFromRelative(0.3f, 3f, 0f)
        val dull = AmbientPalette.levelFromRelative(0.3f, 0.3f, 0f)
        assertTrue("高灵敏度该更亮（$sensitive vs $dull）", sensitive > dull)
    }

    @Test
    fun `底光不会被区间映射吃掉`() {
        // 底光是「呼吸底光」，安静时灯不能真灭。
        val level = AmbientPalette.levelFromRelative(0f, 1.4f, 20f)
        assertTrue("底光该保留（实际 $level）", level >= 0.2f - 0.001f)
    }

    // ------------------------------------------------------------ 感知明度

    @Test
    fun `感知明度给暗部留了底`() {
        // L = level × 0.92 + 0.04：level=0 时 L=0.04，不是纯黑；
        // level=1 时 L=0.96 —— 顶部也留 4% 余量（留给人眼的高光压缩）。
        assertEquals(0.04f, AmbientPalette.perceptualLightness(0f), 0.001f)
        assertEquals(0.96f, AmbientPalette.perceptualLightness(1f), 0.001f)
        assertTrue("暗部不该是纯黑（实际 ${AmbientPalette.perceptualLightness(0f)}）", AmbientPalette.perceptualLightness(0f) > 0f)
    }

    // ------------------------------------------------------------ 彩度解耦

    @Test
    fun `彩度跟着高频瞬态走`() {
        // 高频很响（镲片）时彩度该比高频安静时更高 ——
        // 这就是「饱和度与色相、明度都不同步」的解耦。
        val dull = FloatArray(bandCount) { 0.1f }
        val bright = FloatArray(bandCount) { 0.1f }
        for (i in bandCount * 2 / 3 until bandCount) bright[i] = 0.9f
        val cDull = AmbientPalette.chromaTarget(dull, 0.8f)
        val cBright = AmbientPalette.chromaTarget(bright, 0.8f)
        assertTrue("高频瞬态该抬高彩度（$cDull vs $cBright）", cBright > cDull)
    }

    @Test
    fun `彩度不超过上限`() {
        val bands = FloatArray(bandCount) { 1f }
        assertTrue(
            "彩度不该超过上限（实际 ${AmbientPalette.chromaTarget(bands, 1f)}）",
            AmbientPalette.chromaTarget(bands, 1f) <= AmbientPalette.MAX_CHROMA + 0.0001f,
        )
    }

    @Test
    fun `饱和度设置越大彩度越高`() {
        val bands = FloatArray(bandCount) { 0.4f }
        assertTrue(
            "饱和度设置该影响彩度",
            AmbientPalette.chromaTarget(bands, 1f) > AmbientPalette.chromaTarget(bands, 0f),
        )
    }

    // ------------------------------------------------------------ OKLCH 出图

    @Test
    fun `OKLCH 出图在 0 到 255 之间`() {
        for (h in listOf(0f, 90f, 180f, 270f)) {
            for (l in listOf(0f, 0.5f, 1f)) {
                val c = AmbientPalette.colorOfOklch(h, l, 0.2f)
                assertTrue("r 越界（$c）", c.r in 0..255)
                assertTrue("g 越界（$c）", c.g in 0..255)
                assertTrue("b 越界（$c）", c.b in 0..255)
            }
        }
    }

    @Test
    fun `明度越高颜色越亮`() {
        val dark = AmbientPalette.colorOfOklch(200f, 0.2f, 0.15f)
        val bright = AmbientPalette.colorOfOklch(200f, 0.8f, 0.15f)
        val sum = { c: AmbientColor -> c.r + c.g + c.b }
        assertTrue("亮色该更亮（${sum(dark)} vs ${sum(bright)}）", sum(bright) > sum(dark))
    }
}
