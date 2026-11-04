package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 115 轮：五行歌词块的**共用排版规则**。
 *
 * 桌面（`ScenePage`）和设置页里的预览条共用这一份 ——
 * 两边各写一套的话，预览跟实际画出来的东西对不上，用户就白调滑杆了。
 */
class LyricBlockLayoutTest {

    /** 一组典型的字体度量（ascent / descent 都是正值）。 */
    private fun metrics(): Pair<FloatArray, FloatArray> {
        val asc = floatArrayOf(39f, 46f, 65f, 46f, 39f)
        val desc = floatArrayOf(10f, 11f, 16f, 11f, 10f)
        return asc to desc
    }

    @Test
    fun `当前行的基线是零`() {
        val (asc, desc) = metrics()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 12f)
        assertEquals(0f, base[LyricBlockLayout.CURRENT], 0.001f)
    }

    @Test
    fun `行序从上到下`() {
        val (asc, desc) = metrics()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 12f)
        assertTrue("上2 < 上1", base[0] < base[1])
        assertTrue("上1 < 当前", base[1] < base[2])
        assertTrue("当前 < 下1", base[2] < base[3])
        assertTrue("下1 < 下2", base[3] < base[4])
    }

    @Test
    fun `相邻行的间距由字体度量加行距决定`() {
        val (asc, desc) = metrics()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 12f)
        // 当前行 ↔ 上 1 句：当前行的 ascent + 行距 + 上 1 句的 descent
        assertEquals(asc[2] + 18f + desc[1], base[2] - base[1], 0.001f)
        // 当前行 ↔ 下 1 句：当前行的 descent + 行距 + 下 1 句的 ascent
        assertEquals(desc[2] + 18f + asc[3], base[3] - base[2], 0.001f)
        // 上 1 句 ↔ 上 2 句：衬句之间的行距
        assertEquals(asc[1] + 12f + desc[0], base[1] - base[0], 0.001f)
    }

    @Test
    fun `上下只是近似对称差在字体度量上`() {
        val (asc, desc) = metrics()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 12f)
        // 上下两行到当前行的距离**不会完全相等**：
        // 字形的 ascent（往上留白）比 descent（往下留白）大，
        // 所以上一行离得远一点。差值只来自字体度量：
        //   上一行算的是「上一行的 descent」，下一行算的是「下一行的 ascent」。
        val up = base[2] - base[1]
        val down = base[3] - base[2]
        assertEquals((asc[2] - desc[2]) - (asc[3] - desc[1]), up - down, 0.001f)
    }

    @Test
    fun `行距越大行与行离得越远`() {
        val (asc, desc) = metrics()
        val near = LyricBlockLayout.baselines(asc, desc, 18f, 12f)
        val far = LyricBlockLayout.baselines(asc, desc, 30f, 20f)
        assertTrue("大行距应该更松", far[2] - far[1] > near[2] - near[1])
        assertTrue("大行距应该更松", far[1] - far[0] > near[1] - near[0])
    }

    @Test
    fun `平移之后整块中心落在目标上`() {
        val (asc, desc) = metrics()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 12f)
        val shift = LyricBlockLayout.centerShift(asc, desc, base, 500f)
        for (i in base.indices) base[i] += shift
        val top = base[0] - asc[0]
        val bottom = base[4] + desc[4]
        assertEquals(500f, (top + bottom) / 2f, 0.001f)
    }

    @Test
    fun `字号变大后平移量会把块拉回同一个中心`() {
        val (asc, desc) = metrics()
        // 1.4 倍的行距（字号整体放大时的近似）
        val base = LyricBlockLayout.baselines(asc, desc, 25.2f, 16.8f)
        val shift = LyricBlockLayout.centerShift(asc, desc, base, 500f)
        for (i in base.indices) base[i] += shift
        val top = base[0] - asc[0]
        val bottom = base[4] + desc[4]
        assertEquals("放大后中心仍然在 500", 500f, (top + bottom) / 2f, 0.001f)
    }
}
