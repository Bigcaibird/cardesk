package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第三百一十八轮：视频壁纸的规格判定与日/夜文件选择（纯逻辑，不需要 Android）。
 */
class VideoSpecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun info(width: Int, height: Int, fps: Float? = 30f, bytes: Long = 1_000_000L): VideoSpec.Info =
        VideoSpec.Info(width = width, height = height, fps = fps, durationMs = 5_000L, bytes = bytes)

    private fun file(name: String, bytes: Int = 8): File =
        tmp.newFile(name).also { it.writeBytes(ByteArray(bytes)) }

    // ---------------------------------------------------------------- 规格判定

    @Test
    fun `1080p30 直接放过`() {
        assertEquals(VideoSpec.Verdict.Ok, VideoSpec.check(info(1920, 1080)))
    }

    @Test
    fun `低于 1080p 也放过`() {
        assertEquals(VideoSpec.Verdict.Ok, VideoSpec.check(info(1280, 720)))
    }

    @Test
    fun `1440p 只提示可能卡顿`() {
        val verdict = VideoSpec.check(info(2560, 1440))
        assertTrue(verdict is VideoSpec.Verdict.Warn)
    }

    @Test
    fun `超过 4K 拒绝`() {
        val verdict = VideoSpec.check(info(4096, 2160))
        assertTrue(verdict is VideoSpec.Verdict.Reject)
    }

    @Test
    fun `刚好的 4K 不拒绝（但会提示）`() {
        val verdict = VideoSpec.check(info(3840, 2160))
        assertTrue(verdict is VideoSpec.Verdict.Warn)
    }

    @Test
    fun `60fps 以上拒绝`() {
        val verdict = VideoSpec.check(info(1920, 1080, fps = 120f))
        assertTrue(verdict is VideoSpec.Verdict.Reject)
    }

    @Test
    fun `30fps 以上 60fps 以内提示`() {
        val verdict = VideoSpec.check(info(1920, 1080, fps = 50f))
        assertTrue(verdict is VideoSpec.Verdict.Warn)
    }

    @Test
    fun `读不出尺寸按损坏处理`() {
        val verdict = VideoSpec.check(info(0, 0))
        assertTrue(verdict is VideoSpec.Verdict.Reject)
    }

    @Test
    fun `没有帧率信息时不因帧率拒绝`() {
        assertEquals(VideoSpec.Verdict.Ok, VideoSpec.check(info(1920, 1080, fps = null)))
    }

    // ---------------------------------------------------------------- 日/夜文件选择

    @Test
    fun `白天选日间版`() {
        val day = file("wallpaper.mp4")
        val night = file("wallpaper_night.mp4")
        assertEquals(day, VideoSpec.chooseFile(day, night, nightNow = false))
    }

    @Test
    fun `夜间有夜间版就选夜间版`() {
        val day = file("wallpaper.mp4")
        val night = file("wallpaper_night.mp4")
        assertEquals(night, VideoSpec.chooseFile(day, night, nightNow = true))
    }

    @Test
    fun `夜间没有夜间版就回退日间版`() {
        val day = file("wallpaper.mp4")
        assertEquals(day, VideoSpec.chooseFile(day, null, nightNow = true))
    }

    @Test
    fun `夜间版是空文件也算没有`() {
        val day = file("wallpaper.mp4")
        val night = file("wallpaper_night.mp4", bytes = 0)
        assertEquals(day, VideoSpec.chooseFile(day, night, nightNow = true))
    }

    @Test
    fun `两段都没有返回 null`() {
        assertNull(VideoSpec.chooseFile(null, null, nightNow = true))
    }

    @Test
    fun `仅有夜间版时日间与夜间都兜底选夜间版`() {
        val night = file("wallpaper_night.mp4")
        assertEquals(night, VideoSpec.chooseFile(null, night, nightNow = true))
        assertEquals(night, VideoSpec.chooseFile(null, night, nightNow = false))
    }

    // ---------------------------------------------------------------- 格式化

    @Test
    fun `摘要包含分辨率帧率与体积`() {
        val text = VideoSpec.summary(info(1920, 1080, fps = 30f, bytes = 4_404_019L))
        assertEquals("1920×1080 · 30fps · 4.2MB", text)
    }

    @Test
    fun `帧率是整数时不带小数点`() {
        assertEquals("30", VideoSpec.formatFps(30f))
    }

    @Test
    fun `帧率是小数时保留一位`() {
        assertEquals("29.9", VideoSpec.formatFps(29.94f))
    }

    @Test
    fun `体积过百兆不带小数`() {
        assertEquals("120MB", VideoSpec.formatBytes(120L * 1024 * 1024))
    }
}
