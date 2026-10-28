package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词**擦除高亮**的纯逻辑单测。
 *
 * （帧回调 `exp(-dt*18)`、
 * 边缘宽度 `max(6px, 8dp)`、边缘带 55% 透明度。
 */
class LyricWipeTest {

    private val eps = 1e-4f

    // ---------------------------------------------------------------- 常量

    /** 目标跳变阈值 0.02。 */
    @Test
    fun jumpThreshold_matchesReference() {
        assertEquals(0.02f, LyricWipe.JUMP_THRESHOLD, eps)
    }

    /** 帧回调里的指数系数 18。 */
    @Test
    fun rate_matchesReference() {
        assertEquals(18f, LyricWipe.RATE, eps)
    }

    /** 边缘带用强调色 55% 透明度。 */
    @Test
    fun edgeAlphaRatio_matchesReference() {
        assertEquals(0.55f, LyricWipe.EDGE_ALPHA_RATIO, eps)
    }

    // ---------------------------------------------------------------- edgeWidth

    /** `Math.max(6.0f, density * 8.0f)`：低密度时保底 6px。 */
    @Test
    fun edgeWidth_hasSixPixelFloor() {
        assertEquals(6f, LyricWipe.edgeWidth(0.5f), eps)
        assertEquals(6f, LyricWipe.edgeWidth(0.75f), eps)
    }

    /** 密度 ≥ 0.75 时按 8dp 算。 */
    @Test
    fun edgeWidth_scalesWithDensity() {
        assertEquals(16f, LyricWipe.edgeWidth(2f), eps)
        assertEquals(8f, LyricWipe.edgeWidth(1f), eps)
    }

    // ---------------------------------------------------------------- smoothStep

    /** 指数逼近：单帧后应当朝目标靠近，但**不能一步到位**。 */
    @Test
    fun smoothStep_movesTowardTargetWithoutOvershoot() {
        val next = LyricWipe.smoothStep(0f, 1f, 16L)
        assertTrue("应当往前走", next > 0f)
        assertTrue("不应一步到位", next < 1f)
    }

    /** 逼近是单调的：帧间隔越大，越接近目标。 */
    @Test
    fun smoothStep_isMonotonicInFrameTime() {
        val small = LyricWipe.smoothStep(0f, 1f, 8L)
        val big = LyricWipe.smoothStep(0f, 1f, 32L)
        assertTrue(big > small)
    }

    /** 逼近不会过冲（不能超过 target）。 */
    @Test
    fun smoothStep_neverOvershoots() {
        var d = 0f
        repeat(200) {
            d = LyricWipe.smoothStep(d, 1f, 16L)
            assertTrue("过冲了: $d", d <= 1f + eps)
        }
    }

    /** 足够多帧之后应当吸附到目标（误差 < 4e-4）。 */
    @Test
    fun smoothStep_convergesToTarget() {
        var d = 0f
        repeat(300) {
            d = LyricWipe.smoothStep(d, 1f, 16L)
        }
        assertEquals(1f, d, 4e-4f)
    }

    /** 已经在目标上时不应抖动。 */
    @Test
    fun smoothStep_staysAtTarget() {
        assertEquals(0.5f, LyricWipe.smoothStep(0.5f, 0.5f, 16L), eps)
    }

    // ---------------------------------------------------------------- LyricWipeState

    /** 往前：display 不立刻跳到 target，交给动画追。 */
    @Test
    fun state_forwardKeepsDisplayForAnimation() {
        val s = LyricWipeState()
        s.setTarget(0.5f)
        assertEquals(0f, s.display, eps)
        assertEquals(0.5f, s.target, eps)
    }

    /** 往回（切歌 / 跳行）：**直接跳**，不做倒带。 */
    @Test
    fun state_backwardJumpsImmediately() {
        val s = LyricWipeState()
        s.setTarget(0.8f)
        s.advance(1000L)
        s.advance(1016L)
        assertTrue(s.display > 0f)
        // 回退到更小的目标 → 立刻落到新目标
        s.setTarget(0.1f)
        assertEquals(0.1f, s.display, eps)
        assertEquals(0.1f, s.target, eps)
    }

    /**
     * 回退幅度落在 0.02 死区内（`0.02f + fMax >= displayFraction`）
     * **仍走动画**，display 不立刻变。
     */
    @Test
    fun state_smallBackwardStepStaysAnimated() {
        val s = LyricWipeState()
        s.setTarget(0.5f)
        s.advance(1000L)
        s.advance(1016L)
        val before = s.display
        s.setTarget(before - 0.01f)
        assertEquals("死区内应保持原值交给动画", before, s.display, eps)
        assertEquals(before - 0.01f, s.target, eps)
    }

    /** 回退幅度超过 0.02 → **直接跳**到新目标（切歌 / 跳行）。 */
    @Test
    fun state_largeBackwardStepJumpsImmediately() {
        val s = LyricWipeState()
        s.setTarget(0.5f)
        s.advance(1000L)
        s.advance(1016L)
        val before = s.display
        s.setTarget(before - 0.05f)
        assertEquals(before - 0.05f, s.display, eps)
        assertEquals(before - 0.05f, s.target, eps)
    }

    /** 目标被钳在 0..1。 */
    @Test
    fun state_clampsTarget() {
        val s = LyricWipeState()
        s.setTarget(1.7f)
        assertEquals(1f, s.target, eps)
        s.setTarget(-0.4f)
        assertEquals(0f, s.target, eps)
    }

    /** 重复设置同一个目标不应产生变化。 */
    @Test
    fun state_ignoresSameTarget() {
        val s = LyricWipeState()
        s.setTarget(0.3f)
        s.advance(1000L)
        val d = s.display
        s.setTarget(0.3f)
        assertEquals(d, s.display, eps)
    }

    /** advance：还在动时返回 true，停下来后返回 false。 */
    @Test
    fun state_advanceReportsMotion() {
        val s = LyricWipeState()
        s.setTarget(1f)
        assertTrue(s.advance(1000L))
        var guard = 0
        while (s.advance(1016L + guard * 16L) && guard < 500) {
            guard++
        }
        assertTrue("应当收敛", guard < 500)
        assertEquals(1f, s.display, 4e-4f)
    }

    /** reset 回到全零。 */
    @Test
    fun state_resetClearsEverything() {
        val s = LyricWipeState()
        s.setTarget(0.7f)
        s.advance(1000L)
        s.reset()
        assertEquals(0f, s.display, eps)
        assertEquals(0f, s.target, eps)
    }

    /** 收敛后不再请求下一帧（避免空转）。 */
    @Test
    fun state_settlesAndStopsAnimating() {
        val s = LyricWipeState()
        s.setTarget(0.9f)
        var t = 0L
        var moving = true
        var frames = 0
        while (moving && frames < 600) {
            t += 16L
            moving = s.advance(t)
            frames++
        }
        assertFalse("应当停下来", moving)
        assertTrue("帧数应当有限", frames < 600)
    }
}
