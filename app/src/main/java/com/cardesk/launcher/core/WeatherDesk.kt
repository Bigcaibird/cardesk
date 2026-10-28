package com.cardesk.launcher.core

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ==================================================================
// 持久化键名
// ==================================================================

/** 缓存城市（`WeatherDesk.KEY_CITY`）。 */
const val WEATHER_KEY_CITY = "weather_last_city"

/** 缓存天气描述（`WeatherDesk.KEY_SKY`）。 */
const val WEATHER_KEY_SKY = "weather_last_sky"

/** 缓存温度（`WeatherDesk.KEY_TEMP`）。 */
const val WEATHER_KEY_TEMP = "weather_last_temp"

/** 共享 prefs 文件名（与 是同一个 "cardesk"）。 */
const val WEATHER_PREFS_NAME = "cardesk"

// ==================================================================
// 纯函数（可在 app/src/test 下直接 JVM 单测，不碰任何 Android API）
// ==================================================================

/**
 * WMO 天气码 → 中文文案。
 *
 * 逐条`WeatherDesk.wmo`
 * `
 * 0 -> "晴"
 * 1..3 -> "多云"
 * 4..48 -> "雾"
 * 49..57 -> "毛毛雨"
 * 58..67 -> "雨"
 * 68..77 -> "雪"
 * 78..82 -> "阵雨"
 * 83..86 -> "阵雪"
 * 87..99 -> "雷阵雨"
 * 其它 -> null
 * `
 *
 * 是「上界链式判断」（`code <= 3` / `code <= 48` …），负数会落到 "多云"
 * 这里按 WMO 码表显式分段（码表从 0 起），负数一律返回 null。
 */
fun wmoLabel(code: Int): String? = when {
    code == 0 -> "晴"
    code in 1..3 -> "多云"
    code in 4..48 -> "雾"
    code in 49..57 -> "毛毛雨"
    code in 58..67 -> "雨"
    code in 68..77 -> "雪"
    code in 78..82 -> "阵雨"
    code in 83..86 -> "阵雪"
    code in 87..99 -> "雷阵雨"
    else -> null
}

/**
 * 空气质量档位文案。
 *
 * `
 * <= 50 -> "空气优"
 * <= 100 -> "空气良"
 * <= 150 -> "轻度污染"
 * <= 200 -> "中度污染"
 * 其它 -> "重度污染"
 * `
 */
fun airQualityLabel(aqi: Int): String = when {
    aqi <= 50 -> "空气优"
    aqi <= 100 -> "空气良"
    aqi <= 150 -> "轻度污染"
    aqi <= 200 -> "中度污染"
    else -> "重度污染"
}

/**
 * 天气文案 + 空气质量文案的拼接。
 *
 * 拼法是 `天气 • 空气质量`，用 **" • "**（空格 + U+2022 + 空格）分隔。
 * 空气质量取不到时只留天气；天气取不到但空气质量有值时只留空气质量。
 */
fun combineSky(sky: String?, airQuality: String?): String? = when {
    !sky.isNullOrEmpty() && !airQuality.isNullOrEmpty() -> "$sky • $airQuality"
    !sky.isNullOrEmpty() -> sky
    !airQuality.isNullOrEmpty() -> airQuality
    else -> null
}

/**
 * 弱城市判定（`WeatherDesk.isWeakCity`）。
 *
 * 规则：null / 空白 → 弱；不含汉字 → 弱。
 * 也就是说**只有中文地名才算「有效城市」**，英文/拼音地名一律视为弱城市，
 * 会在下一轮被更权威的反查结果替换。
 */
fun isWeakCity(city: String?): Boolean {
    if (city.isNullOrBlank()) return true
    return !hasHan(city)
}

/**
 * 清洗城市名（`WeatherDesk.cleanCity`）
 * 去掉首尾空白，空串视为 null。
 */
fun cleanCity(value: String?): String? {
    val trimmed = value?.trim() ?: return null
    return if (trimmed.isEmpty()) null else trimmed
}

/**
 * bigdatacloud 反查结果里挑城市字段。
 *
 * 字段顺序：**city → locality → principalSubdivision**
 * （逐条照抄）。
 *
 * 注意顺序很关键：bigdatacloud 的 `city` 才是「杭州市」这种市级名，
 * `locality` 往往是「拱墅区」这种区级名；把它排在 `city` 之后。
 */
fun pickBigDataCloudCity(map: Map<String, Any?>): String {
    for (key in BDC_CITY_KEYS) {
        val city = cleanCity(JsonLite.str(map, key))
        if (city != null) return city
    }
    return ""
}

/**
 * nominatim 反查结果里挑城市字段。
 *
 * 字段顺序：**city → town → county → state**
 * （逐条照抄）。
 */
fun pickNominatimCity(map: Map<String, Any?>): String {
    val address = map["address"] as? Map<String, Any?> ?: return ""
    for (key in NOMINATIM_CITY_KEYS) {
        val city = cleanCity(JsonLite.str(address, key))
        if (city != null) return city
    }
    return ""
}

/** bigdatacloud 候选字段（`pickBigDataCloudCity` 用）。 */
private val BDC_CITY_KEYS = listOf("city", "locality", "principalSubdivision")

/** nominatim 候选字段（`pickNominatimCity` 用）。 */
private val NOMINATIM_CITY_KEYS = listOf("city", "town", "county", "state")

/**
 * 解析 open-meteo 的 `current` 段，返回温度（四舍五入到整数）与天气文案。
 *
 * `WeatherDesk.queryNetwork`
 *  是小数，用 `Math.round` 四舍五入成整数；
 *  映射成中文文案（文案缺失时 sky 为 null）。
 */
fun parseCurrentWeather(json: String): WeatherNow? {
    val root = JsonLite.parseObject(json) ?: return null
    val current = root["current"] as? Map<String, Any?> ?: return null
    val raw = JsonLite.double(current, "temperature_2m", Double.NaN)
    if (raw.isNaN()) return null
    return WeatherNow(roundTemp(raw), wmoLabel(JsonLite.int(current, "weather_code", -1)))
}

/**
 * 解析 open-meteo 空气质量接口的。
 *
 * 是 `Math.round` 后取整；取不到返回 null。
 */
fun parseAirQuality(json: String): Int? {
    val root = JsonLite.parseObject(json) ?: return null
    val current = root["current"] as? Map<String, Any?> ?: return null
    val raw = JsonLite.double(current, "us_aqi", Double.NaN)
    if (raw.isNaN()) return null
    return Math.round(raw).toInt()
}

/** 解析 bigdatacloud 响应里的城市（网络层用）。 */
fun parseBigDataCloudCity(json: String): String =
    JsonLite.parseObject(json)?.let { pickBigDataCloudCity(it) } ?: ""

/** 解析 nominatim 响应里的城市（网络层用）。 */
fun parseNominatimCity(json: String): String =
    JsonLite.parseObject(json)?.let { pickNominatimCity(it) } ?: ""

/** 温度四舍五入（`Math.round`）。 */
fun roundTemp(raw: Double): Int = Math.round(raw).toInt()

/** 天气接口 URL。 */
fun forecastUrl(lat: Double, lon: Double): String =
    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,weather_code&timezone=auto"

/** 空气质量接口 URL。 */
fun airQualityUrl(lat: Double, lon: Double): String =
    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
        "&current=us_aqi"

/** bigdatacloud 反查 URL。 */
fun bigDataCloudUrl(lat: Double, lon: Double): String =
    "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon" +
        "&localityLanguage=zh"

/** nominatim 反查 URL。 */
fun nominatimUrl(lat: Double, lon: Double): String =
    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10" +
        "&accept-language=zh-CN,zh&lat=$lat&lon=$lon"


/** 一次天气结果：整数温度 + 天气文案（文案可能为 null）。 */
data class WeatherNow(val temp: Int, val sky: String?)

/**
 * 定位候选（筛选条件，抽成纯函数便于单测）。
 *
 * @param timeMillis 定位时间戳（取最新的那个）
 * @param ageNanos 距今的时长（`SystemClock.elapsedRealtimeNanos()` 差值）
 * @param accuracyMeters 水平精度；负数表示**没有精度信息**（里 为假时不过滤）
 */
data class LocationCandidate(
    val timeMillis: Long,
    val ageNanos: Long,
    val accuracyMeters: Float,
)

/** 位置最大可接受时长：600 秒（`600000000000L` 纳秒）。 */
const val WEATHER_MAX_LOCATION_AGE_NANOS = 600_000_000_000L

/** 位置最大可接受精度：5000 米（`5000.0f`）。 */
const val WEATHER_MAX_LOCATION_ACCURACY_M = 5000f

/**
 * 从各 provider 的候选里挑一个可用的位置。
 *
 * 筛选
 * - `ageNanos >= 0` 且 `ageNanos <= 600s`（位置要新鲜）；
 * - 没有精度信息、或精度 ≤ 5000m（位置要准）；
 * - 多个候选里取**时间戳最新**的。
 *
 * 是遍历 `LocationManager.getProviders(true)`（**所有启用的 provider**）
 * 车机上厂商可能用自定义 provider 名，所以不能只试 GPS/NETWORK/PASSIVE。
 */
fun pickFreshestLocation(candidates: List<LocationCandidate>): LocationCandidate? {
    var best: LocationCandidate? = null
    for (c in candidates) {
        if (c.ageNanos < 0 || c.ageNanos > WEATHER_MAX_LOCATION_AGE_NANOS) continue
        if (c.accuracyMeters >= 0f && c.accuracyMeters > WEATHER_MAX_LOCATION_ACCURACY_M) continue
        if (best == null || c.timeMillis > best.timeMillis) best = c
    }
    return best
}

/**
 * 降级取位置：不看时效与精度，只取**最新的**那个。
 *
 * 筛选（600s / 5000m）在车机上可能太严 —— 车机的 GPS 常常很久不更新
 * 而导航 App 也可能把位置缓存得更久。这时如果严格按过滤就会**完全拿不到位置**
 * 天气永远显示「获取中」。所以先按过滤，滤空时退而求其次取任意最新位置
 * 并在日志里明确标成「降级」。
 */
fun pickAnyLocation(candidates: List<LocationCandidate>): LocationCandidate? =
    candidates.maxByOrNull { it.timeMillis }

/** 是否含汉字（`WeatherDesk.hasCjk`）。 */
private fun hasHan(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isHanCodePoint(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

/** 汉字码点判断（覆盖 CJK 统一表意文字及其扩展/兼容区）。 */
private fun isHanCodePoint(cp: Int): Boolean = when {
    cp in 0x4E00..0x9FFF -> true // CJK 统一表意文字
    cp in 0x3400..0x4DBF -> true // 扩展 A
    cp in 0xF900..0xFAFF -> true // 兼容表意文字
    cp in 0x20000..0x2A6DF -> true // 扩展 B
    cp in 0x2A700..0x2EBEF -> true // 扩展 C~F
    cp in 0x2F800..0x2FA1F -> true // 兼容补充
    cp in 0x30000..0x3134F -> true // 扩展 G
    else -> false
}

// ==================================================================
// 天气子系统
// ==================================================================

/**
 * 天气子系统（`WeatherDesk`）。
 *
 * ## 数据源优先级
 *
 * `
 * start()
 *  ├─ restoreCached() 回放缓存（天空 + 温度，城市留空）
 *  └─ requestFresh()
 *       ├─ 没有位置 → 投递 ("", 缓存天空, 缓存温度)
 *       ├─ postDelayed(4s) ─────► 兜底：起网络回落
 *       └─ Thread("weather-car")
 *            ├─ queryCarService() 车机 SDK（有数据就直接用）
 *            └─ 失败 → startNetworkFallback()
 * `
 *
 * ## 行为对照
 * - **车机 SDK 优先**：反射调用
 *   `com.ecarx.sdk.weather.WeatherAPI`，只在车机上存在；
 *   我们用 `core/CarWeatherApi.kt` 封装。
 * - 网络回落：open-meteo 天气 + 空气质量（`queryAirQuality`）+ 三级城市反查
 *
 * - 定位：`LocationManager.getProviders(true)` 遍历**所有**启用的 provider，
 *   取**最新且新鲜（≤600s）、准确（≤5000m）**的位置
 *
 * - 缓存：`weather_last_city` / `weather_last_sky` / `weather_last_temp`
 *   与 `core/Prefs.kt` 共用 "cardesk" prefs。
 * - 代次：每次 代次 +1，只有最新代次的结果才允许投递
 *   （generation / deliveredGen）。
 *
 * ## 与差异（有意为之）
 * -用 MMKV 存缓存，我们用 SharedPreferences（项目不引入第三方库）。
 * - `deliver` 的陈旧判定发生在后台线程；我们在投递到主线程前再校验一次代次。
 */
class WeatherDesk(context: Context, listener: Listener) {

    /** 天气回调（`WeatherDesk.Listener`）。 */
    interface Listener {
        fun onWeather(city: String, sky: String, temp: Int?)
    }

    private val app: Context = context.applicationContext
    private val listener: Listener = listener
    private val sp = app.getSharedPreferences(WEATHER_PREFS_NAME, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /** 代次（`WeatherDesk.generation`）。 */
    private val generation = AtomicInteger(0)

    /** 已投递过的代次（`WeatherDesk.deliveredGen`）。 */
    private val deliveredGen = AtomicInteger(-1)

    /**
     * 网络回落是否已启动（`WeatherDesk.fallbackStarted`）。
     *
     * 用 `compareAndSet` 保证**一轮刷新里最多走一次网络**。
     */
    private val fallbackStarted = AtomicBoolean(false)

    /**
     * 主动要来的定位（见 [requestOneShotFix]）。
     *
     * 只读 `getLastKnownLocation`，车机刚开机时 GPS 还没有 fix → 拿不到位置 → 天气永远空。
     * 这里主动订阅一次定位更新，拿到 fix 后重新走一轮取数。
     */
    @Volatile
    private var pendingFix: Location? = null

    /**
     * **城市兜底坐标**（纬度 → 经度）。
     *
     * 真机上系统定位可能长期没有缓存位置（provider 只有 `passive,gps` 且都没 fix），
     * 这时用高德的城市名换算经纬度顶上 —— 见 [requestByCityFallback]。
     */
    @Volatile
    private var cityFallbackLatLon: Pair<Double, Double>? = null

    /** 城市兜底用的城市名（[cityFallbackLatLon] 对应的城市）。 */
    @Volatile
    private var cityFallbackName: String? = null

    /**
     * 高德位置缓存的本地副本（时间戳 → 坐标）。
     *
     *  在取数链路上会被调用好几次（车机 SDK / 网络 / 投递），
     * 每次都去读高德的 sqlite 太重 —— 这里缓存 10 秒。
     */
    @Volatile
    private var amapPositionCache: Pair<Long, Pair<Double, Double>>? = null

    /** 正在等待的单次定位监听（避免重复订阅）。 */
    private var fixListener: LocationListener? = null

    /**
     * 4 秒兜底定时器（`WeatherDesk.networkFallback` +）。
     *
     * 车机 SDK 存在但一直没回调时，靠它把网络回落拉起来。
     */
    private val networkFallback = Runnable { startNetworkFallback() }

    init {
        // 区域信息落盘要用到上下文（见 `AmapCitySource.recordArea`）。
        AmapCitySource.bindContext(app)
    }

    /** 对外入口：先回放缓存，再走刷新。 */
    fun start() {
        restoreCached()
        requestFresh()
    }

    /** 停止：取消待投递的回调，并让在途请求结果作废。 */
    fun stop() {
        main.removeCallbacks(networkFallback)
        stopFixRequest()
        generation.incrementAndGet()
    }

    /**
     * 请求一次最新天气。
     *
     * 顺序
     * 1. 代次 +1；
     * 2. 没有位置 → 清缓存城市并投递（**城市 ""、天空用缓存、温度用缓存**），
     *    但**不 return**，车机 SDK 仍然要试；
     * 3. 重置网络回落闸门、撤掉旧的 4s 定时器、重挂 4s 定时器；
     * 4. 后台线程先试车机 SDK，失败再走网络。
     */
    fun requestFresh() {
        val gen = generation.incrementAndGet()
        val loc = lastLocation()
        if (loc == null) {
            // 没有位置：清空缓存城市，回调城市 ""、温度用缓存、天空用缓存
            //
            sp.edit().putString(WEATHER_KEY_CITY, "").apply()
            val sky = sp.getString(WEATHER_KEY_SKY, "") ?: ""
            post { listener.onWeather("", sky, readCachedTemp()) }
            // 排障关键行：把「定位为什么拿不到」一次说清 ——
            // permission grant / AppOps mode / 全局位置开关 / provider 启用情况 / uid。
            Logx.i(
                LogTags.WEATHER, "天气刷新：无有效定位，跳过取数",
                "gen" to gen,
                "state" to Permissions.describeLocationState(app),
            )
            // 到此为止就结束了（车机刚开机 GPS 没 fix 时，同样拿不到）。
            // 这里补一步：主动订阅一次定位更新，拿到 fix 后立刻重跑一轮。
            requestOneShotFix()
            // 再补一步：**城市兜底**（读高德的城市 → 地理编码 → 拿坐标）。
            // 真机上系统定位可能长期拿不到，而高德一定知道自己在哪个城市。
            requestByCityFallback(gen)
        }
        fallbackStarted.set(false)
        main.removeCallbacks(networkFallback)
        main.postDelayed(networkFallback, CAR_TIMEOUT_MS)
        // 网络请求跑在后台线程（`new Thread(..., "weather-car")`）。
        Thread({ if (!queryCarService()) startNetworkFallback() }, "weather-car").start()
    }

    /**
     * 主动订阅一次定位更新（**没有的一步**）。
     *
     * ## 为什么需要
     *
     * 只读 `LocationManager.getLastKnownLocation()`。车机刚开机（或长时间没动）时
     * GPS 还没有 fix → last known location 是 null →也拿不到天气。
     * 而且系统要等**有人订阅过定位**才会给 app 喂位置数据。
     *
     * 这里主动订阅一次（拿到 fix 就立刻退订），拿到位置后重跑一轮取数。
     *
     * ## 安全边界
     * - 只订阅**一次**，拿到 fix 立刻 `removeUpdates`；
     * - 没有可用 provider 时直接放弃；
     * - 已经有订阅在跑时不重复订阅；
     * - 时取消订阅，避免回调打到已停止的界面。
     */
    private fun requestOneShotFix() {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (fixListener != null) return
        val enabled = lm.getProviders(true)
        // **订阅所有可用的 provider**，不是只挑一个。
        //
        // 真机（领克 dcy11 / Android 9）实测：provider 只有 `passive,gps`，
        // 没有 network。我们只订阅 gps —— 而 passive 才是「车机上别的 App
        // （高德 / 腾讯车联）已经拿到位置后被动分发」的那一路，
        // 地下车库 / 室内 gps 永远等不到 fix，passive 却可能有。
        val providers = if (enabled.isEmpty()) lm.getProviders(false) else enabled
        if (providers.isEmpty()) {
            Logx.i(LogTags.WEATHER, "无法请求定位：没有可用的 provider", "all" to lm.getProviders(false).joinToString(","))
            return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Logx.i(
                    LogTags.WEATHER, "收到定位更新",
                    "lat" to location.latitude, "lon" to location.longitude,
                    "provider" to location.provider,
                    "accuracy" to (if (location.hasAccuracy()) location.accuracy else -1f),
                )
                pendingFix = location
                stopFixRequest()
                // 拿到位置后重跑一轮（走完整的车机 SDK → 网络回落链路）
                requestFresh()
            }

            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        val subscribed = ArrayList<String>()
        for (provider in providers) {
            val ok = runCatching {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, main.looper)
            }.isSuccess
            if (ok) subscribed.add(provider) else {
                Logx.i(LogTags.WEATHER, "订阅定位失败", "provider" to provider)
            }
        }
        if (subscribed.isEmpty()) {
            Logx.i(LogTags.WEATHER, "定位订阅全部失败", "providers" to providers.joinToString(","))
            return
        }
        fixListener = listener
        Logx.i(
            LogTags.WEATHER, "已订阅定位更新（等待 fix）",
            "providers" to subscribed.joinToString(","),
            "all" to providers.joinToString(","),
        )
        // 兜底：30 秒还没等到 fix 就退订，等下一轮 30 分钟刷新再说
        main.postDelayed({ stopFixRequest() }, FIX_TIMEOUT_MS)
    }

    /** 退订一次性的定位监听。 */
    private fun stopFixRequest() {
        val listener = fixListener ?: return
        fixListener = null
        runCatching {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.removeUpdates(listener)
        }
    }

    // ------------------------------------------------------------------ 城市兜底

    /**
     * **城市兜底**：系统定位拿不到时，用高德的城市名换算经纬度再查天气。
     *
     * ## 为什么需要
     *
     * 真机（领克 dcy11 / Android 9）实测：定位 provider 只有 `passive,gps`，
     * 两个都没有缓存位置 —— 天气链路第一步就断了。
     * 而**高德导航一定知道自己在哪个城市**（高德车机版自己就有三级城市兜底，
     * 见 [AmapCitySource] 的说明。
     *
     * 流程：读高德城市（广播缓存 → 高德的城市文件）→ open-meteo 地理编码换经纬度
     * 走和「有系统定位」完全一样的取数链路（车机 SDK → 网络回落）。
     *
     * @param gen 本轮代次（过期就不做）。
     */
    private fun requestByCityFallback(gen: Int) {
        Thread({
            try {
                var city = amapCity()
                if (city.isNullOrBlank()) {
                    // 还没有城市 → **主动向高德索要区域信息**（第三十一轮新增）。
                    //
                    // 高德向车机（ecarx.naviservice）要区域信息，车机回包后高德把城市
                    // 全局广播出来（KEY_TYPE=10030）→ AmapCitySource.broadcastCity。
                    // 这是真机上「系统定位完全没有」时唯一可靠的坐标来源入口。
                    //
                    // 车机上高德可能刚被拉起来、车机回包可能慢 —— 所以最多试两轮。
                    for (attempt in 1..AREA_INFO_ATTEMPTS) {
                        Logx.i(
                            LogTags.WEATHER,
                            "城市兜底：还没有城市，向高德索要区域信息",
                            "attempt" to attempt,
                        )
                        AmapCitySource.requestAreaInfo(app)
                        val got = awaitCityFromAmap(AREA_INFO_WAIT_MS)
                        if (!got.isNullOrBlank()) {
                            city = got
                            Logx.i(
                                LogTags.WEATHER,
                                "城市兜底：高德区域信息已到手",
                                "city" to got,
                                "attempt" to attempt,
                            )
                            break
                        }
                    }
                    if (city.isNullOrBlank()) {
                        Logx.i(
                            LogTags.WEATHER,
                            "城市兜底：拿不到城市名",
                            "attempts" to AREA_INFO_ATTEMPTS,
                            "waitMs" to AREA_INFO_WAIT_MS,
                            "state" to Permissions.describeLocationState(app),
                        )
                        return@Thread
                    }
                }
                val geo = geocodeCity(city)
                if (geo == null) {
                    // 地理编码失败（车机上公网可能不通）→ **仍然试车机 SDK 的「按城市」入口**。
                    //
                    // 车机天气 SDK 走车联网，只要给出城市名就能查 —— 不依赖公网。
                    Logx.i(LogTags.WEATHER, "城市兜底：地理编码失败，改走车机 SDK 按城市", "city" to city)
                    cityFallbackName = city
                    if (!queryCarService()) {
                        Thread({ queryNetwork() }, "weather-network-city").start()
                    }
                    return@Thread
                }
                if (gen != generation.get()) return@Thread
                cityFallbackLatLon = geo
                cityFallbackName = city
                Logx.i(
                    LogTags.WEATHER,
                    "城市兜底坐标就绪",
                    "city" to city,
                    "lat" to geo.first,
                    "lon" to geo.second,
                )
                // 用这个坐标重跑一轮取数（车机 SDK 优先，失败再走网络）。
                if (!queryCarService()) {
                    Thread({ queryNetwork() }, "weather-network-city").start()
                }
            } catch (t: Throwable) {
                Logx.w(LogTags.WEATHER, "城市兜底异常", t)
            }
        }, "weather-city").start()
    }

    /**
     * **已知城市名**（高德区域广播 / 持久化 / 高德城市文件里拿到的）。
     *
     * 用途：城市反查（bigdatacloud / nominatim）失败时，
     * 用这个已知城市顶上 —— 免得天气出来了却显示不出城市名。
     */
    @Volatile
    private var knownCity: String? = null

    /**
     * 拿当前城市名。
     *
     * 顺序：高德区域广播里缓存的城市（实时）→ **上次持久化的城市**
     * 高德写的城市文件（持久，但真机上常常读不到）。
     */
    private fun amapCity(): String? {
        AmapCitySource.broadcastCity?.let {
            Logx.i(LogTags.WEATHER, "城市来源：高德区域广播", "city" to it)
            knownCity = it
            return it
        }
        AmapCitySource.persistedCity(app)?.let {
            Logx.i(LogTags.WEATHER, "城市来源：上次持久化的城市", "city" to it)
            knownCity = it
            return it
        }
        val fromFile = AmapCitySource.readCityFromFile(app)
        if (!fromFile.isNullOrBlank()) {
            Logx.i(LogTags.WEATHER, "城市来源：高德城市文件", "city" to fromFile)
            knownCity = fromFile
            return fromFile
        }
        return null
    }

    /**
     * 已知城市名（安静版，不打日志、不触发 IO）。
     *
     * 只用已经拿到的结果；没有就返回 null（不查文件、不读广播）。
     */
    private fun knownCityOrNull(): String? {
        knownCity?.let { return it }
        AmapCitySource.broadcastCity?.let { knownCity = it; return it }
        val persisted = AmapCitySource.persistedCity(app)
        if (!persisted.isNullOrBlank()) {
            knownCity = persisted
            return persisted
        }
        return null
    }

    /**
     **向高德索要区域信息，并等它回话（最多 [AREA_INFO_WAIT_MS]）。
     *
     * 高德收到 的广播后会去向车机要区域信息，
     * 车机回包后高德把城市**全局广播**出来——
     * 由 `core/nav/AmapDesktopGuide` 接住并写进 [AmapCitySource]。
     *
     * @return 等到的城市名；超时返回 null
     */
    private fun awaitCityFromAmap(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            AmapCitySource.broadcastCity?.let { return it }
            try {
                Thread.sleep(300)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return AmapCitySource.broadcastCity
    }

    /** 城市名 → 经纬度（open-meteo 地理编码）。 */
    private fun geocodeCity(city: String): Pair<Double, Double>? = try {
        val json = http(WeatherCity.cityGeocodeUrl(city))
        val geo = WeatherCity.parseCityGeocode(json)
        if (geo == null) Logx.i(LogTags.WEATHER, "城市地理编码无结果", "city" to city)
        geo
    } catch (t: Throwable) {
        Logx.w(LogTags.WEATHER, "城市地理编码失败", t, "city" to city)
        null
    }

    // ------------------------------------------------------------------ 缓存

    /**
     * 回放缓存：把缓存的天空/温度立刻回调一次。
     *
     * 缓存回放**城市传空串**（的构造里 city 固定是 ""）
     * 这里照抄 —— 城市要靠反查拿到，缓存里的城市可能是上次的旧值。
     */
    private fun restoreCached() {
        if (!sp.contains(WEATHER_KEY_TEMP)) return
        val sky = sp.getString(WEATHER_KEY_SKY, "") ?: ""
        val temp = readCachedTemp() ?: return
        Logx.i(LogTags.WEATHER, "回放天气缓存", "sky" to sky, "temp" to temp)
        post { listener.onWeather("", sky, temp) }
    }

    /** 读取缓存温度；没有缓存（或类型不对）时返回 null（温度就「跳过」）。 */
    private fun readCachedTemp(): Int? {
        if (!sp.contains(WEATHER_KEY_TEMP)) return null
        return try {
            sp.getInt(WEATHER_KEY_TEMP, 0)
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ 投递

    /**
     * 投递一次天气结果。
     *
     * 规则：
     * - 温度为 null 直接丢弃。
     * - 代次不是最新 → 丢弃（陈旧结果不许覆盖新请求）。
     * - 同一代次只投递一次；但若「新城市不是弱城市、旧缓存城市是弱城市」，
     *   则允许用新城市升级缓存并再投递一次（city-upgrade）。
     */
    private fun deliver(gen: Int, city: String?, sky: String?, temp: Int?, source: String) {
        if (temp == null) return
        if (gen != generation.get()) return // 陈旧代次：丢弃

        // 没有位置时城市一律当空。
        //
        // 判定是 `lastLocation() != null`；这里再加一条
        // **车机天气 SDK 自己返回了有效城市名**（它带 GNSS，比我们更清楚位置）时，
        // 也认为位置是有的 —— 否则会把 SDK 给的城市白白丢掉。
        val hasPosition = lastLocation() != null || !isWeakCity(city.orEmpty())
        val effectiveCity = if (hasPosition) city.orEmpty() else ""
        val cachedCity = sp.getString(WEATHER_KEY_CITY, "") ?: ""

        val newGen = deliveredGen.get() != gen
        val cityUpgrade = !newGen && !isWeakCity(effectiveCity) && isWeakCity(cachedCity)
        if (!newGen && !cityUpgrade) return

        if (newGen) deliveredGen.set(gen)

        val editor = sp.edit()
        if (!isWeakCity(effectiveCity) || newGen) {
            editor.putString(WEATHER_KEY_CITY, effectiveCity)
        }
        if (sky != null) {
            editor.putString(WEATHER_KEY_SKY, sky)
        } else if (newGen) {
            editor.putString(WEATHER_KEY_SKY, "")
        }
        editor.putInt(WEATHER_KEY_TEMP, temp)
        editor.apply()

        val outCity = sp.getString(WEATHER_KEY_CITY, effectiveCity) ?: effectiveCity
        val outSky = sp.getString(WEATHER_KEY_SKY, sky ?: "") ?: ""
        Logx.i(
            LogTags.WEATHER,
            "天气已投递",
            "source" to source,
            "city" to outCity,
            "sky" to outSky,
            "temp" to temp,
            "upgrade" to cityUpgrade,
        )
        post {
            // 投递前再校验代次：期间若又发了新请求，旧结果就作废。
            if (gen != generation.get()) return@post
            listener.onWeather(outCity, outSky, temp)
        }
    }

    // ------------------------------------------------------------------ 车机 SDK

    /**
     * 问车机天气 SDK。
     *
     * 用反射：`com.ecarx.sdk.weather.WeatherAPI` → `createWeatherAPI(Context)`
     * 实例的 `getWeatherByLocation(经度, 纬度, 回调)`。回调是**接口**，用动态代理接管。
     *
     * @return true = 请求已发出（不代表一定有数据；靠 4 秒定时器兜底）
     */
    private fun queryCarService(): Boolean {
        // 车机天气 SDK 只在车机 /system 上有；普通手机/模拟器上没有是**正常情况**，
        // 不打堆栈（否则每 30 分钟往落盘日志里塞一份 ClassNotFoundException）。
        if (!CarWeatherApi.isAvailable()) {
            Logx.i(LogTags.WEATHER, "车机天气不可用：设备上没有车机天气 SDK")
            return false
        }
        val loc = lastLocation()
        // 系统定位优先；没有就用**城市兜底坐标**（见 requestByCityFallback）。
        val fallback = cityFallbackLatLon
        val lat = loc?.latitude ?: fallback?.first
        val lon = loc?.longitude ?: fallback?.second
        return try {
            val apiClass = Class.forName(CarWeatherApi.WEATHER_API_CLASS)
            // 排障关键行：**先**列出这台车 SDK 的全部 public 方法，再去创建实例。
            //
            // `Class.getMethods()` 本身不受隐藏 API 限制（限制只发生在 invoke 上），
            // 所以即使 `createWeatherAPI` 调不通，这个清单也一定拿得到 ——
            // 诊断包里就能直接看到「这台车的天气 SDK 到底有哪些入口」。
            Logx.i(
                LogTags.WEATHER, "车机天气 SDK 方法清单",
                "class" to CarWeatherApi.WEATHER_API_CLASS,
                "methods" to CarWeatherApi.describeMethods(apiClass),
            )
            val create = CarWeatherApi.findCreateMethod(apiClass)
            if (create == null) {
                Logx.i(LogTags.WEATHER, "车机天气不可用：找不到 createWeatherAPI")
                return false
            }
            val instance = create.invoke(null, app)
            if (instance == null) {
                Logx.i(LogTags.WEATHER, "车机天气不可用：createWeatherAPI 返回 null")
                return false
            }
            if (lat == null || lon == null) {
                // 没有坐标 → 先试 SDK 的**按城市入口**（我们补充）。
                //
                // 车机 SDK 多半还有「按城市查天气」或「取当前城市天气」这种接口；
                // 真机上有没有，看同一轮日志里的「车机天气 SDK 方法清单」。
                val cityName = cityFallbackName ?: amapCity()
                val cityMethod = CarWeatherApi.findCityQueryMethod(instance)
                if (cityMethod != null && !cityName.isNullOrBlank()) {
                    val cityArgs = CarWeatherApi.buildCityArgs(
                        cityMethod.parameterTypes,
                        cityName,
                    ) { iface ->
                        CarWeatherApi.callbackProxy(iface) { callbackArgs ->
                            onCarWeatherCallback(callbackArgs)
                        }
                    }
                    cityMethod.invoke(instance, *cityArgs)
                    Logx.i(
                        LogTags.WEATHER,
                        "车机天气请求已发出（按城市）",
                        "method" to cityMethod.name,
                        "city" to cityName,
                    )
                    return true
                }
                // 没有城市 → 试 SDK 的**无位置入口**（我们补充，见
                // `CarWeatherApi.findNoLocationQueryMethod` 的说明。
                val noLoc = CarWeatherApi.findNoLocationQueryMethod(instance)
                if (noLoc == null) {
                    Logx.i(LogTags.WEATHER, "车机天气跳过：没有位置，且 SDK 没有无位置入口")
                    return false
                }
                val noLocArgs = CarWeatherApi.buildArgs(
                    noLoc.parameterTypes,
                    0.0,
                    0.0,
                ) { iface ->
                    CarWeatherApi.callbackProxy(iface) { callbackArgs ->
                        onCarWeatherCallback(callbackArgs)
                    }
                }
                noLoc.invoke(instance, *noLocArgs)
                Logx.i(LogTags.WEATHER, "车机天气请求已发出（无位置入口）", "method" to noLoc.name)
                return true
            }
            val query = CarWeatherApi.findQueryMethod(instance)
            if (query == null) {
                Logx.i(LogTags.WEATHER, "车机天气不可用：找不到 getWeatherByLocation")
                return false
            }
            val args = CarWeatherApi.buildArgs(
                query.parameterTypes,
                lon,
                lat,
            ) { iface ->
                CarWeatherApi.callbackProxy(iface) { callbackArgs ->
                    onCarWeatherCallback(callbackArgs)
                }
            }
            query.invoke(instance, *args)
            Logx.i(
                LogTags.WEATHER,
                "车机天气请求已发出",
                "lat" to lat,
                "lon" to lon,
                "args" to query.parameterTypes.size,
                "source" to if (loc != null) "device" else "city",
            )
            true
        } catch (t: Throwable) {
            Logx.w(LogTags.WEATHER, "车机天气不可用", t)
            false
        }
    }

    /**
     * 车机 SDK 的回调。
     *
     * 对回调里的**每个非 null 参数**都 harvest 一次
     * 天气文案与温度**都取不到**就放弃这次回调。
     */
    private fun onCarWeatherCallback(args: Array<out Any?>?) {
        val raws = CarWeatherApi.harvestAll(args)
        if (raws.isEmpty()) {
            Logx.d(LogTags.WEATHER, "车机天气回调无有效数据")
            return
        }
        val gen = generation.get()
        for (raw in raws) {
            var city = raw.city
            // 城市是弱城市（空/非中文）且有位置 → 反查补一个中文城市
            //
            if (isWeakCity(city)) {
                val loc = lastLocation()
                if (loc != null) {
                    val resolved = reverseCityWithRetry(loc.latitude, loc.longitude)
                    if (!isWeakCity(resolved) || city == null) city = resolved
                }
                // 反查也拿不到 → 用已知城市（高德给的）顶上
                if (isWeakCity(city)) {
                    knownCityOrNull()?.let { city = it }
                }
            }
            deliver(gen, city, raw.sky, raw.temp, "car")
        }
    }

    // ------------------------------------------------------------------ 网络回落

    /**
     * 启动网络回落。
     *
     * 一轮刷新里**最多起一次**网络线程；且只有「本代还没投递过」
     * 或「缓存城市是弱城市」时才真的发请求。
     */
    private fun startNetworkFallback() {
        if (!fallbackStarted.compareAndSet(false, true)) return
        val delivered = deliveredGen.get() == generation.get()
        val cachedCity = sp.getString(WEATHER_KEY_CITY, "") ?: ""
        if (delivered && !isWeakCity(cachedCity)) {
            Logx.d(LogTags.WEATHER, "已有可用天气缓存，跳过网络回落")
            return
        }
        Logx.i(LogTags.WEATHER, "启动天气网络回落")
        Thread({ queryNetwork() }, "weather-network").start()
    }

    /**
     * 网络取数。
     *
     * 顺序：城市反查 → 天气接口 → 空气质量接口 → 拼接 → 投递。
     * 明确**没有 IP 定位回落**（没有位置就直接放弃）——
     * 这里：**只用设备定位**。
     *
     * > 曾经想过用 IP 定位兜底，但车机走的是**物联卡**，出口 IP 是运营商的异地节点，
     * > 定位出来的城市完全不对（用户实测反馈）。所以不做。
     * > 定位拿不到时正确的解法是**把定位权限/AppOps 开对**，见 [Permissions]。
     */
    private fun queryNetwork() {
        try {
            val loc = lastLocation()
            // 系统定位优先；没有就用**城市兜底坐标**（见 requestByCityFallback）。
            val fallback = cityFallbackLatLon
            val lat = loc?.latitude ?: fallback?.first
            val lon = loc?.longitude ?: fallback?.second
            if (lat == null || lon == null) {
                Logx.i(
                    LogTags.WEATHER, "网络天气跳过：无有效设备定位",
                    "state" to Permissions.describeLocationState(app),
                )
                return
            }
            // 城市：优先反查（更准），反查失败就用兜底城市名。
            //
            // 反查要联网，车机上公网可能不通；这时**已知城市**（高德给的）顶上，
            // 免得天气出来了城市名却是空的。
            val reversed = reverseCityWithRetry(lat, lon)
            val city = if (!isWeakCity(reversed)) {
                reversed
            } else {
                cityFallbackName ?: knownCityOrNull() ?: reversed
            }
            Logx.i(
                LogTags.WEATHER, "网络天气使用定位",
                "city" to city, "lat" to lat, "lon" to lon,
                "source" to if (loc != null) "device" else "city",
            )
            val now = parseCurrentWeather(http(forecastUrl(lat, lon)))
            if (now == null) {
                Logx.w(LogTags.WEATHER, "网络天气解析失败")
                return
            }
            val air = queryAirQuality(lat, lon)
            val sky = combineSky(now.sky, air)
            deliver(generation.get(), city, sky, now.temp, "network")
        } catch (t: Throwable) {
            Logx.w(LogTags.WEATHER, "网络天气失败", t)
        }
    }

    /**
     * 空气质量。
     *
     * 取不到就返回 null（不影响天气本身）。
     */
    private fun queryAirQuality(lat: Double, lon: Double): String? = try {
        val aqi = parseAirQuality(http(airQualityUrl(lat, lon)))
        if (aqi == null) {
            Logx.d(LogTags.WEATHER, "空气质量不可用")
            null
        } else {
            Logx.d(LogTags.WEATHER, "空气质量", "aqi" to aqi)
            airQualityLabel(aqi)
        }
    } catch (t: Throwable) {
        Logx.d(LogTags.WEATHER, "空气质量不可用", t)
        null
    }

    /**
     * 城市反查：系统 Geocoder → bigdatacloud → nominatim
     * （三级回落链）。
     */
    private fun reverseCityWithRetry(lat: Double, lon: Double): String {
        // 第一级：系统 Geocoder（`WeatherDesk.systemCity`）
        val system = systemCity(lat, lon)
        if (!isWeakCity(system)) return system.orEmpty()

        // 第二级：bigdatacloud（`WeatherDesk.secondaryCity`）
        val secondary = try {
            parseBigDataCloudCity(http(bigDataCloudUrl(lat, lon)))
        } catch (t: Throwable) {
            Logx.d(LogTags.WEATHER, "bigdatacloud 反查不可用", t)
            ""
        }
        if (!isWeakCity(secondary)) return secondary

        // 第三级：nominatim（`WeatherDesk.reverseCity`）
        val tertiary = try {
            parseNominatimCity(http(nominatimUrl(lat, lon)))
        } catch (t: Throwable) {
            Logx.d(LogTags.WEATHER, "nominatim 反查不可用", t)
            ""
        }
        if (!isWeakCity(tertiary)) return tertiary

        Logx.w(LogTags.WEATHER, "所有反查源都拿不到中文城市")
        return secondary.ifEmpty { tertiary.ifEmpty { system.orEmpty() } }
    }

    /**
     * 系统 Geocoder 反查。
     *
     * 字段顺序是 **locality → subAdminArea → adminArea**
     *
     */
    private fun systemCity(lat: Double, lon: Double): String? {
        return try {
            if (!Geocoder.isPresent()) {
                Logx.d(LogTags.WEATHER, "系统 Geocoder 不可用")
                return null
            }
            @Suppress("DEPRECATION")
            val list = Geocoder(app, Locale.CHINA).getFromLocation(lat, lon, 1)
            val address = list?.firstOrNull()
            cleanCity(address?.locality)
                ?: cleanCity(address?.subAdminArea)
                ?: cleanCity(address?.adminArea)
        } catch (t: Throwable) {
            Logx.d(LogTags.WEATHER, "系统反查不可用", t)
            null
        }
    }

    /**
     * HTTP GET
     * 6s 连接/读取超时、固定 UA 与 Accept-Language、读完断开、
     * **2xx 都算成功**（判的是 `code < 200 || code >= 300`）。
     */
    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", HTTP_USER_AGENT)
        conn.setRequestProperty("Accept-Language", HTTP_ACCEPT_LANGUAGE)
        try {
            val code = conn.responseCode
            if (code < 200 || code >= 300) throw IllegalStateException("HTTP $code for $url")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 定位

    /**
     * 最后一次已知位置。
     *
     * 遍历 `getProviders(true)`（**所有启用的 provider**），车机上厂商可能用自定义
     * provider 名，所以不能只试 GPS/NETWORK/PASSIVE。
     *
     * 筛选条件：位置新鲜（≤600s）、准确（≤5000m）
     * 多个候选取**最新**的。
     */
    private fun lastLocation(): Location? {
        return try {
            // 主动要来的 fix 优先（见 requestOneShotFix）
            pendingFix?.let {
                Logx.d(LogTags.WEATHER, "使用主动请求到的定位", "lat" to it.latitude, "lon" to it.longitude)
                return it
            }
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            // 用的是 `getProviders(true)`（只列**已启用**的 provider）。
            // 车机上有些定位 provider 是「存在但未启用」，true 取不到时再兜一次 false。
            val enabled = lm.getProviders(true)
            val providers = if (enabled.isEmpty()) lm.getProviders(false) else enabled
            // **再补一轮「按名字猜」的 provider**（我们补充）。
            //
            // 高德车机版拿天气位置用的是
            // `getLastKnownLocation(Location.Provider.GNSS)`
            // —— **"gnss" 这个 provider 常常不在 返回的列表里**
            // （Android 9 的 GNSS provider 与 "gps" 是两套），但 `getLastKnownLocation("gnss")`
            // 仍然能取到值。模拟器实测：列表只有 passive/gps，高德却用 "gnss" 拿到了
            // 39.90923,116.39742。
            //
            //  也是**显式点名** "network" / "gps"
            // 取位置的，不是只遍历列表 —— 这里照这个思路补齐常见名字。
            val providerNames = LinkedHashSet<String>()
            providerNames.addAll(providers)
            providerNames.addAll(EXTRA_PROVIDER_NAMES)
            val pairs = ArrayList<Pair<Location, LocationCandidate>>(providerNames.size)
            for (provider in providerNames) {
                val loc = try {
                    lm.getLastKnownLocation(provider)
                } catch (t: Throwable) {
                    Logx.i(
                        LogTags.WEATHER, "取位置失败",
                        "provider" to provider, "err" to (t.message ?: t.javaClass.simpleName),
                    )
                    null
                }
                if (loc == null) {
                    // 排障关键行：分清「provider 没有 lastKnownLocation」和
                    // 「有但被 600s/5000m 滤掉了」—— 真机上前者占多数。
                    Logx.i(LogTags.WEATHER, "定位 provider 无缓存位置", "provider" to provider)
                    continue
                }
                pairs.add(
                    loc to LocationCandidate(
                        timeMillis = loc.time,
                        ageNanos = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos,
                        accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else -1f,
                    )
                )
            }
            if (pairs.isEmpty()) {
                // 排障关键行：车机上拿不到位置时，这里能直接看出「有几个 provider」
                Logx.i(
                    LogTags.WEATHER, "没有可用定位",
                    "enabledProviders" to enabled.size,
                    "allProviders" to providers.size,
                    "providers" to providers.joinToString(","),
                    "tried" to providerNames.joinToString(","),
                )
                return fallbackLocation()
            }
            val picked = pickFreshestLocation(pairs.map { it.second })
            if (picked != null) {
                val chosen = pairs.firstOrNull { it.second === picked }?.first
                    ?: pairs.first { it.second.timeMillis == picked.timeMillis }.first
                Logx.i(
                    LogTags.WEATHER, "定位可用",
                    "lat" to chosen.latitude, "lon" to chosen.longitude,
                    "ageMs" to (picked.ageNanos / 1_000_000L),
                    "accuracy" to picked.accuracyMeters,
                )
                persistFix(chosen)
                return chosen
            }
            //  600s / 5000m 过滤在车机上可能过严（车机 GPS 常常很久不更新
            // 导航 App 也可能把位置缓存得更久→ 滤空时退而求其次取任意最新位置。
            val relaxed = pickAnyLocation(pairs.map { it.second })
            if (relaxed == null) {
                Logx.i(
                    LogTags.WEATHER, "定位全部过期或精度不达标",
                    "candidates" to pairs.size,
                    "providers" to providers.joinToString(","),
                )
                return fallbackLocation()
            }
            val chosen = pairs.firstOrNull { it.second === relaxed }?.first
                ?: pairs.first { it.second.timeMillis == relaxed.timeMillis }.first
            Logx.w(
                LogTags.WEATHER, "定位时效/精度不达标，已降级使用",
                "lat" to chosen.latitude, "lon" to chosen.longitude,
                "ageMs" to (relaxed.ageNanos / 1_000_000L),
                "accuracy" to relaxed.accuracyMeters,
                "providers" to providers.joinToString(","),
            )
            persistFix(chosen)
            chosen
        } catch (t: Throwable) {
            Logx.w(LogTags.WEATHER, "取位置不可用", t)
            fallbackLocation()
        }
    }

    /**
     * **系统定位彻底拿不到时的兜底位置**（我们补充）。
     *
     * 顺序：高德缓存的最近位置 → 我们自己持久化的上次定位。
     *
     * 两者都是「没有新鲜 fix 时的近似位置」，但对**城市级天气**完全够用
     * （天气按城市变化，几十公里内的差别不影响天气现象）。
     */
    private fun fallbackLocation(): Location? {
        amapCachedLocation()?.let { return it }
        return persistedFix()
    }

    /**
     * 记住一次成功定位（落盘）。
     *
     * 车机的 GNSS 可能很久不更新，甚至几天都不动；一旦拿到过位置，
     * 就存下来 —— 下次系统定位拿不到时可以直接用（见 [persistedFix]）。
     */
    private fun persistFix(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        if (!WeatherLocation.isValidLatLon(lat, lon)) return
        val prefs = Prefs(app)
        prefs.weatherFixLat = lat.toFloat()
        prefs.weatherFixLon = lon.toFloat()
        prefs.weatherFixTime = System.currentTimeMillis()
    }

    /**
     * **持久化的上次定位**（我们补充）。
     *
     * 只在「系统定位完全拿不到」时使用，且最多复用 [PERSISTED_FIX_MAX_AGE_MS]；
     * 天气是城市级的，用一次旧位置比完全拿不到天气强得多。
     */
    private fun persistedFix(): Location? {
        val prefs = Prefs(app)
        val lat = prefs.weatherFixLat
        val lon = prefs.weatherFixLon
        val time = prefs.weatherFixTime
        if (lat.isNaN() || lon.isNaN() || time <= 0L) return null
        val age = System.currentTimeMillis() - time
        if (!WeatherLocation.isFixUsable(age, PERSISTED_FIX_MAX_AGE_MS)) {
            Logx.i(LogTags.WEATHER, "持久化定位已过期", "ageMs" to age)
            return null
        }
        Logx.i(
            LogTags.WEATHER,
            "定位来源：持久化的上次定位",
            "lat" to lat.toDouble(), "lon" to lon.toDouble(),
            "ageMs" to age,
        )
        return Location(PERSISTED_FIX_PROVIDER).apply {
            latitude = lat.toDouble()
            longitude = lon.toDouble()
        }
    }

    /**
     * **高德缓存的最近位置**（我们补充的位置来源）。
     *
     * 系统定位完全没有时（真机常态：provider 只有 `passive,gps` 且都没缓存位置），
     * 就用高德车机版自己缓存的车辆位置 —— 它是**明文 JSON**
     * （`files/pos_cache.db` 的 `POS_TABLE.DATA`），见 [AmapCitySource.readPositionFromFile]。
     *
     * 高德在车机上一定能定位（导航要定位），所以这条路的可用性比系统定位高。
     */
    private fun amapCachedLocation(): Location? {
        val cached = amapPositionCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < AMAP_POSITION_CACHE_MS) {
            return Location(AMAP_LOCATION_PROVIDER).apply {
                latitude = cached.second.first
                longitude = cached.second.second
            }
        }
        val pos = AmapCitySource.readPositionFromFile(app) ?: return null
        amapPositionCache = now to pos
        Logx.i(
            LogTags.WEATHER,
            "定位来源：高德位置缓存",
            "lat" to pos.first,
            "lon" to pos.second,
        )
        return Location(AMAP_LOCATION_PROVIDER).apply {
            latitude = pos.first
            longitude = pos.second
        }
    }

    // ------------------------------------------------------------------ 主线程投递

    private fun post(block: () -> Unit) {
        main.post { block() }
    }

    private companion object {
        /** HTTP 超时。 */
        const val HTTP_TIMEOUT_MS = 6000

        /** 请求头 UA。 */
        const val HTTP_USER_AGENT = "LynkCoDesktop/1.0"

        /** 请求头语言。 */
        const val HTTP_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9"

        /** 车机等待窗口（`WeatherDesk.CAR_TIMEOUT_MS`）。 */
        const val CAR_TIMEOUT_MS = 4000L

        /** 主动订阅定位后等 fix 的超时（超时就退订，等下一轮刷新）。 */
        const val FIX_TIMEOUT_MS = 30_000L

        /**
         * **按名字补试的定位 provider**（我们补充）。
         *
         * 高德车机版取天气位置用的是 `getLastKnownLocation("gnss")`
         * 而 `"gnss"` 常常**不在**
         * 返回列表里（Android 9 的 GNSS provider 与 "gps" 是两套）。
         * 模拟器实测：列表只有 `passive,gps`，高德却用 "gnss" 拿到了位置。
         *
         *  同样是**显式点名** "network"/"gps"
         * 不是只遍历列表 —— 这里把常见名字都补上，provider 不存在时 catch 掉即可。
         */
        val EXTRA_PROVIDER_NAMES = listOf("gnss", "network", "fused", "gps", "passive")

        /** 高德位置缓存的本地保留时长（毫秒）—— 避免每次取数都去读高德的库。 */
        const val AMAP_POSITION_CACHE_MS = 10_000L

        /** 伪 provider 名（高德缓存的位置不是系统 provider，标出来便于日志区分）。 */
        const val AMAP_LOCATION_PROVIDER = "amap-cache"

        /**
         * **向高德索要区域信息**后等它回话的最长时间（毫秒）。
         *
         * 高德要向车机（binder）走一圈，通常 1 秒内就有；给 8 秒余量。
         */
        const val AREA_INFO_WAIT_MS = 8_000L

        /**
         * 向高德索要区域信息的**尝试轮数**。
         *
         * 车机上高德可能刚被唤起、车机回包可能慢 —— 最多试两轮（合计约 16 秒）。
         */
        const val AREA_INFO_ATTEMPTS = 2

        /**
         * **持久化定位**的最长复用时长（毫秒）。
         *
         * 天气是城市级的：一次旧位置（哪怕是几小时前的）也比完全查不到天气强。
         * 6 小时 —— 覆盖「停车过夜 / 长时间地库」这类场景。
         */
        const val PERSISTED_FIX_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        /** 伪 provider 名（持久化的上次定位不是系统 provider，标出来便于日志区分）。 */
        const val PERSISTED_FIX_PROVIDER = "persisted-fix"
    }
}
