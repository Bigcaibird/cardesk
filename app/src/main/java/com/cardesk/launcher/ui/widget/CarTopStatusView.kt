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
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.Car3dPainter
import com.cardesk.launcher.core.car3d.CarPose
import com.cardesk.launcher.core.car3d.CarPoseResolver

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

    /**
     * 用哪个视角的烘焙图。
     *
     * 默认 [Car3dAssets.VIEW_TOP]（俯视）；
     * 弹窗改成 [Car3dAssets.VIEW_DIALOG]（烘焙侧直出的小图，缺图回退 hero → top → 自绘）。
     * 视角缺图时 [Car3dAssets] 自动回退 `top`，再不行回退自绘。
     */
    var view: String = Car3dAssets.VIEW_TOP

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 写实车模状态机（弹窗是大图槽位，门 + 窗同时开时用 `all`，见 [CarPoseResolver]）。 */
    private val car3d = Car3dPainter.Scene(this)

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

        // 第163轮：先试写实烘焙车模；画得出来就整张图接管（含门/窗/尾门状态），
        // 不再叠加 2D 面板层。画不出来（资产缺失 / 开关关闭）走原来的自绘路径。
        val dir = CarModels.assetDir(carModel)
        // 第179轮：纯俯视资产（只声明 cardtop）的车型，这里也用 cardtop；
        // 三视角齐全的老资产仍按调用方给的 view。
        val effView = if (Car3dAssets.topDownOnly(context, dir)) Car3dAssets.VIEW_CARDTOP else view
        if (dir != null) {
            car3d.setPose(
                context,
                dir,
                com.cardesk.launcher.core.car3d.Car3dDebugPose.pose
                    ?: CarPose.of(doorsClosed, windowsOpen, hoodClosed, trunkClosed),
                rich = w >= CarPoseResolver.RICH_MIN_WIDTH_PX,
                view = effView,
            )
            // 俯视：沿用 120×240 设计外框（与 2D 车模/命中区完全对齐）；
            // 其它视角（dialog/hero）：整块区域等比居中，避免槽位比例与资产不一致时压扁。
            val top = effView == Car3dAssets.VIEW_TOP
            val rect = if (top) car3dRect(context, dir, frame, w, h) else RectF(0f, 0f, w, h)
            if (car3d.paint(canvas, context, effView, rect, fit = !top)) return
        }

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

    /**
     * 车模要占的矩形。
     *
     * - 俯视图：用 [CarTopPainter.Frame] 映射出的 120×240 设计外框（与桌面车况卡逐像素重合）；
     * - 3/4 大图（hero）：按 [Car3dAssets.Model.views] 里的槽位宽高比**等比放进**本 View，
     *   居中留白 —— 直接拉满会把车压扁，3/4 视角一旦变形（轮距/车顶线）非常显眼。
     */
    private fun car3dRect(
        context: Context,
        dir: String,
        frame: CarTopPainter.Frame,
        w: Float,
        h: Float,
    ): RectF {
        if (view == Car3dAssets.VIEW_TOP) {
            return RectF(
                frame.px(0f),
                frame.py(0f),
                frame.px(CarModels.DESIGN_W),
                frame.py(CarModels.DESIGN_H),
            )
        }
        // 槽位比例：先看请求视角，再按回退链（dialog → hero → top）找，
        // 都找不到才用 3/4 大图的默认比例 —— 否则新视角（dialog）在 model.json
        // 声明之前会被错误比例压扁/拉长。
        // 第179轮：按**有效视角**的比例算 —— top-only 新资产会落到 cardtop，
        // 否则弹窗里 356×264 的俯视图会被 3/4 的默认比例压扁。
        val effective = runCatching {
            Car3dAssets.usedView(context, dir, view)
        }.getOrNull() ?: view
        val spec = Car3dAssets.slotOf(context, dir, effective)
        val aspectW = (spec?.first ?: 344).toFloat()
        val aspectH = (spec?.second ?: 224).toFloat()
        val s = minOf(w / aspectW, h / aspectH)
        val rw = aspectW * s
        val rh = aspectH * s
        return RectF((w - rw) / 2f, (h - rh) / 2f, (w + rw) / 2f, (h + rh) / 2f)
    }

    private companion object {
        /** 前四扇门（与 `CarStatus.items` 的顺序一致：0..3 = 门）。 */
        const val DOOR_COUNT = 4
    }
}
