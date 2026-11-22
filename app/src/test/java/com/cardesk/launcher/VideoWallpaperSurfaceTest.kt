package com.cardesk.launcher

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.VideoWallpaper
import com.cardesk.launcher.ui.LauncherView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 第208轮：**视频壁纸冷启动恢复**的回归测试。
 *
 * ## 根因
 *
 * `TextureView.surfaceTexture` 只有在 View attach 且完成布局之后才非 null。
 * 冷启动恢复视频壁纸时（`build()` 里 `post { startVideoWallpaper() }`），
 * 那个 Runnable 很容易跑在 SurfaceTexture 建好之前，
 * 于是 `Surface(null)` 直接抛异常 → 视频壁纸起不来，
 * catch 分支还顺手把用户的「视频壁纸」开关写成了关闭。
 *
 * ## 锁住什么
 *
 * ① Surface 没就绪时 **不抛异常**，而是挂起起播请求；
 * ② 挂起期间 **不动**用户的开关（不能被内部的 stop 误关）；
 * ③ 用户主动关掉视频壁纸时，挂起的请求要一起取消
 *    （不然 SurfaceTexture 稍后就绪时又自己播起来）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class VideoWallpaperSurfaceTest {

    private fun launcher(): LauncherView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return findLauncher(root)!!
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 造一个「已导入视频」的现场（内容是假的，这里只关心起播时机，不解码）。 */
    private fun fakeImportedVideo(ctx: Context) {
        val f = VideoWallpaper.file(ctx)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(1024))
    }

    @Test
    fun `Surface 未就绪时起播请求挂起而不是抛异常`() {
        val ctx = RuntimeEnvironment.getApplication()
        fakeImportedVideo(ctx)
        val l = launcher()
        // 不抛异常就是过了；Robolectric 下 TextureView 没有 SurfaceTexture，正好走挂起分支
        l.startVideoWallpaper()
        assertTrue(
            "Surface 未就绪时应该挂起起播请求，等 onSurfaceTextureAvailable 再播",
            l.pendingVideoWallpaperStartForTest(),
        )
    }

    @Test
    fun `挂起期间不能把用户的视频壁纸开关误关掉`() {
        val ctx = RuntimeEnvironment.getApplication()
        fakeImportedVideo(ctx)
        val prefs = Prefs(ctx)
        prefs.videoWallpaper = true
        prefs.videoWallpaperEnabled = true
        val l = launcher()
        l.startVideoWallpaper()
        assertTrue(prefs.videoWallpaperEnabled)
        assertTrue(prefs.videoWallpaper)
    }

    @Test
    fun `关掉视频壁纸会取消挂起的起播请求`() {
        val ctx = RuntimeEnvironment.getApplication()
        fakeImportedVideo(ctx)
        val l = launcher()
        l.startVideoWallpaper()
        assertTrue(l.pendingVideoWallpaperStartForTest())
        l.stopVideoWallpaper()
        assertFalse(
            "用户关了视频壁纸后，Surface 就绪时不该又自己播起来",
            l.pendingVideoWallpaperStartForTest(),
        )
    }
}
