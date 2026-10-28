package com.cardesk.launcher.core

/**
 * Dock **快捷应用**槽位（第五十四轮新增）。
 *
 * Dock 第五十四轮起是四段：**导航键 · 音乐卡 · 空调簇 · 快捷应用**。
 * 三段之间不再刻意留大空隙，省下来的空间放快捷应用（默认 [SLOTS] 个槽位），
 * 槽位内容由用户自己配置。
 *
 * ## 为什么要做成纯逻辑
 *
 * 配置是「槽位 → 包名」的映射，用户随时可能：
 * - 把同一个应用填到两个槽位（要去重）；
 * - 填超过 [SLOTS] 个（要截断）；
 * - 清空某个槽位（空串要剔掉）；
 * - 填一个没装 / 已被卸载的应用（真机上要兜底成「空槽」）。
 *
 * 所以「怎么存」和「怎么画」必须分开：
 * 这里只管**存取与规整**（可单测），画的部分在 `ui/shell/DockBarView.kt`。
 *
 * 存储格式：**逗号分隔的包名**，按槽位顺序 —— 与 `Prefs.appOrder` 同一套路，
 * 不引入任何新的序列化设施（项目禁止 org.json）。
 */
object DockQuickApps {

    /** 槽位数（第五十四轮用户定的 3 个）。 */
    const val SLOTS = 3

    /** 持久化键（逗号分隔的包名，按槽位顺序）。 */
    const val KEY = "dock_quick_apps"

    /** 逗号分隔字符串 → 槽位列表（空串 = 未配置）。 */
    fun parse(csv: String): List<String> = normalize(csv.split(','))

    /** 槽位列表 → 逗号分隔字符串。 */
    fun toCsv(pkgs: List<String>): String = normalize(pkgs).joinToString(",")

    /**
     * 规整：去空白 → 去空 → 去重 → 截断到 [SLOTS]。
     *
     * 去重时保留**第一次出现**的位置（也就是靠前的槽位优先）。
     */
    fun normalize(pkgs: List<String>): List<String> =
        pkgs.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(SLOTS)

    /**
     * 补齐成 [SLOTS] 个槽位（不足的用空串占位）。
     *
     * 画的时候按槽位下标取，空串 = 该槽位空着（画「+」占位）。
     */
    fun pad(pkgs: List<String>): List<String> {
        val n = normalize(pkgs)
        return List(SLOTS) { n.getOrElse(it) { "" } }
    }

    /**
     * 把槽位列表里的某一格换成新值（换完仍然去重 + 截断）。
     *
     * @param current 当前槽位列表（可以不足 [SLOTS]）
     * @param index 槽位下标
     * @param pkg 新包名（空串 = 清空该槽位）
     */
    fun withSlot(current: List<String>, index: Int, pkg: String): List<String> {
        if (index !in 0 until SLOTS) return normalize(current)
        val padded = pad(current).toMutableList()
        padded[index] = pkg.trim()
        return normalize(padded)
    }

    /** 读写通道（便于单测替身）。 */
    interface Store {
        fun load(): List<String>
        fun save(pkgs: List<String>)
    }

    /** 基于 [Prefs] 的实现。 */
    class PrefsStore(private val prefs: Prefs) : Store {
        override fun load(): List<String> = parse(prefs.stringValue(KEY, ""))
        override fun save(pkgs: List<String>) = prefs.setStringValue(KEY, toCsv(pkgs))
    }
}
