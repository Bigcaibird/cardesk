package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车门未关告警（[DoorAlert]）单测（第136轮，纯逻辑）。
 *
 * 这里钉死三件事：
 *
 * 1. **读不到 ≠ 开着** —— 读不到的部件**不提示**（误报一次用户就不信这个提示了）；
 * 2. **具体到是哪一扇门** —— 提示里必须带门名，不能只说「有门没关」；
 * 3. **引擎盖 / 后备箱也算「没关好」**，车窗**不算**。
 */
class DoorAlertTest {

    // ---------------------------------------------------------------- 基本判定

    @Test
    fun `四门全关时不提示`() {
        val items = DoorAlert.openItems(listOf(true, true, true, true), hoodClosed = true, trunkClosed = true)
        assertTrue(items.isEmpty())
        assertFalse(DoorAlert.shouldShow(listOf(true, true, true, true), true, true))
    }

    @Test
    fun `有一扇门开着就提示，并且带上那扇门的名字`() {
        val items = DoorAlert.openItems(listOf(false, true, true, true), hoodClosed = true, trunkClosed = true)
        assertEquals(1, items.size)
        assertEquals("左前门", items[0].label)
        assertEquals(DoorAlert.Kind.DOOR, items[0].kind)
        assertEquals(0, items[0].index)
    }

    @Test
    fun `多扇门开着会全部列出来`() {
        val items = DoorAlert.openItems(listOf(false, true, false, true), hoodClosed = true, trunkClosed = true)
        assertEquals(listOf("左前门", "左后门"), items.map { it.label })
    }

    @Test
    fun `门名顺序是固定的（不是输入顺序）`() {
        val items = DoorAlert.openItems(listOf(true, false, true, false), hoodClosed = true, trunkClosed = true)
        assertEquals(listOf("右前门", "右后门"), items.map { it.label })
    }

    // ---------------------------------------------------------------- 读不到 ≠ 开着

    @Test
    fun `读不到的部件不提示`() {
        val items = DoorAlert.openItems(listOf(null, null, null, null), hoodClosed = null, trunkClosed = null)
        assertTrue(items.isEmpty())
        assertFalse(DoorAlert.shouldShow(listOf(null, null, null, null), null, null))
    }

    @Test
    fun `一半读不到一半关着时不提示`() {
        val items = DoorAlert.openItems(listOf(null, true, null, true), hoodClosed = null, trunkClosed = true)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `整体读不到（doors 为 null）时不提示`() {
        assertTrue(DoorAlert.openItems(null, null, null).isEmpty())
    }

    @Test
    fun `门数组长度不足时也不提示（多出来的位置当读不到）`() {
        val items = DoorAlert.openItems(listOf(true, false), hoodClosed = true, trunkClosed = true)
        assertEquals(listOf("右前门"), items.map { it.label })
    }

    // ---------------------------------------------------------------- 引擎盖 / 后备箱

    @Test
    fun `引擎盖开着也算没关好`() {
        val items = DoorAlert.openItems(listOf(true, true, true, true), hoodClosed = false, trunkClosed = true)
        assertEquals(listOf("引擎盖"), items.map { it.label })
        assertEquals(DoorAlert.Kind.HOOD, items[0].kind)
    }

    @Test
    fun `后备箱开着也算没关好`() {
        val items = DoorAlert.openItems(listOf(true, true, true, true), hoodClosed = true, trunkClosed = false)
        assertEquals(listOf("后备箱"), items.map { it.label })
        assertEquals(DoorAlert.Kind.TRUNK, items[0].kind)
    }

    @Test
    fun `门加引擎盖加后备箱一起开时全部列出`() {
        val items = DoorAlert.openItems(listOf(false, true, true, true), hoodClosed = false, trunkClosed = false)
        assertEquals(listOf("左前门", "引擎盖", "后备箱"), items.map { it.label })
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun `标题：一处未关与多处未关的说法不同`() {
        assertEquals("有车门未关", DoorAlert.title(1))
        assertEquals("3 处未关", DoorAlert.title(3))
        assertEquals("", DoorAlert.title(0))
    }

    @Test
    fun `正文列出具体是哪几个`() {
        val items = DoorAlert.openItems(listOf(false, true, true, false), hoodClosed = false, trunkClosed = true)
        assertEquals("左前门、右后门、引擎盖", DoorAlert.summary(items))
        assertEquals("左前门、右后门、引擎盖 还没关好，请检查", DoorAlert.detail(items))
    }

    @Test
    fun `没有未关时正文为空`() {
        assertEquals("", DoorAlert.detail(emptyList()))
    }
}
