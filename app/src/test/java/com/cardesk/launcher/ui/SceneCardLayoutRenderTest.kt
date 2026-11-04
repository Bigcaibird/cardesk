package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.CardLayout
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 桌面卡片布局（C2）的效果图：隐藏胎压卡 + 音乐卡放大一档，
 * 渲染 `preview/y05-scene-cards.png` 供目视检查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneCardLayoutRenderTest {

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun 自定义布局渲染() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()

        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val field = LauncherView::class.java.getDeclaredField("scenePage").apply { isAccessible = true }
        val scenePage = field.get(launcher) as com.cardesk.launcher.ui.pages.ScenePage

        val custom = CardLayout.withVisible(
            CardLayout.withSize(CardLayout.DEFAULT, CardLayout.MUSIC, CardLayout.Size.LARGE),
            CardLayout.TIRE,
            false,
        )
        scenePage.cardLayout = custom
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()

        val dir = File("../preview").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, "y05-scene-cards.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // 卡片库：把行程 / 车门车窗两张新卡也放上桌面，再出一张效果图。
        val withLibrary = CardLayout.withVisible(
            CardLayout.withVisible(custom, CardLayout.TRIP_CARD, true),
            CardLayout.STATUS,
            true,
        )
        scenePage.cardLayout = withLibrary
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val bmp2 = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp2))
        FileOutputStream(File(dir, "y06-scene-cards-library.png")).use {
            bmp2.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // 窄卡回归：七张卡全部上桌（快捷车控会被压到最窄），确认卡内控件不再溢出卡片。
        scenePage.cardLayout = CardLayout.DEFAULT.map { it.copy(visible = true) }
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val bmp3 = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp3))
        FileOutputStream(File(dir, "y07-scene-cards-seven.png")).use {
            bmp3.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // 第112轮：只剩一张「行程卡」（原来的行程1），里程/时长走 ITripData。
        //
        // 这里用**默认布局本身**（不再手动关掉胎压卡）——
        // 第九十六轮那张图要同时塞行程1 + 行程2 两张卡，位置不够才把胎压卡关掉；
        // 第112轮行程2 没了，位置腾出来了，效果图必须跟真机一致：
        // **续航 / 音乐 / 空调 / 胎压 / 行程卡** 五张。
        val trips = CardLayout.DEFAULT
        scenePage.cardLayout = trips
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val bmp4 = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp4))
        FileOutputStream(File(dir, "y13-trip-cards.png")).use {
            bmp4.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // 回归：默认布局**必须**把胎压卡画出来。
        //
        // 第112轮出过一次乌龙 —— 渲染测试为了给两张行程卡腾地方把胎压卡 `visible=false`，
        // 结果交付出去的效果图里没有胎压卡，用户以为被删了。
        // 这里把「默认布局 = 5 张可见、含胎压」钉死，别再靠肉眼。
        val visibleIds = trips.filter { it.visible }.map { it.id }
        assertEquals(
            listOf(
                CardLayout.RANGE, CardLayout.MUSIC, CardLayout.CLIMATE,
                CardLayout.TIRE, CardLayout.TRIP_CARD,
            ),
            visibleIds,
        )
        assertTrue(
            "胎压卡必须在默认布局里可见",
            trips.any { it.id == CardLayout.TIRE && it.visible },
        )
        // 像素级：胎压卡那块**真的画了东西**（不是只排在布局里）。
        // 反射拿 `ScenePage.cardRects`（真实排布结果），取胎压卡的矩形。
        val rectsField = com.cardesk.launcher.ui.pages.ScenePage::class.java
            .getDeclaredField("cardRects").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cardRects = rectsField.get(scenePage) as Map<String, android.graphics.RectF>
        val tireRect = cardRects[CardLayout.TIRE]
        assertNotNull("胎压卡应该在排布结果里", tireRect)
        var tirePixels = 0
        for (y in tireRect!!.top.toInt() until tireRect.bottom.toInt()) {
            for (x in tireRect.left.toInt() until tireRect.right.toInt()) {
                if (bmp4.getPixel(x, y) != 0) tirePixels++
            }
        }
        assertTrue("胎压卡那块应该画出了内容（实际 $tirePixels 像素）", tirePixels > 0)

        // 布局落盘往返：下一个进程读回来还是这份布局。
        val prefs = Prefs(activity.applicationContext)
        CardLayout.PrefsStore(prefs).save(custom)
        val loaded = CardLayout.PrefsStore(prefs).load()
        assertNotNull("布局应能读回", loaded)
        assert(loaded!!.none { it.id == CardLayout.TIRE && it.visible })
    }

    /**
     * 第111轮效果图：
     * - `preview/y14-settings-cards.png` —— 设置页「桌面卡片」页（新增「车况」独立开关）；
     * - `preview/y15-scene-no-car-status.png` —— 关掉右侧车况卡后的桌面。
     */
    @Test
    fun 车况卡开关效果图() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()

        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)

        // —— 设置页「桌面卡片」——
        LauncherView::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }
            .invoke(launcher)
        shadowOf(Looper.getMainLooper()).idle()
        val overlayField = LauncherView::class.java.getDeclaredField("settings").apply { isAccessible = true }
        val overlay = overlayField.get(launcher) as? com.cardesk.launcher.ui.SettingsOverlay
        assertNotNull("设置页应该打开了", overlay)
        overlay!!.showPageKey("cards")
        shadowOf(Looper.getMainLooper()).idle()
        val dir = File("../preview").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, "y14-settings-cards.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // 关掉设置页，接着拍桌面
        LauncherView::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }
            .invoke(launcher)
        shadowOf(Looper.getMainLooper()).idle()

        // —— 关掉右侧车况卡 ——
        val field = LauncherView::class.java.getDeclaredField("scenePage").apply { isAccessible = true }
        val scenePage = field.get(launcher) as com.cardesk.launcher.ui.pages.ScenePage
        scenePage.showCarStatusCard = false
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val dir2 = File("../preview").apply { mkdirs() }
        val bmp2 = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp2))
        FileOutputStream(File(dir2, "y15-scene-no-car-status.png")).use {
            bmp2.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
