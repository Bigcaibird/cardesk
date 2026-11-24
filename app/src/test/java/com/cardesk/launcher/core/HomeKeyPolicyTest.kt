package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home 键双击手势（第三百一十七轮）。
 *
 * 纯逻辑：只有「按一下」事件，时间由测试传入，所以能用虚拟时间把
 * 窗口边界、连按三下、关闭开关这些路径全部覆盖。
 */
class HomeKeyPolicyTest {

    private fun press(d: HomeKeyPolicy.Detector, now: Long, enabled: Boolean = true, double: Boolean = true) =
        d.onPress(now, enabled = enabled, doubleRecents = double)

    @Test
    fun `第一次按压回桌面`() {
        val d = HomeKeyPolicy.Detector()
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 1_000L))
    }

    @Test
    fun `窗口内第二次按压打开最近任务`() {
        val d = HomeKeyPolicy.Detector()
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 1_000L))
        assertEquals(HomeKeyPolicy.Action.SHOW_RECENTS, press(d, 1_000L + HomeKeyPolicy.DOUBLE_WINDOW_MS))
    }

    @Test
    fun `窗口刚好超时就当新的单击`() {
        val d = HomeKeyPolicy.Detector()
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 1_000L))
        assertEquals(
            HomeKeyPolicy.Action.GO_HOME,
            press(d, 1_000L + HomeKeyPolicy.DOUBLE_WINDOW_MS + 1L),
        )
    }

    @Test
    fun `连按三下是回桌面 最近任务 回桌面`() {
        val d = HomeKeyPolicy.Detector()
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 0L))
        assertEquals(HomeKeyPolicy.Action.SHOW_RECENTS, press(d, 100L))
        // 双击成立后状态清零：第三下重新算单击，不会变成第二次双击。
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 200L))
    }

    @Test
    fun `关掉双击映射后两次按压都是回桌面`() {
        val d = HomeKeyPolicy.Detector()
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 0L, double = false))
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 100L, double = false))
    }

    @Test
    fun `总开关关闭时无动作且不记忆按压`() {
        val d = HomeKeyPolicy.Detector()
        assertEquals(HomeKeyPolicy.Action.NONE, press(d, 1_000L, enabled = false))
        assertFalse(d.pending())
        // 关掉期间那次不算数：重新打开后第一下仍是单击。
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 1_100L))
    }

    @Test
    fun `reset 之后第二下不会合成双击`() {
        val d = HomeKeyPolicy.Detector()
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 0L))
        d.reset()
        assertEquals(HomeKeyPolicy.Action.GO_HOME, press(d, 100L))
    }

    @Test
    fun `pending 反映是否在等第二下`() {
        val d = HomeKeyPolicy.Detector()
        assertFalse(d.pending())
        press(d, 0L)
        assertTrue(d.pending())
        press(d, 100L)
        assertFalse(d.pending())
    }
}
