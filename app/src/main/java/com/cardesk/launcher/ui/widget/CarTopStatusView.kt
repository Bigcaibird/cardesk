package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.CarStatus
import com.cardesk.launcher.core.CarStatusGeometryFactory
import com.cardesk.launcher.core.CarTopGeometryFactory
import com.cardesk.launcher.core.CarTopPainter
import com.cardesk.launcher.core.DoorSwing
import com.cardesk.launcher.core.Skin

/**
 * 车况面板视图（第136轮新增）：**车模 + 每个门 / 盖的开闭高亮**。
 *
 * ## 它是什么
 *
 * 桌面右侧那张「车况卡」里的内容（俯视车模 + 门/窗/盖状态）单独抽成一个 View，
 * 供「车门未关」弹窗复用 —— 这样弹窗里画出来的车和桌面卡片上的是**同一台车、同一套配色**。
 *
 * 车模本体委托给 [CarTopPainter]（第136轮从 `ScenePage` 提取的公共组件），
 * 面板叠加层的画法与车况卡**逐字一致**（同一套几何 + 同一套摆门角度）。
 *
 * ## 三种状态的画法（与车况卡一致）
 *
 * | 状态 | 画法 |
 * | --- | --- |
 * | 开 | 橙色实心高亮；门还会**绕铰链摆出去** 45° |
 * | 关 | 淡描边（只勾轮廓，不抢注意力） |
 * | 读不到 | 中性灰描边 —— **不猜**，读不到 ≠ 关着 |
 */
class CarTopStatusView(context: Context) : View(context) {

    /** 车型（决定车模几何）。 */
    var carModel: String = CarModels.MODEL_03

    /** 四门状态：`true` = 关，`false` = 开，`null` = 读不到。 */
    var doorsClosed: List<Boolean?>? = null

    /** 四窗状态：`true` = 开，`false` = 关。 */
    var windowsOpen: BooleanArray? = null

    /** 引擎盖：`true` = 关，`false` = 开，`null` = 读不到。 */
    var hoodClosed: Boolean? = null

    /** 后备箱：`true` = 关，`false` = 开，`null` = 读不到。 */
    var trunkClosed: Boolean? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val colors = if (Skin.isNight) {
            com.cardesk.launcher.core.CarColors.night
        } else {
            com.cardesk.launcher.core.CarColors.day
        }
        val geo = CarTopGeometryFactory.of(carModel)
        val frame = CarTopPainter.frame(w / 2f, h / 2f, w, h)
        CarTopPainter.draw(canvas, frame, geo, colors)

        // —— 面板叠加（顺序与车况卡一一对应）——
        val items = CarStatus.items(doorsClosed, windowsOpen, hoodClosed, trunkClosed)
        val rects = CarStatusGeometryFactory.of(geo).rects()
        for (k in items.indices) {
            val g = rects[k]
            val pr = RectF(frame.px(g.left), frame.py(g.top), frame.px(g.right), frame.py(g.bottom))
            when (items[k].open) {
                true -> {
                    if (k < DOOR_COUNT) {
                        // 门开着：先在原位画一个暗色「开口」，再把门板绕铰链摆出去。
                        fill.color = colors.panelGapFill
                        canvas.drawRoundRect(pr, 2f, 2f, fill)
                        stroke.color = colors.panelGapLine
                        stroke.strokeWidth = 1.2f
                        canvas.drawRoundRect(pr, 2f, 2f, stroke)

                        val pose = DoorSwing.pose(k, g)
                        canvas.save()
                        canvas.rotate(pose.angleDeg, frame.px(pose.hingeX), frame.py(pose.hingeY))
                        fill.color = colors.panelOpenFill
                        canvas.drawRoundRect(pr, 2f, 2f, fill)
                        stroke.color = colors.panelOpenLine
                        stroke.strokeWidth = 1.6f
                        canvas.drawRoundRect(pr, 2f, 2f, stroke)
                        canvas.restore()
                    } else {
                        fill.color = colors.panelOpenFill
                        canvas.drawRoundRect(pr, 2f, 2f, fill)
                        stroke.color = colors.panelOpenLine
                        stroke.strokeWidth = 1.6f
                        canvas.drawRoundRect(pr, 2f, 2f, stroke)
                    }
                }
                false -> {
                    fill.color = colors.panelClosedFill
                    canvas.drawRoundRect(pr, 2f, 2f, fill)
                    stroke.color = colors.panelClosedLine
                    stroke.strokeWidth = 1.2f
                    canvas.drawRoundRect(pr, 2f, 2f, stroke)
                }
                null -> {
                    stroke.color = colors.panelUnknownLine
                    stroke.strokeWidth = 1.2f
                    canvas.drawRoundRect(pr, 2f, 2f, stroke)
                }
            }
        }
    }

    private companion object {
        /** 前四扇门（与 `CarStatus.items` 的顺序一致：0..3 = 门）。 */
        const val DOOR_COUNT = 4
    }
}
