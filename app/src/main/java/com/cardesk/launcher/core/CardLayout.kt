package com.cardesk.launcher.core

/**
 * 桌面底部卡片的布局模型（第九十五轮 C2）。
 *
 * 从「只有顺序」升级为「顺序 + 尺寸档位 + 显隐」：
 * - [Slot.size] 决定这张卡的宽度档位（自适应 / 小 / 中 / 大）；
 * - [Slot.visible] 决定是否参与排布与绘制；
 * - 布局整体按 [encode] 落到偏好键 [KEY]（一行文本，便于诊断包排查）。
 *
 * 顺序拖拽仍由场景页处理：[move] 操作的是完整布局，隐藏卡不参与拖拽。
 */
object CardLayout {

    const val QUICK = "quick"
    const val MUSIC = "music"
    const val CLIMATE = "climate"
    const val TIRE = "tire"
    const val CAR = "car"

    /** 第九十五轮迭代 2 新增的可选卡片（默认不显示，从「卡片库」添加）。 */
    const val TRIP = "trip"
    const val STATUS = "status"

    /** 全部可布局的卡片，顺序即默认顺序。 */
    val ALL: List<String> = listOf(QUICK, MUSIC, CLIMATE, TIRE, CAR, TRIP, STATUS)

    /** 默认就显示的卡（其余从卡片库添加）。 */
    private val DEFAULT_VISIBLE = setOf(QUICK, MUSIC, CLIMATE, TIRE, CAR)

    /** 偏好键（第九十五轮起，替代旧的 `scene_bottom_card_order_v1`）。 */
    const val KEY = "scene_card_layout_v2"

    /** 卡片中文名（设置页、日志用）。 */
    fun title(id: String): String = when (id) {
        QUICK -> "快捷应用"
        MUSIC -> "音乐"
        CLIMATE -> "空调"
        TIRE -> "胎压"
        CAR -> "车况"
        TRIP -> "行程"
        STATUS -> "车门车窗"
        else -> id
    }

    /**
     * 尺寸档位。
     *
     * [AUTO] = 自适应：普通卡取自然宽度，车况卡吃掉行尾剩余宽度；
     * 其余档位按自然宽 × [scale] 缩放；车况卡选中/大档位时改为固定宽度。
     */
    enum class Size(val label: String, val scale: Float) {
        AUTO("自适应", 1f),
        SMALL("小", 0.82f),
        MEDIUM("中", 1f),
        LARGE("大", 1.25f),
    }

    data class Slot(val id: String, val size: Size = Size.AUTO, val visible: Boolean = true)

    /** 一张卡在默认布局里的样子（新增卡默认隐藏，等用户从卡片库添加）。 */
    fun defaultSlot(id: String): Slot = Slot(id, Size.AUTO, visible = id in DEFAULT_VISIBLE)

    /** 默认布局：与第九十四轮之前一致的 5 张卡；行程/车门车窗卡默认隐藏。 */
    val DEFAULT: List<Slot> = ALL.map { defaultSlot(it) }

    /** 编码成一行：`quick:AUTO:1,music:MEDIUM:1,...`。 */
    fun encode(layout: List<Slot>): String =
        layout.joinToString(",") { "${it.id}:${it.size.name}:${if (it.visible) 1 else 0}" }

    /** 解析；任何非法/重复/缺项都返回 null（调用方应回退默认布局）。 */
    fun decode(raw: String?): List<Slot>? {
        if (raw.isNullOrBlank()) return null
        val parsed = ArrayList<Slot>()
        for (part in raw.split(',')) {
            if (part.isBlank()) continue
            val fields = part.split(':')
            if (fields.size != 3) return null
            val id = fields[0]
            if (id !in ALL) return null
            val size = Size.entries.firstOrNull { it.name == fields[1] } ?: return null
            val visible = when (fields[2]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            if (parsed.any { it.id == id }) return null
            parsed.add(Slot(id, size, visible))
        }
        return if (parsed.isEmpty()) null else normalize(parsed)
    }

    /** 去重、补缺、丢弃未知 id；保持已有顺序。 */
    fun normalize(layout: List<Slot>): List<Slot> {
        val out = ArrayList<Slot>(ALL.size)
        for (slot in layout) {
            if (slot.id !in ALL) continue
            if (out.any { it.id == slot.id }) continue
            out.add(slot)
        }
        for (id in ALL) {
            if (out.none { it.id == id }) out.add(defaultSlot(id))
        }
        if (out.none { it.visible }) {
            // 至少留一张可见，否则桌面底部会整条消失。
            val idx = out.indexOfFirst { it.id == CAR }.takeIf { it >= 0 } ?: 0
            out[idx] = out[idx].copy(visible = true)
        }
        return out
    }

    /** 把 [from] 位置挪到 [to]（索引基于完整布局；越界不做修改）。 */
    fun move(layout: List<Slot>, from: Int, to: Int): List<Slot> {
        if (from == to) return layout
        if (from !in layout.indices || to !in layout.indices) return layout
        val out = layout.toMutableList()
        val item = out.removeAt(from)
        out.add(to, item)
        return out
    }

    fun withSize(layout: List<Slot>, id: String, size: Size): List<Slot> =
        layout.map { if (it.id == id) it.copy(size = size) else it }

    /** 显示/隐藏一张卡；不允许把最后一张可见卡也隐藏。 */
    fun withVisible(layout: List<Slot>, id: String, visible: Boolean): List<Slot> {
        if (!visible && layout.count { it.visible } <= 1 && layout.any { it.id == id && it.visible }) return layout
        return layout.map { if (it.id == id) it.copy(visible = visible) else it }
    }

    fun visible(layout: List<Slot>): List<Slot> = layout.filter { it.visible }

    /**
     * 计算一条横排里每张卡的宽度。
     *
     * @param rowWidth 可用宽度
     * @param gap 卡间距
     * @param naturalWidth 该卡的自然/固定宽度（不含档位缩放）
     * @param minWidth 最小宽度保护（车况卡）
     * @return 可见卡按顺序的 (id, width)；不会返回隐藏卡。
     */
    fun widths(
        layout: List<Slot>,
        rowWidth: Float,
        gap: Float,
        naturalWidth: (String) -> Float,
        minWidth: Float = 160f,
    ): List<Pair<String, Float>> {
        val slots = visible(layout)
        if (slots.isEmpty()) return emptyList()
        val gaps = gap * (slots.size - 1)
        val base = slots.map { slot ->
            if (slot.id == CAR && slot.size == Size.AUTO) 0f else naturalWidth(slot.id) * slot.size.scale
        }
        val leftover = rowWidth - base.sum() - gaps
        val widths = base.toMutableList()
        val carIndex = slots.indexOfFirst { it.id == CAR && it.size == Size.AUTO }
        if (carIndex >= 0) {
            // 车况卡自适应：吃掉行尾剩余宽度。卡太多/太宽时把其它「自适应」卡按比例收窄，
            // 保证整行不溢出（否则第 6、7 张卡会把车况卡挤到屏幕外）。
            if (leftover < minWidth) {
                val others = slots.indices.filter { it != carIndex && slots[it].size == Size.AUTO }
                val totalOthers = others.sumOf { base[it].toDouble() }.toFloat()
                val need = minWidth - leftover
                if (totalOthers > 0f) {
                    val keep = (1f - need / totalOthers).coerceIn(0.25f, 1f)
                    for (i in others) widths[i] = base[i] * keep
                }
                widths[carIndex] = minWidth
            } else {
                widths[carIndex] = leftover
            }
        } else {
            // 车况卡隐藏或被设成固定档位：剩余宽度在「自适应」卡之间均分/分摊。
            val targets = slots.indices.filter { slots[it].size == Size.AUTO }
            val pool = targets.ifEmpty { listOf(slots.lastIndex) }
            if (leftover >= 0f) {
                val share = leftover / pool.size
                for (i in pool) widths[i] += share
            } else {
                // 固定档位太宽：自适应卡按比例收窄，保证整行不溢出。
                val totalAuto = pool.sumOf { base[it].toDouble() }.toFloat()
                val deficit = -leftover
                if (totalAuto > 0f) {
                    val keep = (1f - deficit / totalAuto).coerceIn(0.4f, 1f)
                    for (i in pool) widths[i] = base[i] * keep
                } else {
                    widths[pool.last()] = (widths[pool.last()] + leftover).coerceAtLeast(minWidth)
                }
            }
        }
        return slots.mapIndexed { i, slot -> slot.id to widths[i].coerceAtLeast(minWidth * 0.75f) }
    }

    /** 顺序持久化通道（由宿主注入，例如 [PrefsStore]）。 */
    interface Store {
        fun load(): List<Slot>?
        fun save(layout: List<Slot>)
    }

    class PrefsStore(private val prefs: Prefs) : Store {
        override fun load(): List<Slot>? = decode(prefs.stringValue(KEY, ""))
        override fun save(layout: List<Slot>) {
            prefs.setStringValue(KEY, encode(normalize(layout)))
        }
    }
}
