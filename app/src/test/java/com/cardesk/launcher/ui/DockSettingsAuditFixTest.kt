package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.NavSlot
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.OemDockPolicy
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.dock.DockVisibility
import com.cardesk.launcher.core.dock.SystemNavigationPolicy
import com.cardesk.launcher.ui.shell.DockBarView
import com.cardesk.launcher.ui.shell.EnhancedNavBarView
import com.cardesk.launcher.ui.shell.NavSlotBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 「Dock 与导航栏」全量设置项审计与槽位编辑回归测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DockSettingsAuditFixTest {

    // --------------------------------------------------------- 1. 状态栏生效应用正向语义

    @Test
    fun `全局 Dock 下状态栏生效应用按正向名单生效`() {
        assertEquals(
            "immersive.status=com.a,com.b",
            SystemNavigationPolicy.merge(
                current = null,
                hideStatus = true,
                hideStatusPackages = setOf("com.b", "com.a"),
                navInsets = true,
                statusIncludeMode = true,
            ),
        )
        assertEquals(
            "immersive.status=*",
            SystemNavigationPolicy.merge(
                current = null,
                hideStatus = true,
                hideStatusPackages = emptySet(),
                navInsets = true,
                statusIncludeMode = true,
            ),
        )
        assertNull(
            "清空名单（NO_APPS 哨兵）时不应隐藏任何应用的状态栏",
            SystemNavigationPolicy.merge(
                current = null,
                hideStatus = true,
                hideStatusPackages = setOf(OemDockPolicy.NO_APPS),
                navInsets = true,
                statusIncludeMode = true,
            ),
        )
    }

    // --------------------------------------------------------- 2. 隐藏桌面 Dock 对全局 Dock 生效

    @Test
    fun `隐藏桌面 Dock 开启时桌面隐藏全局 Dock 但其他应用照常显示`() {
        assertFalse(
            "桌面在前台且开启「隐藏桌面 Dock」时，全局 Dock 应隐藏",
            DockVisibility.want(
                nativeInsets = true,
                homeForeground = true,
                immersiveAppForeground = false,
                hideHomeDock = true,
            ),
        )
        assertTrue(
            "切到其他应用时，全局 Dock 应照常显示",
            DockVisibility.want(
                nativeInsets = true,
                homeForeground = false,
                immersiveAppForeground = false,
                hideHomeDock = true,
            ),
        )
    }

    // --------------------------------------------------------- 3. 槽位条主页单击与超宽缩放

    @Test
    fun `槽位条主页槽单击能正常触发 onSlot`() {
        val ctx = RuntimeEnvironment.getApplication()
        val bar = NavSlotBarView(ctx).apply {
            slots = NavSlots.defaults()
        }
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(112, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 112)
        val bmp = Bitmap.createBitmap(1920, 112, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bmp))

        val homeRect = bar.hitRects()[NavSlot.ACTION_HOME]
        assertNotNull("主页槽应该注册了命中区", homeRect)

        var clicked: NavSlot? = null
        bar.onSlot = { clicked = it }

        val cx = homeRect!!.centerX()
        val cy = homeRect.centerY()
        val now = System.currentTimeMillis()
        bar.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0))
        bar.onTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, cx, cy, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NavSlotBarView.HOME_TAP_WINDOW_MS + 100))

        assertNotNull("主页槽单击应触发 onSlot（旧版因 downSlot 置空永远不触发）", clicked)
        assertEquals(NavSlot.ACTION_HOME, clicked?.actionId)
    }

    @Test
    fun `槽位条 20 个以上槽位自动缩放不超出屏幕宽度`() {
        val ctx = RuntimeEnvironment.getApplication()
        val many = NavSlots.defaults().toMutableList().apply {
            add(NavSlot.createLyric())
            add(NavSlot.createDatetime(NavSlot.ACTION_DATETIME_HM_WEEK, "日期时间"))
            add(NavSlot.createAction(NavSlot.ACTION_SETTINGS, "设置"))
            add(NavSlot.createApp("com.example.a", "应用A"))
            add(NavSlot.createApp("com.example.b", "应用B"))
        }
        val bar = NavSlotBarView(ctx).apply {
            drawBackdrop = true
            slots = many
        }
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(112, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 112)
        bar.draw(Canvas(Bitmap.createBitmap(1920, 112, Bitmap.Config.ARGB_8888)))

        val maxRight = bar.hitRects().values.maxOf { it.right }
        assertTrue("全部槽位必须缩放在 1920 宽度内，实测右边缘=$maxRight", maxRight <= 1920f)
    }

    // --------------------------------------------------------- 4. 槽位编辑弹窗添加切换

    @Test
    fun `槽位编辑面板点击加系统动作和加应用直接切换选择视图`() {
        val ctx = RuntimeEnvironment.getApplication()
        val dialog = NavSlotDialog(ctx, Palettes.night, NavSlots.defaults())
        var lastList: List<NavSlot>? = null
        dialog.onChange = { lastList = it }

        val addActionBtn = findTextView(dialog, "+ 系统动作")
        assertNotNull("应有「+ 系统动作」按钮", addActionBtn)
        addActionBtn!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("点击后应直接展示系统动作选择头", findTextView(dialog, "选择要添加的系统动作"))
        assertNotNull("应提供返回按钮", findTextView(dialog, "← 返回槽位列表"))

        // 选中「设置」动作后自动回到列表并追加槽位
        val settingsItem = findTextView(dialog, "设置")
        assertNotNull("系统动作列表里应有「设置」", settingsItem)
        settingsItem!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("添加槽位后应触发 onChange", lastList)
        assertEquals(NavSlots.defaults().size + 1, lastList!!.size)
        assertEquals(NavSlot.ACTION_SETTINGS, lastList!!.last().actionId)

        // 点击「+ 应用」应展示应用选择面板
        val addAppBtn = findTextView(dialog, "+ 应用")
        assertNotNull(addAppBtn)
        addAppBtn!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("点击「+ 应用」应直接展示应用选择头", findTextView(dialog, "选择要添加的应用"))
    }

    // --------------------------------------------------------- 5. 已下线的旧偏好残留不会把底栏切进槽位条或 pill

    @Test
    fun `即使旧偏好残留槽位条或增强导航栏底栏也统一使用设计 Dock`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val prefs = Prefs(activity.applicationContext)
        prefs.globalDockEnabled = false
        prefs.enhancedNav = true
        prefs.navStyle = EnhancedNavBarView.MODE_SLOTS

        val activity2 = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(activity2.window.decorView)!!
        val slotsBarField = LauncherView::class.java.getDeclaredField("slotsBar").apply { isAccessible = true }
        val slotsBar = slotsBarField.get(launcher) as? NavSlotBarView
        assertNull("槽位条已下线，不应再挂载 NavSlotBarView", slotsBar)

        val showSettings = LauncherView::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }
        val closeSettings = LauncherView::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }
        showSettings.invoke(launcher)
        shadowOf(Looper.getMainLooper()).idle()
        closeSettings.invoke(launcher)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        val dockField = LauncherView::class.java.getDeclaredField("dock").apply { isAccessible = true }
        val dock = dockField.get(launcher) as DockBarView
        assertEquals("关闭设置页后设计 Dock 必须保持 VISIBLE", View.VISIBLE, dock.visibility)
    }

    private fun findTextView(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findTextView(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
