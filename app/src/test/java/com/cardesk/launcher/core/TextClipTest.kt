package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextClip] 单测（纯逻辑，不依赖 Android）。
 *
 * 布局里的 `android:maxLines="1"` + `android:ellipsize="end"`
 * 超宽文本必须截断成省略号，**绝不能画到相邻控件上**。
 */
class TextClipTest {

    /** 假测量：每个字符 10px。 */
    private fun width10(s: String): Float = s.length * 10f

    @Test
    fun `不超宽原样返回`() {
        assertEquals("abc", TextClip.ellipsize("abc", ::width10, 100f))
        assertEquals("abc", TextClip.ellipsize("abc", ::width10, 30f))
    }

    @Test
    fun `超宽时截断并加省略号`() {
        // 10 字符 = 100px > 60px → 砍到 5 个字符（50px）+ 省略号（10px）= 60px
        val out = TextClip.ellipsize("abcdefghij", ::width10, 60f)
        assertEquals("abcde…", out)
    }

    @Test
    fun `截断后总宽不超过可用宽度`() {
        val text = "Why she had to go I don't know she wouldnt say"
        val out = TextClip.ellipsize(text, ::width10, 120f)
        assertTrue("截断后必须放得下", width10(out) <= 120f)
        assertTrue("必须带省略号", out.endsWith(TextClip.ELLIPSIS))
        assertTrue("必须比原文短", out.length < text.length)
    }

    @Test
    fun `空串原样返回`() {
        assertEquals("", TextClip.ellipsize("", ::width10, 100f))
    }

    @Test
    fun `可用宽度为零时只返回省略号`() {
        assertEquals(TextClip.ELLIPSIS, TextClip.ellipsize("abcdef", ::width10, 0f))
        assertEquals(TextClip.ELLIPSIS, TextClip.ellipsize("abcdef", ::width10, -1f))
    }

    @Test
    fun `只差一点点也要截断`() {
        // 3 字符 = 30px，可用 29px → 必须截断
        val out = TextClip.ellipsize("abc", ::width10, 29f)
        assertTrue(out.endsWith(TextClip.ELLIPSIS))
        assertTrue(width10(out) <= 29f)
    }

    @Test
    fun `刚好等于可用宽度不截断`() {
        // 4 字符 = 40px，可用 40px → 正好放下，原样返回
        assertEquals("abcd", TextClip.ellipsize("abcd", ::width10, 40f))
        // 5 字符 = 50px > 40px → 砍成 3 字符 + 省略号 = 40px
        assertEquals("abc…", TextClip.ellipsize("abcde", ::width10, 40f))
    }
}
