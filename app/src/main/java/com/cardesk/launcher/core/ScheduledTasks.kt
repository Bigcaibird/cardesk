package com.cardesk.launcher.core

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 计划任务模型， `TaskScheduler.ScheduledTask`
 *
 *
 * ## 持久化格式
 *
 * 存的是 **JSON 数组**
 * 字段名固定：`id` / `condition` / `delaySeconds` / `action` / `target` / `enabled`。
 * 本应用早期用竖线文本存，两边互读即崩，所以改成同样的 JSON 数组；
 * 老数据仍能读（读不出来就当空）。
 *
 * ## 取值
 *
 * - `condition`：`BOOT_COMPLETE`（开机后）/ `LAUNCH_OPEN`（进入桌面后）
 * - `action`：`OPEN_APP` / `OPEN_URL` / `HOTSPOT_ON` / `HOTSPOT_OFF`
 * - `delaySeconds`：0..30（滑杆上限 30 秒）
 */
data class ScheduledTask(
    val id: String,
    val condition: String,
    val action: String = ScheduledTasks.ACTION_APP,
    val target: String = "",
    val delaySeconds: Int = ScheduledTasks.DELAY_DEFAULT,
    val enabled: Boolean = true,
) {
    /** 单任务 JSON 对象。 */
    fun toJson(): MutableMap<String, Any?> = linkedMapOf(
        "id" to id,
        "condition" to condition,
        "delaySeconds" to delaySeconds.coerceIn(0, ScheduledTasks.DELAY_MAX),
        "action" to action,
        "target" to target,
        "enabled" to enabled,
    )

    /** 单任务 JSON 字符串。 */
    fun encode(): String = JsonLite.write(toJson())

    companion object {
        /** 本应用 0.x 的竖线文本格式（`id|condition|action|target|delay|enabled`），只用于读旧数据。 */
        fun decode(line: String): ScheduledTask? {
            val p = line.split('|')
            if (p.size < 4) return null
            return ScheduledTask(
                id = ScheduledTasks.unesc(p[0]).ifBlank { return null },
                condition = ScheduledTasks.unesc(p[1]).let {
                    if (it == ScheduledTasks.COND_LAUNCH) ScheduledTasks.COND_LAUNCH else ScheduledTasks.COND_BOOT
                },
                action = ScheduledTasks.unesc(p[2]).ifBlank { ScheduledTasks.ACTION_APP },
                target = ScheduledTasks.unesc(p[3]),
                delaySeconds = p.getOrNull(4)?.toIntOrNull()?.coerceIn(0, ScheduledTasks.DELAY_MAX)
                    ?: ScheduledTasks.DELAY_DEFAULT,
                enabled = p.getOrNull(5) != "0",
            )
        }
    }
}

object ScheduledTasks {

    const val COND_BOOT = "BOOT_COMPLETE"
    const val COND_LAUNCH = "LAUNCH_OPEN"
    const val ACTION_APP = "OPEN_APP"
    const val ACTION_URL = "OPEN_URL"
    const val ACTION_HOTSPOT_ON = "HOTSPOT_ON"
    const val ACTION_HOTSPOT_OFF = "HOTSPOT_OFF"

    /** 延迟上限 30 秒。 */
    const val DELAY_MAX = 30

    /** 编辑器默认延迟 8 秒（`draftDelay = 8`）。 */
    const val DELAY_DEFAULT = 8

    const val KEY = "scheduled_tasks_json"

    /** 开机自动热点（`hotspot_boot_auto`）。 */
    const val KEY_HOTSPOT_AUTO = "hotspot_boot_auto"

    /**
     * 任务条件的编辑器文案
     * 「开机完成后」/「进入桌面后」。
     */
    fun conditions(): List<Pair<String, String>> = listOf(
        COND_BOOT to "开机完成后",
        COND_LAUNCH to "进入桌面后",
    )

    /**
     * 任务列表里的条件说明
     * 「开机后」/「打开桌面后」+「N 秒」或「立即」（立即前面没有空格）。
     */
    fun conditionLabel(task: ScheduledTask): String {
        val base = if (task.condition == COND_BOOT) "开机后" else "打开桌面后"
        return if (task.delaySeconds > 0) "$base ${task.delaySeconds} 秒" else "${base}立即"
    }

    /**
     * 任务动作说明
     * 「打开应用」/「打开 <应用名>」/「打开链接」/「开启热点」/「关闭热点」。
     */
    fun actionLabel(task: ScheduledTask, appLabel: String? = null): String = when (task.action) {
        ACTION_URL -> if (task.target.isEmpty()) "打开链接" else task.target
        ACTION_HOTSPOT_ON -> "开启热点"
        ACTION_HOTSPOT_OFF -> "关闭热点"
        else -> if (task.target.isEmpty()) "打开应用" else "打开 ${appLabel ?: task.target}"
    }

    // ------------------------------------------------------------------ 持久化

    /** 任务列表 → JSON 数组字符串。 */
    fun encode(list: List<ScheduledTask>): String = JsonLite.write(list.map { it.toJson() })

    /** JSON 数组字符串 → 任务列表；旧版竖线文本自动回落解析。 */
    fun decode(raw: String): List<ScheduledTask> {
        if (raw.isBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            // 排障场景：计划任务 JSON 损坏 —— 解析失败必须留 WARN，否则任务会「凭空消失」
            if (JsonLite.parse(trimmed) == null) {
                Logx.w(LogTags.TASK, "任务 JSON 解析失败", "rawLen" to trimmed.length, "raw" to trimmed.take(200))
                return emptyList()
            }
            val list = JsonLite.parseArray(trimmed).mapNotNull { fromJson(it) }
            if (list.isEmpty()) {
                Logx.w(LogTags.TASK, "任务 JSON 解析后没有可用条目", "rawLen" to trimmed.length)
            }
            return list
        }
        // 旧版竖线文本格式（0.x 的老数据）
        Logx.i(LogTags.TASK, "任务 JSON：命中旧版竖线文本格式，按兼容解析")
        return decodeLegacy(trimmed)
    }

    /** 单个任务 JSON → 任务对象。 */
    fun fromJson(map: Map<String, Any?>?): ScheduledTask? {
        if (map == null) return null
        val id = JsonLite.str(map, "id", "")
        if (id.isBlank()) {
            // 排障场景：任务条目没有 id —— 这条永远不会被调度，会被丢掉
            Logx.w(LogTags.TASK, "任务条目缺少 id，已跳过", "entry" to map.toString().take(200))
            return null
        }
        var condition = JsonLite.str(map, "condition", COND_LAUNCH)
        if (condition == "DELAY") condition = COND_LAUNCH
        if (condition != COND_BOOT && condition != COND_LAUNCH) condition = COND_LAUNCH
        return ScheduledTask(
            id = id,
            condition = condition,
            action = JsonLite.str(map, "action", ACTION_APP).ifBlank { ACTION_APP },
            target = JsonLite.str(map, "target", ""),
            delaySeconds = JsonLite.int(map, "delaySeconds", 0).coerceIn(0, DELAY_MAX),
            enabled = JsonLite.bool(map, "enabled", true),
        )
    }

    fun load(prefs: Prefs): MutableList<ScheduledTask> = decode(prefs.stringValue(KEY, "")).toMutableList()

    fun save(prefs: Prefs, tasks: List<ScheduledTask>) {
        prefs.setStringValue(KEY, encode(tasks))
        // 排障场景：任务列表保存了多少条（新建/删除后对不上时看这条）
        Logx.i(LogTags.TASK, "任务列表已保存", "count" to tasks.size)
    }

    fun newId(now: Long = System.currentTimeMillis()): String = "t$now"

    // ------------------------------------------------------------------ 开机自动热点

    fun isHotspotAutoEnabled(prefs: Prefs): Boolean = prefs.flag(KEY_HOTSPOT_AUTO, false)

    fun setHotspotAutoEnabled(prefs: Prefs, enabled: Boolean) {
        val from = isHotspotAutoEnabled(prefs)
        prefs.setFlag(KEY_HOTSPOT_AUTO, enabled)
        // 排障场景：开机自动热点开关改了没生效
        Logx.i(
            LogTags.TASK,
            "设置变更",
            "key" to KEY_HOTSPOT_AUTO,
            "name" to "自动热点",
            "from" to from,
            "to" to enabled,
        )
    }

    // ------------------------------------------------------------------ 旧格式兜底

    private fun decodeLegacy(raw: String): List<ScheduledTask> =
        raw.split('\n').filter { it.isNotBlank() }.mapNotNull { ScheduledTask.decode(it) }

    fun esc(s: String): String = s.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")

    fun unesc(s: String): String = s.replace("%7C", "|").replace("%0A", "\n").replace("%25", "%")
}
