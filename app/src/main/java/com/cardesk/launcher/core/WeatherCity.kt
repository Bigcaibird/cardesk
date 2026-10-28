package com.cardesk.launcher.core

/**
 * 天气用的「城市名 ↔ 经纬度」换算（纯逻辑，便于 JVM 单测）。
 *
 * ## 为什么需要这一层
 *
 * 真机（领克 dcy11 / Android 9）实测：**系统只有 `passive,gps` 两个定位 provider，
 * 且都没有缓存位置** —— GNSS 在地库/室内永远等不到 fix，
 * 于是「拿位置 → 查天气」这条链在真机上第一步就断了。
 *
 * 但车机上**高德导航一定知道自己在哪个城市**（它的地图引擎有车联网定位），
 * 高德车机版自己就是三级兜底拿城市：
 *
 * | 级别 | 高德实现 | 位置 |
 * |---|---|---|
 * | 1 | GNSS 缓存位置 → 逆地理编码 | （`getLastKnownLocation("gnss")`） |
 * | 2 | **广播 10030 的 `CITY_NAME`** | |
 * | 3 | 写 `files/city.txt`（省-市-区） | |
 *
 * 我们用第 2/3 级拿到**城市名**，再换算成经纬度去查天气 —— 这样即使系统定位完全不可用，
 * 天气也能出来。
 *
 * ## 城市名规范化
 *
 * 照抄高德 的：
 * 「北京市」→「北京」、「XX地区」→「XX」、「XX盟」→「XX」，
 * 「香港特别行政区」→「香港」、「澳门特别行政区」→「澳门」。
 */
object WeatherCity {

    /** open-meteo 地理编码接口（城市名 → 经纬度）。 */
    const val GEOCODE_BASE = "https://geocoding-api.open-meteo.com/v1/search"

    /**
     * 从高德的「省-市-区」城市文件里取城市名。
     *
     * 高德写出来的格式（模拟器实测）：`北京市-北京市-东城区`
     * 取第二段（城市）→ 规范化 → 「北京」。
     *
     * @return 规范化后的城市名；文件为空 / 格式不认识 → null
     */
    fun pickCityFromAmapFile(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('-').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        // 「省-市-区」：第二段是城市；只有一段时就是它本身。
        val city = if (parts.size >= 2) parts[1] else parts[0]
        return normalizeCityName(city) ?: normalizeCityName(parts[0])
    }

    /**
     * 城市名规范化（照高德）。
     *
     * - 去掉末尾的「市」「地区」「盟」；
     * - 「香港特别行政区」→「香港」、「澳门特别行政区」→「澳门」；
     * - 自治区要保留（「内蒙古自治区」→「内蒙古」而不是空）。
     */
    fun normalizeCityName(raw: String?): String? {
        var name = raw?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (name == "香港特别行政区") return "香港"
        if (name == "澳门特别行政区") return "澳门"
        if (name.endsWith("特别行政区")) {
            name = name.removeSuffix("特别行政区")
            return name.ifEmpty { null }
        }
        // 「内蒙古自治区」「广西壮族自治区」「新疆维吾尔自治区」这类要去「自治区」留主体
        if (name.endsWith("自治区")) {
            name = name.removeSuffix("自治区")
            return name.ifEmpty { null }
        }
        if (name.endsWith("市")) name = name.dropLast(1)
        else if (name.endsWith("地区")) name = name.dropLast(2)
        else if (name.endsWith("盟")) name = name.dropLast(1)
        return name.ifEmpty { null }
    }

    /**
     * 城市名 → 地理编码接口 URL。
     *
     * 用 open-meteo 的 geocoding（和天气同一个服务商，车联网不通时也能一起挂）。
     */
    fun cityGeocodeUrl(city: String): String =
        "$GEOCODE_BASE?name=${urlEncode(city)}&count=1&language=zh&format=json"

    /**
     * 解析地理编码结果(纬度, 经度)。
     *
     * 返回 null 的情况：没有 results、results 为空、纬度/经度不在合法范围内。
     */
    fun parseCityGeocode(json: String): Pair<Double, Double>? {
        val root = JsonLite.parseObject(json) ?: return null
        val results = root["results"] as? List<*> ?: return null
        val first = results.firstOrNull() as? Map<String, Any?> ?: return null
        val lat = JsonLite.double(first, "latitude", Double.NaN)
        val lon = JsonLite.double(first, "longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return lat to lon
    }

    /** URL 编码（只留 ASCII 字母数字与 `-_.~`，其余按 UTF-8 逐字节 `%XX`）。 */
    fun urlEncode(text: String): String {
        val out = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            val safe = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "-_.~"
            if (safe) out.append(c) else out.append('%').append("%02X".format(v))
        }
        return out.toString()
    }
}
