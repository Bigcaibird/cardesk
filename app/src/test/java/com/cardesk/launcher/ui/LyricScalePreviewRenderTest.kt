package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.cardesk.launcher.ui.widget.LyricScalePreviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 第 115 轮：设置页里的**歌词预览条**。
 *
 * 预览条跟桌面中央那块歌词走**同一份排版**（`LyricBlockLayout`），
 * 所以断言的是「预览里看到的就是桌面上会出现的」：
 * 五行、上下对称、缩放后仍然居中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricScalePreviewRenderTest {

    private val lines = listOf(
        "第一句 夜空中最亮的星",
        "第二句 风雨里追赶",
        "第三句 我们都曾勇敢地往前走",
        "第四句 直到看见远方的灯火",
        "第五句 请指引我靠近你",
    )

    @Test
    fun 预览条画出五行歌词() {
        val bands = bands(preview(1f, lines), blank())
        println("[115轮] 预览行带: $bands")
        assertEquals("预览条应该正好 5 行", 5, bands.size)
        // 上下对称
        assertEquals(bands[0].last - bands[0].first, bands[4].last - bands[4].first)
        assertEquals(bands[1].last - bands[1].first, bands[3].last - bands[3].first)
    }

    @Test
    fun 预览条放大后仍然居中() {
        val normal = bands(preview(1f, lines), blank())
        val big = bands(preview(1.4f, lines), blank())
        println("[115轮] 预览 140% 行带: $big")
        assertEquals("放大后还是 5 行", 5, big.size)

        // 放大后每行更高
        assertTrue(
            "140% 的当前句应该更高",
            (big[2].last - big[2].first) > (normal[2].last - normal[2].first),
        )

        // 整块仍然居中（预览 View 高 430，中心 215）
        val center = (big.first().first + big.last().last) / 2f
        assertTrue("140% 时块中心($center) 应该还在 215 附近", abs(center - 215f) <= 16f)
    }

    @Test
    fun 预览条没有歌词时不画字() {
        val view = preview(1f, emptyList())
        val p = view.getPixel(480, 60)
        val q = view.getPixel(480, 300)
        assertEquals("空歌词时整块应该是纯底色", p, q)
    }

    /** 建一个预览条 View 并画到 960×430 的位图上。 */
    private fun preview(scale: Float, lines: List<String>): Bitmap {
        return draw(LyricScalePreviewView(RuntimeEnvironment.getApplication()).apply {
            this.scale = scale
            this.lyricLines = lines
        })
    }

    /** 只有底板、没有字的底图。 */
    private fun blank(): Bitmap {
        return draw(LyricScalePreviewView(RuntimeEnvironment.getApplication()).apply {
            lyricLines = emptyList()
        })
    }

    private fun draw(view: LyricScalePreviewView): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(430, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 960, 430)
        val bmp = Bitmap.createBitmap(960, 430, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 两张图的差分 → 连续变化的行带。 */
    private fun bands(a: Bitmap, b: Bitmap): List<IntRange> {
        val changed = BooleanArray(430)
        for (y in 0 until 430) {
            var hit = false
            for (x in 0 until 960) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                val dr = abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
                val dg = abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
                val db = abs((p and 0xFF) - (q and 0xFF))
                if (dr + dg + db > 60) {
                    hit = true
                    break
                }
            }
            changed[y] = hit
        }
        val out = mutableListOf<IntRange>()
        var start = -1
        for (i in changed.indices) {
            if (changed[i] && start < 0) start = i
            if (!changed[i] && start >= 0) {
                out.add(start until i)
                start = -1
            }
        }
        if (start >= 0) out.add(start until 430)
        return out
    }
}
