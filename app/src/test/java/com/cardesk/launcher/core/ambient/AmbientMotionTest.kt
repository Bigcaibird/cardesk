package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [AmbientMotion] 单测（纯状态机，不依赖 Android）。
 *
 * 这条规则决定「灯是**跳**的还是**流动**的」：
 * - 色相走**最短路径**（359°→1° 只走 2°，不绕 358°）；
 * - 色相有**速度上限**（不会一步甩到目标）；
 * - 亮度**快起慢落**（鼓点立刻跟上，音乐停了慢慢暗下去）；
 * - 低频**起拍**叠一个衰减脉冲（灯有呼吸感）。
 */
class AmbientMotionTest {

    private fun motion(
        hueTauMs: Float = 320f,
        hueMaxDegPerSec: Float = 90f,
        attackTauMs: Float = 50f,
        decayTauMs: Float = 320f,
        bassTauMs: Float = 160f,
        pulseTauMs: Float = 170f,
        pulseGain: Float = 0.45f,
        beatFloor: Float = 0.08f,
        beatRatio: Float = 1.15f,
    ) = AmbientMotion(
        hueTauMs = hueTauMs,
        hueMaxDegPerSec = hueMaxDegPerSec,
        attackTauMs = attackTauMs,
        decayTauMs = decayTauMs,
        bassTauMs = bassTauMs,
        pulseTauMs = pulseTauMs,
        pulseGain = pulseGain,
        beatFloor = beatFloor,
        beatRatio = beatRatio,
    )

    // ------------------------------------------------------------ 最短路径

    @Test
    fun `色相走最短路径 359度到1度只差2度`() {
        assertEquals(2f, AmbientMotion.shortestDelta(359f, 1f), 0.001f)
        assertEquals(-2f, AmbientMotion.shortestDelta(1f, 359f), 0.001f)
        assertEquals(90f, AmbientMotion.shortestDelta(0f, 90f), 0.001f)
        assertEquals(-90f, AmbientMotion.shortestDelta(90f, 0f), 0.001f)
    }

    @Test
    fun `色相归一到 0 到 360`() {
        assertEquals(0f, AmbientMotion.normalizeHue(360f), 0.001f)
        assertEquals(350f, AmbientMotion.normalizeHue(-10f), 0.001f)
    }

    // ------------------------------------------------------------ 色相平滑

    @Test
    fun `第一帧直接落到目标色相`() {
        // 开灯时颜色是「亮」出来的，不该从 0° 扫过去。
        val state = motion().step(AmbientMotion.State(), 200f, 60, 0f, 100)
        assertEquals(200f, state.hue, 0.001f)
        assertTrue(state.initialized)
    }

    @Test
    fun `色相单帧位移不超过速度上限`() {
        val m = motion(hueMaxDegPerSec = 90f)
        var state = m.step(AmbientMotion.State(), 0f, 100, 0f, 100)
        state = m.step(state, 90f, 100, 0f, 100)
        val moved = abs(AmbientMotion.shortestDelta(0f, state.hue))
        assertTrue("100ms 内最多走 9°（90°/s），实际 $moved", moved <= 9f + 0.001f)
        assertTrue("应该朝目标动", moved > 0f)
    }

    @Test
    fun `色相不会一步跳到目标`() {
        val m = motion()
        var state = m.step(AmbientMotion.State(), 0f, 100, 0f, 100)
        state = m.step(state, 180f, 100, 0f, 100)
        assertTrue("不该一步到位（实际 ${state.hue}）", abs(state.hue - 180f) > 100f)
    }

    @Test
    fun `目标抖动时色相不会跟着一起抖`() {
        // 这就是「僵硬」的反面：目标在 0°/30° 之间来回跳，
        // 实际色相每帧的位移必须远小于抖动幅度。
        val m = motion()
        var state = m.step(AmbientMotion.State(), 0f, 80, 0.3f, 100)
        var maxStep = 0f
        repeat(20) { i ->
            val target = if (i % 2 == 0) 0f else 30f
            val before = state.hue
            state = m.step(state, target, 80, 0.3f, 100)
            maxStep = maxOf(maxStep, abs(AmbientMotion.shortestDelta(before, state.hue)))
        }
        assertTrue("单帧位移应被限速夹住（实际 $maxStep）", maxStep <= 9f + 0.01f)
    }

    @Test
    fun `时间差被夹住 切后台再回来不会一步补上`() {
        val m = motion()
        var state = m.step(AmbientMotion.State(), 0f, 100, 0f, 100)
        state = m.step(state, 90f, 100, 0f, 100)
        val before = state.hue
        // 一帧隔了 10 分钟 —— 上限按 1 秒算
        state = m.step(state, 270f, 100, 0f, 600_000L)
        val moved = abs(AmbientMotion.shortestDelta(before, state.hue))
        assertTrue("单帧位移应被限速夹住（实际 $moved）", moved <= 90f + 0.01f)
    }

    // ------------------------------------------------------------ 亮度快起慢落

    @Test
    fun `亮度上升比下降快`() {
        val m = motion()
        var up = AmbientMotion.State()
        repeat(10) { up = m.step(up, 0f, 100, 0f, 100) }
        var down = up
        repeat(10) { down = m.step(down, 0f, 0, 0f, 100) }
        assertTrue("一秒钟后上升应该接近满（${up.level}）", up.level > 0.95f)
        assertTrue("一秒钟后下降应该远低于满（${down.level}）", down.level < 0.2f)
    }

    @Test
    fun `亮度始终夹在 0 到 1`() {
        val m = motion()
        var state = AmbientMotion.State()
        repeat(50) { state = m.step(state, 0f, 100, 0f, 100) }
        assertTrue(state.level <= 1f && state.level >= 0f)
        repeat(50) { state = m.step(state, 0f, 0, 0f, 100) }
        assertTrue(state.level <= 1f && state.level >= 0f)
        assertTrue(state.brightness <= 1f && state.brightness >= 0f)
    }

    // ------------------------------------------------------------ 起拍脉冲

    @Test
    fun `低频突然冲高算起拍`() {
        val m = motion()
        var state = AmbientMotion.State()
        // 先把低频包络拉起来
        repeat(5) { state = m.step(state, 0f, 50, 0.5f, 100) }
        state = m.step(state, 0f, 50, 0.95f, 100)
        assertEquals(1f, state.pulse, 0.001f)
        assertTrue("起拍时亮度要高于纯响度（level=${state.level}）", state.brightness > state.level)
    }

    @Test
    fun `脉冲会衰减`() {
        val m = motion()
        var state = AmbientMotion.State()
        repeat(5) { state = m.step(state, 0f, 50, 0.5f, 100) }
        state = m.step(state, 0f, 50, 0.95f, 100)
        assertEquals(1f, state.pulse, 0.001f)
        // 低频落回去 → 脉冲按时间常数衰减
        state = m.step(state, 0f, 50, 0f, 100)
        assertTrue("脉冲该衰减（实际 ${state.pulse}）", state.pulse < 1f && state.pulse > 0f)
    }

    @Test
    fun `底噪不起拍`() {
        val m = motion()
        var state = AmbientMotion.State()
        repeat(5) { state = m.step(state, 0f, 50, 0.05f, 100) }
        state = m.step(state, 0f, 50, 0.05f, 100)
        assertEquals(0f, state.pulse, 0.001f)
    }

    @Test
    fun `没有起拍时亮度就是响度本身`() {
        val m = motion(pulseGain = 0f)
        var state = AmbientMotion.State()
        repeat(20) { state = m.step(state, 0f, 80, 0.05f, 100) }
        assertFalse(state.pulse > 0.01f)
        assertEquals(state.level, state.brightness, 0.001f)
    }

    // ------------------------------------------------------------ 新旧链路对比

    @Test
    fun `新链路比旧链路跳得小得多`() {
        // 合成一段「鼓点」：低频里最响的那一段在两个频段之间来回跳
        // （这正是真实音乐里最让灯「跳」的场景）。
        val bands = FloatArray(16)
        fun frame(loudBand: Int) {
            for (i in bands.indices) bands[i] = 0.05f
            bands[loudBand] = 0.9f
        }

        // —— 旧链路：频谱直接算色相 → 乘亮度 ——
        var lastHue = 0f
        var prevOld: AmbientColor? = null
        var maxOld = 0
        // —— 新链路：目标值 → 动感层 → 颜色 ——
        val m = motion()
        var state = AmbientMotion.State()
        var prevNew: AmbientColor? = null
        var maxNew = 0

        for (i in 0 until 40) {
            frame(if (i % 2 == 0) 0 else 3)

            val computed = AmbientPalette.colorFrom(
                bands = bands,
                level = 0.5f,
                mode = AmbientPalette.MODE_BASS,
                fixedHue = 0f,
                sensitivity = 1f,
                minBrightness = 0,
                maxBrightness = 100,
                fallbackHue = lastHue,
            )
            lastHue = AmbientPalette.spectrumHue(bands, lastHue)
            val old = AmbientPalette.scaleByBrightness(computed)
            prevOld?.let { maxOld = maxOf(maxOld, AmbientPalette.colorDistance(it, old)) }
            prevOld = old

            val targetHue = AmbientPalette.targetHue(
                bands = bands,
                mode = AmbientPalette.MODE_BASS,
                fixedHue = 0f,
                fallbackHue = state.hue,
            )
            val targetLevel = AmbientPalette.targetLevel(
                bands = bands,
                level = 0.5f,
                mode = AmbientPalette.MODE_BASS,
                sensitivity = 1f,
                minBrightness = 0,
                maxBrightness = 100,
            )
            state = m.step(state, targetHue, targetLevel, AmbientPalette.bassEnergy(bands), 100)
            val new = AmbientPalette.colorOf(state.hue, (state.brightness * 100f).roundToInt())
            prevNew?.let { maxNew = maxOf(maxNew, AmbientPalette.colorDistance(it, new)) }
            prevNew = new
        }

        assertTrue(
            "旧链路单帧最大跳变 $maxOld 应该远大于新链路的 $maxNew",
            maxOld > maxNew * 3,
        )
        assertTrue("新链路每帧仍然要在动（$maxNew）", maxNew > 0)
    }
}
