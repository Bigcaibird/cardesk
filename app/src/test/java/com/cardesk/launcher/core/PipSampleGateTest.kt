package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画采样 alpha 闸门（[PipSampleGate]）单测（第224轮，纯逻辑）。
 *
 * 背景（用户第224轮报的现象）：**导入视频壁纸后，画中画的高德跑回首页**。
 *
 * 根因之一是「看不见」被当成了「黑」：
 * `ActivityView` 内部是 `SurfaceView`（独立 SurfaceFlinger 图层），
 * 窗口缓冲在承载面位置是**透明的洞**（alpha=0、RGB=0）。
 * 视频壁纸把窗口切成 `TRANSLUCENT` 后，采样恒为 0 → 连续 3 帧判黑
 * → 重建承载面 → 重启高德。
 *
 * 这一组用例把「**看不见 ≠ 黑**」这条契约逐条钉死：
 * 1. 全透明 ⇒ BLIND，**不累加**连续黑帧（且清零），永不触发自愈；
 * 2. 不透明且很暗 ⇒ BLACK，连续 3 帧累积到阈值；
 * 3. 不透明且有内容 ⇒ CONTENT，清零；
 * 4. 「黑 → 看不见 → 黑」的连续黑帧**不成立**（中间被打断）。
 */
class PipSampleGateTest {

    // ---------------------------------------------------------------- 常量口径

    @Test
    fun `不透明 alpha 阈值是 128`() {
        assertEquals(
            "阈值变了就会改变「半透明控件算不算画面」的判定，必须显式确认",
            128,
            PipSampleGate.OPAQUE_ALPHA_MIN,
        )
        assertTrue("127 半透明，不算不透明", !PipSampleGate.isOpaque(127))
        assertTrue("128 正好一半，算不透明", PipSampleGate.isOpaque(128))
        assertTrue("255 完全不透明", PipSampleGate.isOpaque(255))
        assertFalse("0 完全透明", PipSampleGate.isOpaque(0))
    }

    @Test
    fun `不透明占比阈值是百分之二`() {
        assertEquals(
            "64×36 = 2304 像素 → 约 46 个；透明洞整片 alpha=0，远低于这条线",
            0.02,
            PipSampleGate.OPAQUE_RATIO_MIN,
            1e-9,
        )
    }

    // ---------------------------------------------------------------- 占比计算

    @Test
    fun `全透明像素数组的占比是 0`() {
        val pixels = IntArray(64 * 36) { 0x00000000 }
        assertEquals(0.0, PipSampleGate.opaqueRatio(pixels), 1e-9)
    }

    @Test
    fun `全不透明像素数组的占比是 1`() {
        val pixels = IntArray(64 * 36) { 0xFF000000.toInt() }
        assertEquals(1.0, PipSampleGate.opaqueRatio(pixels), 1e-9)
    }

    @Test
    fun `空数组不会崩且占比为 0`() {
        assertEquals(0.0, PipSampleGate.opaqueRatio(IntArray(0)), 1e-9)
    }

    @Test
    fun `刚好一百个不透明像素的占比是一半`() {
        val pixels = IntArray(200) { i ->
            if (i < 100) 0xFF123456.toInt() else 0x00123456
        }
        assertEquals(0.5, PipSampleGate.opaqueRatio(pixels), 1e-9)
    }

    // ---------------------------------------------------------------- 帧判定

    @Test
    fun `全透明一律判采样不可用 即使像素判黑成立`() {
        // 第224轮的核心：判黑只看 RGB 时这里是 black=true（RGB 全 0）；
        // 闸门必须把它拦成 BLIND，否则就会重建承载面、重启高德。
        assertEquals(
            "透明洞与纯黑在 RGB 上同数据 —— 但看不见不等于黑",
            PipSampleGate.Frame.BLIND,
            PipSampleGate.classify(opaqueRatio = 0.0, blackByPixels = true),
        )
    }

    @Test
    fun `不透明且很暗判黑`() {
        assertEquals(
            "真正能看见画面、且画面确实黑 → 才允许判黑",
            PipSampleGate.Frame.BLACK,
            PipSampleGate.classify(opaqueRatio = 1.0, blackByPixels = true),
        )
    }

    @Test
    fun `不透明且有内容判有画面`() {
        assertEquals(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.classify(opaqueRatio = 1.0, blackByPixels = false),
        )
    }

    @Test
    fun `占比刚好压在阈值上按可见处理`() {
        // classify 用的是 `< OPAQUE_RATIO_MIN`，等于阈值算可见（保守：别轻易判盲）。
        assertEquals(
            PipSampleGate.Frame.BLACK,
            PipSampleGate.classify(
                opaqueRatio = PipSampleGate.OPAQUE_RATIO_MIN,
                blackByPixels = true,
            ),
        )
    }

    @Test
    fun `占比略低于阈值算不可用`() {
        assertEquals(
            PipSampleGate.Frame.BLIND,
            PipSampleGate.classify(
                opaqueRatio = PipSampleGate.OPAQUE_RATIO_MIN - 1e-6,
                blackByPixels = true,
            ),
        )
    }

    // ---------------------------------------------------------------- 连续黑帧

    @Test
    fun `连续黑帧只在判黑时累加`() {
        assertEquals(1, PipSampleGate.nextBlackStreak(0, PipSampleGate.Frame.BLACK))
        assertEquals(2, PipSampleGate.nextBlackStreak(1, PipSampleGate.Frame.BLACK))
        assertEquals(3, PipSampleGate.nextBlackStreak(2, PipSampleGate.Frame.BLACK))
    }

    @Test
    fun `采样不可用会清零连续黑帧`() {
        assertEquals(
            "「看不见」必须打断连续黑 —— 中间夹一帧看不见就不该继续往 3 帧上累",
            0,
            PipSampleGate.nextBlackStreak(2, PipSampleGate.Frame.BLIND),
        )
    }

    @Test
    fun `有画面会清零连续黑帧`() {
        assertEquals(0, PipSampleGate.nextBlackStreak(2, PipSampleGate.Frame.CONTENT))
    }

    @Test
    fun `黑 盲 黑 的序列永远到不了触发阈值`() {
        // 视频壁纸下的真实序列：黑帧与「看不见」交替出现。
        var streak = 0
        repeat(10) {
            streak = PipSampleGate.nextBlackStreak(streak, PipSampleGate.Frame.BLACK)
            streak = PipSampleGate.nextBlackStreak(streak, PipSampleGate.Frame.BLIND)
        }
        assertEquals("交替出现时连续黑帧恒为 0，永不触发自愈", 0, streak)
    }

    // ---------------------------------------------------------------- 见过画面

    @Test
    fun `只有有内容才算见过画面`() {
        assertTrue(PipSampleGate.nextSawContent(false, PipSampleGate.Frame.CONTENT))
        assertFalse(
            "黑帧不算见过画面",
            PipSampleGate.nextSawContent(false, PipSampleGate.Frame.BLACK),
        )
        assertFalse(
            "看不见也不算见过画面（不能把它当证据）",
            PipSampleGate.nextSawContent(false, PipSampleGate.Frame.BLIND),
        )
    }

    @Test
    fun `见过画面之后是粘性的`() {
        assertTrue(PipSampleGate.nextSawContent(true, PipSampleGate.Frame.BLACK))
        assertTrue(PipSampleGate.nextSawContent(true, PipSampleGate.Frame.BLIND))
    }
}
