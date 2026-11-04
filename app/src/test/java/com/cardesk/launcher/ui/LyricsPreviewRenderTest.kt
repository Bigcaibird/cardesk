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
 * 首页中央大字歌词（第 113 轮：三行 → **五行**，并把当前行之外的四行整体放大）。
 *
 * 效果图：`preview/y27-lyrics-5lines.png`（桌面）、`preview/y27-lyrics-5lines-guide.png`（带导航引导卡）。
 *
 * **断言不靠肉眼**：拿一张「没有歌词」的底图跟「有歌词」的图**逐像素差分**，
 * 把差分出来的行带数出来 —— 必须正好 **5 条**，
 * 且字号关系是「当前句 > 紧邻衬句 > 远衬句」，
 * 整块还要落在**引导卡下沿**与**底部卡片区上沿**之间，
 * 并且**不能画到引导卡上**。
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

    @Test
    fun 渲染五行大字歌词() {
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

        // 五行歌词（上 2 / 上 1 / 当前 / 下 1 / 下 2）
        val lines = listOf(
            "第一句 夜空中最亮的星",
            "第二句 风雨里追赶",
            "第三句 我们都曾勇敢地往前走",
            "第四句 直到看见远方的灯火",
            "第五句 请指引我靠近你",
            "第六句 是否星空的尽头有你",
        )

        // ① 底图（无引导卡、无歌词）
        val base = shot(decor)

        // ② 有歌词（仍无引导卡）→ 差分出 5 条行带
        scenePage.setLyricWindow(lines, 2)
        scenePage.setLyricWipeImmediate(0.42f)
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val withLyric = shot(decor)

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

        val dir = File("../preview").apply { mkdirs() }
        FileOutputStream(File(dir, "y27-lyrics-5lines.png")).use {
            withLyric.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        FileOutputStream(File(dir, "y27-lyrics-5lines-guide.png")).use {
            withGuide.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // ⑤ 差分出行带（只扫歌词区，避开状态栏时钟与右侧车况卡）
        val bands = diffBands(base, withLyric, x0 = 300, x1 = 1440, y0 = 240, y1 = 764)
        println("[113轮] 歌词行带: $bands")

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

        // —— 位置：整块落在「引导卡下沿」与「底部卡片区上沿」之间 ——
        assertTrue(
            "最上面一行($far) 不该压到左上引导卡（下沿 ${ScenePage.LYRIC_GUIDE_CARD_BOTTOM}）",
            far.first > ScenePage.LYRIC_GUIDE_CARD_BOTTOM,
        )
        assertTrue(
            "最下面一行($far2) 不该压到底部卡片区（上沿 ${ScenePage.LYRIC_BOTTOM_CARD_TOP}）",
            far2.last < ScenePage.LYRIC_BOTTOM_CARD_TOP,
        )

        // —— 当前句基线落在设计坐标上（锚点不能漂）——
        assertTrue(
            "当前句墨迹底(${main.last}) 应该贴着基线 ${ScenePage.LYRIC_MAIN_Y}",
            main.last in (ScenePage.LYRIC_MAIN_Y - 12).toInt()..(ScenePage.LYRIC_MAIN_Y + 32).toInt(),
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
