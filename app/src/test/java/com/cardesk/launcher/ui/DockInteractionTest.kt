package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.shell.DockBarView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Dock 键的交互回归（第五十六轮）。
 *
 * 真机反馈的三个问题都在这里钉住：
 * 1. 抽屉开着的时候点 Dock 的 **home 键没反应**（浮层还盖在上面）；
 * 2. 抽屉只能点抽屉以外的地方关，**点 Dock 的「应用」键关不掉**；
 * 3. 设置里勾「隐藏原车 Dock」，消失的却是**桌面自己的** Dock。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DockInteractionTest {

    private fun setup(): LauncherView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(root)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        return launcher!!
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

    /** 把关闭动画（240ms）跑完。 */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun appsKeyOpensThenClosesDrawer() {
        val launcher = setup()
        val dock = findField<DockBarView>(launcher, "dock")
        assertNotNull("LauncherView 里应该有 dock", dock)
        val drawer = findField<DrawerPage>(launcher, "drawerPage")
        assertNotNull("LauncherView 里应该有 drawerPage", drawer)

        // 第一次点：打开
        dock!!.onAppsDrawer?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("点「应用」键应该打开抽屉", drawer!!.parent)

        // 第二次点：**关掉**（以前这里是 no-op，只能点抽屉外面关）
        dock.onAppsDrawer?.invoke()
        settle()
        assertTrue("再点一次「应用」键应该关闭抽屉", drawer.parent == null)
    }

    @Test
    fun homeKeyClosesDrawerBeforeGoingHome() {
        val launcher = setup()
        val dock = findField<DockBarView>(launcher, "dock")
        val drawer = findField<DrawerPage>(launcher, "drawerPage")

        dock!!.onAppsDrawer?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("先打开抽屉", drawer!!.parent)

        // 抽屉开着点 home：以前只切页面、抽屉还盖着 → 用户看到「home 键不生效」
        dock.onHome?.invoke()
        settle()
        assertTrue("点 home 键应该把抽屉收掉", drawer.parent == null)
    }

    @Test
    fun hidingOemDockDoesNotHideOurDock() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val prefs = Prefs(activity.applicationContext)
        // 「隐藏原车 Dock」打开 —— 只该影响原车 Dock，不该动桌面自己的 Dock
        prefs.hideDock = true
        prefs.hideHomeDock = false

        val launcher = setup()
        val dock = findField<DockBarView>(launcher, "dock")
        assertNotNull(dock)
        assertFalse(
            "勾「隐藏原车 Dock」不该把桌面自己的 Dock 一起隐藏",
            dock!!.visibility == View.GONE,
        )
    }

    @Test
    fun hidingHomeDockHidesOurDockOnly() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val prefs = Prefs(activity.applicationContext)
        // 「隐藏桌面 Dock」打开 —— 只该隐藏我们自己的那一栏
        prefs.hideHomeDock = true
        prefs.hideDock = false

        val launcher = setup()
        val dock = findField<DockBarView>(launcher, "dock")
        assertNotNull(dock)
        assertTrue("勾「隐藏桌面 Dock」应该把桌面 Dock 隐藏", dock!!.visibility == View.GONE)
    }

    @Test
    fun immersiveNavigationDefaultsOnAndHomeDockHideDefaultsOff() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val prefs = Prefs(activity.applicationContext)
        assertTrue("「沉浸系统导航栏」默认开（系统 Dock 只在底部上滑时唤起）", prefs.oemNavImmersive)
        assertFalse("「隐藏桌面 Dock」默认关", prefs.hideHomeDock)
    }
}
