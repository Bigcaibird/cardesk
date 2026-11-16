package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 第180轮：车况卡**胎压胶囊**的两条硬约束。
 *
 * 1. **日间不能是「一坨黑」** —— 胶囊底色原来写死成 `0xE010141B`（近黑），
 *    而字色跟着主题走（白天 = 深灰）→ 深底深字，四个胎压糊成黑块。
 *    现在底色也走主题 token，白天浅底深字、夜里深底浅字。
 * 2. **字号是原来的 2 倍** —— 数值 11 → 22、单位 7 → 14（用户明确要求）。
 *
 * 这两条都是「看着一眼就知道、但很容易被改回去」的性质，所以用像素钉死：
 * 直接量画出来的像素，不靠「属性被赋值了」那种自欺欺人的断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarStatusTirePillTest {

    /** 车况卡尺寸（`ScenePage` 常量：388×380）。 */
    private val cardW = 388f
    private val cardH = 380f

    /** 车模图区（第182轮竖放：x=71，y=34，246×300，水平居中）。 */
    private var lastScene: ScenePage? = null

    private val image = RectF(71f, 34f, 317f, 334f)

    /** 真机 TPMS 报的是 kPa（这里画成 bar 的显示口径）。 */
    private val tires = floatArrayOf(240f, 240f, 238f, 235f)

    /**
     * 只画四颗胎压胶囊，返回整张卡的位图。
     *
     * 直接反射调 `ScenePage.drawTirePills`（private），
     * 这样车模、歌词、别的卡片统统不参与 —— 量到的差异只可能来自胶囊本身。
     */
    private fun renderPills(night: Boolean): Bitmap {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // 主题要等活动起来**之后**再套：MainActivity → LauncherView 启动时会自己
        // `Skin.apply(resolvePalette())` 一次（按系统时间解日夜），会覆盖前面套的值。
        Skin.apply(if (night) Palettes.night else Palettes.day)
        val scene = ScenePage(activity)
        lastScene = scene
        val vehicleInfo = VehicleInfo(ambientTemp = 24f, rangeKm = 386, tires = tires, source = "测试")
        scene.bind(vehicleInfo, HvacState(), 0)

        val bmp = Bitmap.createBitmap(cardW.toInt(), cardH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val m = ScenePage::class.java.getDeclaredMethod(
            "drawTirePills",
            Canvas::class.java,
            RectF::class.java,
            VehicleInfo::class.java,
            RectF::class.java,
            RectF::class.java,
            Boolean::class.javaPrimitiveType,
            android.content.Context::class.java,
        ).apply { isAccessible = true }
        m.invoke(scene, canvas, RectF(0f, 0f, cardW, cardH), vehicleInfo, image, null, true, activity)
        return bmp
    }

    /**
     * 左上角那颗胶囊在卡里的矩形。
     *
     * 第183轮起摆位改成「按关车态参考框 + 后轮可上移」，不再能从图区四角直接算，
     * 所以直接读 `drawTirePills` 记录下来的实际中心（`lastTirePillCenters`）。
     */
    private fun pillRect(): RectF {
        val w = ScenePage::class.java.getDeclaredField("TIRE_PILL_W").apply { isAccessible = true }
            .get(null) as Float
        val h = ScenePage::class.java.getDeclaredField("TIRE_PILL_H").apply { isAccessible = true }
            .get(null) as Float
        val centersField = ScenePage::class.java.getDeclaredField("lastTirePillCenters")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val centers = centersField.get(lastScene!!) as List<PointF>
        val c = centers[0]
        return RectF(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
    }

    /** 相对亮度（0..1）。 */
    private fun lum(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = ch((c shr 16) and 0xFF)
        val g = ch((c shr 8) and 0xFF)
        val b = ch(c and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun Bitmap.px(x: Int, y: Int): Int = getPixel(x, y)

    /**
     * 胶囊**底色**的取样点。
     *
     * 取「左边缘往里 6px、垂直居中」：这里既不在圆角弧上、也不在描边上，
     * 更不会碰到文字（数值居中，左右各留了十几像素）。
     *
     * 别去取胶囊的左上角 —— 那里是圆角描边的抗锯齿边缘，
     * alpha 极小的像素反算成 RGB 会是乱七八糟的颜色（量到过「绿色」）。
     */
    private fun fillSample(pill: RectF): Pair<Int, Int> =
        ((pill.left + 6f).toInt()) to (pill.centerY().toInt())

    /** 胶囊内**文字**的包围盒高度（只看数值那一行，避开描边和下面的「bar」）。 */
    private fun valueTextHeight(bmp: Bitmap, pill: RectF): Int {
        val (fx, fy) = fillSample(pill)
        val fill = bmp.px(fx, fy)
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        // 数值行：胶囊上沿 +6 .. +25（数值基线在 +24，再往下就是「bar」的笔画了）；
        // 左右各让开 10px，避开圆角弧和描边。
        val y0 = (pill.top + 6f).toInt()
        val y1 = (pill.top + 25f).toInt()
        val x0 = (pill.left + 10f).toInt()
        val x1 = (pill.right - 10f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = bmp.px(x, y)
                if (abs(lum(c) - lum(fill)) > 0.06) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (bottom < top) 0 else bottom - top + 1
    }

    /**
     * 日间模式：胶囊底要**浅**、字要**深** —— 不能是黑底黑字。
     */
    @Test
    fun `日间模式 胎压胶囊是浅底深字而不是一坨黑`() {
        val bmp = renderPills(night = false)
        val pill = pillRect()
        // 胶囊内部（还没有字的地方）= 底色
        val (fx, fy) = fillSample(pill)
        val fill = bmp.px(fx, fy)
        assertTrue(
            "日间胶囊底色应该是浅色（实际 ${String.format("#%06X", 0xFFFFFF and fill)}），不能是深底",
            lum(fill) > 0.45,
        )
        // 底色必须**浅于**字色 —— 也就是字真的读得出来
        var darkest = 1.0
        var lightest = 0.0
        val x0 = (pill.left + 3f).toInt()
        val x1 = (pill.right - 3f).toInt()
        val y0 = (pill.top + 3f).toInt()
        val y1 = (pill.bottom - 3f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val l = lum(bmp.px(x, y))
                darkest = min(darkest, l)
                lightest = max(lightest, l)
            }
        }
        assertTrue("胶囊里应该同时有深色（字）和浅色（底）", darkest < 0.2 && lightest > 0.45)
    }

    /**
     * 夜间模式：胶囊底要**深**、字要**浅**（原来的观感不能被改坏）。
     */
    @Test
    fun `夜间模式 胎压胶囊是深底浅字`() {
        val bmp = renderPills(night = true)
        val pill = pillRect()
        val (fx, fy) = fillSample(pill)
        val fill = bmp.px(fx, fy)
        assertTrue(
            "夜间胶囊底色应该是深色（实际 ${String.format("#%06X", 0xFFFFFF and fill)}）",
            lum(fill) < 0.2,
        )
    }

    /**
     * 字号是原来的 **2 倍**：数值行的高度 ≈ 11px 字号的 2 倍。
     */
    @Test
    fun `胎压数值字号是原来的两倍`() {
        val bmp = renderPills(night = true)
        val pill = pillRect()
        val drawn = valueTextHeight(bmp, pill)
        assertTrue("数值行应该画出来东西（实际高度 $drawn）", drawn > 0)

        // 参考：同一串「2.4」用 11px 画，量它的高度。
        // 铺一层不透明黑底 + 白字，判定口径和胶囊那边一致（相对底色的亮度差）。
        val ref = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        Canvas(ref).drawColor(0xFF000000.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = 0xFFFFFFFF.toInt()
        }
        Canvas(ref).drawText("2.4", 10f, 40f, p)
        val refH = inkHeight(ref, 0, ref.width - 1, 0, ref.height - 1)
        assertTrue("参考高度量得出来（实际 $refH）", refH > 0)

        val ratio = drawn.toDouble() / refH
        assertTrue(
            "胎压数值字号应该是原来的 2 倍（实际 ${String.format("%.2f", ratio)} 倍，高度 $drawn / $refH）",
            ratio > 1.8 && ratio < 2.2,
        )
    }

    /** 位图里「亮过黑底」像素的高度（与胶囊那边同一套判定口径）。 */
    private fun inkHeight(bmp: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (lum(bmp.px(x, y)) > 0.06) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (bottom < top) 0 else bottom - top + 1
    }
}
