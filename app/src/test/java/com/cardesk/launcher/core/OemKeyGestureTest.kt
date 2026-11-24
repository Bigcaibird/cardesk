package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三百一十五轮：**方控广播的手势机**（纯逻辑，JVM 单测）。
 *
 * 与 `KeyGestureTest` 的区别：这里吃的是「单击 / 长按」**语义事件**
 * （系统已经把双击拆成两次单击），所以双击要靠时间窗口合成。
 */
class OemKeyGestureTest {

    private val voice = OemKeyProtocol.KEY_R_VOICE_ASSIST

    @Test
    fun `单击挂起，窗口到期才发`() {
        val d = OemKeyGesture.Detector()
        assertEquals(OemKeyGesture.Gesture.NONE, d.onClick(voice, 1000L).gesture)
        assertEquals(voice, d.pendingKey())
        assertEquals(OemKeyGesture.Gesture.SINGLE, d.expire(1380L).gesture)
        // 结算一次就清空，不会重复发
        assertEquals(OemKeyGesture.Gesture.NONE, d.expire(1400L).gesture)
        assertEquals(0, d.pendingKey())
    }

    @Test
    fun `窗口内的第二次单击算双击`() {
        val d = OemKeyGesture.Detector()
        d.onClick(voice, 1000L)
        val result = d.onClick(voice, 1300L)
        assertEquals(OemKeyGesture.Gesture.DOUBLE, result.gesture)
        // 双击之后窗口到期不应再补单击
        assertEquals(OemKeyGesture.Gesture.NONE, d.expire(2000L).gesture)
        assertEquals(0, d.pendingKey())
    }

    @Test
    fun `窗口边界值算双击`() {
        val d = OemKeyGesture.Detector()
        d.onClick(voice, 1000L)
        assertEquals(
            OemKeyGesture.Gesture.DOUBLE,
            d.onClick(voice, 1000L + OemKeyGesture.DOUBLE_WINDOW_MS).gesture,
        )
    }

    @Test
    fun `超出窗口的第二次单击当新的单击`() {
        val d = OemKeyGesture.Detector()
        d.onClick(voice, 1000L)
        val secondAt = 1000L + OemKeyGesture.DOUBLE_WINDOW_MS + 1
        val second = d.onClick(voice, secondAt)
        assertEquals(OemKeyGesture.Gesture.NONE, second.gesture)
        // 新的挂起从第二次单击开始计时 ⇒ 到期时间也随之后移
        assertEquals(OemKeyGesture.Gesture.SINGLE, d.expire(secondAt + OemKeyGesture.DOUBLE_WINDOW_MS).gesture)
    }

    @Test
    fun `长按立即成立并作废挂起单击`() {
        val d = OemKeyGesture.Detector()
        d.onClick(voice, 1000L)
        assertEquals(OemKeyGesture.Gesture.LONG, d.onLong(voice).gesture)
        assertEquals(0, d.pendingKey())
        // 长按之后窗口到期不该再补一个单击
        assertEquals(OemKeyGesture.Gesture.NONE, d.expire(1600L).gesture)
    }

    @Test
    fun `窗口没到期时不结算`() {
        val d = OemKeyGesture.Detector()
        d.onClick(voice, 1000L)
        assertEquals(OemKeyGesture.Gesture.NONE, d.expire(1200L).gesture)
        assertFalse(d.isExpired(1200L))
        assertTrue(d.isExpired(1000L + OemKeyGesture.DOUBLE_WINDOW_MS))
        // 挂起仍然在
        assertEquals(OemKeyGesture.Gesture.SINGLE, d.expire(1500L).gesture)
    }

    @Test
    fun `不同按键的单击不会跟别的键配成双击`() {
        val d = OemKeyGesture.Detector()
        d.onClick(voice, 1000L)
        val other = d.onClick(OemKeyProtocol.KEY_ASSIST, 1200L)
        assertEquals(OemKeyGesture.Gesture.NONE, other.gesture)
        // 新键覆盖挂起（同一时刻只会有一颗键在手）
        assertEquals(OemKeyProtocol.KEY_ASSIST, d.pendingKey())
    }

    @Test
    fun `非法键码不产生任何手势`() {
        val d = OemKeyGesture.Detector()
        assertEquals(OemKeyGesture.Gesture.NONE, d.onClick(0, 1000L).gesture)
        assertEquals(0, d.pendingKey())
    }

    @Test
    fun `reset 清掉挂起`() {
        val d = OemKeyGesture.Detector()
        d.onClick(voice, 1000L)
        d.reset()
        assertEquals(0, d.pendingKey())
        assertEquals(OemKeyGesture.Gesture.NONE, d.expire(2000L).gesture)
    }

    @Test
    fun `双击窗口与 KeyEvent 通路一致`() {
        // 两条通路吃同一颗键，窗口不一致会让用户觉得「桌面里好点、导航里不好点」
        assertEquals(KeyGesture.DOUBLE_WINDOW_MS, OemKeyGesture.DOUBLE_WINDOW_MS)
    }
}
