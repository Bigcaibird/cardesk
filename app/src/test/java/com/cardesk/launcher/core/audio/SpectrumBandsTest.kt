package com.cardesk.launcher.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [SpectrumBands] 单测（纯逻辑，不依赖 Android）。
 *
 * 这条规则决定「频谱画出来对不对」：
 * - FFT 字节 → 频段能量的映射（**字节布局对不对**、频率对不对得上）；
 * - dB 标定（安静时不能糊成一片、响的时候也不能永远到顶）；
 * - 非对称平滑（灯要跟得上鼓点，但不能一帧一跳）。
 *
 * 第七十九轮整体对齐参照工程 `uni-launcher`
 * （`uni-launcher/.../audio/SpectrumBands.java` + `SpectrumBridge.java`）。
 */
class SpectrumBandsTest {

    /**
     * 造一个「某个频率上有单频正弦」的 FFT 字节数组。
     *
     * ## 字节布局（第七十九轮改过）
     *
     * ```
     * fft[0] = DC，fft[1] = Nyquist
     * fft[2k] = 第 k 个频点的实部，fft[2k+1] = 虚部
     * ```
     *
     * 以前我们把实部写在 `fft[1 + 2k]` —— 那是**错一位**的布局，
     * 会把「上一对的虚部 + 当前对的实部」当成一对 ⇒ 每个频段取到的都是混频。
     */
    private fun toneFft(freqHz: Double, sampleRate: Int = 48_000, fftSize: Int = 256): ByteArray {
        val n = fftSize * 2
        val binHz = sampleRate.toDouble() / n
        val bin = (freqHz / binHz).roundToInt().coerceIn(1, (fftSize - 2) / 2)
        val fft = ByteArray(fftSize)
        fft[2 * bin] = 127
        return fft
    }

    @Test
    fun `单频信号落在对应的频段`() {
        // 1000Hz → 24 段对数切分（60Hz~14kHz）下的第 12 段
        val bands = SpectrumBands.fromFft(toneFft(1000.0), 24, 48_000)
        assertEquals(24, bands.size)
        val peak = bands.indices.maxByOrNull { bands[it] } ?: -1
        assertEquals(12, peak)
        assertTrue("峰值段应该有能量", bands[peak] > 0.4f)
    }

    @Test
    fun `低频与高频落在不同段`() {
        val low = SpectrumBands.fromFft(toneFft(80.0), 24, 48_000)
        val high = SpectrumBands.fromFft(toneFft(8000.0), 24, 48_000)
        val lowPeak = low.indices.maxByOrNull { low[it] } ?: -1
        val highPeak = high.indices.maxByOrNull { high[it] } ?: -1
        assertTrue("80Hz 应该在低频段（实际 $lowPeak）", lowPeak in 0..2)
        assertTrue("8kHz 应该在高频段（实际 $highPeak）", highPeak >= 18)
    }

    @Test
    fun `实部虚部按 AOSP 布局读取`() {
        // 把能量写在「上一对的虚部」那个位置上（错位布局）——
        // 正确实现**不应该**把它当成第 k 个频点的实部。
        val fft = ByteArray(256)
        fft[1 + 2 * 20] = 127
        val bands = SpectrumBands.fromFft(fft, 24, 48_000)
        // 错位的那一位在正确实现里属于「第 19 个频点的虚部」，
        // 只会有一点点能量漏到相邻段，绝不会整段到顶。
        assertTrue("错位字节不该造成整段满能量", bands.max() < 0.5f)
    }

    @Test
    fun `静音时没有能量`() {
        val bands = SpectrumBands.fromFft(ByteArray(256), 24, 48_000)
        bands.forEach { assertEquals(0f, it, 0.0001f) }
    }

    @Test
    fun `空数据不崩`() {
        // 没有数据也返回同样段数的全 0（调用方按固定段数画图）
        assertEquals(24, SpectrumBands.fromFft(ByteArray(0), 24, 48_000).size)
        assertTrue(SpectrumBands.fromFft(ByteArray(0), 24, 48_000).all { it == 0f })
        // 数据太短 → 全 0，但不能崩
        assertTrue(SpectrumBands.fromFft(ByteArray(2), 24, 48_000).all { it == 0f })
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
    fun `FFT 电平取最强谱线`() {
        // 输出混音这条路是轮询 getFft()（不挂回调）→ 拿不到波形，电平从 FFT 推
        val loud = SpectrumBands.levelFromFft(toneFft(1000.0))
        assertTrue("有单频信号时电平应该明显大于 0（实际 $loud）", loud > 0.5f)
        assertTrue("电平不能超过 1", loud <= 1f)
        // 静音
        assertEquals(0f, SpectrumBands.levelFromFft(ByteArray(256)), 0.0001f)
        // 空 / 太短
        assertEquals(0f, SpectrumBands.levelFromFft(ByteArray(0)), 0.0001f)
        assertEquals(0f, SpectrumBands.levelFromFft(ByteArray(2)), 0.0001f)
    }

    @Test
    fun `dB 标定是绝对的_不会把每帧峰值都拉到 1`() {
        // 满功率 → 0dB → 1
        assertEquals(1f, SpectrumBands.calibrate(floatArrayOf(1f))[0], 0.001f)
        // 半功率 → 约 -3dB → 明显小于 1（**不**做逐帧自动增益）
        val half = SpectrumBands.calibrate(floatArrayOf(0.5f))[0]
        assertTrue("半功率不该被拉到 1（实际 $half）", half in 0.9f..0.98f)
        // 相对安静的信号明显更小
        val quiet = SpectrumBands.calibrate(floatArrayOf(0.001f))[0]
        assertTrue("安静的信号应该更小（实际 $quiet）", quiet < half)
    }

    @Test
    fun `低于地板的值归零`() {
        val out = SpectrumBands.calibrate(floatArrayOf(1e-7f, 1e-8f))
        assertTrue("远低于地板时应该整条为 0", out.all { it == 0f })
        assertEquals(0f, SpectrumBands.calibrate(floatArrayOf(0f))[0], 0.0001f)
    }

    @Test
    fun `平滑是非对称的_瞬时上升缓慢回落`() {
        // 上一帧 [1, 0]，当前帧 [0, 1]
        // → 第 0 段：当前 0，但上一帧有 1 → 落回 0.7（不是对称平均的 0.75）
        // → 第 1 段：当前 1 更大 → 立刻到 1（不是对称平均的 0.25）
        val prev = floatArrayOf(1f, 0f)
        val cur = floatArrayOf(0f, 1f)
        val out = SpectrumBands.smooth(prev, cur, 0.70f)
        assertEquals(0.70f, out[0], 0.0001f)
        assertEquals(1f, out[1], 0.0001f)
    }

    @Test
    fun `平滑系数被夹在 0 到 1 之间`() {
        val out = SpectrumBands.smooth(floatArrayOf(1f), floatArrayOf(0f), 5f)
        assertEquals(1f, out[0], 0.0001f)
        val out2 = SpectrumBands.smooth(floatArrayOf(1f), floatArrayOf(0f), -1f)
        assertEquals(0f, out2[0], 0.0001f)
    }

    @Test
    fun `整帧处理`() {
        val fft = toneFft(1000.0)
        val first = SpectrumBands.process(fft, FloatArray(0), 24, 48_000, 0.70f)
        assertEquals(24, first.size)
        assertTrue(first.any { it > 0.2f })
        // 第二帧应该更接近当前值（非对称平滑：当前值大就立刻跟上去）
        val second = SpectrumBands.process(fft, first, 24, 48_000, 0.70f)
        assertTrue("同一帧连续处理应该收敛", second[12] >= first[12])
    }
}
