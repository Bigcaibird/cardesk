package com.cardesk.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 设置页结构（第149轮：合并同模块的页面 + 清死代码 + 优化分区标题）。
 *
 * ## 这条测试锁住三件事
 *
 * ① **合并后的页面都能打开** —— 导航树里点哪一项都不该崩；
 * ② **旧 key 仍然能路由** —— 外部深链（`showPageKey`）可能还在用合并前的 key，
 *    那些 key 必须还能打开对应的页（只是标题和内容归到新页）；
 * ③ **死代码真的删掉了** —— 旧的 `globalGroups` / `systemGroups` 只喂两个
 *    从没被 `addView` 进视图树的计数徽标，留着就是每次开设置页白构造一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsLayoutTest {

    private fun overlay(): com.cardesk.launcher.ui.SettingsOverlay {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val prefs = Prefs(activity)
        return com.cardesk.launcher.ui.SettingsOverlay(
            activity,
            prefs,
            com.cardesk.launcher.core.Palettes.night,
        )
    }

    /** 反射拿设置页的导航树（key → 行）。 */
    private fun navRows(overlay: com.cardesk.launcher.ui.SettingsOverlay): Map<String, TextView> {
        val f = com.cardesk.launcher.ui.SettingsOverlay::class.java
            .getDeclaredField("navRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(overlay) as Map<String, TextView>
    }

    @Test
    fun `导航树里合并后的页面都在`() {
        val rows = navRows(overlay())
        for (key in listOf("boot", "cards", "lyric", "dock", "media")) {
            assertNotNull("导航树里应该有「$key」", rows[key])
        }
    }

    @Test
    fun `导航项比合并前少了`() {
        val rows = navRows(overlay())
        // 合并前 28 项。这轮把「桌面自启 / 卡片风格 / 桌面歌词 / 仪表歌词 /
        // Dock 快捷应用」五项并进了别的页（-5），又新开「歌词」「媒体源」两项（+2），
        // 净剩 25 项。
        assertEquals("导航项应该正好是 25 项（28 - 5 + 2）", 25, rows.size)
    }

    @Test
    fun `合并掉的旧 key 仍然能路由到对应页面`() {
        val overlay = overlay()
        // 旧 key 现在指向合并后的那一页 —— 外部深链还在用它们。
        for (key in listOf("autostart", "cardstyle", "desktoplyric", "clusterlyric", "quickapps")) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        // 不崩就是过；再确认一下标题不是空的（旧 key 不在导航树里，标题要单独给）。
        val titleField = com.cardesk.launcher.ui.SettingsOverlay::class.java
            .getDeclaredField("pageTitle").apply { isAccessible = true }
        val title = titleField.get(overlay) as TextView
        overlay.showPageKey("desktoplyric")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("旧 key 深链进来标题不该是空的", title.text.isNotEmpty())
    }

    @Test
    fun `合并后的每一页都能打开且有内容`() {
        val overlay = overlay()
        for (key in listOf("boot", "cards", "lyric", "dock", "media", "diagnostics")) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val bodyField = com.cardesk.launcher.ui.SettingsOverlay::class.java
                .getDeclaredField("body").apply { isAccessible = true }
            val body = bodyField.get(overlay) as ViewGroup
            assertTrue("「$key」页应该有内容", body.childCount > 0)
        }
    }

    @Test
    fun `死代码已经删掉_不再有全局和系统两份导航`() {
        val cls = com.cardesk.launcher.ui.SettingsOverlay::class.java
        val names = cls.declaredFields.map { it.name }
        assertFalse(
            "旧的 globalGroups / systemGroups 应该删掉（它们只喂从不渲染的计数徽标）",
            names.contains("globalGroups") || names.contains("systemGroups"),
        )
        assertFalse(
            "tabGlobal / tabSystem 同理",
            names.contains("tabGlobal") || names.contains("tabSystem"),
        )
    }

    // -------------------------------------------------------------- 新的偏好

    @Test
    fun `启动后自动播放默认是关的`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        assertFalse(
            "默认必须关 —— 开机弹出一个音乐 App 挺打扰",
            prefs.autoLaunchMediaApp,
        )
    }

    @Test
    fun `启动后自动播放能存能读`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.autoLaunchMediaApp = true
        assertTrue(prefs.autoLaunchMediaApp)
        prefs.autoLaunchMediaApp = false
        assertFalse(prefs.autoLaunchMediaApp)
    }

    @Test
    fun `仪表歌词模块宽度下限是10%`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.overlayWidthPct = 10
        assertEquals("10% 必须存得进去", 10, prefs.overlayWidthPct)
        prefs.overlayWidthPct = 5
        assertEquals("低于下限要被夹到 10%", 10, prefs.overlayWidthPct)
    }
}
