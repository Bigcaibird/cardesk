package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.widget.OverlayBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第 124 轮：设置页**主体外的那圈留白**改成透出下面的桌面。
 *
 * 用户原话：
 *
 * > 设置页主体外的留白不会透到桌面吗？把它改成透到桌面
 *
 * ## 怎么验「透出桌面」
 *
 * 断言「某个像素是不是桌面上的某个颜色」没有意义 —— 桌面背景本身就有渐变。
 * 这里换了个更硬的办法：
 *
 * **在背景板底下垫一块纯红**，然后渲染背景板，看红色有没有透上来。
 *
 * - 透出模式下 → 红分量应该**压倒**绿蓝（底下那块红很明显）；
 * - 不透明模式下 → 红分量应该几乎为 0（桌面被 `.bgwash` + `.scrim` 挡死）。
 *
 * 这样断言的是「桌面透不透得过来」这件事本身，而不是某张设计稿上的某个色值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSeeThroughTest {

    @Test
    fun `透出模式下_桌面能透过来`() {
        val bmp = render(seeThrough = true)
        // 取背景板正中（面板之外的位置：面板左缘 240，这里取 60）
        val p = bmp.getPixel(60, 540)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val redShare = r.toFloat() / (r + g + b).coerceAtLeast(1)
        assertTrue(
            "底下的红色应该透上来，实际像素 rgb=($r,$g,$b)，红色占比只有 $redShare",
            redShare > 0.7f,
        )
    }

    @Test
    fun `不透明模式下_桌面完全看不见`() {
        val bmp = render(seeThrough = false)
        val p = bmp.getPixel(60, 540)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        // `.bgwash` 夜间底色 0x0F1116 + `.scrim` 0xA808090C —— 红最多也就十几。
        assertTrue(
            "不透明时桌面不该透上来，实际像素 rgb=($r,$g,$b)",
            r < 40 && r <= g,
        )
    }

    @Test
    fun `面板主体是不透明的_桌面透不过去`() {
        val bmp = renderWithPanel(seeThrough = true)
        // 面板盖在 (300..600, 300..400) —— 那里应该只有面板自己的颜色。
        val p = bmp.getPixel(450, 350)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        // 面板底色 = palette.surface = 0x1F232D（夜间），偏冷、亮度低。
        // 底下那块红要是透上来，r 会明显偏大。
        assertTrue(
            "面板主体应该挡住桌面，实际像素 rgb=($r,$g,$b)",
            r < 60 && r <= g,
        )
    }

    @Test
    fun `设置页的背景板确实开了透出`() {
        // 防回退：以后谁把 seeThrough 关掉，这里会红。
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        val overlay = SettingsOverlay(activity, Prefs(activity), Palettes.night)
        val f = SettingsOverlay::class.java.getDeclaredField("scrimView")
        f.isAccessible = true
        val scrim = f.get(overlay)
        assertTrue("背景板应该是 OverlayBackdrop，实际是 $scrim", scrim is OverlayBackdrop)
        assertTrue("设置页背景板应该透出桌面", (scrim as OverlayBackdrop).seeThrough)
    }

    @Test
    fun `透出率是算得出来的`() {
        // 纯 Kotlin：桌面剩下的比例 = (1 - bgAlpha) × (1 - scrimAlpha)
        assertEquals(1f, OverlaySpec.throughRate(0, 0), 0.001f)
        assertEquals(0f, OverlaySpec.throughRate(255, 255), 0.001f)
        // 半透明底 + 半透明遮罩 → 各自挡一半
        assertEquals(0.25f, OverlaySpec.throughRate(128, 128), 0.01f)
        // 夜间实际值：底 0x4D、遮罩 0xA8 缩一半 → 0x54
        val nightRate = OverlaySpec.throughRate(
            OverlaySpec.THROUGH_BG_ALPHA,
            ((0xA8 * OverlaySpec.THROUGH_SCRIM_SCALE) + 0.5f).toInt(),
        )
        assertTrue("夜间透出率应该在 40%~60% 之间，实际 $nightRate", nightRate in 0.40f..0.60f)
    }

    // ---------------------------------------------------------------

    /** 渲染：底下垫一块纯红，上面盖背景板。 */
    private fun render(seeThrough: Boolean): Bitmap {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val under = View(activity)
        under.setBackgroundColor(0xFFFF0000.toInt())
        root.addView(under, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val backdrop = OverlayBackdrop(activity).apply {
            palette = Palettes.night
            withScrim = true
            this.seeThrough = seeThrough
        }
        root.addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return draw(root)
    }

    /** 渲染：纯红打底 → 半透明背景板 → 一张不透明面板卡。 */
    private fun renderWithPanel(seeThrough: Boolean): Bitmap {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val under = View(activity)
        under.setBackgroundColor(0xFFFF0000.toInt())
        root.addView(under, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val backdrop = OverlayBackdrop(activity).apply {
            palette = Palettes.night
            withScrim = true
            this.seeThrough = seeThrough
        }
        root.addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 面板卡：不透明，模拟设置页主体（Skin.flat(Shape.CARD) = palette.surface）
        val panel = View(activity)
        panel.setBackgroundColor(Palettes.night.surface)
        root.addView(
            panel,
            FrameLayout.LayoutParams(600, 400).apply {
                leftMargin = 300
                topMargin = 300
            },
        )
        return draw(root)
    }

    private fun draw(root: View): Bitmap {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        return bmp
    }
}
