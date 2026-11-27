package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.ScheduledTask
import com.cardesk.launcher.core.ScheduledTasks
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.TaskScheduler
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.ui.pressFeedback

/**
 * 计划任务列表。
 *
 * 第四十九轮按设计板 `s06-tasks` 重绘：面板 1040×700 摆在 (440,150)，
 * 标题 38px bold (40,34)，「新建」是右上角 22px accent 纯文字（right=36 top=36），
 * 1px 分割线 (40,104) 宽 960，任务行 880×96 圆角 20（surfaceSoft + 1px 描边），
 * 行内 26px 粗体名称 + 19px 弱色条件 + 84×48 开关 + 26px chevron。
 */
class TaskListDialog(
    context: Context,
    pal: Palette,
    private val prefs: Prefs,
) : DialogSheet(context, pal, 1040, 700, "自动任务", 440, 150) {

    var onEdit: ((ScheduledTask?) -> Unit)? = null
    var onChange: (() -> Unit)? = null

    private val listBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(ROW_W, FrameLayout.LayoutParams.WRAP_CONTENT)
    }

    /**
     * 当前的任务行（重建时刷新）。
     *
     * 留着是为了弹窗打开时给它们**错峰入场**（见 [onAttachedToWindow]）。
     */
    private val rowViews = mutableListOf<View>()

    init {
        // 标题（设计板 s06：(40,34) 38px bold）
        addTitle("自动任务", 38f, 40, 34)
        // 「导入示例」+「新建」= 右上角操作
        val presets = Ui.text(context, "导入示例", 20f, pal.textDim, bold = false).apply {
            gravity = Gravity.CENTER
            pressFeedback()
            setOnClickListener {
                val tasks = ScheduledTasks.load(prefs)
                val existingIds = tasks.map { it.id }.toSet()
                val added = ScheduledTasks.examplePresets().filter { it.id !in existingIds }
                if (added.isNotEmpty()) {
                    tasks.addAll(added)
                    ScheduledTasks.save(prefs, tasks)
                    onChange?.invoke()
                    reload()
                }
            }
        }
        placeEnd(presets, 118, 37)
        val add = Ui.text(context, "新建", 22f, pal.accent, bold = true).apply {
            gravity = Gravity.CENTER
            pressFeedback()
            setOnClickListener {
                // 排障场景：点了「新建」没进编辑器 —— 记一下
                Logx.event(LogTags.TASK, "新建自动任务")
                onEdit?.invoke(null)
            }
        }
        placeEnd(add, 36, 36)
        // 1px 分割线（stroke 色，偏移 (40,104) 宽 960）
        addHairline(40, 104, 960)

        // 任务行列表：设计板把第一行放在 40（会盖住标题与分割线），
        // 这里下移到分割线下方 32px，行距仍是设计板的 112。
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox)
        }
        place(scroll, 40, 136, ROW_W, 460)

        // 底部说明 + 关闭键
        place(Ui.text(context, "满足传感器/状态条件或开机后自动执行对应动作", 19f, pal.textFaint), 40, 618)
        place(closePill(), panelWidth - 36 - 150, 602, 150, 66)
        reload()
    }

    fun reload() {
        val tasks = ScheduledTasks.load(prefs)
        listBox.removeAllViews()
        rowViews.clear()
        // 排障场景：任务列表为空 / 数量对不上 —— 记下实际加载了几条
        Logx.i(LogTags.TASK, "任务列表加载", "count" to tasks.size)
        if (tasks.isEmpty()) {
            // 空态文案
            listBox.addView(
                Ui.text(context, "暂无自动任务，可点击右上角「导入示例」或「新建」\n例如：温度 ≤ 10℃ 开主驾座椅加热，AQI ≥ 100 切内循环", 22f, pal.textDim),
                Ui.linear(-1, 80),
            )
            return
        }
        tasks.forEachIndexed { index, task ->
            val row = row(index, task)
            rowViews += row
            listBox.addView(row, Ui.linear(-1, ROW_H, 0f, 0, 16))
        }
    }

    /**
     * 弹窗挂到窗口上：任务行**错峰入场**。
     *
     * 列表是弹窗打开之后才看见的 —— 跟面板一起「啪」地出现太平，
     * 逐行淡入 + 微微上移更像「一列任务铺开」。
     *
     * ## 为什么必须放在 attach 之后
     *
     * 未挂到窗口上的视图上动画不会跑，
     * alpha 会停在 0 —— 整列任务就永远看不见了。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rowViews.forEachIndexed { index, view -> Motion.revealStagger(view, index) }
    }

    private fun row(index: Int, task: ScheduledTask): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 设计板 s06 任务行：surfaceSoft 底 + 1px 描边 + 圆角 20
            background = Ui.round(Skin.surfaceSoft, 20f, 1f, Skin.NAV_STROKE)
            setPadding(24, 0, 24, 0)
            // 整行可点（点行进编辑器）—— 之前整行点下去和点普通文字没区别，
            // 用户会以为没点到。行很大，用行专用的缩放比例：轻轻一沉就够。
            isClickable = true
            pressFeedback(MotionSpec.PRESS_SCALE_ROW)
        }
        // 名称 26px bold
        row.addView(
            Ui.text(context, actionLabel(task), 26f, pal.text, bold = true),
            LinearLayout.LayoutParams(-2, -2),
        )
        // 条件 19px textFaint，与名称间距 20
        row.addView(
            Ui.text(context, ScheduledTasks.conditionLabel(task), 19f, pal.textFaint),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 20 },
        )
        // 弹性空档：开关靠右
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        // 「删除」：设计板 s06 没画这一项，但删除功能必须保留 —— 用弱色文字放在开关左侧
        val remove = Ui.text(context, "删除", 19f, pal.textFaint).apply {
            gravity = Gravity.CENTER
            pressFeedback()
            setOnClickListener {
                val tasks = ScheduledTasks.load(prefs)
                tasks.removeAll { it.id == task.id }
                ScheduledTasks.save(prefs, tasks)
                TaskScheduler.cancel(context, task.id)
                // 排障场景：任务删了还在跑 —— 记下删除与取消调度
                Logx.event(
                    LogTags.TASK,
                    "删除计划任务",
                    "id" to task.id,
                    "condition" to task.condition,
                    "target" to task.target,
                    "remaining" to tasks.size,
                )
                onChange?.invoke()
                reload()
            }
        }
        row.addView(remove, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 24 })
        // 开关 84×48（设计板 `.switch`）
        val toggle = FlatSwitch(context).apply {
            accent = pal.accent
            on = task.enabled
            setOnClickListener {
                val from = task.enabled
                on = !on
                val tasks = ScheduledTasks.load(prefs)
                val i = tasks.indexOfFirst { it.id == task.id }
                if (i >= 0) {
                    tasks[i] = tasks[i].copy(enabled = on)
                    ScheduledTasks.save(prefs, tasks)
                    if (on) TaskScheduler.schedule(context, tasks[i]) else TaskScheduler.cancel(context, task.id)
                }
                // 排障场景：任务启用/停用没反应 —— 记下任务 id 与开关变化
                Logx.event(
                    LogTags.TASK,
                    if (on) "启用计划任务" else "停用计划任务",
                    "id" to task.id,
                    "condition" to task.condition,
                    "action" to task.action,
                    "target" to task.target,
                    "from" to from,
                    "to" to on,
                )
                onChange?.invoke()
                reload()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(FlatSwitch.SWITCH_W, FlatSwitch.SWITCH_H))
        // chevron 26px textFaint，与开关间距 24
        row.addView(
            IconView(context, IconView.Glyph.CHEVRON_RIGHT, 26f).apply { iconColor = pal.textFaint },
            LinearLayout.LayoutParams(26, 26).apply { leftMargin = 24 },
        )
        // 点整行进编辑器
        row.setOnClickListener {
            // 排障场景：点任务进编辑器 —— 记下要编辑的是哪条
            Logx.event(
                LogTags.TASK,
                "编辑计划任务",
                "id" to task.id,
                "condition" to task.condition,
                "action" to task.action,
                "target" to task.target,
                "delaySeconds" to task.delaySeconds,
            )
            onEdit?.invoke(task)
        }
        return row
    }

    private fun actionLabel(task: ScheduledTask): String = when (task.action) {
        ScheduledTasks.ACTION_APP -> "打开 ${appLabel(task.target)}"
        else -> ScheduledTasks.actionLabel(task)
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "（未选择应用）"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        /** 设计板 s06：任务行 880×96，行距 112。 */
        private const val ROW_W = 880
        private const val ROW_H = 96
    }
}

/**
 * 任务编辑。
 *
 * 第四十九轮按设计板 `s07-taskedit` 重绘：面板 1040×700 摆在 (440,150)，
 * 「任务名称」标签 (40,110) + 输入框 (40,142) 960×76，
 * 「触发条件」标签 (40,248) + 分段控件 (40,280)，
 * 「执行动作」标签 (40,386) + 动作选择行 (40,418) 960×76，
 * 右下角「取消 / 保存」胶囊组（right=36 bottom=32）。
 *
 * 现有的**延时秒数 / 应用列表 / 链接 / 热点**等额外功能全部保留，
 * 排在动作行之下（内容区可滚动）。
 */
class TaskEditorDialog(
    context: Context,
    pal: Palette,
    private val prefs: Prefs,
    private val existing: ScheduledTask?,
) : DialogSheet(context, pal, 1040, 700, if (existing == null) "新建任务" else "编辑任务", 440, 150) {

    var onSaved: (() -> Unit)? = null

    private var condition = existing?.condition ?: ScheduledTasks.COND_TEMP_LT
    private var threshold = existing?.threshold ?: ScheduledTasks.defaultThreshold(condition)
    private var delay = existing?.delaySeconds ?: ScheduledTasks.DELAY_DEFAULT
    private var target = existing?.target ?: if (existing == null) "2" else ""
    private var action = existing?.action ?: ScheduledTasks.ACTION_SEAT_HEAT_DRIVER

    /** 「执行动作」选择器是否展开（设计稿里动作行右端是 chevron，点开才出选项）。 */
    private var actionPickerOpen = false

    /** 输入框里的文字：APP 动作时是应用名过滤词，URL 动作时是链接本身。 */
    private var inputText: String = ""

    private var appBox: LinearLayout? = null

    private lateinit var delayLabel: TextView
    private lateinit var thresholdLabel: TextView

    init {
        render()
    }

    private fun render() {
        body.removeAllViews()
        addTitle()
        addClose()

        // 内容区：(40,110) 960×490 —— 设计稿字段按固定坐标排布，额外功能排在动作行下面。
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(960, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        place(scroll, 40, 110, 960, 490)

        // 「任务名称」标签 (40,110) 20px textFaint
        content.addView(Ui.text(context, inputLabel(), 20f, pal.textFaint), Ui.linear(960, 32))
        // 输入框 (40,142) 960×76 圆角 18，文字 26px，左内边距 22
        val input = EditText(context).apply {
            setText(inputText)
            setTextColor(pal.text)
            setHintTextColor(pal.textFaint)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f)
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)
            setPadding(22, 0, 22, 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString().orEmpty()
                    if (value == inputText) return
                    inputText = value
                    if (action == ScheduledTasks.ACTION_URL) {
                        // 链接动作：输入框就是链接本体
                        target = value.trim()
                    } else {
                        // 应用动作：输入框按名字过滤下面的应用列表
                        refreshApps()
                    }
                }
            })
        }
        content.addView(input, Ui.linear(960, 76))
        // 30px 空档 → 「触发条件」标签落在 (40,248)
        content.addView(View(context), Ui.linear(960, 30))
        content.addView(Ui.text(context, "触发条件", 20f, pal.textFaint), Ui.linear(960, 32))
        val conditions = ScheduledTasks.conditions()
        content.addView(
            chipGrid(conditions, condition, columns = 4) { nextCond ->
                val from = condition
                condition = nextCond
                if (ScheduledTasks.needsThreshold(nextCond)) {
                    threshold = ScheduledTasks.defaultThreshold(nextCond)
                }
                Logx.event(
                    LogTags.TASK,
                    "任务条件变更",
                    "id" to (existing?.id ?: "new"),
                    "from" to from,
                    "to" to condition,
                )
                render()
            },
            Ui.linear(960, -2),
        )
        if (ScheduledTasks.needsThreshold(condition)) {
            content.addView(View(context), Ui.linear(960, 12))
            val unit = ScheduledTasks.thresholdUnit(condition)
            val thRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            thresholdLabel = Ui.text(
                context,
                "触发阈值：${ScheduledTasks.formatThreshold(condition, threshold)}$unit",
                21f,
                pal.textDim,
            )
            thRow.addView(thresholdLabel, LinearLayout.LayoutParams(0, -2, 1f))
            val step = ScheduledTasks.thresholdStep(condition)
            val stepText = if (step % 1f == 0f) step.toInt().toString() else String.format(java.util.Locale.US, "%.1f", step)
            val thMinus = chip("−$stepText$unit", sizePx = 20f) {
                threshold -= step
                render()
                Motion.nudge(thresholdLabel)
            }
            val thPlus = chip("+$stepText$unit", sizePx = 20f) {
                threshold += step
                render()
                Motion.nudge(thresholdLabel)
            }
            thRow.addView(thMinus, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 10 })
            thRow.addView(thPlus, LinearLayout.LayoutParams(-2, -2))
            content.addView(thRow, Ui.linear(960, 56))
        }
        // 30px 空档 → 「执行动作」
        content.addView(View(context), Ui.linear(960, 30))
        content.addView(Ui.text(context, "执行动作", 20f, pal.textFaint), Ui.linear(960, 32))
        // 动作选择行 (40,418) 960×76 圆角 18，右端 chevron 26px textFaint
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)
            setPadding(22, 0, 22, 0)
            isClickable = true
            pressFeedback()
        }
        actionRow.addView(
            Ui.text(context, actionLabel(), 26f, pal.textDim),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        actionRow.addView(
            IconView(context, IconView.Glyph.CHEVRON_RIGHT, 26f).apply { iconColor = pal.textFaint },
            LinearLayout.LayoutParams(26, 26),
        )
        actionRow.setOnClickListener {
            actionPickerOpen = !actionPickerOpen
            render()
        }
        content.addView(actionRow, Ui.linear(960, 76))
        content.addView(View(context), Ui.linear(960, 12))

        // 动作选项：点开动作行才出现
        if (actionPickerOpen) {
            val actions = ScheduledTasks.actions()
            content.addView(
                chipGrid(actions, action, columns = 4) { nextAction ->
                    val from = action
                    action = nextAction
                    actionPickerOpen = false
                    if (ScheduledTasks.isSeatAction(action) && target.toIntOrNull() == null) {
                        target = "2"
                    }
                    Logx.event(
                        LogTags.TASK,
                        "任务动作变更",
                        "id" to (existing?.id ?: "new"),
                        "from" to from,
                        "to" to action,
                    )
                    render()
                },
                Ui.linear(960, -2),
            )
            content.addView(View(context), Ui.linear(960, 12))
        }

        // 延时秒数（仅开机/进桌面条件需要）
        if (ScheduledTasks.isStartupCondition(condition)) {
            val delayRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            delayLabel = Ui.text(context, "等 $delay 秒再执行（0=立即）", 21f, pal.textDim)
            delayRow.addView(delayLabel, LinearLayout.LayoutParams(0, -2, 1f))
            val minus = chip("−5s", sizePx = 20f) {
                val from = delay
                delay = (delay - 5).coerceAtLeast(0)
                Logx.d(LogTags.TASK, "任务延迟调整", "from" to from, "to" to delay)
                render()
                Motion.nudge(delayLabel)
            }
            val plus = chip("+5s", sizePx = 20f) {
                val from = delay
                delay = (delay + 5).coerceAtMost(ScheduledTasks.DELAY_MAX)
                Logx.d(LogTags.TASK, "任务延迟调整", "from" to from, "to" to delay)
                render()
                Motion.nudge(delayLabel)
            }
            delayRow.addView(minus, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 10 })
            delayRow.addView(plus, LinearLayout.LayoutParams(-2, -2))
            content.addView(delayRow, Ui.linear(960, 56))
        }

        // 座椅档位选择（座椅加热/通风动作）
        if (ScheduledTasks.isSeatAction(action)) {
            content.addView(Ui.text(context, "目标档位", 20f, pal.textFaint), Ui.linear(960, 32))
            val levels = listOf("1" to "1 档", "2" to "2 档", "3" to "3 档", "0" to "关闭")
            val activeLevel = target.toIntOrNull()?.coerceIn(0, 3)?.toString() ?: "2"
            content.addView(
                chipGrid(levels, activeLevel, columns = 4) { lvl ->
                    target = lvl
                    render()
                },
                Ui.linear(960, -2),
            )
            content.addView(View(context), Ui.linear(960, 12))
        }

        // 动作明细：应用列表 / 链接 / 热点说明
        when (action) {
            ScheduledTasks.ACTION_APP -> {
                // —— 分组标题是「打开哪个应用」
                content.addView(Ui.text(context, "打开哪个应用", 20f, pal.textFaint), Ui.linear(960, 32))
                val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                appBox = box
                content.addView(box, Ui.linear(960, -2))
                refreshApps()
            }

            ScheduledTasks.ACTION_URL -> {
                content.addView(Ui.text(context, "打开哪个链接", 20f, pal.textFaint), Ui.linear(960, 32))
                content.addView(
                    chip("填入示例地址", sizePx = 20f) {
                        target = "https://www.example.com"
                        inputText = target
                        render()
                    },
                    Ui.linear(-2, -2),
                )
            }

            else -> {
            }
        }

        // 左下角「删除」（仅编辑已有任务时显示）
        if (existing != null) {
            val delBtn = Ui.text(context, "删除", 24f, pal.danger, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
                setPadding(36, 14, 36, 14)
                pressFeedback()
                setOnClickListener { deleteExisting() }
            }
            place(delBtn, 36, panelHeight - 32 - 63, -2, 63)
        }

        // 右下角按钮组：「取消 / 保存」（设计板 s07：right=36 bottom=32）
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(
            chip("取消", sizePx = 24f, padH = 36, padV = 14) { onClose?.invoke() },
            LinearLayout.LayoutParams(-2, -2),
        )
        footer.addView(
            primaryButton("保存", 24f) { save() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 16 },
        )
        placeEnd(footer, 36, panelHeight - 32 - 63, -2, 63)
    }

    /** 应用列表（按输入框里的名字过滤）。 */
    private fun refreshApps() {
        val box = appBox ?: return
        box.removeAllViews()
        val keyword = inputText.trim()
        val apps = launchableApps()
        val visible = if (keyword.isBlank()) {
            apps
        } else {
            apps.filter { it.second.contains(keyword, ignoreCase = true) || it.first.contains(keyword, ignoreCase = true) }
        }
        if (visible.isEmpty()) {
            box.addView(Ui.text(context, "没有可启动的应用", 21f, pal.textDim), Ui.linear(-1, 60))
            return
        }
        visible.forEach { (pkg, label) ->
            val active = pkg == target
            val item = Ui.text(context, if (active) "● $label" else label, 24f, if (active) pal.accentInk else pal.textDim)
                .apply {
                    background = if (active) {
                        Ui.round(pal.accent, Shape.PILL)
                    } else {
                        Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
                    }
                    setPadding(22, 14, 22, 14)
                    pressFeedback()
                    setOnClickListener {
                        val from = target
                        target = pkg
                        // 排障场景：目标应用选错 —— 记下切换
                        Logx.event(
                            LogTags.TASK,
                            "任务目标应用变更",
                            "id" to (existing?.id ?: "new"),
                            "from" to from,
                            "to" to pkg,
                        )
                        refreshApps()
                    }
                }
            box.addView(item, Ui.linear(-1, 60, 0f, 0, 8))
        }
    }

    private fun inputLabel(): String =
        if (action == ScheduledTasks.ACTION_URL) "链接地址" else "任务名称"

    private fun chipGrid(
        items: List<Pair<String, String>>,
        selectedKey: String,
        columns: Int,
        onSelect: (String) -> Unit,
    ): LinearLayout {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            chunk.forEachIndexed { idx, (key, label) ->
                val active = key == selectedKey
                val btn = Ui.text(context, label, 20f, if (active) pal.accentInk else pal.textDim, bold = active).apply {
                    gravity = Gravity.CENTER
                    background = if (active) {
                        Ui.round(pal.accent, Shape.PILL)
                    } else {
                        Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
                    }
                    setPadding(16, 12, 16, 12)
                    pressFeedback()
                    setOnClickListener { onSelect(key) }
                }
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (idx < chunk.size - 1) rightMargin = 10
                    },
                )
            }
            for (pad in chunk.size until columns) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            container.addView(row, Ui.linear(-1, -2, 0f, 0, 10))
        }
        return container
    }

    private fun actionLabel(): String = ScheduledTasks.actionLabel(
        ScheduledTask(
            id = existing?.id ?: "",
            condition = condition,
            action = action,
            target = target,
        ),
    )

    private fun save() {
        val tasks = ScheduledTasks.load(prefs)
        val id = existing?.id ?: ScheduledTasks.newId()
        val index = tasks.indexOfFirst { it.id == id }
        val isNew = index < 0
        //
        // 只有**新建**才置 enabled = true；编辑时保留任务原来的开关状态，
        // 否则「编辑一个已停用的任务」会把它悄悄改回启用。
        val enabled = existing?.enabled ?: true
        val cooldown = existing?.takeIf { it.condition == condition }?.cooldownSeconds
            ?: ScheduledTasks.defaultCooldown(condition)
        val task = ScheduledTask(
            id = id,
            condition = condition,
            action = action,
            target = target,
            delaySeconds = delay,
            enabled = enabled,
            threshold = threshold,
            cooldownSeconds = cooldown,
        )
        if (index >= 0) tasks[index] = task else tasks.add(task)
        ScheduledTasks.save(prefs, tasks)
        TaskScheduler.schedule(context, task)
        // 排障场景：任务保存了没跑 —— 记下新建/修改、条件、动作、目标与延迟
        Logx.event(
            LogTags.TASK,
            if (isNew) "新建计划任务" else "修改计划任务",
            "id" to id,
            "condition" to condition,
            "action" to action,
            "target" to target,
            "delaySeconds" to delay,
            "enabled" to enabled,
            "total" to tasks.size,
        )
        onSaved?.invoke()
    }

    private fun deleteExisting() {
        val targetTask = existing ?: return
        val tasks = ScheduledTasks.load(prefs)
        tasks.removeAll { it.id == targetTask.id }
        ScheduledTasks.save(prefs, tasks)
        TaskScheduler.cancel(context, targetTask.id)
        Logx.event(
            LogTags.TASK,
            "删除自动任务",
            "id" to targetTask.id,
            "condition" to targetTask.condition,
            "action" to targetTask.action,
            "remaining" to tasks.size,
        )
        onSaved?.invoke()
    }

    private fun launchableApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return infos.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm).toString()
            pkg to label
        }.distinctBy { it.first }.sortedBy { it.second }
    }
}
