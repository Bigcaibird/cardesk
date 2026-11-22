package com.cardesk.launcher.core.dock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第204轮：全局 Dock 显隐的纯函数（见 [DockVisibility]）。 */
class DockVisibilityTest {

    @Test
    fun `桌面在前台时可见`() {
        assertTrue(DockVisibility.want(nativeInsets = true, homeForeground = true, immersiveAppForeground = false))
    }

    @Test
    fun `别的 App 在前台时也可见（盖住原车栏）`() {
        assertTrue(DockVisibility.want(nativeInsets = true, homeForeground = false, immersiveAppForeground = false))
    }

    @Test
    fun `沉浸型 App 在前台时让位`() {
        assertFalse(DockVisibility.want(nativeInsets = true, homeForeground = false, immersiveAppForeground = true))
    }

    @Test
    fun `桌面在前台时沉浸名单不生效`() {
        assertFalse(DockVisibility.want(nativeInsets = false, homeForeground = true, immersiveAppForeground = true))
    }
}
