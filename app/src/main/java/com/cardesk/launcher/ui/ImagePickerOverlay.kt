package com.cardesk.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.ImageLibrary
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec

/**
 * 第230轮：**应用内图片选择器**（不依赖系统相册）。
 *
 * ## 为什么要有这个
 *
 * 用户反馈：真车上点「导入壁纸」只弹一句「没有可用的图片选择器」。
 * 车机上没有相册 / DocumentsUI 接管 `ACTION_OPEN_DOCUMENT`，
 * 和视频壁纸第218轮遇到的是同一堵墙（视频那边第219轮已用 [VideoPickerOverlay] 兜住）。
 *
 * ⇒ 图片照同一套做：自己列 `Pictures` / `DCIM` / `Download` / `Download/CarDesk`
 * 里的图片，带缩略图让用户点。
 *
 * ## 形态
 *
 * 与 [VideoPickerOverlay] 一致：全屏层 = 遮罩 + 居中面板；
 * 面板 = 标题 / 状态行 / 可滚动列表 / 底部按钮行。
 * 每行 = **缩略图**（96×96，圆角）+ 文件名 + 「大小 · 日期」。
 *
 * ## 线程
 *
 * 扫描与缩略图解码都在**后台线程**做（车机存储里可能几千张图），
 * 结果切回主线程刷新列表。
 */
class ImagePickerOverlay(context: Context) : FrameLayout(context) {

    companion object {
        private const val PANEL_W = 820
        private const val PANEL_MAX_H = 760
        private const val LIST_H = 470
        private const val ITEM_H = 112
        private const val THUMB = 96
        private const val HEADER_TEXT = 22f
        private const val ITEM_TEXT = 20f
        private const val META_TEXT = 15f
        private const val STATUS_TEXT = 16f
        private const val BUTTON_TEXT = 18f

        private val PANEL_BG = 0xEB161E2C.toInt()
        private val PANEL_STROKE = 0x29FFFFFF.toInt()
        private val HEADER_COLOR = 0xFF61E6EEFA.toInt()
        private val ITEM_COLOR = 0xFFEEF3FA.toInt()
        private val META_COLOR = 0xFF9AA7B8.toInt()
        private val ACCENT = 0xFF17BEBB.toInt()
        private val DANGER = 0xFFEB161E.toInt()
    }

    /** 用户选了哪张图。 */
    var onPick: ((ImageLibrary.Item) -> Unit)? = null

    /** 点了「用系统选择器」。 */
    var onUseSystemPicker: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val statusText = Ui.text(context, "正在扫描…", STATUS_TEXT, META_COLOR)
    private val listBox = LinearLayout(context)
    private var opened = false
    private var scanning = false

    init {
        scrim.setBackgroundColor(0x00000000)
        scrim.isClickable = true
        Motion.press(scrim)
        scrim.setOnClickListener { hide() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(24, 20, 24, 20)
        panel.background = Ui.round(PANEL_BG, 26f, 1f, PANEL_STROKE)
        panel.isClickable = true
        addView(panel, LayoutParams(PANEL_W, ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = Ui.text(context, "选择壁纸图片", HEADER_TEXT, HEADER_COLOR, bold = true)
        header.setPadding(6, 0, 0, 6)
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusText.setPadding(6, 0, 0, 10)
        panel.addView(statusText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LIST_H))

        listBox.orientation = LinearLayout.VERTICAL
        scroll.addView(listBox, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(
            button("重新扫描", ACCENT) { startScan() },
            LinearLayout.LayoutParams(0, 56, 1f).apply { rightMargin = 12 },
        )
        footer.addView(
            button("用系统选择器", META_COLOR) {
                hide()
                onUseSystemPicker?.invoke()
            },
            LinearLayout.LayoutParams(0, 56, 1f).apply { rightMargin = 12 },
        )
        footer.addView(
            button("取消", DANGER) { hide() },
            LinearLayout.LayoutParams(0, 56, 1f),
        )
        footer.setPadding(0, 14, 0, 0)
        panel.addView(footer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        visibility = View.GONE
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView {
        val tv = Ui.text(context, label, BUTTON_TEXT, color)
        tv.gravity = Gravity.CENTER
        tv.background = Ui.round(0x22FFFFFF, 14f, 1f, 0x33FFFFFF)
        tv.isClickable = true
        Motion.press(tv)
        tv.setOnClickListener { onClick() }
        return tv
    }

    /** 打开面板并开始扫描。 */
    fun show() {
        opened = true
        visibility = View.VISIBLE
        Motion.enterSheet(panel, null)
        startScan()
        Logx.event(LogTags.SETTING, "图片库：打开选择器")
    }

    fun hide() {
        if (!opened) return
        opened = false
        panel.animate().cancel()
        panel.animate().withEndAction(null)
        panel.animate()
            .alpha(0f)
            .scaleX(MotionSpec.SHEET_ENTER_SCALE)
            .scaleY(MotionSpec.SHEET_ENTER_SCALE)
            .translationY(MotionSpec.SHEET_SLIDE_PX)
            .setDuration(MotionSpec.SHEET_EXIT_DURATION)
            .setInterpolator(Motion.EMPHASIS)
            .withEndAction {
                panel.animate().withEndAction(null)
                visibility = View.GONE
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.translationY = 0f
            }
            .start()
        Logx.event(LogTags.SETTING, "图片库：关闭选择器")
    }

    val isOpen: Boolean get() = opened

    /** 扫描 + 解缩略图（后台线程）。 */
    fun startScan() {
        if (scanning) return
        scanning = true
        statusText.text = "正在扫描…"
        listBox.removeAllViews()
        val ctx = context.applicationContext
        Thread {
            val items = ImageLibrary.scanLibrary(ctx)
            // 缩略图也在后台解，主线程只负责铺 View。
            val thumbs = HashMap<String, Bitmap>(items.size)
            for (item in items) {
                thumbs[item.path] = decodeThumb(item.path) ?: continue
            }
            main.post {
                scanning = false
                if (!opened) return@post
                showItems(items, thumbs)
            }
        }.start()
    }

    /** 解一张 96×96 左右的缩略图（用 `inSampleSize`，别把原图整个读进内存）。 */
    private fun decodeThumb(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB && bounds.outHeight / (sample * 2) >= THUMB) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()

    private fun showItems(items: List<ImageLibrary.Item>, thumbs: Map<String, Bitmap>) {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            statusText.text = "没找到图片"
            val tip = Ui.text(
                context,
                "把图片放进这台车的 ${ImageLibrary.SHARE_DIR_NAME} 目录就能在这里看到：\n" +
                    "设置 → 局域网传输 → 手机扫码上传，传完回来点「重新扫描」。\n" +
                    "也可以点「用系统选择器」走系统那条路。",
                META_TEXT,
                META_COLOR,
            )
            tip.setPadding(6, 4, 0, 0)
            listBox.addView(tip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        statusText.text = "找到 ${items.size} 张图片，点一张即可导入"
        for (item in items) {
            listBox.addView(row(item, thumbs[item.path]), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ITEM_H))
        }
    }

    /** 一行：缩略图 + 文件名 + 元信息。 */
    private fun row(item: ImageLibrary.Item, thumb: Bitmap?): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        box.setPadding(10, 8, 10, 8)
        box.background = Ui.round(0x14FFFFFF, 12f)
        box.isClickable = true
        Motion.press(box)

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (thumb != null) setImageBitmap(thumb) else setBackgroundColor(0x22FFFFFF)
            background = if (thumb != null) Ui.round(0x22FFFFFF, 12f) else background
        }
        box.addView(image, LinearLayout.LayoutParams(THUMB, THUMB).apply { rightMargin = 16 })

        val textBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val name = Ui.text(context, item.name, ITEM_TEXT, ITEM_COLOR)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        textBox.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val meta = buildList {
            add(ImageLibrary.formatSize(item.sizeBytes))
            if (item.modifiedAt > 0L) add(formatDate(item.modifiedAt))
            if (item.path.isNotEmpty()) add(item.path)
        }.joinToString("  ·  ")
        val metaText = Ui.text(context, meta, META_TEXT, META_COLOR)
        metaText.maxLines = 1
        metaText.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        metaText.setPadding(0, 4, 0, 0)
        textBox.addView(metaText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        box.setOnClickListener {
            Logx.event(
                LogTags.SETTING,
                "图片库：选中图片",
                "name" to item.name,
                "size" to item.sizeBytes,
                "path" to item.path,
            )
            hide()
            onPick?.invoke(item)
        }
        return box
    }

    /** 日期文案（`2026-11-19`）。 */
    private fun formatDate(ms: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return String.format(
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!opened) return
        val maxH = minOf(PANEL_MAX_H, height - 80)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(PANEL_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST),
        )
        panel.layout(
            (width - PANEL_W) / 2,
            (height - panel.measuredHeight) / 2,
            (width + PANEL_W) / 2,
            (height + panel.measuredHeight) / 2,
        )
    }
}
