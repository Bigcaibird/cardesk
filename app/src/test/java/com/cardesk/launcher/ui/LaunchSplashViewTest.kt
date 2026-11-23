package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第 256 轮：启动页日/夜双版（画板 `b01-boot-day` / `b02-boot-night`）。
 *
 * 背景是画板烘焙的 960×540 PNG，这里直接比**像素**：画板左上/右上/顶部中线的取色
 * 与实机截图同一口径，避免「代码看起来对、观感不对」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchSplashViewTest {

    private fun render(night: Boolean): Bitmap {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.dayNightEnabled = false
        prefs.setLastAppliedNight(night)
        val view = LaunchSplashView(ctx)
        assertEquals("启动页配色没跟随 lastAppliedNightUi", night, view.night)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1920, 1080)
        // 第一帧带 200ms 的深色淡入：Robolectric 里 `SystemClock.uptimeMillis()` 不会自己走，
        // 必须显式推进时钟，否则取到的永远是覆盖着深色遮罩的第一帧。
        view.draw(canvas)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
            java.time.Duration.ofMillis(LaunchSplashView.REVEAL_MS.toLong() + 60),
        )
        view.draw(canvas)
        return bmp
    }

    private fun assertNear(what: String, expect: Int, actual: Int, tol: Int) {
        val de = Color.red(expect)
        val dg = Color.green(expect)
        val db = Color.blue(expect)
        val ar = Color.red(actual)
        val ag = Color.green(actual)
        val ab = Color.blue(actual)
        val ok = kotlin.math.abs(de - ar) <= tol && kotlin.math.abs(dg - ag) <= tol &&
            kotlin.math.abs(db - ab) <= tol
        assertTrue(
            "$what 取色不对：期望 ($de,$dg,$db)±$tol，实际 ($ar,$ag,$ab)",
            ok,
        )
    }

    @Test
    fun `日间背景取自画板 b01`() {
        val bmp = render(night = false)
        assertNear("左上青绿区", 0xFFD3EDEC.toInt(), bmp.getPixel(100, 100), 14)
        assertNear("右上青绿", 0xFF8FDAD6.toInt(), bmp.getPixel(1800, 100), 14)
        assertNear("顶部中线", 0xFFDCEFF1.toInt(), bmp.getPixel(960, 60), 14)
    }

    @Test
    fun `夜间背景取自画板 b02`() {
        val bmp = render(night = true)
        assertNear("左上", 0xFF06111D.toInt(), bmp.getPixel(100, 100), 6)
        assertNear("顶部冷光", 0xFF0D2231.toInt(), bmp.getPixel(960, 60), 8)
        assertNear("左下", 0xFF03060C.toInt(), bmp.getPixel(300, 900), 8)
    }

    @Test
    fun `圆环 品牌字 进度条都在画板位置`() {
        val bmp = render(night = true)
        val bg = bmp.getPixel(400, 540)
        // 环心 (960, 461)，r=66：正上方环带应明显亮于背景。
        val ring = bmp.getPixel(960, (461 - 66).coerceAtLeast(0))
        assertNotEquals("圆心正上方应该是圆环轨道/弧，不该与背景同色", bg, ring)
        // 品牌字墨迹：字带 (y 570..690) 里要有比背景亮的像素。
        var ink = 0
        for (y in 570 until 690) {
            for (x in 700 until 1220) {
                val c = bmp.getPixel(x, y)
                if (Color.red(c) + Color.green(c) + Color.blue(c) > 260) ink++
            }
        }
        assertTrue("品牌字没画出来（ink=$ink）", ink > 800)
    }

    @Test
    fun `节奏与几何常量对齐画板`() {
        assertEquals(120f, LaunchSplashView.ARC_SWEEP, 0.01f)
        assertEquals(1400L, LaunchSplashView.SPIN_MS)
        assertEquals(1400L, LaunchSplashView.BAR_MS)
        assertEquals(66f, LaunchSplashView.RING_R, 0.01f)
        assertEquals(320f, LaunchSplashView.BAR_W, 0.01f)
        assertEquals(62f, LaunchSplashView.WORDMARK_SP, 0.01f)
        // 日/夜两版的弧色都按画板给（日 teal→blue、夜 blue→teal）。
        assertEquals(0xFF00A9A0.toInt(), LaunchSplashView.arcColors(false).first)
        assertEquals(0xFF4FC3F7.toInt(), LaunchSplashView.arcColors(false).second)
        assertEquals(0xFF4FC3F7.toInt(), LaunchSplashView.arcColors(true).first)
        assertEquals(0xFF7BE0D6.toInt(), LaunchSplashView.arcColors(true).second)
    }
}
