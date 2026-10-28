package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空调业务逻辑单测：全部跑在 JVM 上，不依赖 Android framework。
 * 覆盖乐观更新、写后屏蔽窗口、联动规则、互斥规则。
 */
class HvacLogicTest {

    private var now = 10_000L
    private val writes = mutableListOf<Triple<Int, Int, Int>>()
    private val tempWrites = mutableListOf<Pair<Int, Float>>()
    private val defrostWrites = mutableListOf<Pair<Int, Boolean>>()

    private fun logic(writeOk: Boolean = true) = HvacLogic(
        clock = { now },
        write = { func, zone, value ->
            writes.add(Triple(func, zone, value))
            writeOk
        },
        writeTempCelsius = { zone, celsius ->
            tempWrites.add(zone to celsius)
            writeOk
        },
        writeDefrost = { func, on ->
            defrostWrites.add(func to on)
            writeOk
        },
    )

    private fun lastWrite() = writes.last()

    @Test
    fun powerOn_raisesFanToAtLeastOneAndWritesFanSpeed() {
        // —— 开机且风量 < 1 时
        // 风量置 1 **并真的写一次风量属性**
        val h = logic()
        h.togglePower()
        assertTrue(h.state.power)
        assertEquals(HvacIds.FAN_MIN_STEP, h.state.fanLevel)
        assertEquals(HvacIds.POWER, writes[0].first)
        assertEquals(1, writes[0].third)
        assertEquals(HvacIds.FAN_SPEED, writes[1].first)
        assertEquals(HvacCodec.fanRaw(HvacIds.FAN_MIN_STEP), writes[1].third)
    }

    @Test
    fun acAndAuto_onlyRaiseFanLocallyWithoutWritingIt() {
        // toggleAc:929-935 / toggleAuto:944-950 —— 未上电时上电、风量最低 1
        // 但**不写风量属性**（只有 togglePower 会写）
        val h = logic()
        h.toggleAc()
        assertTrue(h.state.power)
        assertEquals(HvacIds.FAN_MIN_STEP, h.state.fanLevel)
        assertTrue(writes.none { it.first == HvacIds.FAN_SPEED })

        val h2 = logic()
        h2.toggleAuto()
        assertTrue(h2.state.power)
        assertEquals(HvacIds.FAN_MIN_STEP, h2.state.fanLevel)
        assertTrue(writes.none { it.first == HvacIds.FAN_SPEED })
    }

    @Test
    fun powerOff_clearsAcAutoAndFan() {
        val h = logic()
        h.togglePower()
        h.toggleAc()
        h.toggleAuto()
        h.togglePower()
        assertFalse(h.state.power)
        assertFalse(h.state.ac)
        assertFalse(h.state.auto)
        assertEquals(0, h.state.fanLevel)
        assertEquals(0, lastWrite().third)
    }

    @Test
    fun acImplicitlyPowersOn() {
        val h = logic()
        h.toggleAc()
        assertTrue(h.state.power)
        assertTrue(h.state.ac)
    }

    @Test
    fun stepTemp_usesFloatChannelAndClamps() {
        val h = logic()
        h.stepTemp(HvacIds.ZONE_DRIVER, 0.5f)
        // 初始温度是 23.5℃
        assertEquals(24f, h.state.tempDriver, 0.001f)
        assertEquals(HvacIds.ZONE_DRIVER, tempWrites.last().first)
        assertEquals(24f, tempWrites.last().second, 0.001f)

        h.stepTemp(HvacIds.ZONE_DRIVER, 100f)
        assertEquals(HvacIds.TEMP_MAX, h.state.tempDriver, 0.001f)

        h.stepTemp(HvacIds.ZONE_DRIVER, -100f)
        assertEquals(HvacIds.TEMP_MIN, h.state.tempDriver, 0.001f)
    }

    @Test
    fun clampTemp_snapsToHalfDegree() {
        // —— 夹范围后再吸附到 0.5℃
        assertEquals(23.5f, HvacCodec.clampTemp(23.3f), 0.001f)
        assertEquals(23.5f, HvacCodec.clampTemp(23.7f), 0.001f)
        assertEquals(24f, HvacCodec.clampTemp(23.8f), 0.001f)
        assertEquals(16f, HvacCodec.clampTemp(10f), 0.001f)
        assertEquals(32f, HvacCodec.clampTemp(40f), 0.001f)
    }

    @Test
    fun syncedTemp_movesBothSidesFromDriver() {
        val h = logic()
        h.toggleSync()
        assertTrue(h.state.tempSynced)
        // 开启同步时先把主驾温度写回车端（toggleTempSync:1014-1017）
        assertTrue(tempWrites.isNotEmpty())
        h.stepTemp(HvacIds.ZONE_PASSENGER, 1f)
        assertEquals(24.5f, h.state.tempDriver, 0.001f)
        assertEquals(24.5f, h.state.tempPassenger, 0.001f)
        // 同步态下调温后必定再写一次双区标志（stepSyncedTemp:856）
        assertEquals(HvacIds.TEMP_DUAL, writes.last().first)
        assertEquals(1, writes.last().third)
    }

    @Test
    fun stepFan_clampsAndSendsRawLevel() {
        val h = logic()
        h.stepFan(100)
        assertEquals(HvacIds.FAN_MAX, h.state.fanLevel)
        assertEquals(HvacCodec.fanRaw(HvacIds.FAN_MAX), lastWrite().third)
        // stepFanSpeed：档位下界是 1（0 只在「关风量」这条路上出现）
        h.stepFan(-100)
        assertEquals(HvacIds.FAN_MIN_STEP, h.state.fanLevel)
    }

    @Test
    fun stepFan_belowMinTurnsFanOffWithSideEffects() {
        // stepFanSpeed:763-788 —— 减档时只有 fanSpeed > 1 才走普通路径
        // fanSpeed <= 1 再减就是「关机 + 风量 0 + AC/AUTO 清零」
        val h = logic()
        h.togglePower()
        h.toggleAc()
        h.toggleAuto()
        h.stepFan(2)     // 升到 3 档
        assertEquals(3, h.state.fanLevel)
        h.stepFan(-1)    // 降到 2
        assertEquals(2, h.state.fanLevel)
        h.stepFan(-1)    // 降到 1
        assertEquals(HvacIds.FAN_MIN_STEP, h.state.fanLevel)
        h.stepFan(-1)    // 再往下走 = 关风量
        assertEquals(0, h.state.fanLevel)
        assertFalse(h.state.power)
        assertFalse(h.state.ac)
        assertFalse(h.state.auto)
    }

    @Test
    fun defrostFront_turnsOnPowerAndMaxFan() {
        val h = logic()
        h.toggleDefrostFront()
        assertTrue(h.state.defrostFront)
        assertTrue(h.state.power)
        assertEquals(HvacIds.FAN_MAX, h.state.fanLevel)
        // 写用
        assertEquals(HvacIds.DEFROST_FRONT_MAX, defrostWrites.last().first)
        assertTrue(defrostWrites.last().second)
        // 除霜走专用写入路径，不走普通三参写入
        assertTrue(writes.none { it.first == HvacIds.DEFROST_FRONT_MAX })
    }

    @Test
    fun defrostRear_usesRearFuncId() {
        val h = logic()
        h.toggleDefrostRear()
        assertTrue(h.state.defrostRear)
        assertEquals(HvacIds.DEFROST_REAR, defrostWrites.last().first)
        assertTrue(defrostWrites.last().second)
        h.toggleDefrostRear()
        assertFalse(h.state.defrostRear)
        assertFalse(defrostWrites.last().second)
    }

    @Test
    fun blowingMode_cyclesAndWritesEncodedValue() {        val h = logic()
        h.setBlowing(0)
        assertEquals(0, h.state.blowing)
        assertEquals(HvacCodec.blowingRaw(0), lastWrite().third)
        h.setBlowing(6)
        assertEquals(6, h.state.blowing)
        // 越界索引被夹紧
        h.setBlowing(99)
        assertEquals(6, h.state.blowing)
    }

    @Test
    fun seatHeat_cyclesAndIsMutuallyExclusiveWithVent() {
        val h = logic()
        h.cycleSeat(HvacIds.ZONE_DRIVER, heat = true)
        assertEquals(1, h.state.seatHeatDriver)
        assertEquals(0, h.state.seatVentDriver)

        // 打开通风会先把加热清零
        h.cycleSeat(HvacIds.ZONE_DRIVER, heat = false)
        assertEquals(1, h.state.seatVentDriver)
        assertEquals(0, h.state.seatHeatDriver)

        h.cycleSeat(HvacIds.ZONE_DRIVER, heat = false)
        h.cycleSeat(HvacIds.ZONE_DRIVER, heat = false)
        assertEquals(3, h.state.seatVentDriver)
        h.cycleSeat(HvacIds.ZONE_DRIVER, heat = false)
        assertEquals(0, h.state.seatVentDriver)

        val clearWrite = writes.filter { it.first == HvacIds.SEAT_HEAT && it.third == 0 }
        assertTrue(clearWrite.isNotEmpty())
    }

    @Test
    fun remoteDuringHold_isIgnored_butAcceptedAfterWindow() {
        val h = logic()
        h.togglePower()
        assertTrue(h.isHolding())

        now += 300
        assertFalse(h.onRemote(HvacIds.POWER, 0, 0))
        assertTrue(h.state.power)

        now += HvacLogic.HOLD_MS
        assertTrue(h.onRemote(HvacIds.POWER, 0, 0))
        assertFalse(h.state.power)
    }

    @Test
    fun failedWrite_doesNotOpenHoldWindow() {
        val h = logic(writeOk = false)
        h.togglePower()
        assertFalse(h.lastWriteOk)
        assertEquals(0, h.writeCount)
        assertFalse(h.isHolding())
        // 乐观更新仍然生效，UI 不卡顿
        assertTrue(h.state.power)
    }

    @Test
    fun writeAttempts_countsEveryAttemptIncludingFailures() {
        val h = logic(writeOk = false)
        assertEquals(0, h.writeAttempts)
        h.togglePower()
        // togglePower 会写电源 + 风量两次
        assertEquals(2, h.writeAttempts)
        assertEquals(0, h.writeCount)
    }

    @Test
    fun noWriteAction_doesNotTouchWriteAttempts() {
        // 吹风模式索引没变时直接返回，不应该被当成一次写入
        val h = logic()
        h.setBlowing(h.state.blowing)
        assertEquals(0, h.writeAttempts)
    }

    @Test
    fun snapshot_appliesRemoteState() {
        val h = logic()
        val changed = h.applySnapshot(
            mapOf(
                HvacIds.POWER to 1,
                HvacIds.AC to 1,
                HvacIds.FAN_SPEED to HvacIds.FAN_SPEED + 4,
                HvacIds.TEMP to HvacCodec.tempRaw(26.5f),
                HvacIds.SEAT_HEAT to HvacIds.SEAT_HEAT + 2,
            ),
        )
        assertTrue(changed)
        assertTrue(h.state.power)
        assertTrue(h.state.ac)
        assertEquals(4, h.state.fanLevel)
        assertEquals(26.5f, h.state.tempDriver, 0.001f)
        assertEquals(2, h.state.seatHeatDriver)
    }

    @Test
    fun emptySnapshot_isNoOp() {
        val h = logic()
        assertFalse(h.applySnapshot(emptyMap()))
    }

    // ---------------------------------------------------------------- MAX 风量

    @Test
    fun maxFan_switchesBetweenMaxAndMid() {
        val h = logic()
        h.toggleMaxFan()
        assertEquals(HvacIds.FAN_MAX, h.state.fanLevel)
        h.toggleMaxFan()
        assertEquals(HvacIds.FAN_MID, h.state.fanLevel)
        h.toggleMaxFan()
        assertEquals(HvacIds.FAN_MAX, h.state.fanLevel)
    }

    @Test
    fun maxFan_powersOnBeforeWritingFanSpeed() {
        val h = logic()
        h.toggleMaxFan()
        // 未开机先 setPower(true)，再写风量
        assertTrue(h.state.power)
        assertEquals(HvacIds.POWER, writes[0].first)
        assertEquals(1, writes[0].third)
        assertEquals(HvacIds.FAN_SPEED, writes[1].first)
    }

    @Test
    fun maxFan_doesNotRewritePowerWhenAlreadyOn() {
        val h = logic()
        h.togglePower()
        writes.clear()
        h.toggleMaxFan()
        assertTrue(writes.none { it.first == HvacIds.POWER })
        assertEquals(HvacIds.FAN_SPEED, lastWrite().first)
    }

    @Test
    fun stepFan_clampsToOneThroughSeven() {
        val h = logic()
        h.stepFan(20)
        assertEquals(HvacIds.FAN_MAX, h.state.fanLevel)
        h.stepFan(-20)
        // 下界是 1（stepFanSpeed 夹 1..7）
        assertEquals(HvacIds.FAN_MIN_STEP, h.state.fanLevel)
    }

    @Test
    fun stepFan_powersOnWhenChangingFanSpeed() {
        val h = logic()
        h.stepFan(1)
        assertTrue(h.state.power)
        assertTrue(writes.any { it.first == HvacIds.POWER })
    }

    // ------------------------------------------------- 与对齐的写入约定

    @Test
    fun globalWrites_useZoneDriverNotZero() {
        // 里所有全局写入都是 setFuncVal(func, 1, value)——zone 传 0 会被固件拒绝
        val h = logic()
        h.togglePower()
        h.toggleAc()
        h.toggleAuto()
        h.toggleCirculation()
        h.stepFan(1)
        h.toggleSync()
        h.setBlowing(2)
        h.cycleSeat(HvacIds.ZONE_DRIVER, heat = true)
        assertTrue(writes.isNotEmpty())
        for (w in writes) {
            assertEquals("func=${w.first} 的写入 zone 必须是 1", HvacIds.ZONE_GLOBAL, w.second)
        }
    }

    @Test
    fun fanOff_writesZeroNotFanSpeedBase() {
        // 关风量写 setFuncVal(FAN_SPEED, 1, 0)
        val h = logic()
        h.togglePower()
        h.stepFan(-10)
        h.stepFan(-1)
        val fanOff = writes.filter { it.first == HvacIds.FAN_SPEED }.last()
        assertEquals(0, fanOff.third)
        assertEquals(HvacIds.ZONE_GLOBAL, fanOff.second)
    }

    @Test
    fun fanSpeed_writeUsesBaseOffsetEncoding() {
        // setFuncVal(FAN_SPEED, 1, level + FAN_SPEED_BASE)
        val h = logic()
        val before = h.state.fanLevel
        h.stepFan(3)
        val fan = writes.filter { it.first == HvacIds.FAN_SPEED }.last()
        assertEquals(HvacIds.FAN_SPEED + before + 3, fan.third)
    }

    @Test
    fun defrostFallsBackToOrdinaryWriteWhenSpecialChannelAbsent() {
        // 没有专用除霜通道时（模拟车/旧固件），退化成普通三参写入，zone 仍是 1
        val h = HvacLogic(
            clock = { now },
            write = { func, zone, value ->
                writes.add(Triple(func, zone, value))
                true
            },
        )
        h.toggleDefrostFront()
        val w = writes.last()
        assertEquals(HvacIds.DEFROST_FRONT_MAX, w.first)
        assertEquals(HvacIds.ZONE_GLOBAL, w.second)
        assertEquals(1, w.third)
    }

    @Test
    fun seatHeat_writesBaseOffsetEncoding() {
        // setFuncVal(SEAT_HEATING, 1, base + level)
        val h = logic()
        h.cycleSeat(HvacIds.ZONE_DRIVER, heat = true)
        val w = writes.filter { it.first == HvacIds.SEAT_HEAT }.first()
        assertEquals(HvacIds.SEAT_HEAT + 1, w.third)
        assertEquals(HvacIds.ZONE_DRIVER, w.second)
    }
}
