package com.cardesk.launcher.ui.shell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 页面指示器。
 *
 * 规格（**版是右侧竖条**）
 * - 玻璃竖条 52 宽、右侧 36px、上下 padding 16
 * - 每个点命中区 52×44，点之间间距 4
 * - 选中点 10×30、未选中 10×10
 * - 选中色 `#3d7bff`、未选中色 `#66ffffff`
 * - 切换时高度做 350ms + `OvershootInterpolator(1.4f)` 动画
 *   （选中 30 / 未选中 10）
 *
 * **第三十三轮按用户要求改位置**：从「屏幕右侧竖条」改成
 * **「底部 Dock 栏正上方、水平居中」**，并相应改成**横向**排列
 * （竖条挪到 Dock 上方居中会又高又怪）。
 * 颜色、尺寸比例、切换动画全部。
 *
 * **第八十二轮按用户要求再改位置**：从「Dock 栏正上方」改成
 * **「状态栏那一行的正中」**。
 *
 * 为什么是这个位置：状态栏原来正中间摆着「位置 + 天气」两个胶囊，
 * 第八十二轮把它们挪到了右侧图标行（和蓝牙/信号/时钟在一起），
 * 正中就空出来了 —— 页面指示器放在状态栏正中，
 * 既不再压在 Dock 上方（那里原来很挤），也不用另找位置。
 *
 * 几何直接引用 [ShellStatusBar.ROW_TOP] / [ShellStatusBar.ROW_H]，
 * **不另写一份数值** —— 状态栏行高以后再改，圆点会自动跟着走，
 * 两处各写一个常量早晚会不同步。
 */
class PageDotsView(context: Context) : View(context) {

    var count: Int = 3
        set(v) {
            field = v.coerceAtLeast(1)
            heights = FloatArray(field) { if (it == index) DOT_H_ON else DOT_H_OFF }
            requestLayout()
            invalidate()
        }
    var index: Int = 0
        set(v) {
            val next = if (count <= 0) 0 else ((v % count) + count) % count
            if (field == next) return
            field = next
            // 排障场景：「切页后圆点没动」——当前页变化落盘。
            Logx.d(LogTags.SCENE, "页面圆点索引变化", "index" to next, "count" to count)
            animateDots()
        }
    var onDot: ((Int) -> Unit)? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.GLASS_FILL }
    private val barStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.GLASS_STROKE
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private val hits = ArrayList<RectF>()

    // —— 第三十三轮：横向排列，贴在 Dock 上方居中 ——
    // —— 第八十二轮：改到状态栏那一行的正中（横向排列保留）——

    /** 横向布局：玻璃条左右 padding。 */
    private val padH = 10f

    /**
     * 横向布局：玻璃条上下 padding。
     *
     * 第八十二轮从 8 收到 3：玻璃条现在要放进**状态栏那一行**里
     * （行高 43），原来 44 高的条根本塞不进去。
     */
    private val padV = 3f

    /** 每个点的命中高度（点带高度）。 */
    private val hitH = 28f

    /** 点与点之间的间距。 */
    private val dotGap = 8f

    /** 圆点高度（未选中点 10×10，横向布局里高度固定 10）。 */
    private val dotH = 10f

    /** 每个点当前动画宽度（px），选中 30 / 未选中 10。 */
    private var heights: FloatArray = FloatArray(3) { if (it == 0) DOT_H_ON else DOT_H_OFF }
    private var animator: ValueAnimator? = null

    /** 玻璃条宽：按「每个点占一个 30 宽的槽 + 间距」算，固定不随动画变。 */
    private fun barWidth(): Float = padH * 2 + count * DOT_H_ON + (count - 1) * dotGap

    /** 玻璃条高。 */
    private fun barHeight(): Float = padV * 2 + hitH

    /**
     * 玻璃条上沿。
     *
     * 第八十二轮：条**垂直居中在状态栏那一行里**
     * （行中心 = [ShellStatusBar.ROW_TOP] + [ShellStatusBar.ROW_H] / 2）。
     */
    private fun barTop(): Float = ShellStatusBar.ROW_TOP + (ShellStatusBar.ROW_H - barHeight()) / 2f

    /**
     * （:1915-1943）：每个点从当前高度动画到目标高度
     * 时长 350ms，插值器 `OvershootInterpolator(1.4f)`。
     */
    private fun animateDots() {
        animator?.cancel()
        val from = heights.copyOf()
        val to = FloatArray(count) { if (it == index) DOT_H_ON else DOT_H_OFF }
        if (from.size != to.size) {
            heights = to
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOT_ANIM_MS
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                for (i in to.indices) heights[i] = from[i] + (to[i] - from[i]) * f
                invalidate()
            }
        }
        animator = a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = barWidth()
        val h = barHeight()
        // 水平居中 + 垂直居中在状态栏那一行里（第八十二轮：从 Dock 上方挪过来）
        val left = (Design.W - w) / 2f
        val top = barTop()
        val rect = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, barPaint)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, barStroke)

        hits.clear()
        // 圆点横向紧凑排列、整体在条内居中（选中点变宽时整组跟着居中）
        val widths = FloatArray(count) { if (it < heights.size) heights[it] else DOT_H_OFF }
        val groupW = widths.sum() + dotGap * (count - 1).coerceAtLeast(0)
        val innerW = w - padH * 2
        var x = left + padH + (innerW - groupW) / 2f
        val cy = top + padV + hitH / 2f
        val slotW = if (count > 0) innerW / count else innerW
        for (i in 0 until count) {
            // 命中区：等分条内宽度
            val slotLeft = left + padH + slotW * i
            hits.add(RectF(slotLeft, top + padV, slotLeft + slotW, top + padV + hitH))
            val dw = widths[i]
            dotPaint.color = if (i == index) Skin.ACCENT else Skin.surfaceStrong
            canvas.drawRoundRect(RectF(x, cy - dotH / 2f, x + dw, cy + dotH / 2f), dotH / 2f, dotH / 2f, dotPaint)
            x += dw + dotGap
        }
    }

    private var downHit = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只有命中指示点才消费事件，其余位置放行给下层页面
                downHit = hits.any { it.contains(event.x, event.y) }
                return downHit
            }
            MotionEvent.ACTION_UP -> {
                if (downHit) {
                    hits.forEachIndexed { i, r ->
                        if (r.contains(event.x, event.y)) {
                            // 排障场景：「点圆点没切页」——带目标页落盘。
                            Logx.event(
                                LogTags.SCENE,
                                "页面圆点点击切页",
                                "target" to i,
                                "current" to index,
                                "count" to count,
                            )
                            onDot?.invoke(i)
                            performClick()
                        }
                    }
                }
                downHit = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downHit = false
                return false
            }
        }
        return downHit
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Design.W.toInt(), Design.H.toInt())
    }

    companion object {
        /** 选中点高度 30px。 */
        const val DOT_H_ON = 30f

        /** 未选中点高度 10px。 */
        const val DOT_H_OFF = 10f

        /** 动画时长 350ms。 */
        const val DOT_ANIM_MS = 350L
    }
}
