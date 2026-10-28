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
import android.widget.Toast
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.ChipButton
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.vehicle.TireOrder
import com.cardesk.launcher.vehicle.VehicleInfo

/**
 * 车辆页：续航 / 胎压 / 车门状态 / 一些模拟快捷动作。
 */
@SuppressLint("ViewConstructor")
class VehiclePage(context: Context, private val pal: Palette) : FrameLayout(context) {

    private val rangeValue: TextView
    private val tireValues = arrayOfNulls<TextView>(4)
    private val carView: TopCarView
    private val doorLabels = ArrayList<TextView>()
    private val sourceLabel: TextView

    init {
        addView(Ui.text(context, "车辆", 44f, pal.text, bold = true), Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 56, 4))
        sourceLabel = Cards.caption(context, pal, "", 20f)
        addView(sourceLabel, Ui.frame(-2, -2, Gravity.TOP or Gravity.END, 64, 14))

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(grid, Ui.frame(1744, 748, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 96))

        // 第一行：续航卡 + 胎压卡
        val row1 = Cards.row(context)
        grid.addView(row1, Ui.linear(-1, 340))

        val range = Cards.card(context, pal)
        range.addView(Cards.caption(context, pal, "续航与电量", 24f))
        range.addView(Cards.gap(context, 10))
        rangeValue = Cards.value(context, pal, "386 km", 64f)
        range.addView(rangeValue)
        range.addView(Cards.gap(context, 18))
        range.addView(Cards.kv(context, pal, "电池电量", "78%"))
        range.addView(Cards.gap(context, 10))
        range.addView(Cards.kv(context, pal, "平均电耗", "14.2 kWh/100km"))
        row1.addView(range, Ui.linear(860, 340))

        row1.addView(View(context), LinearLayout.LayoutParams(24, 1))

        val tires = Cards.card(context, pal)
        val tireTop = Cards.row(context)
        tireTop.addView(Cards.caption(context, pal, "胎压监测", 24f))
        tireTop.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        tires.addView(tireTop, Ui.linear(-1, 36))
        tires.addView(Cards.gap(context, 8))
        val tireGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val names = TireOrder.NAMES
        for (r in 0 until 2) {
            val rowView = Cards.row(context)
            for (c in 0 until 2) {
                val i = r * 2 + c
                val cell = Cards.row(context)
                cell.addView(Cards.caption(context, pal, names[i], 22f))
                cell.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                val v = Ui.text(context, "--", 30f, pal.text, bold = true)
                tireValues[i] = v
                cell.addView(v)
                rowView.addView(cell, Ui.linear(0, 62, 1f))
                if (c == 0) rowView.addView(View(context), LinearLayout.LayoutParams(30, 1))
            }
            tireGrid.addView(rowView, Ui.linear(-1, 62))
        }
        tires.addView(tireGrid, Ui.linear(-1, 148))
        row1.addView(tires, Ui.linear(0, 340, 1f))

        grid.addView(Cards.gap(context, 24))

        // 第二行：车模 + 状态
        val row2 = Cards.row(context)
        grid.addView(row2, Ui.linear(-1, 0, 1f))

        val model = Cards.card(context, pal)
        model.addView(Cards.caption(context, pal, "车门与车窗", 24f))
        val modelBody = Cards.row(context)
        model.addView(modelBody, Ui.linear(-1, 0, 1f))
        carView = TopCarView(context, pal)
        modelBody.addView(carView, Ui.linear(300, -1))
        modelBody.addView(View(context), LinearLayout.LayoutParams(28, 1))
        val states = Cards.column(context)
        states.gravity = Gravity.CENTER_VERTICAL
        for (label in listOf("主驾车门", "副驾车门", "左后车门", "右后车门")) {
            val rowView = Cards.row(context)
            rowView.addView(Cards.caption(context, pal, label, 24f))
            rowView.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            val v = Ui.text(context, "已关闭", 24f, pal.ok, bold = true)
            doorLabels.add(v)
            rowView.addView(v)
            states.addView(rowView, Ui.linear(-1, 52))
        }
        modelBody.addView(states, Ui.linear(0, -1, 1f))
        row2.addView(model, Ui.linear(860, -1))

        row2.addView(View(context), LinearLayout.LayoutParams(24, 1))

        val actions = Cards.card(context, pal)
        actions.addView(Cards.caption(context, pal, "快捷动作", 24f))
        actions.addView(Cards.gap(context, 16))
        val actionNames = listOf("解锁车辆", "锁车", "寻车闪灯", "后备箱", "车窗透气", "空调预热")
        for (i in actionNames.indices step 2) {
            val rowView = Cards.row(context)
            for (j in i until minOf(i + 2, actionNames.size)) {
                val name = actionNames[j]
                val chip = ChipButton.make(context, pal, name)
                chip.setOnClickListener {
                    // 排障场景：「车辆页快捷动作点了只弹 Toast」——动作名落盘。
                    Logx.event(LogTags.SCENE, "车辆页快捷动作点击", "action" to name)
                    Toast.makeText(context, "模拟环境：$name（真车通道接入后下发）", Toast.LENGTH_SHORT).show()
                }
                rowView.addView(chip, Ui.linear(0, 72, 1f))
                if (j == i) rowView.addView(View(context), LinearLayout.LayoutParams(18, 1))
            }
            actions.addView(rowView, Ui.linear(-1, 72))
            actions.addView(Cards.gap(context, 14))
        }
        row2.addView(actions, Ui.linear(0, -1, 1f))
    }

    fun bind(info: VehicleInfo) {
        // 排障场景：「车辆页数据不刷新」——数据绑定用 DEBUG，不落盘。
        Logx.d(
            LogTags.SCENE,
            "车辆页数据绑定",
            "source" to info.source,
            "rangeKm" to info.rangeKm,
        )
        rangeValue.text = if (info.rangeKm >= 0) "${info.rangeKm} km" else "--"
        for (i in tireValues.indices) {
            val t = info.tires.getOrNull(i) ?: Float.NaN
            // 第三十七轮：单位是 kPa（真车 TPMS 报 240/227/237/235），按整数显示。
            tireValues[i]?.text = if (t.isNaN()) "--" else String.format(java.util.Locale.US, "%d", Math.round(t))
        }
        sourceLabel.text = "数据来源：${info.source}"
        // 车门：优先用四门明细；**整组明细都没有**时才退回整体状态（UI 只有二态）。
        //
        // 第四十五轮起明细是**逐门独立**的，所以「这一扇读不到」与
        // 「整组读不到」要分开：前者显示「--」（不猜），后者才退回二态。
        val doors = info.doorsClosed
        val allClosed = info.doorsAllClosed
        for (i in doorLabels.indices) {
            val closed = if (doors != null) doors.getOrNull(i) else allClosed
            val v = doorLabels[i] ?: continue
            v.text = when (closed) {
                true -> "已关闭"
                false -> "已打开"
                null -> "--"
            }
            v.setTextColor(
                when (closed) {
                    true -> pal.ok
                    false -> pal.warn
                    null -> pal.textFaint
                },
            )
        }
        carView.invalidate()
    }

    /** 俯视车模，纯绘制。 */
    private class TopCarView(context: Context, private val pal: Palette) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            paint.color = pal.surfaceSoft
            canvas.drawRoundRect(RectF(0f, 0f, w, h), 22f, 22f, paint)

            paint.color = pal.surfaceStrong
            val bodyW = w * 0.30f
            val bodyH = h * 0.82f
            val body = RectF(cx - bodyW / 2, cy - bodyH / 2, cx + bodyW / 2, cy + bodyH / 2)
            canvas.drawRoundRect(body, 42f, 42f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = pal.accent
            canvas.drawRoundRect(body, 42f, 42f, paint)
            paint.style = Paint.Style.FILL

            paint.color = pal.accentSoft
            val front = Path().apply {
                moveTo(cx - bodyW * 0.30f, cy - bodyH * 0.26f)
                lineTo(cx + bodyW * 0.30f, cy - bodyH * 0.26f)
                lineTo(cx + bodyW * 0.22f, cy - bodyH * 0.10f)
                lineTo(cx - bodyW * 0.22f, cy - bodyH * 0.10f)
                close()
            }
            canvas.drawPath(front, paint)
            val rear = Path().apply {
                moveTo(cx - bodyW * 0.28f, cy + bodyH * 0.20f)
                lineTo(cx + bodyW * 0.28f, cy + bodyH * 0.20f)
                lineTo(cx + bodyW * 0.20f, cy + bodyH * 0.32f)
                lineTo(cx - bodyW * 0.20f, cy + bodyH * 0.32f)
                close()
            }
            canvas.drawPath(rear, paint)

            paint.color = pal.withAlpha(pal.textFaint, 0xCC)
            val tireW = bodyW * 0.22f
            val tireH = bodyH * 0.13f
            for ((tx, ty) in listOf(
                cx - bodyW / 2 to cy - bodyH * 0.26f,
                cx + bodyW / 2 to cy - bodyH * 0.26f,
                cx - bodyW / 2 to cy + bodyH * 0.26f,
                cx + bodyW / 2 to cy + bodyH * 0.26f,
            )) {
                canvas.drawRoundRect(
                    RectF(tx - tireW / 2, ty - tireH / 2, tx + tireW / 2, ty + tireH / 2),
                    8f, 8f, paint,
                )
            }
        }
    }
}
