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
}
