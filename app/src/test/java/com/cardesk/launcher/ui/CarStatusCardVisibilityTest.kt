package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第111轮：**车况卡**（右侧固定那张）的显隐开关。
 *
 * 背景：设置页「桌面卡片 → 车况」的开关以前控制的是**续航卡**
 * （行内 id = `car`，画的是里程 + 油量条），真正的车况卡压根没有开关。
 * 本轮给车况卡补上独立开关（`Prefs.carStatusCardVisible`），
 * 行内那张改叫「续航」，两者各管各的。
 *
 * 这里验两件事：
 * 1. 隐藏时**车窗命中区不再注册** —— 点那个位置不会误触「切换全部车窗」；
 * 2. 隐藏时**那一块像素真的不画**（不是画了但看不见）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarStatusCardVisibilityTest {

    /** 车况卡的设计坐标（与 `ScenePage.CAR_STATUS_CARD_*` 一致）。 */
    private val cardRegion = RectF(1488f, 490f, 1876f, 740f)

    private fun newScenePage(): ScenePage {
        val page = ScenePage(RuntimeEnvironment.getApplication())
        page.bind(
            VehicleInfo(
                rangeKm = 386,
                fuelPercent = 62,
                doorsClosed = listOf(true, true, true, true),
                doorsAllClosed = true,
                hoodClosed = true,
                trunkClosed = true,
                windowsOpen = booleanArrayOf(false, false, false, false),
                windowsAllClosed = true,
            ),
            HvacState(),
            0,
        )
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        return page
    }

    /** 反射拿 `ScenePage.hits`（命中区表）。 */
    private fun hitKeys(page: ScenePage): Set<String> {
        val field = ScenePage::class.java.getDeclaredField("hits").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(page) as Map<String, RectF>
        return map.keys.toSet()
    }

    private fun render(page: ScenePage): Bitmap {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        page.draw(Canvas(bmp))
        return bmp
    }

    /** 区域里「非透明像素」的数量 —— 卡片画了才会有。 */
    private fun paintedPixels(bmp: Bitmap): Int {
        var n = 0
        for (y in cardRegion.top.toInt() until cardRegion.bottom.toInt()) {
            for (x in cardRegion.left.toInt() until cardRegion.right.toInt()) {
                if (bmp.getPixel(x, y) != 0) n++
            }
        }
        return n
    }

    @Test
    fun 车况卡显示时车窗命中区已注册() {
        val page = newScenePage()
        page.showCarStatusCard = true
        render(page)
        val keys = hitKeys(page)
        for (k in listOf("carWin0", "carWin1", "carWin2", "carWin3")) {
            assertTrue("车窗命中区 $k 应该已注册", k in keys)
        }
    }

    @Test
    fun 车况卡隐藏时整张卡不画且不注册车窗命中区() {
        val page = newScenePage()
        page.showCarStatusCard = false
        render(page)
        val keys = hitKeys(page)
        for (k in listOf("carWin0", "carWin1", "carWin2", "carWin3")) {
            assertFalse("车况卡隐藏后不该再注册 $k", k in keys)
        }
    }

    @Test
    fun 车况卡隐藏时那一块像素不画() {
        val shown = newScenePage().apply { showCarStatusCard = true }
        val shownBmp = render(shown)
        val hidden = newScenePage().apply { showCarStatusCard = false }
        val hiddenBmp = render(hidden)

        val shownPixels = paintedPixels(shownBmp)
        val hiddenPixels = paintedPixels(hiddenBmp)
        assertTrue("显示时车况卡区域应该有内容（实际 $shownPixels）", shownPixels > 0)
        assertEquals("隐藏时车况卡区域不应该有任何像素", 0, hiddenPixels)
    }

    @Test
    fun 车况卡开关默认开启且可持久化() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        // 默认 true = 保持升级前的现状（这张卡以前一直显示，没有开关）。
        assertTrue("车况卡默认应该显示", prefs.carStatusCardVisible)
        prefs.carStatusCardVisible = false
        assertFalse(prefs.carStatusCardVisible)
        prefs.carStatusCardVisible = true
        assertTrue(prefs.carStatusCardVisible)
    }
}
