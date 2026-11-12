package com.cardesk.launcher.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 设置页「滑动时不该触发桌面翻页」（第149轮，需求 3）。
 *
 * ## 这个 bug 的机制
 *
 * 设置页是桌面的**子 View**（`LauncherView.content` 里 `addView` 进去的），
 * 所以桌面的 `onInterceptTouchEvent` 对设置页里的控件**同样生效**：
 * 桌面把「单指横滑超过阈值」当成翻页手势，会把 MOVE 事件抢走 ——
 * 拖滑杆时手指必然横向移动，一越过阈值就被抢，
 * 表现就是**滑杆卡住不动，桌面反而翻页了**。
 *
 * ## 修法与「置位时机」
 *
 * `requestDisallowInterceptTouchEvent(true)` 会把 `FLAG_DISALLOW_INTERCEPT`
 * 一路传到祖先；祖先的 `ViewGroup.dispatchTouchEvent` 看到这个标志位时
 * **不会**再去调 `onInterceptTouchEvent`。
 *
 * 但时机很关键（把 `android-all` 的 `ViewGroup` 反编译看过）：
 *
 * - `dispatchTouchEvent` 在 **ACTION_DOWN** 时会调 `resetTouchState()`，
 *   它干的第一件事就是 `mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT` ——
 *   **每次新手势开始，祖先身上的标志都会被清掉**。
 *   ⇒ 「挂上去时置一次」等于没置。
 * - `AbsSeekBar` 在 `ScrollView` 里按下时走 `isInScrollingContainer()` 分支，
 *   **不会**调 `startDrag`，要等位移超 touch slop 的第一个 MOVE 才调 ——
 *   而那个 MOVE 正是桌面要抢的那个。⇒ 「等滑杆回调再置」也太晚。
 *
 * 正确做法：在 DOWN 派发**之后**立刻置位（见 `SettingsOverlay.dispatchTouchEvent`）。
 *
 * ## 这条测试锁的是什么
 *
 * 用一个「仿桌面」的父容器复刻桌面的抢事件逻辑，
 * 断言**桌面一次都不许抢**设置页里的 MOVE。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsTouchInterceptTest {

    /**
     * 仿桌面：横滑超过阈值就把事件抢走（等价于翻页）。
     *
     * 真实桌面在 `LauncherView.onInterceptTouchEvent` 里就是这么干的：
     * 位移超过 `SINGLE_FINGER_SLOP` 且横向占优 → `return true` 抢走事件。
     */
    private class DesktopLikeParent(context: Context) : FrameLayout(context) {
        /** 抢走过的 MOVE 事件次数。 */
        var stolenMoves = 0
            private set

        /** 有没有被抢过（只要抢过一次就说明设置页没保护住）。 */
        val stolen: Boolean get() = stolenMoves > 0

        private var downX = 0f

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = ev.x
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.x - downX) > SLOP) {
                        stolenMoves++
                        return true
                    }
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        companion object {
            /** 桌面翻页阈值（真机上比这个值更大，这里取小一点更容易复现）。 */
            const val SLOP = 40f
        }
    }

    private fun activity(): com.cardesk.launcher.MainActivity =
        Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()

    /** 量一遍尺寸 —— 命中测试要用到子视图的宽高，不量的话点不到任何东西。 */
    private fun layout(parent: ViewGroup) {
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 1920, 1080)
    }

    /**
     * 组一套「桌面 → 设置页 → 探针」的三层结构。
     *
     * 探针是个可点击的空 View，只为了**吃掉按下** ——
     * 没有子视图消费按下的话，父容器压根不会把 MOVE 往下派，
     * 也就测不出「桌面抢不抢」这件事。
     */
    private fun buildTree(activity: com.cardesk.launcher.MainActivity): Pair<DesktopLikeParent, SettingsOverlay> {
        val parent = DesktopLikeParent(activity)
        activity.setContentView(parent)
        layout(parent)

        val overlay = SettingsOverlay(activity, Prefs(activity), Palettes.night)
        parent.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        layout(parent)

        val probe = View(activity).apply { isClickable = true }
        overlay.addView(probe, FrameLayout.LayoutParams(600, 600))
        layout(parent)
        return parent to overlay
    }

    @Test
    fun `设置页里横滑时桌面一次都不许抢事件`() {
        val activity = activity()
        val (parent, _) = buildTree(activity)

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        // 一次位移 500px —— 模拟「划得快」，单个 MOVE 就越过任何阈值
        val move = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_MOVE, 600f, 100f, 0)
        parent.dispatchTouchEvent(down)
        parent.dispatchTouchEvent(move)
        down.recycle()
        move.recycle()

        assertEquals(
            "桌面抢走了 ${parent.stolenMoves} 个 MOVE —— 设置页滑动会触发翻页",
            0,
            parent.stolenMoves,
        )
    }

    @Test
    fun `连续多次横滑桌面都不许抢事件`() {
        val activity = activity()
        val (parent, _) = buildTree(activity)

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        parent.dispatchTouchEvent(down)
        for (i in 1..6) {
            val move = MotionEvent.obtain(0L, 16L * i, MotionEvent.ACTION_MOVE, 100f + 80f * i, 100f, 0)
            parent.dispatchTouchEvent(move)
            move.recycle()
        }
        down.recycle()

        assertEquals("桌面一次都不许抢", 0, parent.stolenMoves)
    }

    @Test
    fun `按下之后祖先身上必须挂着禁止拦截标志`() {
        val activity = activity()
        val (parent, overlay) = buildTree(activity)

        // 只发一个 DOWN —— 设置页在这一步就该把标志置到祖先身上
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        overlay.dispatchTouchEvent(down)
        down.recycle()

        assertTrue(
            "设置页自己身上必须挂着 FLAG_DISALLOW_INTERCEPT",
            hasDisallowInterceptFlag(overlay),
        )
        assertTrue(
            "祖先（桌面）身上必须挂着 FLAG_DISALLOW_INTERCEPT",
            hasDisallowInterceptFlag(parent),
        )
    }

    /**
     * 读 `ViewGroup.mGroupFlags` 里的 `FLAG_DISALLOW_INTERCEPT`。
     *
     * 这个标志是 `package` 级常量（0x80000），没有公开的读法，
     * 只能反射 —— 反编译 `android-all` 时顺带确认过它的值。
     */
    private fun hasDisallowInterceptFlag(view: ViewGroup): Boolean {
        val flagsField = ViewGroup::class.java.getDeclaredField("mGroupFlags").apply { isAccessible = true }
        val flags = flagsField.getInt(view)
        return (flags and 0x80000) != 0
    }

    /** 摘掉设置页后要把标志撤掉，否则桌面再也翻不了页。 */
    @Test
    fun `关掉设置页后标志要撤掉`() {
        val activity = activity()
        val (parent, overlay) = buildTree(activity)

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        overlay.dispatchTouchEvent(down)
        down.recycle()
        assertTrue("先确认标志确实置上了", hasDisallowInterceptFlag(parent))

        parent.removeView(overlay)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(
            "设置页摘掉后，桌面身上的禁止拦截标志必须撤掉（否则桌面翻不了页）",
            !hasDisallowInterceptFlag(parent),
        )
    }
}
