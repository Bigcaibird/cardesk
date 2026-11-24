package com.cardesk.launcher.ui

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.KeyActionResolver
import com.cardesk.launcher.core.OemKeyProtocol
import com.cardesk.launcher.core.OemKeyProtocol.Press
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * 第三百一十五轮：**方控广播通路**的端到端测试。
 *
 * ## 这条用例守的是什么
 *
 * 「按键增强有问题」的根因是两条**结构性缺陷**，都不是配置读错：
 *
 * 1. 只在 `MainActivity.dispatchKeyEvent` 收按键 ⇒ 桌面不在前台就收不到。
 *    修法：新增广播接收器 + 动态注册（`ui/OemKeyReceiver`、`ui/OemKeyRegistry`）。
 * 2. 媒体键被无条件吞掉 ⇒ 没配长按动作时方控切歌失灵。
 *    修法：没配长按动作就整颗键放行；配了就短按补发给系统。
 *
 * 前者在模拟器上用 `am broadcast` 实测过（系统发出的方控事件就是普通隐式广播），
 * 后者必须在这里验：**模拟器造不出「按住 600ms」**（`input keyevent --longpress`
 * 的 down/up 落在同一毫秒），所以用 Robolectric 的虚拟时钟推进定时器。
 *
 * ## 覆盖
 *
 * | 场景 | 期望 |
 * |---|---|
 * | 语音键单击（配了动作） | 双击窗口到期后执行单击动作 |
 * | 语音键长按 | 立即执行长按动作，不补发单击 |
 * | 两次单击在窗口内 | 合成双击，执行双击动作（只执行一次） |
 * | 双击没配、单击配了 | 退化成一次单击动作 |
 * | 三个槽位都没配 | 不执行任何动作，但广播仍被吃掉（转给车机语音兜底） |
 * | 媒体键没配长按 | **不吞按键**（返回 false，系统照常切歌） |
 * | 媒体键配了长按 | 按下/抬起被吃，短按不执行动作；按住超阈值才执行 |
 * | 设置页自检按钮 | 单击/长按自测能把事件喂进执行端 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyChannelBroadcastTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private lateinit var prefs: Prefs
    private lateinit var executor: KeyActionExecutor

    /** 360 被切换的次数。 */
    private var toggles = 0

    /** 执行过的动作（动作码 + 来源）。 */
    private val executed = mutableListOf<Pair<Int, String>>()

    @Before
    fun setUp() {
        prefs = Prefs(app)
        prefs.keysEnhanced = true
        resetActions()
        toggles = 0
        executed.clear()
        executor = KeyActionExecutor(
            context = app,
            prefs = prefs,
            onToggle360 = { toggles++ },
        )
        executor.onExecuted = { action, source -> executed += action.action to source }
    }

    @After
    fun tearDown() {
        executor.stop()
        OemKeyRuntime.reset()
    }

    private fun resetActions() {
        prefs.voiceClickAction = Prefs.ACTION_NONE
        prefs.voiceDoubleAction = Prefs.ACTION_NONE
        prefs.voiceLongAction = Prefs.ACTION_NONE
        prefs.mediaPrevLongAction = Prefs.ACTION_NONE
        prefs.mediaNextLongAction = Prefs.ACTION_NONE
    }

    /** 推进虚拟时钟（毫秒），触发到期的定时器。 */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** 灌一次方控广播事件。 */
    private fun oem(press: Press, keyCode: Int = OemKeyProtocol.KEY_R_VOICE_ASSIST): Boolean =
        executor.onOemKey(OemKeyProtocol.OemKeyEvent(keyCode, press, OemKeyProtocol.ACTION_VOICE))

    // ------------------------------------------------------------------ 语音键

    @Test
    fun `语音键单击等双击窗口到期后才执行`() {
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE
        assertTrue(oem(Press.CLICK))
        // 窗口内还没执行（否则双击时会先触发单击）
        assertEquals(0, toggles)
        advance(cardeskWindow() + 20)
        assertEquals(1, toggles)
        assertEquals(1, executed.size)
        assertTrue(executed[0].second.contains("单击"))
    }

    @Test
    fun `语音键长按立即执行且不补发单击`() {
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE
        assertTrue(oem(Press.LONG))
        assertEquals(1, toggles)
        // 窗口到期也不该再补一个单击
        advance(cardeskWindow() + 20)
        assertEquals(1, toggles)
        assertTrue(executed[0].second.contains("长按"))
    }

    @Test
    fun `两次单击在窗口内合成双击`() {
        prefs.voiceDoubleAction = Prefs.ACTION_360_TOGGLE
        assertTrue(oem(Press.CLICK))
        advance(150)
        assertTrue(oem(Press.CLICK))
        assertEquals(1, toggles)
        assertEquals("方控语音键双击", executed[0].second)
        // 双击之后不再补单击
        advance(cardeskWindow() + 20)
        assertEquals(1, toggles)
    }

    @Test
    fun `双击没配时退化成一次单击`() {
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE
        assertTrue(oem(Press.CLICK))
        advance(150)
        assertTrue(oem(Press.CLICK))
        assertEquals(1, toggles)
        assertTrue(executed[0].second.contains("按单击执行"))
    }

    @Test
    fun `三个槽位都没配时不吃动作但仍吃掉广播`() {
        assertTrue(oem(Press.CLICK))
        advance(cardeskWindow() + 50)
        assertEquals(0, toggles)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `总开关关闭时方控广播不拦`() {
        prefs.keysEnhanced = false
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE
        assertFalse(oem(Press.CLICK))
        advance(cardeskWindow() + 50)
        assertEquals(0, toggles)
    }

    @Test
    fun `未知键码不处理`() {
        assertFalse(oem(Press.CLICK, keyCode = 4242))
    }

    // ---------------------------------------------------------------- 媒体键

    @Test
    fun `媒体键没配长按时不吞按键`() {
        // 第三百一十五轮修的 bug：以前这里返回 true ⇒ 方控切歌失灵
        assertFalse(down(KeyActionResolver.KEYCODE_MEDIA_PREVIOUS))
        assertFalse(up(KeyActionResolver.KEYCODE_MEDIA_PREVIOUS))
        assertFalse(down(KeyActionResolver.KEYCODE_MEDIA_NEXT))
        assertFalse(up(KeyActionResolver.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun `媒体键配了长按时短按不执行动作`() {
        prefs.mediaPrevLongAction = Prefs.ACTION_360_TOGGLE
        assertTrue(down(KeyActionResolver.KEYCODE_MEDIA_PREVIOUS))
        // 没按住到阈值就抬手 ⇒ 不执行长按动作（短按由执行端补发回系统）
        assertTrue(up(KeyActionResolver.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(0, toggles)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `媒体键按住超过阈值执行长按动作`() {
        prefs.mediaPrevLongAction = Prefs.ACTION_360_TOGGLE
        assertTrue(down(KeyActionResolver.KEYCODE_MEDIA_PREVIOUS))
        advance(600)
        assertEquals(1, toggles)
        assertTrue(up(KeyActionResolver.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(1, toggles)
    }

    // ------------------------------------------------------------ 设置页自检

    @Test
    fun `设置页自检按钮能把事件喂进执行端`() {
        // 第三百一十五轮：按键增强面板已合并进设置页 —— 自检按钮就在「按键增强」页底部
        prefs.keysEnhanced = true
        prefs.mediaPrevLongAction = Prefs.ACTION_360_TOGGLE
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // 挂执行端要在 Activity 建好之后：MainActivity 会把桌面自己的执行端挂上去，
        // 这里换成用例的执行端，才能数到 toggles
        OemKeyRuntime.attach(activity, prefs, executor)
        val overlay = SettingsOverlay(activity, prefs, Palettes.night)
        // select 是私有的：直接把设置页切到「按键增强」页
        SettingsOverlay::class.java
            .getDeclaredMethod("select", String::class.java)
            .apply { isAccessible = true }
            .invoke(overlay, "keys")
        shadowOf(Looper.getMainLooper()).idle()

        val texts = collectTexts(overlay)
        assertTrue("缺少「按键通路」分组", texts.contains("按键通路（车机广播）"))
        assertTrue("缺少最近按键诊断行", texts.contains("最近按键"))
        assertTrue("缺少单击自测按钮", texts.contains("单击"))
        assertTrue("缺少长按自测按钮", texts.contains("长按"))
        assertTrue("缺少媒体长按自测按钮", texts.contains("媒体长按"))

        // 点「媒体长按」⇒ 走媒体键长按动作（上一曲 = 360 开关）
        val button = findTextView(overlay) { it.text == "媒体长按" }
        assertTrue("找不到媒体长按自测按钮", button != null)
        button!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, toggles)
    }

    // ------------------------------------------------------------------ 工具

    private fun cardeskWindow(): Long = com.cardesk.launcher.core.OemKeyGesture.DOUBLE_WINDOW_MS

    private fun down(keyCode: Int): Boolean =
        executor.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0), SystemClock.uptimeMillis())

    private fun up(keyCode: Int): Boolean =
        executor.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0), SystemClock.uptimeMillis())

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotEmpty()) out += text
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun findTextView(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }
}
