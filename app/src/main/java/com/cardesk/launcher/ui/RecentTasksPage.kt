package com.cardesk.launcher.ui

import android.content.Context
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
import com.cardesk.launcher.core.Shape as CoreShape
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.RecentTasks
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.widget.OverlayBackdrop

/**
 * 最近任务页（第九十五轮 C3，对照 UNI 的 `recent_tasks_panel.dart`）。
 *
 * 版面复用抽屉的页面级卡片：面板 1876×867 @ (44,73)，标题「最近任务」40px，
 * 右侧「清空」+ 关闭；下面是 4 列任务卡（图标 + 名称 + 包名 + 锁定 / 关闭）。
 *
 * 数据来自 [RecentTasks]（平台签名下 `getRecentTasks` / `removeTask` / `moveTaskToFront`）。
 * 只读 + 单条关闭 + 清空（锁定的保留）；缩略图留到后续轮次。
 */
class RecentTasksPage(
    context: Context,
    private val pal: Palette,
    private val tasks: RecentTasks = RecentTasks(context),
) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null
    var onOpenApp: ((RecentTasks.App) -> Unit)? = null

    private val prefs = Prefs(context.applicationContext)

    private lateinit var grid: GridLayout
    private lateinit var hint: TextView
    private lateinit var clear: TextView
    private lateinit var countBadge: TextView
    private lateinit var memValue: TextView
    private lateinit var memBar: MemoryBar

    private var panelView: View? = null
    private var scrimView: View? = null

    fun playOpen() {
        OverlayAnim.open(panelView, scrimView)
    }

    fun playClose(onEnd: () -> Unit) {
        OverlayAnim.close(panelView, scrimView, onEnd)
    }

    init {
        val scrim = OverlayBackdrop(context).apply {
            palette = this@RecentTasksPage.pal
            withScrim = false
            isClickable = true
            setOnClickListener {
                Logx.event(LogTags.RECENT, "最近任务关闭（点击遮罩）")
                onClose?.invoke()
            }
        }
        scrimView = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

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

        val title = Ui.text(context, "最近任务", 40f, Skin.TEXT, bold = true)
        panel.addView(title, abs(PANEL_PAD, TITLE_TOP, -1, 52))

        // 标题旁的任务数量徽标（画板 v6：「最近任务 8 个」）。
        countBadge = Ui.text(context, "0 个", 16f, Skin.TEXT_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(14, 0, 14, 0)
            background = Ui.round(pal.withAlpha(Skin.TEXT, 0x14), CoreShape.PILL)
        }
        panel.addView(countBadge, abs(PANEL_PAD + TITLE_TEXT_W, TITLE_TOP + 14, 86, 30))

        // 右上「运行内存 3.2 / 4.0 GB」+ 进度条（画板 v6 新增）。
        val memLabel = Ui.text(context, "运行内存", 14f, Skin.TEXT_DIM)
        panel.addView(memLabel, abs(MEM_LEFT, TITLE_TOP + 12, 120, 24))
        memValue = Ui.text(context, "-- / -- GB", 18f, Skin.TEXT_SOFT, bold = true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        panel.addView(memValue, abs(MEM_LEFT, TITLE_TOP + 8, MEM_W, 30))
        memBar = MemoryBar(context)
        panel.addView(memBar, abs(MEM_LEFT, TITLE_TOP + MEM_BAR_TOP, MEM_W, 6))

        // 右侧：清空 + 关闭
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clear = pill("清空", false) {
            val removed = tasks.clear()
            Logx.event(LogTags.RECENT, "点击清空最近任务", "removed" to removed)
            reload()
        }
        val close = pill("关闭", true) { onClose?.invoke() }
        actions.addView(clear, LinearLayout.LayoutParams(-2, 48).apply { rightMargin = 12 })
        actions.addView(close, LinearLayout.LayoutParams(-2, 48))
        panel.addView(
            actions,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = 30
                rightMargin = 8
            },
        )

        panel.addView(
            View(context).apply { setBackgroundColor(Skin.NAV_STROKE) },
            abs(PANEL_PAD, DIVIDER_TOP, PANEL_W - PANEL_PAD * 2, 1),
        )

        hint = Ui.text(context, "", 18f, Skin.TEXT_DIM).apply { gravity = Gravity.CENTER }
        panel.addView(hint, abs(PANEL_PAD, GRID_TOP, PANEL_W - PANEL_PAD * 2, 120))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        grid = GridLayout(context).apply { columnCount = COLUMNS }
        scroll.addView(grid, FrameLayout.LayoutParams(GRID_W, LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, abs(PANEL_PAD, GRID_TOP, GRID_W, GRID_H))

        reload()
        Logx.event(LogTags.RECENT, "最近任务页打开", "state" to tasks.state.name)
    }

    /** 重新读取并重画。 */
    fun reload() {
        grid.removeAllViews()
        if (!tasks.available) {
            hint.visibility = View.VISIBLE
            hint.text = when (tasks.state) {
                RecentTasks.State.NO_PERMISSION -> "这台车机没有开放最近任务权限"
                RecentTasks.State.UNSUPPORTED -> "这台车机不支持最近任务"
                RecentTasks.State.READY -> ""
            }
            clear.visibility = View.GONE
            return
        }
        val apps = tasks.list()
        countBadge.text = "${apps.size} 个"
        bindMemory()
        hint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        hint.text = "暂无最近任务"
        clear.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        for ((index, app) in apps.withIndex()) {
            val cell = card(app)
            grid.addView(cell)
            // 网格是整块重建的 —— 逐项淡入上移，
            // 用户看得出「这是一批新出现的任务」，而不是整页闪一下。
            Motion.revealStagger(cell, index)
        }
        Logx.i(LogTags.RECENT, "最近任务列表加载", "count" to apps.size)
    }

    private fun card(app: RecentTasks.App): View {
        val cell = FrameLayout(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.CARD, strong = true)
            setPadding(18, 16, 18, 14)
            isClickable = true
            pressFeedback()
            setOnClickListener {
                Logx.event(LogTags.RECENT, "点击最近任务", "pkg" to app.pkg, "task" to app.taskId)
                onOpenApp?.invoke(app)
            }
        }
        // 顶行：图标 + 名称 / 包名
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            setImageDrawable(app.icon)
            background = Skin.solid(Skin.surfaceSoft, 16f)
        }
        head.addView(icon, LinearLayout.LayoutParams(ICON, ICON))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(
            Ui.text(context, app.label, 22f, Skin.TEXT, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        texts.addView(
            Ui.text(context, app.pkg, 15f, Skin.TEXT_DIM).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 2 },
        )
        head.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 14 })
        // 「占用 N MB」徽标（画板 v6 新增）：拿不到进程 PSS 时显示 `--`，不编造数字。
        val usedMb = ProcMemory.pssMb(context, app.pkg)
        head.addView(
            Ui.text(context, if (usedMb == null) "占用 --" else "占用 $usedMb MB", 12f, Skin.TEXT_SOFT).apply {
                gravity = Gravity.CENTER
                setPadding(12, 0, 12, 0)
                background = Ui.round(pal.withAlpha(Skin.TEXT, 0x10), CoreShape.PILL)
            },
            LinearLayout.LayoutParams(-2, 26).apply { leftMargin = 10 },
        )
        box.addView(head, LinearLayout.LayoutParams(-1, -2))

        // 底行：锁定 / 关闭
        val foot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val locked = tasks.isLocked(app.pkg)
        val lock = pill(if (locked) "已锁定" else "锁定", locked) {
            val now = tasks.toggleLock(app.pkg)
            reload()
            Logx.event(LogTags.RECENT, "最近任务锁定", "pkg" to app.pkg, "locked" to now)
        }
        val remove = pill("关闭任务", false) {
            tasks.close(app)
            reload()
        }
        foot.addView(lock, LinearLayout.LayoutParams(-2, 44).apply { rightMargin = 10 })
        foot.addView(remove, LinearLayout.LayoutParams(-2, 44))
        // 画板按钮在卡片底部（y≈355 绝对 / 卡内 158），压到底部而不是紧跟标题。
        // 中间必须有一根 weight=1 的弹簧：否则 foot 会紧贴 head（实机 y≈285），
        // 卡片下半截留白、按钮悬在中间。
        box.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        box.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })

        cell.addView(box, FrameLayout.LayoutParams(-1, -1))
        cell.layoutParams = GridLayout.LayoutParams().apply {
            width = CELL_W
            height = CELL_H
            rightMargin = CELL_GAP
            bottomMargin = CELL_GAP
        }
        return cell
    }

    /**
     * 右上「运行内存」：已用 = total - avail（`ActivityManager.MemoryInfo`）。
     *
     * 进度条按已用/总量填色。取不到时显示 `-- / -- GB` 并把进度条清空 ——
     * 不编造数字（画板注释也写明数据来自 `ActivityManager.MemoryInfo`）。
     */
    private fun bindMemory() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        val ok = runCatching { am?.getMemoryInfo(info) }.isSuccess && info.totalMem > 0
        if (!ok) {
            memValue.text = "-- / -- GB"
            memBar.fill = 0f
            return
        }
        val totalGb = info.totalMem / 1024.0 / 1024.0 / 1024.0
        val usedGb = (info.totalMem - info.availMem) / 1024.0 / 1024.0 / 1024.0
        memValue.text = String.format(java.util.Locale.US, "%.1f / %.1f GB", usedGb, totalGb)
        memBar.fill = (usedGb / totalGb).toFloat().coerceIn(0f, 1f)
    }

    /** 6px 运行内存进度条（画板右上，青绿填充 + 浅底槽）。 */
    private inner class MemoryBar(ctx: Context) : View(ctx) {
        var fill: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        private val track = Ui.round(pal.withAlpha(Skin.TEXT, 0x14), 3f)
        private val bar = Ui.round(Skin.ACCENT, 3f)

        override fun onDraw(canvas: android.graphics.Canvas) {
            track.setBounds(0, 0, width, height)
            track.draw(canvas)
            if (fill > 0f) {
                bar.setBounds(0, 0, (width * fill).toInt().coerceAtLeast(6), height)
                bar.draw(canvas)
            }
        }
    }

    private fun pill(text: String, active: Boolean, onClick: () -> Unit): TextView =
        Ui.text(context, text, 18f, if (active) Skin.ACCENT_INK else Skin.TEXT_SOFT, bold = active).apply {
            gravity = Gravity.CENTER
            setPadding(20, 8, 20, 8)
            background = if (active) Skin.solid(Skin.ACCENT, Shape.PILL) else
                Ui.round(Skin.CHIP_FILL, Shape.PILL, 1f, Skin.CHIP_STROKE)
            isClickable = true
            pressFeedback()
            setOnClickListener { onClick() }
        }

    private fun abs(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    companion object {
        private const val PANEL_W = 1876
        private const val PANEL_H = 867
        private const val PANEL_LEFT = 44
        private const val PANEL_TOP = 73
        private const val PANEL_PAD = 40
        private const val TITLE_TOP = 34
        private const val DIVIDER_TOP = 100
        private const val GRID_TOP = 124
        private const val GRID_H = 700
        private const val GRID_W = PANEL_W - PANEL_PAD * 2
        private const val COLUMNS = 4
        private const val CELL_W = 422
        private const val CELL_H = 193
        private const val CELL_GAP = 24
        private const val ICON = 64

        /** 标题「最近任务」40px bold 的宽度（画板实测徽标从 250 起始 → 相对 206）。 */
        private const val TITLE_TEXT_W = 176

        /** 右上运行内存块（画板 10-recent：x1418..1730 → 相对面板 1374..1686）。 */
        private const val MEM_LEFT = 1371
        private const val MEM_W = 230
        private const val MEM_BAR_TOP = 56
    }

    /**
     * 进程内存（画板 v6 的每卡「占用 N MB」）。
     *
     * Android 9 的 `ActivityManager.getProcessMemoryInfo` 只对本进程有效，
     * `getRunningAppProcesses` 也已经只回自己的进程；这里退一步读 `/proc`：
     * 按 `cmdline` 匹配包名 → `statm` 第 2 字段（常驻页数）× 页大小。
     * **读不到就返回 null**（调用方显示「占用 --」），不猜数字。
     */
    private object ProcMemory {
        private val pageKb = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024

        fun pssMb(context: android.content.Context, pkg: String): Int? {
            // ① 先走 ActivityManager：本应用是 `android.uid.system`，
            //    `getRunningAppProcesses()` / `getProcessMemoryInfo()` 能拿到别的进程
            //    （/proc/<pid>/statm 对 uid1000 是 `-r--r----- root radio`，读不到）。
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            val pid = runCatching {
                am?.runningAppProcesses?.firstOrNull { it.processName == pkg }?.pid
            }.getOrNull()
            if (pid != null && pid > 0) {
                val kb = runCatching {
                    am?.getProcessMemoryInfo(intArrayOf(pid))?.firstOrNull()?.totalPss ?: 0
                }.getOrNull() ?: 0
                if (kb > 0) return (kb / 1024).coerceAtLeast(1)
            }
            val dirs = runCatching { java.io.File("/proc").listFiles() }.getOrNull() ?: return null
            for (dir in dirs) {
                val pid = dir.name.toIntOrNull() ?: continue
                val cmdline = runCatching {
                    java.io.File("/proc/$pid/cmdline").readText().trimEnd('\u0000')
                }.getOrNull() ?: continue
                if (cmdline != pkg) continue
                val statm = runCatching {
                    java.io.File("/proc/$pid/statm").readText().trim().split(' ')
                }.getOrNull() ?: return null
                val rssPages = statm.getOrNull(1)?.toLongOrNull() ?: return null
                return (rssPages * pageKb / 1024).toInt()
            }
            return null
        }
    }
}
