package com.cardesk.launcher

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.ui.LauncherView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第三十二轮：桌面**左右三指滑动**换页的端到端验证。
 *
 * 走的是真实的 `LauncherView.onInterceptTouchEvent` / `onTouchEvent` 链路：
 * ACTION_DOWN（1 指）→ ACTION_POINTER_DOWN（3 指）→ ACTION_MOVE（3 指整体横移）
 * ACTION_UP，断言页码确实变了。
 *
 * 判定阈值/方向/环绕的纯逻辑在 里覆盖，这里只管接线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreeFingerSwipeTest {

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 造一个带 n 根手指的 MotionEvent。 */
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
        return MotionEvent.obtain(
            0L, 0L, action, xs.size, props, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    /** 当前页码（LauncherView 的私有字段）。 */
    private fun pageOf(launcher: LauncherView): Int {
        val f = LauncherView::class.java.getDeclaredField("page")
        f.isAccessible = true
        return f.getInt(launcher)
    }

    private fun launch(): Pair<LauncherView, android.view.View> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        return launcher!! to decor
    }

    /**
     * 三指整体左滑 400px → 下一页；再右滑 400px → 上一页。
     *
     * 手指初始位置在屏幕中段（避开最右 120px 的右缘竖滑命中区）。
     */
    @Test
    fun `三指左滑翻到下一页右滑翻回上一页`() {
        val (launcher, _) = launch()
        assertEquals("初始应该在第一页", 0, pageOf(launcher))

        // ① 1 指按下
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        // ② 第 3 指按下（POINTER_DOWN，pointerCount=3）
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 700f, 800f),
                floatArrayOf(700f, 700f, 700f),
            )
        )
        // ③ 三指整体左滑 400px
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_MOVE,
                floatArrayOf(200f, 300f, 400f),
                floatArrayOf(700f, 700f, 700f),
            )
        )
        // ④ 抬起
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(200f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("三指左滑应该翻到第二页", 1, pageOf(launcher))

        // ⑤ 再来一次三指右滑 400px → 回到第一页
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 700f, 800f),
                floatArrayOf(700f, 700f, 700f),
            )
        )
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_MOVE,
                floatArrayOf(1000f, 1100f, 1200f),
                floatArrayOf(700f, 700f, 700f),
            )
        )
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(1000f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("三指右滑应该翻回第一页", 0, pageOf(launcher))
    }

    /** 位移不足 160 设计 px 时不翻页。 */
    @Test
    fun `三指滑动距离不够不翻页`() {
        val (launcher, _) = launch()
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 700f, 800f),
                floatArrayOf(700f, 700f, 700f),
            )
        )
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_MOVE,
                floatArrayOf(560f, 660f, 760f),
                floatArrayOf(700f, 700f, 700f),
            )
        )
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(560f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("位移 40px 小于阈值，不该翻页", 0, pageOf(launcher))
    }

    /** 只有两指时不触发三指换页。 */
    @Test
    fun `两指滑动不翻页`() {
        val (launcher, _) = launch()
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 800f),
                floatArrayOf(700f, 700f),
            )
        )
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_MOVE,
                floatArrayOf(200f, 400f),
                floatArrayOf(700f, 700f),
            )
        )
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(200f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("两指不是三指手势，不该翻页", 0, pageOf(launcher))
    }
}
