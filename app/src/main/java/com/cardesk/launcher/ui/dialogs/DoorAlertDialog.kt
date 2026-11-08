package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.DoorAlert
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.ui.widget.CarTopStatusView

/**
 * 「有车门未关」弹窗（第136轮新增）。
 *
 * ## 什么时候弹
 *
 * 车况信号里有**开着**的门 / 引擎盖 / 后备箱时弹（判定见 [DoorAlert]）。
 * 读不到的部件**不算**开着 —— 宁可晚点提醒，也不误报。
 *
 * ## 什么时候自己关
 *
 * - **所有部件都关上** → 上层（`LauncherView`）会把这个弹窗撤掉（自动关闭）；
 * - **用户手动点关闭** → 撤掉，并且这一轮「未关」期间不再打扰
 *   （直到全部关上、再次打开才会重新弹）。
 *
 * ## 长什么样
 *
 * 内容用**和桌面车况卡同一套车模**（[CarTopStatusView] → [com.cardesk.launcher.core.CarTopPainter]），
 * 开着的门在车模上**橙色高亮、门板摆出去**，一眼能看出是哪一扇门开着；
 * 下面再列一遍**具体是哪几个**（左前门 / 引擎盖 …），文字与图形互相印证。
 */
class DoorAlertDialog(
    context: Context,
    pal: Palette,
    openItems: List<DoorAlert.Item>,
    doorsClosed: List<Boolean?>?,
    windowsOpen: BooleanArray?,
    hoodClosed: Boolean?,
    trunkClosed: Boolean?,
    carModel: String,
) : DialogSheet(context, pal, PANEL_W, PANEL_H, DoorAlert.title(openItems.size), PANEL_LEFT, PANEL_TOP) {

    /** 车模视图（开着的高亮）。 */
    private val car = CarTopStatusView(context)

    /** 未关部件的胶囊行。 */
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    /** 说明文字。 */
    private val detail: TextView = Ui.text(context, DoorAlert.detail(openItems), 22f, pal.textDim)

    init {
        addTitle()
        addClose()

        place(car, CAR_LEFT, CAR_TOP, CAR_W, CAR_H)
        place(row, 40, LIST_TOP, PANEL_W - 80, LIST_H)
        place(detail, 40, DETAIL_TOP, PANEL_W - 80, 40)

        // —— 底部按钮：手动关闭 ——
        place(
            primaryButton("我知道了", 24f) { onClose?.invoke() },
            PANEL_W - 40 - 220, BOTTOM_TOP, 220, 66,
        )

        render(openItems, doorsClosed, windowsOpen, hoodClosed, trunkClosed, carModel)
    }

    /**
     * 更新内容（开着的东西变了：又多开了一扇门 / 关掉了一扇）。
     *
     * 上层每秒同步一次车门状态，弹窗开着的时候要跟着变，
     * 否则会出现「弹窗说有 3 个没关，其实只剩 1 个」这种过期信息。
     */
    fun update(
        openItems: List<DoorAlert.Item>,
        doorsClosed: List<Boolean?>?,
        windowsOpen: BooleanArray?,
        hoodClosed: Boolean?,
        trunkClosed: Boolean?,
        carModel: String,
    ) {
        render(openItems, doorsClosed, windowsOpen, hoodClosed, trunkClosed, carModel)
    }

    private fun render(
        openItems: List<DoorAlert.Item>,
        doorsClosed: List<Boolean?>?,
        windowsOpen: BooleanArray?,
        hoodClosed: Boolean?,
        trunkClosed: Boolean?,
        carModel: String,
    ) {
        car.carModel = carModel
        car.doorsClosed = doorsClosed
        car.windowsOpen = windowsOpen
        car.hoodClosed = hoodClosed
        car.trunkClosed = trunkClosed
        car.invalidate()

        row.removeAllViews()
        openItems.forEachIndexed { index, item ->
            val chip = Ui.text(context, item.label, 24f, pal.warn, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, pal.warn)
                setPadding(26, 12, 26, 12)
                pressFeedback()
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, 58).apply {
                if (index > 0) marginStart = 14
            })
        }

        detail.text = DoorAlert.detail(openItems)
    }

    private companion object {
        /**
         * 面板尺寸（设计坐标 1920×1080）。
         *
         * 高度是算过的：**面板底边必须落在 Dock 之上**，
         * 否则底部「我知道了」按钮会被 Dock 挡住（第一版就是这么翻车的：
         * 面板 120..940，按钮落在 930..996，正好压在 Dock 里）。
         * 现在面板 70..910，底部按钮 800..866，离 Dock（~960）还有一段。
         */
        const val PANEL_W = 760
        const val PANEL_H = 840

        /** 面板位置（居中偏上，给 Dock 让位）。 */
        const val PANEL_LEFT = 580
        const val PANEL_TOP = 70

        /** 车模视图（设计空间 120×240，正好 1:2）。 */
        const val CAR_W = 280
        const val CAR_H = 500
        const val CAR_LEFT = 240
        const val CAR_TOP = 100

        /** 开门清单 / 说明 / 按钮的纵向位置（面板内坐标）。 */
        const val LIST_TOP = 610
        const val LIST_H = 60
        const val DETAIL_TOP = 678
        const val BOTTOM_TOP = 730
    }
}
