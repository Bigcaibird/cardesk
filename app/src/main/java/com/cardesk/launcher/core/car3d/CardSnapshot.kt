package com.cardesk.launcher.core.car3d

import kotlin.math.max
import kotlin.math.min

/**
 * 车况卡「快照版」（第167轮）的**胶囊/引线布局**——纯几何，不碰 Canvas，便于单测。
 *
 * ## 为什么单独一层
 *
 * 胶囊位置来自资产 `model.json` 的 `anchors.<view>.<item>`（归一化 0..1，asset-08 用相机投影算）。
 * 没有锚点时不能空白：按「沿车模框左右边缘均匀排布」兜底。两条路径都在这一个函数里，
 * 页面只负责把 [Pill] 画出来——这样「胶囊不压字、不出卡、不重叠」能被测试锁住。
 *
 * ## 排布规则（与设计规范 §2.4 一致）
 *
 * - 胶囊一律**向车外**偏移：锚点 x<0.5 放左侧、否则右侧；引线从胶囊靠车一侧连到锚点。
 * - 同一侧按锚点 y 排序，最小间距 [STACK_STEP]，整体夹在 [bounds] 里，不压标题/续航/底部统计行。
 * - 警告胶囊（门/窗/尾门）优先占位；胎压胶囊始终显示，排在后面。
 * - 超过 [MAX_PILLS] 个时只画 [MAX_PILLS]-1 个，剩下的并成一个 `+N` 胶囊。
 */
object CardSnapshot {

    /** 胶囊高度（设计像素）。 */
    const val PILL_H = 24f

    /** 胶囊圆角。 */
    const val PILL_RADIUS = 12f

    /** 胶囊左右内边距。 */
    const val PILL_PAD_X = 8f

    /** 同一侧相邻胶囊的最小中心距。 */
    const val STACK_STEP = 26f

    /** 胶囊靠车一侧到锚点的**最小**间距（设计像素，第168轮：胶囊贴车体）。 */
    const val GAP_MIN = 26f

    /** 胶囊靠车一侧到锚点的**最大**间距。 */
    const val GAP_MAX = 36f

    /** 引线最大长度（横向 gap + 纵向错开的斜线总长）。 */
    const val LEADER_MAX = 40f

    /** 目标间距：取 26..36 的中间值，再按纵向错开量收窄，保证引线 ≤ [LEADER_MAX]。 */
    private const val GAP_TARGET = 30f

    /** 异常胶囊（门/窗/尾门）最多显示几个，其余并成 `+N`。 */
    const val MAX_PILLS = 4

    /** 优先级 ≥ 该值的胶囊**不受** [MAX_PILLS] 限制（胎压四轮始终全显示）。 */
    const val ALWAYS_PRIORITY = 10

    /** 归一化锚点（相对车模图，0..1）。 */
    data class NormPoint(val x: Float, val y: Float)

    /** 车模实际绘制矩形（设计像素）。 */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * 待摆放的一条胶囊。
     *
     * @param text 文案（门/窗/尾门用标签，胎压用数值）
     * @param warn 是否橙色警告样式（异常项）
     * @param anchorKey 锚点键：`lf/rf/lb/rb/trunk` 或 `tires.fl/...`；`null` = 没锚点走兜底排布
     * @param unit 胎压单位（`bar`），非空时按「数值 + 小号单位」画
     */
    data class Item(
        val text: String,
        val warn: Boolean,
        val anchorKey: String? = null,
        val unit: String? = null,
        val priority: Int = 0,
    )

    /** 摆好的胶囊（设计像素，相对卡片左上角由调用方加偏移）。 */
    data class Pill(
        val text: String,
        val warn: Boolean,
        val unit: String?,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** 引线终点（锚点）与起点（胶囊靠车一侧中点）。 */
        val leadX: Float,
        val leadY: Float,
        val leadFromX: Float,
        val leadFromY: Float,
        val leader: Boolean,
    ) {
        val width: Float get() = right - left
        val centerY: Float get() = (top + bottom) / 2f
    }

    /**
     * 计算全部胶囊位置。
     *
     * @param items 顺序无关（[Item.priority] 小的先占位，警告项调用方给 0）
     * @param anchors 归一化锚点；`null` = 资产还没给锚点 → 沿车模框边缘均匀排布
     * @param image 车模**实际绘制**矩形（`fit` 之后的那块，不是外框）
     * @param bounds 胶囊允许活动区域（卡片内容区，已避开标题/续航/底部统计）
     * @param measure 文案宽度测量（调用方传 `Paint::measureText`）
     */
    fun layout(
        items: List<Item>,
        anchors: Map<String, NormPoint>?,
        image: Box,
        bounds: Box,
        measure: (String) -> Float,
        maxWarn: Int = MAX_PILLS,
    ): List<Pill> {
        if (items.isEmpty()) return emptyList()

        // 异常胶囊最多 4 个（其余并成 +N）；胎压这类「常驻」胶囊永远全显示。
        val warnings = items.filter { it.priority < ALWAYS_PRIORITY }.sortedBy { it.priority }
        val always = items.filter { it.priority >= ALWAYS_PRIORITY }
        val shown = if (warnings.size <= maxWarn) warnings else warnings.take((maxWarn - 1).coerceAtLeast(0))
        val hidden = warnings.size - shown.size
        val placed = shown + always

        // 没锚点时：沿车模框左右两列均匀散开（左列从上到下、右列从上到下）。
        val fallback = fallbackAnchors(placed, image)

        data class Draft(val item: Item, val anchor: NormPoint, val width: Float, val leftSide: Boolean)

        val drafts = placed.mapIndexed { idx, item ->
            val a = anchors?.get(item.anchorKey.orEmpty()) ?: fallback[idx]
            val w = measure(item.text) + if (item.unit != null) measure(item.unit) * 0.55f + 6f else 0f
            Draft(item, a, w + PILL_PAD_X * 2f, a.x < 0.5f)
        }

        val pills = ArrayList<Pill>(drafts.size + 1)
        for (side in listOf(true, false)) {
            val group = drafts.filter { it.leftSide == side }
                .sortedBy { image.top + it.anchor.y * image.height }
            if (group.isEmpty()) continue

            // 先按锚点 y 放到同一侧，再自上而下做最小间距约束，最后整体夹进 bounds。
            val tops = group.map { image.top + it.anchor.y * image.height - PILL_H / 2f }.toMutableList()
            val low = bounds.top
            val high = max(bounds.top, bounds.bottom - PILL_H)
            for (i in 1 until tops.size) {
                if (tops[i] < tops[i - 1] + STACK_STEP) tops[i] = tops[i - 1] + STACK_STEP
            }
            // 超出下边界 → 整组上移
            val overflow = tops.last() + PILL_H - bounds.bottom
            if (overflow > 0f) {
                for (i in tops.indices) tops[i] -= overflow
            }
            tops[0] = tops[0].coerceIn(low, high)
            for (i in 1 until tops.size) {
                tops[i] = tops[i].coerceIn(tops[i - 1] + STACK_STEP, high)
            }

            group.forEachIndexed { i, d ->
                val anchorX = image.left + d.anchor.x * image.width
                val anchorY = image.top + d.anchor.y * image.height
                val top = tops[i]
                val w = d.width
                // 胶囊**贴车体**：靠车一侧距锚点 26~36px；纵向错开后按引线 ≤40px 收窄。
                val dy = (top + PILL_H / 2f) - anchorY
                val gap = if (dy >= LEADER_MAX) {
                    GAP_MIN
                } else {
                    val room = kotlin.math.sqrt((LEADER_MAX * LEADER_MAX - dy * dy).coerceAtLeast(0f))
                    room.coerceIn(GAP_MIN, GAP_TARGET.coerceAtLeast(GAP_MIN)).coerceAtMost(GAP_MAX)
                }
                val left = if (side) {
                    (anchorX - gap - w).coerceAtLeast(bounds.left)
                } else {
                    (anchorX + gap).coerceAtMost(bounds.right - w)
                }
                val right = left + w
                // 胶囊被边界挡住时，引线从胶囊内侧边缘出发（不画到胶囊身上）。
                val fromX = if (side) right else left
                pills.add(
                    Pill(
                        text = d.item.text,
                        warn = d.item.warn,
                        unit = d.item.unit,
                        left = left,
                        top = top,
                        right = right,
                        bottom = top + PILL_H,
                        leadX = anchorX,
                        leadY = anchorY,
                        leadFromX = fromX,
                        leadFromY = top + PILL_H / 2f,
                        leader = true,
                    ),
                )
            }
        }

        if (hidden > 0) {
            val text = "+$hidden"
            val w = measure(text) + PILL_PAD_X * 2f
            // `+N` 贴在**最后一个异常胶囊下方**（同侧、同内缘），不甩到卡角。
            // 取**最靠下**的异常胶囊作为参照（左右两组都排完才知道谁最低）。
            val ref = pills.filter { it.warn }.maxByOrNull { it.bottom }
            val left = (ref?.left ?: (bounds.right - w)).coerceIn(bounds.left, bounds.right - w)
            var top = ((ref?.bottom ?: bounds.bottom) + 4f).coerceIn(bounds.top, bounds.bottom - PILL_H)
            // 不压住同列的其它胶囊（尤其是胎压），逐 4px 下移，实在放不下贴底。
            while (top + PILL_H <= bounds.bottom &&
                pills.any { p -> p.left < left + w && p.right > left && p.top < top + PILL_H && p.bottom > top }
            ) {
                top += 4f
            }
            if (top + PILL_H > bounds.bottom) top = bounds.bottom - PILL_H
            pills.add(
                Pill(
                    text = text,
                    warn = false,
                    unit = null,
                    left = left,
                    top = top,
                    right = left + w,
                    bottom = top + PILL_H,
                    leadX = ref?.leadX ?: image.right,
                    leadY = ref?.leadY ?: image.bottom,
                    leadFromX = left,
                    leadFromY = top + PILL_H / 2f,
                    leader = false,
                ),
            )
        }
        return pills
    }


    /** 状态文字里一段的配色类别。 */
    enum class StatusKind { NONE, DOOR, WINDOW, GENERIC }

    /** 状态文字的一段：文本 + 配色类别。多个 ITEM 段之间由调用方画「、」。 */
    data class StatusPart(val text: String, val kind: StatusKind)

    /**
     * 车况卡状态文字（第174轮）：必须把车窗也算进来。
     *
     * 规则（用户 m02705 第 1 条）：
     * - 0 处未关 → 一段「全车已关」；
     * - 1~2 处（车门/车窗/尾门都算一处）→ 逐个列出，车门橙、车窗黄；
     * - ≥3 处 → 合并一句「车门、窗未关」（只有车门/只有车窗时给更准确的措辞）。
     *
     * 纯函数，不碰 Android，便于单测覆盖 0/1/2/3 四种情形。
     */
    fun statusParts(
        openDoorLabels: List<String>,
        openWindowLabels: List<String>,
        trunkOpen: Boolean,
    ): List<StatusPart> {
        if (openDoorLabels.isEmpty() && openWindowLabels.isEmpty() && !trunkOpen) {
            return listOf(StatusPart("全车已关", StatusKind.NONE))
        }
        val items = ArrayList<StatusPart>()
        openDoorLabels.forEach { items.add(StatusPart(it, StatusKind.DOOR)) }
        openWindowLabels.forEach { items.add(StatusPart(it, StatusKind.WINDOW)) }
        if (trunkOpen) items.add(StatusPart("后备箱", StatusKind.DOOR))
        if (items.size <= 2) return items
        val hasDoor = openDoorLabels.isNotEmpty() || trunkOpen
        val hasWindow = openWindowLabels.isNotEmpty()
        val text = when {
            hasDoor && hasWindow -> "车门、窗未关"
            hasDoor -> "车门未关"
            else -> "车窗未关"
        }
        return listOf(StatusPart(text, StatusKind.GENERIC))
    }

    /** 无锚点兜底：按左右两侧交替、沿车模框纵向 18%..82% 均匀分布。 */
    private fun fallbackAnchors(items: List<Item>, image: Box): List<NormPoint> {
        val out = ArrayList<NormPoint>(items.size)
        val leftCount = (items.size + 1) / 2
        var li = 0
        var ri = 0
        items.forEachIndexed { idx, _ ->
            val left = idx % 2 == 0
            val n = if (left) leftCount else items.size / 2
            val k = if (left) li++ else ri++
            val t = if (n <= 1) 0.5f else 0.18f + 0.64f * k / (n - 1).toFloat()
            out.add(NormPoint(if (left) 0.18f else 0.82f, t))
        }
        return out
    }
}
