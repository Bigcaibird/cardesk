package com.cardesk.launcher.ui.widget

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.cardesk.launcher.core.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * 空调温度表盘（`TempDialView`）的 **± 键按住连发**。
 *
 * ## 覆盖的那个坑：按住不放再也不动了
 *
 * ± 键原来只在 `ACTION_DOWN` 步进**一次**（±0.5℃）就结束了 ——
 * 手指不抬起来，`ACTION_MOVE` 又因为没在拖动直接返回，
 * 所以**按住不放就再也不加温**。
 *
 * 用户想加 2℃ 得连点 4 次，车机上这是很别扭的手感
 * （车机自带的 HVAC 面板都是按住连发的）。
 *
 * ## 这轮怎么修的
 *
 * `Handler.postDelayed` 排一个**自续**的任务：到点步进一次、再排下一次；
 * 抬手 / 视图离开窗口时撤掉。自续的好处是**节奏不累积漂移** ——
 * 每一次都从「上一次真正跑完的时刻」起算。
 *
 * 起始门槛用**系统长按超时**（`ViewConfiguration.getLongPressTimeout()`，
 * 和 Dock 的长按一个标准），用户不用学新规矩，也不会因为手抖就连加好几度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TempDialViewRepeatTest {

    private val dialWidth = 340
    private val dialHeight = 424

    /** 系统长按超时（连发的起始门槛）。 */
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    /** 连发间隔。 */
    private val repeatInterval = TempDialView.REPEAT_INTERVAL_MS

    private fun makeDial(): TempDialView {
        val v = TempDialView(RuntimeEnvironment.getApplication())
        v.palette = Palettes.night
        v.measure(
            View.MeasureSpec.makeMeasureSpec(dialWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dialHeight, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, dialWidth, dialHeight)
        // 画一次：± 键的热区坐标是在 onDraw 里算出来的
        v.draw(Canvas(Bitmap.createBitmap(dialWidth, dialHeight, Bitmap.Config.ARGB_8888)))
        return v
    }

    private fun touch(v: TempDialView, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    /** 推进主线程时钟（Handler 的延时任务靠它才会跑）。 */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** 加号键的位置（onDraw 里算出来的）。 */
    private fun plusX(dial: TempDialView): Float =
        dial.width / 2f + (TempDialView.BUTTON_SIZE + TempDialView.BUTTON_GAP) / 2f

    /** 减号键的位置。 */
    private fun minusX(dial: TempDialView): Float =
        dial.width / 2f - (TempDialView.BUTTON_SIZE + TempDialView.BUTTON_GAP) / 2f

    /** ± 键所在的那一行。 */
    private val buttonY = TempDialView.BUTTON_TOP + TempDialView.BUTTON_SIZE / 2f

    // ------------------------------------------------------------------ 连发

    /**
     * 长按超时**之前**不连发 —— 只有按下那一次。
     *
     * 门槛和系统长按一致：手抖一下不会连加好几度。
     */
    @Test
    fun 长按超时之前不会连发() {
        val v = makeDial()
        var deltas = 0
        v.onDelta = { deltas++ }

        touch(v, MotionEvent.ACTION_DOWN, plusX(v), buttonY)
        advance(longPressTimeout - 50)

        assertEquals(
            "还没到长按门槛就连发了（门槛应为系统长按超时 $longPressTimeout ms）",
            1,
            deltas,
        )
    }

    /**
     * 按住不放 → **连发**。
     *
     * 1 秒内：初始 1 次 + 连发若干次。
     * 具体次数由「长按门槛 + 连发间隔」决定，这里按公式算，不写死数字。
     */
    @Test
    fun 按住不放会连发() {
        val v = makeDial()
        var deltas = 0
        v.onDelta = { deltas++ }

        touch(v, MotionEvent.ACTION_DOWN, plusX(v), buttonY)
        advance(1000)

        // 初始 1 次；连发从 longPressTimeout 开始，每 repeatInterval 一次
        val expected = 1 + ((1000 - longPressTimeout) / repeatInterval + 1).toInt()
        assertEquals("按住 1 秒应该连发到 $expected 次，实际 $deltas 次", expected, deltas)
        assertTrue("按住不放应该连发（实际只步进了 $deltas 次）", deltas > 1)
    }

    /**
     * 连发节奏是**每 150ms 一次**。
     *
     * 门槛刚过时先来一次，之后按固定间隔来。
     */
    @Test
    fun 连发节奏是固定间隔() {
        val v = makeDial()
        var deltas = 0
        v.onDelta = { deltas++ }

        touch(v, MotionEvent.ACTION_DOWN, plusX(v), buttonY)
        // 刚好过一个长按门槛 → 初始 1 次 + 连发第 1 次
        advance(longPressTimeout + 10)
        assertEquals("长按门槛刚过时应该步进 2 次", 2, deltas)

        // 再过一个连发间隔 → 第 2 次连发
        advance(repeatInterval + 10)
        assertEquals("再过一个连发间隔应该步进 3 次", 3, deltas)
    }

    /**
     * 松手之后**立刻停**，不再往下加。
     */
    @Test
    fun 松手后不再连发() {
        val v = makeDial()
        var deltas = 0
        v.onDelta = { deltas++ }

        touch(v, MotionEvent.ACTION_DOWN, plusX(v), buttonY)
        advance(longPressTimeout + 10)
        val beforeUp = deltas

        touch(v, MotionEvent.ACTION_UP, plusX(v), buttonY)
        advance(2000)

        assertEquals("松手后还在连发（抬手时是 $beforeUp 次，之后变成了 $deltas 次）", beforeUp, deltas)
    }

    /**
     * 连发时**温度要真的往上走**。
     *
     * 模拟真实链路：步进 → 车机回读 → 写回表盘。
     */
    @Test
    fun 连发时温度真的往上走() {
        val v = makeDial()
        var deltas = 0
        v.onDelta = { delta ->
            deltas++
            v.temperature = v.temperature + delta   // 模拟车机回读
        }

        val before = v.temperature
        touch(v, MotionEvent.ACTION_DOWN, plusX(v), buttonY)
        advance(1000)

        assertTrue("连发时温度没往上走", v.temperature > before)
        assertEquals(
            "温度涨的幅度应该等于步进次数 × 0.5℃",
            deltas * TempDialView.STEP,
            v.temperature - before,
            0.001f,
        )
    }

    /**
     * **减号**键同样连发（方向别写反）。
     */
    @Test
    fun 减号键也连发() {
        val v = makeDial()
        var deltas = 0
        var sum = 0f
        v.onDelta = { delta ->
            deltas++
            sum += delta
        }

        touch(v, MotionEvent.ACTION_DOWN, minusX(v), buttonY)
        advance(1000)

        assertTrue("减号键按住应该连发（实际 $deltas 次）", deltas > 1)
        assertEquals("减号键每次都该是 −0.5℃", -deltas * TempDialView.STEP, sum, 0.001f)
    }

    /**
     * **拖圆环**不该触发连发 —— 连发只属于 ± 键。
     */
    @Test
    fun 拖圆环不会误触发连发() {
        val v = makeDial()
        var deltas = 0
        v.onDelta = { deltas++ }

        val cx = v.width / 2f
        touch(v, MotionEvent.ACTION_DOWN, cx, TempDialView.RING_CY - TempDialView.RING_R)
        advance(2000)

        assertEquals("拖圆环时不该有步进回调", 0, deltas)
    }

    /**
     * 视图离开窗口后**不再连发**。
     *
     * 否则页面被切走、视图被摘掉之后，按住的手指还在（或者手势被系统掐断），
     * 后台还会一直往车机下发温度。
     */
    @Test
    fun 视图离开窗口后不再连发() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val v = makeDial()
        var deltas = 0
        v.onDelta = { deltas++ }
        container.addView(v)

        touch(v, MotionEvent.ACTION_DOWN, plusX(v), buttonY)
        advance(longPressTimeout + 10)
        val beforeDetach = deltas
        assertTrue("前置条件：detach 之前应该已经连发过", beforeDetach > 1)

        // 把视图摘掉 → onDetachedFromWindow
        container.removeView(v)

        advance(2000)
        assertEquals(
            "视图离开窗口后还在连发（detach 时是 $beforeDetach 次，之后变成了 $deltas 次）",
            beforeDetach,
            deltas,
        )
    }
}
