package com.cardesk.launcher.core

import kotlin.math.abs

/**
 * 桌面翻页手势的纯逻辑判定。
 *
 * 第三十七轮把翻页手势收敛成三种：
 * 1. **双指横滑**（主手势，第206轮起）——单指要留给画中画里拖地图，双指不冲突；
 *    第三十七轮到第205轮曾经是「单指横滑」，实测和画中画的拖拽抢手势，已换掉；
 * 2. **左右三指滑动**（保留，第三十二轮用户要求）——多指手势仍然可用；
 * 3. **右缘竖滑**（保留手势）——最右 120px 命中区，纵向位移超过阈值就翻页。
 *
 * 出处
 * - 右缘竖滑：`DesignCanvas.EDGE_SWIPE_WIDTH / EDGE_SWIPE_SLOP / EDGE_SWIPE_TRIGGER`
 *   与 `onInterceptTouchEvent` / `onTouchEvent`；
 * - 三指手势：`onInterceptTouchEvent` 与 `dispatchTouchEvent`，
 *   阈值沿用 `m1183df(160f)`；
 * - 翻页取模循环。
 *
 * 纯 Kotlin（不依赖 Android），可在 JVM 单测里覆盖「阈值 / 方向 / 环绕」全链路。
 */
object PageGesture {

    /**
     * 三指横滑触发阈值（设计坐标 px）。
     *
     * `m1183df(160f)`
     * 只是方向从「竖滑」改成「横滑」。
     */
    const val THREE_FINGER_PX = 160f

    /**
     * 双指横滑的翻页阈值（设计坐标 px，按两指中位点算）。
     *
     * 第206轮：用户要求把翻页从单指改成**双指**——单指在画中画里要拖地图，
     * 两者在同一个区域天然冲突；双指既能翻页、又不抢单指的地图手势。
     *
     * 第207轮：160 → 140。双指比单指多一根手指要就位、行程也更短，
     * 阈值不降一档的话手感就是「推不动」。
     */
    const val TWO_FINGER_PX = 140f

    /**
     * 双指横滑必须「比纵向更横」的比例。
     *
     * 与 [SINGLE_FINGER_AXIS_RATIO] 同口径：斜着走的双指滑动不算翻页。
     */
    const val TWO_FINGER_AXIS_RATIO = 1.2f

    /**
     * 双指横滑时，两指间距允许的**相对**变化比例。
     *
     * 捏合缩放会让中位点几乎不动、但两指间距大幅变化；
     * 超过这个比例就当成「缩放手势」而不是翻页。
     *
     * 第207轮：0.18 → 0.30。原来太紧，真手指间距的正常抖动就被判成捏合，
     * 结果是「双指滑不动」。
     */
    const val TWO_FINGER_SCALE_TOLERANCE = 0.30f

    /**
     * 双指间距的**绝对**容差（设计坐标 px）。
     *
     * 两指离得近时（初始只有几十 px），相对比例会被几像素的抖动放大成
     * 「百分比暴涨」，正常横滑会被误判成捏合。
     * 给一个绝对下限兜住这种小间距场景。
     */
    const val TWO_FINGER_SPAN_SLOP = 60f

    /** 右缘竖滑的翻页阈值（设计坐标 px）。 */
    const val EDGE_TRIGGER = 120f

    /**
     * 单指横滑的翻页阈值（设计坐标 px）。
     *
     * `m1183df(160f)` ——
     * 那个值原本是三指竖滑的阈值，单指横滑用同一个数量级最合适：
     * 太小会和「底部卡片拖拽」「画中画点位」打架，太大则要划很远才翻页。
     */
    const val SINGLE_FINGER_PX = 160f

    /**
     * 单指横滑必须「比纵向更横」的比例。
     *
     * 桌面里有不少纵向手势（抽屉滚动、温度盘拖动、空调面板），
     * 横滑判定必须明显偏横，才不会把它们误判成翻页。
     */
    const val SINGLE_FINGER_AXIS_RATIO = 1.2f

    /**
     * 单指横滑的翻页步进。
     *
     * @param dx 水平位移（设计坐标 px）：**左滑为负、右滑为正**
     * @param dy 纵向位移（设计坐标 px）
     * @param threshold 触发阈值（设计坐标 px）
     * @param axisRatio 横向必须大于纵向的倍数
     * @return `1` = 下一页，`-1` = 上一页，`0` = 不触发
     */
    fun singleFingerStep(
        dx: Float,
        dy: Float,
        threshold: Float = SINGLE_FINGER_PX,
        axisRatio: Float = SINGLE_FINGER_AXIS_RATIO,
    ): Int {
        if (abs(dx) <= threshold) return 0
        if (abs(dx) <= abs(dy) * axisRatio) return 0
        return if (dx < 0f) 1 else -1
    }

    /**
     * 双指横滑的翻页步进（第206轮：主手势；第207轮修判定口径）。
     *
     * @param dx 两指中位点的水平位移（设计坐标 px）：**左滑为负、右滑为正**
     * @param dy 两指中位点的纵向位移（设计坐标 px）
     * @param span0 按下时两指间距（设计坐标 px）
     * @param span 抬手时两指间距（设计坐标 px）
     * @param threshold 触发阈值（设计坐标 px）
     * @param axisRatio 横向必须大于纵向的倍数
     * @return `1` = 下一页，`-1` = 上一页，`0` = 不触发（位移不够 / 不够横 / 在缩放）
     */
    fun twoFingerStep(
        dx: Float,
        dy: Float,
        span0: Float,
        span: Float,
        threshold: Float = TWO_FINGER_PX,
        axisRatio: Float = TWO_FINGER_AXIS_RATIO,
    ): Int {
        if (isTwoFingerPinch(span0, span)) return 0
        if (abs(dx) < threshold) return 0
        if (abs(dx) <= abs(dy) * axisRatio) return 0
        return if (dx < 0f) 1 else -1
    }

    /**
     * 两指间距的变化是不是「捏合缩放」（而不是横滑翻页）。
     *
     * 判定用**绝对量 + 相对量**两条线，取更容易触发的那条：
     * - 相对：间距变化超过初始间距的 [TWO_FINGER_SCALE_TOLERANCE]（30%）；
     * - 绝对：间距变化超过 [TWO_FINGER_SPAN_SLOP]（60 设计 px）。
     *
     * 为什么要有绝对量那条：两指初始靠得近时（只有几十 px），
     * 手指抖 10px 就是「30% 的变化」，纯相对判定会把正常横滑误杀成捏合。
     */
    fun isTwoFingerPinch(span0: Float, span: Float): Boolean {
        val delta = abs(span - span0)
        if (delta <= TWO_FINGER_SPAN_SLOP) return false
        return delta > span0 * TWO_FINGER_SCALE_TOLERANCE
    }

    /**
     * 三指横滑的翻页步进。
     *
     * @param dx 三指中位点的水平位移（设计坐标 px）：**左滑为负、右滑为正**
     * @param threshold 触发阈值（设计坐标 px）
     * @return `1` = 下一页，`-1` = 上一页，`0` = 不触发（位移不够）
     */
    fun threeFingerStep(dx: Float, threshold: Float = THREE_FINGER_PX): Int {
        if (abs(dx) > threshold) return if (dx > 0f) -1 else 1
        return 0
    }

    /**
     * 右缘竖滑的翻页步进（手势，保留）。
     *
     * @param dy 纵向位移（设计坐标 px）：**上滑为负、下滑为正**
     * @param threshold 触发阈值（设计坐标 px）
     * @return `1` = 下一页，`-1` = 上一页，`0` = 不触发
     */
    fun edgeSwipeStep(dy: Float, threshold: Float = EDGE_TRIGGER): Int {
        if (abs(dy) > threshold) return if (dy <= 0f) 1 else -1
        return 0
    }

    /**
     * 翻页后的页码（取模循环，不是夹紧）。
     *
     * @param from 当前页
     * @param step 步进：`1` 下一页、`-1` 上一页
     * @param pageCount 总页数
     */
    fun wrapIndex(from: Int, step: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return ((from + step) % pageCount + pageCount) % pageCount
    }
}
