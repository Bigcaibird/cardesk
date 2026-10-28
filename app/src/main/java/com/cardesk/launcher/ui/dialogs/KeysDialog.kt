package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui

/**
 * 按键增强设置， +
 *
 *
 * 结构一致：
 *
 * - 顶部「启用按键增强」开关；
 * - 分组「语音助手 / * 键」→ 单击 / 双击 / 长按；
 * - 分组「媒体按键（长按）」→ 上一曲 / 下一曲；
 * - 每个键位先选动作，动作是「切换驾驶模式」时再多一级「　目标驾驶模式」。
 *
 * 动作清单与编码
 * `无=3` / `切换驾驶模式=0` / `360环影(开关)=1` / `唤起语音助手=2`；
 * 支持导航投屏时多一项 `切换导航投屏=4`。
 *
 * 持久化键名一致：
 * `voice_click_action` / `voice_click_mode` / `voice_double_action` / `voice_double_mode` /
 * `voice_long_action` / `voice_long_mode` / `media_prev_long_action` / `media_prev_long_mode` /
 * `media_next_long_action` / `media_next_long_mode`。
 *
 * 第四十九轮按设计板 `s10-keys` 重绘：面板 1000×640 摆在 (460,180)，
 * 标题 38px bold (40,34)，行 900 宽圆角 20（surfaceSoft + 1px 描边），
 * 行内 26px 粗体名称 + 19px 弱色描述 + 84×48 开关。
 */
class KeysDialog(
    context: Context,
    pal: Palette,
    private val prefs: Prefs,
) : DialogSheet(context, pal, 1000, 640, "按键增强", 460, 180) {

    var onChanged: (() -> Unit)? = null

    /** 导航投屏动作是否可用。 */
    var dimNaviCastSupported: Boolean = false

    /** 动作清单。 */
    private val actions = listOf(
        ACTION_NONE to "无",
        Prefs.ACTION_DRIVE_MODE to "切换驾驶模式",
        Prefs.ACTION_360_TOGGLE to "360环影(开关)",
        Prefs.ACTION_VOICE_ASSIST to "唤起语音助手",
    ) + if (dimNaviCastSupported) listOf(Prefs.ACTION_DIM_NAVI_CAST_TOGGLE to "切换导航投屏") else emptyList()

    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        // 「让位」由 DialogSheet 统一处理在**遮罩容器**上
        //
        // 遮罩不覆盖增强导航栏，而不是把面板往上顶。

        // 标题（设计板 s10：(40,34) 38px bold）+ 右上角关闭
        addTitle("按键增强", 38f, 40, 34)
        addClose()

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(rows)
        }
        place(scroll, 40, 110, ROW_W, 430)

        // 右下角按钮组：「恢复默认 / 确认」
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(
            chip("恢复默认", sizePx = 20f, padH = 18, padV = 14) {
                resetDefaults()
                // 排障场景：恢复默认后按键行为还是老的 —— 记下重置动作
                Logx.event(LogTags.KEYS, "按键增强恢复默认")
                onChanged?.invoke()
                render()
            },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 },
        )
        footer.addView(
            primaryButton("确认", 20f) {
                Logx.event(LogTags.KEYS, "按键增强面板确认")
                onChanged?.invoke()
                onClose?.invoke()
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        placeEnd(footer, 36, panelHeight - 32 - 52)
        render()
    }

    /** 「启用按键增强」开关。 */
    private fun enableRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 设计板 s10 的行：surfaceSoft 底 + 1px 描边 + 圆角 20
            background = Ui.round(Skin.surfaceSoft, 20f, 1f, Skin.NAV_STROKE)
            setPadding(24, 0, 24, 0)
        }
        row.addView(
            Ui.text(context, "启用按键增强", 26f, pal.text, bold = true),
            LinearLayout.LayoutParams(-2, -2),
        )
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        // 开关 84×48（设计板 `.switch`）
        val toggle = FlatSwitch(context).apply {
            accent = pal.accent
            on = prefs.keysEnhanced
            setOnClickListener {
                val from = prefs.keysEnhanced
                on = !on
                prefs.keysEnhanced = on
                // 排障场景：按键增强开了但按键没反应 —— 先确认总开关写进去了
                Logx.event(
                    LogTags.KEYS,
                    "按键增强总开关变更",
                    "key" to "custom_keys_enabled",
                    "from" to from,
                    "to" to on,
                )
                onChanged?.invoke()
                render()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(FlatSwitch.SWITCH_W, FlatSwitch.SWITCH_H))
        return row
    }

    /** 一个键位：动作选择 + （动作是「切换驾驶模式」时的）二级目标驾驶模式。 */
    private fun keySlot(
        label: String,
        action: Int,
        onAction: (Int) -> Unit,
        mode: Int,
        onMode: (Int) -> Unit,
    ) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Skin.surfaceSoft, 20f, 1f, Skin.NAV_STROKE)
            setPadding(24, 14, 24, 14)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.text(context, label, 26f, pal.text, bold = true), LinearLayout.LayoutParams(-2, -2))
        head.addView(
            Ui.text(context, actionName(action), 19f, pal.textFaint),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 20 },
        )
        card.addView(head, Ui.linear(-1, -2))
        card.addView(
            actionChips(action) { value ->
                // 排障场景：按键改绑后方向盘按键没反应 —— 记下键名与改绑前后的动作
                Logx.event(
                    LogTags.KEYS,
                    "键位改绑",
                    "key" to label,
                    "from" to actionName(action),
                    "to" to actionName(value),
                    "fromValue" to action,
                    "toValue" to value,
                )
                onAction(value)
            },
            Ui.linear(-1, 48).apply { topMargin = 10 },
        )
        if (action == Prefs.ACTION_DRIVE_MODE) {
            card.addView(
                Ui.text(context, "　目标驾驶模式", 19f, pal.textFaint),
                Ui.linear(-1, -2).apply { topMargin = 8 },
            )
            card.addView(
                modeChips(mode) { value ->
                    // 排障场景：切换驾驶模式没切过去 —— 记下目标驾驶模式的值
                    Logx.event(
                        LogTags.KEYS,
                        "目标驾驶模式设置",
                        "key" to label,
                        "from" to mode,
                        "to" to value,
                        "toName" to modeName(value),
                    )
                    onMode(value)
                },
                Ui.linear(-1, 48).apply { topMargin = 6 },
            )
        }
        rows.addView(card, Ui.linear(-1, -2, 0f, 0, 16))
    }

    /** 动作值 → 中文名（日志用，认不出来就回显原值）。 */
    private fun actionName(value: Int): String =
        actions.firstOrNull { it.first == value }?.second ?: value.toString()

    /** 驾驶模式值 → 中文名（日志用）。 */
    private fun modeName(value: Int): String {
        val index = DRIVE_MODE_VALUES.indexOf(value)
        return if (index >= 0) DRIVE_MODE_LABELS[index] else value.toString()
    }

    private fun actionChips(current: Int, onPick: (Int) -> Unit): View {
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((value, label) in actions) {
            line.addView(
                chip(label, value == current, sizePx = 20f, padH = 18, padV = 10) {
                    onPick(value)
                    onChanged?.invoke()
                    render()
                },
                Ui.linear(-2, 48, 0f, 8),
            )
        }
        scroll.addView(line)
        return scroll
    }

    private fun modeChips(current: Int, onPick: (Int) -> Unit): View {
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val selected = driveModeIndex(current)
        for (i in DRIVE_MODE_LABELS.indices) {
            line.addView(
                chip(DRIVE_MODE_LABELS[i], i == selected, sizePx = 20f, padH = 18, padV = 10) {
                    onPick(DRIVE_MODE_VALUES[i])
                    onChanged?.invoke()
                    render()
                },
                Ui.linear(-2, 48, 0f, 8),
            )
        }
        scroll.addView(line)
        return scroll
    }

    private fun resetDefaults() {
        // 排障场景：恢复默认后想知道到底把哪些键位清空了 —— 先留旧值
        val before = listOf(
            "voice_click_action" to prefs.voiceClickAction,
            "voice_double_action" to prefs.voiceDoubleAction,
            "voice_long_action" to prefs.voiceLongAction,
            "media_prev_long_action" to prefs.mediaPrevLongAction,
            "media_next_long_action" to prefs.mediaNextLongAction,
        )
        prefs.voiceClickAction = Prefs.ACTION_NONE
        prefs.voiceDoubleAction = Prefs.ACTION_NONE
        prefs.voiceLongAction = Prefs.ACTION_NONE
        prefs.mediaPrevLongAction = Prefs.ACTION_NONE
        prefs.mediaNextLongAction = Prefs.ACTION_NONE
        prefs.voiceClickMode = 0
        prefs.voiceDoubleMode = 0
        prefs.voiceLongMode = 0
        prefs.mediaPrevLongMode = 0
        prefs.mediaNextLongMode = 0
        // 排障场景：恢复默认明细
        Logx.i(
            LogTags.KEYS,
            "按键增强恢复默认明细",
            "before" to before.joinToString(",") { "${it.first}=${it.second}" },
            "after" to "全部动作=无(ACTION_NONE)、驾驶模式=0",
        )
    }

    private fun render() {
        rows.removeAllViews()
        rows.addView(enableRow(), Ui.linear(-1, ROW_H, 0f, 0, 16))
        if (!prefs.keysEnhanced) {
            rows.addView(
                Ui.text(context, "开启后可为语音键与媒体长按绑定动作。", 20f, pal.textFaint),
                Ui.linear(-1, 48),
            )
            return
        }
        rows.addView(
            Ui.text(context, "语音助手 / * 键", 20f, pal.textFaint),
            Ui.linear(-1, 32, 0f, 0, 4),
        )
        keySlot("单击", prefs.voiceClickAction, { prefs.voiceClickAction = it }, prefs.voiceClickMode) {
            prefs.voiceClickMode = it
        }
        keySlot("双击", prefs.voiceDoubleAction, { prefs.voiceDoubleAction = it }, prefs.voiceDoubleMode) {
            prefs.voiceDoubleMode = it
        }
        keySlot("长按", prefs.voiceLongAction, { prefs.voiceLongAction = it }, prefs.voiceLongMode) {
            prefs.voiceLongMode = it
        }
        rows.addView(
            Ui.text(context, "媒体按键（长按）", 20f, pal.textFaint),
            Ui.linear(-1, 32, 0f, 0, 4),
        )
        keySlot("上一曲", prefs.mediaPrevLongAction, { prefs.mediaPrevLongAction = it }, prefs.mediaPrevLongMode) {
            prefs.mediaPrevLongMode = it
        }
        keySlot("下一曲", prefs.mediaNextLongAction, { prefs.mediaNextLongAction = it }, prefs.mediaNextLongMode) {
            prefs.mediaNextLongMode = it
        }
    }

    companion object {
        /** `Prefs.ACTION_NONE`（= 3）。 */
        const val ACTION_NONE = Prefs.ACTION_NONE

        /**
         * 驾驶模式清单
         * 本应用实现见 `com.cardesk.launcher.vehicle.DriveMode.UI_MODE_LABELS`。
         */
        val DRIVE_MODE_LABELS: List<String> = com.cardesk.launcher.vehicle.DriveMode.UI_MODE_LABELS.toList()

        /**
         * 驾驶模式取值。
         */
        val DRIVE_MODE_VALUES: List<Int> = com.cardesk.launcher.vehicle.DriveMode.UI_MODE_VALUES.toList()

        const val SELECTION_ECO = com.cardesk.launcher.vehicle.DriveMode.SELECTION_ECO

        /**
         * 取值 → 清单下标
         * 认不出来的值一律落到「经济模式」。
         */
        fun driveModeIndex(value: Int): Int {
            val index = DRIVE_MODE_VALUES.indexOf(value)
            return if (index >= 0) index else 0
        }

        /** 设计板 s10：行宽 900 / 行高 88。 */
        private const val ROW_W = 900
        private const val ROW_H = 88
    }
}
