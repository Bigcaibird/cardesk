package com.cardesk.launcher.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 导航引导卡的文案/计算规则单测。
 */
class NavGuideTextTest {

    // ------------------------------------------------------------ directionText

    @Test
    fun `转向编码 2 4 6 65 是左转`() {
        for (icon in listOf(2, 4, 6, 65)) {
            assertEquals("向左 ", NavGuideText.directionText(icon))
        }
    }

    @Test
    fun `转向编码 3 5 7 66 是右转`() {
        for (icon in listOf(3, 5, 7, 66)) {
            assertEquals("向右 ", NavGuideText.directionText(icon))
        }
    }

    @Test
    fun `转向编码 8 是掉头`() {
        assertEquals("掉头 ", NavGuideText.directionText(8))
    }

    @Test
    fun `其余编码都是直行`() {
        for (icon in listOf(0, 1, 9, 64, 67, 100, -1)) {
            assertEquals("直行 ", NavGuideText.directionText(icon))
        }
    }

    // ------------------------------------------------------------ 距离文案

    @Test
    fun `分段距离小于 1000 米取整`() {
        assertEquals("0", NavGuideText.formatSegmentDistance(0))
        assertEquals("423", NavGuideText.formatSegmentDistance(423))
        assertEquals("999", NavGuideText.formatSegmentDistance(999))
    }

    @Test
    fun `分段距离负数按 0 处理`() {
        assertEquals("0", NavGuideText.formatSegmentDistance(-5))
    }

    @Test
    fun `分段距离大于等于 1000 换成一位小数公里`() {
        assertEquals("1.0", NavGuideText.formatSegmentDistance(1000))
        assertEquals("1.4", NavGuideText.formatSegmentDistance(1400))
        assertEquals("12.3", NavGuideText.formatSegmentDistance(12345))
    }

    @Test
    fun `分段距离单位在 1000 米切换`() {
        assertEquals("米", NavGuideText.segmentUnit(999))
        assertEquals("公里", NavGuideText.segmentUnit(1000))
    }

    @Test
    fun `路线剩余里程固定是公里`() {
        assertEquals("0.0", NavGuideText.formatRouteDistance(0))
        assertEquals("0.5", NavGuideText.formatRouteDistance(500))
        assertEquals("12.4", NavGuideText.formatRouteDistance(12400))
    }

    // ------------------------------------------------------------ 剩余时长

    @Test
    fun `不足一小时只显示分钟`() {
        assertEquals("18 分", NavGuideText.formatRemainDuration(1080))
        assertEquals("00 分", NavGuideText.formatRemainDuration(0))
        assertEquals("01 分", NavGuideText.formatRemainDuration(30))
    }

    @Test
    fun `超过一小时补零显示时分`() {
        assertEquals("01 时 30 分", NavGuideText.formatRemainDuration(5400))
        assertEquals("02 时 05 分", NavGuideText.formatRemainDuration(7500))
    }

    @Test
    fun `整小时不带分钟`() {
        // 小时位补零，分钟位为 0 时整段不输出
        assertEquals("01 时", NavGuideText.formatRemainDuration(3600))
    }

    @Test
    fun `秒数四舍五入到分钟`() {
        // 89 秒 → 1.48 分 → 1 分
        assertEquals("01 分", NavGuideText.formatRemainDuration(89))
        // 91 秒 → 1.52 分 → 2 分
        assertEquals("02 分", NavGuideText.formatRemainDuration(91))
    }

    @Test
    fun `负的剩余时间按 0 处理`() {
        assertEquals("00 分", NavGuideText.formatRemainDuration(-100))
    }

    // ------------------------------------------------------------ ETA

    @Test
    fun `ETA 时间是 HH mm 格式`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.OCTOBER, 24, 20, 52, 0)
        cal.set(Calendar.MILLISECOND, 0)
        assertEquals("20:52", NavGuideText.formatEtaTime(cal.timeInMillis))
    }

    @Test
    fun `ETA 完整文案带到达后缀`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.OCTOBER, 24, 8, 5, 0)
        cal.set(Calendar.MILLISECOND, 0)
        assertEquals("08:05 到达", NavGuideText.formatGuideEta(cal.timeInMillis))
        assertEquals(" 到达", NavGuideText.ETA_SUFFIX)
    }

    // ------------------------------------------------------------ 道路文案

    @Test
    fun `道路文案优先用下一条路`() {
        assertEquals(
            "向左 进入 金峰北路",
            NavGuideText.roadText(2, "当前路", "金峰北路"),
        )
    }

    @Test
    fun `下一条路为空时用当前路`() {
        assertEquals(
            "直行 进入 当前路",
            NavGuideText.roadText(1, "当前路", ""),
        )
        assertEquals(
            "直行 进入 当前路",
            NavGuideText.roadText(1, "当前路", null),
        )
    }

    @Test
    fun `两条路都空时只有方向词`() {
        assertEquals("向右 ", NavGuideText.roadText(3, "", null))
    }

    // ------------------------------------------------------------ 有效性

    @Test
    fun `引导数据有效性判定`() {
        assertTrue(NavGuideText.isValidGuide(2, 100, "当前路", null))
        assertTrue(NavGuideText.isValidGuide(2, 0, null, "下一条路"))
        assertFalse(NavGuideText.isValidGuide(0, 100, "当前路", null))
        assertFalse(NavGuideText.isValidGuide(-1, 100, "当前路", null))
        assertFalse(NavGuideText.isValidGuide(2, -1, "当前路", null))
        assertFalse(NavGuideText.isValidGuide(2, 100, "", null))
        assertFalse(NavGuideText.isValidGuide(2, 100, null, ""))
    }

    // ------------------------------------------------------------ 车道线解析

    @Test
    fun `车道线 JSON 解析取前端图标`() {
        val raw = """
            {
              "drive_way_enabled": true,
              "drive_way_size": 3,
              "drive_way_info": [
                {"drive_way_number": 2, "drive_way_lane_Front_icon": 19, "drive_way_lane_Back_icon": 4},
                {"drive_way_number": 0, "drive_way_lane_Front_icon": 0,  "drive_way_lane_Back_icon": 1},
                {"drive_way_number": 1, "drive_way_lane_Front_icon": 0,  "drive_way_lane_Back_icon": 2}
              ]
            }
        """.trimIndent()
        val tiles = DriveWay.parse(raw)
        assertNotNull(tiles)
        requireNotNull(tiles)
        assertEquals(3, tiles.size)
        // 按 drive_way_number 升序
        assertEquals(LaneTile(1, false), tiles[0])
        assertEquals(LaneTile(2, false), tiles[1])
        assertEquals(LaneTile(19, true), tiles[2])
    }

    @Test
    fun `车道线 JSON 解析在后端编码不小于 15 时视为高亮`() {
        val raw = """{"drive_way_info":[{"drive_way_lane_Back_icon":16}]}"""
        val tiles = DriveWay.parse(raw)
        requireNotNull(tiles)
        assertEquals(1, tiles.size)
        assertTrue(tiles[0].on)
    }

    @Test
    fun `车道线 JSON 关闭时返回 null`() {
        val raw = """{"drive_way_enabled":false,"drive_way_info":[{"drive_way_lane_Front_icon":1}]}"""
        assertNull(DriveWay.parse(raw))
    }

    @Test
    fun `车道线 JSON 信息为空时返回 null`() {
        assertNull(DriveWay.parse("""{"drive_way_info":[]}"""))
        assertNull(DriveWay.parse("""{"other":1}"""))
    }

    @Test
    fun `车道线 JSON 的 size 越界时用数组长度`() {
        val raw = """{"drive_way_size":99,"drive_way_info":[{"drive_way_lane_Front_icon":1}]}"""
        val tiles = DriveWay.parse(raw)
        requireNotNull(tiles)
        assertEquals(1, tiles.size)
    }

    @Test
    fun `车道线 legacy 解析用高亮段覆盖`() {
        val tiles = DriveWay.parse("1,2,3|0,17,0")
        requireNotNull(tiles)
        assertEquals(3, tiles.size)
        assertEquals(LaneTile(1, false), tiles[0])
        assertEquals(LaneTile(17, true), tiles[1])
        assertEquals(LaneTile(3, false), tiles[2])
    }

    @Test
    fun `车道线 legacy 解析没有高亮段时全部用底图`() {
        val tiles = DriveWay.parse("1,2,3")
        requireNotNull(tiles)
        assertEquals(listOf(LaneTile(1, false), LaneTile(2, false), LaneTile(3, false)), tiles)
    }

    @Test
    fun `车道线 legacy 解析忽略分号与空白`() {
        val tiles = DriveWay.parse("1, 2;3")
        requireNotNull(tiles)
        assertEquals(3, tiles.size)
        assertEquals(3, tiles[2].icon)
    }

    @Test
    fun `车道线解析空输入返回 null`() {
        assertNull(DriveWay.parse(null))
        assertNull(DriveWay.parse(""))
        assertNull(DriveWay.parse("   "))
    }

    @Test
    fun `宽容取整兼容字符串数值`() {
        val map = mapOf<String, Any?>("a" to " 12 ", "b" to "abc", "c" to 7, "d" to 3.9)
        assertEquals(12, DriveWay.optIntFlex(map, "a", 0))
        assertEquals(0, DriveWay.optIntFlex(map, "b", 0))
        assertEquals(7, DriveWay.optIntFlex(map, "c", 0))
        assertEquals(3, DriveWay.optIntFlex(map, "d", 0))
        assertEquals(9, DriveWay.optIntFlex(map, "missing", 9))
    }

    // ------------------------------------------------------------ 车道图形映射

    @Test
    fun `全部 84 个编码都有已知图形`() {
        val missing = (0..LaneShapes.MAX_CODE).filter { !LaneShapes.isKnown(it) }
        assertTrue("未覆盖的编码：$missing", missing.isEmpty())
        assertEquals(LaneShapes.MAX_CODE + 1, LaneShapes.knownCodes.size)
    }

    @Test
    fun `未知编码回退成直行`() {
        assertEquals(LaneShape.STRAIGHT, LaneShapes.of(999))
        assertEquals(LaneShape.STRAIGHT, LaneShapes.of(-1))
    }

    @Test
    fun `前后端同形编码映射到同一种图形`() {
        // 0/15 都是直行，1/16 都是左转，3/18 都是右转，6/20 都是掉头
        assertEquals(LaneShapes.of(0), LaneShapes.of(15))
        assertEquals(LaneShapes.of(1), LaneShapes.of(16))
        assertEquals(LaneShapes.of(3), LaneShapes.of(18))
        assertEquals(LaneShapes.of(6), LaneShapes.of(20))
    }

    // ------------------------------------------------------------ 渲染模型

    @Test
    fun `渲染模型把原始数据算成文案`() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(2026, Calendar.OCTOBER, 24, 8, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val model = NavGuideModel.build(
            icon = 2,
            segmentMeters = 1500,
            currentRoad = "当前路",
            nextRoad = "金峰北路",
            remainSeconds = 1080,
            remainMeters = 12400,
            lanesRaw = "1,2|0,17",
            now = cal.timeInMillis,
        )
        assertEquals("1.5", model.segmentText)
        assertEquals("公里", model.segmentUnit)
        assertEquals("向左 进入 金峰北路", model.road)
        assertEquals("12.4", model.remainKm)
        assertEquals("18 分", model.remainTime)
        // ETA = now + 1080 秒 = 08:18
        assertEquals("08:18", model.etaTime)
        assertEquals(NavGuideText.GuideIcon.LEFT, model.icon)
        assertEquals(2, model.lanes.size)
        assertTrue(model.lanes[1].on)
    }

    @Test
    fun `渲染模型没有车道线时车道线列表为空`() {
        val model = NavGuideModel.build(
            icon = 8,
            segmentMeters = 0,
            currentRoad = "路",
            nextRoad = "",
            remainSeconds = 0,
            remainMeters = 0,
            lanesRaw = null,
        )
        assertTrue(model.lanes.isEmpty())
        assertEquals(NavGuideText.GuideIcon.UTURN, model.icon)
    }
}
