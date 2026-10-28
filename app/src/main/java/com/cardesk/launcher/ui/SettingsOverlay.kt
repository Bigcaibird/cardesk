package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import com.cardesk.launcher.core.AdbDebug
import com.cardesk.launcher.core.AdbDiagnostics
import com.cardesk.launcher.core.AdbOutcome
import com.cardesk.launcher.core.DayNight
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.OemDockPolicy
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.ScheduledTasks
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogLevel
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.widget.CardStylePreviewView
import com.cardesk.launcher.ui.widget.OverlayBackdrop
import com.cardesk.launcher.ui.widget.QrCodeView
import com.cardesk.launcher.ui.widget.WallpaperThumbView
import com.cardesk.launcher.vehicle.DriveMode

/**
 * 设置浮层，沿用 App 的
 * （、
 *
 *
 * 第四十九轮按设计板 `s05-settings` 重排：
 * - 面板 **1440×920**，位于 `left 240 / top 80`（居中）；
 * - 全屏遮罩用 [Skin.SCRIM]，面板是 `Skin.flat(Shape.CARD)` 扁平面；
 * - 左栏宽 **380** + 1px 竖分割线：标题「桌面设置」34px 粗体 (36, 26)，
 *   下面是**一棵分组树**（组标题 16px 弱文字，y 起点 100、组标题后 +28、
 *   每个条目 +34、每组结束 +10；条目 332×32 圆角 10，选中 = 强调色软底 + 强调色描边 + 强调色字；
 * - 右栏内容区左右留白 48（内容宽 964）：页标题 40px 粗体 (48, 34)、
 *   「关闭」胶囊 `right 36 / top 36`、1px 分割线 (48, 190)；
 * - 开关行从 y=120 起、步长 130：名称 28px 粗体、描述 19px 次文字（间距 8）、
 *   开关 84×48（右侧）、1px 分割线在行顶 +92。
 *
 * 控件库（行高 92 / 分割线 / 开关 / 滑块 / 分段控件）按设计板统一，
 * 各页面的功能项与文案一个没少。
 */
class SettingsOverlay(
    context: Context,
    private val prefs: Prefs,
    private val pal: Palette,
) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null
    var onApply: (() -> Unit)? = null
    var onVehicleSource: (() -> Unit)? = null
    var onEditNavSlots: (() -> Unit)? = null
    var onShowTasks: (() -> Unit)? = null
    var onShowLayoutDialog: (() -> Unit)? = null
    var onShowUpdate: (() -> Unit)? = null
    var onShowRestart: (() -> Unit)? = null
    var onShowAccent: (() -> Unit)? = null

    /** 保留兼容：内置的「Dock 生效应用」面板已改用共享的 AppPickerDialog。 */
    var onShowDockApps: (() -> Unit)? = null

    /** 「指定隐藏状态栏的应用」选择器（MainActivity.openDesignDockAppPicker 的另一半）。 */
    var onShowStatusApps: (() -> Unit)? = null

    /**
     * 「Dock 快捷应用」配置面板（第五十四轮）。
     *
     * Dock 最右边多了一段快捷应用槽位，槽位内容由用户在这里配
     * （也可以直接长按 Dock 上的槽位进同一个面板）。
     */
    var onShowDockQuickApps: (() -> Unit)? = null

    /**
     * 打开画中画应用选择器。
     *
     * @param true = 选**副画**的应用（`pip2_app`），false = 主画（`pip_app`）。
     */
    var onShowPipPicker: ((Boolean) -> Unit)? = null
    var onShowKeys: (() -> Unit)? = null

    /**
     * 壁纸被换掉（第五十七轮）。
     *
     * 设置页只管落盘（`design_wallpaper_index`），**真正的画面更新**交给桌面：
     * 桌面收到后立刻把 `BackgroundView` 换成新壁纸，用户在设置页里就能看到效果。
     */
    var onWallpaperChange: ((Int) -> Unit)? = null

    /**
     * 卡片风格改了（第五十八轮）：透明度 / 颜色 / 圆角。
     *
     * 设置页只管落盘 + 刷新预览，**全局生效**交给桌面（桌面要重算 token 并刷新画面）。
     */
    var onCardStyleChange: (() -> Unit)? = null

    /**
     * 打开卡片颜色选择器（第五十八轮）。
     *
     * 复用现成的 [com.cardesk.launcher.ui.dialogs.ColorPickerDialog]，
     * 选中后桌面会把结果写回 `prefs.cardColor`。
     */
    var onShowCardColor: (() -> Unit)? = null

    private data class NavItem(val key: String, val label: String)
    private data class NavGroup(val title: String, val items: List<NavItem>)
    private data class TabView(val root: LinearLayout, val label: TextView, val count: TextView)

    /**
     * 左侧导航分组与条目，按 重建
     *
     */
    private val globalGroups = listOf(
        NavGroup(
            "智能功能",
            listOf(
                NavItem("boot", "启动切换"),
                NavItem("keys", "按键增强"),
                NavItem("cluster", "方控仪表"),
                NavItem("tasks", "计划任务"),
                NavItem("autostart", "桌面自启"),
            ),
        ),
        NavGroup(
            "布局与显示",
            listOf(
                NavItem("daynight", "日夜模式"),
                NavItem("dualpip", "双画中画"),
            ),
        ),
        NavGroup(
            "网络与升级",
            listOf(
                NavItem("lan", "局域网传输"),
                NavItem("update", "在线更新"),
            ),
        ),
    )

    private val systemGroups = listOf(
        NavGroup(
            "车辆功能",
            listOf(
                NavItem("p360", "360 环影"),
                NavItem("autostop", "启停控制"),
                NavItem("hotspot", "自动热点"),
                NavItem("adb", "ADB 调试"),
            ),
        ),
        NavGroup(
            "界面显示",
            listOf(
                NavItem("layout", "仪表歌词"),
                NavItem("dock", "Dock 状态栏"),
                NavItem("quickapps", "Dock 快捷应用"),
            ),
        ),
        NavGroup(
            "系统控制",
            listOf(
                NavItem("native", "默认设置"),
                NavItem("diagnostics", "系统诊断"),
                NavItem("restart", "重启车机"),
                // 调试工具（没有这一页，是为真机排障加的）
                NavItem("logs", "诊断日志"),
            ),
        ),
    )

    private var tab = 0
    private var selectedKey = "boot"

    /**
     * 第四十九轮：左侧导航改成**一棵完整的树**（设计板 `s05-settings`）。
     *
     * 第四十三～四十八轮是「全局 / 系统」两棵子树 + 顶部 tab 条来回切；
     * 设计板把两棵树合成一棵，分组按归并为
     * 智能功能 / 布局与显示 / 网络与升级 / 车控 / 系统。
     */
    private val mergedGroups = listOf(
        NavGroup(
            "智能功能",
            listOf(
                NavItem("boot", "启动切换"),
                NavItem("keys", "按键增强"),
                NavItem("cluster", "方控仪表"),
                NavItem("tasks", "计划任务"),
                NavItem("autostart", "桌面自启"),
            ),
        ),
        NavGroup(
            "布局与显示",
            listOf(
                NavItem("daynight", "日夜模式"),
                NavItem("wallpaper", "壁纸"),
                NavItem("cardstyle", "卡片风格"),
                NavItem("dualpip", "双画中画"),
                NavItem("layout", "仪表歌词"),
                NavItem("dock", "Dock 状态栏"),
                NavItem("quickapps", "Dock 快捷应用"),
            ),
        ),
        NavGroup(
            "网络与升级",
            listOf(
                NavItem("lan", "局域网传输"),
                NavItem("update", "在线更新"),
            ),
        ),
        NavGroup(
            "车控",
            listOf(
                NavItem("p360", "360 环影"),
                NavItem("autostop", "启停控制"),
                NavItem("hotspot", "自动热点"),
                NavItem("adb", "ADB 调试"),
            ),
        ),
        NavGroup(
            "系统",
            listOf(
                NavItem("native", "默认设置"),
                NavItem("diagnostics", "系统诊断"),
                NavItem("restart", "重启车机"),
                // 调试工具（没有这一页，是为真机排障加的）
                NavItem("logs", "诊断日志"),
            ),
        ),
    )

    private lateinit var navList: FrameLayout
    private lateinit var pageTitle: TextView
    private lateinit var body: LinearLayout
    private lateinit var tabGlobal: TabView
    private lateinit var tabSystem: TabView
    private lateinit var navScroll: ScrollView
    private lateinit var bodyScroll: ScrollView

    /** 「启动后」小节标题（只有「启动切换」页显示）。 */
    private lateinit var bootCaption: TextView

    /** 左栏条目（key → 视图），用于选中态刷新。 */
    private val navRows = LinkedHashMap<String, TextView>()

    // ---------------------------------------------------------- ADB 调试页（第五十五轮）

    /** ADB 页最近一次操作的结论（切走再切回来还在）。 */
    private var adbLastAction: String = "打开本页只检查状态；点下方按钮才会更改调试设置。"

    /** ADB 页的诊断文本（切走再切回来还在）。 */
    private var adbDiagnostics: String = ""

    /** ADB 页的诊断详情是否展开（默认收起，别把设置页糊成字墙）。 */
    private var adbDiagnosticsExpanded: Boolean = false

    /** ADB 页的结果回调：后台操作结束后用它原地刷新，不重建页面。 */
    private var adbOnResult: ((AdbOutcome) -> Unit)? = null

    private var panelWidth = PANEL_W
    private var panelHeight = PANEL_H
    private var leftWidth = LEFT_W

    /** 玻璃面板（动画用：打开时上滑、关闭时下滑）。 */
    private var panelView: View? = null

    /** 遮罩（动画用：打开淡入、关闭淡出）。 */
    private var scrimView: View? = null

    /**
     * 播放**打开**动画（滑入 + 淡入）。
     */
    fun playOpen() {
        OverlayAnim.open(panelView, scrimView)
    }

    /**
     * 播放**关闭**动画（下滑 + 淡出）。
     *
     * @param onEnd 动画结束后回调（一般是把这层从窗口摘掉）
     */
    fun playClose(onEnd: () -> Unit) {
        OverlayAnim.close(panelView, scrimView, onEnd)
    }

    init {
        build()
    }

    // ------------------------------------------------------------- 骨架

    private fun build() {
        val scrim = OverlayBackdrop(context).apply {
            // 第五十轮：背景板 = `.bgwash` + `.scrim`（不透明），与设计板 `s05-settings` 一致。
            palette = this@SettingsOverlay.pal
            withScrim = true
            isClickable = true
            setOnClickListener { onClose?.invoke() }
        }
        scrimView = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val profile = com.cardesk.launcher.core.Platforms.detect(context, prefs.platformOverride)
        val compact = profile.compactUi
        // 第四十九轮按设计板 `s05-settings`：面板 **1440×920**（left 240 / top 80）。
        panelWidth = if (compact) 1180 else PANEL_W
        panelHeight = if (compact) 880 else PANEL_H
        leftWidth = if (compact) 300 else LEFT_W
        val rightWidth = panelWidth - leftWidth
        val sheet = FrameLayout(context).apply {
            // 第四十九轮「浮屿」：纯扁平面 + 1px 描边，零投影零玻璃。
            background = Skin.flat(com.cardesk.launcher.core.Shape.CARD)
            isClickable = true
            setOnClickListener { }
        }
        panelView = sheet
        if (compact) {
            addView(sheet, LayoutParams(panelWidth, panelHeight, Gravity.CENTER))
        } else {
            addView(
                sheet,
                LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START).apply {
                    leftMargin = PANEL_LEFT
                    topMargin = PANEL_TOP
                },
            )
        }

        // 左栏 380
        val left = FrameLayout(context)
        sheet.addView(left, FrameLayout.LayoutParams(leftWidth, LayoutParams.MATCH_PARENT))

        // 设计板 s05：左栏顶部是 34px 粗体「桌面设置」(36, 26)，下面直接是分组树
        // （第四十九轮起去掉了第四十三～四十八轮的「全局 / 系统」tab 条）。
        val brand = Ui.text(context, "桌面设置", 34f, pal.text, bold = true)
        left.addView(brand, abs(36, 26, -1, 46))
        // tab 计数徽标仍然维护
        // 只是第四十九轮起不再把切换条画出来（左栏改成单棵树）。
        tabGlobal = tabView("全局")
        tabSystem = tabView("系统")

        navScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        navList = FrameLayout(context)
        navScroll.addView(navList, ViewGroup.LayoutParams(-1, -2))
        left.addView(navScroll, abs(0, TREE_TOP, leftWidth, TREE_VIEW_H))

        // VIN + 开通免激活同一行（底部行）
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(
            bottomRow,
            abs(36, TREE_TOP + TREE_VIEW_H + 4, leftWidth - 72, VIN_ROW_H),
        )
        val vin = Ui.text(context, "VIN：（空）", 13f, pal.textFaint).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 2
        }
        bottomRow.addView(vin, LinearLayout.LayoutParams(0, -1, 1f))
        val activate = Ui.text(context, "开通免激活", 16f, pal.accent, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 0, 0)
            setOnClickListener { select("activate") }
        }
        bottomRow.addView(activate, LinearLayout.LayoutParams(-2, -1))

        // 分隔线
        val divider = View(context).apply { setBackgroundColor(Skin.CHIP_STROKE) }
        sheet.addView(divider, FrameLayout.LayoutParams(1, LayoutParams.MATCH_PARENT).also { it.leftMargin = leftWidth })

        // 右栏
        val right = FrameLayout(context)
        sheet.addView(right, FrameLayout.LayoutParams(rightWidth, LayoutParams.MATCH_PARENT).also { it.leftMargin = leftWidth })

        pageTitle = Ui.text(context, "启动切换", 40f, pal.text, bold = true)
        right.addView(pageTitle, abs(CONTENT_PAD, 34, -1, 48))

        val close = Ui.text(context, "关闭", 24f, pal.textDim).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.CHIP_FILL, com.cardesk.launcher.core.Shape.PILL, 1f, Skin.CHIP_STROKE)
            setOnClickListener { onClose?.invoke() }
        }
        right.addView(close, FrameLayout.LayoutParams(150, 66, Gravity.TOP or Gravity.END).also {
            it.rightMargin = 36; it.topMargin = 36
        })

        // 第五十九轮：**去掉标题下那条绝对定位的分割线**。
        // 它原来钉在 y=190，而正文滚动区从 120 就开始了 —— 于是这条线**落进了第一行里**，
        // 跟第一行自带的分割线（行底 y=212）只隔 22px，屏幕上就是「两根横线」。
        // 现在分割线只由 自己画（每行一条），标题靠留白跟内容分开。

        // 「启动后」小节标题（设计板把分组标题放在左栏，这里保留原文案做右栏小节标题）
        bootCaption = Ui.text(context, "启动后", 16f, pal.textFaint).apply { visibility = View.GONE }
        right.addView(bootCaption, abs(CONTENT_PAD, 88, -1, 22))

        bodyScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CONTENT_PAD, 0, CONTENT_PAD, 40)
        }
        bodyScroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        right.addView(bodyScroll, FrameLayout.LayoutParams(rightWidth, panelHeight - 140).also { it.topMargin = 120 })

        switchTab(0)
    }

    /** FrameLayout 绝对定位（相对父容器左上角）。 */
    private fun abs(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    private fun tabView(label: String): TabView {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val text = Ui.text(context, label, 19f, pal.textDim)
        val count = Ui.text(context, "0", 15f, pal.textDim)
        root.addView(text, LinearLayout.LayoutParams(-2, -2))
        root.addView(count, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = 8 })
        return TabView(root, text, count)
    }

    private fun switchTab(index: Int) {
        tab = index
        paintTab(tabGlobal, index == 0)
        paintTab(tabSystem, index == 1)
        // 排障场景：用户在全局/系统两组之间来回切 —— 记下切到了哪一组
        Logx.event(LogTags.SETTING, "切换设置分组", "tab" to if (index == 0) "全局" else "系统")
        // 计数徽标（/ recountTabs:2708-2711）
        tabGlobal.count.text = globalGroups.sumOf { it.items.size }.toString()
        tabSystem.count.text = systemGroups.sumOf { it.items.size }.toString()
        val groups = mergedGroups
        navList.removeAllViews()
        navRows.clear()
        // 设计板 s05 的树坐标：y 起点 100，组标题后 +28，每个条目 +34，每组结束 +10。
        //
        // 第六十一轮按用户要求「左边太紧凑」把节奏放松：
        //   - 条目行距 34 → [TREE_ITEM_STEP]（行高 [TREE_ITEM_H] + 间隙）；
        //   - 组标题 → 首条目 28 → [TREE_GROUP_STEP]（分组因此一眼能分开）；
        //   - 组尾 → 下一组组标题 10 → [TREE_GROUP_END]。
        var y = TREE_TOP
        for (group in groups) {
            val title = Ui.text(context, group.title, 16f, pal.textFaint)
            navList.addView(title, abs(TREE_GROUP_X, y - TREE_TOP, -1, 24))
            y += TREE_GROUP_STEP
            for (item in group.items) {
                val row = Ui.text(context, item.label, 20f, pal.textDim).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 0, 12, 0)
                    setOnClickListener { select(item.key) }
                }
                navList.addView(row, abs(TREE_ITEM_X, y - TREE_TOP, TREE_ITEM_W, TREE_ITEM_H))
                navRows[item.key] = row
                y += TREE_ITEM_STEP
            }
            y += TREE_GROUP_END
        }
        select(selectedKey.takeIf { key -> groups.any { g -> g.items.any { it.key == key } } } ?: groups.first().items.first().key)
    }

    private fun paintTab(view: TabView, active: Boolean) {
        view.label.setTextColor(if (active) pal.accent else pal.textDim)
        view.count.setTextColor(if (active) pal.accent else pal.textDim)
        view.root.background = Ui.round(if (active) pal.accentSoft else 0x00000000, 12f)
    }

    /** 供外部（深链/预览测试）直接切到某一页。 */
    fun showPageKey(key: String) = select(key)

    private fun select(key: String) {
        selectedKey = key
        for ((k, row) in navRows) {
            val active = k == key
            row.setTextColor(if (active) pal.accent else pal.textDim)
            row.background = if (active) {
                Ui.round(pal.accentSoft, 10f, 1f, pal.accent)
            } else {
                null
            }
        }
        // 「启动后」只属于「启动切换」页
        bootCaption.visibility = if (key == "boot") View.VISIBLE else View.GONE
        // 「开通免激活」不在导航组里，标题固定
        val label = if (key == "activate") {
            "开通免激活"
        } else {
            mergedGroups.flatMap { it.items }.firstOrNull { it.key == key }?.label ?: ""
        }
        pageTitle.text = label
        body.removeAllViews()
        bodyScroll.scrollTo(0, 0)
        // 离开 ADB 页就把结果回调摘掉：后台操作回来时不该再去刷新已经不存在的视图
        if (key != "adb") adbOnResult = null
        // 排障场景：用户进了哪一页 —— 记录页面 key 与中文名
        Logx.event(LogTags.SETTING, "进入设置页", "page" to key, "name" to label)
        when (key) {
            "boot" -> pageBoot()
            "keys" -> pageKeys()
            "cluster" -> pageCluster()
            "tasks" -> pageTasks()
            "autostart" -> pageAutostart()
            "daynight" -> pageDayNight()
            "wallpaper" -> pageWallpaper()
            "cardstyle" -> pageCardStyle()
            "dualpip" -> pageDualPip()
            "lan" -> pageLan()
            "update" -> pageUpdate()
            "p360" -> pageCamera360()
            "autostop" -> pageAutostop()
            "hotspot" -> pageHotspot()
            "adb" -> pageAdb()
            "layout" -> pageLayout()
            "dock" -> pageDock()
            "quickapps" -> pageDockQuickApps()
            "native" -> pageNativeSettings()
            "diagnostics" -> pageDiagnostics()
            "restart" -> pageRestart()
            "logs" -> pageLogs()
            "activate" -> pageActivate()
        }
    }

    // ------------------------------------------------------------- 控件库

    private fun section(title: String) {
        body.addView(Ui.text(context, title, 22f, pal.textFaint), LinearLayout.LayoutParams(-1, 40).also { it.topMargin = 8 })
    }

    private fun note(text: String) {
        body.addView(Ui.text(context, text, 22f, pal.textDim).apply { setLineSpacing(10f, 1f) },
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })
    }

    /**
     * 一行：**28px 粗体主标题 + 19px 副标题（间距 8）** + 右侧控件
     * （设计板 `s05-settings` 的开关行）。
     *
     * 行块高 [ROW_H]，行底一条 1px 分割线 + [ROW_GAP] 间距 → 下一行行顶正好 +[ROW_H] + 1 + [ROW_GAP]。
     *
     * 第五十九轮：标题组**垂直居中**在行块里（原来贴在行顶，行块一高就头重脚轻），
     * 右侧控件也从「顶 + 14」改成**垂直居中**，跟标题对齐。
     */
    private fun row(control: View, label: String, subtitle: String? = null) {
        val holder = FrameLayout(context)
        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textBox.addView(Ui.text(context, label, 28f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2))
        if (!subtitle.isNullOrEmpty()) {
            textBox.addView(
                Ui.text(context, subtitle, 19f, pal.textDim),
                LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 },
            )
        }
        holder.addView(textBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        holder.addView(control, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).also {
            it.topMargin = 14
        })
        body.addView(holder, LinearLayout.LayoutParams(-1, ROW_H))
        val line = View(context).apply { setBackgroundColor(Skin.NAV_STROKE) }
        body.addView(line, LinearLayout.LayoutParams(-1, 1).also { it.bottomMargin = ROW_GAP })
    }

    /**
     * 一行「标签 + 右侧取值」。
     *
     * @return 取值那个 TextView（第五十五轮：ADB 页要在操作后**原地刷新**取值，
     *         所以这里把视图交出去，而不是只往 body 里塞。
     */
    private fun kvRow(label: String, value: String, ok: Boolean): TextView {
        val holder = FrameLayout(context)
        holder.addView(
            Ui.text(context, label, 22f, pal.textDim).apply { gravity = Gravity.CENTER_VERTICAL },
            FrameLayout.LayoutParams(-1, -1).also { it.leftMargin = 18 },
        )
        val readout = Ui.text(context, value, 22f, if (ok) pal.ok else pal.textDim).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        holder.addView(
            readout,
            FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_VERTICAL or Gravity.END).also { it.rightMargin = 18 },
        )
        body.addView(holder, LinearLayout.LayoutParams(-1, 72))
        body.addView(View(context).apply { setBackgroundColor(Skin.NAV_STROKE) }, LinearLayout.LayoutParams(-1, 1))
        return readout
    }

    private fun switchRow(label: String, initial: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
        val toggle = BoardSwitch(context).apply {
            tint = pal.accent
            checked = initial
            setOnClickListener {
                toggle()
                onChange(checked)
                onApply?.invoke()
            }
        }
        row(toggle, label, subtitle)
    }

    /** 一行选择：左侧标题 + 右侧横向滚动的选项条（选项多时也能滚）。 */
    /** 按**下标**取值的选择行（options 是文案，selected 是下标）。 */
    private fun chipRow(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        chipRowCore<Int>(label, options, selected, { it }, onPick)
    }

    /** 按**字符串**取值的选择行（如车型编号 `01` / `02HB` / `03+`，不是下标）。 */
    private fun chipRow(label: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
        chipRowCore<String>(label, options, options.indexOf(selected), { options[it] }, onPick)
    }

    private fun <T> chipRowCore(
        label: String,
        options: List<String>,
        selectedIndex: Int,
        valueOf: (Int) -> T,
        onPick: (T) -> Unit,
    ) {
        val holder = FrameLayout(context)
        holder.addView(
            Ui.text(context, label, 22f, pal.textDim).apply { gravity = Gravity.CENTER_VERTICAL },
            FrameLayout.LayoutParams(300, -1).also { it.leftMargin = 18 },
        )
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val seg = buildSegment(options, selectedIndex) { index ->
            onPick(valueOf(index))
            onApply?.invoke()
        }
        scroll.addView(seg)
        // 宽度必须占满「标签右侧的剩余空间」（-1 + 左外边距），
        // 否则 wrap_content 的内容会溢出并压在标签上
        holder.addView(
            scroll,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.END).also {
                it.rightMargin = 18
                it.leftMargin = 300
            },
        )
        body.addView(holder, LinearLayout.LayoutParams(-1, 96))
        val line = View(context).apply { setBackgroundColor(Skin.NAV_STROKE) }
        body.addView(line, LinearLayout.LayoutParams(-1, 1))
    }

    /**
     * 分段控件（设计板 `.seg`）：软底 + 1px 描边 + 胶囊圆角，内边距 5、选项间距 6；
     * 选项 20px / 内边距 9×22。
     */
    private fun buildSegment(
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit,
    ): LinearLayout {
        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(Skin.surfaceSoft, com.cardesk.launcher.core.Shape.PILL, 1f, Skin.NAV_STROKE)
            setPadding(5, 5, 5, 5)
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { index, text ->
            val item = Ui.text(context, text, 20f, pal.textDim).apply {
                gravity = Gravity.CENTER
                setPadding(22, 9, 22, 9)
                setOnClickListener {
                    views.forEachIndexed { i, v -> paintSegment(v, i == index) }
                    onPick(index)
                }
            }
            views.add(item)
            seg.addView(item, LinearLayout.LayoutParams(-2, -2).also { if (index > 0) it.leftMargin = 6 })
        }
        views.forEachIndexed { i, v -> paintSegment(v, i == selectedIndex) }
        return seg
    }

    /**
     * 分段项两态（设计板 `.seg span`）：
     * - 选中：**强调色实底 + 强调色上的字 + 加粗**；
     * - 未选中：透明底 + 次级文字。
     */
    private fun paintSegment(seg: TextView, active: Boolean) {
        seg.setTextColor(if (active) pal.accentInk else pal.textDim)
        seg.typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
        seg.background = if (active) {
            Ui.round(pal.accent, com.cardesk.launcher.core.Shape.PILL)
        } else {
            null
        }
    }

    /** 一行选择器：左侧标题 + 右侧当前值（点开另一个面板）。 */
    private fun pickerRow(label: String, valueText: String, onClick: () -> Unit) {
        val value = Ui.text(context, valueText, 24f, pal.text).apply {
            gravity = Gravity.CENTER
            setPadding(28, 16, 28, 16)
            background = Ui.round(Skin.GLASS_FILL, com.cardesk.launcher.core.Shape.PILL, 1f, Skin.CHIP_STROKE)
            setOnClickListener { onClick() }
        }
        row(value, label)
    }

    /**
     * 一行滑块。
     *
     * @param step 步长（例如 30 表示 30 分钟一档）
     * @param format 自定义读数格式（例如把分钟数显示成 "06:00"）
     */
    private fun sliderRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        unit: String = "",
        step: Int = 1,
        format: ((Int) -> String)? = null,
        onChange: (Int) -> Unit,
    ) {
        val holder = FrameLayout(context)
        val caption = Ui.text(context, label, 22f, pal.textDim).apply { gravity = Gravity.CENTER_VERTICAL }
        val readout = Ui.text(context, format?.invoke(value) ?: "$value$unit", 22f, pal.text).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        holder.addView(caption, FrameLayout.LayoutParams(260, -1).also { it.leftMargin = 18 })
        val bar = SeekBar(context).apply {
            this.max = ((max - min) / step).coerceAtLeast(0)
            progress = ((value - min) / step).coerceIn(0, this.max)
            progressTintList = android.content.res.ColorStateList.valueOf(pal.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFF2FFFFFF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress * step + min
                    readout.text = format?.invoke(v) ?: "$v$unit"
                    if (fromUser) {
                        onChange(v)
                        onApply?.invoke()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        holder.addView(bar, FrameLayout.LayoutParams(420, 64, Gravity.CENTER_VERTICAL or Gravity.END).also {
            it.rightMargin = 150
        })
        holder.addView(readout, FrameLayout.LayoutParams(130, -1, Gravity.CENTER_VERTICAL or Gravity.END).also {
            it.rightMargin = 18
        })
        body.addView(holder, LinearLayout.LayoutParams(-1, 96))
        val line = View(context).apply { setBackgroundColor(Skin.NAV_STROKE) }
        body.addView(line, LinearLayout.LayoutParams(-1, 1))
    }

    private fun bodyField(view: android.view.View, height: Int = 60) {
        body.addView(view, LinearLayout.LayoutParams(-1, height).also { it.topMargin = 10 })
    }

    private fun actionButton(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView {
        val color = when {
            danger -> pal.danger
            primary -> pal.accent
            else -> pal.textDim
        }
        // 第五十一轮：实底按钮上的文字用 `--accentInk`（日间白 / 夜间深青），
        // 之前写死夜间的 `0xFF08101C`，日间在青绿实底上几乎看不清。
        val tv = Ui.text(context, text, 19f, if (primary || danger) pal.accentInk else color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(30, 14, 30, 14)
            background = Ui.round(
                when {
                    danger -> pal.danger
                    primary -> pal.accent
                    else -> Skin.CHIP_FILL
                },
                com.cardesk.launcher.core.Shape.PILL,
                if (primary || danger) 0f else 1f,
                Skin.CHIP_STROKE,
            )
            setOnClickListener { onClick() }
        }
        body.addView(tv, LinearLayout.LayoutParams(-2, 64).also {
            it.topMargin = 18
        })
        return tv
    }

    private fun readInt(key: String, def: Int) = prefs.intValue(key, def)

    private fun writeInt(key: String, v: Int) {
        prefs.setIntValue(key, v)
        onApply?.invoke()
    }

    /**
     * 记录一次设置变更。
     *
     * 排障场景：真机上「设置改了没生效」—— 用 `key` + `name` + `from` + `to`
     * 就能确认到底写进了哪个偏好键、旧值和新值是什么，落盘日志里可直接 `grep key=xxx`。
     *
     * @param tag 按键增强相关的用 [LogTags.KEYS]，其余用 [LogTags.SETTING]。
     */
    private fun logSetting(
        key: String,
        name: String,
        from: Any?,
        to: Any?,
        tag: String = LogTags.SETTING,
        vararg extra: Pair<String, Any?>,
    ) {
        val fields = arrayOf<Pair<String, Any?>>(
            "key" to key,
            "name" to name,
            "from" to from,
            "to" to to,
        ) + extra
        Logx.i(tag, "设置变更", *fields)
    }

    /**
     * 记录一次设置变更（滑杆/拖动类高频操作）。
     *
     * 高频刷新的设置项不能打 INFO（会刷爆车机存储），这里只进 Logcat。
     */
    private fun logSettingDebug(key: String, name: String, from: Any?, to: Any?) {
        Logx.d(LogTags.SETTING, "设置变更", "key" to key, "name" to name, "from" to from, "to" to to)
    }

    // ------------------------------------------------------------- 各页

    /** 启动切换。 */
    private fun pageBoot() {
        switchRow("自动导航投屏", prefs.startNaviCastEnabled, "开机后将导航投送到仪表屏") {
            val from = prefs.startNaviCastEnabled
            prefs.startNaviCastEnabled = it
            logSetting("start_navi_cast_enabled", "自动导航投屏", from, it)
        }
        switchRow("自动切换驾驶模式", prefs.startSwitchEnabled) {
            val from = prefs.startSwitchEnabled
            prefs.startSwitchEnabled = it
            logSetting("start_switch_enabled", "自动切换驾驶模式", from, it)
        }
        // 「目标驾驶模式」：标签一行 + 分段控件一行（设计板 (48, y) / (48, y+40)）
        body.addView(
            Ui.text(context, "目标驾驶模式", 24f, pal.textDim),
            LinearLayout.LayoutParams(-1, 29),
        )
        val seg = buildSegment(
            DriveMode.UI_MODE_LABELS.toList(),
            driveModeIndex(prefs.startSwitchMode),
        ) { idx ->
            val from = prefs.startSwitchMode
            val to = DriveMode.UI_MODE_VALUES[idx]
            prefs.startSwitchMode = to
            logSetting("start_switch_mode", "目标驾驶模式", from, to)
        }
        // 驾驶模式有 11 档（设计板只画了前 5 档），放横向滚动里，别把内容撑破。
        val segScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        segScroll.addView(seg)
        body.addView(segScroll, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 11 })
        // 说明文字：18px 弱文字、宽 900、行高 1.6（设计板 (48, y+140)）
        body.addView(
            Ui.text(
                context,
                "「启动切换」只包含导航投屏与驾驶模式两项；桌面自启在「桌面自启」页，" +
                    "开机自动打开 ADB 在「车控 → ADB 调试」页。",
                18f,
                pal.textFaint,
            ).apply { setLineSpacing(11f, 1f) },
            LinearLayout.LayoutParams(900, -2).also { it.topMargin = 48 },
        )
    }

    /** 方控仪表（case "cluster"）。 */
    private fun pageCluster() {
        switchRow("桥接开关", prefs.flag(KEY_BRIDGE_ENABLED), "连接车机仪表与方控通道") {
            val from = prefs.flag(KEY_BRIDGE_ENABLED)
            prefs.setFlag(KEY_BRIDGE_ENABLED, it)
            logSetting(KEY_BRIDGE_ENABLED, "方控桥接开关", from, it)
        }
        note(
            "本机没有车机 SDK 的 SessionBridge（需要系统权限）；" +
                "这里只保存开关位，实际桥接由车辆通道模块负责。"
        )
    }

    /** 按键增强。 */
    private fun pageKeys() {
        switchRow("启用按键增强", prefs.keysEnhanced, "为方向盘按键绑定额外动作") {
            val from = prefs.keysEnhanced
            prefs.keysEnhanced = it
            logSetting("custom_keys_enabled", "启用按键增强", from, it, LogTags.KEYS)
        }
        if (!prefs.keysEnhanced) {
            note("开启后可为语音键与媒体长按绑定动作。")
            return
        }
        section("语音助手 / * 键")
        keySlot("单击", prefs.voiceClickAction, { prefs.voiceClickAction = it }, prefs.voiceClickMode) {
            prefs.voiceClickMode = it
        }
        keySlot("双击", prefs.voiceDoubleAction, { prefs.voiceDoubleAction = it }, prefs.voiceDoubleMode) {
            prefs.voiceDoubleMode = it
        }
        keySlot("长按", prefs.voiceLongAction, { prefs.voiceLongAction = it }, prefs.voiceLongMode) {
            prefs.voiceLongMode = it
        }
        section("媒体按键（长按）")
        keySlot("上一曲", prefs.mediaPrevLongAction, { prefs.mediaPrevLongAction = it }, prefs.mediaPrevLongMode) {
            prefs.mediaPrevLongMode = it
        }
        keySlot("下一曲", prefs.mediaNextLongAction, { prefs.mediaNextLongAction = it }, prefs.mediaNextLongMode) {
            prefs.mediaNextLongMode = it
        }
        actionButton("打开按键增强面板…") { onShowKeys?.invoke() }
    }

    /**
     * 一个键位
     * 一行 = 键位名 + 动作选择；**只有动作是「切换驾驶模式」时**才追加「　目标驾驶模式」一行。
     */
    private fun keySlot(
        label: String,
        action: Int,
        onAction: (Int) -> Unit,
        mode: Int,
        onMode: (Int) -> Unit,
    ) {
        chipRow(label, KEY_ACTION_LABELS, KEY_ACTION_VALUES.indexOf(action).takeIf { it >= 0 } ?: 0) { idx ->
            val from = action
            val to = KEY_ACTION_VALUES[idx]
            onAction(to)
            // 排障场景：方向盘按键改绑后没反应 —— 记下键名与改绑前后的动作
            logSetting(
                keyActionKey(label),
                label,
                keyActionName(from),
                keyActionName(to),
                LogTags.KEYS,
                "fromValue" to from,
                "toValue" to to,
            )
        }
        if (action == Prefs.ACTION_DRIVE_MODE) {
            chipRow("　目标驾驶模式", DriveMode.UI_MODE_LABELS.toList(), driveModeIndex(mode)) { idx ->
                val from = mode
                val to = DriveMode.UI_MODE_VALUES[idx]
                onMode(to)
                // 排障场景：切换驾驶模式没切过去 —— 记下目标驾驶模式
                logSetting(
                    keyModeKey(label),
                    "$label·目标驾驶模式",
                    keyModeName(from),
                    keyModeName(to),
                    LogTags.KEYS,
                    "fromValue" to from,
                    "toValue" to to,
                )
            }
        }
    }

    /** 按键动作值 → 中文名（日志用，认不出来就回显原值）。 */
    private fun keyActionName(value: Int): String {
        val index = KEY_ACTION_VALUES.indexOf(value)
        return if (index >= 0) KEY_ACTION_LABELS[index] else value.toString()
    }

    /** 驾驶模式值 → 中文名（日志用，认不出来就回显原值）。 */
    private fun keyModeName(value: Int): String {
        val index = DriveMode.UI_MODE_VALUES.indexOf(value)
        return if (index >= 0) DriveMode.UI_MODE_LABELS[index] else value.toString()
    }

    /** 键位名 → 持久化键名（按键动作），日志用。 */
    private fun keyActionKey(label: String): String = when (label) {
        "单击" -> "voice_click_action"
        "双击" -> "voice_double_action"
        "长按" -> "voice_long_action"
        "上一曲" -> "media_prev_long_action"
        "下一曲" -> "media_next_long_action"
        else -> "key_action_$label"
    }

    /** 键位名 → 持久化键名（目标驾驶模式），日志用。 */
    private fun keyModeKey(label: String): String = when (label) {
        "单击" -> "voice_click_mode"
        "双击" -> "voice_double_mode"
        "长按" -> "voice_long_mode"
        "上一曲" -> "media_prev_long_mode"
        "下一曲" -> "media_next_long_mode"
        else -> "key_mode_$label"
    }

    /** 取值 → 驾驶模式清单下标（认不出来一律落到「经济模式」）。 */
    private fun driveModeIndex(value: Int): Int {
        val index = DriveMode.UI_MODE_VALUES.indexOf(value)
        return if (index >= 0) index else 0
    }

    /** 计划任务。 */
    private fun pageTasks() {
        // —— 标题行右侧是「+ 添加」
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(context, "计划任务", 18f, pal.textDim), Ui.linear(0, -2, 1f))
        header.addView(
            Ui.text(context, "+ 添加", 18f, pal.accent).apply {
                gravity = Gravity.CENTER
                setPadding(16, 10, 16, 10)
                setOnClickListener {
                    // 排障场景：计划任务面板没打开 —— 记下这次点击
                    Logx.event(LogTags.TASK, "从设置页打开计划任务面板")
                    onShowTasks?.invoke()
                }
            },
            Ui.linear(-2, -2),
        )
        body.addView(header, Ui.linear(-1, 44, 0f, 0, 12))

        val tasks = ScheduledTasks.load(prefs)
        // 排障场景：设置页里的任务列表为空 / 数量不对
        Logx.i(LogTags.TASK, "计划任务页加载", "count" to tasks.size)
        if (tasks.isEmpty()) {
            note("开机或进桌面后自动打开某个 App\n例如：开机 8 秒后打开导航")
        }
        for (task in tasks) {
            row(
                Ui.text(context, "删除", 18f, pal.danger).apply {
                    gravity = Gravity.CENTER
                    setPadding(16, 10, 16, 10)
                    background = Ui.round(Skin.CHIP_FILL, 12f)
                    setOnClickListener {
                        val list = ScheduledTasks.load(prefs)
                        list.removeAll { it.id == task.id }
                        ScheduledTasks.save(prefs, list)
                        cancelTask(task.id)
                        // 排障场景：任务删了还在跑 —— 记下删除与取消调度
                        Logx.event(
                            LogTags.TASK,
                            "从设置页删除计划任务",
                            "id" to task.id,
                            "condition" to task.condition,
                            "target" to task.target,
                            "remaining" to list.size,
                        )
                        select(selectedKey)
                    }
                },
                appLabelOf(task.target) ?: ScheduledTasks.actionLabel(task),
                ScheduledTasks.conditionLabel(task),
            )
        }
        note("延迟上限 ${ScheduledTasks.DELAY_MAX} 秒；「进入桌面后」的任务在桌面进程内延迟执行，不走闹钟。")
    }

    private fun cancelTask(taskId: String) {
        runCatching { com.cardesk.launcher.core.TaskScheduler.cancel(context, taskId) }
            .onFailure {
                // 排障场景：任务删了但闹钟没取消掉
                Logx.w(LogTags.TASK, "取消任务调度失败", it, "id" to taskId)
            }
    }

    private fun appLabelOf(pkg: String): String? {
        if (pkg.isBlank()) return null
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    /** 桌面自启（case "autostart"）。 */
    private fun pageAutostart() {
        switchRow("桌面开机启动", prefs.bootAutostart, "开机后进入本桌面") {
            val from = prefs.bootAutostart
            prefs.bootAutostart = it
            logSetting("desktop_boot_autostart", "桌面开机启动", from, it)
        }
        note("对应 BootReceiver（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED）；正式系统应用还会把自身写进 priv-app 并停用原生 launcher。")
    }

    /** 日夜模式。 */
    private fun pageDayNight() {
        switchRow("启用日夜模式同步", prefs.dayNightEnabled, "系统、地图与桌面主题保持一致") {
            val from = prefs.dayNightEnabled
            prefs.dayNightEnabled = it
            logSetting("daynight_enabled", "启用日夜模式同步", from, it)
        }
        cardChoice("跟随高德", "随导航日夜切换", prefs.dayNightMode == DayNight.MODE_FOLLOW_AMAP) {
            val from = prefs.dayNightMode
            prefs.dayNightMode = DayNight.MODE_FOLLOW_AMAP
            logSetting("daynight_mode", "日夜模式来源·跟随高德", from, DayNight.MODE_FOLLOW_AMAP)
        }
        cardChoice("跟随光感", "依据环境光传感器", prefs.dayNightMode == DayNight.MODE_FOLLOW_SENSOR) {
            val from = prefs.dayNightMode
            prefs.dayNightMode = DayNight.MODE_FOLLOW_SENSOR
            logSetting("daynight_mode", "日夜模式来源·跟随光感", from, DayNight.MODE_FOLLOW_SENSOR)
        }
        cardChoice("跟随时间", "日出日落自动切换", prefs.dayNightMode == DayNight.MODE_FOLLOW_TIME) {
            val from = prefs.dayNightMode
            prefs.dayNightMode = DayNight.MODE_FOLLOW_TIME
            logSetting("daynight_mode", "日夜模式来源·跟随时间", from, DayNight.MODE_FOLLOW_TIME)
        }
        if (prefs.dayNightMode == DayNight.MODE_FOLLOW_TIME) {
            section("切换时间")
            timePickerRow("白天开始", prefs.dayStartMin) { prefs.dayStartMin = it }
            timePickerRow("夜间开始", prefs.nightStartMin) { prefs.nightStartMin = it }
        }
        val night = pal.night
        note(
            "当前：" + (if (night) "夜间（深色）" else "日间（浅色）") + " · " +
                (if (prefs.dayNightEnabled) "已启用同步" else "未启用同步"),
        )
        actionButton("立即切换") { onApply?.invoke() }
        actionButton("自定义主题色…") { onShowAccent?.invoke() }
    }

    /**
     * 一张「卡片选项」：标题 + 副标题，选中时高亮。
     */
    private fun cardChoice(title: String, subtitle: String, selected: Boolean, onPick: () -> Unit) {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(
                if (selected) pal.accentSoft else Skin.GLASS_FILL,
                20f,
                1f,
                if (selected) pal.accent else Skin.CHIP_STROKE,
            )
            setPadding(24, 14, 24, 14)
            setOnClickListener {
                onPick()
                onApply?.invoke()
                select(selectedKey)
            }
        }
        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textBox.addView(
            Ui.text(context, title, 23f, if (selected) pal.accent else pal.text),
            LinearLayout.LayoutParams(-1, -2),
        )
        textBox.addView(
            Ui.text(context, subtitle, 17f, pal.textDim),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 4 },
        )
        holder.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        holder.addView(
            Ui.text(context, if (selected) "●" else "○", 22f, if (selected) pal.accent else pal.textFaint).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(48, -2),
        )
        body.addView(holder, LinearLayout.LayoutParams(-1, 80).also { it.bottomMargin = 8; it.topMargin = 6 })
    }

    /** 时/分双滚轮。 */
    private fun timePickerRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
        val value = Ui.text(context, DayNight.formatMinute(minutes), 20f, pal.text).apply {
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            background = Ui.round(Skin.GLASS_FILL, 20f, 1f, Skin.CHIP_STROKE)
            setOnClickListener { showTimePicker(label, minutes, onChange) }
        }
        row(value, label)
    }

    private fun showTimePicker(title: String, minutes: Int, onChange: (Int) -> Unit) {
        // 排障场景：日夜切换时间改不了 —— 记下打开了哪个时间选择器
        Logx.event(LogTags.SETTING, "打开时间选择器", "title" to title, "current" to minutes)
        val overlay = FrameLayout(context)
        val scrim = View(context).apply {
            // 第五十一轮：遮罩跟随主题，不再写死 60% 黑。
            setBackgroundColor(Skin.SCRIM)
            isClickable = true
            setOnClickListener { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        }
        overlay.addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.glass(28f, true, Skin.SHEET_FILL)
            setPadding(28, 24, 28, 28)
        }
        overlay.addView(sheet, LayoutParams(520, -2, Gravity.CENTER))
        sheet.addView(Ui.text(context, title, 26f, pal.text, bold = true), LinearLayout.LayoutParams(-1, 44))
        sheet.addView(
            Ui.text(context, "24 小时制 · 点按箭头调整", 16f, pal.textFaint),
            LinearLayout.LayoutParams(-1, 34),
        )
        val wheels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val hourPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 23
            value = minutes / 60
            wrapSelectorWheel = false
        }
        val minutePicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = minutes % 60
            wrapSelectorWheel = false
        }
        wheels.addView(hourPicker, LinearLayout.LayoutParams(0, 180, 1f))
        wheels.addView(
            Ui.text(context, ":", 48f, pal.text, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(72, 180),
        )
        wheels.addView(minutePicker, LinearLayout.LayoutParams(0, 180, 1f))
        sheet.addView(wheels, LinearLayout.LayoutParams(-1, 200).also { it.topMargin = 8 })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(
            Ui.text(context, "取消", 20f, pal.textDim).apply {
                gravity = Gravity.CENTER
                background = Ui.round(Skin.CHIP_FILL, 22f, 1f, Skin.CHIP_STROKE)
                setOnClickListener {
                    // 排障场景：时间选择器被取消 —— 记下没改
                    Logx.event(LogTags.SETTING, "取消时间选择器", "title" to title)
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                }
            },
            LinearLayout.LayoutParams(0, 60, 1f).also { it.rightMargin = 12 },
        )
        buttons.addView(
            Ui.text(context, "确认", 20f, pal.accentInk, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(pal.accent, 22f)
                setOnClickListener {
                    val picked = DayNight.minuteOfDay(hourPicker.value, minutePicker.value)
                    val from = minutes
                    onChange(picked)
                    // 排障场景：日夜切换时间改了没生效 —— 记下改前改后的分钟数
                    logSetting(
                        timePickerKey(title),
                        title,
                        from,
                        picked,
                    )
                    Logx.event(
                        LogTags.SETTING,
                        "确认时间选择器",
                        "title" to title,
                        "from" to from,
                        "to" to picked,
                        "text" to DayNight.formatMinute(picked),
                    )
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    onApply?.invoke()
                    select(selectedKey)
                }
            },
            LinearLayout.LayoutParams(0, 60, 1f),
        )
        sheet.addView(buttons, LinearLayout.LayoutParams(-1, 70).also { it.topMargin = 8 })
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 时间选择器标题 → 持久化键名（日志用）。 */
    private fun timePickerKey(title: String): String = when (title) {
        "白天开始" -> "daynight_day_start_min"
        "夜间开始" -> "daynight_night_start_min"
        else -> "daynight_time_$title"
    }

    /**
     * 壁纸设置（第五十七轮新增页）。
     *
     * 第四十九轮「浮屿」把壁纸压成了纯色底，用户明确要求
     * 「默认壁纸不要用纯色的，用风景、拟物类的」——
     * 这一页就是挑风景的入口：上面一张大预览，下面 9 张缩略图（点一下立刻换）。
     *
     * 缩略图画的是**和桌面上完全一样**的那张壁纸（同一个渲染器、同一套日夜配色），
     * 所以「缩略图里长什么样，桌面上就是什么样」。
     */
    private fun pageWallpaper() {
        val current = com.cardesk.launcher.core.Wallpapers.normalize(prefs.wallpaper)

        section("当前壁纸")
        val previewHolder = FrameLayout(context)
        val preview = WallpaperThumbView(
            context,
            com.cardesk.launcher.core.Wallpapers.spec(current),
            pal.night,
            pal.accent,
        ).apply { chosen = true }
        previewHolder.addView(preview, FrameLayout.LayoutParams(620, 349, Gravity.CENTER))
        body.addView(previewHolder, LinearLayout.LayoutParams(-1, 349).also { it.topMargin = 10 })

        val caption = Ui.text(context, "", 22f, pal.textDim)
        body.addView(caption, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 14 })

        section("预设壁纸")
        val thumbs = ArrayList<WallpaperThumbView>()
        val labels = ArrayList<TextView>()
        for (r in 0 until 3) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val index = r * 3 + c
                val spec = com.cardesk.launcher.core.Wallpapers.spec(index)
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val thumb = WallpaperThumbView(context, spec, pal.night, pal.accent)
                val label = Ui.text(context, spec.name, 20f, pal.textDim).apply {
                    gravity = Gravity.CENTER
                }
                cell.addView(thumb, LinearLayout.LayoutParams(-1, 172))
                cell.addView(label, LinearLayout.LayoutParams(-1, 36))
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).also { if (c > 0) it.leftMargin = 24 })
                thumbs.add(thumb)
                labels.add(label)
            }
            body.addView(line, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = if (r == 0) 12 else 22 })
        }

        /** 刷新「当前壁纸」预览 + 选中态。 */
        fun refresh(index: Int) {
            val spec = com.cardesk.launcher.core.Wallpapers.spec(index)
            preview.spec = spec
            caption.text = "当前：${spec.name} · ${spec.desc}"
            thumbs.forEachIndexed { i, t ->
                val on = i == index
                t.chosen = on
                labels[i].setTextColor(if (on) pal.accent else pal.textDim)
                labels[i].typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        refresh(current)

        thumbs.forEachIndexed { index, thumb ->
            thumb.setOnClickListener {
                val from = prefs.wallpaper
                val next = com.cardesk.launcher.core.Wallpapers.normalize(index)
                if (next == from) return@setOnClickListener
                prefs.wallpaper = next
                logSetting("design_wallpaper_index", "壁纸", from, next, LogTags.SETTING, "name" to com.cardesk.launcher.core.Wallpapers.name(next))
                refresh(next)
                // 立刻让桌面换上 —— 用户在设置页里就能看到效果。
                onWallpaperChange?.invoke(next)
            }
        }

        section("说明")
        note(
            "共 ${com.cardesk.launcher.core.Wallpapers.COUNT} 张预置壁纸，全部是风景类，" +
                "默认「${com.cardesk.launcher.core.Wallpapers.name(com.cardesk.launcher.core.Wallpapers.DEFAULT_INDEX)}」。",
        )
        note("所有壁纸都是程序化绘制的（不占用额外资源），日夜模式各有一套配色。")
        note("桌面下方提示条上的圆点也能直接切换壁纸。")
    }

    /**
     * 卡片风格（第五十八轮新增页）：**透明度 / 颜色 / 圆角**。
     *
     * 这一页改的是**全局 token**：
     * - **透明度** → 卡面 + 卡内两层一起变透明（壁纸透得出来）；
     * - **颜色** → 换掉卡面色，卡内两层按「浮屿」的明度差规则派生；
     * - **圆角** → 卡片圆角可调，内嵌块 / 图标底**等比缩放**。
     *
     * 改动**即时生效**：设置页自己刷新预览，同时通知桌面重算 token 并刷新画面。
     */
    private fun pageCardStyle() {
        section("预览")
        val preview = CardStylePreviewView(context)
        body.addView(preview, LinearLayout.LayoutParams(-1, 260).also { it.topMargin = 10 })

        /** 把当前偏好算成生效的配色 + 圆角：刷新预览，并通知桌面全局生效。 */
        fun push() {
            val effective = com.cardesk.launcher.core.CardStyle.apply(
                pal,
                prefs.cardColor,
                prefs.cardOpacity,
                prefs.cardRadius,
            )
            preview.palette = effective
            preview.radius = com.cardesk.launcher.core.CardStyle.clampRadius(prefs.cardRadius).toFloat()
            preview.wallpaperIndex = prefs.wallpaper
            preview.refresh()
            onCardStyleChange?.invoke()
        }
        push()

        note("下面是当前壁纸上的样例卡片：卡片越透明，壁纸透得越多。")

        sliderRow(
            "卡片透明度",
            prefs.cardOpacity,
            com.cardesk.launcher.core.CardStyle.MIN_OPACITY,
            com.cardesk.launcher.core.CardStyle.MAX_OPACITY,
            "%",
            5,
        ) { v ->
            val from = prefs.cardOpacity
            prefs.cardOpacity = v
            logSetting("card_opacity", "卡片透明度", from, v)
            push()
        }
        note("卡内两块（选中槽 / 内嵌块）会跟着一起透明。")

        pickerRow("卡片颜色", cardColorLabel()) { onShowCardColor?.invoke() }
        actionButton("恢复跟随主题颜色") {
            val from = prefs.cardColor
            prefs.cardColor = 0
            logSetting("card_color", "卡片颜色", from, 0)
            push()
        }
        note("自选颜色后，卡内两层会按主题的明度差重新派生，保证层级关系还在。")

        sliderRow(
            "卡片圆角",
            prefs.cardRadius,
            com.cardesk.launcher.core.CardStyle.MIN_RADIUS,
            com.cardesk.launcher.core.CardStyle.MAX_RADIUS,
            " px",
            2,
        ) { v ->
            val from = prefs.cardRadius
            prefs.cardRadius = v
            logSetting("card_radius", "卡片圆角", from, v)
            push()
        }
        note("内嵌块与图标底会按 28/18/16 的比例等比缩放，层级关系不变。")

        section("说明")
        note("卡片风格只影响卡片面（首页卡片、抽屉、弹层、设置面板），不影响强调色与文字颜色。")
    }

    /** 卡片颜色的当前取值文案。 */
    private fun cardColorLabel(): String {
        val c = prefs.cardColor
        return if (c == 0) "跟随主题" else String.format("#%06X", c and 0xFFFFFF)
    }

    /** 双画中画。 */
    private fun pageDualPip() {
        switchRow("双画中画", prefs.dualPip, "第二页右侧胎压和音乐卡片替换为第二画中画") {
            val from = prefs.dualPip
            //
            // 开双画时主副画不能是同一个应用，否则拒绝并提示。
            if (it && !prefs.pipApp.isBlank() && prefs.pipApp == prefs.pip2App) {
                Toast.makeText(context, "双画中画请选择不同应用", Toast.LENGTH_SHORT).show()
                Logx.w(
                    LogTags.SETTING,
                    "开启双画中画被拒：主副画是同一个应用",
                    "pkg" to prefs.pipApp,
                )
                return@switchRow
            }
            prefs.dualPip = it
            logSetting("desk_dual_pip", "双画中画", from, it)
        }
        //
        // 两行分别是「一画显示应用」「二画显示应用」，右侧显示**应用名**（不是包名），
        // 没设置时显示「未设置」。
        pickerRow("一画显示应用", pipPickerValue(prefs.pipApp)) {
            // 排障场景：画中画应用选不了 / 选错 —— 记下打开了选择器
            Logx.event(LogTags.SETTING, "打开画中画应用选择器", "current" to prefs.pipApp, "secondary" to false)
            onShowPipPicker?.invoke(false)
        }
        pickerRow("二画显示应用", pipPickerValue(prefs.pip2App)) {
            Logx.event(LogTags.SETTING, "打开画中画应用选择器", "current" to prefs.pip2App, "secondary" to true)
            onShowPipPicker?.invoke(true)
        }
        note(
            "双画中画 = 导航页左侧主画、右侧副画，各跑一个应用（主画 pip_app / 副画 pip2_app）。\n" +
                "副画承载需要系统权限（ActivityView 是系统 API），非系统签名时副画会显示为应用卡片。",
        )
    }

    /**
     * 画中画选择器右侧的当前值
     * 显示**应用名**，没设置时显示「未设置」。
     */
    private fun pipPickerValue(pkg: String): String =
        if (pkg.isBlank()) "未设置" else (appLabelOf(pkg) ?: pkg)

    /**
     * Dock 快捷应用（第五十四轮）。
     *
     * Dock 最右边是一段快捷应用槽位（默认 3 个），这里进配置面板挑应用；
     * 也可以直接**长按 Dock 上的某个槽位**进同一个面板。
     */
    private fun pageDockQuickApps() {
        section("Dock 快捷应用")
        val assigned = prefs.dockQuickApps.filter { it.isNotEmpty() }
        kvRow(
            "已配置",
            if (assigned.isEmpty()) "未配置" else "${assigned.size} / ${com.cardesk.launcher.core.DockQuickApps.SLOTS} 个",
            assigned.isNotEmpty(),
        )
        note("选中的应用会固定在 Dock 最右侧；没配的槽位显示「+」，点它就能挑应用。")
        actionButton("配置 Dock 快捷应用…", primary = true) {
            Logx.event(
                LogTags.SETTING,
                "打开 Dock 快捷应用配置",
                "current" to prefs.dockQuickApps.joinToString(","),
            )
            onShowDockQuickApps?.invoke()
        }
    }

    /** 360 环影。 */
    private fun pageCamera360() {
        switchRow("启用 360 环影增强", prefs.camera360Enabled, "按键与档位联动环影视角") {
            val from = prefs.camera360Enabled
            prefs.camera360Enabled = it
            logSetting("camera360_enabled_v2", "启用 360 环影增强", from, it)
        }
        if (!prefs.camera360Enabled) {
            note("开启后可配置按键与档位对应视角。")
            return
        }
        chipRow("视角延迟", CAMERA360_DELAY_LABELS, CAMERA360_DELAY_MS.indexOf(prefs.camera360DelayMs)) { idx ->
            val from = prefs.camera360DelayMs
            val to = CAMERA360_DELAY_MS[idx]
            prefs.camera360DelayMs = to
            logSetting("camera360_view_delay_ms", "视角延迟", from, to)
        }
        chipRow("按键视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360KeyView)) { idx ->
            val from = prefs.camera360KeyView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360KeyView = to
            logSetting("camera360_key_view_v2", "按键视角", from, to)
        }
        chipRow("倒车视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360ReverseView)) { idx ->
            val from = prefs.camera360ReverseView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360ReverseView = to
            logSetting("camera360_reverse_view_v2", "倒车视角", from, to)
        }
        chipRow("R→D 视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360RdView)) { idx ->
            val from = prefs.camera360RdView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360RdView = to
            logSetting("camera360_rd_view_v2", "R→D 视角", from, to)
        }
        chipRow("D 档视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360DView)) { idx ->
            val from = prefs.camera360DView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360DView = to
            logSetting("camera360_d_view_v2", "D 档视角", from, to)
        }
        chipRow("转向灯视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360LampView)) { idx ->
            val from = prefs.camera360LampView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360LampView = to
            logSetting("camera360_lamp_view_v2", "转向灯视角", from, to)
        }
        chipRow("N/P 关闭环影", listOf("无", "关"), if (prefs.camera360NpClose) 1 else 0) {
            val from = prefs.camera360NpClose
            val to = it == 1
            prefs.camera360NpClose = to
            logSetting("camera360_np_close_v2", "N/P 关闭环影", from, to)
        }
        section("通道")
        note(
            "环影走三条通道（按优先级回退）：\n" +
                "1) AdaptAPI 0x23030100（setFunctionValue）\n" +
                "2) 反射 EcarxAVMManager.CB_PAC_Activation / CB_PAC_ViewSelection\n" +
                "3) 反射 ECarXCar → ParkassiManager\n" +
                "三条通道「非短路 |」双写并回读校验；820A 与 E03 平台视角编码顺序相反。"
        )
    }

    /** 启停控制（case "autostop"）。 */
    private fun pageAutostop() {
        switchRow("开机后自动关闭启停", prefs.issKeepEnabled, "开启后保持 ISS 关闭；关闭则不改动车机逻辑") {
            val from = prefs.issKeepEnabled
            prefs.issKeepEnabled = it
            logSetting("iss_keep_enabled", "开机后自动关闭启停", from, it)
        }
        note("开机后自动关闭启停的状态会保存到 iss_keep_enabled 配置项。")
    }

    /** 自动热点（case "hotspot"）。 */
    private fun pageHotspot() {
        switchRow("自动热点", ScheduledTasks.isHotspotAutoEnabled(prefs), "开机后自动打开车机热点") {
            val from = ScheduledTasks.isHotspotAutoEnabled(prefs)
            ScheduledTasks.setHotspotAutoEnabled(prefs, it)
            logSetting(ScheduledTasks.KEY_HOTSPOT_AUTO, "自动热点", from, it)
        }
        note("自动热点的开关状态保存在 hotspot_boot_auto 配置项。")
    }

    /**
     * ADB 调试页（第五十五轮）。
     *
     * 原来这一页只有一个「自动 ADB」开关（写 `adb_enabled` 一个键）。
     * 这一轮把「领克 05 调试工具 2.0」逆出来的**完整无线 ADB 链路**整合进来
     *
     * - **状态**：身份 / 开发者选项 / USB 调试 / 开发者页面 / ADB 服务 /
     *   无线端口 / 本机 5555 / 本机 IPv4 / 恢复记录 / 前置检查；
     * - **自动开关**：开机自动开调试开关、开机自动开无线端口；
     * - **操作**：开启 USB 调试 / 开启无线 ADB(5555) / 关闭无线并恢复端口 /
     *   恢复 USB 接口 / 恢复调试开关 / **恢复全部设置** / 打开原生开发者选项；
     * - **诊断**：属性清单 + USB 功能 + SELinux 上下文 + 崩溃日志，可复制。
     *
     * 「恢复全部设置」是**安全出口**：无线 ADB 开着的时候同网段任何设备都能连上车机，
     * 所以它必须显眼、好找，而且失败要如实报。
     */
    private fun pageAdb() {
        val runtime = com.cardesk.launcher.core.AdbBridge.AdbRuntime
        val controller = runtime.controller(context)
        var state = runtime.state(context)
        var support = runtime.support(context)

        // 状态行绑定：操作完成后**原地刷新**取值，不重建页面（免得滚动位置被重置）。
        val bindings = ArrayList<Pair<TextView, () -> Pair<String, Boolean>>>()

        fun live(label: String, provider: () -> Pair<String, Boolean>) {
            val (value, ok) = provider()
            bindings += kvRow(label, value, ok) to provider
        }

        fun refreshState() {
            state = runtime.state(context)
            support = runtime.support(context)
            for ((view, provider) in bindings) {
                val (value, ok) = provider()
                view.text = value
                view.setTextColor(if (ok) pal.ok else pal.textDim)
            }
        }

        section("当前状态")
        live("身份") {
            val identity = "uid ${state.uid}" + if (state.platformSigned) " · 平台签名" else " · 非平台签名"
            identity to (state.uid == 1000)
        }
        live("开发者选项") { AdbDiagnostics.settingLabel(state.devSetting) to (state.devSetting == "1") }
        live("USB 调试") { AdbDiagnostics.settingLabel(state.adbSetting) to (state.adbSetting == "1") }
        live("开发者页面") {
            AdbDiagnostics.componentLabel(state.componentState) to state.componentEnabled
        }
        live("ADB 服务") {
            (if (state.adbdService.isEmpty()) "<空>" else state.adbdService) to (state.adbdService == "running")
        }
        live("无线端口") {
            (if (state.wirelessPort.isEmpty()) "未配置" else state.wirelessPort) to
                (state.wirelessPort == AdbDebug.WIRELESS_PORT)
        }
        live("本机 5555") { (if (state.portOpen) "接受连接" else "未接受连接") to state.portOpen }
        live("本机 IPv4") { state.localIp to true }
        live("恢复记录") {
            state.restoreSummary to (state.hasDebugSnapshot || state.hasUsbSnapshot || state.hasPortSnapshot)
        }
        live("前置检查") { support.reason to support.ok }

        section("自动开关")
        switchRow("自动 ADB", prefs.adbAuto, "开机后自动打开调试开关") {
            val from = prefs.adbAuto
            prefs.adbAuto = it
            logSetting("adb_boot_auto", "自动 ADB", from, it)
            if (it) {
                // 立刻生效一次（开机自启由 BootReceiver 负责）
                runAdbAction("自动 ADB") { controller.enableDebug() }
            }
        }
        switchRow("无线 ADB 自启", prefs.adbWirelessAuto, "开机后自动开启 5555 端口") {
            val from = prefs.adbWirelessAuto
            prefs.adbWirelessAuto = it
            logSetting("adb_wireless_boot_auto", "无线 ADB 自启", from, it)
            if (it) {
                runAdbAction("无线 ADB 自启") { controller.enableWirelessSession() }
            }
        }
        note("没有系统签名 / 没有 WRITE_SECURE_SETTINGS 时，这两个开关只会记录状态，不会改动车机。")

        section("操作")
        actionButton("开启 USB 调试", primary = true) {
            runAdbAction("开启 USB 调试") { controller.enableUsbSession() }
        }
        actionButton("开启无线 ADB（5555）", primary = true) {
            runAdbAction("开启无线 ADB") { controller.enableWirelessSession() }
        }
        actionButton("关闭无线并恢复端口") {
            runAdbAction("关闭无线并恢复端口") { controller.restoreWirelessSession() }
        }
        actionButton("恢复 USB 接口") {
            runAdbAction("恢复 USB 接口") { controller.restoreUsb() }
        }
        actionButton("恢复调试开关") {
            runAdbAction("恢复调试开关") { controller.restoreDebug() }
        }
        actionButton("恢复全部设置", danger = true) {
            runAdbAction("恢复全部设置") { controller.restoreAll() }
        }
        actionButton("打开原生开发者选项") {
            Logx.event(LogTags.SETTING, "打开原生开发者选项")
            val ok = runtime.openDevSettings(context)
            if (!ok) {
                Logx.w(LogTags.SYS, "打开原生开发者选项失败", "component" to AdbDebug.COMPONENT_ACTIVITY)
                Toast.makeText(context, "打不开原生开发者选项页", Toast.LENGTH_SHORT).show()
            }
        }
        note("无线 ADB 开着的时候，同网段的设备都能连上车机。调试完请点「恢复全部设置」。")

        section("诊断")
        val resultView = Ui.text(context, adbLastAction, 21f, pal.text).apply {
            setLineSpacing(8f, 1f)
            setTextIsSelectable(true)
        }
        body.addView(resultView, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })
        // 诊断详情默认**收起**：整页属性清单 + 崩溃日志有几十行，
        // 摊开会把这一页糊成一堵字墙（APK 也是默认收起、点一下才展开）。
        val diagView = Ui.text(context, adbDiagnostics, 18f, pal.textDim).apply {
            setLineSpacing(6f, 1f)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            visibility = if (adbDiagnosticsExpanded) View.VISIBLE else View.GONE
        }
        body.addView(diagView, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 12 })
        actionButton(if (adbDiagnosticsExpanded) "收起诊断详情" else "展开诊断详情") {
            adbDiagnosticsExpanded = !adbDiagnosticsExpanded
            diagView.visibility = if (adbDiagnosticsExpanded) View.VISIBLE else View.GONE
            select(selectedKey)
        }
        actionButton("刷新诊断") {
            adbDiagnostics = runtime.diagnostics(context)
            diagView.text = adbDiagnostics
            refreshState()
            Toast.makeText(context, "诊断已刷新", Toast.LENGTH_SHORT).show()
        }
        actionButton("复制诊断") {
            val report = runtime.report(context, adbLastAction)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CarDesk ADB 诊断", report))
                Toast.makeText(context, "诊断已复制到剪贴板", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "拿不到剪贴板服务", Toast.LENGTH_SHORT).show()
            }
        }

        // 操作完成后回调：原地刷新状态行 + 结果文本 + 诊断文本（不重建页面，滚动位置不跳）
        adbOnResult = { outcome ->
            adbLastAction = if (outcome.detail.isEmpty()) {
                outcome.title
            } else {
                "${outcome.title}\n${outcome.detail}"
            }
            adbDiagnostics = runtime.diagnostics(context)
            resultView.text = adbLastAction
            diagView.text = adbDiagnostics
            refreshState()
        }
    }

    /**
     * 在后台线程跑一次 ADB 操作，结束后回主线程刷新状态。
     *
     * 为什么必须放后台：写系统设置、切 USB 功能位、重启 adbd、等端口，
     * 任何一步都可能卡住几百毫秒到一秒多，堵在主线程上会让整页假死。
     */
    private fun runAdbAction(label: String, block: () -> AdbOutcome) {
        Logx.event(LogTags.SETTING, "ADB 操作", "action" to label)
        Thread {
            val outcome = runCatching(block).getOrElse {
                AdbOutcome(false, "操作未完成", AdbDiagnostics.describe(it))
            }
            com.cardesk.launcher.core.AdbBridge.AdbRuntime.logOutcome(label, outcome)
            post {
                adbOnResult?.invoke(outcome)
                Toast.makeText(context, outcome.title, Toast.LENGTH_LONG).show()
            }
        }.apply {
            name = "adb-action"
            isDaemon = true
        }.start()
    }

    /** 仪表歌词 + 桌面歌词字号 + 胎压。 */
    private fun pageLayout() {
        section("壁纸")
        pickerRow("桌面壁纸", com.cardesk.launcher.core.Wallpapers.name(prefs.wallpaper)) {
            select("wallpaper")
        }
        section("桌面歌词字号")
        note("第二页音乐卡收起 / 展开；单位为设计像素。")
        sliderRow("收起·主歌词", prefs.deskCompactMainSp, 14, 64, " px") {
            val from = prefs.deskCompactMainSp
            prefs.deskCompactMainSp = it
            logSettingDebug("desk_desk_compact_main_sp", "收起·主歌词", from, it)
        }
        sliderRow("收起·副歌词", readInt("desk_desk_compact_sub_sp", 20), 10, 48, " px") {
            val from = readInt("desk_desk_compact_sub_sp", 20)
            writeInt("desk_desk_compact_sub_sp", it)
            logSettingDebug("desk_desk_compact_sub_sp", "收起·副歌词", from, it)
        }
        sliderRow("展开·主歌词", readInt("desk_desk_expanded_main_sp", 28), 14, 64, " px") {
            val from = readInt("desk_desk_expanded_main_sp", 28)
            writeInt("desk_desk_expanded_main_sp", it)
            logSettingDebug("desk_desk_expanded_main_sp", "展开·主歌词", from, it)
        }
        sliderRow("展开·副歌词", readInt("desk_desk_expanded_sub_sp", 20), 10, 48, " px") {
            val from = readInt("desk_desk_expanded_sub_sp", 20)
            writeInt("desk_desk_expanded_sub_sp", it)
            logSettingDebug("desk_desk_expanded_sub_sp", "展开·副歌词", from, it)
        }
        // 第三十二轮起桌面只有两页，`desk_desk_page3_main_sp` / `desk_desk_page3_sub_sp`
        // 这两个「第三页全屏歌词」字号已经没有对应的画面了，设置项随之移除；
        // 落盘键 `desk_desk_page3_main_sp` / `desk_desk_page3_sub_sp` 保留，将来恢复独立播放器页可直接用。
        note(
            "本页暂未实现「仪表 Overlay 歌词」（目标屏幕 / 模块宽度 / 背景透明度 / 位置 / 歌词颜色）——" +
                "它依赖车机的第二屏通道。"
        )
        section("胎压")
        sliderRow("胎压数字字号", prefs.deskTpmsValueSp, 10, 48, " px") {
            val from = prefs.deskTpmsValueSp
            prefs.deskTpmsValueSp = it
            logSettingDebug("desk_tpms_value_sp", "胎压数字字号", from, it)
        }
        // 车型编号（：键 `tpms_car_model`，默认 `03`）。
        // 01 / 02 / 02HB / 05 是 SUV 家族，03 / 03+ 是轿车家族。
        chipRow("车模编号", com.cardesk.launcher.core.CarModels.MODEL_IDS, prefs.carModel) { modelId ->
            val from = prefs.carModel
            prefs.carModel = modelId
            logSetting("tpms_car_model", "车模编号", from, modelId)
        }
        chipRow("车辆数据源", listOf("自动探测", "模拟车辆", "真车通道"), prefs.vehicleSource) { idx ->
            val from = prefs.vehicleSource
            prefs.vehicleSource = idx
            logSetting("vehicle_source", "车辆数据源", from, idx)
            onVehicleSource?.invoke()
        }
        note("车模按车型自绘，不联网下载；切换「车模编号」后立即生效。")
        actionButton("打开布局参数对话框…") { onShowLayoutDialog?.invoke() }
    }

    /** Dock 状态栏（case "dock"）+ 增强导航栏 / 槽位。 */
    private fun pageDock() {
        // 第五十六轮：这里原来只有一个「隐藏原车 Dock」开关，
        // 而我们把它**同时**用在了「隐藏原车 Dock」和「隐藏桌面自己的 Dock」上 ——
        // 于是勾上它，消失的是**桌面自己的** Dock（用户反馈「隐藏系统 Dock 栏功能实现错误」）。
        // 现在拆成三个互不相干的开关。
        switchRow("沉浸系统导航栏", prefs.oemNavImmersive, "桌面自带 Dock 平时隐藏，从底部上滑可临时唤出") {
            val from = prefs.oemNavImmersive
            prefs.oemNavImmersive = it
            logSetting("oem_nav_immersive", "沉浸系统导航栏", from, it)
        }
        switchRow("隐藏桌面 Dock", prefs.hideHomeDock, "只隐藏桌面自己画的底部控制栏") {
            val from = prefs.hideHomeDock
            prefs.hideHomeDock = it
            logSetting("home_dock_hidden", "隐藏桌面 Dock", from, it)
        }
        switchRow("隐藏原车 Dock", prefs.hideDock, "对指定应用隐藏原车 Dock（不影响桌面 Dock）") {
            val from = prefs.hideDock
            prefs.hideDock = it
            logSetting("dockbar_hide_dock", "隐藏原车 Dock", from, it)
        }
        pickerRow("Dock 生效应用", dockAppSummary(prefs.hideDockApps)) {
            // 排障场景：Dock 生效应用名单没生效 —— 记下打开选择器时的名单
            Logx.event(
                LogTags.SETTING,
                "打开 Dock 生效应用选择器",
                "current" to prefs.hideDockApps.joinToString(","),
            )
            onShowDockApps?.invoke()
        }
        actionButton("指定隐藏原车 Dock 的应用（已选 ${selectedAppCount(prefs.hideDockApps)} 个）…") {
            Logx.event(
                LogTags.SETTING,
                "打开 Dock 生效应用选择器",
                "current" to prefs.hideDockApps.joinToString(","),
            )
            onShowDockApps?.invoke()
        }
        switchRow("隐藏原车状态栏", prefs.hideStatus, "仅隐藏原车顶栏") {
            val from = prefs.hideStatus
            prefs.hideStatus = it
            logSetting("dockbar_hide_status", "隐藏原车状态栏", from, it)
        }
        pickerRow("状态栏生效应用", dockAppSummary(prefs.hideStatusApps)) {
            Logx.event(
                LogTags.SETTING,
                "打开状态栏生效应用选择器",
                "current" to prefs.hideStatusApps.joinToString(","),
            )
            onShowStatusApps?.invoke()
        }
        actionButton("指定隐藏状态栏的应用（已选 ${selectedAppCount(prefs.hideStatusApps)} 个）…") {
            Logx.event(
                LogTags.SETTING,
                "打开状态栏生效应用选择器",
                "current" to prefs.hideStatusApps.joinToString(","),
            )
            onShowStatusApps?.invoke()
        }
        note("勾选后立即生效；清空表示不对任何应用生效。Dock 与状态栏名单互不影响。")
        section("增强导航栏")
        switchRow("增强导航栏（贴底 pill）", prefs.enhancedNav, "与隐藏 Dock / 下移 Dock 互斥") {
            val from = prefs.enhancedNav
            prefs.enhancedNav = it
            logSetting("navbar_enhanced_enabled", "增强导航栏", from, it)
        }
        switchRow("下移 Dock", prefs.lowerDock, "与增强导航栏互斥") {
            val from = prefs.lowerDock
            prefs.lowerDock = it
            logSetting("dockbar_lower_dock", "下移 Dock", from, it)
        }
        // 导航栏样式（两种形态）
        // - 「设计 Dock」：pill 里装我们那套 1864×104 的设计版 Dock；
        // - 「槽位条」：pill 里装**可配置槽位**（一族）
        //   槽位里可以放媒体槽 / **歌词槽**。
        //
        // 原来这个键没人写（`navbar_style` 恒为 0），槽位条形态根本进不去 ——
        // 歌词槽的擦除高亮也就无从验证。本轮补上入口。
        chipRow("导航栏样式", listOf("设计 Dock", "槽位条"), prefs.navStyle) { idx ->
            val from = prefs.navStyle
            prefs.navStyle = idx
            logSetting("navbar_style", "导航栏样式", from, idx)
        }
        actionButton("编辑槽位（${NavSlots.load(prefs).size} 项）", primary = true) {
            // 排障场景：槽位编辑面板没打开 —— 记下这次点击
            Logx.event(LogTags.SETTING, "打开槽位编辑面板")
            onEditNavSlots?.invoke()
        }
        actionButton("恢复默认 15 槽位") {
            NavSlots.save(prefs, NavSlots.defaults())
            // 排障场景：槽位被一键重置 —— 记下重置动作（落盘明细见 NavSlots.save）
            Logx.event(LogTags.SETTING, "恢复默认 15 槽位")
            onApply?.invoke()
        }
        section("槽位规则")
        note(
            "槽位规则：顺序即视觉顺序；空槽是 3px 半透明细条、不计入 24 个上限；" +
                "只在「普通槽之后、第一个空调槽前」插一根分隔线；媒体槽最小 200dp、歌词槽最小 150dp、温度槽最小 112dp。\n" +
                "默认 15 槽：返回 / 主页 / 应用列表 / 分屏 / 360 / 媒体 / 空调电源 / 主驾温度 / 风扇 / 副驾温度 / AC / AUTO / 内外循环 / 前除霜 / 后除霜。"
        )
    }

    /** 已选应用数（不含「一个都不隐藏」的哨兵）。 */
    private fun selectedAppCount(apps: Set<String>): Int = apps.count { it != OemDockPolicy.NO_APPS }

    /**
     * 「生效应用」摘要
     * 空集 = 全部应用；含哨兵 = 未选择应用；否则列出前两个应用名。
     */
    private fun dockAppSummary(apps: Set<String>): String {
        if (apps.isEmpty()) return "全部应用"
        if (apps.contains(OemDockPolicy.NO_APPS)) return "未选择应用"
        val all = launcherApps().map { it.first }
        if (all.isNotEmpty() && all.all { apps.contains(it) }) return "全部应用"
        val names = apps.mapNotNull { appLabelOf(it) ?: it }.take(2)
        val joined = names.joinToString("、")
        return if (apps.size <= names.size) joined else "$joined 等${apps.size}个"
    }

    private fun launcherApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }
            .getOrElse { emptyList() }
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    /**
     * 「Dock / 状态栏生效应用」面板由共享的 `AppPickerDialog` 承担
     * 标题区分 Dock / 状态栏
     * 预勾选规则同、「全选 / 清空」按钮 + 「已选择 N/M」、勾选后立即生效。
     */

    /** 默认设置（case "native"）。 */
    private fun pageNativeSettings() {
        actionButton("打开系统默认桌面设置", primary = true) {
            // 排障场景：系统默认桌面页打不开 —— 记下尝试了哪个 Intent
            Logx.event(LogTags.SETTING, "打开系统默认桌面设置")
            val intents = listOf(
                Intent(Settings.ACTION_HOME_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (intent in intents) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    Logx.i(LogTags.SETTING, "已打开系统设置页", "action" to intent.action)
                    return@actionButton
                } catch (e: Exception) {
                    // 排障场景：某些车机没有这个系统页
                    Logx.w(LogTags.SETTING, "打开系统设置页失败", e, "action" to intent.action)
                }
            }
            Toast.makeText(context, "本机没有可打开的系统设置页", Toast.LENGTH_SHORT).show()
        }
        note("按 Home 键 / 开机后进入的桌面由系统选择")
        actionButton("打开系统设置") {
            Logx.event(LogTags.SETTING, "打开系统设置")
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // 排障场景：系统设置打不开
                Logx.w(LogTags.SETTING, "打开系统设置失败", e)
                Toast.makeText(context, "打不开系统设置", Toast.LENGTH_SHORT).show()
            }
        }
        note("进入 Android 系统设置")
    }

    /** 系统诊断+ 平台与兼容 + 媒体来源。 */
    private fun pageDiagnostics() {
        section("设备与分支")
        val metrics = context.resources.displayMetrics
        kvRow("系统", "Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}", true)
        kvRow("屏幕", "${metrics.widthPixels}×${metrics.heightPixels} · ${metrics.densityDpi} dpi", true)
        section("平台识别")
        val profile = com.cardesk.launcher.vehicle.VehicleController.platformProfile
            ?: com.cardesk.launcher.core.Platforms.detect(context, prefs.platformOverride)
        note(profile.summary())
        chipRow("平台判定", listOf("自动识别", "强制 E03", "强制 820A"), prefs.platformOverride) { idx ->
            val from = prefs.platformOverride
            prefs.platformOverride = idx
            logSetting("platform_override", "平台判定", from, idx)
            com.cardesk.launcher.vehicle.VehicleController.reconnect(context, prefs)
            select(selectedKey)
        }
        note("判定规则：densityDpi<=160 且屏幕长边>=1800 → 820A；其余按 E03 处理。")
        section("车控通道状态")
        val vc = com.cardesk.launcher.vehicle.VehicleController
        val gw = if (vc.initialized) runCatching { vc.gateway }.getOrNull() else null
        kvRow("当前生效通道", vc.sourceLabel, !vc.isMockFallback)
        kvRow("通道连接", if (gw?.isConnected() == true) "已连接" else "未连接", gw?.isConnected() == true)
        kvRow("车机服务", if (gw?.isServiceConnected() == true) "已连上" else "未连上", gw?.isServiceConnected() == true)
        kvRow(
            "最近一次写入",
            when {
                vc.lastWriteAt == 0L -> "尚未写入"
                vc.lastWriteOk -> "成功"
                else -> "失败"
            },
            vc.lastWriteOk,
        )
        vc.lastWriteError?.let { kvRow("失败原因", it, false) }
        section("系统能力")
        val caps = com.cardesk.launcher.core.SystemOps.capabilityReport(context)
        for ((cap, ok) in caps) {
            kvRow(cap.label, if (ok) "可用" else "不可用（需 system/平台签名）", ok)
        }
        // 排障场景：系统能力（静默安装/系统设置写入等）到底哪几项可用
        Logx.i(
            LogTags.SYS,
            "系统能力报告",
            "caps" to caps.joinToString(",") { "${it.first.label}=${if (it.second) "可用" else "不可用"}" },
        )
        section("媒体来源（固定 9 个）")
        for (source in com.cardesk.launcher.core.media.MediaSources.LIST) {
            val candidates = com.cardesk.launcher.core.media.MediaSources.packageCandidates(source.label)
            if (candidates.isEmpty()) {
                kvRow(source.label, "兜底来源（按当前活跃会话匹配）", true)
                continue
            }
            val installed = candidates.firstOrNull { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
            }
            kvRow(
                source.label,
                if (installed != null) "$installed · 已安装" else "${candidates.first()} 等 ${candidates.size} 个候选 · 未安装",
                installed != null,
            )
        }
        actionButton("重新探测平台与通道", primary = true) {
            com.cardesk.launcher.vehicle.VehicleController.reconnect(context, prefs)
            select(selectedKey)
        }
    }

    /** 重启车机。 */
    private fun pageRestart() {
        section("重启车机")
        note("将关闭所有应用并重新启动系统，约需 40 秒。行驾中请勿操作。")
        actionButton("立即重启", danger = true) {
            // 排障场景：点了重启没反应 —— 记下打开了确认框
            Logx.event(LogTags.SETTING, "打开重启车机确认框")
            onShowRestart?.invoke()
        }
    }

    /**
     * 诊断日志（**没有这一页**，是为真机排障加的调试工具）。
     *
     * - 日志级别：控制哪些级别会落盘（低于级别的只进 Logcat）；
     * - 导出诊断包：把日志 + 运行快照打成 zip，放到 `Download/CarDesk/`；
     * - 分享：直接把 zip 调起系统分享发出来；
     * - 预览：看最后若干行，确认日志确实在写。
     */
    private fun pageLogs() {
        section("通知使用权")
        note(
            com.cardesk.launcher.core.media.NotificationAccess.describeEnumeration(context) +
                "\n会话枚举需要「通知使用权」或「MEDIA_CONTENT_CONTROL」二者之一；" +
                "两条都不通时系统会拒绝枚举（音乐区一直显示占位），" +
                "桌面启动时会自动跳到系统的「通知使用权」设置页。",
        )

        section("日志级别")
        note(
            "调试级别会显著增加日志量与写盘频率。默认「信息」；" +
                "排查问题时调到「调试」，复现后请调回来。",
        )
        val levels = listOf(
            LogLevel.ERROR to "仅错误",
            LogLevel.WARN to "错误+警告",
            LogLevel.INFO to "错误+警告+信息",
            LogLevel.DEBUG to "全部（含调试）",
        )
        chipRow(
            "落盘级别",
            levels.map { it.second },
            levels.indexOfFirst { it.first == Logx.level() }.coerceAtLeast(0),
        ) { idx ->
            Logx.setLevel(levels[idx].first)
            select("logs")
        }

        section("收集")
        note(
            "导出内容 = 全部日志文件 + 运行快照（版本、系统、权限、车控通道状态、设置全量键值）。\n" +
                "导出目录：Download/CarDesk/（Android 10 及以上）；" +
                "Android 9 及以下需要存储写入权限。\n" +
                "日志占用上限：单文件 ${com.cardesk.launcher.core.log.FileLogSink.MAX_FILE_BYTES / 1024} KB × " +
                "${com.cardesk.launcher.core.log.FileLogSink.MAX_FILES} 份 = " +
                "${com.cardesk.launcher.core.log.FileLogSink.MAX_TOTAL_BYTES / 1024} KB（超了自动删最老的）。" +
                "当前占用 ${com.cardesk.launcher.core.log.Logx.totalBytes() / 1024} KB。",
        )
        actionButton("导出诊断包", primary = true) {
            com.cardesk.launcher.core.log.LogTools.export(context) { msg ->
                com.cardesk.launcher.core.log.LogTools.toast(context, msg)
            }
        }
        actionButton("导出并分享") {
            com.cardesk.launcher.core.log.LogTools.exportAndShare(context) { msg ->
                if (msg.isNotEmpty()) com.cardesk.launcher.core.log.LogTools.toast(context, msg)
            }
        }
        actionButton("清空日志", danger = true) {
            com.cardesk.launcher.core.log.LogTools.clear(context) { msg ->
                com.cardesk.launcher.core.log.LogTools.toast(context, msg)
                select("logs")
            }
        }

        section("最近日志")
        val tail = com.cardesk.launcher.core.log.LogTools.preview(40)
        note(tail)
    }

    /** 局域网传输。 */
    private fun pageLan() {
        val status = Ui.text(context, "", 17f, pal.textDim).apply { setLineSpacing(6f, 1f) }
        body.addView(status, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 })

        // —— 二维码（第六十七轮）：手机扫一下就能打开，不用手动敲地址 ——
        //
        // 原来只给一行 `http://192.168.x.x:18765/?t=<token>` 文本，
        // 用户得在电脑上把它敲进去 —— IP + 端口 + token，敲错一个字符就白搭。
        val qr = QrCodeView(context).apply { preferredSize = QR_SIZE_PX }
        body.addView(qr, LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX).also { it.topMargin = 18 })
        val qrHint = Ui.text(context, "", 15f, pal.textDim)
        body.addView(qrHint, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })

        val toggle = actionButton("启动局域网传输", primary = true) {}
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun fmtSize(bytes: Long): String = when {
            bytes >= 1 shl 20 -> String.format("%.1f MB", bytes / 1048576f)
            bytes >= 1 shl 10 -> String.format("%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }
        fun paintFiles() {
            listBox.removeAllViews()
            val files = LanTransferServer.rootDir(context).listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            section("共享目录文件（${files.size} 个）")
            if (files.isEmpty()) {
                note("目录为空。启动服务后把地址贴到电脑浏览器，即可上传/下载。")
                return
            }
            for (f in files.take(10)) {
                kvRow(f.name, fmtSize(f.length()), true)
            }
            if (files.size > 10) note("… 还有 ${files.size - 10} 个文件")
            note("目录：${LanTransferServer.rootDir(context).absolutePath}")
        }
        fun paint() {
            if (LanTransferServer.running) {
                val url = LanTransferServer.url(context)
                status.text = "运行中 · $url\n目录：" + LanTransferServer.rootDir(context).absolutePath
                toggle.text = "停止服务"
                // —— 二维码跟着地址走（token / 端口变了都要变）
                qr.text = url
                qrHint.text = "手机扫这个码，直接打开上传 / 下载页面"
            } else {
                status.text = "未启动。端口顺序探测 18765..18775、URL 必须带 token；启动后把地址贴到电脑浏览器即可上传/下载。"
                toggle.text = "启动局域网传输"
                // 没启动时没有地址 → 不画码（留白比画一个扫不通的码强）
                qr.text = ""
                qrHint.text = "启动服务后这里会出现二维码"
            }
        }
        toggle.setOnClickListener {
            if (LanTransferServer.running) {
                // 排障场景：服务停不掉 —— 记下停止动作
                Logx.event(LogTags.LAN, "停止局域网传输")
                LanTransferServer.stop()
                paint()
                return@setOnClickListener
            }
            val root = LanTransferServer.rootDir(context)
            if (root.listFiles().isNullOrEmpty()) {
                File(root, "欢迎使用.txt").writeText("把文件放到这个目录即可在车机与电脑之间传输。")
                // 排障场景：共享目录是空的 —— 记下已放入的欢迎文件
                Logx.i(LogTags.LAN, "共享目录为空，已写入欢迎文件", "dir" to root.absolutePath)
            }
            // 排障场景：电脑连不上 —— 记下启动动作（端口/token 在 LanTransferServer 里）
            Logx.event(LogTags.LAN, "启动局域网传输", "dir" to root.absolutePath)
            LanTransferServer.start(
                context,
                onStarted = {
                    Logx.i(LogTags.LAN, "局域网传输启动回调成功", "url" to it)
                    Toast.makeText(context, "已启动：$it", Toast.LENGTH_LONG).show()
                    paint()
                    select(selectedKey)
                },
                onError = {
                    // 排障场景：服务起不来 —— 记下失败原因
                    Logx.w(LogTags.LAN, "局域网传输启动失败", "reason" to it)
                    Toast.makeText(context, "启动失败：$it", Toast.LENGTH_LONG).show()
                },
            )
            paint()
        }
        actionButton("刷新文件列表") { select(selectedKey) }
        body.addView(listBox, LinearLayout.LayoutParams(-1, -2))
        paint()
        paintFiles()
        note("端口 18765..18775、`?t=<token>`；启动后把地址贴到电脑浏览器即可上传/下载。")
    }

    /** 在线更新。 */
    private fun pageUpdate() {
        section("在线更新")
        note("当前版本：CarDesk 1.0\n更新通道：使用 PackageInstaller.Session 静默安装；无 INSTALL_PACKAGES 权限时回退到系统安装器。")
        val urlField = android.widget.EditText(context).apply {
            hint = "https://…/cardesk.apk"
            setText(prefs.updateUrl)
            setTextColor(pal.text)
            setHintTextColor(pal.textDim)
            textSize = 18f
            setSingleLine()
            background = Skin.solid(Skin.GLASS_STRONG_FILL, 18f)
            setPadding(24, 12, 24, 12)
        }
        bodyField(urlField, height = 60)
        actionButton("保存更新地址") {
            val from = prefs.updateUrl
            val to = urlField.text.toString().trim()
            prefs.updateUrl = to
            logSetting("update_url", "更新地址", from, to)
            Toast.makeText(context, "已保存更新地址", Toast.LENGTH_SHORT).show()
        }
        actionButton("检查更新", primary = true) {
            // 排障场景：点了检查更新没反应 —— 记下打开更新对话框
            Logx.event(LogTags.SETTING, "打开在线更新对话框", "url" to prefs.updateUrl)
            onShowUpdate?.invoke()
        }
        actionButton("系统能力自检") {
            val report = com.cardesk.launcher.core.SystemOps.capabilityReport(context)
            val text = report.joinToString("\n") { (cap, ok) -> "${if (ok) "✔" else "✘"} ${cap.label}" }
            // 排障场景：系统能力自检结果 —— 记下哪几项可用
            Logx.i(
                LogTags.SYS,
                "系统能力自检",
                "caps" to report.joinToString(",") { "${it.first.label}=${if (it.second) "可用" else "不可用"}" },
            )
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    /** 开通免激活。 */
    private fun pageActivate() {
        section("激活信息")
        kvRow("车辆 VIN", "（空）", false)
        note(
            "本版本不设激活码，所有功能直接可用。\n\n" +
                "激活信息仅用于查询车辆 VIN。"
        )
    }

    companion object {
        /** 面板宽（设计 px）—— 设计板 `s05-settings`。 */
        const val PANEL_W = 1440

        /** 局域网传输页的二维码边长（设计 px）。 */
        const val QR_SIZE_PX = 300

        /** 面板高（设计 px）—— 设计板 `s05-settings`。 */
        const val PANEL_H = 920

        /** 面板左边距（设计 px）—— 居中（1920 − 1440 = 480，两侧各 240）。 */
        const val PANEL_LEFT = 240

        /** 面板上边距（设计 px）—— 居中（1080 − 920 = 160，上下各 80）。 */
        const val PANEL_TOP = 80

        /** 左栏宽（设计 px）。 */
        const val LEFT_W = 380

        /** 右栏内容左右留白（设计 px）。 */
        const val CONTENT_PAD = 48

        /** 右栏内容宽（设计 px）—— 1060 − 48 × 2。 */
        const val CONTENT_W = 964

        /** 左栏分组树起点（相对面板，设计 px）。 */
        const val TREE_TOP = 100

        /** 左栏分组树可视高（设计 px）—— 覆盖设计板那棵树（100 → 884）。 */
        const val TREE_VIEW_H = 784

        /** 左栏组标题左边缘（设计 px）。 */
        const val TREE_GROUP_X = 36

        /** 左栏条目左边缘（设计 px）。 */
        const val TREE_ITEM_X = 24

        /** 左栏条目宽（设计 px）。 */
        const val TREE_ITEM_W = 332

        /** 左栏条目高（设计 px）。 */
        const val TREE_ITEM_H = 36

        /**
         * 左栏条目**行距**（设计 px）—— 一个条目占 [TREE_ITEM_H]，行距比行高多出来的就是间隙。
         *
         * 第六十一轮按用户要求「左边太紧凑」从 34 调到 40（行高同时 32 → 36）。
         */
        const val TREE_ITEM_STEP = 40

        /**
         * 左栏**组标题**占位高（设计 px）—— 组标题本身 24 高，多出来的 8 是它到首个条目的间隙。
         *
         * 第六十一轮从 28 调到 32：分组边界一眼能看出来。
         */
        const val TREE_GROUP_STEP = 32

        /** 左栏**组尾**到下一组组标题的间距（设计 px）。第六十一轮从 10 调到 16。 */
        const val TREE_GROUP_END = 16

        /** 左栏底部 VIN 行高（设计 px）。 */
        const val VIN_ROW_H = 28

        /** 右栏一行内容高（设计 px）—— 行底分割线在 +92，下一行行顶 +130。 */
        const val ROW_H = 92

        /** 右栏行底分割线之后到下一行的间距（设计 px）。 */
        const val ROW_GAP = 37

        /** 方控桥接开关（本应用自有的开关位；用车机 SDK 的 SessionBridge）。 */
        const val KEY_BRIDGE_ENABLED = "session_bridge_enabled"

        /** 按键动作清单。 */
        val KEY_ACTION_LABELS: List<String> = listOf("无", "切换驾驶模式", "360环影(开关)", "唤起语音助手")

        /** 按键动作编码。 */
        val KEY_ACTION_VALUES: List<Int> = listOf(
            Prefs.ACTION_NONE, Prefs.ACTION_DRIVE_MODE, Prefs.ACTION_360_TOGGLE, Prefs.ACTION_VOICE_ASSIST,
        )

        /** 360 视角延迟档位。 */
        val CAMERA360_DELAY_LABELS: List<String> = Prefs.CAMERA360_DELAY_LABELS.toList()

        /** 360 视角延迟取值。 */
        val CAMERA360_DELAY_MS: List<Int> = Prefs.CAMERA360_DELAYS.toList()

        /** 360 视角标签。 */
        val CAMERA360_VIEW_LABELS: List<String> = Prefs.CAMERA360_VIEW_LABELS.toList()

        /** 360 视角取值。 */
        val CAMERA360_VIEW_IDS: List<Int> = Prefs.CAMERA360_VIEW_IDS.toList()
    }
}

/**
 * 设计板 `s05-settings` 的开关：**84×48** 胶囊轨道 + 1px 描边 + 38 圆点。
 *
 * 共享的 `ToggleSwitch` 是 76×44，这里按设计板单独画一个
 * 交互不变（点一下切换）。
 */
private class BoardSwitch(context: Context) : View(context) {

    /** 开启时的强调色。 */
    var tint: Int = Skin.ACCENT

    /** 是否开启。 */
    var checked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(SW, SH)
    }

    /** 切换开关。 */
    fun toggle() {
        checked = !checked
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)

        // —— 轨道 ——
        paint.style = Paint.Style.FILL
        paint.color = if (checked) tint else Skin.surfaceStrong
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (checked) tint else Skin.NAV_STROKE
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

        // —— 圆点（关：left 4；开：left 40）——
        paint.style = Paint.Style.FILL
        paint.color = if (checked) 0xFFFFFFFF.toInt() else Skin.surface
        val cx = if (checked) 40f + KNOB / 2f else 4f + KNOB / 2f
        canvas.drawCircle(cx, h / 2f, KNOB / 2f, paint)
    }

    companion object {
        /** 开关宽（设计 px）。 */
        const val SW = 84

        /** 开关高（设计 px）。 */
        const val SH = 48

        /** 圆点直径（设计 px）。 */
        const val KNOB = 38f
    }
}
