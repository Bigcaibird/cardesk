package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.CarStatusItem
import com.cardesk.launcher.core.DoorAlert
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.pressFeedback
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
 * ## 本轮修复（画板 → 代码）
 *
 * 量的是 `cardesk-ui/design/redesign/06-door-alert-day.png`（1920×1080 设计坐标，
 * 用 PIL 逐像素量的 bbox，不是目测）。画板的面板是
 * **x519..1400 / y271..760 ⇒ 881×489 @ (519,271)**、圆角拟合 **r≈28~30**，
 * 内容顺序固定为：
 *
 * ```
 * 2 处未关                                   ✕   ← 标题 / 关闭图标
 * 左前门、右前门 还没关好，请检查                  ← 一行说明
 * [左前门] [右前门]                              ← accent 胶囊
 *                白色极简俯视车模                  ← 自绘，不是实拍图
 *                                  [ 我知道了 ]   ← accent 实底
 * ```
 *
 * 改前实测是 760×840 @ (580,70) + 右上「关闭」文字胶囊 + 322px 实拍烘焙车图 +
 * **danger 橙**胶囊，顺序是「标题 → 关闭 → 车图 → 胶囊 → 说明 → 按钮」——
 * 尺寸、顺序、告警色三样都跟画板对不上。本轮逐条对齐：
 *
 * 1. 面板 **881×489 @ (519,271)**；圆角走 `SHEET_RADIUS = 28f`
 *    （`DialogSheet` 默认是 `Shape.CARD`，会被设置页的「卡片风格」改掉，
 *    所以这里用同一个 `Skin.flat` 重建背景，只换圆角，填充/描边不变）；
 * 2. 右上角「关闭」文字胶囊 → **自绘 `✕`**（56×56 热区，2px 圆头线、
 *    ink ≈15px，颜色 `pal.textDim`，正好等于画板量的 (90,102,114)）；
 * 3. 内容顺序改成画板的「标题 → 说明 → 胶囊 → 车模 → 按钮」；
 * 4. 胶囊不再是 `pal.carStateWarn`（橙/danger 语义），
 *    改成 **accent 青**：字 `pal.accent`、底 `pal.withAlpha(pal.accent, 0x1F)`
 *    （= 画板量到的 (208,232,231)，即 accent 12% 叠白）、
 *    描边 `pal.withAlpha(pal.accent, 0x66)`；字号 30 → 20、行高 66 → 48，
 *    单个胶囊 88×48（画板实测 88×48、间距 13，这里 gap 取 12）；
 * 5. 车模从**实拍烘焙图**（`CarTopStatusView`，`VIEW_DIALOG`）换成
 *    [DoorCarView]：Canvas 自绘的白色极简俯视车（车身 / 前挡 / 车顶 / 后窗 /
 *    四轮 / 车尾 accent 条），门、引擎盖、后备箱开着的位置用 accent 高亮。
 *
 * 顺手也改了两处只跟画板有关的字号：标题 38 → 32（画板 ink 高 26px），
 * 说明 28 → 20（画板 ink 高 14px），按钮 28 → 26（画板按钮 220×56）。
 *
 * ## 夜间没有独立画板（诚实说明）
 *
 * `redesign/` 里**只有 `06-door-alert-day.png`**，06 没有夜间板
 * （`n01`–`n05` 是另外几个界面）。所以本弹窗夜间没有可比对的画板，
 * 夜间观感由 token 自动反转：`pal.text` 在夜间是浅色 ⇒ 车身的半透明
 * 填充/描边自动变亮，`pal.accent` 夜间是更亮的青，胶囊底/描边跟着变。
 *
 * ## 回调没改
 *
 * `✕` 和「我知道了」都是 `onClose?.invoke()`（和改前的 `addClose` 完全一致），
 * 「撤窗 + 本轮不再打扰」的逻辑在 `LauncherView`，不经过本类。
 *
 * @param carModel 车型（`Prefs.carModel`）。第 253 轮起用于**实车俯视车模**
 *   （`Car3dPainter` + `VIEW_CARDTOP`），资产缺失时回退 2D 自绘。
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

    /** 车模（实车俯视 3D；资产缺失时才回退自绘 2D 车）。 */
    private val car = DoorCarView(context, pal)

    /**
     * 未关部件的胶囊行。
     *
     * 仍用 [WrapRow]（第188轮的老问题：横向 LinearLayout 放不下时会把胶囊压扁，
     * 文字折行后竖着叠在一起）。画板规格下最多 6 个胶囊
     * （四门 + 引擎盖 + 后备箱），单个 88 + 间距 12 ⇒ 588 < 列宽 797，
     * **永远是一行**，所以行高直接给 [CHIP_H]，不需要再按行数重排。
     */
    private val row = WrapRow(context).apply {
        gapX = CHIP_GAP
        gapY = CHIP_GAP
        // 画板里胶囊从内容左边线起排（不是居中）：只有一枚时居中会飘到面板正中。
        centerLines = false
    }

    /** 说明文字（画板：20px、`textFaint`）。 */
    private val detail: TextView = Ui.text(context, DoorAlert.detail(openItems), DETAIL_SP, pal.textFaint)

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

    /** 底部「我知道了」按钮（画板 220×56，accent 实底 + 白粗体）。 */
    private val button: TextView = primaryButton("我知道了", BUTTON_SP) { onClose?.invoke() }

    init {
        // 画板圆角 28；`DialogSheet` 默认用 `Shape.CARD`（24，且会被卡片风格改），
        // 所以这里用同一个 `Skin.flat` 重建一次背景 —— 只有圆角不同，填充/描边照旧。
        // 面板底：玻璃化之后 `Skin.flat` 用的是 78% 的 `surfaceStrong`，
        // 而我们的「模糊底」只糊了壁纸、糊不到页面内容 —— 播放中的歌词会**清楚地**
        // 透在面板上（截图肉眼可见「一起踏…座春秋」）。弹窗只要一张纸的观感，
        // 所以这里把 alpha 提到 94%，仍保留 sheen/描边/阴影的玻璃质感。
        sheet.background = Skin.flat(
            SHEET_RADIUS,
            fill = (Skin.SHEET_FILL and 0x00FFFFFF) or (0xF2 shl 24),
        )

        titleView = Ui.text(context, DoorAlert.title(openItems.size), TITLE_SP, pal.text, bold = true)
        place(titleView, SIDE, TITLE_TOP, ROW_W, -2)

        // 右上角 ✕：画板 ink 14×14、中心在面板内 (817,63) ⇒ 56×56 热区放 (789,36)。
        // 点击行为和原来的「关闭」胶囊一致（`onClose?.invoke()`）。
        placeEnd(CloseGlyphView(context, pal), CLOSE_RIGHT, CLOSE_TOP, CLOSE_BOX, CLOSE_BOX)
            .setOnClickListener { onClose?.invoke() }

        place(detail, SIDE, DETAIL_TOP, ROW_W, DETAIL_H)
        place(row, SIDE, LIST_TOP, ROW_W, CHIP_H)
        place(car, CAR_LEFT, CAR_TOP, CAR_W, CAR_H)
        placeEnd(button, BUTTON_RIGHT, BUTTON_TOP, BUTTON_W, BUTTON_H)

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
        car.doorsClosed = doorsClosed
        car.windowsOpen = windowsOpen
        car.hoodClosed = hoodClosed
        car.trunkClosed = trunkClosed
        car.carModel = carModel
        car.applyPose(doorsClosed, windowsOpen, hoodClosed, trunkClosed)
        car.invalidate()

        // 标题跟着未关处数走（第188轮）：之前标题是构造时算死的，
        // 「开着的东西」变了之后标题会说过期数字（「6 处未关」而实际只剩一处）。
        titleView.text = DoorAlert.title(openItems.size)

        row.removeAllViews()
        openItems.forEach { item ->
            val chip = Ui.text(context, item.label, CHIP_SP, pal.accent).apply {
                gravity = Gravity.CENTER
                // 画板里的胶囊是 accent 的面：12% 底 + 40% 描边，**不是** danger 橙。
                background = Ui.round(
                    pal.withAlpha(pal.accent, CHIP_FILL_ALPHA),
                    Shape.PILL,
                    1f,
                    pal.withAlpha(pal.accent, CHIP_STROKE_ALPHA),
                )
                setPadding(CHIP_PAD_H, 0, CHIP_PAD_H, 0)
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
    }

    /**
     * 说明文字：单行放得下就一行，放不下就拆成「清单 + 提示」两行。
     *
     * ## 为什么不用 TextView 自己折行
     *
     * 自动折行的断点由宽度决定，「左前门、右前门、…、后备箱 还 | 没关好，请检查」
     * 会从「还 / 没关好」中间断开，读起来别扭。这里先量一遍：
     * 放得下 → 保持原来的一行；放不下 → 主动拆成两行，断点由我们说了算。
     *
     * 画板是一行小字；行数变了只是说明文字自己变高（`place` 给了 [DETAIL_H]
     * 的固定高，两行时会顶到胶囊行下沿附近），车模和按钮位置不动。
     */
    private fun detailText(items: List<DoorAlert.Item>): String {
        val one = DoorAlert.detail(items)
        detailPaint.textSize = DETAIL_SP
        // 留 8px 余量：测量和 TextView 自己的排版有亚像素差，宁可早一点拆行。
        if (detailPaint.measureText(one) <= ROW_W - 8f) return one
        return DoorAlert.detailTwoLines(items)
    }

    private companion object {
        /**
         * 面板尺寸与位置。
         *
         * 第 254 轮按用户反馈**整体放大**：画板是 881×489，换 3D 车模后车只占中间一小块。
         * 第 255 轮用户连着提了四轮：先说「大一点」，再说「宽一点」，再说「太长、车靠底」，
         * 最后明确「要**差不多正方形**的弹窗」，900×840 之后又嫌太大 ——
         * 现在定稿 760×720（近正方形、屏幕正中），车模槽位 320×390。
         */
        const val PANEL_W = 760
        const val PANEL_H = 720
        const val PANEL_LEFT = (Design.W.toInt() - PANEL_W) / 2
        const val PANEL_TOP = (Design.H.toInt() - PANEL_H) / 2

        /** 面板圆角（画板拟合 28~30，取 28）。 */
        const val SHEET_RADIUS = 28f

        /** 左右内容边距（画板：标题/说明/胶囊都从 panel-rel x42 起）。 */
        const val SIDE = 50

        /** 一行内容的可用宽度。 */
        const val ROW_W = PANEL_W - SIDE * 2

        /** 标题字号 / 位置（画板 ink y49..74、高 26 ⇒ 32px 粗体）。 */
        const val TITLE_SP = 36f
        const val TITLE_TOP = 38

        /** 说明字号 / 位置（画板 ink y91..105 ⇒ 20px）。 */
        const val DETAIL_SP = 21f
        const val DETAIL_TOP = 90
        const val DETAIL_H = 56

        /** 胶囊行位置（画板 y403..450 ⇒ panel-rel 132）。 */
        const val LIST_TOP = 156

        /** 单个胶囊高度（画板 48）。 */
        const val CHIP_H = 48

        /** 胶囊之间、以及两行之间的间距（画板实测 13，取 12）。 */
        const val CHIP_GAP = 14

        /** 胶囊字号（画板 20px）。 */
        const val CHIP_SP = 21f

        /** 胶囊左右内边距：3 个汉字 60 + 2×14 = 88，正好等于画板的胶囊宽度。 */
        const val CHIP_PAD_H = 15

        /** 胶囊底色透明度（画板 (208,232,231) = accent 12% 叠白）。 */
        const val CHIP_FILL_ALPHA = 0x1F

        /** 胶囊描边透明度（画板描边 ≈ accent 40~50%）。 */
        const val CHIP_STROKE_ALPHA = 0x66

        /**
         * 车模槽位：220×250，车身按 54%×92% 画 ⇒ 118.8×230，
         * 水平和画板一致（panel-rel x380..499）、纵向 196..426（画板 197..425）。
         */
        const val CAR_W = 320
        const val CAR_H = 390
        const val CAR_LEFT = (PANEL_W - CAR_W) / 2
        const val CAR_TOP = 214

        /** 底部按钮（画板 rel x620..839 / y400..455 ⇒ 220×56，右边距 41）。 */
        const val BUTTON_W = 220
        const val BUTTON_H = 58
        const val BUTTON_RIGHT = 50
        const val BUTTON_TOP = 620
        const val BUTTON_SP = 28f

        /** 右上 ✕ 的热区（画板 ink 只有 14×14，热区放大到 56×56）。 */
        const val CLOSE_BOX = 56
        const val CLOSE_RIGHT = 38
        const val CLOSE_TOP = 38
    }
}

/**
 * 右上角的关闭图标（画板：两条 2px 圆头线，ink 约 14×14，色 `pal.textDim`）。
 *
 * 为什么不用 `TextView("✕")`：Android 9 AOSP 的默认字体里不保证有 U+2715，
 * 退化成方框就白改了。Canvas 画两条线，任何机型都一样。
 */
private class CloseGlyphView(context: Context, private val pal: Palette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        color = pal.textDim
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        // 56 的盒子 × 0.13 = 7.3 ⇒ ink 14.6px，和画板的 14×14 对齐。
        val arm = minOf(w, h) * 0.13f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, paint)
    }
}

/**
 * 车门提醒弹窗里的**俯视极简车模**（Canvas 自绘，不依赖任何位图资产）。
 *
 * ## 画板长什么样
 *
 * `06-door-alert.png` 里的车模是**白色极简车**，不是实拍图：
 * 冷白色圆角车身（230,238,242）+ 深色前挡/后窗（90,103,111，约 63% 黑）+
 * 浅灰车顶（199,211,215，约 16% 黑）+ 车身两侧外凸的灰色轮胎；
 * **开着的前门在车身两侧画成 accent 竖条**（0,169,160），车尾还有一条小的 accent 尾灯。
 *
 * ## 为什么重写一份
 *
 * `ui/pages/VehiclePage.kt` 里已有 `TopCarView`，但它是 `private`，
 * 跨文件拿不到；本轮的约束是**只改两个文件**，不能为了复用去动那个文件。
 * 所以这里按同一套画法（四轮 → 车身 → 前挡/车顶/后窗 → 高亮）重画，
 * 只保留这个弹窗需要的部分。**画板车身下没有落影**，所以这一版不画阴影。
 *
 * 各部件的位置/尺寸都是按画板逐像素量的（相对车身高的比例）：
 * 前轮 0.21~0.354、后轮 0.64~0.82、前挡 0.105~0.293、车顶 0.362~0.507、
 * 后窗 0.664~0.882、车尾 accent 条 0.803~0.834、开门竖条 0.384~0.541；
 * 车身宽 90、轮胎各外露 12（整车 119×229）。
 *
 * ## 颜色
 *
 * 全部走 [Palette] token，没有新增硬编码色：车身的「白」是 `pal.text` 的低透明度
 * 叠在面板上——日间 `text` 是深墨色（叠出来是比面板略深的冷灰，和画板一致），
 * 夜间 `text` 是浅色（叠出来是暗底上的浅车），日夜自动反转。
 * 只有「开着的部位」用 [Palette.accent]，和画板的青一致。
 */
private class DoorCarView(context: Context, private val pal: Palette) : View(context) {

    /**
     * 实车俯视车模（第 253 轮）。
     *
     * 原来这里是**自绘的 2D 圆角车身 + 四个轮胎**：与首页车况卡、车辆详情页用的
     * `Car3dPainter` 完全不是一套东西，同一台车在两个地方长得不一样。
     * 现在统一走 3D 资产（`VIEW_CARDTOP`），姿态跟着门/窗/前后备零件走；
     * 资产缺失（老车型 / 关闭了车模设置）时仍回退到下面的自绘路径。
     */
    private val car3d = Car3dPainter.Scene(this)
    private var dir: String? = null

    /** 车型（来自 `Prefs.carModel`，由 [DoorAlertDialog.render] 每次同步）。 */
    var carModel: String = com.cardesk.launcher.core.CarModels.MODEL_03
        set(value) {
            if (field == value) return
            field = value
            dir = com.cardesk.launcher.core.CarModels.assetDir(value)
            invalidate()
        }

    /** 按车门/车窗/引擎盖/后备箱状态摆姿态（开着的位置由资产里的开门图体现）。 */
    fun applyPose(
        doorsClosed: List<Boolean?>?,
        windowsOpen: BooleanArray?,
        hoodClosed: Boolean?,
        trunkClosed: Boolean?,
    ) {
        car3d.setPose(
            context,
            dir,
            CarPose.of(doorsClosed, windowsOpen, hoodClosed, trunkClosed),
            rich = true,
            view = Car3dAssets.VIEW_CARDTOP,
        )
    }

    /** 四门：`true` = 关，`false` = 开，`null` = 读不到（不画高亮）。 */
    var doorsClosed: List<Boolean?>? = null

    /** 四窗：`true` = 开（下标同 `CarStatus.DOOR_LABELS`）。 */
    var windowsOpen: BooleanArray? = null
    var hoodClosed: Boolean? = null
    var trunkClosed: Boolean? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 先试实车车模：画出来了就不再画下面的 2D 车身。
        // 槽位 220×250 与 cardtop 资产 264×356 比例不同，必须 fit=true 等比放进矩形，
        // 否则车会被横向拉扁。
        if (car3d.paint(canvas, context, Car3dAssets.VIEW_CARDTOP, RectF(0f, 0f, w, h), fit = true)) {
            return
        }

        val cx = w / 2f

        // 车身占槽位 41%×92%：220×250 的槽位 ⇒ 90×230，加上两侧各 12 的轮胎
        // 正好是画板量到的整车 119×229（车身窄、车头长，不是正圆柱）。
        val bodyW = w * 0.41f
        val bodyH = h * 0.92f
        val left = cx - bodyW / 2f
        val right = cx + bodyW / 2f
        val top = (h - bodyH) / 2f
        val body = RectF(left, top, right, top + bodyH)
        val radius = bodyW * 0.34f

        // 轮胎：画板是**整块露在车身外**（车身左边 914、轮胎 902..913），
        // 前后各一对，前轮 0.21~0.354H、后轮 0.64~0.82H。
        fill.color = pal.withAlpha(pal.text, TIRE_ALPHA)
        val wheelW = bodyW * 0.133f
        for (spec in WHEELS) {
            val wheelTop = top + bodyH * spec[0]
            val wheelBottom = top + bodyH * spec[1]
            canvas.drawRoundRect(
                RectF(left - wheelW, wheelTop, left, wheelBottom),
                2.5f, 2.5f, fill,
            )
            canvas.drawRoundRect(
                RectF(right, wheelTop, right + wheelW, wheelBottom),
                2.5f, 2.5f, fill,
            )
        }

        // 车漆（半透明 ≈ 比面板深一点的白，见类注释）+ 极浅描边，避免整块没边界。
        fill.color = pal.withAlpha(pal.text, BODY_ALPHA)
        canvas.drawRoundRect(body, radius, radius, fill)
        stroke.color = pal.withAlpha(pal.text, BODY_STROKE_ALPHA)
        canvas.drawRoundRect(body, radius, radius, stroke)

        // 前挡（0.105~0.293H）→ 车顶（0.362~0.507H，比车漆深一档）→ 后窗（0.664~0.882H）。
        fill.color = pal.withAlpha(pal.text, GLASS_ALPHA)
        canvas.drawRoundRect(
            RectF(cx - bodyW * 0.31f, top + bodyH * 0.105f, cx + bodyW * 0.31f, top + bodyH * 0.293f),
            bodyW * 0.14f, bodyW * 0.14f, fill,
        )
        fill.color = pal.withAlpha(pal.text, ROOF_ALPHA)
        canvas.drawRoundRect(
            RectF(cx - bodyW * 0.30f, top + bodyH * 0.362f, cx + bodyW * 0.30f, top + bodyH * 0.507f),
            6f, 6f, fill,
        )
        fill.color = pal.withAlpha(pal.text, GLASS_ALPHA)
        canvas.drawRoundRect(
            RectF(cx - bodyW * 0.27f, top + bodyH * 0.664f, cx + bodyW * 0.27f, top + bodyH * 0.882f),
            bodyW * 0.11f, bodyW * 0.11f, fill,
        )

        // 开着的门：车身两侧的 accent 竖条，位置就在画板那两扇前门的地方
        // （前门 0.384~0.541H，正好在前后两对轮胎之间的那条缝里），
        // 画板是 accent 约 70% 叠在车身侧沿上 ⇒ 这里用 `withAlpha(accent, 0xB4)`。
        val doors = doorsClosed
        if (doors != null) {
            fill.color = pal.withAlpha(pal.accent, DOOR_BAR_ALPHA)
            for (i in 0 until 4) {
                if (doors.getOrNull(i) != false) continue // 关 / 读不到 → 不高亮
                val isLeft = i % 2 == 0
                val bandTop = top + bodyH * (if (i < 2) 0.384f else 0.60f)
                val bandBottom = bandTop + bodyH * 0.157f
                val barW = bodyW * 0.09f
                // 贴在轮胎那一层的**外沿**（车身之外，和画板一样挨着轮胎）。
                val barLeft = if (isLeft) left - wheelW else right + wheelW - barW
                canvas.drawRoundRect(
                    RectF(barLeft, bandTop, barLeft + barW, bandBottom),
                    barW / 2f, barW / 2f, fill,
                )
            }
        }

        // 开着的窗：把车顶两侧的窗沿描成 accent。不另画形状，免得和门条抢眼
        // （车窗开着不算「没关好」，画板也不强调它，这里只做一点提示）。
        val windows = windowsOpen
        if (windows != null) {
            stroke.color = pal.accent
            stroke.strokeWidth = 2f
            for (i in 0 until 4) {
                if (windows.getOrNull(i) != true) continue
                val x = if (i % 2 == 0) left + 2f else right - 2f
                canvas.drawLine(x, top + bodyH * 0.362f, x, top + bodyH * 0.507f, stroke)
            }
            stroke.strokeWidth = 1.2f
        }

        // 引擎盖开着：车头一条 accent 横条。
        if (hoodClosed == false) {
            fill.color = pal.accent
            canvas.drawRoundRect(
                RectF(cx - bodyW * 0.22f, top + bodyH * 0.025f, cx + bodyW * 0.22f, top + bodyH * 0.055f),
                3f, 3f, fill,
            )
        }

        // 车尾那条小的 accent 条：画板常驻，压在后窗下沿附近（0.803~0.834H）。
        // 后备箱开着时更浓，关着时是 70% 的 accent。
        fill.color = if (trunkClosed == false) pal.accent else pal.withAlpha(pal.accent, DOOR_BAR_ALPHA)
        canvas.drawRoundRect(
            RectF(cx - bodyW * 0.11f, top + bodyH * 0.803f, cx + bodyW * 0.11f, top + bodyH * 0.834f),
            2f, 2f, fill,
        )
    }

    /** 轮胎的纵向区间（车身高的比例）：前轮 / 后轮，画板实测 0.21~0.354 与 0.64~0.82。 */
    private val WHEELS = arrayOf(floatArrayOf(0.21f, 0.354f), floatArrayOf(0.64f, 0.82f))

    /** 车身各层的透明度（都是 `pal.text` 的 alpha，不引入新颜色）。 */
    private companion object {
        const val TIRE_ALPHA = 0x3A
        const val BODY_ALPHA = 0x0E
        const val BODY_STROKE_ALPHA = 0x16
        const val GLASS_ALPHA = 0xA0
        const val ROOF_ALPHA = 0x28

        /** 车门 / 车尾的 accent 条：画板是 accent 约 70% 叠在车身上。 */
        const val DOOR_BAR_ALPHA = 0xB4
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
