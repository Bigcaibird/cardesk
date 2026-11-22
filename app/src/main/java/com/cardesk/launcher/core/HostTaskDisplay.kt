package com.cardesk.launcher.core

/**
 * 「承载应用的 task 到底在哪个屏上」的**四态判据** —— 纯逻辑，不碰 Android 类（第224轮）。
 *
 * ## 为什么要有这个文件
 *
 * 用户报「画中画的高德跑首页来了」的第二条根因：
 * 弹文件选择器时 cardesk 走到 `onStop` → `ActivityView` 的 surface 被销毁
 * （AOSP 9 只做 `mVirtualDisplay.setSurface(null)`，**不 release**）→
 * 回到桌面时 surface 重建 → `ActivityEmbed.ReadyStarter.onSurfaceCreated()`
 * 的重建分支**无条件** `startActivity` → 高德被重新拉起 → 落回首页。
 *
 * 但「surface 重建」**不等于**「承载应用需要重新拉起」：
 * 绝大多数情况下应用还活着、task 还在我们的虚拟屏上，只是它的画面没地方画了。
 *
 * ## 四态语义（关键：判不准 → 按「没黑」处理）
 *
 * | 态 | 含义 | 是否重新拉起 |
 * |---|---|---|
 * | [State.ON_OUR_DISPLAY] | task 明确在我们的虚拟屏上 | **否** |
 * | [State.OFF_OUR_DISPLAY] | task 明确在别的屏上（被搬到主屏 / 外接屏） | 是（搬回来） |
 * | [State.NOT_FOUND] | 查了，但找不到承载应用的 task（被系统回收） | 是 |
 * | [State.UNKNOWN] | 判不准（查不到 task 列表 / 我们自己的 displayId 未知 / 正在搬家） | **否** |
 *
 * 为什么 UNKNOWN 不重启 —— 这是**代价不对称**：
 * 误判成「需要重启」的代价是**把用户正在用的应用踢回首页**（用户报的就是这个现象），
 * 而漏判的代价只是「画面真的没了但没自愈」，那还有进程看门狗
 * （`PipSurface.isProcessAlive()`，连续 3 次看不到进程才动手）兜底。
 * ⇒ **判不准就当没黑。**
 */
object HostTaskDisplay {

    /** 承载应用 task 的屏幕归属。 */
    enum class State {
        /** task 明确在我们的虚拟屏上 → 不重启。 */
        ON_OUR_DISPLAY,

        /** task 明确在别的屏上 → 需要搬回来（重新拉起）。 */
        OFF_OUR_DISPLAY,

        /** 查过了，找不到承载应用的 task → 需要重新拉起。 */
        NOT_FOUND,

        /** 判不准 → 按「没黑」处理，不重启。 */
        UNKNOWN,
    }

    /**
     * 由「我们的虚拟屏 id」+「承载应用各 task 的 displayId」推状态。
     *
     * @param ourDisplayId 我们自己的 VirtualDisplay id；`null` = 查不到（能力缺失）
     * @param taskDisplayIds 匹配到承载应用的 task 各自的 displayId（反射取不到时为 `null`）
     * @param migrating 是否正处于 `HostTaskMigration` 搬家窗口内（此时一切判断都不可信）
     */
    fun stateOf(
        ourDisplayId: Int?,
        taskDisplayIds: List<Int?>,
        migrating: Boolean = false,
    ): State {
        // 搬家途中 task 的 displayId 正在变，任何快照都可能是中间态。
        if (migrating) return State.UNKNOWN
        // 连自己在哪个屏都不知道，就没法比较归属。
        if (ourDisplayId == null) return State.UNKNOWN
        // 查到了全局 task 列表，但里面没有承载应用的 task ⇒ 被系统回收了。
        if (taskDisplayIds.isEmpty()) return State.NOT_FOUND

        var sawUnknown = false
        for (displayId in taskDisplayIds) {
            when {
                displayId == null -> sawUnknown = true
                displayId == ourDisplayId -> return State.ON_OUR_DISPLAY
            }
        }
        // 有匹配的 task，但一个 displayId 都读不到 ⇒ 判不准。
        return if (sawUnknown) State.UNKNOWN else State.OFF_OUR_DISPLAY
    }

    /**
     * 承载面重建时，**是否需要重新拉起承载应用**。
     *
     * 只有 [State.OFF_OUR_DISPLAY] 与 [State.NOT_FOUND] 需要；
     * [State.ON_OUR_DISPLAY] 与 [State.UNKNOWN] 一律不动（判不准就当没黑）。
     */
    fun shouldRelaunch(state: State): Boolean = when (state) {
        State.OFF_OUR_DISPLAY, State.NOT_FOUND -> true
        State.ON_OUR_DISPLAY, State.UNKNOWN -> false
    }

    /**
     * 像素判黑的自愈路径（`PipSurface.checkBlackScreen`）是否可以把「task 不在我们屏上」
     * 当作黑屏证据。
     *
     * 与 [shouldRelaunch] 不同：这里 [State.NOT_FOUND] **不算**黑屏证据 ——
     * 「找不到 task」更可能是权限/时序问题，交给进程看门狗，不要凭它重建。
     */
    fun isOffScreenForBlackHeal(state: State): Boolean = state == State.OFF_OUR_DISPLAY
}
