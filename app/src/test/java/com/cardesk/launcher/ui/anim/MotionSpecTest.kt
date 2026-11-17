package com.cardesk.launcher.ui.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局动效的**数值规格**单测（纯 Kotlin，不碰 Android）。
 *
 * 这一层存在的意义就在这里：动效的数值被改坏时，
 * 构建期就能发现，不用上车机才知道「手感不对了」。
 */
class MotionSpecTest {

    // ---------------------------------------------------------------
    // 按压反馈
    // ---------------------------------------------------------------

    /** 按下 60ms、松手 90ms —— 延续 App 原有的按压手感。 */
    @Test
    fun pressDurations_matchReference() {
        assertEquals(60L, MotionSpec.PRESS_DOWN_DURATION)
        assertEquals(90L, MotionSpec.PRESS_UP_DURATION)
    }

    /** 按下缩到 0.96，松手回到 1。 */
    @Test
    fun pressScale_matchesReference() {
        assertEquals(0.96f, MotionSpec.PRESS_SCALE, 1e-4f)
    }

    /** 回弹比按下慢：按下去要快，弹回来才有「弹」的余韵。 */
    @Test
    fun pressUpIsSlowerThanPressDown() {
        assertTrue(MotionSpec.PRESS_UP_DURATION > MotionSpec.PRESS_DOWN_DURATION)
    }

    /** 图标按钮缩得比普通按钮更狠，否则小按钮看不出按压。 */
    @Test
    fun iconPressScaleIsStrongerThanDefault() {
        assertTrue(MotionSpec.PRESS_SCALE_ICON < MotionSpec.PRESS_SCALE)
    }

    /** 整行可点只轻微下沉，不能晃。 */
    @Test
    fun rowPressScaleIsGentlerThanDefault() {
        assertTrue(MotionSpec.PRESS_SCALE_ROW > MotionSpec.PRESS_SCALE)
        assertTrue(MotionSpec.PRESS_SCALE_ROW < 1f)
    }

    /** 所有按压比例都必须落在 (0, 1]。 */
    @Test
    fun pressScalesAreWithinRange() {
        for (s in listOf(MotionSpec.PRESS_SCALE, MotionSpec.PRESS_SCALE_ICON, MotionSpec.PRESS_SCALE_ROW)) {
            assertTrue("scale=$s", s > 0f && s <= 1f)
        }
    }

    /** 传 null 用默认比例；传了就用给的。 */
    @Test
    fun pressScale_fallsBackToDefault() {
        assertEquals(MotionSpec.PRESS_SCALE, MotionSpec.pressScale(null), 1e-4f)
        assertEquals(0.5f, MotionSpec.pressScale(0.5f), 1e-4f)
    }

    // ---------------------------------------------------------------
    // 状态切换
    // ---------------------------------------------------------------

    /** 状态切换的形变是「鼓一下」—— 大于 1。 */
    @Test
    fun stateBumpIsAboveOne() {
        assertTrue(MotionSpec.STATE_BUMP > 1f)
    }

    /** 鼓动的幅度不能太大，否则控件像在抽搐。 */
    @Test
    fun stateBumpIsRestrained() {
        assertTrue(MotionSpec.STATE_BUMP <= 1.1f)
    }

    /** 开关滑块 200ms。 */
    @Test
    fun knobDuration_matchesReference() {
        assertEquals(200L, MotionSpec.KNOB_DURATION)
    }

    // ---------------------------------------------------------------
    // 数值变化
    // ---------------------------------------------------------------

    /** 数值变化的动画时长。 */
    @Test
    fun valueDuration_matchesReference() {
        assertEquals(200L, MotionSpec.VALUE_DURATION)
    }

    /** 数值变化的位移幅度是个小数目。 */
    @Test
    fun valueNudgeIsSmall() {
        assertTrue(MotionSpec.VALUE_NUDGE_PX > 0f)
        assertTrue(MotionSpec.VALUE_NUDGE_PX <= 16f)
    }

    // ---------------------------------------------------------------
    // 列表入场
    // ---------------------------------------------------------------

    /** 第 0 项不延迟。 */
    @Test
    fun stagger_firstItemHasNoDelay() {
        assertEquals(0L, MotionSpec.staggerDelayMs(0))
    }

    /** 错峰按步长递增。 */
    @Test
    fun stagger_increasesByStep() {
        assertEquals(MotionSpec.LIST_STAGGER_STEP, MotionSpec.staggerDelayMs(1))
        assertEquals(MotionSpec.LIST_STAGGER_STEP * 3, MotionSpec.staggerDelayMs(3))
    }

    /** 超过上限之后不再递增 —— 否则列表尾巴会等太久。 */
    @Test
    fun stagger_isCappedAtMaxItems() {
        val capped = MotionSpec.staggerDelayMs(MotionSpec.LIST_STAGGER_MAX_ITEMS)
        val beyond = MotionSpec.staggerDelayMs(MotionSpec.LIST_STAGGER_MAX_ITEMS + 50)
        assertEquals(capped, beyond)
        assertTrue(capped < 400L)
    }

    /** 负数序号按 0 算。 */
    @Test
    fun stagger_negativeIndexIsTreatedAsZero() {
        assertEquals(0L, MotionSpec.staggerDelayMs(-5))
    }

    /** 整段错峰的最长等待不能超过半秒。 */
    @Test
    fun stagger_totalWaitStaysShort() {
        val total = MotionSpec.staggerDelayMs(MotionSpec.LIST_STAGGER_MAX_ITEMS)
        assertTrue("total=$total", total <= 500L)
    }

    // ---------------------------------------------------------------
    // 浮层 / 对话框
    // ---------------------------------------------------------------

    /** 对话框进场 200ms、退场 240ms —— 和浮层（OverlaySpec）一个口径。 */
    @Test
    fun sheetDurations_matchReference() {
        assertEquals(200L, MotionSpec.SHEET_ENTER_DURATION)
        assertEquals(240L, MotionSpec.SHEET_EXIT_DURATION)
    }

    /** 退场比进场慢：关要给一个明确的收尾。 */
    @Test
    fun sheetExitIsSlowerThanEnter() {
        assertTrue(MotionSpec.SHEET_EXIT_DURATION > MotionSpec.SHEET_ENTER_DURATION)
    }

    /** 对话框的滑入距离比浮层短。 */
    @Test
    fun sheetSlideIsShorterThanOverlaySlide() {
        assertTrue(MotionSpec.SHEET_SLIDE_PX < com.cardesk.launcher.ui.OverlaySpec.SLIDE_PX)
    }

    /** 进场时的起始缩放略小于 1（有纵深）。 */
    @Test
    fun sheetEnterScaleHasDepth() {
        assertTrue(MotionSpec.SHEET_ENTER_SCALE < 1f)
        assertTrue(MotionSpec.SHEET_ENTER_SCALE >= 0.9f)
    }

    // ---------------------------------------------------------------
    // 插值器
    // ---------------------------------------------------------------

    /** 标准减速：PathInterpolator(0.4f, 0f, 0.2f, 1f)。 */
    @Test
    fun standardInterpolatorMatchesReference() {
        assertEquals(0.4f, MotionSpec.STANDARD_X1, 1e-4f)
        assertEquals(0f, MotionSpec.STANDARD_Y1, 1e-4f)
        assertEquals(0.2f, MotionSpec.STANDARD_X2, 1e-4f)
        assertEquals(1f, MotionSpec.STANDARD_Y2, 1e-4f)
    }

    /** 强减速与浮层动画同一条：PathInterpolator(0.2f, 0.8f, 0.2f, 1f)。 */
    @Test
    fun emphasisInterpolatorMatchesOverlaySpec() {
        assertEquals(com.cardesk.launcher.ui.OverlaySpec.INTERPOLATOR_X1, MotionSpec.EMPHASIS_X1, 1e-4f)
        assertEquals(com.cardesk.launcher.ui.OverlaySpec.INTERPOLATOR_Y1, MotionSpec.EMPHASIS_Y1, 1e-4f)
        assertEquals(com.cardesk.launcher.ui.OverlaySpec.INTERPOLATOR_X2, MotionSpec.EMPHASIS_X2, 1e-4f)
        assertEquals(com.cardesk.launcher.ui.OverlaySpec.INTERPOLATOR_Y2, MotionSpec.EMPHASIS_Y2, 1e-4f)
    }

    /** 回弹的过冲不能太大，否则像弹簧玩具。 */
    @Test
    fun reboundTensionIsRestrained() {
        assertTrue(MotionSpec.REBOUND_TENSION > 1f)
        assertTrue(MotionSpec.REBOUND_TENSION <= 1.5f)
    }

    // ---------------------------------------------------------------
    // 颜色插值
    // ---------------------------------------------------------------

    /** t=0 全是起点，t=1 全是终点。 */
    @Test
    fun lerpColor_endpointsAreExact() {
        val from = 0xFF000000.toInt()
        val to = 0xFFFFFFFF.toInt()
        assertEquals(from, MotionSpec.lerpColor(from, to, 0f))
        assertEquals(to, MotionSpec.lerpColor(from, to, 1f))
    }

    /** 中点落在中间。 */
    @Test
    fun lerpColor_midpointIsHalfway() {
        val from = 0xFF000000.toInt()
        val to = 0xFFFFFFFF.toInt()
        val mid = MotionSpec.lerpColor(from, to, 0.5f)
        // 透明度两端都是 0xFF —— 它不该变。
        assertEquals(0xFF, mid ushr 24 and 0xFF)
        // 三个颜色通道从 0 走到 255，中点各是 128（四舍五入，不是截断的 127）。
        assertEquals(0x80, mid ushr 16 and 0xFF)
        assertEquals(0x80, mid ushr 8 and 0xFF)
        assertEquals(0x80, mid and 0xFF)
    }

    /** t 超出 [0,1] 要夹住，不能溢出 0..255。 */
    @Test
    fun lerpColor_clampsOutOfRangeT() {
        val from = 0xFF000000.toInt()
        val to = 0xFFFFFFFF.toInt()
        assertEquals(from, MotionSpec.lerpColor(from, to, -1f))
        assertEquals(to, MotionSpec.lerpColor(from, to, 2f))
    }

    /** 每个通道都要夹在 0..255。 */
    @Test
    fun lerpColor_keepsChannelsInRange() {
        val from = 0xFF102030.toInt()
        val to = 0xFFA0B0C0.toInt()
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val c = MotionSpec.lerpColor(from, to, t)
            for (shift in listOf(24, 16, 8, 0)) {
                val ch = c ushr shift and 0xFF
                assertTrue("channel=$ch", ch in 0..255)
            }
        }
    }
}
