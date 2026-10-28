package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.NavSlot
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.dialogs.IconView
import com.cardesk.launcher.ui.shell.NavSlotBarView
import com.cardesk.launcher.ui.widget.OverlayBackdrop

/**
 * 导航栏槽位管理面板， +
 *
 *
 * 结构：全屏遮罩 + 面板；标题「导航栏槽位管理」+ 完成按钮 → 固定高度列表
 * 提示「长按条目拖动排序；有效槽位最多 24 个，空槽位不限」→ 底部三按钮
 * 「+ 系统动作 / + 应用 / + 空槽」。
 *
 * 拖拽排序不用长按 + 位移，改用「上移/下移」两枚小按钮，交互语义相同（改顺序）、
 * 实现更稳；配额、空槽、分隔线规则、JSON 持久化按既有约定实现。
 *
 * 应用槽额外支持「主画 / 副画 / 全屏」。
 *
 * 第四十九轮按设计板 `s12-slots` 重绘：面板 880×**760** 摆在 (520,150)，
 * 标题 36px bold (40,32)，右上角「添加」accent 纯文字（right=36 top=36），
 * 「完成」放在它左侧（right=110 top=30），1px 分割线 (40,96) 宽 800，
 * 槽位行 740×72 圆角 18（surfaceSoft + 1px 描边），
 * 行内 52×52 图标底板 + 24px 名称 + 22px 垃圾桶图标。
 */
class NavSlotDialog(
    context: Context,
    private val pal: Palette,
    initial: List<NavSlot>,
) : FrameLayout(context) {

    var onChange: ((List<NavSlot>) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val slots = initial.toMutableList()
    private lateinit var list: LinearLayout
    private lateinit var counter: TextView
    private var picking = false

    init {
        build()
    }

    private fun build() {
        // 排障场景：槽位面板打开时带了几个槽位
        Logx.event(LogTags.SETTING, "打开槽位管理面板", "count" to slots.size)
        val scrim = OverlayBackdrop(context).apply {
            // 第五十轮：背景板 = `.bgwash` + `.scrim`（不透明），与设计板 s12 一致。
            palette = this@NavSlotDialog.pal
            withScrim = true
            isClickable = true
            setOnClickListener { close() }
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 设计板 s12：880×760 摆在 (520,150)
        val sheet = FrameLayout(context).apply {
            // 第四十九轮「浮屿」：纯扁平面 + 1px 描边。
            // 第五十一轮修正：设计板 `.card` 用的是 `--surface`，之前误用 `--surfaceStrong`。
            background = Skin.flat(Shape.CARD)
        }
        addView(
            sheet,
            LayoutParams(PANEL_W, PANEL_H, Gravity.TOP or Gravity.START).apply {
                leftMargin = PANEL_LEFT
                topMargin = PANEL_TOP
            },
        )

        // 标题（设计板 s12：(40,32) 36px bold）
        sheet.addView(
            Ui.text(context, "导航栏槽位管理", 36f, pal.text, bold = true),
            LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = 40
                topMargin = 32
            },
        )
        // 「添加」= 右上角 accent 纯文字（right=36 top=36，22px）
        val add = Ui.text(context, "添加", 22f, pal.accent, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showCatalog() }
        }
        sheet.addView(
            add,
            LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = 36
                topMargin = 36
            },
        )
        // 「完成」= 放在「添加」左侧（right=110 top=30），不与设计元素重叠
        val done = Ui.text(context, "完成", 24f, pal.accent, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
            setOnClickListener { close() }
        }
        sheet.addView(
            done,
            LayoutParams(120, 60, Gravity.TOP or Gravity.END).apply {
                rightMargin = 110
                topMargin = 30
            },
        )
        // 1px 分割线 (40,96) 宽 800
        sheet.addView(
            View(context).apply { setBackgroundColor(pal.stroke) },
            LayoutParams(800, 1, Gravity.TOP or Gravity.START).apply {
                leftMargin = 40
                topMargin = 96
            },
        )

        counter = Ui.text(context, "", 19f, pal.textFaint)
        sheet.addView(
            counter,
            LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = 40
                topMargin = 648
            },
        )

        // 槽位列表：设计板把第一行放在 30（会盖住标题与分割线），这里下移到分割线下方
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, LayoutParams(-1, -2))
        sheet.addView(
            scroll,
            LayoutParams(ROW_W, LIST_H, Gravity.TOP or Gravity.START).apply {
                leftMargin = 40
                topMargin = 128
            },
        )

        val hint = Ui.text(
            context,
            "长按条目拖动排序；有效槽位最多 24 个，空槽位不限",
            15f,
            pal.textFaint,
        )
        sheet.addView(
            hint,
            LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = 40
                topMargin = 678
            },
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sheet.addView(
            actions,
            LayoutParams(PANEL_W - 80, 52, Gravity.TOP or Gravity.START).apply {
                leftMargin = 40
                topMargin = 704
            },
        )
        actions.addView(button("+ 系统动作") { showCatalog() }, LinearLayout.LayoutParams(0, 52, 1f).also { it.rightMargin = 6 })
        actions.addView(button("+ 应用") { showApps() }, LinearLayout.LayoutParams(0, 52, 1f).also { it.rightMargin = 6 })
        actions.addView(button("+ 空槽") { addSlot(NavSlot.createEmpty()) }, LinearLayout.LayoutParams(0, 52, 1f).also { it.rightMargin = 6 })
        actions.addView(button("恢复默认") { restoreDefaults() }, LinearLayout.LayoutParams(0, 52, 1f))

        refresh()
    }

    /**
     * 增强导航栏开启时，对话框要避开底部的 Dock / 槽位条
     *
     */
    private fun dockClearancePx(): Int {
        val enabled = runCatching { Prefs(context).enhancedNav }.getOrDefault(false)
        if (!enabled) return 0
        // 排障场景：对话框被底部导航栏盖住 —— 记下让位高度
        Logx.d(LogTags.SYS, "增强导航栏让位高度", "clearancePx" to NavSlotBarView.WIDE_HEIGHT_PX)
        return NavSlotBarView.WIDE_HEIGHT_PX
    }

    /**
     * 遮罩容器给**增强导航栏**让位
     *
     * 开启增强导航栏时对话框窗口高度减去导航栏高度，遮罩不覆盖导航栏；
     * 普通模式为 0，铺满整屏。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val clearance = dockClearancePx()
        if (clearance <= 0) return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        lp.bottomMargin = clearance
        layoutParams = lp
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        Ui.text(context, label, 18f, if (primary) pal.accent else pal.text, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
            setOnClickListener { onClick() }
        }

    private fun refresh() {
        list.removeAllViews()
        counter.text = "有效槽位 ${NavSlots.countedCount(slots)} / ${NavSlots.MAX_COUNTED}"
        for ((index, slot) in slots.withIndex()) {
            list.addView(slotRow(index, slot), LinearLayout.LayoutParams(-1, ROW_H).also { it.bottomMargin = 16 })
        }
        if (picking) list.addView(pickerView(), LinearLayout.LayoutParams(-1, -2))
    }

    private fun slotRow(index: Int, slot: NavSlot): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 设计板 s12：行是描边卡片（740×72 圆角 18）
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)
            setPadding(20, 0, 20, 0)
        }
        // 类型图标底板 52×52 圆角 16
        row.addView(typeIcon(slot), LinearLayout.LayoutParams(52, 52).also { it.rightMargin = 16 })
        // 名称 24px
        val name = slot.label.ifBlank { slot.actionId.ifBlank { slot.pkg } }
        row.addView(
            Ui.text(context, name, 24f, pal.text).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        // 应用槽的启动画布（主画 / 副画 / 全屏）
        if (slot.type == NavSlot.TYPE_APP) {
            row.addView(
                Ui.text(context, NavSlot.launchModeLabel(slot.launchMode), 15f, pal.accent, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Ui.round(pal.accentSoft, 10f)
                    setPadding(10, 0, 10, 0)
                    setOnClickListener { cycleLaunchMode(index) }
                },
                LinearLayout.LayoutParams(84, 44).also { it.rightMargin = 6 },
            )
        }
        row.addView(miniButton("↑") { move(index, -1) }, LinearLayout.LayoutParams(52, 44).also { it.rightMargin = 6 })
        row.addView(miniButton("↓") { move(index, 1) }, LinearLayout.LayoutParams(52, 44).also { it.rightMargin = 10 })
        // 垃圾桶图标 22px textFaint（设计板 s12 的行尾）
        val trash = IconView(context, IconView.Glyph.TRASH, 22f).apply {
            iconColor = pal.textFaint
            isClickable = true
            setOnClickListener {
                val removed = slots.getOrNull(index)
                slots.removeAt(index)
                // 排障场景：槽位「自己少了」—— 记下删掉的是哪一个
                Logx.event(
                    LogTags.SETTING,
                    "移除槽位",
                    "index" to index,
                    "type" to removed?.type,
                    "actionId" to removed?.actionId,
                    "pkg" to removed?.pkg,
                    "label" to removed?.label,
                    "count" to slots.size,
                )
                commit()
            }
        }
        row.addView(trash, LinearLayout.LayoutParams(22, 22).also { it.rightMargin = 6 })
        return row
    }

    /** 主画 → 副画 → 全屏 循环切换。 */
    private fun cycleLaunchMode(index: Int) {
        val slot = slots.getOrNull(index) ?: return
        val next = when (slot.launchMode) {
            NavSlot.LAUNCH_FULLSCREEN -> NavSlot.LAUNCH_PIP1
            NavSlot.LAUNCH_PIP1 -> NavSlot.LAUNCH_PIP2
            else -> NavSlot.LAUNCH_FULLSCREEN
        }
        slots[index] = slot.copy(launchMode = next)
        // 排障场景：应用槽启动到别的画布上去了 —— 记下切换前的画布
        Logx.event(
            LogTags.SETTING,
            "槽位启动画布变更",
            "index" to index,
            "pkg" to slot.pkg,
            "from" to slot.launchMode,
            "to" to next,
        )
        commit()
    }

    private fun typeIcon(slot: NavSlot): TextView {
        val glyph = when {
            slot.isEmpty -> "·"
            slot.type == NavSlot.TYPE_MEDIA -> "♪"
            slot.type == NavSlot.TYPE_LYRIC -> "词"
            slot.type == NavSlot.TYPE_DATETIME -> "时"
            NavSlots.isTempSlot(slot) -> "温"
            NavSlots.isTextToggle(slot) -> "AC"
            NavSlots.isHvacAction(slot.actionId) -> "空"
            slot.actionId == "camera_360" -> "360"
            slot.actionId == "back" -> "←"
            slot.actionId == "home" -> "⌂"
            slot.actionId == "tasks" -> "☰"
            slot.actionId == "split" -> "▥"
            slot.actionId == "settings" -> "⚙"
            else -> "图"
        }
        return Ui.text(context, glyph, 24f, pal.textDim, bold = true).apply {
            gravity = Gravity.CENTER
            // 设计板 `.tile`：surfaceSoft 底 + 1px 描边 + 圆角 16
            background = Ui.round(Skin.surfaceSoft, Shape.TILE, 1f, Skin.NAV_STROKE)
        }
    }

    private fun miniButton(label: String, onClick: () -> Unit): TextView =
        Ui.text(context, label, 16f, pal.textDim).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.surfaceSoft, 12f)
            setOnClickListener { onClick() }
        }

    private fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target < 0 || target >= slots.size) {
            // 排障场景：点了上移/下移但没动 —— 已经在边界上
            Logx.d(LogTags.SETTING, "槽位移动被忽略：已在边界", "index" to index, "delta" to delta)
            return
        }
        val item = slots.removeAt(index)
        slots.add(target, item)
        // 排障场景：槽位顺序与预期不符 —— 记下移动前后的位置
        Logx.event(
            LogTags.SETTING,
            "槽位顺序调整",
            "from" to index,
            "to" to target,
            "actionId" to item.actionId,
            "label" to item.label,
        )
        commit()
    }

    private fun addSlot(slot: NavSlot) {
        if (!slot.isEmpty && NavSlots.countedCount(slots) >= NavSlots.MAX_COUNTED) {
            // 排障场景：加槽位没加上 —— 有效槽位已到 24 个上限
            Logx.w(
                LogTags.SETTING,
                "添加槽位被拒绝：有效槽位已达上限",
                "counted" to NavSlots.countedCount(slots),
                "max" to NavSlots.MAX_COUNTED,
                "type" to slot.type,
                "actionId" to slot.actionId,
            )
            android.widget.Toast.makeText(context, "有效槽位已达 ${NavSlots.MAX_COUNTED} 个上限", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        slots.add(slot)
        picking = false
        // 排障场景：槽位加没加上 —— 记下类型、动作与包名
        Logx.event(
            LogTags.SETTING,
            "添加槽位",
            "type" to slot.type,
            "actionId" to slot.actionId,
            "pkg" to slot.pkg,
            "label" to slot.label,
            "launchMode" to slot.launchMode,
            "count" to slots.size,
        )
        commit()
    }

    private fun restoreDefaults() {
        slots.clear()
        slots.addAll(NavSlots.defaults())
        picking = false
        // 排障场景：槽位被一键重置 —— 记下重置后的数量
        Logx.event(LogTags.SETTING, "槽位恢复默认", "count" to slots.size)
        commit()
    }

    private fun commit() {
        refresh()
        // 排障场景：槽位保存前最后一次确认（真正落盘在 NavSlots.save 里）
        Logx.d(LogTags.SETTING, "槽位面板提交", "count" to slots.size, "counted" to NavSlots.countedCount(slots))
        onChange?.invoke(slots.toList())
    }

    // --------------------------------------------------------- 添加面板

    private fun showCatalog() {
        picking = true
        refresh()
    }

    private fun showApps() {
        picking = true
        refresh()
    }

    /** 添加时的选择面板：系统动作 22 项 + 已安装应用（最多 12 个）。 */
    private fun pickerView(): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 14, 0, 8)
        }
        panel.addView(
            Ui.text(context, "选择要添加的槽位", 16f, pal.accent, bold = true),
            LinearLayout.LayoutParams(-1, 36),
        )
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (chunk in NavSlots.CATALOG.chunked(3)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((id, label) in chunk) {
                row.addView(
                    Ui.text(context, label, 15f, pal.text).apply {
                        gravity = Gravity.CENTER
                        background = Ui.round(Skin.surfaceSoft, 12f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(8, 0, 8, 0)
                        setOnClickListener { addSlot(NavSlots.createSlotForAction(id, label)) }
                    },
                    LinearLayout.LayoutParams(0, 52, 1f).also { it.rightMargin = 8; it.topMargin = 6 },
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        // 日期/媒体/歌词类型槽
        val typeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val extra = listOf(
            "媒体" to NavSlot.createMedia(),
            "歌词" to NavSlot.createLyric(),
            "日期时间" to NavSlot.createDatetime(NavSlot.ACTION_DATETIME_HM_WEEK, NavSlot.datetimeLabel(NavSlot.ACTION_DATETIME_HM_WEEK)),
        )
        for ((label, slot) in extra) {
            typeRow.addView(
                Ui.text(context, label, 15f, pal.text).apply {
                    gravity = Gravity.CENTER
                    background = Ui.round(pal.accentSoft, 12f)
                    setOnClickListener { addSlot(slot) }
                },
                LinearLayout.LayoutParams(0, 52, 1f).also { it.rightMargin = 8; it.topMargin = 6 },
            )
        }
        grid.addView(typeRow, LinearLayout.LayoutParams(-1, -2))

        // 已安装应用
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(intent, 0) }.getOrElse { emptyList() }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .take(12)
        if (apps.isNotEmpty()) {
            grid.addView(
                Ui.text(context, "应用", 15f, pal.textFaint),
                LinearLayout.LayoutParams(-1, 34).also { it.topMargin = 8 },
            )
            for (chunk in apps.chunked(3)) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for ((pkg, label) in chunk) {
                    row.addView(
                        Ui.text(context, label, 15f, pal.textDim).apply {
                            gravity = Gravity.CENTER
                            background = Ui.round(Skin.surfaceSoft, 12f)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(8, 0, 8, 0)
                            setOnClickListener { addSlot(NavSlot.createApp(pkg, label)) }
                        },
                        LinearLayout.LayoutParams(0, 52, 1f).also { it.rightMargin = 8; it.topMargin = 6 },
                    )
                }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        } else {
            panel.addView(
                Ui.text(context, "未读到可添加的应用（需要 QUERY_ALL_PACKAGES 或已安装三方应用）", 14f, pal.textFaint),
                LinearLayout.LayoutParams(-1, 34),
            )
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    private fun close() {
        picking = false
        // 排障场景：面板关闭时还剩几个槽位（没点「完成」保存的话会丢弃）
        Logx.event(LogTags.SETTING, "关闭槽位管理面板", "count" to slots.size)
        onClose?.invoke()
    }

    companion object {
        /** 设计板 s12：面板 880×760 摆在 (520,150)。 */
        private const val PANEL_W = 880
        private const val PANEL_H = 760
        private const val PANEL_LEFT = 520
        private const val PANEL_TOP = 150

        /** 槽位行 740×72，行距 88。 */
        private const val ROW_W = 740
        private const val ROW_H = 72
        private const val LIST_H = 512
    }
}
