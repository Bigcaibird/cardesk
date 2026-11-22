package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第207轮：**视频壁纸**的类型判断测试。
 *
 * 判断逻辑要在「MIME 不可信」的情况下也认得出视频 ——
 * 有些车机相册返回的是 `application/octet-stream`，只看 MIME 会漏。
 */
class VideoWallpaperTest {

    @Test
    fun `MIME 说��视频就认`() {
        assertTrue(VideoWallpaper.looksLikeVideo("video/mp4", "a.mp4"))
        assertTrue(VideoWallpaper.looksLikeVideo("video/webm", null))
        assertTrue(VideoWallpaper.looksLikeVideo("VIDEO/3GP", null))
    }

    @Test
    fun `MIME 不可信时看扩展名`() {
        // 有些车机相册给的是 octet-stream
        assertTrue(VideoWallpaper.looksLikeVideo("application/octet-stream", "clip.MP4"))
        assertTrue(VideoWallpaper.looksLikeVideo(null, "movie.mkv"))
        assertTrue(VideoWallpaper.looksLikeVideo(null, "clip.webm"))
    }

    @Test
    fun `图片不是视频`() {
        assertFalse(VideoWallpaper.looksLikeVideo("image/jpeg", "a.jpg"))
        assertFalse(VideoWallpaper.looksLikeVideo("image/png", "a.png"))
        assertFalse(VideoWallpaper.looksLikeVideo("application/octet-stream", "a.jpg"))
    }

    @Test
    fun `什么都没有就当不是视频`() {
        assertFalse(VideoWallpaper.looksLikeVideo(null, null))
        assertFalse(VideoWallpaper.looksLikeVideo("", ""))
    }

    @Test
    fun `没有扩展名的文件名不当成视频`() {
        // "clip" 没有扩展名 → 不能靠 "clip" 里含 "c" 之类蒙对
        assertFalse(VideoWallpaper.looksLikeVideo(null, "clip"))
        assertFalse(VideoWallpaper.looksLikeVideo(null, "my.mp4.txt"))
    }

    @Test
    fun `体积上限是 64MB`() {
        assertEquals(64L * 1024 * 1024, VideoWallpaper.MAX_BYTES)
    }
}
