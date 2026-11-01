package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.dialogs.DriveModeDialog
import com.cardesk.launcher.vehicle.DriveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 驾驶模式快捷面板（第九十五轮补丁）的渲染与交互测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveModePanelTest {

    private fun texts(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun find(root: View, label: String): TextView? {
        if (root is TextView && root.text?.toString() == label) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), label)?.let { return it }
        }
        return null
    }

    private fun layout(dialog: DriveModeDialog) {
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        dialog.layout(0, 0, 1920, 1080)
    }

    @Test
    fun 面板列出全部模式并高亮当前项() {
        val context = RuntimeEnvironment.getApplication()
        val dialog = DriveModeDialog(
            context,
            Palettes.night,
            DriveMode.SELECTION_SPORT,
            onSwitch = {},
            onMoreSettings = {},
        )
        layout(dialog)
        val all = texts(dialog)
        assertTrue("应显示标题", all.any { it == "驾驶模式" })
        assertTrue("应显示当前模式", all.any { it.startsWith("当前模式") && it.contains("运动模式") })
        DriveMode.UI_MODE_LABELS.forEach { label ->
            assertTrue("缺少模式：$label", all.any { it == label })
        }

        val dir = File("../preview").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        dialog.draw(Canvas(bitmap))
        FileOutputStream(File(dir, "y10-drive-mode-panel.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun 点模式会回调切换并且结果能回填() {
        val context = RuntimeEnvironment.getApplication()
        var picked = -1
        val dialog = DriveModeDialog(
            context,
            Palettes.night,
            DriveMode.SELECTION_ECO,
            onSwitch = { picked = it },
            onMoreSettings = {},
        )
        layout(dialog)
        find(dialog, "运动模式")?.performClick()
        assertEquals(DriveMode.SELECTION_SPORT, picked)
        assertTrue(texts(dialog).any { it.contains("正在切换") })

        dialog.showResult(false, "切换失败：这台车机不允许桌面直接写驾驶模式")
        assertTrue(texts(dialog).any { it.contains("切换失败") })
    }
}
