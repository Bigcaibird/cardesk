package com.cardesk.launcher.core

import com.cardesk.launcher.core.HostTaskDisplay.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 承载应用 task 屏幕归属的四态判据（[HostTaskDisplay]）单测（第224轮，纯逻辑）。
 *
 * 背景：用户报「导入视频壁纸后，画中画的高德跑回首页」的第二条根因 ——
 * 弹文件选择器 → cardesk `onStop` → 承载面 surface 销毁（AOSP 9 不 release）
 * → 回到桌面 surface 重建 → `ReadyStarter` **无条件** `startActivity` → 高德被重拉。
 *
 * 关键的取舍：**判不准就不重启**（UNKNOWN 不重启）——
 * 误判成「需要重启」的代价是把用户正在用的应用踢回首页，而那正是用户报的现象。
 */
class HostTaskDisplayTest {

    // ---------------------------------------------------------------- 正常归属

    @Test
    fun `task 在我们的虚拟屏上`() {
        assertEquals(
            "承载应用还挂在我们屏上 → 正常，绝不能重新拉起",
            State.ON_OUR_DISPLAY,
            HostTaskDisplay.stateOf(ourDisplayId = 2, taskDisplayIds = listOf(2)),
        )
    }

    @Test
    fun `task 在别的屏上`() {
        assertEquals(
            "被搬到主屏 / 外接屏 → 需要搬回来（重新拉起）",
            State.OFF_OUR_DISPLAY,
            HostTaskDisplay.stateOf(ourDisplayId = 2, taskDisplayIds = listOf(0)),
        )
    }

    @Test
    fun `多个 task 只要有一个在我们屏上就算在`() {
        assertEquals(
            "应用可能同时有多个 task（文件选择器 / 详情页），任一在我们屏上就不该重启",
            State.ON_OUR_DISPLAY,
            HostTaskDisplay.stateOf(ourDisplayId = 2, taskDisplayIds = listOf(0, 2)),
        )
    }

    @Test
    fun `多个 task 都在别的屏上才算不在`() {
        assertEquals(
            State.OFF_OUR_DISPLAY,
            HostTaskDisplay.stateOf(ourDisplayId = 2, taskDisplayIds = listOf(0, 1)),
        )
    }

    // ---------------------------------------------------------------- 查不到 / 判不准

    @Test
    fun `找不到承载应用的 task`() {
        assertEquals(
            "被系统回收了 → 需要重新拉起",
            State.NOT_FOUND,
            HostTaskDisplay.stateOf(ourDisplayId = 2, taskDisplayIds = emptyList()),
        )
    }

    @Test
    fun `不知道自己在那块屏上`() {
        assertEquals(
            "取不到我们的 VirtualDisplay id ⇒ 判不准",
            State.UNKNOWN,
            HostTaskDisplay.stateOf(ourDisplayId = null, taskDisplayIds = listOf(0)),
        )
    }

    @Test
    fun `读不到 task 的 displayId 时判不准`() {
        assertEquals(
            "有 task 但一个 displayId 都反射不到 ⇒ 判不准，不能当成「不在我们屏上」",
            State.UNKNOWN,
            HostTaskDisplay.stateOf(ourDisplayId = 2, taskDisplayIds = listOf(null)),
        )
    }

    @Test
    fun `部分 task 读不到 displayId 时判不准`() {
        assertEquals(
            "混合情况一律按判不准处理（宁可不重启）",
            State.UNKNOWN,
            HostTaskDisplay.stateOf(ourDisplayId = 2, taskDisplayIds = listOf(0, null)),
        )
    }

    @Test
    fun `正在搬家时一律判不准`() {
        assertEquals(
            "搬家途中 task 的 displayId 正在变，任何快照都是中间态",
            State.UNKNOWN,
            HostTaskDisplay.stateOf(
                ourDisplayId = 2,
                taskDisplayIds = listOf(0),
                migrating = true,
            ),
        )
    }

    @Test
    fun `搬家途中即使 task 已在我们屏上也判不准`() {
        // 搬家刚开始/刚结束时快照都不可信 —— 统一 UNKNOWN，避免重复拉起。
        assertEquals(
            State.UNKNOWN,
            HostTaskDisplay.stateOf(
                ourDisplayId = 2,
                taskDisplayIds = listOf(2),
                migrating = true,
            ),
        )
    }

    // ---------------------------------------------------------------- 动作映射

    @Test
    fun `明确在我们屏上不重新拉起`() {
        assertFalse(HostTaskDisplay.shouldRelaunch(State.ON_OUR_DISPLAY))
    }

    @Test
    fun `判不准不重新拉起`() {
        assertFalse(
            "这是本轮最关键的一条：判不准就当没黑",
            HostTaskDisplay.shouldRelaunch(State.UNKNOWN),
        )
    }

    @Test
    fun `task 在别的屏或没了要重新拉起`() {
        assertTrue(HostTaskDisplay.shouldRelaunch(State.OFF_OUR_DISPLAY))
        assertTrue(HostTaskDisplay.shouldRelaunch(State.NOT_FOUND))
    }

    // ---------------------------------------------------------------- 黑屏自愈口径

    @Test
    fun `黑屏自愈只认明确不在我们屏上`() {
        assertTrue(
            "只有「明确在别的屏」才是黑屏证据",
            HostTaskDisplay.isOffScreenForBlackHeal(State.OFF_OUR_DISPLAY),
        )
        // NOT_FOUND 不算黑屏证据 —— 更可能是权限/时序问题，交给进程看门狗。
        assertFalse(
            "找不到 task 不当作黑屏（不能凭它重启承载应用）",
            HostTaskDisplay.isOffScreenForBlackHeal(State.NOT_FOUND),
        )
        assertFalse(HostTaskDisplay.isOffScreenForBlackHeal(State.ON_OUR_DISPLAY))
        assertFalse(HostTaskDisplay.isOffScreenForBlackHeal(State.UNKNOWN))
    }
}
