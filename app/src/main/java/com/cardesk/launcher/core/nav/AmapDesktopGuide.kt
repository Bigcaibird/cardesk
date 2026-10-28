package com.cardesk.launcher.core.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import com.cardesk.launcher.core.AmapAreaInfo
import com.cardesk.launcher.core.AmapCitySource
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 高德「桌面导航引导」数据源。
 *
 * | 行为 |位置 |
 * |---|---|
 * | 收 `AUTONAVI_STANDARD_BROADCAST_SEND` | |
 * | 导航状态 | |
 * | 车道线 | |
 * | 引导数据 | |
 * | 12 秒没有新数据就收起卡片 | |
 * | 主动向高德索要数据 | |
 *
 * ## 与唯一差异
 *
 * `requestGuideData` 里**发了两次**广播（先 `sendBroadcastAsUser`，再无条件
 * `sendBroadcast`——那是反编译后能看出的重复调用，功能上只是多发一次。
 * 我们只发一次（优先 `sendBroadcastAsUser`），不改变高德侧看到的内容。
 */
class AmapDesktopGuide(context: Context, private val listener: Listener) {

    /** 一条导航引导数据（`AmapDesktopGuide.Guide`）。 */
    data class Guide(
        /** 转向图标编码。 */
        val icon: Int,
        /** 下一段还有多远（米）。 */
        val segmentMeters: Int,
        /** 当前道路名。 */
        val currentRoad: String,
        /** 下一条道路名。 */
        val nextRoad: String,
        /** 全程剩余时间（秒）。 */
        val remainSeconds: Int,
        /** 全程剩余距离（米）。 */
        val remainMeters: Int,
        /** 原始车道线数据（`EXTRA_DRIVE_WAY`），可能为空。 */
        val lanes: String,
    )

    /** 数据回调。 */
    interface Listener {
        /** 有一条新的引导数据。 */
        fun onGuide(guide: Guide)

        /** 收到（可能不带引导数据的）车道线数据。 */
        fun onLanes(raw: String)

        /** 导航结束 / 超时，卡片要收起。 */
        fun onStopped()

        /**
         * 高德广播了当前城市。
         *
         * 天气链路在系统定位拿不到位置时靠这个城市换算经纬度（见 `core/AmapCitySource`），
         * 所以城市一变就该重跑一轮天气。
         */
        fun onCityChanged(city: String)
    }

    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val main = Handler(Looper.getMainLooper())

    private var registered = false
    private var navigating = false
    private var lastGuideAt = 0L

    /** 最近一次的车道线原文（引导数据里没带车道线时用它兜底）。 */
    private var lastLanes: String? = null

    /**
     * 上一次记过 INFO 的「导航状态」值。
     *
     * 高德**空闲时也会每 10 秒推一次状态**（实测 `state=40`），
     * 每次都记 INFO 会把车机日志刷爆（是打到 logcat，不落盘）。
     * 这里只在**状态变化**时记 INFO，重复的降 DEBUG。
     */
    private var lastLoggedState: Int? = null

    /**
     * 上一次记过 INFO 的「无效引导」指纹（`keyType|icon|seg`）+ 时间。
     *
     * 高德空闲时**每秒**推一帧引导（`icon=-1 seg=-1`），实测 35 分钟就能刷出 1600+ 行。
     * 把它打进 logcat（不落盘），我们 `Logx.i` 是**落盘**的，直接照抄会把车机存储吃满。
     * 所以：指纹变了（或隔了 30 秒）记 INFO，重复的降 DEBUG。
     */
    private var lastInvalidKey: String? = null
    private var lastInvalidAt = 0L

    /** 上一次记过 INFO 的「有效引导」时间（心跳节流）。 */
    private var lastValidAt = 0L

    private val staleCheck = Runnable {
        if (!navigating) return@Runnable
        if (System.currentTimeMillis() - lastGuideAt < STALE_MS) return@Runnable
        Logx.i(LogTags.NAV_UI, "导航引导超时收起", "staleMs" to STALE_MS)
        stopGuide()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handle(intent)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /** 注册广播接收器并主动索要一次数据。 */
    fun start() {
        if (registered) return
        val filter = IntentFilter(ACTION_SEND)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                // 用字面量 2 = RECEIVER_EXPORTED（高德是别的应用，必须 EXPORTED）
                app.registerReceiver(receiver, filter, 2)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "注册高德导航广播失败", it)
        }.isSuccess
        registered = ok
        Logx.i(LogTags.NAV_UI, "启动导航引导数据源", "registered" to ok, "action" to ACTION_SEND)
        if (ok) requestGuideData()
    }

    /** 注销广播接收器。 */
    fun stop() {
        main.removeCallbacks(staleCheck)
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
                .onFailure { Logx.w(LogTags.NAV_UI, "注销高德导航广播失败", it) }
        }
        registered = false
        navigating = false
        lastLanes = null
        Logx.i(LogTags.NAV_UI, "停止导航引导数据源")
    }

    // ------------------------------------------------------------------ 广播处理

    private fun handle(intent: Intent?) {
        if (intent == null || ACTION_SEND != intent.action) return
        when (val type = intent.getIntExtra(EXTRA_KEY_TYPE, -1)) {
            KEY_STATE -> handleState(intent.getIntExtra(EXTRA_STATE, -1))
            KEY_DRIVE_WAY, KEY_DRIVE_WAY_YH_A, KEY_DRIVE_WAY_YH_B -> handleLanes(intent.getStringExtra(EXTRA_DRIVE_WAY))
            KEY_GUIDE, KEY_GUIDE_EXT, KEY_GUIDE_YH -> handleGuide(intent, type)
            // 区域信息：高德把自己的当前城市广播出来（`10077`）。
            //
            // 高德车机版自己就用这条做城市兜底
            // 我们收下来存进 [AmapCitySource]，
            // 天气链路在系统定位拿不到位置时用它换算经纬度。
            //
            // 10077 是另一种回包形态（多带区县与国家），同样带城市名，两个都收。
            KEY_AREA_INFO, KEY_AREA_INFO_ALT -> handleAreaInfo(intent)
            else -> Logx.d(LogTags.NAV_UI, "忽略高德广播", "keyType" to type)
        }
    }

    /**
     * 高德的区域信息广播（`10077`）。
     *
     * 带 `EXTRA_PROVICE`（省）、`EXTRA_CITY`（市）、`EXTRA_AREA_NAME`/`EXTRA_DISTRICT`（区）。
     */
    private fun handleAreaInfo(intent: Intent) {
        val area = AmapCitySource.areaFromExtras(intent.extras)
        val city = AmapAreaInfo.pickCity(area)
        if (city.isNullOrBlank()) {
            Logx.d(LogTags.NAV_UI, "高德区域广播没有城市名", "keyType" to intent.getIntExtra(EXTRA_KEY_TYPE, -1))
            return
        }
        // 写进内存 + 落盘（高德只在收到车机区域信息时广播一次，错过就没了）
        AmapCitySource.recordArea(area)
        Logx.i(
            LogTags.NAV_UI,
            "收到高德区域信息",
            "city" to city,
            "province" to (area?.province ?: ""),
            "areaCode" to (area?.areaCode ?: ""),
            "keyType" to intent.getIntExtra(EXTRA_KEY_TYPE, -1),
        )
        // 城市变了 → 让天气链路重跑一轮（系统定位不可用时它就是我们的坐标来源）。
        listener.onCityChanged(city)
    }

    /**
     * 导航状态。
     *
     * - 8（导航中）/ 10（模拟导航）→ 开始索要数据
     * - 2 / 9 / 12 / 19 → 收起卡片
     */
    private fun handleState(state: Int) {
        // 高德空闲时每 10 秒推一次同样的状态；只在**变化**时记 INFO，重复的降 DEBUG。
        // （是打进 logcat 不落盘；我们 Logx.i 落盘，照抄会把车机存储吃满。）
        if (lastLoggedState == state) {
            Logx.d(LogTags.NAV_UI, "高德导航状态（未变化）", "state" to state)
        } else {
            lastLoggedState = state
            Logx.i(LogTags.NAV_UI, "高德导航状态", "state" to state)
        }
        if (state == STATE_NAVIGATING || state == STATE_SIMULATING) {
            navigating = true
            requestGuideData()
        } else if (state == STATE_IDLE_1 || state == STATE_IDLE_2 || state == STATE_IDLE_3 || state == STATE_IDLE_4) {
            stopGuide()
        }
    }

    /**
     * 车道线。
     *
     * 空串直接忽略（`TextUtils.isEmpty` 分支）。
     */
    private fun handleLanes(raw: String?) {
        if (raw.isNullOrEmpty()) {
            Logx.d(LogTags.NAV_UI, "车道线数据为空，忽略")
            return
        }
        Logx.i(
            LogTags.NAV_UI,
            "收到车道线数据",
            "len" to raw.length,
            "sample" to sample(raw),
        )
        lastLanes = raw
        listener.onLanes(raw)
    }

    /**
     * 引导数据。
     *
     * 无效（`icon <= 0` / 分段距离 < 0 / 当前与下一条道路名都空）就丢弃。
     */
    private fun handleGuide(intent: Intent, type: Int) {
        val guide = Guide(
            icon = intent.getIntExtra(EXTRA_ICON, -1),
            segmentMeters = intent.getIntExtra(EXTRA_SEG_REMAIN_DIS, -1),
            currentRoad = intent.getStringExtra(EXTRA_CUR_ROAD_NAME).orEmpty(),
            nextRoad = intent.getStringExtra(EXTRA_NEXT_ROAD_NAME).orEmpty(),
            remainSeconds = intent.getIntExtra(EXTRA_ROUTE_REMAIN_TIME, 0),
            remainMeters = intent.getIntExtra(EXTRA_ROUTE_REMAIN_DIS, 0),
            lanes = intent.getStringExtra(EXTRA_DRIVE_WAY).orEmpty(),
        )
        if (!NavGuideText.isValidGuide(guide.icon, guide.segmentMeters, guide.currentRoad, guide.nextRoad)) {
            // 高德空闲时**每秒**推一帧无效引导（icon=-1 seg=-1），
            // 每次都记 INFO 会把落盘日志刷爆（实测 35 分钟 1600+ 行）。
            // 指纹变了 / 隔了 30 秒才记 INFO，重复的降 DEBUG。
            val key = "$type|${guide.icon}|${guide.segmentMeters}"
            val now = System.currentTimeMillis()
            if (key != lastInvalidKey || now - lastInvalidAt > INVALID_LOG_WINDOW_MS) {
                lastInvalidKey = key
                lastInvalidAt = now
                Logx.i(
                    LogTags.NAV_UI,
                    "丢弃无效的导航引导数据",
                    "keyType" to type,
                    "icon" to guide.icon,
                    "seg" to guide.segmentMeters,
                )
            } else {
                Logx.d(
                    LogTags.NAV_UI,
                    "丢弃无效的导航引导数据（重复）",
                    "keyType" to type,
                    "icon" to guide.icon,
                    "seg" to guide.segmentMeters,
                )
            }
            return
        }
        navigating = true
        lastGuideAt = System.currentTimeMillis()
        main.removeCallbacks(staleCheck)
        main.postDelayed(staleCheck, STALE_MS)
        if (guide.lanes.isNotEmpty()) lastLanes = guide.lanes
        // 导航中每秒一帧，INFO 按心跳节流（每 10 秒一条），中间的降 DEBUG。
        val now = System.currentTimeMillis()
        val fields = arrayOf(
            "keyType" to type,
            "icon" to guide.icon,
            "seg" to guide.segmentMeters,
            "remainM" to guide.remainMeters,
            "remainS" to guide.remainSeconds,
            "road" to guide.nextRoad.ifEmpty { guide.currentRoad },
            "lanesLen" to guide.lanes.length,
        )
        if (now - lastValidAt >= VALID_HEARTBEAT_MS) {
            lastValidAt = now
            Logx.i(LogTags.NAV_UI, "收到导航引导数据", *fields)
        } else {
            Logx.d(LogTags.NAV_UI, "收到导航引导数据（心跳内）", *fields)
        }
        listener.onGuide(guide)
    }

    /** 收起卡片。 */
    private fun stopGuide() {
        main.removeCallbacks(staleCheck)
        if (!navigating) return
        navigating = false
        lastLanes = null
        // 下一段导航的状态要重新记 INFO（状态值可能和上次一样）
        lastLoggedState = null
        Logx.i(LogTags.NAV_UI, "收起导航引导卡片")
        listener.onStopped()
    }

    // ------------------------------------------------------------------ 索要数据

    /**
     * 向高德索要引导数据。
     *
     * （`KEY_REQUEST_HUD`），收件人是高德自己的
     * `AmapAutoBroadcastReceiver`。
     */
    fun requestGuideData() {
        val targets = wakeTargets()
        Logx.i(LogTags.NAV_UI, "请求高德引导数据", "targets" to targets.joinToString(","))
        for (pkg in targets) {
            val intent = Intent(ACTION_RECV)
            intent.setPackage(pkg)
            intent.setClassName(pkg, AMAP_RECEIVER)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(EXTRA_KEY_TYPE, KEY_REQUEST_HUD)
            val ok = runCatching { sendAsUser(intent) }
                .onFailure { Logx.w(LogTags.NAV_UI, "请求高德引导数据失败", it, "pkg" to pkg) }
                .isSuccess
            if (!ok) return
        }
    }

    /**
     * 尽量用 `sendBroadcastAsUser` 发（车机多用户场景下高德在当前用户下）。
     *
     * 是反射调用，拿不到就退回普通 `sendBroadcast`。
     */
    private fun sendAsUser(intent: Intent): Boolean {
        val user = runCatching { Process.myUserHandle() }.getOrNull()
        if (user != null) {
            val sent = runCatching {
                Context::class.java
                    .getMethod("sendBroadcastAsUser", Intent::class.java, UserHandle::class.java)
                    .invoke(app, intent, user)
                true
            }.isSuccess
            if (sent) return true
        }
        app.sendBroadcast(intent)
        return true
    }

    /**
     * 要唤醒的高德包名。
     *
     * 逻辑：候选 = [高德主包, 高德 YH 包, 设置里配的额外高德包]；
     * 若画中画当前承载的应用是其中之一，就只唤醒它；否则唤醒第一个。
     */
    fun wakeTargets(): List<String> {
        val candidates = ArrayList<String>(3)
        addPackage(candidates, AMAP_PKG)
        addPackage(candidates, AMAP_PKG_EXTRA)
        addPackage(candidates, prefs.extraAmapPackage)
        if (candidates.size <= 1) return candidates
        for (p in listOf(prefs.pipApp, prefs.pip2App)) {
            if (p.isNotEmpty() && candidates.contains(p)) return listOf(p)
        }
        return listOf(candidates[0])
    }

    private fun addPackage(list: MutableList<String>, pkg: String?) {
        if (pkg == null) return
        val trimmed = pkg.trim()
        if (trimmed.isEmpty() || list.contains(trimmed)) return
        list.add(trimmed)
    }

    /** 最近一次的车道线原文（引导数据没带时用它兜底）。 */
    fun lastLanes(): String? = lastLanes

    /** 当前是否处于「导航中」。 */
    fun isNavigating(): Boolean = navigating

    private fun sample(text: String): String {
        val flat = text.replace('\n', ' ')
        return if (flat.length <= 160) flat else flat.substring(0, 160) + "…"
    }

    companion object {
        /** 高德发出来的广播 action（`AmapDesktopGuide.ACTION_SEND`）。 */
        const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"

        /** 我们发给高德的 action（`AmapDesktopGuide.ACTION_RECV`）。 */
        const val ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"

        /** 高德广播接收组件（`AmapDesktopGuide.AMAP_RECEIVER`）。 */
        const val AMAP_RECEIVER = "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"

        /** 高德主包。 */
        const val AMAP_PKG = "com.autonavi.amapauto"

        /** 高德 YH 包。 */
        const val AMAP_PKG_EXTRA = "com.autonavi.amapautp.yh"

        /** 消息类型键（`AmapDesktopGuide.KEY_TYPE`）。 */
        const val EXTRA_KEY_TYPE = "KEY_TYPE"

        /** 导航状态键。 */
        const val EXTRA_STATE = "EXTRA_STATE"

        /** 车道线数据键（`AmapDesktopGuide.EXTRA_DRIVE_WAY`）。 */
        const val EXTRA_DRIVE_WAY = "EXTRA_DRIVE_WAY"

        /** 引导数据里的转向图标键。 */
        const val EXTRA_ICON = "ICON"

        /** 引导数据里的分段剩余距离键。 */
        const val EXTRA_SEG_REMAIN_DIS = "SEG_REMAIN_DIS"

        /** 引导数据里的当前道路名键。 */
        const val EXTRA_CUR_ROAD_NAME = "CUR_ROAD_NAME"

        /** 引导数据里的下一条道路名键。 */
        const val EXTRA_NEXT_ROAD_NAME = "NEXT_ROAD_NAME"

        /** 引导数据里的全程剩余时间键。 */
        const val EXTRA_ROUTE_REMAIN_TIME = "ROUTE_REMAIN_TIME"

        /** 引导数据里的全程剩余距离键。 */
        const val EXTRA_ROUTE_REMAIN_DIS = "ROUTE_REMAIN_DIS"

        /** 导航状态：导航中。 */
        const val STATE_NAVIGATING = 8

        /** 导航状态：模拟导航。 */
        const val STATE_SIMULATING = 10

        /** 导航状态：空闲（2）。 */
        const val STATE_IDLE_1 = 2

        /** 导航状态：空闲（9）。 */
        const val STATE_IDLE_2 = 9

        /** 导航状态：空闲（12）。 */
        const val STATE_IDLE_3 = 12

        /** 导航状态：空闲（19）。 */
        const val STATE_IDLE_4 = 19

        /** 引导数据（`AmapDesktopGuide.KEY_GUIDE`）。 */
        const val KEY_GUIDE = 10001

        /** 引导数据扩展（`AmapDesktopGuide.KEY_GUIDE_EXT`）。 */
        const val KEY_GUIDE_EXT = 10003

        /** 引导数据 YH 版（`AmapDesktopGuide.KEY_GUIDE_YH`）。 */
        const val KEY_GUIDE_YH = 12205

        /**
         * 区域信息（高德把自己的当前城市广播出来）。
         *
         * 高德车机版用它做城市兜底，
         * 我们收下来给天气链路用（见 `core/AmapCitySource`）。
         */
        const val KEY_AREA_INFO = 10030

        /**
         * 区域信息（另一种回包形态，多带区县与国家）。
         *
         * 高德 的 `case 10077` → `new v5(intent)`，
         * 回包 带 `EXTRA_PROVICE` / `EXTRA_CITY` /
         * `EXTRA_DISTRICT` / `EXTRA_AREA_CODE` /。
         */
        const val KEY_AREA_INFO_ALT = 10077

        /** 导航状态（`AmapDesktopGuide.KEY_STATE`）。 */
        const val KEY_STATE = 10019

        /** 车道线（`AmapDesktopGuide.KEY_DRIVE_WAY`）。 */
        const val KEY_DRIVE_WAY = 13012

        /** 车道线 YH A（`AmapDesktopGuide.KEY_DRIVE_WAY_YH_A`）。 */
        const val KEY_DRIVE_WAY_YH_A = 13021

        /** 车道线 YH B（`AmapDesktopGuide.KEY_DRIVE_WAY_YH_B`）。 */
        const val KEY_DRIVE_WAY_YH_B = 13022

        /** 索要引导数据（`AmapDesktopGuide.KEY_REQUEST_HUD`）。 */
        const val KEY_REQUEST_HUD = 10031

        /** 多久没有新数据就收起卡片（`AmapDesktopGuide.STALE_MS = 12000`）。 */
        const val STALE_MS = 12000L

        /**
         * 「无效引导」记 INFO 的最小间隔（毫秒）。
         *
         * 高德空闲时每秒推一帧无效引导；把它打进 logcat（不落盘）
         * 我们落盘就必须节流，否则车机存储会被刷满。
         */
        const val INVALID_LOG_WINDOW_MS = 30_000L

        /** 「有效引导」记 INFO 的心跳间隔（毫秒）。 */
        const val VALID_HEARTBEAT_MS = 10_000L
    }
}
