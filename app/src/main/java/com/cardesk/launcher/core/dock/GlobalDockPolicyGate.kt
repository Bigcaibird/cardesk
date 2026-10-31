package com.cardesk.launcher.core.dock

import android.content.Context
import com.cardesk.launcher.core.OemDockPolicy
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.SystemOps
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 全局 Dock 的 `policy_control` 闸口（第八十七轮）。
 *
 * ## 为什么需要这个对象
 *
 * 全局 Dock 有两种运行路径，必须写**两套完全不同**的 policy：
 *
 * 1. `NavInsets.applied()` 为真（真车、平台签名 + RRO 生效）：
 *    原车导航栏的 112px 已经由系统预留，**绝不能**再写
 *    `immersive.navigation` / `immersive.full` —— 写了预留区就塌掉，
 *    其它应用的内容会跑到我们的 Dock 底下。这条路径只保留状态栏沉浸，
 *    交给 [SystemNavigationPolicy]（它保证只输出 `immersive.status`）。
 * 2. `NavInsets.applied()` 为假（默认 / 普通安装包 / 模拟器）：
 *    没有预留区，而 `TYPE_APPLICATION_OVERLAY`(2038) 的窗口层级**低于**
 *    系统导航栏，原车 nav 条会画在我们 Dock 上面。所以这条路径必须
 *    写 `immersive.navigation=*`，让原车导航栏**整体退出窗口布局、不再绘制**，
 *    2038 窗口才能贴底画满、把这块位置占住。
 *    代价：其它应用底部 112px 会被 Dock 盖住（设置页 note 已写明）。
 *
 * ## 为什么要「运行时」判分支
 *
 * `NavInsets.applied()` 是 prefs 里的状态，RRO 可能在服务启动之后才生效
 * （安装/启用覆盖包、重启）。所以服务启动、开关切换、开机广播、桌面回到前台
 * 都要重新走一次 [apply]，不能只在 `onCreate` 里判一次。
 *
 * ## 为什么服务也要能写
 *
 * 关掉全局 Dock 时如果只停服务、不恢复 policy，用户会得到
 * 「既没有原车 Dock、也没有 CarDesk Dock」的空白底边。服务进程里拿不到
 * `LauncherView` 实例（而且当时 Activity 可能根本没活着），
 * 所以统一的恢复逻辑放在这里，[GlobalDockService] 和 `LauncherView`
 * 都调同一个入口 —— 两边各写一套一定会漂移。
 */
object GlobalDockPolicyGate {

    /**
     * 按当前 prefs / RRO 状态写 policy。
     *
     * @return 是否真的写成功（普通安装包没有 `WRITE_SECURE_SETTINGS` 时为 false，
     *         调用方负责用 overscan / `StatusBarManager` 之类的兜底）。
     */
    fun apply(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val globalDock = prefs.globalDockEnabled
        val navInsets = runCatching { NavInsets.applied(app) }.getOrDefault(false)
        val applied = if (globalDock) {
            applyGlobalDock(app, prefs, navInsets)
        } else {
            restoreNormal(app, prefs)
        }
        Logx.i(
            LogTags.SYS,
            "全局 Dock policy 闸口",
            "globalDock" to globalDock,
            "navInsets" to navInsets,
            "applied" to applied,
        )
        // 给 insets teammate 的排障快照：合并两条路径的实际状态。
        Logx.d(
            LogTags.SYS,
            "全局 Dock 策略快照",
            "gate" to mapOf("globalDock" to globalDock, "navInsets" to navInsets),
            "systemPolicy" to runCatching { SystemNavigationPolicy.snapshot(app) }.getOrDefault(emptyMap<String, Any?>()),
            "navInsetsSnapshot" to runCatching { NavInsets.snapshot(app) }.getOrDefault(emptyMap<String, Any?>()),
        )
        return applied
    }

    /**
     * 无条件恢复「普通桌面」policy（**不读** `globalDockEnabled` 开关）。
     *
     * [GlobalDockService.onDestroy] 用这个：那一刻窗口已经移除了，不管开关
     * 现在是什么状态，`immersive.navigation=*` 都必须先撤掉 ——
     * 否则系统里原车导航栏是关着的、我们的 Dock 又没了，
     * 用户会连「返回 / 主页」的入口都看不到。
     *
     * 如果开关其实还开着（服务被系统杀掉后 START_STICKY 会重建），
     * 重建时 `onCreate` 会再走一遍 [apply]，把 policy 写回全局 Dock 状态，
     * 中间最多空一瞬。反过来（先恢复成普通 state、再看开关）
     * 最坏也只是原车 Dock 闪一下，不会把用户锁在无导航栏的界面里。
     */
    fun restore(context: Context): Boolean {
        val app = context.applicationContext
        Logx.i(LogTags.SYS, "全局 Dock policy 无条件恢复（服务销毁）")
        return restoreNormal(app, Prefs(app))
    }

    /**
     * 全局 Dock 开启中：两条路径。
     *
     * 注意这里**不看** `prefs.hideDock`：原车 Dock 在全局 Dock 模式下
     * 要么被 RRO 预留（applied 分支，原车 Dock 一直活着），要么被
     * `immersive.navigation=*` 整体关掉（!applied 分支）。
     * 再叠加「隐藏原车 Dock」开关只会让两个开关互相打架。
     */
    private fun applyGlobalDock(
        context: Context,
        prefs: Prefs,
        navInsetsApplied: Boolean,
    ): Boolean {
        if (navInsetsApplied) {
            // 预留区由 RRO 保住：只写状态栏沉浸，永不碰 navigation/full。
            Logx.event(
                LogTags.SYS,
                "全局 Dock：接管导航栏高度路径（不写 immersive.navigation）",
                "hideStatus" to prefs.hideStatus,
            )
            return SystemNavigationPolicy.apply(
                context = context,
                hideStatus = prefs.hideStatus,
                hideStatusPackages = prefs.hideStatusApps.toSet(),
                navInsets = true,
            )
        }
        // 没有预留区：让原车导航栏整体让位（B 方案，Lead 已在模拟器验过）。
        val value = OemDockPolicy.buildPolicyValue(
            hideDock = true,
            hideStatus = prefs.hideStatus,
            // 空名单 + hideDock=true → navigation 输出 "*"，即所有应用都去掉导航栏。
            hideDockPackages = emptyList(),
            hideStatusPackages = prefs.hideStatusApps,
        )
        Logx.event(
            LogTags.SYS,
            "全局 Dock：覆盖原车 Dock 路径（immersive.navigation=*）",
            "policy" to value,
        )
        return SystemOps.applyPolicyControl(context, value)
    }

    /**
     * 全局 Dock 关闭后恢复「普通桌面」应有的 policy。
     *
     * 与 `LauncherView.applyOemPolicy()` 的非全局分支逐字同构：
     * 还原成用户原本的「隐藏原车 Dock / 状态栏 / 沉浸」设置。
     * 这样从「全局 Dock 打开」退回「关闭」时，原车 Dock 会**回来**
     * （否则底边就空了）。
     */
    private fun restoreNormal(context: Context, prefs: Prefs): Boolean {
        val immersivePackages =
            if (prefs.oemNavImmersive) listOf(context.packageName) else emptyList()
        val value = OemDockPolicy.buildPolicyValue(
            hideDock = prefs.hideDock,
            hideStatus = prefs.hideStatus,
            hideDockPackages = prefs.hideDockApps,
            hideStatusPackages = prefs.hideStatusApps,
            immersivePackages = immersivePackages,
        )
        Logx.event(
            LogTags.SYS,
            "全局 Dock 关闭：恢复普通 policy",
            "policy" to value,
            "hideDock" to prefs.hideDock,
        )
        return SystemOps.applyPolicyControl(context, value)
    }
}
