package com.cardesk.launcher.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第222轮：**壁纸亮度采样**的回归。
 *
 * 1. 纯色图采样出来就是那个颜色；
 * 2. 夜间自定义壁纸的压暗系数要算进去（不然采样值比屏幕上看到的亮一截）；
 * 3. 「等比填满 + 居中裁切」的源图映射要对（竖拍的照片不能取错地方）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperSamplerTest {

    /** 造一张纯色图。 */
    private fun solid(w: Int, h: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return bmp
    }

    @Test
    fun 纯色图采样出来就是那个颜色() {
        val bmp = solid(1920, 1080, 0xFF203040.toInt())
        val top = WallpaperSampler.averageColor(bmp, WallpaperSampler.TOP_REGION, false)
        val center = WallpaperSampler.averageColor(bmp, WallpaperSampler.CENTER_REGION, false)
        assertNotNull(top)
        assertNotNull(center)
        assertEquals(0x20, (top!! shr 16) and 0xFF)
        assertEquals(0x30, (top shr 8) and 0xFF)
        assertEquals(0x40, top and 0xFF)
        assertEquals(top, center)
    }

    @Test
    fun 上亮下暗的图两块采样值不同() {
        // 只有上 25% 是亮的 —— 顶区（y 0~20%）整块在亮区里，
        // 中区（y 34~62%）整块在暗区里。
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFF0F0F0.toInt())
        canvas.drawRect(0f, 270f, 1920f, 1080f, android.graphics.Paint().apply { color = 0xFF101010.toInt() })

        val top = WallpaperSampler.averageColor(bmp, WallpaperSampler.TOP_REGION, false)!!
        val center = WallpaperSampler.averageColor(bmp, WallpaperSampler.CENTER_REGION, false)!!
        assertTrue("顶区应该偏亮：$top", WallpaperContrast.luma(top) > 200)
        assertTrue("中区应该偏暗：$center", WallpaperContrast.luma(center) < 80)
    }

    @Test
    fun 夜间自定义壁纸的压暗系数随高度变化() {
        // 顶部最暗（40% 黑 + 25% 渐变），55% 高度以下只剩 40% 黑。
        val top = WallpaperContrast.luma(
            (0xFF808080.toInt() and 0xFFFFFF).let {
                val f = WallpaperSampler.customNightScrim(0f)
                Color.rgb((128 * f).toInt(), (128 * f).toInt(), (128 * f).toInt())
            },
        )
        val mid = WallpaperContrast.luma(
            Color.rgb(
                (128 * WallpaperSampler.customNightScrim(0.8f)).toInt(),
                (128 * WallpaperSampler.customNightScrim(0.8f)).toInt(),
                (128 * WallpaperSampler.customNightScrim(0.8f)).toInt(),
            ),
        )
        assertTrue("顶部要更暗：$top vs $mid", top < mid)
        // 0.8 高处：只剩 40% 黑 ⇒ 0.6 倍 ⇒ 128×0.6 ≈ 76
        assertTrue("0.8 高处的压暗不对：$mid", kotlin.math.abs(mid - 76) <= 2)
    }

    @Test
    fun 夜间采样会把亮度算低() {
        val bmp = solid(1920, 1080, 0xFF808080.toInt())
        val day = WallpaperSampler.averageColor(bmp, WallpaperSampler.TOP_REGION, false)!!
        val night = WallpaperSampler.averageColor(bmp, WallpaperSampler.TOP_REGION, true)!!
        assertTrue("夜间必须比白天暗：$night vs $day", night < day)
    }

    @Test
    fun 源图映射按居中裁切换算() {
        // 16:9 的源图 + 16:9 的画面 ⇒ 原图（不裁）。
        val same = WallpaperSampler.sourceRectFor(
            1920, 1080, 1920f, 1080f, WallpaperSampler.TOP_REGION,
        )
        // 顶区是 x 55%~100%、y 0~20% ⇒ [1056, 0, 1920, 216]
        assertEquals(1056, same[0])
        assertEquals(0, same[1])
        assertEquals(1920, same[2])
        assertEquals(216, same[3])

        // 竖拍的照片（1080×1920）铺到 16:9 画面上 ⇒ 按宽放大、上下被裁掉一大截，
        // 画面顶部对应的是源图**中间偏上**那一段（不是源图第一行）。
        val portrait = WallpaperSampler.sourceRectFor(
            1080, 1920, 1920f, 1080f, WallpaperSampler.TOP_REGION,
        )
        assertEquals(656, portrait[1]) // 源图 y 从 656 开始（按宽放大后上下裁掉一大截）
        assertTrue("竖拍时不该从源图第一行开始：${portrait[1]}", portrait[1] > 0)
        assertTrue("竖拍时下边应该被裁掉一截：${portrait[3]}", portrait[3] in 1 until 1920)
        // 横向按宽放大、不裁 ⇒ 源图 x 从 594（顶区左边界 55%）到 1080。
        assertEquals(594, portrait[0])
        assertEquals(1080, portrait[2])
    }

    @Test
    fun 位图不可用时返回空而不是崩() {
        assertEquals(null, WallpaperSampler.averageColor(null, WallpaperSampler.TOP_REGION, false))
        val recycled = solid(8, 8, Color.WHITE)
        recycled.recycle()
        assertEquals(null, WallpaperSampler.averageColor(recycled, WallpaperSampler.TOP_REGION, false))
    }

    // ------------------------------------------------------------------
    // 第231轮：视频壁纸的「亮度分布」（平均值 + 暗端 / 亮端）
    // ------------------------------------------------------------------

    @Test
    fun 纯色图的分布三档一致() {
        val bmp = solid(320, 180, 0xFF585858.toInt())
        val tone = WallpaperSampler.tone(bmp, WallpaperSampler.TOP_REGION, false)!!
        assertEquals(88, WallpaperContrast.luma(tone.average))
        assertEquals(WallpaperContrast.luma(tone.average), WallpaperContrast.luma(tone.dark))
        assertEquals(WallpaperContrast.luma(tone.average), WallpaperContrast.luma(tone.bright))
    }

    @Test
    fun 一半暗一半亮时暗端亮端分得开() {
        // 上半（含顶部采样区的绝大部分）亮，下半暗。
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF202020.toInt())
        canvas.drawRect(0f, 0f, 320f, 90f, android.graphics.Paint().apply { color = 0xFFF0F0F0.toInt() })
        // 用**跨上下两半**的中心区（顶部区只覆盖亮的那一半，采不出暗端）。
        val tone = WallpaperSampler.tone(bmp, WallpaperSampler.CENTER_REGION, false)!!
        assertTrue("亮端要够亮：${WallpaperContrast.luma(tone.bright)}", WallpaperContrast.luma(tone.bright) > 200)
        assertTrue("暗端要够暗：${WallpaperContrast.luma(tone.dark)}", WallpaperContrast.luma(tone.dark) < 60)
        assertTrue(
            "平均值要落在中间：${WallpaperContrast.luma(tone.average)}",
            WallpaperContrast.luma(tone.average) in 40..215,
        )
    }

    @Test
    fun 分布采样对空位图返回空() {
        assertEquals(null, WallpaperSampler.tone(null, WallpaperSampler.TOP_REGION, false))
        val recycled = solid(16, 16, Color.WHITE)
        recycled.recycle()
        assertEquals(null, WallpaperSampler.tone(recycled, WallpaperSampler.TOP_REGION, false))
    }
}
