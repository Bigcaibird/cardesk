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
    val threshold: Float = 0f,
    val cooldownSeconds: Int = ScheduledTasks.COOLDOWN_DEFAULT,
) {
    /** 单任务 JSON 对象。 */
    fun toJson(): MutableMap<String, Any?> = linkedMapOf(
        "id" to id,
        "condition" to condition,
        "delaySeconds" to delaySeconds.coerceIn(0, ScheduledTasks.DELAY_MAX),
        "action" to action,
        "target" to target,
        "enabled" to enabled,
        "threshold" to threshold.toDouble(),
        "cooldownSeconds" to cooldownSeconds.coerceAtLeast(0),
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
    const val COND_TEMP_LT = "TEMP_LT"
    const val COND_TEMP_GT = "TEMP_GT"
    const val COND_AQI_GT = "AQI_GT"
    const val COND_AQI_LT = "AQI_LT"
    const val COND_PM25_GT = "PM25_GT"
    const val COND_SPEED_GT = "SPEED_GT"
    const val COND_VOLTAGE_LT = "VOLTAGE_LT"
    const val COND_NIGHT_ON = "NIGHT_ON"
    const val COND_DAY_ON = "DAY_ON"
    const val COND_IGNITION_OFF = "IGNITION_OFF"
    const val COND_LOCK_CAR = "LOCK_CAR"

    const val ACTION_APP = "OPEN_APP"
    const val ACTION_URL = "OPEN_URL"
    const val ACTION_HOTSPOT_ON = "HOTSPOT_ON"
    const val ACTION_HOTSPOT_OFF = "HOTSPOT_OFF"
    const val ACTION_SEAT_HEAT_DRIVER = "SEAT_HEAT_DRIVER"
    const val ACTION_SEAT_HEAT_PASSENGER = "SEAT_HEAT_PASSENGER"
    const val ACTION_SEAT_VENT_DRIVER = "SEAT_VENT_DRIVER"
    const val ACTION_CIRC_INNER = "CIRC_INNER"
    const val ACTION_CIRC_OUTSIDE = "CIRC_OUTSIDE"
    const val ACTION_HVAC_AC_ON = "HVAC_AC_ON"
    const val ACTION_HVAC_AUTO_ON = "HVAC_AUTO_ON"
    const val ACTION_AMBIENT_ON = "AMBIENT_ON"
    const val ACTION_AMBIENT_OFF = "AMBIENT_OFF"
    const val ACTION_CLOSE_WINDOWS = "CLOSE_WINDOWS"

    /** 延迟上限 30 秒。 */
    const val DELAY_MAX = 30

    /** 编辑器默认延迟 8 秒（`draftDelay = 8`）。 */
    const val DELAY_DEFAULT = 8

    /** 传感器条件默认冷却时间（秒）。 */
    const val COOLDOWN_DEFAULT = 300

    /** 熄火/锁车等离散状态事件默认防抖冷却时间（秒）。 */
    const val COOLDOWN_POWER_EVENT = 10

    const val KEY = "scheduled_tasks_json"

    /** 开机自动热点（`hotspot_boot_auto`）。 */
    const val KEY_HOTSPOT_AUTO = "hotspot_boot_auto"

    private val KNOWN_CONDITIONS = setOf(
        COND_BOOT, COND_LAUNCH,
        COND_TEMP_LT, COND_TEMP_GT,
        COND_AQI_GT, COND_AQI_LT,
        COND_PM25_GT, COND_SPEED_GT, COND_VOLTAGE_LT,
        COND_NIGHT_ON, COND_DAY_ON,
        COND_IGNITION_OFF, COND_LOCK_CAR,
    )

    fun isStartupCondition(condition: String): Boolean =
        condition == COND_BOOT || condition == COND_LAUNCH

    fun defaultCooldown(condition: String): Int = when (condition) {
        COND_IGNITION_OFF, COND_LOCK_CAR -> COOLDOWN_POWER_EVENT
        else -> COOLDOWN_DEFAULT
    }

    fun needsThreshold(condition: String): Boolean = condition in setOf(
        COND_TEMP_LT, COND_TEMP_GT,
        COND_AQI_GT, COND_AQI_LT,
        COND_PM25_GT, COND_SPEED_GT, COND_VOLTAGE_LT,
    )

    fun defaultThreshold(condition: String): Float = when (condition) {
        COND_TEMP_LT -> 10f
        COND_TEMP_GT -> 28f
        COND_AQI_GT -> 100f
        COND_AQI_LT -> 50f
        COND_PM25_GT -> 75f
        COND_SPEED_GT -> 80f
        COND_VOLTAGE_LT -> 11.8f
        else -> 0f
    }

    fun thresholdStep(condition: String): Float = when (condition) {
        COND_TEMP_LT, COND_TEMP_GT -> 1f
        COND_AQI_GT, COND_AQI_LT, COND_PM25_GT -> 10f
        COND_SPEED_GT -> 5f
        COND_VOLTAGE_LT -> 0.2f
        else -> 1f
    }

    fun thresholdUnit(condition: String): String = when (condition) {
        COND_TEMP_LT, COND_TEMP_GT -> "℃"
        COND_SPEED_GT -> "km/h"
        COND_VOLTAGE_LT -> "V"
        else -> ""
    }

    fun formatThreshold(condition: String, value: Float): String = when (condition) {
        COND_VOLTAGE_LT -> String.format(java.util.Locale.US, "%.1f", value)
        COND_TEMP_LT, COND_TEMP_GT -> if (value % 1f == 0f) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
        else -> Math.round(value).toString()
    }

    fun isSeatAction(action: String): Boolean =
        action == ACTION_SEAT_HEAT_DRIVER || action == ACTION_SEAT_HEAT_PASSENGER || action == ACTION_SEAT_VENT_DRIVER

    /**
     * 任务条件的编辑器文案。
     */
    fun conditions(): List<Pair<String, String>> = listOf(
        COND_BOOT to "开机完成后",
        COND_LAUNCH to "进入桌面后",
        COND_TEMP_LT to "温度低于",
        COND_TEMP_GT to "温度高于",
        COND_AQI_GT to "AQI 高于",
        COND_AQI_LT to "AQI 低于",
        COND_PM25_GT to "PM2.5 高于",
        COND_SPEED_GT to "车速高于",
        COND_VOLTAGE_LT to "电瓶电压低于",
        COND_NIGHT_ON to "进入夜间模式",
        COND_DAY_ON to "进入日间模式",
        COND_IGNITION_OFF to "熄火时",
        COND_LOCK_CAR to "离车锁车时",
    )

    /** 编辑器动作选项（不含打开应用/链接以外的细分在编辑器里按 chip 展开）。 */
    fun actions(): List<Pair<String, String>> = listOf(
        ACTION_SEAT_HEAT_DRIVER to "主驾座椅加热",
        ACTION_SEAT_HEAT_PASSENGER to "副驾座椅加热",
        ACTION_SEAT_VENT_DRIVER to "主驾座椅通风",
        ACTION_CIRC_INNER to "切换内循环",
        ACTION_CIRC_OUTSIDE to "切换外循环",
        ACTION_HVAC_AC_ON to "开启 A/C",
        ACTION_HVAC_AUTO_ON to "开启 AUTO",
        ACTION_AMBIENT_ON to "开启氛围灯",
        ACTION_AMBIENT_OFF to "关闭氛围灯",
        ACTION_CLOSE_WINDOWS to "关闭所有车窗",
        ACTION_APP to "打开应用",
        ACTION_URL to "打开链接",
        ACTION_HOTSPOT_ON to "开启热点",
        ACTION_HOTSPOT_OFF to "关闭热点",
    )

    /** 示例自动化规则（温度→座椅加热/通风，AQI→内外循环）。 */
    fun examplePresets(): List<ScheduledTask> = listOf(
        ScheduledTask(
            id = "preset-cold-seat-heat",
            condition = COND_TEMP_LT,
            threshold = 10f,
            action = ACTION_SEAT_HEAT_DRIVER,
            target = "2",
            delaySeconds = 0,
            enabled = true,
        ),
        ScheduledTask(
            id = "preset-hot-seat-vent",
            condition = COND_TEMP_GT,
            threshold = 28f,
            action = ACTION_SEAT_VENT_DRIVER,
            target = "2",
            delaySeconds = 0,
            enabled = true,
        ),
        ScheduledTask(
            id = "preset-aqi-inner",
            condition = COND_AQI_GT,
            threshold = 100f,
            action = ACTION_CIRC_INNER,
            target = "",
            delaySeconds = 0,
            enabled = true,
        ),
        ScheduledTask(
            id = "preset-aqi-outside",
            condition = COND_AQI_LT,
            threshold = 50f,
            action = ACTION_CIRC_OUTSIDE,
            target = "",
            delaySeconds = 0,
            enabled = true,
        ),
        ScheduledTask(
            id = "preset-lock-close-windows",
            condition = COND_LOCK_CAR,
            threshold = 0f,
            action = ACTION_CLOSE_WINDOWS,
            target = "",
            delaySeconds = 0,
            enabled = true,
            cooldownSeconds = COOLDOWN_POWER_EVENT,
        ),
    )

    /**
     * 任务列表里的条件说明。
     */
    fun conditionLabel(task: ScheduledTask): String = when (task.condition) {
        COND_BOOT -> if (task.delaySeconds > 0) "开机后 ${task.delaySeconds} 秒" else "开机后立即"
        COND_LAUNCH -> if (task.delaySeconds > 0) "打开桌面后 ${task.delaySeconds} 秒" else "打开桌面后立即"
        COND_TEMP_LT -> "温度 ≤ ${formatThreshold(task.condition, task.threshold)}℃"
        COND_TEMP_GT -> "温度 ≥ ${formatThreshold(task.condition, task.threshold)}℃"
        COND_AQI_GT -> "车内 AQI ≥ ${formatThreshold(task.condition, task.threshold)}"
        COND_AQI_LT -> "车内 AQI ≤ ${formatThreshold(task.condition, task.threshold)}"
        COND_PM25_GT -> "车内 PM2.5 ≥ ${formatThreshold(task.condition, task.threshold)}"
        COND_SPEED_GT -> "车速 ≥ ${formatThreshold(task.condition, task.threshold)} km/h"
        COND_VOLTAGE_LT -> "电瓶电压 ≤ ${formatThreshold(task.condition, task.threshold)} V"
        COND_NIGHT_ON -> "进入夜间模式时"
        COND_DAY_ON -> "进入日间模式时"
        COND_IGNITION_OFF -> "车辆熄火时"
        COND_LOCK_CAR -> "离车锁车时"
        else -> "打开桌面后"
    }

    /**
     * 任务动作说明。
     */
    fun actionLabel(task: ScheduledTask, appLabel: String? = null): String {
        val level = task.target.toIntOrNull()?.coerceIn(0, 3) ?: 2
        val levelText = if (level == 0) "关闭" else "${level}档"
        return when (task.action) {
            ACTION_URL -> if (task.target.isEmpty()) "打开链接" else task.target
            ACTION_HOTSPOT_ON -> "开启热点"
            ACTION_HOTSPOT_OFF -> "关闭热点"
            ACTION_SEAT_HEAT_DRIVER -> "主驾座椅加热 $levelText"
            ACTION_SEAT_HEAT_PASSENGER -> "副驾座椅加热 $levelText"
            ACTION_SEAT_VENT_DRIVER -> "主驾座椅通风 $levelText"
            ACTION_CIRC_INNER -> "切换为内循环"
            ACTION_CIRC_OUTSIDE -> "切换为外循环"
            ACTION_HVAC_AC_ON -> "开启空调 A/C"
            ACTION_HVAC_AUTO_ON -> "开启空调 AUTO"
            ACTION_AMBIENT_ON -> "开启氛围灯"
            ACTION_AMBIENT_OFF -> "关闭氛围灯"
            ACTION_CLOSE_WINDOWS -> "关闭所有车窗"
            else -> if (task.target.isEmpty()) "打开应用" else "打开 ${appLabel ?: task.target}"
        }
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
        if (condition !in KNOWN_CONDITIONS) condition = COND_LAUNCH
        val threshold = (map["threshold"] as? Number)?.toFloat() ?: defaultThreshold(condition)
        val cooldown = JsonLite.int(map, "cooldownSeconds", COOLDOWN_DEFAULT).coerceAtLeast(0)
        return ScheduledTask(
            id = id,
            condition = condition,
            action = JsonLite.str(map, "action", ACTION_APP).ifBlank { ACTION_APP },
            target = JsonLite.str(map, "target", ""),
            delaySeconds = JsonLite.int(map, "delaySeconds", 0).coerceIn(0, DELAY_MAX),
            enabled = JsonLite.bool(map, "enabled", true),
            threshold = threshold,
            cooldownSeconds = cooldown,
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
