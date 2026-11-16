package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.dialogs.VehicleStatusDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第189轮：「车辆状态」面板版式重排的回归。
 *
 * ## 这条用例守的是什么
 *
 * 用户原话：「车况卡详情页有两个关闭，去掉一个，同时调整一下文字布局，
 * 左右侧车窗门的文字提示分别放到左右两侧，后备箱的放到卡片下面」。
 *
 * 对应三件事：
 * 1. 面板里**只有一个**「关闭」（右上角那个胶囊）；
 * 2. 左列只放**车的左边**的门/窗、右列只放**车的右边**的门/窗；
 * 3. 「后备箱」单独放在车模**下面**一行。
 *
 * ## 为什么用像素判位置
 *
 * 这些标签是**画在 Canvas 上的**（不是子 View），视图树里查不到。
 * 所以这里把车模视图画进 Bitmap，按区域数「告警色像素」：
 * 左前门开 → 左列有橙色、右列没有；右前门开 → 反过来。
 *
 * 颜色口径（夜间调色板）：
 * - 车门/尾门开 = 橙 `#FF9F43`；
 * - 车窗开 = 黄 `#F5C542`（绿通道 197，比橙的 159 高，用它区分）。
 * - 关着的项是暗色灰（红通道 < 200），不会被误判。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleStatusDialogLayoutTest {

    /** 面板宽（设计 px）。 */
    private val panelW = 760

    /** 面板高（第189轮：720 → 660，底部那个「关闭」按钮删了）。 */
    private val panelH = 660

    /** 车模视图宽（= 左右标签列 + 车模）。 */
    private val carW = 712

    /** 车模视图高。 */
    private val carH = 470

    /** 车模区高度（底部 52px 留给「后备箱」那一行）。 */
    private val carAreaH = 418

    /** 左侧标签列的扫描范围（x < 140：车模图从 x=201 才开始）。 */
    private val leftColX = 0 until 140

    /** 右侧标签列的扫描范围（车模图到 x=511 结束，右列从 594 开始）。 */
    private val rightColX = 572 until carW

    private fun dialogOf(
        doors: List<Boolean?>,
        windows: BooleanArray = booleanArrayOf(false, false, false, false),
        trunkClosed: Boolean? = true,
    ): VehicleStatusDialog {
        val dialog = VehicleStatusDialog(
            RuntimeEnvironment.getApplication(),
            Palettes.night,
            doors,
            windows,
            true,
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

    // ---------------------------------------------------------------- 视图树

    private fun collect(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun texts(root: View): List<TextView> =
        collect(root).filterIsInstance<TextView>()

    private fun bodyOf(dialog: VehicleStatusDialog): ViewGroup {
        val f = com.cardesk.launcher.ui.dialogs.DialogSheet::class.java
            .getDeclaredField("body").apply { isAccessible = true }
        return f.get(dialog) as ViewGroup
    }

    private fun sheetOf(dialog: VehicleStatusDialog): View {
        val f = com.cardesk.launcher.ui.dialogs.DialogSheet::class.java
            .getDeclaredField("sheet").apply { isAccessible = true }
        return f.get(dialog) as View
    }

    /** 车模视图（body 里唯一的非 TextView 子节点）。 */
    private fun carView(dialog: VehicleStatusDialog): View =
        (0 until bodyOf(dialog).childCount)
            .map { bodyOf(dialog).getChildAt(it) }
            .first { it !is TextView }

    private fun carBitmap(dialog: VehicleStatusDialog): Bitmap {
        val car = carView(dialog)
        val bmp = Bitmap.createBitmap(car.width, car.height, Bitmap.Config.ARGB_8888)
        car.draw(Canvas(bmp))
        return bmp
    }

    // ---------------------------------------------------------------- 像素统计

    /** 数「告警色像素」：红高、绿在 [120,185]、蓝低 → 橙色（门/尾箱开）。 */
    private fun orangeCount(bmp: Bitmap, xs: IntRange, ys: IntRange): Int {
        var n = 0
        for (y in ys) {
            for (x in xs) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 200 && g in 120..185 && b < 120) n++
            }
        }
        return n
    }

    /** 数「黄色像素」（车窗开）：红高、绿在 [186,225]、蓝低。 */
    private fun yellowCount(bmp: Bitmap, xs: IntRange, ys: IntRange): Int {
        var n = 0
        for (y in ys) {
            for (x in xs) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 200 && g in 186..225 && b < 120) n++
            }
        }
        return n
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 第189轮：面板里只剩**一个**「关闭」。
     */
    @Test
    fun `第189轮 面板里只有一个关闭`() {
        val dialog = dialogOf(listOf(true, true, true, true))
        val closes = texts(dialog).filter { it.text == "关闭" }
        assertEquals(
            "「车辆状态」面板里应该只有一个「关闭」（底部那个重复的主按钮要删掉）",
            1,
            closes.size,
        )
    }

    /**
     * 面板高度收窄了，说明文字还在面板里。
     */
    @Test
    fun `第189轮 面板收窄且说明文字不越界`() {
        val dialog = dialogOf(listOf(true, true, true, true))
        assertEquals("面板高度应该是 660（底部按钮删掉后收上来）", panelH, sheetOf(dialog).height)
        val hint = texts(dialog).first { it.text.contains("引擎盖无数据通道") }
        assertTrue("说明文字超出面板（${hint.bottom} > $panelH）", hint.bottom <= panelH)
    }

    /**
     * 左前门开 → **左列**高亮，右列不动。
     */
    @Test
    fun `第189轮 左门开只高亮左列`() {
        val dialog = dialogOf(listOf(false, true, true, true))
        val bmp = carBitmap(dialog)
        assertEquals("车模视图尺寸不对", carW, bmp.width)
        assertEquals("车模视图尺寸不对", carH, bmp.height)
        assertTrue(
            "左前门开着，左列应该有橙色高亮",
            orangeCount(bmp, leftColX, 0 until carAreaH) > 0,
        )
        assertEquals(
            "右列不该有橙色高亮（右前门是关的）",
            0,
            orangeCount(bmp, rightColX, 0 until carAreaH),
        )
    }

    /**
     * 右前门开 → **右列**高亮，左列不动。
     */
    @Test
    fun `第189轮 右门开只高亮右列`() {
        val dialog = dialogOf(listOf(true, false, true, true))
        val bmp = carBitmap(dialog)
        assertEquals(
            "左列不该有橙色高亮（左前门是关的）",
            0,
            orangeCount(bmp, leftColX, 0 until carAreaH),
        )
        assertTrue(
            "右前门开着，右列应该有橙色高亮",
            orangeCount(bmp, rightColX, 0 until carAreaH) > 0,
        )
    }

    /**
     * 车窗开 → **黄色**高亮，而且落在对应的那一侧。
     */
    @Test
    fun `第189轮 车窗开高亮在对应一侧`() {
        // 左前窗开（windows 下标 = FL/FR/RL/RR，0 = 左前窗）。
        val left = dialogOf(
            listOf(true, true, true, true),
            windows = booleanArrayOf(true, false, false, false),
        )
        val leftBmp = carBitmap(left)
        assertTrue(
            "左前窗开着，左列应该有黄色高亮",
            yellowCount(leftBmp, leftColX, 0 until carAreaH) > 0,
        )
        assertEquals(
            "右列不该有黄色高亮",
            0,
            yellowCount(leftBmp, rightColX, 0 until carAreaH),
        )

        // 右前窗开（windows 下标 1 = 右前窗）。
        val right = dialogOf(
            listOf(true, true, true, true),
            windows = booleanArrayOf(false, true, false, false),
        )
        val rightBmp = carBitmap(right)
        assertEquals(
            "左列不该有黄色高亮",
            0,
            yellowCount(rightBmp, leftColX, 0 until carAreaH),
        )
        assertTrue(
            "右前窗开着，右列应该有黄色高亮",
            yellowCount(rightBmp, rightColX, 0 until carAreaH) > 0,
        )
    }

    /**
     * 后备箱开 → 高亮落在**车模下面**那一行（底部 52px），不在左右列里。
     */
    @Test
    fun `第189轮 后备箱高亮在车模下面`() {
        val dialog = dialogOf(listOf(true, true, true, true), trunkClosed = false)
        val bmp = carBitmap(dialog)
        assertTrue(
            "后备箱开着，车模下面那一行应该有橙色高亮",
            orangeCount(bmp, 0 until carW, carAreaH until carH) > 0,
        )
        assertEquals(
            "后备箱不该在左右两列里高亮",
            0,
            orangeCount(bmp, leftColX, 0 until carAreaH),
        )
        assertEquals(
            "后备箱不该在左右两列里高亮",
            0,
            orangeCount(bmp, rightColX, 0 until carAreaH),
        )
    }

    /**
     * 面板宽没变（左右列 + 车模还是塞得下）。
     */
    @Test
    fun `第189轮 面板宽度不变`() {
        val dialog = dialogOf(listOf(true, true, true, true))
        assertEquals("面板宽度应该是 760", panelW, sheetOf(dialog).width)
    }
}
