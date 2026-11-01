package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.nav.AmapTrafficLight
import com.cardesk.launcher.core.nav.LaneShape
import com.cardesk.launcher.core.nav.LaneTile
import com.cardesk.launcher.core.nav.DesktopGuide
import com.cardesk.launcher.core.nav.NavGuideModel
import com.cardesk.launcher.core.nav.NavGuideText
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
 * 第九十九轮效果图：
 *
 * - `preview/y22-cruise-card.png`：巡航卡（道路 + 车道线 + 左/直/右三枚红绿灯）
 * - `preview/y22-nav-lanes.png`：导航卡带车道线（组合箭头）
 * - `preview/y22-lane-arrows.png`：9 种车道箭头大图（检查转弯箭头在直行箭头下方）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavCruiseRenderTest {

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun renderDecor(activity: MainActivity, name: String) {
        val decor = activity.window.decorView
        decor.invalidate()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        val dir = File("../preview").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun 巡航卡与车道箭头渲染() {
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

        // ① 巡航卡：道路 + 车道线（左/直/右箭头）+ 三枚不同灯态/读秒
        val lights = listOf(
            AmapTrafficLight.DirectionLight(
                direction = AmapTrafficLight.DIR_LEFT,
                lamp = AmapTrafficLight.Lamp.RED,
                countdownSeconds = 20,
                rawStatus = 0,
            ),
            AmapTrafficLight.DirectionLight(
                direction = AmapTrafficLight.DIR_STRAIGHT,
                lamp = AmapTrafficLight.Lamp.GREEN,
                countdownSeconds = 12,
                rawStatus = 1,
            ),
            AmapTrafficLight.DirectionLight(
                direction = AmapTrafficLight.DIR_RIGHT,
                lamp = AmapTrafficLight.Lamp.UNKNOWN,
                countdownSeconds = null,
                rawStatus = 2,
            ),
        )
        scenePage.setGuide(
            NavGuideModel.buildCruise(
                icon = 2,
                road = "人民路",
                lanesRaw = "15,1,3",
                lights = lights,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        renderDecor(activity, "y22-cruise-card.png")

        // ② 导航卡带组合车道线（直行+左转/直行+右转/直行+左右/掉头…）
        val lanes = listOf(
            LaneTile(15, true),   // 直行（高亮）
            LaneTile(1, false),   // 左转
            LaneTile(3, false),   // 右转
            LaneTile(2, false),   // 直行+左转
            LaneTile(4, false),   // 直行+右转
            LaneTile(5, false),   // 直行+左转+右转
            LaneTile(6, false),   // 掉头
            LaneTile(0, false),   // 直行（后端）
        )
        scenePage.setGuide(
            DesktopGuide(
                segmentText = "500",
                segmentUnit = "米",
                road = "向左 进入 金峰北路",
                remainKm = "12.4",
                remainTime = "18 分",
                etaTime = "18:42",
                icon = NavGuideText.GuideIcon.LEFT,
                lanes = lanes,
                trafficLight = AmapTrafficLight.Signal(
                    lamp = AmapTrafficLight.Lamp.GREEN,
                    countdownSeconds = 12,
                    rawStatus = 4,
                    rawDirection = 1,
                    enumVerified = true,
                ),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        renderDecor(activity, "y22-nav-lanes.png")

        // ③ 9 种车道箭头大图（反射调用私有 drawLaneGlyph）
        val method = com.cardesk.launcher.ui.pages.ScenePage::class.java.getDeclaredMethod(
            "drawLaneGlyph",
            Canvas::class.java,
            LaneShape::class.java,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val bmp = Bitmap.createBitmap(1920, 460, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0B0F14.toInt())
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA4B2.toInt()
            textSize = 20f
        }
        val shapes = LaneShape.values()
        shapes.forEachIndexed { i, shape ->
            val col = i % 5
            val row = i / 5
            val x = 120f + col * 350f
            val y = 90f + row * 220f
            method.invoke(scenePage, canvas, shape, x, y, 140f, 0xFFFFFFFF.toInt())
            canvas.drawText(shape.name, x, y + 200f, label)
        }
        val dir = File("../preview").apply { mkdirs() }
        FileOutputStream(File(dir, "y22-lane-arrows.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // 实际 chip 尺寸（38px）单独一排，检查真实大小下的间距。
        val small = Bitmap.createBitmap(1920, 120, Bitmap.Config.ARGB_8888)
        val smallCanvas = Canvas(small)
        smallCanvas.drawColor(0xFF0B0F14.toInt())
        shapes.forEachIndexed { i, shape ->
            method.invoke(scenePage, smallCanvas, shape, 120f + i * 110f, 40f, 38f, 0xFFFFFFFF.toInt())
        }
        FileOutputStream(File(dir, "y22-lane-arrows-small.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
