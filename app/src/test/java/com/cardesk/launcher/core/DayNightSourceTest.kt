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

    /**
     * AdaptAPI 昼夜通道的**实际明暗**（`getDayNight()` / `onDayNightChanged`）。
     *
     * 出处：`com.ecarx.xui.adaptapi.device.daynightmode.DayNightMode`
     * ——`getDayNight()` 返回 `1` 白天 / `2` 夜间 / `0` 读不到；
     * 处于「跟随」时取车机光照 `Twlibrists`（`OFF`→夜间、`ON`→白天）。
     */
    @Test
    fun nightFromDisplay_mapsDayNight() {
        assertEquals(false, DayNightSource.nightFromDisplay(DayNightSource.DISPLAY_DAY))
        assertEquals(true, DayNightSource.nightFromDisplay(DayNightSource.DISPLAY_NIGHT))
        // 读不到 / 非法值 → 无法判定
        assertNull(DayNightSource.nightFromDisplay(0))
        assertNull(DayNightSource.nightFromDisplay(-1))
        assertNull(DayNightSource.nightFromDisplay(3))
    }

    /**
     * AdaptAPI 昼夜通道的**模式**（`getDayNightMode()` / `onDayNightModeChange`）。
     *
     * 「跟随」（3）时模式本身不定明暗 → 返回 null，调用方要再读一次实际明暗。
     */
    @Test
    fun nightFromMode_mapsFixedModes_butAutoIsUnknown() {
        assertEquals(false, DayNightSource.nightFromMode(DayNightSource.DISPLAY_DAY))
        assertEquals(true, DayNightSource.nightFromMode(DayNightSource.DISPLAY_NIGHT))
        assertNull(DayNightSource.nightFromMode(DayNightSource.MODE_AUTO))
        assertNull(DayNightSource.nightFromMode(0))
    }
}
