package com.cardesk.launcher.core.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第182轮：「任意 App 自己 hideNavigationBars 时 Dock 让位」的判定逻辑。
 *
 * 判定来源是系统窗口策略 dump 里的 `mLastSystemUiFlags`，这里只测**纯解析 + 判位**，
 * 不测真正跑 `dumpsys`（那属于设备行为）。
 */
class ImmersiveDetectorTest {

    @Test
    fun `HIDE_NAVIGATION 与两种 IMMERSIVE 都算隐藏导航栏`() {
        assertTrue(ImmersiveDetector.looksImmersive(0x2)) // HIDE_NAVIGATION
        assertTrue(ImmersiveDetector.looksImmersive(0x800)) // IMMERSIVE
        assertTrue(ImmersiveDetector.looksImmersive(0x1000)) // IMMERSIVE_STICKY
        assertTrue(ImmersiveDetector.looksImmersive(0x2 or 0x1000))
    }

    @Test
    fun `只隐藏状态栏不算隐藏导航栏`() {
        // FULLSCREEN = 0x4，只影响状态栏。
        assertFalse(ImmersiveDetector.looksImmersive(0x4))
        assertFalse(ImmersiveDetector.looksImmersive(0x0))
        // LAYOUT_STABLE / LAYOUT_HIDE_NAVIGATION 只是布局标志，不代表真的隐藏了。
        assertFalse(ImmersiveDetector.looksImmersive(0x100 or 0x200))
    }

    @Test
    fun `能从 dumpsys 文本里解析出标志位`() {
        val dump = """
            WindowManagerPolicy
              mSafeMode=false
              mLastSystemUiFlags=0x1002
              mNavigationBarOnBottom=true
        """.trimIndent()
        assertEquals(0x1002, ImmersiveDetector.parseSystemUiFlags(dump))
        assertTrue(ImmersiveDetector.looksImmersive(ImmersiveDetector.parseSystemUiFlags(dump)!!))
    }

    @Test
    fun `没有标志位时返回 null 而不是 0`() {
        // null 表示「探测不可用」，调用方要回退名单判断；
        // 返回 0 会被当成「确认没沉浸」，那就把名单里的 App 漏掉了。
        assertNull(ImmersiveDetector.parseSystemUiFlags("no flags here"))
        assertNull(ImmersiveDetector.parseSystemUiFlags(null))
        assertNull(ImmersiveDetector.parseSystemUiFlags(""))
    }

    // ---------------------------------------- 第193轮：全局沉浸策略会让探测失效

    @Test
    fun `通配符导航沉浸算全局策略`() {
        assertTrue(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.navigation=*"))
        assertTrue(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.full=*"))
    }

    @Test
    fun `带豁免包的通配符仍然算全局策略`() {
        // 除豁免包外所有 App 都被强制沉浸 ⇒ 探测对绝大多数 App 都不可信。
        assertTrue(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.navigation=*,-com.ecarx.cardesk"))
    }

    @Test
    fun `只针对单个包的策略不算全局`() {
        // 别的 App 前台时这个全局标志位仍然有代表性 ⇒ 探测照常可用。
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.navigation=com.ecarx.cardesk"))
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.navigation=com.a,com.b"))
    }

    @Test
    fun `只沉浸状态栏不算全局导航策略`() {
        // immersive.status 也会带 IMMERSIVE 位，但它只影响状态栏，
        // 不足以让「隐藏导航栏」的探测失效。
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.status=*"))
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy("immersive.status=*,-com.a"))
    }

    @Test
    fun `空策略不算全局`() {
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy(null))
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy(""))
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy("null"))
        assertFalse(ImmersiveDetector.hasGlobalImmersivePolicy("foo=bar"))
    }

    @Test
    fun `多段策略里只要有一段是通配符就算全局`() {
        assertTrue(
            ImmersiveDetector.hasGlobalImmersivePolicy(
                "immersive.status=*:immersive.navigation=*:foo=bar",
            ),
        )
    }
}
