package com.cardesk.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.dialogs.VehicleStatusDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第188轮：「车辆状态」面板的标题要跟着门态走。
 *
 * 之前标题是构造时算死的 —— 面板开着的时候门全关上了，
 * 标题还写着「有车门未关」，这是另一种「显示异常」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleStatusDialogTitleTest {

    private fun dialogOf(
        doors: List<Boolean?>,
        hoodClosed: Boolean? = true,
        trunkClosed: Boolean? = true,
    ): VehicleStatusDialog {
        val dialog = VehicleStatusDialog(
            RuntimeEnvironment.getApplication(),
            Palettes.night,
            doors,
            booleanArrayOf(false, false, false, false),
            hoodClosed,
            trunkClosed,
            "01",
        )
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        dialog.layout(0, 0, 1920, 1080)
        return dialog
    }

    private fun View.titleText(): String {
        fun walk(v: View): TextView? {
            if (v is TextView && (v.text == "有车门未关" || v.text == "车辆状态")) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(this)!!.text.toString()
    }

    @Test
    fun `第188轮 标题跟着门态走`() {
        val dialog = dialogOf(listOf(false, true, true, true))
        assertEquals("有车门未关", dialog.titleText())

        // 门全关上 → 标题要变。
        dialog.update(
            listOf(true, true, true, true),
            booleanArrayOf(false, false, false, false),
            true,
            true,
            "01",
        )
        assertEquals("车辆状态", dialog.titleText())

        // 又开一扇 → 变回来。
        dialog.update(
            listOf(false, true, true, true),
            booleanArrayOf(false, false, false, false),
            true,
            true,
            "01",
        )
        assertEquals("有车门未关", dialog.titleText())
    }
}
