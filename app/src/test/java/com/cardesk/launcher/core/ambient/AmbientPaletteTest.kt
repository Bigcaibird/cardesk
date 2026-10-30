package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [AmbientPalette] 单测（纯逻辑，不依赖 Android）。
 *
 * 这条规则决定「灯该是什么颜色、该多亮」：
 * - 色相怎么从频谱里来；
 * - 亮度曲线（灵敏度）怎么算；
 * - 圆周平均（色相是环形的，算术平均会给出离谱结果）。
 */
class AmbientPaletteTest {

    private fun bandsOf(vararg values: Float): FloatArray = FloatArray(16) { values.getOrElse(it) { 0f } }

    @Test
    fun `色相归一`() {
        assertEquals(0f, AmbientPalette.normalizeHue(0f), 0.001f)
        assertEquals(350f, AmbientPalette.normalizeHue(350f), 0.001f)
        assertEquals(10f, AmbientPalette.normalizeHue(370f), 0.001f)
        assertEquals(350f, AmbientPalette.normalizeHue(-10f), 0.001f)
    }

    @Test
    fun `段号对应色相`() {
        assertEquals(0f, AmbientPalette.hueForBand(0, 16), 0.001f)
        assertEquals(180f, AmbientPalette.hueForBand(8, 16), 0.001f)
        assertEquals(337.5f, AmbientPalette.hueForBand(15, 16), 0.001f)
    }

    @Test
    fun `低频能量取前几段的最大值`() {
        assertEquals(0.8f, AmbientPalette.bassEnergy(bandsOf(0.1f, 0.8f, 0.3f, 0.2f, 0.9f, 0.9f)), 0.0001f)
        assertEquals(0f, AmbientPalette.bassEnergy(FloatArray(0)), 0.0001f)
    }

    @Test
    fun `低频色相取最响的那一段`() {
        val b = bandsOf(0.1f, 0.9f, 0.2f)
        assertEquals(AmbientPalette.hueForBand(1, 16), AmbientPalette.bassHue(b, 0f), 0.001f)
        // 没有能量时保留原来的色相
        assertEquals(123f, AmbientPalette.bassHue(bandsOf(), 123f), 0.001f)
    }

    @Test
    fun `低频分辨不出来时改用强度映射色相`() {
        // 第八十五轮：512 点 FFT 下 60~149Hz 那四段**全落进同一个 bin**
        // ⇒ 四段能量永远相等 ⇒「取最响的那一段」永远取第 0 段
        // ⇒ 色相恒为 0°（红）、亮度钉死 —— 灯一动不动。
        val flat = bandsOf(0.9f, 0.9f, 0.9f, 0.9f)
        assertTrue("四段一样 ⇒ 分辨不出来", AmbientPalette.isBassUnresolvable(flat))
        // 此时不能再给「第 0 段的色相」（那是假信息），要按强度给
        assertEquals(
            AmbientPalette.intensityHue(0.9f),
            AmbientPalette.bassHue(flat, 0f),
            0.001f,
        )
        assertTrue(
            "不能再是恒定的 0°",
            abs(AmbientPalette.bassHue(flat, 0f) - AmbientPalette.hueForBand(0, 16)) > 1f,
        )
    }

    @Test
    fun `低频分辨得出来时照旧取最响的那一段`() {
        val b = bandsOf(0.1f, 0.2f, 0.8f, 0.05f)
        assertTrue("四段不一样 ⇒ 分辨得出来", !AmbientPalette.isBassUnresolvable(b))
        assertEquals(AmbientPalette.hueForBand(2, 16), AmbientPalette.bassHue(b, 0f), 0.001f)
    }

    @Test
    fun `没有低频能量不算分辨不出来`() {
        // 全零 = 没声音，走「保留原色相」那条路，不是「分辨不出来」
        assertTrue(!AmbientPalette.isBassUnresolvable(bandsOf(0f, 0f, 0f, 0f)))
        // 差一点点（浮点噪声）也算相等
        assertTrue(AmbientPalette.isBassUnresolvable(bandsOf(0.5f, 0.505f, 0.5f, 0.499f)))
        // 差得够多就不算
        assertTrue(!AmbientPalette.isBassUnresolvable(bandsOf(0.5f, 0.6f, 0.5f, 0.5f)))
    }

    @Test
    fun `强度映射色相是冷到暖的线性曲线`() {
        assertEquals(260f, AmbientPalette.intensityHue(0f), 0.001f)
        assertEquals(20f, AmbientPalette.intensityHue(1f), 0.001f)
        assertEquals(140f, AmbientPalette.intensityHue(0.5f), 0.001f)
        // 超范围要夹住
        assertEquals(260f, AmbientPalette.intensityHue(-1f), 0.001f)
        assertEquals(20f, AmbientPalette.intensityHue(2f), 0.001f)
    }

    @Test
    fun `频谱色相用圆周平均而不是算术平均`() {
        // 第 0 段（0°）与第 15 段（337.5°）等能量 —— 正确答案在 348.75°（绕短边），
        // 算术平均会给出 168.75°（正好是最离谱的青绿）。
        val b = FloatArray(16)
        b[0] = 1f
        b[15] = 1f
        val hue = AmbientPalette.spectrumHue(b, 0f)
        assertTrue("色相应接近 348.75（实际 $hue）", abs(hue - 348.75f) < 5f)
        assertTrue("不能是算术平均的 168.75", abs(hue - 168.75f) > 100f)
    }

    @Test
    fun `没有能量时保留原色相`() {
        assertEquals(200f, AmbientPalette.spectrumHue(bandsOf(), 200f), 0.001f)
        assertEquals(200f, AmbientPalette.spectrumHue(FloatArray(0), 200f), 0.001f)
    }

    @Test
    fun `亮度曲线`() {
        // 最低亮度是**地板**：没声音时也不是全灭，而是停在最低亮度
        assertEquals(20, AmbientPalette.brightnessFor(0f, 1f, 20, 100))
        assertEquals(100, AmbientPalette.brightnessFor(1f, 1f, 20, 100))
        assertEquals(60, AmbientPalette.brightnessFor(0.5f, 1f, 20, 100))
        // 灵敏度高 → 弱音也亮
        val sensitive = AmbientPalette.brightnessFor(0.2f, 3f, 0, 100)
        val dull = AmbientPalette.brightnessFor(0.2f, 0.3f, 0, 100)
        assertTrue("灵敏度 3 应该比 0.3 亮（$sensitive vs $dull）", sensitive > dull)
    }

    @Test
    fun `亮度被夹在上下限之间`() {
        assertEquals(20, AmbientPalette.brightnessFor(0f, 1f, 20, 100))
        assertEquals(100, AmbientPalette.brightnessFor(1f, 1f, 20, 100))
        // 上限比下限低时以下限为准
        assertEquals(80, AmbientPalette.brightnessFor(0.5f, 1f, 80, 20))
        // 上下限相等时就是那个值
        assertEquals(50, AmbientPalette.brightnessFor(0.5f, 1f, 50, 50))
    }

    @Test
    fun `HSV 转 RGB`() {
        val red = AmbientPalette.hsvToRgb(0f, 1f, 1f)
        assertEquals(255, red[0]); assertEquals(0, red[1]); assertEquals(0, red[2])
        val green = AmbientPalette.hsvToRgb(120f, 1f, 1f)
        assertEquals(0, green[0]); assertEquals(255, green[1]); assertEquals(0, green[2])
        val blue = AmbientPalette.hsvToRgb(240f, 1f, 1f)
        assertEquals(0, blue[0]); assertEquals(0, blue[1]); assertEquals(255, blue[2])
        // 白色：色相无关
        val white = AmbientPalette.hsvToRgb(37f, 0f, 1f)
        assertEquals(255, white[0]); assertEquals(255, white[1]); assertEquals(255, white[2])
    }

    @Test
    fun `固定色模式用设定的色相`() {
        val color = AmbientPalette.colorFrom(
            bands = bandsOf(0.5f),
            level = 0.5f,
            mode = AmbientPalette.MODE_FIXED,
            fixedHue = 120f,
            sensitivity = 1f,
            minBrightness = 20,
            maxBrightness = 100,
        )
        // 120° → 绿（饱和度 0.85，所以红蓝各留一点，不是纯绿）
        assertEquals(255, color.g)
        assertTrue("红蓝应该一样（$color）", color.r == color.b)
        assertTrue("红蓝应该很小（$color）", color.r < 60)
        // 亮度按电平
        assertEquals(60, color.brightness)
    }

    @Test
    fun `跟随低频模式亮度跟低频走`() {
        val b = bandsOf(0.8f)
        val color = AmbientPalette.colorFrom(
            bands = b,
            level = 0.1f,
            mode = AmbientPalette.MODE_BASS,
            fixedHue = 0f,
            sensitivity = 1f,
            minBrightness = 0,
            maxBrightness = 100,
        )
        assertEquals("低频模式亮度应该跟低频能量走", 80, color.brightness)
    }

    @Test
    fun `跟随频谱模式亮度跟整体电平走`() {
        val color = AmbientPalette.colorFrom(
            bands = bandsOf(0.8f, 0.8f, 0.8f, 0.8f),
            level = 0.3f,
            mode = AmbientPalette.MODE_SPECTRUM,
            fixedHue = 0f,
            sensitivity = 1f,
            minBrightness = 0,
            maxBrightness = 100,
        )
        assertEquals(30, color.brightness)
    }

    @Test
    fun `颜色十六进制`() {
        assertEquals("#FF8000", AmbientColor(255, 128, 0, 50).toHex())
    }

    @Test
    fun `按亮度缩放颜色 亮度为零就是全黑`() {
        // 车机亮度写的是**用户拖的绝对亮度**，频谱的强弱落到颜色的明暗上 ——
        // 不做这一步，灵敏度 / 亮度上下限就完全没有效果。
        val black = AmbientPalette.scaleByBrightness(AmbientColor(200, 100, 50, 0))
        assertEquals(0, black.r)
        assertEquals(0, black.g)
        assertEquals(0, black.b)
        // 亮度字段本身不变（还是那个百分比）
        assertEquals(0, black.brightness)
    }

    @Test
    fun `按亮度缩放颜色 满亮时保持原样`() {
        val full = AmbientPalette.scaleByBrightness(AmbientColor(200, 100, 50, 100))
        assertEquals(200, full.r)
        assertEquals(100, full.g)
        assertEquals(50, full.b)
    }

    @Test
    fun `按亮度缩放颜色 半亮就是一半`() {
        val half = AmbientPalette.scaleByBrightness(AmbientColor(200, 100, 50, 50))
        assertEquals(100, half.r)
        assertEquals(50, half.g)
        assertEquals(25, half.b)
    }

    @Test
    fun `按亮度缩放颜色 亮度越界会被夹住`() {
        val over = AmbientPalette.scaleByBrightness(AmbientColor(200, 100, 50, 150))
        assertEquals(200, over.r)
        val under = AmbientPalette.scaleByBrightness(AmbientColor(200, 100, 50, -10))
        assertEquals(0, under.r)
    }

    @Test
    fun `颜色距离与亮度差`() {
        val a = AmbientColor(255, 0, 0, 50)
        val b = AmbientColor(0, 255, 0, 80)
        assertEquals(30, AmbientPalette.brightnessDelta(a, b))
        assertTrue(AmbientPalette.colorDistance(a, b) > 300)
    }

    // ------------------------------------------------------------ 目标值（给动感层用）

    @Test
    fun `目标色相跟着模式走`() {
        val b = bandsOf(0.1f, 0.9f, 0.2f)
        // 跟随低频：取最响的那一段
        assertEquals(
            AmbientPalette.hueForBand(1, 16),
            AmbientPalette.targetHue(b, AmbientPalette.MODE_BASS, 0f, 0f),
            0.001f,
        )
        // 固定色：用设定的色相
        assertEquals(120f, AmbientPalette.targetHue(b, AmbientPalette.MODE_FIXED, 120f, 0f), 0.001f)
        // 没有能量时保留传入的色相（动感层传的是**当前**色相）
        assertEquals(77f, AmbientPalette.targetHue(bandsOf(), AmbientPalette.MODE_BASS, 0f, 77f), 0.001f)
    }

    @Test
    fun `目标亮度跟着模式走`() {
        val b = bandsOf(0.8f)
        // 跟随低频 → 低频能量
        assertEquals(
            80,
            AmbientPalette.targetLevel(b, 0.1f, AmbientPalette.MODE_BASS, 1f, 0, 100),
        )
        // 跟随频谱 → 整体电平
        assertEquals(
            30,
            AmbientPalette.targetLevel(b, 0.3f, AmbientPalette.MODE_SPECTRUM, 1f, 0, 100),
        )
        // 固定色 → 也是整体电平
        assertEquals(
            30,
            AmbientPalette.targetLevel(b, 0.3f, AmbientPalette.MODE_FIXED, 1f, 0, 100),
        )
    }

    @Test
    fun `由色相和亮度直接产出颜色 亮度会整体缩放`() {
        // 120° → 绿；亮度 100 → 满亮
        val full = AmbientPalette.colorOf(120f, 100)
        assertTrue("绿应该最亮（$full）", full.g > full.r && full.g > full.b)
        // 亮度 0 → 全黑
        val black = AmbientPalette.colorOf(120f, 0)
        assertEquals(0, black.r)
        assertEquals(0, black.g)
        assertEquals(0, black.b)
        // 亮度越界会被夹住
        val over = AmbientPalette.colorOf(120f, 150)
        assertEquals(100, over.brightness)
    }

    @Test
    fun `由色相和亮度产出颜色 与先算再缩放一致`() {
        val hue = 210f
        val byColorOf = AmbientPalette.colorOf(hue, 40)
        val rgb = AmbientPalette.hsvToRgb(hue, AmbientPalette.SATURATION, 1f)
        val byScale = AmbientPalette.scaleByBrightness(AmbientColor(rgb[0], rgb[1], rgb[2], 40))
        assertEquals(byScale.r, byColorOf.r)
        assertEquals(byScale.g, byColorOf.g)
        assertEquals(byScale.b, byColorOf.b)
    }
}
