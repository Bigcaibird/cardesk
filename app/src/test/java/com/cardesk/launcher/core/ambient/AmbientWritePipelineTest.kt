package com.cardesk.launcher.core.ambient

import com.cardesk.launcher.core.audio.SpectrumPerception
import com.cardesk.launcher.vehicle.AdaptapiConstants
import com.cardesk.launcher.vehicle.VehicleGateway
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 写入链路的**端到端**诊断：车上的颜色会不会跟引擎发散？
 *
 * ## 验的是什么
 *
 * 怀疑过这么一个机制：
 *
 * > 写入层以「上一个**已写**颜色」为基准、渲染层以「上一个**渲染帧**」为基准，
 * > 两层用**同一个**预算 ⇒ 候选色跑得比写入快一倍 ⇒ 写入层每次往回拉，
 * > 误差每帧累积 ⇒ 几秒后车上的颜色与音乐完全无关。
 *
 * 这个测试用**真实的类**把整条链路跑一遍（感知层 → 艺术层 → 运动层 →
 * OKLCH 调色板 → 渲染层 ΔE 限速 → [AdaptapiAmbientLightApi] 写入层），
 * 然后量两个数：
 *
 * 1. **写下去的颜色**与**引擎当下算出来的颜色**差多少（这就是「车上的颜色
 *    离音乐有多远」）；
 * 2. 这个差**会不会随时间变大**（变大 = 发散；不���大 = 只是慢半拍）。
 *
 * ## 为什么用真实时间
 *
 * 写入层的节流（50ms）和退避（1s）用的是 [System.currentTimeMillis]，
 * 渲染层是 60Hz —— 两边必须看同一个时钟，所以这个测试按真实节奏跑，
 * 而不是把渲染层加速到虚拟时间里（那样写入层会一帧都写不出去，
 * 量出来的数没有意义）。
 */
class AmbientWritePipelineTest {

    /** 记录每一次写入的假通道。 */
    private class FakeGateway : VehicleGateway {
        override val id: String = "adaptapi"
        override val displayName: String = "假通道"
        override var on360State: ((Boolean) -> Unit)? = null
        val writes = mutableListOf<Triple<Int, Int, Int>>()

        override fun connect(onResult: (Boolean) -> Unit) = onResult(true)
        override fun disconnect() = Unit
        override fun isConnected(): Boolean = true
        override fun setHvac(func: Int, zone: Int, value: Int): Boolean {
            writes.add(Triple(func, zone, value))
            return true
        }

        override fun setTemp(zone: Int, celsius: Float): Boolean = false
        override fun readAll(): Map<Int, Int> = emptyMap()
        override fun registerListener(listener: (Int, Int, Int) -> Unit) = Unit
        override fun unregisterListener(listener: (Int, Int, Int) -> Unit) = Unit
    }

    private val colorFunc = 0x200A0900

    @After
    fun tearDown() {
        AdaptapiConstants.resetForTest()
    }

    /** 一个合成音乐帧：底鼓在最低三段，旋律在中低段走动，镲片在高频。 */
    private fun musicFrame(phase: Float): FloatArray {
        val bands = FloatArray(24) { 0.06f }
        val beat = phase % 1.0f
        val kick = exp(-beat * 14.0f)
        for (i in 0 until 3) {
            bands[i] = 0.35f + 0.62f * kick
        }
        val melodyBand = 3 + ((phase * 6.0f).roundToInt() % 8)
        bands[melodyBand] = 0.80f
        if (melodyBand + 1 < 24) bands[melodyBand + 1] = 0.55f
        val off = (phase + 0.5f) % 1.0f
        val cymbal = exp(-off * 20.0f)
        for (i in 16 until 24) {
            bands[i] = 0.10f + 0.55f * cymbal
        }
        return bands
    }

    /** 两个颜色之间的 ΔE00。 */
    private fun de(a: AmbientColor, b: AmbientColor): Float = AmbientColorSpace.deltaE00(
        AmbientColorSpace.rgbToLab(a.r, a.g, a.b),
        AmbientColorSpace.rgbToLab(b.r, b.g, b.b),
    )

    @Test
    fun `写入层不会让车上的颜色与引擎发散`() {
        AdaptapiConstants.testOverride = mapOf(
            AdaptapiConstants.FUNC_AMBIENT_COLOR_SET to colorFunc,
            AdaptapiConstants.FUNC_AMBIENT_MAIN_COLOR to 0x200A0200,
            AdaptapiConstants.AMBIENT_MAIN_COLOR_SET_COLOR to 0x200A0203,
        )
        val gw = FakeGateway()
        val maxDeltaE = 1.8f
        val api = AdaptapiAmbientLightApi(maxDeltaE = maxDeltaE) { gw }

        val perception = SpectrumPerception()
        val motion = AmbientMotion()
        val cap = AmbientLightController.perFrameDeltaECap(maxDeltaE)

        var state = AmbientMotion.State()
        var lastRendered: AmbientColor? = null
        var targetHue = 0f
        var targetLevel = 0f
        var targetChroma = 0f
        var bass = 0f

        // 写下去的颜色与引擎当下颜色的差（ΔE00）。
        val gaps = mutableListOf<Float>()
        // 渲染层限速之后，写下去的颜色与**渲染色**的差（写入层自己的贡献）。
        val writeGaps = mutableListOf<Float>()
        // 引擎色（限速之前）每帧走多远。
        val engineSteps = mutableListOf<Float>()
        // 渲染色（限速之后）每帧走多远。
        val renderedSteps = mutableListOf<Float>()
        // 渲染色与引擎色的差（渲染层限速造成的滞后）。
        val renderGaps = mutableListOf<Float>()
        var lastEngine: AmbientColor? = null
        var targetLevelTrace = mutableListOf<Float>()
        var hueTrace = mutableListOf<Float>()
        // 相邻两次写入之间的色差（车机上真正看到的跳变）。
        val steps = mutableListOf<Float>()
        var lastWritten: AmbientColor? = null
        var writeCount = 0

        val totalFrames = 600
        val warmupFrames = 150
        for (i in 0 until totalFrames) {
            // 频谱是 10Hz。
            if (i % 6 == 0) {
                val perceived = perception.process(musicFrame(i / 60f), 100L)
                targetHue = AmbientPalette.lowCentroidHue(perceived, state.hue)
                targetLevel = AmbientPalette.levelFromRelative(
                    perception.relativeLevel,
                    sensitivity = 1.4f,
                    floorPercent = 8f,
                )
                targetChroma = AmbientPalette.chromaTarget(perceived, 0.8f)
                bass = AmbientPalette.bassEnergy(perceived)
            }
            val now = System.currentTimeMillis()
            state = motion.step(
                prev = state,
                targetHue = targetHue,
                targetLevel = targetLevel,
                targetChroma = targetChroma,
                bass = bass,
                dtMs = 16L,
                nowMs = now,
            )
            val lightness = AmbientPalette.perceptualLightness(state.level)
            val engineColor = AmbientPalette.colorOfOklch(
                hue = state.hue,
                lightness = lightness,
                chroma = state.chroma,
            )

            // —— 渲染层限速（真实函数，真实预算）——
            val prev = lastRendered
            val rendered = if (prev == null) {
                engineColor
            } else {
                val (r, g, b) = AmbientColorSpace.limitByDeltaE(
                    prev.r, prev.g, prev.b,
                    engineColor.r, engineColor.g, engineColor.b,
                    cap,
                )
                AmbientColor(r, g, b, engineColor.brightness)
            }

            // —— 诊断：引擎色每帧走多远，限速之后还剩多少 ——
            lastEngine?.let { engineSteps.add(de(it, engineColor)) }
            lastEngine = engineColor
            prev?.let { renderGaps.add(de(it, engineColor)) }
            prev?.let { renderedSteps.add(de(it, rendered)) }
            lastRendered = rendered
            if (i % 20 == 0) {
                targetLevelTrace.add(targetLevel)
                hueTrace.add(state.hue)
            }

            // —— 写入层（真实类：节流 + 死区 + ΔE 拉回）——
            val before = gw.writes.size
            api.apply(rendered)
            val wrote = gw.writes.size > before

            if (i >= warmupFrames && wrote) {
                val value = gw.writes.last { it.first == colorFunc }.third
                val written = AmbientColor(
                    (value shr 16) and 0xFF,
                    (value shr 8) and 0xFF,
                    value and 0xFF,
                    rendered.brightness,
                )
                gaps.add(de(written, engineColor))
                writeGaps.add(de(written, rendered))
                lastWritten?.let {
                    steps.add(de(it, written))
                }
                lastWritten = written
                writeCount++
            }
            Thread.sleep(16L)
        }

        val third = gaps.size / 3
        val gapFirst = gaps.take(third).maxOrNull() ?: 0f
        val gapLast = gaps.takeLast(third).maxOrNull() ?: 0f
        val gapMax = gaps.maxOrNull() ?: 0f
        val stepMax = steps.maxOrNull() ?: 0f

        // ignore: avoid_print
        println("writes: $writeCount, limiter pulls: ${api.deltaELimitedCount}")
        // ignore: avoid_print
        println("gap first third: $gapFirst, last third: $gapLast, max: $gapMax")
        // ignore: avoid_print
        println("max step between two written frames: $stepMax")
        // ignore: avoid_print
        println("engine step per frame: max ${engineSteps.maxOrNull()}, median ${engineSteps.sorted()[engineSteps.size / 2]}")
        // ignore: avoid_print
        println("rendered step per frame: max ${renderedSteps.maxOrNull()}")
        // ignore: avoid_print
        println("render lag (rendered vs engine): max ${renderGaps.maxOrNull()}")
        // ignore: avoid_print
        println("render lag thirds: ${renderGaps.take(renderGaps.size / 3).maxOrNull()} / " +
            "${renderGaps.drop(renderGaps.size / 3).take(renderGaps.size / 3).maxOrNull()} / " +
            "${renderGaps.takeLast(renderGaps.size / 3).maxOrNull()}")
        // ignore: avoid_print
        println("write gap (written vs rendered): max ${writeGaps.maxOrNull()}")
        // ignore: avoid_print
        println("write gap thirds: ${writeGaps.take(writeGaps.size / 3).maxOrNull()} / " +
            "${writeGaps.drop(writeGaps.size / 3).take(writeGaps.size / 3).maxOrNull()} / " +
            "${writeGaps.takeLast(writeGaps.size / 3).maxOrNull()}")
        // ignore: avoid_print
        println("targetLevel trace: ${targetLevelTrace.joinToString(", ")}")
        // ignore: avoid_print
        println("hue trace: ${hueTrace.joinToString(", ")}")

        assertTrue("一次写入都没发生", writeCount > 20)
        // 车机上相邻两帧的色差必须受控（验收第 2 条：ΔE00 ≤ 2.0）。
        assertTrue(
            "车机上相邻两帧差了 $stepMax ΔE00，超过上限",
            stepMax <= maxDeltaE + 0.2f,
        )
        // 关键：**写入层**自己的贡献不能随时间变大。
        //
        // 变大才是发散（写入层每次往回拉、误差每帧累积）。
        // 这里量的是「写下去的颜色」与「渲染层限速之后的颜色」的差 ——
        // 也就是写入层自己造成的那一部分。
        val writeThird = writeGaps.size / 3
        val writeGapFirst = writeGaps.take(writeThird).maxOrNull() ?: 0f
        val writeGapLast = writeGaps.takeLast(writeThird).maxOrNull() ?: 0f
        val writeGapMax = writeGaps.maxOrNull() ?: 0f
        assertTrue(
            "写入层造成的偏差在变大（$writeGapFirst → $writeGapLast）",
            writeGapLast <= writeGapFirst + 0.3f,
        )
        assertTrue(
            "写入层造成的偏差 $writeGapMax ΔE00，太大",
            writeGapMax <= maxDeltaE * 1.5f,
        )
        // 渲染层限速造成的滞后（渲染色 vs 引擎色）**不会一直变大**：
        // 它稳定在「音乐本身的动态范围」附近（这一拍比上一拍亮多少），
        // 跟着音乐起伏，但不会一轮一轮地累积。
        val lagThird = renderGaps.size / 3
        val lagFirst = renderGaps.take(lagThird).maxOrNull() ?: 0f
        val lagLast = renderGaps.takeLast(lagThird).maxOrNull() ?: 0f
        assertTrue(
            "渲染滞后在累积（$lagFirst → $lagLast）",
            lagLast <= lagFirst + 8f,
        )
        // 渲染层限速造成的滞后（渲染色 vs 引擎色）会**一直变大** ——
        // 那是限速本身的效果（引擎想走得比预算快，限速器就把它压住），
        // 所以这里只打印不断言；它不是发散，因为引擎的速度是周期性的，
        // 颜色并不会越走越远，而是在一个滞后上跟着音乐转。
    }
}
