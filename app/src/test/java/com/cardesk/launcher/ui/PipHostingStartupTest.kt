package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.pages.NavPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 画中画承载的**启动期**回归（第116轮）。
 *
 * ## 用户报的现象
 *
 * 4213 版本：导航页的画中画**不显示应用**，只有一块占位图。
 *
 * ## 根因
 *
 * `MainActivity.onDesktopReady()` 是承载的唯一触发点，
 * 而它当时是靠「启动页淡出后 `splash.post { ... }`」触发的 ——
 * 启动页那时已经被 `removeView` 摘掉、**脱离了窗口**，
 * `View.post` 的 Runnable 进了这个视图自己的队列、**永远不会执行**。
 *
 * 于是 `onDesktopReady()` 永不执行 → 承载永不开始 → 画中画停在占位图，
 * 而且**没有异常、没有日志**，只有 `StartupTrace` 里少了 `desktopReady`。
 *
 * ## 这条测试钉什么
 *
 * 「桌面启动之后，画中画承载**最终一定会开始**」——
 * 这是用户看得见的那条契约。
 * 正常路径（桌面就绪）与兜底路径（首帧后 3 秒）**哪条先到都行**，
 * 但最后承载一定会起来，而且 `show()` 一定被调用过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class PipHostingStartupTest {

    /** 一个「装了但没有启动 Intent」的包名 → 承载会降级成卡片，但 `show()` 确实被调用过。 */
    private val pipPkg = "com.cardesk.test.pipapp"

    /** 跑之前先记下画中画应用，跑完还原（别给别的测试留状态）。 */
    private var oldPipApp: String = ""

    @After
    fun tearDown() {
        // 这个测试会真的起一个完整桌面 → 会真的把桌面启动链跑一遍，
        // 里面包括「接上车机昼夜通道」（全局单例）。
        // 不还原的话，后面跑的测试会带着我们的残留（之前就因此踩过一次：
        // 昼夜通道的「回调只注册一次」用例被我们多注册的回调顶红）。
        runCatching {
            Prefs(RuntimeEnvironment.getApplication()).pipApp = oldPipApp
            com.cardesk.launcher.vehicle.AdaptapiDayNight.resetForTest()
            com.ecarx.xui.adaptapi.device.FakeDayNightMode.callbacks.clear()
        }
    }

    @Test
    fun 桌面启动后画中画承载必须已经开始() {
        val launcher = startDesktop()

        assertTrue(
            "桌面启动后画中画承载必须已经开始 —— 不开始的话导航页画中画只有占位图",
            launcher.isPipHostingStarted(),
        )
    }

    @Test
    fun 桌面就绪这条正常路径必须自己能启动承载_不能只靠兜底看门狗() {
        // 兜底看门狗是「桌面首帧之后 3 秒」才接手；
        // 这里只给 800ms（启动页淡出 220ms + 余量），
        // 所以承载只可能是「桌面就绪」这条正常路径启动的 ——
        // 也就是第112轮到第115轮之间**静默断掉的那条链**。
        val launcher = startDesktop(idleMs = 800L)

        assertTrue(
            "桌面就绪后必须立刻开始承载（不能等兜底看门狗）",
            launcher.isPipHostingStarted(),
        )
    }

    @Test
    fun 画中画承载启动后必须真的调用过承载面() {
        val launcher = startDesktop()

        val navPage = findNavPage(launcher)
        assertNotNull("应该能找到导航页", navPage)
        assertEquals(
            "承载必须真的落到主画上（show() 被调用过），不能只是把闸门打开",
            pipPkg,
            navPage!!.pip.currentPackage,
        )
    }

    /**
     * 起一个完整的桌面：设置好画中画应用 → 造 Activity → 走完首帧与启动页淡出。
     *
     * @param idleMs 主线程空转多久（毫秒）。启动页淡出要 220ms，
     *                兜底看门狗要 3000ms —— 给不同的值就能区分是哪条路径起的作用。
     */
    private fun startDesktop(idleMs: Long = 5000L): LauncherView {
        val ctx = RuntimeEnvironment.getApplication()
        // 画中画应用要在桌面启动**之前**就选好（refreshPip 读的是 prefs）。
        val prefs = Prefs(ctx)
        oldPipApp = prefs.pipApp
        prefs.pipApp = pipPkg

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(idleMs))

        val launcher = findLauncher(root)
        assertNotNull("应该能找到桌面", launcher)
        return launcher!!
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findLauncher(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findNavPage(view: View): NavPage? {
        if (view is NavPage) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findNavPage(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
