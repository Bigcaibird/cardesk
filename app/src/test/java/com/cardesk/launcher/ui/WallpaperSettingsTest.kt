package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Wallpapers
import com.cardesk.launcher.ui.widget.BackgroundView
import com.cardesk.launcher.ui.widget.WallpaperThumbView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 壁纸设置页（第五十七轮）的交互回归。
 *
 * 钉住三件事：
 * 1. 「壁纸」页在设置导航里，且列出全部 9 张缩略图；
 * 2. 点缩略图会**落盘**（`design_wallpaper_index`）并回调给桌面
 * 3. 桌面收到回调后**立刻**换背景（用户在设置页里就能看到效果）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class WallpaperSettingsTest {

    private fun activity(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun <T> findAll(root: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        if (type.isInstance(root)) @Suppress("UNCHECKED_CAST") out.add(root as T)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) out.addAll(findAll(root.getChildAt(i), type))
        }
        return out
    }

    private inline fun <reified T> findField(launcher: LauncherView, name: String): T? {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(launcher) as? T
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun 壁纸页列出全部九张缩略图() {
        val a = activity()
        val prefs = Prefs(a.applicationContext)
        val overlay = SettingsOverlay(a, prefs, Palettes.night)
        overlay.showPageKey("wallpaper")
        shadowOf(Looper.getMainLooper()).idle()
        val thumbs = findAll(overlay, WallpaperThumbView::class.java)
        assertEquals("壁纸页应该列出 9 张缩略图（+1 张大预览 = 10 个视图）", 10, thumbs.size)
    }

    @Test
    fun 点缩略图会落盘并回调() {
        val a = activity()
        val prefs = Prefs(a.applicationContext)
        prefs.wallpaper = 0
        val overlay = SettingsOverlay(a, prefs, Palettes.night)
        overlay.showPageKey("wallpaper")
        shadowOf(Looper.getMainLooper()).idle()
        var changed = -1
        overlay.onWallpaperChange = { changed = it }
        val thumbs = findAll(overlay, WallpaperThumbView::class.java)
        assertTrue("应该有缩略图", thumbs.size >= 10)
        // 第 0 个是上方的大预览，第 1 个才是第一张壁纸的缩略图
        thumbs[1 + 4].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("点缩略图要落盘", 4, prefs.wallpaper)
        assertEquals("点缩略图要回调给桌面", 4, changed)
    }

    @Test
    fun 桌面收到壁纸变更会立刻换背景() {
        val a = activity()
        shadowOf(Looper.getMainLooper()).idle()
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(root)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val background = findField<BackgroundView>(launcher!!, "background")
        assertNotNull("桌面应该有背景层", background)

        val method = LauncherView::class.java.getDeclaredMethod("applyWallpaper", Int::class.java)
        method.isAccessible = true
        method.invoke(launcher, 6)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("背景层应该立刻换成第 6 张", 6, background!!.wallpaper)
        val prefs = Prefs(a.applicationContext)
        assertEquals("壁纸要落盘", 6, prefs.wallpaper)
    }

    @Test
    fun 默认壁纸是风景类() {
        val a = activity()
        val prefs = Prefs(a.applicationContext)
        assertEquals("默认壁纸下标", Wallpapers.DEFAULT_INDEX, prefs.wallpaper)
        assertEquals("紫晖雪山", Wallpapers.name(prefs.wallpaper))
    }
}
