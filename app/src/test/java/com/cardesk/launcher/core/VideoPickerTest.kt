package com.cardesk.launcher.core

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 第218轮：**视频壁纸的文件选择**（降级链 + uri 兜底）。
 *
 * 背景见 [VideoPicker] 的类注释 —— 实车（领克 AAOS 820A）上
 * 「没有可用的视频选择器」+「选了但 uri 是 null」两个问题叠在一起。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPickerTest {

    @Test
    fun `降级链先精确后宽泛`() {
        val list = VideoPicker.strategies()
        assertEquals(3, list.size)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, list[0].action)
        assertEquals("video/*", list[0].mime)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, list[1].action)
        assertEquals("*/*", list[1].mime)
        assertEquals(Intent.ACTION_GET_CONTENT, list[2].action)
    }

    @Test
    fun `构造的 intent 带 CATEGORY_OPENABLE`() {
        val intent = VideoPicker.buildIntent(VideoPicker.strategies()[0])
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("video/*", intent.type)
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun `不限类型那一级不带 type`() {
        val intent = VideoPicker.buildIntent(VideoPicker.strategies()[1])
        assertEquals("*/*", intent.type)
    }

    @Test
    fun `能从 data 里取到 uri`() {
        val uri = Uri.parse("content://media/external/video/media/42")
        val data = Intent().setData(uri)
        assertEquals(uri, VideoPicker.extractUri(data))
    }

    @Test
    fun `能从 clipData 里取到 uri`() {
        val uri = Uri.parse("content://media/external/video/media/77")
        val data = Intent().apply {
            clipData = android.content.ClipData.newUri(
                RuntimeEnvironment.getApplication().contentResolver,
                "video",
                uri,
            )
        }
        assertEquals(uri, VideoPicker.extractUri(data))
    }

    @Test
    fun `能从 EXTRA_STREAM 里取到 uri`() {
        val uri = Uri.parse("file:///storage/emulated/0/Movies/a.mp4")
        val data = Intent().putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(uri, VideoPicker.extractUri(data))
    }

    @Test
    fun `空的 intent 取不到 uri`() {
        assertNull(VideoPicker.extractUri(null))
        assertNull(VideoPicker.extractUri(Intent()))
    }

    @Test
    fun `按扩展名判断是不是视频`() {
        assertTrue(VideoPicker.looksLikeVideo(Uri.parse("file:///a/b/MOVIE.MP4")))
        assertTrue(VideoPicker.looksLikeVideo(Uri.parse("file:///a/b/x.mkv")))
        assertFalse(VideoPicker.looksLikeVideo(Uri.parse("file:///a/b/photo.jpg")))
        assertFalse(VideoPicker.looksLikeVideo(Uri.parse("file:///a/b/doc.pdf")))
    }
}
