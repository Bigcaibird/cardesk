package com.cardesk.launcher.core.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ΔE00 限速的**预算分摊**单测。
 *
 * 验的是这轮修掉的那个**发散**：
 *
 * 两层限速（渲染层 / 写入层）如果用**同一个**预算，
 * 候选色跑得比写入快一倍 → 写入层每次都往回拉 →
 * 写下去的颜色与真实颜色的差**每帧累积** →
 * 几秒之后车上的颜色和音乐已经完全不是一回事了。
 *
 * 修法是：**渲染帧的预算按写入周期分摊**，
 * 写入层于是退化成安全网（正常情况下根本不触发）。
 */
class AmbientLightControllerDeltaETest {

    @Test
    fun `渲染帧预算按写入周期分摊`() {
        val cap = AmbientLightController.perFrameDeltaECap(1.8f)
        val expected = 1.8f *
            AmbientLightController.RENDER_INTERVAL_MS.toFloat() /
            AmbientLightController.WRITE_INTERVAL_MS.toFloat()
        assertEquals(expected, cap, 0.0001f)
    }

    @Test
    fun `一个写入周期内的总变化不超过上限`() {
        val maxDe = 1.8f
        val cap = AmbientLightController.perFrameDeltaECap(maxDe)
        val frames = AmbientLightController.WRITE_INTERVAL_MS /
            AmbientLightController.RENDER_INTERVAL_MS
        assertTrue(
            "一个写入周期（$frames 帧）走 $frames × $cap，超过了上限 $maxDe",
            frames * cap <= maxDe,
        )
    }

    @Test
    fun `上限为零时限速关闭`() {
        assertEquals(0f, AmbientLightController.perFrameDeltaECap(0f), 0.0001f)
        assertEquals(0f, AmbientLightController.perFrameDeltaECap(-1f), 0.0001f)
    }

    @Test
    fun `分摊后的预算一定比完整预算小`() {
        // 完整预算用在渲染层上 ⇒ 渲染跑得比写入快 ⇒ 发散。
        // 分摊之后预算必须严格小于完整预算。
        val full = AmbientLightController.DEFAULT_MAX_DELTA_E
        val cap = AmbientLightController.perFrameDeltaECap(full)
        assertTrue("分摊后的预算 $cap 该小于完整预算 $full", cap < full)
    }
}
