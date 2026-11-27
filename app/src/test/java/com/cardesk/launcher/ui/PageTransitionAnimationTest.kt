package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.shell.DockBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 切页**动画**回归（第191轮）。
 *
 * ## 背景：切页其实一直是「硬切」
 *
 * 旧的动画门槛是：
 *
 * ```kotlin
 * val canAnimate = animate && old != target && views.all { it.width > 0 && it.height > 0 }
 * ```
 *
 * 而**进入**的那页平时是 `GONE` —— `GONE` 的视图**不参与测量**，
 * 它从开机起就没量过，`width` 永远是 0。
 *
 * 于是 `canAnimate` 永远是 false → 走 `restPage()` 把两页一显一隐 →
 * **切页从来没有动画过，就是一帧硬切**。
 *
 * 这正是用户说的「切换太过僵硬」的根因 ——
 * 不是动画参数不好看，是**动画压根没跑**。
 *
 * 现在进入页会先被手动量一次（[LauncherView.layoutPageForAnimation]），
 * 动画才真的跑得起来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class PageTransitionAnimationTest {

    private lateinit var activity: MainActivity
    private lateinit var launcher: LauncherView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val found = findLauncher(root)
        assertNotNull("LauncherView 应该挂在窗口里", found)
        launcher = found!!
        // 不承载任何画中画应用 —— 那条路会不停往 Looper 投消息，
        // Robolectric 的 idle() 会永远等不到空闲。
        Prefs(activity.applicationContext).pipApp = ""
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private inline fun <reified T> field(name: String): T? {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(launcher) as? T
    }

    private fun dock(): DockBarView {
        val d = field<DockBarView>("dock")
        assertNotNull("LauncherView 里应该有 dock", d)
        return d!!
    }

    private fun scenePage(): View = field<View>("scenePage")!!
    private fun navPage(): View = field<View>("navPage")!!

    private fun currentPage(): Int = field<Int>("page")!!

    /**
     * 主回归：切页必须真的**动**起来。
     *
     * 断言的是「动画的起始态」——
     * 进入页必须可见、且已经摆到了起始位移上。
     *
     * 硬切（老行为）下：进入页是 GONE、位移 0、alpha 1 → 三条全挂。
     */
    @Test
    fun switchingPageStartsTheSlideAnimation() {
        assertEquals("前置条件：应该在桌面页", 0, currentPage())

        // 注意：这里**不 idle** —— 动画还没跑到下一帧，停在起始态，
        // 正好能看出「有没有把页面摆到起始位移上」。
        dock().onSplit?.invoke()

        val nav = navPage()
        assertEquals(
            "切页后进入页必须是可见的（硬切的话它是 GONE）",
            View.VISIBLE,
            nav.visibility,
        )
        assertTrue(
            "进入页必须已经摆到起始位移上（位移 0 = 根本没动画，是硬切）",
            nav.translationX > 0f,
        )
        assertTrue(
            "进入页应该从**半透明**开始（交叉淡入的起点）",
            nav.alpha < 1f,
        )
    }

    /**
     * 往下一页 = 新页从**右**边进（跟单指左滑手势同向）。
     */
    @Test
    fun forwardSwitchEntersFromTheRight() {
        dock().onSplit?.invoke()
        val nav = navPage()
        assertTrue("往下一页，新页应该从右边进（位移 > 0）", nav.translationX > 0f)
    }

    /**
     * 往上一页 = 新页从**左**边进。
     */
    @Test
    fun backwardSwitchEntersFromTheLeft() {
        // 先切到导航页
        dock().onSplit?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("前置条件：应该在导航页", 1, currentPage())

        // 再切回桌面页
        dock().onSplit?.invoke()
        val scene = scenePage()
        assertTrue("往上一页，新页应该从左边进（位移 < 0）", scene.translationX < 0f)
    }

    /**
     * 位移距离 = 页宽的 22%（不是整屏横移）。
     */
    @Test
    fun travelDistanceIsPartialPageWidth() {
        dock().onSplit?.invoke()
        val nav = navPage()
        val expected = PageTransitionSpec.travelPx(nav.width)
        assertTrue("进入页应该已经量出尺寸（width > 0）", nav.width > 0)
        assertEquals(
            "起始位移应该等于页宽的 22%",
            expected,
            nav.translationX,
            1f,
        )
        assertTrue(
            "位移不该是整屏宽（1920 宽的屏上整屏横移太重）",
            nav.translationX < nav.width,
        )
    }

    /**
     * 动画跑完之后进入页要**归位**（位移 0、alpha 1、缩放 1），
     * 而且离开的那页要 `GONE` —— 不然会挡住触摸。
     */
    @Test
    fun animationEndsWithPagesRestored() {
        dock().onSplit?.invoke()
        // 把动画推到终点
        shadowOf(Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val nav = navPage()
        val scene = scenePage()
        assertEquals("动画结束后进入页要归位（位移 0）", 0f, nav.translationX, 0.5f)
        assertEquals("动画结束后进入页要不透明", 1f, nav.alpha, 0.01f)
        assertEquals(
            "离开的那页要 GONE —— 否则会挡住下面的触摸",
            View.GONE,
            scene.visibility,
        )
    }

    /**
     * 离屏预热停放（`translationX = -4000f`、`VISIBLE`）的导航页在首次切入时，
     * 必须从标准起始位移（`+22%`）滑入，绝不能从 `-4000px` 飞进来。
     */
    @Test
    fun offscreenParkedNavPageEntersFromStandardOffset() {
        val nav = navPage()
        nav.visibility = View.VISIBLE
        nav.translationX = -4000f
        nav.alpha = 1f

        dock().onSplit?.invoke()

        val expected = PageTransitionSpec.travelPx(nav.width)
        assertEquals(
            "离屏停放的导航页进场时应回到标准起始位移",
            expected,
            nav.translationX,
            1f,
        )
        assertEquals("离屏停放的导航页进场时应从 alpha=0 淡入", 0f, nav.alpha, 0.01f)
    }

    /**
     * 静态壁纸冷启动时应预挂一个屏外 1×1 SurfaceView 锚点，
     * 让 Android 9 的首次 relayoutWindow 发生在启动页遮罩期间而非首次切导航页时。
     */
    @Test
    fun staticWallpaperMountsOffscreenSurfaceWarmupAnchor() {
        var found = false
        for (i in 0 until launcher.childCount) {
            val child = launcher.getChildAt(i)
            if (child is android.view.SurfaceView &&
                child.layoutParams.width == 1 &&
                child.layoutParams.height == 1 &&
                child.translationX <= -2400f
            ) {
                found = true
                break
            }
        }
        assertTrue("静态壁纸模式下应存在 1×1 屏外 SurfaceView 预热锚点", found)
    }

    /**
     * 视频壁纸 SurfaceView 的 surface 被销毁时，必须立刻把壁纸让路态关闭
     * （`background.videoPassthrough = false`），防止底层视频层消失后整屏露黑。
     */
    @Test
    fun videoSurfaceDestroyedRestoresStaticBackdropImmediately() {
        val bg = field<com.cardesk.launcher.ui.widget.BackgroundView>("background")!!
        val sv = field<android.view.SurfaceView>("videoSurfaceView")!!
        bg.videoPassthrough = true
        assertTrue(bg.videoPassthrough)

        val conceal = LauncherView::class.java.getDeclaredMethod("concealVideoWallpaperLayer", String::class.java)
        conceal.isAccessible = true
        conceal.invoke(launcher, "test")

        assertTrue("surface 销毁后壁纸层必须立刻恢复绘制（不再透明漏黑）", !bg.videoPassthrough)
        assertNotNull(sv)
    }
}
