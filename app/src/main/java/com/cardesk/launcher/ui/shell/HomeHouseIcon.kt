package com.cardesk.launcher.ui.shell

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * 「主页」房子图标 —— **全局同一形状**。
 *
 * ## 为什么抽出来
 *
 * 桌面 Dock 的 home 键以前画的是「圆环 + 圆点」，
 * 那个形状**看不出是主页**（第七十七轮改成房子）。
 * 而导航槽栏里的「主页」槽位早就是房子了 ——
 * 同一个「主页」在两处画成两个形状，下一轮就会继续漂。
 * 所以把房子的形状收在这里，两边共用。
 *
 * ## 形状
 *
 * ```
 *        /\
 *       /  \
 *      /    \
 *     /______\
 *     |      |
 *     |______|
 * ```
 *
 * - **屋顶**是一个**开口的尖角**（左斜 + 右斜两笔，**不封底**）；
 * - **房身**是「左墙 → 地面 → 右墙」（**上边开口**），
 *   墙头正好落在屋顶那条线上，看起来就是一个完整的房子。
 *
 * 两段路径都**不闭合**，所以屋顶底下不会多出一条横线。
 */
internal object HomeHouseIcon {

    /**
     * 房身宽度 ÷ 屋顶宽度。
     *
     * 屋檐要露出来一点（房身比屋顶窄），
 * 但只露一点点 —— 露太多就成了「帐篷」，不像房子。
     */
    private const val WALL_RATIO = 0.78f

    /**
     * 画一个房子。
     *
     * @param unit 房子的**半宽**（屋顶外沿到中轴的距离）；高度与它同量级。
     * @param paint 描边画笔（[Paint.Style.STROKE]）；颜色与线宽由调用方决定。
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, unit: Float, paint: Paint) {
        val wall = unit * WALL_RATIO
        val p = Path()
        // —— 屋顶：左斜 → 尖角 → 右斜（不封底）——
        p.moveTo(cx - unit, cy)
        p.lineTo(cx, cy - unit)
        p.lineTo(cx + unit, cy)
        // —— 房身：左墙 → 地面 → 右墙（上边开口，屋顶那条线就是墙头）——
        p.moveTo(cx - wall, cy)
        p.lineTo(cx - wall, cy + unit)
        p.lineTo(cx + wall, cy + unit)
        p.lineTo(cx + wall, cy)
        canvas.drawPath(p, paint)
    }
}
