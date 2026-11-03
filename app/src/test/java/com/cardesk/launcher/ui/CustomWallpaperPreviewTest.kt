package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.CustomWallpaper
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.widget.BackgroundView
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
 * 自定义壁纸（第 105 轮）的设置页状态与效果图。
 *
 * 渲染 `preview/y24-settings-wallpaper-custom.png`：已导入照片并选中时，
 * 「壁纸」页应显示照片预览 + 当前使用中 + 删除入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomWallpaperPreviewTest {

    private fun <T> findAll(root: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        if (type.isInstance(root)) @Suppress("UNCHECKED_CAST") out.add(root as T)
        if (root is ViewGroup) for (i in 0 until root.childCount) out.addAll(findAll(root.getChildAt(i), type))
        return out
    }

    private fun shot(view: View, file: File) {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun 已导入时壁纸页显示照片状态并出图() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val prefs = Prefs(a.applicationContext)
        // 直接造一张「导入后」的落盘文件（和 importFromUri 产物同路径、同尺寸）。
        val ctx = a.applicationContext
        CustomWallpaper.reset(ctx)
        val photo = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(0xFF1B2A4A.toInt())
            drawCircle(1500f, 320f, 180f, android.graphics.Paint().apply { color = 0xFFF2D6A2.toInt() })
            drawRect(0f, 760f, 1920f, 1080f, android.graphics.Paint().apply { color = 0xFF12203A.toInt() })
        }
        val f = CustomWallpaper.file(ctx)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { photo.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        photo.recycle()
        CustomWallpaper.invalidate()
        prefs.wallpaperCustom = true

        val overlay = SettingsOverlay(a, prefs, Palettes.night)
        overlay.showPageKey("wallpaper")
        shadowOf(Looper.getMainLooper()).idle()
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        overlay.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("应有『导入照片…』按钮", findAll(overlay, TextView::class.java).firstOrNull { it.text == "导入照片…" })
        assertNotNull("应有『删除自定义壁纸』", findAll(overlay, TextView::class.java).firstOrNull { it.text == "删除自定义壁纸" })
        assertNotNull(
            "应显示当前使用自定义壁纸",
            findAll(overlay, TextView::class.java).firstOrNull { it.text.toString().contains("当前：自定义壁纸") },
        )
        val dir = File("../preview").apply { mkdirs() }
        shot(overlay, File(dir, "y24-settings-wallpaper-custom.png"))
    }

    @Test
    fun 渲染自定义壁纸的桌面背景() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        // 一张「照片感」的图：天空渐变 + 太阳 + 地平线，用来验证居中裁切与夜间 scrim。
        val photo = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(0xFF0E1A33.toInt())
            drawCircle(1180f, 300f, 150f, android.graphics.Paint().apply { color = 0xFFF4D7A0.toInt() })
            drawRect(0f, 620f, 1600f, 1000f, android.graphics.Paint().apply { color = 0xFF0A1224.toInt() })
        }
        val bg = BackgroundView(a)
        bg.palette = com.cardesk.launcher.core.Palettes.night
        bg.custom = true
        bg.customBitmap = photo
        bg.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bg.layout(0, 0, 1920, 1080)
        val dir = File("../preview").apply { mkdirs() }
        shot(bg, File(dir, "y24-home-custom-wallpaper.png"))
        photo.recycle()
    }

    @Test
    fun 未导入时壁纸页给出导入入口() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val ctx = a.applicationContext
        CustomWallpaper.reset(ctx)
        val prefs = Prefs(a.applicationContext)
        prefs.wallpaperCustom = false
        val overlay = SettingsOverlay(a, prefs, Palettes.night)
        overlay.showPageKey("wallpaper")
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("应有『导入照片…』按钮", findAll(overlay, TextView::class.java).firstOrNull { it.text == "导入照片…" })
        assertNotNull(
            "应提示还没有导入",
            findAll(overlay, TextView::class.java).firstOrNull { it.text == "还没有导入壁纸" },
        )
    }
}
