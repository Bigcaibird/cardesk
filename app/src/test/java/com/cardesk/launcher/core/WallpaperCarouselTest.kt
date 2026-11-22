package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第207轮：**壁纸轮播**的纯逻辑测试。
 *
 * 这三条规则最容易出「肉眼看不见、但桌面表现不对」的错，所以单测盯住：
 * 1. 间隔归一（用户改过 ROM 偏好时不能跑飞）；
 * 2. 下一张是下一张（末尾回到开头）；
 * 3. 什么时候才该切（开着 + 够间隔 + 没在拖）。
 */
class WallpaperCarouselTest {

    @Test
    fun `间隔归一到候选里最近的那个`() {
        // 候选：15 / 30 / 60 / 300
        assertEquals(15, WallpaperCarousel.normalizeInterval(10))
        assertEquals(15, WallpaperCarousel.normalizeInterval(15))
        assertEquals(30, WallpaperCarousel.normalizeInterval(28))
        assertEquals(60, WallpaperCarousel.normalizeInterval(45))
        assertEquals(60, WallpaperCarousel.normalizeInterval(60))
        // 120 秒离 60 更近（差 60）而不是离 300（差 180）
        assertEquals(60, WallpaperCarousel.normalizeInterval(120))
        assertEquals(300, WallpaperCarousel.normalizeInterval(9999))
    }

    @Test
    fun `非法的间隔回落到默认`() {
        assertEquals(WallpaperCarousel.DEFAULT_INTERVAL_SEC, WallpaperCarousel.normalizeInterval(0))
        assertEquals(WallpaperCarousel.DEFAULT_INTERVAL_SEC, WallpaperCarousel.normalizeInterval(-5))
    }

    @Test
    fun `下一张是下一张，末尾回到开头`() {
        assertEquals(1, WallpaperCarousel.nextIndex(0, 9))
        assertEquals(8, WallpaperCarousel.nextIndex(7, 9))
        assertEquals(0, WallpaperCarousel.nextIndex(8, 9))
        // 越界也不怕
        assertEquals(0, WallpaperCarousel.nextIndex(-1, 9))
        assertEquals(0, WallpaperCarousel.nextIndex(0, 0))
    }

    @Test
    fun `够间隔才切`() {
        // 关着 → 永远不切
        assertFalse(WallpaperCarousel.shouldSwitch(false, 999_999L, 60, false))
        // 开着但没到间隔 → 不切
        assertFalse(WallpaperCarousel.shouldSwitch(true, 59_000L, 60, false))
        // 开着且够间隔 → 切
        assertTrue(WallpaperCarousel.shouldSwitch(true, 60_000L, 60, false))
        // 正在拖壁纸 → 不切（不能抢用户的操作）
        assertFalse(WallpaperCarousel.shouldSwitch(true, 999_999L, 60, true))
    }

    @Test
    fun `间隔用的是归一后的值`() {
        // 传了个不在候选里的 100 秒 → 按 60 秒算（离 100 最近的是 60）
        assertFalse(WallpaperCarousel.shouldSwitch(true, 59_000L, 100, false))
        assertTrue(WallpaperCarousel.shouldSwitch(true, 60_000L, 100, false))
    }
}
