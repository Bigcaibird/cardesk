package com.cardesk.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置应用闸口（[SettingsApplyGate]）的纯 JVM 单测。
 *
 * 这里不碰 Android：判据都是「值变了没有」，输入输出都是普通值。
 * 覆盖的重点是**只有确认没变才跳过**这条方向性 ——
 * 判错最坏也只是多跑一次，绝不能把该写的写漏。
 */
class SettingsApplyGateTest {

    // ---------------------------------------------------------- policy_control

    @Test
    fun `policy_完全相同不写`() {
        val policy = "immersive.full=com.a,com.b:immersive.navigation=com.c"
        assertFalse(SettingsApplyGate.shouldWritePolicy(policy, policy))
    }

    @Test
    fun `policy_任一段不同就要写`() {
        val a = "immersive.full=com.a:immersive.navigation=com.c"
        val b = "immersive.full=com.a,com.d:immersive.navigation=com.c"
        assertTrue(SettingsApplyGate.shouldWritePolicy(a, b))
    }

    @Test
    fun `policy_空值的几种写法等价`() {
        // 系统里没写过 → null；我们复位时写 "null"；别的写入方可能写空串 / 空白。
        assertFalse(SettingsApplyGate.shouldWritePolicy(null, "null"))
        assertFalse(SettingsApplyGate.shouldWritePolicy("null", null))
        assertFalse(SettingsApplyGate.shouldWritePolicy("", "null"))
        assertFalse(SettingsApplyGate.shouldWritePolicy("  ", ""))
        assertFalse(SettingsApplyGate.shouldWritePolicy(null, null))
    }

    @Test
    fun `policy_空值要变成有策略时必须写`() {
        assertTrue(SettingsApplyGate.shouldWritePolicy(null, "immersive.status=*"))
        assertTrue(SettingsApplyGate.shouldWritePolicy("null", "immersive.status=*"))
    }

    @Test
    fun `policy_有策略要清空时必须写`() {
        assertTrue(SettingsApplyGate.shouldWritePolicy("immersive.status=*", null))
        assertTrue(SettingsApplyGate.shouldWritePolicy("immersive.status=*", "null"))
    }

    @Test
    fun `policy_首尾空白不影响判定`() {
        val policy = "immersive.status=*"
        assertFalse(SettingsApplyGate.shouldWritePolicy("  $policy  ", policy))
    }

    // ---------------------------------------------------------------- overscan

    @Test
    fun `overscan_没写过时第一次一定要写`() {
        assertTrue(SettingsApplyGate.shouldWriteOverscan(SettingsApplyGate.UNSET_OVERSCAN, 0))
        assertTrue(SettingsApplyGate.shouldWriteOverscan(SettingsApplyGate.UNSET_OVERSCAN, -50))
    }

    @Test
    fun `overscan_值没变不写`() {
        assertFalse(SettingsApplyGate.shouldWriteOverscan(0, 0))
        assertFalse(SettingsApplyGate.shouldWriteOverscan(-50, -50))
    }

    @Test
    fun `overscan_值变了要写`() {
        assertTrue(SettingsApplyGate.shouldWriteOverscan(0, -50))
        assertTrue(SettingsApplyGate.shouldWriteOverscan(-50, 0))
    }

    // --------------------------------------------------------------- 画中画

    /** 默认：导航页就绪、承载闸口已开、单画、窗口不透明、非全屏。 */
    private fun inputs(
        navReady: Boolean = true,
        hostingStarted: Boolean = true,
        dualPip: Boolean = false,
        pipApp: String = "com.nav.app",
        pip2App: String = "",
        fullscreen: Boolean = false,
        translucentWindow: Boolean = false,
    ): SettingsApplyGate.PipInputs = SettingsApplyGate.PipInputs(
        navReady = navReady,
        hostingStarted = hostingStarted,
        dualPip = dualPip,
        pipApp = pipApp,
        pip2App = pip2App,
        fullscreen = fullscreen,
        translucentWindow = translucentWindow,
    )

    @Test
    fun `画中画_第一次一定要刷`() {
        assertTrue(SettingsApplyGate.shouldRefreshPip(null, inputs()))
    }

    @Test
    fun `画中画_输入完全没变就不刷`() {
        assertFalse(SettingsApplyGate.shouldRefreshPip(inputs(), inputs()))
    }

    @Test
    fun `画中画_每个输入变化都要刷`() {
        val base = inputs()
        assertTrue(SettingsApplyGate.shouldRefreshPip(base, inputs(navReady = false)))
        assertTrue(SettingsApplyGate.shouldRefreshPip(base, inputs(hostingStarted = false)))
        assertTrue(SettingsApplyGate.shouldRefreshPip(base, inputs(dualPip = true)))
        assertTrue(SettingsApplyGate.shouldRefreshPip(base, inputs(pipApp = "com.other")))
        assertTrue(SettingsApplyGate.shouldRefreshPip(base, inputs(pip2App = "com.nav.app")))
        assertTrue(SettingsApplyGate.shouldRefreshPip(base, inputs(fullscreen = true)))
        assertTrue(SettingsApplyGate.shouldRefreshPip(base, inputs(translucentWindow = true)))
    }

    @Test
    fun `画中画_主包名空串与有值要区分`() {
        val blank = inputs(pipApp = "")
        assertTrue(SettingsApplyGate.shouldRefreshPip(blank, inputs(pipApp = "com.nav.app")))
        assertTrue(SettingsApplyGate.shouldRefreshPip(inputs(), blank))
    }
}
