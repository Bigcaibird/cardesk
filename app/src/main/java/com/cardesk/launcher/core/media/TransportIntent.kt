package com.cardesk.launcher.core.media

/**
 * 「播放 / 暂停」指令的**幂等决策**（纯逻辑，不依赖 Android，可直接 JVM 单测）。
 *
 * ## 为什么要有这个文件（第八十七轮）
 *
 * 参考 UNI `MediaAdapter.setPlaying(boolean)`：UNI 的播放键**不是**「切换」，
 * 而是「把播放态设成我想要的值」——
 *
 * ```java
 * void setPlaying(boolean play) {
 *     boolean playing = state != null && state.getState() == STATE_PLAYING;
 *     if (play != playing) command("toggle-play");
 * }
 * ```
 *
 * 差别很关键。cardesk 以前的 [MusicSessionTracker.playPause] 是「每次无条件按当前快照翻转」，
 * 于是三件事会翻车：
 *
 * 1. **连点两次**：第一次暂停（会话还没把状态报上来）→ 第二次又读到「在放」→ 再暂停一次，
 *    部分播放器把第二次当成「切换」→ 歌又播起来了（用户看到「按暂停反而开始放」）。
 * 2. **Dock / 仪表并发**：全局 Dock 和仪表盘各按一次，等于发了两次指令。
 * 3. **状态已满足还硬发**：界面显示在放、会话也报在放，此时「设成在放」根本不需要下发。
 *
 * 幂等的口径：**只有「期望 ≠ 实测」且「该播放器声明了这个动作」时才下发一次**。
 * 期望状态与实际一致就什么都不做（`already-satisfied`）。
 *
 * ## 为什么不直接用 `android.media.session.PlaybackState`
 *
 * 这个文件要能在 JVM 单测里跑（`app/src/test/.../core/media`），而 Android SDK 的类在
 * 单测里是空壳。所以状态码 / action 位都在本文件里按 **AOSP 数值** 重新声明一遍，
 * 注释里标了出处 —— 数值必须与 `android.media.session.PlaybackState` 保持一致。
 */
object TransportIntent {

    // ---------------------------------------------------------------- 状态码（AOSP 数值）

    /** `PlaybackState.STATE_NONE`。 */
    const val STATE_NONE = 0

    /** `PlaybackState.STATE_STOPPED`。 */
    const val STATE_STOPPED = 1

    /** `PlaybackState.STATE_PAUSED`。 */
    const val STATE_PAUSED = 2

    /** `PlaybackState.STATE_PLAYING`。 */
    const val STATE_PLAYING = 3

    /** `PlaybackState.STATE_FAST_FORWARDING`。 */
    const val STATE_FAST_FORWARDING = 4

    /** `PlaybackState.STATE_REWINDING`。 */
    const val STATE_REWINDING = 5

    /** `PlaybackState.STATE_BUFFERING`。 */
    const val STATE_BUFFERING = 6

    /** `PlaybackState.STATE_ERROR`。 */
    const val STATE_ERROR = 7

    /** `PlaybackState.STATE_CONNECTING`。 */
    const val STATE_CONNECTING = 8

    /** `PlaybackState.STATE_SKIPPING_TO_NEXT`。 */
    const val STATE_SKIPPING_TO_NEXT = 9

    /** `PlaybackState.STATE_SKIPPING_TO_PREVIOUS`。 */
    const val STATE_SKIPPING_TO_PREVIOUS = 10

    /** `PlaybackState.STATE_JUMPING_TO`（API 31+，这里只用于「不是播放」的判定）。 */
    const val STATE_JUMPING_TO = 11

    // ---------------------------------------------------------------- action 位（AOSP 数值）

    /** `PlaybackState.ACTION_PLAY`（1 << 2）。 */
    const val ACTION_PLAY = 1L shl 2

    /** `PlaybackState.ACTION_PAUSE`（1 << 1）。 */
    const val ACTION_PAUSE = 1L shl 1

    /** `PlaybackState.ACTION_PLAY_PAUSE`（1 << 9）。 */
    const val ACTION_PLAY_PAUSE = 1L shl 9

    /** `PlaybackState.ACTION_STOP`（1 << 0）。 */
    const val ACTION_STOP = 1L shl 0

    /** `PlaybackState.ACTION_SKIP_TO_NEXT`（1 << 5）。 */
    const val ACTION_SKIP_TO_NEXT = 1L shl 5

    /** `PlaybackState.ACTION_SKIP_TO_PREVIOUS`（1 << 4）。 */
    const val ACTION_SKIP_TO_PREVIOUS = 1L shl 4

    /** `PlaybackState.ACTION_SEEK_TO`（1 << 8）。 */
    const val ACTION_SEEK_TO = 1L shl 8

    /**
     * 连点仲裁窗口（毫秒）。
     *
     * 800ms 内连点同一颗播放键，判定为「手抖 / 重复触摸」：
     * 第二次**不再翻转**，沿用第一次的目标，避免「按暂停又弹回播放」。
     * 超过窗口的第二次点击是明确的用户意图（要恢复成原来的状态），正常翻转。
     */
    const val TOGGLE_WINDOW_MS = 800L

    /** 决策原因：期望状态已经满足，不需要下发。 */
    const val REASON_ALREADY_SATISFIED = "already-satisfied"

    /** 决策原因：播放器没声明这个动作（缺 action 位），下了也白下。 */
    const val REASON_NO_ACTION = "no-action"

    /** 决策原因：需要下发「播放」。 */
    const val REASON_SEND_PLAY = "send-play"

    /** 决策原因：需要下发「暂停」。 */
    const val REASON_SEND_PAUSE = "send-pause"

    /**
     * 一次播放/暂停决策的结果。
     *
     * @param send 是否真的要下发控制命令（false = 什么都不用做）
     * @param desiredPlaying 期望的播放态（无论是否下发，都是调用方的目标态）
     * @param reason 决策原因（落日志 / 单测断言用，取值见本对象的 `REASON_*`）
     */
    data class Decision(
        val send: Boolean,
        val desiredPlaying: Boolean,
        val reason: String,
    )

    /**
     * 当前会话的实测状态算不算「在放」。
     *
     * `BUFFERING` 也算 —— 缓冲中按播放键的用户意图显然是「暂停」，
     * 以前只看 `PLAYING` 会把缓冲中误判成暂停，按下反而继续播放。
     */
    fun isObservedPlaying(observedState: Int): Boolean =
        observedState == STATE_PLAYING || observedState == STATE_BUFFERING

    /**
     * 幂等决策：要不要为「期望的播放态」下发一次控制命令。
     *
     * 规则（顺序即优先级）：
     * 1. **已满足** —— `PLAYING`/`BUFFERING` 且期望播放，或非播放且期望暂停 → 不下发；
     * 2. **缺 action 位** —— 期望需要 `PLAY`（或 `PLAY_PAUSE`）而播放器没声明 → 不下发
     *    （网易云这类播放器不 `setActions()`，调用方要先把能用的 action 补进来，
     *    见 [MediaButtonTransport.effectiveActions]）；
     * 3. 其余 → 下发一次。
     *
     * @param observedState 会话此刻上报的 `PlaybackState.state`（AOSP 数值）
     * @param desiredPlaying 期望播放态：true = 播放，false = 暂停
     * @param actions 会话声明的 action 位（AOSP 数值，可用 `|` 拼）
     */
    fun decide(observedState: Int, desiredPlaying: Boolean, actions: Long): Decision {
        if (isObservedPlaying(observedState) == desiredPlaying) {
            return Decision(send = false, desiredPlaying = desiredPlaying, reason = REASON_ALREADY_SATISFIED)
        }
        // 期望播放 → 需要 PLAY 能力；期望暂停 → 需要 PAUSE 能力。
        // PLAY_PAUSE 两个方向都算（它就是一个「切换」能力）。
        val needed = if (desiredPlaying) ACTION_PLAY else ACTION_PAUSE
        if ((actions and (needed or ACTION_PLAY_PAUSE)) == 0L) {
            return Decision(send = false, desiredPlaying = desiredPlaying, reason = REASON_NO_ACTION)
        }
        return Decision(
            send = true,
            desiredPlaying = desiredPlaying,
            reason = if (desiredPlaying) REASON_SEND_PLAY else REASON_SEND_PAUSE,
        )
    }

    /**
     * 连点仲裁：一次「按播放/暂停键」最终应该把播放态设成什么。
     *
     * - **窗口内**（距上次真正下发不到 [windowMs]）：判定为重复触摸，**不翻转**，
     *   沿用当前（已被用户上一次意图修正过的）播放态 → 期望与刚下发的一致，
     *   [decide] 会给出 `already-satisfied`，不会把暂停又点回播放。
     * - **窗口外**：以实测为准，翻转一次。
     *
     * 为什么以「上次**下发**时刻」而不是「上次按键时刻」为锚：
     * 只有真正下发过，才有「命令还在路上、实测状态可能还没跟上」这个问题。
     * 没下发的重复点击不该延长窗口，否则连续轻点会让播放键失效。
     *
     * @param currentPlaying 当前（已按用户意图修正过的）播放态，通常是
     *   `resolvePlaying(...)` 的结果；窗口外它就是实测值
     * @param userIntentAtMs 上一次真正下发播放/暂停指令的时刻（`0` = 从没下发过）
     * @param nowMs 当前时刻（毫秒，与 [userIntentAtMs] 同一时钟：`System.currentTimeMillis()`）
     * @param windowMs 连点仲裁窗口，默认 [TOGGLE_WINDOW_MS]
     */
    fun nextToggleTarget(
        currentPlaying: Boolean,
        userIntentAtMs: Long,
        nowMs: Long,
        windowMs: Long = TOGGLE_WINDOW_MS,
    ): Boolean {
        val withinWindow = userIntentAtMs > 0L && nowMs >= userIntentAtMs && nowMs - userIntentAtMs <= windowMs
        return if (withinWindow) currentPlaying else !currentPlaying
    }

    /**
     * 决定是否真的下发 —— [Decision] 之上的最后一层闸门。
     *
     * ## 为什么还需要 [force]（第八十七轮 · 连点与意图窗口叠加）
     *
     * [decide] 只看**实测** `PlaybackState`。但上一轮下发（比如暂停）可能还在路上：
     * 实测仍报 `PLAYING`，而用户已经拨向播放。此时 `decide(PLAYING, desired = true)`
     * 会判 `already-satisfied` → 不下发；等那条暂停落地，最终停在暂停 ——
     * 与用户最后一次操作相反（pause/pause/play 三次连点最容易复现）。
     *
     * 所以「上一次命令未确认 + 目标方向变了」时允许越过 `already-satisfied`。
     * 但**绝不越过 `no-action`**：播放器没声明能力位时照样静默返回，
     * 否则又回到「无条件发指令 / 发全局按键」的老路（会误伤别的 App）。
     *
     * @param decision [decide] 的结论
     * @param force 调用方确认「上次命令未确认且目标方向已变」
     */
    fun shouldDispatch(decision: Decision, force: Boolean): Boolean =
        decision.send || (force && decision.reason == REASON_ALREADY_SATISFIED)
}
