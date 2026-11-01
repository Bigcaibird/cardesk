package com.cardesk.launcher.core

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 原车最近任务（第九十五轮 C3，对照 UNI 的 `RecentTasksRepository`）。
 *
 * 车机是平台签名应用（`android.uid.system`），`ActivityManager.getRecentTasks` /
 * `removeTask` / `moveTaskToFront` 都可用；权限不足时整页降级为提示，不再像以前那样
 * 把「最近任务」退回 HOME（HOME 就是本桌面自己，等于没反应）。
 *
 * 只读 + 单条移除 + 清空，不做缩略图（缩略图在 Android 9 要 `getTaskThumbnail`，
 * 不同 ROM 行为不一致，先按图标 + 名称做，能力探测放后续轮次）。
 */
class RecentTasks(
    private val context: Context,
    private val system: System = ActivityManagerSystem(context),
) {

    /** 一条最近任务（同一包只保留最近一条）。 */
    data class App(
        val pkg: String,
        val taskId: Int,
        val label: String,
        val icon: Drawable?,
    )

    enum class State { READY, NO_PERMISSION, UNSUPPORTED }

    /** 系统侧接口：生产用 [ActivityManagerSystem]，测试注入假实现。 */
    interface System {
        fun list(max: Int): List<Raw>
        fun moveToFront(taskId: Int): Boolean
        fun remove(taskId: Int): Boolean
    }

    /** 原始任务信息（只取排序/启动需要的字段）。 */
    data class Raw(val taskId: Int, val pkg: String, val activity: String?)

    private val prefs = Prefs(context.applicationContext)

    val state: State by lazy { detect() }
    val available: Boolean get() = state == State.READY

    fun list(max: Int = MAX_APPS): List<App> {
        if (!available) return emptyList()
        val pm = context.packageManager
        val self = context.packageName
        val raw = try {
            system.list(max * 3 + 8)
        } catch (e: SecurityException) {
            Logx.w(LogTags.RECENT, "最近任务列表被拒绝", e)
            return emptyList()
        } catch (e: Throwable) {
            Logx.w(LogTags.RECENT, "最近任务列表失败", e)
            return emptyList()
        }
        return raw.asSequence()
            .filter { it.pkg != self }
            .filter { it.pkg.isNotBlank() }
            .distinctBy { it.pkg }
            .take(max)
            .map { task ->
                App(
                    pkg = task.pkg,
                    taskId = task.taskId,
                    label = appLabel(pm, task.pkg),
                    icon = appIcon(pm, task.pkg),
                )
            }
            .toList()
    }

    /** 打开：先把任务挪到前台（保留原页面状态），失败再按启动 Intent 起。 */
    fun open(app: App): Boolean {
        val moved = runCatching { system.moveToFront(app.taskId) }.getOrDefault(false)
        if (moved) {
            Logx.event(LogTags.RECENT, "最近任务打开（moveTaskToFront）", "pkg" to app.pkg, "task" to app.taskId)
            return true
        }
        val intent = context.packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            Logx.w(LogTags.RECENT, "最近任务打开失败：没有启动入口", "pkg" to app.pkg)
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (ok) Logx.event(LogTags.RECENT, "最近任务打开（启动 Intent）", "pkg" to app.pkg)
        return ok
    }

    /** 关掉单条任务（系统会把它从最近任务里摘掉）。 */
    fun close(app: App): Boolean {
        val ok = runCatching { system.remove(app.taskId) }.getOrDefault(false)
        Logx.event(LogTags.RECENT, if (ok) "最近任务关闭" else "最近任务关闭失败", "pkg" to app.pkg)
        return ok
    }

    /** 清空：锁定（[Prefs.recentLocked]）的包保留，返回真正移除的条数。 */
    fun clear(): Int {
        if (!available) return 0
        val locked = prefs.recentLocked
        val self = context.packageName
        val raw = runCatching { system.list(MAX_CLEAR) }.getOrDefault(emptyList())
        var removed = 0
        for (task in raw) {
            if (task.pkg.isBlank() || task.pkg == self || task.pkg in locked) continue
            val ok = runCatching { system.remove(task.taskId) }.getOrDefault(false)
            if (ok) removed++
        }
        Logx.event(LogTags.RECENT, "最近任务清空", "removed" to removed, "locked" to locked.size)
        return removed
    }

    fun toggleLock(pkg: String): Boolean {
        val locked = prefs.recentLocked.toMutableSet()
        val now = if (locked.contains(pkg)) {
            locked.remove(pkg)
            false
        } else {
            locked.add(pkg)
            true
        }
        prefs.recentLocked = locked
        Logx.event(LogTags.RECENT, "最近任务锁定切换", "pkg" to pkg, "locked" to now)
        return now
    }

    fun isLocked(pkg: String): Boolean = prefs.recentLocked.contains(pkg)

    private fun detect(): State {
        return try {
            system.list(1)
            State.READY
        } catch (e: SecurityException) {
            Logx.w(LogTags.RECENT, "最近任务权限不足", e)
            State.NO_PERMISSION
        } catch (e: Throwable) {
            Logx.w(LogTags.RECENT, "最近任务不可用", e)
            State.UNSUPPORTED
        }
    }

    private fun appLabel(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) {
        pkg
    }

    private fun appIcon(pm: PackageManager, pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (e: Throwable) {
        null
    }

    /** 生产实现：直接调 ActivityManager。 */
    private class ActivityManagerSystem(private val context: Context) : System {
        private fun am(): ActivityManager? =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        override fun list(max: Int): List<Raw> {
            val manager = am() ?: return emptyList()
            @Suppress("DEPRECATION")
            val tasks = manager.getRecentTasks(max, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
                ?: return emptyList()
            return tasks.mapNotNull { info ->
                val intent: Intent? = info.baseIntent
                val component: ComponentName? = intent?.component
                val pkg = component?.packageName
                    ?: info.topActivity?.packageName
                    ?: info.origActivity?.packageName
                    ?: return@mapNotNull null
                Raw(info.persistentId, pkg, component?.className)
            }
        }

        override fun moveToFront(taskId: Int): Boolean {
            val manager = am() ?: return false
            @Suppress("DEPRECATION")
            manager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            return true
        }

        override fun remove(taskId: Int): Boolean {
            val manager = am() ?: return false
            // ActivityManager.removeTask 是 @SystemApi（公开 SDK 不可见），平台签名应用走反射。
            return runCatching {
                ActivityManager::class.java
                    .getMethod("removeTask", Int::class.javaPrimitiveType)
                    .invoke(manager, taskId) as? Boolean ?: false
            }.getOrDefault(false)
        }
    }

    companion object {
        /** 页面最多显示几条。 */
        const val MAX_APPS = 12

        /** 清空时扫描的上限（包含用户不常看到的老任务）。 */
        const val MAX_CLEAR = 60
    }
}
