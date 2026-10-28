package com.cardesk.launcher.core

import kotlin.math.abs

/**
 * 桌面翻页手势的纯逻辑判定。
 *
 * 第三十七轮把翻页手势收敛成三种：
 * 1. **单指横滑**（主手势，第三十七轮用户要求）——真车上单指左右滑最顺手；
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
