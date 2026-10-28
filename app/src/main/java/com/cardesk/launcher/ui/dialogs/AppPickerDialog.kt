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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.OemDockPolicy
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback

/**
 * 应用选择网格。
 *
 * ## 两种用法
 *
 * 1. **「Dock / 状态栏生效应用」多选**
 *    - 标题随用途变化：「指定隐藏原车 Dock 的应用」/「指定隐藏状态栏的应用」；
 *    - 预勾选规则：**存储集合为空 → 默认全勾**；存储集合只含哨兵 `__dockbar_no_apps__`
 *      一个都不勾（哨兵会被剔除）；
 *    - 底部只有「全选 / 清空」按钮 + 「已选择 N/M」计数，**没有确认按钮**——
 *      每次勾选都立刻写回并生效
 *    - 一个都不勾时写回哨兵 `__dockbar_no_apps__`（= 一个都不隐藏）。
 * 2. **画中画应用单选**：点一下即切换，没有确认按钮。
 *
 * 第四十九轮按设计板 `s08-picker` 重绘：面板 1080×740 摆在 (420,130)，
 * 搜索框 (40,92) 1000×68（放大镜 24 + 提示 22px，间距 14），
 * 网格 **4 列**：单元格 left = 40 + c*220、top = 190 + r*190，图标 96×96 圆角 26，名称 18px。
 */
class AppPickerDialog(
    context: Context,
    pal: Palette,
    private val multiSelect: Boolean,
    preselected: Set<String> = emptySet(),
    panelWidth: Int = 1080,
    panelHeight: Int = 740,
    title: String? = null,
    /** 底部说明文案（默认按用途给；第五十四轮给「选择快捷应用」用）。 */
    hintText: String? = null,
) : DialogSheet(
    context,
    pal,
    panelWidth,
    panelHeight,
    title ?: if (multiSelect) "指定隐藏原车 Dock 的应用" else "选择画中画应用",
    420,
    130,
) {

    /** 多选模式：每次勾选立刻回调（集合为空表示「一个都不隐藏」，由调用方转成哨兵）。 */
    var onChanged: ((Set<String>) -> Unit)? = null

    /** 兼容旧调用：关闭时回调一次最终集合。 */
    var onPicked: ((Set<String>) -> Unit)? = null

    private val grid = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(CELL_PITCH_X * COLUMNS, FrameLayout.LayoutParams.WRAP_CONTENT)
    }
    private val allApps: List<Pair<String, String>> = loadApps()
    private val selected: MutableSet<String> = LinkedHashSet()

    /** 搜索关键词（设计板 s08 的搜索框）。 */
    private var query: String = ""

    private lateinit var toggleAll: TextView
    private lateinit var counter: TextView

    init {
        // 存储集合为空 → 默认全勾；哨兵 NO_APPS 本身不参与勾选
        val rawEmpty = preselected.isEmpty()
        preselected.forEach { if (it != OemDockPolicy.NO_APPS) selected.add(it) }
        if (rawEmpty && multiSelect) allApps.forEach { selected.add(it.first) }
        // 排障场景：打开选择器时预勾了哪些（默认全勾 / 哨兵一个都不勾）
        Logx.i(
            LogTags.SETTING,
            "应用选择器初始状态",
            "multiSelect" to multiSelect,
            "total" to allApps.size,
            "preselected" to selected.size,
            "preselectedEmpty" to rawEmpty,
            "noAppsSentinel" to (OemDockPolicy.NO_APPS in preselected),
        )

        // 标题（设计板 s08：(40,34) 38px bold）+ 右上角关闭
        addTitle()
        addClose()

        // 搜索框 (40,92) 1000×68 圆角 18：放大镜 24 + 提示「搜索应用」22px，间距 14
        val searchBox = FrameLayout(context).apply {
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)
        }
        val searchIcon = IconView(context, IconView.Glyph.SEARCH, 24f).apply { iconColor = pal.textFaint }
        searchBox.addView(searchIcon, FrameLayout.LayoutParams(24, 24, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = 20 })
        val search = EditText(context).apply {
            setHint("搜索应用")
            setHintTextColor(pal.textFaint)
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f)
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
        }
        searchBox.addView(search, FrameLayout.LayoutParams(-1, -1, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = 58 })
        place(searchBox, 40, 92, panelWidth - 80, 68)

        // 网格区 (40,190)：4 列，单元格 220 宽、190 一行
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(grid)
        }
        val footerTop = panelHeight - 104
        place(scroll, 40, 190, CELL_PITCH_X * COLUMNS, footerTop - 190 - 6)

        // 底部说明 + 「全选 / 清空」+ 计数 + 「取消」
        place(
            Ui.text(
                context,
                hintText ?: if (multiSelect) {
                    "勾选后立即生效：命中的应用在前台时隐藏原车 Dock / 状态栏"
                } else {
                    "点一下即切换画中画承载的应用"
                },
                19f,
                pal.textFaint,
            ),
            40,
            footerTop + 4,
        )
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleAll = chip(if (multiSelect) "全选" else "") {
            if (multiSelect) {
                val clearing = selected.size >= allApps.size
                if (clearing) selected.clear() else allApps.forEach { selected.add(it.first) }
                // 排障场景：「全选 / 清空」点了没反应 —— 记下点了哪个、勾了几个
                Logx.event(
                    LogTags.SETTING,
                    if (clearing) "应用选择器：清空" else "应用选择器：全选",
                    "total" to allApps.size,
                    "selected" to selected.size,
                )
                render()
                emit()
            }
        }
        footer.addView(toggleAll, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 16 })
        counter = Ui.text(context, "", 19f, pal.textFaint)
        footer.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        if (!multiSelect) {
            footer.addView(
                Ui.text(context, "取消", 20f, pal.textDim).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { onClose?.invoke() }
                },
                LinearLayout.LayoutParams(-2, -2),
            )
        }
        place(footer, 40, footerTop + 38, panelWidth - 80, 54)

        render()
    }

    /** 把当前选择回调出去（多选 = 即时生效；单选 = 点一下即切换）。 */
    private fun emit() {
        val snapshot = selected.toSet()
        // 排障场景：Dock / 状态栏「生效应用」名单没生效 —— 记下每次勾选后的完整名单
        if (multiSelect) {
            Logx.i(
                LogTags.SETTING,
                "应用选择器变更",
                "selectedCount" to snapshot.size,
                "total" to allApps.size,
                "apps" to snapshot.joinToString(","),
                "emptyMeansAll" to snapshot.isEmpty(),
            )
            onChanged?.invoke(snapshot)
        } else {
            Logx.i(
                LogTags.SETTING,
                "画中画应用变更",
                "pkg" to snapshot.joinToString(","),
            )
            onPicked?.invoke(snapshot)
        }
    }

    private fun render() {
        grid.removeAllViews()
        val visible = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true) }
        }
        visible.forEachIndexed { index, app ->
            val row = index / COLUMNS
            val col = index % COLUMNS
            // 设计板 s08：单元格 left = 40 + c*220、top = 190 + r*190
            grid.addView(
                cell(app),
                FrameLayout.LayoutParams(CELL_PITCH_X, CELL_PITCH_Y, Gravity.TOP or Gravity.START).apply {
                    leftMargin = col * CELL_PITCH_X
                    topMargin = row * CELL_PITCH_Y
                },
            )
        }
        if (multiSelect) {
            // lambda$openDesignDockAppPicker$25：全选时按钮写「清空」，否则写「全选」
            toggleAll.text = if (selected.size >= allApps.size) "清空" else "全选"
            counter.text = "已选择 ${selected.size}/${allApps.size}"
        } else {
            toggleAll.visibility = View.GONE
            counter.text = "已选择 ${selected.size}/${allApps.size}"
        }
    }

    private fun cell(app: Pair<String, String>): View {
        val (pkg, label) = app
        val active = pkg in selected
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            pressFeedback()
            setOnClickListener {
                if (multiSelect) {
                    if (active) selected.remove(pkg) else selected.add(pkg)
                    // 排障场景：用户勾了哪个 / 取消了哪个 —— 逐条留痕
                    Logx.event(
                        LogTags.SETTING,
                        if (active) "取消勾选应用" else "勾选应用",
                        "pkg" to pkg,
                        "label" to label,
                        "selectedCount" to selected.size,
                    )
                    render()
                    emit()
                } else {
                    selected.clear()
                    selected.add(pkg)
                    // 排障场景：画中画应用选成了谁
                    Logx.event(LogTags.SETTING, "选择画中画应用", "pkg" to pkg, "label" to label)
                    render()
                    emit()
                }
            }
        }

        // 图标 96×96 圆角 26（surfaceSoft），应用图标居中
        val iconBox = FrameLayout(context).apply {
            background = Ui.round(Skin.surfaceSoft, 26f, if (active) 2f else 0f, if (active) pal.accent else 0)
        }
        val loaded = runCatching {
            val pm = context.packageManager
            // 有些应用取不到图标（返回 null 而不是抛异常）——
            // 这时不能塞一个空 ImageView 进去，否则格子里是**空白方块**。
            val drawable = pm.getApplicationIcon(pkg) ?: return@runCatching null
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
            }
        }.getOrNull()
        if (loaded != null) {
            iconBox.addView(loaded, FrameLayout.LayoutParams(88, 88, Gravity.CENTER))
        } else {
            iconBox.addView(
                Ui.text(context, label.take(1), 30f, pal.textDim).apply {
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        }
        if (active) {
            iconBox.addView(
                Ui.text(context, "✓", 22f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Ui.round(pal.accent, Shape.PILL)
                },
                FrameLayout.LayoutParams(32, 32, Gravity.BOTTOM or Gravity.END),
            )
        }
        column.addView(iconBox, LinearLayout.LayoutParams(96, 96))

        // 名称 18px textDim，与图标间距 14
        column.addView(
            Ui.text(context, label, 18f, pal.textDim).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 },
        )
        return column
    }

    companion object {
        /** 网格列数（设计板 s08 是 4 列）。 */
        const val COLUMNS = 4

        /** 列间距 / 行间距（设计 px）：单元格 left = 40 + c*220、top = 190 + r*190。 */
        const val CELL_PITCH_X = 220
        const val CELL_PITCH_Y = 190

        /** 应用图标边长（设计 px）。 */
        const val ICON = 96
    }

    private fun loadApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }
}
