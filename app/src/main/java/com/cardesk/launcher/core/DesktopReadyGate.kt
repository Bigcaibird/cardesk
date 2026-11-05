package com.cardesk.launcher.core

/**
 * 桌面「完全就绪」闸门（第116轮）。
 *
 * ## 这道闸门是为了防什么
 *
 * 第112轮把**画中画承载**（= 真的启动高德这个 Activity）推迟到了
 * 「桌面完全就绪」之后，链路是：
 *
 * ```
 * MainActivity.onDesktopReady()
 *   → LauncherView.startDeferredAmapActions()
 *     → pipHostingStarted = true
 *       → refreshPip() → PipSurface.show(pkg)
 * ```
 *
 * 这条链在第112轮之后**静默断过一次**：
 * `MainActivity.fadeOutSplash()` 淡出启动页时写的是
 *
 * ```kotlin
 * (current.parent as? ViewGroup)?.removeView(current)
 * current.post { onDesktopReady() }
 * ```
 *
 * `View.post(Runnable)` 在视图**没有 attach 到窗口**时，
 * 会把 Runnable 塞进这个视图自己的 `HandlerActionQueue`，
 * 而那个队列只在视图**重新 attach 到窗口**时才会被执行。
 *
 * 启动页刚被 `removeView` 摘掉 → 已经脱离窗口 → 永远不会重新 attach →
 * **那个 Runnable 永远不会跑** → `onDesktopReady()` 永不执行 →
 * 画中画永远停在占位图（用户报「导航页画中画不显示应用」）。
 *
 * 更麻烦的是它**一声不响**：没有异常、没有崩溃、没有日志，
 * 只有 `StartupTrace` 里少了 `desktopReady` 一个分段。
 * 这种「静默失效」光靠修那一行是不够的 —— 下次换个地方再断一次，
 * 又要靠用户报障才能发现。所以这里给「承载该开始了」这件事加一道
 * **兜底闸门**：桌面首帧之后超过 [watchdogMs] 还没等到 desktopReady，
 * 就自己开始承载。
 *
 * ## 为什么是纯 Kotlin
 *
 * 这段判定（谁先到、幂不幂等、窗口有没有到）不碰任何 Android API，
 * 拆出来就能直接 JVM 单测 —— 不用起模拟器就能把每条分支钉死。
 */
class DesktopReadyGate(
    /** 桌面首帧之后，等 desktopReady 最多等多久（毫秒）。 */
    private val watchdogMs: Long = DEFAULT_WATCHDOG_MS,
) {

    /**
     * 桌面首帧画出来的时刻（uptimeMillis）。
     *
     * 没画出来是 `-1` —— 这时**任何时候都不该开始承载**
     * （桌面还没上屏就去启动高德，正是第112轮要避免的事）。
     */
    private var launcherReadyAtMs: Long = -1L

    /** 是否已经收到「桌面完全就绪」（启动页已淡出）。 */
    private var desktopReady: Boolean = false

    /** 承载是否已经启动过（幂等锁）。 */
    private var started: Boolean = false

    /**
     * 桌面首帧画出来了。
     *
     * 只记第一次（重建时可能重复回调）。
     */
    fun markLauncherReady(nowMs: Long) {
        if (launcherReadyAtMs < 0L) launcherReadyAtMs = nowMs
    }

    /**
     * 桌面完全可见了（启动页淡出）。
     *
     * 这是**正常路径**：立刻就可以开始承载。
     */
    fun markDesktopReady() {
        desktopReady = true
    }

    /**
     * 现在该不该开始承载。
     *
     * - 收到过 desktopReady → 立刻该开始；
     * - 没收到，但桌面首帧之后已经等满 [watchdogMs] → 兜底该开始；
     * - 桌面首帧都还没画出来 → 不该开始。
     */
    fun shouldStart(nowMs: Long): Boolean {
        if (desktopReady) return true
        if (launcherReadyAtMs < 0L) return false
        return nowMs - launcherReadyAtMs >= watchdogMs
    }

    /**
     * 幂等消费：只有「第一次真正该开始承载」时返回 true。
     *
     * 两条调用方（正常路径 / 兜底看门狗）都走这里，
     * 所以谁先到都只会开始一次。
     */
    fun consume(nowMs: Long): Boolean {
        if (started) return false
        if (!shouldStart(nowMs)) return false
        started = true
        return true
    }

    /** 承载是否已经启动过（`refreshPip` 的闸门 + 单测用）。 */
    fun isStarted(): Boolean = started

    /** 是否已经收到 desktopReady（单测 / 日志用）。 */
    fun isDesktopReady(): Boolean = desktopReady

    /** 桌面首帧时刻（没画出来为 -1，单测用）。 */
    fun launcherReadyAt(): Long = launcherReadyAtMs

    companion object {
        /**
         * 兜底窗口（毫秒）。
         *
         * 取 3000 的理由：桌面首帧之后，桌面自己的重活
         * （`startServices()`：天气 / 车控 / 导航引导）大约还要 2~3 秒，
         * 3000ms 刚好落在「桌面彻底安静下来」之后 ——
         * 既不会跟桌面首帧抢 CPU（第112轮的目的），
         * 又不会让用户盯着占位图等太久。
         */
        const val DEFAULT_WATCHDOG_MS = 3000L
    }
}
