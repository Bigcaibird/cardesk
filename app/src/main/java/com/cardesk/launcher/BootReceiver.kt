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
        // bootMs = 开机以来毫秒。诊断包里跟「启动分段」的 bootMs 一对照，
        // 就能分清「系统开机到广播用掉多久」和「广播到桌面首帧用掉多久」。
        Logx.i(
            LogTags.APP,
            "收到开机广播",
            "action" to intent.action,
            "bootMs" to android.os.SystemClock.elapsedRealtime(),
        )
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(launch)
            Logx.i(
                LogTags.APP,
                "开机广播已拉起桌面",
                "bootMs" to android.os.SystemClock.elapsedRealtime(),
            )
        } catch (e: Throwable) {
            // Android 10+ 后台启动 Activity 受限，作为 HOME 应用通常已被系统拉起，失败可忽略
            Logx.w(LogTags.APP, "开机广播拉起桌面失败（Android 10+ 后台启动受限）", e)
        }
        // 全局 Dock（第八十七轮）：开机就要常驻。
        // 不能只靠「桌面启动时顺手拉」—— 车机冷启动后用户可能直接开导航，
        // 桌面进程（以及它里面的服务）要等用户回 HOME 才会起来，那时 Dock 早就缺位了；
        // 这里直接 startForegroundService，独立于桌面 Activity。
        runCatching { com.cardesk.launcher.core.dock.GlobalDockService.ensureStarted(context) }
            .onFailure { Logx.w(LogTags.DOCK, "开机启动全局 Dock 失败", it) }
        applyAdbAutoStart(context)
        applyIssAutoStart(context)
        applyClusterLyricsStart(context)
    }

    /**
     * 开机把「仪表歌词投射」服务拉起来（第一百三十三轮）。
     *
     * ## 为什么桌面起来之后还要再拉一次
     *
     * 桌面是 HOME 应用，冷启动一般会被系统拉起 → `MainActivity` 里已经起过一次。
     * 但 Android 10+ 禁止后台启动 Activity，上面那次 `startActivity` **可能失败**
     * （车机不主动 re-launch HOME 时尤其容易失败）；
     * 桌面起不来 = `MainActivity.startLaunchServices` 跑不到 = 投射服务不会起来。
     *
     * 所以这里独立拉一次。开机广播的接收方在系统里有**临时白名单**
     * （`BOOT_COMPLETED` 在 Android 8+ 的后台启动豁免名单里），
     * 所以 `startService` 不会被后台执行限制挡掉。
     *
     * 失败不重试：服务自己带指数退避（1s → 30s），
     * 屏还没热起来 / 悬浮窗权限还没生效时它会自己重连。
     */
    private fun applyClusterLyricsStart(context: Context) {
        val app = context.applicationContext
        if (!Prefs(app).clusterOverlayEnabled) return
        runCatching { com.cardesk.launcher.cluster.ClusterLyricsService.apply(app) }
            .onFailure { Logx.w(LogTags.CLUSTER, "开机启动仪表歌词投射失败", it) }
    }

    /**
     * 开机自动关闭启停（ISS）。
     *
     * ## 为什么要在这里等
     *
     * 车控通道是**异步连上**的：开机广播发出来的时候，
     * `AdaptapiGateway` 往往还没拿到 `ICarFunction` 句柄。
     * 这时候直接写会被拒（「拿不到句柄」），所以这里轮询等通道就绪，
     * 最多等 [ISS_BOOT_TIMEOUT_MS]。
     *
     * ## 为什么要自己 init 车控
     *
     * `VehicleController.init` 平时是 `LauncherView` 建的，而 LauncherView 依附于
     * MainActivity —— Android 10+ 禁止后台启动 Activity，
     * 上面那次 `startActivity` **可能失败**：
     * 桌面起不来 = 车控通道永远不初始化 = 启停永远关不掉。
     * 所以这里主动 init 一次（`init` 本身幂等，`if (initialized) return`，
     * 后面 LauncherView 再调一次不会有副作用）。
     *
     * ## 只在开关打开时才动
     *
     * `iss_keep_enabled` 关着 = 用户明确表示「别碰车机启停」，
     * 这里就什么都不做。
     */
    private fun applyIssAutoStart(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.issKeepEnabled) return
        Thread {
            runCatching { com.cardesk.launcher.vehicle.VehicleController.init(app, prefs) }
                .onFailure { Logx.w(LogTags.VEHICLE, "开机初始化车控失败", it) }
            var waited = 0L
            while (waited < ISS_BOOT_TIMEOUT_MS) {
                val gw = if (com.cardesk.launcher.vehicle.VehicleController.initialized) {
                    runCatching { com.cardesk.launcher.vehicle.VehicleController.gateway }.getOrNull()
                } else {
                    null
                }
                if (gw != null && runCatching { gw.isServiceConnected() }.getOrDefault(false)) {
                    com.cardesk.launcher.vehicle.VehicleController.setIssEnabled(false) { ok, msg ->
                        Logx.i(LogTags.VEHICLE, "开机自动关闭启停", "ok" to ok, "detail" to msg)
                    }
                    return@Thread
                }
                Thread.sleep(ISS_BOOT_POLL_MS)
                waited += ISS_BOOT_POLL_MS
            }
            Logx.w(LogTags.VEHICLE, "开机自动关闭启停超时：车控通道一直没连上")
        }.apply {
            name = "iss-boot"
            isDaemon = true
        }.start()
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

    private companion object {
        /** 等车控通道就绪的最长时间（毫秒）。 */
        const val ISS_BOOT_TIMEOUT_MS = 30_000L

        /** 轮询间隔（毫秒）。 */
        const val ISS_BOOT_POLL_MS = 500L
    }
}
