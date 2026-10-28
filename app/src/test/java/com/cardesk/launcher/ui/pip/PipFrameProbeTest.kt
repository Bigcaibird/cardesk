package com.cardesk.launcher.ui.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PipFrameProbe] 的**纯判定**单测。
 *
 * 这一层只测「要不要自愈」的三道闸 —— 采样本身要真机（`PixelCopy`）才能跑。
 */
class PipFrameProbeTest {

    @Test
    fun `没见过非黑画面时不自愈`() {
        // 第一道闸：有些设备 PixelCopy 采 SurfaceView 恒为黑（硬件 overlay），
        // 那种情况下**绝不能**一直以为黑了 → 一直重启承载应用。
        assertFalse(
            PipFrameProbe.shouldHeal(
                sawContent = false,
                consecutiveBlack = 99,
            ),
        )
    }

    @Test
    fun `黑帧不够多不自愈`() {
        // 第二道闸：避开地图加载中的短暂黑帧
        assertFalse(
            PipFrameProbe.shouldHeal(
                sawContent = true,
                consecutiveBlack = PipFrameProbe.BLACK_STREAK_LIMIT - 1,
            ),
        )
    }

    @Test
    fun `见过画面又连续全黑就自愈`() {
        assertTrue(
            PipFrameProbe.shouldHeal(
                sawContent = true,
                consecutiveBlack = PipFrameProbe.BLACK_STREAK_LIMIT,
            ),
        )
    }

    @Test
    fun `冷却没过不自愈`() {
        // 第三道闸：不要连着重启承载应用
        assertFalse(
            PipFrameProbe.shouldHeal(
                sawContent = true,
                consecutiveBlack = PipFrameProbe.BLACK_STREAK_LIMIT,
                cooldownMs = 60_000L,
                sinceLastHealMs = 1_000L,
            ),
        )
        assertTrue(
            PipFrameProbe.shouldHeal(
                sawContent = true,
                consecutiveBlack = PipFrameProbe.BLACK_STREAK_LIMIT,
                cooldownMs = 60_000L,
                sinceLastHealMs = 60_000L,
            ),
        )
    }

    @Test
    fun `阈值常量符合预期`() {
        assertEquals(3, PipFrameProbe.BLACK_STREAK_LIMIT)
        assertEquals(60_000L, PipFrameProbe.BLACK_HEAL_COOLDOWN_MS)
        assertEquals(10, PipFrameProbe.BLACK_LUMA)
        assertEquals(64, PipFrameProbe.SAMPLE_W)
        assertEquals(36, PipFrameProbe.SAMPLE_H)
    }
}
