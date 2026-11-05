package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.shell.ShellStatusBar
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 第 123 轮：左上角那个入口从**字母「C」**改成**设置图标（齿轮）**。
 *
 * 用户原话：「把左上角的C字设置入口改成一个设置图标」。
 *
 * ## 为什么用像素断言，不用肉眼
 *
 * 「画出来没有」「是不是齿轮」都不能靠看一眼 —— 断言要钉的是**形状**：
 *
 * - **齿轮身是一整圈**：以头像中心为圆心、半径 6.5 的圆上，36 个采样点
 *   应该**全都是齿轮的墨**（中心孔半径 3.8、齿根半径 8，6.5 落在实心带上）。
 *   原来的字母「C」在**右边是开口**的 —— 那一段没有墨，采样点会缺一大片。
 * - **齿尖是 8 个尖角**：半径 10.5 的圆上、8 个齿角方向应该有墨。
 *   字母「C」根本没有这么远的东西（26px 粗体字母的墨最多到半径 7~8）。
 * - **中心是空的**：齿轮中心孔用 EVEN_ODD 挖空，所以正中心是**方块底色**，
 *   不是墨。
 *
 * 这三条合起来才认得出「齿轮」；只看「方块里有没有东西」会跟旧的「C」分不开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarSettingsGearTest {

    @Test
    fun `左上角入口画的是齿轮而不是字母C`() {
        val bmp = render()
        val cx = ShellStatusBar.EDGE + ShellStatusBar.AVATAR / 2f
        val cy = ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H / 2f

        // —— ① 齿轮身：一整圈都是墨 ——
        // 半径 6.5 落在「中心孔(3.8) ~ 齿根(8)」之间的实心带上。
        var ringHit = 0
        var ringTotal = 0
        for (i in 0 until 36) {
            val deg = i * 10.0
            val rad = Math.toRadians(deg)
            val x = (cx + RING_R * cos(rad)).roundToInt()
            val y = (cy + RING_R * sin(rad)).roundToInt()
            ringTotal++
            if (isInk(bmp, x, y)) ringHit++
        }
        assertTrue(
            "齿轮身应该是一整圈：36 个采样点里只命中 $ringHit 个（字母 C 的右边是开口，会缺一大片）",
            ringHit >= 32,
        )

        // —— ② 齿尖：8 个角方向上，半径 10.5 处也要有墨 ——
        var toothHit = 0
        for (i in 0 until 8) {
            val deg = i * 45.0
            val rad = Math.toRadians(deg)
            val x = (cx + TOOTH_R * cos(rad)).roundToInt()
            val y = (cy + TOOTH_R * sin(rad)).roundToInt()
            if (isInk(bmp, x, y)) toothHit++
        }
        assertTrue(
            "8 个齿尖应该都有墨，实际只命中 $toothHit 个（字母 C 根本没有这么远的东西）",
            toothHit >= 6,
        )

        // —— ③ 中心孔是空的（挖空后透出方块底色）——
        assertTrue(
            "齿轮中心应该是空的（透出方块底色），不该是墨",
            !isInk(bmp, cx.roundToInt(), cy.roundToInt()),
        )
    }

    @Test
    fun `入口方块本身没变_命中区也没变`() {
        val bmp = render()
        val left = ShellStatusBar.EDGE.roundToInt()
        val top = (ShellStatusBar.ROW_TOP + (ShellStatusBar.ROW_H - ShellStatusBar.AVATAR) / 2f).roundToInt()
        val right = left + ShellStatusBar.AVATAR.roundToInt()
        val bottom = top + ShellStatusBar.AVATAR.roundToInt()

        // 方块里面仍然是强调色（底板还在）。
        // 注意别取太靠角的位置 —— 圆角 13px 把四个角切掉了，
        // 取角内侧 8px 处才落在方块里。
        assertTrue("头像方块左上部应该是强调色底板", isAccent(bmp, left + 8, top + 8))
        assertTrue("头像方块右下部应该是强调色底板", isAccent(bmp, right - 9, bottom - 9))
        // 方块外不该有内容
        assertTrue(
            "方块右边不该有墨",
            !isInk(bmp, right + 3, top + ShellStatusBar.AVATAR.roundToInt() / 2),
        )
    }

    /** 齿轮身上那条实心带的半径。 */
    private val RING_R = 6.5f

    /** 齿尖采样半径（在齿根 8 与齿尖 11.5 之间）。 */
    private val TOOTH_R = 10.5f

    private fun render(): Bitmap {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        val bar = ShellStatusBar(activity)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bmp))
        return bmp
    }

    /** 这个像素是「齿轮墨」吗（比方块底色更接近墨色）。 */
    private fun isInk(bmp: Bitmap, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return false
        val p = bmp.getPixel(x, y)
        // 透明像素不算墨 —— 不透明之前算「离墨更近」，会把空白处也判成墨。
        if ((p ushr 24) == 0) return false
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val ink = Skin.ACCENT_INK
        val accent = Skin.ACCENT
        return dist(r, g, b, ink) < dist(r, g, b, accent)
    }

    /** 这个像素是方块底色（强调色）吗。 */
    private fun isAccent(bmp: Bitmap, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return false
        val p = bmp.getPixel(x, y)
        if ((p ushr 24) == 0) return false
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val ink = Skin.ACCENT_INK
        val accent = Skin.ACCENT
        return dist(r, g, b, accent) <= dist(r, g, b, ink)
    }

    private fun dist(r: Int, g: Int, b: Int, color: Int): Int {
        val dr = r - ((color shr 16) and 0xFF)
        val dg = g - ((color shr 8) and 0xFF)
        val db = b - (color and 0xFF)
        return abs(dr) + abs(dg) + abs(db)
    }
}
