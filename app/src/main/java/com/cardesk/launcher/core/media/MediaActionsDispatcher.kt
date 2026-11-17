package com.cardesk.launcher.core.media

import android.content.Context
import android.media.session.MediaController
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「收藏 / 播放模式」的**执行层**：按包名把指令分发给对应播放器的协议。
 *
 * ## 分发规则
 *
 * | 包 | 收藏 | 播放模式 |
 * |---|---|---|
 * | QQ 音乐车机版 | URI 协议 `m0=5/6` | URI 协议 `m0=101/103/105` |
 * | 网易云车机版 | 标准会话 `setRating(心形)` | 标准会话 `sendCustomAction("play_mode_action")` |
 *
 * ## 为什么要按包名分发
 *
 * 这两家**没有共同的协议**：
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
     * @param controller 当前会话控制器（网易云协议要用；QQ 协议不用）
     * @param packageName 当前播放器包名
     * @param favorited true = 收藏，false = 取消收藏
     * @return 是否真的下发了（false = 这个播放器不支持，或下发失败）
     */
    fun sendFavorite(
        context: Context,
        controller: MediaController?,
        packageName: String?,
        favorited: Boolean,
    ): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (!MediaActions.supportsFavorite(pkg)) {
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
            else -> false
        }
        Logx.event(
            LogTags.MEDIA,
            "下发收藏指令",
            "pkg" to pkg,
            "favorited" to favorited,
            "ok" to ok,
        )
        return ok
    }

    /**
     * 下发「切播放模式」。
     *
     * @param context 上下文（QQ 协议要用来发 Intent）
     * @param controller 当前会话控制器（网易云协议要用；QQ 协议不用）
     * @param packageName 当前播放器包名
     * @param mode 目标模式
     * @return 是否真的下发了
     */
    fun sendPlayMode(
        context: Context,
        controller: MediaController?,
        packageName: String?,
        mode: MediaActions.PlayMode,
    ): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (!MediaActions.supportsPlayMode(pkg)) {
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
            else -> false
        }
        Logx.event(
            LogTags.MEDIA,
            "下发播放模式指令",
            "pkg" to pkg,
            "mode" to mode.name,
            "ok" to ok,
        )
        return ok
    }

    /**
     * **问**播放器当前是什么播放模式。
     *
     * ## 谁真的答得上来
     *
     * - **网易云**：能（发 `"get_play_mode_action"`，它用 session event 回）；
     * - **QQ 音乐**：**不能** —— 它的三方广播里没有播放模式字段，
     *   URI 协议也只有「设置」没有「读取」，
     *   所以 QQ 的播放模式只能由桌面**自己记**（见 [MediaActions.nextPlayMode]）。
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
