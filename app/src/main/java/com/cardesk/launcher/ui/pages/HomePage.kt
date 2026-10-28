package com.cardesk.launcher.ui.pages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.ChipButton
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleController
import com.cardesk.launcher.vehicle.VehicleInfo
import java.util.Calendar

/**
 * 首页：一屏看完车辆概况，并能一键进入空调 / 360。
 */
@SuppressLint("ViewConstructor")
class HomePage(context: Context, private val pal: Palette) : FrameLayout(context) {

    var onOpenHvac: (() -> Unit)? = null
    var onOpen360: (() -> Unit)? = null
    var onOpenVehicle: (() -> Unit)? = null

    private val greeting: TextView
    private val subtitle: TextView
    private val gearChip: ChipButton
    private val rangeValue: TextView
    private val ambientValue: TextView
    private val speedValue: TextView
    private val odometerValue: TextView
    private val tireValues = arrayOfNulls<TextView>(4)
    private val sourceLabel: TextView

    private val powerChip: ChipButton
    private val tempDriver: TextView
    private val tempPassenger: TextView
    private val fanLabel: TextView
    private val acChip: ChipButton
    private val autoChip: ChipButton
    private val circChip: ChipButton
    private val defrostChip: ChipButton

    init {
        greeting = Ui.text(context, "晚上好", 44f, pal.text, bold = true)
        subtitle = Ui.text(context, "欢迎回来", 24f, pal.textDim)
        val header = Cards.column(context)
        val headerRow = Cards.row(context)
        headerRow.addView(greeting)
        headerRow.addView(Cards.vgap(context, 18))
        headerRow.addView(subtitle)
        header.addView(headerRow)
        addView(header, Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 56, 4))

        // ---------------------------------------------------------------- 卡片区
        val cardsRow = Cards.row(context)
        cardsRow.gravity = Gravity.CENTER_VERTICAL
        addView(cardsRow, Ui.frame(1744, 748, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 96))

        // 卡 1：车辆状态
        val vehicle = Cards.card(context, pal)
        val vehicleTop = Cards.row(context)
        gearChip = ChipButton.make(context, pal, "P 挡")
        gearChip.setOnClickListener {
            // 排障场景：「首页挡位胶囊点了没反应」。
            Logx.event(LogTags.SCENE, "首页挡位胶囊点击")
            onOpenVehicle?.invoke()
        }
        vehicleTop.addView(Cards.caption(context, pal, "车辆状态", 26f))
        vehicleTop.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        vehicleTop.addView(gearChip, LinearLayout.LayoutParams(130, 56))
        vehicle.addView(vehicleTop, Ui.linear(-1, 56))

        vehicle.addView(Cards.gap(context, 22))
        val rangeRow = Cards.row(context)
        rangeRow.gravity = Gravity.BOTTOM
        rangeValue = Cards.value(context, pal, "386", 78f)
        rangeRow.addView(rangeValue)
        rangeRow.addView(Cards.vgap(context, 12))
        val rangeUnit = Cards.unit(context, pal, "km 续航")
        rangeUnit.layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            bottomMargin = 16
        }
        rangeRow.addView(rangeUnit)
        vehicle.addView(rangeRow, Ui.linear(-1, 90))

        vehicle.addView(Cards.gap(context, 26))
        ambientValue = Cards.value(context, pal, "26.5 °C", 30f)
        speedValue = Cards.value(context, pal, "0 km/h", 30f)
        odometerValue = Cards.value(context, pal, "12,480 km", 30f)
        vehicle.addView(Cards.kv(context, pal, "车外温度", "26.5 °C").also { ambientValue.tag = it })
        vehicle.addView(Cards.gap(context, 14))
        vehicle.addView(Cards.kv(context, pal, "车速", "0 km/h"))
        vehicle.addView(Cards.gap(context, 14))
        vehicle.addView(Cards.kv(context, pal, "总里程", "12,480 km"))
        vehicle.addView(Cards.gap(context, 26))
        vehicle.addView(Cards.caption(context, pal, "胎压 (bar)", 22f))
        vehicle.addView(Cards.gap(context, 12))
        val tireNames = arrayOf("左前", "右前", "左后", "右后")
        for (row in 0 until 2) {
            val rowView = Cards.row(context)
            for (col in 0 until 2) {
                val index = row * 2 + col
                val cell = Cards.row(context)
                cell.addView(Cards.caption(context, pal, tireNames[index], 22f))
                val v = Ui.text(context, "--", 28f, pal.text, bold = true)
                tireValues[index] = v
                cell.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                cell.addView(v)
                rowView.addView(cell, Ui.linear(0, 56, 1f))
                if (col == 0) rowView.addView(View(context), LinearLayout.LayoutParams(28, 1))
            }
            vehicle.addView(rowView, Ui.linear(-1, 56))
        }
        vehicle.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        sourceLabel = Cards.caption(context, pal, "数据来源：未连接", 18f)
        vehicle.addView(sourceLabel)
        cardsRow.addView(vehicle, Ui.linear(562, 748))
        cardsRow.addView(View(context), LinearLayout.LayoutParams(28, 1))

        // 卡 2：空调
        val hvac = Cards.card(context, pal)
        val hvacTop = Cards.row(context)
        powerChip = ChipButton.make(context, pal, "电源")
        powerChip.setOnClickListener {
            // 排障场景：「首页空调电源按钮点了车没反应」——动作落盘。
            Logx.event(LogTags.SCENE, "首页空调电源点击")
            VehicleController.togglePower()
        }
        hvacTop.addView(Cards.caption(context, pal, "空调", 26f))
        hvacTop.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        hvacTop.addView(powerChip, LinearLayout.LayoutParams(130, 56))
        hvac.addView(hvacTop, Ui.linear(-1, 56))

        hvac.addView(Cards.gap(context, 28))
        val temps = Cards.row(context)
        val leftCol = Cards.column(context)
        leftCol.addView(Cards.caption(context, pal, "主驾", 24f))
        tempDriver = Cards.value(context, pal, "23.0°", 62f)
        leftCol.addView(tempDriver)
        val rightCol = Cards.column(context)
        rightCol.addView(Cards.caption(context, pal, "副驾", 24f))
        tempPassenger = Cards.value(context, pal, "23.0°", 62f)
        rightCol.addView(tempPassenger)
        temps.addView(leftCol, Ui.linear(0, 150, 1f))
        temps.addView(rightCol, Ui.linear(0, 150, 1f))
        hvac.addView(temps, Ui.linear(-1, 150))

        hvac.addView(Cards.gap(context, 18))
        fanLabel = Ui.text(context, "风量 2 档 · 吹脸", 24f, pal.textDim)
        hvac.addView(fanLabel)

        hvac.addView(Cards.gap(context, 24))
        acChip = ChipButton.make(context, pal, "A/C")
        acChip.setOnClickListener {
            Logx.event(LogTags.SCENE, "首页空调 A/C 点击")
            VehicleController.toggleAc()
        }
        autoChip = ChipButton.make(context, pal, "AUTO")
        autoChip.setOnClickListener {
            Logx.event(LogTags.SCENE, "首页空调 AUTO 点击")
            VehicleController.toggleAuto()
        }
        circChip = ChipButton.make(context, pal, "内循环")
        circChip.setOnClickListener {
            Logx.event(LogTags.SCENE, "首页空调内外循环点击")
            VehicleController.toggleCirculation()
        }
        defrostChip = ChipButton.make(context, pal, "前除霜")
        defrostChip.setOnClickListener {
            Logx.event(LogTags.SCENE, "首页空调前除霜点击")
            VehicleController.toggleDefrostFront()
        }
        val chips = Cards.column(context)
        chips.addView(
            Cards.row(context).apply {
                addView(acChip, Ui.linear(0, 62, 1f))
                addView(View(context), LinearLayout.LayoutParams(18, 1))
                addView(autoChip, Ui.linear(0, 62, 1f))
            },
            Ui.linear(-1, 62),
        )
        chips.addView(Cards.gap(context, 16))
        chips.addView(
            Cards.row(context).apply {
                addView(circChip, Ui.linear(0, 62, 1f))
                addView(View(context), LinearLayout.LayoutParams(18, 1))
                addView(defrostChip, Ui.linear(0, 62, 1f))
            },
            Ui.linear(-1, 62),
        )
        hvac.addView(chips, Ui.linear(-1, 140))

        hvac.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        val openHvac = ChipButton.make(context, pal, "打开空调面板").apply {
            setOnClickListener {
                Logx.event(LogTags.SCENE, "首页打开空调面板点击")
                onOpenHvac?.invoke()
            }
            pressFeedback()
        }
        hvac.addView(openHvac, Ui.linear(-1, 72))
        cardsRow.addView(hvac, Ui.linear(562, 748))
        cardsRow.addView(View(context), LinearLayout.LayoutParams(28, 1))

        // 卡 3：360
        val avm = Cards.card(context, pal)
        avm.addView(Cards.caption(context, pal, "360 全景影像", 26f))
        avm.addView(Cards.gap(context, 20))
        val preview = MiniAvmView(context, pal)
        avm.addView(preview, Ui.linear(-1, 300))
        avm.addView(Cards.gap(context, 20))
        avm.addView(Cards.kv(context, pal, "当前视角", "全景"))
        avm.addView(Cards.gap(context, 12))
        avm.addView(Cards.kv(context, pal, "激活状态", "待机"))
        avm.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        val open360 = ChipButton.make(context, pal, "打开 360").apply {
            setOnClickListener {
                Logx.event(LogTags.SCENE, "首页打开 360 点击")
                onOpen360?.invoke()
            }
            pressFeedback()
        }
        avm.addView(open360, Ui.linear(-1, 72))
        cardsRow.addView(avm, Ui.linear(562, 748))
    }

    fun bind(info: VehicleInfo, s: HvacState, source: String) {
        // 排障场景：「首页数据不刷新」——数据绑定用 DEBUG，不落盘。
        Logx.d(
            LogTags.SCENE,
            "首页数据绑定",
            "source" to source,
            "rangeKm" to info.rangeKm,
            "speed" to info.speedKmh,
            "gear" to info.gear,
        )
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = when {
            hour in 5..10 -> "早上好"
            hour in 11..13 -> "中午好"
            hour in 14..18 -> "下午好"
            else -> "晚上好"
        }
        subtitle.text = "欢迎回来 · $source"

        gearChip.bind("${info.gear} 挡", info.gear == "P")
        rangeValue.text = if (info.rangeKm >= 0) info.rangeKm.toString() else "--"
        ambientValue.text = fmtTemp(info.ambientTemp)
        speedValue.text = "${info.speedKmh} km/h"
        odometerValue.text = if (info.odometerKm >= 0) "${info.odometerKm} km" else "--"
        // 上面的 kv 行是 build 阶段生成的，这里按顺序改第二列文本
        val card = getChildAt(1) as? LinearLayout
        if (card != null) {
            val vehicleCard = card.getChildAt(0) as? LinearLayout
            if (vehicleCard != null) {
                for (i in 0 until vehicleCard.childCount) {
                    val row = vehicleCard.getChildAt(i) as? LinearLayout ?: continue
                    if (row.childCount >= 2) {
                        val key = (row.getChildAt(0) as? TextView)?.text?.toString()
                        val value = row.getChildAt(row.childCount - 1) as? TextView ?: continue
                        when (key) {
                            "车外温度" -> value.text = fmtTemp(info.ambientTemp)
                            "车速" -> value.text = "${info.speedKmh} km/h"
                            "总里程" -> value.text = if (info.odometerKm >= 0) "${info.odometerKm} km" else "--"
                        }
                    }
                }
                for (i in tireValues.indices) {
                    val t = info.tires.getOrNull(i) ?: Float.NaN
                    tireValues[i]?.text = if (t.isNaN()) "--" else String.format("%.1f", t)
                }
                sourceLabel.text = "数据来源：${info.source}"
            }
        }

        powerChip.setActive(s.power)
        tempDriver.text = String.format("%.1f°", s.tempDriver)
        tempPassenger.text = String.format("%.1f°", s.tempPassenger)
        val mode = HvacIds.BLOW_LABELS.getOrNull(s.blowing) ?: "--"
        fanLabel.text = if (s.fanAuto) "风量 自动 · $mode" else "风量 ${s.fanLevel} 档 · $mode"
        acChip.setActive(s.ac)
        autoChip.setActive(s.auto)
        circChip.bind(
            when (s.circulation) {
                0 -> "内循环"
                2 -> "自动循环"
                else -> "外循环"
            },
            s.circulation == 0,
        )
        defrostChip.setActive(s.defrostFront)
    }

    private fun fmtTemp(t: Float): String =
        if (t.isNaN()) "-- °C" else String.format("%.1f °C", t)

    /** 首页那张小尺寸环视预览，纯装饰性绘制。 */
    private class MiniAvmView(context: Context, private val pal: Palette) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = RectF(0f, 0f, w, h)

            paint.color = pal.surfaceSoft
            canvas.drawRoundRect(r, 22f, 22f, paint)

            val pad = 8f
            val pw = (w - pad * 3) / 2f
            val ph = (h - pad * 3) / 2f
            val labels = arrayOf("前", "右", "左", "后")
            for (i in 0 until 4) {
                val left = pad + (i % 2) * (pw + pad)
                val top = pad + (i / 2) * (ph + pad)
                paint.color = if (i == 0) pal.withAlpha(pal.accent, 0x33) else pal.withAlpha(pal.textFaint, 0x22)
                canvas.drawRoundRect(RectF(left, top, left + pw, top + ph), 14f, 14f, paint)
                text.color = pal.textDim
                canvas.drawText(labels[i], left + pw / 2f, top + ph / 2f + 7f, text)
            }

            // 中央车模
            val cx = w / 2f
            val cy = h / 2f
            paint.color = pal.surfaceStrong
            val body = RectF(cx - 46f, cy - 26f, cx + 46f, cy + 26f)
            canvas.drawRoundRect(body, 14f, 14f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = pal.accent
            canvas.drawRoundRect(body, 14f, 14f, paint)
            paint.style = Paint.Style.FILL
            paint.color = pal.accentSoft
            val windshield = Path().apply {
                moveTo(cx - 30f, cy - 16f)
                lineTo(cx + 30f, cy - 16f)
                lineTo(cx + 22f, cy - 4f)
                lineTo(cx - 22f, cy - 4f)
                close()
            }
            canvas.drawPath(windshield, paint)
        }
    }
}
