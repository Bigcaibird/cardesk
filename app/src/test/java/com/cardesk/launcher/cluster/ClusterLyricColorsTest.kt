package com.cardesk.launcher.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 仪表歌词的日夜配色（第一百三十七轮）。
 *
 * ## 为什么这些值得用单测钉住
 *
 * 颜色配错是**最容易复发又最难肉眼发现**的一类错：
 * 改一个色值不会编译失败、不会崩，只会在某一块屏的某个时段「看着别扭」。
 * 而这个功能最要紧的输出就**只有颜色** —— 位置、行数、样式都可以调，
 * 颜色不对就是直接看不清。
 *
 * 所以这里把两件事钉死：
 * 1. **日夜两套确实不同**（防止有人「顺手统一成一套」）；
 * 2. **两套在各自的仪表底色上都够亮**（WCAG AA 正文 4.5 : 1），
 *    而且**亮的那套恰好落在深底上、暗的那套恰好落在白底上**
 *    —— 颜色和底色配反了，对比度照样好看，但字照样看不清。
 */
class ClusterLyricColorsTest {

    @Test
    fun 日夜两套颜色确实不同() {
        assertNotEquals(ClusterLyricColors.main(true), ClusterLyricColors.main(false))
        assertNotEquals(ClusterLyricColors.sub(true), ClusterLyricColors.sub(false))
        assertNotEquals(ClusterLyricColors.plate(true), ClusterLyricColors.plate(false))
    }

    @Test
    fun 取值跟着日夜走() {
        assertEquals(ClusterLyricColors.NIGHT_MAIN, ClusterLyricColors.main(true))
        assertEquals(ClusterLyricColors.DAY_MAIN, ClusterLyricColors.main(false))
        assertEquals(ClusterLyricColors.NIGHT_SUB, ClusterLyricColors.sub(true))
        assertEquals(ClusterLyricColors.DAY_SUB, ClusterLyricColors.sub(false))
        assertEquals(ClusterLyricColors.NIGHT_PLATE, ClusterLyricColors.plate(true))
        assertEquals(ClusterLyricColors.DAY_PLATE, ClusterLyricColors.plate(false))
    }

    @Test
    fun 用户挑过的颜色优先于日夜() {
        val custom = 0xFF123456.toInt()
        assertEquals(custom, ClusterLyricColors.resolveMain(true, custom))
        assertEquals(custom, ClusterLyricColors.resolveMain(false, custom))
        assertEquals(custom, ClusterLyricColors.resolveSub(true, custom))
        assertEquals(custom, ClusterLyricColors.resolveSub(false, custom))
    }

    @Test
    fun 没挑过颜色时跟随日夜() {
        // 0 = 没挑过 = 跟随日夜（这是设置页「恢复跟随日夜」写进去的值）。
        assertEquals(ClusterLyricColors.main(true), ClusterLyricColors.resolveMain(true, 0))
        assertEquals(ClusterLyricColors.main(false), ClusterLyricColors.resolveMain(false, 0))
        assertEquals(ClusterLyricColors.sub(true), ClusterLyricColors.resolveSub(true, 0))
        assertEquals(ClusterLyricColors.sub(false), ClusterLyricColors.resolveSub(false, 0))
    }

    @Test
    fun 底板色本身不带透明度() {
        // 透明度是「仪表背景透明度」滑杆单独给的，
        // 底色常量只带 RGB —— 否则两层 alpha 会叠在一起算出个鬼。
        assertEquals(0, ClusterLyricColors.NIGHT_PLATE ushr 24)
        assertEquals(0, ClusterLyricColors.DAY_PLATE ushr 24)
    }

    @Test
    fun 日间配色落在白色仪表上够清楚() {
        // 日间仪表是白底 ⇒ 字必须够暗。
        val white = 0xFFFFFFFF.toInt()
        val main = contrast(ClusterLyricColors.DAY_MAIN, white)
        val sub = contrast(ClusterLyricColors.DAY_SUB, white)
        assertTrue("日间主色对白底只有 %.2f:1".format(main), main >= 4.5)
        assertTrue("日间副色对白底只有 %.2f:1".format(sub), sub >= 4.5)
    }

    @Test
    fun 夜间配色落在深色仪表上够清楚() {
        // 夜间仪表是深底 ⇒ 字必须够亮。
        val dark = 0xFF1A2430.toInt()
        val main = contrast(ClusterLyricColors.NIGHT_MAIN, dark)
        val sub = contrast(ClusterLyricColors.NIGHT_SUB, dark)
        assertTrue("夜间主色对深底只有 %.2f:1".format(main), main >= 4.5)
        assertTrue("夜间副色对深底只有 %.2f:1".format(sub), sub >= 4.5)
    }

    @Test
    fun 底板和字配在同一侧() {
        // 这是最容易配反的地方：
        // 深底板配深字 → 字糊在底上；浅底板配亮字 → 一样糊。
        //
        // 所以「底板明度」必须和「字明度」**相反**。
        assertTrue(
            "夜间底板应当比夜间字暗",
            luminance(ClusterLyricColors.NIGHT_PLATE) < luminance(ClusterLyricColors.NIGHT_MAIN),
        )
        assertTrue(
            "日间底板应当比日间字亮",
            luminance(ClusterLyricColors.DAY_PLATE) > luminance(ClusterLyricColors.DAY_MAIN),
        )
    }

    // ------------------------------------------------------------ 对比度算法

    /**
     * WCAG 2.x 的相对亮度。
     *
     * sRGB 分量先做**线性化**（分段函数），再按 0.2126 / 0.7152 / 0.0722 加权。
     */
    private fun luminance(color: Int): Double {
        val r = channel(color ushr 16 and 0xFF)
        val g = channel(color ushr 8 and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG 对比度：`(L1 + 0.05) / (L2 + 0.05)`，`L1` 取亮的那一边。 */
    private fun contrast(a: Int, b: Int): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }
}
