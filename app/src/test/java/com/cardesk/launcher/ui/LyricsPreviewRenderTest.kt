package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 首页中央大字歌词（第 106 轮：前后句字号 24→30）效果图。
 *
 * 渲染 `preview/y25-lyrics.png`：上一句 / 当前句（带擦除高亮）/ 下一句。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsPreviewRenderTest {

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun 渲染三行大字歌词() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()

        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val field = LauncherView::class.java.getDeclaredField("scenePage").apply { isAccessible = true }
        val scenePage = field.get(launcher) as com.cardesk.launcher.ui.pages.ScenePage
        scenePage.showCenterLyric = true
        scenePage.lyricPrev = "风吹过山丘与河流"
        scenePage.lyricCurrent = "我们都曾勇敢地往前走"
        scenePage.lyricNext = "直到看见远方的灯火"
        scenePage.setLyricWipeImmediate(0.42f)
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()

        val dir = File("../preview").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, "y25-lyrics.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
