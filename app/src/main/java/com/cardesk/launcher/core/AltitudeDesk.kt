package com.cardesk.launcher.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 海拔采集（定位通道）。
 *
 * ## 为什么要自己采
 *
 * 车机没有独立的「气压高度」通道，车辆数据里也没有海拔字段；唯一能拿到海拔的
 * 来源是系统定位的 [Location.getAltitude]。这个值更新慢、时有时无，还必须在
 * 权限被收走时安静降级 —— 所以单独抽一层，页面只读一个字符串，不关心定位细节。
 *
 * ## 数据链路
 *
 * 1. **GPS 通道**（2s / 0m）与**网络通道**（10s / 10m）同时订阅；
 *    两台 provider 都可能在一条回调里带 altitude，也可能都不带。
 * 2. **NMEA 兜底**：GPS 的第一次 fix 之前，[Location] 回调常常只带经纬度不带高度；
 *    而 NMEA 的 `GGA` 语句本来就含「海拔（米）」字段。两种通道同时听，
 *    谁先给出有效值算谁的。
 * 3. **上次值持久化**：车机熄火再上电、暗电流供电导致进程被杀都是常态。
 *    没有持久化的话每次重启都要在车库里干等 GPS，页面上一片 `--`。
 * 4. **看门狗**：长时间没有有效更新（隧道、地库）时不报错、不闪断，
 *    上一次的数值继续展示，但 [isFresh] 会变成 `false`，日志里留一条 stale。
 *
 * ## 线程模型
 *
 * 所有定位回调都显式绑到主线程的 `Looper`，[onChanged] 因此**一定在主线程**触发；
 * 调用方可以直接改 View，不用自己再 `post`。
 *
 * ## 失败策略
 *
 * 没有权限、没有 provider、系统定位服务不存在 —— 一律只记一条日志然后安静降级，
 * 任何异常都不允许冒泡（桌面 App 因为定位不可用闪退是不可接受的）。
 */
object AltitudeDesk {

    /** GPS 订阅周期：海拔变化慢，2s 足够，又不至于太费电。 */
    const val GPS_INTERVAL_MS = 2000L

    /** GPS 最小位移（0 = 不按位移过滤，静止时也让 altitude 刷新）。 */
    const val GPS_MIN_DISTANCE_M = 0f

    /** 网络定位订阅周期：网络定位更费电，10s 一档做兜底就够。 */
    const val NETWORK_INTERVAL_MS = 10000L

    /** 网络定位最小位移：10m 以内不值得回调一次。 */
    const val NETWORK_MIN_DISTANCE_M = 10f

    /** 多久以内的值算「新鲜」（毫秒）。 */
    const val FRESH_MS = 30 * 60 * 1000L

    /** 看门狗检查周期（毫秒）。 */
    const val WATCHDOG_TICK_MS = 15000L

    /** 超过这么久没有有效更新就记为「定位静默」（毫秒）。 */
    const val STALE_WATCHDOG_MS = 30000L

    /** 上次有效海拔的持久化键。 */
    const val KEY_LAST_M = "altitude_last_m"

    /** 上次有效海拔的时间戳（毫秒，取定位时刻而不是落盘时刻）持久化键。 */
    const val KEY_LAST_AT = "altitude_last_at"

    /**
     * 回调/落盘的最小变化量（米）。
     *
     * GPS 海拔在静止时也会来回跳 ±1~2m；不设阈值的话每 2s 就回调 + 写一次盘，
     * 页面数字一直抖、日志也会被刷爆。0.5m 的阈值既能过滤抖动，
     * 又不至于让正常的坡道变化（几十米）迟到。
     */
    private const val MIN_DELTA_M = 0.5

    /**
     * 即使数值没变，也至少这么久落盘一次时间戳（毫秒）。
     *
     * 新鲜期判定依赖「最后一次有效更新的时间」；如果只在数值变化时落盘，
     * 长时间停在同一高度时时间戳会停在几小时前，重启后误判成过期。
     */
    private const val MIN_PERSIST_INTERVAL_MS = 60_000L

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 最近一次有效海拔（可能是上次运行留下的旧值）。 */
    @Volatile
    private var lastM: Double? = null

    /** 最近一次有效海拔的**测量时刻**。 */
    @Volatile
    private var lastAtMs: Long = 0L

    /** 最近一次**收到**有效回调的时刻（看门狗用它判断定位是否还在工作）。 */
    @Volatile
    private var lastFixAtMs: Long = 0L

    /** 上次真正回调出去的值（节流用）。 */
    private var lastEmittedM: Double? = null

    /** 上次落盘的时刻（落盘节流用）。 */
    private var lastPersistAtMs: Long = 0L

    /** 「已超过新鲜期」是否已经记过日志/通知过（避免每 15s 重复）。 */
    private var staleLogged = false

    /** 「定位静默」是否已经记过（避免每 15s 重复）。 */
    private var silenceLogged = false

    private var prefs: Prefs? = null
    private var locationManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var networkListener: LocationListener? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var started = false

    /**
     * 海拔变化回调（主线程）。
     *
     * - 参数非 `null`：新的有效海拔（米）；
     * - 参数 `null`：值已超过 [FRESH_MS] 不再新鲜。此时 [text] **仍然返回上次的数值**
     *   （页面不会突然跳成 `--`），只是提醒宿主要不要再当实时数据用。
     */
    var onChanged: ((Double?) -> Unit)? = null

    /**
     * 开始采集。**幂等**：重复调用不会重复订阅。
     *
     * 会先把持久化的上次值读出来并回调一次（车机刚开机就能显示一个已知值），
     * 再去申请定位更新。没有定位权限时安静降级（只记一条日志）。
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val localPrefs = Prefs(app)
        prefs = localPrefs

        // 1) 先把上次的值捞出来并回调 —— 冷启动第一帧就有数可显示。
        val savedAt = runCatching { localPrefs.longValue(KEY_LAST_AT, 0L) }.getOrDefault(0L)
        val savedM = runCatching {
            localPrefs.stringValue(KEY_LAST_M, "").trim().toDoubleOrNull()
        }.getOrNull()
        // 内存里的值可能比落盘值更新（落盘有 60s 节流），只有落盘值更晚才覆盖。
        val canRestore = savedM != null && savedAt > 0L && savedAt > lastAtMs &&
            !savedM.isNaN() && !savedM.isInfinite()
        if (canRestore) {
            lastM = savedM
            lastAtMs = savedAt
            lastEmittedM = savedM
            val now = System.currentTimeMillis()
            val fresh = AltitudeFormat.isFresh(now, savedAt)
            staleLogged = !fresh
            Logx.i(
                LogTags.ALT,
                "恢复上次海拔",
                "m" to savedM,
                "ageMs" to (now - savedAt),
                "fresh" to fresh,
            )
        } else {
            Logx.d(LogTags.ALT, "没有可恢复的上次海拔")
        }
        // 重新 attach 时也要把「内存里的现值」重推给页面，否则新建页面没有数。
        lastM?.let { notifyMain(it) }

        // 2) 权限守卫：系统应用也照规矩 checkSelfPermission，缺权限不能崩。
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Logx.w(
                LogTags.ALT,
                "缺少定位权限，海拔采集安静降级",
                "fine" to fine,
                "coarse" to coarse,
            )
            return
        }

        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Logx.w(LogTags.ALT, "系统没有定位服务，海拔采集安静降级")
            return
        }
        locationManager = lm
        started = true

        // 3) 系统缓存里的上一次 fix 先兜一把：不额外耗电，且常常直接带 altitude。
        seedFromLastKnown(lm, fine)

        // 4) GPS：只有 FINE 权限才允许请求 GPS provider。
        if (fine) {
            val gps = newListener("gps")
            gpsListener = gps
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_INTERVAL_MS,
                    GPS_MIN_DISTANCE_M,
                    gps,
                    mainHandler.looper,
                )
            }.onFailure { Logx.w(LogTags.ALT, "订阅 GPS 定位失败", it) }

            // NMEA 兜底：GGA 里有海拔，GPS 首次 fix 前 Location 回调常常不带高度。
            runCatching {
                val nmea = OnNmeaMessageListener { message, _ -> ingestNmea(message) }
                nmeaListener = nmea
                if (!lm.addNmeaListener(nmea, mainHandler)) {
                    Logx.w(LogTags.ALT, "NMEA 监听注册失败（海拔仍走 Location 通道）")
                }
            }.onFailure { Logx.w(LogTags.ALT, "NMEA 监听注册异常", it) }
        }

        // 5) 网络定位：粗/精任一权限可用；没有 network provider 的机型会在下面安静失败。
        val network = newListener("network")
        networkListener = network
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                NETWORK_INTERVAL_MS,
                NETWORK_MIN_DISTANCE_M,
                network,
                mainHandler.looper,
            )
        }.onFailure { Logx.w(LogTags.ALT, "订阅网络定位失败", it) }

        // 6) 看门狗
        mainHandler.postDelayed(watchdog, WATCHDOG_TICK_MS)

        val providers = runCatching { lm.getProviders(true) }.getOrNull()
        Logx.i(
            LogTags.ALT,
            "海拔采集已启动",
            "fine" to fine,
            "coarse" to coarse,
            "providers" to (providers?.joinToString(",") ?: ""),
        )
    }

    /** 停止采集（幂等）。已持久化的上次值保留，下次 [start] 继续用。 */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacks(watchdog)
        val lm = locationManager
        gpsListener?.let { listener ->
            runCatching { lm?.removeUpdates(listener) }
                .onFailure { Logx.w(LogTags.ALT, "退订 GPS 定位失败", it) }
        }
        networkListener?.let { listener ->
            runCatching { lm?.removeUpdates(listener) }
                .onFailure { Logx.w(LogTags.ALT, "退订网络定位失败", it) }
        }
        nmeaListener?.let { listener ->
            runCatching { lm?.removeNmeaListener(listener) }
                .onFailure { Logx.w(LogTags.ALT, "注销 NMEA 监听失败", it) }
        }
        gpsListener = null
        networkListener = null
        nmeaListener = null
        locationManager = null
        Logx.i(LogTags.ALT, "海拔采集已停止", "lastM" to lastM, "fresh" to isFresh())
    }

    /**
     * 最近一次有效海拔（米）。
     *
     * **可能已经过期**：是否需要信任它由 [isFresh] 判断。返回旧值而不是 `null`，
     * 是为了让页面在隧道/地库里继续显示一个有意义的数值，而不是闪成 `--`。
     */
    fun currentM(): Double? = lastM

    /** 当前值是否还在新鲜期内（[FRESH_MS]）。 */
    fun isFresh(): Boolean {
        val m = lastM ?: return false
        if (m.isNaN() || m.isInfinite()) return false
        return AltitudeFormat.isFresh(System.currentTimeMillis(), lastAtMs)
    }

    /** 展示文案：没有值 → `--`，否则 `42 m`（取整）。 */
    fun text(): String = AltitudeFormat.text(currentM())

    // ------------------------------------------------------------------ 采集

    /** 一个只关心 `onLocationChanged` 的定位监听（其余回调是接口要求，空实现）。 */
    private fun newListener(source: String): LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = ingestLocation(location, source)

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    /** `Location` 通道：`hasAltitude()` 为真才取，否则这条回调对海拔没有信息量。 */
    private fun ingestLocation(location: Location, source: String) {
        if (!location.hasAltitude()) return
        val m = location.altitude
        if (m.isNaN() || m.isInfinite()) return
        val now = System.currentTimeMillis()
        // 用**测量时刻**而不是回调时刻：系统缓存的 fix 可能是几小时前的。
        val fixAt = location.time.takeIf { it in 1..now } ?: now
        accept(m, fixAt, source)
    }

    /**
     * NMEA 通道：只解析 `GGA` 语句的第 9 字段（海拔，米）。
     *
     * 不做校验和验证 —— 系统 `LocationManager` 已经过滤过一遍，这里只做字段提取。
     */
    private fun ingestNmea(message: String) {
        val m = AltitudeFormat.parseGgaAltitude(message) ?: return
        accept(m, System.currentTimeMillis(), "nmea")
    }

    /** 用系统缓存的上次定位兜一次（只在值还新鲜时采用）。 */
    private fun seedFromLastKnown(lm: LocationManager, fine: Boolean) {
        val providers = ArrayList<String>(2)
        if (fine) providers.add(LocationManager.GPS_PROVIDER)
        providers.add(LocationManager.NETWORK_PROVIDER)
        val now = System.currentTimeMillis()
        for (provider in providers) {
            val location = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (!location.hasAltitude()) continue
            val m = location.altitude
            if (m.isNaN() || m.isInfinite()) continue
            val fixAt = location.time.takeIf { it in 1..now } ?: now
            if (!AltitudeFormat.isFresh(now, fixAt)) {
                Logx.d(
                    LogTags.ALT,
                    "系统缓存的定位已过期，忽略",
                    "provider" to provider,
                    "ageMs" to (now - fixAt),
                )
                continue
            }
            accept(m, fixAt, "last-known")
            return
        }
    }

    /**
     * 采纳一条有效海拔：更新内存值、按阈值回调、按需落盘。
     *
     * @param fixAtMs 这条海拔的**测量时刻**（新鲜期按它算）
     */
    private fun accept(m: Double, fixAtMs: Long, source: String) {
        val now = System.currentTimeMillis()
        lastM = m
        lastAtMs = fixAtMs
        staleLogged = false
        silenceLogged = false
        // 系统缓存值不代表「定位通道现在能工作」，不刷新静默判据。
        if (source != "last-known") lastFixAtMs = now

        val previous = lastEmittedM
        val changed = previous == null || abs(m - previous) >= MIN_DELTA_M
        val persistDue = now - lastPersistAtMs >= MIN_PERSIST_INTERVAL_MS
        if (changed || persistDue) {
            persist(m, fixAtMs)
            lastPersistAtMs = now
        }
        if (changed) {
            lastEmittedM = m
            // 用 DEBUG：行驶中海拔每几百米就会变一次，INFO 会持续落盘。
            Logx.d(LogTags.ALT, "海拔更新", "m" to m, "source" to source)
            notifyMain(m)
        }
        // 抖动过滤只影响回调/日志：内存值与新鲜期时间戳仍然更新。
    }

    /** 落盘上次值和它的测量时刻（键名见 [KEY_LAST_M] / [KEY_LAST_AT]）。 */
    private fun persist(m: Double, atMs: Long) {
        val localPrefs = prefs ?: return
        runCatching {
            localPrefs.setStringValue(KEY_LAST_M, m.toString())
            localPrefs.setLongValue(KEY_LAST_AT, atMs)
        }.onFailure { Logx.w(LogTags.ALT, "海拔落盘失败", it) }
    }

    /** 在主线程回调；已经在主线程时直接调，避免多一跳时序。 */
    private fun notifyMain(value: Double?) {
        val callback = onChanged ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback(value)
        } else {
            mainHandler.post { onChanged?.invoke(value) }
        }
    }

    /**
     * 看门狗：每 [WATCHDOG_TICK_MS] 检查一次定位是否还在工作。
     *
     * - 超过 [STALE_WATCHDOG_MS] 没有有效回调：记一条「定位静默」（只记一次）；
     * - 值超过 [FRESH_MS]：记一条 stale，并回一次 `null` 告诉宿主「不再新鲜」；
     *   但 [text] 仍返回上次数值，页面不会闪成 `--`。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            val now = System.currentTimeMillis()
            val silence = if (lastFixAtMs <= 0L) Long.MAX_VALUE else now - lastFixAtMs
            if (silence > STALE_WATCHDOG_MS && !silenceLogged) {
                silenceLogged = true
                if (lastM == null) {
                    Logx.w(LogTags.ALT, "定位长时间没有有效更新，海拔暂不可用", "silenceMs" to silence)
                } else {
                    Logx.i(LogTags.ALT, "定位长时间没有更新，沿用上次海拔", "silenceMs" to silence)
                }
            }
            if (lastM != null && !AltitudeFormat.isFresh(now, lastAtMs) && !staleLogged) {
                staleLogged = true
                Logx.w(
                    LogTags.ALT,
                    "海拔已超过新鲜期，按旧值展示",
                    "ageMs" to (now - lastAtMs),
                    "freshMs" to FRESH_MS,
                )
                notifyMain(null)
            }
            mainHandler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }
}

/**
 * 海拔的**纯逻辑**（格式化 / NMEA 解析 / 新鲜期判定）。
 *
 * 单独抽出来是为了能在 JVM 单测里直接验证 —— 采集部分依赖 Android 的
 * `LocationManager`，没法在普通单测里跑；但「42.4 显示成 42 m」这类规则
 * 是页面文案的契约，必须锁住。
 */
internal object AltitudeFormat {

    /** `null` → `--`；其余按四舍五入取整并带单位，如 `42 m`。 */
    fun text(m: Double?): String {
        if (m == null || m.isNaN() || m.isInfinite()) return "--"
        return "${m.roundToInt()} m"
    }

    /**
     * 从 NMEA `GGA` 语句里取海拔（米）。
     *
     * 字段序（0 起）：`$` 之后 0 是语句头（如 `GPGGA`）、6 是定位质量、
     * 9 是海拔（米）。质量 0 表示无效定位，海拔字段为空也必须给 `null`，
     * 不能按 0m 处理（0m 是海平面的合法值，两者必须区分）。
     */
    fun parseGgaAltitude(nmea: String): Double? {
        val line = nmea.trim()
        if (!line.startsWith("$")) return null
        val parts = line.substring(1).split(',')
        if (parts.size <= 9) return null
        val head = parts[0]
        if (head.length != 5 || !head.endsWith("GGA")) return null
        if (parts[6].trim() == "0") return null
        val field = parts[9].trim()
        if (field.isEmpty()) return null
        val altitude = field.toDoubleOrNull() ?: return null
        if (altitude.isNaN() || altitude.isInfinite()) return null
        return altitude
    }

    /**
     * 时间戳 [lastAtMs] 在 [nowMs] 时是否还新鲜。
     *
     * 边界按「不超过 [AltitudeDesk.FRESH_MS] 都算新鲜」处理；未来时间戳
     * （时钟被回拨/篡改）按过期处理，避免把一个不可信的时间当成新鲜。
     */
    fun isFresh(nowMs: Long, lastAtMs: Long): Boolean {
        if (lastAtMs <= 0L) return false
        val age = nowMs - lastAtMs
        return age in 0..AltitudeDesk.FRESH_MS
    }
}
