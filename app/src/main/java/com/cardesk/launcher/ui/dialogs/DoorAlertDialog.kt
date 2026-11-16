package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.CarStatusItem
import com.cardesk.launcher.core.DoorAlert
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.ui.widget.CarTopStatusView
import com.cardesk.launcher.core.car3d.CardSnapshot
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.Car3dPainter
import com.cardesk.launcher.core.car3d.Car3dSettings
import com.cardesk.launcher.core.car3d.CarPose
import com.cardesk.launcher.core.car3d.Car3dDebugPose

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
 * 第164轮起车模换成**左前 3/4 视角的写实烘焙大图**（[CarTopStatusView.view] = `hero`）：
 * 俯视图在 280×500 的竖槽里既看不清门也看不见窗，而这个弹窗是用户判断
 * 「到底哪里没关」的地方 —— 3/4 视角能同时看到车门、车窗和车头，信息量最大。
 * 资产缺失/车型没烘焙时逐级回退（hero → top → 自绘），不会空白。
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

        car.view = com.cardesk.launcher.core.car3d.Car3dAssets.VIEW_DIALOG
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

        /**
         * 车模视图：**横放**的 3/4 大图（712×404；hero 源图等比放进、居中留白）。
         *
         * 第164轮下半版式压缩：车模下边缘 84+404=488，胶囊行 500..560、说明 566..606、
         * 按钮 690..756，删掉原来「说明 572 / 按钮 730」之间约 170px 的空档。
         *
         * 第164轮从「竖着的俯视图 280×500」改过来：3/4 视角是本弹窗的门面，
         * 也要给下面的胶囊行/说明腾出纵向空间。
         */
        const val CAR_W = 712
        const val CAR_H = 404
        const val CAR_LEFT = 24
        const val CAR_TOP = 84

        /** 开门清单 / 说明 / 按钮的纵向位置（面板内坐标）。 */
        const val LIST_TOP = 500
        const val LIST_H = 60
        const val DETAIL_TOP = 566
        const val BOTTOM_TOP = 690
    }
}

/**
 * 「车辆状态」面板（第166轮 C 版车况卡点开）。
 *
 * 版式复用 [DoorAlertDialog]（同一套「浮屿」面板 + 3/4 车模），
 * 但内容换成**10 项逐项状态全列表**（原来平铺在车况卡上的两列文字都搬到这里）：
 *
 * ```
 * 有车门未关 / 车辆状态            ← 标题
 * ┌───────────────────────────┐
 * │      dialog 车模小图        │   ← 340×300 槽（缺 dialog 资产时回退 hero）
 * └───────────────────────────┘
 * 左前门 开   右前门 关
 * 左前窗 关   右前窗 关
 * ...
 *                         [关闭]
 * ```
 *
 * 读不到的项显示 `--`（不猜），与车况卡/`VehicleStats` 口径一致。
 */
class VehicleStatusDialog(
    context: Context,
    pal: Palette,
    doorsClosed: List<Boolean?>?,
    windowsOpen: BooleanArray?,
    hoodClosed: Boolean?,
    trunkClosed: Boolean?,
    carModel: String,
) : DialogSheet(
    context,
    pal,
    PANEL_W,
    VEHICLE_PANEL_H,
    if (hasOpenEffective(doorsClosed, hoodClosed, trunkClosed)) "有车门未关" else "车辆状态",
    PANEL_LEFT,
    PANEL_TOP,
) {

    /** 大图 + 逐项标注（门/窗/尾门在图上用标签+引线，不再两列文字）。 */
    private val car = AnnotatedCarView(context, pal)

    private val hint = Ui.text(
        context,
        "引擎盖无数据通道 · 开着的车门/车窗在图上高亮",
        12f,
        Skin.TEXT_DIM,
    )

    init {
        addTitle()
        addClose()

        place(car, CAR_LEFT, CAR_TOP, CAR_W, CAR_H)
        place(hint, 40, HINT_TOP, PANEL_W - 80, 28)
        place(
            primaryButton("关闭", 24f) { onClose?.invoke() },
            PANEL_W - 40 - 220, VEHICLE_BOTTOM_TOP, 220, 66,
        )
        update(doorsClosed, windowsOpen, hoodClosed, trunkClosed, carModel)
    }


    /** 每秒由 `LauncherView` 同步（面板开着时状态要跟着变）。 */
    fun update(
        doorsClosed: List<Boolean?>?,
        windowsOpen: BooleanArray?,
        hoodClosed: Boolean?,
        trunkClosed: Boolean?,
        carModel: String,
    ) {
        // 调试姿态注入时文字与车模保持一致（每秒的 1Hz 同步也不能把它冲掉）。
        val dp = Car3dDebugPose.pose
        car.setState(
            dp?.doors?.map { !it } ?: doorsClosed,
            dp?.windows ?: windowsOpen,
            hoodClosed,
            dp?.trunk?.let { !it } ?: trunkClosed,
            carModel,
        )
    }

    private companion object {
        const val PANEL_W = 760
        const val PANEL_H = 840
        const val PANEL_LEFT = 580
        const val PANEL_TOP = 70

        /** dialog 车模槽（340×300 的资产等比放进 712×330）。 */
        const val CAR_W = 712
        const val CAR_H = 430
        const val CAR_LEFT = 24
        const val CAR_TOP = 84

        /** 车辆状态面板比门未关弹窗矮：没有胶囊行/列表，只有大图 + 一行说明。 */
        const val VEHICLE_PANEL_H = 720
        const val HINT_TOP = 520
        const val VEHICLE_BOTTOM_TOP = 620

        fun hasOpen(doorsClosed: List<Boolean?>?, hoodClosed: Boolean?, trunkClosed: Boolean?): Boolean {
            if (doorsClosed?.any { it == false } == true) return true
            return hoodClosed == false || trunkClosed == false
        }

        /** 同 [hasOpen]，但调试姿态注入时以注入值为准（模拟器截图用）。 */
        fun hasOpenEffective(
            doorsClosed: List<Boolean?>?,
            hoodClosed: Boolean?,
            trunkClosed: Boolean?,
        ): Boolean {
            val dp = Car3dDebugPose.pose
                ?: return hasOpen(doorsClosed, hoodClosed, trunkClosed)
            return dp.anyDoorOpen || dp.trunk
        }
    }
}

/**
 * 车辆状态大图 + 逐项标注（第169轮）。
 *
 * 在 hero 大图上按资产 `anchors.hero` 画 4 门 / 4 窗 / 尾门的标签与引线：
 * 开着的项高亮（车门橙、**车窗黄**、尾门橙），关着的项暗色；允许标签压车体。
 * 引擎盖没有数据通道，图上不标（由底部小字说明）。
 */
private class AnnotatedCarView(context: Context, private val pal: Palette) : View(context) {

    private val car3d = Car3dPainter.Scene(this)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)

    private var items: List<CarStatusItem> = emptyList()
    private var dir: String? = null
    private var cardtop = false

    fun setState(
        doorsClosed: List<Boolean?>?,
        windowsOpen: BooleanArray?,
        hoodClosed: Boolean?,
        trunkClosed: Boolean?,
        carModel: String,
    ) {
        items = com.cardesk.launcher.core.CarStatus.items(doorsClosed, windowsOpen, hoodClosed, trunkClosed)
        dir = CarModels.assetDir(carModel)
        // 第179轮：资产驱动 —— 纯俯视资产（只声明 cardtop）的车型弹窗也走俯视；
        // 三视角齐全的老资产继续用 hero。
        cardtop = Car3dAssets.topDownOnly(context, dir)
        if (Car3dSettings.enabled) {
            car3d.setPose(
                context,
                dir,
                CarPose.of(doorsClosed, windowsOpen, hoodClosed, trunkClosed),
                rich = true,
                view = if (cardtop) Car3dAssets.VIEW_CARDTOP else Car3dAssets.VIEW_HERO,
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || items.size < 10) return
        val d = dir
        val view = if (cardtop) Car3dAssets.VIEW_CARDTOP else Car3dAssets.VIEW_HERO
        val usedView = Car3dAssets.usedView(context, d, view)
        val slot = Car3dAssets.slotOf(context, d, usedView)

        // 第178轮：俯视横置图 + 上下两条标签带（上行门/尾门，下行四个车窗）。
        // 标签不再连引线（用户明确否决），靠「哪一行 + 水平顺序」对应部件。
        val area = android.graphics.RectF(8f, BAND_H + 6f, w - 8f, h - BAND_H - 6f)
        val image = if (slot != null && slot.second > 0) {
            fitIn(area, slot.first.toFloat() / slot.second)
        } else {
            area
        }
        if (Car3dSettings.enabled) {
            car3d.paint(canvas, context, view, image, fit = false)
        }

        // items 顺序：0-3 门、4 后备箱、5-8 窗、9 引擎盖（引擎盖不参与）。
        drawRow(canvas, intArrayOf(0, 1, 2, 3, 4), 4f, w)
        drawRow(canvas, intArrayOf(5, 6, 7, 8), h - BAND_H + 4f, w)
    }

    /** 画一行标签：整体水平居中，超出宽度时从左边距开始排。 */
    private fun drawRow(canvas: Canvas, idxs: IntArray, top: Float, w: Float) {
        text.typeface = Typeface.DEFAULT
        text.textSize = 12f
        text.textAlign = Paint.Align.CENTER

        val labels = ArrayList<String>(idxs.size)
        val widths = FloatArray(idxs.size)
        for (k in idxs.indices) {
            labels.add(items[idxs[k]].label + " " + com.cardesk.launcher.core.CarStatus.stateText(items[idxs[k]].open))
            widths[k] = text.measureText(labels[k]) + PILL_PAD_X * 2f
        }
        val total = widths.sum() + ROW_GAP * (idxs.size - 1)
        var x = ((w - total) / 2f).coerceAtLeast(4f)
        for (k in idxs.indices) {
            val item = items[idxs[k]]
            val open = item.open == true
            val isWindow = idxs[k] in 5..8
            val color = when {
                !open -> Skin.TEXT_DIM
                isWindow -> WINDOW_YELLOW
                else -> pal.warn
            }
            val left = x
            val right = x + widths[k]
            x = right + ROW_GAP

            box.style = Paint.Style.FILL
            box.color = 0xE010141B.toInt()
            canvas.drawRoundRect(left, top, right, top + PILL_H, PILL_RADIUS, PILL_RADIUS, box)
            box.style = Paint.Style.STROKE
            box.strokeWidth = 1f
            box.color = (color and 0x00FFFFFF) or (170 shl 24)
            canvas.drawRoundRect(left, top, right, top + PILL_H, PILL_RADIUS, PILL_RADIUS, box)

            text.color = color
            val cy = top + PILL_H / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(labels[k], (left + right) / 2f, cy, text)
        }
    }

    private fun fitIn(area: android.graphics.RectF, aspect: Float): android.graphics.RectF {
        if (aspect <= 0f) return android.graphics.RectF(area)
        val w = min(area.width(), area.height() * aspect)
        val h = w / aspect
        val cx = area.centerX()
        val cy = area.centerY()
        return android.graphics.RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private companion object {
        /** 车窗打开 = 黄色（用户指定）。 */
        const val WINDOW_YELLOW = 0xFFF5C542.toInt()

        /** 标签带总高（设计 px）：一行 pill 24 + 上下留白。 */
        const val BAND_H = 34f
        const val PILL_H = 24f
        const val PILL_RADIUS = 12f
        const val PILL_PAD_X = 8f
        const val ROW_GAP = 8f
    }
}
