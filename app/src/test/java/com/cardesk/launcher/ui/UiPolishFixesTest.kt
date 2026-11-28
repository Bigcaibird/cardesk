package com.cardesk.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.cardesk.launcher.core.CardLayout
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.DockOrder
import com.cardesk.launcher.core.Glass
import com.cardesk.launcher.core.GlassDrawable
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.ui.shell.DockBarView
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回归测试：
 * 1. Dock 栏 AUTO 按钮底色与 A/C 及右侧空调图标保持一致（不再固定使用 ACCENT_SOFT）；
 * 2. 车况卡缩为小卡放入底排导致快捷车控卡变窄时，快捷车控按钮仍按卡片高度均匀铺满；
 * 3. GlassDrawable / Glass.card 的模糊背景缩放矩阵按 Design/bmp 放大并按 View 偏移对齐，不再挤压成左上角一团。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class UiPolishFixesTest {

    @Test
    fun `GlassDrawable scales 480x270 backdrop up to 1920x1080 and aligns to View offset`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val root = FrameLayout(ctx).apply {
            layout(0, 0, Design.W.toInt(), Design.H.toInt())
        }
        val panel = FrameLayout(ctx)
        val drawable = GlassDrawable(radius = 0f, fill = Color.TRANSPARENT, strokeColor = Color.TRANSPARENT, shadow = false, sheen = false)
        panel.background = drawable
        root.addView(panel)
        panel.layout(44, 67, 44 + 1836, 67 + 880)
        drawable.setBounds(0, 0, 1836, 880)

        val backdrop = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(1836, 880, Bitmap.Config.ARGB_8888)
        try {
            Glass.publish(backdrop)
            drawable.draw(Canvas(out))
            val matField = GlassDrawable::class.java.getDeclaredField("mat").apply { isAccessible = true }
            val m = matField.get(drawable) as Matrix
            val vals = FloatArray(9)
            m.getValues(vals)
            // Bitmap (480x270) -> Design (1920x1080) must scale by 4.0 (NOT 0.25)
            assertEquals("X 轴应放大 4 倍铺满 1920", 4f, vals[Matrix.MSCALE_X], 0.01f)
            assertEquals("Y 轴应放大 4 倍铺满 1080", 4f, vals[Matrix.MSCALE_Y], 0.01f)
            // Local View at (44, 67) shifts shader by (-44, -67)
            assertEquals("X 偏移应对齐面板 left=44", -44f, vals[Matrix.MTRANS_X], 0.01f)
            assertEquals("Y 偏移应对齐面板 top=67", -67f, vals[Matrix.MTRANS_Y], 0.01f)
        } finally {
            Glass.clear()
            backdrop.recycle()
            out.recycle()
        }
    }

    @Test
    fun `Glass card scales 480x270 backdrop up to 1920x1080`() {
        val backdrop = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            Glass.publish(backdrop)
            Glass.card(Canvas(out), RectF(0f, 0f, 200f, 100f), radius = 0f, offsetX = 0f, offsetY = 968f)
            val matField = Glass::class.java.getDeclaredField("shaderMatrix").apply { isAccessible = true }
            val m = matField.get(Glass) as Matrix
            val vals = FloatArray(9)
            m.getValues(vals)
            assertEquals("Glass.card X 轴应放大 4 倍", 4f, vals[Matrix.MSCALE_X], 0.01f)
            assertEquals("Glass.card Y 轴应放大 4 倍", 4f, vals[Matrix.MSCALE_Y], 0.01f)
            assertEquals("Glass.card Y 偏移应支持 Dock 底部对齐", -968f, vals[Matrix.MTRANS_Y], 0.01f)
        } finally {
            Glass.clear()
            backdrop.recycle()
            out.recycle()
        }
    }

    @Test
    fun `ScenePage quick control buttons fill card height evenly when compact car status card is in bottom row`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        Skin.apply(Palettes.night)
        val page = ScenePage(ctx).apply {
            carStatusCardCompact = true
            carStatusInBottomRow = true
            showCarStatusCard = true
            // 音乐卡自然宽收紧为 380px 后，5 张默认卡时快捷车控仍有 ~428px；
            // 再加显示「快捷应用」模拟 6 卡拥挤底排，确保快捷车控确实被压窄到 < CLIMATE_CARD_W。
            cardLayout = CardLayout.withVisible(CardLayout.DEFAULT, CardLayout.QUICK, true)
            bind(
                VehicleInfo(tires = floatArrayOf(240f, 240f, 240f, 230f)),
                HvacState(seatHeatDriver = 2, seatVentDriver = 0, power = true, fanLevel = 4),
                0,
            )
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, 1080)
        }
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            page.draw(Canvas(bmp))
            @Suppress("UNCHECKED_CAST")
            val cardRects = ScenePage::class.java.getDeclaredField("cardRects").apply { isAccessible = true }
                .get(page) as Map<String, RectF>
            @Suppress("UNCHECKED_CAST")
            val hits = ScenePage::class.java.getDeclaredField("hits").apply { isAccessible = true }
                .get(page) as Map<String, RectF>

            val climateCard = cardRects[CardLayout.CLIMATE]!!
            val seatHeat = hits["seatHeat"]!!
            val seatVent = hits["seatVent"]!!
            val maxFan = hits["maxFan"]!!

            assertTrue("底排加入车况小卡后快捷车控卡应变窄", climateCard.width() < ScenePage.CLIMATE_CARD_W)
            assertEquals("三个按钮顶部应对齐", seatHeat.top, seatVent.top, 0.5f)
            assertEquals("三个按钮高度应一致", seatHeat.height(), maxFan.height(), 0.5f)
            val bottomGap = climateCard.bottom - seatHeat.bottom
            assertTrue("按钮底部留白不应过大（实际 ${bottomGap}px）", bottomGap in 8f..16f)
            assertTrue("按钮高度应铺满标题下方的可用区域（实际 ${seatHeat.height()}px）", seatHeat.height() >= 96f)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `Dock AUTO button uses same glass surface style as AC button`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        Skin.apply(Palettes.night)
        Glass.clear()
        val dock = DockBarView(ctx).apply {
            bind(HvacState(ac = false, auto = false))
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(DockBarView.DOCK_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, DockBarView.DOCK_HEIGHT)
        }
        val bmp = Bitmap.createBitmap(1920, DockBarView.DOCK_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            dock.draw(Canvas(bmp))
            val slots = DockOrder.layout(DockOrder.DEFAULT, 1920f)
            val acSlot = slots.first { it.id == DockOrder.ITEM_AC }
            val autoSlot = slots.first { it.id == DockOrder.ITEM_AUTO }
            // 取按钮胶囊下方避开文字的纯底板像素对比（cy + 18）
            val sampleY = DockBarView.DOCK_HEIGHT / 2 + 18
            val acBg = bmp.getPixel(acSlot.centerX.toInt(), sampleY)
            val autoBg = bmp.getPixel(autoSlot.centerX.toInt(), sampleY)
            assertEquals("AUTO 底板颜色应与 A/C 完全一致", acBg, autoBg)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `Day mode card text and secondary inks keep high contrast over bright and dark wallpapers`() {
        val day = Palettes.day
        val white = 0xFFFFFFFF.toInt()
        val darkWall = 0xFF202428.toInt()

        fun blendOver(fg: Int, bg: Int): Int {
            val a = ((fg ushr 24) and 0xFF) / 255.0
            val r = (((fg ushr 16) and 0xFF) * a + ((bg ushr 16) and 0xFF) * (1.0 - a)).toInt()
            val g = (((fg ushr 8) and 0xFF) * a + ((bg ushr 8) and 0xFF) * (1.0 - a)).toInt()
            val b = ((fg and 0xFF) * a + (bg and 0xFF) * (1.0 - a)).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val glassOverDark = blendOver(day.surface, darkWall)
        val glassOverLight = blendOver(day.surface, white)

        // 卡片标题 / km / 燃料剩余 / 全车已关（textFaint）在白底与深壁纸磨砂玻璃上都要清晰可读
        val faintOnLight = com.cardesk.launcher.core.WallpaperContrast.contrastRatio(day.textFaint, glassOverLight)
        val faintOnDarkGlass = com.cardesk.launcher.core.WallpaperContrast.contrastRatio(day.textFaint, glassOverDark)
        assertTrue("日间弱级文字在白卡面上对比度不足（$faintOnLight）", faintOnLight >= 6.5)
        assertTrue("日间弱级文字在深壁纸玻璃卡面上对比度不足（$faintOnDarkGlass）", faintOnDarkGlass >= 4.0)

        // 次级文字（textDim：车控标签 / bar / 副标题）对比度更高
        val dimOnDarkGlass = com.cardesk.launcher.core.WallpaperContrast.contrastRatio(day.textDim, glassOverDark)
        assertTrue("日间次级文字在深壁纸玻璃卡面上对比度不足（$dimOnDarkGlass）", dimOnDarkGlass >= 5.0)

        // 强调色（详情 › / 编辑 › / 续航油量条）与告警色在白底上保持清晰
        val accentOnLight = com.cardesk.launcher.core.WallpaperContrast.contrastRatio(day.accent, glassOverLight)
        assertTrue("日间强调色在白卡面上对比度不足（$accentOnLight）", accentOnLight >= 4.2)

        // 保持 主文字 < 次级文字 < 弱级文字 的明度层级
        val lumText = com.cardesk.launcher.core.WallpaperContrast.relativeLuminance(day.text)
        val lumDim = com.cardesk.launcher.core.WallpaperContrast.relativeLuminance(day.textDim)
        val lumFaint = com.cardesk.launcher.core.WallpaperContrast.relativeLuminance(day.textFaint)
        assertTrue("日间文字层级应递增: text($lumText) < textDim($lumDim) < textFaint($lumFaint)",
            lumText < lumDim && lumDim < lumFaint)
    }
}
