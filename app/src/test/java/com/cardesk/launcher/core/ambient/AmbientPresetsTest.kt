package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AmbientPresets] 单测。
 *
 * 设置页靠 [AmbientPresets.match] 决定 chip 上高亮哪一个 ——
 * 它认错了，用户改了参数就会看到「预设」显示成另一个，用户会以为是自己改错了。
 */
class AmbientPresetsTest {

    @Test
    fun `六个预设都齐`() {
        assertEquals(6, AmbientPresets.ALL.size)
        assertEquals(AmbientPresets.ALL.map { it.name }, AmbientPresets.NAMES)
    }

    @Test
    fun `预设名不重复`() {
        assertEquals(
            AmbientPresets.NAMES.size,
            AmbientPresets.NAMES.toSet().size,
        )
    }

    @Test
    fun `参数完全匹配时能认出是哪个预设`() {
        AmbientPresets.ALL.forEachIndexed { index, p ->
            val got = AmbientPresets.match(
                p.attackMs, p.decayMs, p.hueTauMs, p.hueMaxDeg, p.hueMaxAccel,
                p.maxDeltaETenths, p.floor, p.breathHundredths, p.saturationHundredths,
                p.hueSpanDeg, p.centroidWindowLowHundredths, p.centroidWindowSpanHundredths,
                p.hueRotateDegPerSec, p.maxChromaHundredths,
                p.hueJumpDeg, p.pulseGainHundredths, p.beatMinGapMs, p.jumpHoldMs,
            )
            assertEquals("「${p.name}」该被认出来", index, got)
        }
    }

    @Test
    fun `改过参数后不再匹配任何预设`() {
        val p = AmbientPresets.BALANCED
        val got = AmbientPresets.match(
            p.attackMs + 5, p.decayMs, p.hueTauMs, p.hueMaxDeg, p.hueMaxAccel,
            p.maxDeltaETenths, p.floor, p.breathHundredths, p.saturationHundredths,
            p.hueSpanDeg, p.centroidWindowLowHundredths, p.centroidWindowSpanHundredths,
            p.hueRotateDegPerSec, p.maxChromaHundredths,
            p.hueJumpDeg, p.pulseGainHundredths, p.beatMinGapMs, p.jumpHoldMs,
        )
        assertEquals("改过参数就不该匹配任何预设", -1, got)
    }

    @Test
    fun `预设参数都在设置项的合法范围内`() {
        // 越界的预设值会被 Prefs 的 coerce 静默夹掉 ⇒ 设置页显示与实际生效值不一致。
        AmbientPresets.ALL.forEach { p ->
            assertTrue("「${p.name}」attack 越界（${p.attackMs}）", p.attackMs in 10..300)
            assertTrue("「${p.name}」decay 越界（${p.decayMs}）", p.decayMs in 60..1200)
            assertTrue("「${p.name}」色相 τ 越界（${p.hueTauMs}）", p.hueTauMs in 60..1200)
            assertTrue("「${p.name}」限速越界（${p.hueMaxDeg}）", p.hueMaxDeg in 10..180)
            assertTrue("「${p.name}」限加速越界（${p.hueMaxAccel}）", p.hueMaxAccel in 30..800)
            assertTrue("「${p.name}」色差上限越界（${p.maxDeltaETenths}）", p.maxDeltaETenths in 2..60)
            assertTrue("「${p.name}」底光越界（${p.floor}）", p.floor in 0..30)
            assertTrue("「${p.name}」呼吸越界（${p.breathHundredths}）", p.breathHundredths in 0..40)
            assertTrue("「${p.name}」饱和度越界（${p.saturationHundredths}）", p.saturationHundredths in 0..100)
            // 五彩三件套 + 自转：同样不能越界，否则滑杆显示与实际生效值对不上
            assertTrue("「${p.name}」色相跨度越界（${p.hueSpanDeg}）", p.hueSpanDeg in 150..360)
            assertTrue("「${p.name}」质心窗口下限越界（${p.centroidWindowLowHundredths}）", p.centroidWindowLowHundredths in 0..70)
            assertTrue("「${p.name}」质心窗口宽度越界（${p.centroidWindowSpanHundredths}）", p.centroidWindowSpanHundredths in 10..100)
            assertTrue("「${p.name}」自转越界（${p.hueRotateDegPerSec}）", p.hueRotateDegPerSec in 0..30)
            assertTrue("「${p.name}」彩度上限越界（${p.maxChromaHundredths}）", p.maxChromaHundredths in 10..40)
            // 跳跃三件套：同样不能越界
            assertTrue("「${p.name}」色相跳变越界（${p.hueJumpDeg}）", p.hueJumpDeg in 0..180)
            assertTrue("「${p.name}」起拍闪光越界（${p.pulseGainHundredths}）", p.pulseGainHundredths in 0..200)
            assertTrue("「${p.name}」起拍间隔越界（${p.beatMinGapMs}）", p.beatMinGapMs in 80..600)
            // 换色间隔：0 = 只跟拍子，其余 80..1000ms
            assertTrue("「${p.name}」换色间隔越界（${p.jumpHoldMs}）", p.jumpHoldMs == 0 || p.jumpHoldMs in 80..1000)
        }
    }

    @Test
    fun `彩虹是最宽、最艳、唯一会自转的一组`() {
        val r = AmbientPresets.RAINBOW
        assertEquals(360, r.hueSpanDeg)
        assertTrue("彩虹跨度该是所有预设里最宽的", AmbientPresets.ALL.all { it.hueSpanDeg <= r.hueSpanDeg })
        assertTrue("彩虹该是唯一会自转的预设", AmbientPresets.ALL.filter { it !== r }.all { it.hueRotateDegPerSec == 0 })
        assertTrue("彩虹该比均衡更艳", r.maxChromaHundredths > AmbientPresets.BALANCED.maxChromaHundredths)
        assertTrue("彩虹该把质心窗口收窄", r.centroidWindowSpanHundredths < AmbientPresets.BALANCED.centroidWindowSpanHundredths)
        // 14 个字段全对上才算「就是彩虹」
        assertEquals(
            AmbientPresets.ALL.indexOf(r),
            AmbientPresets.match(
                r.attackMs, r.decayMs, r.hueTauMs, r.hueMaxDeg, r.hueMaxAccel,
                r.maxDeltaETenths, r.floor, r.breathHundredths, r.saturationHundredths,
                r.hueSpanDeg, r.centroidWindowLowHundredths, r.centroidWindowSpanHundredths,
                r.hueRotateDegPerSec, r.maxChromaHundredths,
                r.hueJumpDeg, r.pulseGainHundredths, r.beatMinGapMs, r.jumpHoldMs,
            ),
        )
        // 跨度被改回 150 就不再是彩虹（避免「改了跨度 chip 还亮着」）
        assertEquals(
            -1,
            AmbientPresets.match(
                r.attackMs, r.decayMs, r.hueTauMs, r.hueMaxDeg, r.hueMaxAccel,
                r.maxDeltaETenths, r.floor, r.breathHundredths, r.saturationHundredths,
                150, r.centroidWindowLowHundredths, r.centroidWindowSpanHundredths,
                r.hueRotateDegPerSec, r.maxChromaHundredths,
                r.hueJumpDeg, r.pulseGainHundredths, r.beatMinGapMs, r.jumpHoldMs,
            ),
        )
    }

    @Test
    fun `夜驾柔和比均衡更保守`() {
        // 夜驾：变化更慢、色差上限更紧、底光更低 —— 这三条是「不刺眼」的来源。
        val n = AmbientPresets.NIGHT
        val b = AmbientPresets.BALANCED
        assertTrue("attack 该更大（${n.attackMs} vs ${b.attackMs}）", n.attackMs > b.attackMs)
        assertTrue("decay 该更大（${n.decayMs} vs ${b.decayMs}）", n.decayMs > b.decayMs)
        assertTrue("色相 τ 该更大（${n.hueTauMs} vs ${b.hueTauMs}）", n.hueTauMs > b.hueTauMs)
        assertTrue("限速该更低（${n.hueMaxDeg} vs ${b.hueMaxDeg}）", n.hueMaxDeg < b.hueMaxDeg)
        assertTrue("色差上限该更紧（${n.maxDeltaETenths} vs ${b.maxDeltaETenths}）", n.maxDeltaETenths < b.maxDeltaETenths)
    }

    @Test
    fun `赛博高对比比均衡更激进`() {
        val c = AmbientPresets.CYBER
        val b = AmbientPresets.BALANCED
        assertTrue("attack 该更小（${c.attackMs} vs ${b.attackMs}）", c.attackMs < b.attackMs)
        assertTrue("decay 该更小（${c.decayMs} vs ${b.decayMs}）", c.decayMs < b.decayMs)
        assertTrue("限速该更高（${c.hueMaxDeg} vs ${b.hueMaxDeg}）", c.hueMaxDeg > b.hueMaxDeg)
        assertTrue("限加速该更高（${c.hueMaxAccel} vs ${b.hueMaxAccel}）", c.hueMaxAccel > b.hueMaxAccel)
        assertTrue("饱和度该更高（${c.saturationHundredths} vs ${b.saturationHundredths}）", c.saturationHundredths > b.saturationHundredths)
    }

    @Test
    fun `跳跃是最跳、最闪、唯一做色相跳变的一组`() {
        val g = AmbientPresets.JUMP
        assertEquals("跳跃用 120° 大跳（三段一组）", 120, g.hueJumpDeg)
        assertTrue(
            "跳跃该有强制换色间隔（没鼓点也要闪）",
            g.jumpHoldMs in 200..600,
        )
        assertTrue(
            "只有跳跃会强制换色",
            AmbientPresets.ALL.filter { it !== g }.all { it.jumpHoldMs == 0 },
        )
        assertTrue(
            "跳跃该是唯一做色相跳变的预设",
            AmbientPresets.ALL.filter { it !== g }.all { it.hueJumpDeg == 0 },
        )
        assertTrue(
            "跳跃该比均衡闪得更强（${g.pulseGainHundredths} vs ${AmbientPresets.BALANCED.pulseGainHundredths}）",
            g.pulseGainHundredths > AmbientPresets.BALANCED.pulseGainHundredths,
        )
        assertTrue(
            "跳跃该比均衡闪得更密（${g.beatMinGapMs} vs ${AmbientPresets.BALANCED.beatMinGapMs}）",
            g.beatMinGapMs < AmbientPresets.BALANCED.beatMinGapMs,
        )
        assertTrue(
            "跳跃该比均衡落得更快（${g.decayMs} vs ${AmbientPresets.BALANCED.decayMs}）",
            g.decayMs < AmbientPresets.BALANCED.decayMs,
        )
        assertEquals("跳跃不要底光（要能闪黑）", 0, g.floor)
        // 18 个字段全对上才算「就是跳跃」
        assertEquals(
            AmbientPresets.ALL.indexOf(g),
            AmbientPresets.match(
                g.attackMs, g.decayMs, g.hueTauMs, g.hueMaxDeg, g.hueMaxAccel,
                g.maxDeltaETenths, g.floor, g.breathHundredths, g.saturationHundredths,
                g.hueSpanDeg, g.centroidWindowLowHundredths, g.centroidWindowSpanHundredths,
                g.hueRotateDegPerSec, g.maxChromaHundredths,
                g.hueJumpDeg, g.pulseGainHundredths, g.beatMinGapMs, g.jumpHoldMs,
            ),
        )
        // 跳变关掉就不再是跳跃
        assertEquals(
            -1,
            AmbientPresets.match(
                g.attackMs, g.decayMs, g.hueTauMs, g.hueMaxDeg, g.hueMaxAccel,
                g.maxDeltaETenths, g.floor, g.breathHundredths, g.saturationHundredths,
                g.hueSpanDeg, g.centroidWindowLowHundredths, g.centroidWindowSpanHundredths,
                g.hueRotateDegPerSec, g.maxChromaHundredths,
                0, g.pulseGainHundredths, g.beatMinGapMs, g.jumpHoldMs,
            ),
        )
    }
}
