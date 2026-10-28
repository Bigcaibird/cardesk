package com.cardesk.launcher.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.widget.CabinView
import com.cardesk.launcher.ui.widget.FanBarsView
import com.cardesk.launcher.ui.widget.OverlayBackdrop
import com.cardesk.launcher.ui.widget.TempDialView
import com.cardesk.launcher.vehicle.HvacCodec
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.VehicleController
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 气候整页：第四十九轮按设计板 `s04-hvac` 重排为**整屏浮层**。
 *
 * 版式（面板 **1640×867**，位于 `left 140 / top 73`）：
 * `
 * 标题行 「空调」40px 粗体 (40, 28) + 状态副标题 (150, 42) + 右侧「关闭」胶囊 (right 36 / top 28)
 * 左列 主驾表盘（环 + 温度 + −/+ 方块）+ 加热 / 通风胶囊（列内 top 452）
 * 中列 风量标题 (460, 110) + 7 个风量方块 56×56 + 「吹风模式」+ 7 个组合按钮 156×68
 *          4 个功能按钮（A/C / AUTO / 外循环 / 温度同步）+ 2 个除霜胶囊
 *          座舱吹风示意
 * 右列 副驾表盘 + 加热 / 通风胶囊
 * `
 *
 * **功能不做减法**：设计板把「吹面 / 吹脚 / 吹窗」三个独立开关画成了 7 个组合按钮，
 * 但 3 位掩码（face=1 / leg=2 / win=4）本来就有 2³ = 8 种组合（去掉「全关」= 7 种），
 * 7 个按钮与原 3 个开关**功能等价**，只是更快捷。座椅加热 / 通风、MAX 风量、
 * 座舱吹风示意全部保留（放在设计板没有元素的空位上）。
 */
class ClimatePage(context: Context, private val pal: Palette) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null

    private lateinit var dialDriver: TempDialView
    private lateinit var dialPassenger: TempDialView
    private lateinit var fanLabel: TextView
    private lateinit var fanBars: FanBarsView
    private lateinit var cabin: CabinView
    private lateinit var powerStateText: TextView

    private val chips = LinkedHashMap<String, ChipButton>()
    private val seatChips = LinkedHashMap<String, ChipButton>()

    /** 面板（动画用）。 */
    private var panelView: View? = null

    /** 遮罩（动画用）。 */
    private var scrimView: View? = null

    /** 打开动画：面板上滑 + 遮罩淡入。 */
    fun playOpen() {
        OverlayAnim.open(panelView, scrimView)
    }

    /** 关闭动画：面板下滑 + 淡出。 */
    fun playClose(onEnd: () -> Unit) {
        OverlayAnim.close(panelView, scrimView, onEnd)
    }

    init {
        // 全屏遮罩（第四十九轮：盖住状态栏和 Dock，与设计板一致）
        // 第五十轮：换成**不透明背景板**（`.bgwash` + `.scrim`），与设计板 `s04-hvac` 一致 ——
        // 桌面卡片不再从遮罩后面透出来。
        val scrim = OverlayBackdrop(context).apply {
            palette = this@ClimatePage.pal
            withScrim = true
            isClickable = true
            setOnClickListener { onClose?.invoke() }
        }
        scrimView = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val card = FrameLayout(context).apply {
            background = Skin.flat(Shape.CARD)
            // 吞掉面板内的空白点击，避免落到遮罩上误关闭（只有面板外点击才关闭）
            isClickable = true
            setOnClickListener { }
        }
        panelView = card
        addView(
            card,
            LayoutParams(PANEL_W, PANEL_H, Gravity.TOP or Gravity.START).apply {
                leftMargin = PANEL_LEFT
                topMargin = PANEL_TOP
            },
        )

        buildTitle(card)
        buildDial(card, driver = true)
        buildDial(card, driver = false)
        buildMiddle(card)
        buildSeatRow(card)
        refresh()
    }

    // ------------------------------------------------------------- 结构

    private fun buildTitle(card: FrameLayout) {
        val title = Ui.text(context, "空调", 40f, Skin.TEXT, bold = true)
        card.addView(title, abs(PANEL_PAD, TITLE_TOP, -1, 52))

        // 电源状态副标题（「已关机 / 运行中」）
        val state = Ui.text(context, "已关机", 22f, Skin.TEXT_SOFT)
        powerStateText = state
        card.addView(state, abs(TITLE_STATE_X, TITLE_STATE_TOP, 200, 30))

        val power = ChipButton.make(context, pal, "电源", VehicleController.state.power).apply {
            setOnClickListener {
                VehicleController.togglePower()
                refresh()
            }
        }
        card.addView(power, abs(POWER_X, POWER_Y, 120, 56))
        chips["power"] = power

        val closeBtn = Ui.text(context, "关闭", 24f, Skin.TEXT_SOFT).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.CHIP_FILL, Shape.PILL, 1f, Skin.CHIP_STROKE)
            setOnClickListener { onClose?.invoke() }
        }
        card.addView(closeBtn, abs(PANEL_W - CLOSE_RIGHT - CLOSE_W, CLOSE_Y, CLOSE_W, CLOSE_H))
    }

    private fun buildDial(card: FrameLayout, driver: Boolean) {
        val dial = TempDialView(context).apply {
            palette = this@ClimatePage.pal
            caption = if (driver) "主驾" else "副驾"
            onDelta = { delta -> VehicleController.stepTemp(if (driver) HvacIds.ZONE_DRIVER else HvacIds.ZONE_PASSENGER, delta) }
            // TempDialView 拖动结束回抛绝对温度，调用方换算成增量（0.2℃ 死区）
            onTemp = { target ->
                val cur = if (driver) VehicleController.state.tempDriver else VehicleController.state.tempPassenger
                val delta = target - cur
                if (kotlin.math.abs(delta) >= 0.2f) {
                    VehicleController.stepTemp(if (driver) HvacIds.ZONE_DRIVER else HvacIds.ZONE_PASSENGER, delta)
                }
            }
        }
        val x = if (driver) DRIVER_X else PASSENGER_X
        card.addView(dial, abs(x, DIAL_TOP, DIAL_W, DIAL_H))
        if (driver) dialDriver = dial else dialPassenger = dial

        // —— 座椅加热 / 通风胶囊（设计板 `.chip`：18px 字、内边距 8×18、胶囊圆角，列内 top 452）——
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val heatKey = if (driver) "seatHL" else "seatHR"
        val ventKey = if (driver) "seatVL" else "seatVR"
        val heat = ChipButton(context, pal, SEAT_CHIP_TEXT).apply {
            bind("加热", false)
            setPadding(SEAT_CHIP_PAD_X, SEAT_CHIP_PAD_Y, SEAT_CHIP_PAD_X, SEAT_CHIP_PAD_Y)
            setOnClickListener {
                val zone = if (driver) HvacIds.ZONE_DRIVER else HvacIds.ZONE_PASSENGER
                VehicleController.cycleSeat(zone, true)
                refresh()
            }
        }
        val vent = ChipButton(context, pal, SEAT_CHIP_TEXT).apply {
            bind("通风", false)
            setPadding(SEAT_CHIP_PAD_X, SEAT_CHIP_PAD_Y, SEAT_CHIP_PAD_X, SEAT_CHIP_PAD_Y)
            setOnClickListener {
                val zone = if (driver) HvacIds.ZONE_DRIVER else HvacIds.ZONE_PASSENGER
                VehicleController.cycleSeat(zone, false)
                refresh()
            }
        }
        seatChips[heatKey] = heat
        seatChips[ventKey] = vent
        row.addView(heat, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = SEAT_CHIP_GAP })
        row.addView(vent, LinearLayout.LayoutParams(-2, -2))
        card.addView(row, abs(x, SEAT_ROW_TOP, DIAL_W, -2))
    }

    /**
     * 中间列：风量 + 吹风模式 + 功能 + 除霜 + 座舱示意。
     */
    private fun buildMiddle(card: FrameLayout) {
        // —— 风量标题 ——
        fanLabel = Ui.text(context, "风量  0  档", 26f, Skin.TEXT_SOFT, bold = false).apply {
            gravity = Gravity.CENTER
        }
        card.addView(fanLabel, abs(MID_X, MID_FAN_LABEL_TOP, MID_W, 34))

        // —— MAX 风量（一键最大风量，设计板没画但功能保留）——
        val maxFan = ChipButton.make(context, pal, "MAX", false).apply {
            setOnClickListener {
                VehicleController.toggleMaxFan()
                refresh()
            }
        }
        card.addView(maxFan, abs(MID_X + MID_W - MAX_W, MID_FAN_LABEL_TOP, MAX_W, 40))
        chips["maxFan"] = maxFan

        // —— 7 个风量方块 ——
        fanBars = FanBarsView(context).apply {
            palette = this@ClimatePage.pal
            onLevel = { level ->
                VehicleController.stepFan(level - VehicleController.state.fanLevel)
                refresh()
            }
        }
        card.addView(fanBars, abs(MID_X + FAN_BARS_OFFSET, FAN_BARS_TOP, FAN_BARS_W, FAN_BARS_H))

        // —— 「吹风模式」标题 ——
        val modeLabel = Ui.text(context, "吹风模式", 26f, Skin.TEXT_SOFT).apply {
            gravity = Gravity.CENTER
        }
        card.addView(modeLabel, abs(MID_X, MID_MODE_LABEL_TOP, MID_W, 34))

        // —— 7 个组合按钮（3 位掩码的全部组合）——
        val modes = listOf(
            "吹脸" to 1,
            "吹脚" to 2,
            "脸+脚" to 3,
            "吹窗" to 4,
            "脸+窗" to 5,
            "脚+窗" to 6,
            "全吹" to 7,
        )
        modes.forEachIndexed { i, (label, mask) ->
            val chip = ChipButton.make(context, pal, label, false).apply {
                setOnClickListener {
                    Logx.event(LogTags.HVAC, "空调浮层：吹风模式", "mask" to mask)
                    // 组合按钮是互斥的：直接落成该掩码（0 档除外）。
                    if (mask != 0) VehicleController.setBlowingMask(mask)
                    refresh()
                }
            }
            chips["mode$mask"] = chip
            // 4 + 3 两行，居中于中间列
            val inFirstRow = i < 4
            val col = if (inFirstRow) i else i - 4
            val rowOffset = if (inFirstRow) 0 else MODE_ROW2_OFFSET
            val count = if (inFirstRow) 4 else 3
            val rowW = count * MODE_W + (count - 1) * MODE_GAP
            val startX = MID_X + (MID_W - rowW) / 2
            card.addView(chip, abs(startX + col * (MODE_W + MODE_GAP), MODE_ROW1_TOP + rowOffset, MODE_W, MODE_H))
        }

        // —— 4 个功能按钮 ——
        val funcs = listOf(
            "ac" to "A/C",
            "auto" to "AUTO",
            "circ" to "外循环",
            "sync" to "温度同步",
        )
        funcs.forEachIndexed { i, (key, label) ->
            val chip = ChipButton.make(context, pal, label, false).apply {
                setOnClickListener {
                    when (key) {
                        "ac" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleAc() }
                        "auto" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleAuto() }
                        "sync" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleSync() }
                        "circ" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleCirculation() }
                    }
                    refresh()
                }
            }
            chips[key] = chip
            val rowW = 4 * FUNC_W + 3 * FUNC_GAP
            val startX = MID_X + (MID_W - rowW) / 2
            card.addView(chip, abs(startX + i * (FUNC_W + FUNC_GAP), FUNC_TOP, FUNC_W, FUNC_H))
        }

        // —— 2 个除霜胶囊（设计板 `.chip`：24px 字、内边距 14×40、胶囊圆角，间距 24）——
        val defrostRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val defrosts = listOf("defF" to "前除霜", "defR" to "后除霜")
        defrosts.forEachIndexed { i, (key, label) ->
            val chip = ChipButton(context, pal, DEFROST_TEXT).apply {
                bind(label, false)
                setPadding(DEFROST_PAD_X, DEFROST_PAD_Y, DEFROST_PAD_X, DEFROST_PAD_Y)
                setOnClickListener {
                    when (key) {
                        "defF" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleDefrostFront() }
                        "defR" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleDefrostRear() }
                    }
                    refresh()
                }
            }
            chips[key] = chip
            defrostRow.addView(chip, LinearLayout.LayoutParams(-2, -2).also {
                if (i == 0) it.rightMargin = DEFROST_GAP
            })
        }
        card.addView(defrostRow, abs(MID_X, DEFROST_TOP, MID_W, -2))

        // —— 座舱吹风示意（CabinView，保留功能）——
        cabin = CabinView(context).apply { palette = this@ClimatePage.pal }
        card.addView(cabin, abs(MID_X + (MID_W - CABIN_W) / 2, CABIN_TOP, CABIN_W, CABIN_H))
    }

    /**
     * 座椅行已经挪到两侧表盘下方（见 [buildDial]）—— 这里不再画分隔线，
     * 设计板 `s04-hvac` 也没有那条线。
     */
    private fun buildSeatRow(card: FrameLayout) = Unit

    private fun abs(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    // ------------------------------------------------------------- 刷新

    fun refresh() {
        val s = VehicleController.state
        // 第五十轮按设计板 `s04-hvac`：圆环弧与温度数值**始终满色**（关机也是），
        // 只有 caption 会变淡 —— 用「温度同步」而不是「关机」作为变淡条件。
        val dim = s.tempSynced
        dialDriver.palette = pal
        dialPassenger.palette = pal
        dialDriver.temperature = s.tempDriver
        dialPassenger.temperature = s.tempPassenger
        // TempDialView.setSynced —— 只变暗，不拦截触摸（同步态仍可调温）
        dialDriver.dimmed = dim
        dialPassenger.dimmed = dim
        dialDriver.disabled = !s.power
        dialPassenger.disabled = !s.power
        dialDriver.caption = if (s.tempSynced) "主驾 · 同步" else "主驾"
        dialPassenger.caption = if (s.tempSynced) "副驾 · 同步" else "副驾"

        // —— 关机不显示风量；开机但读回 0 时显示 1 档
        val shownFan = when {
            !s.power -> 0
            s.fanLevel <= 0 -> 1
            else -> s.fanLevel
        }
        fanLabel.text = "风量  $shownFan  档"
        fanBars.level = if (s.power) s.fanLevel else 0
        fanBars.auto = s.fanAuto
        powerStateText.text = if (s.power) "运行中" else "已关机"

        chips["power"]?.bind("电源", s.power)
        chips["ac"]?.bind("A/C", s.ac)
        chips["auto"]?.bind("AUTO", s.auto)
        chips["sync"]?.bind("温度同步", s.tempSynced)
        // —— 只有内循环才高亮
        chips["circ"]?.bind(if (s.circulation == 0) "内循环" else "外循环", s.circulation == 0)
        chips["defF"]?.let { bindChip(it, "前除霜", s.defrostFront) }
        chips["defR"]?.let { bindChip(it, "后除霜", s.defrostRear) }

        // —— 吹风按 face=1 / leg=2 / window=4 三位掩码显示
        val blowing = HvacCodec.blowingMask(s.blowing)
        for (mask in 1..7) {
            chips["mode$mask"]?.bind(
                MODE_LABELS[mask] ?: "",
                blowing == mask,
            )
        }
        chips["maxFan"]?.bind("MAX", s.fanLevel >= HvacIds.FAN_MAX)

        cabin.palette = pal
        cabin.setFlow(blowing and 1 != 0, blowing and 2 != 0, blowing and 4 != 0)

        // 设计板 s04：座椅 chip 只写「加热 / 通风」，**不带档位数字**
        // （档位由 chip 的激活态表达；写「加热 0」会让人以为功能坏了）。
        seatChips["seatHL"]?.let { bindChip(it, "加热", s.seatHeatDriver > 0) }
        seatChips["seatVL"]?.let { bindChip(it, "通风", s.seatVentDriver > 0) }
        seatChips["seatHR"]?.let { bindChip(it, "加热", s.seatHeatPassenger > 0) }
        seatChips["seatVR"]?.let { bindChip(it, "通风", s.seatVentPassenger > 0) }
    }

    /**
     * chip 重新绑定 + 套用设计板 `.chip` 样式。
     *
     * [ChipButton] 自带的圆角是 22（给 156×68 的组合按钮用），chip 要的是胶囊 999，
     * 所以绑定之后按设计板重刷一次外观（软底 + 1px 描边 / 强调色软底 + 强调色描边）。
     */
    private fun bindChip(chip: ChipButton, text: String, active: Boolean) {
        chip.bind(text, active)
        chip.setTextColor(if (active) Skin.ACCENT else Skin.TEXT_SOFT)
        chip.background = Ui.round(
            if (active) Skin.ACCENT_SOFT else Skin.surfaceSoft,
            Shape.PILL,
            1f,
            if (active) Skin.ACCENT else Skin.NAV_STROKE,
        )
    }

    companion object {
        /** 空调面板宽（设计 px）。 */
        const val PANEL_W = 1640

        /** 空调面板高（设计 px）—— 第五十二轮随内容上移长高 37（830 → 867），底边仍贴 940。 */
        const val PANEL_H = 867

        /** 空调面板左边距（设计 px）—— 居中（1920 − 1640 = 280，两侧各 140）。 */
        const val PANEL_LEFT = 140

        /** 空调面板上边距（设计 px）—— 第五十二轮 110 → 73（状态栏缩到 2/3 后的内容基线）。 */
        const val PANEL_TOP = 73

        /** 面板内容左右留白（设计 px）。 */
        const val PANEL_PAD = 40

        /** 标题顶部。 */
        const val TITLE_TOP = 28

        /** 电源状态副标题左边缘。 */
        const val TITLE_STATE_X = 150

        /** 电源状态副标题顶部。 */
        const val TITLE_STATE_TOP = 42

        /** 电源胶囊左边缘。 */
        const val POWER_X = 268

        /** 电源胶囊顶部。 */
        const val POWER_Y = 30

        /** 「关闭」胶囊宽。 */
        const val CLOSE_W = 150

        /** 「关闭」胶囊高。 */
        const val CLOSE_H = 66

        /** 「关闭」胶囊顶部。 */
        const val CLOSE_Y = 28

        /** 「关闭」胶囊右边距（设计 px）。 */
        const val CLOSE_RIGHT = 36

        /** 主驾列左边缘。 */
        const val DRIVER_X = 60

        /** 副驾列左边缘。 */
        const val PASSENGER_X = 1240

        /** 表盘列宽。 */
        const val DIAL_W = 340

        /** 表盘列高（caption + 环 + −/+ 按钮）。 */
        const val DIAL_H = 424

        /** 表盘顶部。 */
        const val DIAL_TOP = 110

        /** 座椅胶囊顶部（列内 top 452 + 列顶 110）。 */
        const val SEAT_ROW_TOP = 562

        /** 座椅胶囊字号（设计 px）。 */
        const val SEAT_CHIP_TEXT = 18f

        /** 座椅胶囊左右内边距（设计 px）。 */
        const val SEAT_CHIP_PAD_X = 18

        /** 座椅胶囊上下内边距（设计 px）。 */
        const val SEAT_CHIP_PAD_Y = 8

        /** 座椅胶囊间距。 */
        const val SEAT_CHIP_GAP = 24

        /** 座椅区分隔线顶部。 */
        const val SEAT_DIVIDER_TOP = 534

        /** 中间列左边缘。 */
        const val MID_X = 460

        /** 中间列宽。 */
        const val MID_W = 720

        /** 风量标题顶部。 */
        const val MID_FAN_LABEL_TOP = 110

        /** 风量方块行顶部。 */
        val FAN_BARS_TOP = MID_FAN_LABEL_TOP + 48

        /** 风量方块行高。 */
        const val FAN_BARS_H = 56

        /** 风量方块行宽（7 × 56 + 6 × 14）。 */
        const val FAN_BARS_W = 7 * 56 + 6 * 14

        /** 风量方块行在中间列里的居中偏移。 */
        val FAN_BARS_OFFSET = (MID_W - FAN_BARS_W) / 2

        /** MAX 胶囊宽。 */
        const val MAX_W = 120

        /** 「吹风模式」标题顶部。 */
        val MID_MODE_LABEL_TOP = FAN_BARS_TOP + FAN_BARS_H + 30

        /** 吹风模式第一行顶部。 */
        val MODE_ROW1_TOP = MID_MODE_LABEL_TOP + 48

        /** 吹风模式按钮宽。 */
        const val MODE_W = 156

        /** 吹风模式按钮高。 */
        const val MODE_H = 68

        /** 吹风模式按钮间距。 */
        const val MODE_GAP = 16

        /** 吹风模式第二行相对第一行的偏移。 */
        val MODE_ROW2_OFFSET = MODE_H + MODE_GAP

        /** 功能按钮行顶部。 */
        val FUNC_TOP = MODE_ROW1_TOP + MODE_ROW2_OFFSET + MODE_H + 24

        /** 功能按钮宽。 */
        const val FUNC_W = 156

        /** 功能按钮高。 */
        const val FUNC_H = 68

        /** 功能按钮间距。 */
        const val FUNC_GAP = 16

        /** 除霜胶囊行顶部。 */
        val DEFROST_TOP = FUNC_TOP + FUNC_H + 26

        /** 除霜胶囊字号（设计 px）。 */
        const val DEFROST_TEXT = 24f

        /** 除霜胶囊左右内边距（设计 px）。 */
        const val DEFROST_PAD_X = 40

        /** 除霜胶囊上下内边距（设计 px）。 */
        const val DEFROST_PAD_Y = 14

        /** 除霜胶囊占位高（设计 px）—— 排座舱示意时用。 */
        const val DEFROST_BLOCK_H = 60

        /** 除霜胶囊间距。 */
        const val DEFROST_GAP = 24

        /** 座舱示意顶部。 */
        val CABIN_TOP = DEFROST_TOP + DEFROST_BLOCK_H + 24

        /** 座舱示意宽。 */
        const val CABIN_W = 300

        /** 座舱示意高。 */
        const val CABIN_H = 150

        /** 吹风模式按钮文案（掩码 → 文案）。 */
        val MODE_LABELS = mapOf(
            1 to "吹脸",
            2 to "吹脚",
            3 to "脸+脚",
            4 to "吹窗",
            5 to "脸+窗",
            6 to "脚+窗",
            7 to "全吹",
        )
    }
}
