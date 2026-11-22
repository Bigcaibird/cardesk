package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第214轮：**视频壁纸合成后端**的规则测试。
 *
 * ## 背景
 *
 * 第213轮实测出：模拟器上 `TextureView` 那条合成路径**解得出帧但画面不上屏**
 * （与静态壁纸截图全图对比，只差状态栏时钟那一小块）；
 * 同一个文件交给系统 Gallery3D（`SurfaceView` 路径）**画面完整显示**。
 *
 * ⇒ 第214轮把默认后端换成 `SurfaceView`，`TextureView` 保留备选（实车复测用）。
 *
 * 这两条规则错一点点就是「画面全黑」，所以单独拿纯函数钉住。
 */
class VideoWallpaperBackendTest {

    @Test
    fun `默认后端是 SurfaceView`() {
        assertEquals(VideoWallpaper.BACKEND_SURFACE_VIEW, VideoWallpaper.normalizeBackend(0))
        assertEquals(0, VideoWallpaper.BACKEND_SURFACE_VIEW)
    }

    @Test
    fun `TextureView 后端能原样认出来`() {
        assertEquals(VideoWallpaper.BACKEND_TEXTURE_VIEW, VideoWallpaper.normalizeBackend(1))
        assertEquals(1, VideoWallpaper.BACKEND_TEXTURE_VIEW)
    }

    @Test
    fun `非法后端值一律归到 SurfaceView`() {
        // 预置被改坏、版本回退、用户手改 pref……都归到默认值，不能跑到「没有后端」上。
        assertEquals(VideoWallpaper.BACKEND_SURFACE_VIEW, VideoWallpaper.normalizeBackend(99))
        assertEquals(VideoWallpaper.BACKEND_SURFACE_VIEW, VideoWallpaper.normalizeBackend(-1))
        assertEquals(VideoWallpaper.BACKEND_SURFACE_VIEW, VideoWallpaper.normalizeBackend(Int.MIN_VALUE))
    }

    @Test
    fun `只有 SurfaceView 后端需要半透明窗口`() {
        // SurfaceView 的 surface 在窗口 buffer 之下 → 窗口那块区域必须透明。
        assertTrue(VideoWallpaper.needsTranslucentWindow(VideoWallpaper.BACKEND_SURFACE_VIEW))
        // TextureView 是拉模式（帧画进窗口 buffer 本身）→ 窗口不透明也没关系。
        assertFalse(VideoWallpaper.needsTranslucentWindow(VideoWallpaper.BACKEND_TEXTURE_VIEW))
    }

    @Test
    fun `非法后端值的半透明判定等价于默认后端`() {
        assertTrue(VideoWallpaper.needsTranslucentWindow(99))
        assertTrue(VideoWallpaper.needsTranslucentWindow(-1))
    }
}
