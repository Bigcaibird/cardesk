package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第230轮：**图片库**扫描逻辑（[ImageLibrary] 的纯逻辑部分）。
 *
 * 背景：用户反馈静态壁纸导入只弹「没有可用的图片选择器」——
 * 车机没有相册/DocumentsUI，改成应用内列表后，列表的取舍全在这里：
 * 扫哪些扩展名、扫多深、最多列多少、怎么排序。
 */
class ImageLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `图片扩展名白名单包含常见格式但不含动图`() {
        assertTrue(ImageLibrary.isImagePath("a.jpg"))
        assertTrue(ImageLibrary.isImagePath("a.JPEG"))
        assertTrue(ImageLibrary.isImagePath("a.png"))
        assertTrue(ImageLibrary.isImagePath("a.webp"))
        assertTrue(ImageLibrary.isImagePath("a.bmp"))
        assertTrue(ImageLibrary.isImagePath("a.heic"))
        // 刻意不含 gif：静态壁纸只取一帧，放进来会让人误以为会动
        assertFalse(ImageLibrary.isImagePath("a.gif"))
        assertFalse(ImageLibrary.isImagePath("a.mp4"))
        assertFalse(ImageLibrary.isImagePath("a"))
    }

    @Test
    fun `扫描能找到图片并跳过视频和隐藏目录`() {
        val root = tmp.newFolder("Pictures")
        File(root, "one.jpg").writeBytes(ByteArray(16))
        File(root, "two.PNG").writeBytes(ByteArray(16))
        File(root, "clip.mp4").writeBytes(ByteArray(16))
        val hidden = File(root, ".thumbnails").apply { mkdirs() }
        File(hidden, "hidden.png").writeBytes(ByteArray(16))

        val items = ImageLibrary.scanFiles(listOf(root))
        assertEquals(2, items.size)
        assertTrue(items.all { it.name.endsWith(".jpg", true) || it.name.endsWith(".png", true) })
        assertFalse(items.any { it.name == "hidden.png" })
    }

    @Test
    fun `支持子目录但不超过深度上限`() {
        val root = tmp.newFolder("Download")
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "deep.png").writeBytes(ByteArray(8))
        val deeper = File(sub, "deeper").apply { mkdirs() }
        File(deeper, "tooDeep.png").writeBytes(ByteArray(8))

        // 深度 0：只看根目录，进不了子目录
        val depth0 = ImageLibrary.scanFiles(listOf(root), maxDepth = 0)
        assertFalse(depth0.any { it.name == "deep.png" })

        // 深度 1：能看到 sub 里的图，但进不了 sub/deeper
        val depth1 = ImageLibrary.scanFiles(listOf(root), maxDepth = 1)
        assertTrue(depth1.any { it.name == "deep.png" })
        assertFalse(depth1.any { it.name == "tooDeep.png" })

        // 深度 2：两层都能看到
        val depth2 = ImageLibrary.scanFiles(listOf(root), maxDepth = 2)
        assertTrue(depth2.any { it.name == "tooDeep.png" })
    }

    @Test
    fun `条数受上限约束`() {
        val root = tmp.newFolder("DCIM")
        repeat(20) { File(root, "img$it.jpg").writeBytes(ByteArray(4)) }
        val items = ImageLibrary.scanFiles(listOf(root), limit = 5)
        assertEquals(5, items.size)
    }

    @Test
    fun `最新的排前面`() {
        val root = tmp.newFolder("Pictures2")
        val older = File(root, "older.jpg").apply { writeBytes(ByteArray(4)) }
        val newer = File(root, "newer.jpg").apply { writeBytes(ByteArray(4)) }
        older.setLastModified(1_600_000_000_000L)
        newer.setLastModified(1_700_000_000_000L)

        val items = ImageLibrary.scanFiles(listOf(root))
        assertEquals("newer.jpg", items.first().name)
    }

    @Test
    fun `不存在的目录直接跳过`() {
        val missing = File(tmp.root, "nope")
        assertTrue(ImageLibrary.scanFiles(listOf(missing)).isEmpty())
    }

    @Test
    fun `大小文案`() {
        assertEquals("512 B", ImageLibrary.formatSize(512))
        assertEquals("1.5 KB", ImageLibrary.formatSize(1536))
        assertEquals("2.0 MB", ImageLibrary.formatSize(2L * 1024 * 1024))
    }

    @Test
    fun `扩展名取法`() {
        assertEquals(".jpg", ImageLibrary.extensionOf("/sdcard/a/b.jpg"))
        assertEquals("", ImageLibrary.extensionOf("/sdcard/a/b"))
        assertEquals("", ImageLibrary.extensionOf(".hidden"))
    }
}
