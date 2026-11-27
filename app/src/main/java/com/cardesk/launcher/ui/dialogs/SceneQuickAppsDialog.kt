package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.SceneQuickApps
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.pressFeedback

/**
 * 桌面「快捷应用」卡片配置面板：
 * 支持 4 个槽位的更换应用、上移/下移调整顺序、清除单槽、清空全部与恢复默认。
 */
class SceneQuickAppsDialog(
    context: Context,
    pal: Palette,
    private val store: SceneQuickApps.Store,
    /** 当前卡片展示中的默认包名列表（未自定义过时用于预览初态）。 */
    defaultPkgs: List<String> = emptyList(),
    focusSlot: Int = -1,
) : DialogSheet(context, pal, 1120, 740, "桌面快捷应用", 400, 170) {

    var onRequestPicker: ((slotIndex: Int, currentPkg: String) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private var slots: List<String> = if (store.isConfigured()) {
        SceneQuickApps.normalize(store.load())
    } else {
        SceneQuickApps.normalize(defaultPkgs)
    }
    private val rows: MutableList<FrameLayout> = mutableListOf()
    private var lastSlots: List<String> = emptyList()

    private val clearAllButton: TextView = chip("清空全部", onClick = { clearAll() })
    private val resetDefaultButton: TextView = chip("恢复默认", onClick = { resetDefault() })

    init {
        addTitle()
        addClose()
        place(
            Ui.text(
                context,
                "最多配置 ${SceneQuickApps.SLOTS} 个应用显示在桌面「快捷应用」卡片中。" +
                    "点「更换」选择应用，点「↑ / ↓」自由调整顺序。",
                20f,
                pal.textFaint,
            ),
            40,
            96,
        )
        render(focusSlot)
    }

    fun applyPicked(slotIndex: Int, pkg: String) {
        val next = SceneQuickApps.withSlot(slots, slotIndex, pkg)
        slots = next
        store.save(next)
        Logx.event(
            LogTags.SETTING,
            "桌面快捷应用槽位变更",
            "slot" to slotIndex,
            "pkg" to pkg,
            "slots" to next.joinToString(","),
        )
        onChanged?.invoke()
        render(slotIndex)
    }

    fun moveSlot(from: Int, to: Int) {
        if (to !in 0 until SceneQuickApps.SLOTS) return
        val next = SceneQuickApps.swap(slots, from, to)
        slots = next
        store.save(next)
        Logx.event(
            LogTags.SETTING,
            "桌面快捷应用排序调整",
            "from" to from,
            "to" to to,
            "slots" to next.joinToString(","),
        )
        onChanged?.invoke()
        render(to)
    }

    fun clearSlot(slotIndex: Int) = applyPicked(slotIndex, "")

    private fun clearAll() {
        val next = SceneQuickApps.empty()
        slots = next
        store.save(next)
        Logx.event(LogTags.SETTING, "桌面快捷应用清空全部")
        onChanged?.invoke()
        render(-1)
    }

    private fun resetDefault() {
        store.resetDefault()
        Logx.event(LogTags.SETTING, "桌面快捷应用恢复默认")
        onChanged?.invoke()
        onClose?.invoke()
    }

    private fun render(focus: Int) {
        rows.forEach { body.removeView(it) }
        rows.clear()
        for (i in 0 until SceneQuickApps.SLOTS) {
            val row = buildRow(i, focus)
            rows.add(row)
            place(row, 40, 152 + i * 104, panelWidth - 80, 88)
            if (isAttachedToWindow && lastSlots.getOrNull(i) != slots.getOrNull(i)) {
                Motion.revealStagger(row, i)
            }
        }
        lastSlots = slots

        val footY = 152 + SceneQuickApps.SLOTS * 104 + 24
        body.removeView(clearAllButton)
        body.removeView(resetDefaultButton)
        place(clearAllButton, 40, footY, 180, 56)
        place(resetDefaultButton, 236, footY, 180, 56)
    }

    private fun buildRow(index: Int, focus: Int): FrameLayout {
        val pkg = slots.getOrElse(index) { "" }
        val label = appLabel(pkg)
        return FrameLayout(context).apply {
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, if (index == focus) pal.accent else Skin.NAV_STROKE)
            isClickable = true
            pressFeedback()
            setOnClickListener {
                Logx.event(LogTags.SETTING, "打开桌面快捷应用选择器", "slot" to index, "current" to pkg)
                onRequestPicker?.invoke(index, pkg)
            }

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

            addView(
                Ui.text(
                    context,
                    if (pkg.isEmpty()) "槽位 ${index + 1} · 未配置" else label,
                    24f,
                    if (pkg.isEmpty()) pal.textFaint else pal.text,
                    bold = true,
                ).apply { maxLines = 1 },
                FrameLayout.LayoutParams(-1, 34, Gravity.TOP or Gravity.START).apply {
                    leftMargin = 92
                    topMargin = 18
                    rightMargin = 410
                },
            )
            addView(
                Ui.text(
                    context,
                    if (pkg.isEmpty()) "点一下挑选应用" else pkg,
                    17f,
                    pal.textFaint,
                ).apply { maxLines = 1 },
                FrameLayout.LayoutParams(-1, 26, Gravity.TOP or Gravity.START).apply {
                    leftMargin = 92
                    topMargin = 52
                    rightMargin = 410
                },
            )

            // 右侧操作：↑ / ↓ / 更换 / 清除
            if (index > 0) {
                addView(
                    chip("↑", sizePx = 20f, onClick = { moveSlot(index, index - 1) }),
                    FrameLayout.LayoutParams(60, 56, Gravity.TOP or Gravity.END).apply {
                        rightMargin = 290
                        topMargin = 16
                    },
                )
            }
            if (index < SceneQuickApps.SLOTS - 1) {
                addView(
                    chip("↓", sizePx = 20f, onClick = { moveSlot(index, index + 1) }),
                    FrameLayout.LayoutParams(60, 56, Gravity.TOP or Gravity.END).apply {
                        rightMargin = 220
                        topMargin = 16
                    },
                )
            }
            addView(
                chip("更换", sizePx = 20f, onClick = {
                    Logx.event(LogTags.SETTING, "打开桌面快捷应用选择器", "slot" to index, "current" to pkg)
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
        fun fromPrefs(
            context: Context,
            pal: Palette,
            defaultPkgs: List<String> = emptyList(),
            focusSlot: Int = -1,
        ): SceneQuickAppsDialog = SceneQuickAppsDialog(
            context,
            pal,
            SceneQuickApps.PrefsStore(Prefs(context.applicationContext)),
            defaultPkgs,
            focusSlot,
        )
    }
}
