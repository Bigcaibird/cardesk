package com.cardesk.launcher.core

/**
 * 把承载应用的 task **搬到**我们的 VirtualDisplay 上（第141轮，纯逻辑）。
 *
 * ## 为什么需要「搬」这一步
 *
 * 画中画能不能成立，取决于承载应用的 task 是不是**真的挂在我们的虚拟屏上**。
 * 正常情况下，启动时带上 `launchDisplayId` 就会一步到位：
 *
 * - **车机（真机 ROM）**：实测正常 —— task 直接落在我们的虚拟屏上；
 * - **AOSP 9 官方模拟器**：**落不到** —— 新建的 task 仍然被 AMS 放在
 *   **默认显示器（display 0）**上 → 承载应用**全屏抢屏**，
 *   画中画那块只剩一块黑（第140轮实测，截图里高德是全屏的）。
 *
 * 根因（AOSP 9 源码 `ActivityStackSupervisor.findTaskLocked(r, displayId)`）：
 *
 * ```java
 * // 跨**所有** display 找 affinity 匹配的 task；
 * // 目标 display 只是「优先」而不是「限定」——
 * // 只有在别处**没有**更合适的匹配时，才会把跨屏的匹配也返回。
 * else if (mTmpFindTaskResult.r.getDisplayId() == displayId) {
 *     affinityMatch = mTmpFindTaskResult.r;
 * }
 * ```
 *
 * 官方 `VirtualDisplay Launcher` 示例的做法就是**启动后再搬**：
 * `ActivityManager.moveTaskToDisplay(taskId, virtualDisplayId, true)`。
 *
 * ## 为什么这一步是「安全的兜底」而不是「改动主路径」
 *
 * 主路径**一个字都不改**：启动顺序、旗标、Intent 全部保持车机验证过的样子。
 * 这��只在**启动之后**补一脚：
 *
 * - 已经在我们的虚拟屏上 → **什么都不做**（车机上的常态）；
 * - 不在 → 搬过来（模拟器上的常态）。
 *
 * → 对车机而言是**零行为变化**；对模拟器而言是把「全屏抢屏」纠正成真嵌入。
 *
 * ## 平台差异（AOSP 9 vs AOSP 10）
 *
 * | API | AMS 搬 task 的方法 | 说明 |
 * |---|---|---|
 * | 28（AOSP 9） | `IActivityManager.moveStackToDisplay(stackId, displayId)` | **实测有效**（模拟器上把高德的 task 从 display 0 搬到虚拟屏） |
 * | 29+（AOSP 10） | `moveTaskToDisplay(taskId, displayId, toFront)` | 官方示例用的就是它 |
 *
 * AOSP 9 **没有** `moveTaskToDisplay`（那是 AOSP 10 才有的）——
 * 第140轮在设备 framework 里数过字符串，`moveTaskToDisplay` 命中 **0** 次。
 *
 * 本文件只管「**搬不搬、搬哪个、用哪种搬法**」这些取舍；
 * 反射调 AMS 的活儿在 `HostTaskMigration`（Android 侧）。
 */
object HostTaskMigrator {

    /**
     * 一个任务的快照（我们能拿到的字段）。
     *
     * @param taskId 任务 id
     * @param stackId 任务所在**栈**的 id（AOSP 9 搬栈要用；null = 读不到）
     * @param displayId 任务当前所在的 display（**API 29+ 才有**；API 28 = null）
     * @param pkg 任务里的应用包名
     * @param screenWidthDp 任务所在屏的**显示配置**宽（dp）
     * @param screenHeightDp 任务所在屏的显示配置高（dp）
     */
    data class TaskSnapshot(
        val taskId: Int,
        val stackId: Int?,
        val displayId: Int?,
        val pkg: String,
        val screenWidthDp: Int? = null,
        val screenHeightDp: Int? = null,
    )

    /** 一块屏的度量（dp）。 */
    data class DisplayMetrics(
        val widthDp: Int,
        val heightDp: Int,
    )

    /** 搬运方式。 */
    enum class MoveApi {
        /** `moveTaskToDisplay(taskId, displayId, toFront)`（AOSP 10+）。 */
        TASK_TO_DISPLAY,

        /** `moveStackToDisplay(stackId, displayId)`（AOSP 9 / API 28）。 */
        STACK_TO_DISPLAY,
    }

    /**
     * 挑出「承载应用自己的那个 task」。
     *
     * ## 挑的规则
     *
     * 1. **包名要匹配** —— 别人家的 task 一律不看；
     * 2. **优先挑有 stackId 的** —— AOSP 9 搬栈必须要它；
     * 3. **同包名多个 task 时取最靠前的那个** ——
     *    `ActivityManager.getRunningTasks()` 是**按最近使用**排序的，
     *    所以第一个就是我们刚拉起的那个。
     *
     * @param tasks 任务快照列表（**按最近使用排序**，原样传进来）
     * @param pkg 承载应用的包名
     * @return 挑中的 task；null = 没找到
     */
    fun pickHostTask(tasks: List<TaskSnapshot>, pkg: String): TaskSnapshot? {
        if (pkg.isBlank()) return null
        val mine = tasks.filter { it.pkg == pkg }
        if (mine.isEmpty()) return null
        // 有 stackId 的优先（搬栈要用）
        return mine.firstOrNull { it.stackId != null } ?: mine.first()
    }

    /**
     * 承载任务的 task **在不在我们的虚拟屏上**？
     *
     * ## 为什么这条判据要写两套（第141轮实测出来的）
     *
     * `ActivityManager.RunningTaskInfo` 的字段集**按平台不同**——
     * 第141轮在 **API 28** 设备上把运行时真实字段打了出来：
     *
     * ```
     * baseActivity, configuration, description, id, lastActiveTime,
     * numActivities, numRunning, resizeMode, stackId,
     * supportsSplitScreenMultiWindow, thumbnail, topActivity
     * ```
     *
     * **没有 `displayId`**！它是 AOSP 10 才加的。
     * → API 28 上「第130轮那套 `task.displayId == 我们的 id`」的判据
     * **从来就读不到值**（恒为 null → 恒判「不知道」）。
     *
     * 所以 API 28 上改用**第二套证据**：任务的**显示配置**
     * （`RunningTaskInfo.configuration`）。
     * 任务在**哪块屏**上，它的配置就是**那块屏**的配置：
     *
     * | 任务配置的宽度 | 结论 |
     * |---|---|
     * | 跟我们的虚拟屏一样宽 | **在** → 什么都不用做 |
     * | 跟主屏（我们自己的窗口所在那块屏）一样宽 | **不在** → 搬过来 |
     * | 两边都不一样 | **不知道** → **不搬**（宁可不动，也不能误搬） |
     *
     * ## 为什么只比**宽度**，不比高度
     *
     * 实测（API 28 模拟器，1920×1080@160dpi 的主屏 + 906×867@160dpi 的虚拟屏）：
     *
     * ```
     * 主屏： init=1920x1080  app=1920x968
     * 任务配置： screenWidthDp=1920  screenHeightDp=944
     * ```
     *
     * **高度三个口径互相对不上**（1080 / 968 / 944 —— 差的是系统装饰：
     * 状态栏、导航栏），拿高度比必然误判。
     * 而**宽度不受系统装饰影响**（1920 vs 906，干净利落）→ 只比宽度。
     *
     * 「两边都不一样」这一档是安全网：承载应用如果**自己强制了显示配置**
     * （自己定分辨率/方向），它的宽度就可能跟两块屏都不一样 ——
     * 这时**不能**当成「不在」去搬，否则会动一个本来就在对的地方的 task。
     *
     * @param task 任务快照
     * @param ourDisplayId 我们的虚拟屏 id（null = 读不到）
     * @param ourMetrics 我们的虚拟屏度量（null = 读不到）
     * @param mainMetrics 主屏（我们自己的窗口所在那块屏）的度量（null = 读不到）
     * @return true = 在我们的虚拟屏上；false = 不在；**null = 不知道**
     */
    fun isOnOurDisplay(
        task: TaskSnapshot,
        ourDisplayId: Int?,
        ourMetrics: DisplayMetrics?,
        mainMetrics: DisplayMetrics?,
    ): Boolean? {
        // ① 有 displayId（API 29+）→ 直接比，**精确**
        val d = task.displayId
        if (d != null && ourDisplayId != null) return d == ourDisplayId

        // ② 只能用显示配置比（API 28）—— 只比**宽度**
        val tw = task.screenWidthDp ?: return null
        if (ourMetrics != null && tw == ourMetrics.widthDp) return true
        if (mainMetrics != null && tw == mainMetrics.widthDp) return false
        // 两边都不像 → 不知道 → 不搬
        return null
    }

    /**
     * 选一种搬法。
     *
     * @param task 任务快照
     * @param taskApiAvailable 平台是否支持 `moveTaskToDisplay`（AOSP 10+）
     * @return 搬法；null = **搬不了**（缺必要字段）
     */
    fun planMove(task: TaskSnapshot, taskApiAvailable: Boolean): MoveApi? {
        if (taskApiAvailable) return MoveApi.TASK_TO_DISPLAY
        // AOSP 9：只能搬栈 → 必须有 stackId
        return if (task.stackId != null) MoveApi.STACK_TO_DISPLAY else null
    }

    /**
     * 第 n 次尝试之间隔多久（毫秒）。
     *
     * 为什么要退避：承载应用的 task 是**异步**建起来的
     * （AMS 那边还要走一遍启动流程），刚发起启动时它可能还不存在。
     * 一开始隔得短（任务通常已经在了），
     * 后面拉长（免得一直空转）。
     *
     * @param attempt 第几次尝试（0 = 第一次）
     */
    fun delayMsForAttempt(attempt: Int): Long = when (attempt) {
        0 -> 0L
        1 -> 300L
        2 -> 800L
        3 -> 1_500L
        else -> 2_500L
    }

    /** 最多试几次。 */
    const val MAX_ATTEMPTS = 5
}
