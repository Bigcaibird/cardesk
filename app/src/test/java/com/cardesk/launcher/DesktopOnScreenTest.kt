package com.cardesk.launcher

import com.cardesk.launcher.core.HomeForeground
import com.cardesk.launcher.core.dock.DockFanPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第144轮：桌面「窗口还在不在屏幕上」的生命周期口径。
 *
 * ## 这条判据为什么必须有
 *
 * 第142轮拿 `onResume`/`onPause`（焦点）当「用户眼前是不是桌面」的判据，
 * 真机上**桌面上点风量键还是「风量 +1」**。
 *
 * 原因：桌面把画中画承载面（`ActivityView` + `VirtualDisplay`）里的高德拉起来时，
 * 系统把焦点抢走 → 桌面走 `onPause`，**可桌面窗口还亮在屏幕上**，
 * 用户眼前就是桌面（地图就在桌面里的画中画窗口上）。
 *
 * 真机日志（2026-11-07 19:55:56.832 ~ 19:56:06）：
 *
 * ```
 * 19:55:56.832 I CarDesk/App : onResume
 * 19:55:57.109 I CarDesk/NavUI : ActivityView 启动承载应用 pkg=com.autonavi.amapautp.yh
 * 19:55:57.146 I CarDesk/App : onPause      ← 焦点没了，桌面还亮着
 * ```
 *
 * 所以这里用 Robolectric 把三种形态都钉住：
 *
 * - `onStart` 后 → 在屏幕上 → 风量键弹空调浮层；
 * - **只 `onPause`（焦点被承载面抢走）→ 仍在屏幕上 → 还是弹空调浮层**；
 * - `onStop`（别的应用全屏盖上来）→ 不在屏幕上 → 直调风量 +1。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DesktopOnScreenTest {

    @Before
    fun resetState() {
        // HomeForeground 是进程级单例，Robolectric 同一测试类里会跨用例保留状态。
        HomeForeground.isForeground = false
        HomeForeground.isOnScreen = false
    }

    @Test
    fun `桌面回到屏幕上时判为在屏幕上且风量键弹空调浮层`() {
        Robolectric.buildActivity(MainActivity::class.java).setup()

        assertTrue("onStart 之后桌面窗口在屏幕上", HomeForeground.isOnScreen)
        assertEquals(
            DockFanPolicy.ACTION_OPEN_CLIMATE,
            DockFanPolicy.decide(HomeForeground.isOnScreen),
        )
    }

    @Test
    fun `焦点被画中画承载面抢走时仍判为在屏幕上`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        // 画中画承载应用启动 = 系统把焦点抢走 → 桌面走 onPause。
        controller.pause()

        assertFalse("onPause 之后桌面没有焦点", HomeForeground.isForeground)
        assertTrue(
            "桌面窗口还亮在屏幕上，不能当成『不在桌面上』",
            HomeForeground.isOnScreen,
        )
        assertEquals(
            DockFanPolicy.ACTION_OPEN_CLIMATE,
            DockFanPolicy.decide(HomeForeground.isOnScreen),
        )
    }

    @Test
    fun `别的应用全屏盖上来时判为不在屏幕上且风量键直调车控`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        controller.stop()

        assertFalse("onStop 之后桌面窗口不在屏幕上了", HomeForeground.isOnScreen)
        assertEquals(
            DockFanPolicy.ACTION_STEP_FAN,
            DockFanPolicy.decide(HomeForeground.isOnScreen),
        )
    }

    @Test
    fun `焦点回来时窗口口径不受影响`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.pause()
        controller.resume()

        assertTrue(HomeForeground.isForeground)
        assertTrue(HomeForeground.isOnScreen)
        assertEquals(
            DockFanPolicy.ACTION_OPEN_CLIMATE,
            DockFanPolicy.decide(HomeForeground.isOnScreen),
        )
    }
}
