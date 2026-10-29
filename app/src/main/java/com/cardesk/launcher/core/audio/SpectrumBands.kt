package com.cardesk.launcher.core.audio

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 频谱解析的**纯计算**部分（第七十轮）。
 *
 * 音频采集那一层（`Visualizer`）在 [com.cardesk.launcher.core.audio.SpectrumAnalyzer]，
 * 但「字节 → 频段 → 颜色」这条链上的数学全在这里 —— 全是纯函数，能单测。
 *
 * ## 第七十九轮：整体对齐参照工程 `uni-launcher`
 *
 * 用户要求「频谱实现方式可以参考 uni-launcher」，
 * 而参照工程是**同一款车机上已经跑通**的实现
 * （`uni-launcher/app/android/app/src/main/java/xyz/i4o/uni/launcher/audio/SpectrumBands.java`）。
 * 这一版把四件事改成了与它一致：
 *
 * | 口径 | 以前（CarDesk） | 现在（= uni-launcher） |
 * | --- | --- | --- |
 * | FFT 字节布局 | `fft[1+2k]` / `fft[2+2k]` | `fft[2k]` / `fft[2k+1]` |
 * | 分段数 | 16 | 24 |
 * | 频段上下界 | 40Hz ~ 16kHz | `min(60, top/2)` ~ `min(14000, hz/2)` |
 * | 归一化 | 逐帧峰值自动增益 | 绝对 dB 标定（`(db+54)/54`） |
 * | 平滑 | 对称 EMA（α=0.35） | 非对称（瞬时升 / 0.70 降） |
 *
 * ## 为什么字节布局必须改（这是**真的取错了频点**）
 *
 * `Visualizer.getFft()` 的字节布局是（AOSP 公开文档口径）：
 *
 * ```
 * fft[0]     = 直流分量（DC）
 * fft[1]     = 奈奎斯特分量（Nyquist）
 * fft[2k]    = 第 k 个频点的实部
 * fft[2k+1]  = 第 k 个频点的虚部
 * ```
 *
 * 以前 CarDesk 取的是 `fft[1+2k]` / `fft[2+2k]` ——
 * 也就是「上一对的**虚部** + 当前对的**实部**」，
 * 每个频段算出来的是**相邻两个频点的混合模长**，频段边界整体偏一个 bin。
 *
 * 参照工程 uni-launcher 的类注释写得很直白：
 * `Android Visualizer FFT is interleaved signed real/imaginary bytes
 * (DC and Nyquist occupy bytes 0/1)` —— 并按 `fft[2k]` / `fft[2k+1]` 取，
 * 而且它在这台车机上**跑得通**。
 *
 * ## 为什么归一化要换成 dB 标定
 *
 * 以前的「逐帧峰值自动增益」会把**每一帧最响的那一段拉到 1** ——
 * 看上去永远是「有一根到顶」，安静时也会被放大，音乐一停也不掉下去。
 *
 * dB 标定是**绝对**的：`db = 10·log10(power / (128²·2))`，`v = (db + 54) / 54`。
 * -54dB 记 0、0dB 记 1 ⇒ 动态范围是真实的（和 uni-launcher 一致）。
 *
 * ## 为什么平滑要换成非对称
 *
 * 以前是对称 EMA（α=0.35）：上升沿只有 35% 权重 ⇒ **灯跟不上鼓点**。
 * 现在是 `v = max(当前值, 上一帧 × 0.70)`：**瞬时上升、缓慢回落**。
 */
object SpectrumBands {

    /** 默认分段数（与 uni-launcher 一致：24）。 */
    const val DEFAULT_BAND_COUNT = 24

    /**
     * 频段下限（Hz）。
     *
     * 取 `min(60, top/2)`（同 uni-launcher）—— 再低基本是底噪。
     */
    const val BOTTOM_HZ = 60f

    /**
     * 频段上限（Hz）。
     *
     * 取 `min(14000, hz/2)`（同 uni-launcher）——
     * 14k 以上耳朵也听不见了，而 FFT 本身也只覆盖到 `hz/4` 左右。
     */
    const val TOP_HZ = 14_000f

    /** 默认采样率（取不到时按 48k 算）。 */
    const val DEFAULT_SAMPLE_RATE = 48_000

    /**
     * dB 标定的**地板**（-54dB）。
     *
     * 低于它算 0（听不见）。
     */
    const val DB_FLOOR = -54f

    /**
     * dB 标定的**量程**（54dB 对应 1.0）。
     */
    const val DB_RANGE = 54f

    /**
     * 满幅功率的参考值（`128² × 2`）。
     *
     * 字节是有符号的（−128..127），一个频点的功率上界是 `127² + 127²`。
     */
    const val FULL_SCALE_POWER = 128f * 128f * 2f

    /**
     * Visualizer 的 FFT 字节 → 各频段的**线性功率**（0..1 附近，未做 dB 标定）。
     *
     * ## 字节布局
     *
     * ```
     * fft[0]     = 直流分量（DC）
     * fft[1]     = 奈奎斯特分量（Nyquist）
     * fft[2k]    = 第 k 个频点的实部（**有符号**字节，−128..127）
     * fft[2k+1]  = 第 k 个频点的虚部
     * ```
     *
     * ## 采样点数与频点
     *
     * `getFft()` 往缓冲区里写 `captureSize / 2` 个字节，
     * 其中只有 `1`（DC）+ `1`（Nyquist）+ `(size−2)/2` 组实虚对是有效数据。
     * 频率轴按 `N = fft.size × 2`（= captureSize）推：
     * 第 k 个频点的频率是 `k × sampleRate / N`。
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
        if (fft.size < 8 || bandCount <= 0) return bands
        val hz = sampleRate.toFloat()
        if (hz <= 0f) return bands
        // FFT 点数 N = 缓冲区字节数 × 2（= captureSize）
        val n = fft.size * 2
        val binHz = hz / n
        // 频段上下界（同 uni-launcher）
        val top = minOf(TOP_HZ, hz / 2f)
        val bottom = minOf(BOTTOM_HZ, top / 2f)
        if (bottom <= 0f || top <= bottom) return bands
        // 有效复频点：缓冲区里除 DC / Nyquist 外还有多少组实虚对
        val maxBin = (fft.size - 2) / 2
        if (maxBin < 1) return bands

        val ratio = top / bottom
        for (band in 0 until bandCount) {
            val lo = bottom * ratio.pow(band.toFloat() / bandCount)
            val hi = bottom * ratio.pow((band + 1).toFloat() / bandCount)
            // 频点区间（k 从 1 起：k=0 是 DC）
            val first = maxOf(1, minOf(maxBin, (lo / binHz).toInt()))
            val last = maxOf(first + 1, minOf(maxBin + 1, ceil(hi / binHz).toInt()))
            var power = 0f
            for (k in first until last) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                val p = re * re + im * im
                if (p > power) power = p
            }
            // 取**段内最大功率**而不是平均值：
            // 一个音（鼓点、镲片）本来就只占一两个频点，取平均会被稀释掉，
            // 看起来像「什么也没响」。
            bands[band] = power / FULL_SCALE_POWER
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
     * FFT 字节 → 整体电平（0..1）。
     *
     * 口径同 uni-launcher 的 `peakLevel`：**标定后频段的最大值**。
     *
     * @param fft `Visualizer.getFft()` 的原始字节
     * @param bandCount 分几段
     * @param sampleRate 采样率（Hz）
     */
    fun levelFromFft(
        fft: ByteArray,
        bandCount: Int = DEFAULT_BAND_COUNT,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): Float {
        val bands = calibrate(fromFft(fft, bandCount, sampleRate))
        return bands.maxOrNull() ?: 0f
    }

    /**
     * 线性功率 → **dB 标定**的 0..1（与 uni-launcher 一致）。
     *
     * `db = 10·log10(power)`，`v = (db + 54) / 54`，夹在 0..1。
     *
     * ## 为什么不用「逐帧峰值自动增益」
     *
     * 自动增益会把每一帧最响的那段无条件拉到 1 ——
     * 看起来永远是「有一根到顶」，音乐停了也不掉。
     * dB 标定是**绝对**的，动态范围才是真的。
     */
    fun calibrate(bands: FloatArray): FloatArray {
        val out = FloatArray(bands.size)
        for (i in bands.indices) {
            val p = bands[i]
            if (p <= 0f) {
                out[i] = 0f
                continue
            }
            val db = 10f * log10(p)
            out[i] = ((db - DB_FLOOR) / DB_RANGE).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * 非对称平滑（**瞬时上升 / 缓慢回落**）。
     *
     * `out[i] = max(当前值[i], 上一帧[i] × decay)`。
     *
     * ## 为什么不用对称 EMA
     *
     * 对称平均（`prev×(1−α) + cur×α`）在上升沿只有 α 的权重 ——
     * 鼓点来了灯「慢半拍」，看着就不跟手。
     * 非对称：**有就立刻到，没有就慢慢落**。
     *
     * @param decay 回落系数（0.70 = 上一帧的 70%）
     */
    fun smooth(previous: FloatArray, current: FloatArray, decay: Float = 0.70f): FloatArray {
        val n = maxOf(previous.size, current.size)
        val out = FloatArray(n)
        val d = decay.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val p = previous.getOrElse(i) { 0f }
            val c = current.getOrElse(i) { 0f }
            out[i] = maxOf(c, p * d)
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
        decay: Float = 0.70f,
    ): FloatArray {
        val raw = fromFft(fft, bandCount, sampleRate)
        val calibrated = calibrate(raw)
        return smooth(previous, calibrated, decay)
    }
}
