package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.apkstore.ApkEntry
import com.cardesk.launcher.core.apkstore.ApkStore
import com.cardesk.launcher.core.net.TransferFormat
import com.cardesk.launcher.core.update.AppUpdate
import com.cardesk.launcher.ui.Ui
import java.io.File

/**
 * 云端应用「下载 + 安装」进度弹窗（第九十八轮）。
 *
 * 行为：
 * 1. 打开即开始下载，显示**百分比 + 下载速度 + 已下载/总大小 + 用时**；
 * 2. 下载完成自动进入安装，安装结果（静默安装 / 转系统安装器 / 失败）留在弹窗里，
 *    同时用 Toast 提示一次，关掉弹窗也不会漏掉结果；
 * 3. 失败可以点「重试」；成功/失败都写日志（`LogTags.APK_STORE`）。
 *
 * 下载与安装通过构造参数注入，测试里可以喂假进度，不需要联网。
 */
class CloudDownloadDialog(
    context: Context,
    pal: Palette,
    private val entry: ApkEntry,
    private val downloader: (ApkEntry, (Int) -> Unit, (Long, Long, Long) -> Unit) -> File? =
        { e, onPercent, onDetail ->
            ApkStore.download(context.applicationContext, e, onPercent, onDetail)
        },
    private val installer: (File) -> AppUpdate.InstallResult =
        { f -> ApkStore.install(context.applicationContext, f) },
    /** 安装链路提交成功（静默/转安装器）后回调，页面用来把该条目标成「已安装」。 */
    private val onInstalled: (() -> Unit)? = null,
) : DialogSheet(context, pal, 780, 760, "下载安装") {

    private val main = Handler(Looper.getMainLooper())

    private val title = Ui.text(context, entry.name.ifBlank { entry.file }, 34f, pal.text, bold = true)
    private val percentText = Ui.text(context, "0%", 52f, pal.accent, bold = true)
    private val bar = SeekBar(context).apply { isEnabled = false; max = 100 }
    private val speedText = Ui.text(context, "速度：—", 22f, pal.textDim)
    private val bytesText = Ui.text(context, "", 19f, pal.textFaint)
    private val statusText = Ui.text(context, "准备下载…", 21f, pal.textDim)
    private val resultText = Ui.text(context, "", 19f, pal.textFaint).apply { setLineSpacing(8f, 1f) }
    private val footer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val DANGER_TEXT = 0xFFE05B5B.toInt()

    private var retryBtn: TextView? = null
    private var busy = false

    /** 是否还在下载/安装（测试用）。 */
    val isRunning: Boolean get() = busy

    // 速度采样
    private var lastRead = 0L
    private var lastDetailAt = 0L
    private var lastUiAt = 0L

    init {
        val content = flowContent()
        content.addView(title, Ui.linear(-1, -2))
        if (entry.size > 0) {
            content.addView(
                Ui.text(context, "安装包：${entry.file} · ${TransferFormat.bytes(entry.size)}", 19f, pal.textFaint),
                Ui.linear(-1, -2).also { it.topMargin = 6 },
            )
        }
        content.addView(section("下载进度"), Ui.linear(-1, 32).also { it.topMargin = 18 })
        content.addView(percentText, Ui.linear(-1, 64))
        content.addView(bar, Ui.linear(-1, 44, 0f, 4))
        content.addView(speedText, Ui.linear(-1, 30, 0f, 0, 6))
        content.addView(bytesText, Ui.linear(-1, 30))
        content.addView(statusText, Ui.linear(-1, 32, 0f, 0, 12))
        content.addView(section("结果"), Ui.linear(-1, 30))
        content.addView(resultText, Ui.linear(-1, 64, 0f, 0, 4))
        content.addView(footer, Ui.linear(-1, 66, 0f, 0, 10))
        footer.addView(chip("关闭", padH = 26, padV = 14) { onClose?.invoke() }, Ui.linear(-2, 66))

        main.post { startDownload() }
    }

    /** 重新开始一轮下载（首次打开也会走这里）。 */
    fun startDownload() {
        if (busy) return
        busy = true
        retryBtn?.let { footer.removeView(it) }
        retryBtn = null
        percentText.text = "0%"
        bar.progress = 0
        speedText.text = "速度：—"
        bytesText.text = ""
        resultText.text = ""
        statusText.text = "正在下载…"
        statusText.setTextColor(pal.textDim)
        lastRead = 0L
        lastDetailAt = 0L
        lastUiAt = 0L

        Thread({
            val file = downloader(
                entry,
                { p -> main.post { onPercent(p) } },
                { read, total, elapsed -> main.post { onDetail(read, total, elapsed) } },
            )
            if (file == null) {
                main.post { onFailed("下载失败：请检查车机网络后重试") }
                return@Thread
            }
            main.post {
                percentText.text = "100%"
                bar.progress = 100
                statusText.text = "下载完成，正在安装…"
                bytesText.text = if (entry.size > 0) {
                    "已下载 ${TransferFormat.bytes(entry.size)} / 共 ${TransferFormat.bytes(entry.size)}"
                } else {
                    "已下载 ${TransferFormat.bytes(file.length())}"
                }
            }
            val result = runCatching { installer(file) }.getOrElse {
                AppUpdate.InstallResult.Fail(it.message ?: "安装异常")
            }
            main.post { onInstalledResult(result) }
        }, "cloud-download-${entry.file}").start()
    }

    private fun onPercent(p: Int) {
        if (!busy) return
        percentText.text = TransferFormat.percent(p)
        bar.progress = p.coerceIn(0, 100)
    }

    private fun onDetail(read: Long, total: Long, elapsed: Long) {
        if (!busy) return
        val now = System.currentTimeMillis()
        if (lastDetailAt > 0L) {
            val speed = TransferFormat.speedOf(read - lastRead, elapsed - lastDetailAt)
            if (speed > 0.0) speedText.text = "速度：${TransferFormat.speed(speed)}"
        }
        lastRead = read
        lastDetailAt = elapsed
        // 字节数每 200ms 刷一次，避免几十 KB 一块把主线程刷爆
        if (now - lastUiAt < 200L) return
        lastUiAt = now
        bytesText.text = if (total > 0) {
            "已下载 ${TransferFormat.bytes(read)} / 共 ${TransferFormat.bytes(total)} · 已用 ${elapsed / 1000}s"
        } else {
            "已下载 ${TransferFormat.bytes(read)} · 已用 ${elapsed / 1000}s"
        }
    }

    private fun onInstalledResult(result: AppUpdate.InstallResult) {
        busy = false
        statusText.setTextColor(pal.text)
        when (result) {
            AppUpdate.InstallResult.Silent -> {
                statusText.text = "安装完成"
                resultText.text = "已提交静默安装，装完重启车机生效。"
                percentText.text = "100%"
                bar.progress = 100
                onInstalled?.invoke()
                Toast.makeText(context, "${entry.name}：已提交静默安装", Toast.LENGTH_LONG).show()
            }
            is AppUpdate.InstallResult.Installer -> {
                statusText.text = "已转交系统安装器"
                resultText.text = "安装包：${result.location}\n在弹出的安装界面确认即可。"
                percentText.text = "100%"
                bar.progress = 100
                onInstalled?.invoke()
                Toast.makeText(context, "${entry.name}：请在系统安装界面确认", Toast.LENGTH_LONG).show()
            }
            is AppUpdate.InstallResult.Fail -> onFailed("安装失败：${result.reason}")
        }
    }

    private fun onFailed(reason: String) {
        busy = false
        statusText.text = "失败"
        statusText.setTextColor(DANGER_TEXT)
        resultText.text = reason
        if (retryBtn == null) {
            retryBtn = primaryButton("重试") { startDownload() }
            footer.addView(retryBtn, Ui.linear(-2, 66).also { it.leftMargin = 12 })
        }
        Toast.makeText(context, "${entry.name}：$reason", Toast.LENGTH_LONG).show()
    }
}
