package com.cardesk.launcher.core

import android.content.Context
import android.util.Log
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File

/**
 * **高德的城市来源**（真机上系统定位拿不到位置时的兜底）。
 *
 * ## 为什么要它
 *
 * 真机（领克 dcy11 / Android 9）实测：定位 provider 只有 `passive,gps`，
 * 两个都没有缓存位置，GNSS 在地库/室内也等不到 fix
 * 「拿位置 → 查天气」第一步就断了。
 *
 * 但**高德导航一定知道自己在哪个城市**。高德车机版自己就是三级兜底拿城市的：
 *
 * | 级别 | 高德实现 | 位置 |
 * |---|---|---|
 * | 1 | GNSS 缓存位置 → 逆地理编码 | （`getLastKnownLocation("gnss")`） |
 * | 2 | 广播 的 `CITY_NAME` | |
 * | 3 | 写 `files/city.txt`（省-市-区） | |
 *
 * 我们取第 2/3 级：
 *
 * - **读高德的城市文件**：`/data/data/<高德包名>/files/city.txt`
 *   （system 签名 + uid 1000 能读别的应用私有目录；模拟器实测可读）。
 * - **收高德的区域广播**：`AUTONAVI_STANDARD_BROADCAST_SEND` +
 *   `EXTRA_CITY`（`"CITY_NAME"`）+ `EXTRA_PROVICE`（省）。
 *
 * ## 与关系
 *
 * （`WeatherDesk`）**没有**这一层 —— 它只读系统定位。
 * 我们补这一层，是因为真机上那条路走不通（同一个定位 provider 列表、同一个 null）。
 * 这属于「做不到但用户明确要天气」的补充，不是自造功能口径。
 */
object AmapCitySource {

    /** 高德车机版标准广播 action（与高德一致）。 */
    const val ACTION_AMAP = "AUTONAVI_STANDARD_BROADCAST_SEND"

    /** 高德区域信息协议的 KEY_TYPE（高德自己的常量）。 */
    const val KEY_TYPE_AREA = 10030

    /** 高德的城市名 extra（= `"CITY_NAME"`）。 */
    const val EXTRA_CITY = "CITY_NAME"

    /** 高德的省份 extra。 */
    const val EXTRA_PROVINCE = "EXTRA_PROVICE"

    /** 高德车机版在真车上的包名（诊断包实测）。 */
    const val AMAP_YH_PACKAGE = "com.autonavi.amapautp.yh"

    /** 高德城市文件（省-市-区）。 */
    const val FILE_CITY = "city.txt"

    /** 高德上一次城市文件。 */
    const val FILE_CITY_OLD = "city_old.txt"

    /** 高德的位置缓存数据库（`POS_TABLE.DATA` 里是**明文 JSON**）。 */
    const val FILE_POS_CACHE_DB = "pos_cache.db"

    /** 高德位置缓存表名。 */
    const val TABLE_POS = "POS_TABLE"

    /**
     * 找出车机上装的高德包名。
     *
     * 真车是 `com.autonavi.amapautp.yh`（诊断包实测）；
     * 其它车机/手机上可能是别的 `com.autonavi.*`，所以再扫一遍已安装应用。
     */
    fun findAmapPackage(context: Context): String? {
        val pm = context.packageManager
        val hasYh = runCatching { pm.getPackageInfo(AMAP_YH_PACKAGE, 0) }.isSuccess
        if (hasYh) return AMAP_YH_PACKAGE
        val candidates = runCatching {
            pm.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it.startsWith("com.autonavi.") }
                .sorted()
        }.getOrDefault(emptyList())
        return candidates.firstOrNull()
    }

    /**
     * **最近一次从高德区域广播拿到的城市名**（进程内共享）。
     *
     * 高德发 （或 `10077`）时会带省/市/区；导航页的广播接收器
     * （`core/nav/AmapDesktopGuide`）收到后写进来，天气链路直接取。
     */
    @Volatile
    var broadcastCity: String? = null

    /** 最近一次从高德区域广播拿到的完整区域信息（省/市/区/区划码）。 */
    @Volatile
    var broadcastArea: AmapAreaInfo.Area? = null

    /**
     * 记下一次区域信息：写进内存 + **落盘**。
     *
     * 落盘的原因：高德只在收到车机区域信息时广播一次，
     * 我们启动晚就收不到 → 落盘后重启也能直接用上次的城市。
     */
    fun recordArea(area: AmapAreaInfo.Area?) {
        if (area == null) return
        broadcastArea = area
        val city = AmapAreaInfo.pickCity(area)
        if (city.isNullOrEmpty()) return
        broadcastCity = city
        val prefs = Prefs(appContext ?: return)
        prefs.weatherCity = city
        if (!area.province.isNullOrEmpty()) prefs.weatherProvince = area.province.orEmpty()
        if (!area.areaCode.isNullOrEmpty()) prefs.weatherAreaCode = area.areaCode.orEmpty()
    }

    /**
     * 应用上下文（`recordArea` 需要落盘，必须有上下文）。
     *
     * 由 [bindContext] 在应用启动时设置一次。
     */
    @Volatile
    private var appContext: Context? = null

    /** 绑定应用上下文（落盘持久化城市时用）。 */
    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * **向高德索要区域信息**（第三十一轮新增）。
     *
     * 高德收到后会去向车机（`ecarx.naviservice`）要区域信息，
     * 车机回包后高德把城市**全局广播**出来，
     * 由 `core/nav/AmapDesktopGuide` 接收 → [recordArea]。
     *
     * 这是真机上「系统定位完全没有」时唯一可靠的坐标来源入口。
     */
    fun requestAreaInfo(context: Context, lat: Double = 0.0, lon: Double = 0.0): Boolean {
        val pkg = findAmapPackage(context) ?: run {
            Logx.i(LogTags.WEATHER, "索要区域信息失败：设备上没有高德")
            return false
        }
        return AmapAreaInfo.request(context, pkg, lat, lon)
    }

    /**
     * 读出**持久化**的城市名（上次拿到的，重启后仍在）。
     */
    fun persistedCity(context: Context): String? {
        val city = Prefs(context).weatherCity.trim()
        return city.ifEmpty { null }
    }

    /**
     * 读高德写的城市文件 → 规范化城市名。
     *
     * 读不到（没装高德 / 高德没跑过 / 不是 system 身份）返回 null。
     */
    fun readCityFromFile(context: Context): String? {
        val pkg = findAmapPackage(context) ?: return null
        for (name in listOf(FILE_CITY, FILE_CITY_OLD)) {
            val text = readAmapFile(context, pkg, name) ?: continue
            val city = WeatherCity.pickCityFromAmapFile(text)
            if (!city.isNullOrBlank()) {
                Logx.i(
                    LogTags.WEATHER,
                    "高德城市文件命中",
                    "pkg" to pkg,
                    "file" to name,
                    "city" to city,
                    "raw" to text.take(40),
                )
                return city
            }
        }
        return null
    }

    /** 读高德私有目录里的一个文本文件（读不到返回 null）。 */
    private fun readAmapFile(context: Context, pkg: String, name: String): String? = try {
        val dir = File("/data/data/$pkg/files")
        val file = File(dir, name)
        if (file.exists() && file.canRead()) file.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (t: Throwable) {
        // 非 system 身份读不了别的应用私有目录 —— 正常情况，降级即可。
        Logx.d(LogTags.WEATHER, "读高德城市文件失败", "pkg" to pkg, "file" to name, "err" to t.message)
        null
    }

    /**
     * **读高德缓存的最近位置**(纬度, 经度)。
     *
     * ## 这条路为什么最可靠
     *
     * 高德车机版把车辆最近位置写在自己的 `files/pos_cache.db` 里，
     * `POS_TABLE.DATA` 是**明文 JSON**（模拟器实测）：
     *
     * `json
     * {"lon":116.40366388888889,"lat":39.910635,"course":0.0,"isInParking":false,…}
     * `
     *
     * 而**高德在车机上一定能定位**（导航要定位；车机版高德走的是自己的定位链路，
     * 可能用车联网而不是 AOSP 的 LocationManager—— 这正好解释了
     * 「AOSP 的 gps provider 没有缓存位置，但高德有位置」这个现象。
     *
     * 所以这里把高德的位置缓存当成**位置来源之一**：system 身份（uid 1000）能读别的应用私有目录。
     *
     * @return (纬度, 经度)；读不到 / 解析失败 → null
     */
    fun readPositionFromFile(context: Context): Pair<Double, Double>? {
        val pkg = findAmapPackage(context) ?: return null
        val path = "/data/data/$pkg/files/$FILE_POS_CACHE_DB"
        val file = File(path)
        if (!file.exists()) return null
        var db: android.database.sqlite.SQLiteDatabase? = null
        return try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            val raw = db.rawQuery(
                "SELECT DATA FROM $TABLE_POS LIMIT 1",
                null,
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            val json = raw?.trim().orEmpty()
            if (json.isEmpty()) return null
            val obj = JsonLite.parseObject(json)
            val lat = JsonLite.double(obj, "lat", Double.NaN)
            val lon = JsonLite.double(obj, "lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            Logx.i(
                LogTags.WEATHER,
                "高德位置缓存命中",
                "pkg" to pkg,
                "lat" to lat,
                "lon" to lon,
            )
            lat to lon
        } catch (t: Throwable) {
            // 高德正在写库时可能锁住 —— 正常情况，下一轮再试。
            Logx.d(LogTags.WEATHER, "读高德位置缓存失败", "pkg" to pkg, "err" to t.message)
            null
        } finally {
            runCatching { db?.close() }
        }
    }

    /**
     * 从高德区域广播里取城市名。
     *
     * 高德发的 广播带：`EXTRA_PROVICE`（省）、`EXTRA_CITY`（市）、
     * `EXTRA_AREA_NAME`（区）； 另带 `EXTRA_DISTRICT`（区县）与国家。
     * 这里取「市」，并按高德自己的规则去后缀。
     *
     * @param extras 广播的 extras（`Bundle.getExtras()`）
     */
    fun cityFromAreaExtras(extras: android.os.Bundle?): String? =
        AmapAreaInfo.pickCity(areaFromExtras(extras))

    /**
     * 从高德区域广播里取完整区域信息（省/市/区/区划码）。
     *
     * @param extras 广播的 extras（`Bundle.getExtras()`）
     */
    fun areaFromExtras(extras: android.os.Bundle?): AmapAreaInfo.Area? =
        AmapAreaInfo.parseArea(AmapAreaInfo.bundleToMap(extras))
}
