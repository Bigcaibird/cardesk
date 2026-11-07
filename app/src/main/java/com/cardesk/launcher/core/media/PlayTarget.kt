package com.cardesk.launcher.core.media

/**
 * 「**没有打开任何播放器**时，按播放该谁上」—— 纯逻辑（不碰 Android，可 JVM 单测）。
 *
 * ## 为什么要有这个文件（第131轮）
 *
 * 真车现象（用户报的）：
 *
 * > 没打开 QQ 音乐（或任何第三方播放器）时，按方向盘播放键，
 * > **打开的是爱趣听**（车机原厂音源），而不是桌面上选定的那个播放器。
 *
 * 查下来这条链是这么断的：
 *
 * 1. 原车把方向盘按键交给我们的 **OEM 媒体桥**（[OfficialMediaBridge]），
 *    桥按「当前歌名的来源 / 桌面选定的媒体源」算出**目标包名**（这一层是对的）；
 * 2. 目标播放器**没在运行** → 系统里**没有它的 MediaSession** →
 *    `mediaBridgeCommands.send()` 里 `controller == null`，
 *    以前直接记一句「OEM 方控回调已过期，忽略」就结束了；
 * 3. 按键**落进了空里** → 原车只好自己把爱趣听（它的在线音源）拉起来 →
 *    **用户看到的就是「打开爱趣听」**。
 *
 * 所以要补的是第 2 步：**没有会话时，把目标播放器拉起来**，
 * 而不是把按键丢掉。
 *
 * ## 两条不能碰的线
 *
 * 1. **车机原厂音源（爱趣听一类）不由我们接管** ——
 *    原车的收音机 / USB / 蓝牙有它们自己的 owner（见 [MediaBridgePolicy] 顶部的红线）。
 *    这里只决定「**三方在线播放器**该谁上」，原厂音源保持原样。
 * 2. **判据不可靠时宁可不动** —— 拿不到桌面的选择、目标没装、
 *    都按「没有明确目标」处理，绝不乱拉一个 App 出来。
 */
object PlayTarget {

    /**
     * 「没有会话时的播放」走哪条路（见 [noSessionAction]）。
     */
    enum class NoSessionAction {
        /** 没有明确目标 → 走旧逻辑（车机原厂音源那套 DMT 恢复）。 */
        FALLBACK,

        /** 走桌面选定的媒体源（定向播放 + 把它拉起来）。 */
        SELECTED_SOURCE,
    }

    /**
     * 这个包是不是**车机原厂音源**（爱趣听一类）。
     *
     * 与 [MusicSessionTracker] 里原来的 `isAiqutingPkg` 同一套判据
     * （`wecarflow` / `aiquting` / `qqmusictv`），抽出来是为了能单测。
     *
     * 空包名也算「原厂音源」那一类 —— 意思是「**没有明确目标**」，
     * 这时候**不要**由我们去拉起任何东西。
     */
    fun isCarAudioPkg(pkg: String?): Boolean {
        val p = pkg?.lowercase().orEmpty()
        if (p.isEmpty()) return true
        return p.contains("wecarflow") || p.contains("aiquting") || p.contains("qqmusictv")
    }

    /**
     * 这个目标要不要**由我们接管**（拉起它、给它发播放指令）。
     *
     * @param target 目标包名
     * @return true = 接管（三方在线播放器）；false = 不接管（车机原厂音源 / 没有目标）
     */
    fun shouldTakeOver(target: String?): Boolean {
        val t = target?.trim().orEmpty()
        if (t.isEmpty()) return false
        return !isCarAudioPkg(t)
    }

    /**
     * 「没有会话时的播放」该走哪条路（纯函数，可单测）。
     *
     * | 结果 | 含义 |
     * |---|---|
     * | [NoSessionAction.SELECTED_SOURCE] | 走**桌面选定的媒体源**（定向播放 + 拉起它） |
     * | [NoSessionAction.FALLBACK] | 没有明确目标 → 走旧逻辑（车机原厂音源那套 DMT 恢复） |
     *
     * @param target [resolve] 算出来的目标包名
     */
    fun noSessionAction(target: String?): NoSessionAction =
        if (shouldTakeOver(target)) NoSessionAction.SELECTED_SOURCE else NoSessionAction.FALLBACK

    /**
     * 这个目标是不是 **QQ 音乐车机版**（要走它自己的三方协议）。
     *
     * QQ 音乐车机版**不发布会话**，普通播放器的两条路对它都不管用：
     * - `MEDIA_BUTTON` 广播：它不收；
     * - 启动 Intent：只会把界面打开，**不会开始播**。
     *
     * 只有 `qqmusiccar://?action=20&m0=N`（[QqBroadcast.sendControl]）才是真控制，
     * 而且它**没在运行时会把 App 自己拉起来** —— 正是「按播放」该有的样子。
     */
    fun isQqTarget(target: String?): Boolean = target?.trim() == QqBroadcast.PACKAGE

    /**
     * 「没有打开任何播放器时，播放该发给谁」（纯函数，可单测）。
     *
     * ## 优先级
     *
     * 1. **卡片上当前选定的来源**（`design_music_source_v1` / [com.cardesk.launcher.core.Prefs.musicSource]）
     *    —— 桌面音乐卡上显示的那个，用户一眼能看见，**优先**；
     * 2. **设置页的「默认媒体源」**（`default_music_source_v1` /
     *    [com.cardesk.launcher.core.Prefs.defaultMusicSource]）
     *    —— 桌面启动时锁定的那个；第 1 条是「通用音乐」时用它；
     * 3. **记住的上次播放器**（`last_music_package`）——
     *    但**跳过车机原厂音源**（爱趣听一类）：用户明确不要它被打开；
     * 4. **默认音乐应用列表**（[MediaSources.DEFAULT_MUSIC_PACKAGES]）——
     *    最后兜底（QQ 音乐车机版 → 爱趣听 → 酷我 → 网易云 → 汽水）。
     *
     * ## 为什么要 1 优先于 3
     *
     * 「记住的上次播放器」是**历史**，而卡片上的来源是**用户当下的选择**。
     * 真车诊断包实测就是这种错位：用户选了 QQ 音乐，
     * 但上次播放的是车机原厂音源 → 按播放打开的是原厂音源。
     *
     * @param currentSourcePkg 卡片上当前选定的来源对应的包（没装 / 通用音乐 → 空）
     * @param defaultSourcePkg 设置页「默认媒体源」对应的包（没装 / 通用音乐 → 空）
     * @param rememberedPkg 记住的上次播放器包名
     * @param defaultPackages 默认音乐应用候选（按序）
     * @param isInstalled 包是否装了（有启动入口）
     * @return 目标包名；空 = 没有可用目标
     */
    fun resolve(
        currentSourcePkg: String,
        defaultSourcePkg: String,
        rememberedPkg: String,
        defaultPackages: List<String>,
        isInstalled: (String) -> Boolean,
    ): String {
        // 1) 卡片来源行 → 2) 设置页默认源：都是用户的**显式选择**，优先
        for (candidate in listOf(currentSourcePkg, defaultSourcePkg)) {
            val pkg = candidate.trim()
            if (pkg.isEmpty()) continue
            if (isInstalled(pkg)) return pkg
        }
        // 3) 记住的上次播放器（跳过车机原厂音源）
        val remembered = rememberedPkg.trim()
        if (remembered.isNotEmpty() && isInstalled(remembered) && !isCarAudioPkg(remembered)) {
            return remembered
        }
        // 4) 默认音乐应用列表（兜底）
        return defaultPackages.firstOrNull { isInstalled(it) }.orEmpty()
    }
}
