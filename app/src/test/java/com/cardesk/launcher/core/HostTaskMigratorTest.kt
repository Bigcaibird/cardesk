package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 承载任务搬移的取舍规则（[HostTaskMigrator]）单测（第141轮，纯逻辑）。
 *
 * ## 背景
 *
 * 画中画能不能成立，取决于承载应用的 task 是不是**真的挂在我们的虚拟屏上**。
 *
 * - **车机（真机 ROM）**：启动时就落在虚拟屏上 → 不用搬；
 * - **AOSP 9 官方模拟器**：启动时仍然落在**默认显示器**上 → 全屏抢屏，
 *   得在启动**之后**补一脚搬过来。
 *
 * 这一层只管「搬不搬、搬哪个、用哪种搬法」；
 * 反射调 AMS 的活儿在 `HostTaskMigration`（Android 侧）。
 */
class HostTaskMigratorTest {

    private fun task(
        taskId: Int,
        pkg: String,
        displayId: Int? = 0,
        stackId: Int? = null,
        screenWidthDp: Int? = null,
        screenHeightDp: Int? = null,
    ) = HostTaskMigrator.TaskSnapshot(
        taskId = taskId,
        stackId = stackId,
        displayId = displayId,
        pkg = pkg,
        screenWidthDp = screenWidthDp,
        screenHeightDp = screenHeightDp,
    )

    // ------------------------------------------------------------------ 挑哪个 task

    @Test
    fun `挑出承载应用自己的 task`() {
        val picked = HostTaskMigrator.pickHostTask(
            listOf(
                task(11, "com.other.app"),
                task(22, "com.autonavi.amapautp.yh"),
            ),
            pkg = "com.autonavi.amapautp.yh",
        )
        assertEquals("只认包名匹配的", 22, picked?.taskId)
    }

    @Test
    fun `同包名多个 task 时取最靠前的一个`() {
        // getRunningTasks 是「按最近使用」排序的 → 第一个就是我们刚拉起的
        val picked = HostTaskMigrator.pickHostTask(
            listOf(
                task(33, "com.cardesk.embedtest", stackId = 12),
                task(44, "com.cardesk.embedtest", stackId = 13),
            ),
            pkg = "com.cardesk.embedtest",
        )
        assertEquals(33, picked?.taskId)
    }

    @Test
    fun `优先挑有 stackId 的 task`() {
        // AOSP 9 搬栈必须要 stackId
        val picked = HostTaskMigrator.pickHostTask(
            listOf(
                task(55, "com.cardesk.embedtest", stackId = null),
                task(66, "com.cardesk.embedtest", stackId = 21),
            ),
            pkg = "com.cardesk.embedtest",
        )
        assertEquals("有 stackId 的优先", 66, picked?.taskId)
    }

    @Test
    fun `找不到就是 null`() {
        assertNull(
            HostTaskMigrator.pickHostTask(listOf(task(1, "com.other")), pkg = "com.absent"),
        )
    }

    @Test
    fun `包名为空时直接不挑`() {
        assertNull(HostTaskMigrator.pickHostTask(listOf(task(1, "x")), pkg = ""))
    }

    // ------------------------------------------------------------------ 在不在我们的屏上

    @Test
    fun `有 displayId 时按 id 精确判`() {
        // API 29+：`RunningTaskInfo.displayId` 存在 → 直接比
        val on = HostTaskMigrator.isOnOurDisplay(
            task(1, "p", displayId = 21),
            ourDisplayId = 21,
            ourMetrics = HostTaskMigrator.DisplayMetrics(906, 867),
            mainMetrics = HostTaskMigrator.DisplayMetrics(1920, 1080),
        )
        assertEquals(true, on)
    }

    @Test
    fun `displayId 不是我们的就不是`() {
        val on = HostTaskMigrator.isOnOurDisplay(
            task(1, "p", displayId = 0),
            ourDisplayId = 21,
            ourMetrics = HostTaskMigrator.DisplayMetrics(906, 867),
            mainMetrics = HostTaskMigrator.DisplayMetrics(1920, 1080),
        )
        assertEquals(false, on)
    }

    @Test
    fun `API 28 上靠显示配置的宽度判`() {
        // API 28 的 RunningTaskInfo **没有** displayId → 只能比配置宽度
        val task = HostTaskMigrator.TaskSnapshot(
            taskId = 68,
            stackId = 25,
            displayId = null,
            pkg = "p",
            screenWidthDp = 1920,
            screenHeightDp = 944,
        )
        val on = HostTaskMigrator.isOnOurDisplay(
            task,
            ourDisplayId = 13,
            ourMetrics = HostTaskMigrator.DisplayMetrics(906, 867),
            mainMetrics = HostTaskMigrator.DisplayMetrics(1920, 1080),
        )
        assertEquals("宽度跟主屏一样 → 不在我们的屏上", false, on)
    }

    @Test
    fun `配置宽度跟我们的虚拟屏一样就是在`() {
        val task = HostTaskMigrator.TaskSnapshot(
            taskId = 68,
            stackId = 25,
            displayId = null,
            pkg = "p",
            screenWidthDp = 906,
            screenHeightDp = 731,
        )
        val on = HostTaskMigrator.isOnOurDisplay(
            task,
            ourDisplayId = 13,
            ourMetrics = HostTaskMigrator.DisplayMetrics(906, 867),
            mainMetrics = HostTaskMigrator.DisplayMetrics(1920, 1080),
        )
        assertEquals(true, on)
    }

    @Test
    fun `两边都对不上时判不知道`() {
        // 承载应用自己强制了显示配置 → 不能当成「不在」去搬
        val task = HostTaskMigrator.TaskSnapshot(
            taskId = 68,
            stackId = 25,
            displayId = null,
            pkg = "p",
            screenWidthDp = 1280,
            screenHeightDp = 720,
        )
        val on = HostTaskMigrator.isOnOurDisplay(
            task,
            ourDisplayId = 13,
            ourMetrics = HostTaskMigrator.DisplayMetrics(906, 867),
            mainMetrics = HostTaskMigrator.DisplayMetrics(1920, 1080),
        )
        assertNull("宁可不动，也不能误搬", on)
    }

    @Test
    fun `读不到任何证据时判不知道`() {
        val on = HostTaskMigrator.isOnOurDisplay(
            task(1, "p"),
            ourDisplayId = null,
            ourMetrics = null,
            mainMetrics = null,
        )
        assertNull(on)
    }

    // ------------------------------------------------------------------ 用哪种搬法

    @Test
    fun `AOSP 9 走搬栈`() {
        val api = HostTaskMigrator.planMove(
            task(1, "p", stackId = 12),
            taskApiAvailable = false,
        )
        assertEquals(HostTaskMigrator.MoveApi.STACK_TO_DISPLAY, api)
    }

    @Test
    fun `AOSP 10 走搬 task`() {
        val api = HostTaskMigrator.planMove(
            task(1, "p", stackId = 12),
            taskApiAvailable = true,
        )
        assertEquals(HostTaskMigrator.MoveApi.TASK_TO_DISPLAY, api)
    }

    @Test
    fun `AOSP 9 上没有 stackId 就搬不了`() {
        assertNull(
            "搬栈必须要 stackId；没有就老实说搬不了",
            HostTaskMigrator.planMove(task(1, "p", stackId = null), taskApiAvailable = false),
        )
    }

    // ------------------------------------------------------------------ 重试节奏

    @Test
    fun `第一次立刻试之后退避`() {
        assertEquals(0L, HostTaskMigrator.delayMsForAttempt(0))
        assertTrue(HostTaskMigrator.delayMsForAttempt(1) > 0L)
        assertTrue(
            "越往后间隔越大",
            HostTaskMigrator.delayMsForAttempt(4) >= HostTaskMigrator.delayMsForAttempt(2),
        )
    }

    @Test
    fun `尝试次数有上限`() {
        assertTrue("必须有上限，不能无限空转", HostTaskMigrator.MAX_ATTEMPTS in 1..10)
    }
}
