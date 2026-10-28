package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景页音乐卡「进度环 / 封面光晕」几何。
 */
class MusicCardVisualsTest {

    // ------------------------------------------------------------ 进度环 fraction

    @Test
    fun ringFraction_isPositionOverDuration() {
        assertEquals(0.25f, MusicCardVisuals.ringFraction(30_000L, 120_000L), 1e-6f)
    }

    @Test
    fun ringFraction_clampsPositionAboveDuration() {
        assertEquals(1f, MusicCardVisuals.ringFraction(300_000L, 120_000L), 1e-6f)
    }

    @Test
    fun ringFraction_clampsNegativePositionToZero() {
        assertEquals(0f, MusicCardVisuals.ringFraction(-5_000L, 120_000L), 1e-6f)
    }

    @Test
    fun ringFraction_unknownDurationIsZero() {
        // —— duration <= 0 时走 resetMediaProgress() → setFraction(0f)
        assertEquals(0f, MusicCardVisuals.ringFraction(30_000L, 0L), 1e-6f)
    }

    @Test
    fun ringFraction_negativeDurationIsZero() {
        assertEquals(0f, MusicCardVisuals.ringFraction(30_000L, -1L), 1e-6f)
    }

    @Test
    fun ringFraction_alwaysWithinUnitRange() {
        val cases = listOf(
            0L to 200_000L,
            199_999L to 200_000L,
            200_000L to 200_000L,
            999_999L to 200_000L,
        )
        for ((pos, dur) in cases) {
            val f = MusicCardVisuals.ringFraction(pos, dur)
            assertTrue("fraction 必须在 0..1：pos=$pos dur=$dur → $f", f in 0f..1f)
        }
    }

    // ------------------------------------------------------------ 进度环半径

    @Test
    fun ringRadius_matchesReferenceFormula() {
        // —— min(w/2, h/2) - max(1, stroke)
        assertEquals(38f, MusicCardVisuals.ringRadius(80f, 2f), 1e-6f)
    }

    @Test
    fun ringRadius_strokeBelowOneIsClampedToOne() {
        // max(1.0f, strokeWidth)：描边不足 1 时按 1 算
        assertEquals(39f, MusicCardVisuals.ringRadius(80f, 0.4f), 1e-6f)
    }

    @Test
    fun ringRadius_neverNegative() {
        assertTrue(MusicCardVisuals.ringRadius(2f, 2f) <= 0f)
    }

    // ------------------------------------------------------------ 封面光晕

    @Test
    fun glowCenter_isOneTenthWidthAndHalfHeight() {
        // —— (width * 0.1f, height * 0.5f)
        assertEquals(40f, MusicCardVisuals.glowCenterX(0f, 400f), 1e-6f)
        assertEquals(75f, MusicCardVisuals.glowCenterY(0f, 150f), 1e-6f)
    }

    @Test
    fun glowCenter_keepsCardLeftOffset() {
        assertEquals(84f, MusicCardVisuals.glowCenterX(44f, 400f), 1e-6f)
    }

    @Test
    fun glowRadius_isMaxOfWidthAndHeight() {
        // —— Math.max(width, height)
        assertEquals(400f, MusicCardVisuals.glowRadius(400f, 150f), 1e-6f)
        assertEquals(150f, MusicCardVisuals.glowRadius(150f, 150f), 1e-6f)
    }

    @Test
    fun glowColor_isArgbWithGivenAlpha() {
        // —— Color.argb(128, r, g, b)
        val argb = MusicCardVisuals.glowColor(0xFFB8743A.toInt(), 128)
        assertEquals(128, (argb ushr 24) and 0xFF)
        assertEquals(0xB8, (argb ushr 16) and 0xFF)
        assertEquals(0x74, (argb ushr 8) and 0xFF)
        assertEquals(0x3A, argb and 0xFF)
    }

    @Test
    fun glowColor_ignoresSourceAlpha() {
        // setGlowColor 把 alpha 强制成 0xFF，所以采样色带不带 alpha 都一样
        val a = MusicCardVisuals.glowColor(0x00B8743A, 128)
        val b = MusicCardVisuals.glowColor(0xFFB8743A.toInt(), 128)
        assertEquals(a, b)
    }

    // ------------------------------------------------------------ 标题列宽

    @Test
    fun textColumnWidth_matchesReferenceLayout() {
        // 第五十轮对齐设计板 s01：封面 96 → 68、封面与文字列间距 20 → 14、
        // 进度环容器 80 → 64、环与文字列间距 16 → 14、内边距 24 → 18
        // 标题列 = 338 − 36 − 68 − 14 − 14 − 64 = 142
        assertEquals(142f, MusicCardVisuals.textColumnWidth(), 1e-6f)
    }

    /** 旧的 400 宽布局仍要能算（换别的卡宽时用得上）。 */
    @Test
    fun textColumnWidth_referenceLayoutStillComputable() {
        assertEquals(156f, MusicCardVisuals.textColumnWidth(400f, 24f, 96f, 20f, 0f, 80f), 1e-6f)
    }
}
