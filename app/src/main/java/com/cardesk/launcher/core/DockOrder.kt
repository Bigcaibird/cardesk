package com.cardesk.launcher.core

import kotlin.math.abs

/**
 * Dock 栏按钮与控件的自定义顺序模型（纯逻辑，可 JVM 单测）。
 *
 * Dock 共包含 17 个独立按钮/控件（返回 + 导航键 4 枚 + 媒体胶囊 + 空调电源 + 主驾温区 +
 * 风量 + 副驾温区 + A/C + AUTO + 内外循环 + 后除霜 + 前除霜 + 快捷应用 2 枚）。
 *
 * 1920 设计宽下：17 个控件宽度总和为 1750px，左右各留 28px，16 处缝隙均分 114px
 * （每处约 7.1px）；1872 宽全局 Dock 下仍无需缩小控件，任意拖动调换顺序后严格贴齐左右边界、
 * 零重叠、零溢出。
 */
object DockOrder {

    const val KEY = "dock_item_order"

    const val ITEM_BACK = "back"
    const val ITEM_HOME = "home"
    const val ITEM_APPS = "apps"
    const val ITEM_SPLIT = "split"
    const val ITEM_360 = "360"
    const val ITEM_MEDIA = "media"
    const val ITEM_POWER = "power"
    const val ITEM_TEMP_DRIVER = "temp_driver"
    const val ITEM_FAN = "fan"
    const val ITEM_TEMP_PASS = "temp_pass"
    const val ITEM_AC = "ac"
    const val ITEM_AUTO = "auto"
    const val ITEM_CIRC = "circ"
    const val ITEM_DEFROST_R = "defrostR"
    const val ITEM_DEFROST_F = "defrostF"
    const val ITEM_QUICK0 = "quick0"
    const val ITEM_QUICK1 = "quick1"

    /** 默认顺序（最左侧为返回键，其余与原画板 1:1 对齐）。 */
    val DEFAULT: List<String> = listOf(
        ITEM_BACK,
        ITEM_HOME,
        ITEM_APPS,
        ITEM_SPLIT,
        ITEM_360,
        ITEM_MEDIA,
        ITEM_POWER,
        ITEM_TEMP_DRIVER,
        ITEM_FAN,
        ITEM_TEMP_PASS,
        ITEM_AC,
        ITEM_AUTO,
        ITEM_CIRC,
        ITEM_DEFROST_R,
        ITEM_DEFROST_F,
        ITEM_QUICK0,
        ITEM_QUICK1,
    )

    private val VALID_SET: Set<String> = DEFAULT.toSet()

    /** 设计坐标系基准宽与左右留白。 */
    const val DESIGN_W = 1920f
    const val BASE_PAD_X = 28f

    /** 每个控件的布局槽位结果。 */
    data class Slot(
        val id: String,
        val left: Float,
        val width: Float,
    ) {
        val right: Float get() = left + width
        val centerX: Float get() = left + width / 2f
    }

    /** 单个控件在 1920 设计坐标系下的标准宽度。 */
    fun widthOf(id: String): Float = when (id) {
        ITEM_MEDIA -> 354f
        ITEM_TEMP_DRIVER -> 205f
        ITEM_TEMP_PASS -> 207f
        ITEM_AC -> 56f
        ITEM_AUTO -> 64f
        else -> 72f
    }

    /** 规整顺序：去空、去重、过滤未知 ID，缺失项按默认顺序补齐，保证永远恰好 17 项。 */
    fun normalize(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>(DEFAULT.size)
        for (item in raw) {
            val trimmed = item.trim()
            if (trimmed in VALID_SET) seen.add(trimmed)
        }
        for (def in DEFAULT) {
            seen.add(def)
        }
        return seen.toList()
    }

    fun parse(csv: String): List<String> {
        if (csv.isBlank()) return DEFAULT
        val parts = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // 升级兼容：老版本已保存的 16 项自定义顺序中不含 back 时，把返回键补到最左侧
        val migrated = if (ITEM_BACK !in parts && parts.any { it in VALID_SET }) {
            listOf(ITEM_BACK) + parts
        } else {
            parts
        }
        return normalize(migrated)
    }

    fun toCsv(order: List<String>): String = normalize(order).joinToString(",")

    /** 将 [fromIndex] 位置的控件移动到 [toIndex]，返回规整后的新列表。 */
    fun move(order: List<String>, fromIndex: Int, toIndex: Int): List<String> {
        val list = normalize(order).toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) {
            return list
        }
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        return list
    }

    /**
     * 计算整条 Dock 在给定宽度 [totalWidth] 下所有 16 个控件的精确水平坐标。
     *
     * 控件本身尺寸保持不变（与 `DockBarView` 设计一致），屏宽略小于 1920 时优先等比压缩留白；
     * 若极端窄屏连控件总宽都放不下，则整体等比缩放避免溢出。
     */
    fun layout(order: List<String>, totalWidth: Float): List<Slot> {
        val items = normalize(order)
        if (items.isEmpty() || totalWidth <= 0f) return emptyList()
        val totalItemW = items.sumOf { widthOf(it).toDouble() }.toFloat()
        val pad = BASE_PAD_X * (totalWidth / DESIGN_W)
        val avail = (totalWidth - pad * 2f).coerceAtLeast(1f)
        val scale = if (totalItemW > avail) avail / totalItemW else 1f
        val scaledItemW = totalItemW * scale
        val gap = if (items.size > 1) {
            ((avail - scaledItemW) / (items.size - 1)).coerceAtLeast(0f)
        } else {
            0f
        }
        var x = pad
        return items.map { id ->
            val w = widthOf(id) * scale
            val slot = Slot(id, x, w)
            x += w + gap
            slot
        }
    }

    /**
     * 拖动过程中根据被拖控件当前中心 [dragCenterX] 计算无振荡的目标下标。
     *
     * 对所有候选下标 `k`，把 [dragId] 放到位置 `k` 后计算其中心 `cx(k)`：
     * 由于 `cx(0) < cx(1) < ... < cx(n-1)` 严格单调递增，取与 [dragCenterX] 距离最小的 `k`
     * 在数学上唯一且绝不来回抖动（即使 390px 媒体胶囊越过 72px 按键也完全平稳）。
     */
    fun targetIndexForCenter(
        order: List<String>,
        dragId: String,
        dragCenterX: Float,
        totalWidth: Float,
    ): Int {
        val norm = normalize(order)
        val from = norm.indexOf(dragId)
        if (from < 0) return -1
        var bestIdx = from
        var bestDist = Float.MAX_VALUE
        for (candidate in norm.indices) {
            val trial = move(norm, from, candidate)
            val slot = layout(trial, totalWidth)[candidate]
            val dist = abs(slot.centerX - dragCenterX)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = candidate
            }
        }
        return bestIdx
    }
}
