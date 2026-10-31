package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [AmbientMotion]（V2）单测（纯状态机，不依赖 Android）。
 *
 * V2 相对旧版多了三件事，每一件都在这里有对应的断言：
 *
 * 1. **二阶色相**（限速 + **限加速**）—— 见「色相」这一节；
 * 2. **自适应起拍**（阈值跟着慢包络走 + 最短间隔）—— 见「起拍」这一节；
 * 3. **空闲呼吸**（不是冻住，是缓慢漂移 + 呼吸底光）—— 见「空闲」这一节。
 */
class AmbientMotionTest {

    private fun motion(
        hueTauMs: Float = 260f,
        hueMaxDegPerSec: Float = 60f,
        hueMaxAccelDegPerSec2: Float = 240f,
        attackMs: Float = 45f,
        decayMs: Float = 260f,
        bassTauMs: Float = 160f,
        pulseTauMs: Float = 170f,
        pulseGain: Float = 0.35f,
        beatFloor: Float = 0.12f,
        beatRatio: Float = 1.25f,
        beatMinGapMs: Long = 240L,
        breathAmount: Float = 0.10f,
        hueJumpDeg: Float = 0f,
        jumpHoldMs: Long = 0L,
    ) = AmbientMotion(
        hueTauMs = hueTauMs,
        hueMaxDegPerSec = hueMaxDegPerSec,
        hueMaxAccelDegPerSec2 = hueMaxAccelDegPerSec2,
        attackMs = attackMs,
        decayMs = decayMs,
        bassTauMs = bassTauMs,
        pulseTauMs = pulseTauMs,
        pulseGain = pulseGain,
        beatFloor = beatFloor,
        beatRatio = beatRatio,
        beatMinGapMs = beatMinGapMs,
        breathAmount = breathAmount,
        hueJumpDeg = hueJumpDeg,
        jumpHoldMs = jumpHoldMs,
    )

    // ------------------------------------------------------------ 工具

    /**
     * 走一帧的包装（把 nowMs 传进去）。
     *
     * `nowMs` 必须是**单调**的，而且起点要够大 ——
     * 起拍判定里有「距上次起拍 > 240ms」，起点为 0 时第一次永远不满足。
     */
    private fun AmbientMotion.stepFrame(
        state: AmbientMotion.State,
        hue: Float,
        level: Float,
        chroma: Float = 0f,
        bass: Float = 0f,
        dt: Long = 16L,
        now: Long = 10_000L,
        silent: Boolean = false,
    ): AmbientMotion.State = step(
        prev = state,
        targetHue = hue,
        targetLevel = level,
        targetChroma = chroma,
        bass = bass,
        dtMs = dt,
        nowMs = now,
        silent = silent,
    )

    // ------------------------------------------------------------ 最短路径

    @Test
    fun `色相走最短路径 359度到1度只差2度`() {
        assertEquals(2f, AmbientMotion.shortestDelta(359f, 1f), 0.001f)
        assertEquals(-2f, AmbientMotion.shortestDelta(1f, 359f), 0.001f)
        assertEquals(90f, AmbientMotion.shortestDelta(0f, 90f), 0.001f)
    }

    @Test
    fun `色相归一到 0 到 360`() {
        assertEquals(0f, AmbientMotion.normalizeHue(360f), 0.001f)
        assertEquals(350f, AmbientMotion.normalizeHue(-10f), 0.001f)
    }

    // ------------------------------------------------------------ 色相（二阶）

    @Test
    fun `第一帧直接落到目标色相`() {
        // 开灯时颜色是「亮」出来的，不该从 0° 扫过去。
        val state = motion().stepFrame(AmbientMotion.State(), 200f, 0.5f)
        assertEquals(200f, state.hue, 0.001f)
        assertEquals(0f, state.hueVel, 0.001f)
        assertTrue(state.initialized)
    }

    @Test
    fun `色相单帧位移不超过速度上限`() {
        val m = motion(hueMaxDegPerSec = 60f)
        var state = m.stepFrame(AmbientMotion.State(), 0f, 0.5f)
        state = m.stepFrame(state, 180f, 0.5f)
        val moved = abs(AmbientMotion.shortestDelta(0f, state.hue))
        // 16ms × 60°/s = 0.96°
        assertTrue("单帧位移应被限速夹住（实际 $moved）", moved <= 0.96f + 0.001f)
    }

    @Test
    fun `角加速度限幅让起步比限速允许的更慢`() {
        // 这是 V2 相对旧版最关键的一处改进：
        // 旧版只有限速，灯会「甩」过去，甩到一半改向 ⇒ 一顿一顿。
        // V2 加了角加速度上限（默认 240°/s²），
        // 所以**起步的第一帧**远达不到限速允许的位移。
        val m = motion(hueMaxDegPerSec = 60f, hueMaxAccelDegPerSec2 = 240f)
        var state = m.stepFrame(AmbientMotion.State(), 0f, 0.5f)
        state = m.stepFrame(state, 180f, 0.5f)
        val moved = abs(AmbientMotion.shortestDelta(0f, state.hue))
        val speedLimitAllows = 60f * 0.016f
        assertTrue(
            "起步帧应明显慢于限速允许值（实际 $moved，允许 $speedLimitAllows）",
            moved < speedLimitAllows * 0.5f,
        )
        // 角加速度上限：240°/s² × 0.016s = 3.84°/s ⇒ 首帧位移 ≈ 0.06°
        assertTrue("首帧位移应不超过角加速度允许值（实际 $moved）", moved <= 0.07f)
    }

    @Test
    fun `角速度会累积起来 最终仍能到目标`() {
        // 限加速不能变成「到不了」——给足时间必须能走到目标附近。
        val m = motion()
        var state = AmbientMotion.State()
        var now = 10_000L
        repeat(200) {
            now += 16L
            state = m.stepFrame(state, 180f, 0.5f, dt = 16L, now = now)
        }
        assertTrue("200 帧（3.2s）后应接近目标（实际 ${state.hue}）", abs(state.hue - 180f) < 2f)
    }

    @Test
    fun `目标抖动时色相不会跟着一起抖`() {
        // 这就是「僵硬」的反面：目标在 0°/30° 之间来回跳，
        // 实际色相每帧的位移必须远小于抖动幅度。
        val m = motion()
        var state = m.stepFrame(AmbientMotion.State(), 0f, 0.5f)
        var maxStep = 0f
        repeat(30) { i ->
            val target = if (i % 2 == 0) 0f else 30f
            val before = state.hue
            state = m.stepFrame(state, target, 0.5f)
            maxStep = maxOf(maxStep, abs(AmbientMotion.shortestDelta(before, state.hue)))
        }
        assertTrue("单帧位移应被限速夹住（实际 $maxStep）", maxStep <= 0.96f + 0.01f)
    }

    @Test
    fun `时间差被夹住 切后台再回来不会一步补上`() {
        val m = motion()
        var state = m.stepFrame(AmbientMotion.State(), 0f, 0.5f)
        state = m.stepFrame(state, 90f, 0.5f)
        val before = state.hue
        // 一帧隔了 10 分钟 —— 上限按 1 秒算
        state = m.stepFrame(state, 270f, 0.5f, dt = 600_000L)
        val moved = abs(AmbientMotion.shortestDelta(before, state.hue))
        assertTrue("单帧位移应被限速夹住（实际 $moved）", moved <= 60f + 0.01f)
    }

    // ------------------------------------------------------------ 明度（attack / decay）

    @Test
    fun `亮度上升比下降快`() {
        val m = motion()
        var up = AmbientMotion.State()
        repeat(30) { up = m.stepFrame(up, 0f, 1f) }
        var down = up
        repeat(30) { down = m.stepFrame(down, 0f, 0f) }
        assertTrue("半秒后上升应该接近满（${up.level}）", up.level > 0.95f)
        assertTrue("半秒后下降应该明显回落（${down.level}）", down.level < 0.2f)
    }

    @Test
    fun `亮度始终夹在 0 到 1`() {
        val m = motion()
        var state = AmbientMotion.State()
        repeat(200) { state = m.stepFrame(state, 0f, 1f) }
        assertTrue(state.level <= 1f && state.level >= 0f)
        repeat(200) { state = m.stepFrame(state, 0f, 0f) }
        assertTrue(state.level <= 1f && state.level >= 0f)
        assertTrue(state.brightness <= 1f && state.brightness >= 0f)
    }

    @Test
    fun `起音上升时间在 120ms 以内`() {
        // 验收第 6 条：0 → 90% ≤ 120ms。
        val m = motion(attackMs = 45f)
        var state = AmbientMotion.State()
        var now = 10_000L
        var elapsed = 0L
        while (state.level < 0.9f && elapsed < 500L) {
            now += 16L
            elapsed += 16L
            state = m.stepFrame(state, 0f, 1f, dt = 16L, now = now)
        }
        assertTrue("起音应在 120ms 内到 90%（实际 ${elapsed}ms）", elapsed <= 120L)
    }

    // ------------------------------------------------------------ 起拍（自适应阈值）

    /**
     * 把低频包络先拉到 [bass] 上并保持若干帧。
     *
     * ## 为什么基线要用 0.05 而不是 0.5
     *
     * 起拍判据是「低频 > max(0.12, 慢包络 × 1.25)」。
     * 如果基线就用 0.5，**第一帧**就已经超过 0.12 ⇒ 立刻起拍，
     * 后面 240ms 内的第二次都被最短间隔挡掉 ——
     * 想测「某一次冲高算起拍」就永远测不准。
     *
     * 基线取 0.05（低于绝对下限 0.12）⇒ 不会起拍，
     * 只用来把慢包络稳定在一个低水位上。
     */
    private fun settle(
        m: AmbientMotion,
        state: AmbientMotion.State,
        bass: Float,
        frames: Int,
        now: Long,
    ): Pair<AmbientMotion.State, Long> {
        var s = state
        var t = now
        repeat(frames) {
            t += 16L
            s = m.stepFrame(s, 200f, 0.5f, bass = bass, dt = 16L, now = t)
        }
        return s to t
    }

    @Test
    fun `低频突然冲高算起拍`() {
        val m = motion()
        var (state, now) = settle(m, AmbientMotion.State(), 0.05f, 10, 10_000L)
        now += 16L
        state = m.stepFrame(state, 200f, 0.5f, bass = 0.95f, dt = 16L, now = now)
        assertEquals(1f, state.pulse, 0.001f)
    }

    @Test
    fun `底噪不起拍`() {
        // 自适应阈值的意义：慢包络被底噪拉起来之后，
        // 同样的底噪不再满足「比慢包络高 1.25 倍」。
        val m = motion()
        var state = AmbientMotion.State()
        var now = 10_000L
        repeat(20) {
            now += 16L
            state = m.stepFrame(state, 200f, 0.5f, bass = 0.05f, dt = 16L, now = now)
        }
        assertEquals(0f, state.pulse, 0.001f)
    }

    @Test
    fun `最短间隔内不重复起拍`() {
        // 240ms 的最短间隔是为了避免 120BPM 的八分音符（≈250ms）被当成两次起拍。
        val m = motion()
        var (state, now) = settle(m, AmbientMotion.State(), 0.05f, 10, 10_000L)
        now += 16L
        state = m.stepFrame(state, 200f, 0.5f, bass = 0.95f, dt = 16L, now = now)
        assertEquals("第一次应该起拍", 1f, state.pulse, 0.001f)
        // 100ms 之后再来一次强低频 —— 不该再起拍
        now += 100L
        state = m.stepFrame(state, 200f, 0.5f, bass = 0.95f, dt = 16L, now = now)
        assertTrue("最短间隔内不该再次起拍（实际 ${state.pulse}）", state.pulse < 1f)
    }

    @Test
    fun `脉冲会衰减`() {
        val m = motion()
        var (state, now) = settle(m, AmbientMotion.State(), 0.05f, 10, 10_000L)
        now += 16L
        state = m.stepFrame(state, 200f, 0.5f, bass = 0.95f, dt = 16L, now = now)
        assertEquals(1f, state.pulse, 0.001f)
        state = m.stepFrame(state, 200f, 0.5f, bass = 0f, dt = 16L, now = now + 16L)
        assertTrue("脉冲该衰减（实际 ${state.pulse}）", state.pulse < 1f && state.pulse > 0f)
    }

    @Test
    fun `起拍把色相往暖端推一点点`() {
        // 鼓点看得见，但不刺眼 —— 只有 14°。
        val m = motion()
        var (state, now) = settle(m, AmbientMotion.State(), 0.05f, 20, 10_000L)
        val before = state.hue
        now += 16L
        state = m.stepFrame(state, 200f, 0.5f, bass = 0.95f, dt = 16L, now = now)
        val pushed = AmbientMotion.shortestDelta(before, state.hue)
        assertTrue("起拍应把色相往暖端推（实际 $pushed）", pushed > 0f)
    }

    @Test
    fun `色相能追上不是起点的目标`() {
        // 上面那条「速度能起来」用的起点是默认状态，而第一帧会**直接落在**
        // 目标上 —— 误差永远是 0，跟踪能力根本没被测到。
        // 一个只以应有速度 1/64 在爬的色相就是这样活下来的。
        //
        // 这里第一帧落在 200，随后目标移到 280：色相要追 80°，
        // tau = 260 ms 的话一两秒内必须到位。
        val m = motion()
        var state = AmbientMotion.State()
        var now = 10_000L
        now += 16L
        state = m.stepFrame(state, 200f, 0.5f, dt = 16L, now = now)
        assertEquals("第一帧应落在目标上", 200f, state.hue, 0.001f)
        repeat(120) {
            now += 16L
            state = m.stepFrame(state, 280f, 0.5f, dt = 16L, now = now)
        }
        assertTrue(
            "色相应追上目标（实际 ${state.hue}）",
            abs(state.hue - 280f) < 3f
        )
    }

    @Test
    fun `色相仍然滤掉抖动的目标`() {
        // 反过来”的要求：跟踪器要跟得上音乐，但也要慢到不会照抄每一帧。
        // 目标每帧在 0° / 30° 之间翻，色相每帧不能动超过一度。
        val m = motion()
        var state = AmbientMotion.State()
        var now = 10_000L
        now += 16L
        state = m.stepFrame(state, 0f, 0.5f, dt = 16L, now = now)
        var maxStep = 0f
        repeat(30) { i ->
            val target = if (i % 2 == 0) 0f else 30f
            val before = state.hue
            now += 16L
            state = m.stepFrame(state, target, 0.5f, dt = 16L, now = now)
            var moved = abs(state.hue - before) % 360f
            if (moved > 180f) moved = 360f - moved
            if (moved > maxStep) maxStep = moved
        }
        assertTrue("抖动的目标不该让色相每帧乱跳（实际 $maxStep）", maxStep <= 0.97f)
    }

    // ------------------------------------------------------------ 空闲（呼吸）

    @Test
    fun `空闲时色相缓慢漂移而不是冻住`() {
        val m = motion()
        var state = AmbientMotion.State()
        var now = 10_000L
        repeat(20) {
            now += 16L
            state = m.stepFrame(state, 200f, 0.5f, dt = 16L, now = now)
        }
        val before = state.hue
        // 空闲 2 秒
        repeat(125) {
            now += 16L
            state = m.stepFrame(state, 200f, 0f, dt = 16L, now = now, silent = true)
        }
        val drifted = AmbientMotion.shortestDelta(before, state.hue)
        // 2.2°/s × 2s ≈ 4.4°（减去限速影响）
        assertTrue("空闲时色相应缓慢漂移（实际 $drifted）", drifted > 1f)
        assertTrue("漂移不该太猛（实际 $drifted）", drifted < 15f)
    }

    @Test
    fun `空闲时落到呼吸底光而不是灭掉`() {
        val m = motion(breathAmount = 0.10f)
        var state = AmbientMotion.State()
        var now = 10_000L
        repeat(60) {
            now += 16L
            state = m.stepFrame(state, 200f, 1f, dt = 16L, now = now)
        }
        // 音乐停了
        repeat(300) {
            now += 16L
            state = m.stepFrame(state, 200f, 0f, dt = 16L, now = now, silent = true)
        }
        assertTrue("空闲后仍要有底光（实际 ${state.level}）", state.level > 0f)
        assertTrue("空闲后不该还亮着（实际 ${state.level}）", state.level < 0.2f)
    }

    // ------------------------------------------------------------ 彩度

    @Test
    fun `彩度平滑得慢 单帧变化有限`() {
        val m = motion()
        var state = AmbientMotion.State()
        state = m.stepFrame(state, 200f, 0.5f, chroma = 0f)
        state = m.stepFrame(state, 200f, 0.5f, chroma = 0.22f)
        assertTrue("彩度不该一步到位（实际 ${state.chroma}）", state.chroma < 0.22f)
        assertTrue("但该开始涨（实际 ${state.chroma}）", state.chroma > 0f)
    }

    // ------------------------------------------------------------ 与旧版对比

    @Test
    fun `新版单帧色差远小于旧版`() {
        // 场景：低频里最响的那一段在两个频段之间来回跳
        // （这正是真实音乐里最让灯「跳」的场景）。
        val bands = FloatArray(16)
        fun frame(loudBand: Int) {
            for (i in bands.indices) bands[i] = 0.05f
            bands[loudBand] = 0.9f
        }

        // —— 旧版：10Hz，色相是**离散挑出来**的那一段 ——
        var lastHue = 0f
        var prevOldLab: Triple<Float, Float, Float>? = null
        var maxOld = 0f
        for (i in 0 until 40) {
            frame(if (i % 2 == 0) 0 else 3)
            val hue = AmbientPalette.bassHue(bands, lastHue)
            lastHue = hue
            val color = AmbientPalette.colorOf(hue, 50)
            val lab = AmbientColorSpace.rgbToLab(color.r, color.g, color.b)
            prevOldLab?.let { maxOld = maxOf(maxOld, AmbientColorSpace.deltaE00(it, lab)) }
            prevOldLab = lab
        }

        // —— 新版：60Hz，二阶限速 + 感知色空间 ——
        val m = motion()
        var state = AmbientMotion.State()
        var now = 10_000L
        // 先预热到稳态（实车上灯早就亮了，不该拿「从黑启动」去比）
        repeat(120) {
            now += 16L
            state = m.stepFrame(state, 200f, 0.5f, chroma = 0.18f, dt = 16L, now = now)
        }
        var prevRawLab: Triple<Float, Float, Float>? = null
        var prevLimitedColor: AmbientColor? = null
        var maxNewRaw = 0f
        var maxNewLimited = 0f
        for (i in 0 until 40) {
            frame(if (i % 2 == 0) 0 else 3)
            val targetHue = AmbientPalette.lowCentroidHue(bands, state.hue)
            // 60Hz：每个频谱帧（10Hz）之间插 6 个渲染帧
            repeat(6) {
                now += 16L
                state = m.stepFrame(
                    state, targetHue, 0.5f, chroma = 0.18f,
                    bass = AmbientPalette.bassEnergy(bands),
                    dt = 16L, now = now,
                )
                val raw = AmbientPalette.colorOfOklch(
                    state.hue,
                    AmbientPalette.perceptualLightness(state.level),
                    state.chroma,
                )
                // 限速（与控制器用的是同一个函数）
                val limited = prevLimitedColor?.let {
                    val rgb = AmbientColorSpace.limitByDeltaE(
                        it.r, it.g, it.b, raw.r, raw.g, raw.b, MAX_DELTA_E,
                    )
                    AmbientColor(rgb.first, rgb.second, rgb.third, raw.brightness)
                } ?: raw

                val rawLab = AmbientColorSpace.rgbToLab(raw.r, raw.g, raw.b)
                prevRawLab?.let { maxNewRaw = maxOf(maxNewRaw, AmbientColorSpace.deltaE00(it, rawLab)) }

                val limLab = AmbientColorSpace.rgbToLab(limited.r, limited.g, limited.b)
                prevLimitedColor?.let {
                    val prevLimLab = AmbientColorSpace.rgbToLab(it.r, it.g, it.b)
                    maxNewLimited = maxOf(maxNewLimited, AmbientColorSpace.deltaE00(prevLimLab, limLab))
                }

                prevRawLab = rawLab
                prevLimitedColor = limited
            }
        }

        assertTrue(
            "旧版单帧色差 $maxOld 应该远大于新版限速后的 $maxNewLimited",
            maxOld > maxNewLimited * 4,
        )
        assertTrue(
            "新版限速后单帧色差应不超过上限（实际 $maxNewLimited）",
            maxNewLimited <= MAX_DELTA_E + 0.05f,
        )
        assertTrue("限速器确实该介入过（原始色差 $maxNewRaw）", maxNewRaw > MAX_DELTA_E)
    }

    // ------------------------------------------------------------ 跳跃（色相跳变）

    @Test
    fun `色相跳变模式下颜色直接吸到档位`() {
        val m = motion(hueJumpDeg = 60f)
        var s = AmbientMotion.State()
        // 100° 最近的 60° 档位是 120°
        s = m.stepFrame(s, hue = 100f, level = 0.5f)
        assertEquals(120f, s.hue, 0.001f)
        // 10° 归 0°
        s = m.stepFrame(s, hue = 10f, level = 0.5f)
        assertEquals(0f, s.hue, 0.001f)
        // 30° 刚好在中间，四舍五入到 60°
        s = m.stepFrame(s, hue = 30f, level = 0.5f)
        assertEquals(60f, s.hue, 0.001f)
    }

    @Test
    fun `色相跳变一帧到位，不经过中间色`() {
        val jump = motion(hueJumpDeg = 60f)
        var s = AmbientMotion.State()
        s = jump.stepFrame(s, hue = 0f, level = 0.5f)
        assertEquals(0f, s.hue, 0.001f)
        // 目标和上一个档位隔了 180°：跳变模式一帧就到，连续模式要爬几十帧
        s = jump.stepFrame(s, hue = 180f, level = 0.5f)
        assertEquals(180f, s.hue, 0.001f)
        assertEquals(0f, s.hueVel, 0.001f)

        val smooth = motion(hueJumpDeg = 0f)
        var t = AmbientMotion.State()
        t = smooth.stepFrame(t, hue = 0f, level = 0.5f)
        t = smooth.stepFrame(t, hue = 180f, level = 0.5f)
        assertTrue("连续模式一帧只该走一小段（实际 ${t.hue}°）", t.hue < 30f)
    }

    @Test
    fun `色相跳变模式下每个起拍前进一档`() {
        // 60° 档位、120ms 拍间隔：拍子一到就从当前档位跳到下一档。
        //
        // 注意不能一直喂大低音 —— 自适应阈值会把「持续很响」当成常态，不再判起拍。
        // 真实鼓点就是「安静 → 突然变响」，所以测试也这样喂。
        fun run(): List<Float> {
            val m = motion(hueJumpDeg = 60f, beatMinGapMs = 120L)
            var s = AmbientMotion.State()
            var now = 10_000L
            s = m.stepFrame(s, hue = 0f, level = 0.5f, bass = 0f, dt = 16L, now = now)
            val hues = mutableListOf<Float>()
            repeat(4) {
                now += 130L
                s = m.stepFrame(s, hue = 0f, level = 0.5f, bass = 0.05f, dt = 130L, now = now)
                now += 130L
                s = m.stepFrame(s, hue = 0f, level = 0.5f, bass = 0.95f, dt = 130L, now = now)
                hues.add(s.hue)
            }
            return hues
        }
        val hues = run()
        assertTrue(
            "每拍都要换一档颜色（实际 $hues）",
            hues.zipWithNext().all { (a, b) -> abs(a - b) >= 59.9f },
        )
        assertTrue(
            "跳变必须落在 60° 档位上（实际 $hues）",
            hues.all { it % 60f < 0.01f || (60f - it % 60f) < 0.01f },
        )
        // 没有拍子时就定在档位上，不会自己往回滑
        val still = motion(hueJumpDeg = 60f, beatMinGapMs = 120L)
        var u = AmbientMotion.State()
        u = still.stepFrame(u, hue = 100f, level = 0.5f, bass = 0f, dt = 16L, now = 10_000L)
        val first = u.hue
        repeat(6) {
            u = still.stepFrame(u, hue = 100f, level = 0.5f, bass = 0f, dt = 16L, now = 10_000L)
        }
        assertEquals("没拍子时不该自己挪（实际 ${u.hue}）", first, u.hue, 0.001f)
    }

    @Test
    fun `换色间隔封顶：没鼓点也会按时换色`() {
        // 起拍检测是内容驱动的：喂一条「一直很响」的平稳低音，自适应阈值很快把它
        // 当成常态，判不出起拍 —— 之前就是这种场景灯又定住了。
        // 换色间隔 380ms 应该保证「每隔 380ms 至少换一档」。
        fun hues(holdMs: Long): List<Float> {
            val m = motion(hueJumpDeg = 120f, beatMinGapMs = 120L, jumpHoldMs = holdMs)
            var s = AmbientMotion.State()
            var now = 10_000L
            // 先热身：让自适应阈值把「持续 0.5 的低音」认成常态，判不出起拍。
            repeat(12) {
                now += 100L
                s = m.stepFrame(s, hue = 0f, level = 0.5f, bass = 0.5f, dt = 100L, now = now)
            }
            val out = mutableListOf<Float>()
            repeat(12) {
                now += 100L
                s = m.stepFrame(s, hue = 0f, level = 0.5f, bass = 0.5f, dt = 100L, now = now)
                out.add(s.hue)
            }
            return out
        }
        val capped = hues(380L)
        // 1.2s 内至少换 2 次（12 帧 × 100ms）
        val changes = capped.zipWithNext().count { (a, b) -> abs(a - b) > 1f }
        assertTrue("换色间隔封顶后该持续换色（实际换 $changes 次：$capped）", changes >= 2)
        assertTrue("换色仍要落在 120° 档位", capped.all { it % 120f < 0.01f || (120f - it % 120f) < 0.01f })

        // 间隔设为 0 = 只跟拍子：平稳低音下不该自己换色
        val uncapped = hues(0L)
        val stillChanges = uncapped.zipWithNext().count { (a, b) -> abs(a - b) > 1f }
        assertEquals("只跟拍子时平稳低音不该换色（实际 $uncapped）", 0, stillChanges)
    }

    @Test
    fun `起拍闪光越强亮度冲得越高`() {
        val soft = motion(pulseGain = 0.35f)
        val wild = motion(pulseGain = 1.20f)
        var a = AmbientMotion.State()
        var b = AmbientMotion.State()
        var t = 10_000L
        repeat(6) {
            a = soft.stepFrame(a, hue = 0f, level = 0.4f, bass = 0.9f, dt = 16L, now = t)
            b = wild.stepFrame(b, hue = 0f, level = 0.4f, bass = 0.9f, dt = 16L, now = t)
            t += 16L
        }
        assertTrue("跳跃该比默认闪得高（${b.level} vs ${a.level}）", b.level > a.level + 0.05f)
    }

    @Test
    fun `起拍间隔越短闪得越密`() {
        fun beats(gapMs: Long): Int {
            val m = motion(beatMinGapMs = gapMs)
            var s = AmbientMotion.State()
            var n = 0
            var t = 10_000L
            repeat(20) {
                s = m.stepFrame(s, hue = 0f, level = 0.3f, bass = 0.9f, dt = 50L, now = t)
                if (s.pulse >= 0.999f) n++
                t += 50L
            }
            return n
        }
        val tight = beats(120L)
        val loose = beats(240L)
        assertTrue("更短的间隔该闪更多次（$tight vs $loose）", tight > loose)
    }

    companion object {
        /** 每帧感知色差上限（ΔE00）—— 与控制器默认一致。 */
        const val MAX_DELTA_E = 1.8f
    }
}
