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
}
