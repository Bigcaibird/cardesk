package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 360 环影联动决策（[Camera360Policy]）的纯 JVM 单测。
 *
 * 这里不碰 Android：挡位、雷达、视角配置全是普通值。
 * 覆盖的重点是「有上一状态才成立的那些规则」（进 R、R→D、进 D、N/P 关）
 * 以及「绝不能误动作」的情形（总开关关、挡位读不到、视角配成 -1）。
 */
class Camera360PolicyTest {

    /** 默认：总开关开、在 D 挡、无雷达、所有视角不切。单条用例只覆盖自己关心的字段。 */
    private fun input(
        master: Boolean = true,
        gear: Char? = 'D',
        prev: Char? = 'P',
        radar: Boolean = false,
        reverse: Int = -1,
        rd: Int = -1,
        d: Int = -1,
        npClose: Boolean = false,
        radarOpen: Boolean = true,
        delay: Int = 300,
        alreadyOpen: Boolean = false,
    ): Camera360Policy.Input = Camera360Policy.Input(
        masterEnabled = master,
        gear = gear,
        prevGear = prev,
        radarActive = radar,
        reverseView = reverse,
        rdView = rd,
        dView = d,
        npClose = npClose,
        radarOpen = radarOpen,
        delayMs = delay,
        alreadyOpen = alreadyOpen,
    )

    // ------------------------------------------------------------ 总开关 / 挡位未知

    @Test
    fun `总开关关闭时挂倒挡也不动作`() {
        val actions = Camera360Policy.decide(input(master = false, gear = 'R', prev = 'P', reverse = 5))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `挡位读不到时不动作`() {
        val actions = Camera360Policy.decide(input(gear = null, prev = 'R', reverse = 5, d = 5))
        assertTrue(actions.isEmpty())
    }

    // ------------------------------------------------------------ 挡位动作

    @Test
    fun `挂入倒挡开环影并切倒车视角`() {
        val actions = Camera360Policy.decide(input(gear = 'R', prev = 'P', reverse = 5))
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(5, 300, "挂入倒挡")),
            actions,
        )
    }

    @Test
    fun `首次读到倒挡（上一挡未知）也开环影`() {
        val actions = Camera360Policy.decide(input(gear = 'R', prev = null, reverse = 7))
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(7, 300, "挂入倒挡")),
            actions,
        )
    }

    @Test
    fun `一直停在倒挡时重复通知不动作`() {
        val actions = Camera360Policy.decide(input(gear = 'R', prev = 'R', reverse = 5))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `倒挡切前进挡开环影并切 RD 视角`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'R', rd = 6))
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(6, 300, "倒挡切前进挡")),
            actions,
        )
    }

    @Test
    fun `从停车挡进前进挡切 D 挡视角`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'P', d = 8))
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(8, 300, "进入前进挡")),
            actions,
        )
    }

    @Test
    fun `从空挡进前进挡也切 D 挡视角`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'N', d = 4))
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(4, 300, "进入前进挡")),
            actions,
        )
    }

    @Test
    fun `从未知挡进入前进挡切 D 挡视角`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = null, d = 9))
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(9, 300, "进入前进挡")),
            actions,
        )
    }

    @Test
    fun `前进挡内换挡不动作`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'D', d = 8))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `进空挡且开启 NP 关闭时关环影`() {
        val actions = Camera360Policy.decide(input(gear = 'N', prev = 'D', npClose = true))
        assertEquals(listOf<Camera360Policy.Action>(Camera360Policy.Action.Close), actions)
    }

    @Test
    fun `进停车挡且开启 NP 关闭时关环影`() {
        val actions = Camera360Policy.decide(input(gear = 'P', prev = 'N', npClose = true))
        assertEquals(listOf<Camera360Policy.Action>(Camera360Policy.Action.Close), actions)
    }

    @Test
    fun `NP 关闭设置关闭时进空挡不动作`() {
        val actions = Camera360Policy.decide(input(gear = 'N', prev = 'D', npClose = false))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `一直停在空挡时重复通知不再关环影`() {
        val actions = Camera360Policy.decide(input(gear = 'N', prev = 'N', npClose = true))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `首次读到停车挡且开启 NP 关闭时关环影`() {
        val actions = Camera360Policy.decide(input(gear = 'P', prev = null, npClose = true))
        assertEquals(listOf<Camera360Policy.Action>(Camera360Policy.Action.Close), actions)
    }

    // ------------------------------------------------------------ 雷达动作

    @Test
    fun `雷达图形出现时只开环影不切视角`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'D', radar = true))
        assertEquals(listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenOnly), actions)
    }

    @Test
    fun `雷达开关关闭时雷达图形出现不动作`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'D', radar = true, radarOpen = false))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `环影已经开着时雷达出现不重复开`() {
        val actions = Camera360Policy.decide(
            input(gear = 'D', prev = 'D', radar = true, alreadyOpen = true),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `挂倒挡同时雷达出现只按挡位动作不双写`() {
        val actions = Camera360Policy.decide(
            input(gear = 'R', prev = 'P', radar = true, reverse = 5),
        )
        assertEquals(1, actions.size)
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(5, 300, "挂入倒挡")),
            actions,
        )
    }

    // ------------------------------------------------------------ 视角配置为 -1

    @Test
    fun `倒车视角为 -1 时挂倒挡不动作`() {
        val actions = Camera360Policy.decide(input(gear = 'R', prev = 'P', reverse = -1))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `RD 视角为 -1 时倒挡切前进挡不动作`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'R', rd = -1))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `D 挡视角为 -1 时进前进挡不动作`() {
        val actions = Camera360Policy.decide(input(gear = 'D', prev = 'N', d = -1))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `视角延迟原样透传给动作`() {
        val actions = Camera360Policy.decide(input(gear = 'R', prev = 'P', reverse = 5, delay = 800))
        assertEquals(
            listOf<Camera360Policy.Action>(Camera360Policy.Action.OpenAndView(5, 800, "挂入倒挡")),
            actions,
        )
    }

    // ------------------------------------------------------------ 去重

    @Test
    fun `同一状态组合第二次不再放行`() {
        val deduper = Camera360Policy.Deduper()
        val state = input(gear = 'R', prev = 'P', reverse = 5)
        assertTrue(deduper.accept(state))
        assertFalse(deduper.accept(state))
        assertEquals("1|R|0", deduper.lastKey())
    }

    @Test
    fun `挡位变化后同一去重器重新放行`() {
        val deduper = Camera360Policy.Deduper()
        assertTrue(deduper.accept(input(gear = 'R', prev = 'P', reverse = 5)))
        assertFalse(deduper.accept(input(gear = 'R', prev = 'P', reverse = 5)))
        assertTrue(deduper.accept(input(gear = 'D', prev = 'R', rd = 6)))
    }

    @Test
    fun `总开关变化后重新放行`() {
        val deduper = Camera360Policy.Deduper()
        assertTrue(deduper.accept(input(master = true, gear = 'D', prev = 'P', d = 8)))
        assertFalse(deduper.accept(input(master = true, gear = 'D', prev = 'P', d = 8)))
        assertTrue(deduper.accept(input(master = false, gear = 'D', prev = 'P', d = 8)))
    }

    @Test
    fun `雷达状态变化会改变状态键`() {
        val deduper = Camera360Policy.Deduper()
        assertTrue(deduper.accept(input(gear = 'D', prev = 'D', radar = false)))
        assertTrue(deduper.accept(input(gear = 'D', prev = 'D', radar = true)))
        assertFalse(deduper.accept(input(gear = 'D', prev = 'D', radar = true)))
    }

    @Test
    fun `重置后同一状态可以再放行`() {
        val deduper = Camera360Policy.Deduper()
        val state = input(gear = 'R', prev = 'P', reverse = 5)
        assertTrue(deduper.accept(state))
        deduper.reset()
        assertTrue(deduper.accept(state))
    }

    // ------------------------------------------------------------ 动作签名（防抖）

    @Test
    fun `同一视角的动作签名与延迟和原因无关`() {
        val a = Camera360Policy.Action.OpenAndView(5, 300, "挂入倒挡")
        val b = Camera360Policy.Action.OpenAndView(5, 800, "别的触发原因")
        assertEquals(a.signature, b.signature)
    }

    @Test
    fun `开 关 切视角 三类动作签名互不相同`() {
        assertEquals("open+view:5", Camera360Policy.Action.OpenAndView(5, 300, "x").signature)
        assertEquals("open", Camera360Policy.Action.OpenOnly.signature)
        assertEquals("close", Camera360Policy.Action.Close.signature)
        assertEquals("view:5", Camera360Policy.Action.View(5, 300).signature)
    }

    // ------------------------------------------------------------ 挡位值映射

    @Test
    fun `car_signal 29693 的挡位口径`() {
        assertEquals('D', Camera360Policy.gearFromSignal(1))
        assertEquals('R', Camera360Policy.gearFromSignal(2))
        assertEquals('N', Camera360Policy.gearFromSignal(3))
        assertEquals('P', Camera360Policy.gearFromSignal(0))
        assertEquals('P', Camera360Policy.gearFromSignal(4))
        assertNull(Camera360Policy.gearFromSignal(9))
        assertNull(Camera360Policy.gearFromSignal(null))
    }

    @Test
    fun `AOSP CURRENT_GEAR 位口径映射`() {
        assertEquals('D', Camera360Policy.gearFromAospProperty(8))
        assertEquals('R', Camera360Policy.gearFromAospProperty(2))
        assertEquals('P', Camera360Policy.gearFromAospProperty(4))
        assertEquals('N', Camera360Policy.gearFromAospProperty(1))
        // 0x10 起是具体前进挡位（GEAR_1..GEAR_10），一律按前进挡处理
        assertEquals('D', Camera360Policy.gearFromAospProperty(0x10))
        assertEquals('D', Camera360Policy.gearFromAospProperty(0x100))
        assertNull(Camera360Policy.gearFromAospProperty(0))
        assertNull(Camera360Policy.gearFromAospProperty(0x8000))
        assertNull(Camera360Policy.gearFromAospProperty(null))
    }

    @Test
    fun `模拟车辆挡位文本映射忽略大小写与空白`() {
        assertEquals('P', Camera360Policy.gearFromMockText("P"))
        assertEquals('D', Camera360Policy.gearFromMockText("d"))
        assertEquals('R', Camera360Policy.gearFromMockText(" R "))
        assertEquals('N', Camera360Policy.gearFromMockText("n"))
        assertNull(Camera360Policy.gearFromMockText("X"))
        assertNull(Camera360Policy.gearFromMockText(""))
        assertNull(Camera360Policy.gearFromMockText(null))
    }

    // ------------------------------------------------------------ 入口同义

    @Test
    fun `evaluate 与 decide 结论一致`() {
        val state = input(gear = 'R', prev = 'P', reverse = 5)
        assertEquals(Camera360Policy.decide(state), Camera360Policy.evaluate(state))
        assertNotNull(Camera360Policy.evaluate(state))
    }
}
