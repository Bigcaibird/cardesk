package com.cardesk.launcher.core

/**
 * 文本按宽度截断（`ellipsize=end` 语义）——**纯逻辑**，不依赖 Android，可在 JVM 单测里直接验。
 *
 * ## 为什么需要
 *
 * 这些控件都是 `TextView`，布局里写死了
 * `android:maxLines="1"` + `android:ellipsize="end"`：
 *
 * |控件 | 约束 |
 * | --- | --- |
 * | 的 | `maxLines=1` + `ellipsize=end` + `singleLine=true` |
 * | 的 | `maxLines=1` + `ellipsize=end` |
 * | 的 | `maxLines=1` + `ellipsize=end` |
 * | 的 | `maxLines=1` + `ellipsize=end` |
 *
 * 我们有两条路径要遵守这个约束：
 *
 * 1. `TextView` 路径 → 直接设 `maxLines = 1` + `ellipsize = TruncateAt.END`；
 * 2. **canvas 自绘**路径（增强导航栏的槽位）→ canvas 没有换行 / 裁剪能力，
 *    不裁剪的话长句会**直接画到相邻槽位上**（实测：56 字符的英文歌词压在「分屏 / 360」两个槽位上）。
 *
 * ## 算法
 *
 * 与 TextView 的 `ellipsize=end` 一致：超宽时砍掉尾部字符并加一个省略号 `…`，
 * 直到「带省略号的文本」能放进可用宽度为止。全部砍光时返回单个省略号。
 */
object TextClip {

    /** 省略号。 */
    const val ELLIPSIS = "…"

    /**
     * 按可用宽度截断文本。
     *
     * @param text 原文
     * @param measure 文本宽度测量（由调用方用 `Paint.measureText` 提供）
     * @param maxWidth 可用宽度（px）；`<= 0` 表示没有可用宽度 → 只返回省略号
     * @return 不超宽时原样返回；超宽时是「截断 + 省略号」
     */
    fun ellipsize(text: String, measure: (String) -> Float, maxWidth: Float): String {
        if (text.isEmpty()) return text
        if (maxWidth <= 0f) return ELLIPSIS
        if (measure(text) <= maxWidth) return text
        var out = text
        while (out.isNotEmpty() && measure(out + ELLIPSIS) > maxWidth) {
            out = out.dropLast(1)
        }
        return out + ELLIPSIS
    }
}
