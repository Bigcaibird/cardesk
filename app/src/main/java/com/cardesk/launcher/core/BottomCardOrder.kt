package com.cardesk.launcher.core

/**
 * 桌面底部卡片顺序（`scene_bottom_card_order_v1`）。
 *
 * 出处
 * - 键名
 * - 卡片集合 `BOTTOM_CARD_IDS = {快捷, 媒体, 空调, 胎压, 续航}`
 * - 落盘格式：逗号分隔的卡片 id
 * - 读取校验：解析出来的个数必须和卡片数一致，否则整份丢弃
 *
 * 纯 Kotlin（不依赖 Android），可在 JVM 单测里覆盖「编码 / 解码 / 归一化 / 移动」全链路。
 */
object BottomCardOrder {

    /** 快捷入口卡（1 张玻璃卡里 4 个应用位）。 */
    const val QUICK = "quick"

    /** 媒体卡。 */
    const val MUSIC = "music"

    /** 空调快捷卡。 */
    const val CLIMATE = "climate"

    /** 胎压卡。 */
    const val TIRE = "tire"

    /** 续航卡（剩余宽度）。 */
    const val CAR = "car"

    /** 持久化键名。 */
    const val KEY = "scene_bottom_card_order_v1"

    /** （:168）的默认顺序。 */
    val DEFAULT: List<String> = listOf(QUICK, MUSIC, CLIMATE, TIRE, CAR)

    /** 落盘格式：逗号分隔。 */
    fun encode(order: List<String>): String = order.joinToString(",")

    /**
     * 解析落盘内容。个数对不上就整份作废。
     * 解析失败同样返回 null。
     */
    fun decode(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != DEFAULT.size) return null
        if (parts.toSet().size != parts.size) return null
        if (parts.any { !DEFAULT.contains(it) }) return null
        return parts
    }

    /**
     * 归一化：去重 + 补齐缺失的卡片 + 丢弃未知卡片。
     * 保证结果始终是 5 个卡片的一个排列。
     */
    fun normalize(order: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (id in order) if (id in DEFAULT) out.add(id)
        for (id in DEFAULT) if (id !in out) out.add(id)
        return out.toList()
    }

    /**
     * 把 [from] 位置的卡片移动到 [to] 位置（的 remove + addView 语义）。
     */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        val list = order.toMutableList()
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.size - 1)
        val item = list.removeAt(from)
        list.add(target, item)
        return list
    }

    /**
     * 拖拽时按 X 坐标找目标卡片下标：
     * 落在某张卡的区间内就取那张；否则取中心点最近的一张。
     *
     * @param x 指针的 X（与 [bounds] 同一坐标系）
     * @param bounds 每张卡片的 [left, right] 区间，按当前顺序排列
     */
    fun indexAt(x: Float, bounds: List<Pair<Float, Float>>): Int {
        if (bounds.isEmpty()) return -1
        var nearest = -1
        var nearestDist = Float.MAX_VALUE
        for ((i, b) in bounds.withIndex()) {
            val left = b.first
            val right = b.second
            if (x >= left && x < right) return i
            val d = kotlin.math.abs(x - (left + right) / 2f)
            if (d < nearestDist) {
                nearest = i
                nearestDist = d
            }
        }
        return nearest
    }

    /** 顺序的读写通道（宿主注入，例如 [PrefsStore]）。 */
    interface Store {
        /** 读出已保存的顺序；没有或非法时返回 null。 */
        fun load(): List<String>?

        /** 保存顺序。 */
        fun save(order: List<String>)
    }

    /** 基于 [Prefs] 的实现，键名 `scene_bottom_card_order_v1`。 */
    class PrefsStore(private val prefs: Prefs) : Store {
        override fun load(): List<String>? = decode(prefs.stringValue(KEY, ""))

        override fun save(order: List<String>) {
            prefs.setStringValue(KEY, encode(order))
        }
    }
}
