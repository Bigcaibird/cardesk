package com.cardesk.launcher.ui

import com.cardesk.launcher.ui.pages.ScenePage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 第 113 轮：首页中央大字歌词的**五行**取句映射。
 *
 * 只测「整窗 → 五个属性」的映射关系；
 * 像素级的版面断言在 [LyricsPreviewRenderTest] 里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SceneLyricWindowTest {

    private fun scene(): ScenePage = ScenePage(RuntimeEnvironment.getApplication())

    @Test
    fun `整窗刷进五个歌词属性`() {
        val page = scene()
        val lines = listOf("一", "二", "三", "四", "五", "六", "七")
        page.setLyricWindow(lines, 3)
        assertEquals("二", page.lyricPrev2)
        assertEquals("三", page.lyricPrev)
        assertEquals("四", page.lyricCurrent)
        assertEquals("五", page.lyricNext)
        assertEquals("六", page.lyricNext2)
    }

    @Test
    fun `没有歌词时五个属性全空`() {
        val page = scene()
        page.setLyricWindow(listOf("一", "二"), 1)
        page.setLyricWindow(emptyList(), -1)
        assertEquals("", page.lyricPrev2)
        assertEquals("", page.lyricPrev)
        assertEquals("", page.lyricCurrent)
        assertEquals("", page.lyricNext)
        assertEquals("", page.lyricNext2)
    }

    @Test
    fun `歌头歌尾的空位保留不塌陷`() {
        val page = scene()
        page.setLyricWindow(listOf("一", "二"), 0)
        assertEquals("", page.lyricPrev2)
        assertEquals("", page.lyricPrev)
        assertEquals("一", page.lyricCurrent)
        assertEquals("二", page.lyricNext)
        assertEquals("", page.lyricNext2)
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
}
