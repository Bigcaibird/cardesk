package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 卡片风格（第五十八轮）的**效果图**。
 *
 * 产物：
 * - `preview/w04-cardstyle-page.png` —— 设置页「卡片风格」页；
 * - `preview/w05-home-cardstyle.png` —— 桌面首页在「半透明 + 圆角 12」下的实际观感。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardStylePreviewRenderTest {

    private val dir = File("../preview").apply { mkdirs() }

    @Test
    fun 渲染卡片风格设置页() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        layout(decor)

        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        call(launcher!!, "showSettings")
        shadowOf(Looper.getMainLooper()).idle()
        val overlay = findField<SettingsOverlay>(launcher, "settings")
        assertNotNull("设置页应该打开了", overlay)
        overlay!!.showPageKey("cardstyle")
        shadowOf(Looper.getMainLooper()).idle()

        shot(decor, "w04-cardstyle-page.png")

        // 页内容比可视区高，「卡片圆角」那一行在折叠线以下 —— 再滚一张底部的。
        val scroll = findField<android.widget.ScrollView>(overlay, "bodyScroll")
        assertNotNull("设置页应该有个正文滚动容器", scroll)
        scroll!!.scrollTo(0, scroll.getChildAt(0).height)
        shadowOf(Looper.getMainLooper()).idle()
        shot(decor, "w06-cardstyle-page-radius.png")
    }

    @Test
    fun 渲染半透明卡片下的桌面首页() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        // 先把风格写好，再让桌面起来
        val prefs = Prefs(activity.applicationContext)
        prefs.cardOpacity = 55
        prefs.cardRadius = 12
        prefs.cardColor = 0
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        call(launcher!!, "applyCardStyle")
        shadowOf(Looper.getMainLooper()).idle()
        shot(decor, "w05-home-cardstyle.png")
    }

    /**
     * **日间**的「卡片风格」设置页。
     *
     * 日夜是同一套 token、两套值，所以卡片风格在日间也得单独出一组图。
     * 强制进日间：在 Activity 起来**之前**把 `daynight_applied_night_ui` 写成 `false`
     * （对齐 `DayNight.resolveNightUi` —— `daynight_enabled` 默认关闭，
     * 走「上次应用的夜间态」。
     */
    @Test
    fun 渲染日间卡片风格设置页() {
        Prefs(RuntimeEnvironment.getApplication()).setLastAppliedNight(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        assertFalse("这一组应该渲成日间", com.cardesk.launcher.core.Skin.isNight)
        call(launcher!!, "showSettings")
        shadowOf(Looper.getMainLooper()).idle()
        val overlay = findField<SettingsOverlay>(launcher, "settings")
        assertNotNull("设置页应该打开了", overlay)
        overlay!!.showPageKey("cardstyle")
        shadowOf(Looper.getMainLooper()).idle()
        shot(decor, "w07-cardstyle-page-day.png")
        val scroll = findField<android.widget.ScrollView>(overlay, "bodyScroll")
        assertNotNull("设置页应该有个正文滚动容器", scroll)
        scroll!!.scrollTo(0, scroll.getChildAt(0).height)
        shadowOf(Looper.getMainLooper()).idle()
        shot(decor, "w08-cardstyle-page-day-radius.png")
    }

    /**
     * **第二页**（导航页）套用卡片风格后的观感 —— 夜间 / 日间各一张。
     *
     * 第二页的合并卡是**构建时**设置背景的（`NavPage.buildCombinedCard`），
     * 所以它走的是 `NavPage.applyCardStyle()` 那条单独刷新的路 ——
     * 这两张图就是用来验证那条路真的生效了。
     */
    @Test
    fun 渲染夜间第二页卡片风格() {
        renderNavWithCardStyle(day = false, name = "w09-nav-cardstyle-night.png")
    }

    @Test
    fun 渲染日间第二页卡片风格() {
        renderNavWithCardStyle(day = true, name = "w10-nav-cardstyle-day.png")
    }

    /** 日间首页套用卡片风格的观感。 */
    @Test
    fun 渲染日间首页卡片风格() {
        Prefs(RuntimeEnvironment.getApplication()).setLastAppliedNight(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val prefs = Prefs(activity.applicationContext)
        prefs.cardOpacity = 55
        prefs.cardRadius = 12
        prefs.cardColor = 0
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        assertFalse("这一组应该渲成日间", com.cardesk.launcher.core.Skin.isNight)
        call(launcher!!, "applyCardStyle")
        shadowOf(Looper.getMainLooper()).idle()
        shot(decor, "w11-home-cardstyle-day.png")
    }

    private fun renderNavWithCardStyle(day: Boolean, name: String) {
        if (day) Prefs(RuntimeEnvironment.getApplication()).setLastAppliedNight(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val prefs = Prefs(activity.applicationContext)
        prefs.cardOpacity = 55
        prefs.cardRadius = 12
        prefs.cardColor = 0
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        assertEquals("日夜态跟渲染目标对不上", !day, com.cardesk.launcher.core.Skin.isNight)
        call(launcher!!, "applyCardStyle")
        shadowOf(Looper.getMainLooper()).idle()
        // 第二页 = 导航页（showPage(1)）
        applyNavMediaFixture(launcher)
        call(launcher, "showPage", 1)
        shadowOf(Looper.getMainLooper()).idle()
        shot(decor, name)
    }

    /**
     * 导航页音乐卡的**设计板同款数据**（跟 一致）：
     * 小镇姑娘 / 陶喆 / 1:24 / 4:05 + 三行歌词。
     *
     * 不灌这套数据的话第二页是「暂无播放」空态，看不出卡片风格的效果。
     */
    private fun applyNavMediaFixture(launcher: LauncherView) {
        findField<com.cardesk.launcher.ui.pages.NavPage>(launcher, "navPage")?.apply {
            sourceLabel = "通用音乐"
            musicState = "已暂停"
            trackTitle = "小镇姑娘"
            trackArtist = "陶喆"
            artwork = null
            playing = false
            positionText = "1:24"
            durationText = "4:05"
            progress = 0.34f
            setLyricWindow(
                listOf(
                    "我看见 夜空中 最亮的星",
                    "风雨里追赶 雾里分不清影踪",
                    "请指引我靠近你",
                    "是否 星空的尽头 有你",
                ),
                0,
            )
        }
    }

    private fun layout(decor: View) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun shot(decor: View, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private inline fun <reified T> findField(target: Any, name: String): T? {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as? T
    }

    private fun call(target: Any, method: String, vararg args: Any?): Any? {
        val types = args.map {
            when (it) {
                is Int -> Int::class.java
                is Boolean -> Boolean::class.java
                is String -> String::class.java
                else -> Any::class.java
            }
        }.toTypedArray()
        val m = LauncherView::class.java.getDeclaredMethod(method, *types)
        m.isAccessible = true
        return m.invoke(target, *args)
    }
}
