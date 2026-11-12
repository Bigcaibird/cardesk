package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * 「专辑封面当第二页音乐卡背景」的渲染断言。
 *
 * ## 要锁住的两件事
 *
 * ① **封面真的铺在卡上了** —— 不是把卡面换个颜色糊弄过去；
 * ② **文字压在封面上还读得清** —— 亮度上限由 [com.cardesk.launcher.core.media.CoverBackdrop]
 *    反推，这里验证的是**真机像素**确实落进了那个上限里。
 *
 * ## 断言为什么要「先铺不透明底再画」
 *
 * 半透明的东西画在**透明**画布上时，取到的像素 RGB 仍然是原值（只有 alpha 变了），
 * 亮度断言会误判。这里统一先铺一层不透明底色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverBackdropDrawableTest {

    /** 夜间卡面 `#1F232D`。 */
    private val surface = 0xFF1F232D.toInt()

    /** 1px 描边 `#333947`。 */
    private val stroke = 0xFF333947.toInt()

    /** 最弱那档文字 `#757E8E`（= `Skin.TEXT_DIM`）。 */
    private val weakText = 0xFF757E8E.toInt()

    /** 次要文字 `#A2AAB8`（= `Skin.TEXT_SOFT`）。 */
    private val softText = 0xFFA2AAB8.toInt()

    /** 组合卡的尺寸（设计 px，1920×1080 版式里的右槽卡）。 */
    private val cardW = 572
    private val cardH = 867

    /** 画布底色：一律用洋红，任何「没画到」的地方都会露出来。 */
    private val canvasBg = 0xFFFF00FF.toInt()

    private fun drawable(cover: Bitmap?): CoverBackdropDrawable {
        val d = CoverBackdropDrawable(
            surfaceColor = surface,
            strokeColor = stroke,
            cornerRadius = 28f,
            textColor = weakText,
        )
        d.setBounds(0, 0, cardW, cardH)
        if (cover != null) d.bitmap = cover
        ShadowLooper.shadowMainLooper().idle()
        return d
    }

    /** 把背景层画到一张铺了洋红底的画布上。 */
    private fun render(d: CoverBackdropDrawable): Bitmap {
        val bmp = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(canvasBg)
        d.draw(canvas)
        return bmp
    }

    private fun solidCover(color: Int): Bitmap =
        Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(color)
        }

    private fun grayOf(px: Int): Int = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3

    /**
     * 两个颜色的最大通道差。
     *
     * ## 为什么不能直接比「等于卡面色」
     *
     * 遮罩是**半透明**地叠在卡面上的，混色时 Skia 走的是预乘 + 取整，
     * 叠完的像素和原卡面色可能差 **1**（实测 `FF1F232C` vs `FF1F232D`）。
     *
     * 所以「中心像素 == 卡面色 ⇒ 封面没铺上」这种精确判据**是失效的** ——
     * 差 1 就判成「不一样」，断言照样绿，改坏实现也照样绿（反向验证会假通过）。
     *
     * 正确做法：要么给容差，要么用**色相**去判（下面两个用例就是后者）。
     */
    private fun colorDistance(a: Int, b: Int): Int =
        maxOf(
            Math.abs(Color.red(a) - Color.red(b)),
            Math.abs(Color.green(a) - Color.green(b)),
            Math.abs(Color.blue(a) - Color.blue(b)),
        )

    private fun relativeLuminance(color: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(Color.red(color)) +
            0.7152 * lin(Color.green(color)) +
            0.0722 * lin(Color.blue(color))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // ------------------------------------------------------------------ 有封面

    @Test
    fun 有封面时卡面中心带着封面的颜色() {
        // 用**红**封面来判：卡面 `#1F232D` 本身偏蓝（r−b = −14），
        // 所以「中心偏红」只可能来自封面，不会被卡面自己的颜色蒙混过去。
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        val bmp = render(d)
        val center = bmp.getPixel(cardW / 2, cardH / 2)
        assertTrue(
            "卡面中心没有带上封面的颜色：r=${Color.red(center)} b=${Color.blue(center)}（中心像素 $center）",
            Color.red(center) > Color.blue(center) + 8,
        )
        assertTrue(
            "卡面中心和原卡面色几乎一样（差 ${colorDistance(center, surface)}），封面等于没铺上去",
            colorDistance(center, surface) > 8,
        )
    }

    @Test
    fun 纯白封面_压完之后最弱那档文字仍然读得清() {
        val d = drawable(solidCover(Color.WHITE))
        val bmp = render(d)
        val center = bmp.getPixel(cardW / 2, cardH / 2)

        val weakContrast = contrast(weakText, center)
        assertTrue(
            "纯白封面下最弱文字的对比度只有 $weakContrast，应 ≥ 3.0（中心像素 $center）",
            weakContrast >= 3.0,
        )
        val softContrast = contrast(softText, center)
        assertTrue(
            "纯白封面下次要文字的对比度只有 $softContrast，应 ≥ 4.5",
            softContrast >= 4.5,
        )
        // 锚点：相对亮度上限（夜间卡面本身是 0.0168）
        val l = relativeLuminance(center)
        assertTrue(
            "压完之后的相对亮度是 $l，应 ≤ 0.0341（中心像素 $center）",
            l <= 0.0341,
        )
    }

    @Test
    fun 不同封面给出不同的背景() {
        val red = render(drawable(solidCover(0xFFFF2D2D.toInt()))).getPixel(cardW / 2, cardH / 2)
        val blue = render(drawable(solidCover(0xFF1E90FF.toInt()))).getPixel(cardW / 2, cardH / 2)
        assertNotEquals("红封面和蓝封面压出来的底色一样，说明底色没跟着封面走", red, blue)
        assertTrue("红封面压完之后应该还是偏红的", Color.red(red) > Color.blue(red))
        assertTrue("蓝封面压完之后应该还是偏蓝的", Color.blue(blue) > Color.red(blue))
    }

    // ------------------------------------------------------------------ 没封面

    @Test
    fun 没有封面时回退成原来的扁平面() {
        val d = drawable(null)
        val bmp = render(d)
        val center = bmp.getPixel(cardW / 2, cardH / 2)
        // 留 2 的容差：半透明遮罩叠上去时混色会差 1（见 colorDistance 的说明）
        assertTrue(
            "没有封面时卡面中心应该是原来的卡面色（实际 $center，卡面色 $surface）",
            colorDistance(center, surface) <= 2,
        )
    }

    @Test
    fun 描边还在() {
        val d = drawable(null)
        val bmp = render(d)
        // 左边缘那一列应该是 1px 描边（不是卡面色）
        val edge = bmp.getPixel(0, cardH / 2)
        assertNotEquals("卡片左边缘没有描边", surface, edge)
    }

    // ------------------------------------------------------------------ 圆角

    @Test
    fun 圆角之外不画封面() {
        val d = drawable(solidCover(Color.WHITE))
        val bmp = render(d)
        // 左上角是圆角外的区域 —— 必须是画布底色（洋红），不能是封面
        val corner = bmp.getPixel(1, 1)
        assertEquals(
            "圆角外的像素被画上了封面（$corner），圆角裁剪没生效",
            canvasBg,
            corner,
        )
    }

    @Test
    fun 圆角之内是封面() {
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        val bmp = render(d)
        // 圆角内、离边缘 40px 的地方：应该是被压暗后的封面（不是洋红、也不是卡面色）
        val inside = bmp.getPixel(40, cardH / 2)
        assertNotEquals("圆角内没有被画上任何东西", canvasBg, inside)
        assertTrue(
            "圆角内还是原来的卡面色（差 ${colorDistance(inside, surface)}），封面没铺上去",
            colorDistance(inside, surface) > 8,
        )
    }

    // ------------------------------------------------------------------ 其它

    @Test
    fun 模糊图的比例和卡片一致() {
        val d = drawable(solidCover(Color.WHITE))
        val size = d.blurredSize
        assertNotEquals("没有生成模糊图", null, size)
        val (w, h) = size!!
        // 短边 = BLUR_SHORT_SIDE，长宽比与卡片一致
        assertEquals(
            com.cardesk.launcher.core.media.CoverBackdrop.BLUR_SHORT_SIDE,
            minOf(w, h),
        )
        val cardAspect = cardW.toFloat() / cardH
        val blurAspect = w.toFloat() / h
        assertTrue("模糊图比例 $blurAspect 与卡片比例 $cardAspect 不一致", Math.abs(blurAspect - cardAspect) < 0.02)
    }

    @Test
    fun 换封面之后背景会跟着换() {
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        val before = render(d).getPixel(cardW / 2, cardH / 2)
        d.bitmap = solidCover(0xFF1E90FF.toInt())
        ShadowLooper.shadowMainLooper().idle()
        val after = render(d).getPixel(cardW / 2, cardH / 2)
        assertTrue(
            "换封面之后底色几乎没变（差 ${colorDistance(before, after)}）",
            colorDistance(before, after) > 8,
        )
        assertTrue("换之前的底应该偏红", Color.red(before) > Color.blue(before))
        assertTrue("换之后的底应该偏蓝", Color.blue(after) > Color.red(after))
    }

    @Test
    fun 封面有明暗结构时_卡上也看得出明暗() {
        // 这条锁的是「亮度映射」而不是「整体乘系数」：
        // 后者会把所有像素压成同一个亮度 —— 封面等于没画，卡上一片均匀色。
        //
        // 做法：做一张上暗下亮的封面，看卡上下的灰度差是不是还在。
        val cover = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            c.drawColor(0xFF101010.toInt())
            c.drawRect(0f, 300f, 600f, 600f, android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt() })
        }
        val d = drawable(cover)
        val bmp = render(d)
        val top = bmp.getPixel(cardW / 2, 120)
        val bottom = bmp.getPixel(cardW / 2, cardH - 120)
        assertTrue(
            "封面下亮上暗，卡上却是均匀的：上 $top 下 $bottom（灰度 ${grayOf(top)} vs ${grayOf(bottom)}）",
            grayOf(bottom) > grayOf(top) + 12,
        )
    }

    // ------------------------------------------------------------------ 封面开关（第148轮）

    /**
     * 关掉开关 → 卡面退回**纯色卡面**（等于第146轮之前的 `Skin.flat`）。
     */
    @Test
    fun 关掉封面开关_卡面退回纯色() {
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        d.coverEnabled = false
        ShadowLooper.shadowMainLooper().idle()

        val center = render(d).getPixel(cardW / 2, cardH / 2)
        assertTrue(
            "关掉开关后卡面中心应该回到纯卡面色（实际 $center，卡面色 $surface）",
            colorDistance(center, surface) <= 2,
        )
    }

    /**
     * 关掉开关 → **不生成模糊图**。
     *
     * 模糊图要走十几次 `drawBitmap`（逐级减半缩到 64px 再拉回来），
     * 关着的时候还留着它等于白占一张位图、白算一遍。
     */
    @Test
    fun 关掉封面开关_不生成模糊图() {
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        assertNotNull("开着的时候应该已经有模糊图", d.blurredSize)

        d.coverEnabled = false
        ShadowLooper.shadowMainLooper().idle()

        assertNull("关掉之后不该留着模糊图", d.blurredSize)
    }

    /**
     * 关掉 → 再打开 → 背景要回来。
     *
     * 锁的是「关掉只是丢引用、不动源封面」——
     * 源封面还在手里，重新打开时能重新算一遍，不用等换歌。
     */
    @Test
    fun 重新打开封面开关_背景又回来了() {
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        d.coverEnabled = false
        ShadowLooper.shadowMainLooper().idle()
        d.coverEnabled = true
        ShadowLooper.shadowMainLooper().idle()

        assertNotNull("重新打开后应该重新生成模糊图", d.blurredSize)
        val center = render(d).getPixel(cardW / 2, cardH / 2)
        assertTrue(
            "重新打开后卡面中心应该带封面颜色（r=${Color.red(center)} b=${Color.blue(center)}）",
            Color.red(center) > Color.blue(center) + 8,
        )
    }

    /** 关掉开关不能把描边也弄没了。 */
    @Test
    fun 关掉封面开关_描边还在() {
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        d.coverEnabled = false
        ShadowLooper.shadowMainLooper().idle()

        val edge = render(d).getPixel(0, cardH / 2)
        assertNotEquals("卡片左边缘没有描边", surface, edge)
    }

    /**
     * 关掉开关之后**换封面**也不能把背景偷偷画回来。
     *
     * 这条锁的是 `rebuild()` 里 `coverEnabled` 的判断位置 ——
     * 少写那一句，`bitmap` 一变就又把模糊图画上了。
     */
    @Test
    fun 关掉开关之后换封面_背景不会偷偷回来() {
        val d = drawable(solidCover(0xFFFF2D2D.toInt()))
        d.coverEnabled = false
        ShadowLooper.shadowMainLooper().idle()

        d.bitmap = solidCover(0xFF1E90FF.toInt())
        ShadowLooper.shadowMainLooper().idle()

        assertNull("关着的时候换封面不该生成模糊图", d.blurredSize)
        val center = render(d).getPixel(cardW / 2, cardH / 2)
        assertTrue(
            "关着的时候换封面，卡面应该还是纯卡面色（实际 $center）",
            colorDistance(center, surface) <= 2,
        )
    }
}
