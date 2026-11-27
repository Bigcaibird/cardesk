package com.cardesk.launcher.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import com.cardesk.launcher.core.ambient.AmbientLightController
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.vehicle.AdaptapiSensors
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.OemWindowController
import com.cardesk.launcher.vehicle.VehicleController
import com.cardesk.launcher.vehicle.VehicleInfo

/**
 * 自动任务条件引擎：按车辆传感器/状态快照评估规则，边沿触发 + 冷却防抖后执行对应车控或系统动作。
 */
object AutomationEngine {

    /** 用于条件评估的实时传感器/状态快照。 */
    data class Snapshot(
        val tempC: Float? = null,
        val aqiIndoor: Int? = null,
        val pm25Indoor: Int? = null,
        val speedKmh: Int? = null,
        val batteryVoltage: Float? = null,
        val isNight: Boolean? = null,
        val ignitionState: Int? = null,
    )

    private val lastMatched = HashMap<String, Boolean>()
    private val lastFiredAtMs = HashMap<String, Long>()
    private var lastIgnitionState: Int? = null

    private fun isPoweredState(state: Int): Boolean =
        state == AdaptapiSensors.IGNITION_ACTIVE || state == AdaptapiSensors.IGNITION_DRIVING

    private fun isIgnitionOffTarget(state: Int): Boolean =
        state == AdaptapiSensors.IGNITION_CONVENIENCE ||
            state == AdaptapiSensors.IGNITION_INACTIVE ||
            state == AdaptapiSensors.IGNITION_ABANDON

    /**
     * 熄火跳变判定（仅向下掉电方向，解锁上车的 `ABANDON -> INACTIVE -> CONVENIENCE` 向上方向绝不误触）：
     * - 行驶中/已通电 → 便捷模式 / 已熄火 / 离车；
     * - 便捷模式 → 已熄火（原地通电后彻底熄火）。
     */
    private fun isIgnitionOffTransition(prev: Int, curr: Int): Boolean =
        (isPoweredState(prev) && isIgnitionOffTarget(curr)) ||
            (prev == AdaptapiSensors.IGNITION_CONVENIENCE && curr == AdaptapiSensors.IGNITION_INACTIVE)

    fun fromVehicle(info: VehicleInfo, weatherTempC: Int?, isNight: Boolean): Snapshot {
        val temp = info.ambientTemp.takeIf { !it.isNaN() } ?: weatherTempC?.toFloat()
        return Snapshot(
            tempC = temp,
            aqiIndoor = info.aqiIndoor,
            pm25Indoor = info.pm25Indoor,
            speedKmh = info.speedKmh,
            batteryVoltage = info.batteryVoltage,
            isNight = isNight,
            ignitionState = info.ignitionState,
        )
    }

    /** 评估单条条件是否满足；传感器数据不可用时返回 null（不改变状态、不误触发）。 */
    fun matches(task: ScheduledTask, s: Snapshot): Boolean? = when (task.condition) {
        ScheduledTasks.COND_TEMP_LT -> s.tempC?.let { it <= task.threshold }
        ScheduledTasks.COND_TEMP_GT -> s.tempC?.let { it >= task.threshold }
        ScheduledTasks.COND_AQI_GT -> s.aqiIndoor?.let { it >= task.threshold }
        ScheduledTasks.COND_AQI_LT -> s.aqiIndoor?.let { it <= task.threshold }
        ScheduledTasks.COND_PM25_GT -> s.pm25Indoor?.let { it >= task.threshold }
        ScheduledTasks.COND_SPEED_GT -> s.speedKmh?.let { it >= task.threshold }
        ScheduledTasks.COND_VOLTAGE_LT -> s.batteryVoltage?.let { it <= task.threshold }
        ScheduledTasks.COND_NIGHT_ON -> s.isNight?.let { it }
        ScheduledTasks.COND_DAY_ON -> s.isNight?.let { !it }
        ScheduledTasks.COND_IGNITION_OFF -> {
            val curr = s.ignitionState
            if (curr == null || curr == AdaptapiSensors.IGNITION_UNKNOWN) {
                null
            } else {
                val prev = lastIgnitionState
                prev != null && isIgnitionOffTransition(prev, curr)
            }
        }
        ScheduledTasks.COND_LOCK_CAR -> {
            val curr = s.ignitionState
            if (curr == null || curr == AdaptapiSensors.IGNITION_UNKNOWN) {
                null
            } else {
                val prev = lastIgnitionState
                prev != null && prev != AdaptapiSensors.IGNITION_ABANDON &&
                    curr == AdaptapiSensors.IGNITION_ABANDON
            }
        }
        else -> null
    }

    /** 评估本轮应触发的任务列表（边沿触发 `false -> true` 且满足冷却时间；线程安全）。 */
    @Synchronized
    fun evaluate(tasks: List<ScheduledTask>, snapshot: Snapshot, nowMs: Long): List<ScheduledTask> {
        val triggered = mutableListOf<ScheduledTask>()
        for (task in tasks) {
            if (!task.enabled || ScheduledTasks.isStartupCondition(task.condition)) continue
            val matched = matches(task, snapshot) ?: continue
            val prev = lastMatched[task.id] ?: false
            lastMatched[task.id] = matched
            if (matched && !prev) {
                val lastAt = lastFiredAtMs[task.id] ?: Long.MIN_VALUE
                val cooldownMs = task.cooldownSeconds.coerceAtLeast(0) * 1000L
                if (lastAt == Long.MIN_VALUE || nowMs - lastAt >= cooldownMs) {
                    lastFiredAtMs[task.id] = nowMs
                    triggered += task
                }
            }
        }
        val currIgn = snapshot.ignitionState
        if (currIgn != null && currIgn != AdaptapiSensors.IGNITION_UNKNOWN) {
            lastIgnitionState = currIgn
        }
        return triggered
    }

    @Synchronized
    fun resetState() {
        lastMatched.clear()
        lastFiredAtMs.clear()
        lastIgnitionState = null
    }

    fun tick(context: Context, prefs: Prefs, snapshot: Snapshot) {
        val tasks = ScheduledTasks.load(prefs)
        val fired = evaluate(tasks, snapshot, SystemClock.elapsedRealtime())
        for (task in fired) {
            Logx.i(
                LogTags.TASK,
                "自动任务条件触发",
                "id" to task.id,
                "condition" to task.condition,
                "threshold" to task.threshold,
                "action" to task.action,
                "target" to task.target,
            )
            runCatching { executeAction(context, task) }.onFailure {
                Logx.e(LogTags.TASK, "自动任务执行异常", it, "id" to task.id, "action" to task.action)
            }
        }
    }

    /** 执行单条动作（供自动任务引擎与开机/进桌面调度共用）。 */
    fun executeAction(context: Context, task: ScheduledTask) {
        when (task.action) {
            ScheduledTasks.ACTION_SEAT_HEAT_DRIVER -> {
                val lvl = task.target.toIntOrNull()?.coerceIn(0, 3) ?: 2
                VehicleController.setSeatLevel(HvacIds.ZONE_DRIVER, heat = true, level = lvl)
            }
            ScheduledTasks.ACTION_SEAT_HEAT_PASSENGER -> {
                val lvl = task.target.toIntOrNull()?.coerceIn(0, 3) ?: 2
                VehicleController.setSeatLevel(HvacIds.ZONE_PASSENGER, heat = true, level = lvl)
            }
            ScheduledTasks.ACTION_SEAT_VENT_DRIVER -> {
                val lvl = task.target.toIntOrNull()?.coerceIn(0, 3) ?: 2
                VehicleController.setSeatLevel(HvacIds.ZONE_DRIVER, heat = false, level = lvl)
            }
            ScheduledTasks.ACTION_CIRC_INNER -> VehicleController.setCirculation(inner = true)
            ScheduledTasks.ACTION_CIRC_OUTSIDE -> VehicleController.setCirculation(inner = false)
            ScheduledTasks.ACTION_HVAC_AC_ON -> VehicleController.setAc(true)
            ScheduledTasks.ACTION_HVAC_AUTO_ON -> VehicleController.setAuto(true)
            ScheduledTasks.ACTION_AMBIENT_ON -> AmbientLightController.setEnabled(context, true)
            ScheduledTasks.ACTION_AMBIENT_OFF -> AmbientLightController.setEnabled(context, false)
            ScheduledTasks.ACTION_CLOSE_WINDOWS -> {
                OemWindowController.setWindowsClosedAsync(context, closed = true) { ok ->
                    Logx.i(LogTags.TASK, "自动任务关闭所有车窗完成", "id" to task.id, "ok" to ok)
                }
            }
            ScheduledTasks.ACTION_URL -> {
                if (task.target.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(task.target))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            ScheduledTasks.ACTION_HOTSPOT_ON,
            ScheduledTasks.ACTION_HOTSPOT_OFF,
            -> HotspotController.executeTaskAction(context, task)
            else -> {
                val pkg = task.target
                if (pkg.isNotBlank()) {
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                    }
                }
            }
        }
    }
}
