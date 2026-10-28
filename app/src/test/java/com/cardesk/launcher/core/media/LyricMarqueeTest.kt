package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词**跑马灯**的纯逻辑单测。
 *
 * 场景：第三十九轮（4385）导航页歌词放大后，长句比容器宽，
 * 不能再用 `ellipsize=END` 截断，改成整句水平滚动。
 */
class LyricMarqueeTest {

    private val eps = 1e-3f

    // ---------------------------------------------------------------- needsScroll

    /** 文字比容器宽 → 需要滚动。 */
    @Test
    fun needsScroll_whenTextWider() {
        assertTrue(LyricMarquee.needsScroll(300f, 200f))
    }

    /** 文字没超宽 → 不滚动。 */
    @Test
    fun needsScroll_whenTextFits() {
        assertFalse(LyricMarquee.needsScroll(150f, 200f))
    }

    /** 刚好等宽 → 不滚动（差一个像素容差，避免亚像素抖动）。 */
    @Test
    fun needsScroll_toleratesSubPixel() {
        assertFalse(LyricMarquee.needsScroll(200.4f, 200f))
        assertTrue(LyricMarquee.needsScroll(202f, 200f))
    }

    /** 容器宽度还没量出来（0）→ 不滚动。 */
    @Test
    fun needsScroll_whenViewNotMeasured() {
        assertFalse(LyricMarquee.needsScroll(300f, 0f))
    }

    // ---------------------------------------------------------------- cycleMs

    /** 不需要滚动时周期为 0。 */
    @Test
    fun cycleMs_zeroWhenNotOverflowing() {
        assertEquals(0L, LyricMarquee.cycleMs(100f, 200f))
    }

    /** 周期 = 两趟行程 + 两端停留。 */
    @Test
    fun cycleMs_isTwiceTravelPlusTwoPauses() {
        val overflow = 100f
        val travelMs = (overflow / LyricMarquee.SPEED_PX_PER_SEC * 1000f).toLong()
        val expected = travelMs * 2 + LyricMarquee.EDGE_PAUSE_MS * 2
        assertEquals(expected, LyricMarquee.cycleMs(300f, 200f))
    }

    // ---------------------------------------------------------------- offset

    /** 起始位置：文字左边缘贴容器左边（偏移 0）。 */
    @Test
    fun offset_startsAtLeftEdge() {
        assertEquals(0f, LyricMarquee.offset(300f, 200f, 0L), eps)
        assertEquals(0f, LyricMarquee.offset(300f, 200f, 100L), eps)
    }

    /** 左端停留期内不动。 */
    @Test
    fun offset_holdsAtLeftEdgeDuringPause() {
        assertEquals(0f, LyricMarquee.offset(300f, 200f, LyricMarquee.EDGE_PAUSE_MS - 1), eps)
    }

    /** 滚动开始后向左走，且**单调**。 */
    @Test
    fun offset_scrollsLeftMonotonically() {
        val t0 = LyricMarquee.EDGE_PAUSE_MS + 100L
        val t1 = LyricMarquee.EDGE_PAUSE_MS + 900L
        val a = LyricMarquee.offset(300f, 200f, t0)
        val b = LyricMarquee.offset(300f, 200f, t1)
        assertTrue("应当向左移动：a=$a b=$b", b < a)
    }

    /** 行程终点：文字右边缘贴容器右边（偏移 = -溢出量）。 */
    @Test
    fun offset_reachesRightEdge() {
        val travelMs = (100f / LyricMarquee.SPEED_PX_PER_SEC * 1000f).toLong()
        val t = LyricMarquee.EDGE_PAUSE_MS + travelMs
        assertEquals(-100f, LyricMarquee.offset(300f, 200f, t), 0.5f)
    }

    /** 右端也要停留一会儿（此时偏移保持不变）。 */
    @Test
    fun offset_holdsAtRightEdgeDuringPause() {
        val travelMs = (100f / LyricMarquee.SPEED_PX_PER_SEC * 1000f).toLong()
        val t = LyricMarquee.EDGE_PAUSE_MS + travelMs + LyricMarquee.EDGE_PAUSE_MS - 1
        assertEquals(-100f, LyricMarquee.offset(300f, 200f, t), 0.5f)
    }

    /** 偏移量始终落在 [-溢出量, 0] 区间内。 */
    @Test
    fun offset_staysInRange() {
        var t = 0L
        while (t < 20_000L) {
            val o = LyricMarquee.offset(300f, 200f, t)
            assertTrue("越界: $o @ $t", o <= eps && o >= -100f - eps)
            t += 97L
        }
    }

    /** 一个完整周期后回到起点（无缝循环）。 */
    @Test
    fun offset_isContinuousAcrossCycle() {
        val cycle = LyricMarquee.cycleMs(300f, 200f)
        assertEquals(0f, LyricMarquee.offset(300f, 200f, cycle), 1f)
    }

    /** 不超宽时永远不动。 */
    @Test
    fun offset_zeroWhenNotOverflowing() {
        assertEquals(0f, LyricMarquee.offset(100f, 200f, 5000L), eps)
    }

    // ---------------------------------------------------------------- LyricMarqueeState

    /** 不超宽时状态机返回 0。 */
    @Test
    fun state_zeroWhenNotOverflowing() {
        val s = LyricMarqueeState()
        assertEquals(0f, s.offset(1000L, 100f, 200f), eps)
    }

    /** 超宽时状态机开始出偏移。 */
    @Test
    fun state_startsScrolling() {
        val s = LyricMarqueeState()
        s.setText("很长的一句歌词", 1000L)
        val first = s.offset(1000L, 300f, 200f)
        assertEquals("刚换上时应当在起点", 0f, first, eps)
        val later = s.offset(1000L + LyricMarquee.EDGE_PAUSE_MS + 800L, 300f, 200f)
        assertTrue("应当开始滚动: $later", later < 0f)
    }

    /** 文本变了 → 滚动进度重置（回到起点）。 */
    @Test
    fun state_resetsOnTextChange() {
        val s = LyricMarqueeState()
        s.setText("第一句歌词", 1000L)
        s.offset(1000L, 300f, 200f)
        val advanced = s.offset(6000L, 300f, 200f)
        assertTrue(advanced < 0f)
        s.setText("第二句歌词", 6000L)
        assertEquals("换句后应回到起点", 0f, s.offset(6000L, 300f, 200f), eps)
    }

    /** 同样的文本重复设置不应重置。 */
    @Test
    fun state_ignoresSameText() {
        val s = LyricMarqueeState()
        s.setText("同一句", 1000L)
        s.offset(1000L, 300f, 200f)
        val advanced = s.offset(6000L, 300f, 200f)
        s.setText("同一句", 6000L)
        assertTrue("不应被重置", s.offset(6000L, 300f, 200f) < 0f)
        assertEquals(advanced, s.offset(6000L, 300f, 200f), eps)
    }
}
