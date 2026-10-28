package com.cardesk.launcher.core

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 车机天气 SDK 的反射封装。
 *
 * ## 为什么需要它
 *
 * 天气是**车机 SDK 优先**的
 * 先问车机的 `com.ecarx.sdk.weather.WeatherAPI`，拿不到才回落公网接口。
 * 车机 SDK 只存在于车机 `/system`（普通手机、模拟器上都没有），
 * 所以是**纯反射**调用 ——APK 的 dex 里
 * `com.ecarx.sdk.weather.WeatherAPI` / `createWeatherAPI` / `getWeatherByLocation`
 * 都只是**字符串常量**，不是编译期依赖。
 *
 * 我们之前**整条车机通路都没有**，只走公网 open-meteo：
 * 车机上公网域名不一定通，于是天气一直拿不到。
 *
 * ## 调用契约（逐条）
 *
 * |位置 | 行为 |
 * |---|---|
 * | | `Class.forName("com.ecarx.sdk.weather.WeatherAPI")` |
 * | | 找**静态**方法 `createWeatherAPI` 或 `create`，参数恰好 1 个且类型可由 `Context` 赋值 → 用 app 调 |
 * | | 在实例上找 `getWeatherByLocation`，参数 ≥ 2 个 |
 * | | 按参数类型组装：两个 `double` 依次是 **经度、纬度**（注意顺序！）；`interface` 参数填动态代理 |
 * | | 回调代理遍历**所有非 null 参数**，逐个 harvest |
 * | | harvest 取字段：天气文案 / 温度 / 城市 |
 *
 * 本文件只做「反射 + 取值」，不含任何网络与定位逻辑，方便 JVM 单测。
 */
object CarWeatherApi {

    /** 车机天气 SDK 类名。 */
    const val WEATHER_API_CLASS = "com.ecarx.sdk.weather.WeatherAPI"

    /** 天气文案字段。 */
    val SKY_METHODS = listOf("getWeatherPhenomena")

    /** 温度字段（：温度 → temp → cTemperature）。 */
    val TEMP_METHODS = listOf("getTemperature", "getTemp", "getcTemperature")

    /** 城市字段（：city → cityName → locationName）。 */
    val CITY_METHODS = listOf("getCity", "getCityName", "getLocationName")

    /**
     * 一次车机天气回调的原始内容。
     *
     * @param city 城市名（可能为 null / 空）
     * @param sky 天气文案（可能为 null）
     * @param temp 整数温度（可能为 null）
     */
    data class Raw(val city: String?, val sky: String?, val temp: Int?)

    /**
     * 车机天气 SDK 是否存在。
     *
     * 车机上有，普通手机/模拟器上没有 —— 用来决定要不要走车机通路。
     */
    fun isAvailable(): Boolean = runCatching {
        Class.forName(WEATHER_API_CLASS)
        true
    }.getOrDefault(false)

    /**
     * 反射读一个字符串字段。
     *
     * 语义：方法不存在、返回 null、返回空串、返回字面量 "null" 一律当没有值
     * 调用方按顺序试多个方法名，取第一个有值的。
     */
    fun readText(target: Any, methodNames: List<String>): String? {
        for (name in methodNames) {
            val value = invoke(target, name) ?: continue
            val text = value.toString().trim()
            if (text.isEmpty() || text == "null") continue
            return text
        }
        return null
    }

    /**
     * 反射读温度。
     *
     * 语义
     * - 返回 `Number` →；
     * - 返回字符串 → 去掉所有非 `[0-9.+-]` 字符后按 double 解析再 `Math.round`；
     * - 解析不了 → 没有值（调用方继续试下一个方法名）。
     */
    fun readNumber(target: Any, methodNames: List<String>): Int? {
        for (name in methodNames) {
            val value = invoke(target, name) ?: continue
            if (value is Number) return value.toInt()
            val cleaned = value.toString().replace(NON_NUMERIC, "")
            if (cleaned.isEmpty()) continue
            val parsed = cleaned.toDoubleOrNull() ?: continue
            return Math.round(parsed).toInt()
        }
        return null
    }

    /**
     * 从一次回调里抽出天气原始内容。
     *
     * 规则
     * - 天气文案与温度**都取不到** → 返回 null（不投递）；
     * - 城市按 `getCity → getCityName → getLocationName` 顺序取。
     *
     * 城市为 null/空时**不补反查**（反查要在拿到经纬度后做，见 `WeatherDesk`）。
     */
    fun harvest(target: Any): Raw? {
        val sky = readText(target, SKY_METHODS)
        val temp = readNumber(target, TEMP_METHODS)
        if (sky == null && temp == null) return null
        return Raw(city = readText(target, CITY_METHODS), sky = sky, temp = temp)
    }

    /**
     * 从回调方法的**所有非 null 参数**里 harvest。
     *
     * 对每个非 null 参数都调一次 harvest，所以同一个回调带多个参数时会投递多次。
     */
    fun harvestAll(args: Array<out Any?>?): List<Raw> {
        if (args == null) return emptyList()
        val out = ArrayList<Raw>()
        for (arg in args) {
            if (arg == null) continue
            val raw = harvest(arg) ?: continue
            out.add(raw)
        }
        return out
    }

    /**
     * 造一个回调代理。
     *
     * 代理收到任何方法调用都会把参数交给 [onArgs]。
     */
    fun callbackProxy(iface: Class<*>, onArgs: (Array<out Any?>?) -> Unit): Any {
        val handler = InvocationHandler { _, _, args ->
            onArgs(args)
            null
        }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
    }

    /**
     * 找车机 SDK 的创建方法。
     *
     * 遍历 取**第一个**名字是 `createWeatherAPI` 或 `create`
     * 参数恰好 1 个且类型可由 `Context` 赋值的方法，然后静态调用。
     */
    fun findCreateMethod(apiClass: Class<*>): Method? = apiClass.methods.firstOrNull { m ->
        (m.name == "createWeatherAPI" || m.name == "create") &&
            m.parameterTypes.size == 1 &&
            Context::class.java.isAssignableFrom(m.parameterTypes[0])
    }

    /**
     * 找查询方法。
     *
     * 遍历实例的 取**第一个**名字是 `getWeatherByLocation`
     * 参数 ≥ 2 个的方法。
     */
    fun findQueryMethod(instance: Any): Method? =
        instance.javaClass.methods.firstOrNull { m ->
            m.name == "getWeatherByLocation" && m.parameterTypes.size >= 2
        }

    /**
     * 找**不需要经纬度**的查询入口（我们补充）。
     *
     * ## 为什么需要
     *
     * 只有 `getWeatherByLocation(lon, lat)` 一条路，而它**必须先有位置**。
     * 真机（领克 dcy11 / Android 9）实测：系统只有 `passive,gps` 两个定位 provider，
     * `getLastKnownLocation` 两者都返回 null，gps 在地下/室内永远等不到 fix ——
     * 于是整条天气链路（车机 SDK + 网络回落）**全部卡在「没有位置」这一步**。
     *
     * 车机天气 SDK 自己带 GNSS/车联网，往往还有「按当前城市 / 直接取当前天气」这种
     * 入口（不需要调用方给经纬度）。这里反射找一找，**只在路径完全失败后**用。
     *
     * 判定条件（保守，避免瞎调 SDK）：
     * - 方法名以 `get` 开头，且**不含** `ByLocation`（排除那条路）
     * - 参数**全是接口**（回调），没有 double/String/int 等需要调用方提供值的参数。
     */
    fun findNoLocationQueryMethod(instance: Any): Method? =
        instance.javaClass.methods.firstOrNull { m ->
            m.name.startsWith("get") &&
                !m.name.contains("ByLocation") &&
                m.parameterTypes.isNotEmpty() &&
                m.parameterTypes.all { it.isInterface }
        }

    /**
     * 找**按城市名**查询的入口（我们补充）。
     *
     * ## 为什么需要
     *
     * 只有 `getWeatherByLocation(经度, 纬度, 回调)` 一条路，而它**必须先有位置**。
     * 真机上系统定位长期没有位置（provider 只有 `passive,gps` 且都没缓存位置），
     * 于是车机天气 SDK 这条路也走不通。
     *
     * 车机 SDK 多半还有「按城市查」或「取当前天气」这种入口 ——
     * 真机上到底有没有，看 `describeMethods` 打出来的方法清单就能定（已落盘）。
     * 这里先把常见的几种名字都试一遍，参数按类型填（String 填城市名，接口填回调代理）。
     *
     * 判定条件（保守，避免瞎调 SDK）：
     * - 方法名里带 "City"（按城市）或 "Current"/"Now"（当前天气）；
     * - 参数里至少有一个 `String`（城市名），或者全是接口（无参回调）。
     */
    fun findCityQueryMethod(instance: Any): Method? =
        instance.javaClass.methods.firstOrNull { m ->
            if (m.parameterTypes.isEmpty()) return@firstOrNull false
            val byCity = m.name.contains("City", ignoreCase = true)
            val current = m.name.contains("Current", ignoreCase = true) || m.name.contains("Now", ignoreCase = true)
            if (!byCity && !current) return@firstOrNull false
            m.parameterTypes.any { it == String::class.java } || m.parameterTypes.all { it.isInterface }
        }

    /**
     * 组装「按城市」调用的实参。
     *
     * - `String` → 城市名；
     * - `interface` → 动态代理；
     * - 其它 → null。
     */
    fun buildCityArgs(
        types: Array<Class<*>>,
        city: String?,
        proxyFor: (Class<*>) -> Any?,
    ): Array<Any?> {
        val args = arrayOfNulls<Any?>(types.size)
        for (i in types.indices) {
            val type = types[i]
            if (type == String::class.java) args[i] = city
            else if (type.isInterface) args[i] = proxyFor(type)
        }
        return args
    }

    /**
     * 列出车机天气 SDK 的**全部 public 方法签名**（诊断用）。
     *
     * 真机上「天气拿不到」时，必须知道这台车的 SDK 到底提供了哪些入口，
     * 否则只能反复猜方法名。这一行日志能直接给出答案。
     */
    fun describeMethods(instance: Any): String =
        describeMethods(instance.javaClass)

    /**
     * 同上，但直接传 Class —— **不用先创建实例**。
     *
     * ## 为什么要 Class 版本
     *
     * 车机上 `createWeatherAPI` 这类**静态方法**可能因为隐藏 API 限制调不通，
     * 而方法清单本身（`Class.getMethods()`）是能拿到的。
     * 先打清单、再尝试创建实例，这样即使创建失败，诊断包里也**一定能看到这台车
     * 天气 SDK 到底有哪些入口**，下一轮就能直接按真实接口接。
     */
    fun describeMethods(apiClass: Class<*>): String =
        apiClass.methods
            .map { m ->
                m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + "):" + m.returnType.simpleName
            }
            .sorted()
            .joinToString(" ")
            .take(1200)

    /**
     * 按参数类型组装调用实参。
     *
     * - `double` / `Double` → 依次填 **经度、纬度**（顺序
     *   `doubleIndex == 0 ? longitude: latitude`；
     * - `interface` → 填动态代理；
     * - 其它 → 留 null（由 SDK 自行决定默认值）。
     */
    fun buildArgs(
        types: Array<Class<*>>,
        longitude: Double,
        latitude: Double,
        proxyFor: (Class<*>) -> Any?,
    ): Array<Any?> {
        val args = arrayOfNulls<Any?>(types.size)
        var doubleIndex = 0
        for (i in types.indices) {
            val type = types[i]
            if (type == Double::class.javaPrimitiveType || type == Double::class.java) {
                args[i] = if (doubleIndex == 0) longitude else latitude
                doubleIndex++
            } else if (type.isInterface) {
                args[i] = proxyFor(type)
            }
        }
        return args
    }

    /** 调用单个无参方法，失败（不存在/抛异常/返回 null）一律返回 null。 */
    private fun invoke(target: Any, name: String): Any? = runCatching {
        target.javaClass.getMethod(name).invoke(target)
    }.getOrNull()

    /** 非数字字符（温度字符串清洗用）。 */
    private val NON_NUMERIC = Regex("[^0-9.+-]")
}
