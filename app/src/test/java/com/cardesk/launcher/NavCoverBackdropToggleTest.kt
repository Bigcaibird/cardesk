package com.cardesk.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.LauncherView
import com.cardesk.launcher.ui.pages.NavPage
import com.cardesk.launcher.ui.widget.AlbumArtView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「点音乐卡封面图 → 切换歌曲图片作背景」的端到端断言。
 *
 * ## 这条测试真正要锁住的是什么
 *
 * 不是「某个布尔值翻了个面」，而是**三个很容易踩空的地方**：
 *
 * ① **入口真的挂上了** —— 封面图那个 96×96 的 View 上**真的有**点击监听，
 *    而且点下去能触发切换（不是点了个寂寞）。
 *
 * ② **状态真的落盘了** —— 切换要写进 `prefs`，桌面是杀进程重开的，
 *    不落盘等于没做。
 *
 * ③ **冷启动真的记住了** —— 这条最容易翻车：
 *
 *    Kotlin 的属性初始化**不走 setter**（`var x = 初值` 是编译器直接写 field），
 *    所以 `coverBackdropEnabled` 的初值虽然读自 `prefs`，
 *    但背景层（`CoverBackdropDrawable.coverEnabled`）还停在构造时的默认值 `true` ——
 *    **用户上次关掉的背景，冷启动会自己回来**。
 *
 *    `NavPage.build()` 末尾那次 `syncCoverBackdropState()` 就是补这个洞的，
 *    这条用例是它的守门人。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class NavCoverBackdropToggleTest {

    private fun prefs(): Prefs = Prefs(RuntimeEnvironment.getApplication())

    /** 起一个完整的桌面，返回 LauncherView。 */
    private fun setupLauncher(): LauncherView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return findByType(root, LauncherView::class.java)!!
    }

    /** 在 view 树里按类型找第一个匹配的 View。 */
    private fun <T : View> findByType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findByType(view.getChildAt(i), type)?.let { return it }
            }
        }
        return null
    }

    private fun navPageOf(launcher: LauncherView): NavPage =
        findByType(launcher, NavPage::class.java) ?: error("桌面上找不到导航页")

    /** 纯白封面。 */
    private fun whiteCover(): Bitmap =
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
        }

    // ------------------------------------------------------------------ 偏好本身

    @Test
    fun 封面背景开关默认是开的() {
        assertTrue(
            "默认必须是**开**（保持第146轮上线的现状：音乐卡卡面就是模糊封面）",
            prefs().navCoverBackdropEnabled,
        )
    }

    @Test
    fun 封面背景开关能存能读() {
        val p = prefs()
        p.navCoverBackdropEnabled = false
        assertFalse("写进去的 false 读回来必须是 false", prefs().navCoverBackdropEnabled)
        p.navCoverBackdropEnabled = true
        assertTrue("写进去的 true 读回来必须是 true", prefs().navCoverBackdropEnabled)
    }

    // ------------------------------------------------------------------ 入口

    @Test
    fun 封面图上挂着的点击监听真的能切换开关() {
        val launcher = setupLauncher()
        val nav = navPageOf(launcher)

        val art = findByType(nav, AlbumArtView::class.java)
        assertNotNull("导航页里应该有封面图", art)
        assertTrue("封面图必须可点", art!!.isClickable)

        val before = nav.coverBackdropEnabled
        art.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "点封面图之后开关应该翻转（之前 $before，之后 ${nav.coverBackdropEnabled}）",
            nav.coverBackdropEnabled != before,
        )
    }

    @Test
    fun 点封面图会把状态落盘() {
        val launcher = setupLauncher()
        val nav = navPageOf(launcher)
        val art = findByType(nav, AlbumArtView::class.java)!!

        art.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "切换后磁盘上的状态必须和内存里的一致",
            nav.coverBackdropEnabled,
            prefs().navCoverBackdropEnabled,
        )
    }

    @Test
    fun 连续点两次会回到原来的状态() {
        val launcher = setupLauncher()
        val nav = navPageOf(launcher)
        val art = findByType(nav, AlbumArtView::class.java)!!
        val before = nav.coverBackdropEnabled

        art.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        art.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("点两下应该回到原状态", before, nav.coverBackdropEnabled)
    }

    // ------------------------------------------------------------------ 回调

    @Test
    fun 切换会把新状态回调给宿主() {
        val launcher = setupLauncher()
        val nav = navPageOf(launcher)

        var seen: Boolean? = null
        nav.onCoverBackdropToggled = { seen = it }

        nav.toggleCoverBackdrop()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("宿主应该收到回调（否则用户点了不知道状态变了）", seen)
        assertEquals("回调参数必须是切换**之后**的状态", nav.coverBackdropEnabled, seen)
    }

    // ------------------------------------------------------------------ 冷启动记住状态

    /**
     * 第148轮最关键的一条：上次关掉的背景，**冷启动不能自己回来**。
     *
     * 这条守的是 Kotlin「属性初始化不走 setter」那个坑 ——
     * `NavPage.build()` 末尾必须显式同步一次状态。
     *
     * ## 为什么查的是 [NavPage.coverBackdropOn] 而不是 [NavPage.coverBackdropEnabled]
     *
     * `coverBackdropEnabled` 是**属性**（field），它的初值读自 `prefs`，**永远是对的**。
     * 真正会错的是**背景层**（`CoverBackdropDrawable.coverEnabled`）：
     * 少了 `build()` 末尾那次同步，背景层会停在构造时的 `true` ——
     * **属性说「关」、背景层说「开」**，背景照样显示。
     *
     * 只查属性的话，这条用例在 bug 存在时**照样绿**（第148轮第一版就是这么写的，
     * 反向验证时立刻被打脸）。所以必须查背景层的真实状态。
     */
    @Test
    fun 冷启动会记住上次关掉的状态() {
        prefs().navCoverBackdropEnabled = false

        val launcher = setupLauncher()
        val nav = navPageOf(launcher)

        assertFalse(
            "属性应该已经是关的",
            nav.coverBackdropEnabled,
        )
        assertFalse(
            "冷启动后**背景层**仍然是开的 —— 状态没从偏好灌进背景层，" +
                "用户上次关掉的背景自己回来了",
            nav.coverBackdropOn,
        )
    }

    @Test
    fun 冷启动默认状态是开的() {
        val launcher = setupLauncher()
        val nav = navPageOf(launcher)
        assertTrue("默认应该是开的", nav.coverBackdropEnabled)
        assertTrue("默认时背景层也应该是开的", nav.coverBackdropOn)
    }

    /** 切一下之后，属性和背景层必须**同时**翻面（不能一边动一边不动）。 */
    @Test
    fun 切换后属性和背景层保持一致() {
        val launcher = setupLauncher()
        val nav = navPageOf(launcher)
        val before = nav.coverBackdropOn

        nav.toggleCoverBackdrop()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "属性和背景层必须同步（不一致就是状态漏灌了）",
            nav.coverBackdropEnabled,
            nav.coverBackdropOn,
        )
        assertTrue("背景层应该跟着翻了面", nav.coverBackdropOn != before)
    }
}
