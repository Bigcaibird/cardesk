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
 * 设置页**排版**的效果图（第六十轮）。
 *
 * 这一轮按用户要求做了两件事：
 *  1. 设置项之间别那么紧凑（行高 92→104、行间距 37→52）；
 *  2. 去掉标题下那条绝对定位的分割线（它落进第一行，跟行分割线叠成「两根横线」）。
 *
 * 产物：`preview/x01..x04-settings-*.png`（夜间四页）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLayoutRenderTest {

    private val dir = File("../preview").apply { mkdirs() }

    @Test
    fun 渲染设置页四页() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        call(launcher!!, "showSettings")
        shadowOf(Looper.getMainLooper()).idle()
        val overlay = findField<SettingsOverlay>(launcher, "settings")
        assertNotNull("设置页应该打开了", overlay)

        shot(decor, overlay!!, "boot", "x01-settings-boot.png")
        shot(decor, overlay, "dock", "x02-settings-dock.png")
        shot(decor, overlay, "daynight", "x03-settings-daynight.png")
        shot(decor, overlay, "diagnostics", "x04-settings-diagnostics.png")
    }

    private fun shot(decor: View, overlay: SettingsOverlay, key: String, name: String) {
        overlay.showPageKey(key)
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun layout(decor: View) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private inline fun <reified T> findField(target: Any, name: String): T? {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as? T
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
}
