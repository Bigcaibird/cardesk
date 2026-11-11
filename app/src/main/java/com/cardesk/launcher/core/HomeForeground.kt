package com.cardesk.launcher.core

/**
 * 桌面（HOME）当前的两个状态：**有没有焦点**、**窗口还在不在屏幕上**。
 *
 * ## 为什么需要（第130轮 / 第144轮）
 *
 * 画中画黑屏自愈**必须只在用户看得见画中画的时候做**。
 *
 * 反例：用户点「全屏打开」把高德拉到全屏 → 高德的 task 从我们的
 * VirtualDisplay **搬到默认显示器**上 → 画中画这边确实「没有承载画面」了。
 * 这时候如果黑屏自愈还在跑，就会**把高德又拉回画中画**，
 * 打断用户正在用的全屏应用 —— 这是比黑屏更烦人的故障。
 *
 * 所以判据里加一道闸：**桌面不在前台 → 不做任何黑屏自愈**。
 *
 * ## 为什么不用 `ActivityManager` 判前台
 *
 * 判前台应用需要 `REAL_GET_TASKS` 权限，车机上拿不到；
 * 而桌面自己的 Activity 生命周期是**免费的、准的** ——
 * 别的应用盖上来时桌面一定走 `onPause`。
 *
 * ## 第144轮：`onPause` ≠ 桌面不在屏幕上
 *
 * 桌面是 HOME，**自己的窗口里会嵌别家的应用**（画中画承载面 = `ActivityView`
 * + `VirtualDisplay`，见 `ui/embed/ActivityEmbed.kt`）。承载应用启动的那一刻，
 * 系统会把焦点交给承载面，于是我们的 Activity 走了一趟 `onPause`，
 * **但桌面窗口仍然好好地亮在屏幕上**（地图就在桌面里的画中画窗口中）。
 *
 * 真机日志（2026-11-07 19:55:56.832 ~ 19:56:06）就是这个形态：
 *
 * ```
 * 19:55:56.832 I CarDesk/App : onResume
 * 19:55:56.922 I CarDesk/NavUI : 承载面重建，重新拉起承载应用 pkg=com.autonavi.amapautp.yh
 * 19:55:57.109 I CarDesk/NavUI : ActivityView 启动承载应用 pkg=com.autonavi.amapautp.yh
 * 19:55:57.146 I CarDesk/App : onPause        ← 焦点没了，但桌面还亮着
 * （之后 9 秒内再没有 onResume）
 * ```
 *
 * 所以「有没有焦点」（`onResume`/`onPause`）和「窗口还在不在屏幕上」
 * （`onStart`/`onStop`）必须**分成两个信号**：
 *
 * - [isForeground]：焦点口径 —— 用来判断「别的应用是不是在前台」；
 * - [isOnScreen]：窗口口径 —— 用来判断「用户眼前是不是我们的桌面」。
 *
 * 第144轮全局 Dock 的风量键就靠后者：桌面上那条 Dock 就是全局 Dock 窗口，
 * 用焦点口径判会在画中画承载面启动后误判成「不在桌面上」，
 * 于是风量键又变回「风量 +1」（用户反馈的现象）。
 */
object HomeForeground {

    /**
     * 桌面是否**有焦点**（`onResume`/`onPause` 口径）。
     *
     * 默认 true：桌面起来时 `onResume` 一定先于画中画看门狗启动，
     * 这里只是兜底一个初值。
     */
    @Volatile
    var isForeground: Boolean = true

    /**
     * 桌面窗口是否**还在屏幕上**（`onStart`/`onStop` 口径）。
     *
     * 默认 false：进程刚起来、桌面还没 `onStart` 之前，
     * 宁可当成「不在屏幕上」（别的应用在前台时风量键就该直调车控）。
     */
    @Volatile
    var isOnScreen: Boolean = false
}
