package com.cardesk.launcher.core.media

import android.content.Context
import android.media.session.MediaController
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「收藏 / 播放模式」的**执行层**：按包名把指令分发给对应播放器的协议。
 *
 * ## 分发规则（第一九六轮：三层）
 *
 * | 层 | 包 | 收藏 | 播放模式 |
 * |---|---|---|---|
 * | ① 专用通道 | QQ 音乐车机版 | URI 协议 `m0=5/6` | URI 协议 `m0=101/103/105` |
 * | ① 专用通道 | 网易云车机版 | 标准会话 `star_action` | 标准会话 `play_mode_action` |
 * | ② **标准兜底** | 汽水音乐车机版 | 标准评分 `setRating(心形)` | ——（它没有对外通道）|
 * | ② **标准兜底** | **其它任何播放器** | 标准评分 `setRating(心形)` | 标准会话 `SET_PLAY_MODE` |
 *
 * ## 为什么要有第 ② 层
 *
 * 桌面不可能一家一家逆所有音乐 App 的私有协议 →
 * 只能靠 **Android 官方定义的标准会话入口**兜底：
 *
 * | 动作 | 标准入口 | 播放器的回调 |
 * |---|---|---|
 * | 收藏 | `setRating(心形评分)` | `MediaSession.Callback.onSetRating` |
 * | 播放模式 | `sendCustomAction(动作名, 参数)` | `MediaSession.Callback.onCustomAction` |
 *
 * **但不是无脑乱发** —— 能不能亮按钮由**运行时探测**决定
 * （见 [MediaActions.capsOf]）：探不到就隐藏，探到了才下发。
 *
 * ## 为什么要按包名分发
 *
 * QQ 音乐车机版和网易云**没有共同的协议**：
 *
 * - QQ 音乐车机版**不发布可用会话**（会话 `metadata: null` + `state=0`），
 *   只能走它自己那套第三方 URI 协议；
 * - 网易云反过来说，**只有会话**（`onSetRating` / `onCustomAction`），
 *   它的私有 JSK 通道第三方接不上（见 [NeteaseMediaActions] 的 KDoc）。
 *
 * → 没有「统一标准 API」可抄，只能按包名分流。
 *
 * ## 失败怎么办
 *
 * 下发失败**不抛异常**，只记日志并返回 false ——
 * 音乐卡上的按钮点不动不该让桌面崩掉。
 */
object MediaActionsDispatcher {

    /**
     * 下发「收藏 / 取消收藏」。
     *
     * @param context 上下文（QQ 协议要用来发 Intent）
     * @param controller 当前会话控制器（标准通道要用；QQ 协议不用）
     * @param packageName 当前播放器包名
     * @param favorited true = 收藏，false = 取消收藏
     * @param caps 当前播放器的**能力**（探测结果）——
     *   不支持收藏就直接不下发（别往不存在的通道上发）
     * @return 是否真的下发了（false = 这个播放器不支持，或下发失败）
     */
    fun sendFavorite(
        context: Context,
        controller: MediaController?,
        packageName: String?,
        favorited: Boolean,
        caps: MediaActions.Caps,
    ): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (!caps.favorite) {
            Logx.i(
                LogTags.MEDIA,
                "收藏：当前播放器不支持",
                "pkg" to pkg,
            )
            return false
        }
        val ok = when (pkg) {
            QqBroadcast.PACKAGE -> QqBroadcast.sendFavorite(context, favorited)
            MediaButtonTransport.NETEASE_PACKAGE ->
                NeteaseMediaActions.sendFavorite(context, controller, favorited)
            // —— 第②层：标准兜底（汽水 + 其它未适配的播放器）
            else -> StandardMediaActions.sendFavorite(context, controller, favorited)
        }
        Logx.event(
            LogTags.MEDIA,
            "下发收藏指令",
            "pkg" to pkg,
            "favorited" to favorited,
            "通道" to channelOf(pkg),
            "ok" to ok,
        )
        return ok
    }

    /**
     * 下发「切播放模式」。
     *
     * @param context 上下文（QQ 协议要用来发 Intent）
     * @param controller 当前会话控制器（标准通道要用；QQ 协议不用）
     * @param packageName 当前播放器包名
     * @param mode 目标模式
     * @param caps 当前播放器的**能力**（探测结果）
     * @return 是否真的下发了
     */
    fun sendPlayMode(
        context: Context,
        controller: MediaController?,
        packageName: String?,
        mode: MediaActions.PlayMode,
        caps: MediaActions.Caps,
    ): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (!caps.playMode) {
            Logx.i(
                LogTags.MEDIA,
                "播放模式：当前播放器不支持",
                "pkg" to pkg,
            )
            return false
        }
        val ok = when (pkg) {
            QqBroadcast.PACKAGE -> QqBroadcast.sendPlayMode(
                context,
                when (mode) {
                    MediaActions.PlayMode.REPEAT_ONE -> QqBroadcast.MODE_REPEAT_ONE
                    MediaActions.PlayMode.REPEAT_ALL -> QqBroadcast.MODE_REPEAT_ALL
                    MediaActions.PlayMode.SHUFFLE -> QqBroadcast.MODE_SHUFFLE
                },
            )
            MediaButtonTransport.NETEASE_PACKAGE ->
                NeteaseMediaActions.sendPlayMode(context, controller, mode)
            // —— 第②层：标准兜底（未适配的播放器）
            else -> StandardMediaActions.sendPlayMode(context, controller, mode)
        }
        Logx.event(
            LogTags.MEDIA,
            "下发播放模式指令",
            "pkg" to pkg,
            "mode" to mode.name,
            "通道" to channelOf(pkg),
            "ok" to ok,
        )
        return ok
    }

    /**
     * 这个包走的是**哪条通道**（只用于日志，好排障）。
     *
     * @param packageName 播放器包名
     * @return "QQ协议" / "网易云" / "标准兜底"
     */
    private fun channelOf(packageName: String): String = when (packageName) {
        QqBroadcast.PACKAGE -> "QQ协议"
        MediaButtonTransport.NETEASE_PACKAGE -> "网易云"
        else -> "标准兜底"
    }

    /**
     * **问**播放器当前是什么播放模式。
     *
     * ## 谁真的答得上来
     *
     * - **网易云**：能（发 `"get_play_mode_action"`，它用 session event 回）；
     * - **QQ 音乐**：**不能** —— 它的三方广播里没有播放模式字段，
     *   URI 协议也只有「设置」没有「读取」，
     *   所以 QQ 的播放模式只能由桌面**自己记**（见 [MediaActions.nextPlayMode]）；
     * - **标准兜底**：也没有标准「读取」通道 →
     *   只能靠会话 `PlaybackState` 的模式字段（见 [MediaActions.playModeFromState]）。
     *
     * @param controller 当前会话控制器
     * @param packageName 当前播放器包名
     * @return 是否真的问了（false = 这个播放器答不上来）
     */
    fun requestPlayMode(context: Context, controller: MediaController?, packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (pkg != MediaButtonTransport.NETEASE_PACKAGE) return false
        return NeteaseMediaActions.requestPlayMode(context, controller)
    }
}
