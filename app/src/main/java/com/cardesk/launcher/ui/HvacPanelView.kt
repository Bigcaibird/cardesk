package com.cardesk.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.widget.FanStepsView
import com.cardesk.launcher.ui.widget.SeatLevelView
import com.cardesk.launcher.ui.widget.TempDialView
import com.cardesk.launcher.vehicle.HvacCodec
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleController

/**
 * 空调面板浮层：底部 Dock「空调」或首页卡片打开。
 *
 * 布局借鉴了车机桌面那张浮层的信息结构（左主驾 / 中控制区 / 右副驾），
 * 控件与绘制全部自己实现。
 */
@SuppressLint("ViewConstructor")
class HvacPanelView(context: Context, private val pal: Palette) : FrameLayout(context) {

    var onDismiss: (() -> Unit)? = null

    private val title: TextView
    private val subtitle: TextView
    private val driverDial: TempDialView
    private val passengerDial: TempDialView
    private val fan: FanStepsView
    private val modeChips = ArrayList<ChipButton>()
    private val togglePower: ChipButton
    private val fanLabel: TextView
    private val toggleAc: ChipButton
    private val toggleAuto: ChipButton
    private val toggleCirc: ChipButton
    private val toggleSync: ChipButton
    private val defrostFront: ChipButton
    private val defrostRear: ChipButton
    private val driverSeatHeat: SeatLevelView
    private val driverSeatVent: SeatLevelView
    private val passengerSeatHeat: SeatLevelView
    private val passengerSeatVent: SeatLevelView

    /** 8 秒无操作自动关闭。 */
    private val dismissTimer = Runnable { dismiss() }
    private val handler = Handler(Looper.getMainLooper())

    init {
        // 第五十一轮：遮罩跟随主题（夜 0xA808090C / 日 0x61181A20），不再写死 60% 黑。
        setBackgroundColor(Skin.SCRIM)
        isClickable = true
        setOnClickListener { dismiss() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 第四十九轮「浮屿」：纯扁平面 + 1px 描边。
            background = Ui.round(pal.surface, com.cardesk.launcher.core.Shape.CARD, 1f, pal.stroke)
            setPadding(40, 28, 40, 24)
            isClickable = true
        }
        addView(card, Ui.frame(1640, 856, Gravity.CENTER))

        // ---- 标题行 ----
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title = Ui.text(context, "空调", 40f, pal.text, bold = true)
        subtitle = Ui.text(context, "", 22f, pal.textDim)
        val close = ChipButton.make(context, pal, "关闭").apply { setOnClickListener { dismiss() } }
        header.addView(title)
        header.addView(subtitle, Ui.linear(0, -2, 1f, 24).apply { leftMargin = 22 })
        header.addView(close, Ui.linear(120, 60))
        card.addView(header, Ui.linear(-1, 72))

        // ---- 主体三列 ----
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(body, Ui.linear(-1, 0, 1f))

        val left = buildZone(context, HvacIds.ZONE_DRIVER, "主驾")
        driverDial = left.first
        driverSeatHeat = left.second
        driverSeatVent = left.third
        body.addView(left.fourth, Ui.linear(430, -1))

        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 0, 24, 0)
        }
        body.addView(center, Ui.linear(0, -1, 1f))

        fanLabel = Ui.text(context, "风量  0  档", 22f, pal.textDim)
        center.addView(fanLabel, Ui.linear(-2, 34))
        fan = FanStepsView(context)
        fan.palette = pal
        fan.onLevel = { level -> VehicleController.stepFan(level - fan.level) }
        center.addView(fan, Ui.linear(-1, 92, 0f, 0, 12))

        center.addView(Ui.text(context, "吹风模式", 22f, pal.textDim), Ui.linear(-2, 34, 0f, 0, 26))
        val modeRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (row in 0 until 2) {
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (col in 0 until 4) {
                val index = row * 4 + col
                if (index >= HvacIds.BLOW_LABELS.size) break
                val chip = ChipButton.make(context, pal, HvacIds.BLOW_LABELS[index])
                chip.setOnClickListener { VehicleController.setBlowing(index) }
                modeChips.add(chip)
                rowView.addView(chip, Ui.linear(158, 64, 0f, 14, 10))
            }
            modeRows.addView(rowView, Ui.linear(-2, 74))
        }
        center.addView(modeRows, Ui.linear(-1, 148))

        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 26, 0, 0)
        }
        // —— 浮层上有电源键
        togglePower = ChipButton.make(context, pal, "电源")
        togglePower.setOnClickListener { VehicleController.togglePower() }
        toggleAc = ChipButton.make(context, pal, "A/C")
        toggleAc.setOnClickListener { VehicleController.toggleAc() }
        toggleAuto = ChipButton.make(context, pal, "AUTO")
        toggleAuto.setOnClickListener { VehicleController.toggleAuto() }
        toggleCirc = ChipButton.make(context, pal, "内循环")
        toggleCirc.setOnClickListener { VehicleController.toggleCirculation() }
        toggleSync = ChipButton.make(context, pal, "温度同步")
        toggleSync.setOnClickListener { VehicleController.toggleSync() }
        for (chip in listOf(togglePower, toggleAc, toggleAuto, toggleCirc, toggleSync)) {
            toggleRow.addView(chip, Ui.linear(158, 68, 0f, 14, 0))
        }
        center.addView(toggleRow, Ui.linear(-1, 100, 0f, 0, 10))

        val right = buildZone(context, HvacIds.ZONE_PASSENGER, "副驾")
        passengerDial = right.first
        passengerSeatHeat = right.second
        passengerSeatVent = right.third
        body.addView(right.fourth, Ui.linear(430, -1))

        // ---- 底部：除霜 ----
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        defrostFront = ChipButton.make(context, pal, "前除霜")
        defrostFront.setOnClickListener { VehicleController.toggleDefrostFront() }
        defrostRear = ChipButton.make(context, pal, "后除霜")
        defrostRear.setOnClickListener { VehicleController.toggleDefrostRear() }
        footer.addView(defrostFront, Ui.linear(200, 64, 0f, 16, 0))
        footer.addView(defrostRear, Ui.linear(200, 64))
        card.addView(footer, Ui.linear(-1, 96))
    }

    private fun buildZone(context: Context, zone: Int, caption: String): Quad<TempDialView, SeatLevelView, SeatLevelView, LinearLayout> {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(8, 0, 8, 0)
        }
        column.addView(Ui.text(context, caption, 24f, pal.textDim), Ui.linear(-2, 30))

        val dial = TempDialView(context)
        dial.palette = pal
        dial.onDelta = { delta -> VehicleController.stepTemp(zone, delta) }
        // TempDialView 拖动结束回抛绝对温度，换算成增量（0.2℃ 死区）
        dial.onTemp = { target ->
            val cur = VehicleController.state.tempOf(zone)
            val delta = target - cur
            if (kotlin.math.abs(delta) >= 0.2f) {
                VehicleController.stepTemp(zone, delta)
            }
        }
        dial.caption = ""
        column.addView(dial, Ui.linear(-1, 340, 0f, 0, 6))

        val seatRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }
        val heat = SeatLevelView(context)
        heat.palette = pal
        heat.heat = true
        heat.caption = "加热"
        heat.onCycle = { VehicleController.cycleSeat(zone, true) }
        val vent = SeatLevelView(context)
        vent.palette = pal
        vent.heat = false
        vent.caption = "通风"
        vent.onCycle = { VehicleController.cycleSeat(zone, false) }
        seatRow.addView(heat, Ui.linear(190, 170, 0f, 20, 0))
        seatRow.addView(vent, Ui.linear(190, 170))
        column.addView(seatRow, Ui.linear(-1, 170))
        return Quad(dial, heat, vent, column)
    }

    fun dismiss() {
        handler.removeCallbacks(dismissTimer)
        visibility = View.GONE
        onDismiss?.invoke()
    }

    /** 打开时启动 8 秒无操作自动关闭；每次交互重置。 */
    fun armAutoDismiss() {
        handler.removeCallbacks(dismissTimer)
        handler.postDelayed(dismissTimer, AUTO_DISMISS_MS)
    }

    /** 交互后重置自动关闭计时。 */
    fun resetAutoDismiss() {
        armAutoDismiss()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) armAutoDismiss()
        return super.onTouchEvent(event)
    }

    /** 用最新车控状态刷新面板。 */
    fun bind(state: HvacState, source: String) {
        // —— 副标题显示电源状态
        subtitle.text = if (state.power) "运行中" else "已关机"

        driverDial.temperature = state.tempDriver
        passengerDial.temperature = state.tempPassenger
        // TempDialView.setSynced —— 只变暗，不禁用触摸
        driverDial.dimmed = state.tempSynced
        passengerDial.dimmed = state.tempSynced
        driverDial.disabled = !state.power
        passengerDial.disabled = !state.power

        // —— 风量文案与档位条都跟电源状态联动
        fanLabel.text = "风量  ${state.fanLevel}  档"
        fan.level = if (state.power) state.fanLevel else 0
        fan.auto = state.fanAuto

        for (i in modeChips.indices) modeChips[i].setActive(i == state.blowing)

        togglePower.setActive(state.power)
        toggleAc.setActive(state.ac)
        toggleAuto.setActive(state.auto)
        // —— 循环只有内/外两态
        toggleCirc.bind(if (state.circulation == 0) "内循环" else "外循环", state.circulation == 0)
        toggleSync.setActive(state.tempSynced)
        defrostFront.setActive(state.defrostFront)
        defrostRear.setActive(state.defrostRear)

        driverSeatHeat.level = state.seatHeatDriver
        driverSeatVent.level = state.seatVentDriver
        passengerSeatHeat.level = state.seatHeatPassenger
        passengerSeatVent.level = state.seatVentPassenger
    }

    private class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        /** 无操作自动关闭时长。 */
        const val AUTO_DISMISS_MS = 8000L
    }
}
