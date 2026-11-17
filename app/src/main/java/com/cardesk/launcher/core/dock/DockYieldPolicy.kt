package com.cardesk.launcher.core.dock

/**
 * 全局 Dock「**让位**」的判定（第193轮抽成纯函数）。
 *
 * ## 让位是什么
 *
 * 全局 Dock 是一条跨应用的悬浮/导航栏面板。当前台应用**自己隐藏了导航栏**
 * （沉浸型播放器：网易云 / 汽水 / QQ音乐车机版……），它要的是整块屏幕，
 * 这时 Dock 收起、只留一条 6px 的把手 —— 这就叫「让位」。
 *
 * ## 为什么第193轮要抽出来
 *
 * 2026-11-17 的真车诊断包暴露了一个「所有 App 都让位」的故障，根因是
 * `policy_control` 里写了 `immersive.navigation=*`：
 *
 * - WMS 因此给**每一个** App 打上 `HIDE_NAVIGATION`/`IMMERSIVE`；
 * - `dumpsys window policy` 的 `mLastSystemUiFlags` 是**全局**值，
 *   于是它**永远**带沉浸位 —— 它反映的是**策略**，不是某个 App 的行为；
 * - [ImmersiveDetector] 对任何前台 App 都判「沉浸」⇒ Dock 到处让位。
 *
 * 修法分两层：
 * 1. 预留区路径下**不写** `immersive.navigation=*`（见 [SystemNavigationPolicy]）；
 * 2. 万一还有别处写了通配符沉浸策略，[ImmersiveDetector] 判「探测不可信」
 *    （返回 null）—— 这时**宁可不让位**，也不让所有 App 都让位。
 *
 * 抽成纯函数是为了把「探测不可用 ⇒ 不让位」这条**保守规则**钉在单测里：
 * 判定一旦又变成「探测失败也当 false 之外的东西」，单测会立刻红。
 */
object DockYieldPolicy {

    /**
     * 该不该让位。
     *
     * @param knownImmersive 命中「已知沉浸 App」名单 —— 最可靠，**优先**让位。
     * @param desktopOnScreen 桌面窗口是否还在屏幕上 —— 桌面**永远不让位**
     *        （桌面自己的那条 Dock 是页面内 View，让位了桌面上就一条 Dock 都不剩）。
     * @param probe 运行时探测结果：`true`=该 App 自己藏了导航栏（让位）、
     *        `false`=没藏（不让位）、`null`=**探测不可用**（不可信 ⇒ 不让位）。
     * @return 是否让位。
     */
    fun decide(knownImmersive: Boolean, desktopOnScreen: Boolean, probe: Boolean?): Boolean = when {
        // 名单命中 → 让位，不依赖探测。
        knownImmersive -> true
        // 桌面在前台 → 一定不让位。
        desktopOnScreen -> false
        // 探测不可用 → 保守：不让位（宁可多盖一点，也不要到处让位露出原车导航栏）。
        probe == null -> false
        else -> probe
    }
}
