package com.cardesk.launcher.ui

import com.cardesk.launcher.core.media.KTV_CURRENT_LEFT_X
import com.cardesk.launcher.core.media.KTV_NEXT_LEFT_X
import com.cardesk.launcher.core.media.LyricBlockLayout
import com.cardesk.launcher.core.media.LyricLayout
import com.cardesk.launcher.core.media.LyricSizeTier
import com.cardesk.launcher.ui.pages.ScenePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 首页中央大字歌词的**取句映射 + 布局**（第 113 轮起；第 117 轮：两种布局）。
 *
 * 只测「整窗 → 三个属性」的映射关系；
 * 像素级的版面断言在 [LyricsPreviewRenderTest] 里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SceneLyricWindowTest {

    private fun scene(): ScenePage = ScenePage(RuntimeEnvironment.getApplication())

    @Test
    fun `整窗刷进三个歌词属性`() {
        val page = scene()
        val lines = listOf("一", "二", "三", "四", "五", "六", "七")
        page.setLyricWindow(lines, 3)
        assertEquals("三", page.lyricPrev)
        assertEquals("四", page.lyricCurrent)
        assertEquals("五", page.lyricNext)
    }

    @Test
    fun `没有歌词时三个属性全空`() {
        val page = scene()
        page.setLyricWindow(listOf("一", "二"), 1)
        page.setLyricWindow(emptyList(), -1)
        assertEquals("", page.lyricPrev)
        assertEquals("", page.lyricCurrent)
        assertEquals("", page.lyricNext)
    }

    @Test
    fun `歌头歌尾的空位保留不塌陷`() {
        val page = scene()
        page.setLyricWindow(listOf("一", "二"), 0)
        assertEquals("", page.lyricPrev)
        assertEquals("一", page.lyricCurrent)
        assertEquals("二", page.lyricNext)
    }

    // ---------------------------------------------------------- 布局（第117轮）

    @Test
    fun `默认布局是三行`() {
        val page = scene()
        assertEquals(LyricLayout.THREE, page.lyricLayout)
        assertEquals(3, page.lyricLayout.rows)
    }

    @Test
    fun `KTV布局是两行且当前句在最上面`() {
        val layout = LyricLayout.KTV
        assertEquals(2, layout.rows)
        assertEquals("当前句在最上面（第一行）", 0, layout.currentIndex)
        assertEquals("上行是当前句", 0, layout.slots[0])
        assertEquals("下行是下一句", 1, layout.slots[1])
    }

    @Test
    fun `三行布局是上一句当前句下一句`() {
        val layout = LyricLayout.THREE
        assertEquals(3, layout.rows)
        assertEquals(-1, layout.slots[0])
        assertEquals(0, layout.slots[1])
        assertEquals(1, layout.slots[2])
    }

    @Test
    fun `当前句字号不小于其它行`() {
        for (layout in LyricLayout.values()) {
            val cur = layout.sizeTiers[layout.currentIndex]
            val others = layout.sizeTiers.filterIndexed { i, _ -> i != layout.currentIndex }
            assertTrue(
                "${layout.name} 布局的当前句字号不能比其它行小",
                others.all { it <= cur },
            )
        }
    }

    @Test
    fun `KTV两行不加粗_三行当前句加粗`() {
        // 40px 的粗体中文字视觉上比常规字大一号 ——
        // KTV 两行要求「一样大」，所以当前行**不能**加粗，
        // 只靠颜色（青色逐字擦除）+ 位置（在上面）区分。
        assertFalse("KTV 两行的当前句不该加粗", LyricLayout.KTV.boldCurrent)
        assertTrue("三行布局的当前句该加粗（它是唯一的大字号）", LyricLayout.THREE.boldCurrent)
    }

    @Test
    fun `KTV两行两句一样大`() {
        // 用户要求：「前后句保持大小一样，不需要区分前后句大小」。
        val layout = LyricLayout.KTV
        assertEquals("KTV 两行必须用同一个字号档", layout.sizeTiers[0], layout.sizeTiers[1])
        assertEquals("两行都该是「近档」（衬句那一档）", LyricSizeTier.NEAR, layout.sizeTiers[0])
    }

    @Test
    fun `布局键认不出来时回三行`() {
        assertEquals(LyricLayout.THREE, LyricLayout.fromKey(null))
        assertEquals(LyricLayout.THREE, LyricLayout.fromKey(""))
        assertEquals(LyricLayout.THREE, LyricLayout.fromKey("乱写的"))
        assertEquals(LyricLayout.THREE, LyricLayout.fromKey("three"))
        assertEquals(LyricLayout.KTV, LyricLayout.fromKey("ktv"))
        assertEquals(LyricLayout.KTV, LyricLayout.fromKey("KTV"))
    }

    @Test
    fun `布局可以落到偏好里再读回来`() {
        val prefs = com.cardesk.launcher.core.Prefs(RuntimeEnvironment.getApplication())
        assertEquals("three", prefs.sceneLyricLayout)
        prefs.sceneLyricLayout = "ktv"
        assertEquals("ktv", prefs.sceneLyricLayout)
        assertEquals(LyricLayout.KTV, LyricLayout.fromKey(prefs.sceneLyricLayout))
    }

    // ---------------------------------------------------------- 字号缩放（第 114 轮）

    @Test
    fun `歌词缩放默认是百分之百`() {
        val prefs = com.cardesk.launcher.core.Prefs(RuntimeEnvironment.getApplication())
        assertEquals(100, prefs.sceneLyricScalePct)
    }

    @Test
    fun `歌词缩放越界会被夹住`() {
        val prefs = com.cardesk.launcher.core.Prefs(RuntimeEnvironment.getApplication())
        prefs.sceneLyricScalePct = 10
        assertEquals(70, prefs.sceneLyricScalePct)
        prefs.sceneLyricScalePct = 999
        assertEquals(140, prefs.sceneLyricScalePct)
        prefs.sceneLyricScalePct = 120
        assertEquals(120, prefs.sceneLyricScalePct)
    }

    @Test
    fun `场景页的缩放系数越界也会被夹住`() {
        val page = scene()
        page.lyricScale = 0.1f
        assertEquals(0.7f, page.lyricScale, 0.001f)
        page.lyricScale = 9f
        assertEquals(1.4f, page.lyricScale, 0.001f)
        page.lyricScale = 1.2f
        assertEquals(1.2f, page.lyricScale, 0.001f)
    }

    // ------------------------------------------------------ 错落阶梯（第119轮）

    @Test
    fun `KTV两行是错落阶梯_当前句贴左边距下一句往右下错开`() {
        val anchors = LyricLayout.KTV.anchors
        assertNotNull("KTV 两行必须带锚点（位置是定死的）", anchors)
        val a = anchors!!

        // ① 当前句贴桌面的左边距（跟导航引导卡同一条竖线）
        assertEquals(KTV_CURRENT_LEFT_X, a[0].leftX, 0.001f)
        assertEquals(44f, a[0].leftX, 0.001f)

        // ② 下一句往**右**错开
        assertTrue("下一句应该往右下错开", a[1].leftX > a[0].leftX)
        assertEquals(KTV_NEXT_LEFT_X, a[1].leftX, 0.001f)

        // ③ 当前句在**上面**
        assertTrue("当前句应该在下一句上面", a[0].baselineY < a[1].baselineY)
    }

    @Test
    fun `KTV两行都左对齐_不是居中`() {
        val a = LyricLayout.KTV.anchors!!
        // 锚定布局 = 每一行从自己的左边缘起排。
        // 两行的左边缘必须都是「整数意义上的锚点」，
        // 不能像居中块那样被平移到某个计算出来的 x。
        assertEquals(KTV_CURRENT_LEFT_X, a[0].leftX, 0.001f)
        assertEquals(KTV_NEXT_LEFT_X, a[1].leftX, 0.001f)
        // 下一句要错得**够开**（用户示意图里两框之间有一大段空白）
        assertTrue("下一句应该错开足够远", a[1].leftX - a[0].leftX >= 600f)
        // 下一句从屏幕**中线左边一点**起排（用户示意图：x≈776），
        // 但仍在桌面中央区域里 —— 不跑到最右（那里是车况卡）。
        assertTrue("下一句的起点不该跑到屏幕最右", a[1].leftX < 1000f)
    }

    @Test
    fun `KTV两行不压到引导卡也不压到底部卡片区`() {
        val a = LyricLayout.KTV.anchors!!
        val size = ScenePage.LYRIC_NEAR_SIZE

        // ① 当前句的字顶要在引导卡下沿（387）之下
        val curInkTop = a[0].baselineY - size
        assertTrue(
            "当前句字顶($curInkTop) 不该压到引导卡上（下沿 ${ScenePage.LYRIC_GUIDE_CARD_BOTTOM}）",
            curInkTop > ScenePage.LYRIC_GUIDE_CARD_BOTTOM,
        )

        // ② 下一句的字底要在底部卡片区（上沿 764）之上
        val nextInkBottom = a[1].baselineY + size * 0.3f
        assertTrue(
            "下一句字底($nextInkBottom) 不该压到底部卡片区（上沿 ${ScenePage.LYRIC_BOTTOM_CARD_TOP}）",
            nextInkBottom < ScenePage.LYRIC_BOTTOM_CARD_TOP,
        )
    }

    @Test
    fun `KTV下一句不会钻到车况卡底下`() {
        val a = LyricLayout.KTV.anchors!!
        val maxW = LyricBlockLayout.rowMaxWidth(
            leftX = a[1].leftX,
            maxWidth = ScenePage.LYRIC_MAX_W,
            safeRight = ScenePage.LYRIC_SAFE_RIGHT,
        )
        assertTrue(
            "下一句的右边缘(${
                a[1].leftX + maxW
            }) 不能越过车况卡左沿(${ScenePage.LYRIC_SAFE_RIGHT})",
            a[1].leftX + maxW <= ScenePage.LYRIC_SAFE_RIGHT,
        )
        // 40px 一个汉字 → 这一行最多能放十来个字
        assertTrue("一行能放的字数应该在 15 个以上", maxW / ScenePage.LYRIC_NEAR_SIZE >= 15f)
    }

    @Test
    fun `三行布局不用锚点_还是整块居中`() {
        assertNull("三行布局走整块居中，不带锚点", LyricLayout.THREE.anchors)
    }
}
