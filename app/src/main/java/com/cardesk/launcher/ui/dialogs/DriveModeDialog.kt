package com.cardesk.launcher.ui.dialogs

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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

/**
 * 驾驶模式快捷面板（第九十五轮补丁）。
 *
 * 状态栏「驾驶模式 · xx」胶囊点开它就弹这个：
 * - 顶部当前模式；
 * - **本车实际支持的 3 个模式**（经济 / 舒适 / 运动，`DriveMode.UI_MODE_LABELS`），
 *   当前模式高亮，点一下就切；
 * - 切完回读确认，失败给红色提示（部分车型只允许在 P 挡切换）；
 * - 底部保留「打开原厂驾驶模式页」兜底。
 *
 * 真正的写车在 [com.cardesk.launcher.vehicle.VehicleController.setDriveMode]，
 * 面板只负责交互：点击 → `onSwitch(value)`，结果由 `showResult()` 回填。
 */
class DriveModeDialog(
    context: Context,
    pal: Palette,
    currentRaw: Int?,
    private val onSwitch: (Int) -> Unit,
    private val onMoreSettings: () -> Unit,
) : DialogSheet(context, pal, 960, 420, "驾驶模式", 480, 330) {

    private var currentValue: Int = currentRaw ?: DriveMode.SELECTION_NONE
    private var pendingValue: Int? = null
    private var busy = false

    private val status: TextView = Ui.text(context, "", 22f, pal.textDim)
    private val chips = ArrayList<Pair<TextView, Int>>()

    init {
        val column = flowContent()
        column.addView(status, Ui.linear(-1, 40))
        status.gravity = Gravity.CENTER_VERTICAL

        var row: LinearLayout? = null
        for (index in DriveMode.UI_MODE_VALUES.indices) {
            val value = DriveMode.UI_MODE_VALUES[index]
            val label = DriveMode.UI_MODE_LABELS[index]
            if (index % 4 == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                column.addView(row, Ui.linear(-1, 70))
            }
            val chip = chip(label, active = isCurrent(value), sizePx = 22f, padH = 10, padV = 14) {
                request(value, label)
            }
            chips += chip to value
            row?.addView(chip, LinearLayout.LayoutParams(0, 62, 1f).apply { marginEnd = 12 })
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(actions, Ui.linear(-1, 72))
        actions.addView(
            chip("打开原厂驾驶模式页", sizePx = 22f, padH = 22, padV = 14) { onMoreSettings() },
            LinearLayout.LayoutParams(0, 62, 1f),
        )
        actions.addView(
            primaryButton("关闭", 22f) { onClose?.invoke() }.apply {
                background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
                setTextColor(pal.textDim)
            },
            LinearLayout.LayoutParams(0, 62, 1f).apply { marginStart = 12 },
        )

        refreshStatus()
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
        // 先把每个胶囊的**旧**选中态记下来 ——
        // 颜色插值需要知道「从哪来」，不然只能「啪」地换到新颜色。
        val wasActive = chips.map { (_, value) -> isCurrent(value) }
        pendingValue?.let { if (ok) currentValue = it }
        pendingValue = null
        setStatus(message, if (ok) pal.accent else SKIN_DANGER_TEXT)
        chips.forEachIndexed { index, (chip, value) ->
            val active = isCurrent(value)
            animateChipState(chip, wasActive[index], active)
            // 刚选中的那颗「鼓一下」：光靠底色渐变还不够，
            // 余光要的是一个**形变**才敢确认「真的切过去了」。
            if (active) Motion.bump(chip)
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
     * 忙碌态（正在写车）：胶囊变淡且不可点。
     *
     * 之前是「啪」地变灰 —— 用户会以为面板卡住了。
     * 变淡用一条过渡走完，才能读成「正在处理，请稍候」。
     *
     * ## 为什么用独立的 [ValueAnimator] 而不是 `view.animate()`
     *
     * `view.animate()` 和 [Motion.bump] 共用同一个 ViewPropertyAnimator，
     * 而 `bump()` 一上来就 `cancel()` —— 这里的透明度动画会被一起cancel掉，
     * 胶囊就永远停在半透明状态了。
     * 独立的动画器和缩放动画互不干扰。
     */
    private fun setChipsEnabled(enabled: Boolean) {
        val target = if (enabled) 1f else BUSY_ALPHA
        chips.forEach { (chip, _) ->
            chip.isEnabled = enabled
            ValueAnimator.ofFloat(chip.alpha, target).apply {
                duration = MotionSpec.STATE_BUMP_DURATION
                interpolator = Motion.STANDARD
                addUpdateListener { chip.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    /**
     * 胶囊的底色 / 描边 / 字色渐变到新的选中态。
     *
     * 之前是「啪」地换掉 background 和字色 —— 颜色瞬变在余光里抓不住，
     * 用户只看到「闪了一下」，分不清是选中态变了还是别的东西变了。
     *
     * 这里用 [Motion.animateProgress] + [MotionSpec.lerpColor] 把它们插值过去。
     * 底色是 [GradientDrawable]，**就地改它的颜色**而不是换 drawable ——
     * 换 drawable 会把圆角一起换掉，胶囊会「变形」一下。
     */
    private fun animateChipState(chip: TextView, fromActive: Boolean, toActive: Boolean) {
        val fromFill = if (fromActive) pal.accent else Skin.surfaceSoft
        val toFill = if (toActive) pal.accent else Skin.surfaceSoft
        val fromText = if (fromActive) pal.accentInk else pal.textDim
        val toText = if (toActive) pal.accentInk else pal.textDim
        // 描边：选中态没有描边，未选中态有 1px 描边。
        // GradientDrawable **不接受透明色**（会被忽略），所以动画里用宽度 1 +
        // 颜色插值，最后再把宽度收掉（见 onEnd）。
        val fromStroke = if (fromActive) 0 else Skin.NAV_STROKE
        val toStroke = if (toActive) 0 else Skin.NAV_STROKE
        Motion.animateProgress(
            chip,
            MotionSpec.STATE_BUMP_DURATION,
            onEnd = {
                val drawable = chip.background as? GradientDrawable
                if (toActive) drawable?.setStroke(0, 0) else drawable?.setStroke(1, Skin.NAV_STROKE)
            },
        ) { t ->
            val drawable = chip.background as? GradientDrawable
            drawable?.setColor(MotionSpec.lerpColor(fromFill, toFill, t))
            drawable?.setStroke(1, MotionSpec.lerpColor(fromStroke, toStroke, t))
            chip.setTextColor(MotionSpec.lerpColor(fromText, toText, t))
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

    private companion object {
        /** 危险色：读失败/切换失败的提示色。 */
        val SKIN_DANGER_TEXT: Int = 0xFFE05B5B.toInt()

        /**
         * 忙碌态（正在写车）时胶囊的透明度。
         *
         * 变淡 = 「退后」—— 和「不可点」一起告诉用户「正在处理」。
         * 0.4：字还看得清，但一眼就知道现在不是能点的状态。
         */
        const val BUSY_ALPHA = 0.4f
    }
}
