package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 第214轮：**壁纸层「让路」**的行为测试。
 *
 * ## 覆盖的那个坑：让路了但壁纸还留在屏上
 *
 * 第213轮实测时发现：切到视频壁纸后，截图里的静态壁纸**原封不动**。
 *
 * 根因：`BackgroundView.videoPassthrough` 原来是个**普通 `var`**，
 * 改成 `true` 之后这一层**不会自己重绘** —— 屏幕上留着上一帧的壁纸，
 * 看着就像「视频壁纸没生效」。
 *
 * 第214轮把它改成带 `invalidate()` 的 setter。
 *
 * ## 断言为什么直接查像素
 *
 * `videoPassthrough = true` 的语义是「这一层**什么都不画**」——
 * 画布必须**完全透明**（视频要从窗口 buffer 底下透上来）。
 * 光断言「调用了 invalidate」不够：万一以后有人在 `onDraw` 开头
 * 加了一行 `drawColor(...)`，invalidate 照样发生，但画面又被挡住了。
 * 所以这里直接画一张位图，逐像素查 alpha。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class BackgroundViewPassthroughTest {

    private fun newView(): BackgroundView =
        BackgroundView(RuntimeEnvironment.getApplication())

    private fun drawToBitmap(view: View): Bitmap {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 320, 180)
        view.draw(Canvas(bmp))
        return bmp
    }

    @Test
    fun `正常状态壁纸层会画出东西`() {
        val bmp = drawToBitmap(newView())
        var opaque = 0
        for (y in 0 until 180 step 8) {
            for (x in 0 until 320 step 8) {
                if (bmp.getPixel(x, y) ushr 24 != 0) opaque++
            }
        }
        assertTrue("壁纸层本该画出内容，实际不透明像素=$opaque", opaque > 0)
    }

    @Test
    fun `让路之后一个像素都不画`() {
        val view = newView()
        view.videoPassthrough = true
        val bmp = drawToBitmap(view)
        var opaque = 0
        for (y in 0 until 180) {
            for (x in 0 until 320) {
                if (bmp.getPixel(x, y) ushr 24 != 0) opaque++
            }
        }
        assertEquals("让路之后画布必须完全透明", 0, opaque)
    }

    @Test
    fun `让路会触发重绘`() {
        val view = newView()
        val before = view.invalidateCountForTest
        view.videoPassthrough = true
        assertTrue(
            "videoPassthrough 改了必须 invalidate，否则旧壁纸留在屏上",
            view.invalidateCountForTest > before,
        )
    }

    @Test
    fun `让路状态没变就不重绘`() {
        val view = newView()
        view.videoPassthrough = true
        val before = view.invalidateCountForTest
        view.videoPassthrough = true
        assertTrue("同一个值反复设置不该触发重绘", view.invalidateCountForTest == before)
    }
}
