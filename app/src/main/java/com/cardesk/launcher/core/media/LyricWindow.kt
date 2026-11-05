package com.cardesk.launcher.core.media

/**
 * 歌词窗口（expandedLyricWindow）。
 *
 *
 * - `expandedLyricFuture = 3`，
 *   最多 7 行（3 + 当前 + 3）；
 * - 决定「上几行 / 下几行」：
 *
 *   `java
 *   int n = Math.max(1, total);
 *   int half = (n % 2 == 0) ? n / 2: (n + 1) / 2;
 *   return new int[] { half - 1, n - half }; // {past, future}
 *   `
 *
 *   所以 7 行 → {3, 3}、6 行 → {2, 3}、5 行 → {2, 2}、1 行 → {0, 0}；
 * -按可用高度自动收缩总行数（：从 7 行往下试）。
 *
 * 第三十三轮起第二页组合卡的播放器**固定显示 5 句**（用户要求），
 * 即「上 2 句 + 当前句 + 下 2 句」，当前句在竖直方向居中。
 *
 * 纯 Kotlin（不依赖 Android），可在 JVM 单测里覆盖「拆行 / 取行 / 空位占位」。
 */
object LyricWindow {

    /** 第二页组合卡显示的总行数（第三十三轮：5 句）。 */
    const val TOTAL_LINES = 5

    /**
     * 拆成「上方行数 / 下方行数」。
     *
     * 与完全一致。
     *
     * @param total 总行数（含当前句），小于 1 按 1 算
     * @return `[past, future]` —— 上方 past 行、下方 future 行
     */
    fun split(total: Int): IntArray {
        val n = if (total < 1) 1 else total
        val half = if (n % 2 == 0) n / 2 else (n + 1) / 2
        return intArrayOf(half - 1, n - half)
    }

    /**
     * 取窗口里第 [slot] 行的文本。
     *
     * @param lines 全部歌词行
     * @param index 当前行下标（可以小于 0，也可以大于末行）
     * @param slot 相对当前行的偏移：`-2/-1` 上方、`0` 当前、`1/2` 下方
     * @return 该行文本；越界或空行返回空串（界面用占位保持高度）
     */
    fun lineAt(lines: List<String>, index: Int, slot: Int): String {
        if (index < 0) return ""
        return lines.getOrNull(index + slot)?.takeIf { it.isNotBlank() } ?: ""
    }

    /**
     * 生成整窗文本（顺序：上 → 当前 → 下）。
     *
     * @param lines 全部歌词行
     * @param index 当前行下标
     * @param total 总行数
     * @return 与 [split] 对应的行序（负数是上方行，正数是下方行）
     */
    fun slots(total: Int): IntArray {
        val sp = split(total)
        return IntArray(sp[0] + 1 + sp[1]) { it - sp[0] }
    }

    /**
     * 取「以当前行为中心」的整窗文本。
     *
     * 第 113 轮：首页中央大字歌词改成**五行**（上 2 / 当前 / 下 2），
     * 整窗的取句逻辑跟导航页组合卡完全一致 —— 所以把它提到这里，
     * 两边共用一份**纯 Kotlin**实现（可在 JVM 单测里覆盖）。
     *
     * @param lines 全部歌词行
     * @param index 当前行下标（< 0 表示没有歌词，整窗都是空串）
     * @param total 总行数（含当前句），默认 [TOTAL_LINES]
     * @return 长度恒为 `total` 的列表，顺序是「上 → 当前 → 下」；
     *         越界 / 空行处是空串（界面保留位置但不画字）
     */
    fun window(lines: List<String>, index: Int, total: Int = TOTAL_LINES): List<String> {
        val sp = split(total)
        val past = sp[0]
        return List(past + 1 + sp[1]) { i -> lineAt(lines, index, i - past) }
    }

    /**
     * 按**显式行序**取窗口（第117轮）。
     *
     * 首页中央大字歌词有两种布局（[LyricLayout]）：
     * 三行 = `[-1, 0, 1]`（当前句夹在中间）、KTV 两行 = `[0, 1]`（**当前句在上**）。
     *
     * **为什么不直接用 [window] 的 total 版**：`split(n)` 是按「上下均分」算的，
     * 它假设当前句在中间 —— 三行刚好对上，KTV 的「当前句在最上」就说不清了
     * （凑巧 `split(2)` 也返回 `[0, 1]`，但那是巧合，不是语义）。
     * 这里让布局**显式给出行序**，取句只管照着取，
     * 布局定义和取句实现就不会各说各话。
     *
     * @param lines 全部歌词行
     * @param index 当前行下标
     * @param slots 行序（相对当前句的偏移：负 = 上一句，正 = 下一句）
     * @return 长度与 [slots] 相同、顺序一致的列表；越界 / 空行处是空串
     */
    fun window(lines: List<String>, index: Int, slots: IntArray): List<String> =
        slots.map { lineAt(lines, index, it) }
}
