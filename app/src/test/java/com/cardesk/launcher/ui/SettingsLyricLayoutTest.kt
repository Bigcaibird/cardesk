package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.media.LyricLayout
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.ui.widget.LyricScalePreviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 第 117 轮：**首页中央歌词的布局可切**（设置 → 桌面设置 → 仪表歌词 → 首页中央歌词）。
 *
 * 这条用例走的是**真的点击路径**：
 * 点设置页里的「KTV 两行」→ 偏好落盘 → 设置页预览条跟着变 → 桌面也跟着变。
 *
 * 为什么非要这么测：这三处是**分开的三个对象**
 * （`Prefs` / `LyricScalePreviewView` / `ScenePage`），
 * 任何一处漏接线，用户看到的都是「设置改了但画面没变」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsLyricLayoutTest {

    @Test
    fun 点KTV两行会落盘并让预览与桌面都跟着变() {
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
        // 翻到「桌面设置」那一页（歌词设置在里面）。
        (overlay as SettingsOverlay).showPageKey("layout")
        shadowOf(Looper.getMainLooper()).idle()

        // 建页时的说明文案 = 三行的说明
        assertNotNull(
            "三行布局的说明应该在页面上",
            findText(overlay, LyricLayout.THREE.hint),
        )

        // —— 点「KTV 两行」——
        val chip = findText(overlay, "KTV 两行")
        assertNotNull("设置页里应该有「KTV 两行」这个选项", chip)
        chip!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // ④ 说明文案也跟着换（第117轮补的坑：
        //    说明只在建页时写了一次，切布局后还是三行那句话，用户会以为没切成功）
        assertNotNull(
            "切到 KTV 两行后，说明文案应该改成 KTV 的那句",
            findText(overlay, LyricLayout.KTV.hint),
        )

        // ① 偏好落盘
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        assertEquals("ktv", prefs.sceneLyricLayout)

        // ② 设置页的预览条跟着变
        val preview = findPreview(overlay)
        assertNotNull("设置页里应该有歌词预览条", preview)
        assertEquals(LyricLayout.KTV, preview!!.layout)

        // ③ 桌面也跟着变（设置页 → onSceneLyricChanged → ScenePage）
        val scene = findField<ScenePage>(launcher, "scenePage")
        assertNotNull("应该能找到场景页", scene)
        assertEquals(LyricLayout.KTV, scene!!.lyricLayout)
    }

    @Test
    fun 默认是三行_切回三行也能落盘() {
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
        val overlay = findField<SettingsOverlay>(launcher, "settings")!!
        overlay.showPageKey("layout")
        shadowOf(Looper.getMainLooper()).idle()

        // 默认就该是三行
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        assertEquals("three", prefs.sceneLyricLayout)
        assertNotNull("默认说明应是三行那句", findText(overlay, LyricLayout.THREE.hint))
        assertEquals(LyricLayout.THREE, findPreview(overlay)!!.layout)
        assertEquals(LyricLayout.THREE, findField<ScenePage>(launcher, "scenePage")!!.lyricLayout)

        // 先切到 KTV 再切回来
        findText(overlay, "KTV 两行")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ktv", prefs.sceneLyricLayout)

        findText(overlay, "三行")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("three", prefs.sceneLyricLayout)
        assertEquals(LyricLayout.THREE, findPreview(overlay)!!.layout)
        assertEquals(LyricLayout.THREE, findField<ScenePage>(launcher, "scenePage")!!.lyricLayout)
    }

    // ------------------------------------------------------------ 小工具

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 在设置页的视图树里找文字等于 [text] 的那个控件。 */
    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    /** 在设置页的视图树里找歌词预览条。 */
    private fun findPreview(root: View): LyricScalePreviewView? {
        if (root is LyricScalePreviewView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findPreview(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private inline fun <reified T> findField(target: Any, name: String): T? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target) as T?
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
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
