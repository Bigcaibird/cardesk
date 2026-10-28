package com.cardesk.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * **向高德索要「区域信息」（省 / 市 / 区）的协议封装**。
 *
 * ## 为什么需要它（第三十一轮的关键发现）
 *
 * 真机（领克 dcy11 / Android 9）实测：定位 provider 只有 `passive,gps`，两个都没有缓存位置，
 * GNSS 在地库/室内也等不到 fix → 「拿位置 → 查天气」第一步就断了。
 *
 * 但**车机自己知道自己在哪个城市**：车机 HMI（`ecarx.naviservice`）有区域信息，
 * 高德就是向它索要的（高德 dex 里的请求信封写着 `"requestAuthor":"ecarx.naviservice"`）。
 *
 * 高德的实现（只读逆向，见下表）：
 *
 * | 步骤 | 高德实现 | 位置 |
 * |---|---|---|
 * | 客户端 → 高德 | `AmapAutoBroadcastReceiver.onReceive` → `l30.g().b(333).k(intent)` | |
 * | 按 KEY_TYPE 分发 | `case 10029: new v5(intent)` | |
 * | 构造请求 | `new ReqGetAreaInfoModel(lat, lon)` | |
 * | 发给车机 | `AndroidProtocolExe.getAreaInfoAction(...)` | |
 * | 车机回包 → 转广播 | → `Intent`（省/市/区/区划码/国家） | |
 * | 全局广播出去 | `sendBroadcast`（action `AUTONAVI_STANDARD_BROADCAST_SEND`，**无 setPackage**） | |
 *
 * 于是我们就能**主动问高德要城市**：往高德那个 **exported=true** 的广播接收组件
 * （里 `exported=true`，action 含 `AUTONAVI_STANDARD_BROADCAST_RECV`）
 * 发一条 的广播，高德就会去向车机索要区域信息，
 * 车机回包后高德把城市**全局广播**出来，我们收下。
 *
 * ## 与关系
 *
 * （`WeatherDesk`）**没有**这一层 —— 它只读系统定位。
 * 我们补这一层，是因为真机上那条路走不通（同一个 provider 列表、同一个 null）。
 * 这属于「做不到但用户明确要天气」的补充，不是自造功能口径。
 *
 * ## 本文件只做协议封装
 *
 * 解析部分（[parseArea] / [pickCity]）是**纯逻辑**，便于 JVM 单测；
 * 发广播部分（[request]）依赖 Android 上下文。
 */
object AmapAreaInfo {

    // ------------------------------------------------------------------ 协议常量

    /** 高德发给客户端的广播 action（高德自己也是用这个 action 收）。 */
    const val ACTION_AMAP_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"

    /** 客户端发给高德的广播 action（请求引导数据用的就是这个）。 */
    const val ACTION_AMAP_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"

    /** 高德的广播接收组件（**exported=true**，任何应用都能发）。 */
    const val RECEIVER_CLASS = "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"

    /** 消息类型键（高德）。 */
    const val EXTRA_KEY_TYPE = "KEY_TYPE"

    /** 纬度键（高德）。 */
    const val EXTRA_LAT = "EXTRA_LAT"

    /** 经度键（高德）。 */
    const val EXTRA_LON = "EXTRA_LON"

    /** 城市名键（高德 = `"CITY_NAME"`）。 */
    const val EXTRA_CITY = "CITY_NAME"

    /** 省份键（高德 = `"PROVINCE_NAME"`）。 */
    const val EXTRA_PROVINCE = "PROVINCE_NAME"

    /** 区名键（高德）。 */
    const val EXTRA_AREA_NAME = "AREA_NAME"

    /** 区划码键（高德）。 */
    const val EXTRA_AREA_CODE = "AREA_CODE"

    /** 区县键（高德 = `"DISTRICT_NAME"`）。 */
    const val EXTRA_DISTRICT = "DISTRICT_NAME"

    /** 国家键（高德）。 */
    const val EXTRA_COUNTRY = "COUNTRY_NAME"

    /** **索要区域信息**的协议号（高德 的 `case 10029` → `new v5(intent)`）。 */
    const val KEY_TYPE_REQUEST = 10029

    /**
     * **区域信息响应**的协议号。
     *
     * 高德 里 `if (10029 == h()) { t(10030); }` —— 请求用的是 10029，
     * 但回包时会把协议号改成 **10030**（把 写进 `KEY_TYPE`）。
     * 高德自己的天气模块监听的就是 10030。
     */
    const val KEY_TYPE_RESPONSE = 10030

    /**
     * 另一种区域信息响应协议号（高德 的 `case 10077`）。
     *
     * 10077 的回包比 10030 多带「区县」和「国家」（的 `if (10077 == h())` 分支），
     * 同样带城市名。两个都收，谁先来用谁。
     */
    const val KEY_TYPE_RESPONSE_ALT = 10077

    // ------------------------------------------------------------------ 纯逻辑

    /**
     * 一次区域信息（省 / 市 / 区县 / 区划码）。
     *
     * @param province 省份（可能为 null）
     * @param city 城市（可能为 null）
     * @param district 区县（可能为 null）
     * @param areaCode 区划码（可能为 null）
     */
    data class Area(
        val province: String?,
        val city: String?,
        val district: String?,
        val areaCode: String?,
    )

    /**
     * 从广播 extra 里解析区域信息（纯逻辑）。
     *
     * @param values extra 键值（调用方把 `Bundle` 摊平成 Map 传进来）
     * @return 解析结果；城市与省份都没有时返回 null（说明这条广播不是区域信息）
     */
    fun parseArea(values: Map<String, String?>): Area? {
        val city = values[EXTRA_CITY]?.trim()?.takeIf { it.isNotEmpty() }
        val province = values[EXTRA_PROVINCE]?.trim()?.takeIf { it.isNotEmpty() }
        val district = (values[EXTRA_DISTRICT] ?: values[EXTRA_AREA_NAME])
            ?.trim()?.takeIf { it.isNotEmpty() }
        val areaCode = values[EXTRA_AREA_CODE]?.trim()?.takeIf { it.isNotEmpty() }
        if (city == null && province == null) return null
        return Area(province = province, city = city, district = district, areaCode = areaCode)
    }

    /**
     * 从区域信息里挑一个**能用来查天气的城市名**（纯逻辑）。
     *
     * 优先级：**市 → 区县 → 省**（天气要的是「市」这一级；
     * 只有市是「直辖市」时市名就是城市名，规范化交给 [WeatherCity.normalizeCityName]。
     *
     * @return 规范化后的城市名；挑不出来返回 null
     */
    fun pickCity(area: Area?): String? {
        if (area == null) return null
        for (raw in listOf(area.city, area.district, area.province)) {
            val normalized = WeatherCity.normalizeCityName(raw) ?: continue
            if (normalized.isNotEmpty()) return normalized
        }
        return null
    }

    // ------------------------------------------------------------------ 发请求

    /**
     * 向高德**索要一次区域信息**。
     *
     * 高德收到后会去向车机（`ecarx.naviservice`）要区域信息，
     * 车机回包后高德把城市以**全局广播**发出来，
     * 由 `core/nav/AmapDesktopGuide` 接收 → `AmapCitySource.broadcastCity`。
     *
     * @param context 上下文
     * @param pkg 高德包名（真车是 `com.autonavi.amapautp.yh`）
     * @param lat 已知纬度（没有就传 0 —— 高德会用车机自己的位置去要）
     * @param lon 已知经度（同上）
     * @return 广播是否发出成功
     */
    fun request(context: Context, pkg: String, lat: Double = 0.0, lon: Double = 0.0): Boolean {
        return try {
            val intent = Intent(ACTION_AMAP_RECV)
            intent.setComponent(ComponentName(pkg, RECEIVER_CLASS))
            // 高德没在运行时（stopped 状态）默认收不到广播 ——
            // 带上这个 flag 才能把高德顺带拉起来（显式广播本来就允许唤起被停的包）。
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            intent.putExtra(EXTRA_KEY_TYPE, KEY_TYPE_REQUEST)
            intent.putExtra(EXTRA_LAT, lat)
            intent.putExtra(EXTRA_LON, lon)
            context.sendBroadcast(intent)
            Logx.i(
                LogTags.WEATHER,
                "已向高德索要区域信息",
                "pkg" to pkg,
                "lat" to lat,
                "lon" to lon,
                "keyType" to KEY_TYPE_REQUEST,
            )
            true
        } catch (t: Throwable) {
            Logx.w(LogTags.WEATHER, "向高德索要区域信息失败", t, "pkg" to pkg)
            false
        }
    }

    /** 把广播的 `Bundle` 摊平成 Map（Android 依赖集中在这里，便于纯逻辑单测）。 */
    @Suppress("DEPRECATION")
    fun bundleToMap(extras: Bundle?): Map<String, String?> {
        if (extras == null) return emptyMap()
        val out = LinkedHashMap<String, String?>()
        for (key in extras.keySet()) {
            val value = runCatching { extras.get(key) }.getOrNull()
            out[key] = when (value) {
                null -> null
                is String -> value
                else -> value.toString()
            }
        }
        return out
    }
}
