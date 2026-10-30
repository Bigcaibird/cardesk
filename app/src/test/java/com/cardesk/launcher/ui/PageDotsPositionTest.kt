package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.ui.shell.PageDotsView
import com.cardesk.launcher.ui.shell.ShellStatusBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 翻页指示圆点的位置（**第八十二轮：从 Dock 上方挪到状态栏正中**）。
 *
 * 覆盖三件事：
 *
 * 1. **画在状态栏正中**：圆点玻璃条落在状态栏那一行（`ROW_TOP` ~ `ROW_TOP + ROW_H`）
 *    的水平正中，而不是停在 Dock 上方。
 * 2. **Dock 上方空出来了**：老位置不再有内容。
 * 3. **还能点**：挪位置之后点圆点仍然切页（命中区跟着重算了）。
 *
 * ---
 * **为什么几何不写死一个数**
 *
 * 圆点所在的那一行就是状态栏那一行，行高/顶边距由 [ShellStatusBar.ROW_TOP] /
 * [ShellStatusBar.ROW_H] 决定 —— 这两个常量以后再改，圆点必须自动跟着走，
 * 所以这里**引用常量**而不是抄一份数值（抄一份早晚会不同步）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageDotsPositionTest {

    private fun newDots(count: Int = 2): PageDotsView {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        return PageDotsView(activity).apply { this.count = count }
    }

    private fun render(view: View): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 统计一块区域里「画了内容」的像素数（RGB 不全为 0）。 */
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

    @Test
    fun `圆点画在状态栏那一行的正中`() {
        val dots = newDots()
        val bmp = render(dots)

        val rowTop = ShellStatusBar.ROW_TOP.toInt()
        val rowBottom = (ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H).toInt()
        // 正中一小段（水平居中 ±60）
        val mid = content(bmp, 960 - 60, rowTop, 960 + 60, rowBottom)
        assertTrue("状态栏正中应该有翻页圆点，实际 $mid", mid > 0)
    }

    @Test
    fun `圆点玻璃条不会越出状态栏正中那一段`() {
        val dots = newDots()
        val bmp = render(dots)

        // 玻璃条整体落在 x 900~1020（宽 88，水平居中 ⇒ 916~1004）——
        // 这个区间是「位置 + 天气」那一段的左边界要避开的区域。
        assertTrue("玻璃条左边界应在 916 附近", content(bmp, 912, 20, 920, 58) > 0)
        assertTrue("玻璃条右边界应在 1004 附近", content(bmp, 1000, 20, 1008, 58) > 0)
        assertEquals("玻璃条左边不该有东西", 0, content(bmp, 900, 20, 912, 58))
        assertEquals("玻璃条右边不该有东西", 0, content(bmp, 1008, 20, 1020, 58))
    }

    @Test
    fun `Dock 上方的老位置不再有圆点`() {
        val dots = newDots()
        val bmp = render(dots)

        // 老位置：`Design.H - 8(Dock 距底) - 104(Dock 高) - 8(间距)` = 960 附近
        val old = content(bmp, 900, 928, 1020, 960)
        assertEquals("Dock 上方应该空出来了，实际 $old", 0, old)
    }

    @Test
    fun `挪到状态栏后点击圆点仍然能切页`() {
        val dots = newDots(count = 2)
        render(dots) // 先画一次：命中区是在 onDraw 里算出来的

        var target = -1
        dots.onDot = { target = it }

        // 未选中那个点（index=1）：在玻璃条里靠右一点
        val x = 979f
        val y = ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H / 2f
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        dots.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(0L, 20L, MotionEvent.ACTION_UP, x, y, 0)
        dots.dispatchTouchEvent(up)
        up.recycle()

        assertEquals("点未选中的圆点应该切到第 2 页", 1, target)
    }

    @Test
    fun `点击空白处不切页`() {
        val dots = newDots(count = 2)
        render(dots)

        var fired = false
        dots.onDot = { fired = true }

        val x = 400f
        val y = ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H / 2f
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        dots.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(0L, 20L, MotionEvent.ACTION_UP, x, y, 0)
        dots.dispatchTouchEvent(up)
        up.recycle()

        assertTrue("状态栏里点空白不该切页", !fired)
    }
}
