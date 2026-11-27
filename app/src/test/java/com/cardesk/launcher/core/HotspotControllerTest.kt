package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HotspotController] 纯逻辑判定单测（状态文案、活跃态判断、开机自动热点冷却与跳过原因）。
 */
class HotspotControllerTest {

    @Test
    fun stateClassification_andLabels() {
        assertTrue(HotspotController.isActiveState(HotspotController.STATE_ENABLED))
        assertTrue(HotspotController.isActiveState(HotspotController.STATE_ENABLING))
        assertFalse(HotspotController.isActiveState(HotspotController.STATE_DISABLED))
        assertFalse(HotspotController.isActiveState(HotspotController.STATE_DISABLING))
        assertFalse(HotspotController.isActiveState(HotspotController.STATE_FAILED))

        assertTrue(HotspotController.isOffState(HotspotController.STATE_DISABLED))
        assertTrue(HotspotController.isOffState(HotspotController.STATE_DISABLING))
        assertFalse(HotspotController.isOffState(HotspotController.STATE_ENABLED))

        assertEquals("已开启", HotspotController.stateLabel(HotspotController.STATE_ENABLED))
        assertEquals("开启中", HotspotController.stateLabel(HotspotController.STATE_ENABLING))
        assertEquals("已关闭", HotspotController.stateLabel(HotspotController.STATE_DISABLED))
        assertEquals("关闭中", HotspotController.stateLabel(HotspotController.STATE_DISABLING))
        assertEquals("异常", HotspotController.stateLabel(HotspotController.STATE_FAILED))
        assertEquals("未知", HotspotController.stateLabel(HotspotController.STATE_UNKNOWN))
    }

    @Test
    fun shouldAutoStart_respectsSwitchStateAndCooldown() {
        val t0 = 1_000_000L
        // 开关关闭 → 不执行
        assertFalse(HotspotController.shouldAutoStart(enabled = false, alreadyOn = false, lastAt = 0L, now = t0))
        assertEquals("开关关闭", HotspotController.autoSkipReason(false, false, 0L, t0))

        // 热点已开启 → 不重复执行
        assertFalse(HotspotController.shouldAutoStart(enabled = true, alreadyOn = true, lastAt = 0L, now = t0))
        assertEquals("热点已开启", HotspotController.autoSkipReason(true, true, 0L, t0))

        // 首次触发 → 执行
        assertTrue(HotspotController.shouldAutoStart(enabled = true, alreadyOn = false, lastAt = 0L, now = t0))
        assertEquals("", HotspotController.autoSkipReason(true, false, 0L, t0))

        // 冷却期内（10 分钟内）→ 跳过
        val inCooldown = t0 + HotspotController.AUTO_COOLDOWN_MS - 1_000L
        assertFalse(HotspotController.shouldAutoStart(enabled = true, alreadyOn = false, lastAt = t0, now = inCooldown))
        assertTrue(HotspotController.autoSkipReason(true, false, t0, inCooldown).startsWith("冷却中"))

        // 冷却期满 → 允许再次执行
        val afterCooldown = t0 + HotspotController.AUTO_COOLDOWN_MS
        assertTrue(HotspotController.shouldAutoStart(enabled = true, alreadyOn = false, lastAt = t0, now = afterCooldown))
    }
}
