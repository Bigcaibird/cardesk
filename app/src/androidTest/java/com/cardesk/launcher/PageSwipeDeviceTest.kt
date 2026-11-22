package com.cardesk.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.cardesk.launcher.ui.LauncherView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 第206轮：**双指横滑翻页**的端到端验证（真机/模拟器上跑）。
 *
 * 为什么不用 Robolectric：多指触摸的接线（`ACTION_POINTER_DOWN` 的真实派发、
 * 子视图消费后的 CANCEL、`dispatchTouchEvent` 的路径）在 Robolectric 里是模拟的；
 * 这里直接用真实 View 树派发多指 [MotionEvent]，验的是同一份代码在真框架下的行为。
 *
 * 覆盖：
 * 1. 双指左滑 → 下一页；
 * 2. 双指右滑 → 上一页；
 * 3. 单指横滑 → **不翻页**（单指留给画中画拖地图）；
 * 4. 双指捏合（间距变化大）→ 不翻页。
 */
@RunWith(AndroidJUnit4::class)
class PageSwipeDeviceTest {

    private fun launch(): Pair<LauncherView, View> {
        val instr = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instr.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity: Activity = instr.startActivitySync(intent)
        instr.waitForIdleSync()
        val decor = activity.window.decorView
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline &&
            (!launcher!!.isShown || launcher.width <= 0 || launcher.height <= 0)
        ) {
            Thread.sleep(100)
        }
        instr.waitForIdleSync()
        resetToFirstPage(launcher!!)
        return launcher to decor
    }

    /**
     * 测试之间复用同一个 Activity，页索引会留在上一轮翻到的位置，
     * 每个用例开始前用反射把页面拨回第一页（不带动画）。
     */
    private fun resetToFirstPage(launcher: LauncherView) {
        val m = LauncherView::class.java.getDeclaredMethod(
            "showPage", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        m.isAccessible = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync { m.invoke(launcher, 0, false) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun ev(action: Int, xs: FloatArray, ys: FloatArray): MotionEvent {
        val props = Array(xs.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(xs.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun pageOf(launcher: LauncherView): Int {
        val f = LauncherView::class.java.getDeclaredField("page")
        f.isAccessible = true
        return f.getInt(launcher)
    }

    /**
     * 直接派给 LauncherView。
     *
     * 说明：模拟器上没法真实做多指触摸（`/dev/input` 没有触摸屏节点，
     * `input` 命令只支持单指；`UiAutomation.injectInputEvent` 注入的多指事件
     * 会被框架按 split-motion-events 拆成单指序列，LauncherView 收不到
     * POINTER_DOWN）。所以这里在**视图层**验证手势本身：
     * 事件从 LauncherView.dispatchTouchEvent 进入，正是第206轮接管逻辑的位置。
     * 真车上的窗口派发（父容器、InputManager）另由实车验证兜底。
     */
    private fun dispatch(view: View, e: MotionEvent) {
        var ret = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ret = view.dispatchTouchEvent(e) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.recycle()
    }

    /** 双指整体横移 [dx]（正=右滑）。 */
    private fun twoFingerSwipe(view: View, dx: Float, dy: Float = 0f, spanRatio: Float = 1f) {
        val y0 = 700f
        val x0 = 700f
        val x1 = 900f
        val mid = (x0 + x1) / 2f
        val half = (x1 - x0) / 2f * spanRatio
        dispatch(view, ev(MotionEvent.ACTION_DOWN, floatArrayOf(x0), floatArrayOf(y0)))
        dispatch(view, ev(MotionEvent.ACTION_POINTER_DOWN, floatArrayOf(x0, x1), floatArrayOf(y0, y0)))
        dispatch(
            view,
            ev(
                MotionEvent.ACTION_MOVE,
                floatArrayOf(mid + dx / 2f - half, mid + dx / 2f + half),
                floatArrayOf(y0 + dy, y0 + dy),
            ),
        )
        dispatch(view, ev(MotionEvent.ACTION_UP, floatArrayOf(mid + dx / 2f), floatArrayOf(y0 + dy)))
    }

    /**
     * 双指**分多段**横移 [dx]（正=右滑）—— 真机上的真实手势形态。
     *
     * 第207轮：手指滑动是连续很多个小 MOVE，不是一次一个大跳。
     * 以前只发**一个** MOVE 的用例盖不住「接管之后不再更新中位点」这个 bug，
     * 真机表现就是「双指怎么滑都不翻页」。
     */
    private fun twoFingerSwipeInSteps(view: View, dx: Float, steps: Int = 10) {
        val y = 700f
        val x0 = 700f
        val x1 = 900f
        dispatch(view, ev(MotionEvent.ACTION_DOWN, floatArrayOf(x0), floatArrayOf(y)))
        dispatch(view, ev(MotionEvent.ACTION_POINTER_DOWN, floatArrayOf(x0, x1), floatArrayOf(y, y)))
        for (i in 1..steps) {
            val off = dx * i / steps
            dispatch(
                view,
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(x0 + off, x1 + off),
                    floatArrayOf(y, y),
                ),
            )
        }
        dispatch(view, ev(MotionEvent.ACTION_UP, floatArrayOf(x0 + dx), floatArrayOf(y)))
    }

    @Test
    fun `双指左滑翻到下一页`() {
        val (launcher, decor) = launch()
        assertEquals(0, pageOf(launcher))
        twoFingerSwipe(launcher, dx = -400f)
        assertEquals("双指左滑应该翻到第二页", 1, pageOf(launcher))
    }

    /** 第207轮：分多段的双指滑动也要能翻页（真机「滑不动」的复现用例）。 */
    @Test
    fun `双指分多段滑动也能翻到下一页`() {
        val (launcher, decor) = launch()
        assertEquals(0, pageOf(launcher))
        twoFingerSwipeInSteps(launcher, dx = -400f)
        assertEquals("分多段的双指左滑应该翻到第二页", 1, pageOf(launcher))
    }

    /** 第207轮：两指竖向滑动不该翻页。 */
    @Test
    fun `两指竖向滑动不翻页`() {
        val (launcher, decor) = launch()
        assertEquals(0, pageOf(launcher))
        val y = 700f
        dispatch(launcher, ev(MotionEvent.ACTION_DOWN, floatArrayOf(700f), floatArrayOf(y)))
        dispatch(launcher, ev(MotionEvent.ACTION_POINTER_DOWN, floatArrayOf(700f, 900f), floatArrayOf(y, y)))
        for (i in 1..10) {
            val off = 40f * i
            dispatch(
                launcher,
                ev(MotionEvent.ACTION_MOVE, floatArrayOf(700f, 900f), floatArrayOf(y + off, y + off)),
            )
        }
        dispatch(launcher, ev(MotionEvent.ACTION_UP, floatArrayOf(700f), floatArrayOf(y + 400f)))
        assertEquals("竖向的双指滑动不该翻页", 0, pageOf(launcher))
    }

    @Test
    fun `双指右滑翻回上一页`() {
        val (launcher, decor) = launch()
        twoFingerSwipe(launcher, dx = -400f)
        assertEquals(1, pageOf(launcher))
        twoFingerSwipe(launcher, dx = 400f)
        assertEquals("双指右滑应该翻回第一页", 0, pageOf(launcher))
    }

    @Test
    fun `单指横滑不再翻页`() {
        val (launcher, decor) = launch()
        assertEquals(0, pageOf(launcher))
        val y = 700f
        dispatch(launcher, ev(MotionEvent.ACTION_DOWN, floatArrayOf(900f), floatArrayOf(y)))
        dispatch(launcher, ev(MotionEvent.ACTION_MOVE, floatArrayOf(500f), floatArrayOf(y)))
        dispatch(launcher, ev(MotionEvent.ACTION_UP, floatArrayOf(500f), floatArrayOf(y)))
        assertEquals("单指不再承担翻页", 0, pageOf(launcher))
    }

    @Test
    fun `双指捏合不翻页`() {
        val (launcher, decor) = launch()
        assertEquals(0, pageOf(launcher))
        twoFingerSwipe(launcher, dx = -400f, spanRatio = 1.8f)
        assertEquals("捏合缩放不该被当成翻页", 0, pageOf(launcher))
    }

    /**
     * 第207轮：**两指不在同一水平线上**也能横滑翻页。
     *
     * 纵向位移必须与横向位移**同源**（都用两指中位点），
     * 否则两指一高一低时纵向分量会莫名偏大，把横滑手势卡掉。
     */
    @Test
    fun `双指不在同一水平线上也能横滑翻页`() {
        val (launcher, decor) = launch()
        assertEquals(0, pageOf(launcher))
        val y0 = 900f
        val y1 = 100f
        dispatch(launcher, ev(MotionEvent.ACTION_DOWN, floatArrayOf(700f), floatArrayOf(y0)))
        dispatch(
            launcher,
            ev(MotionEvent.ACTION_POINTER_DOWN, floatArrayOf(700f, 900f), floatArrayOf(y0, y1)),
        )
        for (i in 1..10) {
            val off = 40f * i
            dispatch(
                launcher,
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(700f - off, 900f - off),
                    floatArrayOf(y0, y1),
                ),
            )
        }
        dispatch(launcher, ev(MotionEvent.ACTION_UP, floatArrayOf(300f), floatArrayOf(y0)))
        assertEquals("两指不在同一水平线上也该翻到第二页", 1, pageOf(launcher))
    }

    /**
     * 第207轮：**分步抬手**（先抬一根、再抬另一根）也能翻页。
     *
     * 真机上收尾常见的就是分两步：先出 `ACTION_POINTER_UP`（还剩 2 根），
     * 再出 `ACTION_UP`（只剩 1 根）。最后那次抬手只剩一根手指，
     * 纵向位移必须在 MOVE（两指都在）时就记下来。
     */
    @Test
    fun `分步抬手也能横滑翻页`() {
        val (launcher, decor) = launch()
        assertEquals(0, pageOf(launcher))
        val y = 700f
        dispatch(launcher, ev(MotionEvent.ACTION_DOWN, floatArrayOf(700f), floatArrayOf(y)))
        dispatch(
            launcher,
            ev(MotionEvent.ACTION_POINTER_DOWN, floatArrayOf(700f, 900f), floatArrayOf(y, y)),
        )
        for (i in 1..10) {
            val off = 40f * i
            dispatch(
                launcher,
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(700f - off, 900f - off),
                    floatArrayOf(y, y),
                ),
            )
        }
        // 先抬第二指
        dispatch(
            launcher,
            ev(MotionEvent.ACTION_POINTER_UP, floatArrayOf(300f, 500f), floatArrayOf(y, y)),
        )
        // 再抬剩下那根
        dispatch(launcher, ev(MotionEvent.ACTION_UP, floatArrayOf(300f), floatArrayOf(y)))
        assertEquals("分步抬手也该翻到第二页", 1, pageOf(launcher))
    }
}
