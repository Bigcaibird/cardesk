package com.cardesk.launcher.ui

import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.SystemSettingsCatalog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
 * 原厂设置目录页（第九十五轮 C4）的 UI 回归 + 效果图。
 *
 * 注入假的 `com.android.settings` / `com.ecarx.settings` 活动，验证：
 * 1. 页面列出可解析的原厂设置入口（语义化中文名）；
 * 2. 点击入口真的会 `startActivity`；
 * 3. 渲染 `preview/y01-settings-oem.png` 供目视检查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OemSettingsPageTest {

    private val dir = File("../preview").apply { mkdirs() }

    @Test
    fun 原厂设置页列出入口并可打开() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        prime(activity)
        SystemSettingsCatalog.warmNow(activity)
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        call(launcher!!, "showSettings")
        shadowOf(Looper.getMainLooper()).idle()
        val overlay = findField<SettingsOverlay>(launcher, "settings")
        assertNotNull("设置页应该打开了", overlay)
        overlay!!.showPageKey("oemsettings")
        shadowOf(Looper.getMainLooper()).idle()

        val texts = findAll(overlay, TextView::class.java).map { it.text.toString() }
        assertTrue("应列出系统设置，实际=$texts", texts.contains("系统设置"))
        assertTrue("应列出 WLAN，实际=$texts", texts.contains("WLAN"))
        assertTrue("应列出驾驶模式，实际=$texts", texts.contains("驾驶模式"))

        // 点击「系统设置」那一行的父容器 → 应真的发出 startActivity
        val label = findAll(overlay, TextView::class.java).first { it.text == "系统设置" }
        val holder = label.parent as View
        holder.performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("点击入口应发出 Intent", started)

        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, "y01-settings-oem.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun prime(activity: MainActivity) {
        val shadow = shadowOf(activity.packageManager)
        fun add(pkg: String, cls: String, action: String?) {
            val component = ComponentName(pkg, cls)
            shadow.addOrUpdateActivity(
                ActivityInfo().apply {
                    packageName = pkg
                    name = cls
                    exported = true
                    enabled = true
                },
            )
            if (action != null) {
                shadow.addIntentFilterForActivity(component, IntentFilter(action))
            }
        }
        add("com.android.settings", "com.android.settings.Settings", Settings.ACTION_SETTINGS)
        add("com.android.settings", "com.android.settings.wifi.WifiSettings", Settings.ACTION_WIFI_SETTINGS)
        add("com.ecarx.settings", "com.ecarx.settings.drivemode.DriveModeActivity", null)
    }

    private fun layout(decor: View) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun <T> findAll(root: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        if (type.isInstance(root)) @Suppress("UNCHECKED_CAST") out.add(root as T)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) out.addAll(findAll(root.getChildAt(i), type))
        }
        return out
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
}
