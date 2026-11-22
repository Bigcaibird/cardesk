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
}
