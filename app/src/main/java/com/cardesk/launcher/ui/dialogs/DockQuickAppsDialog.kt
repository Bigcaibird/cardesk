package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.cardesk.launcher.core.DockQuickApps
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback

/**
 * Dock **快捷应用**配置面板（第五十四轮新增）。
 *
 * Dock 第五十四轮起最右边多了一段**快捷应用槽位**（默认 [DockQuickApps.SLOTS] 个），
 * 槽位内容由用户自己配。两个入口：
 * 1. 设置页「Dock 快捷应用」条目 → 本面板（三个槽位一起管）；
 * 2. 直接**长按 Dock 上的某个槽位** → 本面板（定位到那一格）。
 *
 * 面板本身只负责**摆三行槽位**；真正挑应用走 [AppPickerDialog]（宿主弹），
 * 挑完宿主回调 [applyPicked] 写回来。
 *
 * 版式（面板 1080×600 @ (420,240)）：
 * - 标题 38px bold @ (40,34)，右上「关闭」胶囊；
 * - 说明 20px @ (40,96)；
 * - 三行槽位 1000×88 @ (40, 160 / 264 / 368)：[图标 56] [应用名 24px / 包名 17px] [更换] [清除]；
 * - 底部「清空全部」胶囊 @ (40, 492)。
 */
class DockQuickAppsDialog(
    context: Context,
    pal: Palette,
    private val store: DockQuickApps.Store,
    /** 打开时要定位的槽位（-1 = 不定位）。 */
    focusSlot: Int = -1,
) : DialogSheet(context, pal, 1080, 600, "Dock 快捷应用", 420, 240) {

    /** 点「更换」：宿主弹应用选择器，选完回调 [applyPicked]。 */
    var onRequestPicker: ((slotIndex: Int, currentPkg: String) -> Unit)? = null

    /** 槽位配置变化（已规整的包名列表）。 */
    var onChanged: ((List<String>) -> Unit)? = null

    /** 当前槽位配置（未规整的原始列表，按槽位顺序）。 */
    private var slots: List<String> = DockQuickApps.pad(store.load())

    /** 槽位行（重建时复用）。 */
    private val rows: MutableList<FrameLayout> = mutableListOf()

    /** 底部「清空全部」胶囊（属性必须在 [init] 之前初始化，否则首帧渲染会拿到 null）。 */
    private val clearAllButton: TextView = chip("清空全部", onClick = { clearAll() })

    init {
        addTitle()
        addClose()
        place(
            Ui.text(
                context,
                "选中的应用会固定在 Dock 最右侧，最多 ${DockQuickApps.SLOTS} 个。" +
                    "点「更换」挑选应用，点「清除」清空该槽位。",
                20f,
                pal.textFaint,
            ),
            40,
            96,
        )

        render(focusSlot)
    }

    /**
     * 宿主选完应用后回调：写入槽位 → 落盘 → 通知外部 → 重建面板。
     */
    fun applyPicked(slotIndex: Int, pkg: String) {
        val next = DockQuickApps.withSlot(slots, slotIndex, pkg)
        slots = next
        store.save(next)
        Logx.event(
            LogTags.SETTING,
            "Dock 快捷应用槽位变更",
            "slot" to slotIndex,
            "pkg" to pkg,
            "slots" to next.joinToString(","),
        )
        onChanged?.invoke(next)
        render(slotIndex)
    }

    /** 清空某一格。 */
    fun clearSlot(slotIndex: Int) = applyPicked(slotIndex, "")

    /** 清空全部。 */
    private fun clearAll() {
        val next = List(DockQuickApps.SLOTS) { "" }
        slots = next
        store.save(next)
        Logx.event(LogTags.SETTING, "Dock 快捷应用清空全部")
        onChanged?.invoke(next)
        render(-1)
    }

    /** 重建三行槽位。[focus] ≥ 0 时给那一行加个强调色描边。 */
    private fun render(focus: Int) {
        rows.forEach { body.removeView(it) }
        rows.clear()
        for (i in 0 until DockQuickApps.SLOTS) {
            val row = buildRow(i, focus)
            rows.add(row)
            place(row, 40, 160 + i * 104, panelWidth - 80, 88)
        }
        // 底部「清空全部」
        body.removeView(clearAllButton)
        place(
            clearAllButton,
            40,
            160 + DockQuickApps.SLOTS * 104 + 24,
            200,
            56,
        )
    }

    private fun buildRow(index: Int, focus: Int): FrameLayout {
        val pkg = slots.getOrElse(index) { "" }
        val label = appLabel(pkg)
        return FrameLayout(context).apply {
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, if (index == focus) pal.accent else Skin.NAV_STROKE)
            isClickable = true
            pressFeedback()
            setOnClickListener {
                Logx.event(LogTags.SETTING, "打开快捷应用选择器", "slot" to index, "current" to pkg)
                onRequestPicker?.invoke(index, pkg)
            }

            // 图标 56 @ (20,16)
            val iconBox = FrameLayout(context)
            if (pkg.isNotEmpty()) {
                val icon = appIcon(pkg)
                if (icon != null) {
                    iconBox.addView(
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(icon)
                        },
                        FrameLayout.LayoutParams(56, 56, Gravity.CENTER),
                    )
                } else {
                    // 取不到图标 → 首字占位（与 Dock 上的空槽同一套底板）
                    iconBox.background = Ui.round(Skin.surfaceSoft, 18f)
                    iconBox.addView(
                        Ui.text(context, label.take(1), 26f, pal.textDim).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(56, 56),
                    )
                }
            } else {
                iconBox.background = Ui.round(Skin.surfaceSoft, 18f)
                iconBox.addView(
                    Ui.text(context, "+", 28f, pal.textFaint).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(56, 56),
                )
            }
            addView(iconBox, FrameLayout.LayoutParams(56, 56, Gravity.TOP or Gravity.START).apply {
                leftMargin = 20
                topMargin = 16
            })

            // 应用名 24px @ (92,22) + 包名 17px @ (92,52)
            addView(
                Ui.text(context, if (pkg.isEmpty()) "未配置" else label, 24f, if (pkg.isEmpty()) pal.textFaint else pal.text, bold = true).apply {
                    maxLines = 1
                },
                FrameLayout.LayoutParams(-1, 34, Gravity.TOP or Gravity.START).apply {
                    leftMargin = 92
                    topMargin = 18
                    rightMargin = 300
                },
            )
            addView(
                Ui.text(context, if (pkg.isEmpty()) "点一下挑选应用" else pkg, 17f, pal.textFaint).apply {
                    maxLines = 1
                },
                FrameLayout.LayoutParams(-1, 26, Gravity.TOP or Gravity.START).apply {
                    leftMargin = 92
                    topMargin = 52
                    rightMargin = 300
                },
            )

            // 右侧两个胶囊：「更换」「清除」
            addView(
                chip("更换", sizePx = 20f, onClick = {
                    Logx.event(LogTags.SETTING, "打开快捷应用选择器", "slot" to index, "current" to pkg)
                    onRequestPicker?.invoke(index, pkg)
                }),
                FrameLayout.LayoutParams(-2, 56, Gravity.TOP or Gravity.END).apply {
                    rightMargin = 118
                    topMargin = 16
                },
            )
            addView(
                chip("清除", sizePx = 20f, onClick = { clearSlot(index) }),
                FrameLayout.LayoutParams(-2, 56, Gravity.TOP or Gravity.END).apply {
                    rightMargin = 20
                    topMargin = 16
                },
            )
        }
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun appIcon(pkg: String): android.graphics.drawable.Drawable? = runCatching {
        context.packageManager.getApplicationIcon(pkg)
    }.getOrNull()

    companion object {
        /** 便捷构造：直接用 [Prefs] 做存储。 */
        fun fromPrefs(context: Context, pal: Palette, focusSlot: Int = -1): DockQuickAppsDialog =
            DockQuickAppsDialog(context, pal, DockQuickApps.PrefsStore(Prefs(context.applicationContext)), focusSlot)
    }
}
