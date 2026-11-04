package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日夜时段判定单测（默认 06:00 / 18:30）。
 */
class DayNightTest {

    @Test
    fun defaults_matchReference() {
        assertEquals(360, DayNight.DEFAULT_DAY_START_MIN)
        assertEquals(1110, DayNight.DEFAULT_NIGHT_START_MIN)
    }

    @Test
    fun isNight_beforeDayStart_isNight() {
        // 05:00 在白天区间之前
        assertTrue(DayNight.isNight(5 * 60, 360, 1110))
    }

    @Test
    fun isNight_insideDayWindow_isDay() {
        assertFalse(DayNight.isNight(12 * 60, 360, 1110))
    }

    @Test
    fun isNight_afterNightStart_isNight() {
        assertTrue(DayNight.isNight(19 * 60, 360, 1110))
    }

    @Test
    fun isNight_exactBoundaries() {
        assertFalse(DayNight.isNight(360, 360, 1110))   // 06:00 是白天起点
        assertTrue(DayNight.isNight(1110, 360, 1110))   // 18:30 是夜间起点
    }

    @Test
    fun isNight_windowCrossingMidnight() {
        // 夜间从 22:00 到 05:00
        assertTrue(DayNight.isNight(23 * 60, 5 * 60, 22 * 60))
        assertTrue(DayNight.isNight(2 * 60, 5 * 60, 22 * 60))
        assertFalse(DayNight.isNight(12 * 60, 5 * 60, 22 * 60))
    }

    @Test
    fun isNight_sameStartAndEnd_isAlwaysNight() {
        //
        assertTrue(DayNight.isNight(0, 360, 360))
        assertTrue(DayNight.isNight(720, 360, 360))
    }

    @Test
    fun minuteOfDay_clampsAndFormats() {
        assertEquals(0, DayNight.minuteOfDay(0, 0))
        assertEquals(1439, DayNight.minuteOfDay(23, 59))
        assertEquals(360, DayNight.minuteOfDay(6, 0))
        assertEquals("06:00", DayNight.formatMinute(360))
        assertEquals("18:30", DayNight.formatMinute(1110))
    }

    @Test
    fun normalizeMode_onlyAcceptsTimeAndSensor() {
        //
        assertEquals(DayNight.MODE_FOLLOW_AMAP, DayNight.normalizeMode(0))
        assertEquals(DayNight.MODE_FOLLOW_AMAP, DayNight.normalizeMode(7))
        assertEquals(DayNight.MODE_FOLLOW_AMAP, DayNight.normalizeMode(-1))
        assertEquals(DayNight.MODE_FOLLOW_TIME, DayNight.normalizeMode(1))
        assertEquals(DayNight.MODE_FOLLOW_SENSOR, DayNight.normalizeMode(2))
    }

    @Test
    fun modeConstants_matchReference() {
        //
        assertEquals(0, DayNight.MODE_FOLLOW_AMAP)
        assertEquals(1, DayNight.MODE_FOLLOW_TIME)
        assertEquals(2, DayNight.MODE_FOLLOW_SENSOR)
    }

    @Test
    fun resolveNightUi_disabled_keepsLastApplied() {
        // computeDesiredNightUi（:387-398）：关闭时回落到上次应用值
        assertTrue(DayNight.resolveNightUi(false, DayNight.MODE_FOLLOW_TIME, 720, 360, 1110, lastAppliedNightUi = null))
        assertTrue(DayNight.resolveNightUi(false, DayNight.MODE_FOLLOW_TIME, 720, 360, 1110, lastAppliedNightUi = true))
        assertFalse(DayNight.resolveNightUi(false, DayNight.MODE_FOLLOW_TIME, 720, 360, 1110, lastAppliedNightUi = false))
    }

    @Test
    fun resolveNightUi_followTime_usesSchedule() {
        assertFalse(DayNight.resolveNightUi(true, DayNight.MODE_FOLLOW_TIME, 720, 360, 1110))
        assertTrue(DayNight.resolveNightUi(true, DayNight.MODE_FOLLOW_TIME, 1200, 360, 1110))
    }

    @Test
    fun resolveNightUi_followAmap_prefersAmapSignal() {
        // computeDesiredNightUi（:342-368）：高德信号优先，其次系统值，最后上次应用值
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = true,
                sensorNightUi = false,
            )
        )
        assertFalse(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 1200,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = false,
                sensorNightUi = false,
            )
        )
    }

    @Test
    fun resolveNightUi_followAmap_withoutSignals_fallsBackToNight() {
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = null,
                sensorNightUi = null,
                lastAppliedNightUi = null,
            )
        )
    }

    @Test
    fun resolveNightUi_followSensor_usesSensorValue() {
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_SENSOR,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                sensorNightUi = true,
            )
        )
        assertFalse(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_SENSOR,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                sensorNightUi = false,
            )
        )
    }

    /**
     * 「跟随高德」时**高德广播的信号优先**于车机/系统的值。
     *
     * 这一条是本次修复的核心：以前桌面没有接高德的信号，
     * 「跟随高德」读的是系统 uiMode（这台车机上没人写，永远是死值），
     * 所以桌面跟高德怎么切都对不上。
     */
    @Test
    fun resolveNightUi_followAmap_amapSignalBeatsCarAndSystem() {
        // 高德说夜间、车机与系统都说白天 → 听高德的
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = true,
                sensorNightUi = false,
                systemNightUi = false,
            )
        )
        // 高德说白天、车机与系统都说夜间 → 还是听高德的
        assertFalse(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 1200,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = false,
                sensorNightUi = true,
                systemNightUi = true,
            )
        )
    }

    /** 没有高德信号时，按「车机光感 → 车机日间模式 → 系统 UI」回落。 */
    @Test
    fun resolveNightUi_followAmap_withoutAmapSignal_fallsBackToSensorThenCarThenSystem() {
        // 光感有值 → 用光感
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = null,
                sensorNightUi = true,
                carNightUi = false,
                systemNightUi = false,
            )
        )
        // 光感读不到 → 用车机日间模式功能值
        assertFalse(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = null,
                sensorNightUi = null,
                carNightUi = false,
                systemNightUi = true,
            )
        )
        // 光感与车机都读不到 → 用系统 UI 夜间态
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_AMAP,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = null,
                sensorNightUi = null,
                carNightUi = null,
                systemNightUi = true,
            )
        )
    }

    /**
     * 「跟随光感」优先用**真·光感**（AdaptAPI 昼夜通道），读不到才往下回落。
     *
     * 这一条是「跟随光感」能真正工作的关键：车机日间模式功能值在真车上恒为 AUTO，
     * 真正的明暗只能从光感通道拿。
     */
    @Test
    fun resolveNightUi_followSensor_prefersLightSensor() {
        // 光感说夜间、车机与系统都说白天 → 听光感的
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_SENSOR,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                sensorNightUi = true,
                carNightUi = false,
                systemNightUi = false,
            )
        )
        // 光感说白天、车机与系统都说夜间 → 还是听光感的
        assertFalse(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_SENSOR,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                sensorNightUi = false,
                carNightUi = true,
                systemNightUi = true,
            )
        )
        // 光感读不到 → 落到车机日间模式功能值
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_SENSOR,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                sensorNightUi = null,
                carNightUi = true,
                systemNightUi = false,
            )
        )
    }

    /** 「跟随光感」不听高德的信号。 */
    @Test
    fun resolveNightUi_followSensor_ignoresAmapSignal() {
        assertTrue(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_SENSOR,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = false,
                sensorNightUi = true,
                carNightUi = false,
                systemNightUi = false,
            )
        )
        assertFalse(
            DayNight.resolveNightUi(
                enabled = true,
                mode = DayNight.MODE_FOLLOW_SENSOR,
                minuteOfDay = 720,
                dayStartMin = 360,
                nightStartMin = 1110,
                amapNightUi = true,
                sensorNightUi = false,
                carNightUi = false,
                systemNightUi = false,
            )
        )
    }
}
