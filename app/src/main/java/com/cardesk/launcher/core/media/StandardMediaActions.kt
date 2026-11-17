package com.cardesk.launcher.core.media

import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * **标准 MediaSession 通道**（第一九六轮）：未适配播放器的**通用兜底**。
 *
 * ## 兜底的依据：Android 官方定义了两个标准入口
 *
 * | 动作 | 标准入口 | 播放器的回调 |
 * |---|---|---|
 * | 收藏 | `setRating(心形评分)` | `MediaSession.Callback.onSetRating` |
 * | 播放模式 | `sendCustomAction(动作名, 参数)` | `MediaSession.Callback.onCustomAction` |
 *
 * 任何播放器只要实现了这两个回调，**第三方就能驱动它**，
 * 不需要桌面为每家单独逆向一套私有协议。
 *
 * **汽水音乐车机版就是这种**（逆向结论见 [MediaActions] 的能力表注释）：
 * 它的 `CoreRemoteControlListener.onSetRating()` 收到心形评分就调
 * `handleCollectOrUnCollectCommand(hasHeart)` → 收藏真的生效。
 *
 * ## 兜底的能力**探测**（不是无脑乱发）
 *
 * 桌面靠**会话自己发布的事实**判断支不支持：
 *
 * | 能力 | 判据 |
 * |---|---|
 * | 收藏 | 会话 metadata 里**有**心形评分（`METADATA_KEY_USER_RATING`）→ 说明它用标准收藏语义 |
 * | 播放模式 | 会话 `PlaybackState` 的 `repeatMode` / `shuffleMode` **有被设置** → 说明它用标准模式语义 |
 *
 * 探测不到就不亮按钮 —— 亮一个「点了没反应」的按钮比没有更糟。
 * 判定逻辑在 [MediaActions.capsOf]（纯逻辑，可单测）。
 *
 * ## 播放模式的下发参数（两套表达一起给）
 *
 * `sendCustomAction` 没有官方约定的参数格式，所以**同时给两套**：
 *
 * - `play_mode`：**通用编码**（顺序=1 / 单曲=2 / 随机=3）；
 * - `repeat_mode` / `shuffle_mode`：**Android 标准值**
 *   （`PlaybackState.REPEAT_MODE_*` / `SHUFFLE_MODE_*`）——
 *   认这套的播放器直接能用。
 */
object StandardMediaActions {

    /** 通用播放模式：顺序播放。 */
    const val PLAY_MODE_REPEAT_ALL = MediaActions.GenericPlayMode.REPEAT_ALL

    /** 通用播放模式：单曲循环。 */
    const val PLAY_MODE_REPEAT_ONE = MediaActions.GenericPlayMode.REPEAT_ONE

    /** 通用播放模式：随机播放。 */
    const val PLAY_MODE_SHUFFLE = MediaActions.GenericPlayMode.SHUFFLE

    /** 通用自定义动作名：切播放模式。 */
    const val ACTION_SET_PLAY_MODE = "com.cardesk.action.SET_PLAY_MODE"

    /** extra：通用播放模式编码。 */
    const val EXTRA_PLAY_MODE = "play_mode"

    /** extra：Android 标准重复模式（`PlaybackState.REPEAT_MODE_*`）。 */
    const val EXTRA_REPEAT_MODE = "repeat_mode"

    /** extra：Android 标准随机模式（`PlaybackState.SHUFFLE_MODE_*`）。 */
    const val EXTRA_SHUFFLE_MODE = "shuffle_mode"

    /**
     * 收藏 / 取消收藏当前歌曲（**标准通道**）。
     *
     * 走 `setRating(心形评分)` —— 播放器实现 `onSetRating` 就能收。
     *
     * @param context 上下文（取真实的调用方包名，framework 拿它做权限检查）
     * @param controller 当前会话的控制器
     * @param favorited true = 收藏，false = 取消收藏
     * @return 是否真的发出去了
     */
    fun sendFavorite(context: Context, controller: MediaController?, favorited: Boolean): Boolean {
        val c = controller ?: run {
            Logx.i(LogTags.MEDIA, "标准通道下发收藏失败：没有会话控制器", "favorited" to favorited)
            return false
        }
        return SessionProxy.sendRating(context, c, Rating.newHeartRating(favorited))
    }

    /**
     * 切播放模式（**标准通道**）。
     *
     * 走 `sendCustomAction(ACTION_SET_PLAY_MODE, …)`。
     *
     * @param context 上下文
     * @param controller 当前会话的控制器
     * @param mode 目标模式
     * @return 是否真的发出去了
     */
    fun sendPlayMode(context: Context, controller: MediaController?, mode: MediaActions.PlayMode): Boolean {
        val c = controller ?: return false
        val extras = Bundle().apply {
            putInt(EXTRA_PLAY_MODE, genericPlayModeCode(mode))
            putInt(EXTRA_REPEAT_MODE, androidRepeatMode(mode))
            putInt(EXTRA_SHUFFLE_MODE, androidShuffleMode(mode))
        }
        return SessionProxy.sendCustomAction(context, c, ACTION_SET_PLAY_MODE, extras)
    }

    /**
     * 通用播放模式编码（纯逻辑，可单测）。
     *
     * | 模式 | 编码 |
     * |---|---|
     * | 顺序播放 | 1 |
     * | 单曲循环 | 2 |
     * | 随机播放 | 3 |
     *
     * 判定逻辑放在 [MediaActions.genericPlayModeCode]（纯 Kotlin，可单测）。
     */
    fun genericPlayModeCode(mode: MediaActions.PlayMode): Int =
        MediaActions.genericPlayModeCode(mode)

    /** 模式 → Android 标准重复模式（`PlaybackState.REPEAT_MODE_*`）。 */
    fun androidRepeatMode(mode: MediaActions.PlayMode): Int = MediaActions.androidRepeatMode(mode)

    /** 模式 → Android 标准随机模式（`PlaybackState.SHUFFLE_MODE_*`）。 */
    fun androidShuffleMode(mode: MediaActions.PlayMode): Int = MediaActions.androidShuffleMode(mode)

    /**
     * 从 `PlaybackState` 里读**重复模式**（反射）。
     *
     * ## 为什么用反射
     *
     * `PlaybackState.getRepeatMode()` / `getShuffleMode()` 是 **API 26** 才有的，
     * 本工程的 `compileSdk` 用的 android.jar 是**裁剪版** → 这两个 getter 不在里面，
     * 直接写 `state.repeatMode` **编译不过**。
     *
     * → 反射调用，运行时能拿到就用（API 26+ 的车机都有）。
     *
     * @param state 当前会话的播放状态（null = 没有会话）
     * @return `PlaybackState.REPEAT_MODE_*`；读不到返回 [MediaActions.RepeatMode.NONE]
     */
    fun repeatModeOf(state: PlaybackState?): Int {
        val s = state ?: return MediaActions.RepeatMode.NONE
        val v = runCatching {
            PlaybackState::class.java.getMethod("getRepeatMode").invoke(s) as? Int
        }.getOrNull()
        return v ?: MediaActions.RepeatMode.NONE
    }

    /**
     * 从 `PlaybackState` 里读**随机模式**（反射，原因同 [repeatModeOf]）。
     *
     * @param state 当前会话的播放状态（null = 没有会话）
     * @return `PlaybackState.SHUFFLE_MODE_*`；读不到返回 [MediaActions.ShuffleMode.NONE]
     */
    fun shuffleModeOf(state: PlaybackState?): Int {
        val s = state ?: return MediaActions.ShuffleMode.NONE
        val v = runCatching {
            PlaybackState::class.java.getMethod("getShuffleMode").invoke(s) as? Int
        }.getOrNull()
        return v ?: MediaActions.ShuffleMode.NONE
    }

    /**
     * 从会话元数据里读**收藏态**（标准通道，纯逻辑，可单测）。
     *
     * 播放器把收藏态放在 `METADATA_KEY_USER_RATING` 的**心形评分**里。
     *
     * @param metadata 当前会话的元数据（null = 没有会话）
     * @return true = 已收藏；false = 未收藏；**null = 不知道**
     *   （没有这个评分项，或者评分类型不是心形）
     */
    fun favoriteHeart(metadata: MediaMetadata?): Boolean? {
        val rating = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING) ?: return null
        if (rating.ratingStyle != Rating.RATING_HEART) return null
        return rating.hasHeart()
    }

    /**
     * 从 `PlaybackState` 的模式字段读**播放模式**（标准通道）。
     *
     * 判定逻辑放在 [MediaActions.playModeFromState]（纯逻辑，可单测）。
     *
     * @param repeatMode `PlaybackState.getRepeatMode()`
     * @param shuffleMode `PlaybackState.getShuffleMode()`
     * @return 对应的模式；读不出来返回 null
     */
    fun playModeFromState(repeatMode: Int, shuffleMode: Int): MediaActions.PlayMode? =
        MediaActions.playModeFromState(repeatMode, shuffleMode)

    /**
     * 这个会话**有没有暴露**播放模式（能力探测用）。
     *
     * 判定逻辑放在 [MediaActions.exposesPlayMode]（纯逻辑，可单测）。
     *
     * @param repeatMode `PlaybackState.getRepeatMode()`
     * @param shuffleMode `PlaybackState.getShuffleMode()`
     */
    fun exposesPlayMode(repeatMode: Int, shuffleMode: Int): Boolean =
        MediaActions.exposesPlayMode(repeatMode, shuffleMode)
}
