package com.cardesk.launcher.core

import com.cardesk.launcher.ui.pip.PipBlackHeal
import com.cardesk.launcher.ui.pip.PipBlackHeal.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画**长时间黑屏不自愈**（长黑轮）单测 —— 纯逻辑，无 Robolectric、无 Android 依赖。
 *
 * ## 真车现象
 *
 * 桌面上承载的导航应用画中画，用户切到其它页较久后回来仍黑；诊断包里
 * **零条**「画中画黑屏自愈」，唯一的「重建承载面」是业务路径（渲染路线切换）。
 *
 * ## 根因（两条判据都够不着）
 *
 * 承载面没有画面时，窗口缓冲在承载面位置是**透明的洞** ⇒ 采样是连续 BLIND
 * （真车 42 秒 / blind=14），只有末尾夹了 1 帧黑（亮像素占比 1%，卡在 2% 阈值边）。
 *
 * 1. 旧规则「盲帧清零连续黑帧」⇒ 连续黑帧永远凑不满 3 帧；
 * 2. 权威判据（task 的 displayId）在车机上恒为「判不准」。
 *
 * ## 修复后的语义（本文件逐条钉住）
 *
 * - 盲帧**不清零**连续黑帧，也不累加（见 [PipSampleGate.nextBlackStreak]）；
 * - 盲帧**不**算「见过画面」，[PipBlackHeal.decide] 的像素判黑仍要求见过画面；
 * - 新增**盲帧超时判据**：连续够多帧看不见承载面（[PipBlackHeal.BLIND_STREAK_LIMIT]）
 *   也算黑屏证据，但只在「盲帧确实代表没有画面」的配置下启用
 *   （[PipBlackHeal.blindCanMeanLost]：半透明窗口 + LAYER 路线时**永不启用**，
 *   防第224轮「视频壁纸把高德判黑重启」回归）；
 * - 自愈后计数归零 + 分级冷却（8s / 20s），不会连着重启；
 * - 冷启动还没画出第一帧时（从没见过画面、也还没自愈过）不靠盲帧动手；
 * - 已经重建过承载面还黑 ⇒ 放弃，绝不无限重启。
 */
class PipBlackHealLongIdleTest {

    // ---------------------------------------------------------------- 采样序列模拟

    /**
     * 按 [com.cardesk.launcher.ui.pip.PipFrameProbe] 的口径喂采样帧，只保留判黑要用的状态。
     *
     * 口径与探针完全一致：
     * - BLIND：`consecutiveBlind++`，连续黑帧走 [PipSampleGate.nextBlackStreak]（保持原值）；
     * - 其它帧：`consecutiveBlind = 0`，再更新连续黑帧与「见过画面」。
     */
    private class Probe {
        var consecutiveBlack = 0
        var consecutiveBlind = 0

        /** 探针「见过应用画面」——[com.cardesk.launcher.ui.pip.PipFrameProbe.sawContent]。 */
        var sawContent = false

        fun feed(vararg frames: PipSampleGate.Frame): Probe {
            frames.forEach { frame ->
                if (frame == PipSampleGate.Frame.BLIND) {
                    consecutiveBlind++
                } else {
                    consecutiveBlind = 0
                    sawContent = PipSampleGate.nextSawContent(sawContent, frame)
                }
                consecutiveBlack = PipSampleGate.nextBlackStreak(consecutiveBlack, frame)
            }
            return this
        }

        /** 对应探针的 `markHealed()`：自愈后忘掉本轮证据，别紧接着又触发。 */
        fun markHealed() {
            consecutiveBlack = 0
            consecutiveBlind = 0
            sawContent = false
        }
    }

    private fun decide(
        probe: Probe,
        healStage: Int = PipBlackHeal.STAGE_NONE,
        taskOffDisplay: Boolean = false,
        sinceLastHealMs: Long = Long.MAX_VALUE,
        blindMeansLost: Boolean = false,
    ): PipBlackHeal.Decision = PipBlackHeal.decide(
        healStage = healStage,
        sawContent = probe.sawContent,
        taskOffDisplay = taskOffDisplay,
        consecutiveBlack = probe.consecutiveBlack,
        sinceLastHealMs = sinceLastHealMs,
        consecutiveBlind = probe.consecutiveBlind,
        blindMeansLost = blindMeansLost,
    )

    // ---------------------------------------------------------------- ① 真黑帧照常自愈

    @Test
    fun `连续三帧真黑 会重新拉起承载应用`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
        )
        assertEquals(3, probe.consecutiveBlack)
        assertTrue("必须先见过画面，像素判黑才生效", probe.sawContent)
        val d = decide(probe)
        assertEquals(Action.RELAUNCH, d.action)
        assertTrue("原因要说清是连续黑帧", d.reason.contains("连续黑帧"))
    }

    @Test
    fun `重新拉起后仍黑 升级为重建承载面`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
        )
        val d = decide(probe, healStage = PipBlackHeal.STAGE_RELAUNCH, sinceLastHealMs = 20_001)
        assertEquals(Action.REBUILD, d.action)
    }

    @Test
    fun `重建过承载面还黑 放弃 不再反复重启`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
        )
        val d = decide(probe, healStage = PipBlackHeal.STAGE_REBUILD, sinceLastHealMs = Long.MAX_VALUE)
        assertEquals(Action.NONE, d.action)
        assertTrue(d.reason.contains("放弃"))
    }

    // ---------------------------------------------------------------- ② 盲帧不打断黑帧计数

    @Test
    fun `黑帧与盲帧交替 也能凑满连续三帧`() {
        // 真车序列：黑帧和「看不见承载面」交替出现（长黑轮根因就是旧规则在这里清零）。
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLIND,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLIND,
            PipSampleGate.Frame.BLACK,
        )
        assertEquals("盲帧不打断黑帧计数", 3, probe.consecutiveBlack)
        assertEquals("末尾是真黑帧，盲帧计数应被清零", 0, probe.consecutiveBlind)
        val d = decide(probe)
        assertEquals(Action.RELAUNCH, d.action)
    }

    @Test
    fun `纯盲帧不会自己累加连续黑帧`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.Frame.BLIND,
            PipSampleGate.Frame.BLIND,
            PipSampleGate.Frame.BLIND,
        )
        assertEquals("盲帧不累加连续黑帧", 0, probe.consecutiveBlack)
        // 盲帧有自己的超时判据，见后面几组；这里先确认不会污染像素判黑。
        assertTrue(probe.sawContent)
    }

    // ---------------------------------------------------------------- ③ 自愈后归零 + 冷却

    @Test
    fun `自愈后计数归零 不会立刻二次触发`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
        )
        assertEquals(Action.RELAUNCH, decide(probe).action)

        // 探针 markHealed() 之后：就当这轮证据没发生过。
        probe.markHealed()
        assertEquals(0, probe.consecutiveBlack)
        assertEquals(0, probe.consecutiveBlind)
        assertFalse(probe.sawContent)
        assertEquals(Action.NONE, decide(probe, healStage = PipBlackHeal.STAGE_RELAUNCH).action)
    }

    @Test
    fun `自愈后冷却期内 证据再足也不重复动作`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
        )
        // 刚自愈过（0ms）→ 第一级冷却 8s 内不动手。
        val stillCooling = decide(
            probe,
            healStage = PipBlackHeal.STAGE_NONE,
            sinceLastHealMs = 0,
        )
        assertEquals(Action.NONE, stillCooling.action)
        assertTrue("要能在日志里看出是冷却挡的", stillCooling.reason.contains("冷却"))

        // 冷却过了 → 照常动手。
        val afterCooldown = decide(
            probe,
            healStage = PipBlackHeal.STAGE_NONE,
            sinceLastHealMs = PipBlackHeal.COOLDOWN_FIRST_MS,
        )
        assertEquals(Action.RELAUNCH, afterCooldown.action)
    }

    // ---------------------------------------------------------------- ④ 纯黑首帧不误判

    @Test
    fun `没看过画面时 连续黑帧不触发自愈`() {
        // 冷启动/应用还没画出第一帧：采样全是黑的，但不能据此重启应用。
        val probe = Probe().feed(
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
            PipSampleGate.Frame.BLACK,
        )
        assertEquals(4, probe.consecutiveBlack)
        assertFalse(probe.sawContent)
        assertEquals(Action.NONE, decide(probe).action)
    }

    @Test
    fun `权威判据仍然独立生效 不需要像素证据`() {
        val probe = Probe() // 采样什么都没发生
        assertEquals(Action.RELAUNCH, decide(probe, taskOffDisplay = true).action)
    }

    // ---------------------------------------------------------------- 盲帧超时判据

    @Test
    fun `盲帧未达上限 不动手`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            *Array(PipBlackHeal.BLIND_STREAK_LIMIT - 1) { PipSampleGate.Frame.BLIND },
        )
        assertEquals(PipBlackHeal.BLIND_STREAK_LIMIT - 1, probe.consecutiveBlind)
        val d = decide(probe, blindMeansLost = true)
        assertEquals(Action.NONE, d.action)
    }

    @Test
    fun `盲帧达到上限 按没有画面自愈`() {
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            *Array(PipBlackHeal.BLIND_STREAK_LIMIT) { PipSampleGate.Frame.BLIND },
        )
        assertEquals(PipBlackHeal.BLIND_STREAK_LIMIT, probe.consecutiveBlind)
        val d = decide(probe, blindMeansLost = true)
        assertEquals(Action.RELAUNCH, d.action)
        assertTrue("原因要说清是长时间看不见承载面", d.reason.contains("看不见承载面"))
    }

    @Test
    fun `半透明窗口下 盲帧永远不当证据`() {
        // 第224轮回归闸：视频壁纸（半透明窗口）下盲帧是正常的，
        // 靠它自愈就会把正常承载的应用重启掉。
        val probe = Probe().feed(
            PipSampleGate.Frame.CONTENT,
            *Array(PipBlackHeal.BLIND_STREAK_LIMIT * 3) { PipSampleGate.Frame.BLIND },
        )
        val d = decide(probe, blindMeansLost = false, sinceLastHealMs = Long.MAX_VALUE)
        assertEquals(Action.NONE, d.action)
    }

    @Test
    fun `冷启动一直看不见 也不靠盲帧动手`() {
        // 从没见过画面、也还没自愈过 → 可能只是应用还没画出第一帧。
        val probe = Probe().feed(
            *Array(PipBlackHeal.BLIND_STREAK_LIMIT * 2) { PipSampleGate.Frame.BLIND },
        )
        assertFalse(probe.sawContent)
        val d = decide(probe, blindMeansLost = true)
        assertEquals(Action.NONE, d.action)
    }

    @Test
    fun `已重发过启动仍看不见 不再等画面证据 直接升级重建`() {
        // 自愈过一次（stage=RELAUNCH）还是看不见：这次要重建承载面，别再空等画面。
        val probe = Probe().feed(
            *Array(PipBlackHeal.BLIND_STREAK_LIMIT) { PipSampleGate.Frame.BLIND },
        )
        assertFalse(probe.sawContent)
        val d = decide(
            probe,
            healStage = PipBlackHeal.STAGE_RELAUNCH,
            sinceLastHealMs = PipBlackHeal.COOLDOWN_SECOND_MS,
            blindMeansLost = true,
        )
        assertEquals(Action.REBUILD, d.action)
    }

    // ---------------------------------------------------------------- 路线/窗口闸门

    @Test
    fun `盲帧能不能当证据 由路线与窗口透明度决定`() {
        // LAYER + 不透明窗口：采不到就是没有画面。
        assertTrue(
            PipBlackHeal.blindCanMeanLost(PipRenderPlan.Mode.LAYER, translucentWindow = false),
        )
        // LAYER + 半透明窗口：窗口缓冲永远有洞（第224轮的误判来源）⇒ 不认。
        assertFalse(
            PipBlackHeal.blindCanMeanLost(PipRenderPlan.Mode.LAYER, translucentWindow = true),
        )
        // MIRROR：承载面画进窗口缓冲，盲帧说明承载面没有画面 ⇒ 认。
        assertTrue(
            PipBlackHeal.blindCanMeanLost(PipRenderPlan.Mode.MIRROR, translucentWindow = true),
        )
        assertTrue(
            PipBlackHeal.blindCanMeanLost(PipRenderPlan.Mode.MIRROR, translucentWindow = false),
        )
        // 没有承载面（降级卡片 / 还没建好）⇒ 判不准，不认。
        assertFalse(PipBlackHeal.blindCanMeanLost(null, translucentWindow = false))
    }

    @Test
    fun `盲帧上限明显比黑帧上限宽松`() {
        // 盲帧是弱证据，必须比「连续 3 帧真黑」更保守（30s vs 9s）。
        assertTrue(
            "盲帧上限 ${PipBlackHeal.BLIND_STREAK_LIMIT} 必须大于黑帧上限 " +
                PipBlackHeal.BLACK_STREAK_LIMIT,
            PipBlackHeal.BLIND_STREAK_LIMIT > PipBlackHeal.BLACK_STREAK_LIMIT,
        )
        assertEquals(3, PipBlackHeal.BLACK_STREAK_LIMIT)
    }
}
