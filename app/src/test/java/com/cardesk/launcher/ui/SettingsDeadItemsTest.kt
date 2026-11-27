package com.cardesk.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
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
 * 设置页「没有功能的设置项」整改（第154轮，第155轮收尾）。
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
 * ## 第154轮定的口径
 *
 * - 前 9 项（5 + 4）**直接删掉** —— 留着比没有更糟（用户会当成设置坏了）；
 * - 后 3 项**保留位置但标注「暂不可用」**，点击只提示、**不再写偏好**
 *   （盘上留一个没人读的值，只会在以后排障时误导人）。
 *
 * 另外把「恢复默认 15 槽位」修成**当场生效**（原来要重启）。
 *
 * ## 第155轮：后 3 项各归其位
 *
 * - 「自动导航投屏」—— 删掉（没有实现可以接）；
 * - 「桌面开机启动」「胎压数字字号」—— **真实现**（用例见 [SettingsLiveItemsTest]）。
 *
 * 所以这一轮之后设置页里**不该再有「暂不可用」**这种占位，
 * `unavailableRow` / `unavailableSliderRow` 那套机制也一并拆了。
 *
 * ## 这条测试锁住什么
 *
 * ① 真正没人读的项**不能再出现在界面上**（360 视角那五项第310轮接了执行端，已移出本清单）；
 * ② 界面里**不该再有「暂不可用」**；
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

    // ---------------------------------------------------------------- ① 死项清理

    /**
     * 第154轮删掉的那五个视角/联动选项，第310轮**接了执行端又回来了**。
     *
     * 执行端是 `vehicle/Camera360Link`：订阅挡位信号（29693 / `CURRENT_GEAR`）与雷达图形
     * 功能号（`HvacIds.FUNC_RADAR_GRAPHICS`），按这里的配置决定「开环影 + 切哪个视角」。
     * 所以它们不再是「写进偏好、没人读」的死项，这条用例改成**正向锁住**：
     * 界面必须有这几行（另有「N/P 挡关闭环影」「雷达图形触发」）。
     *
     * 「转向灯视角」刻意没回来：车机侧还没有可验证的转向灯信号通路，
     * 放出来就又是一条死项。
     *
     * 反向约束仍在：真正没人读的项（下面歌词字号那四条）还得继续不出现。
     */
    @Test
    fun `360 环影页的视角与联动项都有执行端`() {
        val overlay = overlay()
        // 先打开总开关：关着的时候这一页只画得出那一个开关，
        // 后面几项根本不渲染 —— 那样断言「不在页面上」是空的，不算数。
        Prefs(RuntimeEnvironment.getApplication()).camera360Enabled = true
        openPage(overlay, "p360")
        val ts = texts(bodyOf(overlay))
        for (live in listOf(
            "倒车视角",
            "R→D 视角",
            "D 挡视角",
            "N/P 挡关闭环影",
            "雷达图形触发",
        )) {
            assertTrue("「$live」由 vehicle/Camera360Link 读取并执行，不该从界面上消失", ts.any { it == live })
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

    // ---------------------------------------------------------------- ② 「暂不可用」清零
    //
    // 第155轮：「暂不可用」那三项已经各归其位 ——
    // 「自动导航投屏」删掉、「桌面开机启动」「胎压数字字号」真实现。
    // 它们的用例挪到了 [SettingsLiveItemsTest]，
    // 这里只留一条：**界面里不该再有这种占位**。

    @Test
    fun `设置页里不该再有暂不可用`() {
        val overlay = overlay()
        val f = SettingsOverlay::class.java.getDeclaredField("navRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = f.get(overlay) as Map<String, TextView>
        val bad = ArrayList<String>()
        for (key in rows.keys.toList() + "about") {
            openPage(overlay, key)
            for (t in texts(bodyOf(overlay))) {
                if (t.contains("暂不可用")) bad.add("[$key] $t")
            }
        }
        assertTrue(
            "第155轮把「暂不可用」全实现掉了，设置页里不该再有这种占位：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
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

    // ---------------------------------------------------------------- ④ Dock 页已精简掉导航栏样式、槽位及重复开关

    @Test
    fun `Dock 页不再包含已下线的导航栏样式、槽位设置与重复项`() {
        val overlay = overlay()
        openPage(overlay, "dock")
        val ts = texts(bodyOf(overlay))
        for (gone in listOf(
            "导航栏样式",
            "编辑槽位",
            "恢复默认 15 槽位",
            "沉浸系统导航栏",
            "增强导航栏（贴底 pill）",
            "下移 Dock",
        )) {
            assertTrue("「$gone」已下线，不应再出现在 Dock 与导航栏页", ts.none { it == gone })
        }
        for (kept in listOf(
            "隐藏桌面 Dock",
            "隐藏原车 Dock",
            "隐藏原车状态栏",
            "状态栏生效应用",
            "全局 Dock 栏（其他应用也显示）",
            "Dock 圆角",
            "配置 Dock 快捷应用…",
        )) {
            assertTrue("「$kept」是有独立功能的有效项，必须保留", ts.any { it == kept })
        }
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
