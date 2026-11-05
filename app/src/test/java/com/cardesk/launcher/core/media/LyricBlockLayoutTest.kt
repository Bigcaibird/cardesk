package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页中央歌词块的**共用排版规则**（第 115 轮；第 117 轮：行数不再写死）。
 *
 * 桌面（`ScenePage`）和设置页里的预览条共用这一份 ——
 * 两边各写一套的话，预览跟实际画出来的东西对不上，用户就白调滑杆了。
 *
 * 第117轮起有两种布局（三行 / KTV 两行），所以这里的用例都**按布局给行数**，
 * 而不是像以前那样写死 5 行。
 */
class LyricBlockLayoutTest {

    /** 三行布局的字体度量（40px / 56px / 40px，ascent / descent 都是正值）。 */
    private fun metrics3(): Pair<FloatArray, FloatArray> {
        val asc = floatArrayOf(46f, 65f, 46f)
        val desc = floatArrayOf(11f, 16f, 11f)
        return asc to desc
    }

    /** KTV 两行布局的字体度量（40px / 56px）。 */
    private fun metrics2(): Pair<FloatArray, FloatArray> {
        val asc = floatArrayOf(46f, 65f)
        val desc = floatArrayOf(11f, 16f)
        return asc to desc
    }

    @Test
    fun `当前行的基线是零`() {
        val (asc, desc) = metrics3()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 3, 1)
        assertEquals(0f, base[1], 0.001f)
    }

    @Test
    fun `行序从上到下`() {
        val (asc, desc) = metrics3()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 3, 1)
        assertTrue("上一句 < 当前句", base[0] < base[1])
        assertTrue("当前句 < 下一句", base[1] < base[2])
    }

    @Test
    fun `相邻行的间距由字体度量加行距决定`() {
        val (asc, desc) = metrics3()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 3, 1)
        // 当前行 ↔ 上一句：当前行的 ascent + 行距 + 上一句的 descent
        assertEquals(asc[1] + 18f + desc[0], base[1] - base[0], 0.001f)
        // 当前行 ↔ 下一句：当前行的 descent + 行距 + 下一句的 ascent
        assertEquals(desc[1] + 18f + asc[2], base[2] - base[1], 0.001f)
    }

    @Test
    fun `上下只是近似对称差在字体度量上`() {
        val (asc, desc) = metrics3()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 3, 1)
        // 上下两行到当前行的距离**不会完全相等**：
        // 字形的 ascent（往上留白）比 descent（往下留白）大，
        // 所以上一行离得远一点。差值只来自字体度量：
        //   上一行算的是「上一行的 descent」，下一行算的是「下一行的 ascent」。
        val up = base[1] - base[0]
        val down = base[2] - base[1]
        assertEquals((asc[1] - desc[1]) - (asc[2] - desc[0]), up - down, 0.001f)
    }

    @Test
    fun `行距越大行与行离得越远`() {
        val (asc, desc) = metrics3()
        val near = LyricBlockLayout.baselines(asc, desc, 18f, 3, 1)
        val far = LyricBlockLayout.baselines(asc, desc, 30f, 3, 1)
        assertTrue("大行距应该更松", far[1] - far[0] > near[1] - near[0])
        assertTrue("大行距应该更松", far[2] - far[1] > near[2] - near[1])
    }

    @Test
    fun `平移之后整块中心落在目标上`() {
        val (asc, desc) = metrics3()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 3, 1)
        val shift = LyricBlockLayout.centerShift(asc, desc, base, 500f, 3)
        for (i in base.indices) base[i] += shift
        val top = base[0] - asc[0]
        val bottom = base[2] + desc[2]
        assertEquals(500f, (top + bottom) / 2f, 0.001f)
    }

    @Test
    fun `字号变大后平移量会把块拉回同一个中心`() {
        val (asc, desc) = metrics3()
        // 1.4 倍的行距（字号整体放大时的近似）
        val base = LyricBlockLayout.baselines(asc, desc, 25.2f, 3, 1)
        val shift = LyricBlockLayout.centerShift(asc, desc, base, 500f, 3)
        for (i in base.indices) base[i] += shift
        val top = base[0] - asc[0]
        val bottom = base[2] + desc[2]
        assertEquals("放大后中心仍然在 500", 500f, (top + bottom) / 2f, 0.001f)
    }

    // ---------------------------------------------------------- KTV 两行（第117轮）

    @Test
    fun `KTV两行布局也走同一套规则`() {
        val (asc, desc) = metrics2()
        val cur = LyricLayout.KTV.currentIndex
        assertEquals("KTV 布局的当前句在最上面（第一行）", 0, cur)
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 2, cur)
        assertEquals(0f, base[cur], 0.001f)
        assertTrue("下一句应该在当前句下面", base[0] < base[1])
        assertEquals("两行之间的间距 = 下一行的 ascent + 行距 + 当前行的 descent",
            asc[1] + 18f + desc[0], base[1] - base[0], 0.001f)
    }

    @Test
    fun `KTV两行整块同样居中在目标上`() {
        val (asc, desc) = metrics2()
        val base = LyricBlockLayout.baselines(asc, desc, 18f, 2, LyricLayout.KTV.currentIndex)
        val shift = LyricBlockLayout.centerShift(asc, desc, base, 500f, 2)
        for (i in base.indices) base[i] += shift
        val top = base[0] - asc[0]
        val bottom = base[1] + desc[1]
        assertEquals(500f, (top + bottom) / 2f, 0.001f)
    }

    @Test
    fun `当前行下标越界会被拒掉`() {
        val (asc, desc) = metrics3()
        var threw = false
        try {
            LyricBlockLayout.baselines(asc, desc, 18f, 3, 5)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("当前行下标越界必须报错（否则会画到数组外面）", threw)
    }
}
