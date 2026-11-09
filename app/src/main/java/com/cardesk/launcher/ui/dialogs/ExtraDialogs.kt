package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import com.cardesk.launcher.cluster.ClusterLyricsService
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.update.AppUpdate
import com.cardesk.launcher.core.update.R2Release
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback

/**
 * 主题色选择：预设色块 + 色相滑杆 + 预览。
 * 结果写入 `prefs.accentColor`，LauncherView 用它覆盖 Palette 的强调色。
 *
 * 第四十九轮按设计板 `s09-color` 重绘：面板 1040×**660** 摆在 (440,170)，
 * 标题 38px bold (40,34)，8 个预设色块 150×150 圆角 28
 * （left = 60 + c*190、top = 150 + r*190，选中项 4px accent 描边），
 * 1px 分割线 (60,540) 宽 920，「自定义」标签 (60,568)，hex 输入框 (60,596) 920 宽。
 */
class ColorPickerDialog(
    context: Context,
    pal: Palette,
    private val initial: Int,
    title: String = "强调色",
    /**
     * 「恢复默认」按钮的目标色。
     *
     * 强调色默认回到 `Skin.ACCENT`；卡片颜色（第五十八轮）传 0 = **跟随主题**。
     */
    private val defaultColor: Int = Skin.ACCENT,
) : DialogSheet(context, pal, 1040, 660, title, 440, 170) {

    var onPicked: ((Int) -> Unit)? = null

    /**
     * 色相。
     *
     * —— 从当前颜色反推 HSV
     * `Color.colorToHSV(color | 0xFF000000, hsv)`，再取 hue/sat/val。
     * 之前我们写死 218f，导致「打开已有主题色的取色器时滑块位置是错的，
     * 一拖就跳色」。
     */
    private var hue: Float = hsvOf(initial)[0]

    /** 饱和度同样从当前颜色反推（灰度色相没意义时退回 0.85）。 */
    private var sat: Float = hsvOf(initial)[1].takeIf { it > 0.01f } ?: 0.85f

    private var picked = initial

    private val swatch = View(context)
    private val hueLabel = Ui.text(context, "", 19f, pal.textDim)

    init {
        // 标题（设计板 s09：(40,34) 38px bold）
        addTitle(title, 38f, 40, 34)

        // 右上角按钮组：「恢复默认 / 取消 / 应用」（设计板 s09 没画这排，但三个功能必须保留）
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            chip("恢复默认", sizePx = 20f, padH = 18, padV = 14) {
                val from = picked
                picked = defaultColor
                // 滑杆也要跟着回到默认色的色相/饱和度，否则标签和拖动结果对不上
                val hsv = hsvOf(if (defaultColor == 0) Skin.surface else defaultColor)
                hue = hsv[0]
                sat = hsv[1].takeIf { it > 0.01f } ?: 0.85f
                // 排障场景：主题色恢复默认
                Logx.event(
                    LogTags.SETTING,
                    "主题色恢复默认",
                    "from" to String.format("#%06X", from and 0xFFFFFF),
                    "to" to String.format("#%06X", Skin.ACCENT and 0xFFFFFF),
                )
                refresh()
            },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 },
        )
        actions.addView(
            chip("取消", sizePx = 20f, padH = 18, padV = 14) { onClose?.invoke() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 },
        )
        actions.addView(
            primaryButton("应用", 20f) {
                // 排障场景：主题色改了没生效 —— 记下最终写入的色值
                Logx.event(
                    LogTags.SETTING,
                    "应用主题色",
                    "color" to String.format("#%06X", picked and 0xFFFFFF),
                    "hue" to hue.toInt(),
                )
                onPicked?.invoke(picked)
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        placeEnd(actions, 36, 30)

        // 色相滑杆 + 预览色块（设计板 s09 没画这两样，但它们是既有功能）
        val hueRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hueRow.addView(Ui.text(context, "色相", 20f, pal.textFaint), LinearLayout.LayoutParams(60, -2))
        val seek = SeekBar(context).apply {
            max = 360
            progress = hue.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val from = picked
                    hue = progress.toFloat()
                    picked = Color.HSVToColor(floatArrayOf(hue, sat, 0.95f))
                    // 排障场景：色相滑杆拖动 —— 记下色值变化（这是高频拖动，用 DEBUG）
                    Logx.d(
                        LogTags.SETTING,
                        "调整主题色相",
                        "hue" to progress,
                        "from" to String.format("#%06X", from and 0xFFFFFF),
                        "to" to String.format("#%06X", picked and 0xFFFFFF),
                    )
                    refresh()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        hueRow.addView(seek, LinearLayout.LayoutParams(520, 44))
        hueRow.addView(hueLabel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 16 })
        hueRow.addView(swatch, LinearLayout.LayoutParams(48, 48).apply { leftMargin = 16 })
        place(hueRow, 60, 96, 920, 48)

        // 8 个预设色块：150×150 圆角 28，left = 60 + c*190、top = 150 + r*190，选中项 4px accent 描边
        // 第五十一轮：色值改成设计板 的 8 个预设（低饱和一档），
        // 之前用的是自拟的高饱和版，与设计板逐格对不上。
        val presets = listOf(
            0xFF2FBFAF.toInt(), 0xFF4E7FB8.toInt(), 0xFFC9913F.toInt(), 0xFFC9585F.toInt(),
            0xFF8075B8.toInt(), 0xFF5A9968.toInt(), 0xFF9A6FA8.toInt(), 0xFF6E7885.toInt(),
        )
        presets.forEachIndexed { index, color ->
            val dot = View(context).apply {
                background = Ui.round(color, 28f, if (index == 0) 4f else 0f, if (index == 0) pal.accent else 0)
                setOnClickListener {
                    val from = picked
                    picked = color
                    // 排障场景：主题色选了没变 —— 记下选中的色值
                    Logx.event(
                        LogTags.SETTING,
                        "选择预设主题色",
                        "from" to String.format("#%06X", from and 0xFFFFFF),
                        "to" to String.format("#%06X", color and 0xFFFFFF),
                    )
                    refresh()
                }
            }
            val row = index / 4
            val col = index % 4
            place(dot, 60 + col * 190, 150 + row * 190, PRESET_SIZE, PRESET_SIZE)
        }

        // 1px 分割线 (60,540) 宽 920
        addHairline(60, 540, 920)
        // 「自定义」标签 (60,568) 20px textFaint
        place(Ui.text(context, "自定义", 20f, pal.textFaint), 60, 568)
        // hex 输入框 (60,596) 920 宽：设计板是 70 高（596+70 = 666，超出 660 高的面板 6px），这里收到 64 正好贴住面板底边
        val hexInput = EditText(context).apply {
            setText(String.format("#%06X", picked and 0xFFFFFF))
            setTextColor(pal.textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f)
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)
            setPadding(20, 0, 20, 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val raw = s?.toString()?.trim()?.removePrefix("#") ?: ""
                    val v = raw.toIntOrNull(16) ?: return
                    picked = 0xFF000000.toInt() or v
                    refresh(skipHex = true)
                }
            })
        }
        place(hexInput, 60, 596, 920, 64)

        refresh()
    }

    private fun refresh(skipHex: Boolean = false) {
        swatch.background = Ui.round(picked, 12f, 1f, Skin.NAV_STROKE)
        hueLabel.text = "色相 ${hue.toInt()}° · 饱和度 ${(sat * 100).toInt()}%"
    }
}

private const val PRESET_SIZE = 150

/**
 * 颜色 → HSV。
 *
 * 强制补 alpha（`color | 0xFF000000`），保证反推出来的色相是纯色相。
 */
private fun hsvOf(color: Int): FloatArray {
    val hsv = FloatArray(3)
    Color.colorToHSV(color or 0xFF000000.toInt(), hsv)
    return hsv
}

/**
 * 在线更新：拉 R2 上的更新清单 → 有新版本就下载并安装（第六十九轮重写，第八十三轮换源）。
 *
 * ## 更新源
 *
 * 原来是让用户手填一个 APK 地址（`prefs.updateUrl`）—— 填错就更新不了。
 * 接着固定检测**本项目自己的 GitHub Release**；
 * 第八十三轮改成 **Cloudflare R2 自定义域**（`file.chuxi.bid`）：
 * 拉 `cardesk/latest.json` → 读出版本和安装包地址 → 和车机上装着的版本比。
 *
 * 车机在国内访问 `api.github.com` 又慢又不稳，R2 走 Cloudflare 自己的 CDN。
 * 清单格式见 `core/update/R2Release.kt`。
 *
 * ## 检测时机：启动自动检测一次 + 手动
 *
 * 桌面启动 3 秒后自动检测一次（只查清单，不自动下载）；用户也可以在对话框里
 * 点「检查更新」手动再查。下载始终要用户点按钮才会开始。
 *
 * ## 下载与安装
 *
 * 下载 → 校验长度和 SHA-256（清单给了才校验）→ 优先**静默安装**
 * （system 变体有 `INSTALL_PACKAGES`）；静默不行就退到系统安装器
 * （普通安装包唯一能走的路）。
 */
class UpdateDialog(
    context: Context,
    pal: Palette,
    /** 非空 = 打开就自动下载这个版本（启动检测到新版、用户点「立即更新」时用）。 */
    private val autoDownload: R2Release? = null,
) :
    DialogSheet(context, pal, 860, 720, "在线更新") {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val status = Ui.text(context, "状态：尚未检查", 21f, pal.textDim)
    private val detail = Ui.text(context, "", 19f, pal.textFaint).apply { setLineSpacing(8f, 1f) }
    private val bar = SeekBar(context).apply {
        isEnabled = false
        // 没开始下载时不显示那条空进度条
        visibility = View.GONE
    }
    private val footer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 有新版时才出现的「下载并更新」。 */
    private var downloadBtn: TextView? = null

    init {
        val content = flowContent()
        content.addView(section("更新详情"))
        // 这几行都用可换行高度 —— `note()` 是给单行标签用的，长了会被裁掉
        content.addView(line("当前版本：${AppUpdate.versionLabel(context)}"), Ui.linear(-1, -2))
        content.addView(line("更新源：Cloudflare R2 · ${AppUpdate.R2_HOST}"), Ui.linear(-1, -2))
        content.addView(status, Ui.linear(-1, 36))
        // 详情放滚动区里 —— release 说明可能很长，不能把按钮顶出面板
        val detailScroll = ScrollView(context)
        detailScroll.addView(detail, FrameLayout.LayoutParams(-1, -2))
        content.addView(detailScroll, Ui.linear(-1, 0, 1f, 0, 8))
        content.addView(bar, Ui.linear(-1, 60, 0f, 0, 12))
        content.addView(footer, Ui.linear(-1, 66, 0f, 0, 18))
        footer.addView(primaryButton("检查更新") { check() }, Ui.linear(-2, 62))
        // 启动检测弹窗点「立即更新」进来的：直接把版本信息摆好并开始下载
        autoDownload?.let { release ->
            handler.post {
                onChecked(AppUpdate.CheckResult.Available(release, AppUpdate.versionLabel(context)))
                download(release)
            }
        }
    }

    /** 一行弱文字（可换行）。 */
    private fun line(text: String): TextView =
        Ui.text(context, text, 19f, pal.textFaint).apply { setLineSpacing(6f, 1f) }

    /** 手动触发检查。 */
    private fun check() {
        status.text = "状态：检查中…"
        detail.text = ""
        bar.progress = 0
        bar.visibility = View.GONE
        downloadBtn?.let { footer.removeView(it) }
        downloadBtn = null
        // 排障场景：用户点了检查更新 —— 记下这次是**手动**触发
        Logx.event(LogTags.SETTING, "检查更新（手动触发）", "url" to AppUpdate.MANIFEST_URL)
        Thread {
            val result = AppUpdate.check(context)
            handler.post { onChecked(result) }
        }.start()
    }

    private fun onChecked(result: AppUpdate.CheckResult) {
        when (result) {
            is AppUpdate.CheckResult.UpToDate -> {
                status.text = "状态：已是最新版本（${result.current}）"
                detail.text = "线上最新：${result.latestVersion}\n${AppUpdate.MANIFEST_URL}"
            }
            is AppUpdate.CheckResult.Available -> {
                val r = result.release
                val asset = r.apk
                status.text = "状态：发现新版本 ${r.version}"
                val notes = r.notes.trim().take(600)
                detail.text = buildString {
                    append(r.name.ifBlank { r.tag.ifBlank { r.version } })
                    if (asset != null) {
                        append("\n安装包：${asset.name}")
                        if (asset.size > 0) append("（${asset.size / 1024 / 1024.0f} MB）")
                    }
                    if (notes.isNotEmpty()) append("\n\n$notes")
                    if (asset != null) append("\n\n下载地址：${asset.url}")
                }
                val btn = primaryButton("下载并更新") { download(r) }
                downloadBtn = btn
                footer.addView(btn, Ui.linear(-2, 62, 0f, 12))
            }
            is AppUpdate.CheckResult.Failed -> {
                status.text = "状态：检查失败"
                detail.text = "${result.reason}\n\n检查更新需要车机能访问 ${AppUpdate.R2_HOST}。"
            }
        }
    }

    private fun download(release: R2Release) {
        status.text = "状态：下载中…"
        bar.visibility = View.VISIBLE
        bar.progress = 0
        downloadBtn?.let { footer.removeView(it) }
        downloadBtn = null
        Thread {
            val file = AppUpdate.download(context, release) { p ->
                handler.post { bar.progress = p }
            }
            if (file == null) {
                handler.post {
                    status.text = "状态：下载失败"
                    detail.text = "没下下来，请检查车机网络后重试。"
                }
                return@Thread
            }
            handler.post { status.text = "状态：下载完成，正在安装…" }
            val result = AppUpdate.install(context, file)
            handler.post { onInstalled(result) }
        }.start()
    }

    private fun onInstalled(result: AppUpdate.InstallResult) {
        when (result) {
            AppUpdate.InstallResult.Silent -> {
                status.text = "状态：已提交静默安装"
                detail.text = "装完重启车机生效。"
            }
            is AppUpdate.InstallResult.Installer -> {
                status.text = "状态：已转交系统安装器"
                detail.text = "安装包：${result.location}\n在弹出的安装界面确认即可。"
            }
            is AppUpdate.InstallResult.Fail -> {
                status.text = "状态：安装未完成"
                detail.text = result.reason
            }
        }
    }
}

/** 重启确认：有 su 走 root，否则提示需要系统权限。 */
class RestartDialog(context: Context, pal: Palette) : DialogSheet(context, pal, 720, 440, "重启确认") {

    init {
        val content = flowContent()
        content.addView(
            Ui.text(context, "重启会让桌面与所有应用退出，确认现在重启吗？", 24f, pal.text),
            Ui.linear(-1, 80),
        )
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(footer, Ui.linear(-1, 66, 0f, 0, 16))
        footer.addView(chip("取消", sizePx = 24f) { onClose?.invoke() }, Ui.linear(-2, 62, 0f, 12))
        footer.addView(primaryButton("立即重启") {
            // 排障场景：点了「立即重启」车机没重启 —— 记下这次尝试
            Logx.event(LogTags.SETTING, "确认重启车机")
            val ok = com.cardesk.launcher.core.SystemOps.reboot(context) || runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")).waitFor() == 0
            }.getOrDefault(false)
            // 排障场景：重启命令有没有下发成功
            if (ok) {
                Logx.i(LogTags.SETTING, "重启命令已下发")
            } else {
                Logx.w(LogTags.SETTING, "重启命令下发失败：无 root/系统权限")
            }
            android.widget.Toast.makeText(
                context,
                if (ok) "已发送重启命令" else "没有 root/系统权限，无法重启",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            onClose?.invoke()
        }, Ui.linear(-2, 62))
    }
}
