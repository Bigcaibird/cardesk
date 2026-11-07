package com.cardesk.launcher.cluster

/**
 * 仪表歌词面板要画的那一份**快照**。
 *
 * ## 为什么是快照而不是直接读 MusicController
 *
 * 投射窗口画在**另一块屏**上，它没有自己的媒体会话订阅通道。
 * 如果让面板自己去读会话、���自己算当前行，就是**两套时钟、两套算法**——
 * 桌面卡片和仪表上的歌词会在同一时刻显示不同的句子（这正是 UNI 那边
 * 踩过的坑：卡片读 LRC、面板读 KRC，同一首歌显示成两回事）。
 *
 * 所以这里定一份**不可变快照**：由服务在固定节奏上从
 * `MusicController` 取一次，三行文字 + 擦除比例一起冻结，
 * 面板每帧只读这份快照。
 *
 * ## 纯数据
 *
 * 这个类不碰任何 Android 类型，可以直接被 JVM 单测覆盖。
 */
data class ClusterLyricSnapshot(

    /** 歌名。 */
    val title: String = "",

    /** 歌手。 */
    val artist: String = "",

    /** 歌词来源说明（哪个 App / 哪个渠道）。 */
    val sourceLabel: String = "",

    /** 当前是否在播放。 */
    val playing: Boolean = false,

    /** 上一句（副歌词尺寸）。 */
    val previousText: String = "",

    /** 当前句（主歌词尺寸，带擦除高亮）。 */
    val currentText: String = "",

    /** 下一句（副歌词尺寸）。 */
    val nextText: String = "",

    /** 当前句的擦除比例 0..1。 */
    val wipe: Float = 0f,

    /** 歌词总行数（0 = 这首歌没有歌词）。 */
    val lyricLines: Int = 0,
) {

    /** 有没有可画的当前句。 */
    val hasLyrics: Boolean get() = currentText.isNotEmpty()

    /**
     * 面板上的**空态文案**。
     *
     * 三种空态要说三件不同的事，不能混成一句「没有歌词」：
     *
     * - 什么都没在播 → 「等待播放」；
     * - 在播但歌词还没到（联网取词要时间）→ 「正在获取歌词」；
     * - 暂停且确实没词 → 「当前音源未提供歌词」。
     *
     * 第一种和第二种说成「当前音源未提供歌词」是在**撒谎**——
     * 那种情况只持续一两帧，但用户会记住。
     */
    fun emptyLabel(): String = when {
        title.isEmpty() -> "等待播放"
        playing -> "正在获取歌词"
        else -> "当前音源未提供歌词"
    }

    /**
     * 状态页显示的一行说明（`已投射 · 仪表 · ID 1 · 网易云 64 行`）。
     */
    fun describe(): String = when {
        lyricLines > 0 -> (if (sourceLabel.isEmpty()) "歌词" else sourceLabel) + " " + lyricLines + " 行"
        title.isEmpty() -> "尚未拿到曲目"
        else -> "正在匹配歌词"
    }
}
