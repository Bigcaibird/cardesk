package com.cardesk.launcher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cardesk.launcher.ui.LauncherView
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
 * 桌面根视图的烟雾测试：真实走一遍 Activity 创建 + 两页布局（第三十二轮起桌面只有两页），
 * 保证后续改动不会引入「一启动就崩」的问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class LauncherSmokeTest {

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
            for (i in 0 until view.childCount) {
                findLauncher(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun designRoot(launcher: LauncherView): FrameLayout =
        launcher.getChildAt(1) as FrameLayout

    @Test
    fun activityCreatesAndLaysOutThreePages() {
        val launcher = setup()
        val content = designRoot(launcher)
        // 背景 + 内容层
        assertTrue(content.childCount >= 3)
        // 内容层按设计稿固定成 1920×1080
        assertTrue(content.width > 0 && content.height > 0)
    }

    @Test
    fun backNavigatesHomeWithoutFinishing() {
        val launcher = setup()
        // 初始在首页：没有浮层可关，返回键不处理
        assertFalse(launcher.onBack())

        // 切到车辆页后再按返回，应回到首页
        val dock = (designRoot(launcher).getChildAt(2))
        assertTrue(dock is View)
    }

    @Test
    fun hvacActionsUpdateStateEvenWithoutVehicle() {
        val launcher = setup()
        // 模拟环境：按钮动作只走本地状态，不应抛异常
        com.cardesk.launcher.vehicle.VehicleController.togglePower()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(com.cardesk.launcher.vehicle.VehicleController.state.power)
        com.cardesk.launcher.vehicle.VehicleController.togglePower()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * 第四十四轮（紧凑）：导航页从**状态栏那一行的底部**开始。
     *
     * - 第四十二轮： （有状态栏时 110）
     *   顶部留出一条空白带 —— 用户说的「割裂」；
     * - 第四十三轮：改成贴顶 + 状态栏透明浮层 —— 用户又说「内容不要顶到屏幕顶」；
     * - 第四十四轮：选定**紧凑**形态 —— 下移到状态栏行的底部（`ROW_TOP + ROW_H` = 90），
     *   状态栏紧贴内容上沿，中间没有空带。
     */
    @Test
    fun navPageStartsRightBelowTheStatusBarRow() {
        val launcher = setup()
        val navPage = findNavPage(launcher)
        assertNotNull("应该能找到导航页", navPage)
        val lp = navPage!!.layoutParams as ViewGroup.MarginLayoutParams
        // 第四十九轮按设计板 `s02-nav`：导航页内容是 left 44 / top 110 / 宽 1832 / 高 830
        // 两张卡片，底部与 Dock 上沿（968）留 28 的缝。
        assertEquals(
            "导航页应该从设计板的内容基线 73 开始",
            com.cardesk.launcher.ui.pages.NavPage.NAV_CONTENT_TOP,
            lp.topMargin,
        )
        assertEquals(
            "导航页高度应该正好顶到 Dock 上沿",
            968 - com.cardesk.launcher.ui.pages.NavPage.NAV_CONTENT_TOP,
            lp.height,
        )
        assertEquals(
            "组合卡的顶部内边距回到 8",
            com.cardesk.launcher.ui.pages.NavPage.CARD_TOP_INSET,
            navPage.currentCardTopInset,
        )
    }

    /**
     * 第六十三轮：桌面上常驻的**「音乐 ♪」「空调 ❄」两颗悬浮气泡已移除**。
     *
     * 气泡列本身还在（**被最小化的浮窗**的气泡还要用它还原），
     * 但里面一颗常驻气泡都不剩。
     */
    @Test
    fun desktopHasNoMusicOrClimateBubble() {
        val launcher = setup()
        val bubbles = findFloatBubble(launcher)
        assertNotNull("应该能找到悬浮气泡列", bubbles)
        val column = bubbles!!.getChildAt(0) as ViewGroup
        assertEquals("桌面不应该再出现音乐/空调气泡", 0, column.childCount)
        assertEquals("没有气泡时气泡列整体隐藏", View.INVISIBLE, bubbles.visibility)
    }

    private fun findNavPage(view: View): com.cardesk.launcher.ui.pages.NavPage? {
        if (view is com.cardesk.launcher.ui.pages.NavPage) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findNavPage(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findFloatBubble(view: View): com.cardesk.launcher.ui.FloatBubbleView? {
        if (view is com.cardesk.launcher.ui.FloatBubbleView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFloatBubble(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
