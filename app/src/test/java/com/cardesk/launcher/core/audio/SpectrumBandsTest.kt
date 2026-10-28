package com.cardesk.launcher.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [SpectrumBands] 单测（纯逻辑，不依赖 Android）。
 *
 * 这条规则决定「频谱画出来对不对」：
 * - FFT 字节 → 频段能量的映射（频率对不对得上）；
 * - 归一化（安静时不能糊成一片）；
 * - 平滑（灯不能一帧一跳）。
 */
class SpectrumBandsTest {

    /** 造一个「某个频率上有单频正弦」的 FFT 字节数组。 */
    private fun toneFft(freqHz: Double, sampleRate: Int = 48_000, fftSize: Int = 256): ByteArray {
        val n = fftSize * 2
        val binHz = sampleRate.toDouble() / n
        val bin = (freqHz / binHz).roundToInt().coerceIn(1, fftSize / 2 - 1)
        val fft = ByteArray(fftSize)
        fft[1 + 2 * bin] = 127
        return fft
    }

    @Test
    fun `单频信号落在对应的频段`() {
        // 1000Hz → 落在 800~1164Hz 那一段（16 段对数切分下的第 8 段）
        val bands = SpectrumBands.fromFft(toneFft(1000.0), 16, 48_000)
        assertEquals(16, bands.size)
        val peak = bands.indices.maxByOrNull { bands[it] } ?: -1
        assertEquals(8, peak)
        assertTrue("峰值段应该有能量", bands[peak] > 0.5f)
    }

    @Test
    fun `低频与高频落在不同段`() {
        val low = SpectrumBands.fromFft(toneFft(80.0), 16, 48_000)
        val high = SpectrumBands.fromFft(toneFft(8000.0), 16, 48_000)
        val lowPeak = low.indices.maxByOrNull { low[it] } ?: -1
        val highPeak = high.indices.maxByOrNull { high[it] } ?: -1
        assertTrue("80Hz 应该在低频段（实际 $lowPeak）", lowPeak in 0..4)
        assertTrue("8kHz 应该在高频段（实际 $highPeak）", highPeak >= 12)
    }

    @Test
    fun `静音时没有能量`() {
        val bands = SpectrumBands.fromFft(ByteArray(256), 16, 48_000)
        bands.forEach { assertEquals(0f, it, 0.0001f) }
    }

    @Test
    fun `空数据不崩`() {
        // 没有数据也返回同样段数的全 0（调用方按固定段数画图）
        assertEquals(16, SpectrumBands.fromFft(ByteArray(0), 16, 48_000).size)
        assertTrue(SpectrumBands.fromFft(ByteArray(0), 16, 48_000).all { it == 0f })
        // 数据太短 → 全 0，但不能崩
        assertTrue(SpectrumBands.fromFft(ByteArray(2), 16, 48_000).all { it == 0f })
        assertTrue(SpectrumBands.fromFft(ByteArray(256), 0, 48_000).isEmpty())
    }

    @Test
    fun `波形电平按 RMS 算`() {
        // 满幅正弦的近似：全部 127
        val loud = SpectrumBands.levelFromWaveform(ByteArray(64) { 127 })
        assertTrue("满幅应该接近 1（实际 $loud）", loud > 0.9f)
        // 一半幅度
        val half = SpectrumBands.levelFromWaveform(ByteArray(64) { 64 })
        assertTrue(half in 0.4f..0.6f)
        // 静音
        assertEquals(0f, SpectrumBands.levelFromWaveform(ByteArray(64)), 0.0001f)
        assertEquals(0f, SpectrumBands.levelFromWaveform(ByteArray(0)), 0.0001f)
    }

    @Test
    fun `归一化把峰值拉到 1`() {
        val bands = floatArrayOf(0.1f, 0.2f, 0.5f, 0.05f)
        val out = SpectrumBands.normalize(bands)
        assertEquals(1f, out.max(), 0.001f)
        assertTrue("比峰值小的段应该也按比例放大", out[1] > out[0])
        assertTrue("归一后不能超过 1", out.all { it in 0f..1f })
    }

    @Test
    fun `低于地板的值归零`() {
        val out = SpectrumBands.normalize(floatArrayOf(0.001f, 0.002f, 0.003f))
        assertTrue("全是噪声时应该整条为 0", out.all { it == 0f })
    }

    @Test
    fun `平滑是指数平均`() {
        val prev = floatArrayOf(1f, 0f)
        val cur = floatArrayOf(0f, 1f)
        val out = SpectrumBands.smooth(prev, cur, 0.25f)
        assertEquals(0.75f, out[0], 0.0001f)
        assertEquals(0.25f, out[1], 0.0001f)
    }

    @Test
    fun `平滑系数被夹在 0 到 1 之间`() {
        val out = SpectrumBands.smooth(floatArrayOf(1f), floatArrayOf(0f), 5f)
        assertEquals(0f, out[0], 0.0001f)
        val out2 = SpectrumBands.smooth(floatArrayOf(1f), floatArrayOf(0f), -1f)
        assertEquals(1f, out2[0], 0.0001f)
    }

    @Test
    fun `整帧处理`() {
        val fft = toneFft(1000.0)
        val first = SpectrumBands.process(fft, FloatArray(0), 16, 48_000, 0.5f)
        assertEquals(16, first.size)
        assertTrue(first.any { it > 0.2f })
        // 第二帧应该更接近当前值（平滑）
        val second = SpectrumBands.process(fft, first, 16, 48_000, 0.5f)
        assertTrue("同一帧连续处理应该收敛", second[9] >= first[9])
    }
}
