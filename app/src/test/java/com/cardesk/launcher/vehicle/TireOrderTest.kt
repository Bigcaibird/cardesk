package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 四轮摆放顺序的单测。
 *
 * 我们第一页（场景页）与第二页（导航页）都用**竖置车模、车头朝上**：
 * 左列 FL/RL、右列 FR/RR。两页共用 [TireOrder]，保证不会再对不上。
 */
class TireOrderTest {

    @Test
    fun `左列是左前上左后下`() {
        assertEquals(TireOrder.FL, TireOrder.leftIndex(0))
        assertEquals(TireOrder.RL, TireOrder.leftIndex(1))
    }

    @Test
    fun `右列是右前上右后下`() {
        assertEquals(TireOrder.FR, TireOrder.rightIndex(0))
        assertEquals(TireOrder.RR, TireOrder.rightIndex(1))
    }

    @Test
    fun `下标语义是 FL FR RL RR`() {
        assertEquals(0, TireOrder.FL)
        assertEquals(1, TireOrder.FR)
        assertEquals(2, TireOrder.RL)
        assertEquals(3, TireOrder.RR)
    }

    @Test
    fun `四个轮位互不重复且覆盖全部下标`() {
        val all = TireOrder.LEFT_COLUMN.toList() + TireOrder.RIGHT_COLUMN.toList()
        assertEquals(4, all.size)
        assertEquals(setOf(0, 1, 2, 3), all.toSet())
    }

    @Test
    fun `轮位名称与下标一一对应`() {
        assertEquals(listOf("左前", "右前", "左后", "右后"), TireOrder.NAMES.toList())
    }
}
