package com.cardesk.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cardesk.launcher.core.AdbBridge
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 开机后把桌面拉起来。作为 HOME 应用的兜底：部分车机不主动 re-launch HOME。
 *
 * 第五十五轮顺带承担了「自动 ADB」的自启执行：
 * 设置页那两个开关以前只**记录状态**（勾上就写一下 `adb_enabled`），
 * 注释里说「重启后由 BootReceiver 再尝试」，但 BootReceiver 根本没做。
 * 现在真正接上了：
 *
 * - `adbAuto` → 开机打开调试开关（开发者选项 + USB 调试 + 开发者页面入口）；
 * - `adbWirelessAuto` → 开机再把无线端口切到 5555。
 *
 * 两个开关都关时，开机不做任何系统设置写入。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        Logx.init(context.applicationContext)
        Logx.i(LogTags.APP, "收到开机广播", "action" to intent.action)
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(launch)
            Logx.i(LogTags.APP, "开机广播已拉起桌面")
        } catch (e: Throwable) {
            // Android 10+ 后台启动 Activity 受限，作为 HOME 应用通常已被系统拉起，失败可忽略
            Logx.w(LogTags.APP, "开机广播拉起桌面失败（Android 10+ 后台启动受限）", e)
        }
        applyAdbAutoStart(context)
    }

    /**
     * 开机自动打开 ADB（按设置页的两个开关）。
     *
     * 放在后台线程：写系统设置、切端口、重启 adbd 都会耗时，
     * 广播主线程被堵住会拖慢开机（也会被系统判 ANR）。
     */
    private fun applyAdbAutoStart(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val wantDebug = prefs.adbAuto
        val wantWireless = prefs.adbWirelessAuto
        if (!wantDebug && !wantWireless) return
        Thread {
            val controller = AdbBridge.AdbRuntime.controller(app)
            if (wantDebug) {
                val outcome = controller.enableDebug()
                AdbBridge.AdbRuntime.logOutcome("开机自动 ADB", outcome)
            }
            if (wantWireless) {
                val outcome = controller.enableWireless()
                AdbBridge.AdbRuntime.logOutcome("开机自动无线 ADB", outcome)
            }
        }.apply {
            name = "adb-boot"
            isDaemon = true
        }.start()
    }
}
