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
 * ## 检测时机：**手动触发**
 *
 * 对话框打开后**不**自己发请求；只有用户点了「检查更新」才联网查一次。
 * 车机常年挂在后台，自动检查既费流量又会在用户没想更新的时候弹窗。
 *
 * ## 下载与安装
 *
 * 下载 → 校验长度和 SHA-256（清单给了才校验）→ 优先**静默安装**
 * （system 变体有 `INSTALL_PACKAGES`）；静默不行就退到系统安装器
 * （普通安装包唯一能走的路）。
 */
class UpdateDialog(context: Context, pal: Palette) :
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
        content.addView(line("检测需手动触发：不查后台、也不会自动下载。"), Ui.linear(-1, -2))
        content.addView(status, Ui.linear(-1, 36))
        // 详情放滚动区里 —— release 说明可能很长，不能把按钮顶出面板
        val detailScroll = ScrollView(context)
        detailScroll.addView(detail, FrameLayout.LayoutParams(-1, -2))
        content.addView(detailScroll, Ui.linear(-1, 0, 1f, 0, 8))
        content.addView(bar, Ui.linear(-1, 60, 0f, 0, 12))
        content.addView(footer, Ui.linear(-1, 66, 0f, 0, 18))
        footer.addView(primaryButton("检查更新") { check() }, Ui.linear(-2, 62))
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
        content.addView(
            note("重启优先走系统权限的 PowerManager.reboot(null)，失败回退 `su -c reboot`，最后才是 root。"),
            Ui.linear(-1, 90),
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

/**
 * 仪表歌词 / 桌面布局参数。
 *
 * 把这一页放在 SYSTEM tab「界面显示 → 仪表歌词」下，内容分三段
 * 仪表 Overlay（贴到副屏的歌词）、歌词颜色、桌面歌词字号。
 * 这里的滑块全部按**区间与默认值**实现，单位文案也照抄。
 */
class DesktopLayoutDialog(
    context: Context,
    pal: Palette,
    private val prefs: Prefs,
) : DialogSheet(context, pal, 980, 900, "仪表歌词") {

    var onApplied: (() -> Unit)? = null

    init {
        val content = flowContent()
        val scroll = ScrollView(context)
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(inner, FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, Ui.linear(-1, 0, 1f, 0, 8))

        // —— 仪表歌词 ——
        inner.addView(section("仪表歌词"))
        inner.addView(switchLine("仪表 Overlay 歌词", prefs.clusterOverlayEnabled, "将歌词贴到副屏") {
            val from = prefs.clusterOverlayEnabled
            prefs.clusterOverlayEnabled = it
            logSetting("desk_cluster_overlay", "仪表 Overlay 歌词", from, it)
        })
        inner.addView(sliderRow("模块宽度", 30, 100, prefs.overlayWidthPct, "%") {
            val from = prefs.overlayWidthPct
            prefs.overlayWidthPct = it
            logSetting("desk_overlay_w", "模块宽度", from, it, debug = true)
        })
        inner.addView(sliderRow("仪表背景透明度", 0, 100, prefs.overlayBgAlpha, "%") {
            val from = prefs.overlayBgAlpha
            prefs.overlayBgAlpha = it
            logSetting("desk_overlay_bg_alpha", "仪表背景透明度", from, it, debug = true)
        })
        inner.addView(sliderRow("主歌词字号", 12, 72, prefs.overlayMainSp, "") {
            val from = prefs.overlayMainSp
            prefs.overlayMainSp = it
            logSetting("desk_overlay_main_sp", "主歌词字号", from, it, debug = true)
        })
        inner.addView(sliderRow("副歌词字号", 8, 48, prefs.overlaySubSp, "") {
            val from = prefs.overlaySubSp
            prefs.overlaySubSp = it
            logSetting("desk_overlay_sub_sp", "副歌词字号", from, it, debug = true)
        })
        // 第三十二轮起桌面只有两页，`desk_desk_page3_main_sp` / `desk_desk_page3_sub_sp`
        // 这两个「第三页全屏歌词」字号已经没有对应的画面了，设置项随之移除；
        // `Prefs.deskPage3MainSp` / `deskPage3SubSp` 两个键保留，将来要恢复独立播放器页可直接用。

        // —— 歌词颜色 ——
        inner.addView(section("歌词颜色"))
        inner.addView(note("仅作用于仪表盘歌词与进度环，不影响桌面歌词与进度条。"))
        inner.addView(colorRow("主歌词颜色", prefs.clusterLyricMainColor) {
            val from = prefs.clusterLyricMainColor
            prefs.clusterLyricMainColor = it
            logSetting("desk_cluster_lyric_main_color", "主歌词颜色", from, it)
        })
        inner.addView(colorRow("副歌词颜色", prefs.clusterLyricSubColor) {
            val from = prefs.clusterLyricSubColor
            prefs.clusterLyricSubColor = it
            logSetting("desk_cluster_lyric_sub_color", "副歌词颜色", from, it)
        })

        // —— 桌面歌词字号 ——
        inner.addView(section("桌面歌词字号"))
        inner.addView(note("第二页音乐卡收起/展开；单位为设计像素。"))
        inner.addView(sliderRow("收起·主歌词", 14, 64, prefs.deskCompactMainSp, "") {
            val from = prefs.deskCompactMainSp
            prefs.deskCompactMainSp = it
            logSetting("desk_desk_page3_main_sp", "收起·主歌词", from, it, debug = true)
        })
        inner.addView(sliderRow("收起·副歌词", 10, 48, prefs.deskCompactSubSp, "") {
            val from = prefs.deskCompactSubSp
            prefs.deskCompactSubSp = it
            logSetting("desk_desk_page3_sub_sp", "收起·副歌词", from, it, debug = true)
        })
        inner.addView(sliderRow("展开·主歌词", 14, 64, prefs.deskExpandedMainSp, "") {
            val from = prefs.deskExpandedMainSp
            prefs.deskExpandedMainSp = it
            logSetting("desk_desk_page3_main_sp", "展开·主歌词", from, it, debug = true)
        })
        inner.addView(sliderRow("展开·副歌词", 10, 48, prefs.deskExpandedSubSp, "") {
            val from = prefs.deskExpandedSubSp
            prefs.deskExpandedSubSp = it
            logSetting("desk_desk_page3_sub_sp", "展开·副歌词", from, it, debug = true)
        })

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(footer, Ui.linear(-1, 66, 0f, 0, 16))
        footer.addView(chip("取消", sizePx = 24f) { onClose?.invoke() }, Ui.linear(-2, 62, 0f, 12))
        footer.addView(primaryButton("应用") {
            // 排障场景：仪表歌词参数改了没生效 —— 记下应用动作
            Logx.event(LogTags.SETTING, "应用仪表歌词/布局参数")
            onApplied?.invoke()
            onClose?.invoke()
        }, Ui.linear(-2, 62))
    }

    /**
     * 记录一次设置变更。
     *
     * 排障场景：设置页/对话框里改了参数但没生效 —— 用 key + 旧值 + 新值确认写进了哪个偏好键。
     *
     * @param debug 滑杆拖动属于高频操作，用 DEBUG 只进 Logcat、不落盘。
     */
    private fun logSetting(key: String, name: String, from: Any?, to: Any?, debug: Boolean = false) {
        if (debug) {
            Logx.d(LogTags.SETTING, "设置变更", "key" to key, "name" to name, "from" to from, "to" to to)
        } else {
            Logx.i(LogTags.SETTING, "设置变更", "key" to key, "name" to name, "from" to from, "to" to to)
        }
    }

    /** 带副标题的开关行（`toggle(label, subtitle, value, action)`）。 */
    private fun switchLine(
        label: String,
        checked: Boolean,
        subtitle: String? = null,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 0)
        }
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(Ui.text(context, label, 26f, pal.text, bold = true), Ui.linear(-1, -2))
        if (subtitle != null) {
            textCol.addView(Ui.text(context, subtitle, 19f, pal.textFaint), Ui.linear(-1, -2).apply { topMargin = 4 })
        }
        row.addView(textCol, Ui.linear(0, -2, 1f))
        // 设计板 `.switch`：84×48
        val toggle = FlatSwitch(context).apply {
            accent = pal.accent
            on = checked
            setOnClickListener {
                on = !on
                onChange(on)
            }
        }
        row.addView(toggle, Ui.linear(FlatSwitch.SWITCH_W, FlatSwitch.SWITCH_H))
        row.layoutParams = Ui.linear(-1, if (subtitle == null) 88 else 112)
        return row
    }

    /** 滑块行（`slider(label, min, max, value, unit, action)`）。 */
    private fun sliderRow(
        label: String,
        min: Int,
        max: Int,
        value: Int,
        unit: String,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 0)
        }
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(Ui.text(context, label, 22f, pal.text), Ui.linear(-1, -2))
        val valueText = Ui.text(context, "$value$unit", 19f, pal.textDim)
        textCol.addView(valueText, Ui.linear(-1, -2).apply { topMargin = 4 })
        row.addView(textCol, Ui.linear(0, -2, 1f))
        val seek = android.widget.SeekBar(context).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p
                    valueText.text = "$v$unit"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
            })
        }
        row.addView(seek, Ui.linear(420, 48))
        row.layoutParams = Ui.linear(-1, 80)
        return row
    }

    /** 颜色行（`clusterColorRow(label, color, isMain)`）。 */
    private fun colorRow(label: String, color: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 0)
            isClickable = true
        }
        row.addView(Ui.text(context, label, 22f, pal.text), Ui.linear(0, -2, 1f))
        val swatch = View(context).apply {
            background = Ui.round(if (color == 0) pal.withAlpha(pal.text, 0x33) else color, 12f, 1f, pal.stroke)
        }
        row.addView(swatch, Ui.linear(56, 36).apply { rightMargin = 12 })
        row.addView(Ui.text(context, if (color == 0) "跟随主题" else "#%08X".format(color), 19f, pal.textDim), Ui.linear(-2, -2))
        row.setOnClickListener {
            val dialog = ColorPickerDialog(context, pal, if (color == 0) pal.text else color, label)
            // 取色器是**整屏弹层**（面板 1040×660 摆在 (440,170)），
            // 挂在被点的行里会被行高裁掉，所以挂到本对话框的根上。
            val host = this@DesktopLayoutDialog
            dialog.onPicked = { picked ->
                swatch.background = Ui.round(picked, 12f, 1f, pal.stroke)
                onChange(picked)
                host.removeView(dialog)
            }
            dialog.onClose = { host.removeView(dialog) }
            host.addView(dialog, FrameLayout.LayoutParams(-1, -1))
        }
        row.layoutParams = Ui.linear(-1, 76)
        return row
    }
}
