package com.cardesk.launcher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 应用抽屉 UI 重做（第152轮）。
 *
 * ## 用户反馈
 *
 * > 「优化一下应用抽屉的 UI，现在太混乱了，三个点离图标太远，
 * > 把三个点的菜单改成长按图标弹出，去掉长按排序的功能，以及现在的三个点」
 *
 * ## 这条测试锁住四件事
 *
 * ① **格子右上角没有「⋮」了** —— 它贴在 300 宽格子的右沿，离自己的图标 242px，
 *    远得像下一个应用的按钮，满屏「图标 ⋮ 图标 ⋮」就是「太混乱」的根源；
 * ② **长按图标能弹出应用菜单**（菜单入口从按钮挪到了图标上）；
 * ③ **长按不再拖动排序** —— 排序偏好一个字节都不该被改；
 * ④ **图标在格子里居中、名称列加宽** —— 别再贴左沿、别再把应用名截成省略号。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DrawerUiTest {

    private val pkg = "com.example.demo"
    private val cls = "com.example.demo.MainActivity"
    private val demoLabel = "示例应用"
    private val moreGlyph = "⋮"

    private fun activity(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    private fun injectLauncherApp(a: MainActivity) {
        val component = ComponentName(pkg, cls)
        shadowOf(a.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = pkg
                name = cls
                exported = true
                enabled = true
                nonLocalizedLabel = demoLabel
            },
        )
        shadowOf(a.packageManager).addIntentFilterForActivity(
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

    /** 抽屉页面（摆好布局，能量到真实位置）。 */
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

    /** 找到「示例应用」那个格子（图标列，长按弹菜单的宿主）。 */
    private fun cellOf(page: View, label: String = demoLabel): View {
        var node: View? = text(page, label)
        while (node != null) {
            if (node.tag === DrawerPage.APP_CELL_MARK) return node
            node = node.parent as? View
        }
        error("找不到「$label」格子的图标列")
    }

    // ------------------------------------------------------------ ① 没有三个点了

    @Test
    fun `抽屉里没有三个点了`() {
        val a = activity()
        injectLauncherApp(a)
        val page = drawer(a)
        assertTrue(
            "抽屉里不该再有「⋮」按钮 —— 它离自己的图标 242px，看着像下一个应用的",
            findAll(page, TextView::class.java).none { it.text.toString() == moreGlyph },
        )
    }

    @Test
    fun `每个格子都只有一个图标和一个名称_没有多余的按钮`() {
        val a = activity()
        injectLauncherApp(a)
        val page = drawer(a)
        // 抽样一个格子：里面的可点击子 view 应该只有「图标列」一个。
        //
        // 注意查的必须是**格子容器**（body 的父级 FrameLayout），不是 body 本身 ——
        // 「⋮」按钮当初就是挂在格子容器上的（body 的兄弟节点），
        // 只查 body 的子树的话，那个按钮根本看不见，这条断言就成了摆设。
        val body = cellOf(page)
        val host = body.parent as ViewGroup
        val clickable = findAll(host, View::class.java).filter { it.isClickable }
        assertEquals(
            "格子里只该剩「图标列」一个可点区域（⋮ 删了之后不该有第二个）",
            1,
            clickable.size,
        )
    }

    // ------------------------------------------------------------ ② 长按弹菜单

    @Test
    fun `长按图标弹出应用菜单`() {
        val a = activity()
        injectLauncherApp(a)
        Prefs(a.applicationContext).setAppAlias(pkg, "")
        val page = drawer(a)

        cellOf(page).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("长按图标应弹出菜单", findAll(page, TextView::class.java).firstOrNull { it.text == "重命名" })
        assertNotNull("菜单应包含应用信息", findAll(page, TextView::class.java).firstOrNull { it.text == "应用信息" })
        assertNotNull("菜单应包含取消", findAll(page, TextView::class.java).firstOrNull { it.text == "取消" })
    }

    // ------------------------------------------------------------ ③ 不再拖动排序

    @Test
    fun `长按不会改写应用排序`() {
        val a = activity()
        injectLauncherApp(a)
        val prefs = Prefs(a.applicationContext)
        // 放一个已知的排序，长按之后必须一个字节都不变。
        prefs.appOrder = listOf(pkg, "com.example.other")
        val before = prefs.appOrder

        val page = drawer(a)
        cellOf(page).performLongClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "长按只该弹菜单，不该再改写排序偏好",
            before,
            prefs.appOrder,
        )
    }

    @Test
    fun `底部提示不再说拖动排序`() {
        val a = activity()
        injectLauncherApp(a)
        val page = drawer(a)
        val foot = findAll(page, TextView::class.java).firstOrNull { it.text.toString().contains("点按图标启动") }
        assertNotNull("底部提示应该在", foot)
        val s = foot!!.text.toString()
        assertFalse("提示不该再提「拖动排序」：$s", s.contains("拖动排序"))
        assertFalse("提示不该再提「⋮」：$s", s.contains(moreGlyph))
        assertTrue("提示该说清楚长按干什么：$s", s.contains("长按"))
    }

    // ------------------------------------------------------------ ④ 布局不再挤

    @Test
    fun `应用名列加宽了_不再截成一串省略号`() {
        val a = activity()
        injectLauncherApp(a)
        val page = drawer(a)
        val label = text(page, demoLabel)
        assertTrue(
            "名称列宽 ${label.width}，比原来的 76 还窄的话等于没改",
            label.width >= 140,
        )
    }

    @Test
    fun `图标在格子里居中_不是贴左沿`() {
        val a = activity()
        injectLauncherApp(a)
        val page = drawer(a)
        val cell = cellOf(page)
        // 注意：body 自己就是 LinearLayout，所以不能拿 findAll 的第一个 ——
        // 它先序遍历会先返回 body 自己。图标列是 body 的**子** view。
        val column = (cell as ViewGroup).getChildAt(0)
        // 格子 300 宽、名称列 160 宽 → 居中时左边距约 70；贴左沿则是 0。
        val offset = column.left
        assertTrue(
            "图标列左边距只有 $offset —— 还是贴左沿，没有居中",
            offset in 40..110,
        )
        assertEquals(
            "图标列应该在格子里居中",
            Gravity.CENTER_HORIZONTAL,
            (cell as LinearLayout).gravity and Gravity.CENTER_HORIZONTAL,
        )
    }

    @Test
    fun `图标本身还是76_没被改小`() {
        val a = activity()
        injectLauncherApp(a)
        val page = drawer(a)
        val icon = findAll(cellOf(page), ImageView::class.java).first()
        assertEquals("图标边长不该动", DrawerPage.ICON, icon.width)
    }
}
