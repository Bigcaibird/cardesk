package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.SceneQuickControls
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback

/**
 * 桌面「快捷车控」自定义编辑面板（双栏一页式）：
 * - 左栏：当前卡片已选槽位（1~4 个），支持 ↑ / ↓ 排序、移除与恢复默认；
 * - 右栏：按 4 大类展示全部 19 项已打通的一键车控，点一下即可勾选添加或取消移除。
 */
class SceneQuickControlsDialog(
    context: Context,
    pal: Palette,
    private val store: SceneQuickControls.Store,
) : DialogSheet(context, pal, 1480, 820, "自定义快捷车控", 220, 130) {

    var onChanged: ((List<String>) -> Unit)? = null

    private var slots: List<String> = SceneQuickControls.normalize(store.load())

    private val leftHeader: TextView = Ui.text(context, "", 20f, pal.text, bold = true)
    private val leftList: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val rightList: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val resetButton: TextView = chip("恢复默认", onClick = { resetDefault() })

    init {
        addTitle()
        addClose()

        place(
            Ui.text(
                context,
                "支持自定义 ${SceneQuickControls.MIN_SLOTS}~${SceneQuickControls.MAX_SLOTS} 个一键车控显示在桌面「快捷车控」卡片中。" +
                    "右侧勾选增删，左侧 ↑ / ↓ 自由排序。",
                19f,
                pal.textFaint,
            ),
            40,
            92,
            panelWidth - 80,
            30,
        )

        // 左栏：已选槽位
        val leftX = 40
        val colTop = 144
        val leftW = 540
        place(leftHeader, leftX, colTop, leftW, 32)
        place(leftList, leftX, colTop + 42, leftW, 470)
        place(resetButton, leftX, panelHeight - 88, 176, 54)

        // 中间分隔竖线
        val divider = android.view.View(context).apply {
            setBackgroundColor(Skin.NAV_STROKE)
        }
        place(divider, leftX + leftW + 24, colTop, 1, panelHeight - colTop - 40)

        // 右栏：全部 19 项分类可选车控（可滚动）
        val rightX = leftX + leftW + 48
        val rightW = panelWidth - rightX - 40
        place(
            Ui.text(context, "全部可用一键车控（${SceneQuickControls.ALL.size} 项）", 20f, pal.text, bold = true),
            rightX,
            colTop,
            rightW,
            32,
        )
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                rightList,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        place(scroll, rightX, colTop + 42, rightW, panelHeight - colTop - 78)

        render()
    }

    fun toggleControl(id: String) {
        if (id in slots) {
            if (slots.size <= SceneQuickControls.MIN_SLOTS) {
                Toast.makeText(context, "快捷车控至少保留 ${SceneQuickControls.MIN_SLOTS} 项", Toast.LENGTH_SHORT).show()
                return
            }
            applySlots(SceneQuickControls.remove(slots, id), "移除", id)
        } else {
            if (slots.size >= SceneQuickControls.MAX_SLOTS) {
                Toast.makeText(
                    context,
                    "最多配置 ${SceneQuickControls.MAX_SLOTS} 个快捷车控，请先移除一个",
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            applySlots(SceneQuickControls.add(slots, id), "添加", id)
        }
    }

    fun moveSlot(from: Int, to: Int) {
        if (to !in slots.indices || from == to) return
        applySlots(SceneQuickControls.swap(slots, from, to), "排序", "$from->$to")
    }

    private fun resetDefault() {
        store.resetDefault()
        slots = SceneQuickControls.DEFAULT_SLOTS
        Logx.event(LogTags.SETTING, "快捷车控恢复默认", "slots" to slots.joinToString(","))
        onChanged?.invoke(slots)
        render()
    }

    private fun applySlots(next: List<String>, action: String, detail: String) {
        slots = SceneQuickControls.normalize(next)
        store.save(slots)
        Logx.event(
            LogTags.SETTING,
            "桌面快捷车控变更",
            "action" to action,
            "detail" to detail,
            "slots" to slots.joinToString(","),
        )
        onChanged?.invoke(slots)
        render()
    }

    private fun render() {
        leftHeader.text = "当前卡片槽位（已选 ${slots.size}/${SceneQuickControls.MAX_SLOTS}）"
        renderLeftSlots()
        renderCatalog()
    }

    private fun renderLeftSlots() {
        leftList.removeAllViews()
        slots.forEachIndexed { index, id ->
            val def = SceneQuickControls.defOf(id) ?: return@forEachIndexed
            val row = FrameLayout(context).apply {
                background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)

                val badge = Ui.text(context, "${index + 1}", 19f, pal.accentInk, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Ui.round(pal.accent, 14f)
                }
                addView(
                    badge,
                    FrameLayout.LayoutParams(36, 36, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                        leftMargin = 16
                    },
                )

                addView(
                    Ui.text(context, def.label, 22f, pal.text, bold = true).apply { maxLines = 1 },
                    FrameLayout.LayoutParams(-1, 30, Gravity.TOP or Gravity.START).apply {
                        leftMargin = 66
                        topMargin = 14
                        rightMargin = 250
                    },
                )
                addView(
                    Ui.text(context, "${def.category.title} · ${def.description}", 15f, pal.textFaint).apply {
                        maxLines = 1
                    },
                    FrameLayout.LayoutParams(-1, 24, Gravity.TOP or Gravity.START).apply {
                        leftMargin = 66
                        topMargin = 46
                        rightMargin = 250
                    },
                )

                if (index > 0) {
                    addView(
                        chip("↑", sizePx = 21f, onClick = { moveSlot(index, index - 1) }),
                        FrameLayout.LayoutParams(52, 48, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                            rightMargin = 184
                        },
                    )
                }
                if (index < slots.size - 1) {
                    addView(
                        chip("↓", sizePx = 21f, onClick = { moveSlot(index, index + 1) }),
                        FrameLayout.LayoutParams(52, 48, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                            rightMargin = 124
                        },
                    )
                }
                if (slots.size > SceneQuickControls.MIN_SLOTS) {
                    addView(
                        chip("移除", sizePx = 18f, onClick = { toggleControl(id) }),
                        FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                            rightMargin = 14
                        },
                    )
                }
            }
            leftList.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 84).apply {
                    bottomMargin = 14
                },
            )
        }
    }

    private fun renderCatalog() {
        rightList.removeAllViews()
        val selected = slots.toSet()
        for (cat in SceneQuickControls.Category.values()) {
            val items = SceneQuickControls.ALL.filter { it.category == cat }
            if (items.isEmpty()) continue

            rightList.addView(
                Ui.text(context, "${cat.title}（${items.size}）", 17f, pal.textDim, bold = true),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = if (rightList.childCount == 0) 0 else 16
                    bottomMargin = 10
                },
            )

            // 每行 2 列均匀网格
            var i = 0
            while (i < items.size) {
                val pairRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (col in 0..1) {
                    val item = items.getOrNull(i + col)
                    if (item != null) {
                        val on = item.id in selected
                        val card = buildCatalogTile(item, on)
                        pairRow.addView(
                            card,
                            LinearLayout.LayoutParams(0, 74, 1f).apply {
                                if (col == 0) rightMargin = 12
                            },
                        )
                    } else {
                        pairRow.addView(
                            android.view.View(context),
                            LinearLayout.LayoutParams(0, 74, 1f),
                        )
                    }
                }
                rightList.addView(
                    pairRow,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = 10
                    },
                )
                i += 2
            }
        }
    }

    private fun buildCatalogTile(def: SceneQuickControls.ControlDef, selected: Boolean): FrameLayout {
        val bgColor = if (selected) pal.accentSoft else Skin.surfaceSoft
        val strokeColor = if (selected) pal.accent else Skin.NAV_STROKE
        return FrameLayout(context).apply {
            background = Ui.round(bgColor, 16f, if (selected) 2f else 1f, strokeColor)
            isClickable = true
            pressFeedback()
            setOnClickListener { toggleControl(def.id) }

            addView(
                Ui.text(context, def.label, 20f, pal.text, bold = true).apply { maxLines = 1 },
                FrameLayout.LayoutParams(-1, 28, Gravity.TOP or Gravity.START).apply {
                    leftMargin = 18
                    topMargin = 12
                    rightMargin = 104
                },
            )
            addView(
                Ui.text(context, def.description, 14f, pal.textFaint).apply { maxLines = 1 },
                FrameLayout.LayoutParams(-1, 22, Gravity.TOP or Gravity.START).apply {
                    leftMargin = 18
                    topMargin = 42
                    rightMargin = 104
                },
            )

            val badgeText = if (selected) "已添加" else "+ 添加"
            val badgeBg = if (selected) pal.accent else Skin.surfaceStrong
            val badgeFg = if (selected) pal.accentInk else pal.textDim
            addView(
                Ui.text(context, badgeText, 15f, badgeFg, bold = selected).apply {
                    gravity = Gravity.CENTER
                    background = Ui.round(badgeBg, 14f)
                },
                FrameLayout.LayoutParams(82, 34, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = 14
                },
            )
        }
    }

    companion object {
        fun fromPrefs(context: Context, pal: Palette): SceneQuickControlsDialog =
            SceneQuickControlsDialog(
                context,
                pal,
                SceneQuickControls.PrefsStore(Prefs(context.applicationContext)),
            )
    }
}
