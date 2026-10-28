package com.cardesk.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.floats.FloatWindowLayer.WindowBox

/**
 * 悬浮窗气泡列：**被最小化的浮窗**的还原入口。
 *
 * 第六十三轮按用户要求，桌面常驻的**「音乐 ♪」「空调 ❄」两颗气泡已移除**，
 * 这一列现在只承载最小化浮窗的气泡：点一下还原、长按关闭。
 *
 * （05-F4）把三方 App 用 `android.app.ActivityView` 嵌在桌面子 View 里，不需要悬浮窗权限
 * 本应用保留「气泡 + 打开/收起」的交互语义，气泡内容用自绘图标表示（不嵌入三方 App），
 * 因为 `ActivityView` 需要 API 29+ 且需要平台签名侧的能力。
 */
class FloatBubbleView(context: Context) : FrameLayout(context) {

    var palette: Palette = com.cardesk.launcher.core.Palettes.night
        set(value) {
            field = value
            paint()
        }

    /** 浮窗最小化后的气泡入口。 */
    var onRestoreFloat: ((WindowBox) -> Unit)? = null
    var onCloseFloat: ((WindowBox) -> Unit)? = null

    private var floatWindows: List<WindowBox> = emptyList()
    private lateinit var column: LinearLayout

    init {
        column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // 气泡列摆在 **left 44 / top 440**，
        // 单颗气泡 **76×76 圆角 26**（设计板 `.tile`），间距 22。
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).also {
            it.leftMargin = BUBBLE_LEFT
            it.topMargin = BUBBLE_TOP
        })
    }

    /**
     * 设置已最小化的浮窗。
     *
     * 气泡只在桌面首页可点
     * 其它页 alpha 降到 0.45 且不可点。
     */
    fun setFloatWindows(list: List<WindowBox>) {
        floatWindows = list
        paint()
    }

    private fun paint() {
        column.removeAllViews()
        // 最小化的浮窗气泡
        for (box in floatWindows) {
            val bubble = BubbleView(context).apply {
                palette = this@FloatBubbleView.palette
                text = box.label.take(1)
                alpha = if (box.bubbleInteractive) 1f else 0.45f
                isEnabled = box.bubbleInteractive
                isClickable = box.bubbleInteractive
                setOnClickListener {
                    if (box.bubbleInteractive) {
                        // 排障场景：「最小化的浮窗点气泡恢复不了」——还原落盘。
                        Logx.event(
                            LogTags.FLOAT,
                            "最小化浮窗气泡点击：还原",
                            "pkg" to box.packageName,
                            "label" to box.label,
                        )
                        onRestoreFloat?.invoke(box)
                    } else {
                        // 排障场景：「非首页点气泡没反应」——不可点是行为，要写清楚。
                        Logx.w(
                            LogTags.FLOAT,
                            "最小化浮窗气泡不可点（非桌面首页）",
                            "pkg" to box.packageName,
                            "label" to box.label,
                        )
                    }
                }
                setOnLongClickListener {
                    if (box.bubbleInteractive) {
                        Logx.event(
                            LogTags.FLOAT,
                            "最小化浮窗气泡长按：关闭",
                            "pkg" to box.packageName,
                            "label" to box.label,
                        )
                        onCloseFloat?.invoke(box)
                        true
                    } else {
                        Logx.w(
                            LogTags.FLOAT,
                            "最小化浮窗气泡长按被忽略（非桌面首页）",
                            "pkg" to box.packageName,
                            "label" to box.label,
                        )
                        false
                    }
                }
            }
            column.addView(bubble, LinearLayout.LayoutParams(BUBBLE_SIZE, BUBBLE_SIZE).also { it.bottomMargin = BUBBLE_GAP })
        }
        visibility = if (floatWindows.isEmpty()) INVISIBLE else VISIBLE
    }

    /** 单颗气泡：**76×76 圆角 26 方块**（设计板 `.tile`）+ 文字字形。 */
    private class BubbleView(context: Context) : View(context) {
        var palette: Palette = com.cardesk.launcher.core.Palettes.night
            set(value) {
                field = value
                invalidate()
            }

        var text: String = "·"
            set(value) {
                field = value
                invalidate()
            }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            // 设计板 `.tile`：surfaceSoft 纯色面 + 1px 描边 + 圆角 26
            val inset = 1f
            fill.color = palette.surfaceSoft
            canvas.drawRoundRect(
                RectF(inset, inset, w - inset, h - inset),
                BUBBLE_RADIUS,
                BUBBLE_RADIUS,
                fill,
            )
            stroke.color = palette.stroke
            canvas.drawRoundRect(
                RectF(inset + 0.5f, inset + 0.5f, w - inset - 0.5f, h - inset - 0.5f),
                BUBBLE_RADIUS,
                BUBBLE_RADIUS,
                stroke,
            )

            label.color = palette.textDim
            label.textSize = LABEL_TEXT_SIZE
            canvas.drawText(text, cx, cy + label.textSize * 0.36f, label)
        }
    }

    companion object {
        /** 气泡左边距（设计 px）—— 设计板 是 44。 */
        const val BUBBLE_LEFT = 44

        /** 气泡列顶部（设计 px）—— 设计板 是 440。 */
        const val BUBBLE_TOP = 440

        /** 气泡边长（设计 px）—— 设计板 `.tile` 是 76。 */
        const val BUBBLE_SIZE = 76

        /** 气泡圆角（设计 px）—— 设计板 是 26。 */
        const val BUBBLE_RADIUS = 26f

        /** 气泡间距（设计 px）—— 设计板 是 22。 */
        const val BUBBLE_GAP = 22

        /** 气泡里的文字字号（设计 px）—— 原图标尺寸 30 的 0.8 倍。 */
        const val LABEL_TEXT_SIZE = 24f
    }
}
