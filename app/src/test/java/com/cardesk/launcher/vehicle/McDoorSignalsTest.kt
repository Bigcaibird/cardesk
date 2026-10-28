package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MC / Geely 事件属性车门通道的**纯口径**单测。
 *
 * 读五个独立属性
 * `560993300` 左前门 / `301` 左后门 / `302` 右前门 / `303` 右后门 / `304` 后备箱。
 */
class McDoorSignalsTest {

    @Test
    fun `属性 id 符合预期`() {
        assertEquals(560993300, McDoorSignals.PROP_DOOR_FL)
        assertEquals(560993301, McDoorSignals.PROP_DOOR_BL)
        assertEquals(560993302, McDoorSignals.PROP_DOOR_FR)
        assertEquals(560993303, McDoorSignals.PROP_DOOR_BR)
        assertEquals(560993304, McDoorSignals.PROP_TRUNK)
    }

    @Test
    fun `四个门按 FL FR RL RR 排列`() {
        assertEquals(
            listOf(
                McDoorSignals.PROP_DOOR_FL,
                McDoorSignals.PROP_DOOR_FR,
                McDoorSignals.PROP_DOOR_BL,
                McDoorSignals.PROP_DOOR_BR,
            ),
            McDoorSignals.DOOR_PROPS.toList(),
        )
    }

    @Test
    fun `取值口径 0 关 1 开 其它读不到`() {
        assertEquals("0 = 关", true, McDoorSignals.interpret(0L))
        assertEquals("1 = 开", false, McDoorSignals.interpret(1L))
        assertNull("其它值读不出来", McDoorSignals.interpret(2L))
        assertNull("读不到就是读不到", McDoorSignals.interpret(null))
    }

    @Test
    fun `readable 看的是有没有至少一项读到了`() {
        val none = McDoorSignals.McDoorState(listOf(null, null, null, null), null)
        assertFalse(none.readable)

        val one = McDoorSignals.McDoorState(listOf(true, null, null, null), null)
        assertTrue(one.readable)

        val trunkOnly = McDoorSignals.McDoorState(listOf(null, null, null, null), true)
        assertTrue(trunkOnly.readable)
    }
}
