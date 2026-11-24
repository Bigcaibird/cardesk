package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.Car3dDebugPose
import com.cardesk.launcher.core.car3d.Car3dSettings
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.After
import org.junit.Assert.assertEquals
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
 * 第166轮：**车况卡 C 版**（3/4 写实 hero + 三行摘要，整卡可点）。
 *
 * 1. 卡片默认就画写实 hero（不再是「2D 面板示意图」），和关掉写实开关时明显不同；
 * 2. 左前门开 / 全关两帧在车模区域明显不同（姿态真的映射进了烘焙图）；
 * 3. 整卡注册一个命中区，点卡中心会触发 [ScenePage.onOpenVehicleStatus]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Car3dScenePageTest {
    @get:Rule val carAssets = TestCarAssets.Rule()

    /** 车况卡区域（与 `ScenePage.CAR_STATUS_CARD_*` 一致）。 */
    private val cardRegion = RectF(1488f, 376f, 1876f, 756f)

    /** C 版车模区域：卡内 (12,52)-(268,240) 等比居中后的外框。 */
    private val heroRegion = RectF(1500f, 542f, 1756f, 730f)

    private fun page(doors: List<Boolean?>): ScenePage {
        val p = ScenePage(RuntimeEnvironment.getApplication())
        p.bind(
            VehicleInfo(
                rangeKm = 386,
                fuelPercent = 62,
                tires = floatArrayOf(240f, 240f, 238f, 235f),
                doorsClosed = doors,
                doorsAllClosed = doors.all { it == true },
                hoodClosed = true,
                trunkClosed = true,
                windowsOpen = booleanArrayOf(false, false, false, false),
                windowsAllClosed = true,
            ),
            HvacState(),
            0,
        )
        p.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        p.layout(0, 0, 1920, 1080)
        return p
    }

    private fun render(p: ScenePage): Bitmap {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        p.draw(Canvas(bmp))
        return bmp
    }

    private fun crop(bmp: Bitmap, region: RectF): IntArray {
        val x = region.left.toInt()
        val y = region.top.toInt()
        val w = region.width().toInt()
        val h = region.height().toInt()
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, x, y, w, h)
        return out
    }

    private fun diff(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }

    @Before
    fun setUp() {
        Car3dAssets.reset()
        Car3dDebugPose.clear()
        Car3dSettings.enabled = true
        Car3dSettings.paint = Car3dSettings.PAINT_DARK
    }

    @After
    fun tearDown() {
        Car3dAssets.reset()
        Car3dDebugPose.clear()
        Car3dSettings.enabled = true
        Car3dSettings.paint = Car3dSettings.PAINT_DARK
    }

    @Test
    fun `车况卡默认画写实 hero 车模`() {
        val doors = listOf(false, true, true, true)

        Car3dSettings.enabled = true
        val on = crop(render(page(doors)), cardRegion)

        Car3dSettings.enabled = false
        val off = crop(render(page(doors)), cardRegion)

        val d = diff(on, off)
        assertTrue("写实卡与 2D 回退应有明显差异，实际 $d 像素不同", d > 300)
    }

    @Test
    fun `左前门开与全关在车况卡车模区不一样`() {
        val closed = crop(render(page(listOf(true, true, true, true))), heroRegion)
        val openLf = crop(render(page(listOf(false, true, true, true))), heroRegion)

        val d = diff(closed, openLf)
        assertTrue("开门后车模区应明显变化，实际 $d 像素不同", d > 120)
    }

    @Test
    fun `整卡点击触发车辆状态面板回调`() {
        var clicked = 0
        val p = page(listOf(true, true, true, true)).apply { onOpenVehicleStatus = { clicked++ } }
        render(p) // 命中区在 onDraw 里注册
        val field = ScenePage::class.java.getDeclaredField("hits").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val hits = field.get(p) as Map<String, RectF>
        val rect = hits["carStatusCard"]
        assertTrue("整卡命中区应已注册", rect != null)
        assertEquals("命中区应与卡片矩形一致", cardRegion, rect)

        val cx = cardRegion.centerX()
        val cy = cardRegion.centerY()
        p.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx, cy, 0))
        p.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, cx, cy, 0))
        assertEquals("点车况卡应回调一次", 1, clicked)
    }


    /**
     * 第166轮：车况卡视角可切（3/4 侧视 ↔ 俯视）。
     *
     * 选帧路径必须真的不同；资产还没装 `cardtop` 时必须**静默回退 3/4**（不能空白/崩）。
     * 资产装机后这条会自动从「相等」切到「不同」，两种状态都算通过。
     */
    @Test
    fun `车况卡固定俯视 不再受视角设置影响`() {
        val context = RuntimeEnvironment.getApplication()
        Car3dSettings.enabled = true
        val doors = listOf(false, true, true, true)

        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_HERO
        val hero = crop(render(page(doors).also { it.carModel = "01" }), cardRegion)

        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_CARDTOP
        val top = crop(render(page(doors).also { it.carModel = "01" }), cardRegion)

        val d = diff(hero, top)
        assertEquals("第178轮后车况卡固定用 cardtop，视角设置不应再改变画面，实际差 $d", 0, d)
        assertTrue(
            "cardtop 资产必须存在（01 已装）",
            Car3dAssets.hasView(context, CarModels.assetDir("01"), Car3dAssets.VIEW_CARDTOP),
        )
    }

    @Test
    fun `首次启动默认车漆取资产声明`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = com.cardesk.launcher.core.Prefs(context)
        // 干净的 prefs：没有 car3d_paint 值
        prefs.setStringValue(Car3dSettings.KEY_PAINT, "")
        Car3dSettings.load(context, prefs)
        val expected = Car3dAssets.model(context, CarModels.assetDir(prefs.carModel))?.defaultColor
            ?.takeIf { it.isNotEmpty() }
        if (expected != null) {
            org.junit.Assert.assertEquals("默认车漆应取资产 defaultColor", expected, Car3dSettings.paint)
        } else {
            org.junit.Assert.assertEquals(Car3dSettings.PAINT_DARK, Car3dSettings.paint)
        }
        // 已有用户值优先，不被资产默认值覆盖
        prefs.setStringValue(Car3dSettings.KEY_PAINT, Car3dSettings.PAINT_WHITE)
        Car3dSettings.load(context, prefs)
        org.junit.Assert.assertEquals(Car3dSettings.PAINT_WHITE, Car3dSettings.paint)
    }
}
