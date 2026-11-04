package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cardesk.launcher.core.DayNightSource
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * AdaptAPI 的昼夜通道（`Device.getDayNightMode()`）—— 桌面「跟随光感」的真来源。
 *
 * ## 为什么必须是这条通道
 *
 * 车机日间模式功能值（`0x20150100`）在真车上恒为 **AUTO（跟随系统）**
 * （诊断包实测：`读取属性 func=0x20150100 value=538247427`），
 * AUTO 时它没有明暗信息，只能回落到「系统 UI 夜间态」——
 * 而 `UiModeManager.nightMode` 是**设置值**不是当前明暗，在没人写它的车机上是个死值。
 *
 * 真正给出**当前明暗**的是 AdaptAPI 的昼夜通道：
 * - `getDayNight()`：`1` 白天 / `2` 夜间；处于「跟随」时取车机光照信号 `Twlibrists`
 *   （`OFF`→夜间、`ON`→白天），这才是「光感」。
 * - `registerDayNightModeCallBack()`：车机明暗一变就回调
 *   `onDayNightChanged(显示态)` / `onDayNightModeChange(模式)`。
 *
 * 官方桌面读明暗走的就是这条通道（`DayNightController.readSystemNightUi`）。
 *
 * ## 实现方式
 *
 * `com.ecarx.xui.adaptapi.*` 只存在于车机 `/system` 分区，普通 APK 里没有实现，
 * 所以全部用**反射 + 动态代理**（与 `AdaptapiGateway` 同一套路）：
 * - `Device.create(context)` → `getDayNightMode()` → `getDayNight()`
 * - `IDayNightMode$IDayNightChangeCallBack` 用 `Proxy` 实现。
 *
 * `DeviceImpl` 与车控通道（`Car.create`）共用单例 `VehicleSignalManager`，
 * 所以开这条通道**不需要**额外的 binder 连接。
 *
 * 缺任何一环都只意味着「读不到光感」，按下一个来源回落，不会让桌面崩溃。
 */
object AdaptapiDayNight {

    private const val CLS_DEVICE = "com.ecarx.xui.adaptapi.device.Device"
    private const val CLS_CALLBACK =
        "com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode\$IDayNightChangeCallBack"

    /** 挂载失败后的重试间隔（毫秒）。 */
    private const val RETRY_INTERVAL_MS = 5_000L

    /**
     * 光感变化的合并延时（毫秒）。
     *
     * 官方桌面是 400ms（`scheduleApplyFollowSensorCoalesced`）：车机在明暗边界上
     * 会连着推好几帧，不合并会看到配色来回闪。
     */
    private const val DEBOUNCE_MS = 400L

    private val main = Handler(Looper.getMainLooper())

    private var device: Any? = null
    private var mode: Any? = null
    private var getDayNightMethod: Method? = null
    private var registered = false
    private var lastAttemptAt = 0L

    /** 挂载用的 application context（读不到时按节流重试要用）。 */
    private var appContext: Context? = null

    /** 待下发的夜间态（合并窗口内只保留最后一个）。 */
    private var pendingNight: Boolean? = null

    private val applyPending = Runnable {
        val night = pendingNight
        pendingNight = null
        if (night != null) listener?.invoke(night)
    }

    /** 光感变化回调（**主线程**触发）。 */
    @Volatile
    var listener: ((Boolean) -> Unit)? = null

    /**
     * 幂等挂载昼夜通道。
     *
     * 车机服务没连上时 `getDayNightMode()` 会抛 `CarNotConnectedException`，
     * 这里按 [RETRY_INTERVAL_MS] 节流重试。
     *
     * **设备句柄只建一次**：回调注册失败时只重试注册，不重建设备 ——
     * `DayNightMode` 构造时会在共享的 `VehicleSignalManager` 上注册信号回调，
     * 反复重建会攒出一串重复回调。
     *
     * @return true = 昼夜通道可用
     */
    fun attach(context: Context, force: Boolean = false): Boolean {
        if (registered) return true
        appContext = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAttemptAt < RETRY_INTERVAL_MS) return false
        lastAttemptAt = now
        // ① Device 实例**只建一次**：它的构造里会注册蓝牙 PBAP 广播接收器
        //    （厂商 `DeviceImpl` → `BtExtensionImpl` → `PbapExtensionImpl`），
        //    重建会攒出一串重复接收器。
        if (device == null && !createDevice(context)) return false
        // ② 昼夜通道：车机服务没连上时拿不到（`CarNotConnectedException`），可重试；
        //    但成功后**必须缓存**——`DayNightMode` 构造时会在共享的信号管理器上
        //    注册回调且不可反注册，反复建会攒出一串重复回调。
        if (mode == null && !createMode()) return false
        // ③ 回调注册（失败了下次再试，不重建上面两步）
        val ok = registerCallback(mode ?: return false)
        if (ok) {
            Logx.i(
                LogTags.THEME,
                "接上车机昼夜通道",
                "device" to device?.javaClass?.name,
                "night" to nightUi(),
            )
        }
        return ok
    }

    /** 建 Device 实例。 */
    private fun createDevice(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val deviceCls = Class.forName(CLS_DEVICE)
        device = deviceCls.getMethod("create", Context::class.java).invoke(null, app)
        device != null
    }.getOrElse { t ->
        device = null
        Logx.w(
            LogTags.THEME,
            "接车机昼夜通道失败（建设备）",
            t,
            "reason" to "${t.javaClass.simpleName}: ${t.message}",
        )
        false
    }

    /** 取昼夜通道（`Device.getDayNightMode()`）。 */
    private fun createMode(): Boolean = runCatching {
        val dev = device ?: return@runCatching false
        val dn = dev.javaClass.getMethod("getDayNightMode").invoke(dev)
            ?: return@runCatching false
        mode = dn
        getDayNightMethod = runCatching { dn.javaClass.getMethod("getDayNight") }.getOrNull()
        true
    }.getOrElse { t ->
        mode = null
        getDayNightMethod = null
        Logx.w(
            LogTags.THEME,
            "接车机昼夜通道失败（取昼夜通道）",
            t,
            "reason" to "${t.javaClass.simpleName}: ${t.message}",
        )
        false
    }

    /**
     * 当前是不是夜间（**真·光感**）。
     *
     * @return true=夜间 / false=白天；null = 读不到（调用方回落到下一个来源）
     */
    fun nightUi(): Boolean? {
        // 还没挂上时顺手再试一次（内部按 5 秒节流）：车机服务连上后自然就接上了。
        if (!registered) appContext?.let { attach(it) }
        val dn = mode ?: return null
        val m = getDayNightMethod ?: return null
        val value = runCatching { m.invoke(dn) as? Int }.getOrNull() ?: return null
        return DayNightSource.nightFromDisplay(value)
    }

    /**
     * 断开监听（视图销毁时调用）。
     *
     * 通道本身留着（下次还能用），只是不再往回调里派发。
     */
    fun detach() {
        listener = null
        main.removeCallbacks(applyPending)
        pendingNight = null
    }

    /**
     * 清空通道状态（**仅供单测**）。
     *
     * 这是一个进程级单测，真机上不该调用。
     */
    fun resetForTest() {
        detach()
        device = null
        mode = null
        getDayNightMethod = null
        registered = false
        lastAttemptAt = 0L
        appContext = null
    }

    // ------------------------------------------------------------------ 回调注册

    private fun registerCallback(dn: Any): Boolean = runCatching {
        val cbCls = Class.forName(CLS_CALLBACK)
        val handler = InvocationHandler { _, method, args ->
            val value = args?.getOrNull(0) as? Int ?: -1
            when (method.name) {
                // 车机推的**实际明暗**（1 白天 / 2 夜间）
                "onDayNightChanged" -> main.post { handleDisplay(value) }
                // 车机推的**模式**（1 白天 / 2 夜间 / 3 跟随）
                "onDayNightModeChange" -> main.post { handleMode(value) }
            }
            null
        }
        val proxy = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls), handler)
        val ok = dn.javaClass.getMethod("registerDayNightModeCallBack", cbCls)
            .invoke(dn, proxy) as? Boolean ?: false
        registered = ok
        ok
    }.getOrElse { t ->
        Logx.w(
            LogTags.THEME,
            "注册车机昼夜回调失败",
            t,
            "reason" to "${t.javaClass.simpleName}: ${t.message}",
        )
        false
    }

    private fun handleDisplay(display: Int) {
        val night = DayNightSource.nightFromDisplay(display) ?: return
        Logx.i(LogTags.THEME, "车机昼夜回调·明暗", "display" to display, "night" to night)
        post(night)
    }

    private fun handleMode(modeValue: Int) {
        // 「跟随」(3) 时模式本身不定明暗 → 读一次实际明暗
        val night = DayNightSource.nightFromMode(modeValue) ?: nightUi() ?: return
        Logx.i(LogTags.THEME, "车机昼夜回调·模式", "mode" to modeValue, "night" to night)
        post(night)
    }

    /** 合并窗口内只下发最后一次（避免明暗边界上配色来回闪）。 */
    private fun post(night: Boolean) {
        pendingNight = night
        main.removeCallbacks(applyPending)
        main.postDelayed(applyPending, DEBOUNCE_MS)
    }
}
