package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.update.AppUpdate
import com.cardesk.launcher.core.update.R2Release
import com.cardesk.launcher.core.net.TransferFormat
import com.cardesk.launcher.ui.Ui

/**
 * 「启动时查到新版本」的询问弹窗（第九十八轮）。
 *
 * 只做一件事：把版本号/安装包大小/更新说明摆出来，让用户选
 * 「立即更新」（交给 [UpdateDialog] 自动开始下载）或「稍后」
 * （记录到 [com.cardesk.launcher.core.Prefs.updateSkippedVersionCode]，下次启动不再问这个版本）。
 */
class UpdateAvailableDialog(
    context: Context,
    pal: Palette,
    private val release: R2Release,
) : DialogSheet(context, pal, 880, 640, "发现新版本") {

    /** 用户点了「立即更新」。 */
    var onUpdate: (() -> Unit)? = null

    /** 用户点了「稍后」/关闭。 */
    var onLater: (() -> Unit)? = null

    private val apk = release.apk

    init {
        val content = flowContent()
        content.addView(section("新版本"))
        content.addView(line("当前版本：${AppUpdate.versionLabel(context)}"), Ui.linear(-1, -2))
        content.addView(
            line("最新版本：${release.version}" + if (release.versionCode > 0) "（${release.versionCode}）" else ""),
            Ui.linear(-1, -2),
        )
        if (apk != null) {
            val size = if (apk.size > 0) " · ${TransferFormat.bytes(apk.size)}" else ""
            content.addView(line("安装包：${apk.name}$size"), Ui.linear(-1, -2))
        }
        val notes = release.notes.trim().take(800).ifBlank { release.name.ifBlank { "（这个版本没有写更新说明）" } }
        val notesView = line(notes).apply { setLineSpacing(8f, 1f) }
        val scroll = ScrollView(context).apply { addView(notesView) }
        content.addView(scroll, Ui.linear(-1, 0, 1f, 0, 10))

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(footer, Ui.linear(-1, 76, 0f, 0, 10))
        footer.addView(primaryButton("立即更新") { onUpdate?.invoke() }, Ui.linear(-2, 66))
        val later = chip("稍后", padH = 26, padV = 14) { onLater?.invoke() }
        footer.addView(later, Ui.linear(-2, 66).also { it.leftMargin = 16 })
        onClose = { onLater?.invoke() }
    }

    private fun line(text: String): TextView =
        Ui.text(context, text, 21f, pal.textFaint).apply { setLineSpacing(6f, 1f) }
}
