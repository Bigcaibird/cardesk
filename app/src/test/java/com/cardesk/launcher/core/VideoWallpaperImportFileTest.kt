package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * 第219轮：**从文件导入视频壁纸**。
 *
 * 应用内视频列表给的是**文件路径**（不是 content Uri）⇒
 * 这条路必须真的把字节抄进壁纸视频文件里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoWallpaperImportFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        VideoWallpaper.delete(ctx)
    }

    @Test
    fun `从文件导入后字节一致`() {
        val src = tmp.newFile("clip.mp4")
        val payload = ByteArray(4096) { (it % 251).toByte() }
        src.writeBytes(payload)

        val ok = VideoWallpaper.importFromFile(ctx, src)

        assertTrue("导入应成功", ok)
        val out = VideoWallpaper.file(ctx)
        assertTrue("壁纸视频文件应存在：${out.absolutePath}", out.isFile)
        assertEquals(payload.size.toLong(), out.length())
        assertTrue("内容应逐字节一致", payload.contentEquals(out.readBytes()))
    }

    @Test
    fun `导入后已导入标记成立`() {
        val src = tmp.newFile("clip.mp4")
        src.writeBytes(ByteArray(2048))

        VideoWallpaper.importFromFile(ctx, src)

        assertTrue(VideoWallpaper.has(ctx))
        assertEquals(2048L, VideoWallpaper.sizeBytes(ctx))
    }

    @Test
    fun `文件不存在时导入失败`() {
        ShadowLog.clear()
        val missing = File(tmp.root, "not-exist.mp4")

        val ok = VideoWallpaper.importFromFile(ctx, missing)

        assertFalse("不存在的文件应导入失败", ok)
        assertFalse(VideoWallpaper.has(ctx))
        // 必须走「文件不存在或为空」这条**明确的**早退，
        // 而不是掉进异常兜底（那样排查时看不出是哪种失败）。
        assertTrue(
            "应记「文件不存在或为空」，实际=" + ShadowLog.getLogs().joinToString { it.msg },
            ShadowLog.getLogs().any { it.msg.contains("文件不存在或为空") },
        )
    }

    @Test
    fun `空文件导入失败`() {
        val empty = tmp.newFile("empty.mp4")

        val ok = VideoWallpaper.importFromFile(ctx, empty)

        assertFalse("空文件应导入失败", ok)
        assertFalse(VideoWallpaper.has(ctx))
    }

    @Test
    fun `重复导入会覆盖前一个`() {
        val first = tmp.newFile("a.mp4").apply { writeBytes(ByteArray(1000)) }
        val second = tmp.newFile("b.mp4").apply { writeBytes(ByteArray(2000)) }

        VideoWallpaper.importFromFile(ctx, first)
        VideoWallpaper.importFromFile(ctx, second)

        assertEquals(2000L, VideoWallpaper.sizeBytes(ctx))
    }

    @Test
    fun `夜间视频导入落盘到独立文件且不覆盖日间视频`() {
        val daySrc = tmp.newFile("day.mp4").apply { writeBytes(ByteArray(3000) { 1 }) }
        val nightSrc = tmp.newFile("night.mp4").apply { writeBytes(ByteArray(5000) { 2 }) }

        assertTrue("日间视频导入应成功", VideoWallpaper.importFromFile(ctx, daySrc, night = false))
        assertTrue("夜间视频导入应成功", VideoWallpaper.importFromFile(ctx, nightSrc, night = true))

        assertTrue("日间视频应存在", VideoWallpaper.hasDay(ctx))
        assertTrue("夜间视频应存在", VideoWallpaper.hasNight(ctx))
        assertTrue("has() 应为 true", VideoWallpaper.has(ctx))
        assertEquals("日间视频不应被夜间导入覆盖", 3000L, VideoWallpaper.sizeBytes(ctx))
        assertEquals("夜间视频应记录自身大小", 5000L, VideoWallpaper.nightSizeBytes(ctx))

        val dayOut = VideoWallpaper.videoFile(ctx, night = false)
        val nightOut = VideoWallpaper.videoFile(ctx, night = true)
        assertEquals(VideoWallpaper.dayFile(ctx).absolutePath, dayOut.absolutePath)
        assertEquals(VideoWallpaper.nightFile(ctx).absolutePath, nightOut.absolutePath)
        assertEquals(1.toByte(), dayOut.readBytes().first())
        assertEquals(2.toByte(), nightOut.readBytes().first())
    }

    @Test
    fun `仅导入夜间视频时不覆盖日间槽且播放可回退夜间版`() {
        val nightSrc = tmp.newFile("only_night.mp4").apply { writeBytes(ByteArray(4096) { 9 }) }

        assertTrue("仅导入夜间视频应成功", VideoWallpaper.importFromFile(ctx, nightSrc, night = true))
        assertFalse("未导入日间视频时 hasDay 应为 false", VideoWallpaper.hasDay(ctx))
        assertTrue("夜间视频应已落盘", VideoWallpaper.hasNight(ctx))
        assertTrue("仅有夜间视频时 has() 也应为 true", VideoWallpaper.has(ctx))
        assertEquals(0L, VideoWallpaper.sizeBytes(ctx))
        assertEquals(4096L, VideoWallpaper.nightSizeBytes(ctx))

        // 日夜模式下都应拿到有效的夜间视频，不回退到不存在的日间文件
        assertEquals(VideoWallpaper.nightFile(ctx).absolutePath, VideoWallpaper.videoFile(ctx, night = true).absolutePath)
        assertEquals(VideoWallpaper.nightFile(ctx).absolutePath, VideoWallpaper.videoFile(ctx, night = false).absolutePath)
    }

    @Test
    fun `删除夜间视频不影响已导入的日间视频`() {
        val daySrc = tmp.newFile("day2.mp4").apply { writeBytes(ByteArray(1500) { 3 }) }
        val nightSrc = tmp.newFile("night2.mp4").apply { writeBytes(ByteArray(2500) { 4 }) }

        assertTrue(VideoWallpaper.importFromFile(ctx, daySrc, night = false))
        assertTrue(VideoWallpaper.importFromFile(ctx, nightSrc, night = true))

        VideoWallpaper.deleteNight(ctx)

        assertTrue("删除夜间视频后日间视频应完好", VideoWallpaper.hasDay(ctx))
        assertFalse("夜间视频应已删除", VideoWallpaper.hasNight(ctx))
        assertEquals(1500L, VideoWallpaper.sizeBytes(ctx))
        assertEquals(0L, VideoWallpaper.nightSizeBytes(ctx))
        // 夜间模式自动回退日间视频
        assertEquals(VideoWallpaper.dayFile(ctx).absolutePath, VideoWallpaper.videoFile(ctx, night = true).absolutePath)
    }

    @Test
    fun `封面略缩图按日夜槽位独立读写并在删除时同步清理`() {
        val daySrc = tmp.newFile("day_thumb.mp4").apply { writeBytes(ByteArray(1024) { 5 }) }
        val nightSrc = tmp.newFile("night_thumb.mp4").apply { writeBytes(ByteArray(2048) { 6 }) }
        assertTrue(VideoWallpaper.importFromFile(ctx, daySrc, night = false))
        assertTrue(VideoWallpaper.importFromFile(ctx, nightSrc, night = true))

        // 写入有效 JPEG 略缩图并验证从磁盘读回与内存缓存命中
        val dayThumb = VideoWallpaper.thumbFile(ctx, night = false)
        val nightThumb = VideoWallpaper.thumbFile(ctx, night = true)
        dayThumb.parentFile?.mkdirs()
        val bmpDay = android.graphics.Bitmap.createBitmap(120, 68, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF3388CC.toInt()) }
        val bmpNight = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF8844AA.toInt()) }
        java.io.FileOutputStream(dayThumb).use { bmpDay.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        java.io.FileOutputStream(nightThumb).use { bmpNight.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        dayThumb.setLastModified(VideoWallpaper.dayFile(ctx).lastModified() + 1000L)
        nightThumb.setLastModified(VideoWallpaper.nightFile(ctx).lastModified() + 1000L)

        VideoWallpaper.invalidateThumbnail()
        val loadedDay = VideoWallpaper.loadThumbnail(ctx, night = false)
        val loadedNight = VideoWallpaper.loadThumbnail(ctx, night = true)
        assertTrue("日间封面略缩图应加载成功", loadedDay != null && loadedDay.width == 120)
        assertTrue("夜间封面略缩图应加载成功", loadedNight != null && loadedNight.width == 160)
        assertTrue("内存缓存应命中日间封面", VideoWallpaper.cachedThumbnail(night = false) === loadedDay)
        assertTrue("内存缓存应命中夜间封面", VideoWallpaper.cachedThumbnail(night = true) === loadedNight)

        // 删除夜间视频时应同步清理夜间封面，保留日间封面
        VideoWallpaper.deleteNight(ctx)
        assertFalse("夜间封面文件应被清理", nightThumb.exists())
        assertTrue("夜间内存缓存应被清空", VideoWallpaper.cachedThumbnail(night = true) == null)
        assertTrue("日间封面应保持完好", dayThumb.exists() && VideoWallpaper.cachedThumbnail(night = false) != null)
    }
}
