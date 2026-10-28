package com.cardesk.launcher.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 跑马灯参数。
 *
 * - → 滚动速度 42dp/s；
 * - → 每轮开头停 1200ms；
 * - → 每轮结尾停 1400ms；
 * - → 单轮动画最短 2200ms。
 */
object LyricMarquee {

    /** 滚动速度 42dp/s。 */
    const val SPEED_DP_PER_SEC = 42f

    /** 每轮开头停顿。 */
    const val PAUSE_START_MS = 1200L

    /** 每轮结尾停顿。 */
    const val PAUSE_END_MS = 1400L

    /** 单轮动画时长下限。 */
    const val MIN_CYCLE_MS = 2200L

    /** 撑宽 View 时多留的余量（设计 px），避免最后一个字被排版裁掉。 */
    const val LAYOUT_PAD_PX = 24f

    /** 文字宽度是否超过可用宽度（需要滚动）。。 */
    fun needsScroll(textWidthPx: Float, viewportPx: Int): Boolean = textWidthPx > viewportPx + 1f

    /** 一轮要滚多远：正好把超出可用宽度的部分滚完。 */
    fun distance(textWidthPx: Float, viewportPx: Int): Float = textWidthPx - viewportPx

    /** 单轮时长：距离 ÷ 速度，且不短于 [MIN_CYCLE_MS]。。 */
    fun cycleMs(distancePx: Float, density: Float): Long {
        val speedPxPerSec = (density * SPEED_DP_PER_SEC).coerceAtLeast(1f)
        return maxOf(MIN_CYCLE_MS, Math.round(distancePx / speedPxPerSec * 1000f).toLong())
    }
}

/**
 * 带跑马灯的歌词 TextView：文字宽度超过可用宽度时横向滚动，否则静止。
 *
 * 行为
 * - 文字不超宽就不滚；
 * - 长句要**先把 View 撑到文字宽度并左对齐**，
 *   否则 `maxLines=1` 会把长句截断在可用宽度处，尾巴永远露不出来；
 * - 滚动用 `translationX` 从 0 移到 `-distance`，匀速；
 * - 每轮开头停 1200ms（动画 `startDelay`）、结尾停 1400ms（`postDelayed`）；
 * - 用「代号（gen）」保证文本一变旧动画立刻作废；
 * - View 离开窗口（`windowToken == null`）时停止；
 * - 重新设置文本时先取消旧动画。
 */
open class LyricMarqueeTextView(context: Context) : TextView(context) {

    /** 当前代号：文本一变就自增，旧的异步回调据此作废。 */
    private var gen: Int = 0

    /** 正在跑的动画与下一轮的延时任务。 */
    private var animator: ValueAnimator? = null
    private var loop: Runnable? = null

    /** 原始对齐方式（文字在 View 内的对齐）：滚动时临时左对齐，停下来再还原。 */
    var baseGravity: Int = Gravity.START or Gravity.TOP
        set(value) {
            field = value
            gravity = value
        }

    /** 原始布局宽度（-1 = MATCH_PARENT），用于还原。 */
    private var baseWidth: Int = -1

    /** 原始布局对齐（含水平部分），用于还原。 */
    private var baseLayoutGravity: Int = -1

    /** 是否已经记过原始布局。 */
    private var layoutCaptured = false

    init {
        maxLines = 1
        ellipsize = null
        includeFontPadding = false
    }

    override fun setText(text: CharSequence?, type: BufferType) {
        super.setText(text, type)
        // 文本变化 → 旧跑马灯立刻作废，按新文本重来
        restart()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 刚挂上窗口时宽度可能还是 0，重来一次让排版后再跑
        restart()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) restart()
    }

    /** 作废旧一代动画并重新计算。 */
    private fun restart() {
        gen++
        stop()
        val g = gen
        post { if (g == gen) apply(g, 0) }
    }

    /** 停掉动画并复位。 */
    private fun stop() {
        loop?.let { removeCallbacks(it) }
        loop = null
        animator?.let { runCatching { it.cancel() } }
        animator = null
        translationX = 0f
    }

    /**
     * 宽度够了才滚：文字超宽 → 撑宽 + 左对齐并开跑；不超宽 → 停下并还原布局。
     *
     */
    private fun apply(g: Int, retry: Int) {
        if (g != gen) return
        if (windowToken == null) return // 还没挂到窗口上，先不跑
        val content = text?.toString().orEmpty()
        if (content.isEmpty()) {
            stop()
            return
        }
        val viewport = viewportWidth()
        if (viewport <= 0) {
            // 还没量到宽度：稍后重试
            if (retry < 30) postDelayed({ if (g == gen) apply(g, retry + 1) }, 60L)
            return
        }
        val textWidth = measureTextWidth(content)
        if (!LyricMarquee.needsScroll(textWidth, viewport)) {
            stop()
            restoreLayout()
            return
        }
        captureLayout()
        // —— View 撑到文字宽度并左对齐
        // 否则 maxLines=1 会把长句截断在可用宽度处。
        setLayoutWidth(maxOf(viewport + 1, Math.ceil(textWidth.toDouble()).toInt() + LyricMarquee.LAYOUT_PAD_PX.toInt()))
        pinStartAligned()
        val dist = LyricMarquee.distance(textWidth, viewport)
        val duration = LyricMarquee.cycleMs(dist, resources.displayMetrics.density)
        cycle(g, dist, duration)
    }

    /** 可用宽度：父容器的内容宽度。 */
    private fun viewportWidth(): Int {
        val p = parent as? View ?: return width - totalPaddingLeft - totalPaddingRight
        return p.width - p.paddingLeft - p.paddingRight
    }

    /** 记下原始布局参数（只记一次）。 */
    private fun captureLayout() {
        if (layoutCaptured) return
        val lp = layoutParams ?: return
        baseWidth = lp.width
        baseLayoutGravity = when (lp) {
            is LinearLayout.LayoutParams -> lp.gravity
            is FrameLayout.LayoutParams -> lp.gravity
            else -> -1
        }
        layoutCaptured = true
    }

    /** 撑宽 View。 */
    private fun setLayoutWidth(w: Int) {
        val lp = layoutParams ?: return
        if (lp.width == w) return
        lp.width = w
        layoutParams = lp
    }

    /** 布局与文字都改成左对齐。 */
    private fun pinStartAligned() {
        val textGravity = (baseGravity and Gravity.VERTICAL_GRAVITY_MASK) or Gravity.START
        if (gravity != textGravity) gravity = textGravity
        val lp = layoutParams ?: return
        val pinned = keepVerticalGravity(currentLayoutGravity(lp)) or Gravity.START
        if (currentLayoutGravity(lp) != pinned) {
            when (lp) {
                is LinearLayout.LayoutParams -> lp.gravity = pinned
                is FrameLayout.LayoutParams -> lp.gravity = pinned
            }
            layoutParams = lp
        }
    }

    /** 取布局里的 gravity（只有 LinearLayout / FrameLayout 的 LayoutParams 有）。 */
    private fun currentLayoutGravity(lp: android.view.ViewGroup.LayoutParams): Int = when (lp) {
        is LinearLayout.LayoutParams -> lp.gravity
        is FrameLayout.LayoutParams -> lp.gravity
        else -> -1
    }

    /** 还原成没跑马灯时的布局。 */
    private fun restoreLayout() {
        if (!layoutCaptured) return
        if (gravity != baseGravity) gravity = baseGravity
        val lp = layoutParams ?: return
        var changed = false
        if (lp.width != baseWidth) {
            lp.width = baseWidth
            changed = true
        }
        if (currentLayoutGravity(lp) != baseLayoutGravity) {
            when (lp) {
                is LinearLayout.LayoutParams -> lp.gravity = baseLayoutGravity
                is FrameLayout.LayoutParams -> lp.gravity = baseLayoutGravity
            }
            changed = true
        }
        if (changed) layoutParams = lp
    }

    /** 保留垂直对齐，水平改成 START。 */
    private fun keepVerticalGravity(g: Int): Int {
        if (g == -1 || g == 0) return Gravity.CENTER_VERTICAL
        val vertical = g and Gravity.VERTICAL_GRAVITY_MASK
        return if (vertical == 0) Gravity.CENTER_VERTICAL else vertical
    }

    /**
     * 一轮：停 1200ms → 匀速左移 → 停 1400ms → 回到起点再来一轮。
     * 与。
     */
    private fun cycle(g: Int, dist: Float, durationMs: Long) {
        translationX = 0f
        val runner = Runnable {
            if (g != gen || windowToken == null) return@Runnable
            val a = ValueAnimator.ofFloat(0f, -dist)
            a.startDelay = LyricMarquee.PAUSE_START_MS
            a.duration = durationMs
            a.interpolator = LinearInterpolator()
            a.addUpdateListener { anim ->
                if (g != gen || windowToken == null) {
                    anim.cancel()
                    return@addUpdateListener
                }
                translationX = anim.animatedValue as Float
            }
            a.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || g != gen || windowToken == null) return
                    translationX = -dist
                    val next = Runnable { if (g == gen && windowToken != null) cycle(g, dist, durationMs) }
                    loop = next
                    postDelayed(next, LyricMarquee.PAUSE_END_MS)
                }
            })
            animator = a
            a.start()
        }
        loop = runner
        runner.run()
    }

    /** 文字实际宽度。 */
    private fun measureTextWidth(content: String): Float {
        val measured = paint.measureText(content)
        val bounds = Rect()
        paint.getTextBounds(content, 0, content.length, bounds)
        val layout = layout
        val lineWidth = if (layout != null && layout.lineCount > 0) layout.getLineWidth(0) else 0f
        return maxOf(measured, maxOf(bounds.width().toFloat(), lineWidth))
    }
}
