package com.cardesk.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖页开关动画的**数值规格**单测（纯 Kotlin，不碰 Android）。
 *
 * （打开）
 * （关闭）。
 */
class OverlaySpecTest {

    /** `panel.setTranslationY(56.0f)`。 */
    @Test
    fun slideDistance_matchesReference() {
        assertEquals(56f, OverlaySpec.SLIDE_PX, 1e-4f)
    }

    /** 打开：面板 200ms、遮罩 180ms。 */
    @Test
    fun openDurations_matchReference() {
        assertEquals(200L, OverlaySpec.OPEN_DURATION)
        assertEquals(180L, OverlaySpec.OPEN_SCRIM_DURATION)
    }

    /** 关闭：下滑 240ms、淡出 200ms。 */
    @Test
    fun closeDurations_matchReference() {
        assertEquals(240L, OverlaySpec.CLOSE_DURATION)
        assertEquals(200L, OverlaySpec.CLOSE_FADE_DURATION)
    }

    /** 关比开慢一点（240ms vs 200ms）。 */
    @Test
    fun closeIsSlowerThanOpen() {
        assertTrue(OverlaySpec.CLOSE_DURATION > OverlaySpec.OPEN_DURATION)
    }

    /** `PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f)`。 */
    @Test
    fun interpolatorMatchesReference() {
        assertEquals(0.2f, OverlaySpec.INTERPOLATOR_X1, 1e-4f)
        assertEquals(0.8f, OverlaySpec.INTERPOLATOR_Y1, 1e-4f)
        assertEquals(0.2f, OverlaySpec.INTERPOLATOR_X2, 1e-4f)
        assertEquals(1f, OverlaySpec.INTERPOLATOR_Y2, 1e-4f)
    }
}
