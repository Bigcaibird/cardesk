package com.cardesk.launcher.cluster

/**
 * 选「歌词往哪块屏投」的**纯逻辑**（无 Android 类型）。
 *
 * ## 为什么不能瞎猜
 *
 * 车机上的副屏不止一块：仪表、副驾娱乐屏、后退像屏、抬头显示……
 * 猜错的后果不是「歌词没显示」，而是**把歌词糊到副驾屏或倒车影像上**——
 * 那比不做还糟。所以这里的判定顺序刻意保守：
 *
 * 1. **上次用的那块屏还在** → 就用它（id 精确命中）；
 * 2. **同一块物理屏换了 id** → 按名字找回，但**只有名字唯一命中**才认：
 *    两块屏同名、且都不是上次那块 ⇒ 判不了，不猜；
 * 3. 其它情况一律 [NONE]（不投）。
 *
 * ## 主屏永远不是目标
 *
 * 单屏设备（模拟器、平板、没接仪表的车机）里唯一那块屏的 id 是 `0`。
 * 它的名字一旦被存下来，按名字匹配就会把主屏当成仪表，
 * 于是歌词窗口盖在桌面自己身上：整条半透明底把桌面压暗，字全糊了。
 *
 * 所以：**id ≤ 0 的屏一律跳过**，名字匹配也只考虑 id > 0 的。
 *
 * 移植自 UNI 桌面（`DisplayPicker`），口径一致。
 */
object DisplayPicker {

    /** 没有可投的屏。 */
    const val NONE = -1

    /**
     * 选出目标屏在数组里的下标。
     *
     * @param ids 屏 id 数组（`Display.getDisplayId()`）
     * @param names 屏名字数组（`Display.getName()`，系统保证非空）
     * @param wantName 上次记住的名字（空 = 从没选过）
     * @param wantId 上次记住的 id（-1 = 不知道）
     * @return 下标，或 [NONE]
     */
    fun pick(
        ids: IntArray,
        names: Array<String>,
        wantName: String,
        wantId: Int,
    ): Int {
        if (ids.isEmpty() || names.isEmpty()) return NONE
        val length = minOf(ids.size, names.size)
        if (length == 0) return NONE

        // 1. 上次那块屏还在 → 就用它。
        if (wantId > 0) {
            for (i in 0 until length) {
                if (ids[i] == wantId) return i
            }
        }

        // 2. 同一块物理屏换了 id。只有名字唯一命中才认 ——
        //    两块屏同名且都不是上次那块，恰恰是「猜了就会投错」的情况。
        val wanted = wantName.trim()
        if (wanted.isNotEmpty()) {
            var match = NONE
            var matches = 0
            for (i in 0 until length) {
                if (ids[i] <= 0) continue
                val name = names[i].trim()
                if (!wanted.equals(name, ignoreCase = true)) continue
                matches++
                if (match == NONE) match = i
            }
            if (matches == 1) return match
        }
        return NONE
    }

    /** 第一块「自称是 Presentation」的副屏（「替我选一个」用）。 */
    fun firstPresentation(ids: IntArray, presentation: BooleanArray): Int {
        if (ids.isEmpty() || presentation.isEmpty()) return NONE
        val length = minOf(ids.size, presentation.size)
        for (i in 0 until length) {
            if (presentation[i] && ids[i] > 0) return i
        }
        return NONE
    }

    /** 第一块副屏（不限类型；没有屏自称 Presentation 时的兜底）。 */
    fun firstSecondary(ids: IntArray): Int {
        for (i in ids.indices) {
            if (ids[i] > 0) return i
        }
        return NONE
    }

    /** 一行屏幕说明：`仪表 · ID 1 · 1920×720 · 240 dpi`。 */
    fun label(name: String?, id: Int, width: Int, height: Int, densityDpi: Int): String {
        val sb = StringBuilder()
        val trimmed = name?.trim().orEmpty()
        sb.append(if (trimmed.isEmpty()) "屏幕 $id" else trimmed)
        sb.append("  ·  ID ").append(id)
        if (width > 0 && height > 0) sb.append("  ·  ").append(width).append('×').append(height)
        if (densityDpi > 0) sb.append("  ·  ").append(densityDpi).append(" dpi")
        return sb.toString()
    }
}
