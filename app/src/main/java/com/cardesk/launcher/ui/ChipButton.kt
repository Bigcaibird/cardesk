package com.cardesk.launcher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec

/**
 * 面板里的圆角开关按钮：文字 + 可选副标题，激活态用主题色描边。
 *
 * ## 动效
 *
 * 这个控件是 App 里**用得最多的按钮**（桌面、车辆页、空调页、设置页…），
 * 所以按压反馈和状态切换动效都做在类里，**每一个实例天生就有**：
 *
 * - **按压**：按下缩小到 [MotionSpec.PRESS_SCALE]，松手弹回（见 [Motion.press]）；
 * - **状态切换**：激活态变化时先「鼓一下」（见 [Motion.bump]），
 *   底色和文字颜色同时渐变过去，不是硬切。
 *
 * 这样新增一个用 `ChipButton` 的地方，动画是**白送的**，
 * 不用每次都记得补 `pressFeedback()`。
 */
class ChipButton(context: Context, private val palette: Palette, textPx: Float = 24f) : TextView(context) {

    var active: Boolean = false
        private set

    /**
     * 档位指示（第248轮）：`showLevel = true` 时在**右上角**画三根竖条，
     * 点亮到 [level] 根。座椅加热/通风是 0~3 档，胶囊只表达「开没开」，
     * 用户看不出「第几档」，所以在按钮上补一个档位条 —— 与首页快捷车控的
     * 档位条同一套画法（右上角，不压文字）。
     *
     * 默认 `showLevel = false`：绝大多数 [ChipButton] 是开关/单选，没有档位，
     * 不画就不会多出三根无意义的点。
     */
    var showLevel: Boolean = false
        set(value) { field = value; invalidate() }

    var level: Int = 0
        set(value) { field = value.coerceIn(0, LEVEL_MAX); invalidate() }

    private val levelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private var label: String = ""

    /**
     * 背景始终是**同一个** [GradientDrawable]，只改它的颜色和描边。
     *
     * 每次刷新都新建一个 drawable 的话，渐变时每帧都要重新分配对象 ——
     * 状态切换时白白制造十几个 drawable。
     */
    private val chipBackground: GradientDrawable =
        Ui.round(palette.surfaceSoft, 22f, 1f, palette.stroke)

    /**
     * 当前**显示中**的三个颜色。
     *
     * `GradientDrawable` 没法反查自己当前的填充色，所以自己记一份；
     * 渐变每帧更新一次，这样连点两次时是「接着上一次的颜色继续变」。
     */
    private var currentTextColor = 0
    private var currentBgColor = 0
    private var currentStrokeColor = 0

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        setPadding(20, 10, 20, 10)
        background = chipBackground
        setTextColor(palette.textDim)
        currentTextColor = palette.textDim
        currentBgColor = palette.surfaceSoft
        currentStrokeColor = palette.stroke
        // 每个实例天生带按压反馈。
        Motion.press(this)
    }

    fun bind(text: String, active: Boolean) {
        label = text
        this.text = text
        this.active = active
        // 首次绑定不动画：控件刚出现就该是最终样子。
        refresh(animate = false)
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        refresh(animate = true)
        // 状态真的变了 → 鼓一下，给一个「生效了」的二次确认。
        Motion.bump(this)
    }

    fun label(): String = label

    /**
     * 刷新外观。
     *
     * @param animate true = 颜色渐变过去（状态切换）；
     *                false = 直接画好（首次绑定）。
     */
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!showLevel) return
        // 第249轮：用户要求「档位放到按钮底部，改成 --- 这种形式」。
        // 三根横杠（不是右上角的竖条），点亮几档就亮几根：
        //   横杠宽 11dp、高 2.5dp、间距 4dp，整组水平居中、离底边 8dp。
        // 底部留白足够，横杠不会压到「加热 / 通风」两个字。
        val density = resources.displayMetrics.density
        val dashW = 12f * density
        val dashH = 3.2f * density
        val gap = 4f * density
        val totalW = LEVEL_MAX * dashW + (LEVEL_MAX - 1) * gap
        val left0 = (width - totalW) / 2f
        val cy = height - 10f * density
        val lit = if (active) palette.accent else palette.textDim
        val dim = (palette.textDim and 0x00FFFFFF) or (0x73 shl 24)
        for (i in 0 until LEVEL_MAX) {
            levelPaint.color = if (i < level) lit else dim
            val left = left0 + i * (dashW + gap)
            canvas.drawRoundRect(left, cy - dashH / 2f, left + dashW, cy + dashH / 2f,
                dashH / 2f, dashH / 2f, levelPaint)
        }
    }

    private fun refresh(animate: Boolean) {
        // 第四十九轮「浮屿」：激活态 = 强调色软底 + 强调色描边 + 强调色文字；
        // 未激活 = 内嵌块底 + 1px 描边 + 弱化文字。
        val textColor = if (active) palette.accent else palette.textDim
        val bgColor = if (active) palette.accentSoft else palette.surfaceSoft
        val strokeColor = if (active) palette.accent else palette.stroke

        if (!animate) {
            setTextColor(textColor)
            chipBackground.setColor(bgColor)
            chipBackground.setStroke(1, strokeColor)
            // 同步「当前显示的颜色」。
            // 不同步的话，一个**一开始就是激活态**的按钮（比如空调开机时就是开着的）
            // 第一次切换时，渐变会从初始值（未激活色）开始 —— 看起来像是先闪一下再变过去。
            currentTextColor = textColor
            currentBgColor = bgColor
            currentStrokeColor = strokeColor
            return
        }

        // 渐变：从**当前显示的颜色**出发，中途再切换也接着变。
        val fromText = currentTextColor
        val fromBg = currentBgColor
        val fromStroke = currentStrokeColor
        Motion.animateProgress(this) { t ->
            val c = MotionSpec.lerpColor(fromText, textColor, t)
            setTextColor(c)
            currentTextColor = c
            val b = MotionSpec.lerpColor(fromBg, bgColor, t)
            currentBgColor = b
            val s = MotionSpec.lerpColor(fromStroke, strokeColor, t)
            currentStrokeColor = s
            chipBackground.setColor(b)
            chipBackground.setStroke(1, s)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    companion object {
        /** 座椅加热/通风是 0~3 档。 */
        private const val LEVEL_MAX = 3

        fun make(context: Context, palette: Palette, text: String, active: Boolean = false): ChipButton =
            ChipButton(context, palette).apply { bind(text, active) }
    }
}

/**
 * 给 View 挂一个点击时的缩放反馈，模拟车机的"按键回弹"手感。
 *
 * 实现在 [Motion.press] —— 全 App 统一一套时长和曲线。
 * 这个函数保留下来是因为十几个文件都在 import 它。
 */
fun View.pressFeedback(scale: Float? = null) {
    Motion.press(this, scale)
}
