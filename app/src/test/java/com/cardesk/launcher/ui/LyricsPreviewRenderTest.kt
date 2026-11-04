package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.ui.pages.ScenePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * 首页中央大字歌词（第 113 轮：三行 → **五行**；第 114 轮：**块居中** + 字号可调）。
 *
 * 效果图：`preview/y27-lyrics-5lines.png`（桌面）、`preview/y27-lyrics-5lines-guide.png`（带导航引导卡）、
 * `preview/y28-lyrics-scale-140.png`（字号拉到 140%）。
 *
 * **断言不靠肉眼**：拿一张「没有歌词」的底图跟「有歌词」的图**逐像素差分**，
 * 把差分出来的行带数出来 —— 必须正好 **5 条**，
 * 且字号关系是「当前句 > 紧邻衬句 > 远衬句」，
 * 整块**居中**在 [ScenePage.LYRIC_BLOCK_CENTER_Y]、
 * 不压到底部卡片区，**有引导卡时歌词不画到引导卡上**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsPreviewRenderTest {

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 五行歌词（上 2 / 上 1 / 当前 / 下 1 / 下 2）；第一句特意写长，用来测引导卡避让。 */
    private val lines = listOf(
        "第一句 这是一句特别特别长的歌词用来测试会不会压到左上角的引导卡上面去",
        "第二句 风雨里追赶",
        "第三句 我们都曾勇敢地往前走",
        "第四句 直到看见远方的灯火",
        "第五句 请指引我靠近你",
        "第六句 是否星空的尽头有你",
    )

    @Test
    fun 渲染五行大字歌词() {
        val (decor, scenePage) = launch()
        val dir = File("../preview").apply { mkdirs() }

        // ① 底图（无引导卡、无歌词）
        val base = shot(decor)

        // ② 有歌词（仍无引导卡）→ 差分出 5 条行带
        scenePage.setLyricWindow(lines, 2)
        scenePage.setLyricWipeImmediate(0.42f)
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val withLyric = shot(decor)
        FileOutputStream(File(dir, "y27-lyrics-5lines.png")).use {
            withLyric.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // ③ 有引导卡、无歌词（底图）
        scenePage.setLyricWindow(emptyList(), -1)
        scenePage.setGuide(guide())
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val baseGuide = shot(decor)

        // ④ 引导卡 + 五行歌词（最挤的场景）
        scenePage.setLyricWindow(lines, 2)
        scenePage.setLyricWipeImmediate(0.42f)
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val withGuide = shot(decor)
        FileOutputStream(File(dir, "y27-lyrics-5lines-guide.png")).use {
            withGuide.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // ⑤ 差分出行带（只扫歌词区，避开状态栏时钟与右侧车况卡）
        val bands = diffBands(base, withLyric, x0 = 300, x1 = 1440, y0 = 200, y1 = 764)
        println("[113/114轮] 歌词行带: $bands")

        assertEquals("首页中央大字歌词应该正好 5 行", 5, bands.size)

        val far = bands[0]
        val near = bands[1]
        val main = bands[2]
        val near2 = bands[3]
        val far2 = bands[4]

        // —— 上下对称：上 2 / 上 1 与下 1 / 下 2 的高度应当一致 ——
        assertEquals("上 2 与下 2 高度应一致", far.last - far.first, far2.last - far2.first)
        assertEquals("上 1 与下 1 高度应一致", near.last - near.first, near2.last - near2.first)

        // —— 字号三档：当前句 > 紧邻衬句 > 远衬句 ——
        val mainH = main.last - main.first
        val nearH = near.last - near.first
        val farH = far.last - far.first
        assertTrue("当前句($mainH) 应该比紧邻衬句($nearH) 大", mainH > nearH)
        assertTrue("紧邻衬句($nearH) 应该比远衬句($farH) 大", nearH > farH)

        // —— 比第 106 轮的 30px 衬句明显大了一档 ——
        // （30px 中文字形的墨迹高度约 26px；这里远衬句是 34px → 墨迹 ≥ 28）
        assertTrue("远衬句($farH) 应该比原来的 30px 衬句更大", farH >= 28)
        assertTrue("紧邻衬句($nearH) 应该比原来的 30px 衬句更大", nearH >= 32)

        // —— 位置：整块**居中**（第 114 轮）——
        val center = (far.first + far2.last) / 2f
        assertTrue(
            "歌词块中心($center) 应该落在 ${ScenePage.LYRIC_BLOCK_CENTER_Y} 附近",
            abs(center - ScenePage.LYRIC_BLOCK_CENTER_Y) <= 14f,
        )

        // —— 位置：不压到底部卡片区 ——
        assertTrue(
            "最下面一行($far2) 不该压到底部卡片区（上沿 ${ScenePage.LYRIC_BOTTOM_CARD_TOP}）",
            far2.last < ScenePage.LYRIC_BOTTOM_CARD_TOP,
        )

        // —— 有引导卡时，歌词**不能画到引导卡上** ——
        val onGuide = diffBands(
            baseGuide,
            withGuide,
            x0 = 44,
            x1 = 664,
            y0 = 73,
            y1 = ScenePage.LYRIC_GUIDE_CARD_BOTTOM.toInt(),
        )
        assertTrue("歌词不该盖到左上引导卡上，实际差异行带 $onGuide", onGuide.isEmpty())
    }

    /**
     * 第 114 轮：字号拉到 140% 之后，歌词块**仍然居中**（位置不跟着字号漂）。
     */
    @Test
    fun 字号放大后仍然居中() {
        val (decor, scenePage) = launch()
        val dir = File("../preview").apply { mkdirs() }

        val base = shot(decor)

        scenePage.lyricScale = 1.4f
        scenePage.setLyricWindow(lines, 2)
        scenePage.setLyricWipeImmediate(0.42f)
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val big = shot(decor)
        FileOutputStream(File(dir, "y28-lyrics-scale-140.png")).use {
            big.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val bands = diffBands(base, big, x0 = 300, x1 = 1440, y0 = 150, y1 = 764)
        println("[114轮] 140% 行带: $bands")
        assertEquals("放大后还是 5 行", 5, bands.size)

        val center = (bands.first().first + bands.last().last) / 2f
        assertTrue(
            "140% 时歌词块中心($center) 仍然应该落在 ${ScenePage.LYRIC_BLOCK_CENTER_Y} 附近",
            abs(center - ScenePage.LYRIC_BLOCK_CENTER_Y) <= 16f,
        )
        // 放大后每行都更高
        val mainH = bands[2].last - bands[2].first
        assertTrue("140% 的当前句($mainH) 应该比 100% 时更高", mainH >= 70)
        // 还是不能压到底部卡片区
        assertTrue(
            "140% 时最下面一行(${
                bands.last()
            }) 不该压到底部卡片区",
            bands.last().last < ScenePage.LYRIC_BOTTOM_CARD_TOP,
        )
    }

    /** 起一个 Activity，量好尺寸，拿回场景页。 */
    private fun launch(): Pair<View, ScenePage> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()

        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val field = LauncherView::class.java.getDeclaredField("scenePage").apply { isAccessible = true }
        val scenePage = field.get(launcher) as ScenePage
        scenePage.showCenterLyric = true
        return decor to scenePage
    }

    /** 导航引导卡（带车道线 = 最高形态，下沿 387）。 */
    private fun guide(): com.cardesk.launcher.core.nav.DesktopGuide =
        com.cardesk.launcher.core.nav.NavGuideModel.build(
            icon = 1,
            segmentMeters = 320,
            currentRoad = "文景路",
            nextRoad = "",
            remainSeconds = 600,
            remainMeters = 8000,
            lanesRaw = "1,2,3|15,0,0",
        )

    /** 截一张 1920×1080 的图。 */
    private fun shot(decor: View): Bitmap {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        return bmp
    }

    /**
     * 两张图的差分 → 连续变化的行带（每条行带 = 一行文字）。
     *
     * 只看指定矩形，且颜色差超过阈值才算变化（抗锯齿边缘不误判）。
     */
    private fun diffBands(a: Bitmap, b: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): List<IntRange> {
        val changed = BooleanArray(y1 - y0)
        for (y in y0 until y1) {
            var hit = false
            for (x in x0 until x1) {
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
            changed[y - y0] = hit
        }
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (i in changed.indices) {
            if (changed[i] && start < 0) start = i
            if (!changed[i] && start >= 0) {
                bands.add((start + y0) until (i + y0))
                start = -1
            }
        }
        if (start >= 0) bands.add((start + y0) until y1)
        return bands
    }
}
