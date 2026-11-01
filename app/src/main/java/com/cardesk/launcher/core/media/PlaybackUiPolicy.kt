package com.cardesk.launcher.core.media

/**
 * 播放态「给界面的口径」：会话报 PLAYING 时，界面什么时候算「在放」。
 *
 * ## 为什么需要这一层（第九十七轮）
 *
 * 车机音乐 App（com.tencent.wecarflow）在切流量/缓冲时，PlaybackState 一直是
 * PLAYING，但 `position` 会卡住好几秒不动。老的实现把「进度 2.5s 不动」直接当成
 * 暂停推给界面 —— 于是播放键图标在「播放/暂停」之间来回跳，用户看到的就是
 * 「播放暂停状态不断跳动」，实际歌一直在放。
 *
 * 现在的口径：
 * - 会话说 PLAYING → 界面就算在放；
 * - 只有在「PLAYING 且进度**长时间**（[STALL_UI_MS]）不动」时才改判成暂停
 *   （那是真的卡死 / 假播放，见第三十三轮的「显示播放却没声音」）；
 * - 进度短暂卡顿（缓冲、切码率）不再影响播放键。
 */
object PlaybackUiPolicy {

    /** 进度停滞多久才把界面改判成「暂停」。 */
    const val STALL_UI_MS = 6_000L

    /**
     * @param observedPlaying 会话 PlaybackState 是否为 PLAYING
     * @param stalledMs 已停滞的时长（毫秒；0 = 没有停滞）
     */
    fun uiPlaying(observedPlaying: Boolean, stalledMs: Long): Boolean =
        observedPlaying && stalledMs < STALL_UI_MS
}
