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

    // ------------------------------------------------------------ 地图底色（第一百四十二轮）

    /**
     * 高德地图的代表性底色。
     *
     * 这些不是「猜的灰」，是高德日间 / 夜间两套配色里**真实存在的元素色**：
     * 日间是米白底 + 绿地 + 深色 POI 标签；夜间是深蓝黑底 + 青蓝路网 + 亮色 POI 字，
     * 另外两套配色里都有那个**饱和的青蓝**（路线 / 路网 / POI 图标）。
     *
     * 歌词就压在这堆颜色上面 —— 之前只按「白底 / 黑底」配过，
     * 落到地图上就成了「深字陷进深色 POI 标签里」「浅字混进青色路网里」。
     */
    private object MapColors {
        /** 日间：陆地米白底。 */
        const val DAY_LAND = 0xFFF2EFE6.toInt()

        /** 日间：绿地（浅绿）。 */
        const val DAY_PARK = 0xFFD6E8C8.toInt()

        /** 日间：POI 标签的深灰蓝 —— 深色字撞上它就「陷进去」。 */
        const val DAY_POI = 0xFF5A6B7A.toInt()

        /** 日间：水系（浅蓝）。 */
        const val DAY_WATER = 0xFFA8D5F0.toInt()

        /** 夜间：陆地深蓝黑底。 */
        const val NIGHT_LAND = 0xFF0C1018.toInt()

        /** 夜间：路网（深蓝灰）。 */
        const val NIGHT_ROAD = 0xFF2A3550.toInt()

        /** 夜间：POI 标签的浅蓝灰 —— 浅色字撞上它就「混进去」。 */
        const val NIGHT_POI = 0xFF8FA6C0.toInt()

        /** 两套配色里都有的**饱和青蓝**（路线 / 路网 / POI 图标）。 */
        const val CYAN = 0xFF00C8FF.toInt()

        /** 夜间：金色路名。 */
        const val NIGHT_GOLD = 0xFFC8A45C.toInt()
    }

    @Test
    fun 日间配色压在地图上也分得开() {
        val backgrounds = listOf(
            MapColors.DAY_LAND,
            MapColors.DAY_PARK,
            MapColors.DAY_POI,
            MapColors.DAY_WATER,
        )
        for (bg in backgrounds) {
            assertSeparated(
                "日间主色", ClusterLyricColors.DAY_MAIN, ClusterLyricColors.DAY_HALO, bg,
            )
            assertSeparated(
                "日间副色", ClusterLyricColors.DAY_SUB, ClusterLyricColors.DAY_HALO, bg,
            )
        }
    }

    @Test
    fun 夜间配色压在地图上也分得开() {
        val backgrounds = listOf(
            MapColors.NIGHT_LAND,
            MapColors.NIGHT_ROAD,
            MapColors.NIGHT_POI,
            MapColors.CYAN,
            MapColors.NIGHT_GOLD,
        )
        for (bg in backgrounds) {
            assertSeparated(
                "夜间主色", ClusterLyricColors.NIGHT_MAIN, ClusterLyricColors.NIGHT_HALO, bg,
            )
            assertSeparated(
                "夜间副色", ClusterLyricColors.NIGHT_SUB, ClusterLyricColors.NIGHT_HALO, bg,
            )
        }
    }

    @Test
    fun 描边色与文字明度相反() {
        // 描边必须和字**反着来**：白天字暗 → 描边亮；夜里字亮 → 描边暗。
        // 反了的话描边不但不帮忙，还会让字的轮廓糊掉。
        assertTrue(
            "日间描边应当比日间字亮",
            luminance(ClusterLyricColors.DAY_HALO) > luminance(ClusterLyricColors.DAY_MAIN),
        )
        assertTrue(
            "夜间描边应当比夜间字暗",
            luminance(ClusterLyricColors.NIGHT_HALO) < luminance(ClusterLyricColors.NIGHT_MAIN),
        )
    }

    @Test
    fun 夜间主色不再和地图的饱和青蓝一个明度() {
        // 这是「还是不太明显」最硬的一条证据：
        //
        // 旧的夜间主色 `0xFF7FE3D4` 与高德路线青蓝 `#00C8FF` 的对比度只有
        // **1.29 : 1**（亮度 0.642 vs 0.485）—— 也就是说旧配色下，
        // 歌词和地图上的路线**在亮度上几乎等价**，
        // 屏幕上根本分不出谁是谁。
        //
        // 现在主色提到 `0xFFA8F7EA`，亮度 0.808，和地图青蓝拉开了明度差。
        assertTrue(
            "夜间主色和地图青蓝的对比度只有 %.2f:1（应该 > 1.3）".format(
                contrast(ClusterLyricColors.NIGHT_MAIN, MapColors.CYAN),
            ),
            contrast(ClusterLyricColors.NIGHT_MAIN, MapColors.CYAN) > 1.3,
        )
    }

    /**
     * 「字 + 描边」这对组合，必须和底色分得开。
     *
     * 判据不是「字自己的对比度」—— 那是纯色底的思路。
     * 地图底色是**逐块不同**的：同一个字压在一张图上，
     * 这一块底浅、下一块底深，**不可能有一个字色同时压住所有块**。
     *
     * 真正管用的是「贴纸式」的两层结构：
     * **字或者描边，至少有一层和这块底色拉开 4.5 : 1**。
     *
     * - 浅底块 → 靠字（深字压浅底）；
     * - 深底块 → 靠描边（亮字套黑边 / 暗字套白边）。
     *
     * @param text 文字色
     * @param halo 描边色（带 alpha，要先和底色合成再算）
     * @param bg 这一块地图底色
     */
    private fun assertSeparated(name: String, text: Int, halo: Int, bg: Int) {
        val haloAlpha = (halo ushr 24) and 0xFF
        val composited = if (haloAlpha >= 255) {
            halo
        } else {
            // 描边是半透明的：先叠到底色上，再拿合成结果去比。
            val r = blend((halo ushr 16) and 0xFF, (bg ushr 16) and 0xFF, haloAlpha)
            val g = blend((halo ushr 8) and 0xFF, (bg ushr 8) and 0xFF, haloAlpha)
            val b = blend(halo and 0xFF, bg and 0xFF, haloAlpha)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val byText = contrast(text, bg)
        val byHalo = contrast(composited, bg)
        val best = maxOf(byText, byHalo)
        assertTrue(
            "$name 在 #%06X 上分不开：字 %.2f:1、描边 %.2f:1".format(bg and 0xFFFFFF, byText, byHalo),
            best >= 4.5,
        )
    }

    /** 半透明前景叠到底色上（sRGB 直叠，和 Android 的绘制口径一致）。 */
    private fun blend(fg: Int, bg: Int, alpha: Int): Int =
        ((fg * alpha + bg * (255 - alpha)) / 255.0).toInt().coerceIn(0, 255)

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
