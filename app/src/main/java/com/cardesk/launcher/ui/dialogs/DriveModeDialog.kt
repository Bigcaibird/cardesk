package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.vehicle.DriveMode

/**
 * 驾驶模式快捷面板（第九十五轮补丁）。
 *
 * 状态栏「驾驶模式 · xx」胶囊点开它就弹这个：
 * - 顶部当前模式；
 * - **本车实际支持的 3 个模式**（经济 / 舒适 / 运动，`DriveMode.UI_MODE_LABELS`），
 *   当前模式高亮，点一下就切；
 * - 切完回读确认，失败给红色提示（部分车型只允许在 P 挡切换）；
 * - 底部保留「打开原厂驾驶模式页」兜底。
 *
 * 真正的写车在 [com.cardesk.launcher.vehicle.VehicleController.setDriveMode]，
 * 面板只负责交互：点击 → `onSwitch(value)`，结果由 `showResult()` 回填。
 */
class DriveModeDialog(
    context: Context,
    pal: Palette,
    currentRaw: Int?,
    private val onSwitch: (Int) -> Unit,
    private val onMoreSettings: () -> Unit,
) : DialogSheet(context, pal, 960, 420, "驾驶模式", 480, 330) {

    private var currentValue: Int = currentRaw ?: DriveMode.SELECTION_NONE
    private var pendingValue: Int? = null
    private var busy = false

    private val status: TextView = Ui.text(context, "", 22f, pal.textDim)
    private val chips = ArrayList<Pair<TextView, Int>>()

    init {
        val column = flowContent()
        column.addView(status, Ui.linear(-1, 40))
        status.gravity = Gravity.CENTER_VERTICAL

        var row: LinearLayout? = null
        for (index in DriveMode.UI_MODE_VALUES.indices) {
            val value = DriveMode.UI_MODE_VALUES[index]
            val label = DriveMode.UI_MODE_LABELS[index]
            if (index % 4 == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                column.addView(row, Ui.linear(-1, 70))
            }
            val chip = chip(label, active = isCurrent(value), sizePx = 22f, padH = 10, padV = 14) {
                request(value, label)
            }
            chips += chip to value
            row?.addView(chip, LinearLayout.LayoutParams(0, 62, 1f).apply { marginEnd = 12 })
        }

        column.addView(
            note("部分车型只允许在 P 挡 / 静止时切换；切换后没生效会提示失败。"),
            Ui.linear(-1, 40),
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(actions, Ui.linear(-1, 72))
        actions.addView(
            chip("打开原厂驾驶模式页", sizePx = 22f, padH = 22, padV = 14) { onMoreSettings() },
            LinearLayout.LayoutParams(0, 62, 1f),
        )
        actions.addView(
            primaryButton("关闭", 22f) { onClose?.invoke() }.apply {
                background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
                setTextColor(pal.textDim)
            },
            LinearLayout.LayoutParams(0, 62, 1f).apply { marginStart = 12 },
        )

        refreshStatus()
    }

    private fun isCurrent(value: Int): Boolean =
        DriveMode.normalizeModeId(currentValue) == DriveMode.normalizeModeId(value)

    private fun request(value: Int, label: String) {
        if (busy) return
        if (isCurrent(value)) {
            status.text = "当前已经是$label"
            return
        }
        busy = true
        pendingValue = value
        status.text = "正在切换到「$label」…"
        status.setTextColor(pal.textDim)
        chips.forEach { (chip, _) -> chip.isEnabled = false }
        onSwitch(value)
    }

    /** 写车结果（主线程回调）。 */
    fun showResult(ok: Boolean, message: String) {
        busy = false
        chips.forEach { (chip, _) -> chip.isEnabled = true }
        pendingValue?.let { if (ok) currentValue = it }
        pendingValue = null
        status.text = message
        status.setTextColor(if (ok) pal.accent else SKIN_DANGER_TEXT)
        chips.forEach { (chip, value) ->
            val active = isCurrent(value)
            chip.background = if (active) {
                Ui.round(pal.accent, Shape.PILL)
            } else {
                Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
            }
            chip.setTextColor(if (active) pal.accentInk else pal.textDim)
        }
    }

    private fun refreshStatus() {
        status.text = if (DriveMode.normalizeModeId(currentValue) == DriveMode.SELECTION_NONE) {
            "当前模式：读不到"
        } else {
            "当前模式：${DriveMode.fullLabel(currentValue)}"
        }
        status.setTextColor(pal.textDim)
    }

    private companion object {
        /** 危险色：读失败/切换失败的提示色。 */
        val SKIN_DANGER_TEXT: Int = 0xFFE05B5B.toInt()
    }
}
