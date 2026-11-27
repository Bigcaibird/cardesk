package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.net.TransferFormat
import com.cardesk.launcher.core.wallpaperstore.OnlineWallpaperEntry
import com.cardesk.launcher.core.wallpaperstore.WallpaperKind
import com.cardesk.launcher.core.wallpaperstore.WallpaperStore
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.pressFeedback
import java.io.File
import java.util.concurrent.Executors

/**
 * 在线壁纸资源浏览弹窗（Cloudflare R2：`cardesk/wallpapers.json`）。
 *
 * 支持按「全部 / 静态壁纸 / 动态壁纸」筛选，异步拉取清单与预览缩略图，
 * 点选后通过内嵌 [WallpaperDownloadDialog] 显示实时进度并下载应用到桌面。
 */
class OnlineWallpaperDialog(
    context: Context,
    pal: Palette,
    private val fetcher: () -> WallpaperStore.FetchResult = {
        WallpaperStore.fetchIndex(context.applicationContext)
    },
    private val onApplied: (WallpaperKind, Boolean) -> Unit = { _, _ -> },
) : DialogSheet(context, pal, 1380, 820, "在线壁纸资源") {

    private enum class Filter(val label: String) {
        ALL("全部"),
        STATIC("静态壁纸"),
        VIDEO("动态壁纸"),
    }

    private val main = Handler(Looper.getMainLooper())
    private val thumbExecutor = Executors.newFixedThreadPool(2)

    private var alive = true
    private var filter: Filter = Filter.ALL
    private var allItems: List<OnlineWallpaperEntry> = emptyList()
    private var downloadDialog: WallpaperDownloadDialog? = null

    private val filterRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val statusText = Ui.text(context, "正在从 Cloudflare R2 拉取壁纸清单…", 20f, pal.textDim)
    private val gridBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        addTitle()
        addClose()

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(filterRow, LinearLayout.LayoutParams(0, -2, 1f))
        val refreshBtn = primaryButton("刷新列表", 20f) { refresh() }
        topBar.addView(refreshBtn, LinearLayout.LayoutParams(-2, 54).also { it.leftMargin = 14 })
        place(topBar, 36, 118, panelWidth - 72, 60)

        place(statusText, 36, 186, panelWidth - 72, 34)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(gridBox, FrameLayout.LayoutParams(-1, -2))
        }
        place(scroll, 36, 228, panelWidth - 72, panelHeight - 228 - 32)

        renderFilters()
        main.post { refresh() }
    }

    override fun onDetachedFromWindow() {
        alive = false
        thumbExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun renderFilters() {
        filterRow.removeAllViews()
        for (f in Filter.values()) {
            val active = f == filter
            val count = when (f) {
                Filter.ALL -> allItems.size
                Filter.STATIC -> allItems.count { it.kind == WallpaperKind.STATIC }
                Filter.VIDEO -> allItems.count { it.kind == WallpaperKind.VIDEO }
            }
            val label = if (allItems.isNotEmpty()) "${f.label} ($count)" else f.label
            val btn = Ui.text(
                context,
                label,
                20f,
                if (active) pal.accentInk else pal.textDim,
                bold = active,
            ).apply {
                gravity = Gravity.CENTER
                background = if (active) {
                    Ui.round(pal.accent, Shape.PILL)
                } else {
                    Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
                }
                setPadding(24, 10, 24, 10)
                pressFeedback()
                setOnClickListener {
                    if (filter == f) return@setOnClickListener
                    filter = f
                    renderFilters()
                    renderGrid()
                }
            }
            filterRow.addView(btn, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = 12 })
        }
    }

    private fun refresh() {
        statusText.text = "正在从 Cloudflare R2（${WallpaperStore.HOST}）拉取壁纸清单…"
        Thread({
            val res = fetcher()
            main.post {
                if (!alive) return@post
                when (res) {
                    is WallpaperStore.FetchResult.Ok -> {
                        allItems = res.index.items
                        val staticCount = allItems.count { it.kind == WallpaperKind.STATIC }
                        val videoCount = allItems.count { it.kind == WallpaperKind.VIDEO }
                        val date = res.index.updatedAt.take(10)
                        statusText.text = if (allItems.isEmpty()) {
                            "R2 暂无在线壁纸，请用 tools/publish_r2.py --wallpaper 上传后点刷新"
                        } else {
                            "共 ${allItems.size} 项（静态 $staticCount · 动态 $videoCount）" +
                                if (date.isNotEmpty()) " · 更新于 $date" else ""
                        }
                        renderFilters()
                        renderGrid()
                    }
                    is WallpaperStore.FetchResult.Failed -> {
                        statusText.text = "拉取壁纸清单失败：${res.reason}"
                    }
                }
            }
        }, "online-wallpaper-index").start()
    }

    private fun renderGrid() {
        gridBox.removeAllViews()
        val visible = when (filter) {
            Filter.ALL -> allItems
            Filter.STATIC -> allItems.filter { it.kind == WallpaperKind.STATIC }
            Filter.VIDEO -> allItems.filter { it.kind == WallpaperKind.VIDEO }
        }
        if (visible.isEmpty()) {
            val emptyText = if (allItems.isEmpty()) {
                "云端还没有壁纸资源。\n可使用 publish_r2.py --bucket file --wallpaper <图片或视频> 上传。"
            } else {
                "当前分类下暂无壁纸。"
            }
            gridBox.addView(
                Ui.text(context, emptyText, 22f, pal.textDim).apply {
                    setLineSpacing(8f, 1f)
                    setPadding(8, 24, 8, 24)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        for (pair in visible.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { idx, entry ->
                val card = buildCard(entry)
                row.addView(
                    card,
                    LinearLayout.LayoutParams(0, -2, 1f).also {
                        if (idx > 0) it.leftMargin = 18
                    },
                )
            }
            if (pair.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).also { it.leftMargin = 18 })
            }
            gridBox.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 18 })
        }
    }

    private fun buildCard(entry: OnlineWallpaperEntry): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.INNER)
            setPadding(16, 16, 16, 16)
        }

        val previewBox = FrameLayout(context).apply {
            background = Ui.round(Skin.surfaceStrong, Shape.INNER, 1f, Skin.GLASS_STROKE)
            clipToOutline = true
        }
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        previewBox.addView(iv, FrameLayout.LayoutParams(-1, -1))

        val placeholder = Ui.text(
            context,
            if (entry.kind == WallpaperKind.VIDEO) "动态视频壁纸" else "静态壁纸",
            20f,
            pal.textFaint,
        ).apply { gravity = Gravity.CENTER }
        previewBox.addView(placeholder, FrameLayout.LayoutParams(-1, -1))

        val badge = Ui.text(
            context,
            if (entry.kind == WallpaperKind.VIDEO) "动态" else "静态",
            16f,
            if (entry.kind == WallpaperKind.VIDEO) pal.accentInk else pal.text,
            bold = true,
        ).apply {
            gravity = Gravity.CENTER
            background = Ui.round(
                if (entry.kind == WallpaperKind.VIDEO) pal.accent else Skin.surfaceSoft,
                Shape.PILL,
            )
            setPadding(14, 4, 14, 4)
        }
        previewBox.addView(
            badge,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).also {
                it.leftMargin = 12
                it.topMargin = 12
            },
        )
        card.addView(previewBox, LinearLayout.LayoutParams(-1, 210))

        if (entry.thumbUrl.isNotBlank()) {
            loadThumbInto(entry.thumbUrl, iv, placeholder)
        }

        card.addView(
            Ui.text(context, entry.name, 24f, pal.text, bold = true),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 12 },
        )
        val meta = buildString {
            append(entry.file)
            if (entry.size > 0) append("  ·  ").append(TransferFormat.bytes(entry.size))
            if (entry.addedAt.length >= 10) append("  ·  ").append(entry.addedAt.take(10))
        }
        card.addView(
            Ui.text(context, meta, 17f, pal.textFaint),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 4 },
        )

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (entry.kind == WallpaperKind.STATIC) {
            btnRow.addView(
                actionPill("设为日间静态", primary = true) { openDownload(entry, asNightVideo = false) },
                LinearLayout.LayoutParams(0, 50, 1f),
            )
            btnRow.addView(
                actionPill("设为夜间静态", primary = false) { openDownload(entry, asNightVideo = true) },
                LinearLayout.LayoutParams(0, 50, 1f).also { it.leftMargin = 10 },
            )
        } else {
            btnRow.addView(
                actionPill("设为日间动态", primary = true) { openDownload(entry, asNightVideo = false) },
                LinearLayout.LayoutParams(0, 50, 1f),
            )
            btnRow.addView(
                actionPill("设为夜间动态", primary = false) { openDownload(entry, asNightVideo = true) },
                LinearLayout.LayoutParams(0, 50, 1f).also { it.leftMargin = 10 },
            )
        }
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 14 })
        return card
    }

    private fun actionPill(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        Ui.text(
            context,
            label,
            19f,
            if (primary) pal.accentInk else pal.textDim,
            bold = primary,
        ).apply {
            gravity = Gravity.CENTER
            background = if (primary) {
                Ui.round(pal.accent, Shape.PILL)
            } else {
                Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
            }
            setPadding(18, 8, 18, 8)
            pressFeedback()
            setOnClickListener { onClick() }
        }

    private fun loadThumbInto(url: String, iv: ImageView, placeholder: View) {
        runCatching {
            thumbExecutor.execute {
                val bmp: Bitmap? = WallpaperStore.loadThumbnail(context.applicationContext, url)
                if (bmp != null) {
                    main.post {
                        if (!alive) return@post
                        iv.setImageBitmap(bmp)
                        placeholder.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun openDownload(entry: OnlineWallpaperEntry, asNightVideo: Boolean) {
        dismissDownload(animate = false)
        val dlg = WallpaperDownloadDialog(
            context = context,
            pal = pal,
            entry = entry,
            asNightVideo = asNightVideo,
            onApplied = { kind, night ->
                onApplied(kind, night)
            },
        )
        dlg.onClose = { dismissDownload(animate = true) }
        downloadDialog = dlg
        addView(dlg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dismissDownload(animate: Boolean) {
        val dlg = downloadDialog ?: return
        downloadDialog = null
        if (animate) {
            dlg.dismissAnimated { removeView(dlg) }
        } else {
            removeView(dlg)
        }
    }
}

/**
 * 在线壁纸下载与导入进度弹窗（对齐 [CloudDownloadDialog] 的视觉与交互）。
 */
class WallpaperDownloadDialog(
    context: Context,
    pal: Palette,
    private val entry: OnlineWallpaperEntry,
    private val asNightVideo: Boolean = false,
    private val downloader: (OnlineWallpaperEntry, (Int) -> Unit, (Long, Long, Long) -> Unit) -> File? =
        { e, onPercent, onDetail ->
            WallpaperStore.download(context.applicationContext, e, onPercent, onDetail)
        },
    private val applier: (OnlineWallpaperEntry, File, Boolean) -> WallpaperStore.ApplyResult =
        { e, f, night ->
            WallpaperStore.applyDownloaded(context.applicationContext, e, f, night)
        },
    private val onApplied: (WallpaperKind, Boolean) -> Unit = { _, _ -> },
) : DialogSheet(context, pal, 780, 720, "下载壁纸") {

    private val main = Handler(Looper.getMainLooper())
    private val title = Ui.text(context, entry.name, 32f, pal.text, bold = true)
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
    private var closing = false
    private var lastRead = 0L
    private var lastDetailAt = 0L
    private var lastUiAt = 0L

    init {
        val content = flowContent()
        content.addView(title, Ui.linear(-1, -2))
        val targetLabel = when {
            entry.kind == WallpaperKind.STATIC && asNightVideo -> "夜间静态壁纸"
            entry.kind == WallpaperKind.STATIC -> "日间静态壁纸"
            asNightVideo -> "夜间动态壁纸"
            else -> "日间动态壁纸"
        }
        val sizeStr = if (entry.size > 0) " · ${TransferFormat.bytes(entry.size)}" else ""
        content.addView(
            Ui.text(context, "$targetLabel · ${entry.file}$sizeStr", 19f, pal.textFaint),
            Ui.linear(-1, -2).also { it.topMargin = 6 },
        )
        content.addView(section("下载进度"), Ui.linear(-1, 32).also { it.topMargin = 18 })
        content.addView(percentText, Ui.linear(-1, 64))
        content.addView(bar, Ui.linear(-1, 44, 0f, 4))
        content.addView(speedText, Ui.linear(-1, 30, 0f, 0, 6))
        content.addView(bytesText, Ui.linear(-1, 30))
        content.addView(statusText, Ui.linear(-1, 32, 0f, 0, 12))
        content.addView(section("结果"), Ui.linear(-1, 30))
        content.addView(resultText, Ui.linear(-1, 60, 0f, 0, 4))
        content.addView(footer, Ui.linear(-1, 66, 0f, 0, 10))
        footer.addView(chip("关闭", padH = 26, padV = 14) { requestClose() }, Ui.linear(-2, 66))

        main.post { startDownload() }
    }

    private fun requestClose() {
        if (closing) return
        closing = true
        onClose?.invoke()
    }

    private fun setPercent(text: String) {
        if (percentText.text == text) return
        Motion.nudge(percentText)
        percentText.text = text
    }

    fun startDownload() {
        if (busy) return
        busy = true
        retryBtn?.let { footer.removeView(it) }
        retryBtn = null
        setPercent("0%")
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
                setPercent("100%")
                bar.progress = 100
                statusText.text = "下载完成，正在导入壁纸…"
                bytesText.text = "已下载 ${TransferFormat.bytes(file.length())}"
            }
            val result = runCatching { applier(entry, file, asNightVideo) }.getOrElse {
                WallpaperStore.ApplyResult.Failed(it.message ?: "壁纸导入异常")
            }
            main.post { onApplyResult(result) }
        }, "wallpaper-download-${entry.file}").start()
    }

    private fun onPercent(p: Int) {
        if (!busy) return
        setPercent(TransferFormat.percent(p))
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
        if (now - lastUiAt < 200L) return
        lastUiAt = now
        bytesText.text = if (total > 0) {
            "已下载 ${TransferFormat.bytes(read)} / 共 ${TransferFormat.bytes(total)} · 已用 ${elapsed / 1000}s"
        } else {
            "已下载 ${TransferFormat.bytes(read)} · 已用 ${elapsed / 1000}s"
        }
    }

    private fun onApplyResult(result: WallpaperStore.ApplyResult) {
        busy = false
        statusText.setTextColor(pal.text)
        when (result) {
            is WallpaperStore.ApplyResult.StaticApplied -> {
                statusText.text = if (result.night) "已设为夜间静态壁纸" else "已设为日间静态壁纸"
                val saved = if (result.savedPath.isNotEmpty()) "\n已保存：${result.savedPath}" else ""
                resultText.text = "「${entry.name}」已裁切适配车机分辨率并生效。$saved"
                setPercent("100%")
                bar.progress = 100
                onApplied(WallpaperKind.STATIC, result.night)
                Toast.makeText(
                    context,
                    if (result.night) "已设为夜间静态壁纸：${entry.name}" else "已设为日间静态壁纸：${entry.name}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            is WallpaperStore.ApplyResult.VideoApplied -> {
                statusText.text = if (result.night) "已设为夜间动态壁纸" else "已应用为动态壁纸"
                val base = result.warning ?: "「${entry.name}」已导入并启用。"
                val saved = if (result.savedPath.isNotEmpty()) "\n已保存：${result.savedPath}" else ""
                resultText.text = "$base$saved"
                setPercent("100%")
                bar.progress = 100
                onApplied(WallpaperKind.VIDEO, result.night)
                Toast.makeText(context, "已应用动态壁纸：${entry.name}", Toast.LENGTH_SHORT).show()
            }
            is WallpaperStore.ApplyResult.Failed -> onFailed(result.reason)
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
