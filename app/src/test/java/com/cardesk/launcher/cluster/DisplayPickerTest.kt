package com.cardesk.launcher.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「歌词往哪块屏投」的**选屏逻辑**单测。
 *
 * ## 验的是什么
 *
 * 这一段的判定顺序是**刻意保守**的，因为猜错的后果不是「歌词没显示」，
 * 而是**把歌词糊到副驾屏或倒车影像上** —— 那比不做还糟。
 *
 * | 情况 | 期望 |
 * | --- | --- |
 * | 上次那块屏还在（id 命中） | 选中它 |
 * | 屏还在但换了 id，名字**唯一**命中 | 选中它 |
 * | 屏还在但换了 id，名字**不唯一**命中 | 判不了，不投 |
 * | 主屏（id ≤ 0） | 永远不是目标 |
 * | 从没选过 / 什么都没记住 | 判不了，不投 |
 *
 * ## 为什么主屏必须排除
 *
 * 单屏设备（模拟器、平板、没接仪表的车机）里唯一那块屏 id 是 `0`。
 * 它的名字一旦被存下来，按名字匹配就会把主屏当成仪表，
 * 于是歌词窗口盖在桌面自己身上：整条半透明底把桌面压暗，字全糊了。
 */
class DisplayPickerTest {

    private fun pick(
        ids: IntArray,
        names: Array<String>,
        wantName: String = "",
        wantId: Int = -1,
    ): Int = DisplayPicker.pick(ids, names, wantName, wantId)

    // ------------------------------------------------------------ id 命中

    @Test
    fun `id 精确命中就选它`() {
        val ids = intArrayOf(0, 1, 2)
        val names = arrayOf("内屏", "仪表", "副驾")
        assertEquals(1, pick(ids, names, wantId = 1))
        assertEquals(2, pick(ids, names, wantId = 2))
    }

    @Test
    fun `id 命中时无视名字`() {
        // 名字对不上也以 id 为准 —— id 更硬。
        val ids = intArrayOf(0, 1)
        val names = arrayOf("内屏", "仪表")
        assertEquals(1, pick(ids, names, wantName = "旧名字", wantId = 1))
    }

    @Test
    fun `id 不存在时落到名字匹配`() {
        val ids = intArrayOf(0, 1)
        val names = arrayOf("内屏", "仪表")
        // 上次 id 是 5（屏重连后换了 id），靠名字找回来。
        assertEquals(1, pick(ids, names, wantName = "仪表", wantId = 5))
    }

    // ------------------------------------------------------------ 名字匹配

    @Test
    fun `名字唯一命中就选它`() {
        val ids = intArrayOf(0, 3)
        val names = arrayOf("内屏", "Cluster")
        assertEquals(1, pick(ids, names, wantName = "cluster", wantId = -1))
    }

    @Test
    fun `名字不唯一命中就判不了`() {
        // 两块屏同名 ⇒ 猜了就会投错，宁可什么都不投。
        val ids = intArrayOf(0, 1, 2)
        val names = arrayOf("内屏", "副屏", "副屏")
        assertEquals(DisplayPicker.NONE, pick(ids, names, wantName = "副屏", wantId = -1))
    }

    @Test
    fun `名字对不上就判不了`() {
        val ids = intArrayOf(0, 1)
        val names = arrayOf("内屏", "仪表")
        assertEquals(DisplayPicker.NONE, pick(ids, names, wantName = "副驾", wantId = -1))
    }

    @Test
    fun `空名字不参与匹配`() {
        val ids = intArrayOf(0, 1)
        val names = arrayOf("内屏", "仪表")
        assertEquals(DisplayPicker.NONE, pick(ids, names, wantName = "   ", wantId = -1))
    }

    // ------------------------------------------------------------ 主屏排除

    @Test
    fun `主屏 id 为 0 时永远不是目标`() {
        // 只有主屏 → 判不了，不投。
        val ids = intArrayOf(0)
        val names = arrayOf("内屏")
        assertEquals(DisplayPicker.NONE, pick(ids, names, wantName = "内屏", wantId = 0))
    }

    @Test
    fun `主屏即使 id 命中也不选`() {
        // wantId=0 时第一个分支 wantId > 0 不成立 → 不按 id 选；
        // 名字分支又跳过 id ≤ 0 ⇒ 仍然不投。
        val ids = intArrayOf(0, 1)
        val names = arrayOf("内屏", "仪表")
        assertEquals(DisplayPicker.NONE, pick(ids, names, wantName = "", wantId = 0))
    }

    @Test
    fun `id 为负的屏不会被选中`() {
        val ids = intArrayOf(-2, 1)
        val names = arrayOf("怪屏", "仪表")
        assertEquals(DisplayPicker.NONE, pick(ids, names, wantName = "怪屏", wantId = -2))
    }

    // ------------------------------------------------------------ 空输入

    @Test
    fun `空数组判不了`() {
        assertEquals(DisplayPicker.NONE, pick(IntArray(0), arrayOf<String>()))
        assertEquals(DisplayPicker.NONE, pick(intArrayOf(1), arrayOf<String>()))
    }

    // ------------------------------------------------------------ 兜底选择

    @Test
    fun `firstSecondary 取第一块 id 大于 0 的屏`() {
        assertEquals(1, DisplayPicker.firstSecondary(intArrayOf(0, 4, 7)))
        assertEquals(DisplayPicker.NONE, DisplayPicker.firstSecondary(intArrayOf(0)))
    }

    @Test
    fun `firstPresentation 优先取自称副屏的那块`() {
        val ids = intArrayOf(0, 1, 2)
        val presentation = booleanArrayOf(false, false, true)
        assertEquals(2, DisplayPicker.firstPresentation(ids, presentation))
        assertEquals(DisplayPicker.NONE, DisplayPicker.firstPresentation(ids, booleanArrayOf(false, false, false)))
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `label 带出名字 id 尺寸与密度`() {
        val label = DisplayPicker.label("仪表", 1, 1920, 720, 240)
        assert(label.contains("仪表"))
        assert(label.contains("ID 1"))
        assert(label.contains("1920"))
        assert(label.contains("720"))
        assert(label.contains("240"))
    }

    @Test
    fun `label 没有名字时用屏幕序号兜底`() {
        val label = DisplayPicker.label(null, 7, 0, 0, 0)
        assert(label.contains("屏幕 7"))
    }
}
