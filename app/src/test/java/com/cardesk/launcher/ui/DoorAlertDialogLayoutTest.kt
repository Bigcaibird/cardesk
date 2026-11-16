package com.cardesk.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.DoorAlert
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.dialogs.DoorAlertDialog
import com.cardesk.launcher.ui.widget.WrapRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第188轮：「有车门未关」弹窗的排版回归。
 *
 * ## 这条用例守的是什么
 *
 * 未关的部件最多 6 个（四门 + 引擎盖 + 后备箱）。胶囊行只有 680px 宽，
 * 放不下 5 个胶囊时**必须换行**，不能把胶囊压扁。
 *
 * 修复前（横向 LinearLayout）的实测：
 *
 * | 未关处数 | 胶囊 1 | 胶囊 4 | 胶囊 5 | 胶囊 6 |
 * | --- | --- | --- | --- | --- |
 * | 4 | 150 | 150 | — | — |
 * | 5 | 150 | 150 | **16** | — |
 * | 6 | 150 | 150 | **16** | **0** |
 *
 * 被压扁的胶囊里，文字按压出来的宽度折行、竖着叠在一起并溢出胶囊边界，
 * 和旁边的胶囊糊成一团 —— 这就是用户报的「开门弹窗挤成一团」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DoorAlertDialogLayoutTest {

    /** 弹窗面板尺寸（与 `DoorAlertDialog` 的设计坐标一致）。 */
    private val panelW = 760
    private val panelH = 840

    /** 胶囊行可用宽度。 */
    private val rowW = panelW - 80

    /** 造一个「N 处未关」的弹窗并完成测量 / 布局。 */
    private fun dialogOf(
        doors: List<Boolean?>,
        hoodClosed: Boolean? = true,
        trunkClosed: Boolean? = true,
    ): DoorAlertDialog {
        val dialog = DoorAlertDialog(
            RuntimeEnvironment.getApplication(),
            Palettes.night,
            DoorAlert.openItems(doors, hoodClosed, trunkClosed),
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

    private fun View.findFirst(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).findFirst(predicate)?.let { return it }
            }
        }
        return null
    }

    private fun DoorAlertDialog.row(): WrapRow =
        findFirst { it is WrapRow } as WrapRow

    private fun DoorAlertDialog.detail(): TextView =
        findFirst { it is TextView && it.text.contains("请检查") } as TextView

    private fun DoorAlertDialog.button(): TextView =
        findFirst { it is TextView && it.text == "我知道了" } as TextView

    private fun DoorAlertDialog.title(): TextView =
        findFirst { it is TextView && (it.text == "有车门未关" || it.text.endsWith("处未关")) } as TextView

    /**
     * 核心断言：不管开几处，胶囊都保持自己的自然宽度，且都在面板内。
     */
    @Test
    fun `第188轮 胶囊不会被压扁`() {
        val cases = listOf(
            1 to Triple(listOf(false, true, true, true), true, true),
            4 to Triple(listOf(false, false, false, false), true, true),
            5 to Triple(listOf(false, false, false, false), false, true),
            6 to Triple(listOf(false, false, false, false), false, false),
        )
        for ((expect, c) in cases) {
            val dialog = dialogOf(c.first, c.second, c.third)
            val row = dialog.row()
            assertEquals("未关 $expect 处时胶囊个数不对", expect, row.childCount)

            // 自然宽度 = 第一个胶囊的宽度（所有标签都是三个字，宽度一致）。
            val natural = row.getChildAt(0).width
            assertTrue("未关 $expect 处时胶囊宽度为 0（被压扁了）", natural > 0)
            for (i in 0 until row.childCount) {
                val chip = row.getChildAt(i)
                assertEquals("未关 $expect 处时第 $i 个胶囊被压扁", natural, chip.width)
                assertTrue(
                    "未关 $expect 处时第 $i 个胶囊超出胶囊行",
                    chip.left >= 0 && chip.right <= rowW,
                )
            }
        }
    }

    /**
     * 胶囊放不下时**换行**（而不是压扁）。
     */
    @Test
    fun `第188轮 五处未关时胶囊换行`() {
        val dialog = dialogOf(listOf(false, false, false, false), hoodClosed = false)
        val row = dialog.row()
        // 一行放得下 4 个（4×150 + 3×16 = 648 ≤ 680），第 5 个必须换到第二行。
        val firstRow = row.getChildAt(0)
        val lastOfFirstRow = row.getChildAt(3)
        val wrapped = row.getChildAt(4)
        assertEquals("第 4 个胶囊和第 1 个应该在同一行", firstRow.top, lastOfFirstRow.top)
        assertTrue("第 5 个胶囊应该换到第二行", wrapped.top > lastOfFirstRow.top)
        assertTrue("胶囊行应该有两行高", row.height > 66)
    }

    /**
     * 说明文字不能被裁：文字有几行，框就得有几行高。
     */
    @Test
    fun `第188轮 说明文字不裁切`() {
        val dialog = dialogOf(listOf(false, false, false, false), hoodClosed = false, trunkClosed = false)
        val detail = dialog.detail()
        val lines = detail.layout.lineCount
        assertTrue("6 处未关时说明应该折行，实际 $lines 行", lines >= 2)
        val lastBottom = detail.layout.getLineBottom(lines - 1)
        assertTrue(
            "说明文字最后一行被裁掉了（行底 $lastBottom > 框高 ${detail.height}）",
            lastBottom <= detail.height,
        )
    }

    /**
     * 按钮不能被面板底边切掉；也不能跑到 Dock 上（面板底 840）。
     */
    @Test
    fun `第188轮 按钮不越界`() {
        val dialog = dialogOf(listOf(false, false, false, false), hoodClosed = false, trunkClosed = false)
        val button = dialog.button()
        assertTrue("按钮底边超出面板（${button.bottom} > $panelH）", button.bottom <= panelH)
        // 说明文字在按钮上面，不重叠。
        val detail = dialog.detail()
        assertTrue("说明文字和按钮重叠", detail.bottom <= button.top)
    }

    /**
     * 最常见的版式（一两扇门）**保持原样**：说明在 570、按钮在 690。
     *
     * 这是「别为了极端情况把日常版式也改了」的护栏。
     */
    @Test
    fun `第188轮 单处未关沿用原版式`() {
        val dialog = dialogOf(listOf(false, true, true, true))
        val detail = dialog.detail()
        val button = dialog.button()
        assertEquals("说明文字位置变了", 570, detail.top)
        assertEquals("按钮位置变了", 690, button.top)
    }

    /**
     * 标题跟着未关处数走。
     *
     * 之前标题是构造时算死的：**开着的东西**变了之后标题还写着旧数字
     * （「6 处未关」而实际只剩一处），这也是一种「显示异常」。
     */
    @Test
    fun `第188轮 标题跟着未关处数走`() {
        val dialog = dialogOf(listOf(false, false, false, false), hoodClosed = false, trunkClosed = false)
        assertEquals("标题没跟着未关处数更新", "6 处未关", dialog.title().text)

        // 关掉三扇门、引擎盖和后备箱 → 只剩左前门。
        dialog.update(
            DoorAlert.openItems(listOf(false, true, true, true), true, true),
            listOf(false, true, true, true),
            booleanArrayOf(false, false, false, false),
            true,
            true,
            "01",
        )
        assertEquals("标题没跟着未关处数更新", "有车门未关", dialog.title().text)
    }
}
