package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 第227轮：**视频壁纸渲染后端的默认值**，以及「窗口 format」与「画中画路线」两条口径的一致性。
 *
 * ## 背景（第227轮验收查出来的）
 *
 * 第225轮把 `design_video_wallpaper_backend` 的默认值翻成了 `TextureView`（保画中画圆角），
 * 第226轮解决了圆角/残留的病根（窗口透明时画中画改走「窗口内渲染」），但**默认值没翻回来** ——
 * 而第226轮的更新说明写的是「后端**保持 0**（`SurfaceView`）即可」。
 *
 * ⇒ 用户报的「**现在的默认方案**掉帧很严重」在默认配置下**并没有被修**：
 * 默认走的一直是 `TextureView`（每帧一次纹理上传、没有 HWC 直通）。
 *
 * `sp.getInt` 不会把默认值写回文件 ⇒ 从没手动设过这项的用户（= 所有老用户）
 * 拿到的就是默认值 ⇒ 这个默认值必须和更新说明**一字不差**。
 *
 * ## 这里钉住三件事
 *
 * 1. 默认后端是 `SurfaceView`（省电那条路）；
 * 2. 窗口透明性的判据（`needsTranslucentWindow`）与画中画路线的判据（`PipRenderPlan`）
 *    **必须给出一致的结论** —— 不一致就是「直角 / 错位 / 残留黑卡」那三个老毛病；
 * 3. 窗口 format 的目标值只有一个来源（`targetWindowFormat`），
 *    且它**必须在建窗之前**算出来（建窗后再改会重建窗口 surface ⇒ 视频重播）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoWallpaperBackendDefaultTest {

    private fun freshPrefs(): Prefs = Prefs(RuntimeEnvironment.getApplication())

    @Test
    fun `默认后端是 SurfaceView`() {
        assertEquals(
            VideoWallpaper.BACKEND_SURFACE_VIEW,
            freshPrefs().videoWallpaperBackend,
        )
    }

    @Test
    fun `默认后端对应半透明窗口`() {
        // 默认走 SurfaceView ⇒ 视频是硬件图层、压在窗口之下 ⇒ 窗口必须半透明。
        assertTrue(VideoWallpaper.needsTranslucentWindow(freshPrefs().videoWallpaperBackend))
    }

    @Test
    fun `默认后端下窗口目标 format 是半透明`() {
        assertEquals(
            android.graphics.PixelFormat.TRANSLUCENT,
            VideoWallpaper.targetWindowFormat(
                videoOn = true,
                videoEnabled = true,
                backend = VideoWallpaper.BACKEND_SURFACE_VIEW,
            ),
        )
    }

    @Test
    fun `视频壁纸没开时窗口目标 format 是不透明`() {
        // 没开视频 ⇒ 不需要透出视频 ⇒ 窗口不透明（默认）。
        assertEquals(
            android.graphics.PixelFormat.OPAQUE,
            VideoWallpaper.targetWindowFormat(
                videoOn = false,
                videoEnabled = false,
                backend = VideoWallpaper.BACKEND_SURFACE_VIEW,
            ),
        )
    }

    @Test
    fun `TextureView 后端的窗口目标 format 是不透明`() {
        // TextureView 是拉模式（帧画进窗口 buffer 本身）⇒ 不需要半透明窗口。
        assertEquals(
            android.graphics.PixelFormat.OPAQUE,
            VideoWallpaper.targetWindowFormat(
                videoOn = true,
                videoEnabled = true,
                backend = VideoWallpaper.BACKEND_TEXTURE_VIEW,
            ),
        )
    }

    @Test
    fun `窗口透明性与画中画路线的口径必须一致`() {
        // 这两条判据分别在「建窗之前」（MainActivity）和「承载面建起来之前」（refreshPip）用。
        // 口径不一致 ⇒ 窗口透明但画中画按不透明窗口画 ⇒ 圆角失效 / 切页残留黑卡。
        //
        // 第228轮真机复核：壁纸开（窗口半透明）⇒ 地图卡左上角是**直角**；
        // 壁纸关（窗口不透明）⇒ 圆角成立 ⇒ 「透明 ⇒ 镜像」的前提成立，判据不变。
        val cases = listOf(
            Triple(true, true, VideoWallpaper.BACKEND_SURFACE_VIEW),
            Triple(true, true, VideoWallpaper.BACKEND_TEXTURE_VIEW),
            Triple(false, false, VideoWallpaper.BACKEND_SURFACE_VIEW),
            Triple(true, false, VideoWallpaper.BACKEND_SURFACE_VIEW),
        )
        for ((videoOn, videoEnabled, backend) in cases) {
            val translucent = VideoWallpaper.needsTranslucentWindow(videoOn, videoEnabled, backend)
            val expected = if (translucent) PipRenderPlan.Mode.MIRROR else PipRenderPlan.Mode.LAYER
            assertEquals(
                "videoOn=$videoOn enabled=$videoEnabled backend=$backend",
                expected,
                PipRenderPlan.modeFor(translucent),
            )
        }
    }

    @Test
    fun `半透明窗口与画中画路线是一一对应的`() {
        // 窗口透明 ⇒ 镜像；窗口不透明 ⇒ 图层。反了就是「直角 / 错位 / 残留」那三个老毛病。
        assertEquals(PipRenderPlan.Mode.MIRROR, PipRenderPlan.modeFor(true))
        assertEquals(PipRenderPlan.Mode.LAYER, PipRenderPlan.modeFor(false))
    }

    @Test
    fun `显式写成 TextureView 的用户仍然保留自己的选择`() {
        // 改默认值不能影响**已经明确选过**的用户。
        val prefs = freshPrefs()
        prefs.videoWallpaperBackend = VideoWallpaper.BACKEND_TEXTURE_VIEW
        assertEquals(
            VideoWallpaper.BACKEND_TEXTURE_VIEW,
            Prefs(RuntimeEnvironment.getApplication()).videoWallpaperBackend,
        )
    }

    @Test
    fun `后端开关写入后能被读回来`() {
        val prefs = freshPrefs()
        prefs.videoWallpaperBackend = VideoWallpaper.BACKEND_SURFACE_VIEW
        assertEquals(
            VideoWallpaper.BACKEND_SURFACE_VIEW,
            Prefs(RuntimeEnvironment.getApplication()).videoWallpaperBackend,
        )
        assertFalse(VideoWallpaper.needsTranslucentWindow(VideoWallpaper.BACKEND_TEXTURE_VIEW))
    }
}
