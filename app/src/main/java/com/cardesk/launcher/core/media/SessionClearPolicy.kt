package com.cardesk.launcher.core.media

/**
 * 「**会话消失**」时的清理范围（第126轮，纯逻辑，可单测）。
 *
 * ## 为什么要有这个文件
 *
 * QQ 音乐车机版**不发布可用的 MediaSession**（模拟器上 `dumpsys media_session`
 * 实测：`metadata: null` + `state=0` + 没有 `setCallback`），
 * 它的歌名 / 歌手 / 歌词**只能**来自它自己的三方广播（[QqBroadcast]）。
 *
 * 而会话追踪器是**每 2.5s 重扫一次**的：QQ 音乐那两个会话没有 metadata →
 * 选不中 → 每 2.5s 发一次 `onTrackChanged(null)` → 3s 后触发清空
 * （[MusicController] 的 `NULL_SESSION_CLEAR_MS`）。
 *
 * 如果清空时把**广播那份**也一起带走（老实现只有「会话」一个来源，
 * 清空 = 全清），那么每 3 秒卡片上的歌名和歌词就在「有」和「无」之间跳一次 ——
 * 和第 97 轮修掉的「播放/暂停状态不断跳动」是同一个毛病。
 *
 * 所以规则是：**广播那份只在它自己也没了的时候才清**。
 */
object SessionClearPolicy {

    /**
     * 清空会话之后的界面状态。
     *
     * @param track 界面上还显示的曲目（null = 回到「暂无播放」）
     * @param keepLyric 歌词还留着吗
     * @param keepCover 封面缓存还留着吗（留着就不用每 3 秒重取一次）
     */
    data class Result(
        val track: TrackInfo?,
        val keepLyric: Boolean,
        val keepCover: Boolean,
    )

    /**
     * 会话清空之后，界面该显示什么。
     *
     * @param broadcast 广播合成的曲目（null = 广播那份也没了 → 全清）
     * @param hasLyric 当前有没有歌词
     */
    fun afterSessionClear(broadcast: TrackInfo?, hasLyric: Boolean): Result =
        if (broadcast != null) {
            // 广播那份还在 → 歌名/歌词/封面全部保留，界面继续显示它。
            Result(track = broadcast, keepLyric = hasLyric, keepCover = true)
        } else {
            // 广播那份也没了 → 真的回到「暂无播放」。
            Result(track = null, keepLyric = false, keepCover = false)
        }
}
