package com.cardesk.launcher.core.nav

import com.cardesk.launcher.core.JsonLite

/**
 * 一条车道线。
 *
 * - [icon]：车道线图标编码（assets 里的 `hud_lanes/landr_<code>.png`）
 * - [on]：是否是**当前推荐**的那条车道（高亮）
 */
data class LaneTile(val icon: Int, val on: Boolean)

/**
 * 高德「车道线」数据（`EXTRA_DRIVE_WAY`）的解析。
 *
 * 逐条
 *
 * | 分支 |位置 |
 * |---|---|
 * | 入口（trim 后以 `{` 开头走 JSON，否则走 legacy） | |
 * | JSON 分支 | |
 * | legacy 分支（`a,b,c\\|h,i,j`） | |
 * | 整数拆分 | |
 * | 宽容取整 | |
 *
 * 纯 Kotlin、无 Android 依赖，可直接 JVM 单测。
 */
object DriveWay {

    /**
     * 编码 >= 15 视为「高亮」车道。
     *
     * 里前端（`drive_way_lane_Front_icon`）的一组图是 15+，后端的是 0~14
     * 所以 `icon >= 15` 等价于「拿到了前端图标」。
     */
    const val ON_THRESHOLD = 15

    /**
     * 解析车道线数据。
     *
     * @return null 表示**没有**车道线（null / 空数组语义）。
     */
    fun parse(raw: String?): List<LaneTile>? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("{")) parseJson(trimmed) else parseLegacy(trimmed)
    }

    /**
     * JSON 分支。
     *
     * 结构：
     * `json
     * {
     *   "drive_way_enabled": true,
     *   "drive_way_size": 3,
     *   "drive_way_info": [
     *     {"drive_way_number": 0, "drive_way_lane_Front_icon": 15, "drive_way_lane_Back_icon": 0},
     *     {"drive_way_number": 1, "drive_way_lane_Front_icon": 0, "drive_way_lane_Back_icon": 1}
     *   ]
     * }
     * `
     *
     * - `drive_way_info` 为空 → null
     * - `drive_way_enabled` 明确为 false → null（：整个隐藏车道线）
     * - `drive_way_size` 越界（<=0 或 >= 数组长度）→ 用数组长度
     * - 每条取 `front`（前端，高亮）与 `back`（后端），`front > 0` 时用 front
     * - 按 `drive_way_number` **稳定**升序
     */
    fun parseJson(text: String): List<LaneTile>? {
        val root = JsonLite.parseObject(text) ?: return null
        val info = root["drive_way_info"] as? List<*> ?: return null
        if (info.isEmpty()) return null
        if (root.containsKey("drive_way_enabled") && !JsonLite.bool(root, "drive_way_enabled", false)) {
            return null
        }
        var size = optIntFlex(root, "drive_way_size", info.size)
        if (size <= 0 || size >= info.size) size = info.size

        val rows = ArrayList<Triple<Int, Int, Boolean>>()  // number, icon, on
        for (i in 0 until size) {
            val item = info.getOrNull(i) as? Map<*, *> ?: continue
            @Suppress("UNCHECKED_CAST")
            val map = item as Map<String, Any?>
            val back = optIntFlex(map, "drive_way_lane_Back_icon", optIntFlex(map, "drive_way_lane_back_icon", 0))
            val front = optIntFlex(map, "drive_way_lane_Front_icon", optIntFlex(map, "drive_way_lane_front_icon", 0))
            val icon = if (front > 0) front else back
            val on = front > 0 || icon >= ON_THRESHOLD
            val number = optIntFlex(map, "drive_way_number", i)
            rows.add(Triple(number, icon, on))
        }
        if (rows.isEmpty()) return null
        // 用 Collections.sort（稳定排序）按 number 升序
        rows.sortBy { it.first }
        return rows.map { LaneTile(it.second, it.third) }
    }

    /**
     * legacy 分支。
     *
     * 格式：`"1,2,3|15,0,0"` —— 前半段是每条车道的底图编码，
     * 后半段（`|` 之后）是**高亮**编码（>0 才算高亮）。
     */
    fun parseLegacy(text: String): List<LaneTile>? {
        var base = text
        var highlight: String? = null
        val bar = text.indexOf('|')
        if (bar >= 0) {
            base = text.substring(0, bar)
            highlight = text.substring(bar + 1)
        }
        val baseInts = splitInts(base)
        if (baseInts.isEmpty()) return null
        val highlightInts = if (highlight == null) IntArray(0) else splitInts(highlight)
        return baseInts.mapIndexed { i, b ->
            val h = if (i < highlightInts.size) highlightInts[i] else 0
            val icon = if (h > 0) h else b
            LaneTile(icon, h > 0 || icon >= ON_THRESHOLD)
        }
    }

    /**
     * 拆分整数串。
     *
     * 分隔符是 `,` `;` 和空白；解析失败的位**留 0**（不丢位）。
     */
    fun splitInts(text: String?): IntArray {
        if (text == null) return IntArray(0)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return IntArray(0)
        val parts = trimmed.split(Regex("[,;\\s]+"))
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            if (parts[i].isNotEmpty()) {
                out[i] = parts[i].trim().toIntOrNull() ?: 0
            }
        }
        return out
    }

    /**
     * 宽容取整。
     *
     * 数字直接取；字符串先 trim 再 parse，失败用默认值；其它类型用默认值。
     */
    fun optIntFlex(map: Map<String, Any?>?, key: String, default: Int): Int {
        if (map == null) return default
        if (!map.containsKey(key)) return default
        val v = map[key] ?: return default
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is String -> {
                val trimmed = v.trim()
                if (trimmed.isEmpty()) default else trimmed.toIntOrNull() ?: default
            }
            else -> default
        }
    }
}
