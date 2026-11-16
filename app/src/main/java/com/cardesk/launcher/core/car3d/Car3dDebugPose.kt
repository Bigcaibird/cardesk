package com.cardesk.launcher.core.car3d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.vehicle.MockVehicleGateway
import com.cardesk.launcher.vehicle.VehicleController

/**
 * **调试专用**的姿态注入：只覆盖「画哪张车模图」，**不碰任何真实车况读数**。
 *
 * ## 为什么需要
 *
 * API 28 模拟器上没有车控 API（`Car.createCar` 拿不到属性通道），车门/车窗读出来
 * 全是 `null` → 车模永远画 `closed`。而验收截图要看到「左前门未关」的样子。
 * 真车上永远不会有这个入口（见下面的门控），它只是一条给截图用的旁路。
 *
 * ## 用法（仅 debuggable 变体）
 *
 * ```
 * adb shell am broadcast -a com.cardesk.launcher.DEBUG_POSE --es lf 1
 * adb shell am broadcast -a com.cardesk.launcher.DEBUG_POSE --es wins 1
 * adb shell am broadcast -a com.cardesk.launcher.DEBUG_POSE --es trunk 1
 * adb shell am broadcast -a com.cardesk.launcher.DEBUG_POSE --es clear 1
 * ```
 *
 * 门/窗参数可任意组合（`lf`/`rf`/`lb`/`rb`/`wins`/`trunk`），值 `1`/`true` = 开。
 * 广播只写 [pose]，`ScenePage` / `CarTopStatusView` 在下一帧绘制时优先用它；
 * 1Hz 的刷新会把新姿态带进重绘（≤1s 生效）。
 *
 * ## 门控
 *
 * 只有 `applicationInfo.flags and FLAG_DEBUGGABLE != 0` 才注册 receiver。
 * `buildTypes.system` 是 `initWith(debug)`，所以车机上的 system 变体也带得动这条旁路，
 * `release` 变体（用户分发）没有这个 receiver。
 */
object Car3dDebugPose {

    /** 调试广播 action。 */
    const val ACTION = "com.cardesk.launcher.DEBUG_POSE"

    /** 注入的姿态；`null` = 用真实车况。 */
    @Volatile
    var pose: CarPose? = null

    /** 是否已注册（测试/日志用）。 */
    @Volatile
    var installed: Boolean = false
        private set

    private var receiver: BroadcastReceiver? = null

    /** 按 bundle 里的门/窗/尾门开关造一个姿态（单独出来是为了能纯 JVM 测）。 */
    fun poseFrom(intent: Intent?): CarPose? {
        if (intent == null) return null
        if (on(intent, "clear")) return null
        val doors = BooleanArray(CarPose.DOOR_COUNT) { on(intent, DOOR_KEYS[it]) }
        // 单窗优先（win_lf/win_rf/win_lb/win_rb），否则 wins 统一开四窗；截图需要「只开一扇」。
        val windows = BooleanArray(CarPose.DOOR_COUNT) { k ->
            on(intent, WIN_KEYS[k]) || on(intent, "wins")
        }
        val trunk = on(intent, "trunk")
        if (!doors.any { it } && !windows.any { it } && !trunk) return null
        return CarPose(doors = doors, windows = windows, trunk = trunk)
    }

    /** 仅在 debuggable 变体注册；重复调用只装一次。 */
    fun install(context: Context) {
        if (installed) return
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Logx.d(LogTags.SCENE, "car3d 调试姿态注入未启用（非 debuggable 变体）")
            installed = true
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val p = poseFrom(intent)
                pose = p
                applyToMock(p)
                // 调试旁路：顺便可切车模/车漆/视角，方便截图时不用点设置页。
                // 只写偏好并重载，真实用户路径不变（仅 debuggable 生效）。
                if (ctx != null) {
                    val prefs = com.cardesk.launcher.core.Prefs(ctx)
                    var changed = false
                    intent?.getStringExtra("paint")?.let {
                        prefs.setStringValue(Car3dSettings.KEY_PAINT, it); changed = true
                    }
                    intent?.getStringExtra("view")?.let {
                        prefs.setStringValue(Car3dSettings.KEY_CARD_VIEW, it); changed = true
                    }
                    intent?.getStringExtra("model")?.let {
                        prefs.setStringValue(com.cardesk.launcher.core.CarModels.KEY, it); changed = true
                    }
                    if (changed) Car3dSettings.load(ctx, prefs)
                }
                Logx.d(LogTags.SCENE, "car3d 调试姿态注入", "pose" to (p ?: "clear"))
            }
        }
        runCatching { context.registerReceiver(r, IntentFilter(ACTION)) }
            .onSuccess {
                receiver = r
                installed = true
                Logx.d(LogTags.SCENE, "car3d 调试姿态注入已注册", "action" to ACTION)
            }
            .onFailure { Logx.w(LogTags.SCENE, "car3d 调试姿态注入注册失败", "error" to it) }
    }

    /**
     * 调试旁路：**仅当当前网关是模拟车辆**时，把姿态同步进模拟车端状态。
     *
     * 为什么需要：模拟器上没有车控 API，车门读数恒为「全关」；如果只改绘制姿态，
     * 就会出现「车模开门、旁边文字写全关」的自相矛盾截图。
     *
     * 边界：只写 [MockVehicleGateway.simDoorsClosed] 与 `setWindowsOpen`（模拟值），
     * 真车通道（AdaptAPI / car_signal / PA_CP）一个字段都不碰；真车上根本没有 mock 网关，
     * 这条分支不会生效。**仍然只用于调试截图。**
     */
    fun applyToMock(p: CarPose?) {
        val gw = runCatching { VehicleController.gateway }.getOrNull() as? MockVehicleGateway ?: return
        for (i in 0 until CarPose.DOOR_COUNT) {
            gw.simDoorsClosed[i] = !(p?.doors?.get(i) ?: false)
        }
        // 逐扇写入（单窗截图要用）；`wins` 四扇全开时也走这条路。
        gw.setWindowsOpenEach(p?.windows ?: BooleanArray(CarPose.DOOR_COUNT))
    }

    /** 测试用：清掉注入值。 */
    fun clear() {
        pose = null
    }

    private fun on(intent: Intent, key: String): Boolean {
        if (!intent.hasExtra(key)) return false
        val s = runCatching { intent.getStringExtra(key) }.getOrNull()
        if (s != null) return s == "1" || s.equals("true", ignoreCase = true)
        return runCatching { intent.getIntExtra(key, 0) != 0 }.getOrDefault(false)
    }

    private val DOOR_KEYS = arrayOf("lf", "rf", "lb", "rb")

    /** 单窗开：`win_lf/win_rf/win_lb/win_rb`（1=开），缺省回退 `wins`。 */
    private val WIN_KEYS = arrayOf("win_lf", "win_rf", "win_lb", "win_rb")
}
