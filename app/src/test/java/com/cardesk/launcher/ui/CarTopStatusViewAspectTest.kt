package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.Car3dSettings
import com.cardesk.launcher.ui.widget.CarTopStatusView
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.cardesk.launcher.core.car3d.TestCarAssets
import org.junit.Rule

/**
 * 第189轮：「车门未关」弹窗里的**车模被拉变形**的回归。
 *
 * ## 用户反馈
 *
 * > 「车门开启弹窗没有修好，我的意思是车子的模型变形了，比例不对」
 *
 * ## 根因
 *
 * 弹窗给车模的槽位是 **712×404（横）**，而现在的俯视资产是 **264×356（竖）**。
 * 车模位图是在**解码时**就按目标尺寸缩放的
 * （`Car3dAssets.decodeScaled` → `Bitmap.createScaledBitmap(raw, w, h)`），
 * 所以「按比例等比居中」那一步是在一张**已经拉变形**的图上做的 ——
 * 等于没做：竖版的车被压成一辆「横躺着的车」。
 *
 * ## 这条用例守的是什么
 *
 * 同一个车模在横槽（712×404）和竖槽（299×404）里画出来，
 * **轮廓的宽高比必须一样**。变形之前横槽里那辆是宽扁的、比值差出一大截。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarTopStatusViewAspectTest {
    @get:Rule val carAssets = TestCarAssets.Rule()

    @Before
    fun setUp() {
        Car3dAssets.reset()
        Car3dSettings.enabled = true
    }

    @After
    fun tearDown() {
        Car3dAssets.reset()
        Car3dSettings.enabled = true
    }

    /** 按「车门未关」弹窗里的参数画一张车模。 */
    private fun render(w: Int, h: Int): Bitmap {
        val view = CarTopStatusView(RuntimeEnvironment.getApplication())
        view.carModel = "01"
        view.doorsClosed = listOf(true, true, true, true)
        view.windowsOpen = booleanArrayOf(false, false, false, false)
        view.hoodClosed = true
        view.trunkClosed = true
        // 弹窗用的是 dialog 视角；cardtop-only 的车型会自动回退到 cardtop。
        view.view = Car3dAssets.VIEW_DIALOG
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 非透明像素的包围盒（= 车身的轮廓）。 */
    private fun inkBounds(bmp: Bitmap): RectF {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (((bmp.getPixel(x, y) ushr 24) and 0xFF) >= 24) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX) return RectF()
        return RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    /**
     * 横槽里的车模不能被拉扁：轮廓比例要和竖槽里的一致。
     */
    @Test
    fun `第189轮 横槽里的车模不被拉变形`() {
        val wide = inkBounds(render(712, 404))
        val tall = inkBounds(render(299, 404))

        assertTrue("宽槽里应该画出车（包围盒 $wide）", wide.width() > 10 && wide.height() > 10)
        assertTrue("窄槽里应该画出车（包围盒 $tall）", tall.width() > 10 && tall.height() > 10)

        val wideAspect = wide.width() / wide.height()
        val tallAspect = tall.width() / tall.height()
        val diff = abs(wideAspect - tallAspect) / tallAspect
        assertTrue(
            "横槽里的车模被拉变形了：比例 $wideAspect，竖槽里是 $tallAspect（差 ${(diff * 100).toInt()}%）",
            diff < 0.06f,
        )
    }

    /**
     * 俯视车是**竖着**的：车不能比槽位还宽。
     */
    @Test
    fun `第189轮 车模在横槽里仍然是竖的`() {
        val b = inkBounds(render(712, 404))
        assertTrue(
            "车模被拉宽了（${b.width()}×${b.height()}，槽位 712×404）—— 竖版俯视图不能横着躺",
            b.height() > b.width(),
        )
        assertTrue(
            "车模应该还留白（宽 ${b.width()} < 槽位 712），不该硬拉满",
            b.width() < 700f,
        )
    }
}
