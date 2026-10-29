package com.cardesk.launcher.core.audio

/**
 * 频谱采集的**纯参数**（不依赖 Android，能单测）。
 *
 * ## 为什么单独抽出来
 *
 * 采集本身（`Visualizer` 的创建 / 轮询 / 异常处理）在 [SpectrumAnalyzer] 里，
 * 但里面掺着好几个「数字口径」—— 采集点数怎么夹、FFT 缓冲区多大、
 * 采样率到底是 Hz 还是毫赫兹 —— 这些全是**算错了就整条链路失真**的地方，
 * 而且**不接设备就验不了**。所以把口径抽成纯函数，单测直接盯住。
 *
 * ## 口径的来源
 *
 * 参照工程 `uni-launcher`（同一款车机，已跑通）：
 * `app/android/app/src/main/java/xyz/i4o/uni/launcher/audio/SpectrumBridge.java`
 * —— 用 `Visualizer(0)`（全局输出混音）、10Hz 轮询 `getFft()`。
 */
object SpectrumCapture {

    /**
     * 全局输出混音的会话 id。
     *
     * `Visualizer(0)` = 抓**全局输出混音**（所有 App 播出来的声音混在一起），
     * 不需要知道「谁在放」、不需要别人的 `audioSessionId`、**不需要隐藏 API**。
     *
     * 这条路能独立跑起来是这一轮的关键：以前只走「挂别人会话」，
     * 而拿会话 id 依赖隐藏 API —— 豁免一失败，整条频谱就永远停在 IDLE。
     */
    const val OUTPUT_MIX_SESSION_ID = 0

    /**
     * 期望的采集点数（2 的幂）。
     *
     * 512 点 → FFT 有效数据 256 字节 → 覆盖到采样率 / 4（48k 时约 12kHz）。
     */
    const val PREFERRED_CAPTURE_SIZE = 512

    /**
     * 轮询间隔（毫秒）→ **10Hz**。
     *
     * 与 uni-launcher 一致：频谱是给人看的，10Hz 足够跟得上鼓点，
     * 而且车机 IO 与 CPU 压力都可控（25Hz 的回调式采集在车机上太吵）。
     */
    const val POLL_INTERVAL_MS = 100L

    /**
     * 采样率的「合理下限」（Hz）。
     *
     * 低于这个值说明拿到的不是采样率（多半是 0 或者被 ROM 换了个单位）。
     */
    const val MIN_PLAUSIBLE_SAMPLE_RATE = 1000

    /**
     * 「像毫赫兹」的阈值。
     *
     * AOSP 文档写 `Visualizer.getSamplingRate()` 返回 Hz，
     * 但车机 ROM（以及 uni-launcher 的实测）返回的是**毫赫兹**（48000000）。
     * 两种都认：大于这个阈值就按毫赫兹折算成 Hz。
     */
    private const val MILLI_HZ_THRESHOLD = 100_000

    /**
     * 采集点数 → 实际用的点数（夹到设备支持的范围里）。
     *
     * `Visualizer.setCaptureSize` 只接受 `getCaptureSizeRange()` 给的范围，
     * 越界会直接抛异常 —— 所以**先夹再设**。
     *
     * @param minSize 设备支持的下限
     * @param maxSize 设备支持的上限
     * @param preferred 期望值（默认 [PREFERRED_CAPTURE_SIZE]）
     */
    fun resolveCaptureSize(
        minSize: Int,
        maxSize: Int,
        preferred: Int = PREFERRED_CAPTURE_SIZE,
    ): Int {
        val lo = minSize.coerceAtLeast(1)
        val hi = maxSize.coerceAtLeast(lo)
        return preferred.coerceIn(lo, hi)
    }

    /**
     * 采集点数 → FFT 缓冲区字节数。
     *
     * ## 为什么是采集点数的一半
     *
     * AOSP 的 `Visualizer.getFft(byte[])` 只往缓冲区里写 **captureSize / 2** 个字节
     * （1 字节直流 + (N/2−1)/2 组实虚对）。
     * 缓冲区按 `captureSize` 分配的话，后半截永远是 0，
     * 而 [SpectrumBands.fromFft] 是按「缓冲区长度 × 2 = 采样点数」推频率的 ——
     * 频率会整整差一倍，高频段还全是空的。
     */
    fun fftBufferSize(captureSize: Int): Int = (captureSize / 2).coerceAtLeast(8)

    /**
     * 采样率归一（统一成 Hz）。
     *
     * @param raw `Visualizer.getSamplingRate()` 的原始值
     * @return Hz；拿不到（<= 0）返回 0
     */
    fun normalizeSampleRate(raw: Int): Int {
        if (raw <= 0) return 0
        return if (raw >= MILLI_HZ_THRESHOLD) raw / 1000 else raw
    }

    /**
     * 采样率看着合不合理（是不是 Hz）。
     *
     * @param sampleRate 已归一的采样率
     */
    fun isPlausibleSampleRate(sampleRate: Int): Boolean =
        sampleRate >= MIN_PLAUSIBLE_SAMPLE_RATE

    /**
     * 这个异常是不是「权限不够」。
     *
     * 车机上 `Visualizer` 拿不到权限时抛的是 `SecurityException`，
     * 但部分 ROM 会包成别的异常类型 —— 所以也看消息里有没有 permission。
     */
    fun isPermissionError(t: Throwable): Boolean {
        if (t is SecurityException) return true
        val msg = t.message ?: return false
        return msg.contains("permission", ignoreCase = true) ||
            msg.contains("Permission Denial", ignoreCase = true)
    }
}
