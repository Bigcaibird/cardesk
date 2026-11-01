package com.cardesk.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 原厂设置目录（第九十五轮，移植自 UNI 的 `SystemSettingsRepository` 语义化入口思路）。
 *
 * 桌面以前的「默认设置」页只会打开 `Settings.ACTION_SETTINGS`（Android 系统设置），
 * 但车机上真正好用的是**原厂设置**（`com.ecarx.settings`）——驾驶模式、蓝牙、热点、
 * 显示/声音这些用户高频入口都在那里，而它没有统一 action 可依赖。
 *
 * 这里的做法：
 * 1. 维护一张**语义化入口表**（[Spec]）：每项给出稳定 id、兜底中文名、候选 action；
 * 2. 用 `PackageManager.queryIntentActivities` 把 action 解析成真实 Activity，
 *    解析到才出现在列表里（解析不到就灰掉/不显示，绝不猜组件名）；
 * 3. 原厂专有入口（驾驶模式）按**已确认的组件名**探测，找不到就静默跳过；
 * 4. 原车设置包存在时，补一条「原厂设置」总入口（launch intent）。
 *
 * 扫描要过一遍 PackageManager（真机约几百毫秒），所以 [warmAsync] 在后台线程做，
 * UI 先渲染「正在扫描…」，扫描完再刷新这一页；[warmNow] 供测试/同步场景使用。
 */
object SystemSettingsCatalog {

    /** 一条可打开的原厂设置入口。 */
    data class Entry(
        val id: String,
        val label: String,
        val source: String,
        val intent: Intent,
    )

    /** 语义化入口定义：id 稳定，[actions] 按优先级尝试，[component] 用于原厂专有页。 */
    data class Spec(
        val id: String,
        val label: String,
        val actions: List<String> = emptyList(),
        val component: ComponentName? = null,
    )

    /** 目录最多保留的条数（防 OEM 包把列表撑爆）。 */
    private const val MAX_ENTRIES = 40

    /** 缓存有效期：10 分钟（车机设置页不会频繁变）。 */
    private const val REFRESH_MS = 10 * 60 * 1000L

    private const val SOURCE_OEM = "原厂设置"
    private const val SOURCE_ANDROID = "Android 设置"

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    private var builtAtMs = 0L

    @Volatile
    private var building = false

    /**
     * 原厂设置入口表（顺序即展示顺序）。
     *
     * 只收「车机上真的可能存在的页」；Android 9 上不存在对应 Action 的项解析不到，
     * 自然会被跳过，所以这张表可以跨版本共用。
     */
    fun specs(): List<Spec> = listOf(
        Spec("oem_settings", "原厂设置", component = ComponentName("com.ecarx.settings", "com.ecarx.settings.Settings")),
        Spec("drive_mode", "驾驶模式", component = ComponentName("com.ecarx.settings", "com.ecarx.settings.drivemode.DriveModeActivity")),
        Spec("settings", "系统设置", listOf(Settings.ACTION_SETTINGS)),
        Spec("wireless", "无线和网络", listOf(Settings.ACTION_WIRELESS_SETTINGS)),
        Spec("wifi", "WLAN", listOf(Settings.ACTION_WIFI_SETTINGS)),
        Spec("bluetooth", "蓝牙", listOf(Settings.ACTION_BLUETOOTH_SETTINGS)),
        Spec("hotspot", "个人热点", listOf("android.settings.TETHER_SETTINGS")),
        Spec("display", "显示", listOf(Settings.ACTION_DISPLAY_SETTINGS)),
        Spec("sound", "声音", listOf(Settings.ACTION_SOUND_SETTINGS)),
        Spec(
            "language",
            "语言与输入法",
            listOf(Settings.ACTION_LOCALE_SETTINGS, Settings.ACTION_INPUT_METHOD_SETTINGS),
        ),
        Spec("datetime", "日期与时间", listOf(Settings.ACTION_DATE_SETTINGS)),
        Spec(
            "apps",
            "应用管理",
            listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS),
        ),
        Spec("storage", "存储", listOf("android.settings.INTERNAL_STORAGE_SETTINGS")),
        Spec("datausage", "流量使用", listOf("android.settings.DATA_USAGE_SETTINGS")),
        Spec("location", "位置信息", listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
        Spec("security", "安全", listOf(Settings.ACTION_SECURITY_SETTINGS)),
        Spec("battery", "电池", listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
        Spec("accessibility", "无障碍", listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        Spec(
            "home",
            "默认桌面",
            listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        ),
        Spec("about", "关于车机", listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)),
    )

    /** 已扫描到的入口（可能为空：还没扫过，或这台车没有任何原厂设置页）。 */
    fun entries(): List<Entry> = entries

    /** 是否已经扫描过（UI 用来区分「空 = 没扫」与「空 = 真没有」）。 */
    fun isWarmed(): Boolean = builtAtMs > 0L

    /**
     * 后台预热：同一时间只跑一次；缓存未过期时直接返回。
     *
     * 不阻塞调用方（设置页打开时调用，先画「正在扫描…」）。
     */
    fun warmAsync(context: Context) {
        if (building) return
        val now = SystemClock.elapsedRealtime()
        if (builtAtMs > 0L && now - builtAtMs < REFRESH_MS) return
        building = true
        val app = context.applicationContext
        val thread = Thread({
            try {
                refresh(app)
            } catch (t: Throwable) {
                Logx.w(LogTags.SYS, "原厂设置目录扫描失败", t)
            } finally {
                building = false
            }
        }, "sys-settings-catalog")
        thread.isDaemon = true
        thread.start()
    }

    /** 同步扫描（测试 / 兜底用），返回扫描结果。 */
    fun warmNow(context: Context): List<Entry> {
        refresh(context.applicationContext)
        return entries
    }

    /** 诊断用：清空缓存（设置里改了包状态后可重扫）。 */
    fun invalidate() {
        entries = emptyList()
        builtAtMs = 0L
    }

    /** 打开一条入口；返回 false 表示该入口当前已不可用。 */
    fun open(context: Context, entry: Entry): Boolean = runCatching {
        context.startActivity(Intent(entry.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse { t ->
        Logx.w(LogTags.SYS, "打开原厂设置失败", t, "id" to entry.id, "label" to entry.label)
        false
    }

    /** 按 id 打开（深链 / 任务动作可能只存 id）。 */
    fun openById(context: Context, id: String): Boolean {
        val entry = entries.firstOrNull { it.id == id } ?: return false
        return open(context, entry)
    }

    /**
     * 驾驶模式胶囊专用入口（第九十五轮补丁）：直接点原厂「驾驶模式」页。
     *
     * 不依赖 [entries] 的异步扫描（状态栏胶囊点击发生在主线程，扫描要做一堆
     * `queryIntentActivities`）：这里只查一个已知组件
     * `com.ecarx.settings/.drivemode.DriveModeActivity`。
     *
     * @return true=已拉起；false=这台车没有独立驾驶模式页，由调用方兜底。
     */
    fun openDriveMode(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getActivityInfo(DRIVE_MODE_COMPONENT, 0)
        if (!info.exported || !info.enabled) return false
        context.startActivity(
            Intent().setComponent(DRIVE_MODE_COMPONENT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Logx.event(LogTags.SYS, "打开驾驶模式页")
        true
    }.getOrElse {
        Logx.w(LogTags.SYS, "打开驾驶模式页失败", it)
        false
    }

    private fun refresh(context: Context) {
        val pm = context.packageManager
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (spec in specs()) {
            if (out.size >= MAX_ENTRIES) break
            val resolved = resolveSpec(pm, spec, seen) ?: continue
            val activity = resolved
            val key = activity.packageName + "/" + activity.name
            if (!seen.add(key)) continue
            out += Entry(
                id = spec.id,
                label = spec.label,
                source = sourceLabel(pm, activity),
                intent = Intent().setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        // 原车设置包存在时补一条总入口（launch intent 比猜组件稳）。
        val oemLaunch = runCatching { pm.getLaunchIntentForPackage(OEM_PACKAGE) }.getOrNull()
        if (oemLaunch != null) {
            val key = OEM_PACKAGE + "/launch"
            if (seen.add(key)) {
                out += Entry(
                    id = "oem_launch",
                    label = "原厂设置主页",
                    source = SOURCE_OEM,
                    intent = Intent(oemLaunch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        entries = out
        builtAtMs = SystemClock.elapsedRealtime()
        Logx.i(
            LogTags.SYS,
            "原厂设置目录已扫描",
            "count" to out.size,
            "ids" to out.joinToString(",") { it.id },
        )
    }

    /** 解析一条 Spec：先试显式组件，再按 action 解析；解析不到返回 null。 */
    private fun resolveSpec(pm: PackageManager, spec: Spec, seen: Set<String>): ActivityInfo? {
        val component = spec.component
        if (component != null) {
            val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull()
            if (info != null && usable(info)) return info
        }
        for (action in spec.actions) {
            val candidates = runCatching {
                pm.queryIntentActivities(Intent(action), 0)
            }.getOrNull().orEmpty()
            for (resolved in candidates) {
                val info = resolved.activityInfo ?: continue
                if (!usable(info)) continue
                val key = info.packageName + "/" + info.name
                if (key in seen) continue
                return info
            }
        }
        return null
    }

    private fun usable(info: ActivityInfo): Boolean {
        if (!info.exported || !info.enabled) return false
        // 真机上 activity 一定有 applicationInfo；测试注入的条目可能没有 —— 只校验 activity 本身。
        val app = info.applicationInfo ?: return true
        return app.enabled
    }

    private fun sourceLabel(pm: PackageManager, info: ActivityInfo): String {
        val pkg = info.packageName
        return when {
            pkg == OEM_PACKAGE || pkg.contains("ecarx.settings") -> SOURCE_OEM
            pkg == ANDROID_SETTINGS_PACKAGE || pkg.contains("android.settings") -> SOURCE_ANDROID
            else -> runCatching {
                info.applicationInfo.loadLabel(pm)?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: SOURCE_ANDROID
        }
    }

    private const val OEM_PACKAGE = "com.ecarx.settings"

    /** 原厂「驾驶模式」页（`com.ecarx.settings.drivemode.DriveModeActivity`）。 */
    private val DRIVE_MODE_COMPONENT: ComponentName =
        ComponentName(OEM_PACKAGE, "com.ecarx.settings.drivemode.DriveModeActivity")
    private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
}
