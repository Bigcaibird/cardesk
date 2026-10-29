package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 车机端 `GeelyVehicleProperty.PA_CP_*` 车门属性的**常量与顺序**单测。
 *
 * 这些 id 来自车机端 `ecarx.adapterapi.jar`（`classes.dex`）反汇编，
 * 一旦对错，桌面上的车门状态会整体指向错误的属性 —— 所以钉死。
 */
class PaCpDoorPropsTest {

    @Test
    fun `属性 id 与车机端 jar 一致`() {
        assertEquals(560993300, PaCpDoorProps.DOOR_DRIVER)
        assertEquals(560993301, PaCpDoorProps.DOOR_DRIVER_REAR)
        assertEquals(560993302, PaCpDoorProps.DOOR_PASSENGER)
        assertEquals(560993303, PaCpDoorProps.DOOR_PASSENGER_REAR)
        assertEquals(560993304, PaCpDoorProps.TAILGATE)
    }

    @Test
    fun `十六进制与 jar 里的常量一致`() {
        assertEquals(0x21701414, PaCpDoorProps.DOOR_DRIVER)
        assertEquals(0x21701415, PaCpDoorProps.DOOR_DRIVER_REAR)
        assertEquals(0x21701416, PaCpDoorProps.DOOR_PASSENGER)
        assertEquals(0x21701417, PaCpDoorProps.DOOR_PASSENGER_REAR)
        assertEquals(0x21701418, PaCpDoorProps.TAILGATE)
    }

    @Test
    fun `四门按 FL FR RL RR 排列`() {
        assertEquals(
            listOf(
                PaCpDoorProps.DOOR_DRIVER,      // 主驾 = 左前
                PaCpDoorProps.DOOR_PASSENGER,   // 副驾 = 右前
                PaCpDoorProps.DOOR_DRIVER_REAR, // 驾驶员侧后门 = 左后
                PaCpDoorProps.DOOR_PASSENGER_REAR, // 乘客侧后门 = 右后
            ),
            PaCpDoorProps.DOORS_IN_FL_FR_RL_RR.toList(),
        )
    }

    @Test
    fun `ALL 含四个门加尾门`() {
        assertEquals(5, PaCpDoorProps.ALL.size)
        assertEquals(
            listOf(
                PaCpDoorProps.DOOR_DRIVER,
                PaCpDoorProps.DOOR_DRIVER_REAR,
                PaCpDoorProps.DOOR_PASSENGER,
                PaCpDoorProps.DOOR_PASSENGER_REAR,
                PaCpDoorProps.TAILGATE,
            ),
            PaCpDoorProps.ALL.toList(),
        )
    }

    @Test
    fun `名字能对上属性`() {
        assertEquals("主驾门", PaCpDoorProps.nameOf(PaCpDoorProps.DOOR_DRIVER))
        assertEquals("尾门", PaCpDoorProps.nameOf(PaCpDoorProps.TAILGATE))
        // 认不出来的属性退回十六进制，不编名字
        assertEquals("0x00000001", PaCpDoorProps.nameOf(1))
    }

    @Test
    fun `McDoorSignals 的常量与这里是同一套`() {
        assertEquals(PaCpDoorProps.DOOR_DRIVER, McDoorSignals.PROP_DOOR_FL)
        assertEquals(PaCpDoorProps.DOOR_PASSENGER, McDoorSignals.PROP_DOOR_FR)
        assertEquals(PaCpDoorProps.DOOR_DRIVER_REAR, McDoorSignals.PROP_DOOR_BL)
        assertEquals(PaCpDoorProps.DOOR_PASSENGER_REAR, McDoorSignals.PROP_DOOR_BR)
        assertEquals(PaCpDoorProps.TAILGATE, McDoorSignals.PROP_TRUNK)
        assertEquals(PaCpDoorProps.DOORS_IN_FL_FR_RL_RR.toList(), McDoorSignals.DOOR_PROPS.toList())
    }

    @Test
    fun `McDoorSignals 的取值口径是 0 关 1 开`() {
        assertEquals(true, McDoorSignals.interpret(0L))
        assertEquals(false, McDoorSignals.interpret(1L))
        assertEquals(null, McDoorSignals.interpret(2L))
    }

    @Test
    fun `车门候选属性号包含 AOSP 与 ECARX 两套`() {
        // AOSP 标准号在前，ECARX 自己那份在后
        assertNotNull(VehicleIds.DOOR_POS_CANDIDATES.firstOrNull { it == 0x16400B00 })
        assertNotNull(VehicleIds.DOOR_POS_CANDIDATES.firstOrNull { it == 0x16401000 })
        assertNotNull(VehicleIds.WINDOW_POS_CANDIDATES.firstOrNull { it == 0x13400BC0 })
        assertNotNull(VehicleIds.WINDOW_POS_CANDIDATES.firstOrNull { it == 0x13401000 })
    }
}
