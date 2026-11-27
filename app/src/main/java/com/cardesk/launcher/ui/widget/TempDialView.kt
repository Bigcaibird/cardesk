package com.cardesk.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * 空调温度表盘：圆环 + 中间温度 + 左右 −/+ 方块按钮。
 *
 * ## 与对齐的交互
 *
 * | 行为 ||
 * |---|---|
 * | 按下 ± 键 | 立刻 ±0.5℃（在 ACTION_DOWN） |
 * | 按住 ± 键不放 | 满一个**长按超时**（系统标准，和 Dock 长按一致）后开始**连发**，每 150ms 一次 |
 * | 按住表盘拖动 | `atan2` 取角度 → 夹到 [-135°,135°] → 映射 16~32℃ → 吸附 0.5℃ |
 * | 拖动中 | 圆环和数字**跟着手指走**（直接落值，不做渐变，见 [applyDragTemperature]） |
 * | 抬手 | 回调**绝对温度** `onTemp(temp)` —— 报的是**拖出来的那个值** |
 * | 拖动中回读 | 只挡**外部回读**（车机下发的实际温度），避免回声打断手势 |
 * | `setSynced(true)` | **只画徽标，不禁用触摸**——同步态下仍可调温 |
 *
 * ## 版式（第四十九轮按设计板 `s04-hvac` 重排）
 *
 * 容器固定 **340×424**：
 * `
 *  caption 26px 顶部居中（设计板把「主驾 / 副驾」放在环上方）
 *  圆环 直径 236（r = 114，描边 20.8），圆心 (170, 186)
 *  温度 62px 粗体，圆环正中
 *  − / + 72×72 圆角 24 方块，顶部 352
 * `
 *
 * 第五十轮按设计板 `s04-hvac` 修正两处：
 * - 描边 8 → **20.8**（SVG `stroke-width="8"` 在 100×100 viewBox 放大到 260px）；
 * - 弧长口径 `(t − 16) / 16` → **`t / 30`**（设计板 23.5℃ = 78.3%）。
 */
class TempDialView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * **真实温度值**（[temperature] 背后那个字段）。
     *
     * 之所以把它和 [temperature] 拆开，是因为「谁在写这个值」有两种，
     * 而两者的**规则不一样**：
     *
     * | 谁在写 | 走哪条路 | 拖动中允许吗 |
     * |---|---|---|
     * | 车机回读（实际温度下发） | [temperature] setter | **不允许**（回声会打断手势） |
     * | 用户自己拖出来的 | [applyDragTemperature] | **必须允许**（否则拖动形同虚设） |
     *
     * 之前这两种走的是**同一个** setter，而 setter 在 `dragging` 为真时直接 return ——
     * 于是用户自己拖出来的值也被当成「车机回声」挡掉了：
     * 表盘纹丝不动，抬手时 [onTemp] 报的又是拖之前的旧温度，
     * 宿主换算出来的增量约等于 0 → **拖表盘完全没有效果**。
     */
    private var tempValue: Float = 23.5f

    /**
     * 对外的温度入口（车机回读 / 外部设置）。
     *
     * 拖动中**忽略**外部写入：车机的回声会把用户正在拖的那个值覆盖掉，手感就断了。
     * 用户自己拖出来的值不走这里（见 [applyDragTemperature]），所以不会被误伤。
     */
    var temperature: Float
        get() = tempValue
        set(value) {
            // 拖动中不回读覆盖，避免手势被打断
            if (dragging) return
            applyTemperature(value)
        }

    var caption: String = "主驾"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 只影响 **caption** 的字色（同步态下 caption 变淡，但**仍可操作**，同款）。
     *
     * 第五十轮按设计板 `s04-hvac` 修正：圆环进度弧与温度数值**始终是满色**
     * （设计板里即使「已关机」也是满色 accent 弧 + 满色温度字），
     * 所以这里不再把弧 / 温度调暗。
     */
    var dimmed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 真正拦截触摸（关机时用）。 */
    var disabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** ±0.5℃ 步进回调。 */
    var onDelta: ((Float) -> Unit)? = null

    /** 拖动结束的**绝对温度**回调。 */
    var onTemp: ((Float) -> Unit)? = null

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arcRect = RectF()
    private var minusCx = 0f
    private var plusCx = 0f
    private var buttonCy = 0f

    /** 是否正在拖动表盘。 */
    private var dragging = false

    /**
     * **画出来**的温度（动画中间态）。
     *
     * 真值还是 [temperature]（它是对外的唯一入口）；
     * 这里存的是「此刻该画多少度」—— 温度变化时它从旧值渐变到新值，
     * 圆环弧长和中间的数字都跟着它走。
     */
    private var displayTemp = 23.5f

    /**
     * 写入温度，并让显示值**渐变**过去（外部回读走这条）。
     *
     * 数值变了 → 圆环和数字**渐变**过去。
     * 之前是直接改数字 + invalidate：圆环「啪」地跳一下、数字也「啪」地跳一下，
     * 看不出是渐变过去的，像是换了一张图。
     */
    private fun applyTemperature(value: Float) {
        if (tempValue == value) return
        tempValue = value
        // 按住 ± 键连发时，回读来得比渐变还快（150ms 一步 vs 200ms 渐变）——
        // 渐变会被反复重定向，数值**永远落后**于真实值，看着就是「按了没反应」。
        // 这时候换短一档的渐变（120ms < 150ms），每一步都能走完。
        val duration =
            if (pressedButton != null) MotionSpec.VALUE_FAST_DURATION else MotionSpec.VALUE_DURATION
        animateTemperature(value, duration)
    }

    /**
     * 写入**用户拖出来**的温度（拖动走这条）。
     *
     * 与 [applyTemperature] 只差一件事：**不渐变，直接落到目标值**。
     *
     * 拖动时手指是节奏的来源，渐变会永远滞后于手指 ——
     * 每一次 MOVE 都会重启动画（从当前显示值渐变到新目标），
     * 目标在不停变，动画永远追不上，看着就是「表盘不跟手」。
     * 0.5℃ 的吸附步长足够细，直接落值反而跟手。
     */
    private fun applyDragTemperature(value: Float) {
        if (tempValue == value) return
        tempValue = value
        // 作废在途的渐变帧 —— 上一段渐变的尾帧会把 displayTemp 写回旧值，
        // 正在拖的时候那一帧一来，「跟着手指走」就断了。
        tempToken = Any()
        displayTemp = value
        invalidate()
    }

    /**
     * 温度渐变的「凭证」。
     *
     * 温度可能来得比动画快（连续按 ±、车机回读）——
     * 上一段动画的帧如果还在写 [displayTemp]，两段动画会互相打架（画面会抖）。
     * 这里用一枚 token 认领「当前这一段」：后来的 token 作废旧帧。
     */
    private var tempToken: Any? = null

    /**
     * 温度渐变到新值。
     *
     * 用 [Motion.animateValue]：每帧回调中间值，画出来就行。
     *
     * @param duration 渐变时长 —— 连发时用短一档（见 [applyTemperature]）。
     */
    private fun animateTemperature(target: Float, duration: Long = MotionSpec.VALUE_DURATION) {
        val from = displayTemp
        val token = Any()
        tempToken = token
        Motion.animateValue(from, target, duration) { v ->
            if (tempToken !== token) return@animateValue
            displayTemp = v
            invalidate()
        }
    }

    /**
     * 当前被按住的 ± 键（`"minus"` / `"plus"`）。
     *
     * ± 键是**画在这个视图里**的（不是子 View），
     * 所以没法用 [Motion.press] —— 只能在画的时候自己缩放。
     * 做法和 Dock 的自绘按键一样：记下按的是哪个键，
     * 画的时候以**键自己的圆心**为中心缩放。
     */
    private var pressedButton: String? = null

    /** 按下缩放的当前值（动画中间态）。 */
    private var pressScale = 1f

    /** 按下 / 松手的缩放动画。 */
    private var pressAnimator: ValueAnimator? = null

    /** 按住某个 ± 键：缩小一点。 */
    private fun startPress(key: String) {
        pressedButton = key
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, MotionSpec.PRESS_SCALE).apply {
            duration = MotionSpec.PRESS_DOWN_DURATION
            interpolator = Motion.STANDARD
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    /** 松手：弹回原大。 */
    private fun endPress() {
        pressedButton = null
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, 1f).apply {
            duration = MotionSpec.PRESS_UP_DURATION
            interpolator = Motion.REBOUND
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    // ------------------------------------------------------------------ ± 键连发

    /**
     * 按住 ± 键的**连发**计时器。
     *
     * ## 为什么需要它
     *
     * ± 键原来只在 `ACTION_DOWN` 步进**一次** —— 按住不放就再也不动了。
     * 想加 2℃ 就得连点 4 次，车机上这是很别扭的手感
     * （车机自带的 HVAC 面板都是按住连发的）。
     *
     * 做法和 Dock 的长按一个路子：`Handler.postDelayed` 排一个**自续**的任务，
     * 到点了步进一次、再排下一次；抬手 / 视图离开窗口时撤掉。
     */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 连发任务：到点了就再步进一次，然后排下一次。
     *
     * 自续（而不是排一串固定任务）的好处是**节奏不累积漂移** ——
     * 每一次都从「上一次真正跑完的时刻」起算 150ms。
     */
    private val repeatTask = object : Runnable {
        override fun run() {
            val key = pressedButton ?: return   // 已经松手了 —— 别再排下一次
            onDelta?.invoke(if (key == "minus") -STEP else STEP)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    /**
     * 停掉连发。
     *
     * 抬手、视图离开窗口都要调 —— 否则视图被摘掉之后还在后台调 [onDelta]，
     * 宿主照样会往车机下发温度。
     */
    private fun stopRepeat() {
        handler.removeCallbacks(repeatTask)
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val cx = w / 2f
        val cy = RING_CY
        val r = RING_R

        // —— 圆环轨道（满圈）——
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        arc.color = palette.surfaceStrong
        canvas.drawCircle(cx, cy, r, arc)

        // —— 强调色进度：从正上方顺时针走，温度越高覆盖越多 ——
        //
        // 第五十轮按设计板 `s04-hvac` 修正弧长口径：
        // 设计板圆环是 `<svg viewBox="0 0 100 100">` 里的 r=44、
        // `stroke-dasharray="276.5"`（= 2πr，满圈）、`stroke-dashoffset="60"`，
        // 可见弧 = 276.5 − 60 = 216.5 → **216.5 / 276.5 = 78.3%**。
        //
        // 78.3% 对应的温度是 **23.5 / 30**（16℃ 起、30℃ 满格），
        // 所以比例口径是 `temperature / 30`，不是 `(t − 16) / 16`。
        val frac = (displayTemp / ARC_TEMP_SPAN).coerceIn(0f, 1f)
        if (frac > 0.005f) {
            arc.color = palette.accent
            canvas.drawArc(arcRect, RING_START_ANGLE, 360f * frac, false, arc)
        }

        // —— 温度数值（设计板：满色 text，不随关机变暗）——
        textPaint.color = palette.text
        textPaint.textSize = TEMP_TEXT_SIZE
        canvas.drawText(format(displayTemp), cx, cy + TEMP_TEXT_SIZE * 0.34f, textPaint)

        // —— caption（环上方）——
        textPaint.color = if (dimmed) palette.textFaint else palette.textDim
        textPaint.textSize = CAPTION_TEXT_SIZE
        canvas.drawText(caption, cx, CAPTION_BASELINE, textPaint)

        // —— − / + 方块按钮 ——
        buttonCy = BUTTON_TOP + BUTTON_SIZE / 2f
        minusCx = cx - (BUTTON_SIZE + BUTTON_GAP) / 2f
        plusCx = cx + (BUTTON_SIZE + BUTTON_GAP) / 2f
        drawButton(canvas, "minus", minusCx, buttonCy, "−")
        drawButton(canvas, "plus", plusCx, buttonCy, "+")
    }

    /**
     * 画一个 ± 键。
     *
     * 按住的那个键以**自己的圆心**为中心缩小（和 Dock 自绘按键一个做法）——
     * 缩放围绕圆心才不会看着「往边上歪」。
     */
    private fun drawButton(canvas: Canvas, key: String, cx: Float, cy: Float, glyph: String) {
        val s = if (pressedButton == key) pressScale else 1f
        canvas.save()
        if (s != 1f) canvas.scale(s, s, cx, cy)

        buttonPaint.style = Paint.Style.FILL
        buttonPaint.color = palette.surfaceSoft
        val rr = BUTTON_RADIUS
        canvas.drawRoundRect(cx - BUTTON_SIZE / 2f, BUTTON_TOP, cx + BUTTON_SIZE / 2f, BUTTON_TOP + BUTTON_SIZE, rr, rr, buttonPaint)
        buttonPaint.style = Paint.Style.STROKE
        buttonPaint.strokeWidth = 1f
        buttonPaint.color = palette.stroke
        canvas.drawRoundRect(cx - BUTTON_SIZE / 2f, BUTTON_TOP, cx + BUTTON_SIZE / 2f, BUTTON_TOP + BUTTON_SIZE, rr, rr, buttonPaint)
        buttonPaint.style = Paint.Style.FILL

        buttonText.color = palette.text
        buttonText.textSize = BUTTON_TEXT_SIZE
        canvas.drawText(glyph, cx, cy + BUTTON_TEXT_SIZE * 0.36f, buttonText)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                // onTouchEvent:203-217 —— 落在 ± 键上立刻步进，否则进入拖动
                if (onButton(x, y)) {
                    val minus = x < width / 2f
                    // 按下反馈 + 步进同时发生：手指还没抬起来就已经看到键缩了一下，
                    // 这才像「按到了」。
                    startPress(if (minus) "minus" else "plus")
                    onDelta?.invoke(if (minus) -STEP else STEP)
                    // 按住不放 → 连发。
                    // 门槛用**系统长按超时**（和 Dock 的长按一个标准），
                    // 用户不用学新的规矩，也不会因为手抖就连加好几度。
                    stopRepeat()
                    handler.postDelayed(repeatTask, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                // 走 [applyDragTemperature]（不是 `temperature = …`）：
                // 后者在拖动中会被当成「车机回声」挡掉，指针动了值却不动。
                applyDragTemperature(temperatureFor(event.x, event.y))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 先停连发：手指已经抬起来了，不该再往下加温度。
                stopRepeat()
                if (pressedButton != null) endPress()
                if (dragging) {
                    // 顺序很重要：**先把 dragging 放下，再回调**。
                    // 手指已经抬起来了，这时宿主在 [onTemp] 里回写的温度
                    // 必须被接受 —— 之前顺序反了，回写被当成「拖动中的回声」丢掉了。
                    dragging = false
                    // 上报的是**拖出来的绝对温度**（宿主靠它换算增量）。
                    // 之前这里报的是拖之前的旧值 → 增量≈0 → 拖表盘完全没效果。
                    onTemp?.invoke(temperature)
                }
                invalidate()
                return true
            }
        }
        return true
    }

    /** 命中 ± 按钮热区（矩形，方块按钮）。 */
    private fun onButton(x: Float, y: Float): Boolean {
        if (y < BUTTON_TOP - 8f || y > BUTTON_TOP + BUTTON_SIZE + 8f) return false
        val half = BUTTON_SIZE / 2f + 8f
        return abs(x - minusCx) < half || abs(x - plusCx) < half
    }

    /**
     * 拖动角度 → 温度。
     *
     * `atan2(x - w/2, -(y - 圆心y))` → 角度夹到 [-135°,135°]
     * 线性映射到 16~32℃ → 吸附到 0.5℃。
     */
    private fun temperatureFor(x: Float, y: Float): Float {
        val cx = width / 2f
        val cy = RING_CY
        var degrees = Math.toDegrees(Math.atan2((x - cx).toDouble(), (-(y - cy)).toDouble()))
        if (degrees > 180.0) degrees -= 360.0
        if (degrees < -135.0) degrees = -135.0
        if (degrees > 135.0) degrees = 135.0
        val raw = ((degrees + 135.0) / 270.0 * 16.0) + 16.0
        return snap(raw.toFloat())
    }

    /** 吸附到 0.5℃。 */
    private fun snap(t: Float): Float {
        val snapped = Math.round(t * 2f) / 2f
        return snapped.coerceIn(16f, 32f)
    }

    /**
     * 温度文案：固定一位小数。
     */
    private fun format(t: Float): String = String.format(Locale.US, "%.1f°", t)

    companion object {
        /** 圆环半径（设计 px）—— 描边 8，外径 236。 */
        const val RING_R = 114f

        /** 圆环圆心 y（设计 px，相对容器顶）。 */
        const val RING_CY = 186f

        /** 圆环描边宽度（设计 px）—— 设计板 SVG `stroke-width="8"` × (260/100) = 20.8。 */
        const val RING_STROKE = 20.8f

        /**
         * 弧长口径的温度跨度（设计 px）。
         *
         * 设计板 `s04-hvac` 的圆环满圈对应 **30℃**（16℃ 起点不占弧），
         * 23.5℃ → 78.3% 与 SVG 的 `dasharray/dashoffset` 完全吻合。
         */
        const val ARC_TEMP_SPAN = 30f

        /** 强调色弧起点（度）—— 正上方。 */
        const val RING_START_ANGLE = -90f

        /** 温度字号（设计 px）。 */
        const val TEMP_TEXT_SIZE = 62f

        /** caption 字号（设计 px）。 */
        const val CAPTION_TEXT_SIZE = 26f

        /** caption 基线（设计 px，相对容器顶）。 */
        const val CAPTION_BASELINE = 26f

        /** ± 按钮边长（设计 px）。 */
        const val BUTTON_SIZE = 72f

        /** ± 按钮圆角（设计 px）。 */
        const val BUTTON_RADIUS = 24f

        /** ± 按钮顶部（设计 px，相对容器顶）。 */
        const val BUTTON_TOP = 352f

        /** ± 按钮中心间距（设计 px）。 */
        const val BUTTON_GAP = 64f

        /** ± 按钮字形字号（设计 px）。 */
        const val BUTTON_TEXT_SIZE = 30f

        /** 单次步进 0.5℃。 */
        const val STEP = 0.5f

        /**
         * 按住 ± 键的**连发间隔**（毫秒）。
         *
         * 150ms：每秒约 6.7 步 = **3.3℃/s** ——
         * 16℃ 的全量程大约 5 秒走完，既不拖沓也不会一冲到底。
         *
         * 这个数还决定了一件事：连发时的渐变必须**短于**它
         * （见 [MotionSpec.VALUE_FAST_DURATION]），否则数值会一直落后。
         */
        const val REPEAT_INTERVAL_MS = 150L
    }
}
