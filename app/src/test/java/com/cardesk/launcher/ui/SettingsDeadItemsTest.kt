package com.cardesk.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.NavSlots
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
import org.robolectric.shadows.ShadowToast

/**
 * 设置页「没有功能的设置项」整改（第154轮）。
 *
 * ## 背景
 *
 * 审计（`emu-verify/y154/SETTINGS-AUDIT.md`）查出 12 个设置项
 * **把值写进偏好但没有任何代码读** —— 开关能切、能持久化，功能一点没变：
 *
 * - 360 环影页 5 个视角选项（倒车 / R→D / D 档 / 转向灯 / N·P 关闭），
 *   实现侧只有一条 `schedule360View(按键视角, 延迟)` 通路；
 * - 歌词页 4 个「导航栏歌词槽字号」滑杆，字号在 `NavSlotBarView` 里是写死的；
 * - 「桌面开机启动」「自动导航投屏」「胎压数字字号」三项各自有人读、无人用。
 *
 * ## 本轮定的口径
 *
 * - 前 9 项（5 + 4）**直接删掉** —— 留着比没有更糟（用户会当成设置坏了）；
 * - 后 3 项**保留位置但标注「暂不可用」**，点击只提示、**不再写偏好**
 *   （盘上留一个没人读的值，只会在以后排障时误导人）。
 *
 * 另外把「恢复默认 15 槽位」修成**当场生效**（原来要重启）。
 *
 * ## 这条测试锁住什么
 *
 * ① 删掉的 9 项**不能再出现在界面上**（防止有人「顺手加回来」）；
 * ② 保留的三项必须**明说暂不可用**，而且**点了不写盘**；
 * ③ 360 环影总开关拨开后，子项必须**当场出现**（不用切走再切回）；
 * ④ 「恢复默认 15 槽位」必须**通知宿主刷新槽位**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsDeadItemsTest {

    private fun overlay(): SettingsOverlay {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return SettingsOverlay(
            activity,
            Prefs(activity),
            com.cardesk.launcher.core.Palettes.night,
        )
    }

    /** 设置页正文容器（页面内容都挂在这下面）。 */
    private fun bodyOf(overlay: SettingsOverlay): LinearLayout {
        val f = SettingsOverlay::class.java.getDeclaredField("body").apply { isAccessible = true }
        return f.get(overlay) as LinearLayout
    }

    /** 收集一棵子树里所有 TextView 的文字。 */
    private fun texts(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** 找到「标题文字等于 label」的那一行（行 = body 的直接子 ViewGroup）。 */
    private fun rowOf(overlay: SettingsOverlay, label: String): ViewGroup? {
        val body = bodyOf(overlay)
        for (i in 0 until body.childCount) {
            val child = body.getChildAt(i)
            if (child is ViewGroup && texts(child).any { it == label }) return child
        }
        return null
    }

    private fun openPage(overlay: SettingsOverlay, key: String) {
        overlay.showPageKey(key)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    // ---------------------------------------------------------------- ① 删掉的 9 项

    @Test
    fun `360 环影页不再有五个没人读的视角选项`() {
        val overlay = overlay()
        // 先打开总开关：关着的时候这一页只画得出那一个开关，
        // 后面几项根本不渲染 —— 那样断言「不在页面上」是空的，不算数。
        Prefs(RuntimeEnvironment.getApplication()).camera360Enabled = true
        openPage(overlay, "p360")
        val ts = texts(bodyOf(overlay))
        for (gone in listOf("倒车视角", "R→D 视角", "D 档视角", "转向灯视角", "N/P 关闭环影")) {
            assertTrue("「$gone」写进偏好后没人读，应该从界面上删掉", ts.none { it == gone })
        }
    }

    @Test
    fun `360 环影页真正能用的三项还在`() {
        val overlay = overlay()
        Prefs(RuntimeEnvironment.getApplication()).camera360Enabled = true
        openPage(overlay, "p360")
        val ts = texts(bodyOf(overlay))
        for (kept in listOf("启用 360 环影增强", "视角延迟", "按键视角")) {
            assertTrue("「$kept」是活的（实现侧真的读它），不该被误删", ts.any { it == kept })
        }
    }

    @Test
    fun `歌词页不再有四个没人读的歌词槽字号滑杆`() {
        val overlay = overlay()
        openPage(overlay, "lyric")
        val ts = texts(bodyOf(overlay))
        for (gone in listOf("收起·主歌词", "收起·副歌词", "展开·主歌词", "展开·副歌词")) {
            assertTrue("「$gone」的值没人读，应该从界面上删掉", ts.none { it == gone })
        }
    }

    @Test
    fun `首页中央歌词字号还在`() {
        val overlay = overlay()
        openPage(overlay, "lyric")
        val ts = texts(bodyOf(overlay))
        // 这一套字号是接了 `scene_lyric_scale_pct` 的（活的），跟删掉的四个不是一回事
        assertTrue("首页中央歌词是另一套画面，字号可调，不能一起删", ts.any { it == "首页中央歌词" })
    }

    // ---------------------------------------------------------------- ② 标注暂不可用的 3 项

    /** 行里那个「暂不可用」的状态标记（靠标记找，不靠扫文字）。 */
    private fun unavailableMark(row: ViewGroup): TextView? {
        fun walk(v: View): TextView? {
            if (v is TextView && v.tag === SettingsOverlay.UNAVAILABLE_MARK) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(row)
    }

    @Test
    fun `桌面开机启动标注暂不可用且点击不写偏好`() {
        val overlay = overlay()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        openPage(overlay, "boot")
        val row = rowOf(overlay, "桌面开机启动")
        assertNotNull("「桌面开机启动」这行要留着（只是标成暂不可用）", row)

        val mark = unavailableMark(row!!)
        assertNotNull("必须有「暂不可用」的状态标记", mark)
        assertEquals("状态标记上就写「暂不可用」", "暂不可用", mark!!.text.toString())
        assertEquals(
            "「暂不可用」要用淡字，不能跟正常文字一样显眼",
            com.cardesk.launcher.core.Palettes.night.textFaint,
            mark.currentTextColor,
        )

        val before = prefs.bootAutostart
        row.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("点了不该写偏好：没人读的值留在盘上只会误导排障", before, prefs.bootAutostart)
        assertTrue(
            "点了要有提示，不能让人以为界面卡住了",
            ShadowToast.getTextOfLatestToast()?.contains("暂不可用") == true,
        )
    }

    @Test
    fun `自动导航投屏标注暂不可用且点击不写偏好`() {
        val overlay = overlay()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        openPage(overlay, "boot")
        val row = rowOf(overlay, "自动导航投屏")
        assertNotNull("「自动导航投屏」这行要留着", row)

        val mark = unavailableMark(row!!)
        assertNotNull("必须有「暂不可用」的状态标记", mark)
        assertEquals("状态标记上就写「暂不可用」", "暂不可用", mark!!.text.toString())

        val before = prefs.startNaviCastEnabled
        row.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("点了不该写偏好", before, prefs.startNaviCastEnabled)
    }

    @Test
    fun `胎压数字字号标注暂不可用且滑杆是禁用的`() {
        val overlay = overlay()
        openPage(overlay, "carinfo")
        val row = rowOf(overlay, "胎压数字字号")
        assertNotNull("「胎压数字字号」这行要留着", row)

        val mark = unavailableMark(row!!)
        assertNotNull("必须有「暂不可用」的状态标记", mark)
        assertEquals("读数要写明暂不可用", "暂不可用", mark!!.text.toString())

        // 滑杆必须是禁用的 —— 拖得动却不生效，比不显示更糟
        val seek = (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filterIsInstance<android.widget.SeekBar>()
            .firstOrNull()
        assertNotNull("这一行应该还是滑杆形态（只是禁用）", seek)
        assertFalse("滑杆必须禁用", seek!!.isEnabled)
    }

    // ---------------------------------------------------------------- ③ 360 子项即时出现

    /**
     * 找出那一行右侧的开关控件。
     *
     * 开关类 `BoardSwitch` 是文件里的 private class，测试拿不到类型，
     * 只能按「是个叶子 View、又不是 TextView」认出来。
     */
    private fun switchIn(row: ViewGroup): View? =
        (0 until row.childCount)
            .map { row.getChildAt(it) }
            .firstOrNull { it is View && it !is ViewGroup && it !is TextView }

    @Test
    fun `360 环影总开关打开后子项立刻出现`() {
        val overlay = overlay()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.camera360Enabled = false
        openPage(overlay, "p360")

        // 关着的时候这一页只有一个总开关，后两项根本不渲染
        assertTrue(
            "总开关关着时不该出现子项",
            texts(bodyOf(overlay)).none { it == "视角延迟" },
        )

        val row = rowOf(overlay, "启用 360 环影增强")
        assertNotNull("找不到总开关那一行", row)
        val sw = switchIn(row!!)
        assertNotNull("总开关那行右侧应该有个开关", sw)

        sw!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // 关键：**不切走、不切回**，子项就得当场出现。
        // 以前总开关只写偏好不重建本页，用户拨完开关眼前什么都没变，
        // 得切走再切回才看得到 —— 这一页刚收敛过，剩下两项要是还藏着，
        // 等于把唯一能用的也藏了。
        val ts = texts(bodyOf(overlay))
        assertTrue("拨开总开关后「视角延迟」要立刻出现", ts.any { it == "视角延迟" })
        assertTrue("拨开总开关后「按键视角」要立刻出现", ts.any { it == "按键视角" })
        assertTrue("总开关本身要真的打开", prefs.camera360Enabled)
    }

    // ---------------------------------------------------------------- ④ 恢复默认槽位当场生效

    @Test
    fun `恢复默认 15 槽位会通知宿主刷新槽位`() {
        val overlay = overlay()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        var notified = 0
        overlay.onNavSlotsChanged = { notified++ }

        openPage(overlay, "dock")
        val btn = findButton(bodyOf(overlay), "恢复默认 15 槽位")
        assertNotNull("找不到「恢复默认 15 槽位」按钮", btn)

        // 先写一份非默认的槽位，确认按钮真的把它重置了
        NavSlots.save(prefs, NavSlots.defaults().take(3))
        btn!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("必须通知宿主刷新槽位条（否则要重启才生效）", 1, notified)
        assertEquals(
            "槽位要真的恢复成默认",
            NavSlots.defaults().size,
            NavSlots.load(prefs).size,
        )
    }

    /** 按文字找 actionButton（带 ACTION_BUTTON_MARK 标记的 TextView）。 */
    private fun findButton(root: ViewGroup, text: String): TextView? {
        fun walk(v: View): TextView? {
            if (v is TextView && v.text.toString() == text) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(root)
    }
}
