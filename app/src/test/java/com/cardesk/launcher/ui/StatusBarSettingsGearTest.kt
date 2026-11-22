package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.cardesk.launcher.core.Glass
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
 * 第 300 轮设计改造：入口从「强调色实心方块 + 深色齿轮」改成
 * **「玻璃方块 + ink.text 齿轮」**（这一行不再有任何饱和色块）。
 * 几何没变 —— 方块仍是 34×34、齿轮仍是 8 齿、仍居中。
 *
 * ## 为什么用像素断言，不用肉眼
 *
 * 「画出来没有」「是不是齿轮」都不能靠看一眼 —— 断言要钉的是**形状**：
 *
 * - **齿轮身是一整圈**：中心孔半径 [ShellStatusBar.AVATAR_GEAR_HOLE_R]=2.6、
 *   齿根半径 [ShellStatusBar.AVATAR_GEAR_BODY_R]=6.2，取两者之间
 *   （`BODY_R * 0.65` ≈ 4.0）画一圈 36 个采样点，应该**全都是齿轮的墨**。
 *   原来的字母「C」在**右边是开口**的 —— 那一段没有墨，采样点会缺一大片。
 * - **8 个齿是真的凸出来**：在齿根与齿尖之间的半径
 *   （`(BODY_R + R) / 2` ≈ 7.4）上，8 个齿角方向**必须有墨**、
 *   8 个齿间方向（+22.5°）**必须没有墨**。实心圆盘会在齿间也有墨，
 *   字母「C」则连齿角都没有 —— 这一条同时排除这两种冒充者。
 * - **中心是空的**：齿轮中心孔用 EVEN_ODD 挖空，所以正中心是**方块底色**，
 *   不是墨。
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
        // 采样半径落在「中心孔(2.6) ~ 齿根(6.2)」之间的实心带上。
        var ringHit = 0
        for (i in 0 until 36) {
            val deg = i * 10.0
            val rad = Math.toRadians(deg)
            val x = (cx + RING_R * cos(rad)).roundToInt()
            val y = (cy + RING_R * sin(rad)).roundToInt()
            if (isGearInk(bmp, x, y)) ringHit++
        }
        assertTrue(
            "齿轮身应该是一整圈：36 个采样点里只命中 $ringHit 个（字母 C 的右边是开口，会缺一大片）",
            ringHit >= 34,
        )

        // —— ② 齿尖 8 个方向有墨、齿间 8 个方向没墨 ——
        var toothHit = 0
        var valleyHit = 0
        for (i in 0 until 8) {
            val deg = i * 45.0
            if (inkAt(bmp, cx, cy, TOOTH_R, deg)) toothHit++
            if (inkAt(bmp, cx, cy, TOOTH_R, deg + 22.5)) valleyHit++
        }
        assertTrue(
            "8 个齿尖方向应该都有墨，实际只命中 $toothHit 个（字母 C 根本没有这么远的东西）",
            toothHit >= 7,
        )
        assertTrue(
            "齿间（+22.5°）应该是缺口、不该有墨，实际命中 $valleyHit 个（命中就说明是实心圆盘，不是齿轮）",
            valleyHit <= 1,
        )

        // —— ③ 中心孔是空的（挖空后透出方块底色）——
        assertTrue(
            "齿轮中心应该是空的（透出方块底色），不该是墨",
            !isGearInk(bmp, cx.roundToInt(), cy.roundToInt()),
        )
    }

    private fun inkAt(bmp: Bitmap, cx: Float, cy: Float, r: Float, deg: Double): Boolean {
        val rad = Math.toRadians(deg)
        return isGearInk(bmp, (cx + r * cos(rad)).roundToInt(), (cy + r * sin(rad)).roundToInt())
    }

    @Test
    fun `入口方块是玻璃底_命中区没变`() {
        val bmp = render()
        val left = ShellStatusBar.EDGE.roundToInt()
        val top = (ShellStatusBar.ROW_TOP + (ShellStatusBar.ROW_H - ShellStatusBar.AVATAR) / 2f).roundToInt()
        val right = left + ShellStatusBar.AVATAR.roundToInt()
        val bottom = top + ShellStatusBar.AVATAR.roundToInt()

        // 第300轮：方块底不再是强调色，而是和驾驶胶囊同一种玻璃面。
        // 取底部中间（在齿轮半径 8.6 之外、也避开顶部 38% 的高光）采样。
        val cx = (left + right) / 2
        val face = facePixel(bmp, cx, bottom - 3)
        val expected = Skin.overlayTop.surface
        assertTrue(
            "入口方块应该换成了玻璃面（palette ${Integer.toHexString(expected)}，" +
                "实测 ${Integer.toHexString(face)}）",
            sameFace(expected, face),
        )
        assertTrue(
            "玻璃底是半透明的，不该再是强调色实心块",
            isAccent(bmp, cx, bottom - 3).not(),
        )

        // 方块外不该有内容（右边是 12px 间隙，再往右才是驾驶胶囊）
        assertTrue(
            "方块右边不该有墨",
            !isGearInk(bmp, right + 3, top + ShellStatusBar.AVATAR.roundToInt() / 2),
        )
    }

    /** 采样得到的底板像素。 */
    private fun facePixel(bmp: Bitmap, x: Int, y: Int): Int = bmp.getPixel(x, y)

    /** 画出来的像素是不是 palette 那张面：alpha 逐字一致，RGB 允许 ±4 量化误差。 */
    private fun sameFace(expected: Int, actual: Int): Boolean {
        if ((expected ushr 24) != (actual ushr 24)) return false
        for (shift in intArrayOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            if (abs(e - a) > 4) return false
        }
        return true
    }

    /** 齿轮身上那条实心带的半径：中心孔 2.6 与齿根 6.2 之间。 */
    private val RING_R = ShellStatusBar.AVATAR_GEAR_BODY_R * 0.65f

    /** 齿的采样半径（齿根 6.2 与齿尖 8.6 之间）。 */
    private val TOOTH_R = (ShellStatusBar.AVATAR_GEAR_BODY_R + ShellStatusBar.AVATAR_GEAR_R) / 2f

    private fun render(): Bitmap {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        val bar = ShellStatusBar(activity)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        // 玻璃面是半透明的：清掉已发布的模糊壁纸，像素才只反映「面」本身。
        Glass.publish(null)
        bar.draw(Canvas(bmp))
        return bmp
    }

    /**
     * 这个像素是「齿轮墨」吗。
     *
     * 第300轮起齿轮是 `ink.text`（夜间近白、日间近黑）画在**半透明玻璃底**上，
     * 所以判据是「比玻璃底色更接近文字色」，而不是「比强调色更接近 accentInk」。
     */
    private fun isGearInk(bmp: Bitmap, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return false
        val p = bmp.getPixel(x, y)
        // 透明像素不算墨 —— 不透明之前算「离墨更近」，会把空白处也判成墨。
        if ((p ushr 24) == 0) return false
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return dist(r, g, b, Skin.overlayTop.text) < dist(r, g, b, Skin.overlayTop.surface)
    }

    /** 这个像素还是强调色实心块吗（用来证明旧底色已经没了）。 */
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
