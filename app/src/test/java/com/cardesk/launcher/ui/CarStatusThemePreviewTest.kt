package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import com.cardesk.launcher.core.DoorAlert
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.Car3dSettings
import com.cardesk.launcher.ui.dialogs.DoorAlertDialog
import com.cardesk.launcher.ui.dialogs.VehicleStatusDialog
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import java.io.File
import java.io.FileOutputStream
import org.junit.After
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
 * 车况卡 / 弹窗页的**日夜对比**预览图。
 *
 * 胎压胶囊与弹窗标签都画在**车模图上**（图是烘焙好的深色位图），
 * 白天夜里车模一样暗 —— 所以凡是「胶囊底色写死成深色、字色跟着主题走」
 * 的画法，白天就会变成**深底深字**（一坨黑）。
 *
 * 这里把同一套内容各出一张日/夜图，用来肉眼比对。
 *
 * 产物：`cardesk/preview/theme-*.png`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarStatusThemePreviewTest {
    @get:Rule val carAssets = TestCarAssets.Rule()

    private val dir: File = File("../preview").apply { mkdirs() }

    /** 车况卡在设计坐标里的位置（与 `Car3dPreviewTest` 一致）。 */
    private val cardRegion = Rect(1488, 376, 1876, 756)

    @Before
    fun setUp() {
        Car3dAssets.reset()
        Car3dSettings.enabled = true
    }

    @After
    fun tearDown() {
        Car3dAssets.reset()
        Car3dSettings.enabled = true
        Skin.apply(Palettes.night)
    }

    private fun page(doors: List<Boolean?>): ScenePage {
        val page = ScenePage(RuntimeEnvironment.getApplication())
        page.bind(
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
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        return page
    }

    private fun save(bmp: Bitmap, name: String) {
        FileOutputStream(File(dir, "theme-$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** 车况卡（异常状态：左前门开 + 左前窗开）。 */
    private fun shotCardOpen(pal: Palette, name: String) {
        Skin.apply(pal)
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val page = page(listOf(false, true, true, true)).also {
            it.bind(
                VehicleInfo(
                    rangeKm = 386,
                    fuelPercent = 62,
                    tires = floatArrayOf(240f, 240f, 238f, 235f),
                    doorsClosed = listOf(false, true, true, true),
                    doorsAllClosed = false,
                    hoodClosed = true,
                    trunkClosed = true,
                    windowsOpen = booleanArrayOf(true, false, false, false),
                    windowsAllClosed = false,
                ),
                HvacState(),
                0,
            )
            it.invalidate()
        }
        page.draw(Canvas(full))
        val crop = Bitmap.createBitmap(
            full, cardRegion.left, cardRegion.top, cardRegion.width(), cardRegion.height(),
        )
        save(Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, false), name)
    }

    /** 车辆状态面板（异常状态：左前门开 + 左前窗开）。 */
    private fun shotStatusDialogOpen(pal: Palette, name: String) {
        Skin.apply(pal)
        val dialog = VehicleStatusDialog(
            RuntimeEnvironment.getApplication(),
            pal,
            listOf(false, true, true, true),
            booleanArrayOf(true, false, false, false),
            true,
            true,
            "01",
        )
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        dialog.layout(0, 0, 1920, 1080)
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawColor(0xFF141A26.toInt())
        dialog.draw(canvas)
        save(Bitmap.createBitmap(full, 580, 70, 760, 720), name)
    }

    private fun shotCard(pal: Palette, name: String) {
        Skin.apply(pal)
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        page(listOf(true, true, true, true)).draw(Canvas(full))
        val crop = Bitmap.createBitmap(
            full, cardRegion.left, cardRegion.top, cardRegion.width(), cardRegion.height(),
        )
        save(Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, false), name)
    }

    private fun shotStatusDialog(pal: Palette, name: String) {
        Skin.apply(pal)
        val dialog = VehicleStatusDialog(
            RuntimeEnvironment.getApplication(),
            pal,
            listOf(true, true, true, true),
            booleanArrayOf(false, false, false, false),
            true,
            true,
            "01",
        )
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        dialog.layout(0, 0, 1920, 1080)
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawColor(0xFF141A26.toInt())
        dialog.draw(canvas)
        save(Bitmap.createBitmap(full, 580, 70, 760, 720), name)
    }

    private fun shotDoorDialog(pal: Palette, name: String) {
        Skin.apply(pal)
        val doors = listOf(false, true, true, true)
        val dialog = DoorAlertDialog(
            RuntimeEnvironment.getApplication(),
            pal,
            DoorAlert.openItems(doors, true, true),
            doors,
            booleanArrayOf(false, false, false, false),
            true,
            true,
            "01",
        )
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        dialog.layout(0, 0, 1920, 1080)
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawColor(0xFF141A26.toInt())
        dialog.draw(canvas)
        save(Bitmap.createBitmap(full, 580, 70, 760, 840), name)
    }

    @Test
    fun `车况卡 日夜对比`() {
        shotCard(Palettes.night, "01-card-night")
        shotCard(Palettes.day, "02-card-day")
    }

    @Test
    fun `车辆状态弹窗 日夜对比`() {
        shotStatusDialog(Palettes.night, "03-status-dialog-night")
        shotStatusDialog(Palettes.day, "04-status-dialog-day")
    }

    @Test
    fun `车门未关弹窗 日夜对比`() {
        shotDoorDialog(Palettes.night, "05-door-dialog-night")
        shotDoorDialog(Palettes.day, "06-door-dialog-day")
    }

    /** 开门 + 开窗：验证告警色（橙 / 黄）在白底上读不读得出来。 */
    @Test
    fun `异常状态 日夜对比`() {
        Skin.apply(Palettes.night)
        shotStatusDialogOpen(Palettes.night, "07-open-status-dialog-night")
        shotStatusDialogOpen(Palettes.day, "08-open-status-dialog-day")
        shotCardOpen(Palettes.night, "09-open-card-night")
        shotCardOpen(Palettes.day, "10-open-card-day")
    }
}
