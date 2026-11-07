package com.cardesk.launcher.core

/**
 * 桌面（HOME）当前是否在前台。
 *
 * ## 为什么需要（第130轮）
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
 */
object HomeForeground {

    /**
     * 桌面是否在前台。
     *
     * 默认 true：桌面起来时 `onResume` 一定先于画中画看门狗启动，
     * 这里只是兜底一个初值。
     */
    @Volatile
    var isForeground: Boolean = true
}
