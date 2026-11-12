package com.cardesk.launcher.core.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「桌面启动完成后唤起默认媒体来源 App 并让它播放」（第149轮）。
 *
 * ## 为什么单独一个文件
 *
 * 这件事有三段,每段都可能单独失败,而且**失败都不该影响桌面**：
 *
 * ① 解析出「是哪个 App」（默认源 → 包名）；
 * ② 把 App 拉起来；
 * ③ 等它把 `MediaSession` 挂上来,然后下「播放」指令。
 *
 * 三段揉在 `LauncherView` 里会变成一大坨不可测的逻辑，所以拆到这里：
 * 纯逻辑（①②的判定）留在本文件里，Android 侧只有「起 Intent」和「轮询」两处。
 *
 * ## 关键设计：播放指令是**幂等的**
 *
 * 绝不能用 [MusicSessionTracker.playPause] —— 那是「翻转」语义。
 * 启动场景下会话刚建立、播放态还没上报，翻转一次很可能变成「暂停」。
 *
 * 这里用的是 [MusicSessionTracker.setPlaying] 的「表达期望状态」语义：
 * 已经在放 → 什么都不做；不在放 → 下发一次播放。
 */
object MediaAppLauncher {

    /**
     * 拉起 App 之后，等多久开始找会话。
     *
     * App 冷启动（进程创建 + 首屏 + 隐私弹窗）通常要 1~2 秒，
     * 600ms 就开始轮询太早，1200ms 比较稳。
     */
    const val SESSION_WAIT_START_MS = 1200L

    /** 轮询间隔。 */
    const val POLL_INTERVAL_MS = 400L

    /** 最多等多久。超时了就放弃 —— 宁可没播上，也不要一直挂着定时器。 */
    const val SESSION_WAIT_TIMEOUT_MS = 15_000L

    /**
     * 该不该做这件事（纯逻辑，好测）。
     *
     * @param autoLaunchEnabled 设置页那个开关
     * @param sourceId 默认媒体源 id
     * @return true = 该拉起
     */
    fun shouldLaunch(autoLaunchEnabled: Boolean, sourceId: String): Boolean {
        if (!autoLaunchEnabled) return false
        // 「通用音乐」没有对应的 App —— 它只是「跟随系统当前会话」，没有东西可拉。
        if (sourceId.isBlank() || sourceId == MediaSources.GENERIC_ID) return false
        return true
    }

    /**
     * 解析要拉起的包名（纯逻辑，好测）。
     *
     * @return null = 没装 / 源不认识 / 是通用音乐
     */
    fun resolvePackage(sourceId: String, isInstalled: (String) -> Boolean): String? =
        MediaSources.resolveDefaultPackage(sourceId) { pkg ->
            runCatching { isInstalled(pkg) }.getOrDefault(false)
        }

    /**
     * 「会话是不是目标 App 的？」—— 轮询里判断「可以下播放指令了」的依据（纯逻辑，好测）。
     *
     * ## 为什么要比对包名
     *
     * 系统里可能同时挂着好几个 `MediaSession`（车机的、别的播放器的）。
     * 只有**目标 App 自己**的会话才该被下播放指令 ——
     * 打到别人身上等于替第三方 App 按了播放。
     *
     * @param trackPkg 当前会话所属包名；null = 还没有会话
     * @param targetPkg 我们拉起来的那个 App 的包名
     */
    fun sessionMatches(trackPkg: String?, targetPkg: String): Boolean =
        trackPkg != null && trackPkg == targetPkg

    /**
     * 真正执行：起 App → 等会话 → 下播放指令。
     *
     * ## 开关判断放在**里面**
     *
     * 放在外面的话每个调用方都得自己判一遍，早晚有地方忘 ——
     * 忘了就会变成「用户明明关了开关，开机还是弹出音乐 App」。
     *
     * @param autoLaunchEnabled 设置页那个开关
     * @param onStarted 已把 App 拉起来（回调里可以记日志 / 刷新界面）
     * @return 拉起的包名；null = 没拉（开关关 / 通用音乐 / 没装 / 起不来）
     */
    fun launchAndPlay(
        context: Context,
        autoLaunchEnabled: Boolean,
        sourceId: String,
        isInstalled: (String) -> Boolean = { pkg ->
            runCatching {
                context.packageManager.getApplicationInfo(pkg, 0) != null
            }.getOrDefault(false)
        },
        onStarted: ((String) -> Unit)? = null,
    ): String? {
        if (!shouldLaunch(autoLaunchEnabled, sourceId)) {
            Logx.i(
                LogTags.MEDIA,
                "启动后唤起媒体 App：开关关着或没有对应 App，不动作",
                "enabled" to autoLaunchEnabled,
                "sourceId" to sourceId,
            )
            return null
        }
        val pkg = resolvePackage(sourceId, isInstalled)
        if (pkg == null) {
            Logx.i(LogTags.MEDIA, "启动后唤起媒体 App：无可用包", "sourceId" to sourceId)
            return null
        }
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
        }.getOrNull()
        if (intent == null) {
            Logx.w(LogTags.MEDIA, "启动后唤起媒体 App：拿不到启动 Intent", "pkg" to pkg)
            return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            Logx.w(LogTags.MEDIA, "启动后唤起媒体 App：起不来", it, "pkg" to pkg)
            false
        }
        if (!started) return null
        Logx.i(LogTags.MEDIA, "启动后唤起媒体 App：已拉起", "pkg" to pkg, "sourceId" to sourceId)
        onStarted?.invoke(pkg)
        awaitSessionThenPlay(pkg)
        return pkg
    }

    /**
     * 轮询等 `MediaSession` 挂上来，一旦出现就下「播放」指令。
     *
     * ## 为什么是「等会话」而不是「发广播播放键」
     *
     * `ACTION_MEDIA_BUTTON` 广播打的是「当前正在出声的 App」，
     * 启动那一刻可能还没有任何 App 在出声，广播就丢了。
     * 等会话出现后用 [MusicSessionTracker.setPlaying] 精确打到那个 App 上，才靠谱。
     *
     * ## 为什么超时后放弃
     *
     * 有些播放器首次启动会弹隐私政策，用户不点同意就永远不会有会话。
     * 这种情况下一直挂着轮询只是白耗电 —— 超时放弃更干净。
     */
    private fun awaitSessionThenPlay(pkg: String) {
        val handler = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + SESSION_WAIT_TIMEOUT_MS
        val tick = object : Runnable {
            override fun run() {
                val tracker = MusicSessionTracker.get()
                val track = tracker.track
                if (sessionMatches(track?.pkg, pkg)) {
                    // 会话在了 —— 下「确保在播」（幂等：已经在放就什么都不做）。
                    tracker.setPlaying(true)
                    Logx.i(LogTags.MEDIA, "启动后唤起媒体 App：会话已到，已下发播放", "pkg" to pkg)
                    return
                }
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                } else {
                    Logx.i(
                        LogTags.MEDIA,
                        "启动后唤起媒体 App：等待会话超时，放弃播放",
                        "pkg" to pkg,
                    )
                }
            }
        }
        handler.postDelayed(tick, SESSION_WAIT_START_MS)
    }
}
