package com.cardesk.launcher.ui.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 横向**换行**容器（第188轮新增）。
 *
 * ## 为什么不用横向 LinearLayout
 *
 * 横向 LinearLayout 放不下时**不会换行**，而是把子项依次压扁到「还剩多少宽度就拿多少」——
 * 最后一个子项甚至被压成 0 宽。子项里的 TextView 会按这个压出来的宽度**折行**，
 * 几个字竖着叠在一起、又溢出胶囊边界，和旁边的胶囊糊成一团。
 *
 * 「车门未关」弹窗的胶囊行就踩了这个坑（实测，第188轮）：
 *
 * | 未关处数 | 第 1 个胶囊 | 最后一个胶囊 | 现象 |
 * | --- | --- | --- | --- |
 * | 4 | 150px | 150px | 正常 |
 * | 5 | 150px | **16px** | 文字挤成竖条、溢出胶囊 |
 * | 6 | 150px | **0px** | 最后一个胶囊彻底消失 |
 *
 * ## 这里的做法
 *
 * 贪心排版：按可用宽度从左到右放，**放不下就换到下一行**，子项永远保持自己的尺寸。
 * 控件高度 = 各行高度之和 + 行间距（由内容决定，父容器按需给高度）。
 */
class WrapRow(context: Context) : ViewGroup(context) {

    /** 同一行内两个子项之间的间距（设计 px）。 */
    var gapX: Int = 0

    /** 两行之间的间距（设计 px）。 */
    var gapY: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val avail = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE / 4
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        // 子项按「自然宽度」量（UNSPECIFIED/AT_MOST），**不能**用父容器的 EXACTLY 宽度 ——
        // 那会把每个胶囊都拉成整行宽（getChildMeasureSpec 的老规矩）。
        val childWidthSpec = MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams
            val childHeightSpec = if (lp != null && lp.height >= 0) {
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            }
            child.measure(childWidthSpec, childHeightSpec)
        }

        var contentH = 0
        var contentW = 0
        for (slot in pack(avail)) {
            val bottom = slot.top + slot.child.measuredHeight
            if (bottom > contentH) contentH = bottom
            val right = slot.left + slot.child.measuredWidth
            if (right > contentW) contentW = right
        }
        val measuredW = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(contentW + paddingLeft + paddingRight, widthSize)
            else -> contentW + paddingLeft + paddingRight
        }
        setMeasuredDimension(
            measuredW,
            resolveSize(contentH + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        for (slot in pack(avail)) {
            slot.child.layout(
                slot.left,
                slot.top,
                slot.left + slot.child.measuredWidth,
                slot.top + slot.child.measuredHeight,
            )
        }
    }

    /** 一个子项的落位。 */
    private class Slot(val child: View, val left: Int, val top: Int)

    /** 一行里的子项。 */
    private class Line(val children: List<View>, val width: Int, val height: Int)

    /**
     * 贪心排版：按可用宽度从左到右放，放不下就换行。
     *
     * 子项宽度用**测量出来的自然宽度**（不是父容器给的宽度），
     * 所以胶囊永远保持自己的尺寸，不会被压扁。
     *
     * 每一行**整体居中**：原版弹窗的胶囊行是 `gravity = CENTER`，
     * 换成换行容器后如果改成左对齐，单个胶囊就会从中间跑到左边 —— 那是可见的变化。
     */
    private fun pack(avail: Int): List<Slot> {
        val lines = ArrayList<Line>()
        var current = ArrayList<View>()
        var lineW = 0
        var lineH = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val w = child.measuredWidth
            val h = child.measuredHeight
            val need = w + gapX

            // 第一行放不下也照放（否则会死循环）；之后放不下就换行。
            if (current.isNotEmpty() && lineW + need > avail) {
                lines.add(Line(current, lineW, lineH))
                current = ArrayList()
                lineW = 0
                lineH = 0
            }
            current.add(child)
            lineW += need
            if (h > lineH) lineH = h
        }
        if (current.isNotEmpty()) lines.add(Line(current, lineW, lineH))

        val out = ArrayList<Slot>(childCount)
        var y = paddingTop
        for (line in lines) {
            // 整行居中（宽度扣掉最后一个子项后面的 gapX）。
            val contentW = (line.width - gapX).coerceAtLeast(0)
            var x = paddingLeft + ((avail - contentW) / 2).coerceAtLeast(0)
            for (child in line.children) {
                out.add(Slot(child, x, y))
                x += child.measuredWidth + gapX
            }
            y += line.height + gapY
        }
        return out
    }
}
