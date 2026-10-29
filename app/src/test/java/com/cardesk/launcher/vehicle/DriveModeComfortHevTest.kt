package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驾驶模式两个缺陷的回归单测。
 *
 * ## 缺陷一：漏了一个真实返回值
 *
 * 车端读回的 `570491153`（厂商 `IDriveMode.DRIVE_MODE_SELECTION_COMFORT_HEV`，
 * 同一个值还有别名 `DRIVE_MODE_SELECTION_NORMAL`）之前不在
 * [DriveMode.normalizeModeId] 里 → 被归 0 → 状态胶囊显示「待命」。
 *
 * ## 缺陷二：读失败的哨兵值被当成模式号
 *
 * 车端读失败时返回 `253` / `254` / `255`
 * （`ICarFunction.COMMON_VALUE_ERROR` / `COMMON_VALUE_NONE` /
 * `IDriveMode.DRIVE_MODE_SELECTION_UNKNOWN`），**不是模式号**。
 */
class DriveModeComfortHevTest {

    @Test
    fun `570491153 归一之后还是它自己`() {
        assertEquals(DriveMode.SELECTION_COMFORT_HEV, DriveMode.normalizeModeId(570491153))
    }

    @Test
    fun `570491153 有两字短标签`() {
        val label = DriveMode.shortLabelForValue(DriveMode.SELECTION_COMFORT_HEV)
        assertEquals("标准", label)
        // 项目风格：短标签不超过两个字
        assertTrue(label!!.length <= 2)
    }

    @Test
    fun `570491153 的标签不与舒适模式重名`() {
        // 「舒适」已经是 DRIVE_MODE_SELECTION_COMFORT(570491138) 的标签了
        assertFalse(
            DriveMode.shortLabelForValue(DriveMode.SELECTION_COMFORT_HEV) ==
                DriveMode.shortLabelForValue(DriveMode.SELECTION_COMFORT)
        )
    }

    @Test
    fun `三个哨兵值就是厂商常量里的那三个数`() {
        // ICarFunction.COMMON_VALUE_ERROR / COMMON_VALUE_NONE
        // IDriveMode.DRIVE_MODE_SELECTION_UNKNOWN
        assertEquals(253, DriveMode.READ_ERROR)
        assertEquals(254, DriveMode.READ_NONE)
        assertEquals(255, DriveMode.READ_UNKNOWN)
    }

    @Test
    fun `哨兵值认得出是读失败`() {
        assertTrue(DriveMode.isReadError(DriveMode.READ_ERROR))
        assertTrue(DriveMode.isReadError(DriveMode.READ_NONE))
        assertTrue(DriveMode.isReadError(DriveMode.READ_UNKNOWN))
    }

    @Test
    fun `正常模式号不会被误判成读失败`() {
        assertFalse(DriveMode.isReadError(DriveMode.SELECTION_ECO))
        assertFalse(DriveMode.isReadError(DriveMode.SELECTION_COMFORT))
        assertFalse(DriveMode.isReadError(DriveMode.SELECTION_COMFORT_HEV))
        assertFalse(DriveMode.isReadError(DriveMode.SELECTION_TRACK))
        assertFalse(DriveMode.isReadError(DriveMode.SELECTION_CUSTOM))
    }

    @Test
    fun `哨兵值归到无模式 不当成有效模式号`() {
        assertEquals(DriveMode.SELECTION_NONE, DriveMode.normalizeModeId(DriveMode.READ_ERROR))
        assertEquals(DriveMode.SELECTION_NONE, DriveMode.normalizeModeId(DriveMode.READ_NONE))
        assertEquals(DriveMode.SELECTION_NONE, DriveMode.normalizeModeId(DriveMode.READ_UNKNOWN))
    }

    @Test
    fun `哨兵值没有短标签 走待命兜底`() {
        assertNull(DriveMode.shortLabelForValue(DriveMode.READ_ERROR))
        assertNull(DriveMode.shortLabelForValue(DriveMode.READ_NONE))
        assertNull(DriveMode.shortLabelForValue(DriveMode.READ_UNKNOWN))
        assertEquals("待命", DriveMode.currentShortLabel(DriveMode.READ_ERROR))
        assertEquals("待命", DriveMode.currentShortLabel(DriveMode.READ_NONE))
        assertEquals("待命", DriveMode.currentShortLabel(DriveMode.READ_UNKNOWN))
    }
}
