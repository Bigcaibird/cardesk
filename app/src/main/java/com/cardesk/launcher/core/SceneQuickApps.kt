package com.cardesk.launcher.core

/**
 * 桌面「快捷应用」卡片的槽位配置模型（纯逻辑，可 JVM 单测）。
 *
 * 与 [DockQuickApps] 保持同一套存储风格，区别在于：
 * - 固定 [SLOTS] = 4 个槽位；
 * - **保留空槽位位置**（例如 `pkgA,,pkgC,`），方便用户在卡片上逐格自由调整、更换或清空，
 *   并通过 [swap] 上下移动调整顺序；
 * - 未自定义过（`configured == false`）时，卡片默认自动填充前 4 个可用应用；
 *   一旦用户自定义，即按槽位配置渲染（空槽位显示「+ 添加」）。
 */
object SceneQuickApps {

    const val SLOTS = 4

    /** 持久化键：逗号分隔的 4 个槽位包名（空串代表空槽）。 */
    const val KEY = "scene_quick_apps"

    /** 是否由用户自定义过（未自定义时自动填充默认应用）。 */
    const val CONFIGURED_KEY = "scene_quick_apps_configured"

    /** 解析逗号分隔字符串为定长 [SLOTS] 槽位列表（保留空位、去除重复非空包名）。 */
    fun parse(csv: String): List<String> {
        if (csv.isEmpty()) return List(SLOTS) { "" }
        return normalize(csv.split(','))
    }

    /** 编码为逗号分隔字符串（固定 [SLOTS] 段）。 */
    fun toCsv(slots: List<String>): String = normalize(slots).joinToString(",")

    /** 规整为定长 [SLOTS] 列表：保留空位，非空包名去重（先出现者优先）。 */
    fun normalize(raw: List<String>): List<String> {
        val seen = HashSet<String>(SLOTS)
        return List(SLOTS) { i ->
            val pkg = raw.getOrElse(i) { "" }.trim()
            if (pkg.isEmpty() || !seen.add(pkg)) "" else pkg
        }
    }

    /** 把指定槽位替换为 [pkg]（若该包名已在其他槽位出现，原槽位清空避免重复）。 */
    fun withSlot(current: List<String>, index: Int, pkg: String): List<String> {
        val out = normalize(current).toMutableList()
        if (index !in 0 until SLOTS) return out
        val trimmed = pkg.trim()
        if (trimmed.isNotEmpty()) {
            for (i in out.indices) {
                if (i != index && out[i] == trimmed) out[i] = ""
            }
        }
        out[index] = trimmed
        return out
    }

    /** 交换两个槽位的位置（用于上移 / 下移自由调整顺序）。 */
    fun swap(current: List<String>, from: Int, to: Int): List<String> {
        val out = normalize(current).toMutableList()
        if (from !in 0 until SLOTS || to !in 0 until SLOTS || from == to) return out
        val tmp = out[from]
        out[from] = out[to]
        out[to] = tmp
        return out
    }

    /** 清空全部槽位。 */
    fun empty(): List<String> = List(SLOTS) { "" }

    interface Store {
        fun load(): List<String>
        fun save(slots: List<String>)
        fun isConfigured(): Boolean
        fun resetDefault()
    }

    class PrefsStore(private val prefs: Prefs) : Store {
        override fun load(): List<String> = parse(prefs.stringValue(KEY, ""))
        override fun save(slots: List<String>) {
            prefs.setStringValue(KEY, toCsv(slots))
            prefs.setFlag(CONFIGURED_KEY, true)
        }
        override fun isConfigured(): Boolean = prefs.flag(CONFIGURED_KEY, false)
        override fun resetDefault() {
            prefs.setStringValue(KEY, "")
            prefs.setFlag(CONFIGURED_KEY, false)
        }
    }
}
