package com.cardesk.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.CardLayout
import com.cardesk.launcher.core.CardStyle
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.media.MediaActions
import com.cardesk.launcher.core.media.MediaButtonTransport
import com.cardesk.launcher.ui.pages.ScenePage
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

/**
 * 桌面全部卡片在各尺寸档位（自适应 / 小 / 中 / 大）、拥挤多卡底排、
 * 车况小卡并入底排以及自定义自由摆放模式下的样式与防溢出回归单测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class AllCardsResponsiveAuditTest {

    private fun sampleVehicle() = VehicleInfo(
        rangeKm = 1280,
        fuelPercent = 78,
        doorsClosed = listOf(true, true, true, true),
        doorsAllClosed = true,
        hoodClosed = true,
        trunkClosed = true,
        windowsOpen = booleanArrayOf(false, false, false, false),
        windowsAllClosed = true,
        tires = floatArrayOf(240f, 238f, 242f, 245f),
        tripKm = 128.4,
        tripDurationMs = 96.0 * 60_000.0,
        avgFuelConsumption = 7.4,
        odometerKm = 36842,
    )

    private fun createScenePage(): ScenePage {
        val ctx: Application = RuntimeEnvironment.getApplication()
        Skin.apply(Palettes.night)
        return ScenePage(ctx).apply {
            bind(sampleVehicle(), HvacState(power = true, fanLevel = 7, seatHeatDriver = 2, seatVentDriver = 1), 0)
            setQuickApps(
                listOf(
                    ScenePage.QuickApp("com.a", "高德地图车机版", null),
                    ScenePage.QuickApp("com.b", "QQ音乐车机版", null),
                    ScenePage.QuickApp("", "", null),
                    ScenePage.QuickApp("com.d", "电话", null),
                ),
            )
            mediaSource = "网易云音乐车机版"
            mediaTitle = "这是一首非常非常长用来测试窄卡片截断效果的歌曲名"
            mediaArtist = "林俊杰 / 周杰伦 现场珍藏版"
            mediaPlaying = true
            musicPackage = MediaButtonTransport.NETEASE_PACKAGE
            favoriteState = MediaActions.FavoriteState.FAVORITED
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, 1080)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cardRectsOf(page: ScenePage): Map<String, RectF> =
        ScenePage::class.java.getDeclaredField("cardRects").apply { isAccessible = true }.get(page) as Map<String, RectF>

    @Suppress("UNCHECKED_CAST")
    private fun hitsOf(page: ScenePage): MutableMap<String, RectF> =
        ScenePage::class.java.getDeclaredField("hits").apply { isAccessible = true }.get(page) as MutableMap<String, RectF>

    @Test
    fun `music card base width is balanced and scales cleanly across SMALL MEDIUM LARGE`() {
        assertEquals("音乐卡自然宽应为紧凑的 380px", 380f, ScenePage.MUSIC_CARD_W, 0.1f)
        val page = createScenePage()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)

        fun widthFor(size: CardLayout.Size): Float {
            page.cardLayout = CardLayout.withSize(CardLayout.DEFAULT, CardLayout.MUSIC, size)
            page.draw(Canvas(bmp))
            return cardRectsOf(page)[CardLayout.MUSIC]!!.width()
        }

        val wSmall = widthFor(CardLayout.Size.SMALL)
        val wMedium = widthFor(CardLayout.Size.MEDIUM)
        val wLarge = widthFor(CardLayout.Size.LARGE)

        assertEquals(380f * 0.82f, wSmall, 1.5f)
        assertEquals(380f, wMedium, 1.5f)
        assertEquals(380f * 1.25f, wLarge, 1.5f)
        assertTrue("音乐卡应严格满足 小 < 中 < 大", wSmall < wMedium && wMedium < wLarge)
    }

    @Test
    fun `free placement custom mode respects slot size for music and other cards`() {
        val page = createScenePage()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        page.cardFreePositions = mapOf(CardLayout.MUSIC to (200f to 220f))

        fun freeWidthFor(size: CardLayout.Size): Float {
            page.cardLayout = CardLayout.withSize(CardLayout.DEFAULT, CardLayout.MUSIC, size)
            page.draw(Canvas(bmp))
            return cardRectsOf(page)[CardLayout.MUSIC]!!.width()
        }

        val wSmall = freeWidthFor(CardLayout.Size.SMALL)
        val wMedium = freeWidthFor(CardLayout.Size.MEDIUM)
        val wLarge = freeWidthFor(CardLayout.Size.LARGE)

        assertEquals("自由摆放小档宽度应生效", 380f * 0.82f, wSmall, 1.5f)
        assertEquals("自由摆放中档宽度应生效", 380f, wMedium, 1.5f)
        assertEquals("自由摆放大档宽度应生效", 380f * 1.25f, wLarge, 1.5f)
    }

    @Test
    fun `CardLayout widths never overflow rowWidth even when all 6 active cards are fixed LARGE`() {
        val allLarge = CardLayout.active(CardLayout.DEFAULT).map { it.copy(size = CardLayout.Size.LARGE, visible = true) }
        val rowW = 1832f
        val gap = ScenePage.CARD_GAP
        val natural = mapOf(
            CardLayout.RANGE to ScenePage.RANGE_CARD_W,
            CardLayout.QUICK to ScenePage.QUICK_CARD_W,
            CardLayout.MUSIC to ScenePage.MUSIC_CARD_W,
            CardLayout.CLIMATE to ScenePage.CLIMATE_CARD_W,
            CardLayout.TRIP_CARD to ScenePage.TRIP_BOTTOM_CARD_W,
            CardLayout.STATUS to ScenePage.STATUS_BOTTOM_CARD_W,
        )
        val widths = CardLayout.widths(allLarge, rowW, gap, { natural[it] ?: 300f })
        assertEquals(6, widths.size)
        val total = widths.sumOf { it.second.toDouble() }.toFloat() + gap * (widths.size - 1)
        assertTrue("全部大档 6 张卡不得超出桌面行宽($total > $rowW)", total <= rowW + 1f)
        assertTrue("每张卡宽度都应大于 160px", widths.all { it.second >= 160f })
    }

    @Test
    fun `all fixed sizes distribute proportionally without exploding the last card`() {
        val allSmall = CardLayout.active(CardLayout.DEFAULT)
            .map { it.copy(size = CardLayout.Size.SMALL) }
        val rowW = 1832f
        val gap = ScenePage.CARD_GAP
        val natural = mapOf(
            CardLayout.RANGE to ScenePage.RANGE_CARD_W,
            CardLayout.MUSIC to ScenePage.MUSIC_CARD_W,
            CardLayout.CLIMATE to ScenePage.CLIMATE_CARD_W,
            CardLayout.TRIP_CARD to ScenePage.TRIP_BOTTOM_CARD_W,
        )
        val widths = CardLayout.widths(allSmall, rowW, gap, { natural[it] ?: 300f })
        val byId = widths.toMap()
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + gap * (widths.size - 1)
        assertEquals(rowW, sum, 1.5f)
        assertTrue(
            "全固定小档时末张行程卡不应独占全部余量（实际=${byId[CardLayout.TRIP_CARD]}）",
            byId[CardLayout.TRIP_CARD]!! < 560f,
        )
        assertTrue(
            "全固定小档时音乐卡宽度应小于等于快捷车控，保持比例一致",
            byId[CardLayout.MUSIC]!! < byId[CardLayout.CLIMATE]!!,
        )
    }

    @Test
    fun `every desktop card draws cleanly across widths 160 to 650 with all hits inside card rect`() {
        val page = createScenePage()
        val drawContent = ScenePage::class.java.getDeclaredMethod(
            "drawCardContent",
            Canvas::class.java,
            String::class.java,
            RectF::class.java,
        ).apply { isAccessible = true }
        val hits = hitsOf(page)
        val allIds = listOf(
            CardLayout.RANGE,
            CardLayout.QUICK,
            CardLayout.MUSIC,
            CardLayout.CLIMATE,
            CardLayout.TRIP_CARD,
            CardLayout.STATUS,
            CardLayout.CAR_STATUS,
        )
        val testWidths = listOf(160f, 220f, 280f, 344f, 420f, 525f, 650f)

        // 同时验证自定义卡片圆角与透明度（如 0px 直角与 48px 大圆角、50% 半透明）不会异常
        for (radius in listOf(0, 24, 48)) {
            CardStyle.applyRadius(radius)
            val styled = CardStyle.apply(Palettes.night, 0xFF1F2B3E.toInt(), 65, radius)
            Skin.apply(styled)
            for (id in allIds) {
                for (w in testWidths) {
                    val bmp = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    val rect = RectF(40f, 40f, 40f + w, 40f + 150f)
                    hits.clear()
                    drawContent.invoke(page, canvas, id, rect)
                    for ((key, hit) in hits) {
                        assertTrue(
                            "卡片 $id 在宽=$w 圆角=$radius 时命中区 $key($hit) 超出卡框($rect)",
                            hit.left >= rect.left - 1f &&
                                hit.right <= rect.right + 1f &&
                                hit.top >= rect.top - 1f &&
                                hit.bottom <= rect.bottom + 1f,
                        )
                    }
                }
            }
        }
        CardStyle.applyRadius(CardStyle.DEFAULT_RADIUS)
        Skin.apply(Palettes.night)
    }

    @Test
    fun `crowded 7-card bottom row with compact car status card stays within screen right edge`() {
        val page = createScenePage()
        page.showCarStatusCard = true
        page.carStatusCardCompact = true
        page.carStatusInBottomRow = true
        page.cardLayout = CardLayout.active(CardLayout.DEFAULT).map { it.copy(size = CardLayout.Size.LARGE, visible = true) }

        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        page.draw(Canvas(bmp))
        val rects = cardRectsOf(page)
        assertEquals("6 张底排卡 + 1 张车况小卡 = 7 张", 7, rects.size)
        val rightEdge = rects.values.maxOf { it.right }
        assertNotNull(rects[CardLayout.CAR_STATUS])
        assertTrue("7 张卡总右缘不得超出屏幕($rightEdge > 1880)", rightEdge <= 1880f)
    }
}
