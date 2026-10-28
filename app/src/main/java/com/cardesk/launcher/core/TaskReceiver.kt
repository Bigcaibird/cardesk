package com.cardesk.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 计划任务到点执行。动作集合与 `TaskScheduler` 对齐：
 * 打开应用 / 打开链接 / 开热点 / 关热点（热点两条在免系统签名版本里只会提示不可用）。
 */
class TaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID) ?: run {
            // 排障场景：闹钟响了但收不到任务 id —— 广播的 extra 没带上
            Logx.w(LogTags.TASK, "计划任务触发失败：广播缺少任务 id", "action" to intent.action)
            return
        }
        val prefs = Prefs(context)
        val task = ScheduledTasks.load(prefs).firstOrNull { it.id == id }
        if (task == null) {
            // 排障场景：任务列表里已经没有这条了（被删/被改 id）
            Logx.w(LogTags.TASK, "计划任务触发失败：任务已不存在", "id" to id)
            return
        }
        if (!task.enabled) {
            // 排障场景：任务被停用了却还是被闹钟唤醒了
            Logx.i(LogTags.TASK, "计划任务触发：任务已停用，跳过", "id" to id)
            return
        }
        // 排障场景：任务「没跑」—— 这条是触发点，记录 id、条件、目标与延迟
        Logx.i(
            LogTags.TASK,
            "计划任务触发",
            "id" to task.id,
            "condition" to task.condition,
            "action" to task.action,
            "target" to task.target,
            "delaySeconds" to task.delaySeconds,
        )
        runCatching { execute(context, task) }.onFailure {
            // 排障场景：动作执行抛异常（缺权限、目标应用崩了等）
            Logx.e(LogTags.TASK, "计划任务执行异常", it, "id" to task.id, "action" to task.action)
            Toast.makeText(context, "计划任务执行失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun execute(context: Context, task: ScheduledTask) {
        when (task.action) {
            ScheduledTasks.ACTION_URL -> {
                if (task.target.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(task.target))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    Logx.i(LogTags.TASK, "计划任务执行成功：打开链接", "id" to task.id, "target" to task.target)
                } else {
                    Logx.w(LogTags.TASK, "计划任务执行失败：打开链接但地址为空", "id" to task.id)
                }
            }
            ScheduledTasks.ACTION_HOTSPOT_ON,
            ScheduledTasks.ACTION_HOTSPOT_OFF,
            -> {
                // 排障场景：热点动作在无系统签名的车机上必然不可用
                Logx.w(
                    LogTags.TASK,
                    "计划任务执行降级：热点开关需要系统签名",
                    "id" to task.id,
                    "action" to task.action,
                )
                Toast.makeText(context, "热点开关需要系统签名，此项仅保留入口", Toast.LENGTH_SHORT).show()
            }
            else -> launchApp(context, task)
        }
    }

    private fun launchApp(context: Context, task: ScheduledTask) {
        val pkg = task.target
        if (pkg.isBlank()) {
            Logx.w(LogTags.TASK, "计划任务执行失败：目标应用为空", "id" to task.id)
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            // 排障场景：目标应用被卸载了
            Logx.w(LogTags.TASK, "计划任务执行失败：目标应用无启动入口", "id" to task.id, "target" to pkg)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        Logx.i(LogTags.TASK, "计划任务执行成功：打开应用", "id" to task.id, "target" to pkg)
    }

    companion object {
        const val ACTION_FIRE = "com.cardesk.launcher.action.SCHEDULED_TASK"
    }
}
