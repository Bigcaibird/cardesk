package com.cardesk.launcher.core.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「桌面启动完成后唤起默认媒体来源 App 并让它播放」（第149轮；
 * 第一九四轮起改为**优先后台起播**）。
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
 *
 * ## 第一九四轮：起播顺序改为**先后台、再前台**
 *
 * 原来第②步是 `startActivity` —— 音乐 App 被**拉到前台**，用户的桌面被顶掉。
 * 现在先试 [BackgroundPlayback]：绑播放器的 `MediaBrowserService`，
 * 播放器在后台把会话挂上来，直接下「播放」，**它一个 Activity 都不起**。
 *
 * 绑不上（汽水不接外部客户端、QQ 音乐没有这个服务）就**退回前台**，
 * 行为与第一四九轮一致 —— 功能不退化。
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
     * ## 和上面那段「只锁定、不拉起」的区别
     *
     * 上面那段是**无条件**的「锁定默认源」—— 桌面起来就知道该听谁的，
     * 但**不打扰用户**（不弹 App）。
     *
     * 这一段是**用户显式打开开关**后才会发生的「拉起 App + 下播放指令」——
     * 属于「开机就直接开始听歌」的需求。
     *
     * 开关默认关：开机弹出一个音乐 App 挺打扰，而且默认源是「通用音乐」时
     * 根本没有对应的 App 可拉（`MediaAppLauncher.shouldLaunch` 会挡住）。
     *
     * ## 起播顺序：先后台，再前台（第一九四轮）
     *
     * 原来只有一条路：`startActivity` 把音乐 App **拉到前台**。
     * 现在先试[后台起播](BackgroundPlayback)——绑它的 `MediaBrowserService`，
     * 播放器在后台把会话挂上来，直接下「播放」，**音乐 App 一个 Activity 都不起**。
     *
     * 绑不上（汽水不接外部客户端 / QQ 音乐压根没有这个服务）就**退回前台**，
     * 行为与以前完全一致 —— 功能不退化，只是仍然会拉前台。
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

        // 第一步：试后台起播（不拉前台）。
        //
        // 连接是**异步**的（要等播放器进程起来），所以这里返回的是「是否发起了后台尝试」；
        // 连不上时由 [BackgroundPlayerConnector] 的回调触发前台降级。
        if (tryBackgroundPlayback(
                context = context,
                pkg = pkg,
                onStarted = onStarted,
                onFallback = { launchInForeground(context, pkg, sourceId, onStarted) },
            )
        ) {
            Logx.i(
                LogTags.MEDIA,
                "启动后唤起媒体 App：已发起后台起播，不拉前台",
                "pkg" to pkg,
                "sourceId" to sourceId,
            )
            return pkg
        }

        // 第二步：后台通道不可用（汽水 / QQ 音乐 / 服务没注册）→ 拉前台，行为与第一四九轮一致。
        launchInForeground(context, pkg, sourceId, onStarted)
        return pkg
    }

    /**
     * 降级路径：把播放器**拉到前台**，等它的会话挂上来后下「播放」。
     *
     * 与第一四九轮完全一样 —— 保证「后台通道不可用」时功能不退化。
     */
    private fun launchInForeground(
        context: Context,
        pkg: String,
        sourceId: String,
        onStarted: ((String) -> Unit)?,
    ) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
        }.getOrNull()
        if (intent == null) {
            Logx.w(LogTags.MEDIA, "启动后唤起媒体 App：拿不到启动 Intent", "pkg" to pkg)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            Logx.w(LogTags.MEDIA, "启动后唤起媒体 App：起不来", it, "pkg" to pkg)
            false
        }
        if (!started) return
        Logx.i(
            LogTags.MEDIA,
            "启动后唤起媒体 App：已拉起（后台通道不可用，走前台）",
            "pkg" to pkg,
            "sourceId" to sourceId,
        )
        onStarted?.invoke(pkg)
        awaitSessionThenPlay(pkg)
    }

    /**
     * 试后台起播：绑播放器的 `MediaBrowserService`，拿到会话就下「播放」。
     *
     * ## 为什么不让这一层去查「有哪些浏览器服务」
     *
     * 系统里可能同时挂着系统音乐、别的播放器的浏览器服务。
     * 我们只认**目标包自己**的那个（[BackgroundPlayback.pickBrowserService]）——
     * 连到别人的服务上下指令等于替第三方按播放。
     *
     * ## 为什么失败要**降级**而不是放弃
     *
     * 后台起播是**优化**，不是功能前提。播放器不支持（汽水 / QQ 音乐）时，
     * 退回前台拉起，行为与第一四九轮完全一样 —— 用户照样能听到歌。
     *
     * @param onStarted 成功起播后的回调
     * @param onFallback 连不上时的降级回调（走前台拉起）
     * @return true = 已发起后台尝试；false = 这条路根本不可用（调用方该立刻走前台）
     */
    private fun tryBackgroundPlayback(
        context: Context,
        pkg: String,
        onStarted: ((String) -> Unit)?,
        onFallback: () -> Unit,
    ): Boolean {
        val channel = BackgroundPlayback.channelOf(pkg) ?: return false
        if (!channel.hasBrowserService) {
            Logx.i(
                LogTags.MEDIA,
                "后台起播：该播放器没有浏览器服务，只能拉前台",
                "pkg" to pkg,
            )
            return false
        }
        val serviceClass = channel.browserServiceClass ?: return false

        // 运行时核对：这个服务确实注册了标准 action（清单说了算，不是我们说了算）。
        //
        // ## `serviceInfo.name` 的形态：实测是**相对类名**
        //
        // Android 9 上实测给的是 `com.netease.cloudmusic.service.AutomotiveService`
        // —— 含点号，但**不含包名**。所以判据只能是「**是不是以目标包名开头**」，
        // 不能用「含不含点号」。
        //
        // 另一个坑：`serviceInfo.packageName` 才是**这个服务属于谁**。
        // 用它筛出目标包的服务，再把相对类名补成完整类名 ——
        // 这样别的 App 的服务（系统音乐、汽水）根本不会进候选，
        // 也就不会出现「把别人的服务补上目标包前缀」的脏数据。
        val resolved = runCatching {
            val pm = context.packageManager
            val services = pm.queryIntentServices(
                Intent(BackgroundPlayback.ACTION_MEDIA_BROWSER_SERVICE),
                0,
            )
            val names = services.mapNotNull { info ->
                val serviceInfo = info.serviceInfo ?: return@mapNotNull null
                if (serviceInfo.packageName != pkg) return@mapNotNull null
                val name = serviceInfo.name ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                if (name.startsWith("$pkg.")) name else "$pkg.$name"
            }
            // 排障：系统枚举到的浏览器服务原样落盘 —— 类名形态（完整 / 相对）
            // 在不同系统上不一样，靠猜会连到别的 App 的服务上。
            Logx.i(
                LogTags.MEDIA,
                "后台起播：系统枚举到的浏览器服务",
                "pkg" to pkg,
                "services" to names.joinToString(","),
            )
            BackgroundPlayback.pickBrowserService(names, pkg)
        }.getOrNull()

        if (resolved == null) {
            Logx.i(
                LogTags.MEDIA,
                "后台起播：系统里查不到该播放器的浏览器服务，退回前台",
                "pkg" to pkg,
                "expected" to serviceClass,
            )
            return false
        }

        BackgroundPlayerConnector.connectAndPlay(
            context = context,
            packageName = pkg,
            browserServiceClass = resolved,
            onPlay = { controller ->
                // 拿得到控制器就说明通道通了 —— 下「播放」。
                // 幂等：已经在放就什么都不做（翻转一次很可能变成「暂停」）。
                runCatching {
                    val state = controller.playbackState
                    val playing = state != null &&
                        state.state == android.media.session.PlaybackState.STATE_PLAYING
                    if (!playing) controller.transportControls.play()
                }.onFailure {
                    Logx.w(LogTags.MEDIA, "后台起播：下发播放失败", it, "pkg" to pkg)
                }
                onStarted?.invoke(pkg)
            },
            onFailure = { reason ->
                // 连不上（汽水不接外部客户端 / 播放器没起来）→ 降级到拉前台。
                Logx.i(
                    LogTags.MEDIA,
                    "后台起播：连不上，降级到拉前台",
                    "pkg" to pkg,
                    "reason" to reason,
                )
                onFallback()
            },
        )
        // 连接是异步的；这里只表示「已发起」。
        return true
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
