package com.cardesk.launcher.core.dock

import com.cardesk.launcher.core.OemDockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第193轮：**Dock 在几乎所有 App 里让位 + 让位后原车导航栏露出来**的回归护栏。
 *
 * ## 故障现象（2026-11-17 真车诊断包 `cardesk-logs-20261117-083723`）
 *
 * 用户报：「不止在网易云以及汽水让位了，在几乎所有 app 都让位了，
 * 包括桌面本身，而且让位后车机自带的导航栏出来了」。
 *
 * ## 根因链（一条策略引发三件事）
 *
 * `policy_control` 里写了 `immersive.navigation=*`（第189轮把「隐藏原车 Dock」
 * 改成全局生效时，误接到了**预留区路径**上）：
 *
 * 1. 系统导航栏连同它 112px 的**预留带**被从**所有** App 的窗口布局里摘掉
 *    ⇒ App 全屏，底部内容被 Dock 盖住；
 * 2. WMS 因此给**每一个** App 打上 `HIDE_NAVIGATION`/`IMMERSIVE`，
 *    而 `dumpsys window policy` 的 `mLastSystemUiFlags` 是**全局**值
 *    ⇒ [ImmersiveDetector] 对任何前台 App 都判「沉浸」
 *    ⇒ **Dock 在几乎所有 App 里让位**（桌面只靠 `homeForeground` 豁免才幸免）；
 * 3. CarDesk 的 Dock 是 `TYPE_NAVIGATION_BAR_PANEL`(2024)，**盖在**系统的
 *    `CarNavigationBar` 窗口之上（诊断包实测两者同帧 `[0,968][1920,1080]`）——
 *    Dock 一隐藏，底下的原车导航栏就露了出来。
 *
 * ## 三条不变量（这里逐条钉住）
 *
 * - 预留区路径**绝不**写 `immersive.navigation=*`；
 * - 无预留区路径**必须**写（2038 悬浮窗层级低于系统导航栏，不摘掉会被原车 nav 条盖住）；
 * - 让位判定里「探测不可用」⇒ **不让位**（宁可多盖，也不要到处让位）。
 */
class DockYieldPolicyTest {

    // ---------------------------------------------------------------- 让位判定

    @Test
    fun `名单命中的沉浸 App 让位`() {
        assertTrue(DockYieldPolicy.decide(knownImmersive = true, desktopOnScreen = false, probe = false))
    }

    @Test
    fun `探测到沉浸的 App 让位`() {
        assertTrue(DockYieldPolicy.decide(knownImmersive = false, desktopOnScreen = false, probe = true))
    }

    @Test
    fun `普通 App 不让位`() {
        assertFalse(DockYieldPolicy.decide(knownImmersive = false, desktopOnScreen = false, probe = false))
    }

    @Test
    fun `桌面永远不让位`() {
        // 桌面自己的那条 Dock 是**页面内 View**，让位了桌面上就一条 Dock 都不剩。
        assertFalse(DockYieldPolicy.decide(knownImmersive = false, desktopOnScreen = true, probe = true))
    }

    @Test
    fun `探测不可用时不让位`() {
        // 第193轮的核心保守规则：
        // 探测不可信（全局沉浸策略在场 / 没 shell 权限）时，
        // **宁可多盖一点，也不要到处让位** —— 让位会把原车导航栏露出来。
        assertFalse(DockYieldPolicy.decide(knownImmersive = false, desktopOnScreen = false, probe = null))
    }

    @Test
    fun `桌面在前台时探测结果一律不参与判定`() {
        for (probe in listOf(true, false, null)) {
            assertFalse(
                "桌面在前台时不该让位（probe=$probe）",
                DockYieldPolicy.decide(knownImmersive = false, desktopOnScreen = true, probe = probe),
            )
        }
    }

    // ---------------------------------------------------------------- 策略层

    @Test
    fun `预留区路径不写全局导航沉浸`() {
        // 预留带必须活着 —— 写了就把它摘掉，App 全屏、Dock 盖内容、
        // 全局沉浸标志位还会污染让位探测。
        val output = SystemNavigationPolicy.merge(
            current = null,
            hideStatus = false,
            hideStatusPackages = emptySet(),
            navInsets = true,
            hideNavigation = true,
        )
        assertFalse(
            "预留区路径不该写 immersive.navigation：$output",
            output.orEmpty().contains("immersive.navigation"),
        )
    }

    @Test
    fun `无预留区路径仍然写全局导航沉浸`() {
        // 2038 悬浮窗层级低于系统导航栏 —— 不把原车 nav 条从窗口布局里摘掉，
        // 它就会画在我们的 Dock 上面。这条路径**必须**写。
        val value = OemDockPolicy.buildPolicyValue(
            hideDock = true,
            hideStatus = false,
            hideStatusPackages = null,
        )
        assertEquals("immersive.navigation=*", value)
    }

    @Test
    fun `全局沉浸策略会让沉浸探测失效`() {
        // mLastSystemUiFlags 是全局值：有通配符沉浸策略时它永远带沉浸位，
        // 反映的是策略而不是某个 App —— 这正是「所有 App 都让位」的来源。
        assertTrue(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.navigation=*"))
        // 单包策略不影响别的 App，探测仍然有意义。
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.navigation=com.ecarx.cardesk"))
    }
}
