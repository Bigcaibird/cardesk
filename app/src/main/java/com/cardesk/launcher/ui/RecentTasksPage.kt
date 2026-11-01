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
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.RecentTasks
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
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
                topMargin = 36
                rightMargin = PANEL_PAD
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
        hint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        hint.text = "暂无最近任务"
        clear.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        for (app in apps) grid.addView(card(app))
        Logx.i(LogTags.RECENT, "最近任务列表加载", "count" to apps.size)
    }

    private fun card(app: RecentTasks.App): View {
        val cell = FrameLayout(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.INNER, fill = Skin.surfaceSoft)
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
        box.addView(head, LinearLayout.LayoutParams(-1, -2))

        // 底行：锁定 / 关闭
        val foot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
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
        box.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        cell.addView(box, FrameLayout.LayoutParams(-1, -1))
        cell.layoutParams = GridLayout.LayoutParams().apply {
            width = CELL_W
            height = CELL_H
            rightMargin = CELL_GAP
            bottomMargin = CELL_GAP
        }
        return cell
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
        private const val CELL_W = 428
        private const val CELL_H = 200
        private const val CELL_GAP = 20
        private const val ICON = 72
    }
}
