package com.cardesk.launcher.core.car3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.roundToInt

/**
 * 写实车模的**绘制器**：纯 `drawBitmap`，不吃 GPU、不开线程、不 new。
 *
 * ## 它解决什么问题
 *
 * 烘焙好的车模图是「某个状态的一张透明底图」（见 [CarPoseResolver] 的策展状态），
 * 运行时只需要做两件事：
 *
 * 1. 状态没变 → 把当前状态图贴到槽位矩形里（一次 `drawBitmap`）；
 * 2. 状态变了 → 播该部件的一组动画帧（`anim_<kind>_<i>`），播完即停。
 *
 * 所以这里**没有渲染循环**：动画期间每帧末尾 `postInvalidateOnAnimation()` 把下一帧
 * 叫出来，播完就不再叫 —— 空闲时零帧（车况卡 1Hz 重绘也不会让动画常驻）。
 * 自绘动画的既有先例见 `ui/widget/CabinView.kt`（onDraw 末尾 `postInvalidateOnAnimation`）。
 *
 * ## 槽位
 *
 * 调用方传一个 `RectF`（与现在 `CarTopPainter.Frame` 映射出来的车身外框**完全一致**），
 * 位图在解码时就缩放到这个矩形的像素尺寸，绘制时只做 1:1 贴图。
 *
 * ## 失败处理
 *
 * [paint] 返回 `false` = 这张画不出来（关掉开关 / 资产缺失 / 解码失败），
 * 调用方**必须**接着画原来的 `CarTopPainter`。这个判断是 per-frame 的，
 * 但 [Car3dAssets] 已经把失败记死，不会每帧重试 IO。
 */
object Car3dPainter {

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * 一个槽位（一张 `View` 或一块 Canvas 区域）的 car3d 状态机。
     *
     * `ScenePage` 的胎压卡/车况卡共用一个实例（同一台车、同一套动画、两个尺寸的位图）；
     * 「车门未关」弹窗里的 [com.cardesk.launcher.ui.widget.CarTopStatusView] 自己一个实例。
     *
     * 线程约束：只在主线程调用（`View.onDraw` / `bind`）。
     */
    class Scene(private val host: View) {

        /**
         * 最近一次绘制里「车模实心像素」的外接框（画布坐标）。
         *
         * 第181轮：胎压胶囊要「浮在四角但不贴车」，需要一个真实的车体边界；
         * 位图四周有大量透明留白（阴影/取景），用绘制矩形当车体边界会算不准。
         */
        var lastCarBounds: android.graphics.RectF? = null
            private set


        private var dir: String? = null
        private var rich = false
        private var target: CarPose? = null
        private var stateKey: String = CarPoseResolver.STATE_CLOSED
        private var unknown = false

        private var animKind: CarAnimKind? = null
        private var animReverse = false
        private var animFrames = 0
        private var view = Car3dAssets.VIEW_TOP

        private var animDurationMs = 0
        private var animStart = 0L

        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            style = Paint.Style.FILL
        }

        /** 是否已经有过一次姿态（没有 = 还没数据，`paint` 会直接失败）。 */
        val hasPose: Boolean get() = target != null

        /**
         * 更新姿态。**幂等**：每秒 1Hz 的刷新重复喂同一个姿态什么都不做。
         *
         * @param dir 车型资产目录（`CarModels.assetDir`）；`null` = 该车型没有资产
         * @param pose 目标姿态
         * @param rich 是否大图槽位（弹窗 = true，卡片 = false）
         */
        fun setPose(
            context: Context,
            dir: String?,
            pose: CarPose,
            rich: Boolean,
            view: String = Car3dAssets.VIEW_TOP,
        ) {
            if (!Car3dSettings.enabled || dir == null) {
                this.dir = dir
                this.view = view
                target = null
                animKind = null
                return
            }
            if (this.dir == dir && this.rich == rich && this.view == view && target?.sameAs(pose) == true) {
                return
            }
            val previous = target
            this.dir = dir
            this.rich = rich
            this.view = view
            target = pose
            val res = CarPoseResolver.resolve(previous, pose, rich)
            stateKey = res.stateKey
            unknown = res.unknown
            val anim = res.anim
            // 首次出图不播动画（直接给目标状态）；没上屏（Robolectric 离屏 draw）也不播，
            // 否则 preview 截图会拍到动画第一帧、和真实状态对不上。
            if (anim == null || previous == null || !host.isAttachedToWindow) {
                animKind = null
                return
            }
            // 动画帧必须与状态图同机位（首帧 = closed），否则播完会跳一下 —— 自检不过就不播。
            if (!animUsable(context, dir, anim.kind.key)) {
                animKind = null
                return
            }
            startAnim(context, dir, anim, SystemClock.uptimeMillis())
        }

        /** 该视角/该动画的自检（按 model.json 的 slot 尺寸的半分辨率做，结果永久缓存）。 */
        private fun animUsable(context: Context, dir: String, kind: String): Boolean {
            val spec = Car3dAssets.model(context, dir)?.views?.get(view)
            val w = spec?.slotW ?: 280
            val h = spec?.slotH ?: 500
            return Car3dAssets.canAnimate(context, dir, view, kind, w, h)
        }

        /**
         * 把车模画到 [rect]。
         *
         * @return `true` = 已经画了（调用方不要再画 2D 车模）；`false` = 画不出来，回退 2D
         */
        fun paint(
            canvas: Canvas,
            context: Context,
            view: String = Car3dAssets.VIEW_TOP,
            rect: RectF,
            fit: Boolean = false,
        ): Boolean {
            if (!Car3dSettings.enabled) return false
            val d = dir ?: return false
            val w = rect.width().roundToInt()
            val h = rect.height().roundToInt()
            if (w <= 0 || h <= 0) return false

            val now = SystemClock.uptimeMillis()
            val kind = animKind
            // 车漆：绘制时才读（设置页切换后下一帧就换图，不需要重建状态机）。
            val color = Car3dSettings.paint
            var bmp: android.graphics.Bitmap? = null
            if (kind != null && animDurationMs > 0 && animFrames > 0) {
                val elapsed = now - animStart
                if (elapsed >= animDurationMs) {
                    animKind = null
                    bmp = Car3dAssets.stateBitmap(context, d, view, stateKey, w, h, color)
                } else {
                    val i = (elapsed * animFrames / animDurationMs).toInt().coerceIn(0, animFrames - 1)
                    val frame = if (animReverse) animFrames - 1 - i else i
                    bmp = Car3dAssets.animBitmap(context, d, view, kind.key, frame, w, h, color)
                    if (bmp == null) {
                        bmp = Car3dAssets.stateBitmap(context, d, view, stateKey, w, h, color)
                    }
                    host.postInvalidateOnAnimation()
                }
            } else {
                bmp = Car3dAssets.stateBitmap(context, d, view, stateKey, w, h, color)
            }
            if (bmp == null) return false
            // fit=true：把 rect 当**区域**，按位图自身宽高比等比放进去并居中。
            // 卡片/弹窗的槽位比例与资产比例不一定一致（例如 hero 缺图回退 top 时），
            // 直接拉伸会把人眼看扁 —— 这样任何回退都不会变形。
            val dst = if (fit) {
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                if (bw <= 0f || bh <= 0f) rect else {
                    val s = minOf(rect.width() / bw, rect.height() / bh)
                    val dw = bw * s
                    val dh = bh * s
                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
                }
            } else {
                rect
            }
            canvas.drawBitmap(bmp, null, dst, filterPaint)
            lastCarBounds = opaqueBounds(bmp, dst)
            if (unknown && animKind == null) {
                // 「读不到」角标：一个很小的暗点，和车况卡图例的「--」对应。
                canvas.drawCircle(dst.right - 9f, dst.top + 9f, 4.5f, badgePaint)
            }
            return true
        }

        /** 扫 alpha≥200 的实心像素，映射到画布坐标；全透明返回 null。 */
        private fun opaqueBounds(bmp: android.graphics.Bitmap, dst: android.graphics.RectF): android.graphics.RectF? {
            val bw = bmp.width
            val bh = bmp.height
            if (bw <= 0 || bh <= 0) return null
            var minX = bw
            var minY = bh
            var maxX = -1
            var maxY = -1
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    if (((bmp.getPixel(x, y) ushr 24) and 0xFF) >= 200) {
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) return null
            val sx = dst.width() / bw
            val sy = dst.height() / bh
            return android.graphics.RectF(
                dst.left + minX * sx,
                dst.top + minY * sy,
                dst.left + (maxX + 1) * sx,
                dst.top + (maxY + 1) * sy,
            )
        }

        /** 清掉本地状态（测试用；资产缓存清理由 [Car3dAssets.reset] 负责）。 */
        fun reset() {
            dir = null
            rich = false
            target = null
            stateKey = CarPoseResolver.STATE_CLOSED
            unknown = false
            animKind = null
            animFrames = 0
            animDurationMs = 0
            animStart = 0L
        }

        /** 播动画；同一种动画反向时从当前帧接上（不跳回第一帧）。 */
        private fun startAnim(context: Context, dir: String, anim: CarAnim, now: Long) {
            val model = Car3dAssets.model(context, dir) ?: return
            val spec = model.anims[anim.kind.key] ?: return
            val prevKind = animKind
            val prevFrames = animFrames
            val prevDuration = animDurationMs
            val prevReverse = animReverse
            val prevStart = animStart

            var startIndex = 0
            if (prevKind == anim.kind && prevFrames > 0 && prevDuration > 0) {
                val e = (((now - prevStart) * prevFrames) / prevDuration).toInt().coerceIn(0, prevFrames - 1)
                val shown = if (prevReverse) prevFrames - 1 - e else e
                startIndex = if (anim.reverse) spec.frames - 1 - shown else shown
                startIndex = startIndex.coerceIn(0, spec.frames - 1)
            }
            animKind = anim.kind
            animReverse = anim.reverse
            animFrames = spec.frames
            animDurationMs = spec.ms
            animStart = now - startIndex.toLong() * spec.ms / spec.frames
            host.postInvalidateOnAnimation()
        }
    }
}
