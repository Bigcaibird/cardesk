package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * 专辑图（`AlbumArtView`）的绘制断言。
 *
 * ## 覆盖的那个坑：占位底的半透明 alpha 漏进封面
 *
 * 旧实现里占位底与封面**共用一个画笔**。而 `Paint.setColor()` 连 alpha 一起改，
 * 占位底色是半透明的（导航页 `pal.withAlpha(Skin.TEXT, 0x14)` → alpha 只有 20/255）。
 *
 * 真机上的顺序必然是：
 *
 * ① 桌面上电、还没有封面 → 走占位分支 → 画笔 alpha 被改成 **20**；
 * ② 歌曲起来、封面到位 → 走封面分支 → `drawBitmap(..., paint)` 用的还是
 *    **alpha=20** 的画笔 → 封面只画了 8% 的不透明度。
 *
 * 表现为：第二页音乐卡的封面像蒙了一层半透明灰纱，底下透出卡片底色；
 * 而**同一首歌在 Dock 里是亮的**（Dock 现画现用 `new Paint(...)`，alpha 恒为 255）。
 *
 * ## 断言为什么要「先铺不透明底再画」
 *
 * 半透明的封面画在**透明**画布上时，取到的像素 RGB 仍然是纯白（只有 alpha 变了），
 * 断言亮度会误判为「通过」。
 * 必须像真机那样：先铺一张**不透明**的卡片底色，再把封面画上去 ——
 * 这样 alpha 漏了就会真的把底色透出来，亮度立刻掉下去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumArtViewTest {

    /** 卡片底色（第二页组合卡的深色面）。 */
    private val cardBg = 0xFF1A1D26.toInt()

    private fun makeView(): AlbumArtView {
        val v = AlbumArtView(org.robolectric.RuntimeEnvironment.getApplication())
        v.placeholderColor = 0x14FFFFFF // 与 NavPage 的占位底色一致：alpha = 0x14
        v.cornerRadius = 26f
        v.measure(
            View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 96, 96)
        return v
    }

    /** 纯白封面：画对了中心就应该是纯白。 */
    private fun whiteCover(): Bitmap =
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
        }

    /**
     * 像真机那样：先铺不透明卡片底色，再画专辑图，取中心一小块的平均亮度。
     */
    private fun renderOnCard(v: View): Double {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(cardBg) // 不透明底 —— alpha 漏了就会在这里暴露
        v.draw(canvas)
        val px = IntArray(16 * 16)
        bmp.getPixels(px, 0, 16, 40, 40, 16, 16)
        var sum = 0L
        for (p in px) sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
        return sum.toDouble() / px.size
    }

    /**
     * 渲染整张图，返回画布（画在一张不透明底上）。
     *
     * 和 [renderOnCard] 的区别：那个返回中心一小块的平均亮度，
     * 这个返回整张图 —— 查**具体某个像素**的时候用这个。
     */
    private fun renderAll(v: View): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(cardBg)
        v.draw(canvas)
        return bmp
    }

    @Test
    fun 占位底之后封面来了_封面必须是满不透明度() {
        val v = makeView()

        // ① 先经历「还没有封面」—— 真机上必然先这样
        v.bitmap = null
        ShadowLooper.shadowMainLooper().idle()
        v.draw(Canvas(Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)))

        // ② 封面随后到位
        v.bitmap = whiteCover()
        ShadowLooper.shadowMainLooper().idle()

        val brightness = renderOnCard(v)
        assertTrue(
            "封面被画成了半透明（卡片上的亮度只有 $brightness，应接近 255）——" +
                "占位底的 alpha 漏进了封面画笔",
            brightness > 240.0,
        )
    }

    @Test
    fun 封面一直都在时也是满不透明度() {
        val v = makeView()
        v.bitmap = whiteCover()
        ShadowLooper.shadowMainLooper().idle()

        val brightness = renderOnCard(v)
        assertTrue("卡片上的封面亮度只有 $brightness，应接近 255", brightness > 240.0)
    }

    @Test
    fun 没有封面时画的是半透明的占位底() {
        val v = makeView()
        v.bitmap = null
        ShadowLooper.shadowMainLooper().idle()

        // 占位底是 alpha 0x14 的白，压在卡片底色上 → 应该是「底色 + 一层很淡的白」
        val brightness = renderOnCard(v)
        val bgBrightness = (Color.red(cardBg) + Color.green(cardBg) + Color.blue(cardBg)) / 3.0
        val expected = bgBrightness + (255 - bgBrightness) * (0x14 / 255.0)
        assertTrue(
            "占位底的亮度应为 $expected（底色 $bgBrightness + 淡白），实际 $brightness",
            kotlin.math.abs(brightness - expected) < 6.0,
        )
    }

    /**
     * 封面图上**不许画任何标记**。
     *
     * 用户第148轮后半段明确要求：「不要加对角斜杠和暗罩」。
     * 封面图始终是原来那张封面 —— 变的只是**整张卡**的卡面
     * （有封面纹理 ↔ 纯色卡面）。
     *
     * ## 断言怎么写才不脆弱
     *
     * 不能断言「和某个基准图逐像素相同」（换一台设备色值就可能差 1）。
     * 这里用**纯白封面**当基准：圆角之内必须**一个像素不差地是纯白** ——
     * 任何暗罩（压暗）或斜杠（画白线）都会在某个像素上露出来。
     */
    @Test
    fun 封面图上不画任何标记() {
        val v = makeView()
        v.bitmap = whiteCover()
        ShadowLooper.shadowMainLooper().idle()

        val bmp = renderAll(v)
        // 20..76 是圆角**之内**的安全区（96×96、圆角 26）
        for (y in 20..76) {
            for (x in 20..76) {
                val p = bmp.getPixel(x, y)
                assertEquals(
                    "封面图上有多余的标记：($x,$y) 不是纯白（像素 $p）",
                    Color.WHITE,
                    p,
                )
            }
        }
    }

    // ------------------------------------------------------------------ 圆角裁剪

    /**
     * 封面必须按圆角裁剪 —— 不能画成方角。
     *
     * 这条在第148轮换掉了原来的「标记不画到圆角外面」：
     * 用户明确不要在封面图上加任何标记（暗罩 / 斜杠），所以那条没了，
     * 但**封面本身的圆角裁剪**反而更需要守住 —— 没有标记兜着，
     * 圆角一旦失效，方角封面会直接暴露在卡片上。
     */
    @Test
    fun 封面按圆角裁剪_不会画到圆角外面() {
        val v = makeView()
        v.bitmap = whiteCover()
        ShadowLooper.shadowMainLooper().idle()

        val bmp = renderAll(v)
        // 左上角是圆角外 —— 必须还是卡片底色，不能是封面
        assertEquals(
            "圆角外被画上了封面（像素 ${bmp.getPixel(1, 1)}），圆角裁剪没生效",
            cardBg,
            bmp.getPixel(1, 1),
        )
    }
}
