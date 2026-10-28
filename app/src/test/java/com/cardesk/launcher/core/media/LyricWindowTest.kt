package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第三十三轮：歌词窗口。
 *
 * 第二页组合卡的播放器显示 **5 句**（上 2 + 当前 + 下 2），当前句在竖直方向居中。
 */
class LyricWindowTest {

    // ---------------------------------------------------------- 拆行

    @Test
    fun `七行拆成上三下三`() {
        val sp = LyricWindow.split(7)
        assertEquals(3, sp[0])
        assertEquals(3, sp[1])
    }

    @Test
    fun `五行拆成上二下二`() {
        val sp = LyricWindow.split(5)
        assertEquals(2, sp[0])
        assertEquals(2, sp[1])
    }

    @Test
    fun `六行拆成上二下三`() {
        val sp = LyricWindow.split(6)
        assertEquals(2, sp[0])
        assertEquals(3, sp[1])
    }

    @Test
    fun `四行拆成上一行下两行`() {
        val sp = LyricWindow.split(4)
        assertEquals(1, sp[0])
        assertEquals(2, sp[1])
    }

    @Test
    fun `一行拆成上下都零`() {
        val sp = LyricWindow.split(1)
        assertEquals(0, sp[0])
        assertEquals(0, sp[1])
    }

    @Test
    fun `零行和负数按一行算`() {
        assertEquals(0, LyricWindow.split(0)[0])
        assertEquals(0, LyricWindow.split(-3)[0])
        assertEquals(0, LyricWindow.split(-3)[1])
    }

    @Test
    fun `总行数恒等于上行加当前加下行`() {
        for (n in 1..9) {
            val sp = LyricWindow.split(n)
            assertEquals("总行数 $n", n, sp[0] + 1 + sp[1])
        }
    }

    // ---------------------------------------------------------- 槽位顺序

    @Test
    fun `五行的槽位是负二到正二`() {
        assertEquals(listOf(-2, -1, 0, 1, 2), LyricWindow.slots(5).toList())
    }

    @Test
    fun `七行的槽位是负三到正三`() {
        assertEquals(listOf(-3, -2, -1, 0, 1, 2, 3), LyricWindow.slots(7).toList())
    }

    // ---------------------------------------------------------- 取行

    @Test
    fun `按当前行取上下各两句`() {
        val lines = listOf("一", "二", "三", "四", "五", "六", "七")
        assertEquals("二", LyricWindow.lineAt(lines, 3, -2))
        assertEquals("三", LyricWindow.lineAt(lines, 3, -1))
        assertEquals("四", LyricWindow.lineAt(lines, 3, 0))
        assertEquals("五", LyricWindow.lineAt(lines, 3, 1))
        assertEquals("六", LyricWindow.lineAt(lines, 3, 2))
    }

    @Test
    fun `越界返回空串`() {
        val lines = listOf("一", "二")
        assertEquals("", LyricWindow.lineAt(lines, 0, -2))
        assertEquals("", LyricWindow.lineAt(lines, 1, 2))
        assertEquals("", LyricWindow.lineAt(lines, -1, 0))
        assertEquals("", LyricWindow.lineAt(emptyList(), 0, 0))
    }

    @Test
    fun `空行当成空串`() {
        val lines = listOf("一", "  ", "三")
        assertEquals("", LyricWindow.lineAt(lines, 1, 0))
        assertEquals("三", LyricWindow.lineAt(lines, 2, 0))
    }

    @Test
    fun `默认总行数是五`() {
        assertEquals(5, LyricWindow.TOTAL_LINES)
    }
}
