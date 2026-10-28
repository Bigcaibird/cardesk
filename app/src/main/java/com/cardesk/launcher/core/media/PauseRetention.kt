package com.cardesk.launcher.core.media

/**
 * 「暂停态元数据保留」的纯判定（`MusicSessionTracker.Record`）。
 *
 * 抽成纯对象是为了能单测 —— 这条规则决定了真机上
 * 「暂停一下歌名和歌词就全没了」这个现象会不会复现：
 *
 * - 候选选择用的是 （暂停时保留的那份元数据），
 *   而不是会话此刻上报的原始元数据；
 * - 所以暂停后即使会话把元数据撤了，这个会话**仍然**算「有歌」，
 *   不会被踢出候选 → 界面不会被清空。
 *
 *
 * -
 * -
 * -
 * -
 * -
 */
object PauseRetention {

    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_BUFFERING = 6
    const val STATE_ERROR = 7
    const val STATE_CONNECTING = 8

    fun isActive(state: Int?): Boolean =
        state == STATE_PLAYING || state == STATE_BUFFERING || state == STATE_CONNECTING

    fun isPauseMetadataPackage(pkg: String?): Boolean =
        pkg == "com.tencent.wecarflow" || pkg == "com.ecarx.multimedia"

    /**
     *
     *
     * 只有「支持暂停态保留的包」（车机多媒体）+ 状态合适时才能保留元数据。
     */
    fun canRetainPause(pkg: String?, state: Int?, userPauseRequested: Boolean): Boolean {
        if (!isPauseMetadataPackage(pkg)) return false
        if (state == null) return false
        if (state != STATE_PAUSED) {
            if (!userPauseRequested) return false
            if (!isActive(state) && state != STATE_STOPPED) return false
        }
        return true
    }

    /**
     * 判定结果。
     *
     * @param keepPausedMetadata 这次状态变化后，暂停态元数据还留不留
     * @param userPauseRequested 这次状态变化后，「用户按过暂停」这个标记还留不留
     */
    data class StateResult(
        val keepPausedMetadata: Boolean,
        val userPauseRequested: Boolean,
    )

    /**
     *
     *
     * @param hasMetadata 当前是否还有可用元数据（决定恢复到「活跃」态时要不要重新记住）
     */
    fun onStateChanged(
        state: Int?,
        userPauseRequested: Boolean,
        hasMetadata: Boolean,
    ): StateResult {
        if (state == null || state == STATE_NONE || state == STATE_ERROR ||
            (state == STATE_STOPPED && !userPauseRequested)
        ) {
            return StateResult(keepPausedMetadata = false, userPauseRequested = false)
        }
        if (isActive(state)) {
            return StateResult(keepPausedMetadata = hasMetadata, userPauseRequested = false)
        }
        return StateResult(keepPausedMetadata = true, userPauseRequested = userPauseRequested)
    }
}
