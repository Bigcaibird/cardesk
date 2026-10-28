package com.cardesk.launcher.ui.pages

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui

/**
 * 应用页：列出可启动的第三方/系统应用，点击直接启动。
 * 作为桌面必须在 Manifest 里声明 QUERY_ALL_PACKAGES 才能看到全部应用。
 */
@SuppressLint("ViewConstructor")
class AppPage(context: Context, private val pal: Palette) : FrameLayout(context) {

    private val grid: GridLayout
    private val hint: TextView

    init {
        addView(Ui.text(context, "应用", 44f, pal.text, bold = true), Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 56, 4))
        hint = Ui.text(context, "正在读取应用列表…", 24f, pal.textDim)
        addView(hint, Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 56, 76))

        grid = GridLayout(context).apply {
            columnCount = 6
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        addView(grid, Ui.frame(1744, 640, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 120))
        refresh()
    }

    fun refresh() {
        grid.removeAllViews()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 排障场景：「应用页一个应用都没有 / 加载慢」——数量 + 耗时落盘。
        val startMs = System.currentTimeMillis()
        val infos: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString() }
                .take(24)
        } catch (e: Throwable) {
            Logx.w(LogTags.DRAWER, "应用页应用列表查询失败", e, "reason" to e.message)
            emptyList()
        }
        Logx.i(
            LogTags.DRAWER,
            "应用页列表加载",
            "count" to infos.size,
            "costMs" to (System.currentTimeMillis() - startMs),
        )

        if (infos.isEmpty()) {
            hint.text = "没有读到可启动的应用（部分系统需要 QUERY_ALL_PACKAGES 权限）"
            return
        }
        hint.text = "共 ${infos.size} 个应用"

        for (info in infos) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                background = Ui.round(pal.surface, 26f, 1f, pal.stroke)
                setOnClickListener { launch(info) }
                setOnLongClickListener {
                    // 排障场景：「应用页长按看不到包名」——包名落盘。
                    Logx.d(
                        LogTags.DRAWER,
                        "应用页长按查看包名",
                        "pkg" to info.activityInfo.packageName,
                    )
                    android.widget.Toast.makeText(
                        context,
                        info.activityInfo.packageName,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    true
                }
            }
            val icon = ImageView(context)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            try {
                icon.setImageDrawable(info.loadIcon(pm))
            } catch (e: Throwable) {
                icon.setBackgroundColor(Color.TRANSPARENT)
            }
            cell.addView(icon, LinearLayout.LayoutParams(88, 88))
            val label = Ui.text(context, info.loadLabel(pm), 22f, pal.text)
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER
            cell.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
            grid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = 272
                    height = 190
                    setMargins(8, 8, 8, 8)
                },
            )
        }
    }

    private fun launch(info: ResolveInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        // 排障场景：「应用页点图标启动不了」——包名落盘。
        Logx.event(
            LogTags.DRAWER,
            "应用页启动应用",
            "pkg" to info.activityInfo.packageName,
            "activity" to info.activityInfo.name,
        )
        try {
            context.startActivity(intent)
        } catch (e: Throwable) {
            Logx.w(
                LogTags.DRAWER,
                "应用页应用启动失败",
                e,
                "pkg" to info.activityInfo.packageName,
            )
            android.widget.Toast.makeText(context, "无法启动：${info.loadLabel(context.packageManager)}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
