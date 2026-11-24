package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.ui.pressFeedback
import kotlin.math.roundToInt

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

    /** 预览色块当前显示的颜色（动画中间态也是这个值）。 */
    private var swatchColor = initial

    /** 8 个预设色（设计板 s09 的低饱和一档）。 */
    private val presets = listOf(
        0xFF2FBFAF.toInt(), 0xFF4E7FB8.toInt(), 0xFFC9913F.toInt(), 0xFFC9585F.toInt(),
        0xFF8075B8.toInt(), 0xFF5A9968.toInt(), 0xFF9A6FA8.toInt(), 0xFF6E7885.toInt(),
    )

    /** 预设色块（视图）—— 用来做选中态动画。 */
    private val presetDots = mutableListOf<View>()

    /** 每个色块当前的描边宽度（描边渐变动画的起点）。 */
    private val presetStrokeWidth = FloatArray(presets.size)

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
                refreshWithNudge()
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

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    // 拖动过程中色相一直在变，每变一次就顶一下的话标签会一直抖；
                    // 拖完（松手）再顶一下，告诉用户「停在这了」。
                    Motion.nudge(hueLabel)
                }
            })
        }
        hueRow.addView(seek, LinearLayout.LayoutParams(520, 44))
        hueRow.addView(hueLabel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 16 })
        hueRow.addView(swatch, LinearLayout.LayoutParams(48, 48).apply { leftMargin = 16 })
        place(hueRow, 60, 96, 920, 48)

        // 8 个预设色块：150×150 圆角 28，left = 60 + c*190、top = 150 + r*190，选中项 4px accent 描边
        // 第五十一轮：色值改成设计板 的 8 个预设（低饱和一档），
        // 之前用的是自拟的高饱和版，与设计板逐格对不上。
        presets.forEachIndexed { index, color ->
            val selected = isPresetSelected(index)
            val dot = View(context).apply {
                background = Ui.round(color, PRESET_RADIUS, if (selected) PRESET_STROKE_PX else 0f, if (selected) pal.accent else 0)
                presetStrokeWidth[index] = if (selected) PRESET_STROKE_PX else 0f
                // 色块是能点的（点了就换主题色）—— 按压反馈不能少
                pressFeedback()
                setOnClickListener { dotView ->
                    val from = picked
                    picked = color
                    // 排障场景：主题色选了没变 —— 记下选中的色值
                    Logx.event(
                        LogTags.SETTING,
                        "选择预设主题色",
                        "from" to String.format("#%06X", from and 0xFFFFFF),
                        "to" to String.format("#%06X", color and 0xFFFFFF),
                    )
                    // 选中的那一颗「鼓一下」：点下去之后要有一个明确的回音，
                    // 用户才知道「这一颗被我选中了」。
                    Motion.bump(dotView)
                    refreshPresetBorders()
                    refreshWithNudge()
                }
            }
            presetDots += dot
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
        // 预览色块的颜色**渐变**过去 —— 直接换色是瞬间的，
        // 用户只会觉得「闪了一下」，看不出是「换了一个颜色」。
        animateSwatch(picked)
        hueLabel.text = "色相 ${hue.toInt()}° · 饱和度 ${(sat * 100).toInt()}%"
    }

    /**
     * 刷新预览，并让读数顶一下。
     *
     * 「读数变了」是要被看见的 —— 数字直接换掉的话，
     * 余光根本抓不到「色相从 218 变成 240 了」。
     */
    private fun refreshWithNudge() {
        refresh()
        Motion.nudge(hueLabel)
    }

    /**
     * 预览色块渐变到目标颜色。
     *
     * 用 [Motion.animateProgress] + [MotionSpec.lerpColor]：
     * 每一帧把当前显示色插值到目标色，**就地改** drawable 的颜色
     * （换整个 drawable 会连圆角一起换掉，色块会「变形」一下）。
     */
    private fun animateSwatch(target: Int) {
        val from = swatchColor
        if (from == target) {
            // 颜色没变：把底色补上（第一次刷新时还没有 drawable）
            applySwatchColor(from)
            return
        }
        Motion.animateProgress(swatch, MotionSpec.STATE_BUMP_DURATION) { t ->
            swatchColor = MotionSpec.lerpColor(from, target, t)
            applySwatchColor(swatchColor)
        }
    }

    /** 把预览色块涂成指定颜色。 */
    private fun applySwatchColor(color: Int) {
        val drawable = swatch.background as? GradientDrawable
        if (drawable == null) {
            swatch.background = Ui.round(color, SWATCH_RADIUS, 1f, Skin.NAV_STROKE)
            return
        }
        drawable.setColor(color)
    }

    /**
     * 预设色块的选中描边。
     *
     * **选中规则是既有逻辑**（永远是第一个色块），
     * 这里**不改**它 —— 改了就是改行为。
     * 这里只负责把「描边变了」这件事**做成动画**：
     * 颜色瞬变在余光里抓不住，渐变过去才看得出「选中态换了」。
     */
    private fun refreshPresetBorders() {
        presetDots.forEachIndexed { index, dot ->
            val selected = isPresetSelected(index)
            val target = if (selected) PRESET_STROKE_PX else 0f
            val from = presetStrokeWidth[index]
            if (from == target) return@forEachIndexed
            val fromColor = if (from > 0f) pal.accent else 0
            val toColor = if (selected) pal.accent else 0
            Motion.animateProgress(
                dot,
                MotionSpec.STATE_BUMP_DURATION,
                onEnd = {
                    val drawable = dot.background as? GradientDrawable
                    drawable?.setStroke(target.toInt(), if (selected) pal.accent else 0)
                    presetStrokeWidth[index] = target
                },
            ) { t ->
                val width = (from + (target - from) * t).roundToInt()
                val drawable = dot.background as? GradientDrawable
                drawable?.setStroke(width, MotionSpec.lerpColor(fromColor, toColor, t))
            }
        }
    }

    /** 预设色块的选中规则（既有逻辑：第一个色块带强调色描边）。 */
    private fun isPresetSelected(index: Int): Boolean = index == 0
}

private const val PRESET_SIZE = 150

/** 预设色块圆角（设计 px）。 */
private const val PRESET_RADIUS = 28f

/** 预设色块选中时的描边宽度（设计 px）。 */
private const val PRESET_STROKE_PX = 4f

/** 预览色块圆角（设计 px）。 */
private const val SWATCH_RADIUS = 12f

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
    /**
     * 更新通道（第三百零九轮）：`stable` 正式版 / `preview` 开发版。
     *
     * 由设置页的「更新渠道」下拉决定，存在 [com.cardesk.launcher.core.Prefs.updateChannel]；
     * 这里只读不管写 —— 弹窗里换通道会让人以为「换个源再查」，实际会丢当前检查状态。
     */
    private val channel: String = AppUpdate.CHANNEL_STABLE,
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
        content.addView(
            line(
                "更新源：Cloudflare R2 · ${AppUpdate.R2_HOST}" +
                    "（${AppUpdate.channelLabel(channel)}）",
            ),
            Ui.linear(-1, -2),
        )
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

    /**
     * 写状态行：换文案 + 顶一下。
     *
     * 状态行是这个面板上唯一的**读数**（「检查中 / 下载中 / 已完成」）。
     * 文字直接换掉的话，用户只会觉得「界面闪了一下」；
     * 顶一下才读得出「状态变了」。
     */
    private fun setStatus(text: String) {
        if (status.text != text) Motion.nudge(status)
        status.text = text
    }

    /** 手动触发检查。 */
    private fun check() {
        setStatus("状态：检查中…")
        detail.text = ""
        bar.progress = 0
        bar.visibility = View.GONE
        downloadBtn?.let { footer.removeView(it) }
        downloadBtn = null
        // 排障场景：用户点了检查更新 —— 记下这次是**手动**触发
        Logx.event(
            LogTags.SETTING, "检查更新（手动触发）",
            "channel" to AppUpdate.channelLabel(channel),
            "url" to AppUpdate.manifestUrl(channel),
        )
        Thread {
            // 手动检查：允许「跨通道」直接切换（切了更新源就不比版本号大小）
            val result = AppUpdate.check(context, channel, allowBranchSwitch = true)
            handler.post { onChecked(result) }
        }.start()
    }

    private fun onChecked(result: AppUpdate.CheckResult) {
        when (result) {
            is AppUpdate.CheckResult.UpToDate -> {
                setStatus("状态：已是最新版本（${result.current}）")
                detail.text = "线上最新：${result.latestVersion}\n${AppUpdate.manifestUrl(channel)}"
            }
            is AppUpdate.CheckResult.Available -> {
                val r = result.release
                val asset = r.apk
                val target = AppUpdate.channelLabel(channel)
                val installed = AppUpdate.channelLabel(AppUpdate.installedChannel(context))
                if (result.branchSwitch) {
                    // 跨通道换包：两条分支各自递增版本号，谁大谁小没有意义 →
                    // 不提示「新版本」，直接给「切换到这一支」的入口。
                    setStatus("状态：可切换到$target（${r.version}）")
                } else {
                    setStatus("状态：发现新版本 ${r.version}")
                }
                val notes = r.notes.trim().take(600)
                detail.text = buildString {
                    append(r.name.ifBlank { r.tag.ifBlank { r.version } })
                    if (result.branchSwitch) {
                        append("\n\n当前安装的是$installed，选中的是$target。")
                        append("两条通道的版本号各自递增，跨通道不比较大小，")
                        append("这里直接把$target 的包换上来（装完版本号可能变小，属正常）。")
                    }
                    if (asset != null) {
                        append("\n安装包：${asset.name}")
                        if (asset.size > 0) append("（${asset.size / 1024 / 1024.0f} MB）")
                    }
                    if (notes.isNotEmpty()) append("\n\n$notes")
                    if (asset != null) append("\n\n下载地址：${asset.url}")
                }
                val btn = primaryButton(
                    if (result.branchSwitch) "下载并切换" else "下载并更新",
                ) { download(r, allowDowngrade = result.branchSwitch) }
                downloadBtn = btn
                footer.addView(btn, Ui.linear(-2, 62, 0f, 12))
            }
            is AppUpdate.CheckResult.Failed -> {
                setStatus("状态：检查失败")
                detail.text = "${result.reason}\n\n检查更新需要车机能访问 ${AppUpdate.R2_HOST}。"
            }
        }
    }

    private fun download(release: R2Release, allowDowngrade: Boolean = false) {
        setStatus("状态：下载中…")
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
                    setStatus("状态：下载失败")
                    detail.text = "没下下来，请检查车机网络后重试。"
                }
                return@Thread
            }
            handler.post { setStatus("状态：下载完成，正在安装…") }
            val result = AppUpdate.install(context, file, allowDowngrade)
            handler.post { onInstalled(result) }
        }.start()
    }

    private fun onInstalled(result: AppUpdate.InstallResult) {
        when (result) {
            AppUpdate.InstallResult.Silent -> {
                setStatus("状态：已提交静默安装")
                detail.text = "装完重启车机生效。"
            }
            is AppUpdate.InstallResult.Installer -> {
                setStatus("状态：已转交系统安装器")
                detail.text = "安装包：${result.location}\n在弹出的安装界面确认即可。"
            }
            is AppUpdate.InstallResult.Fail -> {
                setStatus("状态：安装未完成")
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
