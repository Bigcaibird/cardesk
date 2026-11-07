package com.cardesk.launcher.ui.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画**黑屏自愈**的判据与分级决策单测（第130轮）。
 *
 * ## 这条测试守住什么
 *
 * 第129轮撞见「画中画黑屏」，查下来是**一整条链子断了**：
 *
 * 1. 黑屏**从来没被桌面发现过**（探针压根没在工作）；
 * 2. 自愈只有**一次**机会 → 自愈一次不奏效就**永久黑屏**。
 *
 * 这里把「判黑」和「该做什么动作」两条决策钉死：
 * - **task 不在我们的 display 上 → 判黑**（权威判据，不需要像素证据）；
 * - **像素判黑必须先见过应用画面**（否则会把正常的高德判成黑屏 —— 实测踩过）；
 * - **分级不能退化**（必须能从「重发启动」升级到「重建承载面」，
 *   而且重建过之后必须**放弃**，绝不无限重启）。
 */
class PipBlackHealTest {

    private fun decide(
        healStage: Int = PipBlackHeal.STAGE_NONE,
        sawContent: Boolean = true,
        taskOffDisplay: Boolean = false,
        consecutiveBlack: Int = PipBlackHeal.BLACK_STREAK_LIMIT,
        sinceLastHealMs: Long = Long.MAX_VALUE,
    ) = PipBlackHeal.decide(
        healStage = healStage,
        sawContent = sawContent,
        taskOffDisplay = taskOffDisplay,
        consecutiveBlack = consecutiveBlack,
        sinceLastHealMs = sinceLastHealMs,
    )

    // ---------------------------------------------------------------- 判据

    @Test
    fun `没达判据就不动作`() {
        // 黑帧不够多、task 也在我们的 display 上
        assertEquals(
            PipBlackHeal.Action.NONE,
            decide(consecutiveBlack = PipBlackHeal.BLACK_STREAK_LIMIT - 1).action,
        )
    }

    @Test
    fun `见过画面之后连续全黑就重发启动`() {
        val d = decide(sawContent = true, consecutiveBlack = PipBlackHeal.BLACK_STREAK_LIMIT)
        assertEquals(PipBlackHeal.Action.RELAUNCH, d.action)
    }

    /**
     * **第130轮实测踩过的坑**：像素判黑在没有「见过应用画面」时**不能**成立。
     *
     * `ActivityView` 的画面在独立图层上，`PixelCopy` 拷窗口拷不到它 ——
     * 于是「应用画面是黑的」和「我们看不见应用画面」数据上完全一样。
     * 当成判据 = 把正常的高德判成黑屏（实测确实误判并重启了高德）。
     */
    @Test
    fun `没见过应用画面时像素判黑不成立_防误判闸`() {
        val d = decide(
            sawContent = false,
            taskOffDisplay = false,
            consecutiveBlack = 99,
        )
        assertEquals(PipBlackHeal.Action.NONE, d.action)
    }

    /**
     * 权威判据：承载应用的 Activity **不在我们的 VirtualDisplay 上** → 黑屏。
     *
     * 这条**不需要**任何像素证据 —— 它就是黑屏的成因本身。
     */
    @Test
    fun `任务不在我们的display上就判黑_权威判据`() {
        val d = decide(
            sawContent = false,
            taskOffDisplay = true,
            consecutiveBlack = 0,
        )
        assertEquals(PipBlackHeal.Action.RELAUNCH, d.action)
        assertTrue(d.reason.contains("VirtualDisplay"))
    }

    // ---------------------------------------------------------------- 分级

    @Test
    fun `已经重发过一次还黑就升级成重建承载面`() {
        val d = decide(
            healStage = PipBlackHeal.STAGE_RELAUNCH,
            sawContent = true,
            consecutiveBlack = PipBlackHeal.BLACK_STREAK_LIMIT,
        )
        assertEquals(PipBlackHeal.Action.REBUILD, d.action)
    }

    @Test
    fun `已经重建过还黑就放弃_绝不无限重启`() {
        val d = decide(
            healStage = PipBlackHeal.STAGE_REBUILD,
            sawContent = true,
            taskOffDisplay = true,
            consecutiveBlack = 99,
        )
        assertEquals(PipBlackHeal.Action.NONE, d.action)
        assertTrue("放弃的理由要能落进日志", d.reason.contains("放弃"))
    }

    @Test
    fun `冷却期内不动手`() {
        // 第一次自愈后 8 秒内不再动（黑屏是用户可见故障，第一次要快）
        val d = decide(sinceLastHealMs = PipBlackHeal.COOLDOWN_FIRST_MS - 1)
        assertEquals(PipBlackHeal.Action.NONE, d.action)
        assertTrue(d.reason.contains("冷却"))
    }

    @Test
    fun `冷却逐级递增`() {
        assertEquals(
            PipBlackHeal.COOLDOWN_FIRST_MS,
            PipBlackHeal.cooldownFor(PipBlackHeal.STAGE_NONE),
        )
        assertEquals(
            PipBlackHeal.COOLDOWN_SECOND_MS,
            PipBlackHeal.cooldownFor(PipBlackHeal.STAGE_RELAUNCH),
        )
        // 重建过之后不再自愈 → 冷却等于「永远等不到」
        assertEquals(
            Long.MAX_VALUE,
            PipBlackHeal.cooldownFor(PipBlackHeal.STAGE_REBUILD),
        )
        assertTrue(PipBlackHeal.COOLDOWN_SECOND_MS > PipBlackHeal.COOLDOWN_FIRST_MS)
    }

    @Test
    fun `自愈动作会逐级升级而不是一直停在重发启动`() {
        // 完整走一遍：重发启动 → 重建承载面 → 放弃
        var stage = PipBlackHeal.STAGE_NONE
        val actions = mutableListOf<PipBlackHeal.Action>()
        repeat(4) {
            val d = decide(
                healStage = stage,
                sawContent = true,
                taskOffDisplay = true,
                consecutiveBlack = PipBlackHeal.BLACK_STREAK_LIMIT,
                sinceLastHealMs = Long.MAX_VALUE,
            )
            actions.add(d.action)
            stage = when (d.action) {
                PipBlackHeal.Action.RELAUNCH -> PipBlackHeal.STAGE_RELAUNCH
                PipBlackHeal.Action.REBUILD -> PipBlackHeal.STAGE_REBUILD
                PipBlackHeal.Action.NONE -> stage
            }
        }
        assertEquals(
            listOf(
                PipBlackHeal.Action.RELAUNCH,
                PipBlackHeal.Action.REBUILD,
                PipBlackHeal.Action.NONE,
                PipBlackHeal.Action.NONE,
            ),
            actions,
        )
    }

    @Test
    fun `常量符合预期`() {
        assertEquals(0, PipBlackHeal.STAGE_NONE)
        assertEquals(1, PipBlackHeal.STAGE_RELAUNCH)
        assertEquals(2, PipBlackHeal.STAGE_REBUILD)
        assertEquals(3, PipBlackHeal.BLACK_STREAK_LIMIT)
    }

    // ---------------------------------------------------------------- display 判据

    /**
     * 第130轮：黑屏的**权威判据** —— 承载应用的 Activity 还在不在我们的
     * VirtualDisplay 上。
     */
    @Test
    fun `任务不在我们的display上就是黑屏`() {
        assertTrue(PipBlackHeal.isTaskOffDisplay(ourDisplayId = 24, taskDisplayIds = listOf(0)))
        assertTrue(PipBlackHeal.isTaskOffDisplay(ourDisplayId = 24, taskDisplayIds = listOf(25, 0)))
    }

    @Test
    fun `任务在我们的display上就不是黑屏`() {
        assertFalse(PipBlackHeal.isTaskOffDisplay(ourDisplayId = 24, taskDisplayIds = listOf(24)))
        assertFalse(PipBlackHeal.isTaskOffDisplay(ourDisplayId = 24, taskDisplayIds = listOf(0, 24)))
    }

    @Test
    fun `判不准的时候宁可漏判不能误判`() {
        // 拿不到我们的 displayId → 不知道 → 不判黑
        assertFalse(PipBlackHeal.isTaskOffDisplay(ourDisplayId = null, taskDisplayIds = listOf(0)))
        // 一个 task 都没找到 → 不知道 → 不判黑
        assertFalse(PipBlackHeal.isTaskOffDisplay(ourDisplayId = 24, taskDisplayIds = emptyList()))
    }
}
