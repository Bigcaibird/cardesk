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
     * ## 为什么从 512 改成 1024（第八十五轮）
     *
     * **512 点在 44.1kHz 下分辨不了低频区**，于是「跟随低频」的色相**永远是 0°（红）**。
     *
     * 频率分辨率 = 采样率 / 采集点数：
     *
     * | 采集点数 | 44.1kHz 下的 bin 间隔 | 60~149Hz 的低频四段落在哪 |
     * | --- | --- | --- |
     * | 512 | **86.1 Hz** | 四段**全部塌进同一个 bin**（bin 1） |
     * | 1024 | **43.1 Hz** | 四段各占不同 bin（[1] / [1,2] / [2] / [2,3]） |
     *
     * 四段塌进同一个 bin ⇒ 这四段的功率**永远相等** ⇒
     * `AmbientPalette.bassHue` 的「取最响的那一段」**永远取到第 0 段** ⇒
     * 色相恒为 0°，亮度也因为低频能量饱和而钉死 ——
     * 表现就是「灯是红的、亮度拉满、一动不动」，也就是用户说的「太僵硬」。
     *
     * 1024 点把低频区分开了，色相才能跟着贝斯的音高走。
     *
     * ## 为什么不会在不支持的设备上出事
     *
     * [resolveCaptureSize] 会把 1024 **夹到设备支持的范围里**
     * （`Visualizer.getCaptureSizeRange()`），设备只支持 512 就还是 512 ——
     * 行为与从前一致，不会更差。
     * 实测：ecarx 平台模拟器（Android 10）报的范围是 **128 ~ 1024**。
     */
    const val PREFERRED_CAPTURE_SIZE = 1024

    /**
     * 采集点数的**兜底值**（设备报支持、实际却拒绝时退回这个）。
     *
     * 512 是从前的口径：能用就用，不能用也只是分辨率低一点，
     * 总比整个频谱采集起不来强。
     */
    const val FALLBACK_CAPTURE_SIZE = 512

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
     * ## 第八十轮：**按采集点数给，不能给一半**
     *
     * AOSP 公开文档口径：`getFft()` 往缓冲区里写的是 **N 个字节**
     * （1 字节 DC + 1 字节 Nyquist + (N/2−1) 组实虚对，每组 2 字节 = N）。
     *
     * 以前这里给的是 `captureSize / 2` —— **ROM 会往这个数组里写 N−1 个字节**，
     * 越界那两百多个字节**直接踩坏 Java 堆**；
     * 堆坏掉之后不一定立刻崩，ART 的 GC 扫到坏引用时才 native SIGSEGV
     * （现场表现就是「开氛围灯页过一会儿闪退」——
     * 第八十轮在 ecarx 平台模拟器上复现，tombstone 定位到
     * `art::gc::collector::ConcurrentCopying::AddLiveBytesAndScanRef`）。
     *
     * 参照工程 `uni-launcher`（**同一款车机，已跑通**）用的是 `new byte[captureSize]`，
     * 与文档口径一致 —— 本项目现在也对齐它。
     *
     * 频率轴不再靠「缓冲区长度 × 2」凑，而是显式用采集点数 N（见 [SpectrumBands.fromFft]）。
     */
    fun fftBufferSize(captureSize: Int): Int = captureSize.coerceAtLeast(8)

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
