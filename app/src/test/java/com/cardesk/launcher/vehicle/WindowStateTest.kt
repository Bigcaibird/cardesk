package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WindowState] 单测（纯口径，不依赖 Android 运行时）。
 *
 * 车窗的降级链是
 * `IDoorAndWindow` → `BCM_FUNC_WINDOW` → `WINDOW_POS` 属性，
 * 逐扇合并；这里只测**合并与聚合**的纯逻辑。
 */
class WindowStateTest {

    @Test
    fun `三档来源名字与文档口径一致`() {
        assertEquals("IDoorAndWindow", WindowState.SOURCE_OEM)
        // 第七十一轮：逐扇开度只在浮点通道的 `BCM_FUNC_WINDOW_POS` 里实现
        assertEquals("BCM_FUNC_WINDOW_POS", WindowState.SOURCE_ADAPTAPI)
        assertEquals("WINDOW_POS", WindowState.SOURCE_PROPERTY)
    }

    @Test
    fun `优先用 IDoorAndWindow`() {
        val r = WindowState.compose(
            oem = listOf(true, false, true, false),
            adapt = listOf(false, false, false, false),
            prop = listOf(false, false, false, false),
        )
        assertEquals(WindowState.SOURCE_OEM, r.source)
        assertEquals(listOf(true, false, true, false), r.eachOpen)
        assertEquals(false, r.allClosed)
    }

    @Test
    fun `IDoorAndWindow 没有时用 BCM_FUNC_WINDOW`() {
        val r = WindowState.compose(
            oem = null,
            adapt = listOf(false, false, true, true),
            prop = listOf(true, true, true, true),
        )
        assertEquals(WindowState.SOURCE_ADAPTAPI, r.source)
        assertEquals(listOf(false, false, true, true), r.eachOpen)
    }

    @Test
    fun `只剩 WINDOW_POS 属性时用它`() {
        val r = WindowState.compose(
            oem = null,
            adapt = null,
            prop = listOf(false, false, false, false),
        )
        assertEquals(WindowState.SOURCE_PROPERTY, r.source)
        assertEquals(true, r.allClosed)
    }

    @Test
    fun `逐扇合并 每一扇取第一个能解释的来源`() {
        val r = WindowState.compose(
            oem = listOf(true, null, null, null),
            adapt = listOf(null, false, null, null),
            prop = listOf(null, null, true, true),
        )
        assertEquals(listOf(true, false, true, true), r.eachOpen)
    }

    @Test
    fun `有一扇读不到就不能说全关`() {
        // 「读不到」≠「关着」—— 桌面显示 `--` 比显示一个假的「全关」好。
        val r = WindowState.compose(
            oem = listOf(false, false, false, null),
            adapt = null,
            prop = null,
        )
        assertNull(r.allClosed)
        assertEquals(listOf(false, false, false, null), r.eachOpen)
    }

    @Test
    fun `全读不到时整组为空`() {
        val r = WindowState.compose(oem = null, adapt = null, prop = null)
        assertNull(r.eachOpen)
        assertNull(r.allClosed)
        assertEquals("无", r.source)
    }

    @Test
    fun `布尔数组给 UI 用 读不到的那一扇按关处理`() {
        val r = WindowState.compose(
            oem = listOf(true, false, null, null),
            adapt = null,
            prop = null,
        )
        val arr = r.open!!
        assertEquals(4, arr.size)
        assertTrue(arr[0])
        assertEquals(false, arr[1])
    }
}
