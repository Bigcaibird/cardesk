package com.cardesk.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.media.MediaSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 设置页第151轮的两个修复。
 *
 * ## ① 媒体来源改成下拉
 *
 * 原来「默认来源」是一排 chip（分段控件）：9 个选项在 1920 宽下挤成两行还要横向滚动，
 * 而选中项（默认「通用音乐」）在**中间**，打开设置页一眼看不出当前选的是谁。
 * 改成一个下拉行：收起时当前值写在行上，展开时 9 个选项一次列全。
 *
 * ## ② 按钮压住下一项
 *
 * 「Dock 快捷应用」那个「配置 Dock 快捷应用…」按钮底边直接贴住下一行的标题
 * （「沉浸系统导航栏」），看着像**压在了上面**。
 * 根因：[SettingsOverlay.actionButton] 只给了 `topMargin`，没给 `bottomMargin`。
 *
 * ## 为什么这两条都要**从视图树里**查
 *
 * - 下拉：源码里写没写 `chipRow`/`dropdownRow` 是一回事，**真机上到底渲染成什么样**是另一回事；
 * - 间距：`bottomMargin > 0` 只是「设了」，**够不够**要真量一遍布局才知道。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsDropdownTest {

    private fun overlay(prefs: Prefs? = null): SettingsOverlay {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return SettingsOverlay(activity, prefs ?: Prefs(activity), Palettes.night)
    }

    /** 反射拿设置页正文容器。 */
    private fun bodyOf(overlay: SettingsOverlay): ViewGroup {
        val f = SettingsOverlay::class.java.getDeclaredField("body").apply { isAccessible = true }
        return f.get(overlay) as ViewGroup
    }

    /** 顺着视图树把打了标记的 view 全捞出来（不限 TextView）。 */
    private fun marked(root: View, mark: Any): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.tag === mark) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun texts(root: View, mark: Any): List<String> =
        marked(root, mark).filterIsInstance<TextView>().map { it.text.toString() }

    /** 把正文量一遍、摆一遍，拿到子 view 的真实位置。 */
    private fun layoutBody(body: ViewGroup): ViewGroup {
        body.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
        )
        body.layout(0, 0, 1000, body.measuredHeight)
        return body
    }

    // ------------------------------------------------------- ① 媒体来源下拉

    @Test
    fun `媒体源页用下拉_不再有 chip 行`() {
        val overlay = overlay()
        overlay.showPageKey("media")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val body = bodyOf(overlay)

        val values = texts(body, SettingsOverlay.DROPDOWN_VALUE_MARK)
        assertEquals("媒体源页应该正好有一个下拉行（当前值）", 1, values.size)
        assertEquals(
            "媒体源页不该再有 chip 分段控件了（9 个选项挤一行、选中项在中间看不见）",
            0,
            marked(body, SettingsOverlay.CHIP_SEG_MARK).size,
        )
    }

    @Test
    fun `下拉显示的就是当前选中的来源`() {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val prefs = Prefs(activity)
        prefs.defaultMusicSource = "qq"

        val overlay = overlay(prefs)
        overlay.showPageKey("media")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val values = texts(bodyOf(overlay), SettingsOverlay.DROPDOWN_VALUE_MARK)
        assertEquals("当前值应该显示当前选中的来源", listOf("QQ音乐"), values)
    }

    @Test
    fun `下拉列出了全部 9 个来源`() {
        val overlay = overlay()
        overlay.showPageKey("media")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val options = texts(bodyOf(overlay), SettingsOverlay.DROPDOWN_OPTION_MARK)
        assertEquals(
            "下拉应该列出全部 ${MediaSources.LIST.size} 个来源",
            MediaSources.LIST.size,
            options.size,
        )
        assertEquals(
            "选项顺序要跟来源顺序一致",
            MediaSources.LIST.map { it.label },
            options,
        )
    }

    @Test
    fun `点选项就换源_而且落盘`() {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val prefs = Prefs(activity)
        prefs.defaultMusicSource = "generic"

        var picked: String? = null
        val overlay = overlay(prefs)
        overlay.onDefaultMediaSource = { picked = it }
        overlay.showPageKey("media")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val options = marked(bodyOf(overlay), SettingsOverlay.DROPDOWN_OPTION_MARK)
            .filterIsInstance<TextView>()
        val netease = options.first { it.text.toString() == "网易云音乐" }
        netease.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("点了选项就该换源并落盘", "netease", prefs.defaultMusicSource)
        assertEquals("还得回调出去（桌面那边要跟着切会话）", "netease", picked)
    }

    @Test
    fun `选了具体App之后通用音乐那条提示要收起来`() {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val prefs = Prefs(activity)
        prefs.defaultMusicSource = "generic"

        val overlay = overlay(prefs)
        overlay.showPageKey("media")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val body = bodyOf(overlay)
        val notes = marked(body, SettingsOverlay.NOTE_MARK).filterIsInstance<TextView>()
        val genericNote = notes.firstOrNull { it.text.toString().startsWith("要先在上面把默认来源选成具体的 App") }
        assertTrue("默认是通用音乐时应该有这条提示", genericNote != null)
        assertEquals("提示默认要显示", View.VISIBLE, genericNote!!.visibility)

        val options = marked(body, SettingsOverlay.DROPDOWN_OPTION_MARK).filterIsInstance<TextView>()
        options.first { it.text.toString() == "QQ音乐" }.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(
            "选了具体 App，这条提示就该收起来",
            View.GONE,
            genericNote.visibility,
        )
    }

    // ------------------------------------------------------- ② 按钮不压下一项

    @Test
    fun `按钮下面必须留间距_不然会压住下一项`() {
        val overlay = overlay()
        overlay.showPageKey("dock")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val body = layoutBody(bodyOf(overlay))
        val buttons = marked(body, SettingsOverlay.ACTION_BUTTON_MARK)
        assertTrue("Dock 页应该有按钮（配置 Dock 快捷应用…）", buttons.isNotEmpty())

        for (b in buttons) {
            val lp = b.layoutParams as ViewGroup.MarginLayoutParams
            assertTrue(
                "按钮「${(b as TextView).text}」底下必须留间距（之前只给了 topMargin，底边贴住下一行）",
                lp.bottomMargin > 0,
            )
        }
    }

    @Test
    fun `按钮底边到下一项要真的有空隙_不是零距离`() {
        val overlay = overlay()
        overlay.showPageKey("dock")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val body = layoutBody(bodyOf(overlay))
        // 「配置 Dock 快捷应用…」按钮的下一个兄弟 = 「沉浸系统导航栏」那一行。
        val button = marked(body, SettingsOverlay.ACTION_BUTTON_MARK)
            .filterIsInstance<TextView>()
            .first { it.text.toString().contains("配置 Dock 快捷应用") }
        val idx = (0 until body.childCount).first { body.getChildAt(it) === button }
        assertTrue("按钮后面应该还有内容（下一行）", idx + 1 < body.childCount)
        val next = body.getChildAt(idx + 1)

        val gap = next.top - button.bottom
        assertTrue(
            "按钮底边到下一项只隔了 $gap px —— 这就是「压到了下一个选项」",
            gap >= 12,
        )
    }

    @Test
    fun `每一页的按钮底下都有间距`() {
        val overlay = overlay()
        val f = SettingsOverlay::class.java.getDeclaredField("navRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = f.get(overlay) as Map<String, TextView>

        for (key in rows.keys) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val body = layoutBody(bodyOf(overlay))
            for (b in marked(body, SettingsOverlay.ACTION_BUTTON_MARK)) {
                val lp = b.layoutParams as ViewGroup.MarginLayoutParams
                assertTrue(
                    "「$key」页的按钮「${(b as TextView).text}」底下没留间距",
                    lp.bottomMargin > 0,
                )
            }
        }
    }
}
