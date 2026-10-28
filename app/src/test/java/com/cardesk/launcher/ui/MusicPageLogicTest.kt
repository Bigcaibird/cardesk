package com.cardesk.launcher.ui

import com.cardesk.launcher.ui.widget.LyricMarquee
import com.cardesk.launcher.ui.widget.LyricSettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音乐页的纯逻辑单测（不依赖 Android View）：
 * - 时间格式与拖动换算
 *   ；
 * - 跑马灯的滚动条件、距离与单轮时长
 * - 落位动画的插值曲线。
 */
class MusicPageLogicTest {

    @Test
    fun clock_isMinuteSecond() {
        assertEquals("0:00", MusicPageLogic.formatClock(0))
        assertEquals("0:09", MusicPageLogic.formatClock(9))
        assertEquals("1:05", MusicPageLogic.formatClock(65))
        assertEquals("12:34", MusicPageLogic.formatClock(754))
    }

    @Test
    fun clock_negative_isClampedToZero() {
        assertEquals("0:00", MusicPageLogic.formatClock(-5))
    }

    @Test
    fun seek_requiresDuration() {
        assertFalse(MusicPageLogic.isSeekable(0))
        assertFalse(MusicPageLogic.isSeekable(-1))
        assertTrue(MusicPageLogic.isSeekable(1))
    }

    @Test
    fun seekSec_mapsTouchToTime() {
        // 宽度 1000、时长 100s → 每 10px 一秒
        assertEquals(0, MusicPageLogic.seekSec(0f, 1000, 100))
        assertEquals(50, MusicPageLogic.seekSec(500f, 1000, 100))
        assertEquals(100, MusicPageLogic.seekSec(1000f, 1000, 100))
    }

    @Test
    fun seekSec_clampsOutOfBounds() {
        // 手指拖到进度条外面不能越界
        assertEquals(0, MusicPageLogic.seekSec(-50f, 1000, 100))
        assertEquals(100, MusicPageLogic.seekSec(5000f, 1000, 100))
    }

    @Test
    fun seekSec_withoutDurationOrWidth_isZero() {
        assertEquals(0, MusicPageLogic.seekSec(500f, 1000, 0))
        assertEquals(0, MusicPageLogic.seekSec(500f, 0, 100))
    }

    @Test
    fun marquee_scrollsOnlyWhenTextOverflows() {
        assertFalse(LyricMarquee.needsScroll(100f, 200))
        assertFalse(LyricMarquee.needsScroll(200f, 200))
        assertTrue(LyricMarquee.needsScroll(260f, 200))
    }

    @Test
    fun marquee_distanceCoversOverflow() {
        // 超出 60px 就滚 60px（滚完整句正好填满可用宽度）
        assertEquals(60f, LyricMarquee.distance(260f, 200), 0.001f)
    }

    @Test
    fun marquee_cycleTimeFollowsSpeed() {
        // 42dp/s，density=2 → 84px/s；420px → 5000ms
        assertEquals(5000L, LyricMarquee.cycleMs(420f, 2f))
        // 距离太短时保底 2200ms
        assertEquals(2200L, LyricMarquee.cycleMs(10f, 2f))
    }

    @Test
    fun marquee_pauseConstantsMatchReference() {
        assertEquals(1200L, LyricMarquee.PAUSE_START_MS)
        assertEquals(1400L, LyricMarquee.PAUSE_END_MS)
        assertEquals(42f, LyricMarquee.SPEED_DP_PER_SEC, 0.001f)
    }

    @Test
    fun settle_interpolatorIsCubicOutRemainder() {
        assertEquals(0f, LyricSettle.cubicOutRemainder(0f), 0.0001f)
        assertEquals(1f, LyricSettle.cubicOutRemainder(1f), 0.0001f)
        // 1 - (1 - 0.5)^3 = 0.875
        assertEquals(0.875f, LyricSettle.cubicOutRemainder(0.5f), 0.0001f)
        // 越界输入被夹到 [0,1]
        assertEquals(0f, LyricSettle.cubicOutRemainder(-1f), 0.0001f)
        assertEquals(1f, LyricSettle.cubicOutRemainder(2f), 0.0001f)
    }

    @Test
    fun settle_constantsMatchReference() {
        assertEquals(360L, LyricSettle.DURATION_MS)
        assertEquals(32f, LyricSettle.AMPLITUDE_DP, 0.001f)
    }
}
