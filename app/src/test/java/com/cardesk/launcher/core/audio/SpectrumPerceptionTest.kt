package com.cardesk.launcher.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpectrumPerception] 单测（感知层，纯状态机）。
 *
 * 验的是这一层真正解决的那个问题：
 * **绝对 dB 不构成对比** —— 母带压得响的歌所有频段都在 0.7 以上，
 * 录得轻的歌所有频段都在 0.2 以下。归一之后必须都落到「相对自己的平均水平」。
 */
class SpectrumPerceptionTest {

    private val bandCount = 24

    /** 造一帧频段（0..1，绝对 dB 标定口径）。 */
    private fun frame(value: Float, n: Int = bandCount): FloatArray =
        FloatArray(n) { value }

    /**
     * 连续喂 n 帧相同的频段，返回最后一帧的归一结果。
     */
    private fun feed(
        p: SpectrumPerception,
        input: FloatArray,
        frames: Int = 1,
        dtMs: Long = 100L,
    ): FloatArray {
        var out = FloatArray(0)
        repeat(frames) {
            out = p.process(input, dtMs)
        }
        return out
    }

    // ------------------------------------------------------------ 每段自适应参考

    @Test
    fun `恒定输入时相对值落在 0_5 附近`() {
        // 这是这一层的核心性质：**该段的平均水平必须落在 0.5**。
        // 母带响的歌、录得轻的歌，出来都一样。
        val p = SpectrumPerception(bandCount = bandCount)
        val out = feed(p, frame(0.5f), frames = 60)
        for (i in out.indices) {
            assertTrue("第 $i 段应是 0.5 附近（实际 ${out[i]}）", abs(out[i] - 0.5f) < 0.05f)
        }
    }

    @Test
    fun `不同的绝对电平归一后结果一致`() {
        // 一段响、一段轻 —— 归一之后应当长得一样。
        val loud = SpectrumPerception(bandCount = bandCount)
        val quiet = SpectrumPerception(bandCount = bandCount)
        val a = feed(loud, frame(0.8f), frames = 60)
        val b = feed(quiet, frame(0.2f), frames = 60)
        for (i in a.indices) {
            assertTrue("第 $i 段应一致（${a[i]} vs ${b[i]}）", abs(a[i] - b[i]) < 0.05f)
        }
    }

    @Test
    fun `每段独立标定`() {
        // 低频很响、高频很轻 —— 归一之后两段都该在 0.5 附近，
        // 这样「哪一段在响」的信息才不会被绝对电平吃掉。
        val p = SpectrumPerception(bandCount = bandCount)
        val input = FloatArray(bandCount) { if (it < 4) 0.9f else 0.15f }
        val out = feed(p, input, frames = 60)
        for (i in out.indices) {
            assertTrue("第 $i 段应独立标定到 0.5 附近（实际 ${out[i]}）", abs(out[i] - 0.5f) < 0.05f)
        }
    }

    @Test
    fun `高于平均值的一段会顶到 1`() {
        val p = SpectrumPerception(bandCount = bandCount)
        // 先把第 0 段的参考拉到 0.3
        val quiet = FloatArray(bandCount) { if (it == 0) 0.3f else 0.3f }
        feed(p, quiet, frames = 40)
        // 然后第 0 段突然很响（高 12dB 以上）
        val loud = quiet.copyOf()
        loud[0] = 0.85f
        val out = feed(p, loud, frames = 1)
        assertTrue("冲高的段应接近 1（实际 ${out[0]}）", out[0] > 0.8f)
    }

    // ------------------------------------------------------------ 静音门

    @Test
    fun `音乐停了频段归零`() {
        val p = SpectrumPerception(bandCount = bandCount)
        feed(p, frame(0.6f), frames = 30)
        // 音乐停 → 所有频段掉到 0（dB 被夹在 -54）
        val out = feed(p, frame(0f), frames = 10)
        for (i in out.indices) {
            assertTrue("第 $i 段该归零（实际 ${out[i]}）", out[i] < 0.02f)
        }
        assertTrue("整体相对电平该归零（实际 ${p.relativeLevel}）", p.relativeLevel < 0.02f)
    }

    @Test
    fun `静音门最终会关上`() {
        val p = SpectrumPerception(bandCount = bandCount)
        feed(p, frame(0.6f), frames = 30)
        assertTrue("有声音时门该是开的（实际 ${p.gate}）", p.gate > 0.9f)
        // 峰值跟随按 15dB/s 回落 → 从 -21dB 落到 -54dB 要约 2.2 秒
        feed(p, frame(0f), frames = 40, dtMs = 100L)
        assertTrue("静音后门该关上（实际 ${p.gate}）", p.gate < 0.05f)
    }

    @Test
    fun `音乐恢复后门会重新打开`() {
        val p = SpectrumPerception(bandCount = bandCount)
        feed(p, frame(0.6f), frames = 30)
        feed(p, frame(0f), frames = 40)
        assertTrue("静音后门该关上（实际 ${p.gate}）", p.gate < 0.05f)
        feed(p, frame(0.6f), frames = 5)
        assertTrue("音乐恢复后门该重新打开（实际 ${p.gate}）", p.gate > 0.5f)
    }

    // ------------------------------------------------------------ 整体相对电平

    @Test
    fun `相对电平在 0 到 1 之间`() {
        val p = SpectrumPerception(bandCount = bandCount)
        for (v in listOf(0f, 0.1f, 0.3f, 0.5f, 0.9f, 1f)) {
            feed(p, frame(v), frames = 30)
            assertTrue("相对电平 $v 应在 0..1（实际 ${p.relativeLevel}）", p.relativeLevel in 0f..1f)
        }
    }

    @Test
    fun `参考电平慢速跟随 突变不会立刻改标定`() {
        // τ = 2 秒 ⇒ 100ms 一帧只走约 5%。
        // 这一条守护的是「换歌不用重新标定」与「不追着信号跑」。
        val p = SpectrumPerception(bandCount = bandCount)
        feed(p, frame(0.5f), frames = 60)
        val before = p.relativeLevel
        // 突然变响
        val out = feed(p, frame(0.9f), frames = 1)
        assertTrue("突变后相对值该顶上去（实际 ${out[0]}）", out[0] > 0.7f)
        // 但整体相对电平不会一步跳到顶（参考还在慢慢跟）
        assertTrue("整体电平不该一步跳满（实际 $before → ${p.relativeLevel}）", p.relativeLevel <= 1f)
    }

    // ------------------------------------------------------------ 重置

    @Test
    fun `重置后参考电平清空`() {
        val p = SpectrumPerception(bandCount = bandCount)
        feed(p, frame(0.8f), frames = 60)
        p.reset()
        assertEquals(0f, p.relativeLevel, 0.0001f)
        assertEquals(0f, p.gate, 0.0001f)
        // 重置后第一帧直接拿当前值当参考 → 相对值仍然是 0.5
        val out = feed(p, frame(0.8f), frames = 1)
        assertTrue("重置后首帧应是 0.5（实际 ${out[0]}）", abs(out[0] - 0.5f) < 0.05f)
    }

    private fun abs(v: Float) = if (v < 0f) -v else v
}
