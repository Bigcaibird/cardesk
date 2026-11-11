package com.cardesk.launcher.core.dock

/**
 * 第144轮：全局 Dock **风量键**的语义决策（纯函数，可单测）。
 *
 * ## 背景：桌面上那条 Dock 就是「全局 Dock 窗口」
 *
 * 第101轮起用 RRO 覆盖包把系统导航栏高度改成 112px，
 * 全局 Dock 用 `TYPE_NAVIGATION_BAR_PANEL`(2024) 占住这块区域；
 * 桌面自己那条 Dock 在 `globalReserved` 时被 GONE 掉了
 * （否则会出现「两条 Dock 叠在一起」）。
 *
 * 于是**桌面上用户点到的风量键，点的其实是全局 Dock 窗口里的那个键**，
 * 而全局 Dock 没有页面上下文，只能自己决定语义：
 *
 * - 用户眼前是桌面 → 弹**空调浮层**（回桌面执行，见 `MainActivity.ACTION_CLIMATE`）；
 * - 别的应用在前台 → 直调车控**风量 +1**，不打断当前应用。
 *
 * ## 第142~143轮踩的坑：用「有没有焦点」判断「用户眼前是不是桌面」
 *
 * 第142轮拿 `MainActivity` 的 `onResume`/`onPause` 当判据（`homeForeground`），
 * 真机上**没生效**：桌面上点风量键还是「风量 +1」。
 *
 * 原因在 2026-11-07 的真机日志里写着（见 `core/HomeForeground` 的类注释）：
 *
 * ```
 * 19:55:56.832 I CarDesk/App : onResume
 * 19:55:57.109 I CarDesk/NavUI : ActivityView 启动承载应用 pkg=com.autonavi.amapautp.yh
 * 19:55:57.146 I CarDesk/App : onPause      ← 焦点让给画中画承载面，桌面还亮着
 * ```
 *
 * 桌面把自己的画中画（`ActivityView` + `VirtualDisplay`）里嵌了高德，
 * 承载应用一起来就**把焦点抢走**，桌面走 `onPause` —— 可**桌面窗口还在屏幕上**，
 * 用户眼前就是桌面，地图就在桌面里的画中画窗口上。
 *
 * 于是焦点口径的 `homeForeground` 变成 false，
 * 风量键被判成「不在桌面上」→ 直调 `stepFan(1)` → 用户看到的还是「风量 +1」。
 *
 * ## 判据改成「窗口还在不在屏幕上」
 *
 * `onStart`/`onStop` 才是「窗口还在不在屏幕上」的口径：
 *
 * - 别的应用全屏盖上来 → 桌面 `onStop` → 不在屏幕上 → 风量 +1 ✓；
 * - 画中画承载面启动 → 桌面只是 `onPause`，**不会 `onStop`**（窗口还亮着）
 *   → 还在屏幕上 → 弹空调浮层 ✓。
 *
 * 所以这里只做一件事：把「桌面在不在屏幕上」翻译成「风量键该干什么」。
 */
object DockFanPolicy {

    /** 弹**空调浮层**（回桌面执行 `MainActivity.ACTION_CLIMATE`）。 */
    const val ACTION_OPEN_CLIMATE = "open_climate"

    /** 直调车控把**风量 +1**（不打断当前应用）。 */
    const val ACTION_STEP_FAN = "step_fan"

    /**
     * 风量键该干什么。
     *
     * @param desktopOnScreen 桌面的窗口是否还在屏幕上（`onStart`/`onStop` 口径）。
     */
    fun decide(desktopOnScreen: Boolean): String =
        if (desktopOnScreen) ACTION_OPEN_CLIMATE else ACTION_STEP_FAN
}
