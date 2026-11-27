package com.cardesk.launcher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.ui.LauncherView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 第211轮：**壁纸轮播心跳的生命周期**。
 *
 * ## 盯的是什么
 *
 * 第207轮加的轮播心跳（每秒查一次「够不够时间切壁纸」）有两个缺陷：
 *
 * 1. **重建会叠加** —— `build()` 在换肤（[LauncherView.applyTheme]）和改底栏形态
 *    （[LauncherView.onSettingsApplied]）时都会重跑，而 `postDelayed` 前**没有去重**
 *    → 每重建一次，1 秒心跳就多挂一条链，跑 N 份。
 * 2. **detach 之后不停** —— 心跳原来自己 `new Handler(...)`，
 *    `onDetachedFromWindow()` 里的 `handler.removeCallbacksAndMessages(null)`
 *    （另一个 Handler 实例）**清不掉它** → 视图没了它还在跑。
 *
 * 修法：统一走类里的 `handler` + post 前 `removeCallbacks` 去重 +
 * `onAttachedToWindow` 里重新挂上。
 *
 * 这里用 [LauncherView.carouselTickCountForTest] 数心跳跑了多少次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class WallpaperCarouselTickerTest {

    private fun launcher(): LauncherView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return findLauncher(root)!!
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 反射调私有的 `build()`（换肤 / 改底栏形态都会走它）。 */
    private fun invokeBuild(l: LauncherView) {
        val m = LauncherView::class.java.getDeclaredMethod("build")
        m.isAccessible = true
        m.invoke(l)
    }

    /** 反射调 `onDetachedFromWindow()`（protected）。 */
    private fun invokeDetach(l: LauncherView) {
        val m = LauncherView::class.java.getDeclaredMethod("onDetachedFromWindow")
        m.isAccessible = true
        m.invoke(l)
    }

    @Test
    fun `心跳稳定时每秒只跑一次`() {
        val l = launcher()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val base = l.carouselTickCountForTest()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val ticks = l.carouselTickCountForTest() - base
        // 3 秒 → 3 次左右（允许 ±1 的调度抖动）
        assertTrue("3 秒里心跳跑了 $ticks 次，应该约 3 次", ticks in 2..4)
    }

    @Test
    fun `重建不会让心跳叠加`() {
        val l = launcher()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val base = l.carouselTickCountForTest()
        // 模拟换肤 3 次 —— 每次都会重跑 build()
        repeat(3) {
            invokeBuild(l)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val ticks = l.carouselTickCountForTest() - base
        // 修之前：3 秒 × 4 条链 = 12 次；修之后：还是 3 次左右
        assertTrue("重建 3 次后 3 秒里心跳跑了 $ticks 次（修之前会是 12 次）", ticks in 2..5)
    }

    @Test
    fun `detach 之后心跳停`() {
        val l = launcher()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val before = l.carouselTickCountForTest()
        invokeDetach(l)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val after = l.carouselTickCountForTest()
        assertEquals("detach 之后心跳不该再跑", before, after)
    }

    @Test
    fun `后台 onActivityStopped 心跳停，回前台 onActivityStarted 恢复`() {
        val l = launcher()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val beforeStop = l.carouselTickCountForTest()
        l.onActivityStopped()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val afterStop = l.carouselTickCountForTest()
        assertEquals("切到后台后心跳不该继续跑", beforeStop, afterStop)

        l.onActivityStarted()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val afterStart = l.carouselTickCountForTest()
        val resumedTicks = afterStart - afterStop
        assertTrue("回到前台后 3 秒内应恢复心跳（实测 $resumedTicks 次）", resumedTicks in 2..4)
    }
}
