package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.shell.DockBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 画中画全屏把 Dock「切屏」按钮废掉（第167轮）。
 *
 * ## 复现路径（真机 2026-11-16，诊断包 cardesk-logs-20261116-115454）
 *
 * ```
 * 11:25:22.405  画中画进入全屏           ← 高德铺满 1920×1080，盖住根容器
 * 11:28:00.119  切页 from=1 to=0          ← Dock 分屏键：逻辑切页成功
 * ...（11:28~11:37 共 22 次，每次都「切页成功」，用户眼前一个像素没变）
 * 11:37:44.673  画中画全屏切换 to=还原    ← 改点小 chevron，画面才终于恢复
 * ```
 *
 * 根因：画中画全屏时承载面被 `bringChildToFront` 提到**根容器最上层**，
 * 而 [LauncherView.showPage] 只切页面索引、**不还原全屏层** ——
 * 于是用户看到的就是「切屏按钮点了没反应」。
 *
 * 这里把三条漏网的路钉住：
 * 1. Dock 分屏键（切页）必须先还原全屏；
 * 2. Dock 应用键（抽屉）必须先还原全屏（抽屉插在状态栏之前，会被全屏层压住）；
 * 3. 外部 HOME（全局 Dock 主页键 / 系统 HOME）必须先还原全屏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class PipFullscreenBlocksPageSwitchTest {

    private lateinit var activity: MainActivity
    private lateinit var launcher: LauncherView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val found = findLauncher(root)
        assertNotNull("LauncherView 应该挂在窗口里", found)
        launcher = found!!
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private inline fun <reified T> field(name: String): T? {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(launcher) as? T
    }

    private fun getField(name: String): Any? {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(launcher)
    }

    private fun setField(name: String, value: Any?) {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(launcher, value)
    }

    private fun invoke(name: String): Any? {
        val f = LauncherView::class.java.getDeclaredMethod(name)
        f.isAccessible = true
        return f.invoke(launcher)
    }

    /** 把画中画推进全屏态（跟用户点小 chevron 走同一条路）。 */
    private fun enterPipFullscreen() {
        val prefs = Prefs(activity.applicationContext)
        prefs.pipApp = "com.autonavi.amapauto"
        invoke("togglePipFullscreen")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("前置条件：画中画应该进入全屏态", getField("pipFullscreen") as Boolean)
    }

    /**
     * 先切到画中画页（真机里全屏是从画中画页的小 chevron 进的）。
     *
     * 这里**直接置页号**而不是调 `showPage(1)`：
     * `showPage` 会走 `refreshPip()` → 真去承载画中画应用 →
     * 那条路径会不停往主 Looper 投消息，Robolectric 的 `idle()` 会永远等不到空闲。
     * 本用例只关心「全屏层盖住切页」，不需要真的把应用拉起来。
     */
    private fun switchToNavPage() {
        setField("page", 1)
        assertEquals("前置条件：应该在画中画页", 1, currentPage())
    }

    private fun currentPage(): Int = getField("page") as Int

    private fun dock(): DockBarView {
        val d = field<DockBarView>("dock")
        assertNotNull("LauncherView 里应该有 dock", d)
        return d!!
    }

    /** 画中画承载面（导航页里的那个 PipSurface）。 */
    private fun pipView(): View {
        val navPage = field<Any>("navPage")
        assertNotNull("应该有导航页", navPage)
        val f = navPage!!::class.java.getDeclaredField("pip")
        f.isAccessible = true
        return f.get(navPage) as View
    }

    /**
     * 主回归：画中画全屏时点 Dock 分屏键 —— 必须先还原全屏，再切页。
     */
    @Test
    fun splitKeyExitsPipFullscreenBeforeSwitchingPage() {
        switchToNavPage()
        enterPipFullscreen()

        dock().onSplit?.invoke()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "Dock 分屏键切页前必须先还原画中画全屏，" +
                "否则全屏层盖在根容器最上层，用户眼前什么都没变",
            getField("pipFullscreen") as Boolean,
        )
        assertEquals("分屏键应该把页面切回桌面页", 0, currentPage())
    }

    /**
     * 切页后画中画要挂回导航页的地图卡，不能留在根容器里
     * （留在根容器 = 它还会盖住桌面页）。
     */
    @Test
    fun pipReturnsToMapCardAfterFullscreenExit() {
        switchToNavPage()
        enterPipFullscreen()
        val content = field<ViewGroup>("content")
        assertNotNull("应该有根容器", content)
        val pip = pipView()

        assertTrue(
            "前置条件：全屏时画中画应该挂在根容器上（盖住整屏）",
            pip.parent === content,
        )

        dock().onSplit?.invoke()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "还原后画中画应该回到导航页的地图卡里，不能还挂在根容器上",
            pip.parent !== content,
        )
    }

    /**
     * 画中画全屏时点 Dock 应用键 —— 抽屉插在状态栏之前，
     * 不还原就会被全屏层压在下面（用户看到「应用键没反应」）。
     */
    @Test
    fun appsKeyExitsPipFullscreenSoDrawerIsVisible() {
        switchToNavPage()
        enterPipFullscreen()
        val drawer = field<View>("drawerPage")
        assertNotNull("应该有抽屉页", drawer)

        dock().onAppsDrawer?.invoke()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "开抽屉前必须先还原画中画全屏，否则抽屉被全屏层压住看不见",
            getField("pipFullscreen") as Boolean,
        )
        assertNotNull("抽屉应该被打开（挂到容器上）", drawer!!.parent)
    }

    /**
     * 外部 HOME（全局 Dock 主页键 / 系统 HOME）—— 全屏时必须还原。
     *
     * 非全屏时**不动页号**（老行为：只把桌面拉到前台）。
     */
    @Test
    fun externalHomeExitsPipFullscreen() {
        switchToNavPage()
        enterPipFullscreen()
        launcher.handleExternalHome()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "外部 HOME 必须先还原画中画全屏",
            getField("pipFullscreen") as Boolean,
        )
    }

    @Test
    fun externalHomeKeepsPageWhenNotFullscreen() {
        // 非全屏：外部 HOME 不该改页号（老行为）
        val before = currentPage()
        launcher.handleExternalHome()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("非全屏时外部 HOME 不该动页号", before, currentPage())
    }

    /**
     * 切页的落盘日志要带上全屏态 —— 否则排查时只看到「切页成功」，
     * 完全看不出上面还压着一层全屏画中画。
     */
    @Test
    fun pageSwitchLogCarriesFullscreenFlag() {
        switchToNavPage()
        enterPipFullscreen()

        // 切页会被闸口拦成「先还原」，所以先确认全屏被还原掉
        dock().onSplit?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(getField("pipFullscreen") as Boolean)
        assertEquals("闸口应该把目标页一次切到位（不是先还原再切两次）", 0, currentPage())
    }
}
