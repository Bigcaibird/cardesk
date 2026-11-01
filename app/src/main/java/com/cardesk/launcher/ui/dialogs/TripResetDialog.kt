package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.ui.Ui

/**
 * 「复位行程2」确认弹窗（第九十六轮）。
 *
 * 排障场景：点了行程2 卡上的「复位」但数据没归零 —— 这里把结果如实写出来：
 * 成功「已复位行程2」；失败「这台车机不支持桌面复位行程2（…）」。
 *
 * 复位入口在文档里只有常量、没有实现（`ITripData.TRIP_FUNC_RESET` 等），
 * 所以 [onReset] 交给 VehicleController 做多路尝试 + 回读验证。
 */
class TripResetDialog(context: Context, pal: Palette) :
    DialogSheet(context, pal, 760, 600, "复位行程2") {

    /** 用户点「确认复位」时回调；执行结果通过 [showResult] 回填。 */
    var onReset: (() -> Unit)? = null

    private lateinit var status: TextView
    private var busy = false

    init {
        val content = flowContent()

        content.addView(
            Ui.text(context, "行程2 从上次复位开始累计，确认现在清零重新开始吗？", 24f, pal.text),
            Ui.linear(-1, 76),
        )
        content.addView(
            note(
                "复位后里程 / 时长 / 平均油耗从 0 开始累计；行程1（总）不受影响。\n" +
                    "这台车机的复位入口文档里没有可直接调用的实现，桌面会依次尝试文档里出现的几种方式，并回读确认是否生效。",
            ),
            Ui.linear(-1, 170),
        )
        status = Ui.text(context, "", 20f, pal.textDim).also {
            content.addView(it, Ui.linear(-1, 44))
        }

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(footer, Ui.linear(-1, 66, 0f, 0, 16))
        footer.addView(chip("取消", sizePx = 24f) { onClose?.invoke() }, Ui.linear(-2, 62, 0f, 12))
        footer.addView(primaryButton("确认复位") { request() }, Ui.linear(-2, 62))
    }

    private fun request() {
        if (busy) return
        busy = true
        status.setTextColor(pal.textDim)
        status.text = "正在复位…"
        onReset?.invoke()
    }

    /** 复位结果：成功绿色、失败红色（与驾驶模式面板同一套状态色）。 */
    fun showResult(ok: Boolean, message: String) {
        busy = false
        if (!::status.isInitialized) return
        status.setTextColor(if (ok) pal.accent else DANGER_TEXT)
        status.text = message
    }

    private companion object {
        const val DANGER_TEXT = 0xFFE05B5B.toInt()
    }
}
