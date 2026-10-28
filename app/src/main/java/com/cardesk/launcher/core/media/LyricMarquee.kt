package com.cardesk.launcher.core.media

/**
 * 歌词**跑马灯**（超宽时水平滚动）。
 *
 * ## 为什么需要它
 *
 * 第三十九轮（4385）把导航页歌词放大（当前句 28px → 34px）之后，
 * 长句比容器宽的概率明显变大；原来那套做法是 `ellipsize=END` —— 直接把后半句截掉，
 * 歌词读一半就没了。用户要求改成**滚动跑马灯**：整句从左往右匀速平移，
 * 两端各停一会儿再反向滚回来，一句能完整读完。
 *
 * ## 几何
 *
 * `
 * 容器宽 = viewWidth
 * 文字宽 = textWidth
 * 溢出量 = overflow = textWidth - viewWidth
 * `
 *
 * 偏移量 `offset` 取值 `[-overflow, 0]`：
 *
 * - `0` → 文字**左边缘**贴着容器左边（起始位置）；
 * - `-overflow` → 文字**右边缘**贴着容器右边（末尾位置）。
 *
 * 一个完整周期 = 「左端停留 → 向左滚 → 右端停留 → 滚回来」，
 * 周期长度 = `2 × (overflow / 速度) + 2 × 停留`。
 *
 * 纯 Kotlin（不依赖 Android），可在 JVM 单测里覆盖。
 */
object LyricMarquee {

    /** 滚动速度（px / 秒）。 */
    const val SPEED_PX_PER_SEC = 26f

    /** 两端停留时长（毫秒）。 */
    const val EDGE_PAUSE_MS = 1400L

    /** 判定溢出至少要超出的像素数（避免亚像素抖动导致来回切换）。 */
    const val OVERFLOW_EPS = 1f

    /**
     * 是否需要滚动。
     *
     * @param textWidth 文字实际宽度（px）
     * @param viewWidth 容器宽度（px）
     */
    fun needsScroll(textWidth: Float, viewWidth: Float): Boolean =
        viewWidth > 0f && textWidth > viewWidth + OVERFLOW_EPS

    /**
     * 一个完整往返周期的时长（毫秒）。
     *
     * 不需要滚动时返回 0。
     */
    fun cycleMs(textWidth: Float, viewWidth: Float): Long {
        val overflow = textWidth - viewWidth
        if (overflow <= 0f) return 0L
        val travel = (overflow / SPEED_PX_PER_SEC * 1000.0).toLong()
        return travel * 2 + EDGE_PAUSE_MS * 2
    }

    /**
     * 给定「已经过去多久」算出水平偏移（<= 0）。
     *
     * @param textWidth 文字实际宽度（px）
     * @param viewWidth 容器宽度（px）
     * @param elapsedMs 从滚动开始算起的毫秒数
     * @return 偏移量：0 = 左端；`-overflow` = 右端
     */
    fun offset(textWidth: Float, viewWidth: Float, elapsedMs: Long): Float {
        val overflow = textWidth - viewWidth
        if (overflow <= 0f || elapsedMs <= 0L) return 0f
        val speed = SPEED_PX_PER_SEC.toDouble()
        val travelMs = overflow / speed * 1000.0
        val cycle = travelMs * 2.0 + EDGE_PAUSE_MS * 2.0
        val t = (elapsedMs % cycle.toLong().toDouble())
        val pause = EDGE_PAUSE_MS.toDouble()
        return when {
            // 左端停留
            t < pause -> 0.0
            // 向左滚（文字左边缘往左走）
            t < pause + travelMs -> -(t - pause) / 1000.0 * speed
            // 右端停留
            t < pause * 2 + travelMs -> -overflow.toDouble()
            // 滚回来
            else -> -overflow.toDouble() + (t - pause * 2 - travelMs) / 1000.0 * speed
        }.toFloat()
    }
}

/**
 * 跑马灯的**显示状态机**（每一行歌词各持一份）。
 *
 * 滚动起点 = **这句歌词被换上的那一刻**（而不是第一次绘制那一刻）：
 * 歌词一换就开始滚，符合直觉；页面重新显示时也不会「接着上一句的进度」滚半截。
 */
class LyricMarqueeState {

    /** 锚点时间戳（毫秒）：当前这句歌词被换上/被换掉的时刻。 */
    private var anchorMs: Long = 0L

    /** 当前文本（变了就重置）。 */
    private var lastText: String = ""

    /**
     * 文本变化 → 重置滚动进度。
     *
     * @param text 新文本
     * @param nowMs 变化发生的时刻（毫秒）
     */
    fun setText(text: String, nowMs: Long) {
        if (text == lastText) return
        lastText = text
        reset(nowMs)
    }

    /**
     * 重置（回到左端起点）。
     *
     * @param nowMs 重新起滚的时刻（毫秒）
     */
    fun reset(nowMs: Long) {
        anchorMs = nowMs
    }

    /**
     * 当前水平偏移（<= 0）。
     *
     * 不需要滚动时返回 0。
     *
     * @param nowMs 当前时间戳（毫秒）
     * @param textWidth 文字实际宽度（px）
     * @param viewWidth 容器宽度（px）
     */
    fun offset(nowMs: Long, textWidth: Float, viewWidth: Float): Float {
        if (!LyricMarquee.needsScroll(textWidth, viewWidth)) return 0f
        val elapsed = nowMs - anchorMs
        if (elapsed < 0L) return 0f
        return LyricMarquee.offset(textWidth, viewWidth, elapsed)
    }
}
