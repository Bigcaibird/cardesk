package com.cardesk.launcher.ui

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.cardesk.launcher.core.KeyActionResolver
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * 第218轮：**按键增强执行端**的端到端测试。
 *
 * ## 为什么必须有这个测试
 *
 * 手势识别本身（[com.cardesk.launcher.core.KeyGesture]）是纯状态机，已经有 JVM 单测覆盖。
 * 但真正决定「哪个手势 ⇒ 哪个动作」的是 [KeyActionExecutor] 这一层：
 * 它要把 **按下 / 抬起 / 超时** 三路输入拼起来，还要保证
 * 「单击延迟到双击窗口结束才发」「长按之后不再补发单击」这些时序约束。
 *
 * ## 为什么不用模拟器验证
 *
 * 模拟器上 `input keyevent --longpress` 的 down/up 落在**同一毫秒**，
 * `sendevent` 注入的 evdev 事件又不被 Android 输入子系统消费
 * ⇒ 模拟器**根本造不出「按住 600ms」这种输入**。
 * 所以长按这条链路只能用 Robolectric 的虚拟时钟验。
 *
 * ## 时钟
 *
 * Robolectric 默认 PAUSED looper 模式：`shadowOf(Looper.getMainLooper()).idleFor(...)`
 * 会推进虚拟时钟并触发到期的定时器 ⇒ 不需要真的 sleep。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyActionExecutorTest {

    private lateinit var prefs: Prefs
    private lateinit var executor: KeyActionExecutor

    /** 360 切换被调用的次数。 */
    private var toggle360Count = 0

    /** 执行过的动作（动作码 + 来源描述）。 */
    private val executed = mutableListOf<Pair<Int, String>>()

    @Before
    fun setUp() {
        prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.keysEnhanced = true
        prefs.voiceClickAction = Prefs.ACTION_NONE
        prefs.voiceDoubleAction = Prefs.ACTION_NONE
        prefs.voiceLongAction = Prefs.ACTION_NONE

        toggle360Count = 0
        executed.clear()

        executor = KeyActionExecutor(
            context = RuntimeEnvironment.getApplication(),
            prefs = prefs,
            onToggle360 = { toggle360Count++ },
        )
        executor.onExecuted = { action, source -> executed += action.action to source }
    }

    // ------------------------------------------------------------------ 工具

    private fun now(): Long = SystemClock.uptimeMillis()

    /** 推进虚拟时钟（毫秒），顺带跑到期的定时器。 */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun press(keyCode: Int) {
        executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode), now())
    }

    private fun release(keyCode: Int) {
        executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode), now())
    }

    /**
     * 语音键 keyCode。
     *
     * ⚠️ 这里**必须写字面量 108**，不能用 `KeyEvent.KEYCODE_VOICE_ASSIST` ——
     * 本工程 compileSdk 是 34，而 Android 14 把 `KEYCODE_VOICE_ASSIST` 从 108 改成了 231。
     * 写常量会编译成 231，拿 API 28 的车机去对就对不上了。
     * 详见 `core/KeyActionResolver.VOICE_KEY_CODES` 的注释。
     */
    private val voiceKey = 108

    /** Android 14 起的语音键新值。 */
    private val voiceKeyNew = 231

    private val mediaPrevKey = KeyEvent.KEYCODE_MEDIA_PREVIOUS
    private val mediaNextKey = KeyEvent.KEYCODE_MEDIA_NEXT

    // ------------------------------------------------------------------ 长按

    /**
     * 核心：**按住超过阈值 ⇒ 执行长按动作**。
     *
     * 这是模拟器验不了的那条路径（`input keyevent --longpress` 不真按住）。
     */
    @Test
    fun 长按语音键超过阈值后执行长按动作() {
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        // 还没到阈值 ⇒ 不该执行
        advance(KeyGestureThreshold.LONG - 50)
        assertEquals("阈值前不应执行", 0, executed.size)
        assertEquals("阈值前不该切 360", 0, toggle360Count)

        // 过阈值 ⇒ 长按定时器触发
        advance(100)
        assertEquals("长按应执行一次", 1, executed.size)
        assertEquals(Prefs.ACTION_360_TOGGLE, executed[0].first)
        assertEquals(1, toggle360Count)
    }

    /**
     * 长按成立之后抬手 ⇒ **不能再补发一次单击**。
     *
     * 否则一次长按会触发两个动作（长按动作 + 单击动作），用户会看到「切了两次」。
     */
    @Test
    fun 长按成立后抬手不再补发单击() {
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        advance(KeyGestureThreshold.LONG + 100)
        assertEquals("长按执行一次", 1, executed.size)

        release(voiceKey)
        // 把双击窗口整个走完
        advance(KeyGestureThreshold.DOUBLE + 100)
        assertEquals("长按 + 抬手不应触发单击", 1, executed.size)
        assertEquals("切 360 只该一次", 1, toggle360Count)
    }

    /** 阈值之内抬手 ⇒ 按单击处理（不按长按）。 */
    @Test
    fun 阈值内抬手按单击处理() {
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        advance(KeyGestureThreshold.LONG - 100)
        release(voiceKey)
        advance(KeyGestureThreshold.DOUBLE + 100)

        assertEquals("只应执行单击一次", 1, executed.size)
        assertTrue("执行来源应是单击", executed[0].second.contains("单击"))
    }

    /** 媒体上一曲 / 下一曲按键已移除增强 ⇒ 按下/抬起一律放行不消费。 */
    @Test
    fun 媒体上一曲和下一曲一律放行不拦截() {
        assertFalse(executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, mediaPrevKey), now()))
        advance(KeyGestureThreshold.LONG + 100)
        assertFalse(executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, mediaPrevKey), now()))

        assertFalse(executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, mediaNextKey), now()))
        advance(KeyGestureThreshold.LONG + 100)
        assertFalse(executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, mediaNextKey), now()))

        assertEquals("媒体键不应触发任何按键增强动作", 0, executed.size)
    }

    /** 长按只能触发一次（定时器不会重复触发）。 */
    @Test
    fun 长按只触发一次() {
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        advance(KeyGestureThreshold.LONG + 100)
        advance(KeyGestureThreshold.LONG + 100)
        advance(KeyGestureThreshold.LONG + 100)

        assertEquals("长按只触发一次", 1, executed.size)
    }

    // ------------------------------------------------------------------ 单击 / 双击

    /** 单击**必须等双击窗口结束**才发（否则双击会先误触发单击）。 */
    @Test
    fun 单击在双击窗口结束后才执行() {
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        release(voiceKey)

        advance(KeyGestureThreshold.DOUBLE - 100)
        assertEquals("窗口内不应执行单击", 0, executed.size)

        advance(200)
        assertEquals("窗口结束应执行单击", 1, executed.size)
        assertTrue(executed[0].second.contains("单击"))
    }

    /** 双击成立 ⇒ 只执行双击动作，挂起的单击作废。 */
    @Test
    fun 双击只执行双击动作单击作废() {
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE
        prefs.voiceDoubleAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        release(voiceKey)
        advance(50)
        press(voiceKey)
        release(voiceKey)

        assertEquals("双击应立刻执行", 1, executed.size)
        assertTrue("执行来源应是双击", executed[0].second.contains("双击"))

        // 把双击窗口整个走完，单击不应再冒出来
        advance(KeyGestureThreshold.DOUBLE + 200)
        assertEquals("单击应被双击作废", 1, executed.size)
    }

    /** 两次点击间隔超过窗口 ⇒ 算两次独立单击。 */
    @Test
    fun 间隔超过窗口算两次独立单击() {
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        release(voiceKey)
        advance(KeyGestureThreshold.DOUBLE + 100)
        assertEquals("第一次单击", 1, executed.size)

        press(voiceKey)
        release(voiceKey)
        advance(KeyGestureThreshold.DOUBLE + 200)
        assertEquals("第二次单击", 2, executed.size)
    }

    // ------------------------------------------------------------------ 开关与拦截

    /** 总开关关闭 ⇒ 事件不被消费，也什么都不执行。 */
    @Test
    fun 总开关关闭时不消费按键事件() {
        prefs.keysEnhanced = false
        prefs.voiceClickAction = Prefs.ACTION_360_TOGGLE

        val consumed = executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, voiceKey), now())
        assertFalse("开关关闭时不应消费事件", consumed)

        advance(KeyGestureThreshold.LONG + 200)
        assertEquals("开关关闭时不应执行", 0, executed.size)
    }

    /** 不关心的按键 ⇒ 不消费（不影响系统默认行为）。 */
    @Test
    fun 不关心的按键不被消费() {
        val consumed = executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME), now())
        assertFalse("HOME 键不应被消费", consumed)
    }

    /** 长按期间系统发来的重复事件（repeatCount > 0）应被忽略。 */
    @Test
    fun 长按期间的重复事件被忽略() {
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        // `repeatCount` 是 KeyEvent 的 final 字段 ⇒ 用五参构造造一个带重复次数的事件
        val repeat = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, voiceKey, 1)
        executor.onKeyEvent(repeat, now())

        advance(KeyGestureThreshold.LONG + 100)
        assertEquals("重复事件不应产生额外动作", 1, executed.size)
    }

    /** 动作配成「无」⇒ 不执行任何东西（也不该崩）。 */
    @Test
    fun 动作配成无时什么都不做() {
        prefs.voiceLongAction = Prefs.ACTION_NONE

        press(voiceKey)
        advance(KeyGestureThreshold.LONG + 100)

        assertEquals("「无」不该执行", 0, executed.size)
        assertEquals("「无」不该切 360", 0, toggle360Count)
    }

    /** 长按语音键的「来源」描述里应带 keyCode，便于诊断包定位真实键码。 */
    @Test
    fun 长按来源描述里带keyCode() {
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE

        press(voiceKey)
        advance(KeyGestureThreshold.LONG + 100)

        assertEquals(1, executed.size)
        assertTrue(
            "来源应含 keyCode，实际=${executed[0].second}",
            executed[0].second.contains(voiceKey.toString()),
        )
    }

    /**
 * 不认识的按键：**不消费**，但要**留痕**。
 *
 * ## 为什么
 *
 * 实车的语音键真实 keyCode 未知（各家车机不一样），
 * 现在只能靠候选列表覆盖 ⇒ 用户按一次语音键，诊断包里就能看到真实值。
 *
 * ⚠️ 必须是 INFO 级别 —— `Logx` 的 DEBUG 默认**不落盘** ⇒ 诊断包里看不到。
 */
@Test
    fun 不认识的按键不消费但会留痕() {
        ShadowLog.clear()

        val consumed = executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME), now())

        assertFalse("不认识键不应被消费", consumed)
        val logged = ShadowLog.getLogs().any {
            it.type == android.util.Log.INFO && it.msg.contains("不认识的按键")
        }
        assertTrue("陌生 keyCode 应记进日志（诊断包要能看到）", logged)
    }

/**
     * 同一个陌生 keyCode 连续按，**只记一次**（免得刷屏）。
 */
@Test
    fun 陌生按键的日志有间隔限流() {
        ShadowLog.clear()
        val key = KeyEvent.KEYCODE_VOLUME_UP

        executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key), now())
        advance(KeyGestureThreshold.DOUBLE + 100)
        executor.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key), now())
        advance(KeyGestureThreshold.DOUBLE + 100)

        val count = ShadowLog.getLogs().count {
            it.type == android.util.Log.INFO && it.msg.contains("不认识的按键")
        }
        assertEquals("同一 keyCode 在间隔内只记一次", 1, count)
    }

    /**
     * Android 14 起的语音键新值（231）也要认。
     *
     * ⚠️ `KeyEvent.KEYCODE_VOICE_ASSIST` 在 compileSdk 34 上就是 231，
     * 而 API 28 的车机发的是 108 —— 两个都得在候选表里。
     */
    @Test
    fun 语音键新旧两个取值都认() {
        prefs.voiceLongAction = Prefs.ACTION_360_TOGGLE

        press(voiceKeyNew)
        advance(KeyGestureThreshold.LONG + 100)

        assertEquals("231 也应被当成语音键", 1, executed.size)
    }

    /**
     * 阈值常量的小抄，避免测试里散落魔法数字。
     */
    private object KeyGestureThreshold {
        const val LONG = com.cardesk.launcher.core.KeyGesture.LONG_THRESHOLD_MS
        const val DOUBLE = com.cardesk.launcher.core.KeyGesture.DOUBLE_WINDOW_MS
    }
}
