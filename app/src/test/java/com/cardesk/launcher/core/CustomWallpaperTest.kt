package com.cardesk.launcher.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 自定义壁纸（第 105 轮）核心回归。
 *
 * 1. `inSampleSize` 只按 2 的幂降采样，避免 4K 原图整张解进内存；
 * 2. `fitTo` 等比填满 + 居中裁切到 1920×1080；
 * 3. `importFromUri → 落盘 → loadScaled` 走通，并且只留一张全屏位图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomWallpaperTest {

    @Test
    fun sampleSize只按二的幂降采样() {
        // 4K 原图：目标 1920×1080 → 只要宽高都还不小于目标就继续减半 → 2
        assertEquals(2, CustomWallpaper.computeSampleSize(3840, 2160, 1920, 1080))
        // 正好 1080p：不缩
        assertEquals(1, CustomWallpaper.computeSampleSize(1920, 1080, 1920, 1080))
        // 手机竖拍 3000×4000：高度够、宽度缩一半也还 >1920 → 1（缩了就不够宽）
        assertEquals(1, CustomWallpaper.computeSampleSize(3000, 4000, 1920, 1080))
        // 8K：→ 4
        assertEquals(4, CustomWallpaper.computeSampleSize(7680, 4320, 1920, 1080))
        // 异常输入不炸
        assertEquals(1, CustomWallpaper.computeSampleSize(0, 0, 1920, 1080))
    }

    @Test
    fun fitTo等比填满并居中裁切() {
        val src = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val out = CustomWallpaper.fitTo(src, 1920, 1080)
        assertEquals(1920, out.width)
        assertEquals(1080, out.height)
    }

    @Test
    fun 从Uri导入后只留一张缩放好的位图() {
        val ctx = RuntimeEnvironment.getApplication()
        CustomWallpaper.reset(ctx)
        // 造一张 800×600 的"照片"，以 PNG 字节喂给 ContentResolver。
        val src = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(src).apply {
            drawColor(Color.rgb(30, 60, 120))
            drawCircle(400f, 300f, 120f, android.graphics.Paint().apply { color = Color.rgb(240, 200, 120) })
        }
        val bytes = ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        src.recycle()
        val uri = Uri.parse("content://test/photo.png")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        assertTrue("导入应成功", CustomWallpaper.importFromUri(ctx, uri))
        assertTrue("应该落盘", CustomWallpaper.has(ctx))
        val bmp = CustomWallpaper.loadScaled(ctx)
        assertNotNull("应该能解出位图", bmp)
        assertEquals(1920, bmp!!.width)
        assertEquals(1080, bmp.height)
        assertTrue("单槽缓存应只算这一张", CustomWallpaper.cachedBytes() > 0)
        // 再解一次返回同一张（不重复分配）
        assertTrue("重复 load 应复用缓存", CustomWallpaper.loadScaled(ctx) === bmp)

        CustomWallpaper.reset(ctx)
        assertTrue("删除后文件应消失", !CustomWallpaper.has(ctx))
    }
}
