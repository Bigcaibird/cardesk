package com.cardesk.launcher.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpectrumCapture] 单测（纯参数，不依赖 Android）。
 *
 * 这几个数字口径一旦算错，整条频谱链路就**静默失真**：
 * - 采集点数越界 → `setCaptureSize` 直接抛异常（挂不上）；
 * - FFT 缓冲区按采集点数分配 → 频率整整差一倍、高频段全空；
 * - 采样率单位搞错（Hz / 毫赫兹）→ 频段全部错位。
 *
 * 参照工程：uni-launcher 的 `audio/SpectrumBridge.java`（同一款车机，已跑通）。
 */
class SpectrumCaptureTest {

    @Test
    fun `输出混音的会话 id 是 0`() {
        assertEquals(0, SpectrumCapture.OUTPUT_MIX_SESSION_ID)
    }

    @Test
    fun `轮询是 10Hz`() {
        assertEquals(100L, SpectrumCapture.POLL_INTERVAL_MS)
    }

    @Test
    fun `采集点数夹在设备支持的范围里`() {
        // 常见范围 128~8192 → 512 直接可用
        assertEquals(512, SpectrumCapture.resolveCaptureSize(128, 8192))
        // 设备只支持到 256 → 降到 256
        assertEquals(256, SpectrumCapture.resolveCaptureSize(128, 256))
        // 设备最小就是 512 → 用 512
        assertEquals(512, SpectrumCapture.resolveCaptureSize(512, 8192))
        // 设备不支持 512（最大 256）→ 夹到 256
        assertEquals(256, SpectrumCapture.resolveCaptureSize(128, 256, preferred = 512))
        // 范围是乱的（min > max）→ 不崩，取 min
        assertTrue(SpectrumCapture.resolveCaptureSize(1024, 128) in 1..4096)
    }

    @Test
    fun `FFT 缓冲区是采集点数的一半`() {
        // AOSP 的 getFft() 只写 captureSize/2 个字节；
        // 按采集点数分配的话后半截永远是 0，频率会差一倍。
        assertEquals(256, SpectrumCapture.fftBufferSize(512))
        assertEquals(64, SpectrumCapture.fftBufferSize(128))
        // 太小也要给个能用的下限
        assertTrue(SpectrumCapture.fftBufferSize(4) >= 8)
    }

    @Test
    fun `采样率毫赫兹折算成赫兹`() {
        // 车机 ROM 上 Visualizer.getSamplingRate() 返回的是毫赫兹
        assertEquals(48000, SpectrumCapture.normalizeSampleRate(48_000_000))
        assertEquals(44100, SpectrumCapture.normalizeSampleRate(44_100_000))
        // 已经是 Hz 的原样返回
        assertEquals(48000, SpectrumCapture.normalizeSampleRate(48000))
        // 拿不到
        assertEquals(0, SpectrumCapture.normalizeSampleRate(0))
        assertEquals(0, SpectrumCapture.normalizeSampleRate(-1))
    }

    @Test
    fun `采样率合理性判断`() {
        assertTrue(SpectrumCapture.isPlausibleSampleRate(48000))
        assertTrue(SpectrumCapture.isPlausibleSampleRate(44100))
        assertFalse(SpectrumCapture.isPlausibleSampleRate(0))
        assertFalse(SpectrumCapture.isPlausibleSampleRate(480))
    }

    @Test
    fun `权限异常能认出来`() {
        assertTrue(SpectrumCapture.isPermissionError(SecurityException("denied")))
        assertTrue(
            SpectrumCapture.isPermissionError(
                RuntimeException("Permission Denial: cannot open"),
            ),
        )
        assertFalse(SpectrumCapture.isPermissionError(RuntimeException("普通错误")))
    }
}
