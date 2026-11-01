package com.cardesk.launcher.core.net

/**
 * 下载进度/速度的展示格式（第九十八轮）。
 *
 * 云端应用下载弹窗要同时显示「百分比 + 下载速度」，速度由「已下载字节差 / 时间差」
 * 算出来；格式化成字符串的逻辑放在这里，方便单测（不依赖 Android）。
 */
object TransferFormat {

    /** 字节数 → `12.3 MB` / `512 KB` / `900 B`。 */
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        return when {
            value >= 1L shl 30 -> String.format("%.2f GB", value / 1073741824.0)
            value >= 1L shl 20 -> String.format("%.1f MB", value / 1048576.0)
            value >= 1L shl 10 -> String.format("%.0f KB", value / 1024.0)
            else -> "$value B"
        }
    }

    /** 字节/秒 → `1.2 MB/s`（速度未知时返回「—」）。 */
    fun speed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.0 || bytesPerSecond.isNaN() || bytesPerSecond.isInfinite()) return "—"
        return bytes(bytesPerSecond.toLong()) + "/s"
    }

    /** 0..100 的百分比文案（越界自动夹取）。 */
    fun percent(value: Int): String = "${value.coerceIn(0, 100)}%"

    /**
     * 速度平滑：用两次采样之间的字节差和毫秒差算，避免首帧/单块抖动。
     * 返回 0 表示这次采样不算（时间差太小）。
     */
    fun speedOf(deltaBytes: Long, deltaMs: Long): Double {
        if (deltaMs <= 0L || deltaBytes <= 0L) return 0.0
        return deltaBytes * 1000.0 / deltaMs
    }
}
