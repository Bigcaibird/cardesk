package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车况卡（**第三十六轮第三轮改造**）的纯逻辑单测。
 *
 * 车况卡要按「俯视车模」标出每一个车门 / 车窗 / 后备箱 / 引擎盖的开闭，
 * 这里定死两件事：
 *
 * 1. **条目顺序** —— 图例的 10 个条目必须和车模上的 10 块面板**一一对应**，
 *    下标错一位就会出现「左前门显示的是左前窗的状态」这种错位；
 * 2. **几何合理性** —— 面板矩形必须落在车模设计空间（120 × 240）内，
 *    而且左右对称（镜像关系）。
 */
class CarStatusTest {

    // ---------------------------------------------------------------- 条目顺序

    /** 10 个条目的名字与顺序是固定的（图例两列 × 五行）。 */
    @Test
    fun itemOrderIsFixed() {
        val items = CarStatus.items(
            doors = listOf(true, true, true, true),
            windows = booleanArrayOf(false, false, false, false),
            hood = true,
            trunk = true,
        )
        assertEquals(10, items.size)
        assertEquals(
            listOf("左前门", "右前门", "左后门", "右后门", "后备箱", "左前窗", "右前窗", "左后窗", "右后窗", "引擎盖"),
            items.map { it.label },
        )
    }

    /** 门的输入是「是否关闭」，输出是「是否打开」——方向不能反。 */
    @Test
    fun doorClosedMeansNotOpen() {
        val items = CarStatus.items(
            doors = listOf(true, false, true, false),
            windows = booleanArrayOf(true, false, true, false),
            hood = true,
            trunk = false,
        )
        // 前左门关 → 「关」；右前门开 → 「开」
        assertEquals(false, items[0].open)
        assertEquals(true, items[1].open)
        // 后备箱传 false = **没关** → 「开」；引擎盖传 true = 关着 → 「关」
        assertEquals(true, items[4].open)
        assertEquals(false, items[9].open)
        // 车窗本身就是「是否打开」
        assertEquals(true, items[5].open)
        assertEquals(false, items[6].open)
    }

    /** 读不到 → null（UI 显示「--」），绝不能当成「关」。 */
    @Test
    fun unreadableIsNullNotClosed() {
        val items = CarStatus.items(doors = null, windows = null, hood = null, trunk = null)
        assertEquals(10, items.size)
        for (it in items) assertNull(it.open)
    }

    /** 只有一部分读得到时，那几项仍然是 null。 */
    @Test
    fun partialReadableKeepsNull() {
        val items = CarStatus.items(
            doors = listOf(true, false, true, true),
            windows = null,
            hood = null,
            trunk = true,
        )
        assertEquals(false, items[0].open)
        assertEquals(true, items[1].open)
        assertNull(items[5].open)
        assertNull(items[9].open)
    }

    /** 状态文案：`开` / `关` / `--`。 */
    @Test
    fun stateText() {
        assertEquals("开", CarStatus.stateText(true))
        assertEquals("关", CarStatus.stateText(false))
        assertEquals("--", CarStatus.stateText(null))
    }

    // ---------------------------------------------------------------- 几何

    /** 车模面板矩形数量必须和条目数量一致（一一对应）。 */
    @Test
    fun geometryRectCountMatchesItems() {
        val geo = CarStatusGeometryFactory.of(CarTopGeometryFactory.of(CarModels.MODEL_03))
        assertEquals(10, geo.rects().size)
        assertEquals(10, CarStatus.items(null, null, null, null).size)
    }

    /** 面板必须落在设计空间（120 × 240）内。 */
    @Test
    fun panelsInsideDesignSpace() {
        for (model in CarModels.MODEL_IDS) {
            val geo = CarStatusGeometryFactory.of(CarTopGeometryFactory.of(model))
            for (r in geo.rects()) {
                assertTrue("$model 面板越界 left=${r.left}", r.left >= 0f)
                assertTrue("$model 面板越界 right=${r.right}", r.right <= CarModels.DESIGN_W)
                assertTrue("$model 面板越界 top=${r.top}", r.top >= 0f)
                assertTrue("$model 面板越界 bottom=${r.bottom}", r.bottom <= CarModels.DESIGN_H)
                assertTrue("$model 面板宽高非法 ${r.width}x${r.height}", r.width > 0f && r.height > 0f)
            }
        }
    }

    /** 左右面板必须**镜像对称**（车模是左右对称的）。 */
    @Test
    fun panelsAreMirrored() {
        val geo = CarStatusGeometryFactory.of(CarTopGeometryFactory.of(CarModels.MODEL_03))
        fun mirror(r: GeoRect) = CarModels.DESIGN_W - r.right
        assertEquals(mirror(geo.frontLeftDoor), geo.frontRightDoor.left, 0.01f)
        assertEquals(mirror(geo.rearLeftDoor), geo.rearRightDoor.left, 0.01f)
        assertEquals(mirror(geo.frontLeftWindow), geo.frontRightWindow.left, 0.01f)
        assertEquals(mirror(geo.rearLeftWindow), geo.rearRightWindow.left, 0.01f)
    }

    /**
     * 侧窗必须夹在「门皮 ↔ 车顶玻璃」之间，而且**不与门皮重叠**——
     * 重叠的话「门开着」的高亮会把侧窗一起盖住，分不清是门还是窗开了。
     */
    @Test
    fun windowsSitBetweenDoorsAndRoof() {
        val geo = CarTopGeometryFactory.of(CarModels.MODEL_03)
        val status = CarStatusGeometryFactory.of(geo)
        // 左侧：门皮在外（更小 x），侧窗在内（更大 x），两者不重叠
        assertTrue(status.frontLeftDoor.right <= status.frontLeftWindow.left)
        assertTrue(status.rearLeftDoor.right <= status.rearLeftWindow.left)
        // 右侧：侧窗在内（更小 x），门皮在外（更大 x）
        assertTrue(status.frontRightWindow.right <= status.frontRightDoor.left)
        assertTrue(status.rearRightWindow.right <= status.rearRightDoor.left)
        // 侧窗紧贴车顶玻璃外侧，不压到玻璃上
        assertTrue(status.frontLeftWindow.right <= geo.roof.left)
        assertTrue(status.frontRightWindow.left >= geo.roof.right)
    }

    /** 后备箱在车尾、引擎盖在车头。 */
    @Test
    fun trunkIsAtTailAndHoodAtNose() {
        val geo = CarTopGeometryFactory.of(CarModels.MODEL_03)
        val status = CarStatusGeometryFactory.of(geo)
        assertTrue(status.trunk.top > geo.roof.bottom)
        assertTrue(status.hood.bottom < geo.windshield.topLeft.y)
        assertNotNull(status.trunk)
    }
}
