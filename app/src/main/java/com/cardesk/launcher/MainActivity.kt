package com.cardesk.launcher

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.NotificationAccess
import com.cardesk.launcher.ui.LauncherView

/**
 * 唯一的 Activity：承载整个桌面。
 *
 * 作为 HOME 应用被系统拉起（Manifest 里声明了 MAIN/LAUNCHER/HOME），
 * 不主动 finish，返回键交给 [LauncherView] 处理。
 */
class MainActivity : Activity() {

    private lateinit var launcher: LauncherView

    /**
     * 已经因为「没通知使用权」跳过一次系统设置页。
     *
     * 防止从设置页返回后又跳一次（用户明明点了拒绝）。
     */
    private var notificationGuideShown = false

    private val notificationCheck = Runnable { checkNotificationPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志系统要先于一切初始化（后面的每一步埋点都依赖它）
        Logx.init(applicationContext)
        Logx.i(
            LogTags.APP,
            "桌面启动",
            "sdk" to Build.VERSION.SDK_INT,
            "pid" to Process.myPid(),
            "restored" to (savedInstanceState != null),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        // 隐藏 API 豁免：车机的天气 SDK / TBox 都是 ROM 私有类，
        // 没有豁免就反射不到（走的是 targetSdk 29 的口径，我们已对齐）。
        // 只影响本进程，失败也不影响任何功能 —— 见 `core/HiddenApiCompat`。
        com.cardesk.launcher.core.HiddenApiCompat.applyOnStart(applicationContext)

        launcher = LauncherView(this)
        setContentView(launcher)
        runCatching {
            com.cardesk.launcher.core.TaskScheduler.scheduleLaunchTasks(
                applicationContext,
                com.cardesk.launcher.core.Prefs(applicationContext),
            )
        }.onFailure { Logx.w(LogTags.TASK, "桌面内任务调度失败", it) }
        runCatching { com.cardesk.launcher.core.media.MusicController.get().start(applicationContext) }
            .onFailure { Logx.w(LogTags.MEDIA, "媒体会话启动失败", it) }
        // MainActivity.checkNotificationPermission():1297-1305 —— 没通知使用权就跳系统设置页。
        // 这里延后一点再判：通知监听服务是**异步绑定**的，立刻判会把「还没绑上」误判成「没授权」，
        // 白白把用户弹到系统设置页。
        launcher.removeCallbacks(notificationCheck)
        launcher.postDelayed(notificationCheck, NOTIFICATION_CHECK_DELAY_MS)
        // 定位权限（天气要用）。真机上「权限显示已授予但一个位置都拿不到」——
        // 根因是 AppOps 没打开（pm grant 只写 permission grant）。
        // 这里走一次正常授权流程：system app 直接写 AppOps，普通安装走系统弹窗。
        launcher.postDelayed(locationCheck, LOCATION_CHECK_DELAY_MS)
    }

    /** 申请定位权限（延后一点，别和桌面首帧抢）。 */
    private val locationCheck = Runnable {
        runCatching { com.cardesk.launcher.core.Permissions.ensureLocation(this) }
            .onFailure { Logx.w(LogTags.WEATHER, "定位权限处理失败", it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != com.cardesk.launcher.core.Permissions.REQUEST_LOCATION) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        Logx.i(
            LogTags.WEATHER,
            "定位权限申请结果",
            "granted" to granted,
            "state" to com.cardesk.launcher.core.Permissions.describeLocationState(this),
        )
        // 授权完成后立刻重跑一轮天气取数。
        if (granted) {
            runCatching { launcher.refreshWeather() }
                .onFailure { Logx.w(LogTags.WEATHER, "定位授权后刷新天气失败", it) }
        }
    }

    override fun onResume() {
        super.onResume()
        Logx.i(LogTags.APP, "onResume")
        hideSystemUi()
        // 从系统「通知使用权」页返回时再判一次：授权成功就不该再跳
        launcher.removeCallbacks(notificationCheck)
        launcher.postDelayed(notificationCheck, NOTIFICATION_CHECK_DELAY_MS)
        // 从系统「应用权限」页返回时也再判一次定位。
        launcher.removeCallbacks(locationCheck)
        launcher.postDelayed(locationCheck, LOCATION_CHECK_DELAY_MS)
    }

    override fun onPause() {
        Logx.i(LogTags.APP, "onPause")
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { com.cardesk.launcher.core.media.MusicController.get().stop() }
            .onFailure { Logx.w(LogTags.MEDIA, "媒体会话停止失败", it) }
        Logx.i(LogTags.APP, "onDestroy")
        super.onDestroy()
    }

    /**
     * 通知使用权检查 + 引导。
     *
     * **判定条件一致**：系统白名单里没有我们的通知监听组件 → 跳
     * `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`。
     *
     * 只有这一条通路（只看
     * `enabled_notification_listeners` 白名单串，所以它**总是弹**。
     * 之前我们把条件放宽成「会话枚举有没有通路」（`MEDIA_CONTENT_CONTROL`
     * 也算，于是平台签名的 system 包（自带该权限）在真机上永远不弹 ——
     * 与行为不符，用户反馈「版是会弹的」。
     *
     * 与差异只有一处：**本次进程内只引导一次**
     * 用户拒绝后不会被反复弹走（每次 onCreate 都会弹）。
     */
    private fun checkNotificationPermission() {
        if (NotificationAccess.isReferenceGranted(this)) {
            if (notificationGuideShown) {
                Logx.i(LogTags.APP, "通知使用权已授予", "state" to NotificationAccess.describeEnumeration(this))
            }
            notificationGuideShown = false
            return
        }
        if (notificationGuideShown) return
        notificationGuideShown = true
        Logx.event(
            LogTags.APP,
            "未授予通知使用权，跳系统设置页引导",
            "component" to NotificationAccess.referenceListenerComponent(this),
        )
        val opened = NotificationAccess.openSettings(this)
        if (opened) {
            // 没有这一步；只是给用户一个「为什么要跳」的提示，不改变任何行为
            Toast.makeText(
                this,
                "请在系统设置中授予「通知使用权」，否则桌面无法读取音乐信息",
                Toast.LENGTH_LONG,
            ).show()
        }
        Logx.i(
            LogTags.APP, "通知使用权引导结束",
            "opened" to opened,
            "state" to NotificationAccess.describeEnumeration(this),
        )
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 返回键永远不退出桌面：先关浮层，再回首页，最后停在首页。
        // 作为 HOME 应用不调用 super.onBackPressed()，否则会把桌面自己 finish 掉。
        launcher.onBack()
    }

    /**
     * 画中画全屏时**拦下返回键**。
     *
     * ## 为什么必须在这里拦
     *
     * AOSP 的 `ActivityView` 会把 key event **转发给 VirtualDisplay 里的应用**
     * （`ActivityView.dispatchKeyEvent`），所以全屏时按返回键，
     * 事件被承载应用（高德）吃掉 —— 桌面这边根本收不到，
     * 结果「画中画全屏」变成**单向门**：进得去、出不来。
     *
     * `Activity.dispatchKeyEvent` 比 View 树的派发更早，
     * 在这里吃掉 BACK，全屏就能用返回键还原。
     *
     * 做法是全屏时露出 `navSplit` 玻璃条当还原按钮
     * ——
     * 我们画中画全屏时是提到根容器上的，`navSplit` 被盖住，
     * 所以这里用返回键兜底。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (launcher.isPipFullscreen()) {
                Logx.event(LogTags.NAV_UI, "画中画全屏：返回键还原")
                launcher.exitPipFullscreen()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        /**
         * 延迟多久再判通知使用权。
         *
         * 通知监听服务是系统异步绑定的，刚启动时通常还没绑上；
         * 太早判会把「还没绑上」误判成「没授权」，白把用户弹到系统设置页。
         */
        const val NOTIFICATION_CHECK_DELAY_MS = 2000L

        /**
         * 延迟多久再申请定位权限。
         *
         * 定位权限是天气的前提，但弹窗会打断首屏观感 —— 延后一点再问。
         * 用户拒绝后不会重复弹（系统行为），只在 `onResume` 再判一次状态。
         */
        const val LOCATION_CHECK_DELAY_MS = 3500L
    }
}
