package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipResumeWakeTest {

    @Test
    fun `never picks display 0 or invalid display id`() {
        val stacks = listOf(
            PipResumeWake.StackTarget(
                stackId = 1,
                displayId = 0,
                topPackage = "com.autonavi.amapautp.yh",
                taskIds = listOf(10),
                taskNames = listOf("com.autonavi.amapautp.yh/com.autonavi.amapauto.MainMapActivity"),
            ),
        )
        assertNull(PipResumeWake.pickStackOnDisplay(null, "com.autonavi.amapautp.yh", stacks))
        assertNull(PipResumeWake.pickStackOnDisplay(0, "com.autonavi.amapautp.yh", stacks))
        assertNull(PipResumeWake.pickStackOnDisplay(-1, "com.autonavi.amapautp.yh", stacks))
    }

    @Test
    fun `ignores main display fullscreen amap and only picks stack on our virtual display`() {
        val stacks = listOf(
            PipResumeWake.StackTarget(
                stackId = 0,
                displayId = 0,
                topPackage = "com.ecarx.cardesk",
                taskIds = listOf(142),
                taskNames = listOf("com.ecarx.cardesk/com.cardesk.launcher.MainActivity"),
            ),
            PipResumeWake.StackTarget(
                stackId = 5,
                displayId = 0,
                topPackage = "com.autonavi.amapautp.yh",
                taskIds = listOf(150),
                taskNames = listOf("com.autonavi.amapautp.yh/com.autonavi.amapauto.MainMapActivity"),
            ),
            PipResumeWake.StackTarget(
                stackId = 12,
                displayId = 3,
                topPackage = "com.autonavi.amapautp.yh",
                taskIds = listOf(137),
                taskNames = listOf("com.autonavi.amapautp.yh/com.autonavi.auto.remote.fill.UsbFillActivity"),
            ),
        )
        val picked = PipResumeWake.pickStackOnDisplay(3, "com.autonavi.amapautp.yh", stacks)
        assertEquals(PipResumeWake.PickedStack(stackId = 12, topTaskId = 137), picked)
        assertNull(PipResumeWake.pickStackOnDisplay(4, "com.autonavi.amapautp.yh", stacks))
    }

    @Test
    fun `prefers stack matching package name when multiple stacks exist on virtual display`() {
        val stacks = listOf(
            PipResumeWake.StackTarget(
                stackId = 11,
                displayId = 3,
                topPackage = "com.android.packageinstaller",
                taskIds = listOf(130),
                taskNames = listOf("com.android.packageinstaller/.GrantPermissionsActivity"),
            ),
            PipResumeWake.StackTarget(
                stackId = 12,
                displayId = 3,
                topPackage = "com.autonavi.amapautp.yh",
                taskIds = listOf(136, 137),
                taskNames = listOf("com.autonavi.amapautp.yh/com.autonavi.amapauto.MainMapActivity"),
            ),
        )
        val picked = PipResumeWake.pickStackOnDisplay(3, "com.autonavi.amapautp.yh", stacks)
        assertEquals(PipResumeWake.PickedStack(stackId = 12, topTaskId = 137), picked)
    }

    @Test
    fun `skips empty stacks on our display`() {
        val stacks = listOf(
            PipResumeWake.StackTarget(
                stackId = 12,
                displayId = 3,
                topPackage = null,
                taskIds = emptyList(),
                taskNames = emptyList(),
            ),
        )
        assertNull(PipResumeWake.pickStackOnDisplay(3, "com.autonavi.amapautp.yh", stacks))
    }

    @Test
    fun `shouldWake requires all five guards`() {
        assertTrue(PipResumeWake.shouldWake(true, true, true, true, false))
        assertFalse(PipResumeWake.shouldWake(false, true, true, true, false))
        assertFalse(PipResumeWake.shouldWake(true, false, true, true, false))
        assertFalse(PipResumeWake.shouldWake(true, true, false, true, false))
        assertFalse(PipResumeWake.shouldWake(true, true, true, false, false))
        assertFalse(PipResumeWake.shouldWake(true, true, true, true, true))
    }
}
