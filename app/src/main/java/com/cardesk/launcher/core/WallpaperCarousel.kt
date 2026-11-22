package com.cardesk.launcher.core

/**
 * 壁纸**轮播**的纯逻辑（第207轮）。
 *
 * ## 为什么单独抽一个类
 *
 * 轮播这件事有三个容易出错的点，都跟「时间」有关，混在 UI 里很难测：
 *
 * 1. **间隔归一** —— 用户可能在 ROM 里改过偏好值，落盘的秒数不在候选里；
 * 2. **下一张是���一张** —— 轮播不能因为「当前是自定义壁纸」就乱跳；
 * 3. **什么时候该切** —— 到了间隔时间 ≠ 立刻就切（用户正在拖壁纸时不能抢）。
 *
 * 这三条都是**纯计算**，不依赖 Android，所以在 JVM 上直接单测。
 * 真正的定时器在 [com.cardesk.launcher.ui.LauncherView] 里。
 */
object WallpaperCarousel {

    /**
     * 轮播间隔的候选项（秒）。
     *
     * 15s 偏快（适合展厅 / 拍照），30s 是常见的「换一张」节奏，
     * 60s 是默认，300s（5 分钟）适合只想偶尔换换的场合。
     */
    val INTERVAL_SECONDS: List<Int> = listOf(15, 30, 60, 300)

    /** 默认间隔（秒）。 */
    const val DEFAULT_INTERVAL_SEC: Int = 60

    /** 间隔的下限（秒）—— 比这还短就没法看了。 */
    const val MIN_INTERVAL_SEC: Int = 15

    /** 间隔的上限（秒）。 */
    const val MAX_INTERVAL_SEC: Int = 300

    /**
     * 把任意秒数归一到**候选里最接近的那一个**。
     *
     * 比「夹到 [MIN, MAX]」好：候选是不连续的（15/30/60/300），
     * 夹紧会把 100 秒变成 100 秒（不在候选里），设置页就没法显示了。
     *
     * **平局取大的那个**（45 秒 → 60 秒而不是 30 秒）：
     * 间隔偏长只是「换得慢一点」，偏短会让壁纸频繁跳动、看得晃。
     * 两个后果不对称时取保守的那个。
     *
     * @param sec 原始秒数
     * @return 候选里最接近的秒数
     */
    fun normalizeInterval(sec: Int): Int {
        if (sec <= 0) return DEFAULT_INTERVAL_SEC
        var best = INTERVAL_SECONDS[0]
        var bestDist = kotlin.math.abs(best - sec)
        for (candidate in INTERVAL_SECONDS.drop(1)) {
            val dist = kotlin.math.abs(candidate - sec)
            // 距离更近就换；距离相同时取**更大**的（平局取大）。
            if (dist < bestDist || (dist == bestDist && candidate > best)) {
                best = candidate
                bestDist = dist
            }
        }
        return best
    }

    /**
     * 轮播的下一个下标。
     *
     * @param current 当前壁纸下标
     * @param count 壁纸总数
     * @return 下一张（末尾回到开头）
     */
    fun nextIndex(current: Int, count: Int): Int {
        if (count <= 0) return 0
        return ((current + 1) % count + count) % count
    }

    /**
     * 该不该在这一刻切壁纸。
     *
     * 条件有三个，缺一不可：
     * - 轮播**开着**；
     * - 距上次切换已经**够间隔**；
     * - 当前**不是在拖壁纸**（用户正拖着呢，不能抢）。
     *
     * @param enabled 轮播开关
     * @param elapsedMs 距上次切换的毫秒数
     * @param intervalSec 间隔（秒）
     * @param dragging 用户正在拖壁纸
     * @return true = 该切了
     */
    fun shouldSwitch(
        enabled: Boolean,
        elapsedMs: Long,
        intervalSec: Int,
        dragging: Boolean,
    ): Boolean {
        if (!enabled) return false
        if (dragging) return false
        val intervalMs = normalizeInterval(intervalSec) * 1000L
        return elapsedMs >= intervalMs
    }
}
