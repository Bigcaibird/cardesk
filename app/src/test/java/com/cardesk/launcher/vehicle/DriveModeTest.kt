package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 驾驶模式短标签单测。
 */
class DriveModeTest {

    @Test
    fun shortLabel_mapsSelectionConstants() {
        assertEquals("经济", DriveMode.shortLabelForValue(DriveMode.SELECTION_ECO))
        assertEquals("舒适", DriveMode.shortLabelForValue(DriveMode.SELECTION_COMFORT))
        assertEquals("运动", DriveMode.shortLabelForValue(DriveMode.SELECTION_SPORT))
        assertEquals("越野", DriveMode.shortLabelForValue(DriveMode.SELECTION_XC))
        assertEquals("陡坡缓降", DriveMode.shortLabelForValue(DriveMode.SELECTION_HDC))
        assertEquals("电动", DriveMode.shortLabelForValue(DriveMode.SELECTION_PURE))
        assertEquals("混动", DriveMode.shortLabelForValue(DriveMode.SELECTION_HYBRID))
        assertEquals("动力", DriveMode.shortLabelForValue(DriveMode.SELECTION_POWER))
        assertEquals("雪地", DriveMode.shortLabelForValue(DriveMode.SELECTION_SNOW))
        assertEquals("经济PHEV", DriveMode.shortLabelForValue(DriveMode.SELECTION_ECO_PHEV))
        assertEquals("赛道", DriveMode.shortLabelForValue(DriveMode.SELECTION_TRACK))
        assertEquals("个性", DriveMode.shortLabelForValue(DriveMode.SELECTION_CUSTOM))
    }

    @Test
    fun shortLabel_mapsLegacyInterfaceIndexes() {
        assertEquals("经济", DriveMode.shortLabelForValue(1))
        assertEquals("舒适", DriveMode.shortLabelForValue(2))
        assertEquals("运动", DriveMode.shortLabelForValue(3))
        assertEquals("越野", DriveMode.shortLabelForValue(4))
        assertEquals("陡坡缓降", DriveMode.shortLabelForValue(5))
        assertEquals("电动", DriveMode.shortLabelForValue(6))
        assertEquals("混动", DriveMode.shortLabelForValue(7))
        assertEquals("动力", DriveMode.shortLabelForValue(8))
        assertEquals("赛道", DriveMode.shortLabelForValue(9))
        assertEquals("经济PHEV", DriveMode.shortLabelForValue(10))
        assertEquals("个性", DriveMode.shortLabelForValue(11))
    }

    @Test
    fun shortLabel_unknownValueIsNull() {
        assertNull(DriveMode.shortLabelForValue(0))
        assertNull(DriveMode.shortLabelForValue(-1))
        assertNull(DriveMode.shortLabelForValue(123456))
    }

    @Test
    fun currentShortLabel_fallsBackToStandby() {
        assertEquals("待命", DriveMode.currentShortLabel(0))
        assertEquals("待命", DriveMode.currentShortLabel(-5))
        assertEquals("运动", DriveMode.currentShortLabel(DriveMode.SELECTION_SPORT))
    }

    @Test
    fun fullLabel_matchesReferenceCatalog() {
        assertEquals("经济模式", DriveMode.fullLabel(DriveMode.SELECTION_ECO))
        assertEquals("赛道模式", DriveMode.fullLabel(DriveMode.SELECTION_TRACK))
        assertEquals("个性化模式", DriveMode.fullLabel(DriveMode.SELECTION_CUSTOM))
        assertEquals("经济模式PHEV", DriveMode.fullLabel(DriveMode.SELECTION_ECO_PHEV))
        assertEquals("标准模式", DriveMode.fullLabel(DriveMode.SELECTION_COMFORT_HEV))
        assertEquals("雪地模式", DriveMode.fullLabel(DriveMode.SELECTION_SNOW))
    }

    @Test
    fun resolveSupportedModes_sortsDeduplicatesAndFiltersInvalid() {
        val raw = intArrayOf(
            DriveMode.SELECTION_XC,
            DriveMode.READ_ERROR,
            DriveMode.SELECTION_SPORT,
            DriveMode.SELECTION_CUSTOM,
            999999,
            DriveMode.SELECTION_ECO,
            DriveMode.SELECTION_SPORT,
            DriveMode.SELECTION_COMFORT,
        )
        val resolved = DriveMode.resolveSupportedModes(raw)
        org.junit.Assert.assertArrayEquals(
            intArrayOf(
                DriveMode.SELECTION_ECO,
                DriveMode.SELECTION_COMFORT,
                DriveMode.SELECTION_SPORT,
                DriveMode.SELECTION_CUSTOM,
                DriveMode.SELECTION_XC,
            ),
            resolved,
        )
    }

    @Test
    fun resolveSupportedModes_fallsBackToDefaultWhenEmptyOrNull() {
        org.junit.Assert.assertArrayEquals(DriveMode.SUPPORTED_VALUES, DriveMode.resolveSupportedModes(null))
        org.junit.Assert.assertArrayEquals(DriveMode.SUPPORTED_VALUES, DriveMode.resolveSupportedModes(intArrayOf()))
        org.junit.Assert.assertArrayEquals(
            DriveMode.SUPPORTED_VALUES,
            DriveMode.resolveSupportedModes(intArrayOf(DriveMode.READ_ERROR, DriveMode.READ_UNKNOWN, 0)),
        )
    }

    @Test
    fun resolveSupportedModes_includesValidCurrentModeIfMissing() {
        val resolved = DriveMode.resolveSupportedModes(
            intArrayOf(DriveMode.SELECTION_ECO, DriveMode.SELECTION_COMFORT),
            currentRaw = DriveMode.SELECTION_HYBRID,
        )
        org.junit.Assert.assertArrayEquals(
            intArrayOf(
                DriveMode.SELECTION_ECO,
                DriveMode.SELECTION_COMFORT,
                DriveMode.SELECTION_HYBRID,
            ),
            resolved,
        )
    }

    @Test
    fun vehicleController_refreshesDynamicSupportedModesFromGateway() {
        val gw = MockVehicleGateway().apply {
            simSupportedDriveModes = intArrayOf(
                DriveMode.SELECTION_POWER,
                DriveMode.SELECTION_PURE,
                DriveMode.SELECTION_HYBRID,
                DriveMode.SELECTION_COMFORT_HEV,
            )
        }
        VehicleController.resetSupportedDriveModesCache()
        VehicleController.maybeRefreshSupportedDriveModes(gw, force = true)
        org.junit.Assert.assertArrayEquals(
            intArrayOf(
                DriveMode.SELECTION_COMFORT_HEV,
                DriveMode.SELECTION_PURE,
                DriveMode.SELECTION_HYBRID,
                DriveMode.SELECTION_POWER,
            ),
            VehicleController.supportedDriveModes(),
        )
        VehicleController.resetSupportedDriveModesCache()
    }
}
