package com.cardesk.launcher

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Wallpapers
import com.cardesk.launcher.ui.LauncherView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 第207轮：**壁纸轮播**的设备级验证。
 *
 * 覆盖：
 * 1. 静态壁纸轮播：开着时，到点自动切到下一张；
 * 2. 关掉轮播后不再自动切。
 */
@RunWith(AndroidJUnit4::class)
class WallpaperFeatureDeviceTest {

    private fun launch(): Pair<LauncherView, View> {
        val instr = InstrumentationRegistry.getInstrumentation()
        val intent = android.content.Intent(instr.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity: Activity = instr.startActivitySync(intent)
        instr.waitForIdleSync()
        val decor = activity.window.decorView
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline &&
            (!launcher!!.isShown || launcher.width <= 0 || launcher.height <= 0)
        ) {
            Thread.sleep(100)
        }
        instr.waitForIdleSync()
        return launcher!! to decor
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 桌面当前的壁纸下标（反射读 BackgroundView 的字段）。 */
    private fun currentWallpaper(launcher: LauncherView): Int {
        val bgField = LauncherView::class.java.getDeclaredField("background")
        bgField.isAccessible = true
        val bg = bgField.get(launcher)
        val f = bg.javaClass.getDeclaredField("wallpaper")
        f.isAccessible = true
        return f.getInt(bg)
    }

    @Test
    fun `静态壁纸轮播到点自动切下一张`() {
        val (launcher, _) = launch()
        val prefs = Prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        prefs.wallpaperCarousel = true
        // 最短间隔 15 秒
        prefs.wallpaperCarouselIntervalSec = 15
        val before = currentWallpaper(launcher)
        println("[WP] 轮播前壁纸下标 = $before")

        // 轮播心跳是 1 秒一跳，等它切
        val deadline = SystemClock.uptimeMillis() + 25_000
        var after = before
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(1000)
            after = currentWallpaper(launcher)
            if (after != before) break
        }
        println("[WP] 轮播后壁纸下标 = $after")
        assertEquals(
            "轮播应该自动切到下一张（$before → ${Wallpapers.next(before)}）",
            Wallpapers.next(before),
            after,
        )
        prefs.wallpaperCarousel = false
    }

    @Test
    fun `关掉轮播后不再自动切`() {
        val (launcher, _) = launch()
        val prefs = Prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        prefs.wallpaperCarousel = false
        val before = currentWallpaper(launcher)
        Thread.sleep(18_000)
        assertEquals("轮播关着就不该切壁纸", before, currentWallpaper(launcher))
    }
}
