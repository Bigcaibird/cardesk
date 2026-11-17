package com.cardesk.launcher.core.media

/**
 * 「后台起播」通道的**协议事实与判定**（纯逻辑，可 JVM 单测）。
 *
 * ## 要解决的问题
 *
 * 「启动后自动播放」开关打开时，桌面会走 [MediaAppLauncher.launchAndPlay]：
 * `getLaunchIntentForPackage` → `startActivity` → **把音乐 App 拉到前台** →
 * 等它把 `MediaSession` 挂上来 → 下「播放」。
 *
 * 用户要的是**不把音乐 App 唤醒到前台，在后台直接播放**。
 *
 * ## 结论：能做到，但**只能对一部分播放器做到**
 *
 * 模拟器实测（Android 9，车机真实 APK）得到的关键规律：
 *
 * | 通道 | App 没运行时能不能唤起 | 会不会拉前台 |
 * |---|---|---|
 * | **绑定 `MediaBrowserService`** | **能**（绑定会拉起它的进程） | **不会**（起的是 Service，不是 Activity） |
 * | 发定向 `MEDIA_BUTTON` 广播 | **不能**（App 没运行 → 接收器没进程 → 广播无人接） | 不会 |
 * | 发定向第三方广播（QQ 音乐那套） | **不能**（同上） | 不会（但也唤不起来） |
 * | `startActivity`（现状） | 能 | **会** |
 *
 * → **唯一能在后台冷启动播放器的正规通道是 `MediaBrowserService`。**
 *
 * ## 各播放器的实测结果
 *
 * | 播放器 | 浏览器服务 | 结果 |
 * |---|---|---|
 * | 网易云车机版 `com.netease.cloudmusic.iot` | `com.netease.cloudmusic.service.AutomotiveService` | ✅ **可用**（实测连上并建会话，前台不变） |
 * | 汽水车机版 `com.luna.music.car` | `com.luna.player.impl.playerservice.MediaBrowserPlayerService` | ❌ **连不上**（它的 `onGetRoot()` 返回 null，不接外部客户端） |
 * | QQ 音乐车机版 `com.tencent.qqmusiccar` | **没有** | ❌ 只能退回拉前台 |
 *
 * 汽水这一条与 [SodaMediaSource] 里记的事实一致（browse 树是空的）。
 *
 * ## 所以判定分三档
 *
 * 1. **有浏览器服务且连得上** → 后台起播（不拉前台）；
 * 2. **有浏览器服务但连不上**（汽水） → 退回拉前台；
 * 3. **没有浏览器服务**（QQ 音乐） → 退回拉前台。
 *
 * 「退回」不是失败 —— 是**保证功能不退化**：以前能播，现在还是能播，只是仍然会拉前台。
 */
object BackgroundPlayback {

    /**
     * 浏览器服务的标准 action。
     *
     * 播放器把自己的 `MediaBrowserService` 子类注册成这个 action，
     * 我们靠它把「哪些 App 有后台起播能力」查出来。
     */
    const val ACTION_MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"

    /** 网易云音乐车机版的浏览器服务类名（实测 6.2.81 / 6002081）。 */
    const val NETEASE_BROWSER_SERVICE = "com.netease.cloudmusic.service.AutomotiveService"

    /** 汽水音乐车机版的浏览器服务类名（实测 1.1.27 / 101027）。 */
    const val SODA_BROWSER_SERVICE = "com.luna.player.impl.playerservice.MediaBrowserPlayerService"

    /** QQ 音乐车机版：没有浏览器服务，只能拉前台。 */
    const val QQ_PACKAGE = "com.tencent.qqmusiccar"

    /**
     * 一个播放器的「后台起播通道」。
     *
     * @param packageName 包名
     * @param browserService 浏览器服务的**相对类名**（相对包名，如
     *   `com.netease.cloudmusic.service.AutomotiveService`）；null = 没有这个通道
     */
    data class Channel(
        val packageName: String,
        val browserService: String?,
    ) {
        /** 有没有后台起播通道。 */
        val hasBrowserService: Boolean get() = !browserService.isNullOrBlank()

        /**
         * 浏览器服务的**完整**类名（`包名.相对类名`）。
         *
         * 系统枚举 `queryIntentServices` 返回的就是完整类名，用它来匹配。
         */
        val browserServiceClass: String?
            get() = browserService?.takeIf { it.isNotBlank() }?.let { "$packageName.$it" }
    }

    /**
     * 已知播放器的后台起播通道表。
     *
     * 只列**实测过**的 —— 宁缺毋滥：把一个连不上的服务写进来，
     * 只会让后台起播每次都失败再退回前台，白绕一圈。
     *
     * 汽水虽然在表里，但它的连接会失败（`onGetRoot()` 返回 null），
     * 留着是为了让日志能说清「它有通道但连不上」，而不是笼统的「没通道」。
     */
    private val CHANNELS: Map<String, Channel> = mapOf(
        MediaButtonTransport.NETEASE_PACKAGE to Channel(
            packageName = MediaButtonTransport.NETEASE_PACKAGE,
            browserService = NETEASE_BROWSER_SERVICE,
        ),
        SodaMediaSource.PACKAGE to Channel(
            packageName = SodaMediaSource.PACKAGE,
            browserService = SODA_BROWSER_SERVICE,
        ),
        QQ_PACKAGE to Channel(
            packageName = QQ_PACKAGE,
            browserService = null,
        ),
    )

    /**
     * 这个包的「后台起播通道」。
     *
     * 表里没有的包 → 返回 null（**不猜**：随便猜一个服务名去绑定，
     * 轻则连不上，重则连到不相干的服务上乱下指令）。
     */
    fun channelOf(packageName: String?): Channel? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        return CHANNELS[pkg]
    }

    /**
     * 这个包**有没有**后台起播通道。
     */
    fun hasBackgroundChannel(packageName: String?): Boolean =
        channelOf(packageName)?.hasBrowserService == true

    /**
     * 起播方式。
     */
    enum class Mode {
        /** 后台起播（绑浏览器服务，不拉前台）。 */
        BACKGROUND,

        /** 拉前台起播（现状；后台通道不可用时的降级）。 */
        FOREGROUND,
    }

    /**
     * 该用哪种方式起播。
     *
     * @param packageName 目标播放器包名
     * @param browserServiceAvailable 运行时查到的浏览器服务类名；null = 没查到
     * @return 有浏览器服务 → [Mode.BACKGROUND]；否则 → [Mode.FOREGROUND]
     */
    fun modeFor(packageName: String?, browserServiceAvailable: Boolean): Mode =
        if (browserServiceAvailable) Mode.BACKGROUND else Mode.FOREGROUND

    /**
     * 从一组候选类名里挑出**属于这个包**的浏览器服务类名（纯逻辑，可单测）。
     *
     * 系统里可能同时挂着好几个 `MediaBrowserService`（系统音乐、别的播放器…），
     * 只能挑**目标包自己**的那个 —— 连到别人的服务上下指令等于替第三方按播放。
     *
     * @param candidates 系统枚举到的全部浏览器服务类名
     * @param packageName 目标包名
     * @return 属于该包的类名；没有 → null
     */
    fun pickBrowserService(candidates: List<String>, packageName: String): String? {
        val prefix = "$packageName."
        return candidates.firstOrNull { it.startsWith(prefix) }
    }
}
