package com.cardesk.launcher.ui.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.apkstore.ApkEntry
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback

/**
 * 云端应用列表的一行（第九十八轮）。
 *
 * 第八十四轮那版只有「文件名 + 一行 meta」：几个包都以 `.apk` 结尾时，
 * 名称/大小/SHA 挤在一行里很难分辨谁是谁（用户反馈「不清晰」）。
 * 现在一卡一人：
 *
 * ```
 * [01]  酷狗音乐                        已安装
 *       kugou-8.9.1.apk  ·  11.8 MB     [重新安装]
 *       上传 2026-11-01  ·  SHA 0123456789ab
 * ```
 *
 * 序号块 + 名称/文件名/大小/日期/SHA + 状态 + 按钮，字段各自成行，靠视觉分组区分。
 */
object CloudAppRowView {

    fun build(
        context: Context,
        pal: Palette,
        entry: ApkEntry,
        index: Int,
        installed: Boolean,
        onDownload: (ApkEntry) -> Unit,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Skin.flat(Shape.INNER)
            setPadding(18, 16, 18, 16)
        }

        val number = Ui.text(
            context,
            (index + 1).toString().padStart(2, '0'),
            24f, pal.accentInk, bold = true,
        ).apply {
            gravity = Gravity.CENTER
            background = Ui.round(pal.accent, Shape.INNER)
        }
        card.addView(number, LinearLayout.LayoutParams(64, 64))

        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(mid, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = 18 })
        mid.addView(
            Ui.text(context, entry.name.ifBlank { entry.file.substringAfterLast('/') }, 26f, pal.text, bold = true),
            LinearLayout.LayoutParams(-1, -2),
        )
        val line1 = buildString {
            append(entry.file)
            if (entry.size > 0) append("  ·  ").append(size(entry.size))
        }
        mid.addView(
            Ui.text(context, line1, 19f, pal.textDim),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 6 },
        )
        val line2 = buildString {
            append("上传 ").append(if (entry.addedAt.length >= 10) entry.addedAt.take(10) else "未知")
            append("  ·  SHA ").append(if (entry.sha256.length >= 12) entry.sha256.take(12) else "未提供")
        }
        mid.addView(
            Ui.text(context, line2, 17f, pal.textFaint),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 4 },
        )

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        card.addView(right, LinearLayout.LayoutParams(-2, -2))
        val state = Ui.text(
            context,
            if (installed) "已安装" else "可下载",
            17f,
            if (installed) pal.accent else pal.textFaint,
            bold = installed,
        ).apply { gravity = Gravity.END }
        right.addView(state, LinearLayout.LayoutParams(-2, -2))
        val btn: TextView = Ui.text(
            context,
            if (installed) "重新安装" else "下载",
            19f,
            if (installed) pal.textDim else pal.accentInk,
            bold = !installed,
        ).apply {
            gravity = Gravity.CENTER
            background = if (installed) {
                Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
            } else {
                Ui.round(pal.accent, Shape.PILL)
            }
            setPadding(26, 12, 26, 12)
            pressFeedback()
            setOnClickListener { onDownload(entry) }
        }
        right.addView(btn, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = 10 })
        return card
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576f)
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
