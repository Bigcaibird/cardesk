package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第219轮：**应用内视频库**的扫描逻辑（纯 JVM，不需要设备）。
 *
 * 覆盖：扩展名判定、目录扫描（深度/上限/隐藏目录）、排序、文案格式化。
 */
class VideoLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(path: String, size: Int = 1024): File {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(size) { 0 })
        return f
    }

    // ------------------------------------------------------------------ 扩展名

    @Test
    fun `取扩展名`() {
        assertEquals(".mp4", VideoLibrary.extensionOf("/storage/emulated/0/Download/CarDesk/a.mp4"))
        assertEquals(".mkv", VideoLibrary.extensionOf("/a/b/c.MKV"))
        assertEquals("", VideoLibrary.extensionOf("/a/b/noext"))
        assertEquals("", VideoLibrary.extensionOf("/a/b/.hidden"))
    }

    @Test
    fun `只认视频扩展名`() {
        assertTrue(VideoLibrary.isVideoPath("a.mp4"))
        assertTrue(VideoLibrary.isVideoPath("a.MOV"))
        assertTrue(VideoLibrary.isVideoPath("a.webm"))
        assertFalse(VideoLibrary.isVideoPath("a.txt"))
        assertFalse(VideoLibrary.isVideoPath("a.jpg"))
        assertFalse(VideoLibrary.isVideoPath("a.mp4.txt"))
    }

    // ------------------------------------------------------------------ 扫描

    @Test
    fun `扫目录只出视频`() {
        val root = tmp.newFolder("lib")
        write(File(root, "a.mp4").path)
        write(File(root, "b.txt").path)
        write(File(root, "c.jpg").path)

        val items = VideoLibrary.scanFiles(listOf(root))

        assertEquals(1, items.size)
        assertEquals("a.mp4", items[0].name)
        assertEquals(File(root, "a.mp4").absolutePath, items[0].path)
    }

    @Test
    fun `扫目录会递归子目录`() {
        val root = tmp.newFolder("lib")
        write(File(root, "top.mp4").path)
        write(File(root, "sub/inner.mp4").path)

        val items = VideoLibrary.scanFiles(listOf(root))

        assertEquals(2, items.size)
    }

    @Test
    fun `超过深度上限就不再往下钻`() {
        val root = tmp.newFolder("lib")
        write(File(root, "a.mp4").path)
        write(File(root, "d1/b.mp4").path)
        write(File(root, "d1/d2/c.mp4").path)

        // maxDepth=1 ⇒ 只看根目录（深度 0）与它下面一层（深度 1）
        val items = VideoLibrary.scanFiles(listOf(root), maxDepth = 1)

        assertEquals(2, items.size)
        assertTrue(items.none { it.name == "c.mp4" })
    }

    @Test
    fun `隐藏目录不扫`() {
        val root = tmp.newFolder("lib")
        write(File(root, "a.mp4").path)
        write(File(root, ".hidden/secret.mp4").path)

        val items = VideoLibrary.scanFiles(listOf(root))

        assertEquals(1, items.size)
        assertEquals("a.mp4", items[0].name)
    }

    @Test
    fun `结果条数有上限`() {
        val root = tmp.newFolder("lib")
        repeat(10) { write(File(root, "v$it.mp4").path) }

        val items = VideoLibrary.scanFiles(listOf(root), limit = 3)

        assertEquals(3, items.size)
    }

    @Test
    fun `目录不存在就当没有`() {
        val items = VideoLibrary.scanFiles(listOf(File(tmp.root, "not-exist")))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `空的目录返回空列表`() {
        val root = tmp.newFolder("lib")
        assertTrue(VideoLibrary.scanFiles(listOf(root)).isEmpty())
    }

    // ------------------------------------------------------------------ 排序与文案

    @Test
    fun `新的排前面`() {
        val root = tmp.newFolder("lib")
        val old = write(File(root, "old.mp4").path).apply { setLastModified(1000L) }
        val neu = write(File(root, "new.mp4").path).apply { setLastModified(9000L) }

        val items = VideoLibrary.scanFiles(listOf(root))

        assertEquals(neu.name, items[0].name)
        assertEquals(old.name, items[1].name)
    }

    @Test
    fun `大小文案`() {
        assertEquals("1.0 KB", VideoLibrary.formatSize(1024L))
        assertEquals("1.0 MB", VideoLibrary.formatSize(1024L * 1024L))
        assertEquals("58.1 MB", VideoLibrary.formatSize(60_878_141L))
        assertEquals("512 B", VideoLibrary.formatSize(512L))
    }

    @Test
    fun `视频扩展名白名单覆盖常见格式`() {
        listOf(".mp4", ".mkv", ".avi", ".mov", ".3gp", ".m4v", ".webm", ".ts", ".flv", ".wmv")
            .forEach { assertTrue("应支持 $it", VideoLibrary.isVideoPath("x$it")) }
    }
}
