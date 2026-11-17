package com.cardesk.launcher.core.media

import android.content.Context
import android.media.session.MediaController
import android.os.Bundle
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 网易云车机版（`com.netease.cloudmusic.iot`）的**收藏 / 播放模式**通道（第一九五轮）。
 *
 * ## 为什么走「标准 MediaSession」而不是它的私有 JSK
 *
 * 网易云有一套自己的车机协议（JSK / `IIotServer`），看着像是唯一入口，
 * 但那条路**第三方根本接不上**：
 *
 * - `IIotServer` 由 `CarCommonProvider` 注册进 `ServiceFacade`，
 *   而 `ServiceFacade` 是网易云**进程内**的静态 `Map`
 *   （`com/netease/cloudmusic/common/ServiceFacade.java`：
 *   `private static Map<String, Object> sServiceMap = new HashMap()`）——
 *   别的进程读不到；
 * - 网易云清单里只导出两个 Service（`PlayService` / `AutomotiveService`），
 *   `IIotServer` 不在其中，也没有对应的 AIDL 入口。
 *
 * 真正能用的是它 **MediaSession 回调里的标准入口**
 * （反编译 `vc/g$b`，`com.netease.cloudmusic` 的会话回调）：
 *
 * ```java
 * @Override public void onSetRating(RatingCompat rating) {
 *     if (rating.getRatingStyle() == 1) {            // RATING_HEART
 *         Bundle b = new Bundle();
 *         b.putBoolean("is_star", rating.hasHeart());
 *         g.f24419a.F("star_action", b);             // → 收藏 / 取消收藏
 *     }
 * }
 *
 * @Override public void onCustomAction(String action, Bundle extras) {
 *     g.f24419a.F(action, extras);                    // → "play_mode_action" 等
 * }
 * ```
 *
 * `g.F(action, extras)` 是动作分发器（`vc/g.java`），
 * 它按动作名找处理器（`yc/p.java` 是这张表）：
 *
 * | 动作名 | 处理器 | 作用 |
 * |---|---|---|
 * | `"star_action"` | `yc/s0` | 收藏 / 取消收藏（extra `is_star`） |
 * | `"play_mode_action"` | `yc/y` | 切播放模式（extra `play_mode` = 1/2/3） |
 * | `"get_play_mode_action"` | `yc/i` | 读当前播放模式（结果用 session event 回） |
 *
 * → 结论：**收藏和播放模式都是标准会话能力**，第三方拿会话控制器就能读写。
 *
 * ## 收藏态怎么读回来
 *
 * 网易云把收藏态放在 **metadata 的心形评分**里
 * （`vc/g.java` 的 `T(boolean star)` / `N(...)`）：
 *
 * ```java
 * builder.putRating(MediaMetadataCompat.METADATA_KEY_USER_RATING,
 *                   RatingCompat.newHeartRating(star));
 * ```
 *
 * 模拟器实测 `dumpsys media_session` 里网易云那一条会话显示 `rating type=1`
 * （`RATING_HEART`）—— 第三方用 `metadata.getRating(METADATA_KEY_USER_RATING).hasHeart()`
 * 就能读出来。
 *
 * ## 播放模式怎么读回来
 *
 * 发 `"get_play_mode_action"`，网易云会用 **session event** 回一条
 * （`yc/i.java`：`mediaSession.sendSessionEvent(action, bundle)`），
 * bundle 里是 `play_mode`（1/2/3，映射见 [MediaActions.NeteasePlayMode]）。
 * 监听 `MediaController.Callback.onSessionEvent` 就能收到。
 *
 * ## 收藏为什么也走 `sendCustomAction`（而不是 `setRating`）
 *
 * 桌面原本按「标准语义」用 `setRating(心形评分)`。
 * 设备实测（API 28）发现**这条路根本走不通**：
 *
 * - `MediaController` 上既没有 `setRating` 也没有 `sendCustomAction`
 *   （API 29 才把它们移出 framework），公开的只有 `sendCommand`；
 * - `sendCommand` **不能**替代 —— androidx 的
 *   `MediaSessionCompat.Callback` 把 `onCommand` 与 `onCustomAction`
 *   当成**两个独立方法**，`onCommand` 不会自动转到 `onCustomAction`；
 * - 会话控制接口 `ISessionController` 上**有 `sendCustomAction`、但没有 `setRating`**。
 *
 * → 所以收藏改走 `sendCustomAction("star_action", {is_star})` ——
 * 这跟网易云 `onSetRating` 内部做的事**完全一样**
 * （`onSetRating` 就是 `g.F("star_action", {is_star})`），
 * 效果等价，而且少依赖一个 framework 接口。
 *
 * ## 怎么拿到会话控制代理（隐藏 API）
 *
 * 设备实测（API 28）的结论：
 *
 * | 找法 | 结果 |
 * |---|---|
 * | `MediaController.sendCustomAction` / `setRating` | **不存在** |
 * | `MediaController` 的 `mImpl` 字段 | **不存在** |
 * | `MediaController$TransportControls.mImpl` | **不存在** |
 * | `MediaSession.Token.getMediaController()` | **不存在** |
 * | **`MediaSession.Token.mBinder`** | ✅ **它本身就是代理** |
 * | `MediaController.mSessionBinder` | ✅ 同一个代理的另一个入口 |
 *
 * `Token.mBinder` 的运行时类型是
 * `android.media.session.ISessionController$Stub$Proxy` ——
 * **已经是现成的代理对象**，直接反射调它的方法即可。
 *
 * ## AIDL 代理方法的隐藏参数
 *
 * `ISessionController$Stub$Proxy` 的方法签名带 **2 个隐藏参数**
 * （`sendCustomAction` 是 4 个：action / extras / flags / token），
 * 而接口 `ISessionController` 上声明的是 2 个。
 *
 * → 两种都找，**优先用参数少的那个**（少传隐藏参数更稳）。
 *
 * ## 第一九六轮：代理层抽到公共文件
 *
 * 这套「找代理 → 找方法 → 按签名拼参数」的逻辑**不是网易云专有的** ——
 * 通用兜底通道（[StandardMediaActions]）也要用同一套
 * → 抽到 [SessionProxy] 里，本文件只留网易云自己的动作名与编码。
 */
object NeteaseMediaActions {

    /** 动作名：收藏 / 取消收藏（`yc/s0`）。 */
    const val ACTION_STAR = "star_action"

    /** 动作名：切播放模式（`yc/y`）。 */
    const val ACTION_PLAY_MODE = "play_mode_action"

    /** 动作名：读当前播放模式（`yc/i`）。 */
    const val ACTION_GET_PLAY_MODE = "get_play_mode_action"

    /** extra：收藏与否（`is_star` = true 表示收藏）。 */
    const val EXTRA_IS_STAR = "is_star"

    /** extra：播放模式编码（1 / 2 / 3）。 */
    const val EXTRA_PLAY_MODE = "play_mode"

    /**
     * 收藏 / 取消收藏当前歌曲。
     *
     * 走 `sendCustomAction("star_action", {is_star})` ——
     * 跟网易云 `onSetRating` 内部做的事**完全一样**
     * （`onSetRating` 就是 `g.F("star_action", {is_star})`），
     * 但不依赖 framework 的 `setRating`（设备上压根没有）。
     *
     * @param context 上下文
     * @param controller 当前会话的控制器（null = 没有会话，发不了）
     * @param favorited true = 收藏，false = 取消收藏
     * @return 是否真的发出去了
     */
    fun sendFavorite(context: Context, controller: MediaController?, favorited: Boolean): Boolean {
        val c = controller ?: run {
            Logx.i(LogTags.MEDIA, "网易云下发收藏失败：没有会话控制器", "favorited" to favorited)
            return false
        }
        val extras = Bundle().apply { putBoolean(EXTRA_IS_STAR, favorited) }
        return SessionProxy.sendCustomAction(context, c, ACTION_STAR, extras)
    }

    /**
     * 切播放模式。
     *
     * 走 `sendCustomAction("play_mode_action", {play_mode: n})`。
     *
     * @param context 上下文
     * @param controller 当前会话的控制器
     * @param mode 目标模式
     * @return 是否真的发出去了
     */
    fun sendPlayMode(context: Context, controller: MediaController?, mode: MediaActions.PlayMode): Boolean {
        val c = controller ?: return false
        val code = MediaActions.neteasePlayModeCode(mode)
        val extras = Bundle().apply { putInt(EXTRA_PLAY_MODE, code) }
        return SessionProxy.sendCustomAction(context, c, ACTION_PLAY_MODE, extras)
    }

    /**
     * **问**网易云当前是什么播放模式。
     *
     * 结果**不是**直接返回的 —— `onCustomAction` 没有返回值通道，
     * 网易云是用 **session event** 回的
     * （`yc/i.java` → `mediaSession.sendSessionEvent("get_play_mode_action", {play_mode})`）。
     * 所以调用方要去 [MusicSessionTracker] 的会话事件回调里收。
     *
     * @param context 上下文
     * @return 是否真的发出去了
     */
    fun requestPlayMode(context: Context, controller: MediaController?): Boolean {
        val c = controller ?: return false
        return SessionProxy.sendCustomAction(context, c, ACTION_GET_PLAY_MODE, null)
    }
}
