package com.cardesk.launcher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 抽屉浮层的生命周期（第152轮真机发现的 bug）。
 *
 * ## 现象
 *
 * 抽屉页是 [LauncherView] **长期持有的同一个实例** ——
 * 打开是 `content.addView(drawerPage)`，关闭是 `content.removeView(drawerPage)`。
 * 摘掉视图**不会**清掉实例内部挂着的浮层（应用菜单 / 重命名 / 禁用确认）。
 *
 * 于是：
 *
 * 1. 长按图标弹出应用菜单；
 * 2. 按返回键 —— 抽屉整个关掉，**菜单留在实例里**；
 * 3. 再打开抽屉 —— 菜单又盖在上面，它的遮罩（`MATCH_PARENT` + `isClickable`）
 *    把所有点击都吃掉，**点图标一点反应都没有**（第一次点只是把菜单关掉）。
 *
 * ## 修法
 *
 * - [DrawerPage.clearOverlays]：抽屉一关就把浮层全清掉；
 * - [DrawerPage.closeTopOverlay]：返回键**先关浮层、再关抽屉**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DrawerOverlayTest {

    private val pkg = "com.example.demo"
    private val cls = "com.example.demo.MainActivity"
    private val demoLabel = "示例应用"

    private fun activity(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    /** 注入一个示例应用，保证抽屉里至少有一个格子。 */
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

    private fun texts(root: View): List<String> =
        findAll(root, TextView::class.java).map { it.text.toString() }

    /** 菜单 / 弹窗上那些一眼能认出来的字。 */
    private fun overlayTexts(root: View): List<String> =
        texts(root).filter { it in setOf("重命名", "应用信息", "禁用应用", "启用应用", "卸载", "取消") }

    // ------------------------------------------------------------ LauncherView 层

    private fun launcherOf(a: MainActivity): LauncherView {
        val root = a.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return findByType(root, LauncherView::class.java) ?: error("窗口里找不到 LauncherView")
    }

    private fun <T : View> findByType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findByType(view.getChildAt(i), type)?.let { return it }
        }
        return null
    }

    /** 反射调用 LauncherView 的私有方法。 */
    private fun call(launcher: LauncherView, name: String) {
        launcher.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(launcher)
    }

    /** 反射取 LauncherView 的私有字段。 */
    private fun drawerOf(launcher: LauncherView): DrawerPage {
        val f = LauncherView::class.java.getDeclaredField("drawerPage")
        f.isAccessible = true
        return f.get(launcher) as DrawerPage
    }

    /** 在抽屉里长按第一个格子，弹出应用菜单。 */
    private fun longPressFirstCell(drawer: DrawerPage): View {
        val cell = findAll(drawer, View::class.java).firstOrNull { it.tag === DrawerPage.APP_CELL_MARK }
            ?: error("抽屉里一个格子都没有")
        cell.performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        return cell
    }

    @Test
    fun `关抽屉后应用菜单不能残留`() {
        val a = activity()
        injectLauncherApp(a)
        val launcher = launcherOf(a)
        val drawer = drawerOf(launcher)

        call(launcher, "showDrawer")
        shadowOf(Looper.getMainLooper()).idle()
        longPressFirstCell(drawer)
        assertTrue("长按应该弹出菜单", overlayTexts(drawer).contains("重命名"))

        call(launcher, "closeDrawer")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "关抽屉后菜单必须清掉 —— 它会跟着抽屉页的实例留到下一次打开，" +
                "那时候菜单的遮罩会把所有点击吃掉，点图标一点反应都没有",
            overlayTexts(drawer).isEmpty(),
        )
    }

    @Test
    fun `重开抽屉不能带着上次的菜单`() {
        val a = activity()
        injectLauncherApp(a)
        val launcher = launcherOf(a)
        val drawer = drawerOf(launcher)

        call(launcher, "showDrawer")
        shadowOf(Looper.getMainLooper()).idle()
        longPressFirstCell(drawer)
        call(launcher, "closeDrawer")
        shadowOf(Looper.getMainLooper()).idle()

        call(launcher, "showDrawer")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "重开抽屉时不该还有菜单浮层",
            overlayTexts(drawer).isEmpty(),
        )
        assertTrue(
            "抽屉本身要正常打开（应用名还在）",
            texts(drawer).contains(demoLabel),
        )
    }

    @Test
    fun `返回键先关菜单_不关抽屉`() {
        val a = activity()
        injectLauncherApp(a)
        val launcher = launcherOf(a)
        val drawer = drawerOf(launcher)

        call(launcher, "showDrawer")
        shadowOf(Looper.getMainLooper()).idle()
        longPressFirstCell(drawer)
        assertTrue("长按应该弹出菜单", overlayTexts(drawer).contains("重命名"))

        launcher.onBack()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("返回键应该先关菜单", overlayTexts(drawer).isEmpty())
        assertTrue(
            "菜单关掉时**抽屉不该跟着关** —— 用户只是要退出菜单",
            drawer.parent != null,
        )
    }

    @Test
    fun `没有浮层时返回键才关抽屉`() {
        val a = activity()
        injectLauncherApp(a)
        val launcher = launcherOf(a)
        val drawer = drawerOf(launcher)

        call(launcher, "showDrawer")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("抽屉该是打开的", drawer.parent != null)

        launcher.onBack()
        // 关抽屉有 240ms 的下滑动画，推进时间让回调跑完。
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("没有浮层时返回键应该把抽屉关掉", drawer.parent)
    }

    // ------------------------------------------------------------ DrawerPage 层

    @Test
    fun `没有浮层时closeTopOverlay返回false`() {
        val a = activity()
        injectLauncherApp(a)
        val drawer = DrawerPage(a, com.cardesk.launcher.core.Palettes.night)
        a.setContentView(drawer)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("本来就没有浮层，不该关掉任何东西", drawer.closeTopOverlay())
    }

    @Test
    fun `clearOverlays把菜单和弹窗都清掉`() {
        val a = activity()
        injectLauncherApp(a)
        val drawer = DrawerPage(a, com.cardesk.launcher.core.Palettes.night)
        a.setContentView(drawer)
        shadowOf(Looper.getMainLooper()).idle()

        longPressFirstCell(drawer)
        assertTrue("长按应该弹出菜单", overlayTexts(drawer).contains("重命名"))

        // 从菜单点「重命名」，再弹一层重命名弹窗。
        findAll(drawer, TextView::class.java).first { it.text == "重命名" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("应该弹出重命名弹窗", findAll(drawer, TextView::class.java).firstOrNull { it.text == "重命名应用" })

        drawer.clearOverlays()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("重命名弹窗要清掉", findAll(drawer, TextView::class.java).firstOrNull { it.text == "重命名应用" })
        assertTrue("应用菜单也要清掉", overlayTexts(drawer).isEmpty())
    }
}
