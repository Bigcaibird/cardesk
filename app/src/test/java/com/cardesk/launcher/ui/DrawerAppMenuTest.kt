package com.cardesk.launcher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 应用抽屉的应用菜单（第九十五轮 C5）回归。
 *
 * 钉住三件事：
 * 1. 格子右上角「⋮」菜单能打开（重命名 / 应用信息 / 卸载 / 取消）；
 * 2. 重命名写入 `launcher_app_alias`，抽屉立刻用别名显示；
 * 3. 别名留空 = 恢复系统名称。
 *
 * 注意：Robolectric 里卡面自己的 MainActivity 也是 LAUNCHER 应用，页面里会出现两个
 * 「⋮」，所以必须按「示例应用」格子的祖先去取 ⋮，不能取页面里第一个。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DrawerAppMenuTest {

    private val pkg = "com.example.demo"
    private val cls = "com.example.demo.MainActivity"
    private val demoLabel = "示例应用"
    private val moreGlyph = "\u22EE"

    private fun activity(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun injectLauncherApp(a: MainActivity) {
        val component = ComponentName(pkg, cls)
        val shadow = shadowOf(a.packageManager)
        shadow.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = pkg
                name = cls
                exported = true
                enabled = true
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

    /** 找到指定显示名那个格子的「⋮」按钮。 */
    private fun menuButtonOfDemo(page: View, label: String = demoLabel): TextView {
        var node: View? = text(page, label)
        while (node != null) {
            val button = findAll(node, TextView::class.java).firstOrNull { it.text.toString() == moreGlyph }
            if (button != null) return button
            node = node.parent as? View
        }
        error("找不到示例应用格子的 ⋮ 按钮")
    }

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

    @Test
    fun 应用菜单可打开且含四个动作() {
        val a = activity()
        injectLauncherApp(a)
        val prefs = Prefs(a.applicationContext)
        prefs.setAppAlias(pkg, "")
        val page = drawer(a)
        val grid = findAll(page, GridLayout::class.java).first()
        assertTrue("注入的示例应用应该出现在抽屉里", grid.childCount >= 1)

        menuButtonOfDemo(page).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("菜单应包含重命名", findAll(page, TextView::class.java).firstOrNull { it.text == "重命名" })
        assertNotNull("菜单应包含应用信息", findAll(page, TextView::class.java).firstOrNull { it.text == "应用信息" })
        assertNotNull("菜单应包含卸载（非系统应用）", findAll(page, TextView::class.java).firstOrNull { it.text == "卸载" })
        assertNotNull("菜单应包含取消", findAll(page, TextView::class.java).firstOrNull { it.text == "取消" })
    }

    @Test
    fun 重命名写入别名并立刻生效() {
        val a = activity()
        injectLauncherApp(a)
        val prefs = Prefs(a.applicationContext)
        prefs.setAppAlias(pkg, "")
        val page = drawer(a)

        menuButtonOfDemo(page).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        findAll(page, TextView::class.java).first { it.text == "重命名" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val input = findAll(page, EditText::class.java).first()
        input.setText("我的示例")
        text(page, "保存").performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("我的示例", prefs.appAliasOf(pkg))
        assertNotNull("抽屉应立刻显示别名", findAll(page, TextView::class.java).firstOrNull { it.text == "我的示例" })
    }

    @Test
    fun 别名留空恢复系统名称() {
        val a = activity()
        injectLauncherApp(a)
        val prefs = Prefs(a.applicationContext)
        prefs.setAppAlias(pkg, "旧别名")
        val page = drawer(a)
        assertNotNull("应先显示旧别名", findAll(page, TextView::class.java).firstOrNull { it.text == "旧别名" })

        menuButtonOfDemo(page, "旧别名").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        findAll(page, TextView::class.java).first { it.text == "重命名" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        findAll(page, EditText::class.java).first().setText("")
        text(page, "保存").performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("空别名应清掉持久化记录", prefs.appAliasOf(pkg))
        assertTrue("抽屉不应再显示旧别名", findAll(page, TextView::class.java).none { it.text == "旧别名" })
    }
}
