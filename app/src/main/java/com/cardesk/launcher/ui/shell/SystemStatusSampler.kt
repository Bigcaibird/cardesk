package com.cardesk.launcher.ui.shell

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.cardesk.launcher.core.SystemStatusPolicy
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 状态栏连接状态**采样器**（第138轮，Android 侧）。
 *
 * 采样口径见 [SystemStatusPolicy]（纯逻辑那一份）。
 * 这里只负责**把真机状态读出来**，并按「**变了才回调**」往上送。
 *
 * ## 四条链路分别怎么读
 *
 * | 图标 | 数据源 | 说明 |
 * |---|---|---|
 * | WiFi | `ConnectivityManager` + `WifiManager` | 连没连 / 信号等级（`calculateSignalLevel(rssi, 5)` → 0..4） |
 * | 热点 | `WifiManager.isWifiApEnabled`（**反射**） | 隐藏 API；读不到就**不显示**（不猜） |
 * | 蓝牙 | `BluetoothAdapter` | 开没开 / 有没有连上（A2DP 或通话任一） |
 * | 蜂窝 | **车机 TBox**（`com.ecarx.xui.adaptapi.tbox.TBox`） | 车机自己的 4G 信号，见下 |
 *
 * ## 蜂窝信号为什么走 TBox 而不是 TelephonyManager
 *
 * `TelephonyManager.getDataNetworkType()` / `getSignalStrength()` 在 Android 9+
 * 需要 `READ_PHONE_STATE`（dangerous 权限，平台签名也拿不到，除非额外 `pm grant`）。
 *
 * 而这台车机的**车机 SDK 本身就带 TBox**：
 *
 * ```
 * com.ecarx.xui.adaptapi.tbox.TBox.create(Context)
 *   ├─ getSignalStrength() : android.telephony.SignalStrength   ← 车机 4G 信号
 *   └─ getNetworkType()    : String                            ← 网络类型文案
 * ```
 *
 * 那个 `SignalStrength` 是**车机给的**（TBOX / eSIM 侧），
 * 正好就是原车状态栏那几格信号 —— 这才是「信号」该显示的东西。
 *
 * ## 采样节奏
 *
 * 后台线程 **2 秒**一轮（与参考工程一致），**只在快照变了**时才回调 ——
 * 状态栏每秒重绘，但连接状态不会每秒变，没必要每次都重算。
 */
class SystemStatusSampler(private val context: Context) {

    /** 采样回调（主线程）。 */
    fun interface Listener {
        fun onStatus(snapshot: SystemStatusPolicy.Snapshot)
    }

    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    /** 主线程 Handler（回调要落在主线程 —— 状态栏是 View）。 */
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    /** 上一份快照的指纹（变了才回调、变了才记日志）。 */
    private var lastSignature: String? = null

    /**
     * 启动采样。
     *
     * @param listener 变化回调（**主线程**）
     */
    fun start(listener: Listener) {
        if (running) return
        running = true
        val t = HandlerThread("cardesk-system-status").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        worker = h
        h.post(poll(listener))
    }

    /** 停止采样。 */
    fun stop() {
        running = false
        worker?.removeCallbacksAndMessages(null)
        worker = null
        thread?.quitSafely()
        thread = null
        lastSignature = null
    }

    private fun poll(listener: Listener): Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val h = worker ?: return
            val snapshot = try {
                sample()
            } catch (t: Throwable) {
                Logx.w(LogTags.SYS, "系统状态采样失败", t)
                null
            }
            if (snapshot != null) {
                val sig = snapshot.signature()
                if (sig != lastSignature) {
                    lastSignature = sig
                    Logx.i(LogTags.SYS, "系统状态变化", "status" to sig)
                    // 回调到**主线程**（状态栏是 View，只能在主线程改）。
                    main.post {
                        if (running) listener.onStatus(snapshot)
                    }
                }
            }
            if (running) h.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------ 采样

    /**
     * 采一次样。
     *
     * 单项失败**不影响其它项** —— 每一项各自 try/catch，
     * 读不到就是「读不到」，不猜、也不让整次采样失败。
     */
    fun sample(): SystemStatusPolicy.Snapshot {
        val wifi = readWifi()
        val hotspot = readHotspot()
        val bt = readBluetooth()
        val mobile = readMobile()
        return SystemStatusPolicy.Snapshot(
            wifiKnown = wifi.known,
            wifiConnected = wifi.connected,
            wifiLevel = wifi.level,
            hotspotOn = hotspot,
            mobileKnown = mobile.known,
            mobileEnabled = mobile.enabled,
            mobileLevel = mobile.level,
            mobileLabel = mobile.label,
            bluetoothKnown = bt.known,
            bluetoothEnabled = bt.enabled,
            bluetoothConnected = bt.connected,
        )
    }

    private data class WifiRead(
        val known: Boolean,
        val connected: Boolean,
        val level: Int,
    )

    /** WiFi：连没连 + 信号等级。 */
    private fun readWifi(): WifiRead {
        val connected = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
        }.getOrDefault(false)
        if (!connected) return WifiRead(known = true, connected = false, level = SystemStatusPolicy.LEVEL_UNKNOWN)
        val rssi = runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.connectionInfo?.rssi ?: Int.MIN_VALUE
        }.getOrDefault(Int.MIN_VALUE)
        val level = if (rssi in -100..0) {
            runCatching { WifiManager.calculateSignalLevel(rssi, 5) }
                .getOrDefault(SystemStatusPolicy.LEVEL_UNKNOWN)
                .coerceIn(0, SystemStatusPolicy.LEVEL_MAX)
        } else {
            SystemStatusPolicy.LEVEL_UNKNOWN
        }
        return WifiRead(known = true, connected = true, level = level)
    }

    /**
     * 车机热点开没开。
     *
     * `WifiManager.isWifiApEnabled()` 是**隐藏 API**，用反射取。
     *
     * 读不到 → 当作**没开**（不显示热点图标）。
     * 宁可少显示一个图标，也不要显示一个「其实没开热点」的图标。
     */
    private fun readHotspot(): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        return runCatching {
            val m = wm.javaClass.getMethod("isWifiApEnabled")
            m.invoke(wm) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private data class BtRead(
        val known: Boolean,
        val enabled: Boolean,
        val connected: Boolean,
    )

    /**
     * 蓝牙：开没开 / 有没有连上。
     *
     * 「连上」的口径：**A2DP（音乐）或 通话任一**处于 CONNECTED —— 与参考工程一致。
     *
     * 读失败**要说出来**（限频一条）：否则「蓝牙图标一直不出现」，
     * 排查时完全看不出是「蓝牙没开」还是「根本没读到」。
     */
    private fun readBluetooth(): BtRead {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null) {
            warnOnce("bt-adapter", "蓝牙适配器取不到", "hint" to "系统没有蓝牙或服务没起来")
            return BtRead(known = false, enabled = false, connected = false)
        }
        val enabled = runCatching { adapter.isEnabled }.getOrElse {
            warnOnce("bt-enabled", "蓝牙开关读不到", "error" to it.javaClass.simpleName)
            false
        }
        val connected = runCatching {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrElse {
            warnOnce("bt-profile", "蓝牙连接态读不到", "error" to it.javaClass.simpleName)
            false
        }
        return BtRead(known = true, enabled = enabled, connected = connected)
    }

    /**
     * 限频告警（同一类最多 60 秒一条）。
     *
     * 采样每 2 秒跑一次，不限频就是每 2 秒刷一条日志。
     */
    private fun warnOnce(key: String, msg: String, extra: Pair<String, Any>) {
        val now = System.currentTimeMillis()
        val last = warnAt[key] ?: 0L
        if (now - last < WARN_INTERVAL_MS) return
        warnAt[key] = now
        Logx.w(LogTags.SYS, msg, extra)
    }

    private val warnAt = HashMap<String, Long>()

    private data class MobileRead(
        val known: Boolean,
        val enabled: Boolean,
        val level: Int,
        val label: String,
    )

    /**
     * 车机自己的 4G 信号（走 ecarx TBox）。
     *
     * 读不到 TBox → **回落到 `ConnectivityManager`**：
     * 默认网络带 `TRANSPORT_CELLULAR` 就说明「车机在蜂窝网络上」，
     * 这时蜂窝图标**该出现**，只是强度读不到（画弱色空图标）。
     *
     * 回落这一刀是为了对齐参考工程：那边 `mobileKnown` 是「TelephonyManager 拿得到」
     * 就为真，所以蜂窝图标几乎一直显示。我们不能因为 TBox 缺失就**整个图标都不画**
     * —— 那会让状态栏看着像「车机没网」。
     */
    private fun readMobile(): MobileRead {
        val tbox = tboxInstance()
        if (tbox != null) {
            val ss = runCatching {
                tbox.javaClass.getMethod("getSignalStrength").invoke(tbox)
            }.getOrNull()
            val level = if (ss != null) {
                runCatching { (ss as android.telephony.SignalStrength).level }
                    .getOrDefault(SystemStatusPolicy.LEVEL_UNKNOWN)
                    .let { if (it in 0..SystemStatusPolicy.LEVEL_MAX) it else SystemStatusPolicy.LEVEL_UNKNOWN }
            } else {
                SystemStatusPolicy.LEVEL_UNKNOWN
            }
            val label = runCatching {
                tbox.javaClass.getMethod("getNetworkType").invoke(tbox)?.toString().orEmpty()
            }.getOrDefault("")
            // 「移动数据开没开」车机侧没有直接开关 —— TBox 有信号就当作链路是通的。
            return MobileRead(
                known = true,
                enabled = level >= 0 || label.isNotEmpty(),
                level = level,
                label = label,
            )
        }
        // 回落：默认网络是不是蜂窝？
        val onCellular = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }.getOrDefault(false)
        if (!onCellular) {
            return MobileRead(
                known = false, enabled = false, level = SystemStatusPolicy.LEVEL_UNKNOWN, label = "",
            )
        }
        // 在蜂窝网络上，但强度读不到（TBox 没有 / 没 READ_PHONE_STATE）→ 弱色空图标。
        return MobileRead(
            known = true,
            enabled = true,
            level = SystemStatusPolicy.LEVEL_UNKNOWN,
            label = "",
        )
    }

    // ------------------------------------------------------------------ TBox

    /** TBox 实例（创建一次，缓存住 —— 每次反射创建太贵）。 */
    @Volatile
    private var tbox: Any? = null

    @Volatile
    private var tboxTried = false

    /**
     * 取车机 TBox 实例（`com.ecarx.xui.adaptapi.tbox.TBox.create(Context)`）。
     *
     * 拿不到就一直是 null（只试一次，别每轮都去 Class.forName）。
     */
    private fun tboxInstance(): Any? {
        tbox?.let { return it }
        if (tboxTried) return null
        tboxTried = true
        val inst = runCatching {
            val cls = Class.forName(CLS_TBOX)
            val create = cls.methods.firstOrNull { m ->
                m.name == "create" &&
                    m.parameterTypes.size == 1 &&
                    Context::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: return null
            create.invoke(null, context.applicationContext)
        }.getOrNull()
        tbox = inst
        if (inst == null) {
            Logx.d(LogTags.SYS, "车机 TBox 取不到，蜂窝信号图标不显示", "cls" to CLS_TBOX)
        }
        return inst
    }

    companion object {
        /** 采样间隔（毫秒）。与参考工程一致（桌面可见时 2 秒）。 */
        const val SAMPLE_INTERVAL_MS = 2000L

        /** 车机 TBox 类名。 */
        const val CLS_TBOX = "com.ecarx.xui.adaptapi.tbox.TBox"

        /** 同类告警的最小间隔（毫秒）—— 采样每 2 秒一轮，不限频就是刷屏。 */
        const val WARN_INTERVAL_MS = 60_000L
    }
}
