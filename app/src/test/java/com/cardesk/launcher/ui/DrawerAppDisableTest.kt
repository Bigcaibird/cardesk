package com.cardesk.launcher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
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
 * 应用抽屉「禁用 / 启用」（第九十四轮）回归。
 *
 * 钉住三件事：
 * 1. 已禁用的应用仍出现在抽屉里，但图标压暗、名称用弱色，并标「已禁用」；
 * 2. 禁用应用的应用菜单给的是「启用应用」，可用应用给的是「禁用应用」；
 * 3. 点已禁用应用不会去启动它，而是打开菜单（唯一的恢复入口）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DrawerAppDisableTest {

    private val pkg = "com.example.demo"
    private val cls = "com.example.demo.MainActivity"
    private val demoLabel = "示例应用"

    private fun activity(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun injectLauncherApp(a: MainActivity, enabled: Boolean) {
        val component = ComponentName(pkg, cls)
        val shadow = shadowOf(a.packageManager)
        shadow.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = pkg
                name = cls
                exported = true
                this.enabled = enabled
                nonLocalizedLabel = demoLabel
            },
        )
        shadow.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun <T> findAll(root: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        if (type.isInstance(root)) @Suppress("UNCHECKED_CAST") out.add(root as T)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) out.addAll(findAll(root.getChildAt(i), type))
        }
        return out
    }

    private fun text(root: View, value: String): TextView =
        findAll(root, TextView::class.java).first { it.text.toString() == value }

    private fun drawer(a: MainActivity): DrawerPage {
        val page = DrawerPage(a, Palettes.night)
        a.setContentView(page)
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return page
    }

    /**
     * 找到指定显示名那个**应用格子**（长按弹菜单的宿主）。
     *
     * 第152轮：菜单入口从「右上角 ⋮ 按钮」改成了「**长按图标**」，
     * 所以这里改成往上找带 [DrawerPage.APP_CELL_MARK] 标记的祖先。
     */
    private fun menuButtonOf(page: View, label: String): View {
        var node: View? = text(page, label)
        while (node != null) {
            if (node.tag === DrawerPage.APP_CELL_MARK) return node
            node = node.parent as? View
        }
        error("找不到「$label」格子的图标列")
    }

    @Test
    fun 禁用应用仍在抽屉里并显示已禁用标记() {
        val a = activity()
        injectLauncherApp(a, enabled = false)
        Prefs(a.applicationContext).setAppAlias(pkg, "")
        val page = drawer(a)

        assertNotNull("禁用应用的名称应仍可见", findAll(page, TextView::class.java).firstOrNull { it.text == demoLabel })
        assertNotNull("禁用应用应有「已禁用」标记", findAll(page, TextView::class.java).firstOrNull { it.text == "已禁用" })

        // 图标压暗：找到示例应用格子里的 ImageView，alpha 应小于 1。
        var node: View? = text(page, demoLabel)
        var iconAlpha = 1f
        while (node != null) {
            val icon = findAll(node, ImageView::class.java).firstOrNull()
            if (icon != null) {
                iconAlpha = icon.alpha
                break
            }
            node = node.parent as? View
        }
        assertEquals("禁用应用图标应压暗", 0.35f, iconAlpha, 0.001f)
    }

    @Test
    fun 禁用应用的菜单给的是启用应用() {
        val a = activity()
        injectLauncherApp(a, enabled = false)
        Prefs(a.applicationContext).setAppAlias(pkg, "")
        val page = drawer(a)

        menuButtonOf(page, demoLabel).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("禁用应用菜单应含「启用应用」", findAll(page, TextView::class.java).firstOrNull { it.text == "启用应用" })
        assertTrue("禁用应用菜单不应再给「禁用应用」", findAll(page, TextView::class.java).none { it.text == "禁用应用" })
    }

    @Test
    fun 可用应用的菜单给的是禁用应用() {
        val a = activity()
        injectLauncherApp(a, enabled = true)
        Prefs(a.applicationContext).setAppAlias(pkg, "")
        val page = drawer(a)

        menuButtonOf(page, demoLabel).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("可用应用菜单应含「禁用应用」", findAll(page, TextView::class.java).firstOrNull { it.text == "禁用应用" })
    }

    @Test
    fun 点已禁用应用打开菜单而不是启动() {
        val a = activity()
        injectLauncherApp(a, enabled = false)
        Prefs(a.applicationContext).setAppAlias(pkg, "")
        val page = drawer(a)

        // 点击落在格子的 body（可点击层）上；名称 TextView 自己不接点击。
        var clickable: View? = text(page, demoLabel)
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent as? View
        assertNotNull("示例应用格子应有可点击层", clickable)
        clickable!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("点禁用应用应打开菜单（里面有启用应用）", findAll(page, TextView::class.java).firstOrNull { it.text == "启用应用" })
        val started = shadowOf(a).nextStartedActivity
        assertTrue("已禁用应用不应被启动", started == null || started.component?.packageName != pkg)
    }
}
