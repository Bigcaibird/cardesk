package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「五彩」参数单测：[AmbientPalette] 的色相跨度 / 质心展开 / 彩度上限 / 色相自转。
 *
 * 三件套各自解决一件事，缺一个就只剩「一点点变化」：
 *
 * 1. **跨度**（150° → 360°）：决定色相**最多**能走多远；
 * 2. **质心展开**（0.20~0.65 → 0~1）：决定真实音乐**能不能**走满那个跨度；
 * 3. **彩度上限**（0.22 → 0.32）：决定走出来的红/绿**够不够艳**。
 *
 * 外加**自转**：质心不动的时候（副歌反复）靠时间把色相推过去。
 */
class AmbientPaletteRainbowTest {

    private val bandCount = 24

    /** 单段激励：第 [loud] 段最响。 */
    private fun singleBand(loud: Int, value: Float = 0.9f): FloatArray =
        FloatArray(bandCount) { if (it == loud) value else 0f }

    /** 「像真实音乐」的宽带谱：整体有底噪，只有一段稍强。 */
    private fun broadband(focus: Int, base: Float = 0.1f, peak: Float = 1.0f): FloatArray =
        FloatArray(bandCount) { if (it == focus) peak else base }

    private fun normalize(hue: Float): Float {
        var h = hue % 360f
        if (h < 0f) h += 360f
        return h
    }

    /** 圆上覆盖角度 = 360° − 最大空档。 */
    private fun coverage(hues: List<Float>): Float {
        val sorted = hues.map { normalize(it) }.sorted()
        var maxGap = 0f
        for (i in sorted.indices) {
            val gap = if (i == sorted.lastIndex) {
                sorted[0] + 360f - sorted[i]
            } else {
                sorted[i + 1] - sorted[i]
            }
            if (gap > maxGap) maxGap = gap
        }
        return 360f - maxGap
    }

    @Test
    fun `默认参数与改造前的公式逐位一致`() {
        // 默认 span=150、窗口 0..1 ⇒ 展开是恒等，色相公式与第七十九轮那版一模一样。
        for (loud in 0 until bandCount) {
            val bands = singleBand(loud)
            val centroid = AmbientPalette.spectralCentroid(bands)
            val expected = normalize(
                AmbientPalette.HUE_BAND_HIGH -
                    (AmbientPalette.HUE_BAND_HIGH - AmbientPalette.HUE_BAND_LOW) * centroid,
            )
            assertEquals("第 $loud 段", expected, AmbientPalette.centroidHue(bands, 42f), 1e-4f)
            assertEquals("第 $loud 段（低频模式同源）", expected, AmbientPalette.centroidHue(bands, 42f, spanDeg = AmbientPalette.HUE_BAND_SPAN), 1e-4f)
        }
    }

    @Test
    fun `默认窗口是恒等映射`() {
        for (c in listOf(0f, 0.2f, 0.425f, 0.66f, 1f)) {
            assertEquals(c, AmbientPalette.expandCentroid(c, 0f, 1f), 1e-6f)
        }
    }

    @Test
    fun `彩虹窗口把 020 到 065 拉伸成 0 到 1`() {
        assertEquals(0f, AmbientPalette.expandCentroid(0.20f, 0.20f, 0.45f), 1e-5f)
        assertEquals(1f, AmbientPalette.expandCentroid(0.65f, 0.20f, 0.45f), 1e-5f)
        assertEquals(0.5f, AmbientPalette.expandCentroid(0.425f, 0.20f, 0.45f), 1e-4f)
        // 窗口外被夹住，不会越界
        assertEquals(0f, AmbientPalette.expandCentroid(0.05f, 0.20f, 0.45f), 1e-5f)
        assertEquals(1f, AmbientPalette.expandCentroid(0.95f, 0.20f, 0.45f), 1e-5f)
    }

    @Test
    fun `单段激励下彩虹参数覆盖整圈`() {
        val rainbow = (0 until bandCount).map { i ->
            AmbientPalette.centroidHue(
                singleBand(i),
                0f,
                spanDeg = AmbientPalette.HUE_SPAN_MAX,
                windowLow = AmbientPalette.RAINBOW_CENTROID_WINDOW_LOW,
                windowSpan = AmbientPalette.RAINBOW_CENTROID_WINDOW_SPAN,
            )
        }
        val cov = coverage(rainbow)
        assertTrue("24 个采样点该覆盖 ≥300°，实际 ${"%.1f".format(cov)}", cov >= 300f)
    }

    @Test
    fun `真实宽带谱下展开让色相行程至少翻倍`() {
        // 只拉跨度：谱质心只在 0.37~0.63 之间动，色相只走约 90°。
        val spanOnly = (0 until bandCount).map { i ->
            AmbientPalette.centroidHue(broadband(i), 0f, spanDeg = AmbientPalette.HUE_SPAN_MAX)
        }
        // 再加展开：同一段频谱，色相行程被拉到 200° 以上。
        val rainbow = (0 until bandCount).map { i ->
            AmbientPalette.centroidHue(
                broadband(i),
                0f,
                spanDeg = AmbientPalette.HUE_SPAN_MAX,
                windowLow = AmbientPalette.RAINBOW_CENTROID_WINDOW_LOW,
                windowSpan = AmbientPalette.RAINBOW_CENTROID_WINDOW_SPAN,
            )
        }
        val spanCov = coverage(spanOnly)
        val rainbowCov = coverage(rainbow)
        assertTrue(
            "展开该把 ${"%.1f".format(spanCov)}° 拉到两倍以上，实际 ${"%.1f".format(rainbowCov)}°",
            rainbowCov >= spanCov * 2f,
        )
        assertTrue("彩虹下真实音乐也该走 ≥180°", rainbowCov >= 180f)
    }

    @Test
    fun `彩度上限可以抬高到 032 但硬上限是 040`() {
        val loud = FloatArray(bandCount) { 1f }
        // 默认上限：仍按 0.22 收住
        assertTrue(AmbientPalette.chromaTarget(loud, saturation = 1f) <= AmbientPalette.MAX_CHROMA + 1e-5f)
        // 彩虹上限：能真的更艳（否则红/绿出来是灰的）
        val rainbow = AmbientPalette.chromaTarget(loud, saturation = 1f, maxChroma = AmbientPalette.RAINBOW_MAX_CHROMA)
        assertTrue("彩虹彩度该 >0.22，实际 $rainbow", rainbow > AmbientPalette.MAX_CHROMA)
        assertTrue("彩虹彩度该 ≤0.32，实际 $rainbow", rainbow <= AmbientPalette.RAINBOW_MAX_CHROMA + 1e-5f)
        // 谁传更大都被硬上限夹住
        assertTrue(
            AmbientPalette.chromaTarget(loud, saturation = 1f, maxChroma = 10f) <=
                AmbientPalette.MAX_CHROMA_HARD + 1e-5f,
        )
    }

    @Test
    fun `自转相位按秒积分并归一`() {
        assertEquals(18f, AmbientPalette.rotationPhase(0f, 18f, 1_000L), 1e-4f)
        assertEquals(8f, AmbientPalette.rotationPhase(350f, 18f, 1_000L), 1e-4f)
        assertEquals(100f, AmbientPalette.rotationPhase(100f, 0f, 1_000L), 1e-4f)
        // dt 夹到 1s：切后台回来不会一步转几十度
        assertEquals(18f, AmbientPalette.rotationPhase(0f, 18f, 60_000L), 1e-4f)
        // 转速夹到 30°/s
        assertEquals(30f, AmbientPalette.rotationPhase(0f, 1_000f, 1_000L), 1e-4f)
    }

    @Test
    fun `自转一分钟色相行程超过 300 度`() {
        // 模拟控制器的 60Hz 渲染：目标色相 = 基色 + 自转相位，再走二阶运动。
        val motion = AmbientMotion(
            hueTauMs = 240f,
            hueMaxDegPerSec = 90f,
            hueMaxAccelDegPerSec2 = 360f,
            attackMs = 40f,
            decayMs = 240f,
            bassTauMs = 160f,
            pulseTauMs = 170f,
            pulseGain = 0.35f,
            beatFloor = 0.12f,
            beatRatio = 1.25f,
            beatMinGapMs = 240L,
            breathAmount = 0.08f,
        )
        val dt = 16L
        val rotateDegPerSec = 18f
        var state = AmbientMotion.State()
        var phase = 0f
        var travel = 0f
        var now = 0L
        repeat(60 * 1000 / dt.toInt()) {
            phase = AmbientPalette.rotationPhase(phase, rotateDegPerSec, dt)
            val target = AmbientPalette.normalizeHue(200f + phase)
            state = motion.step(
                prev = state,
                targetHue = target,
                targetLevel = 0.6f,
                targetChroma = 0.26f,
                bass = 0.2f,
                dtMs = dt,
                nowMs = now,
                silent = false,
            )
            travel += kotlin.math.abs(shortestDelta(state.hue, normalize(200f + phase)))
            now += dt
        }
        val expected = rotateDegPerSec * 60f
        assertTrue(
            "60s 以 18°/s 自转，色相应走 ≈${"%.0f".format(expected)}°，实际 ${"%.0f".format(travel)}°",
            travel >= 300f,
        )
        assertTrue("色相差额不该超过目标 $travel", travel >= expected * 0.8f)
    }

    private fun shortestDelta(a: Float, b: Float): Float {
        var d = normalize(b - a)
        if (d > 180f) d -= 360f
        return d
    }

    @Test
    fun `彩虹彩度不会被运动层压回 022`() {
        // 艺术层按 prefs 传到 0.32，运动层只兜硬上限 0.40 ——
        // 如果这里又夹回 0.22，彩虹就只是「目标更艳、灯还是灰的」。
        val motion = AmbientMotion(
            hueTauMs = 240f,
            hueMaxDegPerSec = 90f,
            hueMaxAccelDegPerSec2 = 360f,
            attackMs = 40f,
            decayMs = 240f,
            bassTauMs = 160f,
            pulseTauMs = 170f,
            pulseGain = 0.35f,
            beatFloor = 0.12f,
            beatRatio = 1.25f,
            beatMinGapMs = 240L,
            breathAmount = 0.08f,
        )
        var state = AmbientMotion.State()
        var now = 0L
        repeat(200) {
            state = motion.step(
                prev = state,
                targetHue = 200f,
                targetLevel = 0.6f,
                targetChroma = 0.32f,
                bass = 0f,
                dtMs = 16L,
                nowMs = now,
                silent = false,
            )
            now += 16L
        }
        assertTrue("彩度该真的到 0.32 附近，实际 ${state.chroma}", state.chroma > AmbientPalette.MAX_CHROMA)
        assertTrue("也不该超过硬上限", state.chroma <= AmbientPalette.MAX_CHROMA_HARD)
    }
}
