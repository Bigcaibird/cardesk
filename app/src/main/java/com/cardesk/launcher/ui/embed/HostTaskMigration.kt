package com.cardesk.launcher.ui.embed

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.cardesk.launcher.core.HostTaskMigrator
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 把承载应用的 task **搬到**我们的 VirtualDisplay 上（第141轮，Android 侧）。
 *
 * ## 这件事的定位
 *
 * **不是**改启动路径，而是**启动之后补一脚**。
 *
 * 启动路径（顺序、旗标、Intent）保持车机验证过的样子，一个字不改；
 * 这里只在启动之后看一眼：
 *
 * - 承载应用的 task **已经在**我们的虚拟屏上 → **什么都不做**；
 * - **不在** → 搬到我们的虚拟屏上。
 *
 * 为什么要补这一脚（第140轮实测取证）：
 *
 * | 环境 | 启动后 task 落在哪 | 画中画 |
 * |---|---|---|
 * | 车机（真机 ROM） | 我们的虚拟屏 ✓ | 正常 |
 * | **AOSP 9 官方模拟器** | **默认显示器（display 0）** ✗ | **全屏抢屏**，画中画一块黑 |
 *
 * AOSP 9 落回主屏的根因在 AMS 源码里：
 * `ActivityStackSupervisor.findTaskLocked(r, displayId)` **跨所有 display**
 * 找 affinity 匹配的 task，目标 display 只是「优先」不是「限定」。
 * 官方 `VirtualDisplay Launcher` 示例的做法就是**启动后再搬**
 * （`moveTaskToDisplay`）。
 *
 * ## 为什么这一步对车机是「零风险」
 *
 * 搬之前先判「**已经在我们屏上了吗**」——
 * 已经在 → 直接返回，**一次 AMS 调用都不发**。
 * 车机上这就是常态 → 行为完全不变。
 *
 * 只有「不在」才搬，而「不在」在车机上恰恰就是**第139轮那个故障**
 * （画中画变成全屏）—— 搬回来正是我们想要的结果。
 *
 * ## 平台差异
 *
 * | API | 用的 AMS 方法 | 实测 |
 * |---|---|---|
 * | 28（AOSP 9） | `IActivityManager.moveStackToDisplay(stackId, displayId)` | ✓ 模拟器上把高德的 task 从 display 0 搬到虚拟屏 |
 * | 29+ | `moveTaskToDisplay(taskId, displayId, toFront)` | 启动本来就落在虚拟屏上，这一步基本用不到 |
 *
 * AOSP 9 **没有** `moveTaskToDisplay`（设备 framework 里数过，命中 0 次），
 * 所以 28 上只能走「搬栈」。
 */
object HostTaskMigration {

    /** 最多看多少个任务（`getRunningTasks` 的上限）。 */
    private const val MAX_TASKS = 80

    private val handler = Handler(Looper.getMainLooper())

    /** 正在搬移中的包名（看门狗要靠它**暂缓判黑**，见 [isMigrating]）。 */
    private val inFlight = LinkedHashSet<String>()

    /** 已取消的搬移（包名 → 待执行的 Runnable）。 */
    private val pending = HashMap<String, Runnable>()

    /**
     * 启动承载应用之后调它：**在几秒内**把承载应用的 task 搬到我们的虚拟屏。
     *
     * @param view 承载面（用来拿我们的 VirtualDisplay id）
     * @param pkg 承载应用的包名
     */
    fun schedule(view: View, pkg: String?) {
        val target = pkg ?: return
        if (target.isBlank()) return
        cancel(target)
        inFlight += target
        attempt(view, target, 0)
    }

    /**
     * 这个包**正在搬移**中吗？
     *
     * ## 看门狗为什么要问这个
     *
     * 搬移完成之前，承载应用的 task 确实**不在**我们的虚拟屏上
     * （它还在主屏，等着被搬）。
     * 这时候黑屏判据会把它判成「黑屏」→ **重启承载应用** → 越搬越乱。
     *
     * 所以搬移期间判据一律按「**不知道**」处理（= 不判黑、不动作）。
     *
     * @param pkg 承载应用的包名
     */
    fun isMigrating(pkg: String): Boolean = synchronized(inFlight) { inFlight.contains(pkg) }

    /** 取消某个包正在进行的搬移（承载面被拆了 / 换应用了）。 */
    fun cancel(pkg: String) {
        synchronized(inFlight) {
            inFlight.remove(pkg)
            pending.remove(pkg)?.let { handler.removeCallbacks(it) }
        }
    }

    /** 取消全部搬移（桌面退出时用）。 */
    fun cancelAll() {
        synchronized(inFlight) {
            val all = pending.values.toList()
            pending.clear()
            inFlight.clear()
            all.forEach { handler.removeCallbacks(it) }
        }
    }

    /** 第 attempt 次尝试。 */
    private fun attempt(view: View, pkg: String, attempt: Int) {
        if (attempt >= HostTaskMigrator.MAX_ATTEMPTS) {
            finish(pkg, moved = false, reason = "尝试次数用尽")
            return
        }
        val r = runOnce(view, pkg)
        when (r) {
            Result.DONE -> finish(pkg, moved = true, reason = "已在我们的虚拟屏上")
            // 搬完**不等于搬稳了** —— AMS 调用没报错 ≠ task 真留在虚拟屏上。
            // （实测：高德进程自己起活动时会把 task 又拉回主屏。）
            // 所以搬完**再验一次**：还在 → 收工；不在 → 继续追。
            Result.MOVED -> scheduleNext(view, pkg, attempt, "已搬一次，再验一次是否搬稳")
            Result.NOT_FOUND -> scheduleNext(view, pkg, attempt, "还没找到承载应用的 task")
            Result.NO_DISPLAY_ID -> scheduleNext(view, pkg, attempt, "取不到我们的虚拟屏 id")
            Result.UNKNOWN_DISPLAY -> scheduleNext(view, pkg, attempt, "读不到 task 的 displayId（判据不可用）")
            Result.CANNOT_MOVE -> scheduleNext(view, pkg, attempt, "搬不了（缺 stackId）")
            Result.FAILED -> scheduleNext(view, pkg, attempt, "搬移调用失败")
        }
    }

    private fun scheduleNext(view: View, pkg: String, attempt: Int, reason: String) {
        val next = attempt + 1
        if (next >= HostTaskMigrator.MAX_ATTEMPTS) {
            finish(pkg, moved = false, reason = "$reason，且已达最大尝试次数")
            return
        }
        val delay = HostTaskMigrator.delayMsForAttempt(next)
        val runnable = Runnable { attempt(view, pkg, next) }
        synchronized(inFlight) {
            if (!inFlight.contains(pkg)) return
            pending[pkg]?.let { handler.removeCallbacks(it) }
            pending[pkg] = runnable
        }
        Logx.d(
            LogTags.NAV_UI,
            "承载任务搬移重试",
            "pkg" to pkg,
            "attempt" to next,
            "delay" to delay,
            "reason" to reason,
        )
        handler.postDelayed(runnable, delay)
    }

    private fun finish(pkg: String, moved: Boolean, reason: String) {
        synchronized(inFlight) {
            inFlight.remove(pkg)
            pending.remove(pkg)?.let { handler.removeCallbacks(it) }
        }
        if (moved) {
            Logx.i(LogTags.NAV_UI, "承载任务搬移完成", "pkg" to pkg, "reason" to reason)
        } else {
            Logx.w(
                LogTags.NAV_UI,
                "承载任务没能搬到我们的虚拟屏（画中画可能显示为全屏）",
                "pkg" to pkg,
                "reason" to reason,
            )
        }
    }

    /** 一次尝试的结果。 */
    private enum class Result {
        /** 已经在我们的虚拟屏上。 */
        DONE,

        /** 搬完了。 */
        MOVED,

        /** 还没找到承载应用的 task（应用刚起来，task 还没建好）。 */
        NOT_FOUND,

        /** 取不到我们的虚拟屏 id。 */
        NO_DISPLAY_ID,

        /** 搬不了（缺 stackId）。 */
        CANNOT_MOVE,

        /** AMS 调用抛了。 */
        FAILED,

        /**
         * 找到了 task，但**读不到它的 displayId** → 不知道它在哪个屏上。
         *
         * 这跟「已在我们的屏上」是**两回事**：
         * 前者 = 判据不可用（读不到隐藏字段），后者 = 确确实实在上面。
         * 混为一谈会让排障时把「读不到」当成「正常」（第141轮踩过）。
         */
        UNKNOWN_DISPLAY,
    }

    /** 跑一次。 */
    private fun runOnce(view: View, pkg: String): Result {
        val ctx = view.context
        val ourId = ActivityEmbed.virtualDisplayIdOf(view) ?: return Result.NO_DISPLAY_ID
        // 两块屏的度量（dp）：
        // - **我们的虚拟屏** —— 判「在不在我们屏上」要比它；
        // - **主屏**（我们自己的窗口所在那块屏）—— 比不上它就说明「不在我们屏上」。
        val ourMetrics = metricsOf(displayById(ctx, ourId))
        val mainMetrics = metricsOf(view.display)
        val tasks = runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getRunningTasks(MAX_TASKS)
        }.getOrNull() ?: return Result.NOT_FOUND
        if (tasks.isEmpty()) return Result.NOT_FOUND

        val snapshots = tasks.map { t ->
            HostTaskMigrator.TaskSnapshot(
                taskId = readInt(t, "taskId") ?: readInt(t, "id") ?: -1,
                stackId = readInt(t, "stackId"),
                // API 29+ 才有这个字段；API 28 上恒为 null（→ 走「显示配置」那条路）
                displayId = ActivityEmbed.displayIdOfTask(t),
                pkg = t.baseActivity?.packageName ?: t.topActivity?.packageName ?: "",
                // 任务所在屏的显示配置（dp 尺寸）—— API 28 上判「在哪块屏」就靠它
                screenWidthDp = readConfigDp(t, "screenWidthDp"),
                screenHeightDp = readConfigDp(t, "screenHeightDp"),
            )
        }
        val host = HostTaskMigrator.pickHostTask(snapshots, pkg) ?: return Result.NOT_FOUND
        // 诊断行（**限频**：每次搬移只有几行）：把「我们看到的」原样记下来。
        //
        // 为什么必须记：`displayId` / `stackId` 都是**隐藏字段**，
        // 各版本字段集又不一样（AOSP 9 的 RunningTaskInfo **没有** displayId），
        // 所以把真实字段名和真实值打出来，排障时一眼能看出「读到了什么」。
        Logx.d(
            LogTags.NAV_UI,
            "承载任务快照",
            "pkg" to pkg,
            "our" to ourId,
            "task" to host.taskId,
            "stack" to host.stackId,
            "display" to host.displayId,
            "size" to "${host.screenWidthDp}x${host.screenHeightDp}",
            "ourSize" to ourMetrics?.let { "${it.widthDp}x${it.heightDp}" },
            "mainSize" to mainMetrics?.let { "${it.widthDp}x${it.heightDp}" },
            "fields" to taskFieldNames(),
        )

        // 判「在不在我们的虚拟屏上」——
        // **不知道就不搬**（宁可不动，也不能误搬一个本来就在对的地方的 task）。
        val onOurs = HostTaskMigrator.isOnOurDisplay(host, ourId, ourMetrics, mainMetrics)
        if (onOurs == null) return Result.UNKNOWN_DISPLAY
        if (onOurs) return Result.DONE

        // 平台支不支持 task 级搬法（AOSP 10+ 有；AOSP 9 只有搬栈）
        val taskApi = hasMethod("moveTaskToDisplay", 3)
        val api = HostTaskMigrator.planMove(host, taskApi) ?: return Result.CANNOT_MOVE

        val ok = when (api) {
            HostTaskMigrator.MoveApi.TASK_TO_DISPLAY ->
                moveTaskToDisplay(host.taskId, ourId)
            HostTaskMigrator.MoveApi.STACK_TO_DISPLAY ->
                moveStackToDisplay(host.stackId!!, ourId)
        }
        if (!ok) return Result.FAILED
        Logx.i(
            LogTags.NAV_UI,
            "承载任务搬到我们的虚拟屏",
            "pkg" to pkg,
            "api" to api.name,
            "task" to host.taskId,
            "stack" to host.stackId,
            "from" to host.displayId,
            "to" to ourId,
        )
        return Result.MOVED
    }

    /** 读一个 int 字段（隐藏字段用；读不到返回 null）。 */
    private fun readInt(obj: Any, name: String): Int? = runCatching {
        obj.javaClass.getField(name).getInt(obj)
    }.getOrNull()

    /**
     * 读任务所在屏的**显示配置**里的一个 dp 值（`screenWidthDp` / `screenHeightDp`）。
     *
     * `RunningTaskInfo.configuration` 是**公开字段**（AOSP 9 就有），
     * 它记的是**任务所在那块屏**的配置 → API 28 上判断「task 在哪块屏」
     * 就靠它（第141轮实测：`RunningTaskInfo` 里**没有** `displayId`）。
     */
    private fun readConfigDp(task: Any, name: String): Int? = runCatching {
        val cfg = task.javaClass.getField("configuration").get(task)
                as? android.content.res.Configuration ?: return null
        cfg.javaClass.getField(name).getInt(cfg)
    }.getOrNull()

    /** 按 displayId 取那块屏（拿不到返回 null）。 */
    private fun displayById(ctx: Context, displayId: Int): android.view.Display? = runCatching {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        dm?.getDisplay(displayId)
    }.getOrNull()

    /**
     * 一块屏的度量（dp）。
     *
     * 用 `getRealSize()`（**未做兼容缩放**的真实尺寸）+ 密度换算成 dp ——
     * 跟任务的 `Configuration.screenWidthDp` 是同一个口径，才能直接比。
     */
    private fun metricsOf(display: android.view.Display?): HostTaskMigrator.DisplayMetrics? {
        val d = display ?: return null
        return runCatching {
            val size = android.graphics.Point()
            d.getRealSize(size)
            val dm = android.util.DisplayMetrics()
            d.getMetrics(dm)
            val dpi = dm.densityDpi.takeIf { it > 0 } ?: 160
            HostTaskMigrator.DisplayMetrics(
                widthDp = size.x * 160 / dpi,
                heightDp = size.y * 160 / dpi,
            )
        }.getOrNull()
    }

    /**
     * 运行时这个 task 类**真实**的公开字段名（诊断用）。
     *
     * 为什么需要它：`RunningTaskInfo` 的字段集**按版本不同**
     * —— AOSP 9 里**没有** `displayId`（第141轮在设备上数过字段），
     * AOSP 10 才加的。想知道「这个平台上到底能读到什么」，
     * 最快的办法就是把真实字段名打出来，而不是照着某个版本的源码猜。
     */
    private fun taskFieldNames(): String = runCatching {
        ActivityManager.RunningTaskInfo::class.java.fields
            .map { it.name }
            .sorted()
            .joinToString(",")
    }.getOrDefault("")

    /** AMS 服务代理（`ActivityManager.getService()`，隐藏方法）。 */
    private fun service(): Any? = runCatching {
        ActivityManager::class.java.getMethod("getService").invoke(null)
    }.getOrNull()

    /** 平台支不支持某个 AMS 方法（连参数个数一起判）。 */
    private fun hasMethod(name: String, argCount: Int): Boolean = runCatching {
        service()?.javaClass?.methods?.any {
            it.name == name && it.parameterTypes.size == argCount
        } == true
    }.getOrDefault(false)

    /** `moveTaskToDisplay(taskId, displayId, toFront)`（AOSP 10+）。 */
    private fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean = runCatching {
        val svc = service() ?: return false
        val m = svc.javaClass.getMethod(
            "moveTaskToDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        m.invoke(svc, taskId, displayId, true)
        true
    }.getOrDefault(false)

    /** `moveStackToDisplay(stackId, displayId)`（AOSP 9 / API 28）。 */
    private fun moveStackToDisplay(stackId: Int, displayId: Int): Boolean = runCatching {
        val svc = service() ?: return false
        val m = svc.javaClass.getMethod(
            "moveStackToDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        m.invoke(svc, stackId, displayId)
        true
    }.getOrDefault(false)
}
