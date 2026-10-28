package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面强调色的纯逻辑单测。
 */
class CoverAccentTest {

    @Test
    fun pick_emptyPixelsFallsBackToDefault() {
        assertEquals(CoverAccent.DEFAULT_ACCENT, CoverAccent.pick(IntArray(0)))
    }

    @Test
    fun pick_fullyTransparentPixelsAreIgnored() {
        assertEquals(CoverAccent.DEFAULT_ACCENT, CoverAccent.pick(intArrayOf(0x00FF0000)))
    }

    @Test
    fun pick_picksSaturatedColor() {
        val accent = CoverAccent.pick(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        // 三个像素分数相同 → 取第一个（红）
        assertEquals(0xFFFF0000.toInt(), accent)
    }

    @Test
    fun pick_grayImageFallsBackToDefault() {
        // 低饱和度 → 分数不足 0.04
        assertEquals(CoverAccent.DEFAULT_ACCENT, CoverAccent.pick(intArrayOf(0xFF808080.toInt(), 0xFF202020.toInt())))
    }

    @Test
    fun pick_defaultAccentMatchesReferenceValue() {
        // = -4688838
        assertEquals(-4688838, CoverAccent.DEFAULT_ACCENT)
    }

    @Test
    fun sample_nullBitmapFallsBackToDefault() {
        assertEquals(CoverAccent.DEFAULT_ACCENT, CoverAccent.sample(null))
    }

    @Test
    fun pick_saturationAndValueAreBoosted() {
        // 一个偏暗的饱和蓝：HSV(240, 1, 0.5) → s→1.0，v→0.78
        val accent = CoverAccent.pick(intArrayOf(0xFF000080.toInt()))
        val r = (accent shr 16) and 0xFF
        val g = (accent shr 8) and 0xFF
        val b = accent and 0xFF
        assertTrue("b 应最高", b > r && b > g)
        assertTrue("明度应抬到 >= 0.78", b >= 199)
    }
}
