package com.cardesk.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「脱离窗口的视图不能用来 post」——第116轮踩过的坑（根因回归测试）。
 *
 * ## 这条测试在钉什么
 *
 * 第112轮到第115轮，`MainActivity.fadeOutSplash()` 淡出启动页时写的是：
 *
 * ```kotlin
 * (current.parent as? ViewGroup)?.removeView(current)
 * current.post { onDesktopReady() }
 * ```
 *
 * `View.post(Runnable)` 在视图**没有 attach 到窗口**时，
 * 会把 Runnable 塞进这个视图自己的 `HandlerActionQueue`；
 * 而那个队列只在视图**重新 attach 到窗口**时才会被执行
 * （`View.dispatchAttachedToWindow` 里才 `executeActions`）。
 *
 * 启动页刚被 `removeView` 摘掉 → 已经脱离窗口 → 永远不会重新 attach →
 * **那个 Runnable 永远不会跑** →
 * `onDesktopReady()` 永不执行 → 第112轮把画中画承载挂在它后面 →
 * **画中画永远停在占位图**（用户报「导航页画中画不显示应用」）。
 *
 * 而且它**一声不响**：没有异常、没有崩溃、没有日志，
 * 只有 `StartupTrace` 里少了 `desktopReady` 一个分段。
 *
 * 所以这里把平台的这个行为钉死：
 * 以后谁再想在「已经被摘掉」的视图上 post 关键任务，
 * 这条测试会先红给他看。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetachedViewPostTest {

    @Test
    fun 挂在窗口上的视图_post会执行() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = TextView(activity)
        root.addView(view)
        layout(root)

        val ran = AtomicBoolean(false)
        view.post { ran.set(true) }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("挂在窗口上的视图，post 一定会执行", ran.get())
    }

    @Test
    fun 脱离窗口的视图_post永远不会执行() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = TextView(activity)
        root.addView(view)
        layout(root)

        // 关键：把视图从窗口上摘掉（启动页淡出就是这一步）。
        // 之后 `view.post {}` 会进这个视图自己的 HandlerActionQueue，
        // 而那个队列只在**重新 attach** 时才被执行 —— 启动页永远不会再 attach。
        (view.parent as? ViewGroup)?.removeView(view)
        assertFalse("视图已经脱离窗口", view.isAttachedToWindow)

        val ran = AtomicBoolean(false)
        view.post { ran.set(true) }
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "脱离窗口的 View.post 永远不会执行 —— 所以启动页淡出不能用它 post 关键任务",
            ran.get(),
        )
    }

    @Test
    fun 主线程Handler的post跟视图attach状态无关_一定执行() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = TextView(activity)
        root.addView(view)
        layout(root)
        (view.parent as? ViewGroup)?.removeView(view)

        // 这就是第116轮换成的写法：Activity 自己的主线程 Handler。
        val ran = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { ran.set(true) }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("主线程 Handler 的 post 一定跑得到", ran.get())
    }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
