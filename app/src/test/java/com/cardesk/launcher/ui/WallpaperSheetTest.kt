package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Wallpapers
import com.cardesk.launcher.ui.widget.WallpaperRenderer
import org.junit.Assert.assertEquals
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
 * 9 张预置壁纸的**联络表**（第五十七轮）。
 *
 * 产物：`preview/w01-wallpapers-night.png` / `preview/w02-wallpapers-day.png`
 * —— 一张图看全 9 张壁纸（3×3 排布，带名字），方便肉眼过一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperSheetTest {

    private val cellW = 640
    private val cellH = 360

    @Test
    fun 渲染九张壁纸联络表() {
        val dir = File("../preview").apply { mkdirs() }
        renderSheet(dir, "w01-wallpapers-night.png", night = true)
        renderSheet(dir, "w02-wallpapers-day.png", night = false)
    }

    /** 设置页「壁纸」页的出图（看一眼版式对不对）。 */
    @Test
    fun 渲染壁纸设置页() {
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
        call(launcher!!, "showSettings")
        shadowOf(Looper.getMainLooper()).idle()
        val overlay = findField<SettingsOverlay>(launcher, "settings")
        assertNotNull("设置页应该打开了", overlay)
        overlay!!.showPageKey("wallpaper")
        shadowOf(Looper.getMainLooper()).idle()

        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        val dir = File("../preview").apply { mkdirs() }
        FileOutputStream(File(dir, "w03-wallpaper-page.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private inline fun <reified T> findField(launcher: LauncherView, name: String): T? {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(launcher) as? T
    }

    private fun call(target: Any, method: String, vararg args: Any?): Any? {
        val types = args.map {
            when (it) {
                is Int -> Int::class.java
                is Boolean -> Boolean::class.java
                is String -> String::class.java
                else -> Any::class.java
            }
        }.toTypedArray()
        val m = LauncherView::class.java.getDeclaredMethod(method, *types)
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    private fun renderSheet(dir: File, name: String, night: Boolean) {
        val bmp = Bitmap.createBitmap(cellW * 3, cellH * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f
            color = Color.WHITE
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        Wallpapers.SPECS.forEachIndexed { i, spec ->
            val col = i % 3
            val row = i / 3
            val left = col * cellW
            val top = row * cellH
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            WallpaperRenderer.draw(canvas, spec, cellW.toFloat(), cellH.toFloat(), night)
            canvas.restore()
            // 名字条
            canvas.drawRect(
                RectF(left.toFloat(), top + cellH - 46f, left + cellW.toFloat(), top + cellH.toFloat()),
                Paint().apply { color = 0x8C000000.toInt() },
            )
            canvas.drawText("${i + 1} · ${spec.name}", left + 18f, top + cellH - 14f, text)
        }
        assertEquals(9, Wallpapers.SPECS.size)
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
