package com.cardesk.launcher.core.dock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第144轮：全局 Dock 风量键的语义决策。
 *
 * 这个纯函数就是「桌面上那条 Dock（其实是全局 Dock 窗口）上的风量键」的判据，
 * 覆盖它就覆盖了第142~143轮真机没生效的那条约定：
 *
 * - 桌面窗口还在屏幕上 → 弹**空调浮层**（和桌面自己那条 Dock 的语义一致）；
 * - 别的应用盖上来了 → 直调车控**风量 +1**，不打断当前应用。
 */
class DockFanPolicyTest {

    @Test
    fun `桌面窗口还在屏幕上时弹空调浮层`() {
        assertEquals(
            DockFanPolicy.ACTION_OPEN_CLIMATE,
            DockFanPolicy.decide(desktopOnScreen = true),
        )
    }

    @Test
    fun `桌面窗口不在屏幕上时直调车控风量加一`() {
        assertEquals(
            DockFanPolicy.ACTION_STEP_FAN,
            DockFanPolicy.decide(desktopOnScreen = false),
        )
    }

    @Test
    fun `两个动作互斥`() {
        assertEquals(
            DockFanPolicy.ACTION_OPEN_CLIMATE,
            DockFanPolicy.decide(desktopOnScreen = true),
        )
        assertEquals(
            DockFanPolicy.ACTION_STEP_FAN,
            DockFanPolicy.decide(desktopOnScreen = false),
        )
    }
}
