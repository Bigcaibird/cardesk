package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片风格（第五十八轮）的纯逻辑回归。
 *
 * 这一组是 **JVM 单测**（不碰 Android），钉住四件事：
 * 1. 透明度只缩放 alpha，RGB 不动；
 * 2. 自选卡片色后，卡内两层按明度差**派生**（层级关系还在）；
 * 3. 圆角改动时内嵌块 / 图标底**等比缩放**；
 * 4. 越界输入被夹回合法区间。
 */
class CardStyleTest {

    @Test
    fun 默认值是原样不透明() {
        assertEquals(100, CardStyle.DEFAULT_OPACITY)
        assertEquals(28, CardStyle.DEFAULT_RADIUS)
        assertEquals("卡片风格默认不该改主题", Palettes.night, CardStyle.apply(Palettes.night, 0, 100, 28))
    }

    @Test
    fun 透明度只缩放alpha不动rgb() {
        val out = CardStyle.apply(Palettes.night, 0, 50, 28)
        assertEquals(0x80, (out.surface ushr 24) and 0xFF)
        assertEquals(Palettes.night.surface and 0x00FFFFFF, out.surface and 0x00FFFFFF)
        // 卡内两层也要跟着一起透明
        assertEquals(0x80, (out.surfaceStrong ushr 24) and 0xFF)
        assertEquals(0x80, (out.surfaceSoft ushr 24) and 0xFF)
    }

    @Test
    fun 自选颜色后卡面就是自选色() {
        val custom = 0xFF3A6EA5.toInt()
        val out = CardStyle.apply(Palettes.night, custom, 100, 28)
        assertEquals(custom, out.surface)
        // 两层内嵌面必须和卡面**不同**（否则层级没了）
        assertNotEquals(out.surface, out.surfaceStrong)
        assertNotEquals(out.surface, out.surfaceSoft)
        // 夜间：强面比卡面亮、弱面比卡面暗
        assertTrue(
            "夜间强面应该更亮",
            (out.surfaceStrong and 0xFFFFFF) > (out.surface and 0xFFFFFF),
        )
        assertTrue(
            "夜间弱面应该更暗",
            (out.surfaceSoft and 0xFFFFFF) < (out.surface and 0xFFFFFF),
        )
    }

    @Test
    fun 日间自选颜色的层级方向相反() {
        val custom = 0xFFE8D7B0.toInt()
        val out = CardStyle.apply(Palettes.day, custom, 100, 28)
        assertEquals(custom, out.surface)
        assertTrue(
            "日间强面应该更暗",
            (out.surfaceStrong and 0xFFFFFF) < (out.surface and 0xFFFFFF),
        )
    }

    @Test
    fun 透明度与颜色可以叠加() {
        val custom = 0xFF3A6EA5.toInt()
        val out = CardStyle.apply(Palettes.night, custom, 40, 28)
        assertEquals(0x66, (out.surface ushr 24) and 0xFF)
        assertEquals(custom and 0x00FFFFFF, out.surface and 0x00FFFFFF)
    }

    @Test
    fun 圆角改动时内嵌块等比缩放() {
        CardStyle.applyRadius(40)
        assertEquals(40f, Shape.CARD, 0.01f)
        assertEquals(40f * 18f / 28f, Shape.INNER, 0.01f)
        assertEquals(40f * 16f / 28f, Shape.TILE, 0.01f)
        // 胶囊永远是胶囊
        assertEquals(999f, Shape.PILL, 0.01f)
        // 复位回默认值，后面的用例不受影响
        CardStyle.applyRadius(CardStyle.DEFAULT_RADIUS)
        assertEquals(28f, Shape.CARD, 0.01f)
        assertEquals(18f, Shape.INNER, 0.01f)
        assertEquals(16f, Shape.TILE, 0.01f)
    }

    @Test
    fun 越界输入被夹回() {
        assertEquals(CardStyle.MIN_OPACITY, CardStyle.clampOpacity(0))
        assertEquals(CardStyle.MIN_OPACITY, CardStyle.clampOpacity(-10))
        assertEquals(CardStyle.MAX_OPACITY, CardStyle.clampOpacity(180))
        assertEquals(CardStyle.MIN_RADIUS, CardStyle.clampRadius(-5))
        assertEquals(CardStyle.MAX_RADIUS, CardStyle.clampRadius(999))
        // 夹过之后套用也不能出意外
        val out = CardStyle.apply(Palettes.night, 0, 999, -3)
        assertEquals(0xFF, (out.surface ushr 24) and 0xFF)
    }

    @Test
    fun 颜色混合与alpha缩放() {
        assertEquals(0xFFFFFFFF.toInt(), CardStyle.mix(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0f))
        assertEquals(0xFF000000.toInt(), CardStyle.mix(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), CardStyle.mix(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f))
        assertEquals(0x80102030.toInt(), CardStyle.scaleAlpha(0xFF102030.toInt(), 0.5f))
        assertEquals(0x00102030, CardStyle.scaleAlpha(0xFF102030.toInt(), 0f))
    }
}
