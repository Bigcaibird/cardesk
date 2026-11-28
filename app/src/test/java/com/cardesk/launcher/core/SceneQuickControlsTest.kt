package com.cardesk.launcher.core

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.ui.dialogs.SceneQuickControlsDialog
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.vehicle.DriveMode
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SceneQuickControlsTest {

    @Test
    fun `catalog exposes 19 unique controls and default 3 slots`() {
        assertEquals(19, SceneQuickControls.ALL.size)
        assertEquals(19, SceneQuickControls.ALL.map { it.id }.toSet().size)
        assertEquals(
            listOf(
                SceneQuickControls.SEAT_HEAT_DRIVER,
                SceneQuickControls.SEAT_VENT_DRIVER,
                SceneQuickControls.MAX_FAN,
            ),
            SceneQuickControls.DEFAULT_SLOTS,
        )
        assertEquals("seatHeat", SceneQuickControls.hitKeyFor(SceneQuickControls.SEAT_HEAT_DRIVER))
        assertEquals("seatVent", SceneQuickControls.hitKeyFor(SceneQuickControls.SEAT_VENT_DRIVER))
        assertEquals("maxFan", SceneQuickControls.hitKeyFor(SceneQuickControls.MAX_FAN))
    }

    @Test
    fun `parse add remove swap enforce bounds 1 to 4 and deduplication`() {
        assertEquals(SceneQuickControls.DEFAULT_SLOTS, SceneQuickControls.parse(""))
        assertEquals(SceneQuickControls.DEFAULT_SLOTS, SceneQuickControls.parse("unknown_id"))

        val added = SceneQuickControls.add(SceneQuickControls.DEFAULT_SLOTS, SceneQuickControls.CAM_360)
        assertEquals(4, added.size)
        assertEquals(SceneQuickControls.CAM_360, added.last())

        // 超过 4 个不再增加
        val capped = SceneQuickControls.add(added, SceneQuickControls.WINDOWS_CLOSE)
        assertEquals(4, capped.size)
        assertTrue(SceneQuickControls.WINDOWS_CLOSE !in capped)

        // 排序交换
        val swapped = SceneQuickControls.swap(added, 0, 3)
        assertEquals(SceneQuickControls.CAM_360, swapped[0])
        assertEquals(SceneQuickControls.SEAT_HEAT_DRIVER, swapped[3])

        // 移除到下限 1 个后不可再删
        var cur = swapped
        cur = SceneQuickControls.remove(cur, SceneQuickControls.SEAT_HEAT_DRIVER)
        cur = SceneQuickControls.remove(cur, SceneQuickControls.SEAT_VENT_DRIVER)
        cur = SceneQuickControls.remove(cur, SceneQuickControls.MAX_FAN)
        assertEquals(listOf(SceneQuickControls.CAM_360), cur)
        assertEquals(listOf(SceneQuickControls.CAM_360), SceneQuickControls.remove(cur, SceneQuickControls.CAM_360))
    }

    @Test
    fun `ScenePage renders all 19 controls without overflow and dispatches taps`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        Skin.apply(Palettes.day)
        Glass.clear()

        val page = ScenePage(ctx).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, 1080)
        }
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val allBatches = SceneQuickControls.ALL.map { it.id }.chunked(4)
            for (batch in allBatches) {
                page.quickControls = batch
                page.quickCam360On = true
                page.quickAmbientOn = true
                page.quickHotspotOn = true
                page.quickIssOn = false
                page.bind(
                    VehicleInfo(driveModeRaw = DriveMode.SELECTION_SPORT, driveModeLabel = "运动", tires = floatArrayOf(240f, 240f, 240f, 230f)),
                    HvacState(power = true, ac = true, auto = true, circulation = 0, defrostFront = true, defrostRear = true, tempSynced = true, fanLevel = 7, seatHeatDriver = 2, seatVentPassenger = 1),
                    0,
                )
                page.draw(canvas)

                @Suppress("UNCHECKED_CAST")
                val cardRects = ScenePage::class.java.getDeclaredField("cardRects").apply { isAccessible = true }
                    .get(page) as Map<String, RectF>
                @Suppress("UNCHECKED_CAST")
                val hits = ScenePage::class.java.getDeclaredField("hits").apply { isAccessible = true }
                    .get(page) as Map<String, RectF>

                val card = cardRects[CardLayout.CLIMATE]!!
                assertEquals("快捷车控卡右上角不应显示编辑入口（仅保留设置页入口）", null, hits["climateConfig"])
                for (id in batch) {
                    val r = hits[SceneQuickControls.hitKeyFor(id)]
                    assertNotNull("缺少车控命中区: $id", r)
                    assertTrue("按钮 $id 左边缘越界", r!!.left >= card.left)
                    assertTrue("按钮 $id 右边缘越界", r.right <= card.right)
                    assertTrue("按钮 $id 底边缘越界", r.bottom <= card.bottom)
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `SceneQuickControlsDialog adds reorders removes and persists`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        Skin.apply(Palettes.day)
        val mem = object : SceneQuickControls.Store {
            var data: List<String> = SceneQuickControls.DEFAULT_SLOTS
            override fun load(): List<String> = data
            override fun save(ids: List<String>) { data = ids }
            override fun resetDefault() { data = SceneQuickControls.DEFAULT_SLOTS }
        }
        val observed = mutableListOf<List<String>>()
        val dlg = SceneQuickControlsDialog(ctx, Palettes.day, mem).apply {
            onChanged = { observed.add(it) }
        }

        dlg.toggleControl(SceneQuickControls.CAM_360)
        assertEquals(4, mem.data.size)
        assertEquals(SceneQuickControls.CAM_360, mem.data.last())

        dlg.moveSlot(3, 0)
        assertEquals(SceneQuickControls.CAM_360, mem.data.first())

        dlg.toggleControl(SceneQuickControls.MAX_FAN)
        assertEquals(3, mem.data.size)
        assertTrue(SceneQuickControls.MAX_FAN !in mem.data)
        assertEquals(3, observed.size)
    }
}
