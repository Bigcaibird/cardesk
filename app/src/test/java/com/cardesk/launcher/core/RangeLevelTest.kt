package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 续航分级（[RangeLevel]）单测（第136轮，纯逻辑）。
 *
 * 口径（用户第136轮原话）：
 * - 续航 **小于等于 100KM 且大于 50KM** → 黄色；
 * - 续航 **小于等于 50KM** → 红色；
 * - 其余 → 常态色。
 *
 * 边界值（50 / 100 / 101）必须钉死 ——
 * 「<= 50 归红色」这条最容易在实现时被写成「< 50」。
 */
class RangeLevelTest {

    @Test
    fun `大于 100 公里是正常`() {
        assertEquals(RangeLevel.Level.NORMAL, RangeLevel.levelOf(101))
        assertEquals(RangeLevel.Level.NORMAL, RangeLevel.levelOf(386))
    }

    @Test
    fun `正好 100 公里是黄色`() {
        assertEquals(RangeLevel.Level.WARN, RangeLevel.levelOf(100))
    }

    @Test
    fun `100 往下到 51 是黄色`() {
        assertEquals(RangeLevel.Level.WARN, RangeLevel.levelOf(99))
        assertEquals(RangeLevel.Level.WARN, RangeLevel.levelOf(51))
    }

    @Test
    fun `正好 50 公里是红色（不是黄色）`() {
        assertEquals(RangeLevel.Level.DANGER, RangeLevel.levelOf(50))
    }

    @Test
    fun `50 往下是红色`() {
        assertEquals(RangeLevel.Level.DANGER, RangeLevel.levelOf(49))
        assertEquals(RangeLevel.Level.DANGER, RangeLevel.levelOf(0))
    }

    @Test
    fun `读不到是 UNKNOWN 而不是很低`() {
        assertEquals(RangeLevel.Level.UNKNOWN, RangeLevel.levelOf(null))
        assertEquals(RangeLevel.Level.UNKNOWN, RangeLevel.levelOf(-1))
    }

    @Test
    fun `阈值常量就是口径本身`() {
        assertEquals(100, RangeLevel.WARN_KM)
        assertEquals(50, RangeLevel.DANGER_KM)
    }

    @Test
    fun `级别有可读的名字`() {
        assertEquals("正常", RangeLevel.labelOf(RangeLevel.Level.NORMAL))
        assertEquals("偏低", RangeLevel.labelOf(RangeLevel.Level.WARN))
        assertEquals("很低", RangeLevel.labelOf(RangeLevel.Level.DANGER))
        assertEquals("读不到", RangeLevel.labelOf(RangeLevel.Level.UNKNOWN))
    }
}
