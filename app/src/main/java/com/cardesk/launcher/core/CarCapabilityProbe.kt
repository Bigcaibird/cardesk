package com.cardesk.launcher.core

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * **车机定位/天气能力的反射探测**（第三十一轮新增）。
 *
 * ## 为什么需要它
 *
 * 真机（领克 dcy11 / Android 9）上系统定位长期没有位置（provider 只有 `passive,gps` 且都没 fix）。
 * 只走 `LocationManager.getLastKnownLocation()` 这一条路，所以在真机上也是拿不到位置的
 * —— **但版确实能显示天气**，说明这台车上还有别的天气/城市来源。
 *
 * 车机的定位与天气能力都在 **ECARX AdaptAPI** 里（自己就用它读车辆信号
 * `com.ecarx.xui.adaptapi.car.Car` / `...tpms.TPMS` / `...tbox.TBox`。
 * 其中 **TBox（车联网模块）通常带 GNSS 接收机**，是车机定位的真正来源。
 *
 * 这台车具体提供了哪些方法，**只能到真机上问**。所以这里做一个**纯反射的探测**：
 * 把 TBox / ICarInfo 的公开方法签名、系统服务名、ContentProvider authority
 * 全部列出来打进日志与诊断包 —— 拿到一次，下一轮就能直接按真实接口接。
 *
 * ## 安全边界
 *
 * - 只**读方法签名**与调用无参 getter，不写任何车辆状态；
 * - 所有反射都 `runCatching`，车机上没有这些类时静默降级；
 * - 探测结果只进日志/诊断包，不影响任何功能路径。
 */
object CarCapabilityProbe {

    /** TBox 类名。 */
    const val CLS_TBOX = "com.ecarx.xui.adaptapi.tbox.TBox"

    /** 车辆信息接口（用到的 `com.ecarx.xui.adaptapi.car.base.ICarInfo`）。 */
    const val CLS_CAR_INFO = "com.ecarx.xui.adaptapi.car.base.ICarInfo"

    /** 车辆对象（`com.ecarx.xui.adaptapi.car.Car`，我们 `vehicle/AdaptapiGateway` 已在用）。 */
    const val CLS_CAR = "com.ecarx.xui.adaptapi.car.Car"

    /** 探测结果里每段的最大长度（防止把车机日志撑爆）。 */
    private const val SEGMENT_LIMIT = 900

    /** 方法名里出现这些片段，就认为它可能直接给经纬度。 */
    private val LAT_HINTS = listOf("lat", "latitude", "纬度")
    private val LON_HINTS = listOf("lon", "lng", "long", "longitude", "经度")
    private val LOCATION_HINTS = listOf("location", "position", "gps", "gnss", "coord", "point", "city")

    /**
     * 一次性探测并返回一段可读摘要。
     *
     * @param context 上下文（用于反射创建 TBox / Car 实例）
     */
    fun probe(context: Context): String {
        val sb = StringBuilder()
        sb.append("TBox=").append(probeTbox(context)).append(" | ")
        sb.append("CarInfo=").append(probeClassMethods(CLS_CAR_INFO)).append(" | ")
        sb.append("服务=").append(probeServiceNames()).append(" | ")
        sb.append("Provider=").append(probeProviderAuthorities(context))
        return sb.toString()
    }

    /**
     * 探测 TBox：列出公开方法，并尝试从「像经纬度的 getter」里直接取值。
     *
     * @return 形如 `方法=create(Context):TBox|getSignalStrength():SignalStrength|lat=39.9 lon=116.4`
     */
    private fun probeTbox(context: Context): String {
        val instance = runCatching {
            val cls = Class.forName(CLS_TBOX)
            val create = cls.methods.firstOrNull { m ->
                (m.name == "create" || m.name == "createTbox") &&
                    m.parameterTypes.size == 1 &&
                    Context::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: return@runCatching null
            create.invoke(null, context.applicationContext)
        }.getOrNull() ?: return "不可用"

        val sb = StringBuilder()
        sb.append("方法=").append(describeMethods(instance).take(600))
        val coords = tryExtractLatLon(instance)
        if (coords != null) sb.append("|经纬度=").append(coords)
        return sb.toString()
    }

    /** 列出某个类（或实例）的公开方法签名（诊断用）。 */
    private fun probeClassMethods(className: String): String {
        val cls = runCatching { Class.forName(className) }.getOrNull() ?: return "不可用"
        return describeMethods(cls)
    }

    /**
     * 列出所有 public 方法签名：`name(Param1,Param2):Return`。
     */
    fun describeMethods(target: Any): String {
        val methods = when (target) {
            is Class<*> -> target.methods
            else -> target.javaClass.methods
        }
        return methods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { m ->
                m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + "):" + m.returnType.simpleName
            }
            .sorted()
            .distinct()
            .joinToString(" ")
            .take(SEGMENT_LIMIT)
    }

    /**
     * 试着从实例里直接取出经纬度。
     *
     * 做法：找出所有**无参**且名字像「位置/经纬度」的 public 方法，逐个调用，
     * 看返回值里能不能凑出一对合法的经纬度（或一个能同时给出两者的对象）。
     *
     * @return "lat=39.9 lon=116.4" 形态；取不到返回 null
     */
    fun tryExtractLatLon(instance: Any): String? {
        val candidates = instance.javaClass.methods.filter { m ->
            Modifier.isPublic(m.modifiers) &&
                m.parameterTypes.isEmpty() &&
                !m.returnType.isPrimitive &&
                m.returnType != Void.TYPE
        }
        var lat: Double? = null
        var lon: Double? = null
        for (m in candidates) {
            val name = m.name.lowercase()
            val looksLocation = LOCATION_HINTS.any { name.contains(it) } ||
                LAT_HINTS.any { name.contains(it) } || LON_HINTS.any { name.contains(it) }
            if (!looksLocation) continue
            val value = runCatching { m.invoke(instance) }.getOrNull() ?: continue
            val pair = readLatLon(value, name)
            if (pair != null) {
                if (pair.first != null) lat = pair.first
                if (pair.second != null) lon = pair.second
            }
            if (lat != null && lon != null) break
        }
        if (lat == null || lon == null) return null
        return "lat=$lat lon=$lon"
    }

    /**
     * 从一个返回值里读经纬度。
     *
     * - 数值 → 按方法名判断是纬还是经；
     * - 对象 → 再看它的同名 getter（之类）。
     */
    private fun readLatLon(value: Any, methodName: String): Pair<Double?, Double?>? {
        if (value is Number) {
            val d = value.toDouble()
            return if (LAT_HINTS.any { methodName.lowercase().contains(it) }) d to null
            else if (LON_HINTS.any { methodName.lowercase().contains(it) }) null to d
            else null
        }
        var lat: Double? = null
        var lon: Double? = null
        for (m in value.javaClass.methods) {
            if (m.parameterTypes.isNotEmpty()) continue
            val n = m.name.lowercase()
            val d = runCatching { m.invoke(value) }.getOrNull() as? Number ?: continue
            when {
                n.contains("latitude") || n == "getlat" -> lat = d.toDouble()
                n.contains("longitude") || n.contains("lng") -> lon = d.toDouble()
            }
        }
        return if (lat != null || lon != null) lat to lon else null
    }

    /**
     * 列出系统服务名里与定位/天气/车机相关的那些。
     *
     * `android.os.ServiceManager` 是隐藏 API，反射取；
     * 车机上有 GNSS / 定位 / 车联网 / 天气相关的服务，名字本身就是线索。
     */
    private fun probeServiceNames(): String {
        val names = runCatching {
            val cls = Class.forName("android.os.ServiceManager")
            val m = cls.getMethod("getServiceNames")
            @Suppress("UNCHECKED_CAST")
            (m.invoke(null) as? Array<String>)?.toList().orEmpty()
        }
        val list = names.getOrNull().orEmpty()
        if (list.isEmpty()) {
            // 失败原因一定要带出来 —— 「不可用」到底是**隐藏 API 被拦**还是别的，
            // 决定了「版能反射、我们不能」这个假设成不成立。
            val err = names.exceptionOrNull()
            return "不可用(${err?.javaClass?.simpleName ?: "空"}: ${err?.message?.take(120) ?: "-"})"
        }
        val hit = list.filter { n ->
            val l = n.lowercase()
            LOCATION_HINTS.any { l.contains(it) } ||
                l.contains("weather") || l.contains("ecarx") || l.contains("navi") ||
                l.contains("gnss") || l.contains("tbox") || l.contains("amap")
        }
        return hit.joinToString(",").take(SEGMENT_LIMIT).ifEmpty { "无匹配(${list.size}个服务)" }
    }

    /**
     * 列出与定位/天气相关的 ContentProvider authority。
     *
     * 车机厂商常把车辆信息放在 ContentProvider 里（如 `com.ecarx.hmi.provider.xxx`），
     * 找到 authority 就能直接 query —— 比猜方法名靠谱。
     */
    private fun probeProviderAuthorities(context: Context): String {
        val all = runCatching { queryAllProviders(context.packageManager) }.getOrDefault(emptyList())
        val authorities = all.mapNotNull { info -> info?.authority }
            .filter { it.contains("ecarx", true) || it.contains("weather", true) || it.contains("location", true) || it.contains("hmi", true) || it.contains("geo", true) }
        return authorities.joinToString(",").take(SEGMENT_LIMIT).ifEmpty { "无匹配" }
    }

    /**
     * 列出系统里所有 ContentProvider（反射）。
     *
     * `PackageManager.queryContentProviders` 在 API 33 起换成了带 的重载，
     * 而车机是 API 28 —— 两个签名都不是编译期稳定可用的，所以走反射按版本挑一个。
     */
    private fun queryAllProviders(pm: PackageManager): List<android.content.pm.ProviderInfo?> {
        val intType = Int::class.javaPrimitiveType!!
        val method = if (android.os.Build.VERSION.SDK_INT >= 33) {
            PackageManager::class.java.getMethod("queryContentProviders", String::class.java, intType, intType)
        } else {
            PackageManager::class.java.getMethod("queryContentProviders", String::class.java, intType)
        }
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(pm, null, PackageManager.GET_META_DATA, 0) as? List<android.content.pm.ProviderInfo>
            ?: return emptyList()
        return result
    }

    /** 反射调用一个无参方法（诊断用的小工具）。 */
    fun invokeNoArg(target: Any, method: Method): Any? =
        runCatching { method.invoke(target) }.getOrNull()
}
