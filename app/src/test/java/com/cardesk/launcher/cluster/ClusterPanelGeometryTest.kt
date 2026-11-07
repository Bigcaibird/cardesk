package com.cardesk.launcher.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表歌词面板**排版数学**的单测。
 *
 * ## 验的是什么
 *
 * 这一组数字同时被两个地方用：
 *
 * 1. 服务把窗口加到副屏上时，算「窗口左上角在屏幕像素里的位置」；
 * 2. 设置页预览算「预览里那个块该画在哪」。
 *
 * 两边只要有一处算错，用户在预览里摆好的位置落到仪表上就不是那个位置，
 * 而且**这种错在真车上很难看出来**（面板跑到屏幕外 vs 跑偏一点点，
 * 肉眼分不出来），所以只能靠单测钉住。
 *
 * ## 关键口径
 *
 * - 存的是**面板中心**在屏幕上的比例（`xRatio` / `yRatio`），不是左上角；
 * - 结果必须夹回屏内 —— 面板不可能被拖出屏幕；
 * - 高度按**当前句一个大字号 + 其余小字号**分别算（UNI 只有一档字号）。
 */
class ClusterPanelGeometryTest {

    // ------------------------------------------------------------ clampRatio

    @Test
    fun `clampRatio 把比例夹进 0 到 1`() {
        assertEquals(0f, ClusterPanelGeometry.clampRatio(-0.5f, 0.5f), 0.0001f)
        assertEquals(1f, ClusterPanelGeometry.clampRatio(1.8f, 0.5f), 0.0001f)
        assertEquals(0.3f, ClusterPanelGeometry.clampRatio(0.3f, 0.5f), 0.0001f)
    }

    @Test
    fun `clampRatio 对 NaN 和无穷大退回默认值`() {
        // NaN 往下游传会让整个布局算成 NaN，
        // 窗口尺寸就是 0 —— 表现是「加了窗口但什么都没画」。
        assertEquals(0.5f, ClusterPanelGeometry.clampRatio(Float.NaN, 0.5f), 0.0001f)
        assertEquals(0.5f, ClusterPanelGeometry.clampRatio(Float.POSITIVE_INFINITY, 0.5f), 0.0001f)
        assertEquals(0.5f, ClusterPanelGeometry.clampRatio(Float.NEGATIVE_INFINITY, 0.5f), 0.0001f)
    }

    // ------------------------------------------------------------ widthRatio

    @Test
    fun `widthRatio 不低于五分之一 不高于整屏`() {
        assertEquals(
            ClusterPanelGeometry.MIN_WIDTH_RATIO,
            ClusterPanelGeometry.widthRatio(0.01f),
            0.0001f,
        )
        assertEquals(1f, ClusterPanelGeometry.widthRatio(5f), 0.0001f)
    }

    @Test
    fun `widthRatio 对非法值退回默认宽度`() {
        assertEquals(
            ClusterPanelGeometry.DEFAULT_WIDTH_RATIO,
            ClusterPanelGeometry.widthRatio(Float.NaN),
            0.0001f,
        )
    }

    // ------------------------------------------------------------ rows

    @Test
    fun `rows 夹在 1 到 3 之间`() {
        assertEquals(1, ClusterPanelGeometry.rows(0))
        assertEquals(1, ClusterPanelGeometry.rows(-5))
        assertEquals(3, ClusterPanelGeometry.rows(9))
        assertEquals(2, ClusterPanelGeometry.rows(2))
    }

    // ------------------------------------------------------------ clamp

    @Test
    fun `clamp 在上限小于下限时取下限`() {
        assertEquals(3, ClusterPanelGeometry.clamp(10, 3, 3))
        assertEquals(3, ClusterPanelGeometry.clamp(1, 3, 3))
    }

    // ------------------------------------------------------------ width

    @Test
    fun `width 是屏宽乘比例`() {
        assertEquals(940, ClusterPanelGeometry.width(1000, 0.94f))
        assertEquals(500, ClusterPanelGeometry.width(1000, 0.5f))
    }

    @Test
    fun `width 不会超过屏宽`() {
        assertEquals(1000, ClusterPanelGeometry.width(1000, 2f))
    }

    @Test
    fun `width 在屏宽为 0 时仍返回正数`() {
        // 屏宽读回来是 0（屏刚起来还没量好）时，返回 0 会让窗口宽度为 0，
        // 加窗成功但什么都看不见 —— 返回 1 至少还有一个像素的窗口。
        assertTrue(ClusterPanelGeometry.width(0, 0.5f) >= 1)
    }

    // ------------------------------------------------------------ height

    @Test
    fun `height 一行时只算主歌词`() {
        val h = ClusterPanelGeometry.height(40f, 20f, 1)
        assertEquals((40f * ClusterPanelGeometry.ROW_SPACING).toInt(), h)
    }

    @Test
    fun `height 两行时加一行副歌词`() {
        val h = ClusterPanelGeometry.height(40f, 20f, 2)
        assertEquals(((40f + 20f) * ClusterPanelGeometry.ROW_SPACING).toInt(), h)
    }

    @Test
    fun `height 三行时加两行副歌词`() {
        val h = ClusterPanelGeometry.height(40f, 20f, 3)
        assertEquals(((40f + 20f + 20f) * ClusterPanelGeometry.ROW_SPACING).toInt(), h)
    }

    @Test
    fun `height 行数越多越高`() {
        val one = ClusterPanelGeometry.height(40f, 20f, 1)
        val two = ClusterPanelGeometry.height(40f, 20f, 2)
        val three = ClusterPanelGeometry.height(40f, 20f, 3)
        assertTrue(one < two)
        assertTrue(two < three)
    }

    @Test
    fun `height 对极小字号按 8 兜底`() {
        // 字号是 0 时高度会算成 0，窗口就没有高度了。
        val h = ClusterPanelGeometry.height(0f, 0f, 1)
        assertTrue(h >= 1)
    }

    // ------------------------------------------------------------ origin

    @Test
    fun `origin 按面板中心换算左上角`() {
        // 屏 1000×500，面板 400×100，中心在 (500, 250) → 左上角 (300, 200)。
        val origin = ClusterPanelGeometry.origin(1000, 500, 400, 100, 0.5f, 0.5f)
        assertEquals(300, origin[0])
        assertEquals(200, origin[1])
    }

    @Test
    fun `origin 把面板夹回屏内`() {
        // 中心在左上角 → 左上角应为 0，不能是负数。
        val origin = ClusterPanelGeometry.origin(1000, 500, 400, 100, 0f, 0f)
        assertEquals(0, origin[0])
        assertEquals(0, origin[1])
    }

    @Test
    fun `origin 面板比屏大时也不会越界`() {
        val origin = ClusterPanelGeometry.origin(1000, 500, 2000, 900, 0.5f, 0.5f)
        assertEquals(0, origin[0])
        assertEquals(0, origin[1])
    }

    @Test
    fun `origin 对 NaN 比例退回默认中心`() {
        val origin = ClusterPanelGeometry.origin(1000, 500, 400, 100, Float.NaN, Float.NaN)
        val expectedX = (1000 * ClusterPanelGeometry.DEFAULT_X_RATIO).toInt() - 200
        val expectedY = (500 * ClusterPanelGeometry.DEFAULT_Y_RATIO).toInt() - 50
        assertEquals(expectedX, origin[0])
        assertEquals(expectedY, origin[1])
    }

    @Test
    fun `origin 面板右边缘不会超出屏宽`() {
        val origin = ClusterPanelGeometry.origin(1000, 500, 400, 100, 1f, 1f)
        assertTrue(origin[0] + 400 <= 1000)
        assertTrue(origin[1] + 100 <= 500)
    }
}
