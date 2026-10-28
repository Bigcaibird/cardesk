package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编解码规则的单测：这些值与车机固件约定绑定，改动必须同步改 ID 表。 */
class HvacCodecTest {

    @Test
    fun tempRaw_roundTripsHalfDegrees() {
        assertEquals(46, HvacCodec.tempRaw(23f))
        assertEquals(47, HvacCodec.tempRaw(23.5f))
        assertEquals(23.5f, HvacCodec.tempFromRaw(47), 0.001f)
    }

    @Test
    fun clampTemp_limitsToProtocolRange() {
        assertEquals(HvacIds.TEMP_MIN, HvacCodec.clampTemp(10f), 0.001f)
        assertEquals(HvacIds.TEMP_MAX, HvacCodec.clampTemp(40f), 0.001f)
        assertEquals(25f, HvacCodec.clampTemp(25f), 0.001f)
    }

    @Test
    fun fanLevel_acceptsBothRawAndAutoMarker() {
        assertEquals(0, HvacCodec.fanLevel(HvacIds.FAN_SPEED))
        assertEquals(3, HvacCodec.fanLevel(HvacIds.FAN_SPEED + 3))
        assertEquals(HvacIds.FAN_MAX, HvacCodec.fanLevel(HvacIds.FAN_SPEED_AUTO))
        assertEquals(HvacIds.FAN_MIN, HvacCodec.fanLevel(999))
    }

    @Test
    fun fanRaw_isInverseOfFanLevel() {
        for (level in 0..HvacIds.FAN_MAX) {
            assertEquals(level, HvacCodec.fanLevel(HvacCodec.fanRaw(level)))
        }
    }

    @Test
    fun circulation_normalizesVendorEncodings() {
        assertEquals(0, HvacCodec.normalizeCirculation(HvacIds.CIRC_INNER))
        assertEquals(2, HvacCodec.normalizeCirculation(HvacIds.CIRC_AUTO))
        assertEquals(1, HvacCodec.normalizeCirculation(HvacIds.CIRC_OUTSIDE))
        // 旧固件直接回 0/1/2/3
        assertEquals(0, HvacCodec.normalizeCirculation(1))
        assertEquals(1, HvacCodec.normalizeCirculation(2))
        assertEquals(2, HvacCodec.normalizeCirculation(3))
    }

    @Test
    fun blowing_normalizesBothEncodings() {
        assertEquals(0, HvacCodec.normalizeBlowing(HvacIds.BLOWING_FACE))
        assertEquals(6, HvacCodec.normalizeBlowing(HvacIds.BLOWING_ALL))
        // 旧固件的裸 0..6 是另一套顺序（normalizeBlowingMode:365-376）
        assertEquals(1, HvacCodec.normalizeBlowing(0))   // 0 -> 吹脚
        assertEquals(0, HvacCodec.normalizeBlowing(1))   // 1 -> 吹脸
        assertEquals(3, HvacCodec.normalizeBlowing(2))   // 2 -> 吹窗
        assertEquals(5, HvacCodec.normalizeBlowing(3))   // 3 -> 脚+窗
        assertEquals(2, HvacCodec.normalizeBlowing(4))   // 4 -> 脸+脚
        assertEquals(4, HvacCodec.normalizeBlowing(5))   // 5 -> 脸+窗
        assertEquals(6, HvacCodec.normalizeBlowing(6))   // 6 -> 全吹
        assertEquals(2, HvacCodec.normalizeBlowing(-5))
    }

    @Test
    fun blowingMask_roundTripsWithIndex() {
        // —— face=1 / leg=2 / window=4
        assertEquals(1, HvacCodec.blowingMask(0))
        assertEquals(2, HvacCodec.blowingMask(1))
        assertEquals(3, HvacCodec.blowingMask(2))
        assertEquals(7, HvacCodec.blowingMask(6))
        for (i in 0..6) {
            assertEquals(i, HvacCodec.blowingIndex(HvacCodec.blowingMask(i)))
        }
    }

    @Test
    fun fanLevel_acceptsBareLevels() {
        // parseFanSpeed:415-418 —— 裸 1..7 也是有效档位
        for (level in 1..7) {
            assertEquals(level, HvacCodec.fanLevel(level))
        }
        assertEquals(0, HvacCodec.fanLevel(0))
        assertEquals(0, HvacCodec.fanLevel(HvacIds.FAN_SPEED))
    }

    @Test
    fun seatLevel_acceptsRawAndBaseOffset() {
        assertEquals(0, HvacCodec.seatLevel(HvacIds.SEAT_HEAT, 0))
        assertEquals(2, HvacCodec.seatLevel(HvacIds.SEAT_HEAT, HvacIds.SEAT_HEAT + 2))
        assertEquals(3, HvacCodec.seatLevel(HvacIds.SEAT_VENT, 3))
        // seatLevelFromRaw:635-646 —— 越界值归 0，不是归最大档
        assertEquals(0, HvacCodec.seatLevel(HvacIds.SEAT_HEAT, HvacIds.SEAT_HEAT + 5))
    }

    @Test
    fun seatRaw_zeroMeansOff() {
        assertEquals(0, HvacCodec.seatRaw(HvacIds.SEAT_HEAT, 0))
        assertEquals(HvacIds.SEAT_HEAT + 1, HvacCodec.seatRaw(HvacIds.SEAT_HEAT, 1))
        assertEquals(HvacIds.SEAT_HEAT + 3, HvacCodec.seatRaw(HvacIds.SEAT_HEAT, 9))
    }

    @Test
    fun blowLabels_matchConstantCount() {
        assertEquals(7, HvacIds.BLOW_LABELS.size)
        assertTrue(HvacIds.BLOW_LABELS.all { it.isNotBlank() })
        assertFalse(HvacIds.BLOW_LABELS[0] == HvacIds.BLOW_LABELS[1])
    }

    // ------------------------------------------------- AOSP Car 通道的值翻译

    @Test
    fun carLibValue_switchesBecomeBooleans() {
        assertEquals(true, HvacCodec.toCarLibValue(HvacIds.POWER, 1))
        assertEquals(false, HvacCodec.toCarLibValue(HvacIds.POWER, 0))
        assertEquals(true, HvacCodec.toCarLibValue(HvacIds.AC, 1))
        assertEquals(true, HvacCodec.toCarLibValue(HvacIds.AUTO, 1))
        assertEquals(true, HvacCodec.toCarLibValue(HvacIds.DEFROST_REAR, 1))
        assertEquals(false, HvacCodec.toCarLibValue(HvacIds.DEFROST_FRONT_MAX, 0))
    }

    @Test
    fun carLibValue_fanSpeedBecomesPlainLevel() {
        // AdaptAPI 写的是 FAN_SPEED + level，AOSP 要的是 0..7
        assertEquals(3, HvacCodec.toCarLibValue(HvacIds.FAN_SPEED, HvacIds.FAN_SPEED + 3))
        assertEquals(0, HvacCodec.toCarLibValue(HvacIds.FAN_SPEED, 0))
        assertEquals(HvacIds.FAN_MAX, HvacCodec.toCarLibValue(HvacIds.FAN_SPEED, HvacIds.FAN_SPEED_AUTO))
    }

    @Test
    fun carLibValue_circulationBecomesRecircBoolean() {
        // 内循环 = RECIRC on
        assertEquals(true, HvacCodec.toCarLibValue(HvacIds.CIRCULATION, HvacIds.CIRC_INNER))
        assertEquals(false, HvacCodec.toCarLibValue(HvacIds.CIRCULATION, HvacIds.CIRC_OUTSIDE))
    }

    @Test
    fun carLibValue_blowingBecomesEnumIndex() {
        assertEquals(0, HvacCodec.toCarLibValue(HvacIds.BLOWING, HvacIds.BLOWING_FACE))
        assertEquals(6, HvacCodec.toCarLibValue(HvacIds.BLOWING, HvacIds.BLOWING_ALL))
    }

    @Test
    fun carLibValue_temperatureBecomesCelsius() {
        assertEquals(23.5f, HvacCodec.toCarLibValue(HvacIds.TEMP, 47) as Float, 0.001f)
    }

    @Test
    fun carLibValue_seatBecomesPlainLevel() {
        assertEquals(2, HvacCodec.toCarLibValue(HvacIds.SEAT_HEAT, HvacIds.SEAT_HEAT + 2))
        assertEquals(0, HvacCodec.toCarLibValue(HvacIds.SEAT_VENT, 0))
    }

    @Test
    fun fromCarLibValue_isInverseOfToCarLibValue() {
        assertEquals(HvacIds.FAN_SPEED + 3, HvacCodec.fromCarLibValue(HvacIds.FAN_SPEED, 3))
        assertEquals(0, HvacCodec.fromCarLibValue(HvacIds.FAN_SPEED, 0))
        assertEquals(HvacIds.CIRC_INNER, HvacCodec.fromCarLibValue(HvacIds.CIRCULATION, true))
        assertEquals(HvacIds.CIRC_OUTSIDE, HvacCodec.fromCarLibValue(HvacIds.CIRCULATION, false))
        assertEquals(1, HvacCodec.fromCarLibValue(HvacIds.POWER, true))
        assertEquals(0, HvacCodec.fromCarLibValue(HvacIds.POWER, false))
        assertEquals(HvacIds.BLOWING_WINDOW, HvacCodec.fromCarLibValue(HvacIds.BLOWING, 2))
        assertEquals(HvacIds.SEAT_HEAT + 2, HvacCodec.fromCarLibValue(HvacIds.SEAT_HEAT, 2))
    }

    @Test
    fun fromCarLibValue_temperatureUsesCelsiusRaw() {
        assertEquals(47, HvacCodec.fromCarLibValue(HvacIds.TEMP, 23.5f))
    }

    @Test
    fun canonicalFunc_mapsFrontMaxToFrontReadId() {
        // 前除霜写用 0x10040200（FRONT_MAX），AOSP 通道统一映射到 0x10040100
        assertEquals(HvacIds.DEFROST_FRONT, HvacCodec.canonicalFunc(HvacIds.DEFROST_FRONT_MAX))
        assertEquals(HvacIds.DEFROST_REAR, HvacCodec.canonicalFunc(HvacIds.DEFROST_REAR))
        assertEquals(HvacIds.POWER, HvacCodec.canonicalFunc(HvacIds.POWER))
    }

    // ------------------------------------------------- 属性号与对齐

    @Test
    fun hvacIds_matchReferenceConstants() {
        // 十进制常量
        assertEquals(268501248, HvacIds.POWER)
        assertEquals(268501504, HvacIds.AUTO)
        assertEquals(268501760, HvacIds.AC)
        assertEquals(268566784, HvacIds.FAN_SPEED)
        assertEquals(268566794, HvacIds.FAN_SPEED_AUTO)
        assertEquals(268632320, HvacIds.CIRCULATION)
        assertEquals(268632321, HvacIds.CIRC_INNER)
        assertEquals(268632322, HvacIds.CIRC_OUTSIDE)
        assertEquals(268632323, HvacIds.CIRC_AUTO)
        assertEquals(268697856, HvacIds.DEFROST_FRONT)
        assertEquals(268698112, HvacIds.DEFROST_FRONT_MAX)
        assertEquals(268698368, HvacIds.DEFROST_REAR)
        assertEquals(268763392, HvacIds.SEAT_VENT)
        assertEquals(268763648, HvacIds.SEAT_HEAT)
        assertEquals(268828928, HvacIds.TEMP)
        assertEquals(268829952, HvacIds.TEMP_DUAL)
        assertEquals(268894464, HvacIds.BLOWING)
        assertEquals(268894465, HvacIds.BLOWING_FACE)
    }

    @Test
    fun zones_matchReferenceConstants() {
        assertEquals(1, HvacIds.ZONE_DRIVER)
        assertEquals(4, HvacIds.ZONE_PASSENGER)
        // 全局写入全部用 zone=1，不是 0
        assertEquals(1, HvacIds.ZONE_GLOBAL)
        assertEquals(2, HvacIds.DEFROST_ZONES[0])
        assertEquals(1, HvacIds.DEFROST_ZONES[1])
        assertEquals(0, HvacIds.DEFROST_ZONES[2])
    }
}
