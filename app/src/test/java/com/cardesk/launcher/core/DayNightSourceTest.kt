package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日夜模式信号来源单测。
 */
class DayNightSourceTest {

    @Test
    fun nightFromCarValue_mapsDayNightAuto() {
        assertEquals(false, DayNightSource.nightFromCarValue(DayNightSource.VALUE_DAYMODE_DAY, null))
        assertEquals(true, DayNightSource.nightFromCarValue(DayNightSource.VALUE_DAYMODE_NIGHT, null))
        // AUTO 时看系统 UI 的夜间态
        assertEquals(true, DayNightSource.nightFromCarValue(DayNightSource.VALUE_DAYMODE_AUTO, true))
        assertEquals(false, DayNightSource.nightFromCarValue(DayNightSource.VALUE_DAYMODE_AUTO, false))
        // 系统态读不到 → 无法判定
        assertNull(DayNightSource.nightFromCarValue(DayNightSource.VALUE_DAYMODE_AUTO, null))
    }

    @Test
    fun nightFromCarValue_unknownValueIsNull() {
        assertNull(DayNightSource.nightFromCarValue(0, true))
        assertNull(DayNightSource.nightFromCarValue(12345, true))
    }
}
