package com.cardesk.launcher.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        val foot = Ui.text(context, "点按图标启动应用，长按可拖动排序", 16f, Skin.TEXT_DIM).apply {
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
        val apps = try {
            pm.queryIntentActivities(intent, 0)
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
                { it.loadLabel(pm).toString() },
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
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 第五十一轮：设计板 s03 的格子是 的**定宽块**，
                // 图标（`.appicon` 76×96）用 **贴格子左沿**，
                // 名称在 76 宽的框里居中。之前这里把图标在 300 宽的格子里居中，
                // 整排图标右移了 112px，跟设计板对不上。
                gravity = Gravity.TOP or Gravity.START
                isClickable = true
                setOnClickListener {
                    val pkg = info.activityInfo.packageName
                    val name = info.loadLabel(pm).toString()
                    // 排障场景：「抽屉里点图标没启动 / 启动成了小窗」——选中项与启动方式落盘。
                    Logx.event(
                        LogTags.DRAWER,
                        "抽屉选中应用",
                        "pkg" to pkg,
                        "name" to name,
                        "mode" to if (miniMode) "mini" else "fullscreen",
                    )
                    if (miniMode) {
                        onOpenApp?.invoke(pkg, name, true)
                    } else if (onOpenApp != null) {
                        onOpenApp?.invoke(pkg, name, false)
                    } else {
                        try {
                            val i = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                setClassName(pkg, info.activityInfo.name)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(i)
                        } catch (e: Exception) {
                            // 启动失败静默
                            Logx.w(LogTags.DRAWER, "抽屉应用启动失败", e, "pkg" to pkg, "name" to name)
                        }
                    }
                }
                // 长按 = 拖动排序（Drawer 的 startDragAndDrop +）
                setOnLongClickListener {
                    val pkg = info.activityInfo.packageName
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
            iv.background = Skin.solid(Skin.surfaceSoft, 20f)
            // 图标 + 名称放进一个 76 宽的竖列（设计板 `.appicon` 宽 76），
            // 名称在这一列里居中 —— 整列再贴到 300 宽格子的左沿。
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(iv, LinearLayout.LayoutParams(ICON, ICON))
            val label = Ui.text(context, info.loadLabel(pm).toString(), 18f, Skin.TEXT_SOFT).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            column.addView(label, LinearLayout.LayoutParams(-1, 26).apply { topMargin = 12 })
            cell.addView(column, LinearLayout.LayoutParams(ICON, LayoutParams.WRAP_CONTENT))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = CELL_W
                height = CELL_H
            })
        }
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
