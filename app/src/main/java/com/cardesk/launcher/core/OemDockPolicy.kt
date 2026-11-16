package com.cardesk.launcher.core

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 原车 Dock / 状态栏的隐藏策略。
 *
 * 用 `settings put global policy_control` 控制原车 Dock 与状态栏
 *
 * - `immersive.navigation=<包名列表>`：这些应用在前台时隐藏原车导航栏（Dock）；
 * - `immersive.status=<包名列表>`：这些应用在前台时隐藏系统状态栏；
 * - 两个策略用 `:` 连接；两者都不需要时写入 `null` 取消策略。
 *
 * **空集 = 全部匹配**（所有应用都隐藏），所以必须有一个哨兵表示「一个都不隐藏」。
 * 这就是。
 *
 * 这里是纯 Kotlin 逻辑（不碰 Android），可在 JVM 单测里覆盖。
 *
 * ## 第五十六轮：为什么「沉浸」要用 `policy_control`，不能只靠 `systemUiVisibility`
 *
 * 真机反馈：**点桌面 Dock 的按钮，会把系统自带的 Dock 唤出来**。
 *
 * 根因在两套「隐藏系统导航栏」的机制不一样：
 *
 * | 机制 | 导航栏视觉 | 底部触摸区 | 单击 | 底部上滑 |
 * |---|---|---|---|---|
 * | `SYSTEM_UI_FLAG_HIDE_NAVIGATION` + | 隐藏 | **仍然收触摸** | **会唤起** | 会唤起 |
 * | `policy_control` 的 `immersive.navigation=<包名>` | 隐藏 | **不收触摸** | 不唤起 | 会唤起（系统手势） |
 *
 * 前者只是把导航栏「画掉」，窗口还在、照常吃触摸 ——
 * 而我们的 Dock 画在最底部，正好压在它上面，点 Dock 就等于点它。
 *
 * 后者是把导航栏**从窗口布局里摘掉**，触摸不再落到它身上；
 * 只剩系统自己的「底部上滑」手势能把它临时唤出来 ——
 * 正是用户要的行为。
 *
 * 所以我们的做法是：**用 `immersive.navigation` 把桌面自己（以及用户勾选的应用）
 * 沉浸掉**，view flag 只作为「没有系统权限时的兜底」。
 */
object OemDockPolicy {

    /**
     * 第180轮：**会让全局 Dock 主动让位的沉浸型播放器**。
     *
     * 这两类 App 自己 `hideNavigationBars`，画面直接铺到屏幕最底；
     * 而 CarDesk 的全局 Dock 是悬浮窗，必然压住它们底部 112px（真机反馈
     * 「汽水音乐 / 网易云音乐打开时界面被 Dock 挡住」）。
     *
     * App 侧改不了（`policy_control` 只有强制沉浸、没有强制不沉浸），
     * 只能 Dock 侧让位：**这两个包在前台时把 Dock 隐藏**，退出后再显示。
     *
     * 只放**实测会铺满到底**的包；QQ 音乐车机版遵守导航栏 inset，不需要让位。
     */
    val IMMERSIVE_MEDIA_APPS: Set<String> = setOf(
        "com.netease.cloudmusic.iot",
        "com.luna.music.car",
    )

    /** 前台是这个包时，全局 Dock 是否应当让位（隐藏）。 */
    fun hidesGlobalDockFor(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return IMMERSIVE_MEDIA_APPS.contains(pkg)
    }

    /** 「一个都不隐藏」的哨兵。 */
    const val NO_APPS = "__dockbar_no_apps__"

    /**
     * 把包名集合格式化成 `policy_control` 里的列表。
     * 空集 → `*`（全部匹配）。
     */
    fun formatApps(apps: Collection<String>?): String {
        if (apps.isNullOrEmpty()) return "*"
        val sb = StringBuilder()
        for (pkg in apps) {
            val trimmed = pkg.trim()
            if (trimmed.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(trimmed)
        }
        return if (sb.length == 0) "*" else sb.toString()
    }

    /** 把持久化的字符串（含哨兵）解回集合；空串表示「全部」。 */
    fun decodePackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val set = LinkedHashSet<String>()
        for (part in raw.split(',')) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) set.add(trimmed)
        }
        return set
    }

    /**
     * 把集合编码成持久化字符串（排序去空）。 */
    fun encodePackages(apps: Collection<String>): String =
        apps.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .joinToString(",")

    /**
     * 判断某个包是否命中「隐藏列表」。
     * 空集 = 全部命中；含哨兵 = 一个都不命中（`matchesHideList`）。
     */
    fun matchesHideList(apps: Collection<String>?, pkg: String): Boolean {
        if (apps.isNullOrEmpty()) return true
        if (apps.contains(NO_APPS)) return false
        return apps.contains(pkg)
    }

    /**
     * 生成 `policy_control` 的值。
     *
     * @param hideDock 是否隐藏原车 Dock —— **全局生效**（所有 App 都去掉系统 Dock）
     * @param hideStatus 是否隐藏系统状态栏
     * @param hideStatusPackages 命中时隐藏状态栏的包名集合
     * @param immersivePackages **总是**沉浸的包名集合（一般是桌面自己）。
     *        只在 [hideDock] 关闭时生效 —— 打开时是「全部」，它被 `*` 覆盖了。
     * @return null 表示不需要写策略（两项都不开）
     */
    fun buildPolicyValue(
        hideDock: Boolean,
        hideStatus: Boolean,
        hideStatusPackages: Collection<String>?,
        immersivePackages: Collection<String>? = null,
    ): String? {
        val sb = StringBuilder()
        if (hideDock) {
            // 第189轮：「隐藏原车 Dock」**全局生效** —— 所有 App 都去掉系统 Dock。
            // 以前这里是「只对名单里的 App 生效」（外加桌面自己），
            // 于是勾上开关只对指定 App 管用、对其它 App 一点用没有。
            sb.append("immersive.navigation=*")
        } else {
            // 没开全局隐藏时，只沉浸「总是沉浸」的那几个（一般是桌面自己）。
            val navigation = LinkedHashSet<String>()
            immersivePackages?.forEach { raw ->
                val pkg = raw.trim()
                if (pkg.isNotEmpty()) navigation.add(pkg)
            }
            if (navigation.isNotEmpty()) {
                sb.append("immersive.navigation=").append(formatApps(navigation))
            }
        }
        if (hideStatus) {
            if (sb.isNotEmpty()) sb.append(':')
            sb.append("immersive.status=")
            sb.append(formatApps(hideStatusPackages))
        }
        val value = sb.takeIf { it.length > 0 }?.toString()
        // 排障场景：「隐藏原车 Dock / 状态栏」没反应 —— 先看最终生成的策略串长什么样，
        // 以及两个开关各自是不是被打开（都是 false 时策略为 null，等于没写）。
        Logx.i(
            LogTags.SYS,
            "生成原车 Dock/状态栏策略",
            "hideDock" to hideDock,
            "hideStatus" to hideStatus,
            "immersive" to immersivePackages?.joinToString(","),
            "statusApps" to formatApps(hideStatusPackages),
            "policy" to value,
        )
        return value
    }

    /** 增强导航栏模式下的策略（`buildEnhancedPolicy`：导航栏恒定沉浸，状态栏按包名隐藏）。 */
    fun buildEnhancedPolicy(hideStatus: Boolean, hideStatusPackages: Collection<String>?): String {
        val value = if (!hideStatus) {
            "immersive.navigation=*"
        } else {
            "immersive.navigation=*:immersive.status=" + formatApps(hideStatusPackages)
        }
        // 排障场景：增强导航栏开着但原车栏没让位 —— 看这条恒定沉浸策略
        Logx.i(
            LogTags.SYS,
            "生成增强导航栏策略",
            "hideStatus" to hideStatus,
            "statusApps" to formatApps(hideStatusPackages),
            "policy" to value,
        )
        return value
    }
}
