package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.cardesk.launcher.ui.shell.ShellStatusBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 状态栏「位置 + 天气」（第四轮新增）+ **沉浸式缩放**（第四十三轮）的单测。
 *
 * 覆盖四件事：
 *
 * 1. **数据口径**：[ShellStatusBar.setLocationWeather] 收下城市 / 天气 / 温度，
 *    城市会去掉首尾空格；重复同样的数据不会反复刷（避免日志噪音）。
 * 2. **画得出来**：中置那段（定位针 + 城市 + 分隔点 + 天气图标 + 温度）
 *    在 1920×1080 的正中位置真的有像素。
 * 3. **没数据也不崩**：城市为空显示「位置获取中」、温度为空显示 `--°`。
 * 4. **第四十三轮 / 第五十二轮 / 第六十三轮**：顶部渐变遮罩（沉浸式）+
 *    状态栏元素整体缩小（第四十三轮缩一档、第五十二轮按用户要求统一缩到 **2/3**），
 *    第六十三轮再按用户要求**把行内的字与图标放大一档**，
 *    但**状态栏这一行的大小（顶边距 / 行高 / 左右边距）一个字都不动**。
 *
 * ---
 * **为什么统计的是「内容像素」而不是「不透明像素」**
 *
 * 第四十三轮给状态栏加了一条**顶部渐变遮罩**（黑色 → 透明，96px 高）。
 * 这层遮罩铺满整个宽度，所以「这块区域有没有不透明像素」已经测不出
 * 「内容画没画在这里」了 —— 遮罩自己就贡献了一堆半透明像素。
 *
 * 遮罩是**纯黑**（RGB 全 0，只有 alpha 在变），而状态栏里的内容
 * （白字 / 蓝色头像 / 灰色分隔点）RGB 都不为 0。
 * 所以统计 **r+g+b > 0** 的像素 = 「真正画了内容的地方」，与遮罩无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarLocationWeatherTest {

    private fun newBar(): ShellStatusBar {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        return ShellStatusBar(activity)
    }

    private fun render(bar: ShellStatusBar): Bitmap {
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bmp))
        return bmp
    }

    /** 统计一块区域里「不透明」的像素数。 */
    private fun painted(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if ((bmp.getPixel(x, y) ushr 24) != 0) n++
            }
        }
        return n
    }

    /** 统计一块区域里「画了内容」的像素数（排除纯黑渐变遮罩）。 */
    private fun content(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) + Color.green(p) + Color.blue(p) > 0) n++
            }
        }
        return n
    }

    /** 单个像素上「画了内容」吗。 */
    private fun contentAt(bmp: Bitmap, x: Int, y: Int): Boolean {
        val p = bmp.getPixel(x, y)
        return Color.red(p) + Color.green(p) + Color.blue(p) > 0
    }

    @Test
    fun `位置天气数据能落到状态栏`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        assertEquals("北京市", bar.locationText)
        assertEquals("晴", bar.weatherSky)
        assertEquals(14, bar.weatherTemp)
    }

    @Test
    fun `城市首尾空格会被去掉`() {
        val bar = newBar()
        bar.setLocationWeather("  北京市  ", "晴", 14)
        assertEquals("北京市", bar.locationText)
    }

    @Test
    fun `重复同样的数据不会覆盖成新值`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        bar.setLocationWeather("北京市", "晴", 14)
        assertEquals("北京市", bar.locationText)
        assertEquals(14, bar.weatherTemp)
    }

    @Test
    fun `中置段真的画在屏幕正中`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        val bmp = render(bar)
        // 正中一小块（避开左边头像/胶囊、右边图标/时钟）
        val mid = content(bmp, 880, 30, 1060, 90)
        assertTrue("状态栏中置段应有内容像素，实际 $mid", mid > 0)
        // 正中往左一截（左半边是头像+胶囊，不该有这段内容）
        val left = content(bmp, 620, 30, 780, 90)
        assertTrue("中置段不该画到左边，实际 $left", left == 0)
    }

    @Test
    fun `没有数据时用占位文案且不崩`() {
        val bar = newBar()
        bar.setLocationWeather("", "", null)
        val bmp = render(bar)
        assertEquals("", bar.locationText)
        assertTrue(content(bmp, 880, 30, 1060, 90) > 0)
    }

    // ---------------------------------------------------------------
    // 第四十三轮：沉浸式 + 状态栏整体缩小一档
    // ---------------------------------------------------------------

    @Test
    fun `顶部不再有渐变遮罩`() {
        val bar = newBar()
        val bmp = render(bar)

        // 第四十九轮「浮屿」：状态栏**不再画顶部渐变遮罩**
        // （第四十三轮那个半透明黑渐变属于「沉浸式」时期的设计，已被本轮推翻）。
        assertEquals("顶部正中必须完全透明", 0, Color.alpha(bmp.getPixel(960, 0)))
        assertEquals("整条宽度都不能有遮罩", 0, Color.alpha(bmp.getPixel(5, 0)))
        // 空白区（驾驶模式胶囊右侧、位置胶囊左侧）也必须没有任何遮罩残留
        assertEquals("遮罩之下更不能有残留", 0, Color.alpha(bmp.getPixel(400, 48)))
    }

    // ---------------------------------------------------------------
    // 第六十三轮：行内元素（字 + 图标）放大一档，**行本身大小不动**
    // ---------------------------------------------------------------

    @Test
    fun `第六十三轮行高不变但字和图标更大`() {
        val bar = newBar()
        val bmp = render(bar)

        // 口径：状态栏这一行（band）一个字都不动 ——
        // 边距 29、顶边距 17、行高 43（行底 60）保持第五十二轮的值。
        assertEquals(29f, com.cardesk.launcher.ui.shell.ShellStatusBar.EDGE, 0.01f)
        assertEquals(17f, com.cardesk.launcher.ui.shell.ShellStatusBar.ROW_TOP, 0.01f)
        assertEquals(43f, com.cardesk.launcher.ui.shell.ShellStatusBar.ROW_H, 0.01f)

        // 头像 36 → 40，仍在行内垂直居中（y 18.5~58.5）
        assertEquals(40f, com.cardesk.launcher.ui.shell.ShellStatusBar.AVATAR, 0.01f)
        val avatarRows = (0 until 80).count { contentAt(bmp, 45, it) }
        assertTrue("头像应该占满约 40 行（40×40），实际 $avatarRows", avatarRows in 40..42)
        assertTrue("头像应该画在 x=29~69 / y=18~58", contentAt(bmp, 45, 38))
        assertFalse("头像不该横向超过 x=69", contentAt(bmp, 72, 38))
        assertFalse("头像不该纵向超过 y=58", contentAt(bmp, 45, 61))

        // 状态胶囊 29 → 34，从 x=81（29 + 40 + 12）开始，高 34（y 21.5~55.5）
        assertEquals(34f, com.cardesk.launcher.ui.shell.ShellStatusBar.CHIP_H, 0.01f)
        assertTrue("驾驶模式胶囊应该画在 x=81 之后", content(bmp, 82, 22, 300, 56) > 0)

        // 时钟 25 → 32：墨迹行数明显变多（字放大了）
        val clockRows = (0 until 80).count { y -> (1805..1885).any { contentAt(bmp, it, y) } }
        assertTrue("时钟字号应该放大（墨迹约 23 行），实际 $clockRows", clockRows >= 21)

        // 连接图标 17 → 22
        assertEquals(22f, com.cardesk.launcher.ui.shell.ShellStatusBar.ICON, 0.01f)
    }
}
