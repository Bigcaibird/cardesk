package com.cardesk.launcher.ui.pages

import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.LauncherView
import com.cardesk.launcher.ui.pip.NavSplitPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 第129轮：导航页「画中画全屏按钮」（`navSplit`）**悬浮叠在画中画画面上**。
 *
 * ## 为什么要有这条测试
 *
 * 旧实现把按钮挂在**导航页本体**上（`Gravity.START`）——
 * 也就是独占 navRow 左边那 44px 留白（`NAV_SIDE_MARGIN`），
 * 画中画左侧长期空着一条竖玻璃条，还跟画中画画面不对齐。
 *
 * 这个改动**编译得过、安装得上、也不崩**，但肉眼一眼能看出不对，
 * 真车现场更是「按钮在哪都找不到」。
 * 所以这里用 Robolectric 把真实布局跑一遍，直接断言
 * **按钮的父容器就是画中画承载面**、**它压在画面左边缘**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class NavSplitOverlayTest {

    private fun setup(): LauncherView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        ShadowLooper.idleMainLooper()
        val launcher = findLauncher(root)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
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

    /**
     * 把导航页**真正测量一遍**。
     *
     * 桌面启动时停在首页，导航页是隐藏的 → 没被 measure，
     * 承载面宽度会是 0，坐标断言就没法做。
     * 这里手动跑一遍 measure/layout。
     */
    private fun layoutNavPage(navPage: NavPage) {
        navPage.visibility = View.VISIBLE
        navPage.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        navPage.layout(0, 0, 1920, 1080)
        ShadowLooper.idleMainLooper()
    }

    /**
     * 找那条 36×110 的玻璃按钮。
     *
     * 靠**尺寸**认（36×110 在导航页里唯一），
     * 不靠字段名 —— `navSplit` 是 private。
     */
    private fun findNavSplit(root: View): View? {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val lp = child.layoutParams
                val w = if (lp != null && lp.width > 0) lp.width else child.width
                val h = if (lp != null && lp.height > 0) lp.height else child.height
                if (w == NavSplitPlacement.WIDTH && h == NavSplitPlacement.HEIGHT) return child
                findNavSplit(child)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun 按钮挂在画中画承载面上而不是导航页本体() {
        val launcher = setup()
        val navPage = findNavPage(launcher)
        assertNotNull("应该能找到导航页", navPage)

        val split = findNavSplit(navPage!!)
        assertNotNull("应该能找到画中画全屏按钮（36×110）", split)

        val parent = split!!.parent
        assertNotNull("按钮必须有父容器", parent)

        // —— 核心断言：按钮在**画中画承载面**里（悬浮叠在画面上）
        assertSame(
            "按钮应该挂在画中画承载面（pipCard）里，而不是导航页本体",
            navPage.pipCard,
            parent,
        )

        // —— 旧行为（独占左侧留白）必须回不来
        assertNotSame(
            "按钮不能挂在导航页本体上（旧行为：独占左侧 44px 留白，不在画面上）",
            navPage,
            parent,
        )
    }

    @Test
    fun 按钮尺寸与定位参数沿用原值() {
        val launcher = setup()
        val navPage = findNavPage(launcher)!!
        val split = findNavSplit(navPage)!!
        val lp = split.layoutParams as FrameLayout.LayoutParams

        assertEquals(NavSplitPlacement.WIDTH, lp.width)
        assertEquals(NavSplitPlacement.HEIGHT, lp.height)
        assertEquals(
            "按钮应该左侧 + 垂直居中",
            Gravity.START or Gravity.CENTER_VERTICAL,
            lp.gravity,
        )
    }

    @Test
    fun 按钮压在画中画画面左边缘而不是画外() {
        val launcher = setup()
        val navPage = findNavPage(launcher)!!
        layoutNavPage(navPage)
        val split = findNavSplit(navPage)!!
        val card = navPage.pipCard

        assertTrue("画中画承载面应该有宽度（布局已跑）", card.width > 0)
        // split 是 card 的子 View，left/right 都是**相对 card** 的坐标
        assertEquals("按钮应该紧贴画中画左边缘", 0, split.left)
        assertTrue(
            "按钮不能超出画中画右边界（left=${split.left} width=${split.width} card=${card.width}）",
            split.left + split.width <= card.width,
        )
        // 垂直居中
        val expectedTop = (card.height - NavSplitPlacement.HEIGHT) / 2
        assertTrue(
            "按钮应该垂直居中（top=${split.top} 期望≈$expectedTop card.height=${card.height}）",
            kotlin.math.abs(split.top - expectedTop) <= 2,
        )
    }

    @Test
    fun 全屏往返后按钮回到画中画承载面() {
        val launcher = setup()
        val navPage = findNavPage(launcher)!!
        val split = findNavSplit(navPage)!!

        // 进全屏：按钮被摘到桌面根容器上
        val detached = navPage.detachNavSplit()
        assertNotNull("全屏时应该能把按钮摘出去", detached)
        assertSame("摘出来的应该是同一个按钮", split, detached)

        // 还原：按钮回到画中画承载面（不是导航页本体）
        navPage.attachNavSplit()
        assertSame(
            "还原后按钮应该回到画中画承载面",
            navPage.pipCard,
            split.parent,
        )
        assertNotSame("还原后按钮不能回到导航页本体", navPage, split.parent)
    }

    /**
     * 全屏态的按钮必须与悬浮态**用同一套定位参数**。
     *
     * 两态各写一份字面量的话，改了一处忘了另一处，
     * 用户就会看到「点全屏后按钮跑位了」。
     * 这条直接盯住**全屏态真实 addView 用的 gravity**，
     * 硬编码就会红。
     */
    @Test
    fun 全屏态按钮与悬浮态用同一套定位参数() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.pipApp = "com.cardesk.test.pipapp"
        val launcher = setup()
        val navPage = findNavPage(launcher)!!

        // 进全屏（`LauncherView.togglePipFullscreen`）
        navPage.onSplitToggle?.invoke()
        ShadowLooper.idleMainLooper()
        assertTrue("应该已经进入画中画全屏态", launcher.isPipFullscreen())

        val split = findNavSplit(launcher)
        assertNotNull("全屏时按钮应该浮在画面上", split)
        val lp = split!!.layoutParams as FrameLayout.LayoutParams

        assertEquals(NavSplitPlacement.WIDTH, lp.width)
        assertEquals(NavSplitPlacement.HEIGHT, lp.height)
        assertEquals(
            "全屏态与悬浮态的 gravity 必须一致（用户不用记两个位置）",
            NavSplitPlacement.gravity(fullscreen = false),
            lp.gravity,
        )
        assertNotSame(
            "全屏时按钮应该浮在桌面根容器上，而不是还留在导航页里",
            navPage,
            split.parent,
        )
    }

    @Test
    fun 画中画左右换位时按钮跟着画面走() {
        val launcher = setup()
        val navPage = findNavPage(launcher)!!
        val split = findNavSplit(navPage)!!

        // 换位：承载面自己保持 LTR（只换位置、不镜像内容），
        // 按钮按 Gravity.START 定位 → 永远贴**画中画自己的**左边缘。
        assertTrue("第一次换位应当变成 swapped", navPage.toggleSidesSwapped())
        assertEquals(
            "画中画承载面必须保持 LTR，否则按钮会跑到画面右边",
            NavSplitPlacement.PIP_CARD_LAYOUT_DIRECTION,
            navPage.pipCard.layoutDirection,
        )
        assertSame(
            "换位后按钮仍然在画中画承载面里",
            navPage.pipCard,
            split.parent,
        )
        // 换回去，别影响后续用例
        navPage.applySidesSwapped(false)
    }
}
