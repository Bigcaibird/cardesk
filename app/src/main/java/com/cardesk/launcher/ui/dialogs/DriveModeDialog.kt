package com.cardesk.launcher.ui.dialogs

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.vehicle.DriveMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * 驾驶模式快捷面板（画板 `07-drivemode-day` / `n06-drivemode-night`）。
 *
 * 状态栏「驾驶模式 · xx」胶囊点开它就弹这个。版式按画板绝对坐标：
 * - 面板 820×430 @ (550,298)，标题 28 + 右上 `×`；
 * - 一行状态胶囊（`ⓘ 当前模式：xx`，50 高、圆角 25、`text` 6% 底）；
 * - **三张 228×140 模式卡**（经济 / 舒适 / 运动），选中 = accent 12% 底 + 1.5px accent 描边，
 *   卡内是 36px 线稿图标 + 20px 标签；
 * - 页脚：`打开原厂驾驶模式页` 次级按钮（232×56）+ `关闭` 主按钮（172×56，accent 实底）。
 *
 * 真正的写车在 [com.cardesk.launcher.vehicle.VehicleController.setDriveMode]，
 * 面板只负责交互：点击 → `onSwitch(value)`，结果由 [showResult] 回填。
 */
class DriveModeDialog(
    context: Context,
    pal: Palette,
    currentRaw: Int?,
    private val onSwitch: (Int) -> Unit,
    private val onMoreSettings: () -> Unit,
    supportedModes: IntArray = DriveMode.UI_MODE_VALUES,
) : DialogSheet(
    context,
    pal,
    PANEL_W,
    panelHeightFor(DriveMode.resolveSupportedModes(supportedModes, currentRaw).size),
    "驾驶模式",
    PANEL_LEFT,
    panelTopFor(DriveMode.resolveSupportedModes(supportedModes, currentRaw).size),
) {

    private val modes: IntArray = DriveMode.resolveSupportedModes(supportedModes, currentRaw)
    private var currentValue: Int = currentRaw ?: DriveMode.SELECTION_NONE
    private var pendingValue: Int? = null
    private var busy = false

    private val status: TextView = Ui.text(context, "", 16f, pal.textDim)
    private val chips = ArrayList<Pair<FrameLayout, Int>>()

    init {
        val grid = gridSpec(modes.size)

        // 标题 28 粗体：画板 ink 高 37，基线落面板内 y≈60。
        body.addView(
            Ui.text(context, panelTitle, 28f, pal.text, bold = true),
            Ui.frame(-2, 44, Gravity.TOP or Gravity.START, 38, 24),
        )
        // 右上关闭 `×`：画板是一个裸字符，没有底板。
        body.addView(
            Ui.text(context, "✕", 24f, pal.textDim).apply {
                gravity = Gravity.CENTER
                pressFeedback()
                setOnClickListener { onClose?.invoke() }
            },
            Ui.frame(40, 40, Gravity.TOP or Gravity.END, 0, 26).apply { rightMargin = 26 },
        )

        // 状态胶囊：`ⓘ 当前模式：xx`（画板 596,403 / 1330×50 / r25）。
        val statusRow = FrameLayout(context).apply {
            background = Ui.round(tint(pal.text, 0x0F), 25f)
        }
        body.addView(statusRow, Ui.frame(742, 48, Gravity.TOP or Gravity.START, 38, 103))
        statusRow.addView(
            Ui.text(context, "ⓘ", 16f, pal.textFaint).apply { gravity = Gravity.CENTER },
            Ui.frame(20, 20, Gravity.CENTER_VERTICAL or Gravity.START, 16, 0),
        )
        statusRow.addView(
            status.apply { gravity = Gravity.CENTER_VERTICAL },
            Ui.frame(672, -2, Gravity.CENTER_VERTICAL or Gravity.START, 44, 0),
        )

        // 动态模式卡网格：3 项保持与画板完全一致的 3×1（232×138，间距 23），
        // 4 项单行 4 列，5~6 项 3×2，7~12 项 4 列多行。
        for (index in modes.indices) {
            val value = modes[index]
            val label = DriveMode.fullLabel(value)
            val card = buildModeCard(value, label, grid)
            val active = isCurrent(value)
            paintCard(card, active)
            card.setOnClickListener { request(value, label) }
            card.pressFeedback()
            chips += card to value
            val row = index / grid.cols
            val col = index % grid.cols
            val left = GRID_LEFT + col * (grid.cardW + grid.gapX)
            val top = GRID_TOP + row * (grid.cardH + grid.gapY)
            body.addView(card, Ui.frame(grid.cardW, grid.cardH, Gravity.TOP or Gravity.START, left, top))
        }

        // 页脚：左「打开原厂驾驶模式页」+ 右「关闭」主按钮。
        val footerTop = grid.footerTop
        val more = Ui.text(context, "打开原厂驾驶模式页", 16f, pal.text).apply {
            background = Ui.round(tint(pal.text, 0x0F), 20f)
            gravity = Gravity.CENTER
            pressFeedback()
            setOnClickListener { onMoreSettings() }
        }
        body.addView(more, Ui.frame(232, 56, Gravity.TOP or Gravity.START, 39, footerTop))
        val close = Ui.text(context, "关闭", 16f, pal.accentInk, bold = true).apply {
            background = Ui.round(pal.accent, 20f)
            gravity = Gravity.CENTER
            pressFeedback()
            setOnClickListener { onClose?.invoke() }
        }
        body.addView(close, Ui.frame(179, 56, Gravity.TOP or Gravity.START, 600, footerTop))

        refreshStatus()
    }

    /** 一张模式卡：36px 线稿图标 + 标签，垂直排列居中。 */
    private fun buildModeCard(modeValue: Int, label: String, grid: GridSpec): FrameLayout {
        val compact = grid.cardH < CARD_H
        val iconTop = if (compact) 20 else 28
        val labelTop = if (compact) 64 else 76
        val textSize = if (grid.cardW < 200) 17f else 20f
        return FrameLayout(context).apply {
            background = Ui.round(tint(pal.text, 0x0C), 20f)
            addView(ModeIcon(context, modeValue), Ui.frame(36, 36, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, iconTop))
            addView(
                Ui.text(context, label, textSize, pal.text, bold = true).apply {
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                },
                Ui.frame(-1, 28, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, labelTop),
            )
        }
    }

    private fun isCurrent(value: Int): Boolean =
        DriveMode.normalizeModeId(currentValue) == DriveMode.normalizeModeId(value)

    private fun request(value: Int, label: String) {
        if (busy) return
        if (isCurrent(value)) {
            status.text = "当前已经是$label"
            return
        }
        busy = true
        pendingValue = value
        setStatus("正在切换到「$label」…", pal.textDim)
        setChipsEnabled(false)
        onSwitch(value)
    }

    /** 写车结果（主线程回调）。 */
    fun showResult(ok: Boolean, message: String) {
        busy = false
        setChipsEnabled(true)
        // 先把每个卡片的**旧**选中态记下来 —— 颜色插值需要知道「从哪来」。
        val wasActive = chips.map { (_, value) -> isCurrent(value) }
        pendingValue?.let { if (ok) currentValue = it }
        pendingValue = null
        setStatus(message, if (ok) pal.accent else SKIN_DANGER_TEXT)
        chips.forEachIndexed { index, (card, value) ->
            val active = isCurrent(value)
            animateCardState(card, wasActive[index], active)
            if (active) Motion.bump(card)
        }
    }

    /**
     * 状态文字：换文案 + 换颜色，顺便顶一下。
     *
     * 这是面板上的**状态读数** —— 换字换色都是瞬间的，
     * 顶一下让「状态变了」这件事被看见。
     */
    private fun setStatus(text: String, color: Int) {
        if (status.text != text) Motion.nudge(status)
        status.text = text
        status.setTextColor(color)
    }

    /**
     * 忙碌态（正在写车）：卡片变淡且不可点。
     *
     * 用独立的 [ValueAnimator] 而不是 `view.animate()`：后者和 [Motion.bump]
     * 共用同一个 ViewPropertyAnimator，`bump()` 一上来就 `cancel()`，
     * 这里的透明度动画会被一起 cancel 掉，卡片永远停在半透明。
     */
    private fun setChipsEnabled(enabled: Boolean) {
        val target = if (enabled) 1f else BUSY_ALPHA
        chips.forEach { (card, _) ->
            card.isEnabled = enabled
            ValueAnimator.ofFloat(card.alpha, target).apply {
                duration = MotionSpec.STATE_BUMP_DURATION
                interpolator = Motion.STANDARD
                addUpdateListener { card.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    /** 卡片的底 / 描边 / 字色一次性落到指定选中态（无动画，首帧用）。 */
    private fun paintCard(card: FrameLayout, active: Boolean) {
        val drawable = card.background as? GradientDrawable
        if (active) {
            drawable?.setColor(pal.withAlpha(pal.accent, 0x1A))
            drawable?.setStroke(3, pal.accent)
        } else {
            drawable?.setColor(tint(pal.text, 0x0A))
            drawable?.setStroke(0, 0)
        }
        val label = card.getChildAt(1) as? TextView ?: return
        label.setTextColor(if (active) pal.accent else pal.text)
    }

    /**
     * 卡片的底色 / 描边 / 字色渐变到新的选中态。
     *
     * 底色是 [GradientDrawable]，**就地改它的颜色**而不是换 drawable ——
     * 换 drawable 会把圆角一起换掉，卡片会「变形」一下。
     */
    private fun animateCardState(card: FrameLayout, fromActive: Boolean, toActive: Boolean) {
        val fromFill = if (fromActive) pal.withAlpha(pal.accent, 0x1A) else tint(pal.text, 0x0A)
        val toFill = if (toActive) pal.withAlpha(pal.accent, 0x1A) else tint(pal.text, 0x0A)
        val fromText = if (fromActive) pal.accent else pal.text
        val toText = if (toActive) pal.accent else pal.text
        val label = card.getChildAt(1) as? TextView
        Motion.animateProgress(
            card,
            MotionSpec.STATE_BUMP_DURATION,
            onEnd = {
                val drawable = card.background as? GradientDrawable
                if (toActive) drawable?.setStroke(3, pal.accent) else drawable?.setStroke(0, 0)
            },
        ) { t ->
            val drawable = card.background as? GradientDrawable
            drawable?.setColor(MotionSpec.lerpColor(fromFill, toFill, t))
            // 描边从 0 宽长出来，避免最后一帧才跳出一圈线。
            drawable?.setStroke(
                if (toActive) (3 * t).toInt().coerceAtLeast(1) else (3 * (1 - t)).toInt(),
                MotionSpec.lerpColor(pal.accent, pal.accent, t),
            )
            label?.setTextColor(MotionSpec.lerpColor(fromText, toText, t))
        }
    }

    private fun refreshStatus() {
        status.text = if (DriveMode.normalizeModeId(currentValue) == DriveMode.SELECTION_NONE) {
            "当前模式：读不到"
        } else {
            "当前模式：${DriveMode.fullLabel(currentValue)}"
        }
        status.setTextColor(pal.textDim)
    }

    /** `text` 色按 alpha 叠在面板上：画板的卡片底 / 次级按钮底都是这个口径。 */
    private fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** 模式卡里的 36px 线稿图标（按模式功能号选图标，不依赖下标顺序）。 */
    private class ModeIcon(context: Context, private val modeValue: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Skin.TEXT_DIM
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.save()
            canvas.translate((w - 36f) / 2f, (h - 36f) / 2f)
            when (DriveMode.normalizeModeId(modeValue)) {
                // 经济 / 节能 / 混动 / 纯电：电池 + 闪电。
                DriveMode.SELECTION_ECO,
                DriveMode.SELECTION_ECO_PHEV,
                DriveMode.SELECTION_ECO_PLUS,
                DriveMode.SELECTION_SAVE,
                DriveMode.SELECTION_PURE,
                DriveMode.SELECTION_HYBRID,
                DriveMode.SELECTION_PHEV,
                -> {
                    val body = RectF(6f, 10f, 28f, 28f)
                    canvas.drawRoundRect(body, 4f, 4f, paint)
                    canvas.drawLine(28f, 16f, 32f, 16f, paint)
                    canvas.drawLine(32f, 16f, 32f, 22f, paint)
                    canvas.drawLine(32f, 22f, 28f, 22f, paint)
                    val bolt = Path().apply {
                        moveTo(19f, 13f); lineTo(14f, 20f); lineTo(18f, 20f)
                        lineTo(15f, 26f); lineTo(21f, 18f); lineTo(17f, 18f); close()
                    }
                    canvas.drawPath(bolt, paint)
                }
                // 舒适 / 标准：太阳 + 光芒。
                DriveMode.SELECTION_COMFORT,
                DriveMode.SELECTION_COMFORT_HEV,
                -> {
                    canvas.drawCircle(18f, 18f, 7f, paint)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0).toFloat()
                        val cx = 18f + cos(a) * 12f
                        val cy = 18f + sin(a) * 12f
                        val dx = 18f + cos(a) * 16f
                        val dy = 18f + sin(a) * 16f
                        canvas.drawLine(cx, cy, dx, dy, paint)
                    }
                }
                // 个性化：三条调节滑杆。
                DriveMode.SELECTION_CUSTOM -> {
                    canvas.drawLine(6f, 10f, 30f, 10f, paint)
                    canvas.drawCircle(13f, 10f, 3f, paint)
                    canvas.drawLine(6f, 18f, 30f, 18f, paint)
                    canvas.drawCircle(23f, 18f, 3f, paint)
                    canvas.drawLine(6f, 26f, 30f, 26f, paint)
                    canvas.drawCircle(16f, 26f, 3f, paint)
                }
                // 雪地：六角雪花。
                DriveMode.SELECTION_SNOW -> {
                    for (i in 0 until 3) {
                        val a = Math.toRadians(i * 60.0).toFloat()
                        val dx = cos(a) * 12f
                        val dy = sin(a) * 12f
                        canvas.drawLine(18f - dx, 18f - dy, 18f + dx, 18f + dy, paint)
                    }
                    canvas.drawCircle(18f, 18f, 3.2f, paint)
                }
                // 运动 / 赛道 / 越野 / 动力 / 陡坡缓降等：山景 + 太阳。
                else -> {
                    val mountain = Path().apply {
                        moveTo(4f, 30f); lineTo(14f, 15f); lineTo(21f, 25f)
                        lineTo(25f, 19f); lineTo(32f, 30f); close()
                    }
                    canvas.drawPath(mountain, paint)
                    canvas.drawCircle(27f, 8f, 3.4f, paint)
                }
            }
            canvas.restore()
        }
    }

    private data class GridSpec(
        val cols: Int,
        val rows: Int,
        val cardW: Int,
        val cardH: Int,
        val gapX: Int,
        val gapY: Int,
        val footerTop: Int,
        val panelH: Int,
    )

    private companion object {
        const val PANEL_W = 817
        const val PANEL_H = 438
        const val PANEL_LEFT = 551
        const val PANEL_TOP = 298
        const val GRID_LEFT = 39
        const val GRID_TOP = 176
        const val GRID_W = 742
        const val CARD_W = 232
        const val CARD_H = 138

        fun gridSpec(count: Int): GridSpec {
            val safe = count.coerceAtLeast(1)
            val cols = when {
                safe <= 3 -> safe
                safe == 4 -> 4
                safe <= 6 -> 3
                else -> 4
            }
            val rows = (safe + cols - 1) / cols
            val gapX = if (cols >= 4) 16 else 23
            val cardW = if (cols == 3) CARD_W else (GRID_W - (cols - 1) * gapX) / cols
            val cardH = when {
                rows == 1 -> CARD_H
                rows == 2 -> 118
                else -> 112
            }
            val gapY = if (rows <= 2) 16 else 14
            val gridH = rows * cardH + (rows - 1) * gapY
            val footerTop = GRID_TOP + gridH + 31
            val panelH = footerTop + 56 + 37
            return GridSpec(cols, rows, cardW, cardH, gapX, gapY, footerTop, panelH)
        }

        fun panelHeightFor(count: Int): Int = gridSpec(count).panelH

        fun panelTopFor(count: Int): Int {
            val h = panelHeightFor(count)
            return if (h == PANEL_H) PANEL_TOP else ((1080 - h) / 2).coerceAtLeast(80)
        }

        /** 危险色：读失败/切换失败的提示色。 */
        val SKIN_DANGER_TEXT: Int = 0xFFE05B5B.toInt()

        /**
         * 忙碌态（正在写车）时卡片的透明度。
         *
         * 变淡 = 「退后」—— 和「不可点」一起告诉用户「正在处理」。
         */
        const val BUSY_ALPHA = 0.4f
    }
}
