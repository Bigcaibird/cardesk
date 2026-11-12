package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
 *   图标 **76×76 圆角 20** 软底**居中**，名称 18px 次级文字、距图标 12、宽 160；
 * - 遮罩改用 [Skin.SCRIM]，状态栏和 Dock 仍在抽屉之上可见（抽屉插在状态栏之前）。
 *
 * ## 第152轮：去掉「⋮」，菜单改成长按图标
 *
 * 原来每个格子右上角有一个独立的「⋮」按钮，它贴在 300 宽格子的**右沿**，
 * 离自己的图标 242px —— 远得像**下一个应用**的按钮，
 * 一眼看过去满屏「图标 ⋮ 图标 ⋮」，这就是「太混乱」的根源。
 *
 * 这一轮：
 * - **删掉「⋮」**；
 * - **长按图标**弹出应用菜单（重命名 / 应用信息 / 禁用·启用 / 卸载）；
 * - **去掉长按拖动排序**（长按只剩一个手势，不用跟拖放抢）；
 * - 图标列**在格子里居中**（之前贴左沿是给 ⋮ 让位的）；
 * - 名称列 76 → **160**，别再把应用名截成一串省略号。
 */
class DrawerPage(context: Context, private val pal: Palette) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null
    /** 打开应用：mini=true 表示小窗打开（浮窗承载），false 表示全屏。 */
    var onOpenApp: ((pkg: String, name: String, mini: Boolean) -> Unit)? = null

    private var miniMode = false

    private lateinit var grid: GridLayout
    private lateinit var hint: TextView

    /**
     * 当前显示顺序（包名）。
     *
     * 第152轮**去掉拖放排序**后，它不再被任何地方改写 ——
     * 只用来记一下本次加载了多少个应用（排障日志）。
     */
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
        // 第152轮：**去掉拖放排序**。
        //
        // 以前 grid 上挂了 `setOnDragListener` + 格子长按 `startDragAndDrop`，
        // 长按一个应用能拖着换位置（顺序写进 `launcher_app_order`）。
        // 现在长按改成**弹出应用菜单**，拖放整套（监听 / 拖影 / 落点换算）一并删掉 ——
        // 两个手势抢同一个入口，留着只会打架。
        scroll.addView(grid, FrameLayout.LayoutParams(GRID_W, LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, abs(PANEL_PAD, GRID_TOP, GRID_W, GRID_H))

        // —— 底部提示 ——
        val foot = Ui.text(context, "点按图标启动，长按图标打开菜单", 16f, Skin.TEXT_DIM).apply {
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
        // 用户排序优先：排过序的按保存顺序在前，其余按名称排在后面。
        //
        // 第152轮去掉了拖放排序，**这里只读不写** ——
        // 老用户之前排过的顺序不该因为功能下线就丢（写了就还在）。
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
            val display = displayLabel(info, pm)
            val enabled = isEnabled(info)
            // 第152轮：格子 = 图标列（点按启动 / **长按弹菜单**）。
            //
            // 第九十五轮 C5 的样子是「图标列 + 右上角 ⋮ 菜单按钮」，这一轮整个换掉：
            // ① **去掉 ⋮** —— 它贴在 300 宽格子的右沿，离自己的图标 242px，
            //    远得像**下一个应用**的按钮，一眼看过去就是「图标 ⋮ 图标 ⋮」的乱麻；
            // ② **长按图标弹菜单** —— 菜单入口挪到图标上，符合直觉（长按哪张图管哪张图）；
            // ③ **去掉长按拖动排序** —— 长按只剩一个手势，不用再跟拖放抢。
            val cell = FrameLayout(context)
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 第152轮：打标记，测试才能从视图树里找到「长按弹菜单」的宿主。
                // （菜单入口从「⋮ 按钮」挪到了这里，测试也得跟着换。）
                tag = APP_CELL_MARK
                // 第152轮：图标列改成**在格子里居中**。
                //
                // 之前贴左沿是**给右上角的 ⋮ 让位**（第五十一轮的设计板 s03 是那个样子）；
                // ⋮ 没了之后贴左沿就变成「一整排图标全挤在左边、右边空 224px」，
                // 反而更不平衡。居中之后每列的图标中心间距正好等于格宽 300。
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    if (enabled) {
                        openApp(info, display)
                    } else {
                        showItemMenu(info, display)
                        toast("应用已禁用，可在菜单里启用")
                    }
                }
                // 第152轮：长按 = 弹出应用菜单（原来是拖动排序）。
                setOnLongClickListener {
                    showItemMenu(info, display)
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
            // 第152轮：图标 + 名称放进一个 **160 宽**的竖列（原来只有 76 宽）。
            //
            // 原来名称只有 76 宽，一半应用名都被截成「Amap A...」「CarDes...」——
            // 那才是「太混乱」的另一半：满屏省略号，谁也认不出是哪个 App。
            // 160 宽能放下「Android 设置」「Calculator」「高德地图」这些常见名字。
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
            column.addView(label, LinearLayout.LayoutParams(LABEL_W, 26).apply { topMargin = 12 })
            if (!enabled) {
                column.addView(
                    Ui.text(context, "已禁用", 14f, pal.textFaint),
                    LinearLayout.LayoutParams(LABEL_W, 18).also { it.topMargin = 2 },
                )
            }
            body.addView(column, LinearLayout.LayoutParams(LABEL_W, LayoutParams.WRAP_CONTENT))
            cell.addView(body, FrameLayout.LayoutParams(-1, -1))

            // 第152轮：**右上角的「⋮」按钮删掉了**（菜单入口改成「长按图标」）。
            //
            // 它贴在 300 宽格子的右沿，离自己的图标 242px ——
            // 远得像**下一个应用**的按钮，整屏看上去是「图标 ⋮ 图标 ⋮」的乱麻。
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

    /**
     * 应用菜单：重命名 / 应用信息 / 禁用·启用 / 卸载。
     *
     * 第152轮起，入口是**长按图标**（原来是一个独立的「⋮」按钮）。
     */
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

    /**
     * 关掉抽屉里**最上面**那层浮层。
     *
     * 第152轮真机发现的 bug：应用菜单（长按弹出的那层）打开时按返回键，
     * 整个抽屉会被关掉，而**菜单本身留在了抽屉页里**——
     * 抽屉页是 LauncherView 长期持有的**同一个实例**，
     * 关闭抽屉只是把它从窗口摘掉，实例里的浮层原封不动。
     * 下次打开抽屉，菜单又盖在上面，它的遮罩会把所有点击吃掉，
     * **点图标一点都没反应**（第一次点只是把菜单关掉）。
     *
     * 返回键的优先级也靠它：**先关浮层，再关抽屉**。
     *
     * @return 真的关掉了一层返回 true；本来就没有浮层返回 false。
     */
    fun closeTopOverlay(): Boolean = when {
        // 顺序按「谁在上面谁先关」：重命名 / 禁用确认都是在菜单之上弹出来的。
        renameDialog != null -> {
            dismissRenameDialog()
            true
        }
        confirmDialog != null -> {
            dismissConfirmDialog()
            true
        }
        itemMenu != null -> {
            dismissItemMenu()
            true
        }
        else -> false
    }

    /**
     * 清掉抽屉里的**所有**浮层。
     *
     * 抽屉关闭时必须调用 —— 否则这些浮层会跟着抽屉页的实例留到下一次打开。
     */
    fun clearOverlays() {
        dismissItemMenu()
        dismissRenameDialog()
        dismissConfirmDialog()
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
                // 第152轮：菜单入口从「⋮ 按钮」改成了「长按图标」，这句提示也跟着改。
                "禁用后「$display」会停止运行、图标变灰；随时可以长按它的图标重新启用。",
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

    companion object {
        /**
         * 应用格子（图标列）的标记。
         *
         * 第152轮起，菜单入口是**长按这个格子**弹出来的（原来是一个独立的「⋮」按钮），
         * 所以测试需要能从视图树里精确找到某个应用的那个格子。
         */
        val APP_CELL_MARK = Any()

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

        /**
         * 图标 + 名称那一列的宽（设计 px）。
         *
         * 第152轮：76 → **160**。
         *
         * 原来这列只有 76（跟图标一样宽），名称只能塞 4 个字，
         * 一半应用名都被截成「Amap A...」「CarDes...」—— 满屏省略号，
         * 谁也认不出是哪个 App，这才是「太混乱」的另一半。
         *
         * 160 能放下「Android 设置」「Calculator」「高德地图」这些常见名字；
         * 格子本身是 300，所以列居中后左右各留 70，图标列不会挤在一起。
         */
        const val LABEL_W = 160
    }
}
