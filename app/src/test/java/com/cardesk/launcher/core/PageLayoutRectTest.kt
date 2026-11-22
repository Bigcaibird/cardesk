package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第201轮：切页动画的手动量布局必须尊重页面自己的 LayoutParams。
 *
 * 回归对象：导航页被按 1920×1080 摆过之后，画中画承载面高度 1052 → 867 跳一次，
 * 底部出现长方形黑块（诊断包 2026-11-17）。
 */
class PageLayoutRectTest {

    @Test
    fun matchParent_fillsHost() {
        val box = PageLayoutRect.of(
            hostW = 1920, hostH = 1080,
            lpWidth = -1, lpHeight = -1,
        )!!
        assertEquals(0, box.left)
        assertEquals(0, box.top)
        assertEquals(1920, box.width)
        assertEquals(1080, box.height)
    }

    @Test
    fun navPageLayout_keepsHeightAndTopMargin() {
        // 导航页真身的 LayoutParams：topMargin=73、height=968-73=895（让出状态栏与 Dock 顶边）
        val box = PageLayoutRect.of(
            hostW = 1920, hostH = 1080,
            lpWidth = -1, lpHeight = 895,
            topMargin = 73,
        )!!
        assertEquals(0, box.left)
        assertEquals(73, box.top)
        assertEquals(1920, box.width)
        assertEquals(895, box.height)
    }

    @Test
    fun explicitWidth_wins() {
        val box = PageLayoutRect.of(
            hostW = 1920, hostH = 1080,
            lpWidth = 1600, lpHeight = 800,
            leftMargin = 160, topMargin = 100,
        )!!
        assertEquals(1600, box.width)
        assertEquals(800, box.height)
        assertEquals(160, box.left)
        assertEquals(100, box.top)
    }

    @Test
    fun margins_areSubtracted() {
        val box = PageLayoutRect.of(
            hostW = 1920, hostH = 1080,
            lpWidth = -1, lpHeight = -1,
            leftMargin = 20, topMargin = 30, rightMargin = 40, bottomMargin = 50,
        )!!
        assertEquals(1860, box.width)
        assertEquals(1000, box.height)
    }

    @Test
    fun unmeasuredHost_returnsNull() {
        assertNull(PageLayoutRect.of(0, 0, -1, -1))
        assertNull(PageLayoutRect.of(1920, 0, -1, -1))
    }

    @Test
    fun marginsEatAllSpace_returnsNull() {
        assertNull(PageLayoutRect.of(1920, 1080, -1, -1, topMargin = 1080))
    }
}
