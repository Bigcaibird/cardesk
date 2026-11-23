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

    private fun lum(c: Int): Double =
        0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)

    @Test
    fun `圆环在 66px 半径处且描边 6px 品牌字有墨迹`() {
        val bmp = render(night = true)
        // 环心在 x=960；沿竖直方向扫上半区最亮的行 = 环顶。
        var peakY = -1
        var peak = 0.0
        for (y in 250 until 560) {
            val l = lum(bmp.getPixel(960, y))
            if (l > peak) {
                peak = l
                peakY = y
            }
        }
        assertTrue("环顶没找到亮带", peakY > 0)
        val bg = lum(bmp.getPixel(120, 540))
        assertTrue("环顶亮度应明显高于背景：peak=$peak bg=$bg", peak - bg > 10)
        // 量描边**实心**部分：外侧还有一圈 2.6 倍的柔光，阈值放太低会把光晕算进带宽。
        val core = bg + (peak - bg) * 0.55
        var band = 0
        for (y in peakY - 12..peakY + 12) {
            if (lum(bmp.getPixel(960, y)) > core) band++
        }
        assertTrue("描边带宽应约 6px（实测 $band）", band in 3..14)

        // 品牌字墨迹：字带里要有比背景亮的像素。
        var ink = 0
        for (y in 560 until 700) {
            for (x in 700 until 1220) {
                if (lum(bmp.getPixel(x, y)) > bg + 60) ink++
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
        // 光带用强调色（画板 `var(--accent)`），不是弧的渐变端色 —— 第 256 轮验收的 P1 就是取错了这里。
        assertEquals(0xFF00A9A0.toInt(), LaunchSplashView.barSweepColor(false))
        assertEquals(0xFF16C2B2.toInt(), LaunchSplashView.barSweepColor(true))
        // 光带底轨两版都是 rgba(255,255,255,.10)。
        assertEquals(0x1AFFFFFF, LaunchSplashView.barTrackColor(false))
        assertEquals(0x1AFFFFFF, LaunchSplashView.barTrackColor(true))
        // 日/夜两版的弧色都按画板给（日 teal→blue、夜 blue→teal）。
        assertEquals(0xFF00A9A0.toInt(), LaunchSplashView.arcColors(false).first)
        assertEquals(0xFF4FC3F7.toInt(), LaunchSplashView.arcColors(false).second)
        assertEquals(0xFF4FC3F7.toInt(), LaunchSplashView.arcColors(true).first)
        assertEquals(0xFF7BE0D6.toInt(), LaunchSplashView.arcColors(true).second)
    }
}
