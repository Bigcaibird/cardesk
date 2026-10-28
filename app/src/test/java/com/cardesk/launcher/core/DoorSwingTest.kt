package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车门「张开」姿态的单测（纯几何，不碰 Android）。
 *
 * 车门示意是一张俯视车模，
 * 每扇门绕**铰链**旋转 ±45°。
 */
class DoorSwingTest {

    /** 左前门：外沿在左（x 小），内沿在右。 */
    private val fl = GeoRect(20f, 40f, 30f, 70f)

    /** 右前门：外沿在右（x 大），内沿在左。 */
    private val fr = GeoRect(90f, 40f, 100f, 70f)

    /** 左后门。 */
    private val rl = GeoRect(20f, 72f, 30f, 110f)

    /** 右后门。 */
    private val rr = GeoRect(90f, 72f, 100f, 110f)

    @Test
    fun `张开角是 45 度`() {
        assertEquals(45f, DoorSwing.OPEN_ANGLE_DEG, 0.001f)
    }

    @Test
    fun `前门铰在前缘中点 后门铰在后缘中点`() {
        val flPose = DoorSwing.pose(DoorSwing.FL, fl)
        assertEquals(25f, flPose.hingeX, 0.001f)
        assertEquals(40f, flPose.hingeY, 0.001f)

        val rlPose = DoorSwing.pose(DoorSwing.RL, rl)
        assertEquals(25f, rlPose.hingeX, 0.001f)
        assertEquals(110f, rlPose.hingeY, 0.001f)
    }

    @Test
    fun `左侧门向外翻 右侧门向外翻 方向相反`() {
        // 左前 +45（顺时针，往左外侧倒）、右前 −45
        assertEquals(45f, DoorSwing.pose(DoorSwing.FL, fl).angleDeg, 0.001f)
        assertEquals(-45f, DoorSwing.pose(DoorSwing.FR, fr).angleDeg, 0.001f)
        // 后门铰在后端，所以旋转方向与前门相反（真实车门的 C 柱铰链）
        assertEquals(-45f, DoorSwing.pose(DoorSwing.RL, rl).angleDeg, 0.001f)
        assertEquals(45f, DoorSwing.pose(DoorSwing.RR, rr).angleDeg, 0.001f)
    }

    @Test
    fun `左前门张开后自由端向车外侧移动`() {
        val corners = DoorSwing.swingCorners(DoorSwing.FL, fl)
        // 门板原来的自由端（后外角）在 (20, 70)
        // 旋转后它必须更靠左（x 更小）—— 这就是「门摆出去了」
        val freeCornerX = corners[6]
        assertTrue("自由端应该向车外（左）移动，实际 $freeCornerX", freeCornerX < 20f)
    }

    @Test
    fun `右前门张开后自由端向车外侧移动`() {
        val corners = DoorSwing.swingCorners(DoorSwing.FR, fr)
        // 自由端（后外角）原来在 (100, 70)
        val freeCornerX = corners[6]
        assertTrue("自由端应该向车外（右）移动，实际 $freeCornerX", freeCornerX > 100f)
    }

    @Test
    fun `旋转保持各角到铰链的距离不变`() {
        for ((idx, rect) in listOf(
            DoorSwing.FL to fl,
            DoorSwing.FR to fr,
            DoorSwing.RL to rl,
            DoorSwing.RR to rr,
        )) {
            val pose = DoorSwing.pose(idx, rect)
            val corners = DoorSwing.swingCorners(idx, rect)
            val before = listOf(
                rect.left to rect.top,
                rect.right to rect.top,
                rect.right to rect.bottom,
                rect.left to rect.bottom,
            )
            for (i in 0 until 4) {
                val d0 = hypot(before[i].first - pose.hingeX, before[i].second - pose.hingeY)
                val d1 = hypot(corners[i * 2] - pose.hingeX, corners[i * 2 + 1] - pose.hingeY)
                assertEquals("门 $idx 第 $i 个角到铰链的距离不该变", d0, d1, 0.01f)
            }
        }
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)

    @Test
    fun `张开的门不会压到车况卡旁边的文字列`() {
        // 车况卡里车模宽 100px（设计空间 120），文字块与车模之间留 CAR_STATUS_GAP = 26px。
        // 换算到设计空间：左列文字的右边缘在 x = -31.2，右列文字的左边缘在 x = 151.2。
        val geo = CarTopGeometryFactory.of(CarModels.MODEL_03)
        val rects = CarStatusGeometryFactory.of(geo).rects()
        val gapDesign = 26f / (100f / 120f)
        val leftTextRight = -gapDesign
        val rightTextLeft = CarModels.DESIGN_W + gapDesign
        for (idx in 0..3) {
            val rect = rects[idx]
            val corners = DoorSwing.swingCorners(idx, rect)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            for (i in 0 until 4) {
                if (corners[i * 2] < minX) minX = corners[i * 2]
                if (corners[i * 2] > maxX) maxX = corners[i * 2]
            }
            assertTrue("门 $idx 张开后伸到 $minX，会压到左列文字（$leftTextRight）", minX > leftTextRight)
            assertTrue("门 $idx 张开后伸到 $maxX，会压到右列文字（$rightTextLeft）", maxX < rightTextLeft)
        }
    }
}
