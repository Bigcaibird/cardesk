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
 *    原车导航栏的 112px 已经由系统预留。这条路径默认只保留状态栏沉浸，
 *    交给 [SystemNavigationPolicy]。
 *    **第189轮例外**：用户要求「隐藏原车 Dock」对**所有 App** 生效，
 *    所以开关打开时这里也会写 `immersive.navigation=*`。
 *    代价要说清楚：预留区会因此塌陷，其它应用的内容可能跑到我们的 Dock 底下
 *    （设置页的 note 已经写明这一点）—— 这是「全局隐藏」的必然代价。
 * 2. `NavInsets.applied()` 为假（默认 / 普通安装包 / 模拟器）：
 *    没有预留区，而 `TYPE_APPLICATION_OVERLAY`(2038) 的窗口层级**低于**
 *    系统导航栏，原车 nav 条会画在我们 Dock 上面。所以这条路径必须
 *    写 `immersive.navigation=*`，让原车导航栏**整体退出窗口布局、不再绘制**，
 *    2038 窗口才能贴底画满、把这块位置占住。
 *    代价：其它应用底部 112px 会被 Dock 盖住（设置页 note 已写明）。
 *
 * ## 第 159 轮补充：真车上的实际表现
 *
 * 真车上「底部被挡」**不是普遍现象**，只发生在**沉浸型** App 上：
 *
 * - **遵守系统导航栏 inset 的 App**（QQ 音乐车机版）底边停在 Dock 顶边附近，
 *   全局 Dock 盖不到它 → 正常；
 * - **自己 `hideNavigationBars` 的沉浸型 App**（网易云车机版 / 汽水音乐车机版）
 *   画面铺到屏幕底 1080 → 被全局 Dock 压住底部 112px。
 *
 * 也就是说：**「底部被挡」是 App 自己选了沉浸**，
 * 而 App 侧改不了（`policy_control` 只有强制沉浸、没有强制不沉浸），
 * 只能让 Dock 侧让位。
 *
 * ⚠️ 但「预留区在真车上到底通没通」目前**只是推断**（QQ 音乐也可能是自己做了
 * 安全区适配）。要坐实得用 [DockBottomProbe.systemNavBarHeightPx] 读实测值 ——
 * 那正是第159轮新增的诊断输出。
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
        // 第九十四轮：全局 Dock 默认开启后，必须确认这台车**真的能显示**全局窗口
        // （RRO 预留区或悬浮窗权限），否则原车导航栏被 `immersive.navigation=*`
        // 摘掉、CarDesk 窗口又起不来 —— 用户会既没有原车 Dock 也没有我们的 Dock。
        val overlay = runCatching {
            android.provider.Settings.canDrawOverlays(app)
        }.getOrDefault(false)
        val canRunGlobalDock = navInsets || overlay
        val applied = if (globalDock && canRunGlobalDock) {
            applyGlobalDock(app, prefs, navInsets)
        } else {
            restoreNormal(app, prefs)
        }
        Logx.i(
            LogTags.SYS,
            "全局 Dock policy 闸口",
            "globalDock" to globalDock,
            "navInsets" to navInsets,
            "overlay" to overlay,
            "canRunGlobalDock" to canRunGlobalDock,
            "applied" to applied,
        )
        // 给 insets teammate 的排障快照：合并两条路径的实际状态。
        Logx.d(
            LogTags.SYS,
            "全局 Dock 策略快照",
            "gate" to mapOf(
                "globalDock" to globalDock,
                "navInsets" to navInsets,
                "overlay" to overlay,
            ),
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
     * 第189轮起**要看** `prefs.hideDock` 了：以前完全不看，
     * 于是「隐藏原车 Dock」这个开关在全局 Dock 模式下**一点用没有**
     * （原车 Dock 要么被 RRO 预留、要么被 `immersive.navigation=*` 整体关掉），
     * 用户看到的就是「这个开关只对桌面 App 生效」。
     *
     * ## 第193轮：预留区路径**不写** `immersive.navigation=*`
     *
     * 第189轮把 `hideNavigation = prefs.hideDock` 接到了**预留区路径**上，
     * 结果和这个类自己的设计契约、以及
     * [SystemNavigationPolicy] 的类注释**直接矛盾**：
     *
     * > 全局 Dock 生效时**不能**再对其它应用写 `immersive.navigation` ——
     * > 那会把原生导航栏（连同它占的 112px 预留区）一起从窗口布局里摘掉。
     *
     * 一条 `immersive.navigation=*` 会同时引发三件事：
     *
     * 1. **预留带被摧毁** —— 系统导航栏被从**所有** App 的窗口布局里摘掉，
     *    App 全屏，底部 112px 的预留带根本不存在 ⇒ Dock 盖住所有 App 的内容；
     * 2. **让位探测被污染** —— WMS 因此给**每一个** App 打上
     *    `HIDE_NAVIGATION`/`IMMERSIVE`，`dumpsys window policy` 的
     *    `mLastSystemUiFlags` **永远**带这些位 ⇒
     *    [ImmersiveDetector] 对任何前台 App 都判「沉浸」⇒
     *    **Dock 在几乎所有 App 里让位**（桌面只是靠 `homeForeground` 硬编码豁免才幸免）；
     * 3. **让位后原车导航栏露出来** —— CarDesk 的 Dock 是
     *    `TYPE_NAVIGATION_BAR_PANEL`(2024)，**盖在**系统的 `CarNavigationBar`
     *    窗口之上（实测同帧 `[0,968][1920,1080]`）；
     *    Dock 一隐藏，底下的原车导航栏就露了出来。
     *
     * 所以预留区路径**必须**让预留带活着：原车 Dock 在这条路径上本来就被
     * 我们的 2024 面板**顶替**了（同帧、面板在上），
     * 「隐藏原车 Dock」的语义已经成立，不需要再拿 `immersive.navigation=*` 去摘它。
     *
     * **无预留区路径（2038 悬浮窗）保持原样** —— 那条路径必需：
     * 悬浮窗层级低于系统导航栏，不把原车 nav 条从窗口布局里摘掉，
     * 它就会画在我们的 Dock 上面。
     */
    private fun applyGlobalDock(
        context: Context,
        prefs: Prefs,
        navInsetsApplied: Boolean,
    ): Boolean {
        if (navInsetsApplied) {
            // 预留区由 RRO 保住：状态栏按开关沉浸；
            // `immersive.navigation=*` **一律不写**（第193轮）——
            // 写了就把预留带摘掉，App 全屏、Dock 盖内容、让位探测也会全乱。
            Logx.event(
                LogTags.SYS,
                "全局 Dock：接管导航栏高度路径",
                "hideStatus" to prefs.hideStatus,
                "hideDock" to prefs.hideDock,
                "immersiveNavigation" to false,
            )
            return SystemNavigationPolicy.apply(
                context = context,
                hideStatus = prefs.hideStatus,
                hideStatusPackages = prefs.hideStatusApps.toSet(),
                navInsets = true,
                // 第193轮：预留带路径**禁止**写 immersive.navigation=*。
                hideNavigation = false,
                // 第204轮：改成按 App 写 immersive.navigation=<包名>。
                //
                // 这些 App 在前台时 [GlobalDockService] 会让位（Dock 隐藏），
                // 原车 CarNavigationBar 就靠这条策略一起藏住 —— 真车 A/B 实测
                // `immersive.navigation=<包>` 能让它 mPolicyVisibility=false。
                // 只列具体包，不写 `*`：预留带对其它 App 仍然有效。
                immersiveNavigationPackages = OemDockPolicy.IMMERSIVE_MEDIA_APPS,
                // 别人写的 immersive.full 段（真车上的 hywebview）原样保留。
                preserveForeignImmersiveFull = true,
            )
        }
        // 没有预留区：让原车导航栏整体让位（B 方案，Lead 已在模拟器验过）。
        val value = OemDockPolicy.buildPolicyValue(
            hideDock = true,
            hideStatus = prefs.hideStatus,
            hideStatusPackages = prefs.hideStatusApps,
        )
        Logx.event(
            LogTags.SYS,
            "全局 Dock：覆盖原车 Dock 路径（immersive.navigation=*）",
            "policy" to value,
        )
        // 第204轮：不再整条写死，别人的 immersive.full / 非沉浸段要留着。
        return SystemNavigationPolicy.overwriteOwned(context, value)
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
            hideStatusPackages = prefs.hideStatusApps,
            immersivePackages = immersivePackages,
        )
        Logx.event(
            LogTags.SYS,
            "全局 Dock 关闭：恢复普通 policy",
            "policy" to value,
            "hideDock" to prefs.hideDock,
        )
        // 第204轮：同样走合并写入，别把用户其它 App 的沉浸策略清空。
        return SystemNavigationPolicy.overwriteOwned(context, value)
    }
}
