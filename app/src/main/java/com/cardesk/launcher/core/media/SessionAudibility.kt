package com.cardesk.launcher.core.media

/**
 * 「这个会话是不是**真的在放**」的纯逻辑判定（第202轮）。
 *
 * ## 为什么需要它
 *
 * 真车诊断包（2026-10-23，`com.tencent.wecarflow`）里出现过这样一段：
 *
 * ```
 * 18:36:10 playing=true   position=16044
 * 18:36:14 playing=false  position=16044
 * 18:36:26 playing=true   position=16044
 * 18:36:33 playing=false  position=16044
 * 18:36:37 playing=true   position=17785
 * 18:36:41 playing=false  position=17785
 * ```
 *
 * 会话每 4 秒在 PLAYING / PAUSED 之间翻一次，**进度却一直是同一毫秒**。
 * 用户确认那段时间车上确实有声音，但放的是 QQ 音乐（车机版不发布会话，
 * 只能走广播），爱趣听根本没在出声 —— 也就是说这是一个**僵尸会话**：
 * 状态在翻、位置不动。
 *
 * 旧规则只看 `state == PLAYING`（`SessionChooser` 的「活跃即胜出」、
 * `MusicSessionTracker.nativeSourcePlaying()`），僵尸会话于是：
 *
 * - 每 4 秒把音乐卡从 QQ 音乐抢过去一次（卡片状态来回跳）；
 * - 让 `nativeSourcePlaying` 跟着翻，方控的 claim 也一跳一跳。
 *
 * ## 判定规则
 *
 * 只看两件事：**在播**、**位置推进过**。
 *
 * - 位置在推进 → 真的在放；
 * - 位置连续 [WINDOW_MS] 没推进（且不处于刚恢复播放的宽限期）→ 当成没在放；
 * - 暂停超过 [RESUME_GAP_MS] 再恢复 → 重新给一次宽限
 *   （不然刚点播放的那几秒会被误判）；
 * - 位置未知（`< 0`）→ 无从判断，按在放处理。
 *
 * 僵尸会话每 4 秒翻一次，但暂停时长永远够不到 [RESUME_GAP_MS]，
 * 所以宽限不会被反复重置 —— 一个窗口之后就稳定判成「没在放」。
 */
object SessionAudibility {

    /** 位置多久没推进就认为「其实没在放」。要大于一个重扫周期（`RESCAN_MS` = 2.5s）。 */
    const val WINDOW_MS = 4_000L

    /** 暂停超过这么久再恢复，才重新给宽限（僵尸会话 4s 一翻，够不到）。 */
    const val RESUME_GAP_MS = 10_000L

    /** 一次采样。`Record` 持有一份，按时间往前推。 */
    data class Sample(
        /** 上一次看到的原始位置（`-1` 表示还没采过）。 */
        val lastPositionMs: Long = -1L,
        /** 最近一次**看到它在放**的时刻（暂停时不更新，用来判断「停了多久」）。 */
        val lastPlayingAtMs: Long = 0L,
        /** 最近一次**确认位置推进**（或刚恢复播放给宽限）的时刻。 */
        val lastAdvanceAtMs: Long = 0L,
        /** 上一次采样得出的结论。 */
        val audible: Boolean = false,
    )

    /**
     * 采样一次。
     *
     * @param playing 会话自报的播放态（`state == STATE_PLAYING`）
     * @param positionMs 会话自报的播放位置（`PlaybackState.position`，可能为 -1）
     * @param prev 上一次采样
     * @param nowMs 当前时刻（与 `prev` 同一时钟域，`elapsedRealtime` 即可）
     * @param outputting 音频输出探测的结论（第203轮，见 [AudioOutputProbe]）：
     *   `true`/`false` 是**地面真相**，优先于位置判据；`null` 表示探测不可用，
     *   回落到「位置有没有推进」。
     */
    fun next(
        playing: Boolean,
        positionMs: Long,
        prev: Sample,
        nowMs: Long,
        outputting: Boolean? = null,
    ): Sample {
        if (!playing) {
            // 暂停：只更新位置，**不动** lastPlayingAtMs —— 它表示「最后一次在放」，
            // 恢复播放时要靠它算「停了多久」。
            return prev.copy(lastPositionMs = positionMs, audible = false)
        }
        if (outputting == true) {
            // 音频输出是地面真相：系统说了它正在出声，就不用再看位置快照。
            // 真车（4276）网易云切歌后 position 会停在旧值好几秒，位置法会误判。
            return Sample(
                lastPositionMs = positionMs,
                lastPlayingAtMs = nowMs,
                lastAdvanceAtMs = nowMs,
                audible = true,
            )
        }
        if (outputting == false) {
            // 系统说它没在出声：即使会话自称 PLAYING、位置还在动，也判没在放
            // （例如静音播放、投到别的设备）。
            return Sample(
                lastPositionMs = positionMs,
                lastPlayingAtMs = nowMs,
                lastAdvanceAtMs = prev.lastAdvanceAtMs,
                audible = false,
            )
        }
        if (positionMs < 0L) {
            // 位置未知：无从判断，按在放处理（宁可让真的在放的音源参与选择）。
            return prev.copy(
                lastPositionMs = positionMs,
                lastPlayingAtMs = nowMs,
                lastAdvanceAtMs = nowMs,
                audible = true,
            )
        }
        val resumed = prev.lastPlayingAtMs == 0L || nowMs - prev.lastPlayingAtMs > RESUME_GAP_MS
        val advanced = prev.lastPositionMs >= 0L && positionMs > prev.lastPositionMs
        val advanceAt = if (resumed || advanced) nowMs else prev.lastAdvanceAtMs
        return Sample(
            lastPositionMs = positionMs,
            lastPlayingAtMs = nowMs,
            lastAdvanceAtMs = advanceAt,
            audible = nowMs - advanceAt <= WINDOW_MS,
        )
    }
}
