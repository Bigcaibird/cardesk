package com.cardesk.launcher.ui

/**
 * 音乐页的纯逻辑（不依赖 Android View，方便单测）。
 *
 * - 时间格式对齐；
 * - 拖动换算对齐 （frac = x / 进度条宽度）。
 *
 * ## 为什么单独一个文件
 *
 * 第三十二轮按用户要求**去掉了第三页（播放器页）**，
 * 播放器搬到了**第二页组合卡的右半屏**（`ui/pages/NavPage.kt`）。
 * 这三个纯函数与 Android View 无关、也仍然有单测覆盖，
 * 所以从原来的 里拆出来单独留着（将来若要恢复独立播放器页可直接用）。
 */
object MusicPageLogic {

    /** 秒 → "m:ss"。 */
    fun formatClock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** 没有时长就没法 seek（j3 > 0）。 */
    fun isSeekable(durationSec: Int): Boolean = durationSec > 0

    /** 拖动位置（px）→ 秒数。 */
    fun seekSec(px: Float, viewWidth: Int, durationSec: Int): Int {
        if (viewWidth <= 0 || durationSec <= 0) return 0
        val frac = (px / viewWidth).coerceIn(0f, 1f)
        return (frac * durationSec).toInt().coerceIn(0, durationSec)
    }
}
