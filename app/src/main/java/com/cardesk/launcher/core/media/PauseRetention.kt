package com.cardesk.launcher.core.media

/**
 * 「暂停态元数据保留」的纯判定（`MusicSessionTracker.Record`）。
 *
 * 抽成纯对象是为了能单测 —— 这条规则决定了真机上
 * 「暂停一下歌名和歌词就全没了」这个现象会不会复现：
 *
 * - 候选选择用的是 `effectiveMetadata()`（暂停时保留的那份元数据），
 *   而不是会话此刻上报的原始元数据；
 * - 所以暂停后即使会话把元数据撤了，这个会话**仍然**算「有歌」，
 *   不会被踢出候选 → 界面不会被清空。
 *
 * ## 两条口径（第六十七轮）
 *
 * ### 1. 白名单从「车机多媒体」扩到「所有已知音乐 App」
 *
 * 原来只认 `com.tencent.wecarflow` / `com.ecarx.multimedia`。
 * 于是用户拿 **QQ音乐车机版 / 网易云 / 酷狗 / 酷我 / 汽水** 播歌时，
 * 暂停一下就掉元数据 → 歌名、歌词一起没 → 再按播放也没得恢复。
 *
 * 白名单现在由 [MediaSources] 的包表推导（**单一事实来源**），不再手抄一份。
 *
 * ### 2. **用户自己按了暂停 → 一定保留**（不限包）
 *
 * 白名单只管「**不是**用户按的暂停」（比如车机 App 自己在暂停时撤元数据）。
 * 只要是**用户按下暂停按钮**造成的暂停，那次暂停是我们自己按出来的，
 * 歌名 / 歌词必须留着 —— 否则「按暂停就清屏」在任何 App 上都会复现。
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

    /**
     * 「已知会在暂停时撤掉元数据」的包（**白名单**）。
     *
     * 由 [MediaSources] 的包表推导，避免两处各抄一份、改了一处忘了另一处。
     */
    private val PAUSE_METADATA_PACKAGES: Set<String> = buildSet {
        // 车机多媒体（爱趣听 / 原厂多媒体）
        addAll(MediaSources.MULTIMEDIA_CANDIDATES)
        // 蓝牙 / USB / 系统音乐
        addAll(MediaSources.MEDIA_CANDIDATES)
        // 固定来源：QQ音乐车机版 / 网易云 / 酷狗 / 酷我 / 汽水
        addAll(MediaSources.PRIMARY_PACKAGES.values)
        // 酷我的 clone 变体（来源表里只有原包在首选表里）
        add("cn.kuwo.kwmusiccarclone")
    }

    /**
     * 这个包是不是「已知会在暂停时撤掉元数据」的那一类。
     *
     * 注意：这是**兜底白名单**，不是保留的前提条件 ——
     * 用户自己按的暂停由 [canRetainPause] 直接放行。
     */
    fun isPauseMetadataPackage(pkg: String?): Boolean {
        val p = pkg?.trim()?.lowercase() ?: return false
        if (p.isEmpty()) return false
        return PAUSE_METADATA_PACKAGES.contains(p)
    }

    /**
     * 这一次的状态变化之后，暂停态的元数据还留不留。
     *
     * @param pkg 会话所属包
     * @param state 当前播放状态
     * @param userPauseRequested 用户是否**主动按过暂停**
     */
    fun canRetainPause(pkg: String?, state: Int?, userPauseRequested: Boolean): Boolean {
        if (state == null) return false
        // —— 用户自己按了暂停：这次暂停是我们按出来的，歌名 / 歌词一定要留
        if (userPauseRequested) {
            return state == STATE_PAUSED || state == STATE_STOPPED || isActive(state)
        }
        // —— 不是用户按的：只有「已知会撤元数据」的包才留
        if (!isPauseMetadataPackage(pkg)) return false
        return state == STATE_PAUSED
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
     * 一次状态变化之后的判定。
     *
     * @param hasMetadata 当前是否还有可用元数据（决定恢复到「活跃」态时要不要重新记住）
     * @param hasPausedMetadata 暂停时保留的那份还在不在
     */
    fun onStateChanged(
        state: Int?,
        userPauseRequested: Boolean,
        hasMetadata: Boolean,
        hasPausedMetadata: Boolean = false,
    ): StateResult {
        if (state == null || state == STATE_NONE || state == STATE_ERROR ||
            (state == STATE_STOPPED && !userPauseRequested)
        ) {
            return StateResult(keepPausedMetadata = false, userPauseRequested = false)
        }
        if (isActive(state)) {
            // 恢复播放时车机 App 常常**先报状态、后报元数据**。
            // 那一瞬要是把暂停时保留的那份也丢了，歌名 / 歌词会空一下。
            // 所以：有新元数据就换新的，没有就继续用暂停时留的那份。
            return StateResult(
                keepPausedMetadata = hasMetadata || hasPausedMetadata,
                userPauseRequested = userPauseRequested && !hasMetadata,
            )
        }
        return StateResult(keepPausedMetadata = true, userPauseRequested = userPauseRequested)
    }
}
