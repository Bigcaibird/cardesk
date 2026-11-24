package com.cardesk.launcher.core

import android.content.Context
import android.content.pm.PackageManager
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 原车最近任务压制（第三百一十九轮）。
 *
 * ## 为什么要压
 *
 * Home 键的双击在真车上有两套互不相关的实现：
 *
 * - 我们这套：车机 Input 通道（`ecarx.xui.adaptapi.input.Input`）拦到 Home 键，
 *   自己数双击，弹**本项目**的最近任务页；
 * - 车机框架那套：`services` 里的 double-tap-home 逻辑，双击时拉起
 *   `com.ecarx.recents/…HomeActivity`。
 *
 * 真车实测（2026-11-24 诊断包）：即使拦截回调返回 `true`（吃掉按键），框架那套
 * 仍然照跑 —— 用户会看到「我们的最近任务先出来，车机自己的最近任务再压上来」。
 * 光靠 `abortBroadcast` / `consume` 压不住，只能在接管期间把车机的最近任务
 * **组件**禁用掉，等退出桌面或关掉开关时再恢复。
 *
 * ## 边界
 *
 * - 只在「Home 键接管真的生效」且「双击映射到本项目最近任务」时才压制；
 *   接管没拿到（很多固件不给）、或用户把双击映射关掉，都不动系统。
 * - 压制状态写进 prefs（[KEY_APPLIED]）：万一进程被杀/车机重启，下次启动
 *   [restoreIfNeeded] 会把组件恢复，不会留下「车机永远没有最近任务」的坑。
 * - 目标包不存在（模拟器/别的车型）只记一条日志，不算失败。
 */
object OemRecentsSuppressor {

    /** 真车最近任务包名（诊断包里前台任务是 `com.ecarx.recents/.home.HomeActivity`）。 */
    const val TARGET_PACKAGE = "com.ecarx.recents"

    /** 压制状态标记；进程被杀后靠它恢复。 */
    const val KEY_APPLIED = "oem_recents_suppressed"

    @Volatile
    var statusText: String = "未处理"
        private set

    /** 只在「接管生效 + 双击开最近任务」时才压制。 */
    fun wanted(prefs: Prefs): Boolean =
        HomeKeyRuntime.enabled(prefs) && HomeKeyRuntime.doubleRecents(prefs)

    /**
     * 按当前状态同步一次：该压制就压制，不该压制且之前压过就恢复。
     *
     * 由 [com.cardesk.launcher.vehicle.HomeKeyInterceptor] 在接管成功/停止时调用，
     * 以及设置页开关切换后调用。幂等。
     */
    fun sync(context: Context, prefs: Prefs) {
        val suppress = wanted(prefs) && HomeKeyRuntime.interceptorActive
        if (suppress) suppress(context, prefs) else release(context, prefs)
    }

    /** 启动时兜底：上次记着「压过」，现在不该压了就恢复。 */
    fun restoreIfNeeded(context: Context, prefs: Prefs) {
        if (prefs.flag(KEY_APPLIED, false)) {
            Logx.i(LogTags.HOME, "原车最近任务：上次退出时处于压制状态，先恢复", "pkg" to TARGET_PACKAGE)
            release(context, prefs)
        }
    }

    fun suppress(context: Context, prefs: Prefs) {
        val pm = context.packageManager
        if (!installed(pm)) {
            statusText = "原车最近任务未安装（无需压制）"
            prefs.setFlag(KEY_APPLIED, false)
            Logx.i(LogTags.HOME, "原车最近任务：未安装，跳过压制", "pkg" to TARGET_PACKAGE)
            return
        }
        val ok = runCatching {
            pm.setApplicationEnabledSetting(
                TARGET_PACKAGE,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.DONT_KILL_APP,
            )
            pm.getApplicationEnabledSetting(TARGET_PACKAGE) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }.getOrElse { error ->
            Logx.w(LogTags.HOME, "原车最近任务：压制失败（继续用本项目最近任务，车机那个可能还会弹）", error)
            false
        }
        prefs.setFlag(KEY_APPLIED, ok)
        statusText = if (ok) "已压制（$TARGET_PACKAGE 已禁用）" else "压制失败"
        Logx.i(
            LogTags.HOME,
            if (ok) "原车最近任务：已压制" else "原车最近任务：压制失败",
            "pkg" to TARGET_PACKAGE,
            "applied" to ok,
        )
    }

    fun release(context: Context, prefs: Prefs) {
        if (!prefs.flag(KEY_APPLIED, false) && !installed(context.packageManager)) {
            statusText = "未压制"
            return
        }
        val pm = context.packageManager
        runCatching {
            if (installed(pm)) {
                pm.setApplicationEnabledSetting(
                    TARGET_PACKAGE,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }.onFailure {
            Logx.w(LogTags.HOME, "原车最近任务：恢复失败（下次启动再试）", it)
        }
        prefs.setFlag(KEY_APPLIED, false)
        statusText = "未压制"
        Logx.i(LogTags.HOME, "原车最近任务：已恢复", "pkg" to TARGET_PACKAGE)
    }

    private fun installed(pm: PackageManager): Boolean = runCatching {
        pm.getPackageInfo(TARGET_PACKAGE, 0)
        true
    }.getOrDefault(false)
}
