package com.cardesk.launcher.cluster

/**
 * 仪表歌词的**面板样式**：中文标签 ↔ 存储值。
 *
 * ## 为什么要单独抽一个类（第一百四十三轮）
 *
 * 第一百三十七轮把「仪表歌词」的设置从旧对话框搬进设置页时，
 * 这一行写成了：
 *
 * ```kotlin
 * chipRow("面板样式", listOf("胶囊", "纯文字", "发光"), prefs.clusterStyle) { next ->
 *     prefs.clusterStyle = next        // ← next 是**中文标签**
 * }
 * ```
 *
 * 而 `prefs.clusterStyle` 存的是**英文键**（`pill` / `plain` / `glow`），
 * 投射服务的 [ClusterLyricsService.styleOf] 也只认英文键。
 *
 * 于是两件事同时坏了：
 *
 * 1. **选中态不显示**：`chipRow` 的字符串重载用 `options.indexOf(selected)` 找高亮项，
 *    拿 "pill" 去中文列表里找 → `-1` → **三个 chip 一个都不亮**。
 * 2. **切换无效果**：点一下回传的是中文标签，"纯文字" 存进偏好后，
 *    `styleOf()` 只认 "plain"/"glow"，剩下的全落到 `else` → **一律按「胶囊」渲染**。
 *
 * 现象就是用户说的「点了没反应」。
 *
 * ## 还要给**已经存坏值**的机器留后路
 *
 * 修好设置页只能保证**以后**存的是英文键；已经点了 bug chip 的机器，
 * 偏好里躺着的还是 "胶囊"/"纯文字"/"发光"。
 *
 * 所以归一化（[normalize]）必须**两种都认** —— 这才是真正把老机器救回来的那一步。
 *
 * ## 为什么是纯 Kotlin
 *
 * 和 [ClusterLyricColors] 一个路子：不碰 Android，能在 JVM 单测里逐条断言。
 * 这种「中文标签 / 英文键」对不上的错，最容易复发又最难在车上发现。
 */
object ClusterLyricStyles {

    /**
     * 三个样式：**(显示用的中文标签, 存进偏好的键)**。
     *
     * 顺序 = 设置页里 chip 的顺序。
     */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "胶囊" to "pill",
        "纯文字" to "plain",
        "发光" to "glow",
    )

    /** 「胶囊」在偏好里的键。 */
    const val PILL = "pill"

    /** 「纯文字」在偏好里的键。 */
    const val PLAIN = "plain"

    /** 「发光」在偏好里的键。 */
    const val GLOW = "glow"

    /**
     * 把偏好里可能存着的值归一成**英文键**。
     *
     * 认三种写法：
     * - 英文键（`pill` / `plain` / `glow`）—— 正常路径；
     * - 中文标签（`胶囊` / `纯文字` / `发光`）—— 第一百三十七轮那个 bug 存进去的，
     *   老机器上就是这些值，得认；
     * - 认不出来的 → 按「胶囊」处理（跟服务里 `styleOf` 的 `else` 一致）。
     */
    fun normalize(value: String): String {
        val key = value.trim()
        OPTIONS.forEach { (label, stored) ->
            if (key == label || key.equals(stored, ignoreCase = true)) return stored
        }
        return PILL
    }

    /**
     * 设置页里该高亮第几个 chip。
     *
     * 认不出来（理论上不会）就落到第 0 个，别让整行 chip 都不亮。
     */
    fun indexOf(value: String): Int {
        val key = normalize(value)
        val index = OPTIONS.indexOfFirst { it.second == key }
        return if (index >= 0) index else 0
    }

    /** 存储值 → 显示用的中文标签（认不出来就显示「胶囊」）。 */
    fun labelOf(value: String): String = OPTIONS[indexOf(value)].first
}
