package com.cardesk.launcher.core.dock

/**
 * 全局 Dock 该不该可见（第204轮，纯函数）。
 *
 * ## 背景
 *
 * 第201轮「Dock 永不让位」解决的是「我们的 Dock 一让位，原车 CarNavigationBar 就露出来」。
 * 但代价是沉浸型音乐 App（网易云 / 汽水）底部 112px 一直被我们的 Dock 压着。
 *
 * 第204轮确认了原车栏**可以**按 App 藏掉（`policy_control` 写
 * `immersive.navigation=<包名>`，真车 A/B 实测 `CarNavigationBar.mPolicyVisibility=false`），
 * 于是可以回到「对沉浸型 App 让位」：
 *
 * - 这些 App 在前台 → 我们的 Dock 藏起来；
 * - 同时 policy 把原车栏也藏掉 → **两边都不挡画面**，真全屏。
 *
 * 桌面在前台时 Dock 必须可见（它就是桌面的一部分）；预留区生效时同理
 * （Dock 是系统导航栏的替代品）。
 */
object DockVisibility {

    /**
     * @param nativeInsets 预留区（112px 覆盖包）是否生效
     * @param homeForeground 桌面是否在前台
     * @param immersiveAppForeground 前台的 App 是否属于「我们让位」的沉浸名单
     */
    fun want(
        nativeInsets: Boolean,
        homeForeground: Boolean,
        immersiveAppForeground: Boolean,
    ): Boolean = (nativeInsets || !homeForeground) && !immersiveAppForeground
}
