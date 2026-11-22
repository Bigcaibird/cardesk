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

    // `DoorAlertDialog` 的几何常量在 private companion 里，测试拿不到；
    // 这里按源文件的值写字面量，并标注出处（改产品常量时这里必须跟着改）。
    /** 弹窗面板尺寸：`DoorAlertDialog.PANEL_W / PANEL_H` = 881×489 @ (519,271)。 */
    private val panelW = 881
    private val panelH = 489

    /** 胶囊行可用宽度：`ROW_W = PANEL_W - SIDE(42) * 2` = 797。 */
    private val rowW = 797

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
     * 第242轮：面板从 760×840 收成 881×489，胶囊行宽 797、胶囊高 48 ——
     * **6 处未关也必须排在一行**（画板口径），不许换行、也不许压扁。
     */
    @Test
    fun `第242轮 六处未关也排在一行`() {
        val dialog = dialogOf(listOf(false, false, false, false), hoodClosed = false, trunkClosed = false)
        val row = dialog.row()
        assertEquals("6 处未关时胶囊个数不对", 6, row.childCount)
        val firstTop = row.getChildAt(0).top
        for (i in 0 until row.childCount) {
            assertEquals(
                "第 $i 个胶囊换行了（画板要求 6 个排一行）",
                firstTop,
                row.getChildAt(i).top,
            )
            assertTrue(
                "第 $i 个胶囊超出胶囊行",
                row.getChildAt(i).left >= 0 && row.getChildAt(i).right <= rowW,
            )
        }
        assertEquals("胶囊行高应该就是一个胶囊高", 48, row.height)
    }

    /**
     * 说明文字不能被裁：文字有几行，框就得有几行高。
     */
    @Test
    fun `第242轮 说明文字不裁切`() {
        val dialog = dialogOf(listOf(false, false, false, false), hoodClosed = false, trunkClosed = false)
        val detail = dialog.detail()
        val lines = detail.layout.lineCount
        // 新版面板窄而矮（881×489），说明文案一行就够（画板实测 1 行）。
        assertEquals("6 处未关时说明应该是一行，实际 $lines 行", 1, lines)
        val lastBottom = detail.layout.getLineBottom(lines - 1)
        // 框高是设计板给的 DETAIL_H=26（DETAIL_SP=20）。Robolectric 的通用字体
        // 行高是 1.45em（≈29），设备上的 Noto Sans CJK 只有 ≈1.17em（≈23），
        // 所以这里按「最多只允许一个 descent 的下缘溢出」判：
        // 超出超过一个 descent 就说明字形本体（不只是下缘留白）被切了，那才是真裁切。
        val overflow = lastBottom - detail.height
        val descent = kotlin.math.ceil(detail.paint.fontMetrics.descent.toDouble()).toInt()
        assertTrue(
            "说明文字最后一行被裁掉了（行底 $lastBottom / 框高 ${detail.height}，" +
                "超出 $overflow 超过一个 descent $descent）",
            overflow <= descent,
        )
        assertEquals(
            "说明不该被省略号截断",
            0,
            detail.layout.getEllipsisCount(lines - 1),
        )
    }

    /**
     * 按钮不能被面板底边切掉；也不能跑到 Dock 上（面板底 489）。
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
     * 最常见的版式（一两扇门）**保持画板版式**：说明在 DETAIL_TOP、按钮在 BUTTON_TOP。
     *
     * 这是「别为了极端情况把日常版式也改了」的护栏。
     */
    @Test
    fun `第188轮 单处未关沿用原版式`() {
        val dialog = dialogOf(listOf(false, true, true, true))
        val detail = dialog.detail()
        val button = dialog.button()
        assertEquals("说明文字位置变了", 86, detail.top)
        assertEquals("按钮位置变了", 400, button.top)
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
