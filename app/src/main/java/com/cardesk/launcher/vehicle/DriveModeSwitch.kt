package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「开机自动切换驾驶模式」的**判定与写入**（第一百三十五轮）。
 *
 * ## 为什么要有这个文件
 *
 * 用户在真车诊断包（4231 版）里报的第一条问题就是：
 *
 * > 自动切换驾驶模式功能无效。
 *
 * 查下来是个典型的「**只存不执行**」：
 * 设置页的开关 [com.cardesk.launcher.core.Prefs.startSwitchEnabled]
 * （`start_switch_enabled`）与目标模式 [com.cardesk.launcher.core.Prefs.startSwitchMode]
 * （`start_switch_mode`）**全仓只有 SettingsOverlay 在写，没有任何执行端**
 * （`git log -S start_switch_enabled` 只命中首次提交 `22d5b75`）。
 *
 * 注意别和第132轮接上执行端的**另一个**开关搞混：
 * 那是 `iss_keep_enabled`（发动机启停，功能号 `537002240`），
 * 和驾驶模式（`570491136`）是两条完全不同的功能。
 *
 * ## 写入为什么要自己降级
 *
 * 对标 App 写驾驶模式的顺序（反编译
 * `DriveModeController.writeDriveModeAdaptApi`）：
 *
 * ```
 * setFunctionValue(FUNCTION_DRIVE_MODE_SELECT, modeId)   // 两参，成功就收工
 *   ↓ 失败
 * trySetDriveModeWithZone(f, 2, modeId)                  // 三参 zone=2
 *   ↓ 失败
 * trySetDriveModeWithZone(f, 1, modeId)                  // 三参 zone=1
 * ```
 *
 * 我们原来的 [VehicleGateway.writeFunction] **只有两参一条路**，
 * 固件不提供两参重载时直接失败 —— 对标 App 却能靠 zone 兜底写成功。
 * 所以这里走 [VehicleGateway.writeSelectFunction]（两参优先 + zone 降级）。
 *
 * ## 这里是纯 Kotlin（不碰 Android 上下文），可在 JVM 单测里覆盖。
 */
object DriveModeSwitch {

    /**
     * 两次写入之间的**冷却窗口**（毫秒）。
     *
     * 为什么需要：执行端有**两个**触发点（开机广播 + 桌面启动），
     * 而桌面在一次开机里可能被拉起很多次（用户按 HOME、系统重启 HOME 应用）。
     * 没有冷却就会对着车机连写几十次 —— 写车是同步 Binder，
     * 每次都要跨进程，几十次能把车机服务线程占满。
     *
     * 取 10 分钟：一次开机里肯定够用；车机重启后墙上时钟会跳到当前时间，
     * 冷却自然失效 → 新的一次开机照样会切。
     */
    const val COOLDOWN_MS = 10 * 60 * 1000L

    /**
     * 三参降级时的 zone 顺序。
     *
     * 照抄对标 App `writeDriveModeAdaptApi`：**先 2 后 1**。
     *
     * 为什么不反过来：zone=2 是这台车（领克 dcy11 / 820A 平台）的驾驶模式 zone，
     * 对标 App 把 2 放在 1 前面是有依据的。
     */
    val FALLBACK_ZONES = intArrayOf(2, 1)

    /**
     * 目标模式**合不合法**。
     *
     * 合法 = 能归一成一个**真实存在的模式**（不是读失败哨兵、不是未知值）。
     *
     * 这里比 [DriveMode.SUPPORTED_VALUES] 宽一点：本车面板只列 3 档
     * （经济 / 舒适 / 运动），但持久化里可能留着别的合法档位
     * （例如 HEV 的 `570491153`），不该因为「不在面板列表里」就拒绝执行。
     */
    fun isValidTarget(target: Int): Boolean {
        if (DriveMode.isReadError(target)) return false
        val normalized = DriveMode.normalizeModeId(target)
        if (normalized == DriveMode.SELECTION_NONE) return false
        return DriveMode.shortLabelForValue(normalized) != null
    }

    /**
     * 车端当前模式**是不是已经是目标**了。
     *
     * 已经是 → 不用写（写一遍也是白写，还多一次跨进程）。
     *
     * @param current 读回来的原始值；`null` = 读不到 → 判为「不知道」→ **要写**
     *   （读不到 ≠ 已经是目标；这个区分很重要，否则读失败的机器永远不会切换）。
     */
    fun alreadyAt(current: Int?, target: Int): Boolean {
        val c = current ?: return false
        if (DriveMode.isReadError(c)) return false
        return DriveMode.normalizeModeId(c) == DriveMode.normalizeModeId(target)
    }

    /** 冷却窗口过没过。 */
    fun cooldownElapsed(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || now - lastAt >= COOLDOWN_MS

    /**
     * 这次**要不要执行**一次写入（把所有前置条件收在一处，便于单测钉死）。
     *
     * @param enabled 开关（`start_switch_enabled`）
     * @param target 目标模式（`start_switch_mode`）
     * @param current 读回来的当前模式（`null` = 读不到）
     * @param lastAt 上一次执行的时间戳（墙上时钟，毫秒；0 = 从没执行过）
     * @param now 现在的墙上时钟（毫秒）
     */
    fun shouldRun(
        enabled: Boolean,
        target: Int,
        current: Int?,
        lastAt: Long,
        now: Long,
    ): Boolean = enabled &&
        isValidTarget(target) &&
        !alreadyAt(current, target) &&
        cooldownElapsed(lastAt, now)

    /**
     * 不执行时的**原因**（排障用，日志与诊断包里都要看到）。
     *
     * 空串 = 没有理由拦它 → 可以执行。
     */
    fun skipReason(
        enabled: Boolean,
        target: Int,
        current: Int?,
        lastAt: Long,
        now: Long,
    ): String = when {
        !enabled -> "开关关闭"
        !isValidTarget(target) -> "目标模式非法($target)"
        alreadyAt(current, target) -> "已经是目标模式"
        !cooldownElapsed(lastAt, now) -> "冷却中（距上次 ${now - lastAt}ms）"
        else -> ""
    }

    /**
     * 读一次当前驾驶模式。
     *
     * 读法按文档口径：先一参 `getFunctionValue(func)`，读不到再退两参
     * （部分固件只实现带 zone 的读取；两者都在文档的方法族里）。
     *
     * @return 原始值；`null` = 读不到（**不是**模式号）
     */
    fun readCurrent(gw: VehicleGateway): Int? =
        runCatching { gw.readFunction(DriveMode.FUNCTION_DRIVE_MODE_SELECT) }.getOrNull()
            ?: runCatching { gw.readValue(DriveMode.FUNCTION_DRIVE_MODE_SELECT, 0) }.getOrNull()
            ?: runCatching { gw.readValue(DriveMode.FUNCTION_DRIVE_MODE_SELECT, DriveMode.ZONE) }.getOrNull()

    /**
     * 写一次驾驶模式。
     *
     * 走 [VehicleGateway.writeSelectFunction]：**两参优先**，
     * 失败再按 [FALLBACK_ZONES] 降级到三参（与对标 App 一致）。
     *
     * @return `true` = 车机收下了这条指令（**不代表模式真的变了**，
     *   真车还要回读确认，见 [VehicleController.verifyDriveMode]）。
     */
    fun write(gw: VehicleGateway, target: Int): Boolean {
        if (!isValidTarget(target)) {
            Logx.w(LogTags.VEHICLE, "驾驶模式写入值非法", "value" to target)
            return false
        }
        val ok = runCatching { gw.writeSelectFunction(DriveMode.FUNCTION_DRIVE_MODE_SELECT, target) }
            .getOrDefault(false)
        Logx.i(
            LogTags.VEHICLE,
            if (ok) "写入驾驶模式" else "写入驾驶模式失败",
            "func" to VehicleLog.hex(DriveMode.FUNCTION_DRIVE_MODE_SELECT),
            "value" to target,
            "label" to DriveMode.fullLabel(target),
            "ok" to ok,
            "reason" to runCatching { gw.lastError() }.getOrNull(),
        )
        return ok
    }
}
