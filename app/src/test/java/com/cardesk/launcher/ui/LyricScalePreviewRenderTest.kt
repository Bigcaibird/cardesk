package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.cardesk.launcher.core.media.LyricLayout
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
 * 第 115 轮：设置页里的**歌词预览条**（第 117 轮：跟布局联动）。
 *
 * 预览条跟桌面中央那块歌词走**同一份排版**（`LyricBlockLayout` + `LyricLayout`），
 * 所以断言的是「预览里看到的就是桌面上会出现的」：
 * 行数跟布局一致、上下对称、缩放后仍然居中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricScalePreviewRenderTest {

    private val lines3 = listOf(
        "第一句 夜空中最亮的星",
        "第二句 风雨里追赶",
        "第三句 我们都曾勇敢地往前走",
    )

    /** KTV 两行的行序 = 当前句在上、下一句在下。 */
    private val lines2 = listOf(
        "当前句 我们都曾勇敢地往前走",
        "下一句 直到看见远方的灯火",
    )

    @Test
    fun 预览条画出三行歌词() {
        val bands = bands(preview(1f, LyricLayout.THREE, lines3), blank())
        println("[117轮] 三行预览行带: $bands")
        assertEquals("三行布局的预览应该正好 3 行", 3, bands.size)
        // 上下对称（上面一行与下面一行同字号）
        assertEquals(bands[0].last - bands[0].first, bands[2].last - bands[2].first)
    }

    @Test
    fun 预览条画出KTV两行歌词() {
        val bands = bands(preview(1f, LyricLayout.KTV, lines2), blank())
        println("[117轮] KTV 两行预览行带: $bands")
        assertEquals("KTV 布局的预览应该正好 2 行", 2, bands.size)

        // —— 两句一样大（用户要求：不靠字号区分前后句）——
        val top = bands[0].last - bands[0].first
        val bottom = bands[1].last - bands[1].first
        assertTrue(
            "KTV 两行应该一样高（上行 $top / 下行 $bottom）",
            abs(top - bottom) <= 2,
        )
    }

    @Test
    fun 预览条放大后仍然居中() {
        val normal = bands(preview(1f, LyricLayout.THREE, lines3), blank())
        val big = bands(preview(1.4f, LyricLayout.THREE, lines3), blank())
        println("[117轮] 三行 140% 行带: $big")
        assertEquals("放大后还是 3 行", 3, big.size)

        // 放大后当前句更高
        assertTrue(
            "140% 的当前句应该更高",
            (big[1].last - big[1].first) > (normal[1].last - normal[1].first),
        )

        // 整块仍然居中（预览 View 高 430，中心 215）
        val center = (big.first().first + big.last().last) / 2f
        assertTrue("140% 时块中心($center) 应该还在 215 附近", abs(center - 215f) <= 16f)
    }

    @Test
    fun 预览条没有歌词时不画字() {
        val view = preview(1f, LyricLayout.THREE, emptyList())
        val p = view.getPixel(480, 60)
        val q = view.getPixel(480, 300)
        assertEquals("空歌词时整块应该是纯底色", p, q)
    }

    /** 建一个预览条 View 并画到 960×430 的位图上。 */
    private fun preview(scale: Float, layout: LyricLayout, lines: List<String>): Bitmap {
        return draw(LyricScalePreviewView(RuntimeEnvironment.getApplication()).apply {
            this.scale = scale
            this.layout = layout
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
