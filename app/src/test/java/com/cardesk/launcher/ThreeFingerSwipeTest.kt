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

    /**
     * 第206轮：两指横滑**就是**翻页手势了（单指让给画中画拖地图）。
     */
    @Test
    fun `两指左滑翻到下一页`() {
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
        assertEquals("两指左滑 400px 应该翻到第二页", 1, pageOf(launcher))
    }

    /**
     * 第207轮：**分多段滑动**也要能翻页（真机实测「双指滑不动」的复现用例）。
     *
     * 为什么上面那条用例发现不了问题：它只发**一个** ACTION_MOVE，
     * 位移一次性给到 400px —— 接管判定和最终判定看到的是同一个位移。
     *
     * 真机上的双指滑动是**连续很多个小 MOVE**：
     * 手指先缓慢移动 → 越过接管线（60px）→ 接管 → 继续滑到 400px。
     * 第206轮的实现在接管之后就不再更新中位点了，
     * 于是抬手时算出来的位移永远停在「刚过 60px」那一点，
     * 够不到翻页阈值 → **双指怎么滑都不翻页**。
     *
     * 这里用 10 段 × 40px = 400px 的连续 MOVE 复现真实手势。
     */
    @Test
    fun `两指分多段滑动也能翻到下一页`() {
        val (launcher, _) = launch()
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 800f),
                floatArrayOf(700f, 700f),
            )
        )
        // 10 段 × 40px，整体左移 400px
        for (i in 1..10) {
            val off = 40f * i
            launcher.dispatchTouchEvent(
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(600f - off, 800f - off),
                    floatArrayOf(700f, 700f),
                )
            )
        }
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(200f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("分多段的双指左滑应该翻到第二页", 1, pageOf(launcher))
    }

    /** 第207轮：两指整体**竖着**滑动不翻页（纵向压倒横向）。 */
    @Test
    fun `两指竖向滑动不翻页`() {
        val (launcher, _) = launch()
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 800f),
                floatArrayOf(700f, 700f),
            )
        )
        // 整体下移 400px（横向几乎不动）
        for (i in 1..10) {
            val off = 40f * i
            launcher.dispatchTouchEvent(
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(600f, 800f),
                    floatArrayOf(700f + off, 700f + off),
                )
            )
        }
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(600f), floatArrayOf(1100f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("竖向的双指滑动不该翻页", 0, pageOf(launcher))
    }

    /** 第207轮：捏合（两指间距大幅变化）不该翻页 —— 画中画里缩放地图不能误翻页。 */
    @Test
    fun `两指捏合不翻页`() {
        val (launcher, _) = launch()
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 800f),
                floatArrayOf(700f, 700f),
            )
        )
        // 中位点左移 400px，但两指从相距 200px 张开到 600px
        for (i in 1..10) {
            val off = 40f * i
            val half = 100f + 50f * i
            launcher.dispatchTouchEvent(
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(700f - off - half, 700f - off + half),
                    floatArrayOf(700f, 700f),
                )
            )
        }
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(300f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("捏合缩放不该翻页", 0, pageOf(launcher))
    }

    /**
     * 第207轮：**两指不在同一水平线上**也照样能横滑翻页。
     *
     * 以前纵向位移用的是 `ev.y`（**第一根手指**的 Y），横向用的却是两指中位点，
     * 两者不同源 —— 两指一高一低时纵向分量会莫名偏大，
     * 把本该判为横滑的手势卡掉（用户体感就是「很难滑动」）。
     *
     * 这组数据是**能区分新旧实现**的：
     * - 旧：dy = 第一指 Y 的位移 = -200px，|dx|=400 不大于 |dy|*1.2=240 的 1.2 倍
     *   → 判成纵向手势，不翻页；
     * - 新：dy = 两指中位点的 Y 位移 = 0 → 判成横滑，翻页。
     */
    @Test
    fun `两指不在同一水平线上也能横滑翻页`() {
        val (launcher, _) = launch()
        // 第一指 y=900，第二指 y=100 —— 差了 800px（纵向差必须大到能盖过横向）
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(900f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 800f),
                floatArrayOf(900f, 100f),
            )
        )
        // 整体左移 400px（两指的 Y 都不变）
        for (i in 1..10) {
            val off = 40f * i
            launcher.dispatchTouchEvent(
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(600f - off, 800f - off),
                    floatArrayOf(900f, 100f),
                )
            )
        }
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(200f), floatArrayOf(900f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("两指不在同一水平线上也该翻到第二页", 1, pageOf(launcher))
    }

    /**
     * 第207轮：两指靠得近（初始间距只有 30px）、滑动时有轻微抖动，也照样能翻页。
     *
     * 纯相对量判定下，手指抖 6px 就是「20% 的间距变化」→ 会被误杀成捏合。
     * 绝对容差（60px）兜住这种小间距场景。
     */
    @Test
    fun `两指靠得近且轻微抖动仍能横滑翻页`() {
        val (launcher, _) = launch()
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        // 第二指只离第一指 30px
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 630f),
                floatArrayOf(700f, 700f),
            )
        )
        // 整体左移 400px，其中第二指额外张开 6px（模拟手指抖动）
        for (i in 1..10) {
            val off = 40f * i
            launcher.dispatchTouchEvent(
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(600f - off, 630f - off + 6f),
                    floatArrayOf(700f, 700f),
                )
            )
        }
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(200f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("两指靠得近也该翻到第二页", 1, pageOf(launcher))
    }

    /**
     * 第207轮：**先抬一根手指、再抬另一根**（真机最常见的收尾方式）也能翻页。
     *
     * 抬手分两步时，事件序列是
     * `MOVE → ACTION_POINTER_UP（还剩 2 根手指）→ ACTION_UP（只剩 1 根）`。
     *
     * 关键点：最后那次 `ACTION_UP` 里只剩**一根**手指，
     * 此时再取「两指中位点」会退化成单指的坐标，与按下时的起点不同源；
     * 纵向位移必须在 MOVE（两指都在）时就记下来。
     */
    @Test
    fun `分步抬手（先抬一指再抬另一指）也能横滑翻页`() {
        val (launcher, _) = launch()
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, floatArrayOf(600f), floatArrayOf(700f)))
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_DOWN,
                floatArrayOf(600f, 800f),
                floatArrayOf(700f, 700f),
            )
        )
        // 整体左移 400px
        for (i in 1..10) {
            val off = 40f * i
            launcher.dispatchTouchEvent(
                ev(
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(600f - off, 800f - off),
                    floatArrayOf(700f, 700f),
                )
            )
        }
        // ① 先抬第二指：ACTION_POINTER_UP，此时还剩 2 根手指（抬起的是 index 1）
        launcher.dispatchTouchEvent(
            ev(
                MotionEvent.ACTION_POINTER_UP,
                floatArrayOf(200f, 400f),
                floatArrayOf(700f, 700f),
            )
        )
        // ② 再抬剩下那根：ACTION_UP，此时只剩 1 根手指
        launcher.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, floatArrayOf(200f), floatArrayOf(700f)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("分步抬手也该翻到第二页", 1, pageOf(launcher))
    }
}
