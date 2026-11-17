package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.ui.widget.CarTopStatusView
import com.cardesk.launcher.ui.widget.WrapRow
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

    /**
     * 未关部件的胶囊行。
     *
     * 第188轮：从横向 LinearLayout 换成 [WrapRow]（可换行）——
     * 横向 LinearLayout 放不下 5 个以上胶囊时会把它们**压扁**（实测最后一个被压成
     * 16px / 0px 宽），文字折行后竖着叠在一起、和旁边的胶囊糊成一团，
     * 正是用户报的「开门弹窗挤成一团」。
     */
    private val row = WrapRow(context).apply {
        gapX = CHIP_GAP
        gapY = CHIP_GAP
    }

    /** 说明文字。 */
    private val detail: TextView = Ui.text(context, DoorAlert.detail(openItems), DETAIL_SP, pal.textDim)

    /**
     * 标题（未关处数变了要跟着改，见 [render]）。
     *
     * 之前标题是构造时算好的、**永远不更新** ——
     * 开着一扇门时弹「有车门未关」，之后又开了三扇，胶囊变成四个，
     * 标题还是「有车门未关」（或反过来写着「6 处未关」而实际只剩一处）。
     */
    private val titleView: TextView

    /** 量说明文字宽度用（判断一行放不放得下）。 */
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = DETAIL_SP }

    /** 底部「我知道了」按钮（位置随内容高度走，见 [relayout]）。 */
    private val button: TextView = primaryButton("我知道了", 28f) { onClose?.invoke() }

    init {
        titleView = addTitle()
        addClose(sizePx = 28f)

        car.view = com.cardesk.launcher.core.car3d.Car3dAssets.VIEW_DIALOG
        place(car, CAR_LEFT, CAR_TOP, CAR_W, CAR_H)
        // 高度交给内容（1 行 66 / 2 行 148…），位置在 [relayout] 里按实际行数定。
        place(row, 40, LIST_TOP, ROW_W, -2)
        place(detail, 40, DETAIL_TOP, ROW_W, DETAIL_H)

        // —— 底部按钮：手动关闭 ——
        place(button, PANEL_W - 40 - 220, BOTTOM_TOP, 220, 66)

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

        // 标题跟着未关处数走（第188轮）：之前标题是构造时算死的，
        // 「开着的东西」变了之后标题会说过期数字（「6 处未关」而实际只剩一处）。
        titleView.text = DoorAlert.title(openItems.size)

        row.removeAllViews()
        openItems.forEach { item ->
            val chip = Ui.text(context, item.label, 30f, pal.carStateWarn, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, pal.carStateWarn)
                setPadding(30, 14, 30, 14)
                pressFeedback()
                // 胶囊是**状态**（这一处还开着），不是可点的操作 ——
                // 但它有按压反馈，就得给松手之后一个交代：
                // 鼓一下 = 「收到了，这一处确实还开着」。
                // 不改变任何业务状态，只是让按压有回音。
                setOnClickListener { chipView -> Motion.bump(chipView) }
            }
            // 宽度用 wrap_content（自然宽度）：胶囊不会被压扁，放不下就换行。
            row.addView(chip, ViewGroup.MarginLayoutParams(-2, CHIP_H))
        }

        // 说明文字是**读数**：开着的东西变了才变。
        // 直接换字的话余光抓不到（「刚才说 3 处，现在说 4 处」得盯着看），
        // 顶一下让变化被看见。
        val nextDetail = detailText(openItems)
        if (detail.text != nextDetail) Motion.nudge(detail)
        detail.text = nextDetail
        relayout()
    }

    /**
     * 说明文字：单行放得下就一行，放不下就拆成「清单 + 提示」两行。
     *
     * ## 为什么不用 TextView 自己折行
     *
     * 自动折行的断点由宽度决定，「左前门、右前门、…、后备箱 还 | 没关好，请检查」
     * 会从「还 / 没关好」中间断开，读起来别扭。这里先量一遍：
     * 放得下 → 保持原来的一行；放不下 → 主动拆成两行，断点由我们说了算。
     */
    private fun detailText(items: List<DoorAlert.Item>): String {
        val one = DoorAlert.detail(items)
        detailPaint.textSize = DETAIL_SP
        // 留 8px 余量：测量和 TextView 自己的排版有亚像素差，宁可早一点拆行。
        if (detailPaint.measureText(one) <= ROW_W - 8f) return one
        return DoorAlert.detailTwoLines(items)
    }

    /**
     * 按胶囊行实际占的高度重排下半部分（第188轮）。
     *
     * ## 为什么要有这个方法
     *
     * 未关的部件最多有 6 个（四门 + 引擎盖 + 后备箱），胶囊行放不下时会**换行**。
     * 换行之后说明文字和按钮就得跟着往下挪，否则会**盖在第二行胶囊上**。
     *
     * ## 为什么按钮位置有下限
     *
     * 只开一两扇门（最常见）时沿用原来的版式：说明在 570、按钮在 690。
     * 按钮只在内容确实变高时才往下让，避免「门关一扇 → 按钮跳一下」。
     */
    private fun relayout() {
        // 量胶囊行：高度由内容决定（1 行 66，2 行 66 + 16 + 66 = 148）。
        row.measure(
            View.MeasureSpec.makeMeasureSpec(ROW_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val rowH = row.measuredHeight
        if (rowH <= 0) return

        // 说明文字：量出真实高度（放不下会折成两行），别再固定 44 把第二行裁掉。
        detail.measure(
            View.MeasureSpec.makeMeasureSpec(ROW_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val detailH = maxOf(detail.measuredHeight, DETAIL_H)
        val detailTop = LIST_TOP + rowH + DETAIL_GAP

        moveTo(detail, detailTop, detailH)
        moveTo(button, maxOf(BOTTOM_TOP, detailTop + detailH + BUTTON_GAP))
    }

    /** 改一个已摆放视图的位置（和高度）。 */
    private fun moveTo(view: View, top: Int, height: Int = -1) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.topMargin = top
        if (height > 0) lp.height = height
        view.layoutParams = lp
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

        /**
         * 开门清单 / 说明 / 按钮的纵向位置（面板内坐标）。
         *
         * 第180轮：胶囊行字 24 → 30、行高 58 → 66，说明字 22 → 28，
         * 纵向跟着往下挪了一档。
         *
         * 第188轮：胶囊行高度改由**内容**决定（1 行 66 / 2 行 148），
         * 说明和按钮的位置跟着行数走（见 [relayout]）；
         * [BOTTOM_TOP] 保留为按钮位置的**下限**，最常见的「开一两扇门」版式不变。
         */
        const val LIST_TOP = 496

        /** 胶囊行可用宽度（面板内左右各留 40）。 */
        const val ROW_W = PANEL_W - 80

        /** 胶囊高度（单个）。 */
        const val CHIP_H = 66

        /** 胶囊之间、以及两行之间的间距。 */
        const val CHIP_GAP = 16

        /** 说明文字高度（一行；折成两行时按实际测量高度走）。 */
        const val DETAIL_H = 44

        /** 说明文字字号。 */
        const val DETAIL_SP = 28f

        /** 胶囊行底部到说明文字的间距。 */
        const val DETAIL_GAP = 8

        /** 说明文字底部到按钮的最小间距。 */
        const val BUTTON_GAP = 24

        /** 说明文字默认纵向位置（[relayout] 会按胶囊行行数改写）。 */
        const val DETAIL_TOP = LIST_TOP + CHIP_H + DETAIL_GAP

        /** 按钮默认纵向位置（[relayout] 只会把它往下挪，不会往上提）。 */
        const val BOTTOM_TOP = 690
    }
}

/**
 * 「车辆状态」面板（第166轮 C 版车况卡点开）。
 *
 * 版式复用 [DoorAlertDialog]（同一套「浮屿」面板 + 车模），
 * 但内容换成**10 项逐项状态全列表**（原来平铺在车况卡上的两列文字都搬到这里）。
 *
 * ## 第189轮：版式重排
 *
 * ```
 * 有车门未关 / 车辆状态                       ← 标题
 * ┌──────────────────────────────────────────┐
 * │ 左前门 开 ┌────────┐ 右前门 关            │   ← 左列 / 车模 / 右列
 * │ 左前窗 关 │ 车 模  │ 右前窗 关            │
 * │ 左后门 关 │        │ 右后门 关            │
 * │ 左后窗 关 └────────┘ 右后窗 关            │
 * │              [后备箱 开]                 │   ← 底部单独一行
 * │ 引擎盖无数据通道 · 开着的车门/车窗在图上高亮 │   ← 说明
 * └──────────────────────────────────────────┘
 *                                    [关闭]   ← 只有右上角一个（第189轮删掉了底部那个）
 * ```
 *
 * 以前是两行横排（上排 4 门 + 尾门、下排 4 窗），门和窗隔着 400px、
 * 左右也挨着，看不出方向；现在左列全是车的左边、右列全是车的右边。
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

    /** 标题（门开了 / 全关了要跟着切换，见 [update]）。 */
    private val titleView: TextView

    private val hint = Ui.text(
        context,
        "引擎盖无数据通道 · 开着的车门/车窗在图上高亮",
        20f,
        Skin.TEXT_SOFT,
    )

    init {
        titleView = addTitle()
        addClose(sizePx = 28f)

        place(car, CAR_LEFT, CAR_TOP, CAR_W, CAR_H)
        place(hint, 40, HINT_TOP, PANEL_W - 80, 32)
        // 第189轮：底部那个「关闭」主按钮**删掉了** ——
        // 右上角本来就有一个「关闭」胶囊，同一个面板里摆两个「关闭」纯属重复
        // （用户原话：「车况卡详情页有两个关闭，去掉一个」）。
        // 保留右上角那个：它是全局统一的关闭入口（其它弹窗都有）。
        // 少一个按钮 → 面板也不用留那么大，底部跟着收上来。
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
        // 标题跟着门态走（第188轮）：门全关时面板还写着「有车门未关」是错的。
        titleView.text =
            if (hasOpenEffective(doorsClosed, hoodClosed, trunkClosed)) "有车门未关" else "车辆状态"

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
        const val PANEL_LEFT = 580
        const val PANEL_TOP = 70

        /**
         * 车模槽（第189轮：712×470）。
         *
         * 槽里现在是「左列标签 / 车模 / 右列标签 + 底部一行后备箱」，
         * 所以比原来（712×440）高一点：左右两列各 4 个胶囊（40 高）要放得下。
         */
        const val CAR_W = 712
        const val CAR_H = 470
        const val CAR_LEFT = 24

        /**
         * 车模槽上沿（第189轮：106 → 118）。
         *
         * 右上角「关闭」胶囊占 36..102，槽顶让到 118，两者不叠。
         */
        const val CAR_TOP = 118

        /**
         * 车辆状态面板高度（第189轮：720 → 660）。
         *
         * 删掉底部那个重复的「关闭」按钮后，底部不再需要 66px 的按钮位，
         * 面板跟着收上来（下面只剩一行说明文字）。
         */
        const val VEHICLE_PANEL_H = 660

        /** 说明文字的上沿（车模槽底 588 + 16）。 */
        const val HINT_TOP = 604

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
 * 在竖版俯视大图两侧画门/窗的标签：左列全是**车的左边**、右列全是**车的右边**，
 * 开着的项高亮（车门橙、**车窗黄**），关着的项暗色。
 * 后备箱单独放在车模**下面**一行。
 * 引擎盖没有数据通道，图上不标（由底部小字说明）。
 *
 * 第179轮起按资产选视角：纯俯视资产（只声明 `cardtop`）走俯视，
 * 三视角齐全的老资产走 `hero`。
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

        // 第189轮：版式改成「左列 ‖ 车模 ‖ 右列」，底部再单独一行放后备箱。
        //
        // 原来是两行横排（上排 4 门 + 尾门、下排 4 窗），两个毛病：
        // 1. 门和窗被拆到上下两条带里，隔着 400px，看不出哪个对应哪个部位；
        // 2. 标签横向排满，「左前门」和「右前门」挨着，方向感很弱。
        //
        // 现在左列全是**车的左边**、右列全是**车的右边**（俯视竖版图里
        // 图左 = 车左，见 car3d/model.json 的 anchors），一眼就知道是谁开着。
        val carArea = android.graphics.RectF(SIDE_W, 0f, w - SIDE_W, h - TRUNK_BAND_H)
        val image = if (slot != null && slot.second > 0) {
            fitIn(carArea, slot.first.toFloat() / slot.second)
        } else {
            carArea
        }
        if (Car3dSettings.enabled) {
            car3d.paint(canvas, context, view, image, fit = false)
        }

        // items 顺序：0-3 门、4 后备箱、5-8 窗、9 引擎盖（引擎盖不参与）。
        // 左列：左前门 / 左前窗 / 左后门 / 左后窗（自上而下 = 车头 → 车尾）。
        drawColumn(canvas, intArrayOf(0, 5, 2, 7), alignLeft = true, w = w, areaH = carArea.height())
        // 右列：右前门 / 右前窗 / 右后门 / 右后窗。
        drawColumn(canvas, intArrayOf(1, 6, 3, 8), alignLeft = false, w = w, areaH = carArea.height())
        // 底部一行：后备箱（车模下面）。
        drawRow(canvas, intArrayOf(4), h - TRUNK_BAND_H + (TRUNK_BAND_H - PILL_H) / 2f, w)
    }

    /**
     * 画一列标签（竖排），整列在车模高度里**居中**。
     *
     * ## 顺序
     *
     * 自上而下是「前门 / 前窗 / 后门 / 后窗」——
     * 俯视竖版车模车头朝上，前后顺序和从上到下对得上。
     *
     * ## 字号
     *
     * 整列**统一**一个字号：最长的那条放不下就一档一档往下缩
     * （缩到 [LABEL_SP_MIN] 为止），宁可字小一点也不让胶囊压到车模。
     */
    private fun drawColumn(
        canvas: Canvas,
        idxs: IntArray,
        alignLeft: Boolean,
        w: Float,
        areaH: Float,
    ) {
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT

        val labels = idxs.map {
            items[it].label + " " + com.cardesk.launcher.core.CarStatus.stateText(items[it].open)
        }
        var size = LABEL_SP
        var textW = 0f
        while (true) {
            text.textSize = size
            textW = labels.maxOf { text.measureText(it) }
            if (textW + PILL_PAD_X * 2f <= SIDE_W - 4f || size <= LABEL_SP_MIN) break
            size = maxOf(LABEL_SP_MIN, size - 1f)
        }
        val pillW = textW + PILL_PAD_X * 2f

        val totalH = PILL_H * idxs.size + COLUMN_GAP * (idxs.size - 1)
        var top = (areaH - totalH) / 2f
        idxs.forEachIndexed { k, idx ->
            val left = if (alignLeft) 0f else w - pillW
            drawPill(canvas, idx, labels[k], left, left + pillW, top)
            top += PILL_H + COLUMN_GAP
        }
    }

    /**
     * 画一行标签：整体水平居中，超出宽度时从左边距开始排。
     *
     * ## 第180轮：字号 12 → 22（快 2 倍）
     *
     * 原来 12px 的标签放在 760 宽的面板里几乎看不清（预览图上一排小点点）。
     * 放大后一行五个「左前门 开」在 760px 面板里**排不开**，
     * 所以这里加了**等比缩字号**兜底：装不下就一档一档往下缩，
     * 缩到 [LABEL_SP_MIN] 为止 —— 宁可字小一点，也不让标签溢出面板。
     *
     * 第189轮：横排现在只用来放**底部那一行后备箱**，
     * 左右两列的门/窗改用 [drawColumn]（竖排）。
     *
     * 胶囊底色也跟着主题走（原来写死深色 + 主题字色 = 白底上的「一坨黑」）。
     */
    private fun drawRow(canvas: Canvas, idxs: IntArray, top: Float, w: Float) {
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT

        // 注意：这里要的是「长度 = idxs.size 的列表」，不是 `ArrayList(n)`（那是**容量**，
        // 下标赋值会 IndexOutOfBounds）。
        val labels = MutableList(idxs.size) { "" }
        val widths = FloatArray(idxs.size)
        val avail = w - 8f

        var size = LABEL_SP
        var total = 0f
        while (true) {
            text.textSize = size
            total = ROW_GAP * (idxs.size - 1).coerceAtLeast(0)
            for (k in idxs.indices) {
                labels[k] = items[idxs[k]].label + " " + com.cardesk.launcher.core.CarStatus.stateText(items[idxs[k]].open)
                widths[k] = text.measureText(labels[k]) + PILL_PAD_X * 2f
                total += widths[k]
            }
            if (total <= avail || size <= LABEL_SP_MIN) break
            size = maxOf(LABEL_SP_MIN, size - 1f)
        }

        var x = ((w - total) / 2f).coerceAtLeast(4f)
        for (k in idxs.indices) {
            val left = x
            val right = x + widths[k]
            x = right + ROW_GAP
            drawPill(canvas, idxs[k], labels[k], left, right, top)
        }
    }

    /**
     * 画一个状态胶囊（第189轮：从 [drawRow] 里拆出来，横排/竖排共用）。
     *
     * 配色：开着 = 橙（门/尾门）或黄（车窗），关着 = 暗色（弱化，不抢注意力）。
     */
    private fun drawPill(
        canvas: Canvas,
        idx: Int,
        label: String,
        left: Float,
        right: Float,
        top: Float,
    ) {
        val item = items[idx]
        val open = item.open == true
        val isWindow = idx in 5..8
        val color = when {
            !open -> pal.textDim
            isWindow -> pal.windowOpen
            else -> pal.carStateWarn
        }

        box.style = Paint.Style.FILL
        // 第180轮：底色跟随主题（夜里深灰、白天浅灰），不再写死深色。
        box.color = pal.surfaceStrong
        canvas.drawRoundRect(left, top, right, top + PILL_H, PILL_RADIUS, PILL_RADIUS, box)
        box.style = Paint.Style.STROKE
        box.strokeWidth = 1f
        box.color = (color and 0x00FFFFFF) or (170 shl 24)
        canvas.drawRoundRect(left, top, right, top + PILL_H, PILL_RADIUS, PILL_RADIUS, box)

        text.color = color
        text.textAlign = Paint.Align.CENTER
        val cy = top + PILL_H / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, (left + right) / 2f, cy, text)
        text.textAlign = Paint.Align.LEFT
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
        /**
         * 标签胶囊高（12px 字 → 22px 字，胶囊也跟着长高）。
         */
        const val PILL_H = 40f
        const val PILL_RADIUS = 20f

        /**
         * 标签字号（第180轮：12 → 22）。
         */
        const val LABEL_SP = 22f

        /**
         * 标签字号下限（一列/一行放不下时等比缩到这儿为止）。
         */
        const val LABEL_SP_MIN = 16f

        const val PILL_PAD_X = 12f
        const val ROW_GAP = 10f

        // ------------------------------------------------ 第189轮：左列 / 右列 / 底行

        /**
         * 左右标签列的宽度（设计 px）。
         *
         * 一条标签 = 「左前门 开」= 3 个汉字 + 空格 + 1 个汉字 ≈ 94px，
         * 加左右各 12px 内边距 ≈ 118px —— 这里留 132px，余 14px 给缩字兜底。
         */
        const val SIDE_W = 132f

        /** 同一列里两个胶囊的纵向间距。 */
        const val COLUMN_GAP = 14f

        /**
         * 底部「后备箱」那一行的高度（设计 px）。
         *
         * 车模区只占上面 `高 - 52` 的部分，剩下的 52px 留给后备箱胶囊
         * （胶囊高 40，上下各留 6）。
         */
        const val TRUNK_BAND_H = 52f
    }
}
