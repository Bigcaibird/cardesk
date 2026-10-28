package com.cardesk.launcher.core.update

/**
 * 版本号比较（纯函数，第六十九轮）。
 *
 * 更新源是 GitHub Releases，tag 形如 `v1.0.1035`；
 * 而车机上装着的版本号（`versionName`）是 `1.0.1038-system`
 * （system 变体在版本号后面加了 flavor 后缀）。
 *
 * 两边要能比较，就得先把两边**归一**成同一形态：
 * - 去 `v` / `V` 前缀；
 * - 去 `-system` 这类后缀；
 * - 再按 `.` 分段，逐段比数字。
 *
 * 为什么不比 `versionCode`：Release 的 tag 里只有 `versionName`，
 * `versionCode` 只出现在 release 的**标题**（`CarDesk 4131 / 1.0.1035-system`）里 ——
 * 那是个自由文本字段，拿它当版本依据太脆。
 */
object ReleaseVersion {

    /** 归一：去 `v` 前缀、去 `-` 后缀。`v1.0.1035-system` → `1.0.1035`。 */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (s[0] == 'v' || s[0] == 'V') s = s.substring(1)
        val dash = s.indexOf('-')
        if (dash >= 0) s = s.substring(0, dash)
        return s.trim()
    }

    /** 数字分段：`1.0.1038` → `[1, 0, 1038]`。非数字段按 0 算（不猜、不抛）。 */
    fun parts(raw: String): List<Int> {
        val n = normalize(raw)
        if (n.isEmpty()) return emptyList()
        return n.split('.').map { seg ->
            val digits = seg.takeWhile { it.isDigit() }
            if (digits.isEmpty()) 0 else digits.toIntOrNull() ?: 0
        }
    }

    /**
     * 比较两个版本号。
     *
     * @return a 更新 → 正数；一样 → 0；a 更旧 → 负数。
     */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 远端版本是否比本地新。 */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0
}
