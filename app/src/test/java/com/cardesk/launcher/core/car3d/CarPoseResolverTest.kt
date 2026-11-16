package com.cardesk.launcher.core.car3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarPoseResolver] 单测（纯逻辑，不依赖 Android）。
 *
 * 盯三件事：
 * 1. **姿态 → 状态 key** 的降级顺序（门 ≥2 → all、门 1 + 窗 → 卡片用单门 / 弹窗用 all...）；
 * 2. **动画选择**（多部件同时变只播一个、关回去是倒放、首次不播）；
 * 3. **未知口径**：读不到按关着画，但 `unknown` 要亮（图例里是 --）。
 */
class CarPoseResolverTest {

    private fun pose(
        lf: Boolean = false,
        rf: Boolean = false,
        lb: Boolean = false,
        rb: Boolean = false,
        win: Boolean = false,
        trunk: Boolean = false,
        unknown: Boolean = false,
    ): CarPose = CarPose(
        doors = booleanArrayOf(lf, rf, lb, rb),
        windows = booleanArrayOf(win, win, win, win),
        trunk = trunk,
        unknown = unknown,
    )

    // ---------------------------------------------------------------- 状态 key

    @Test
    fun `全关是 closed`() {
        assertEquals("closed", CarPoseResolver.stateKey(pose(), rich = false))
        assertEquals("closed", CarPoseResolver.stateKey(pose(), rich = true))
    }

    @Test
    fun `单扇门映射到该门的 key`() {
        assertEquals("lf", CarPoseResolver.stateKey(pose(lf = true), rich = false))
        assertEquals("rf", CarPoseResolver.stateKey(pose(rf = true), rich = false))
        assertEquals("lb", CarPoseResolver.stateKey(pose(lb = true), rich = false))
        assertEquals("rb", CarPoseResolver.stateKey(pose(rb = true), rich = false))
    }

    @Test
    fun `两扇门用双门状态_三扇以上才是 all`() {
        // 第178轮：门恰好 2 扇开 → 双门状态（资产按 lf_rf / lf_lb / ... 出图），
        // 这样弹窗里不会把「左前+右前开」画成四门全开。
        assertEquals("lf_lb", CarPoseResolver.stateKey(pose(lf = true, lb = true), rich = false))
        assertEquals("rf_rb", CarPoseResolver.stateKey(pose(rf = true, rb = true), rich = false))
        assertEquals("lf_rf", CarPoseResolver.stateKey(pose(lf = true, rf = true), rich = false))
        assertEquals("lf_rb", CarPoseResolver.stateKey(pose(lf = true, rb = true), rich = false))
        assertEquals(
            "all",
            CarPoseResolver.stateKey(pose(lf = true, rf = true, lb = true, rb = true), rich = false),
        )
    }

    @Test
    fun `只有窗开是 win`() {
        assertEquals("win", CarPoseResolver.stateKey(pose(win = true), rich = false))
    }

    @Test
    fun `只有尾门开是 closed_trunk`() {
        assertEquals("closed_trunk", CarPoseResolver.stateKey(pose(trunk = true), rich = false))
    }

    @Test
    fun `尾门与窗同时开：尾门优先`() {
        assertEquals("closed_trunk", CarPoseResolver.stateKey(pose(trunk = true, win = true), rich = false))
    }

    @Test
    fun `门与窗同时开：只按门算组合 窗不参与`() {
        val p = pose(lf = true, win = true)
        assertEquals("lf", CarPoseResolver.stateKey(p, rich = false))
        assertEquals("lf", CarPoseResolver.stateKey(p, rich = true))
    }

    @Test
    fun `任意门组合按固定顺序拼 key`() {
        assertEquals("lf", CarPoseResolver.stateKey(pose(lf = true), false))
        assertEquals("rf_lb", CarPoseResolver.stateKey(pose(rf = true, lb = true), false))
        assertEquals("lf_rf_lb", CarPoseResolver.stateKey(pose(lf = true, rf = true, lb = true), false))
        assertEquals("all", CarPoseResolver.stateKey(
            pose(lf = true, rf = true, lb = true, rb = true), false))
    }

    @Test
    fun `四扇门用 all 而不是四段拼接`() {
        assertEquals("all", CarPoseResolver.stateKey(
            pose(lf = true, rf = true, lb = true, rb = true), false))
    }

    @Test
    fun `尾门追加 _trunk 后缀`() {
        assertEquals("closed_trunk", CarPoseResolver.stateKey(pose(trunk = true), false))
        assertEquals("lf_trunk", CarPoseResolver.stateKey(pose(lf = true, trunk = true), false))
        assertEquals("lf_rf_trunk", CarPoseResolver.stateKey(
            pose(lf = true, rf = true, trunk = true), false))
        assertEquals("all_trunk", CarPoseResolver.stateKey(
            pose(lf = true, rf = true, lb = true, rb = true, trunk = true), false))
    }

    @Test
    fun `splitState 能拆出门组合与尾门标记`() {
        assertEquals(emptyList<String>() to false, CarPoseResolver.splitState("closed"))
        assertEquals(listOf("lf", "rf", "lb") to false, CarPoseResolver.splitState("lf_rf_lb"))
        assertEquals(listOf("lf", "rf", "lb") to true, CarPoseResolver.splitState("lf_rf_lb_trunk"))
        assertEquals(emptyList<String>() to true, CarPoseResolver.splitState("trunk"))
        assertEquals(listOf("lf") to true, CarPoseResolver.splitState("lf_trunk"))
    }

    // ---------------------------------------------------------------- 动画

    @Test
    fun `首次姿态不播动画`() {
        assertNull(CarPoseResolver.animOf(null, pose(lf = true)))
    }

    @Test
    fun `姿态没变不播动画`() {
        assertNull(CarPoseResolver.animOf(pose(lf = true), pose(lf = true)))
    }

    @Test
    fun `开门是正放 关门是倒放`() {
        val opening = CarPoseResolver.animOf(pose(), pose(lf = true))
        assertEquals(CarAnimKind.LF, opening?.kind)
        assertEquals(false, opening?.reverse)

        val closing = CarPoseResolver.animOf(pose(lf = true), pose())
        assertEquals(CarAnimKind.LF, closing?.kind)
        assertEquals(true, closing?.reverse)
    }

    @Test
    fun `四扇门各自有各自的动画`() {
        assertEquals(CarAnimKind.RF, CarPoseResolver.animOf(pose(), pose(rf = true))?.kind)
        assertEquals(CarAnimKind.LB, CarPoseResolver.animOf(pose(), pose(lb = true))?.kind)
        assertEquals(CarAnimKind.RB, CarPoseResolver.animOf(pose(), pose(rb = true))?.kind)
    }

    @Test
    fun `尾门与窗的动画`() {
        assertEquals(CarAnimKind.TRUNK, CarPoseResolver.animOf(pose(), pose(trunk = true))?.kind)
        assertEquals(CarAnimKind.WINDOW, CarPoseResolver.animOf(pose(), pose(win = true))?.kind)
        assertEquals(true, CarPoseResolver.animOf(pose(win = true), pose())?.reverse)
    }

    @Test
    fun `多部件同时变只播门`() {
        assertEquals(
            CarAnimKind.LF,
            CarPoseResolver.animOf(pose(), pose(lf = true, win = true, trunk = true))?.kind,
        )
        assertEquals(
            CarAnimKind.TRUNK,
            CarPoseResolver.animOf(pose(), pose(win = true, trunk = true))?.kind,
        )
    }

    // ---------------------------------------------------------------- 信号 → 姿态

    @Test
    fun `四门信号 true 表示关 要翻成 doors=false`() {
        val p = CarPose.of(listOf(true, true, true, true), BooleanArray(4), hoodClosed = true, trunkClosed = true)
        assertFalse(p.anyDoorOpen)
        assertFalse(p.anyWindowOpen)
        assertFalse(p.trunk)
        assertFalse(p.unknown)
    }

    @Test
    fun `门开与窗开都映射正确`() {
        val p = CarPose.of(
            listOf(false, true, true, true),
            booleanArrayOf(true, false, false, false),
            hoodClosed = true,
            trunkClosed = true,
        )
        assertTrue(p.doors[0])
        assertTrue(p.windows[0])
        assertFalse(p.windows[1])
        assertTrue(p.sameAs(p))
    }

    @Test
    fun `读不到按关着画 但 unknown 要亮`() {
        val p = CarPose.of(null, null, hoodClosed = null, trunkClosed = null)
        assertFalse(p.anyDoorOpen)
        assertTrue(p.unknown)
    }

    @Test
    fun `引擎盖不参与 unknown 与状态 key`() {
        val p = CarPose.of(
            listOf(true, true, true, true),
            BooleanArray(4),
            hoodClosed = false, // 引擎盖开（真机上永远读不到，这里只验不污染 key）
            trunkClosed = true,
        )
        assertTrue(p.hood)
        assertFalse(p.unknown)
        assertEquals("closed", CarPoseResolver.stateKey(p, rich = true))
    }

    @Test
    fun `尾门信号 false 表示开`() {
        assertTrue(CarPose.of(listOf(true, true, true, true), BooleanArray(4), true, false).trunk)
    }
}
