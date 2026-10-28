package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设计坐标缩放的边界情况。 */
class DesignTest {

    @Test
    fun fullHd_isIdentity() {
        assertEquals(1f, Design.scaleFor(1920, 1080), 0.0001f)
    }

    @Test
    fun fourK_isDouble() {
        assertEquals(2f, Design.scaleFor(3840, 2160), 0.0001f)
    }

    @Test
    fun hd_keepsAspectRatio() {
        val scale = Design.scaleFor(1280, 720)
        assertEquals(2f / 3f, scale, 0.0001f)
    }

    @Test
    fun ultraWide_letterboxesInsteadOfStretching() {
        // 1920x720 这种窄高比屏幕按高度对齐，宽度方向留边
        assertEquals(720f / 1080f, Design.scaleFor(1920, 720), 0.0001f)
    }

    @Test
    fun degenerateSize_fallsBackToIdentity() {
        assertEquals(1f, Design.scaleFor(0, 0), 0.0001f)
        assertEquals(1f, Design.scaleFor(-100, 500), 0.0001f)
    }

    @Test
    fun scaleIsNeverZero() {
        assertTrue(Design.scaleFor(1, 1) > 0f)
    }
}
