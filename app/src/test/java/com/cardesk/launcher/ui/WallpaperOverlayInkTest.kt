package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Glass
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.WallpaperContrast
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.ui.shell.ShellStatusBar
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第222轮：**浮层墨色按壁纸明暗切换**的端到端回归。
 *
 * 场景就是用户反馈的那条：**日间模式 + 暗色壁纸** ⇒
 * 状态栏的时钟、右上角的天气块「不那么明显」。
 *
 * 这里不靠「属性被赋值了」那种自欺欺人的断言 ——
 * **直接把画面画出来量像素**：
 * - 日间主题 + 暗色壁纸 ⇒ 时钟必须是**浅色**；
 * - 日间主题 + 没有暗色壁纸 ⇒ 时钟必须还是**深色**（默认场景零变化）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperOverlayInkTest {

    /** 一张暗色壁纸（接近深蓝夜空）。 */
    private val darkBackdrop = 0xFF10141F.toInt()

    @After
    fun 还原全局状态() {
        // ⚠️ `Skin` 是全局单例，不还原会漏给别的用例（第216轮踩过这个坑）。
        Skin.apply(Palettes.night)
        Skin.setBackdrop(null, null)
        Glass.publish(null)
    }

    private fun activity(): MainActivity {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return act
    }

    /** 画一张状态栏，返回位图。 */
    private fun renderStatusBar(backdrop: Int?): Bitmap {
        val act = activity()
        Skin.apply(Palettes.day)
        Skin.setBackdrop(backdrop, backdrop)
        val bar = ShellStatusBar(act)
        bar.clockText = "10:36"
        bar.fuelPercent = 62
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bmp))
        return bmp
    }

    /** 状态栏时钟所在的那块矩形（按状态栏自己的排版算，别写死坐标）。 */
    private fun clockRect(): Rect {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 32f
            typeface = android.graphics.Typeface.DEFAULT
        }
        val clockW = p.measureText("10:36")
        val right = 1920 - ShellStatusBar.EDGE
        val left = (right - clockW).toInt()
        return Rect(left, 18, right.toInt(), 64)
    }

    /** 区域里的最大感知亮度。 */
    private fun maxLuma(bmp: Bitmap, rect: Rect): Int {
        var max = 0
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val c = bmp.getPixel(x, y)
                if ((c ushr 24) > 0) {
                    val l = WallpaperContrast.luma(c)
                    if (l > max) max = l
                }
                x++
            }
            y++
        }
        return max
    }

    @Test
    fun 日间主题加暗色壁纸时状态栏时钟变浅色() {
        val bmp = renderStatusBar(darkBackdrop)
        val luma = maxLuma(bmp, clockRect())
        assertTrue("时钟应该变成浅色（实测最大亮度 $luma）", luma > 200)
    }

    @Test
    fun 没有暗色壁纸时日间时钟保持深色() {
        // 默认场景必须**一个像素都不变**：日间主题 + 浅色壁纸 ⇒ 仍是深色时钟。
        val bmp = renderStatusBar(null)
        val luma = maxLuma(bmp, clockRect())
        assertTrue("时钟应该还是深色（实测最大亮度 $luma）", luma < 100)
    }

    @Test
    fun 夜间主题加深色壁纸时时钟保持浅色() {
        val act = activity()
        Skin.apply(Palettes.night)
        Skin.setBackdrop(darkBackdrop, darkBackdrop)
        val bar = ShellStatusBar(act)
        bar.clockText = "10:36"
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bmp))
        val luma = maxLuma(bmp, clockRect())
        assertTrue("夜间 + 深色壁纸 ⇒ 时钟是浅色（实测最大亮度 $luma）", luma > 200)
    }

    /**
     * 场景页右上角的天气块（**没有底板**，直接压在壁纸上）。
     *
     * 只调私有的 `drawWeather`（反射），车模 / 歌词 / 别的卡片统统不参与 ——
     * 量到的差异只可能来自天气块本身。
     */
    private fun renderWeather(backdrop: Int?): Bitmap {
        val act = activity()
        Skin.apply(Palettes.day)
        Skin.setBackdrop(backdrop, backdrop)
        val scene = ScenePage(act)
        scene.bind(VehicleInfo(ambientTemp = 24f, rangeKm = 386, source = "测试"), HvacState(), 0)
        scene.weatherTemp = 24
        scene.weatherCity = "杭州"
        scene.weatherSky = "晴"
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val m = ScenePage::class.java.getDeclaredMethod("drawWeather", Canvas::class.java)
            .apply { isAccessible = true }
        m.invoke(scene, Canvas(bmp))
        return bmp
    }

    /** 温度大数字所在的那块（96px 粗体，右上角）。 */
    private fun weatherTempRect(): Rect = Rect(1690, 70, 1880, 168)

    @Test
    fun 天气大数字跟着壁纸明暗换色() {
        val light = renderWeather(darkBackdrop)
        val luma = maxLuma(light, weatherTempRect())
        assertTrue("天气大数字应该变浅（实测最大亮度 $luma）", luma > 200)

        val dark = renderWeather(null)
        val luma2 = maxLuma(dark, weatherTempRect())
        assertTrue("默认场景天气大数字应该还是深色（实测最大亮度 $luma2）", luma2 < 100)
    }

    // ------------------------------------------------------------------
    // 第223轮：状态栏胶囊的**底板**也跟着壁纸明暗走
    // ------------------------------------------------------------------

    /**
     * 取驾驶模式胶囊的矩形（反射读回来，别在测试里写死坐标）。
     *
     * 胶囊的位置取决于文案宽度（「驾驶模式 · 待命」几个字），
     * 写死坐标的话改一次文案就得改一次测试 —— 直接问 View 要最省事。
     */
    private fun assistChipRect(bar: ShellStatusBar): android.graphics.RectF {
        val f = ShellStatusBar::class.java.getDeclaredField("assistRect")
        f.isAccessible = true
        return f.get(bar) as android.graphics.RectF
    }

    /** 胶囊底板上的一个像素（取左内边距那块空白，避开文字和指示点）。 */
    private fun chipFillPixel(bmp: Bitmap, bar: ShellStatusBar): Int {
        val r = assistChipRect(bar)
        return bmp.getPixel(r.left.toInt() + 2, r.centerY().toInt())
    }

    /**
     * 断言「画出来的这个像素就是 palette 里的那张面」。
     *
     * 第242轮：底板改成半透明玻璃面后，位图存取走预乘 / 反预乘（Robolectric NATIVE
     * 还会做一次色彩空间往返），RGB 会有几个 LSB 的量化误差 —— 这不是「换了颜色」。
     * 所以 **alpha 要求逐字相等**（面的透明度不能变），RGB 允许 ±4。
     */
    private fun assertSameFace(expected: Int, actual: Int, what: String) {
        assertEquals("$what：alpha 必须与 palette 一致", expected ushr 24, actual ushr 24)
        for (shift in intArrayOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue(
                "$what：通道偏移 $shift 的偏差 ${kotlin.math.abs(e - a)} 太大" +
                    "（expected #${Integer.toHexString(expected)} actual #${Integer.toHexString(actual)}）",
                kotlin.math.abs(e - a) <= 4,
            )
        }
    }

    @Test
    fun 暗色壁纸上胶囊底板换成夜间那套() {
        // 用户反馈：「状态栏白色的胶囊在黑色的壁纸上有点丑」——
        // 日间那套胶囊底板是浅色 `#F2F5F9`，压在暗色壁纸上就是一块白疙瘩。
        val act = activity()
        Skin.apply(Palettes.day)
        Skin.setBackdrop(darkBackdrop, darkBackdrop)
        val bar = ShellStatusBar(act)
        bar.clockText = "10:36"
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        // 第242轮：胶囊底板改成半透明玻璃面，像素 = 面 × 底层壁纸。
        // 这里先清掉 Glass 的模糊壁纸，让像素只反映「面」本身（颜色 + alpha）。
        Glass.publish(null)
        bar.draw(Canvas(bmp))

        val fill = chipFillPixel(bmp, bar)
        val luma = WallpaperContrast.luma(fill)
        assertTrue(
            "暗壁纸下胶囊底板应该是深色（实测 #${Integer.toHexString(fill)}，亮度 $luma）",
            luma < 60,
        )
        // 状态栏用的是浮层墨色那套的 `surface`（= palette 的 surfaceSoft），
        // 这里直接引用常量，不抄十六进制。
        assertEquals(
            "胶囊底板 token 必须是夜间 palette 的 surfaceSoft",
            Palettes.night.surfaceSoft,
            Skin.overlayTop.surface,
        )
        assertSameFace(Palettes.night.surfaceSoft, fill, "夜间胶囊底板")

        // 底板深了，里面的字必须跟着变浅 —— 否则「深底深字」照样看不见。
        val r = assistChipRect(bar)
        assertTrue(
            "胶囊里的文字应该变浅",
            maxLuma(bmp, android.graphics.Rect(
                r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(),
            )) > 200,
        )
    }

    @Test
    fun 默认场景胶囊底板保持日间那套() {
        // 默认场景必须**一个像素都不变**：日间主题 + 浅壁纸 ⇒ 胶囊还是浅底。
        val act = activity()
        Skin.apply(Palettes.day)
        Skin.setBackdrop(null, null)
        val bar = ShellStatusBar(act)
        bar.clockText = "10:36"
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        Glass.publish(null)
        bar.draw(Canvas(bmp))

        val fill = chipFillPixel(bmp, bar)
        // 第242轮：日间胶囊 = 42% 白玻璃面（palette 的 surfaceSoft）。
        assertEquals(
            "胶囊底板 token 必须是日间 palette 的 surfaceSoft",
            Palettes.day.surfaceSoft,
            Skin.overlayTop.surface,
        )
        assertSameFace(Palettes.day.surfaceSoft, fill, "默认场景胶囊底板")
    }

    @Test
    fun 翻页圆点条也跟着换底() {
        // 状态栏正中的玻璃条和那三个胶囊是同一行、同一材质 ⇒ 一起换。
        fun renderDots(backdrop: Int?): Bitmap {
            val act = activity()
            Skin.apply(Palettes.day)
            Skin.setBackdrop(backdrop, backdrop)
            val dots = com.cardesk.launcher.ui.shell.PageDotsView(act)
            dots.measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            dots.layout(0, 0, 1920, 1080)
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            dots.draw(Canvas(bmp))
            return bmp
        }

        // 条子水平居中（宽 = 20 + 3×30 + 2×8 = 126），取正中上方那一小块底板。
        // 第242轮：底板改成半透明面，位图存取是预乘/反预乘的，RGB 允许 ±4 的量化误差；
        // alpha 必须与 palette 逐字一致（这才是「同一张面」的实质）。
        val dark = renderDots(darkBackdrop).getPixel(960, 24)
        assertSameFace(Palettes.night.surfaceSoft, dark, "暗壁纸下圆点条")

        val light = renderDots(null).getPixel(960, 24)
        assertSameFace(Palettes.day.surfaceSoft, light, "默认场景圆点条")
    }
}
