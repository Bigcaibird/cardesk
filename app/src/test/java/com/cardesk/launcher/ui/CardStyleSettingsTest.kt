package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
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
 * 卡片风格设置（第五十八轮）的交互回归。
 *
 * 钉住三件事：
 * 1. 「卡片风格」页真的有**透明度 / 圆角**两个滑块 + 一个颜色入口；
 * 2. 拖动滑块会**落盘**并通知桌面；
 * 3. 桌面应用后，**全局 token**（卡片面 alpha）和**圆角**都跟着变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class CardStyleSettingsTest {

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

    /**
     * 模拟用户拖动滑块。
     *
     *  走的是 fromUser=false，页面会忽略；
     * 所以直接把视图内部那个监听器取出来，按「用户拖动」调用一次。
     * 字段名在不同 Android 版本里变过，
     * 所以按**类型**找，不按名字。
     */
    private fun userSeek(bar: SeekBar, progress: Int) {
        var listener: SeekBar.OnSeekBarChangeListener? = null
        var clazz: Class<*>? = bar.javaClass
        while (clazz != null && listener == null) {
            clazz.declaredFields.forEach { f ->
                if (SeekBar.OnSeekBarChangeListener::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    listener = f.get(bar) as? SeekBar.OnSeekBarChangeListener
                }
            }
            clazz = clazz.superclass
        }
        assertNotNull("应该能从 SeekBar 里取到变化监听器", listener)
        listener!!.onProgressChanged(bar, progress, true)
    }

    @Test
    fun 卡片风格页有透明度和圆角两个滑块() {
        val a = activity()
        val prefs = Prefs(a.applicationContext)
        val overlay = SettingsOverlay(a, prefs, Palettes.night)
        overlay.showPageKey("cardstyle")
        shadowOf(Looper.getMainLooper()).idle()
        val bars = findAll(overlay, SeekBar::class.java)
        assertEquals("卡片风格页应该有 2 个滑块（透明度 / 圆角）", 2, bars.size)
    }

    @Test
    fun 拖动滑块会落盘并通知桌面() {
        val a = activity()
        val prefs = Prefs(a.applicationContext)
        prefs.cardOpacity = 100
        prefs.cardRadius = 28
        val overlay = SettingsOverlay(a, prefs, Palettes.night)
        overlay.showPageKey("cardstyle")
        shadowOf(Looper.getMainLooper()).idle()
        var changed = 0
        overlay.onCardStyleChange = { changed++ }
        val bars = findAll(overlay, SeekBar::class.java)
        // 第 0 个是透明度（20..100，步长 5）→ 60 对应 progress = (60-20)/5 = 8
        userSeek(bars[0], 8)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("透明度要落盘", 60, prefs.cardOpacity)
        assertTrue("要通知桌面生效", changed > 0)
        // 第 1 个是圆角（0..48，步长 2）→ 12 对应 progress = 12/2 = 6
        userSeek(bars[1], 6)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("圆角要落盘", 12, prefs.cardRadius)
    }

    @Test
    fun 桌面应用后全局token跟着变() {
        val a = activity()
        val prefs = Prefs(a.applicationContext)
        prefs.cardOpacity = 60
        prefs.cardRadius = 12
        prefs.cardColor = 0
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(root)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)

        call(launcher!!, "applyCardStyle")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("卡片圆角要生效", 12f, Shape.CARD, 0.01f)
        assertEquals("内嵌块圆角要等比缩放", 12f * 18f / 28f, Shape.INNER, 0.01f)
        assertEquals(
            "卡片面的 alpha 要按透明度缩放",
            0x99,
            (Skin.surface ushr 24) and 0xFF,
        )
    }

    @Test
    fun 恢复跟随主题后颜色回到主题值() {
        val a = activity()
        val prefs = Prefs(a.applicationContext)
        prefs.cardColor = 0xFF3A6EA5.toInt()
        val overlay = SettingsOverlay(a, prefs, Palettes.night)
        overlay.showPageKey("cardstyle")
        shadowOf(Looper.getMainLooper()).idle()
        // 页面上的「恢复跟随主题颜色」按钮
        val buttons = findAll(overlay, android.widget.TextView::class.java)
            .filter { it.text.toString() == "恢复跟随主题颜色" }
        assertEquals("应该有一个恢复按钮", 1, buttons.size)
        buttons[0].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("颜色要回到跟随主题（0）", 0, prefs.cardColor)
    }
}
