package com.cardesk.launcher.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 计划任务的真实调度， `TaskScheduler`
 * （开机闹钟、 桌面内调度）。
 *
 * ## 两种条件走两条路
 *
 * - `BOOT_COMPLETE`（开机后）：`AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP, delay + elapsedRealtime())`，
 *   到点由广播接收器 `TaskReceiver` 执行动作，任务 id 通过 intent extra 传递。
 * - `LAUNCH_OPEN`（进入桌面后）：**进程内 Handler** 延迟执行
 *   不走 AlarmManager —— 桌面已经在前台了，用闹钟反而要等广播。
 *   还会跳过「目标就是自己」的自杀任务，延迟 0 时立刻执行。
 *
 * 免激活版不做「未激活 5 秒后重试 ×12」的循环，任务只按自身延迟执行一次。
 */
object TaskScheduler {

    const val EXTRA_TASK_ID = "scheduled_task_id"

    /** 桌面内任务的延迟执行器（`TaskScheduler.MAIN_HANDLER`）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 正在等待的桌面内任务（`TaskScheduler.PENDING_LAUNCH_TASKS`）。 */
    private val pendingLaunch = LinkedHashMap<String, Runnable>()

    /** 开机后的任务：走 AlarmManager。 */
    fun scheduleBootTasks(context: Context, prefs: Prefs) {
        val tasks = ScheduledTasks.load(prefs)
        val selected = tasks.filter { it.enabled && it.condition == ScheduledTasks.COND_BOOT }
        // 排障场景：开机任务「没跑」—— 先看这次到底注册了几条闹钟
        Logx.i(LogTags.TASK, "注册开机任务闹钟", "total" to tasks.size, "selected" to selected.size)
        selected.forEach { scheduleBoot(context, it) }
    }

    /** 进入桌面后的任务：走进程内 Handler。 */
    fun scheduleLaunchTasks(context: Context, prefs: Prefs) {
        val tasks = ScheduledTasks.load(prefs)
        val selected = tasks.filter { it.enabled && it.condition == ScheduledTasks.COND_LAUNCH }
        // 排障场景：进桌面任务「没跑」—— 先看这次挂了几条进程内延迟
        Logx.i(LogTags.TASK, "注册进桌面任务延迟", "total" to tasks.size, "selected" to selected.size)
        selected.forEach { scheduleLaunch(context, it) }
    }

    /** 按条件分发（任务编辑器保存后调用）。 */
    fun schedule(context: Context, task: ScheduledTask) {
        // 排障场景：保存任务后到底走了闹钟还是进程内延迟
        Logx.i(
            LogTags.TASK,
            "任务调度分发",
            "id" to task.id,
            "condition" to task.condition,
            "action" to task.action,
            "delaySeconds" to task.delaySeconds,
        )
        if (task.condition == ScheduledTasks.COND_LAUNCH) {
            scheduleLaunch(context, task)
        } else {
            scheduleBoot(context, task)
        }
    }

    /** 开机闹钟。 */
    private fun scheduleBoot(context: Context, task: ScheduledTask) {
        if (task.id.isBlank()) {
            Logx.w(LogTags.TASK, "注册开机闹钟失败：任务 id 为空")
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            // 排障场景：拿不到 AlarmManager，闹钟根本注册不了
            Logx.w(LogTags.TASK, "注册开机闹钟失败：拿不到 AlarmManager", "id" to task.id)
            return
        }
        val trigger = SystemClock.elapsedRealtime() + task.delaySeconds.coerceAtLeast(0) * 1000L
        val pi = pendingIntent(context, task.id)
        // 精确闹钟在 Android 12+ 需要额外授权，这里记录到底用的哪条路径
        var exact = true
        var failed = false
        runCatching {
            //  是 **API 31** 才有的方法，
            // 本项目 compileSdk 29（一致）编不到 —— 用反射问一次。
            val canExact = if (Build.VERSION.SDK_INT < 31) {
                true
            } else {
                runCatching {
                    AlarmManager::class.java.getMethod("canScheduleExactAlarms")
                        .invoke(am) as? Boolean ?: true
                }.getOrDefault(true)
            }
            if (!canExact) {
                exact = false
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        }.onFailure {
            failed = true
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
        }
        // 排障场景：开机任务没触发 —— 看这条确认闹钟注册成功、延迟是多少、是精确还是非精确
        Logx.i(
            LogTags.TASK,
            "已注册开机任务闹钟",
            "id" to task.id,
            "condition" to task.condition,
            "target" to task.target,
            "delaySeconds" to task.delaySeconds,
            "exact" to exact,
            "fallback" to failed,
        )
    }

    /**
     * 桌面内调度
     * 目标是自己的任务跳过；延迟 0 立刻执行；否则 post 到进程内 Handler。
     */
    private fun scheduleLaunch(context: Context, task: ScheduledTask) {
        if (task.id.isBlank()) {
            Logx.w(LogTags.TASK, "注册进桌面延迟失败：任务 id 为空")
            return
        }
        if (task.target.isNotBlank() && task.target == context.packageName) {
            // 排障场景：任务是「打开桌面自己」会被跳过，看起来像没跑
            Logx.i(
                LogTags.TASK,
                "跳过进桌面任务：目标是桌面自身",
                "id" to task.id,
                "target" to task.target,
            )
            return
        }
        cancelPending(task.id)
        val delayMs = task.delaySeconds.coerceAtLeast(0) * 1000L
        if (delayMs <= 0L) {
            Logx.i(
                LogTags.TASK,
                "进桌面任务立即执行",
                "id" to task.id,
                "condition" to task.condition,
                "target" to task.target,
            )
            executeTask(context, task)
            return
        }
        val runnable = Runnable {
            synchronized(pendingLaunch) { pendingLaunch.remove(task.id) }
            val current = ScheduledTasks.load(Prefs(context)).firstOrNull { it.id == task.id }
            if (current == null) {
                // 排障场景：延迟到期但任务已经被删了
                Logx.w(LogTags.TASK, "进桌面任务到期：任务已不存在", "id" to task.id)
                return@Runnable
            }
            if (!current.enabled || current.condition != ScheduledTasks.COND_LAUNCH) {
                // 排障场景：延迟到期但任务被停用/改了条件
                Logx.i(
                    LogTags.TASK,
                    "进桌面任务到期：已停用或条件已变更，跳过",
                    "id" to task.id,
                    "enabled" to current.enabled,
                    "condition" to current.condition,
                )
                return@Runnable
            }
            Logx.i(
                LogTags.TASK,
                "进桌面任务触发",
                "id" to task.id,
                "condition" to current.condition,
                "action" to current.action,
                "target" to current.target,
                "delaySeconds" to current.delaySeconds,
            )
            executeTask(context, current)
        }
        synchronized(pendingLaunch) { pendingLaunch[task.id] = runnable }
        mainHandler.postDelayed(runnable, delayMs)
        // 排障场景：进桌面任务「没跑」—— 看这条确认延迟是否挂上了
        Logx.i(
            LogTags.TASK,
            "已注册进桌面任务延迟",
            "id" to task.id,
            "target" to task.target,
            "delaySeconds" to task.delaySeconds,
        )
    }

    fun cancel(context: Context, taskId: String) {
        val hadPending = cancelPending(taskId)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            Logx.w(LogTags.TASK, "取消任务失败：拿不到 AlarmManager", "id" to taskId)
            return
        }
        am.cancel(pendingIntent(context, taskId))
        // 排障场景：删任务后旧闹钟还在不在 —— 看这条确认已取消
        Logx.i(LogTags.TASK, "已取消任务调度", "id" to taskId, "hadPending" to hadPending)
    }

    private fun cancelPending(taskId: String): Boolean {
        val runnable = synchronized(pendingLaunch) { pendingLaunch.remove(taskId) } ?: return false
        mainHandler.removeCallbacks(runnable)
        return true
    }

    /**
     * 执行任务动作
     * 打开应用 / 打开链接 / 开热点 / 关热点。
     */
    fun executeTask(context: Context, task: ScheduledTask) {
        when (task.action) {
            ScheduledTasks.ACTION_URL -> {
                if (task.target.isNotBlank()) {
                    val ok = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(task.target))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                    // 排障场景：打开链接失败（多半是 URL 非法或没有能处理的 App）
                    if (ok) {
                        Logx.i(LogTags.TASK, "任务执行成功：打开链接", "id" to task.id, "target" to task.target)
                    } else {
                        Logx.w(LogTags.TASK, "任务执行失败：打开链接", "id" to task.id, "target" to task.target)
                    }
                } else {
                    Logx.w(LogTags.TASK, "任务执行失败：打开链接但地址为空", "id" to task.id)
                }
            }
            ScheduledTasks.ACTION_HOTSPOT_ON,
            ScheduledTasks.ACTION_HOTSPOT_OFF,
            -> {
                // 排障场景：热点动作在无系统签名的车机上必然不可用
                Logx.w(
                    LogTags.TASK,
                    "任务执行降级：热点开关需要系统签名",
                    "id" to task.id,
                    "action" to task.action,
                )
                Toast.makeText(context, "热点开关需要系统签名，此项仅保留入口", Toast.LENGTH_SHORT).show()
            }
            else -> {
                if (task.target.isBlank()) {
                    Logx.w(LogTags.TASK, "任务执行失败：目标应用为空", "id" to task.id)
                    return
                }
                val launch = context.packageManager.getLaunchIntentForPackage(task.target)
                if (launch == null) {
                    // 排障场景：目标应用被卸载了
                    Logx.w(LogTags.TASK, "任务执行失败：目标应用无启动入口", "id" to task.id, "target" to task.target)
                    return
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = runCatching { context.startActivity(launch) }.isSuccess
                if (ok) {
                    Logx.i(LogTags.TASK, "任务执行成功：打开应用", "id" to task.id, "target" to task.target)
                } else {
                    Logx.w(LogTags.TASK, "任务执行失败：打开应用", "id" to task.id, "target" to task.target)
                }
            }
        }
    }

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, TaskReceiver::class.java)
            .setAction(TaskReceiver.ACTION_FIRE)
            .setData(android.net.Uri.parse("cardesk://task/$taskId"))
            .putExtra(EXTRA_TASK_ID, taskId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }
}
