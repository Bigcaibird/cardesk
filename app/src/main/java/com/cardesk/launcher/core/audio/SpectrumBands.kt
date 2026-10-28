package com.cardesk.launcher.core.audio

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 频谱解析的**纯计算**部分（第七十轮）。
 *
 * 音频采集那一层（`Visualizer`）在 [com.cardesk.launcher.core.audio.SpectrumAnalyzer]，
 * 但「字节 → 频段 → 颜色」这条链上的数学全在这里 —— 全是纯函数，能单测。
 *
 * ## Visualizer 的 FFT 字节格式
 *
 * `Visualizer.getFft()` 给的字节数组是：
 *
 * ```
 * [DC, re₀, im₀, re₁, im₁, …]
 * ```
 *
 * - `fft[0]` = 直流分量；
 * - `fft[1 + 2k]` / `fft[2 + 2k]` = 第 k 个频点的实部 / 虚部（**有符号**字节，−128..127）。
 *
 * 采样点数 N = `fft.size * 2`（captureSize），有效复频点 `N / 4 = fft.size / 2` 个，
 * 第 k 个频点的频率是 `k × sampleRate / N` ——
 * 也就是说这份 FFT 覆盖到 `sampleRate / 4`（48k 采样时约 12kHz）。
 *
 * ## 分段方式
 *
 * 线性分 16 段的话，低频会挤成 1 段、高频几乎全空 —— 音乐的可视化几乎都按**对数**分段。
 * 这里用 `f(i) = LOW × (HIGH / LOW)^(i / bandCount)` 切边（LOW=40Hz，HIGH=16kHz），
 * 每段取该区间内频点能量的**最大值**（取平均会把单个音稀释掉）。
 */
object SpectrumBands {

    /** 默认分段数。 */
    const val DEFAULT_BAND_COUNT = 16

    /** 分段下限（Hz）—— 再低基本是底噪。 */
    const val LOW_HZ = 40f

    /** 分段上限（Hz）—— 超出这个耳朵也听不见了。 */
    const val HIGH_HZ = 16_000f

    /** 默认采样率（取不到时按 48k 算）。 */
    const val DEFAULT_SAMPLE_RATE = 48_000

    /**
     * Visualizer 的 FFT 字节 → 各频段能量（0..1，未归一化到峰值）。
     *
     * @param fft `Visualizer.getFft()` 的原始字节
     * @param bandCount 分几段
     * @param sampleRate 采样率（Hz）
     */
    fun fromFft(
        fft: ByteArray,
        bandCount: Int = DEFAULT_BAND_COUNT,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): FloatArray {
        val bands = FloatArray(bandCount.coerceAtLeast(0))
        if (fft.size < 4 || bandCount <= 0) return bands
        // 复频点数：fft.size 字节 = 1 个 DC + (fft.size-1)/2 个实虚对
        // （最后一对的虚部可能不完整，所以用 (size-1)/2 而不是 size/2）
        val complexBins = (fft.size - 1) / 2
        if (complexBins <= 0) return bands
        // 采样点数 N = fft.size * 2 → 第 k 个频点的频率
        val n = fft.size * 2
        val binHz = sampleRate.toFloat() / n
        // 这份 FFT 能覆盖到的最高频率
        val maxHz = complexBins * binHz

        // —— 每段的下界频率
        val edges = FloatArray(bandCount + 1)
        for (i in 0..bandCount) {
            val ratio = i.toFloat() / bandCount
            edges[i] = (LOW_HZ * (HIGH_HZ / LOW_HZ).pow(ratio)).coerceAtMost(maxHz)
        }

        for (band in 0 until bandCount) {
            val lo = edges[band]
            val hi = edges[band + 1]
            if (hi <= lo) continue
            var peak = 0f
            var k = 1
            while (k < complexBins && 2 + 2 * k < fft.size) {
                val hz = k * binHz
                if (hz >= lo && hz < hi) {
                    val re = fft[1 + 2 * k].toFloat()
                    val im = fft[2 + 2 * k].toFloat()
                    val mag = sqrt(re * re + im * im)
                    if (mag > peak) peak = mag
                } else if (hz >= hi) {
                    break
                }
                k++
            }
            // 取**段内最大值**而不是平均值：
            // 一个音（鼓点、镲片）本来就只占一两个频点，取平均会被稀释掉，
            // 看起来像「什么也没响」。
            bands[band] = peak / 128f
        }
        return bands
    }

    /**
     * 波形数据 → 整体电平（0..1）。
     *
     * 用 RMS（不是峰值）：峰值容易被一下的敲击拉满，RMS 更接近「听感上的响度」。
     */
    fun levelFromWaveform(wave: ByteArray): Float {
        if (wave.isEmpty()) return 0f
        var sum = 0f
        for (b in wave) {
            val v = b.toFloat()
            sum += v * v
        }
        return (sqrt(sum / wave.size) / 128f).coerceIn(0f, 1f)
    }

    /**
     * 频段能量归一化（按本帧峰值做自动增益）。
     *
     * 不归一的话，音乐一小声整条频谱就贴地了 —— 什么都看不出来。
     *
     * @param floor 噪声地板：低于它的算 0
     * @param ceiling 归一化时忽略的上限（防止某一个尖峰把别的段全压扁）
     */
    fun normalize(bands: FloatArray, floor: Float = 0.02f, ceiling: Float = 0.5f): FloatArray {
        val out = FloatArray(bands.size)
        var peak = 0f
        for (b in bands) if (b > peak) peak = b
        if (peak <= floor) return out
        val scale = 1f / (peak - floor).coerceAtLeast(0.0001f)
        for (i in bands.indices) {
            out[i] = ((bands[i] - floor) * scale).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * 指数平滑（`out = prev × (1−α) + cur × α`）。
     *
     * α 越大越跟手、越小越稳。频谱帧是 20~30ms 一帧，不平滑的话灯会闪。
     */
    fun smooth(previous: FloatArray, current: FloatArray, alpha: Float): FloatArray {
        val n = maxOf(previous.size, current.size)
        val out = FloatArray(n)
        val a = alpha.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val p = previous.getOrElse(i) { 0f }
            val c = current.getOrElse(i) { 0f }
            out[i] = p * (1f - a) + c * a
        }
        return out
    }

    /**
     * 一帧完整处理：FFT 字节 → 平滑后的 0..1 频段。
     *
     * @param previous 上一帧的频段（用于平滑；首次传空数组）
     */
    fun process(
        fft: ByteArray,
        previous: FloatArray,
        bandCount: Int = DEFAULT_BAND_COUNT,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        alpha: Float = 0.35f,
    ): FloatArray {
        val raw = fromFft(fft, bandCount, sampleRate)
        val norm = normalize(raw)
        return smooth(previous, norm, alpha)
    }
}
