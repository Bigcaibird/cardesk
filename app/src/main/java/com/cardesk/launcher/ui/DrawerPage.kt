package com.cardesk.launcher.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.widget.OverlayBackdrop

/**
 * 应用抽屉。
 *
 * 第四十九轮按设计板 `s03-apps` 重排为**页面级卡片**：
 * - 面板 **1876×867**，位于 `left 44 / top 73`（与首页、导航页同一版面网格）；
 * - 标题「全部应用」**40px 粗体** (40, 34)；
 * - 右侧**分段开关**（全屏打开 / 小窗打开）`right 40 / top 40`：软底 + 1px 描边 + 胶囊圆角，
 *   内边距 5、间距 6；选项 20px / 内边距 9×22；**选中** = 强调色实底 + 强调色上的字 + 加粗；
 * - 标题下一条 1px 分隔线 (40, 100) 宽 1796；
 * - 6 列应用网格：单元格 `left 40 + c×300`、`top 150 + r×150`、宽 300；
 *   图标 **76×76 圆角 20** 软底居中，名称 18px 次级文字、距图标 12；
 * - 遮罩改用 [Skin.SCRIM]，状态栏和 Dock 仍在抽屉之上可见（抽屉插在状态栏之前）。
 */
class DrawerPage(context: Context, private val pal: Palette) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null
    /** 打开应用：mini=true 表示小窗打开（浮窗承载），false 表示全屏。 */
    var onOpenApp: ((pkg: String, name: String, mini: Boolean) -> Unit)? = null

    private var miniMode = false

    private lateinit var grid: GridLayout
    private lateinit var hint: TextView

    /** 当前显示顺序（包名），拖动排序后写回 [prefs.appOrder]。 */
    private var currentPackages: List<String> = emptyList()
    private val prefs = com.cardesk.launcher.core.Prefs(context.applicationContext)

    /** 面板（动画用）。 */
    private var panelView: View? = null

    /** 遮罩（动画用）。 */
    private var scrimView: View? = null

    /** 打开动画：面板上滑 + 遮罩淡入（一族）。 */
    fun playOpen() {
        OverlayAnim.open(panelView, scrimView)
    }

    /** 关闭动画：面板下滑 + 淡出（一族）。 */
    fun playClose(onEnd: () -> Unit) {
        OverlayAnim.close(panelView, scrimView, onEnd)
    }

    init {
        val scrim = OverlayBackdrop(context).apply {
            // 第五十轮：设计板 `s03-apps` 是**整页**（`.bgwash` + 卡片 + Dock），
            // **没有 scrim**，所以背景板只要 `.bgwash`（不透明，桌面不再透出）。
            palette = this@DrawerPage.pal
            withScrim = false
            isClickable = true
            setOnClickListener {
                // 排障场景：「点遮罩抽屉没关掉」——关闭动作落盘。
                Logx.event(LogTags.DRAWER, "抽屉关闭（点击遮罩）")
                onClose?.invoke()
            }
        }
        scrimView = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // —— 面板：扁平面 + 1px 描边（第四十九轮「浮屿」）——
        val panel = FrameLayout(context).apply {
            background = Skin.flat(Shape.CARD)
            isClickable = true
            setOnClickListener { }
        }
        panelView = panel
        addView(
            panel,
            LayoutParams(PANEL_W, PANEL_H, Gravity.TOP or Gravity.START).apply {
                leftMargin = PANEL_LEFT
                topMargin = PANEL_TOP
            },
        )

        // —— 标题 ——
        val title = Ui.text(context, "全部应用", 40f, Skin.TEXT, bold = true)
        panel.addView(title, abs(PANEL_PAD, TITLE_TOP, -1, 52))

        // —— 右侧分段开关（全屏打开 / 小窗打开）——
        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(Skin.CHIP_FILL, Shape.PILL, 1f, Skin.CHIP_STROKE)
            setPadding(5, 5, 5, 5)
        }
        val full = segment("全屏打开", true)
        val mini = segment("小窗打开", false)
        fun applyMode(miniOn: Boolean) {
            miniMode = miniOn
            paintSegment(full, !miniOn)
            paintSegment(mini, miniOn)
            // 排障场景：「小窗/全屏切换没生效」——启动方式切换落盘。
            Logx.event(
                LogTags.DRAWER,
                "抽屉启动方式切换",
                "mode" to if (miniOn) "mini" else "fullscreen",
            )
        }
        full.setOnClickListener { applyMode(false) }
        mini.setOnClickListener { applyMode(true) }
        seg.addView(full, LinearLayout.LayoutParams(-2, 44).apply { rightMargin = 6 })
        seg.addView(mini, LinearLayout.LayoutParams(-2, 44))
        panel.addView(
            seg,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = SEG_TOP
                rightMargin = PANEL_PAD
            },
        )

        // —— 标题下的分隔线 ——
        panel.addView(
            View(context).apply { setBackgroundColor(Skin.NAV_STROKE) },
            abs(PANEL_PAD, DIVIDER_TOP, PANEL_W - PANEL_PAD * 2, 1),
        )

        hint = Ui.text(context, "没有读取到可启动的应用", 18f, Skin.TEXT_DIM).apply {
            gravity = Gravity.CENTER
        }
        panel.addView(hint, abs(PANEL_PAD, GRID_TOP, PANEL_W - PANEL_PAD * 2, 120))

        // —— 应用网格 ——
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        grid = GridLayout(context).apply { columnCount = 6 }
        grid.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType("text/plain") == true
                DragEvent.ACTION_DROP -> {
                    val pkg = event.clipData?.getItemAt(0)?.text?.toString()
                    pkg != null && handleDrop(event.x, event.y, pkg)
                }
                else -> true
            }
        }
        scroll.addView(grid, FrameLayout.LayoutParams(GRID_W, LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, abs(PANEL_PAD, GRID_TOP, GRID_W, GRID_H))

        // —— 底部提示（长按拖动排序是 App 侧能力，也有）——
        val foot = Ui.text(context, "点按图标启动，长按拖动排序；格子右上角 ⋮ 可重命名 / 应用信息 / 禁用·启用 / 卸载", 16f, Skin.TEXT_DIM).apply {
            gravity = Gravity.CENTER
        }
        panel.addView(foot, abs(PANEL_PAD, FOOT_TOP, PANEL_W - PANEL_PAD * 2, 26))

        reload()
        // 排障场景：「抽屉打不开 / 抽屉里没应用」——打开落盘（含已加载数量）。
        Logx.event(LogTags.DRAWER, "抽屉打开", "apps" to currentPackages.size)
    }

    private fun segment(text: String, active: Boolean): TextView =
        Ui.text(context, text, 20f, if (active) Skin.ACCENT_INK else Skin.TEXT_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(22, 9, 22, 9)
            paintSegment(this, active)
        }

    /**
     * 分段项两态（设计板 `s03-apps` 的 `.seg span`）：
     * - 选中：**强调色实底 + 强调色上的字 + 加粗**；
     * - 未选中：透明底 + 次级文字（`--textDim`）。
     */
    private fun paintSegment(seg: TextView, active: Boolean) {
        seg.setTextColor(if (active) Skin.ACCENT_INK else Skin.TEXT_SOFT)
        seg.typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
        seg.background = if (active) Skin.solid(Skin.ACCENT, Shape.PILL) else null
    }

    /** FrameLayout 绝对定位（相对面板左上角）。 */
    private fun abs(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    /** 读取全部可启动应用（不再截断），并按用户排序持久化（`launcher_app_order`）。 */
    fun reload() {
        grid.removeAllViews()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 排障场景：「抽屉里一个应用都没有」——加载耗时与数量要能查。
        val startMs = System.currentTimeMillis()
        // 第九十四轮：已禁用的应用也要留在抽屉里（置灰显示），否则禁用后就"消失"了。
        val apps = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString() }
        } catch (e: Exception) {
            Logx.w(LogTags.DRAWER, "抽屉应用列表查询失败", e, "reason" to e.message)
            emptyList()
        }
        // 用户排序优先：排过序的按保存顺序在前，其余按名称排在后面
        val order = prefs.appOrder
        val rank = order.withIndex().associate { (i, pkg) -> pkg to i }
        val sorted = apps.sortedWith(
            compareBy(
                { rank[it.activityInfo.packageName] ?: Int.MAX_VALUE },
                { displayLabel(it, pm) },
            )
        )
        currentPackages = sorted.map { it.activityInfo.packageName }
        hint.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        // 排障场景：「抽屉打开很慢 / 应用数量不对」——数量 + 耗时落盘。
        Logx.i(
            LogTags.DRAWER,
            "抽屉应用列表加载",
            "count" to sorted.size,
            "costMs" to (System.currentTimeMillis() - startMs),
            "ordered" to prefs.appOrder.size,
        )
        for (info in sorted) {
            val pkg = info.activityInfo.packageName
            val display = displayLabel(info, pm)
            val enabled = isEnabled(info)
            // 第九十五轮 C5：格子 = 图标列（点按启动 / 长按拖动排序）+ 右上角「⋮」菜单。
            val cell = FrameLayout(context)
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 第五十一轮：设计板 s03 的格子是 的**定宽块**，
                // 图标（`.appicon` 76×96）用 **贴格子左沿**，
                // 名称在 76 宽的框里居中。之前这里把图标在 300 宽的格子里居中，
                // 整排图标右移了 112px，跟设计板对不上。
                gravity = Gravity.TOP or Gravity.START
                isClickable = true
                setOnClickListener {
                    if (enabled) {
                        openApp(info, display)
                    } else {
                        showItemMenu(info, display)
                        toast("应用已禁用，可在菜单里启用")
                    }
                }
                // 长按 = 拖动排序（Drawer 的 startDragAndDrop +）
                setOnLongClickListener {
                    val clip = ClipData.newPlainText("package", pkg)
                    val shadow = View.DragShadowBuilder(this).apply {
                        // 拖起时放大 1.03 并抬升 translationZ 8dp
                        scaleX = 1.03f
                        scaleY = 1.03f
                        elevation = 8f * resources.displayMetrics.density
                    }
                    // 排障场景：「抽屉里长按拖不动」——拖拽开始落盘。
                    Logx.event(LogTags.DRAWER, "抽屉应用长按开始拖拽", "pkg" to pkg)
                    startDragAndDrop(clip, shadow, pkg, 0)
                    true
                }
            }
            val iv = ImageView(context)
            val icon: Drawable? = try {
                info.loadIcon(pm)
            } catch (e: Exception) {
                null
            }
            iv.setImageDrawable(icon)
            // 已禁用：图标压暗，和可用应用区分开。
            if (!enabled) iv.alpha = 0.35f
            iv.background = Skin.solid(Skin.surfaceSoft, 20f)
            // 图标 + 名称放进一个 76 宽的竖列（设计板 `.appicon` 宽 76），
            // 名称在这一列里居中 —— 整列再贴到 300 宽格子的左沿。
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(iv, LinearLayout.LayoutParams(ICON, ICON))
            val label = Ui.text(context, display, 18f, if (enabled) Skin.TEXT_SOFT else pal.textFaint).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            column.addView(label, LinearLayout.LayoutParams(-1, 26).apply { topMargin = 12 })
            if (!enabled) {
                column.addView(
                    Ui.text(context, "已禁用", 14f, pal.textFaint),
                    LinearLayout.LayoutParams(-1, 18).also { it.topMargin = 2 },
                )
            }
            body.addView(column, LinearLayout.LayoutParams(ICON, LayoutParams.WRAP_CONTENT))
            cell.addView(body, FrameLayout.LayoutParams(-1, -1))

            // 右上角「⋮」：重命名 / 应用信息 / 卸载（第九十五轮 C5）。
            val more = Ui.text(context, "\u22EE", 24f, pal.textDim, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(Skin.CHIP_FILL, Shape.PILL, 1f, Skin.CHIP_STROKE)
                isClickable = true
                pressFeedback()
                setOnClickListener { showItemMenu(info, display) }
            }
            cell.addView(
                more,
                FrameLayout.LayoutParams(52, 52, Gravity.TOP or Gravity.END).apply {
                    topMargin = 6
                    rightMargin = 6
                },
            )
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = CELL_W
                height = CELL_H
            })
        }
    }

    /** 显示名：有别名用别名，否则用系统应用名（第九十五轮 C5）。 */
    private fun displayLabel(info: ResolveInfo, pm: PackageManager): String =
        prefs.appAliasOf(info.activityInfo.packageName) ?: info.loadLabel(pm).toString()

    /** 启动一个抽屉应用（沿用原来的「全屏 / 小窗」两态逻辑）。 */
    private fun openApp(info: ResolveInfo, display: String) {
        val pm = context.packageManager
        val pkg = info.activityInfo.packageName
        // 排障场景：「抽屉里点图标没启动 / 启动成了小窗」——选中项与启动方式落盘。
        Logx.event(
            LogTags.DRAWER,
            "抽屉选中应用",
            "pkg" to pkg,
            "name" to display,
            "mode" to if (miniMode) "mini" else "fullscreen",
        )
        if (miniMode) {
            onOpenApp?.invoke(pkg, display, true)
        } else if (onOpenApp != null) {
            onOpenApp?.invoke(pkg, display, false)
        } else {
            try {
                val i = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(pkg, info.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
            } catch (e: Exception) {
                Logx.w(LogTags.DRAWER, "抽屉应用启动失败", e, "pkg" to pkg, "name" to display)
            }
        }
    }

    // ---------------------------------------------------------- 应用菜单（第九十五轮 C5）

    private var itemMenu: View? = null
    private var renameDialog: View? = null
    private var confirmDialog: View? = null

    /** 格子右上角「⋮」菜单：重命名 / 应用信息 / 卸载。 */
    private fun showItemMenu(info: ResolveInfo, display: String) {
        if (itemMenu != null) return
        val pkg = info.activityInfo.packageName
        val menu = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { dismissItemMenu() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.CARD)
            setPadding(30, 26, 30, 22)
        }
        card.addView(Ui.text(context, display, 30f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2))
        card.addView(
            Ui.text(context, pkg, 17f, pal.textFaint),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 6 },
        )
        fun row(text: String, color: Int, onClick: () -> Unit) {
            val tv = Ui.text(context, text, 25f, color, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 0, 8, 0)
                isClickable = true
                pressFeedback()
                setOnClickListener { onClick() }
            }
            card.addView(tv, LinearLayout.LayoutParams(-1, 92).also { it.topMargin = 12 })
        }
        row("重命名", pal.text) {
            dismissItemMenu()
            showRenameDialog(pkg, display)
        }
        row("应用信息", pal.text) {
            dismissItemMenu()
            openAppInfo(pkg)
        }
        // 第九十四轮：禁用 / 启用。禁用后应用仍在抽屉里，只是置灰。
        when {
            pkg == context.packageName -> card.addView(
                Ui.text(context, "桌面本身不能禁用", 18f, pal.textFaint),
                LinearLayout.LayoutParams(-1, 64).also { it.topMargin = 8 },
            )
            isEnabled(info) -> row("禁用应用", pal.danger) {
                dismissItemMenu()
                showDisableDialog(info, display)
            }
            else -> row("启用应用", Skin.ACCENT) {
                dismissItemMenu()
                setAppEnabled(info, true, display)
            }
        }
        val systemApp = (info.activityInfo.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        if (systemApp) {
            card.addView(
                Ui.text(context, "系统应用不支持卸载", 18f, pal.textFaint),
                LinearLayout.LayoutParams(-1, 64).also { it.topMargin = 8 },
            )
        } else {
            row("卸载", pal.danger) {
                dismissItemMenu()
                uninstallApp(pkg)
            }
        }
        row("取消", pal.textDim) { dismissItemMenu() }
        menu.addView(
            card,
            FrameLayout.LayoutParams(680, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        itemMenu = menu
        addView(menu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        Logx.event(LogTags.DRAWER, "打开应用菜单", "pkg" to pkg)
    }

    private fun dismissItemMenu() {
        val menu = itemMenu ?: return
        itemMenu = null
        removeView(menu)
    }

    private fun componentOf(info: ResolveInfo) =
        android.content.ComponentName(info.activityInfo.packageName, info.activityInfo.name)

    /**
     * 应用当前是否可用：清单里的 enabled + PackageManager 里应用级/组件级的状态都要看。
     *
     * 只读 `activityInfo.enabled` 不够——系统里被禁用（或默认禁用，如 SIM Toolkit）的组件，
     * 查询回来的 ActivityInfo.enabled 仍然是 true，必须查 [PackageManager.getComponentEnabledSetting]。
     */
    private fun isEnabled(info: ResolveInfo): Boolean {
        val app = info.activityInfo.applicationInfo ?: return false
        if (!app.enabled || !info.activityInfo.enabled) return false
        val pm = context.packageManager
        fun usable(state: Int) = state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        val appState = try {
            pm.getApplicationEnabledSetting(info.activityInfo.packageName)
        } catch (e: Exception) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val compState = try {
            pm.getComponentEnabledSetting(componentOf(info))
        } catch (e: Exception) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        return usable(appState) && usable(compState)
    }

    /**
     * 禁用 / 启用应用。
     *
     * 写 `PackageManager.setApplicationEnabledSetting(...)`，需要
     * `CHANGE_COMPONENT_ENABLED_STATE`（平台签名的系统应用才有）。普通安装拿不到权限时
     * 只提示、不崩，也不改界面。
     */
    private fun setAppEnabled(info: ResolveInfo, enabled: Boolean, display: String) {
        val pkg = info.activityInfo.packageName
        try {
            context.packageManager.setApplicationEnabledSetting(
                pkg,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.DONT_KILL_APP,
            )
            if (enabled) {
                // 有些应用只是「入口组件」被禁用（如 SIM Toolkit），应用级恢复后还要把入口组件打开。
                context.packageManager.setComponentEnabledSetting(
                    componentOf(info),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            Logx.event(LogTags.DRAWER, "应用状态变更", "pkg" to pkg, "enabled" to enabled)
            toast(if (enabled) "已启用 $display" else "已禁用 $display")
            reload()
        } catch (e: SecurityException) {
            Logx.w(LogTags.DRAWER, "应用状态变更失败", e, "pkg" to pkg, "enabled" to enabled)
            toast("没有系统权限，无法禁用/启用应用")
        } catch (e: IllegalArgumentException) {
            Logx.w(LogTags.DRAWER, "应用状态变更失败", e, "pkg" to pkg, "enabled" to enabled)
            toast("该应用不支持禁用到用户态")
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 禁用前确认：误点会把应用停掉（可在菜单里恢复，但先问一句更稳）。 */
    private fun showDisableDialog(info: ResolveInfo, display: String) {
        if (confirmDialog != null) return
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { dismissConfirmDialog() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.CARD)
            setPadding(34, 28, 34, 26)
            isClickable = true
        }
        card.addView(Ui.text(context, "禁用应用", 30f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2))
        card.addView(
            Ui.text(
                context,
                "禁用后「$display」会停止运行、图标变灰；随时可以在它的 ⋮ 菜单里重新启用。",
                18f,
                pal.textDim,
            ),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 },
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun button(text: String, primary: Boolean, onClick: () -> Unit) {
            val tv = Ui.text(context, text, 22f, if (primary) Skin.ACCENT_INK else pal.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(28, 12, 28, 12)
                background = Ui.round(
                    if (primary) Skin.ACCENT else Skin.CHIP_FILL,
                    Shape.PILL,
                    if (primary) 0f else 1f,
                    Skin.CHIP_STROKE,
                )
                isClickable = true
                pressFeedback()
                setOnClickListener { onClick() }
            }
            actions.addView(tv, LinearLayout.LayoutParams(-2, 64).also { it.leftMargin = 16 })
        }
        button("取消", false) { dismissConfirmDialog() }
        button("禁用", true) {
            dismissConfirmDialog()
            setAppEnabled(info, false, display)
        }
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 22 })
        overlay.addView(card, FrameLayout.LayoutParams(820, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        confirmDialog = overlay
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dismissConfirmDialog() {
        val d = confirmDialog ?: return
        confirmDialog = null
        removeView(d)
    }

    /** 重命名：本地别名写入 Prefs（`launcher_app_alias`），空串 = 恢复系统名称。 */
    private fun showRenameDialog(pkg: String, current: String) {
        if (renameDialog != null) return
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { dismissRenameDialog() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.CARD)
            setPadding(34, 28, 34, 26)
            isClickable = true
        }
        card.addView(Ui.text(context, "重命名应用", 30f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2))
        card.addView(
            Ui.text(context, "只改桌面显示名，不影响系统里的应用名。留空恢复原名。", 18f, pal.textDim),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 },
        )
        val input = android.widget.EditText(context).apply {
            setText(prefs.appAliasOf(pkg) ?: current)
            setTextColor(pal.text)
            setHintTextColor(pal.textFaint)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26f)
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)
            setPadding(22, 0, 22, 0)
            isSingleLine = true
            setSelection(text.length)
        }
        card.addView(input, LinearLayout.LayoutParams(-1, 78).also { it.topMargin = 18 })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun button(text: String, primary: Boolean, onClick: () -> Unit) {
            val tv = Ui.text(context, text, 22f, if (primary) Skin.ACCENT_INK else pal.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(28, 12, 28, 12)
                background = Ui.round(
                    if (primary) Skin.ACCENT else Skin.CHIP_FILL,
                    Shape.PILL,
                    if (primary) 0f else 1f,
                    Skin.CHIP_STROKE,
                )
                isClickable = true
                pressFeedback()
                setOnClickListener { onClick() }
            }
            actions.addView(tv, LinearLayout.LayoutParams(-2, 64).also { it.leftMargin = 16 })
        }
        button("取消", false) { dismissRenameDialog() }
        button("保存", true) {
            val alias = input.text.toString().trim()
            prefs.setAppAlias(pkg, alias)
            Logx.event(LogTags.DRAWER, "应用重命名", "pkg" to pkg, "alias" to alias)
            dismissRenameDialog()
            reload()
        }
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 22 })
        overlay.addView(card, FrameLayout.LayoutParams(820, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        renameDialog = overlay
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dismissRenameDialog() {
        val dialog = renameDialog ?: return
        renameDialog = null
        removeView(dialog)
    }

    private fun openAppInfo(pkg: String) {
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + pkg)),
            Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                Logx.event(LogTags.DRAWER, "打开应用信息", "pkg" to pkg)
                return
            } catch (e: Exception) {
                Logx.w(LogTags.DRAWER, "打开应用信息失败", e, "pkg" to pkg)
            }
        }
        android.widget.Toast.makeText(context, "没有可打开的应用信息页", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun uninstallApp(pkg: String) {
        val intents = listOf(
            Intent(Intent.ACTION_DELETE).setData(android.net.Uri.parse("package:" + pkg)),
            Intent(Intent.ACTION_UNINSTALL_PACKAGE).setData(android.net.Uri.parse("package:" + pkg)),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                Logx.event(LogTags.DRAWER, "请求卸载应用", "pkg" to pkg, "action" to intent.action)
                return
            } catch (e: Exception) {
                Logx.w(LogTags.DRAWER, "卸载请求失败", e, "pkg" to pkg)
            }
        }
        android.widget.Toast.makeText(context, "本机没有卸载界面，无法卸载", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 拖放落点处理：把被拖的应用挪到目标位置并持久化（`launcher_app_order`）。
     */
    private fun handleDrop(dragX: Float, dragY: Float, draggedPkg: String): Boolean {
        val target = cellIndexAt(dragX, dragY)
        if (target < 0) return false
        val list = currentPackages.toMutableList()
        val from = list.indexOf(draggedPkg)
        if (from < 0) return false
        list.removeAt(from)
        val to = if (target > from) target - 1 else target
        list.add(to.coerceIn(0, list.size), draggedPkg)
        currentPackages = list
        prefs.appOrder = list
        // 排障场景：「抽屉排序没保存 / 拖到了错误位置」——落点与最终顺序落盘。
        Logx.event(
            LogTags.DRAWER,
            "抽屉应用拖放排序",
            "pkg" to draggedPkg,
            "from" to from,
            "to" to to,
            "order" to list.joinToString(","),
        )
        reload()
        return true
    }

    /** 找到坐标落在哪个格子上（-1 表示没落在任何格子）。 */
    private fun cellIndexAt(x: Float, y: Float): Int {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val loc = IntArray(2)
            child.getLocationInWindow(loc)
            val gridLoc = IntArray(2)
            grid.getLocationInWindow(gridLoc)
            val left = loc[0] - gridLoc[0]
            val top = loc[1] - gridLoc[1]
            if (x >= left && x <= left + child.width && y >= top && y <= top + child.height) return i
        }
        return -1
    }

    companion object {
        /** 抽屉面板宽（设计 px）。 */
        const val PANEL_W = 1876

        /** 抽屉面板高（设计 px）—— 第五十二轮随内容上移长高 37（830 → 867），底边仍贴 940。 */
        const val PANEL_H = 867

        /** 抽屉面板左边距（设计 px）—— 与首页 / 导航页同一版面网格。 */
        const val PANEL_LEFT = 44

        /** 抽屉面板上边距（设计 px）—— 第五十二轮 110 → 73（状态栏缩到 2/3 后的内容基线）。 */
        const val PANEL_TOP = 73

        /** 抽屉面板内容左右留白（设计 px）。 */
        const val PANEL_PAD = 40

        /** 标题顶部偏移（相对面板，设计 px）。 */
        const val TITLE_TOP = 34

        /** 分段开关顶部偏移（相对面板，设计 px）。 */
        const val SEG_TOP = 40

        /** 分隔线顶部偏移（相对面板，设计 px）。 */
        const val DIVIDER_TOP = 100

        /** 网格顶部偏移（相对面板，设计 px）。 */
        const val GRID_TOP = 150

        /** 网格区高度（相对面板，设计 px）—— 面板长高 37，网格区同步长高。 */
        const val GRID_H = 657

        /** 底部提示顶部偏移（相对面板，设计 px）—— 面板长高 37，提示跟着下移以保持离底 48。 */
        const val FOOT_TOP = 819

        /** 网格总宽（6 列 × 300，设计 px）。 */
        const val GRID_W = 1800

        /** 单个格子宽（设计 px）。 */
        const val CELL_W = 300

        /** 单个格子高（设计 px）。 */
        const val CELL_H = 150

        /** 应用图标边长（设计 px）。 */
        const val ICON = 76
    }
}
