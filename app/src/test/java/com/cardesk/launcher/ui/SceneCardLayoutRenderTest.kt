package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.CardLayout
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertNotNull
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
            CardLayout.withVisible(custom, CardLayout.TRIP1, true),
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

        // 第九十六轮：行程1（总）/ 行程2（自复位）两张卡，行程2 右上角带「复位」胶囊。
        val trips = CardLayout.withVisible(
            CardLayout.withVisible(
                CardLayout.withVisible(CardLayout.DEFAULT, CardLayout.TRIP1, true),
                CardLayout.TRIP2,
                true,
            ),
            CardLayout.TIRE,
            false,
        )
        scenePage.cardLayout = trips
        shadowOf(Looper.getMainLooper()).idle()
        decor.invalidate()
        val bmp4 = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp4))
        FileOutputStream(File(dir, "y13-trip-cards.png")).use {
            bmp4.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // 布局落盘往返：下一个进程读回来还是这份布局。
        val prefs = Prefs(activity.applicationContext)
        CardLayout.PrefsStore(prefs).save(custom)
        val loaded = CardLayout.PrefsStore(prefs).load()
        assertNotNull("布局应能读回", loaded)
        assert(loaded!!.none { it.id == CardLayout.TIRE && it.visible })
    }
}
