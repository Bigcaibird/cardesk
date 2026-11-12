package com.cardesk.launcher.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.cardesk.launcher.BootReceiver
import com.cardesk.launcher.core.Prefs
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

/**
 * 第155轮：把「暂不可用」的那几项**真正实现掉**。
 *
 * ## 背景
 *
 * 第154轮审计查出 12 个「写进偏好没人读」的设置项，按用户口径分三批处置：
 * 删掉 9 个、标注「暂不可用」3 个。第155轮把剩下的一次性清干净：
 *
 * - 「自动导航投屏」—— 删掉。它**没有实现可以接**（开机流程里从头到尾
 *   没有导航投屏这个动作），留着只会继续骗人；
 * - 「桌面开机启动」—— **实现掉**。它其实早就有一个落点：
 *   [com.cardesk.launcher.BootReceiver] 开机时本来就会显式拉一次桌面
 *   （兜底那些不主动 re-launch HOME 的车机），
 *   只是**没有开关管它**。现在由用户决定；
 * - 「胎压数字字号」—— **实现掉**。字号在渲染里写死成 28，
 *   现在真的读 `prefs.deskTpmsValueSp`（`ScenePage.drawTireCard`）。
 *
 * ## 这条测试锁住什么
 *
 * ① 两个实现了的开关**真的会写偏好**（不是又一个只画的开关）；
 * ② 「桌面开机启动」**开机时真的按它行事**（拉 / 不拉桌面）；
 * ③ 「自动导航投屏」**不能再出现在界面上**；
 * ④ 设置页里**不该再有「暂不可用」**——这一轮之后这类占位应该清零。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsLiveItemsTest {

    private fun overlay(): SettingsOverlay {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return SettingsOverlay(
            activity,
            Prefs(activity),
            com.cardesk.launcher.core.Palettes.night,
        )
    }

    /** 设置页正文容器。 */
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

    /**
     * 那一行右侧的开关控件。
     *
     * 开关类 `BoardSwitch` 是 `SettingsOverlay.kt` 里的 private class，测试拿不到类型，
     * 只能按「是个叶子 View、又不是 TextView」认出来。
     */
    private fun switchIn(row: ViewGroup): View? =
        (0 until row.childCount)
            .map { row.getChildAt(it) }
            .firstOrNull { it is View && it !is ViewGroup && it !is TextView }

    private fun openPage(overlay: SettingsOverlay, key: String) {
        overlay.showPageKey(key)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /** 设置页**所有**页面 key（导航树里的 + 不在树里的「开源与声明」）。 */
    private fun allPageKeys(overlay: SettingsOverlay): List<String> {
        val f = SettingsOverlay::class.java.getDeclaredField("navRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = f.get(overlay) as Map<String, TextView>
        return rows.keys.toList() + "about"
    }

    // ---------------------------------------------------------------- ① 实现了的两个开关

    @Test
    fun `桌面开机启动是活的开关_点了会写偏好`() {
        val overlay = overlay()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        openPage(overlay, "boot")

        val row = rowOf(overlay, "桌面开机启动")
        assertNotNull("「桌面开机启动」这一行要留着（现在是能用的开关）", row)
        val sw = switchIn(row!!)
        assertNotNull("这一行右侧应该有个开关", sw)

        val before = prefs.bootAutostart
        sw!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("点了必须写偏好 —— 不写就还是又一个假的开关", !before, prefs.bootAutostart)
    }

    @Test
    fun `胎压数字字号是活的滑杆_拖了会写偏好`() {
        val overlay = overlay()
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        openPage(overlay, "carinfo")

        val row = rowOf(overlay, "胎压数字字号")
        assertNotNull("「胎压数字字号」这一行要留着", row)
        val seek = (0 until row!!.childCount)
            .map { row.getChildAt(it) }
            .filterIsInstance<SeekBar>()
            .firstOrNull()
        assertNotNull("这一行应该还是滑杆形态", seek)
        assertTrue("滑杆必须能拖 —— 拖不动却写成「暂不可用」就等于没实现", seek!!.isEnabled)

        // 滑杆范围 10..48（step=1），progress 26 → 值 36。
        //
        // 注意不能直接 `setProgress(26)`：那条路是 fromUser=false，
        // 设置页的滑杆**只在 fromUser=true 时才写偏好**
        // （`sliderRow` 里 `if (fromUser) { onChange(v); onApply?.invoke() }`）。
        // 这里走 Robolectric 的 shadow，**按用户拖动的方式**触发监听器。
        val listener = shadowOf(seek).onSeekBarChangeListener
        assertNotNull("滑杆上应该挂着拖动监听", listener)
        listener!!.onProgressChanged(seek, 26, true)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("拖了必须写偏好", 36, prefs.deskTpmsValueSp)
    }

    @Test
    fun `桌面开机启动默认是开的_不动设置的老用户行为不变`() {
        // 默认值取 true 是刻意的：接上一个开关不能顺手改掉老用户的开机行为。
        // 这个键以前没人读，BootReceiver 是**无条件**拉桌面的 ——
        // 默认 false 就等于「一升级，开机不拉桌面了」，那是回归。
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        assertTrue("「桌面开机启动」默认必须是开的", prefs.bootAutostart)
    }

    // ---------------------------------------------------------------- ② 开机时真的按开关行事

    @Test
    fun `开机广播按桌面开机启动开关决定拉不拉桌面`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = Prefs(app)

        // 关掉 → 开机不该拉桌面
        prefs.bootAutostart = false
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(
            "关掉「桌面开机启动」后，开机广播不该拉起桌面",
            shadowOf(app).nextStartedActivity,
        )

        // 打开 → 开机要拉桌面
        prefs.bootAutostart = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("开着「桌面开机启动」时，开机广播必须拉起桌面", started)
        assertEquals(
            "拉起的应该是桌面 MainActivity",
            com.cardesk.launcher.MainActivity::class.java.name,
            started!!.component?.className,
        )
    }

    // ---------------------------------------------------------------- ③ 删掉的那项不能再出现

    @Test
    fun `自动导航投屏已经删掉_不能再出现`() {
        val overlay = overlay()
        for (key in allPageKeys(overlay)) {
            openPage(overlay, key)
            assertTrue(
                "「自动导航投屏」没有实现可以接，第155轮已经删掉了，"
                    .plus("但它又出现在了 [$key] 页"),
                texts(bodyOf(overlay)).none { it == "自动导航投屏" },
            )
        }
    }

    // ---------------------------------------------------------------- ④ 「暂不可用」清零

    @Test
    fun `设置页里不该再有暂不可用`() {
        val overlay = overlay()
        val bad = ArrayList<String>()
        for (key in allPageKeys(overlay)) {
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

    @Test
    fun `之前标注暂不可用的三项现在都有各自的形态`() {
        val overlay = overlay()
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        // 「桌面开机启动」：普通开关（能点、能写盘）
        openPage(overlay, "boot")
        assertNotNull("「桌面开机启动」该是个开关行", rowOf(overlay, "桌面开机启动"))
        assertNotNull("「桌面开机启动」该有个能点的开关", switchIn(rowOf(overlay, "桌面开机启动")!!))

        // 「胎压数字字号」：普通滑杆（能拖、能写盘）
        openPage(overlay, "carinfo")
        val row = rowOf(overlay, "胎压数字字号")
        assertNotNull("「胎压数字字号」该还在", row)
        val seek = (0 until row!!.childCount).map { row.getChildAt(it) }.filterIsInstance<SeekBar>().firstOrNull()
        assertNotNull("「胎压数字字号」该是滑杆", seek)
        assertTrue("滑杆必须可拖", seek!!.isEnabled)

        // 「自动导航投屏」：整行没了
        openPage(overlay, "boot")
        assertFalse("「自动导航投屏」该整行删掉", texts(bodyOf(overlay)).any { it == "自动导航投屏" })
        // 顺带确认这两个键还在偏好层（实现侧要用）
        assertNotNull(prefs.bootAutostart)
    }
}
