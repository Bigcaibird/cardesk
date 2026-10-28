package com.cardesk.launcher.core.media

/**
 * 播放位置解析（纯逻辑，可 JVM 单测）。
 *
 *
 * 歌词与进度条用的位置都是
 *
 * `java
 * long pos = lastPlaybackPosition + (System.currentTimeMillis() - lastPositionUpdateTime);
 * `
 *
 * 即「**会话上报的位置 + 从锚点经过的时间**」。
 *
 * ## 第三十三轮修的问题
 *
 * 之前我们把这段逻辑内联在 `MusicSessionTracker.pushProgress()` 里，有两个坑：
 *
 * 1. **会话没给位置时（`PlaybackState.position == POSITION_UNKNOWN` = -1）**，
 *    代码拿 -1 当基准去外推，`(-1 + 经过时间)` 被 `coerceAtLeast(0)` 拉回 0，
 *    于是位置从 0 重新数 —— 和歌曲真实进度完全对不上，歌词整体错位。
 *    现在改成：**位置未知时沿用上一次已知位置继续外推**；
 *    从头到尾都没拿到过有效位置才回落到 0。
 * 2. **位置可能算出负数或超过总时长**：现在统一钳到 `[0, duration]`。
 *    （`Math.max(0L, position)` 与 `max(0, min(position, duration))`）
 *
 * 另外记下「本次用的是会话上报值还是外推值」，供诊断包定位不同步。
 */
class PlaybackPositionTracker {

    /** 锚点时刻（uptimeMillis 时基）：上一次位置发生变化的那一刻。 */
    private var anchorAtMs = 0L

    /** 锚点对应的会话上报位置（<0 = 还没有过有效位置）。 */
    private var anchoredMs = -1L

    /** 最近一次会话上报的原始值（<0 = 未知）。 */
    var lastObservedMs: Long = POSITION_UNKNOWN
        private set

    /** 最近一次会话是不是没给位置。 */
    var positionUnknown: Boolean = true
        private set

    /**
     * 算出当前播放位置。
     *
     * @param observedMs 会话上报的 `PlaybackState.position`（<0 = 未知）
     * @param nowMs 当前时刻（与 `anchorAtMs` 同一时基）
     * @param playing 会话是否处于播放态
     * @param durationMs 曲目总时长（0 / 负 = 未知，不按它钳）
     */
    fun resolve(observedMs: Long, nowMs: Long, playing: Boolean, durationMs: Long): Long {
        if (observedMs >= 0) {
            // 位置变了 → 重新打锚点（与会话上报时刻对齐）
            if (observedMs != anchoredMs) {
                anchoredMs = observedMs
                anchorAtMs = nowMs
            }
            lastObservedMs = observedMs
            positionUnknown = false
            val pos = if (playing) {
                anchoredMs + (nowMs - anchorAtMs).coerceAtLeast(0L)
            } else {
                observedMs
            }
            return clamp(pos, durationMs)
        }
        // 会话没给位置：沿用上一次已知位置继续外推（没有就 0）
        lastObservedMs = POSITION_UNKNOWN
        positionUnknown = true
        if (anchoredMs < 0) return 0L
        val pos = if (playing) {
            anchoredMs + (nowMs - anchorAtMs).coerceAtLeast(0L)
        } else {
            anchoredMs
        }
        return clamp(pos, durationMs)
    }

    /** 位置变了（用于清掉「停滞」判定等派生状态）。 */
    fun observedChanged(observedMs: Long): Boolean = observedMs >= 0 && observedMs != anchoredMs

    /** 复位（换歌 / 换会话时调用）。 */
    fun reset() {
        anchorAtMs = 0L
        anchoredMs = -1L
        lastObservedMs = POSITION_UNKNOWN
        positionUnknown = true
    }

    companion object {
        /** `PlaybackState.POSITION_UNKNOWN`：会话没上报位置。 */
        const val POSITION_UNKNOWN = -1L

        /** 钳到 `[0, duration]`（duration 未知时只保证 ≥ 0）。 */
        fun clamp(pos: Long, durationMs: Long): Long {
            val low = pos.coerceAtLeast(0L)
            return if (durationMs > 0) low.coerceAtMost(durationMs) else low
        }
    }
}
