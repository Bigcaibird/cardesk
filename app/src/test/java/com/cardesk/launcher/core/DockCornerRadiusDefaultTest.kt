package com.cardesk.launcher.core

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cardesk.launcher.ui.shell.DockBarView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/**
 * 第190轮：**Dock 栏圆角默认值 36 → 0** 的回归。
 *
 * ## 为什么单独钉这一条
 *
 * 圆角是个「默认值」型设置项 —— 大部分人一辈子不会去动它，
 * 所以默认值本身就是**产品观感**的一部分（36 = 浮屿圆角，0 = 直角横条）。
 * 改默认值这种改动最容易被后来的重构悄悄改回去，
 * 所以必须有单测把「默认 0」写成契约。
 *
 * ## 两条契约
 *
 * 1. **没设过的人 → 0**：`dock_corner_radius` 不在 SharedPreferences 里时读出来必须是 0；
 * 2. **设过的人 → 保留自己的值**：只改默认值，不动老用户已经写进去的数
 *    （这条是第190轮刻意做的取舍 —— 不做「强制归零」是为了不覆盖用户选择）。
 *
 * 另外还钉住：**DockBarView 真正画的时候用的就是这个值**，
 * 而不是只在 `Prefs` 里写了个 0、渲染层还拿着写死的 36。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DockCornerRadiusDefaultTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    /** 读 `DockBarView` 私有的 `cornerRadius` 字段（渲染层真正用的那个值）。 */
    private fun renderedRadius(view: DockBarView): Float {
        val f = DockBarView::class.java.getDeclaredField("cornerRadius")
        f.isAccessible = true
        return f.get(view) as Float
    }

    @Test
    fun `默认圆角是0_不是36`() {
        assertEquals(
            "第190轮起 Dock 圆角默认值应为 0（直角横条）",
            0,
            Prefs(ctx()).dockCornerRadius,
        )
    }

    @Test
    fun `没写进SharedPreferences时读出来就是0`() {
        // 直接看底层存储：确认「默认」来自 getOrDefault，而不是上次测试写进去的残留。
        val sp = ctx().getSharedPreferences("cardesk", Context.MODE_PRIVATE)
        sp.edit().remove("dock_corner_radius").commit()
        assertEquals(
            "偏好里没有这条记录时，应该返回默认值 0",
            0,
            Prefs(ctx()).dockCornerRadius,
        )
    }

    @Test
    fun `用户自己设过的值要保留_不能被默认值覆盖`() {
        val prefs = Prefs(ctx())
        prefs.dockCornerRadius = 36
        // 重新 new 一个实例（模拟应用重启后再读）。
        assertEquals(
            "只改默认值，不动老用户已经设过的值",
            36,
            Prefs(ctx()).dockCornerRadius,
        )
        prefs.dockCornerRadius = 0
    }

    @Test
    fun `超出范围的写入要被钳到0到52`() {
        val prefs = Prefs(ctx())
        prefs.dockCornerRadius = 999
        assertEquals("超上限钳到 52", 52, prefs.dockCornerRadius)
        prefs.dockCornerRadius = -10
        assertEquals("负数钳到 0", 0, prefs.dockCornerRadius)
        prefs.dockCornerRadius = 0
    }

    /**
     * 渲染层也得跟着是 0 —— 上一轮就出现过「属性写进 `Prefs` 了，
     * 画的时候还是拿的写死常量」的老毛病（见第155轮胎压字号的教训）。
     */
    @Test
    fun `DockBarView挂到窗口后用的圆角也是0`() {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val host = FrameLayout(activity)
        activity.addContentView(
            host,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200),
        )
        val dock = DockBarView(activity)
        host.addView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            "Dock 渲染层拿到的圆角应该是 0",
            0f,
            renderedRadius(dock),
            0.01f,
        )
    }

    /**
     * 设置页改完圆角 → `DockBarView.refreshAll()` 必须让**活着的那一个实例**跟着变。
     * 这是第183轮加圆角设置时定的通路，第190轮没动它，这里钉住别退化。
     */
    @Test
    fun `设置页改值后refreshAll会刷新所有存活实例`() {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val host = FrameLayout(activity)
        activity.addContentView(
            host,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200),
        )
        val dock = DockBarView(activity)
        host.addView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val prefs = Prefs(activity.applicationContext)
        prefs.dockCornerRadius = 24
        DockBarView.refreshAll(activity.applicationContext)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            "改完设置后存活实例的圆角应该变成 24",
            24f,
            renderedRadius(dock),
            0.01f,
        )
        prefs.dockCornerRadius = 0
    }
}
