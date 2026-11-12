package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第155轮：胎压数字字号**真的接到渲染上了**。
 *
 * ## 为什么不能只断言「属性被赋值了」
 *
 * `scenePage.tpmsValueSp = 40` 赋值成功，只能证明**传参**没问题；
 * 证明不了**画出来**的字真的变大了 —— 而「写进去没人用」正是这个项目
 * 这两轮一直在清的老毛病（第154轮审计里 12 个设置项全是这一类）。
 *
 * 所以这里比**像素**：同一个胎压卡，只改字号，把两次渲染结果相减，
 * 量出「变了的那团像素」的包围盒 —— 字号大，包围盒就高。
 *
 * ## 怎么绕开整个桌面
 *
 * 直接反射调 `ScenePage.drawTireCard(canvas, rect)`：
 * 只画这一张卡，坐标自己定，别的卡片、壁纸、歌词统统不参与 ——
 * 量出来的差别就只可能来自字号。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneTpmsValueSpTest {

    /** 胎压卡的设计尺寸（`ScenePage.cardH` = 150，宽 350）。 */
    private val cardW = 350f
    private val cardH = 150f

    /** 真机 TPMS 报的是 kPa（诊断包 240.00 / 227.50 / 237.50 / 235.00）。 */
    private val tiresWithData = floatArrayOf(240f, 227f, 237f, 235f)

    /**
     * 画一张胎压卡，返回位图。
     *
     * @param sp 胎压数值字号
     * @param tires 四个胎压值；传空数组 = 没有胎压数据（画的是「--」）
     */
    private fun renderTireCard(sp: Int, tires: FloatArray = tiresWithData): Bitmap {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val scene = ScenePage(activity)
        scene.bind(
            VehicleInfo(
                ambientTemp = 24f,
                rangeKm = 286,
                tires = tires,
                source = "测试",
            ),
            HvacState(),
            0,
        )
        scene.tpmsValueSp = sp

        val bmp = Bitmap.createBitmap(cardW.toInt(), cardH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val m = ScenePage::class.java.getDeclaredMethod("drawTireCard", Canvas::class.java, RectF::class.java)
            .apply { isAccessible = true }
        m.invoke(scene, canvas, RectF(0f, 0f, cardW, cardH))
        return bmp
    }

    /**
     * 两张图里**不一样**的那团像素的包围盒（没有差异返回 null）。
     *
     * 只看**有差异**的像素 —— 卡片底色、车模、描边在两次渲染里一模一样，
     * 相减自然抵消，剩下的只有字号变化引起的字形差异。
     */
    private fun diffBox(a: Bitmap, b: Bitmap): RectF? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left) return null
        return RectF(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    /** 两张图里不一样的像素个数。 */
    private fun diffCount(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) n++
            }
        }
        return n
    }

    @Test
    fun `字号变大_画出来的胎压数字就变高`() {
        val small = renderTireCard(20)
        val big = renderTireCard(40)

        val box = diffBox(small, big)
        assertNotNull(
            "两个字号画出来应该不一样 —— 一样就说明字号根本没接到渲染上",
            box,
        )

        // 字号从 20 到 40，字形高度大约翻倍。
        // 这里只断言「明显更高」，不卡死倍数 —— 字形上下留白、
        // 抗锯齿、基线对齐都会带来零头。
        assertTrue(
            "字号 40 画出来的字应该明显比字号 20 高，实际高度：${box!!.height()}",
            box.height() > 20f,
        )
    }

    @Test
    fun `字号越大_画出来的笔画像素越多`() {
        // 用「有胎压数据」和「没数据」两张卡的差来数笔画：
        // 没数据时画的是「--」，差出来的就是数字的像素。
        fun ink(sp: Int): Int = diffCount(renderTireCard(sp), renderTireCard(sp, FloatArray(4)))

        val small = ink(20)
        val big = ink(40)
        assertTrue("字号 20 也该画出数字（对照卡之间要有差别）", small > 0)
        assertTrue(
            "字号 40 的笔画像素应该明显多于字号 20，实际：$big vs $small",
            big > small,
        )
    }
}
