package com.cardesk.launcher.core

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * 俯视车模自绘（第136轮从 `ui/pages/ScenePage.kt` **提取**到这里）。
 *
 * ## 为什么要提取
 *
 * 原来这坨绘制只住在 `ScenePage` 里（胎压卡、车况卡各画一次车模）。
 * 第136轮要加「车门未关」弹窗，弹窗里也要画同一台车模
 * —— 否则弹窗画出来的东西和桌面卡片**长得不一样**，用户会觉得是两个 App。
 *
 * 提取成公共组件后：
 * - 桌面车况卡（`ScenePage.drawCarStatusCard`）继续用它；
 * - 车门未关弹窗（`ui/widget/CarTopStatusView`）也用它；
 * - **车模只有一份实现**，改配色 / 改车型只改一处。
 *
 * ## 纯搬运，不改画法
 *
 * 本文件是**原样搬运**：绘制顺序、几何、颜色口径与提取前逐字一致，
 * 差别只有两处：
 * 1. `carColors` → 参数 `colors`；
 * 2. `carModel` → 参数 `geo`（几何由调用方取好）。
 *
 * 坐标仍然是「设计空间（120×240）→ 目标框」的等比缩放，
 * 由 [Frame] 负责，所以车况卡叠加面板时用的变换与车模**完全一致**（否则面板会错位）。
 */
object CarTopPainter {

    /**
     * 俯视车模的**画布变换**（设计空间 120×240 → 目标框）。
     *
     * 单独抽出来是因为车况卡要在车模上叠加「每个面板的开闭状态」，
     * 叠加层必须用**和车模完全一致**的变换，否则面板会整体错位。
     */
    data class Frame(
        val centerX: Float,
        val centerY: Float,
        val scale: Float,
        val offX: Float,
        val offY: Float,
    ) {
        /** 设计空间 x → 画布 x。 */
        fun px(x: Float): Float = offX + x * scale

        /** 设计空间 y → 画布 y。 */
        fun py(y: Float): Float = offY + y * scale
    }

    /** 目标框 → 车模变换（等比缩放，取宽高两个比例里小的那个）。 */
    fun frame(cx: Float, cy: Float, w: Float, h: Float): Frame {
        val scale = minOf(
            w / CarModels.DESIGN_W,
            h / CarModels.DESIGN_H,
        )
        return Frame(
            centerX = cx,
            centerY = cy,
            scale = scale,
            offX = cx - CarModels.DESIGN_W * scale / 2f,
            offY = cy - CarModels.DESIGN_H * scale / 2f,
        )
    }

    fun draw(canvas: Canvas, frame: Frame, geo: CarTopGeometry, colors: CarColors) {
        val scale = frame.scale
        fun px(x: Float) = frame.px(x)
        fun py(y: Float) = frame.py(y)

        // —— 1. 轮子（垫在车身下面，只探出 6px） ——
        val tirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.tire
            style = Paint.Style.FILL
        }
        val tireEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.tireEdge
            style = Paint.Style.STROKE
            strokeWidth = 1f * scale
        }
        for (wheel in geo.wheels) {
            val r = RectF(px(wheel.left), py(wheel.top), px(wheel.right), py(wheel.bottom))
            canvas.drawRoundRect(r, 5f * scale, 5f * scale, tirePaint)
            canvas.drawRoundRect(r, 5f * scale, 5f * scale, tireEdge)
        }

        // —— 2. 车身 ——
        //
        // 路径按分段构造
        // 鼻尖 ─贝塞尔─▶ 肩线 ─直线─▶ 最宽处 ─直线─▶ 后翼子板 ─贝塞尔─▶ 尾端（再镜像回车头）。
        // 侧腰是**直线**，和车头贝塞尔之间留一个硬肩线 —— 这才是「像车」的关键。
        val bodyPath = android.graphics.Path()
        val noseC = geo.noseCubic
        val tailC = geo.tailCubic
        val cx = CarTopGeometryFactory.CENTER_X
        fun mirrorX(x: Float) = 2f * cx - x
        bodyPath.moveTo(px(geo.nose.x), py(geo.nose.y))
        bodyPath.cubicTo(
            px(noseC.c1.x), py(noseC.c1.y),
            px(noseC.c2.x), py(noseC.c2.y),
            px(geo.shoulder.x), py(geo.shoulder.y),
        )
        bodyPath.lineTo(px(geo.midTop.x), py(geo.midTop.y))
        bodyPath.lineTo(px(geo.midBottom.x), py(geo.midBottom.y))
        bodyPath.cubicTo(
            px(tailC.c1.x), py(tailC.c1.y),
            px(tailC.c2.x), py(tailC.c2.y),
            px(geo.tail.x), py(geo.tail.y),
        )
        // 左半（关于中心线镜像）
        bodyPath.cubicTo(
            px(mirrorX(tailC.c2.x)), py(tailC.c2.y),
            px(mirrorX(tailC.c1.x)), py(tailC.c1.y),
            px(mirrorX(geo.midBottom.x)), py(geo.midBottom.y),
        )
        bodyPath.lineTo(px(mirrorX(geo.midTop.x)), py(geo.midTop.y))
        bodyPath.lineTo(px(mirrorX(geo.shoulder.x)), py(geo.shoulder.y))
        bodyPath.cubicTo(
            px(mirrorX(noseC.c2.x)), py(noseC.c2.y),
            px(mirrorX(noseC.c1.x)), py(noseC.c1.y),
            px(geo.nose.x), py(geo.nose.y),
        )
        bodyPath.close()
        val half = geo.maxHalf
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                px(CarTopGeometryFactory.CENTER_X - half),
                0f,
                px(CarModels.DESIGN_W / 2f + half),
                0f,
                intArrayOf(colors.bodyEdge, colors.bodyMid, colors.bodyEdge),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(bodyPath, bodyPaint)
        bodyPaint.shader = null
        bodyPaint.style = Paint.Style.STROKE
        bodyPaint.strokeWidth = 1.5f * scale
        bodyPaint.color = colors.bodyEdgeLine
        canvas.drawPath(bodyPath, bodyPaint)

        // —— 3. 玻璃（前风挡 / 车顶 / 后风挡） ——
        val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                0f,
                py(geo.noseY),
                0f,
                py(geo.tailY),
                intArrayOf(colors.glassTop, colors.glassBottom),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(glassPath(geo.windshield, true, ::px, ::py), glassPaint)
        canvas.drawPath(glassPath(geo.rearGlass, false, ::px, ::py), glassPaint)

        // 车顶：玻璃色半透叠在车身上（`setAlpha(128)`）
        // 让车漆的中线高光透上来，形成「全景天幕」而不是一块死黑。
        val roofPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.roof
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(px(geo.roof.left), py(geo.roof.top), px(geo.roof.right), py(geo.roof.bottom)),
            geo.roofRadius * scale,
            geo.roofRadius * scale,
            roofPaint,
        )
        val roofEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.roofEdge
            style = Paint.Style.STROKE
            strokeWidth = 1f * scale
        }
        canvas.drawRoundRect(
            RectF(px(geo.roof.left), py(geo.roof.top), px(geo.roof.right), py(geo.roof.bottom)),
            geo.roofRadius * scale,
            geo.roofRadius * scale,
            roofEdge,
        )

        // —— 4. 灯组 ——
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.headLight }
        for (l in geo.headLights) {
            canvas.drawRoundRect(
                RectF(px(l.left), py(l.top), px(l.right), py(l.bottom)),
                2f * scale, 2f * scale, headPaint,
            )
        }
        val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.tailLight }
        for (l in geo.tailLights) {
            canvas.drawRoundRect(
                RectF(px(l.left), py(l.top), px(l.right), py(l.bottom)),
                2f * scale, 2f * scale, tailPaint,
            )
        }
    }

    /**
     * 把一块玻璃四边形变成 `Path`。
     *
     * 前风挡**上沿是弧**（`cubicTo`），后风挡**下沿是弧**
     * 这里用 [bulge] 控制鼓出的高度。
     *
     * @param bulgeTop true = 上沿鼓（风挡），false = 下沿鼓（后风挡）
     */
    private fun glassPath(
        quad: GlassQuad,
        bulgeTop: Boolean,
        px: (Float) -> Float,
        py: (Float) -> Float,
    ): android.graphics.Path {
        val p = android.graphics.Path()
        p.moveTo(px(quad.topLeft.x), py(quad.topLeft.y))
        if (bulgeTop) {
            val span = quad.topRight.x - quad.topLeft.x
            p.cubicTo(
                px(quad.topLeft.x + span * 0.33f), py(quad.topLeft.y - quad.topBulge),
                px(quad.topLeft.x + span * 0.67f), py(quad.topRight.y - quad.topBulge),
                px(quad.topRight.x), py(quad.topRight.y),
            )
            p.lineTo(px(quad.bottomRight.x), py(quad.bottomRight.y))
            p.lineTo(px(quad.bottomLeft.x), py(quad.bottomLeft.y))
        } else {
            p.lineTo(px(quad.topRight.x), py(quad.topRight.y))
            p.lineTo(px(quad.bottomRight.x), py(quad.bottomRight.y))
            val span = quad.bottomRight.x - quad.bottomLeft.x
            p.cubicTo(
                px(quad.bottomRight.x - span * 0.33f), py(quad.bottomRight.y + quad.bottomBulge),
                px(quad.bottomRight.x - span * 0.67f), py(quad.bottomLeft.y + quad.bottomBulge),
                px(quad.bottomLeft.x), py(quad.bottomLeft.y),
            )
        }
        p.close()
        return p
    }
}
