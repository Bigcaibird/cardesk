package com.cardesk.launcher.core

import com.cardesk.launcher.vehicle.AdaptapiSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutomationEngineTest {

    @Before
    fun setUp() {
        AutomationEngine.resetState()
    }

    @Test
    fun `temperature thresholds trigger seat heat and vent with edge and cooldown`() {
        val presets = ScheduledTasks.examplePresets()
        val heatTask = presets.first { it.condition == ScheduledTasks.COND_TEMP_LT }
        val ventTask = presets.first { it.condition == ScheduledTasks.COND_TEMP_GT }
        val rules = listOf(heatTask, ventTask)

        // Normal temp: nothing fires
        assertTrue(AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 20f), 1_000L).isEmpty())

        // Cold temp <= 10C: seat heat fires
        val coldFired = AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 9f), 2_000L)
        assertEquals(listOf(heatTask.id), coldFired.map { it.id })

        // Still cold: edge already true, does not re-fire
        assertTrue(AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 8f), 3_000L).isEmpty())

        // Warm up > 10C, then drop again within cooldown -> suppressed
        assertTrue(AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 15f), 4_000L).isEmpty())
        assertTrue(AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 9f), 5_000L).isEmpty())

        // Warm up > 10C, then drop after 300s cooldown -> fires again
        assertTrue(AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 15f), 305_000L).isEmpty())
        val refired = AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 9f), 306_000L)
        assertEquals(listOf(heatTask.id), refired.map { it.id })

        // Hot temp >= 28C: seat vent fires
        val hotFired = AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(tempC = 29f), 400_000L)
        assertEquals(listOf(ventTask.id), hotFired.map { it.id })
    }

    @Test
    fun `aqi thresholds switch inner and outside circulation`() {
        val presets = ScheduledTasks.examplePresets()
        val innerTask = presets.first { it.condition == ScheduledTasks.COND_AQI_GT }
        val outsideTask = presets.first { it.condition == ScheduledTasks.COND_AQI_LT }
        val rules = listOf(innerTask, outsideTask)

        // AQI = 120 -> inner circulation
        val dirty = AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(aqiIndoor = 120), 1_000L)
        assertEquals(listOf(innerTask.id), dirty.map { it.id })

        // AQI = 40 -> outside circulation
        val clean = AutomationEngine.evaluate(rules, AutomationEngine.Snapshot(aqiIndoor = 40), 2_000L)
        assertEquals(listOf(outsideTask.id), clean.map { it.id })
    }

    @Test
    fun `presets round trip through json`() {
        val presets = ScheduledTasks.examplePresets()
        val decoded = ScheduledTasks.decode(ScheduledTasks.encode(presets))
        assertEquals(presets.size, decoded.size)
        assertEquals(10f, decoded[0].threshold, 0.01f)
        assertEquals(ScheduledTasks.ACTION_SEAT_HEAT_DRIVER, decoded[0].action)
        assertEquals(100f, decoded[2].threshold, 0.01f)
        assertEquals(ScheduledTasks.ACTION_CIRC_INNER, decoded[2].action)
        val lockPreset = decoded.first { it.condition == ScheduledTasks.COND_LOCK_CAR }
        assertEquals(ScheduledTasks.ACTION_CLOSE_WINDOWS, lockPreset.action)
        assertEquals(ScheduledTasks.COOLDOWN_POWER_EVENT, lockPreset.cooldownSeconds)
    }

    @Test
    fun `ignition off triggers only on powered to off transition`() {
        val rule = ScheduledTask(
            id = "ign-off-close",
            condition = ScheduledTasks.COND_IGNITION_OFF,
            action = ScheduledTasks.ACTION_CLOSE_WINDOWS,
            cooldownSeconds = ScheduledTasks.COOLDOWN_POWER_EVENT,
        )
        val rules = listOf(rule)

        // Booting directly into CONVENIENCE (unlocking parked car) must NOT falsely fire
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_CONVENIENCE),
                1_000L,
            ).isEmpty(),
        )

        // Start driving -> still no ignition-off trigger
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_DRIVING),
                2_000L,
            ).isEmpty(),
        )

        // Brief UNKNOWN reading must not lose previous DRIVING state
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_UNKNOWN),
                2_500L,
            ).isEmpty(),
        )

        // Turn off engine: DRIVING -> CONVENIENCE fires once
        val fired = AutomationEngine.evaluate(
            rules,
            AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_CONVENIENCE),
            3_000L,
        )
        assertEquals(listOf(rule.id), fired.map { it.id })

        // Staying in CONVENIENCE does not re-fire
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_CONVENIENCE),
                4_000L,
            ).isEmpty(),
        )

        // ACTIVE -> INACTIVE after 10s cooldown fires again
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_ACTIVE),
                14_000L,
            ).isEmpty(),
        )
        val firedAgain = AutomationEngine.evaluate(
            rules,
            AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_INACTIVE),
            15_000L,
        )
        assertEquals(listOf(rule.id), firedAgain.map { it.id })

        // Unlocking parked car: ABANDON -> INACTIVE -> CONVENIENCE (upward) must NOT fire
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_ABANDON),
                30_000L,
            ).isEmpty(),
        )
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_INACTIVE),
                31_000L,
            ).isEmpty(),
        )
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_CONVENIENCE),
                32_000L,
            ).isEmpty(),
        )

        // Powering off from CONVENIENCE -> INACTIVE ("已熄火") MUST fire
        val convenienceToOff = AutomationEngine.evaluate(
            rules,
            AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_INACTIVE),
            33_000L,
        )
        assertEquals(listOf(rule.id), convenienceToOff.map { it.id })
    }

    @Test
    fun `lock car triggers only on transition to abandon`() {
        val rule = ScheduledTask(
            id = "lock-close",
            condition = ScheduledTasks.COND_LOCK_CAR,
            action = ScheduledTasks.ACTION_CLOSE_WINDOWS,
            cooldownSeconds = ScheduledTasks.COOLDOWN_POWER_EVENT,
        )
        val rules = listOf(rule)

        // Booting directly in ABANDON must NOT falsely fire
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_ABANDON),
                1_000L,
            ).isEmpty(),
        )

        // Unlock & enter car -> CONVENIENCE
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_CONVENIENCE),
                2_000L,
            ).isEmpty(),
        )

        // Leave & lock car: CONVENIENCE -> ABANDON fires once
        val fired = AutomationEngine.evaluate(
            rules,
            AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_ABANDON),
            3_000L,
        )
        assertEquals(listOf(rule.id), fired.map { it.id })

        // Staying in ABANDON does not re-fire
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_ABANDON),
                4_000L,
            ).isEmpty(),
        )

        // Re-enter and lock again after 10s cooldown -> fires again
        assertTrue(
            AutomationEngine.evaluate(
                rules,
                AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_CONVENIENCE),
                14_000L,
            ).isEmpty(),
        )
        val firedAgain = AutomationEngine.evaluate(
            rules,
            AutomationEngine.Snapshot(ignitionState = AdaptapiSensors.IGNITION_ABANDON),
            15_000L,
        )
        assertEquals(listOf(rule.id), firedAgain.map { it.id })
    }

    @Test
    fun `ignition off and lock car labels and json round trip`() {
        val t1 = ScheduledTask(
            id = "t-ign",
            condition = ScheduledTasks.COND_IGNITION_OFF,
            action = ScheduledTasks.ACTION_CLOSE_WINDOWS,
            cooldownSeconds = ScheduledTasks.COOLDOWN_POWER_EVENT,
        )
        val t2 = ScheduledTask(
            id = "t-lock",
            condition = ScheduledTasks.COND_LOCK_CAR,
            action = ScheduledTasks.ACTION_CLOSE_WINDOWS,
            cooldownSeconds = ScheduledTasks.COOLDOWN_POWER_EVENT,
        )
        assertEquals("车辆熄火时", ScheduledTasks.conditionLabel(t1))
        assertEquals("离车锁车时", ScheduledTasks.conditionLabel(t2))
        assertEquals("关闭所有车窗", ScheduledTasks.actionLabel(t1))
        val back = ScheduledTasks.decode(ScheduledTasks.encode(listOf(t1, t2)))
        assertEquals(listOf(t1, t2), back)
    }
}
