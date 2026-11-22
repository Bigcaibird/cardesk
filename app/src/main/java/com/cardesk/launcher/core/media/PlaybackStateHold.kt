package com.cardesk.launcher.core.media

/**
 * 播放态「暂停迟滞」（第201轮）。
 *
 * ## 为什么需要
 *
 * 真机诊断包（2026-10-23，wecarflow）里会话**自己**每 ~4s 在 PLAYING / PAUSED
 * 之间翻一次，而进度完全不动：
 *
 * ```
 * 18:36:10 playing=true  pos=16044
 * 18:36:14 playing=false pos=16044
 * 18:36:26 playing=true  pos=16044
 * 18:36:33 playing=false pos=16044
 * ```
 *
 * 界面上的播放键就跟着一跳一跳。第97轮的 [PlaybackUiPolicy] 只处理
 * 「会话说 PLAYING、但进度停滞」，管不了「会话自己报 PAUSED」，于是
 * 停滞判据还会和会话抖动叠加，跳得更明显。
 *
 * ## 口径
 *
 * - **恢复播放立刻采纳**：用户按下播放必须马上有反馈；
 * - **转为暂停要连续 [pauseSettleMs] 都报暂停**才采纳 —— 缓冲 / 换码率期间的
 *   假暂停被吸收掉；
 * - 用户自己按的播放/暂停走 [force]，完全不受迟滞影响（乐观 UI 不能被它拖慢）。
 *
 * 纯逻辑、无 Android 依赖，可 JVM 单测。
 */
class PlaybackStateHold(
    private val pauseSettleMs: Long = DEFAULT_PAUSE_SETTLE_MS,
) {

    /** 当前对外展示的播放态；null = 还没采过样（第一次直接采纳）。 */
    private var shown: Boolean? = null

    /** 这一轮「暂停」是从什么时候开始的（0 = 还没开始）。 */
    private var pausedSince = 0L

    /**
     * 过一道迟滞。
     *
     * @param next 这一拍会话给的口径（已含停滞判据）
     * @param nowMs 单调时钟（`SystemClock.uptimeMillis()`）
     * @param immediate 用户刚操作过 / 会话刚切换：直接采纳，不等迟滞
     */
    fun hold(next: Boolean, nowMs: Long, immediate: Boolean = false): Boolean {
        val cur = shown
        if (cur == null || immediate) {
            shown = next
            pausedSince = 0L
            return next
        }
        if (next == cur) {
            // 一直在同一态：把「暂停起点」清掉，下次转暂停重新计时。
            pausedSince = 0L
            return cur
        }
        if (next) {
            // 恢复播放：立刻生效。
            shown = true
            pausedSince = 0L
            return true
        }
        if (pausedSince == 0L) pausedSince = nowMs
        if (nowMs - pausedSince >= pauseSettleMs) {
            shown = false
            pausedSince = 0L
        }
        return shown == true
    }

    /** 用户操作 / 换会话时把内部态钉死成 [value]（不走迟滞）。 */
    fun force(value: Boolean) {
        shown = value
        pausedSince = 0L
    }

    /** 下一次 [hold] 重新按第一拍采纳（换会话、清空媒体时用）。 */
    fun reset() {
        shown = null
        pausedSince = 0L
    }

    companion object {
        /** 转暂停要稳定这么久才认（真机上 ~4s 的假暂停抖动都能吸收）。 */
        const val DEFAULT_PAUSE_SETTLE_MS = 4_000L
    }
}
