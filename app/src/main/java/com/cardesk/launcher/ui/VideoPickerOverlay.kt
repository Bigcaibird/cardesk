package com.cardesk.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.VideoLibrary
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec

/**
 * 第219轮：**应用内视频选择器**（不依赖系统选择器）。
 *
 * ## 为什么要有这个
 *
 * 实车（领克 AAOS 820A）上系统选择器不可靠：诊断包里
 * `视频壁纸：收到选视频结果 resultCode=0 uri=null` ⇒ 选择器秒退且不带 uri，
 * 系统里也查不到 `documentsui`。
 *
 * ⇒ 我们**自己列**车机存储里的视频文件让用户点。
 *
 * ## 形态
 *
 * 参照 [SourceMenuLayer]：一个全屏层 = 遮罩 + 居中面板；
 * 面板自上而下 = 标题 / 状态行 / 可滚动列表 / 底部按钮行。
 * 列表每行两行字：**文件名** + 「大小 · 日期 · 时长」。
 *
 * ## 线程
 *
 * 扫描在**后台线程**做（车机存储里可能有几千个文件），
 * 结果切回主线程刷新列表 ⇒ 打开面板时不会卡。
 */
class VideoPickerOverlay(context: Context) : FrameLayout(context) {

    companion object {
        private const val PANEL_W = 760
        private const val PANEL_MAX_H = 720
        private const val LIST_H = 460
        private const val ITEM_H = 76
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

    /** 用户选了哪个视频。 */
    var onPick: ((VideoLibrary.Item) -> Unit)? = null

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

        val header = Ui.text(context, "选择视频壁纸", HEADER_TEXT, HEADER_COLOR, bold = true)
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

        // 底部按钮行
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
        Logx.event(LogTags.SETTING, "视频库：打开选择器")
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
        Logx.event(LogTags.SETTING, "视频库：关闭选择器")
    }

    val isOpen: Boolean get() = opened

    /**
     * 扫我们自己的目录（后台线程）。
     *
     * ⚠️ **只扫 `Download/CarDesk` 这一个目录**（局域网传输的上传落点）——
     * 扫整个共享存储会卡（车机上可能有几千个文件）。
     */
    fun startScan() {
        if (scanning) return
        scanning = true
        statusText.text = "正在扫描…"
        listBox.removeAllViews()
        val ctx = context.applicationContext
        Thread {
            val items = VideoLibrary.scanLibrary(ctx)
            main.post {
                scanning = false
                if (!opened) return@post
                showItems(items)
            }
        }.start()
    }

    /** 把扫描结果铺到列表里。 */
    private fun showItems(items: List<VideoLibrary.Item>) {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            statusText.text = "没找到视频文件"
            val tip = Ui.text(
                context,
                "视频要先放进这台车的 ${VideoLibrary.SHARE_DIR_NAME} 目录：\n" +
                    "设置 → 局域网传输 → 手机扫码上传视频，传完回来点「重新扫描」。\n" +
                    "也可以点「用系统选择器」走系统那条路。",
                META_TEXT,
                META_COLOR,
            )
            tip.setPadding(6, 4, 0, 0)
            listBox.addView(tip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        statusText.text = "找到 ${items.size} 个视频，点一个即可导入"
        for (item in items) {
            listBox.addView(row(item), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ITEM_H))
        }
    }

    /** 一行：文件名 + 元信息。 */
    private fun row(item: VideoLibrary.Item): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(10, 8, 10, 8)
        box.background = Ui.round(0x14FFFFFF, 12f)
        box.isClickable = true
        Motion.press(box)

        val name = Ui.text(context, item.name, ITEM_TEXT, ITEM_COLOR)
        box.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val meta = buildList {
            add(VideoLibrary.formatSize(item.sizeBytes))
            if (item.modifiedAt > 0L) add(formatDate(item.modifiedAt))
            if (item.path.isNotEmpty()) add(item.path)
        }.joinToString("  ·  ")
        val metaText = Ui.text(context, meta, META_TEXT, META_COLOR)
        metaText.setPadding(0, 4, 0, 0)
        box.addView(metaText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.setOnClickListener {
            Logx.event(
                LogTags.SETTING,
                "视频库：选中视频",
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
        // 面板居中；高度超过上限就压扁（列表自己可滚）。
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
