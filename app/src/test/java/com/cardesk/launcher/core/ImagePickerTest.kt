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
import org.robolectric.annotation.Config

/**
 * 第230轮：**静态壁纸的图片选择**（降级链 + uri 兜底）。
 *
 * 背景见 [ImagePicker] 的类注释：车机没有相册/DocumentsUI，
 * 原来只发一次 `ACTION_OPEN_DOCUMENT`，抛异常就弹「没有可用的图片选择器」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagePickerTest {

    @Test
    fun `降级链先精确后宽泛`() {
        val list = ImagePicker.strategies()
        // 刻意没有 GET_CONTENT + 不限类型：那一档会被任意 app 接管（实测跳进音乐播放器）
        assertEquals(3, list.size)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, list[0].action)
        assertEquals("image/*", list[0].mime)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, list[1].action)
        assertEquals("*/*", list[1].mime)
        assertEquals(Intent.ACTION_GET_CONTENT, list[2].action)
        assertEquals("image/*", list[2].mime)
    }

    @Test
    fun `构造的 intent 带 CATEGORY_OPENABLE`() {
        val intent = ImagePicker.buildIntent(ImagePicker.strategies()[0])
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("image/*", intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun `取 uri 优先 data`() {
        val uri = Uri.parse("content://media/1")
        val intent = Intent().apply { data = uri }
        assertEquals(uri, ImagePicker.extractUri(intent))
    }

    @Test
    fun `没有 data 时退回 clipData`() {
        val uri = Uri.parse("content://media/2")
        val clip = android.content.ClipData.newRawUri("img", uri)
        val intent = Intent().apply { clipData = clip }
        assertEquals(uri, ImagePicker.extractUri(intent))
    }

    @Test
    fun `没有 data 和 clipData 时退回 EXTRA_STREAM`() {
        val uri = Uri.parse("file:///sdcard/a.jpg")
        val intent = Intent().apply { putExtra(Intent.EXTRA_STREAM, uri) }
        assertEquals(uri, ImagePicker.extractUri(intent))
    }

    @Test
    fun `什么都没有时返回 null`() {
        assertNull(ImagePicker.extractUri(null))
        assertNull(ImagePicker.extractUri(Intent()))
    }

    @Test
    fun `按扩展名粗筛是不是图片`() {
        assertTrue(ImagePicker.looksLikeImage(Uri.parse("file:///sdcard/a.jpg")))
        assertTrue(ImagePicker.looksLikeImage(Uri.parse("content://x/a.WEBP")))
        assertFalse(ImagePicker.looksLikeImage(Uri.parse("file:///sdcard/a.mp4")))
    }
}
