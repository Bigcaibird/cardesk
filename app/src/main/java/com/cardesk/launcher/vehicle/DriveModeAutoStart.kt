package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「开机自动切换驾驶模式」的**执行端**（第一百三十五轮）。
 *
 * ## 以前为什么没反应
 *
 * 设置页的开关（`start_switch_enabled`）与目标模式（`start_switch_mode`）
 * 全仓**只有 SettingsOverlay 在写**，没有任何人读 —— 典型的「只存不执行」。
 * 第132轮接上执行端的是**另一个**开关（`iss_keep_enabled`，发动机启停），
 * 不是这个。
 *
 * ## 现在什么时候切
 *
 * 两个触发点，指向同一个入口：
 *
 * 1. **开机广播**（`BootReceiver`）：车机真正冷启动 → 这是「开机就切」的主场景；
 * 2. **桌面启动**（`LauncherView`）：有些车机不把 `BOOT_COMPLETED` 广播给我们
 *    （真车诊断包 4231 里就**一条「收到开机广播」都没有** —— 那次是进程重启，
 *    不是设备开机），桌面起来时补一刀，保证这条功能不至于完全瘫掉。
 *
 * 两个触发点都走 [DriveModeSwitch.COOLDOWN_MS] 冷却，一次开机只切一次。
 *
 * ## 为什么要等车控通道
 *
 * 车控通道是**异步连上**的：开机广播发出来的时候，`AdaptapiGateway`
 * 往往还没拿到 `ICarFunction` 句柄，这时候直接写会被拒（「拿不到句柄」）。
 * 所以这里轮询等通道就绪，最多等 [WAIT_TIMEOUT_MS]。
 */
object DriveModeAutoStart {

    /** 等车控通道就绪的最长时间（毫秒）。 */
    const val WAIT_TIMEOUT_MS = 30_000L

    /** 轮询间隔（毫秒）。 */
    const val WAIT_POLL_MS = 500L

    /**
     * 执行一次「开机自动切换驾驶模式」。
     *
     * 幂等：开关关着 / 目标非法 / 冷却中 / 已经是目标模式 → 什么都不做。
     *
     * @param trigger 谁触发的（`开机广播` / `桌面启动`），只进日志，便于分辨是哪条路生效
     */
    fun apply(context: Context, trigger: String) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.startSwitchEnabled) {
            Logx.d(LogTags.VEHICLE, "自动切换驾驶模式：开关关闭", "trigger" to trigger)
            return
        }
        val target = prefs.startSwitchMode
        if (!DriveModeSwitch.isValidTarget(target)) {
            Logx.w(
                LogTags.VEHICLE,
                "自动切换驾驶模式：目标模式非法",
                "trigger" to trigger,
                "target" to target,
            )
            return
        }
        // 冷却在**线程里**再判一次（这里只是早退，省一次线程）：
        // 两个触发点可能几乎同时来。
        val now = System.currentTimeMillis()
        val lastAt = prefs.driveModeSwitchLastAt
        if (!DriveModeSwitch.cooldownElapsed(lastAt, now)) {
            Logx.i(
                LogTags.VEHICLE,
                "自动切换驾驶模式：冷却中，跳过",
                "trigger" to trigger,
                "target" to target,
                "sinceLastMs" to (now - lastAt),
            )
            return
        }
        Thread {
            runCatching {
                // 主动 init 一次：桌面（LauncherView）不一定已经起来，
                // 而 Android 10+ 又禁止后台启动 Activity → 桌面的 init 可能没跑过。
                // `VehicleController.init` 本身幂等（`if (initialized) return`）。
                runCatching { VehicleController.init(app, prefs) }
                    .onFailure { Logx.w(LogTags.VEHICLE, "自动切换驾驶模式：初始化车控失败", it) }
                val gw = awaitGateway()
                if (gw == null) {
                    record(prefs, target, "失败：车控通道超时未连上")
                    Logx.w(
                        LogTags.VEHICLE,
                        "自动切换驾驶模式：车控通道超时未连上",
                        "trigger" to trigger,
                        "target" to target,
                    )
                    return@runCatching
                }
                val current = DriveModeSwitch.readCurrent(gw)
                val at = System.currentTimeMillis()
                val previous = prefs.driveModeSwitchLastAt
                if (!DriveModeSwitch.shouldRun(true, target, current, previous, at)) {
                    val why = DriveModeSwitch.skipReason(true, target, current, previous, at)
                    Logx.i(
                        LogTags.VEHICLE,
                        "自动切换驾驶模式：本次不执行",
                        "trigger" to trigger,
                        "target" to target,
                        "current" to current,
                        "reason" to why,
                    )
                    return@runCatching
                }
                // 冷却从**这次尝试**开始算：失败了也不在十分钟内反复戳车机。
                prefs.driveModeSwitchLastAt = at
                VehicleController.setDriveMode(target) { ok, message ->
                    record(prefs, target, if (ok) "已切到${DriveMode.fullLabel(target)}" else message)
                    Logx.i(
                        LogTags.VEHICLE,
                        "自动切换驾驶模式：结果",
                        "trigger" to trigger,
                        "target" to target,
                        "ok" to ok,
                        "message" to message,
                    )
                }
            }.onFailure {
                Logx.w(LogTags.VEHICLE, "自动切换驾驶模式执行异常", it, "trigger" to trigger)
            }
        }.apply {
            name = "drive-mode-auto"
            isDaemon = true
        }.start()
    }

    /**
     * 等车控通道连上（最多 [WAIT_TIMEOUT_MS]）。
     *
     * 与 `BootReceiver.applyIssAutoStart` 同一套样板：开机时车机服务还没起来，
     * 直接写会被拒。
     */
    private fun awaitGateway(): VehicleGateway? {
        var waited = 0L
        while (waited < WAIT_TIMEOUT_MS) {
            val gw = if (VehicleController.initialized) {
                runCatching { VehicleController.gateway }.getOrNull()
            } else {
                null
            }
            if (gw != null && runCatching { gw.isServiceConnected() }.getOrDefault(false)) {
                return gw
            }
            Thread.sleep(WAIT_POLL_MS)
            waited += WAIT_POLL_MS
        }
        return null
    }

    /** 记一笔结果，供诊断包查看（用户报「无效」时一眼能看出跑到哪一步）。 */
    private fun record(prefs: Prefs, target: Int, message: String) {
        runCatching {
            prefs.driveModeSwitchLastResult = message
            prefs.driveModeSwitchLastTarget = target
        }.onFailure { Logx.w(LogTags.VEHICLE, "记录自动切换驾驶模式结果失败", it) }
    }
}
