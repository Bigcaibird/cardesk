package com.cardesk.launcher.ui.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PipFrameProbe] 的**纯判定**单测。
 *
 * 这一层只测「这一帧算不算黑」「采样矩形往里缩多少」——
 * 采样本身要真机（`PixelCopy`）才能跑；
 * 「要不要自愈」的决策在 [PipBlackHeal] 里测。
 */
class PipFrameProbeTest {

    @Test
    fun `阈值常量符合预期`() {
        assertEquals(3, PipFrameProbe.BLACK_STREAK_LIMIT)
        assertEquals(10, PipFrameProbe.BLACK_LUMA)
        assertEquals(64, PipFrameProbe.SAMPLE_W)
        assertEquals(36, PipFrameProbe.SAMPLE_H)
    }

    // ---------------------------------------------------------------- 第130轮：判黑

    /**
     * 第130轮：判黑补了「整体很暗」这条判据。
     *
     * 承载应用画的是 `#101010` 这种深色底时，最大亮度是 16 ——
     * 在老阈值（< 10）下**判不出来**，但用户看到的就是一块黑。
     */
    @Test
    fun `深色底也判黑_整体很暗`() {
        // #101010 → luma 16：老阈值判不出来
        assertFalse(PipFrameProbe.isBlackFrame(maxLuma = 16, avgLuma = 16))
        // 补上「整体很暗」判据后判得出来
        assertTrue(PipFrameProbe.isBlackFrame(maxLuma = 16, avgLuma = 5))
    }

    @Test
    fun `纯黑当然判黑`() {
        assertTrue(PipFrameProbe.isBlackFrame(maxLuma = 0, avgLuma = 0))
        assertTrue(PipFrameProbe.isBlackFrame(maxLuma = 9, avgLuma = 9))
    }

    @Test
    fun `夜里深色地图不误判`() {
        // 有路网/文字 → 最大亮度很高 → 两条判据都不成立
        assertFalse(PipFrameProbe.isBlackFrame(maxLuma = 180, avgLuma = 30))
        // 平均暗但有一处亮点（路网）→ 也不判黑
        assertFalse(PipFrameProbe.isBlackFrame(maxLuma = 60, avgLuma = 3))
    }

    @Test
    fun `第130轮新增的常量符合预期`() {
        assertEquals(24, PipFrameProbe.DARK_MAX_LUMA)
        assertEquals(8, PipFrameProbe.DARK_AVG_LUMA)
        assertEquals(1_500L, PipFrameProbe.SAMPLE_TIMEOUT_MS)
        assertEquals(30, PipFrameProbe.LOG_EVERY_N_FRAMES)
        assertEquals(48, PipFrameProbe.BRIGHT_LUMA)
        assertEquals(0.02, PipFrameProbe.BRIGHT_RATIO_MIN, 1e-9)
        assertEquals(0.15f, PipFrameProbe.SAMPLE_INSET_RATIO, 1e-6f)
    }

    // ---------------------------------------------------------------- 第130轮：采样矩形

    /**
     * 第130轮：采样矩形必须**往里缩**。
     *
     * 我们自己叠在承载面上的控件（左侧 36×110 的分屏按钮、顶部三个点）是亮的，
     * 不缩进去就会把「黑屏」洗成「有画面」→ 探针永远判不出黑。
     */
    @Test
    fun `采样矩形四边都往里缩`() {
        val r = PipFrameProbe.sampleRectOf(0, 0, 1000, 800, ratio = 0.15f)
        // 左右各缩 150、上下各缩 120
        assertEquals(150, r[0])
        assertEquals(120, r[1])
        assertEquals(850, r[2])
        assertEquals(680, r[3])
    }

    @Test
    fun `采样矩形比例为零时保持原样`() {
        val r = PipFrameProbe.sampleRectOf(16, 60, 1363, 927, ratio = 0f)
        assertEquals(16, r[0])
        assertEquals(60, r[1])
        assertEquals(1363, r[2])
        assertEquals(927, r[3])
    }

    @Test
    fun `采样矩形偏移会跟着走`() {
        val r = PipFrameProbe.sampleRectOf(100, 200, 1100, 1000, ratio = 0.1f)
        assertEquals(200, r[0])
        assertEquals(280, r[1])
        assertEquals(1000, r[2])
        assertEquals(920, r[3])
    }

    // ---------------------------------------------------------------- 第130轮：亮像素占比

    /**
     * 第130轮：判黑加了「亮像素占比」这条判据。
     *
     * 只看最大亮度的话，**一颗**小亮点就能把「黑」洗成「有画面」。
     */
    @Test
    fun `几乎没有亮像素就判黑`() {
        // 有一点亮度（不到纯黑），但几乎没有亮像素 → 判黑
        assertTrue(PipFrameProbe.isBlackFrame(maxLuma = 40, avgLuma = 12, brightRatio = 0.001))
        // 亮像素够多 → 不判黑
        assertFalse(PipFrameProbe.isBlackFrame(maxLuma = 40, avgLuma = 12, brightRatio = 0.30))
    }

    @Test
    fun `亮像素占比的边界`() {
        // 正好等于阈值 → 不判黑（判据是「小于」）
        assertFalse(
            PipFrameProbe.isBlackFrame(
                maxLuma = 200,
                avgLuma = 30,
                brightRatio = PipFrameProbe.BRIGHT_RATIO_MIN,
            ),
        )
        assertTrue(
            PipFrameProbe.isBlackFrame(
                maxLuma = 200,
                avgLuma = 30,
                brightRatio = PipFrameProbe.BRIGHT_RATIO_MIN - 0.001,
            ),
        )
    }

    @Test
    fun `纯黑一定判黑_不依赖占比`() {
        assertTrue(PipFrameProbe.isBlackFrame(maxLuma = 0, avgLuma = 0, brightRatio = 0.0))
        assertTrue(PipFrameProbe.isBlackFrame(maxLuma = 9, avgLuma = 9, brightRatio = 0.0))
    }

    @Test
    fun `正常画面不会被判黑`() {
        // 深色底地图：有大量路网/文字 → 亮像素占比高 → 不判黑
        assertFalse(PipFrameProbe.isBlackFrame(maxLuma = 120, avgLuma = 40, brightRatio = 0.08))
        // 白天地图
        assertFalse(PipFrameProbe.isBlackFrame(maxLuma = 238, avgLuma = 90, brightRatio = 0.45))
    }
}
