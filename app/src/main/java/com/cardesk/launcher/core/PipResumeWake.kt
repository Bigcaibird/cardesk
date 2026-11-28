package com.cardesk.launcher.core

/**
 * Android 9（API 28）画中画承载任务「补一次 onResume 唤醒」的纯逻辑选栈与闸门。
 *
 * ## 根因背景（car15 / Android 9 单 resumed 限制）
 *
 * Android 9 全局同时只能有一个 Activity 处于 `RESUMED` 状态（多屏多 resume 从 Android 10 才引入）：
 *
 * 1. 桌面切到后台（用户打开设置 / 其它 App → `MainActivity.onStop`）时，
 *    `ActivityView` / `PipMirrorHost` 把虚拟屏输出面置空（`VirtualDisplay` 变 `OFF`），
 *    虚拟屏上的高德 `MainMapActivity` 走 `onStop()` 并销毁 `MapSurfaceView`（`surfaceDestroyed`）；
 * 2. 用户按 HOME 回到桌面首页（`page=0`）时，导航页处于离屏保活态，虚拟屏输出面重建变 `ON`，
 *    高德只走到 `onStart()` → 停在 `PAUSED`（全局唯一的 `RESUMED` 槽被 `MainActivity` 占着）；
 * 3. 高德只在 `onResume()` 里重建 `MapSurfaceView`，`onStart()` 不会建；
 *    而为了不把正在导航的高德踢回首页，承载面重建时又不重发 `startActivity(Intent)`；
 * 4. 于是用户从首页切到导航页时，高德仍停在 `PAUSED`、零 `MapSurfaceView` → 画中画纯黑，
 *    必须手动点一下画中画让 WMS 把焦点移到虚拟屏触发 `onResume()` 才恢复。
 *
 * 修法：通过 `IActivityManager.setFocusedStack(stackId)`（严格限定在「我们的虚拟屏」上的栈）
 * 给既有承载任务补一次焦点/唤醒，触发 `onResume()` 重建渲染面，**不重发 Intent、不打断导航路线**。
 */
object PipResumeWake {

    /** `IActivityManager.getAllStackInfos()` 拍平后的单条栈快照。 */
    data class StackTarget(
        val stackId: Int,
        val displayId: Int,
        val topPackage: String?,
        val taskIds: List<Int>,
        val taskNames: List<String>,
    )

    /** 选出的待唤醒目标（一定落在我们的虚拟屏上）。 */
    data class PickedStack(
        val stackId: Int,
        val topTaskId: Int?,
    )

    /**
     * 当前是否应当执行一次「补 onResume 唤醒」。
     *
     * 五道闸全过才动手：
     * - [needsWake]：确实经历过 `onStop` / surface 销毁重建或离屏冷启（避免反复抢焦点）；
     * - [contentVisible]：导航页画中画此刻正在屏幕上可见（绝不在首页偷焦点）；
     * - [hostOnScreen]：桌面窗口在屏幕上（绝不打断前台全屏应用）；
     * - [hasContent] 且 [!pkgBlank]：承载面和包名有效。
     */
    fun shouldWake(
        needsWake: Boolean,
        contentVisible: Boolean,
        hostOnScreen: Boolean,
        hasContent: Boolean,
        pkgBlank: Boolean,
    ): Boolean = needsWake && contentVisible && hostOnScreen && hasContent && !pkgBlank

    /**
     * 从系统全部栈里挑出**严格落在我们虚拟屏 [ourDisplayId] 上**的承载栈。
     *
     * 绝不碰主屏（`displayId <= 0`）或其它显示器的栈 —— 就算用户把高德全屏打开在主屏，
     * 这里也会返回 null，绝不误动主屏任务。
     */
    fun pickStackOnDisplay(
        ourDisplayId: Int?,
        pkg: String,
        stacks: List<StackTarget>,
    ): PickedStack? {
        if (ourDisplayId == null || ourDisplayId <= 0) return null
        val onOurs = stacks.filter { it.displayId == ourDisplayId && it.taskIds.isNotEmpty() }
        if (onOurs.isEmpty()) return null
        val matched = if (pkg.isNotBlank()) {
            onOurs.firstOrNull { stack ->
                stack.topPackage == pkg ||
                    stack.taskNames.any { it == pkg || it.startsWith("$pkg/") }
            }
        } else {
            null
        }
        val chosen = matched ?: onOurs.first()
        return PickedStack(
            stackId = chosen.stackId,
            topTaskId = chosen.taskIds.lastOrNull(),
        )
    }
}
