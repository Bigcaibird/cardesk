package com.cardesk.launcher.core.media

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「后台起播」的执行层：用 `MediaBrowser` 连上播放器的 `MediaBrowserService`，
 * 拿到会话后直接下「播放」—— **全程不起任何 Activity**。
 *
 * ## 为什么这条通道能不起前台
 *
 * `MediaBrowserService` 是一个 **Service**。绑定它只会：
 *
 * 1. 拉起播放器的**进程**（冷启动也行）；
 * 2. 让它把 `MediaSession` 挂上来。
 *
 * **不会**创建窗口、不会切走当前前台 Activity。
 * 拿到 `MediaSession.Token` 后 `MediaController.getTransportControls().play()`
 * 就是「让它在后台开始放」。
 *
 * ## 实测（模拟器，Android 9，网易云车机版 6.2.81 真包）
 *
 * ```
 * foreground before: com.cardesk.bgprobe/.ProbeActivity
 * connecting MediaBrowser -> com.netease.cloudmusic.iot/…AutomotiveService
 * MediaBrowser CONNECTED          <- 271ms
 * controller created, sending PLAY
 * PLAY sent
 * foreground after connect: com.cardesk.bgprobe/.ProbeActivity   <- 前台没变
 * ```
 *
 * 网易云进程起来了、活跃会话建好了（`active=true`），前台始终是调用方。
 *
 * ## 边界
 *
 * - 有些播放器的浏览器服务**不接外部客户端**（汽水：`onGetRoot()` 返回 null）→ 连接失败，
 *   调用方要**退回拉前台**（见 [BackgroundPlayback.modeFor]）。
 * - 连接成功只代表「会话起来了」，**不代表真的在放**（没有播放列表时播放器会拒绝）。
 *   这是播放器自己的事，我们只负责把「播放」指令送到。
 */
object BackgroundPlayerConnector {

    /** 连接超时（毫秒）。播放器进程冷启动可能要一两秒。 */
    const val CONNECT_TIMEOUT_MS = 8000L

    /** 从「服务绑定好」到「会话挂上来」的额外等待（毫秒）。 */
    private const val SESSION_SETTLE_MS = 600L

    /**
     * 结果。
     *
     * @param connected 是否成功拿到会话
     * @param reason 失败原因（成功时为空）
     */
    data class Result(
        val connected: Boolean,
        val reason: String = "",
    )

    /**
     * 后台连上播放器并下发「播放」。
     *
     * @param context 上下文
     * @param packageName 目标包名
     * @param browserServiceClass 浏览器服务的**完整**类名（`包名.相对类名`）
     * @param onPlay 下发播放（已在后台线程，由 [android.media.session.MediaController] 接收）
     * @param onFailure 连不上时回调（主线程）；调用方据此**降级**到拉前台
     */
    fun connectAndPlay(
        context: Context,
        packageName: String,
        browserServiceClass: String,
        onPlay: (MediaController) -> Unit,
        onFailure: ((String) -> Unit)? = null,
    ): Result {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        var browser: MediaBrowser? = null
        var settled = false
        var finished = false

        // `ComponentName(pkg, cls)` 的 cls 要的是**相对**类名；
        // 我们拿到的是完整类名（`包名.相对类名`），要去掉包名前缀。
        val relativeClass = browserServiceClass.removePrefix("$packageName.")
        val component = ComponentName(packageName, relativeClass)

        fun finish(result: Result) {
            if (finished) return
            finished = true
            try {
                browser?.disconnect()
            } catch (ignored: RuntimeException) {
                // 已经断开就算了
            }
            Logx.i(
                LogTags.MEDIA,
                if (result.connected) "后台起播：已连上并下发播放" else "后台起播：连不上",
                "pkg" to packageName,
                "service" to browserServiceClass,
                "reason" to result.reason,
            )
            // 连不上 → 通知调用方**降级**（拉前台），否则用户就完全没声音了。
            if (!result.connected) {
                handler.post { onFailure?.invoke(result.reason) }
            }
        }

        // 超时兜底：不能让一次连不上把桌面拖住。
        handler.postDelayed({
            if (!settled) finish(Result(false, "连接超时"))
        }, CONNECT_TIMEOUT_MS)

        try {
            browser = MediaBrowser(
                app,
                component,
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        Logx.i(
                            LogTags.MEDIA,
                            "后台起播：浏览器服务已连接",
                            "pkg" to packageName,
                            "service" to browserServiceClass,
                        )
                        val token: MediaSession.Token? = try {
                            browser?.sessionToken
                        } catch (error: RuntimeException) {
                            Logx.w(LogTags.MEDIA, "后台起播：取会话令牌失败", error, "pkg" to packageName)
                            null
                        }
                        if (token == null) {
                            finish(Result(false, "会话令牌为空"))
                            return
                        }
                        // 服务绑定好 ≠ 会话已挂上来，稍等一下再取控制器。
                        handler.postDelayed({
                            if (finished) return@postDelayed
                            try {
                                val controller = MediaController(app, token)
                                onPlay(controller)
                                settled = true
                                finish(Result(true))
                            } catch (error: RuntimeException) {
                                Logx.w(LogTags.MEDIA, "后台起播：拿控制器失败", error, "pkg" to packageName)
                                finish(Result(false, "拿控制器失败：" + error.javaClass.simpleName))
                            }
                        }, SESSION_SETTLE_MS)
                    }

                    override fun onConnectionFailed() {
                        // 汽水就是这样：服务在，但 `onGetRoot()` 返回 null，不接外部客户端。
                        finish(Result(false, "播放器不接受外部连接"))
                    }
                },
                null,
            )
            browser.connect()
        } catch (error: RuntimeException) {
            Logx.w(LogTags.MEDIA, "后台起播：连接异常", error, "pkg" to packageName)
            finish(Result(false, "连接异常：" + error.javaClass.simpleName))
        }

        // 立刻返回「已发起」；真实结果走上面的回调与超时。
        return Result(connected = false, reason = "已发起")
    }
}
