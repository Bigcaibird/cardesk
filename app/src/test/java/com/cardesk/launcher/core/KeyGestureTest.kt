package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第218轮：**按键增强的手势识别**（纯逻辑，JVM 单测）。
 *
 * 覆盖三条规则：
 * 1. 单击要等双击窗口结束才发；
 * 2. 窗口内的第二次按下 ⇒ 双击；
 * 3. 按住超过阈值 ⇒ 长按，且长按后不再补发单击。
 */
class KeyGestureTest {

    private fun detector() = KeyGesture.Detector(
        doubleWindowMs = KeyGesture.DOUBLE_WINDOW_MS,
        longThresholdMs = KeyGesture.LONG_THRESHOLD_MS,
    )

    @Test
    fun `单击要等双击窗口结束才发`() {
        val d = detector()
        d.onDown(108, 1000L)
        assertEquals(KeyGesture.Gesture.NONE, d.onUp(108, 1050L))
        // 超时了才发单击
        assertEquals(KeyGesture.Gesture.SINGLE, d.onSingleTimeout())
        // 单击只发一次
        assertEquals(KeyGesture.Gesture.NONE, d.onSingleTimeout())
    }

    @Test
    fun `窗口内的第二次按下算双击`() {
        val d = detector()
        d.onDown(108, 1000L)
        d.onUp(108, 1050L)
        // 第一次的单击还挂着
        d.onDown(108, 1100L)
        assertEquals(KeyGesture.Gesture.DOUBLE, d.onUp(108, 1150L))
        // 双击之后不应再补一个单击
        assertEquals(KeyGesture.Gesture.NONE, d.onSingleTimeout())
    }

    @Test
    fun `窗口外的第二次按下不算双击`() {
        val d = detector()
        d.onDown(108, 1000L)
        d.onUp(108, 1050L)
        d.onDown(108, 1000L + KeyGesture.DOUBLE_WINDOW_MS + 200L)
        assertEquals(KeyGesture.Gesture.NONE, d.onUp(108, 1000L + KeyGesture.DOUBLE_WINDOW_MS + 250L))
    }

    @Test
    fun `按住超过阈值算长按`() {
        val d = detector()
        d.onDown(108, 1000L)
        // 还没到阈值
        assertEquals(KeyGesture.Gesture.NONE, d.onLongTimeout(1000L + KeyGesture.LONG_THRESHOLD_MS - 1L))
        // 到阈值了
        assertEquals(KeyGesture.Gesture.LONG, d.onLongTimeout(1000L + KeyGesture.LONG_THRESHOLD_MS))
    }

    @Test
    fun `长按之后不再补发单击`() {
        val d = detector()
        d.onDown(108, 1000L)
        d.onUp(108, 1050L) // 挂起一个待发单击
        d.onDown(108, 1100L)
        assertEquals(KeyGesture.Gesture.LONG, d.onLongTimeout(1100L + KeyGesture.LONG_THRESHOLD_MS))
        // 长按把挂起的单击作废了
        assertEquals(KeyGesture.Gesture.NONE, d.onSingleTimeout())
    }

    @Test
    fun `长按只触发一次`() {
        val d = detector()
        d.onDown(108, 1000L)
        assertEquals(KeyGesture.Gesture.LONG, d.onLongTimeout(1600L))
        assertEquals(KeyGesture.Gesture.NONE, d.onLongTimeout(2000L))
    }

    @Test
    fun `抬手后没有按下就不算`() {
        val d = detector()
        assertEquals(KeyGesture.Gesture.NONE, d.onUp(108, 1000L))
        assertEquals(KeyGesture.Gesture.NONE, d.onSingleTimeout())
    }

    @Test
    fun `按下与抬起的键码不匹配就不算`() {
        val d = detector()
        d.onDown(108, 1000L)
        assertEquals(KeyGesture.Gesture.NONE, d.onUp(87, 1050L))
    }

    @Test
    fun `两次独立的单击发两次单击`() {
        val d = detector()
        d.onDown(108, 1000L)
        d.onUp(108, 1050L)
        assertEquals(KeyGesture.Gesture.SINGLE, d.onSingleTimeout())
        // 第二次单击离得很远 —— 那是**另一次**单击，不该被吞掉
        d.onDown(108, 2000L)
        d.onUp(108, 2050L)
        assertEquals(KeyGesture.Gesture.SINGLE, d.onSingleTimeout())
        assertEquals(KeyGesture.Gesture.NONE, d.onSingleTimeout())
    }

    @Test
    fun `第二次按下落在双击窗内但抬起略超窗仍判双击且不误发单击`() {
        val d = detector()
        d.onDown(200231, 1000L)
        d.onUp(200231, 1050L)
        // 间隔 300ms（<= 380ms）按下第二次，但按住 150ms 才抬起（1500 - 1050 = 450ms > 380ms）
        d.onDown(200231, 1350L)
        // 按住第二次期间即使超时回调跑了，也不应误发单击
        assertEquals(KeyGesture.Gesture.NONE, d.onSingleTimeout())
        assertEquals(KeyGesture.Gesture.DOUBLE, d.onUp(200231, 1500L))
        assertEquals(KeyGesture.Gesture.NONE, d.onSingleTimeout())
    }
}
