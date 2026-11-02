package com.cardesk.launcher.core

import android.content.Context

/**
 * 轻量持久化（MMKV 配置层）。
 *
 * ## 键名约定
 *
 * 把设置存在 MMKV 里，键名就是功能契约
 *  / …。
 * 本类**键名与默认值**，这样两边互相读取不会把用户配置打回默认。
 *
 * 键名对照（出处）
 * - 日夜：
 * - 360 环影：
 * - 桌面布局字号：
 * - 按键增强：
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("cardesk", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 日夜模式

    /**
     * 日夜模式总开关（`daynight_enabled`，默认 **关闭**）。
     * 关闭时桌面固定用日间配色。
     */
    var dayNightEnabled: Boolean
        get() = flag("daynight_enabled", false)
        set(v) = setFlag("daynight_enabled", v)

    /**
     * 日夜模式来源（`daynight_mode`）
     * - 0 = 跟随高德（`MODE_FOLLOW_AMAP`，**默认**）
     * - 1 = 跟随时间（`MODE_FOLLOW_TIME`）
     * - 2 = 跟随光感（`MODE_FOLLOW_SENSOR`）
     */
    var dayNightMode: Int
        get() = intValue("daynight_mode", MODE_FOLLOW_AMAP).let { if (it == 1 || it == 2) it else MODE_FOLLOW_AMAP }
        set(v) = setIntValue("daynight_mode", if (v == 1 || v == 2) v else MODE_FOLLOW_AMAP)

    /** 白天开始时间（分钟，`daynight_day_start_min`，默认 06:00 = 360）。 */
    var dayStartMin: Int
        get() = intValue("daynight_day_start_min", DayNight.DEFAULT_DAY_START_MIN)
        set(value) = setIntValue("daynight_day_start_min", DayNight.clampMinute(value))

    /** 夜间开始时间（分钟，`daynight_night_start_min`，默认 18:30 = 1110）。 */
    var nightStartMin: Int
        get() = intValue("daynight_night_start_min", DayNight.DEFAULT_NIGHT_START_MIN)
        set(value) = setIntValue("daynight_night_start_min", DayNight.clampMinute(value))

    /** 额外的高德包名（`daynight_extra_amap_pkg`，可为空）。 */
    var extraAmapPackage: String
        get() = stringValue("daynight_extra_amap_pkg", "").trim()
        set(value) = setStringValue("daynight_extra_amap_pkg", value.trim())

    /** 上次实际应用的夜间态（`daynight_applied_night_ui`，null = 还没应用过）。 */
    fun lastAppliedNight(): Boolean? {
        val key = "daynight_applied_night_ui"
        if (!sp.contains(key)) return null
        return sp.getBoolean(key, true)
    }

    fun setLastAppliedNight(value: Boolean) {
        sp.edit().putBoolean("daynight_applied_night_ui", value).apply()
    }

    // ------------------------------------------------------------------ 桌面基础

    /**
     * 壁纸编号（9 张命名壁纸，0 起）。
     *
     * 持久化键是 `design_wallpaper_index`。
     *
     * 第五十七轮：默认那张（0）是**「紫晖雪山」**——风景类，不是纯色；
     * 目录在 [Wallpapers]，设置页「壁纸」页可以挑。
     */
    var wallpaper: Int
        get() = sp.getInt("design_wallpaper_index", Wallpapers.DEFAULT_INDEX)
        set(v) = sp.edit().putInt("design_wallpaper_index", v).apply()

    /** 0=自动探测 1=强制模拟 2=强制真车 */
    var vehicleSource: Int
        get() = sp.getInt("vehicle_source", 0)
        set(v) = sp.edit().putInt("vehicle_source", v).apply()

    /**
     * 自定义更新地址。
     *
     * 第六十九轮起**不再使用**：更新源固定为 GitHub Releases
     * （`github.com/Bigcaibird/cardesk`），检测由用户手动触发。
     * 键保留是为了老设备上的偏好数据不报错，将来要恢复「自定义源」直接能用。
     */
    var updateUrl: String
        get() = sp.getString("update_url", "") ?: ""
        set(value) { sp.edit().putString("update_url", value).apply() }

    /** 最近一次从桌面启动的应用包名（分屏/浮窗入口用）。 */
    var lastAppPkg: String
        get() = stringValue("last_app_pkg", "")
        set(value) = setStringValue("last_app_pkg", value)

    var lastPage: Int
        get() = sp.getInt("last_page", 0)
        set(v) = sp.edit().putInt("last_page", v).apply()

    /** 主题强调色，0 = 跟随日/夜配色。 */
    var accentColor: Int
        get() = intValue("accent_color", 0)
        set(value) = setIntValue("accent_color", value)

    // ------------------------------------------------------------------ 氛围灯（第七十轮）

    /**
     * 氛围灯总开关。
     *
     * 默认**关**：氛围灯的真实通道还没接（见 `core/ambient/AmbientLightApi.kt`），
     * 默认开着只会让人以为「开了没反应」。
     */
    var ambientEnabled: Boolean
        get() = sp.getBoolean("ambient_enabled", false)
        set(value) { sp.edit().putBoolean("ambient_enabled", value).apply() }

    /** 颜色模式（0=跟随低频 / 1=跟随频谱 / 2=固定色）。 */
    var ambientMode: Int
        get() = intValue("ambient_mode", 0)
        set(value) = setIntValue("ambient_mode", value.coerceIn(0, 2))

    /** 固定色的色相（0..360）。 */
    var ambientFixedHue: Int
        get() = intValue("ambient_fixed_hue", 30)
        set(value) = setIntValue("ambient_fixed_hue", value.coerceIn(0, 360))

    /** 灵敏度档位（0..100）。实际值见 [ambientSensitivityValue]。 */
    var ambientSensitivity: Int
        get() = intValue("ambient_sensitivity", 50)
        set(value) = setIntValue("ambient_sensitivity", value.coerceIn(0, 100))

    /**
     * 灵敏度档位 → 实际灵敏度（0.3..3.0）。
     *
     * 灵敏度是**曲线指数**：越大 → 弱音也亮（灯更跟手）。
     */
    fun ambientSensitivityValue(): Float {
        val v = ambientSensitivity.coerceIn(0, 100) / 100f
        return 0.3f + v * (3f - 0.3f)
    }

    /** 最低亮度（0..100）。 */
    var ambientMinBrightness: Int
        get() = intValue("ambient_min_brightness", 20)
        set(value) = setIntValue("ambient_min_brightness", value.coerceIn(0, 100))

    /** 最高亮度（0..100）。 */
    var ambientMaxBrightness: Int
        get() = intValue("ambient_max_brightness", 100)
        set(value) = setIntValue("ambient_max_brightness", value.coerceIn(0, 100))

    // ------------------------------------------------------------------ 氛围灯 V2 参数

    /**
     * 引擎选择：0 = **新版**（V2，感知色空间 + 二阶限速），1 = **旧版**（观感回退）。
     *
     * ## 为什么要在车上留一个开关
     *
     * 旧版链路是**实车验证过**的（灯确实会随音乐变色），
     * 但被评价为「太僵硬」。新版是照着方案与演示台重写的，
     * 两版的观感差别很大 —— 用户需要能在**同一台车、同一首歌**上直接对比，
     * 而不是靠回忆。
     *
     * 切换后立刻重建运动层并重置状态（不然会把旧版的色相状态带过来）。
     */
    var ambientEngine: Int
        get() = intValue("ambient_engine", ENGINE_V2)
        set(value) = setIntValue("ambient_engine", value.coerceIn(ENGINE_V2, ENGINE_LEGACY))

    /** 明度**上升**时间常数（毫秒）—— 鼓点来了多快跟上。 */
    var ambientAttackMs: Int
        get() = intValue("ambient_attack_ms", 45).coerceIn(10, 300)
        set(value) = setIntValue("ambient_attack_ms", value.coerceIn(10, 300))

    /** 明度**下降**时间常数（毫秒）—— 音乐停了多快暗下去。 */
    var ambientDecayMs: Int
        get() = intValue("ambient_decay_ms", 260).coerceIn(60, 1200)
        set(value) = setIntValue("ambient_decay_ms", value.coerceIn(60, 1200))

    /** 色相平滑的时间常数（毫秒）—— 越大越「黏」。 */
    var ambientHueTauMs: Int
        get() = intValue("ambient_hue_tau_ms", 260).coerceIn(60, 1200)
        set(value) = setIntValue("ambient_hue_tau_ms", value.coerceIn(60, 1200))

    /** 色相角速度上限（度/秒）—— 防大跨度变化时灯「甩」过去。 */
    var ambientHueMaxDeg: Int
        get() = intValue("ambient_hue_max_deg", 60).coerceIn(10, 180)
        set(value) = setIntValue("ambient_hue_max_deg", value.coerceIn(10, 180))

    /** 色相角加速度上限（度/秒²）—— 消除「一顿一顿」。 */
    var ambientHueMaxAccel: Int
        get() = intValue("ambient_hue_max_accel", 240).coerceIn(30, 800)
        set(value) = setIntValue("ambient_hue_max_accel", value.coerceIn(30, 800))

    /**
     * 每帧感知色差上限（ΔE00）—— **存的是 ×10 的整数**。
     *
     * 存整数而不是小数：`SharedPreferences` 存浮点要走 `putFloat`，
     * 而这个文件里的读值是**容错**的（`intValue` 会把各种类型都转成 int），
     * 存成整数更稳。取值 = 本字段 ÷ 10。
     */
    var ambientMaxDeltaETenths: Int
        get() = intValue("ambient_max_delta_e", 18).coerceIn(2, 60)
        set(value) = setIntValue("ambient_max_delta_e", value.coerceIn(2, 60))

    /** 每帧感知色差上限（ΔE00，0.2 ~ 6.0）。 */
    fun ambientMaxDeltaE(): Float = ambientMaxDeltaETenths / 10f

    /** 基础亮度（%）—— 呼吸底光，**不能是 0**（灯不能真灭）。 */
    var ambientFloor: Int
        get() = intValue("ambient_floor", 8).coerceIn(0, 30)
        set(value) = setIntValue("ambient_floor", value.coerceIn(0, 30))

    /**
     * 空闲呼吸幅度 —— **存的是 ×100 的整数**（0 ~ 0.40）。
     */
    var ambientBreathHundredths: Int
        get() = intValue("ambient_breath", 10).coerceIn(0, 40)
        set(value) = setIntValue("ambient_breath", value.coerceIn(0, 40))

    /** 空闲呼吸幅度（0 ~ 0.40）。 */
    fun ambientBreathValue(): Float = ambientBreathHundredths / 100f

    /**
     * 饱和度上限 —— **存的是 ×100 的整数**（0 ~ 1.00）。
     */
    var ambientSaturationHundredths: Int
        get() = intValue("ambient_saturation", 80).coerceIn(0, 100)
        set(value) = setIntValue("ambient_saturation", value.coerceIn(0, 100))

    /** 饱和度上限（0 ~ 1.00）。 */
    fun ambientSaturationValue(): Float = ambientSaturationHundredths / 100f

    // ------------------------------------------------------------------ 五彩（V2 色相覆盖）

    /**
     * 色相跨度（度，150..360）。
     *
     * 150° = 原窄带（青→蓝→紫→品红，第八十八轮的默认观感）；
     * 360° = 整圈彩虹。**默认 150**，所以不选彩虹预设时行为逐位不变。
     */
    var ambientHueSpanDeg: Int
        get() = intValue("ambient_hue_span_deg", 150).coerceIn(150, 360)
        set(value) = setIntValue("ambient_hue_span_deg", value.coerceIn(150, 360))

    /**
     * 谱质心展开窗口下沿 —— **存的是 ×100 的整数**（0 ~ 0.70）。
     *
     * 0 = 不展开（恒等映射）。感知层把每段归一化到自己的参考值附近后，
     * 真实音乐的谱质心其实只在 0.3~0.6 之间移动，不展开的话
     * 即使跨度拉到 360° 色相也只走 110°。
     */
    var ambientCentroidWindowLowHundredths: Int
        get() = intValue("ambient_centroid_low", 0).coerceIn(0, 70)
        set(value) = setIntValue("ambient_centroid_low", value.coerceIn(0, 70))

    /** 谱质心展开窗口下沿（0 ~ 0.70）。 */
    fun ambientCentroidWindowLow(): Float = ambientCentroidWindowLowHundredths / 100f

    /** 谱质心展开窗口跨度 —— **存的是 ×100 的整数**（0.10 ~ 1.00，100 = 不展开）。 */
    var ambientCentroidWindowSpanHundredths: Int
        get() = intValue("ambient_centroid_span", 100).coerceIn(10, 100)
        set(value) = setIntValue("ambient_centroid_span", value.coerceIn(10, 100))

    /** 谱质心展开窗口跨度（0.10 ~ 1.00）。 */
    fun ambientCentroidWindowSpan(): Float = ambientCentroidWindowSpanHundredths / 100f

    /** 彩度上限 —— **存的是 ×100 的整数**（0.10 ~ 0.40，默认 0.22）。 */
    var ambientMaxChromaHundredths: Int
        get() = intValue("ambient_max_chroma", 22).coerceIn(10, 40)
        set(value) = setIntValue("ambient_max_chroma", value.coerceIn(10, 40))

    /** 彩度上限（0.10 ~ 0.40）。 */
    fun ambientMaxChromaValue(): Float = ambientMaxChromaHundredths / 100f

    /**
     * 色相自转（度/秒，0 ~ 30）。
     *
     * 0 = 不转（默认）。非 0 时目标色相每帧叠一个连续相位 ——
     * 歌与歌之间质心几乎不动时，靠它保证颜色仍在缓慢流过整圈。
     */
    var ambientHueRotateDegPerSec: Int
        get() = intValue("ambient_hue_rotate", 0).coerceIn(0, 30)
        set(value) = setIntValue("ambient_hue_rotate", value.coerceIn(0, 30))

    /**
     * 色相**跳变**步长（度，0 ~ 180）。
     *
     * 0 = 连续平滑（默认）；>0 时目标色相直接吸到最近的档位 ——
     * 「跳跃」模式用它把颜色从「流过去」变成「跳过去」。
     * 60° = 六档（红/黄/绿/青/蓝/品红）。
     */
    var ambientHueJumpDeg: Int
        get() = intValue("ambient_hue_jump_deg", 0).coerceIn(0, 180)
        set(value) = setIntValue("ambient_hue_jump_deg", value.coerceIn(0, 180))

    /**
     * 起拍闪光强度 —— **存的是 ×100 的整数**（0 ~ 200）。
     *
     * 经典值是 35（明度在起拍瞬间 +35%）；「跳跃」拉到 120 就是闪到接近满亮。
     */
    var ambientPulseGainHundredths: Int
        get() = intValue("ambient_pulse_gain", 35).coerceIn(0, 200)
        set(value) = setIntValue("ambient_pulse_gain", value.coerceIn(0, 200))

    /** 起拍闪光强度（0 ~ 2.00）。 */
    fun ambientPulseGainValue(): Float = ambientPulseGainHundredths / 100f

    /**
     * 起拍最短间隔（毫秒，80 ~ 600）。
     *
     * 越小闪得越密：240 = 最多约 4 次/秒（默认，防八分音符被当成两次起拍）；
     * 120 = 约 8 次/秒，「跳跃」的闪法。
     */
    var ambientBeatMinGapMs: Int
        get() = intValue("ambient_beat_gap_ms", 240).coerceIn(80, 600)
        set(value) = setIntValue("ambient_beat_gap_ms", value.coerceIn(80, 600))

    /**
     * 跳变模式的**强制换色间隔**（毫秒，0 ~ 1000，0 = 只跟起拍）。
     *
     * 起拍检测是内容驱动的：音乐没鼓点、或者低音一直很响被自适应阈值
     * 当成常态时就不会换色。给个上限，保证「隔这么久至少闪一次」。
     */
    var ambientJumpHoldMs: Int
        get() = intValue("ambient_jump_hold_ms", 0).coerceIn(0, 1000)
        set(value) = setIntValue("ambient_jump_hold_ms", value.coerceIn(0, 1000))

    /**
     * 是否停留在**自定义**模式。
     *
     * 预设是前门：页短、只读一个效果说明；`true` 才把全部参数滑杆放出来。
     * 与 [AmbientPresets] 的落点一样是「观感参数」，不碰引擎 / 颜色模式 /
     * 亮度 / 分区编排。
     */
    var ambientCustomMode: Boolean
        get() = sp.getBoolean("ambient_custom_mode", false)
        set(value) { sp.edit().putBoolean("ambient_custom_mode", value).apply() }

    /**
     * 分区编排（区域巡游）。
     *
     * **行车默认关闭** —— 分区切换容易分散注意力，
     * 只在驻车 / 展示场景开启。
     */
    var ambientZoneChoreo: Boolean
        get() = sp.getBoolean("ambient_zone_choreo", false)
        set(value) { sp.edit().putBoolean("ambient_zone_choreo", value).apply() }

    /** 分区驻留时间（毫秒）—— 低于 700ms 会看出硬边。 */
    var ambientZoneDwellMs: Int
        get() = intValue("ambient_zone_dwell_ms", 800).coerceIn(700, 2000)
        set(value) = setIntValue("ambient_zone_dwell_ms", value.coerceIn(700, 2000))

    /** 感知层每段参考电平的时间常数（毫秒）。 */
    var ambientBandRefTauMs: Int
        get() = intValue("ambient_band_ref_tau_ms", 2000).coerceIn(1000, 5000)
        set(value) = setIntValue("ambient_band_ref_tau_ms", value.coerceIn(1000, 5000))

    // ------------------------------------------------------------------ 卡片风格（第五十八轮）

    /**
     * 卡片透明度（20..100，默认 100 = 完全不透明）。
     *
     * 作用范围：卡面 + 卡内两层（[CardStyle.apply]）。
     */
    var cardOpacity: Int
        get() = CardStyle.clampOpacity(intValue("card_opacity", CardStyle.DEFAULT_OPACITY))
        set(value) = setIntValue("card_opacity", CardStyle.clampOpacity(value))

    /**
     * 卡片颜色（0 = 跟随主题；否则是用户自选色，ARGB）。
     *
     * 自选色会连带把卡内两层按明度差派生出来（见 [CardStyle.apply]）。
     */
    var cardColor: Int
        get() = intValue("card_color", 0)
        set(value) = setIntValue("card_color", value)

    /**
     * 卡片圆角（0..48 设计坐标 px，默认 28）。
     *
     * 内嵌块 / 图标底会等比缩放（见 [CardStyle.applyRadius]）。
     */
    var cardRadius: Int
        get() = CardStyle.clampRadius(intValue("card_radius", CardStyle.DEFAULT_RADIUS))
        set(value) = setIntValue("card_radius", CardStyle.clampRadius(value))

    // ------------------------------------------------------------------ 原车 Dock / 状态栏

    /**
     * 「Dock 生效应用」：命中这些包时隐藏原车 Dock（`dockbar_hide_dock_pkgs`）。
     *
     * 选择器在**一个都不选**时写入哨兵 `__dockbar_no_apps__`（= 一个都不隐藏）
     * 空集本身在 `policy_control` 里表示 `*`（= 全部隐藏），两者语义相反。
     * 写入时统一走 [OemDockPolicy.NO_APPS] 哨兵，别让空集漏出去。
     */
    var hideDockApps: Set<String>
        get() = OemDockPolicy.decodePackages(stringValue("dockbar_hide_dock_pkgs", ""))
        set(value) = setStringValue(
            "dockbar_hide_dock_pkgs",
            OemDockPolicy.encodePackages(value.ifEmpty { setOf(OemDockPolicy.NO_APPS) }),
        )

    /** 「状态栏生效应用」：命中这些包时隐藏系统状态栏（`dockbar_hide_status_pkgs`）。 */
    var hideStatusApps: Set<String>
        get() = OemDockPolicy.decodePackages(stringValue("dockbar_hide_status_pkgs", ""))
        set(value) = setStringValue(
            "dockbar_hide_status_pkgs",
            OemDockPolicy.encodePackages(value.ifEmpty { setOf(OemDockPolicy.NO_APPS) }),
        )

    // ------------------------------------------------------------------ 画中画 / 桌面

    /** 画中画候选应用。 */
    var pipApp: String
        get() = stringValue("pip_app", "")
        set(value) = setStringValue("pip_app", value)

    /**
     * **副画**（双画中画的第二路）承载的应用（= `pip2_app`）。
     *
     * 里副画是**第二个 **（挂在）
     * 与主画是两个独立的承载面，可以各跑一个应用。
     */
    var pip2App: String
        get() = stringValue("pip2_app", "")
        set(value) = setStringValue("pip2_app", value)

    /**
     * 应用抽屉的排序（`launcher_app_order`）：逗号分隔的包名，按显示顺序。
     * 不在列表里的应用排在后面，按名称排序。
     */
    var appOrder: List<String>
        get() = stringValue("launcher_app_order", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) = setStringValue(
            "launcher_app_order",
            value.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
        )

    /**
     * Dock **快捷应用**的槽位配置（第五十四轮）：逗号分隔的包名，按槽位顺序。
     *
     * 存取与规整在 [DockQuickApps] 里，这里只做「读出来 / 写进去」的转发。
     */
    var dockQuickApps: List<String>
        get() = DockQuickApps.parse(stringValue(DockQuickApps.KEY, ""))
        set(value) = setStringValue(DockQuickApps.KEY, DockQuickApps.toCsv(value))

    /**
     * 应用重命名别名（第九十五轮 C5，`launcher_app_alias`）。
     *
     * 存成一行一个 `包名=别名`，别名做 URL 编码（允许中文/空格/等号）。
     * 抽屉里显示时优先用别名；别名为空表示恢复系统名称。
     */
    var appAlias: Map<String, String>
        get() = stringValue("launcher_app_alias", "")
            .lineSequence()
            .mapNotNull { line ->
                val cut = line.indexOf('=')
                if (cut <= 0) return@mapNotNull null
                val pkg = line.substring(0, cut).trim()
                if (pkg.isEmpty()) return@mapNotNull null
                val alias = runCatching {
                    java.net.URLDecoder.decode(line.substring(cut + 1), "UTF-8")
                }.getOrDefault("")
                if (alias.isBlank()) null else pkg to alias
            }
            .toMap()
        set(value) = setStringValue(
            "launcher_app_alias",
            value.entries
                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                .joinToString("\n") { (pkg, alias) ->
                    "$pkg=" + java.net.URLEncoder.encode(alias, "UTF-8")
                },
        )

    /** 某个包名的显示名：有别名用别名，否则返回 null（由调用方回落到系统名）。 */
    fun appAliasOf(pkg: String): String? = appAlias[pkg]

    /** 写入/清除一个别名（空别名 = 恢复系统名称）。 */
    fun setAppAlias(pkg: String, alias: String) {
        val next = appAlias.toMutableMap()
        if (alias.isBlank()) next.remove(pkg) else next[pkg] = alias.trim()
        appAlias = next
    }

    /**
     * 最近任务里「锁定」的包（第九十五轮 C3，`recent_locked`）。
     * 清空最近任务时保留这些包的任务；逗号分隔（包名不含逗号）。
     */
    var recentLocked: Set<String>
        get() = stringValue("recent_locked", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(value) = setStringValue("recent_locked", value.filter { it.isNotBlank() }.joinToString(","))

    /**
     * 启动更新检测里用户点过「稍后」的版本号（第九十八轮，`update_skipped_code`）。
     *
     * 同一个版本不再重复弹窗；发新版（versionCode 变了）会重新询问。
     */
    var updateSkippedVersionCode: Long
        get() = sp.getLong("update_skipped_code", 0L)
        set(value) = sp.edit().putLong("update_skipped_code", value).apply()

    /**
     * 云端应用库已安装过的条目（第九十八轮，`cloud_installed_apks`）。
     *
     * key = 条目的 sha256（清单没给就用文件名）；只用于列表上打「已安装」标记。
     */
    var cloudInstalled: Set<String>
        get() = stringValue("cloud_installed_apks", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(value) = setStringValue(
            "cloud_installed_apks",
            value.filter { it.isNotBlank() }.joinToString(","),
        )

    /** 云端应用条目的安装标记 key。 */
    fun cloudInstalledKey(sha256: String, file: String): String =
        sha256.ifBlank { file }

    fun markCloudInstalled(sha256: String, file: String) {
        val key = cloudInstalledKey(sha256, file)
        if (key.isBlank()) return
        cloudInstalled = cloudInstalled + key
    }

    fun isCloudInstalled(sha256: String, file: String): Boolean =
        cloudInstalledKey(sha256, file) in cloudInstalled

    /**
     * 选中的媒体来源 id（`design_music_source_v1`，存的是来源文案）。
     * 这里存 id，文案可由 [com.cardesk.launcher.core.media.MediaSources] 还原。
     */
    var musicSource: String
        get() = stringValue("design_music_source_v1", "generic")
        set(value) = setStringValue("design_music_source_v1", value)

    var mock360Overlay: Boolean
        get() = sp.getBoolean("mock_360_overlay", true)
        set(v) = sp.edit().putBoolean("mock_360_overlay", v).apply()

    // ------------------------------------------------------------------ 360 环影

    /**
     * 360 环影总开关（`camera360_enabled_v2`，**默认关闭**）。
     */
    var camera360Enabled: Boolean
        get() = flag("camera360_enabled_v2", false)
        set(v) = setFlag("camera360_enabled_v2", v)

    /**
     * 视角切换延迟（`camera360_view_delay_ms`）。
     * 只接受 100 / 300 / 800 三个档位，其它值一律回落到 300。
     */
    var camera360DelayMs: Int
        get() = normalizeCamera360Delay(intValue("camera360_view_delay_ms", 300))
        set(value) = setIntValue("camera360_view_delay_ms", normalizeCamera360Delay(value))

    /** 倒车影像视角（`camera360_reverse_view_v2`，默认 -1 = 无）。 */
    var camera360ReverseView: Int
        get() = normalizeCamera360View(intValue("camera360_reverse_view_v2", -1))
        set(value) = setIntValue("camera360_reverse_view_v2", normalizeCamera360View(value))

    /** 方向盘按键视角（`camera360_key_view_v2`）。 */
    var camera360KeyView: Int
        get() = normalizeCamera360View(intValue("camera360_key_view_v2", -1))
        set(value) = setIntValue("camera360_key_view_v2", normalizeCamera360View(value))

    /** 灯控视角（`camera360_lamp_view_v2`）。 */
    var camera360LampView: Int
        get() = normalizeCamera360View(intValue("camera360_lamp_view_v2", -1))
        set(value) = setIntValue("camera360_lamp_view_v2", normalizeCamera360View(value))

    /** D 档视角（`camera360_d_view_v2`）。 */
    var camera360DView: Int
        get() = normalizeCamera360View(intValue("camera360_d_view_v2", -1))
        set(value) = setIntValue("camera360_d_view_v2", normalizeCamera360View(value))

    /** RD 档视角（`camera360_rd_view_v2`）。 */
    var camera360RdView: Int
        get() = normalizeCamera360View(intValue("camera360_rd_view_v2", -1))
        set(value) = setIntValue("camera360_rd_view_v2", normalizeCamera360View(value))

    /** 「关闭导航提示」开关（`camera360_np_close_v2`）。 */
    var camera360NpClose: Boolean
        get() = flag("camera360_np_close_v2", false)
        set(v) = setFlag("camera360_np_close_v2", v)

    // ------------------------------------------------------------------ 桌面布局字号

    /**
     * 桌面紧凑模式主歌词字号（`desk_desk_compact_main_sp`，范围 14..64）。
     */
    var deskCompactMainSp: Int
        get() = intValue("desk_desk_compact_main_sp", 28).coerceIn(14, 64)
        set(value) = setIntValue("desk_desk_compact_main_sp", value.coerceIn(14, 64))

    /**
     * 胎压数值字号（`desk_tpms_value_sp`，默认 20，范围 10..48）。
     */
    var deskTpmsValueSp: Int
        get() = intValue("desk_tpms_value_sp", 20).coerceIn(10, 48)
        set(value) = setIntValue("desk_tpms_value_sp", value.coerceIn(10, 48))

    /**
     * 桌面紧凑模式副歌词字号（`desk_desk_compact_sub_sp`，默认 20，范围 10..48）。
     */
    var deskCompactSubSp: Int
        get() = intValue("desk_desk_compact_sub_sp", 20).coerceIn(10, 48)
        set(value) = setIntValue("desk_desk_compact_sub_sp", value.coerceIn(10, 48))

    /**
     * 桌面展开模式主歌词字号（`desk_desk_expanded_main_sp`，默认 28，范围 14..64）。
     */
    var deskExpandedMainSp: Int
        get() = intValue("desk_desk_expanded_main_sp", 28).coerceIn(14, 64)
        set(value) = setIntValue("desk_desk_expanded_main_sp", value.coerceIn(14, 64))

    /**
     * 桌面展开模式副歌词字号（`desk_desk_expanded_sub_sp`，默认 20，范围 10..48）。
     */
    var deskExpandedSubSp: Int
        get() = intValue("desk_desk_expanded_sub_sp", 20).coerceIn(10, 48)
        set(value) = setIntValue("desk_desk_expanded_sub_sp", value.coerceIn(10, 48))

    /**
     * 第三页全屏歌词主字号（`desk_desk_page3_main_sp`，默认 40，范围 14..64）。
     */
    var deskPage3MainSp: Int
        get() = intValue("desk_desk_page3_main_sp", 40).coerceIn(14, 64)
        set(value) = setIntValue("desk_desk_page3_main_sp", value.coerceIn(14, 64))

    /**
     * 第三页全屏歌词副字号（`desk_desk_page3_sub_sp`，默认 28，范围 10..48）。
     */
    var deskPage3SubSp: Int
        get() = intValue("desk_desk_page3_sub_sp", 28).coerceIn(10, 48)
        set(value) = setIntValue("desk_desk_page3_sub_sp", value.coerceIn(10, 48))

    // ------------------------------------------------------------- 仪表 Overlay

    /** 仪表 Overlay 歌词总开关（`desk_cluster_overlay`，默认关闭）。 */
    var clusterOverlayEnabled: Boolean
        get() = flag("desk_cluster_overlay", false)
        set(v) = setFlag("desk_cluster_overlay", v)

    /** Overlay 模块宽度百分比（`desk_overlay_w`，默认 100，范围 30..100）。 */
    var overlayWidthPct: Int
        get() = intValue("desk_overlay_w", 100).coerceIn(30, 100)
        set(value) = setIntValue("desk_overlay_w", value.coerceIn(30, 100))

    /** Overlay 背景透明度百分比（`desk_overlay_bg_alpha`，默认 70，范围 0..100）。 */
    var overlayBgAlpha: Int
        get() = intValue("desk_overlay_bg_alpha", 70).coerceIn(0, 100)
        set(value) = setIntValue("desk_overlay_bg_alpha", value.coerceIn(0, 100))

    /** Overlay 主歌词字号（`desk_overlay_main_sp`，默认 28，范围 12..72）。 */
    var overlayMainSp: Int
        get() = intValue("desk_overlay_main_sp", 28).coerceIn(12, 72)
        set(value) = setIntValue("desk_overlay_main_sp", value.coerceIn(12, 72))

    /** Overlay 副歌词字号（`desk_overlay_sub_sp`，默认 18，范围 8..48）。 */
    var overlaySubSp: Int
        get() = intValue("desk_overlay_sub_sp", 18).coerceIn(8, 48)
        set(value) = setIntValue("desk_overlay_sub_sp", value.coerceIn(8, 48))

    /** Overlay 水平位置百分比（`desk_overlay_x`，默认 50，范围 0..100）。 */
    var overlayXPct: Int
        get() = intValue("desk_overlay_x", 50).coerceIn(0, 100)
        set(value) = setIntValue("desk_overlay_x", value.coerceIn(0, 100))

    /** Overlay 垂直位置百分比（`desk_overlay_y`，默认 88，范围 0..100）。 */
    var overlayYPct: Int
        get() = intValue("desk_overlay_y", 88).coerceIn(0, 100)
        set(value) = setIntValue("desk_overlay_y", value.coerceIn(0, 100))

    /** 仪表主歌词颜色（`desk_cluster_lyric_main_color`，0 = 跟随主题）。 */
    var clusterLyricMainColor: Int
        get() = intValue("desk_cluster_lyric_main_color", 0)
        set(value) = setIntValue("desk_cluster_lyric_main_color", value)

    /** 仪表副歌词颜色（`desk_cluster_lyric_sub_color`，0 = 跟随主题）。 */
    var clusterLyricSubColor: Int
        get() = intValue("desk_cluster_lyric_sub_color", 0)
        set(value) = setIntValue("desk_cluster_lyric_sub_color", value)

    // ------------------------------------------------------------------ 按键增强

    /** 按键增强总开关（`custom_keys_enabled`，默认关闭）。 */
    var keysEnhanced: Boolean
        get() = flag("custom_keys_enabled", false)
        set(v) = setFlag("custom_keys_enabled", v)

    /** 语音键单击动作（`voice_click_action`，默认 3 = 无）。 */
    var voiceClickAction: Int
        get() = intValue("voice_click_action", ACTION_NONE)
        set(v) = setIntValue("voice_click_action", v)

    /** 语音键单击的驾驶模式选择（`voice_click_mode`）。 */
    var voiceClickMode: Int
        get() = intValue("voice_click_mode", 0)
        set(v) = setIntValue("voice_click_mode", v)

    /** 语音键双击动作（`voice_double_action`）。 */
    var voiceDoubleAction: Int
        get() = intValue("voice_double_action", ACTION_NONE)
        set(v) = setIntValue("voice_double_action", v)

    /** 语音键双击的驾驶模式选择（`voice_double_mode`）。 */
    var voiceDoubleMode: Int
        get() = intValue("voice_double_mode", 0)
        set(v) = setIntValue("voice_double_mode", v)

    /** 语音键长按动作（`voice_long_action`）。 */
    var voiceLongAction: Int
        get() = intValue("voice_long_action", ACTION_NONE)
        set(v) = setIntValue("voice_long_action", v)

    /** 语音键长按的驾驶模式选择（`voice_long_mode`）。 */
    var voiceLongMode: Int
        get() = intValue("voice_long_mode", 0)
        set(v) = setIntValue("voice_long_mode", v)

    /** 上一曲长按动作（`media_prev_long_action`）。 */
    var mediaPrevLongAction: Int
        get() = intValue("media_prev_long_action", ACTION_NONE)
        set(v) = setIntValue("media_prev_long_action", v)

    /** 上一曲长按模式（`media_prev_long_mode`）。 */
    var mediaPrevLongMode: Int
        get() = intValue("media_prev_long_mode", 0)
        set(v) = setIntValue("media_prev_long_mode", v)

    /** 下一曲长按动作（`media_next_long_action`）。 */
    var mediaNextLongAction: Int
        get() = intValue("media_next_long_action", ACTION_NONE)
        set(v) = setIntValue("media_next_long_action", v)

    /** 下一曲长按模式（`media_next_long_mode`）。 */
    var mediaNextLongMode: Int
        get() = intValue("media_next_long_mode", 0)
        set(v) = setIntValue("media_next_long_mode", v)

    /** 启停控制（`start_switch_enabled`）。 */
    var startSwitchEnabled: Boolean
        get() = flag("start_switch_enabled", false)
        set(v) = setFlag("start_switch_enabled", v)

    /** 启停控制的驾驶模式选择（`start_switch_mode`）。 */
    var startSwitchMode: Int
        get() = intValue("start_switch_mode", 0)
        set(v) = setIntValue("start_switch_mode", v)

    /** 启动导航投屏（`start_navi_cast_enabled`）。 */
    var startNaviCastEnabled: Boolean
        get() = flag("start_navi_cast_enabled", false)
        set(v) = setFlag("start_navi_cast_enabled", v)

    /** ISS 保持（`iss_keep_enabled`）。 */
    var issKeepEnabled: Boolean
        get() = flag("iss_keep_enabled", false)
        set(v) = setFlag("iss_keep_enabled", v)

    // ------------------------------------------------------------------ 启动 / Dock / 导航栏

    var bootAutostart: Boolean
        get() = flag("desktop_boot_autostart")
        set(v) = setFlag("desktop_boot_autostart", v)

    /**
     * 隐藏**原车** Dock。
     *
     * 打开时会**关掉增强导航栏**——就是这么做互斥的。
     *
     * 第五十六轮：这条**只管原车 Dock**（走 `policy_control` 的 `immersive.navigation`），
     * **不再控制桌面自己的 Dock 视图** ——
     * 之前我们把两者混在一起，导致设置里勾「隐藏原车 Dock」，
     * 结果消失的是**桌面自己的** Dock（用户反馈：「隐藏系统 Dock 栏功能实现错误」）。
     * 桌面自己的 Dock 改用 [hideHomeDock]。
     */
    var hideDock: Boolean
        get() = flag("dockbar_hide_dock")
        set(v) {
            setFlag("dockbar_hide_dock", v)
            if (v) setFlag("navbar_enhanced_enabled", false)
        }

    /**
     * 隐藏**桌面自己的** Dock 栏（第五十六轮新增）。
     *
     * 与 [hideDock] 分开：
     * - [hideDock] = 隐藏**原车/系统**的 Dock 栏（`policy_control`）；
     * - 本项 = 隐藏**我们画的**那一栏（自己的 View）。
     *
     * 之前这两件事共用 [hideDock] 一个开关，才是「隐藏系统 Dock 栏 → 实际隐藏了 app 的 Dock 栏」。
     */
    var hideHomeDock: Boolean
        get() = flag("home_dock_hidden", false)
        set(v) = setFlag("home_dock_hidden", v)

    /**
     * 沉浸系统导航栏（第五十六轮新增，**默认开**）。
     *
     * 写 `policy_control` 的 `immersive.navigation=<桌面自己的包名>`：
     * 把系统自带 Dock 从窗口布局里摘掉 —— 它**不再接收底部触摸**，
     * 所以点桌面 Dock 的按钮不会再把它唤出来；
     * 只剩「从屏幕底部向上滑」这个系统手势能临时把它叫出来。
     *
     * 与 [hideDock] 的区别：本项只影响**桌面自己**（别的 App 照常有导航栏），
     * [hideDock] 是「对指定应用隐藏原车 Dock」。
     */
    var oemNavImmersive: Boolean
        get() = flag("oem_nav_immersive", true)
        set(v) = setFlag("oem_nav_immersive", v)

    var hideStatus: Boolean
        get() = flag("dockbar_hide_status")
        set(v) = setFlag("dockbar_hide_status", v)

    /**
     * 「隐藏君君状态栏」。
     *
     * 键名 `dockbar_hide_junjun_status`。
     *
     * 第三十七轮：**默认改成关闭（false）**。
     *
     * 默认值是 true，而它的效果是「**只在导航页（第二页）**隐藏自绘状态栏」
     * （：`i3 == 1 && isHideJunjunStatus()`）。
     * 于是真机上第二页一直没有状态栏 —— 用户要求「桌面第二页也需要显示状态栏」。
     *
     * 这个键我们自己的设置面板里并没有暴露（诊断包的 SharedPreferences 里也没有它），
     * 所以它一直是「默认 true」在生效；现在把默认值翻成 false，
     * 两页都显示状态栏。「键名 + 生效页」语义保持不变。
     */
    var hideJunjunStatus: Boolean
        get() = flag("dockbar_hide_junjun_status", false)
        set(v) = setFlag("dockbar_hide_junjun_status", v)

    /** 增强导航栏承载样式：0=设计 Dock，1=槽位条 */
    var navStyle: Int
        get() = intValue("navbar_style", 0)
        set(v) = setIntValue("navbar_style", v)

    /**
     * 桌面悬浮气泡列是否显示。
     *
     * 第六十三轮起，桌面常驻的「音乐 / 空调」两颗气泡已移除，
     * 这一列只剩**被最小化的浮窗**的气泡（点一下还原、长按关闭）；
     * 本开关控制的就是这一列。
     */
    var floatBubbles: Boolean
        get() = flag("desk_float_bubbles", true)
        set(value) = setFlag("desk_float_bubbles", value)

    /**
     * 下移 Dock。
     *
     * 打开时同样会**关掉增强导航栏**。
     */
    var lowerDock: Boolean
        get() = flag("dockbar_lower_dock")
        set(v) {
            setFlag("dockbar_lower_dock", v)
            if (v) {
                setFlag("navbar_enhanced_enabled", false)
                // 第八十七轮：全局 Dock 与下移 Dock 互斥（见 globalDockEnabled）。
                setFlag(KEY_GLOBAL_DOCK, false)
            }
        }

    /**
     * 增强导航栏。
     *
     * 打开时会**关掉隐藏 Dock 与下移 Dock**——三者互斥。
     */
    var enhancedNav: Boolean
        get() = flag("navbar_enhanced_enabled")
        set(v) {
            setFlag("navbar_enhanced_enabled", v)
            if (v) {
                setFlag("dockbar_hide_dock", false)
                setFlag("dockbar_lower_dock", false)
                // 第八十七轮：全局 Dock 也是「另一种底栏形态」，同样互斥。
                // 两套形态同时开会让人在桌面里看到 A、在别的应用里看到 B。
                setFlag(KEY_GLOBAL_DOCK, false)
            }
        }

    /**
     * 全局 Dock（第八十七轮）。
     *
     * 打开后 Dock 变成**常驻其它应用底部**的全局窗口（见
     * `core/dock/GlobalDockService`），顶替原车导航栏；桌面自己仍然画自己的
     * Dock（沉浸窗口不回缩，见设计契约「二、全局 Dock 工作流」）。
     *
     * ## 为什么与 [enhancedNav] / [lowerDock] 互斥
     *
     * 这两种形态改变的是「桌面底栏的高度 / 位置」，而全局窗口画的是同一套
     * [com.cardesk.launcher.ui.shell.DockBarView]。同时开启时，
     * 桌面里看到的底栏和别的应用里看到的底栏会对不上，
     * 用户会以为开了两个功能（契约要求任意时刻只有一种 Dock 形态）。
     *
     * 互斥写法照搬 [hideDock]：**本项打开时关掉对方**；反向互斥写在
     * [enhancedNav] / [lowerDock] 的 setter 里（它们打开时关本项）。
     *
     * ## 第九十四轮：默认开
     *
     * 用户要求「默认全局隐藏系统自带的 Dock 栏，用桌面 Dock 栏」：
     * 原车导航栏在其它应用里不再显示，统一由 CarDesk 的全局 Dock 窗口顶替。
     * 前提是这台车能起全局窗口（RRO 预留区或悬浮窗权限），能力不足时
     * [com.cardesk.launcher.core.dock.GlobalDockPolicyGate] 会退回普通桌面策略，
     * 不会出现「原车 Dock 没了、CarDesk Dock 也没有」的空白底边。
     */
    var globalDockEnabled: Boolean
        get() = flag(KEY_GLOBAL_DOCK, true)
        set(v) {
            setFlag(KEY_GLOBAL_DOCK, v)
            if (v) {
                setFlag("navbar_enhanced_enabled", false)
                setFlag("dockbar_lower_dock", false)
            }
        }

    var dualPip: Boolean
        get() = flag("desk_dual_pip")
        set(v) = setFlag("desk_dual_pip", v)

    /**
     * 车型编号（= `tpms_car_model`）。
     *
     * 取值：`01` / `02` / `02HB` / `03` / `03+` / `05`，默认 `03`（默认车型）。
     * 决定胎压卡里自绘车模的形状：SUV 家族（01/02/02HB/05）与轿车家族（03/03+）。
     */
    var carModel: String
        get() = CarModels.normalize(stringValue(CarModels.KEY, CarModels.MODEL_03))
        set(v) = setStringValue(CarModels.KEY, CarModels.normalize(v))

    var adbAuto: Boolean
        get() = flag("adb_boot_auto")
        set(v) = setFlag("adb_boot_auto", v)

    /**
     * 开机自动开启**无线 ADB**（第五十五轮）。
     *
     * 与 [adbAuto] 分开：一个是「开调试开关」，一个是「把端口切到 5555」。
     * 后者风险更高（开着的时候同网段可连），所以单独一个开关，
     * 关机自启也由 [com.cardesk.launcher.BootReceiver] 负责执行。
     */
    var adbWirelessAuto: Boolean
        get() = flag("adb_wireless_boot_auto")
        set(v) = setFlag("adb_wireless_boot_auto", v)

    /** 平台判定覆盖：0 自动（按密度/长边）、1 强制 E03、2 强制 820A，用于多车型适配验证。 */
    var platformOverride: Int
        get() = intValue("platform_override", 0)
        set(v) = setIntValue("platform_override", v)

    // ------------------------------------------------------------------ 天气定位（我们补充）

    /**
     * 最近一次拿到的**城市名**（来自高德区域广播 / 高德城市文件）。
     *
     * 没有这一项（它只读系统定位），我们补上
     * 真机上系统定位长期没有位置，城市名是我们唯一的坐标来源，
     * 持久化之后重启也不会丢。
     */
    var weatherCity: String
        get() = stringValue(KEY_WEATHER_CITY, "")
        set(v) = setStringValue(KEY_WEATHER_CITY, v)

    /** 最近一次拿到的**省份**（高德区域广播带的 `PROVINCE_NAME`）。 */
    var weatherProvince: String
        get() = stringValue(KEY_WEATHER_PROVINCE, "")
        set(v) = setStringValue(KEY_WEATHER_PROVINCE, v)

    /** 最近一次拿到的**区划码**（高德区域广播带的 `AREA_CODE`）。 */
    var weatherAreaCode: String
        get() = stringValue(KEY_WEATHER_AREA_CODE, "")
        set(v) = setStringValue(KEY_WEATHER_AREA_CODE, v)

    /**
     * 最近一次成功定位的纬度（没有则是 `NaN`）。
     *
     * 拿到位置就存下来：车机 GNSS 可能几小时都不更新，
     * 但「上次在哪儿」对查天气完全够用（天气按城市级变化）。
     */
    var weatherFixLat: Float
        get() = sp.getFloat(KEY_WEATHER_FIX_LAT, Float.NaN)
        set(v) = sp.edit().putFloat(KEY_WEATHER_FIX_LAT, v).apply()

    /** 最近一次成功定位的经度（没有则是 `NaN`）。 */
    var weatherFixLon: Float
        get() = sp.getFloat(KEY_WEATHER_FIX_LON, Float.NaN)
        set(v) = sp.edit().putFloat(KEY_WEATHER_FIX_LON, v).apply()

    /** 最近一次成功定位的时间戳（毫秒；0 = 从没定位到过）。 */
    var weatherFixTime: Long
        get() = sp.getLong(KEY_WEATHER_FIX_TIME, 0L)
        set(v) = sp.edit().putLong(KEY_WEATHER_FIX_TIME, v).apply()

    // ------------------------------------------------------------------ 通用读写

    /** 通用布尔开关（键名键，便于对照）。 */
    fun flag(name: String, def: Boolean = false): Boolean = sp.getBoolean(name, def)

    fun setFlag(name: String, value: Boolean) {
        sp.edit().putBoolean(name, value).apply()
    }

    /**
     * 读一个整型设置。
     *
     * ## 第七十九轮 P0：**类型不符不能崩**
     *
     * `SharedPreferences.getInt()` 在键值被存成别的类型时**直接抛 ClassCastException**。
     * 氛围灯设置页一进来就要读 4 个这样的键
     * （`ambient_mode` / `ambient_sensitivity` / `ambient_min_brightness` / `ambient_max_brightness`），
     * 任何一个类型不对，整页就崩 —— 而且崩在页面的构造函数里，
     * 现场只剩「窗口黑了」，日志里连一行异常都没有。
     *
     * 现在读不到就**退一步取**：数字字符串按数字取、布尔按 0/1 取，
     * 实在认不出来才用默认值。**宁可设置项不对，也不能让页面白屏。**
     */
    fun intValue(name: String, def: Int): Int {
        val raw = runCatching { sp.all[name] }.getOrNull() ?: return def
        return when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Float -> raw.toInt()
            is Double -> raw.toInt()
            is Boolean -> if (raw) 1 else 0
            is String -> raw.trim().toIntOrNull() ?: def
            else -> def
        }
    }

    fun stringValue(name: String, def: String): String = sp.getString(name, def) ?: def

    fun setStringValue(name: String, value: String) {
        sp.edit().putString(name, value).apply()
    }

    fun setIntValue(name: String, value: Int) {
        sp.edit().putInt(name, value).apply()
    }

    fun remove(name: String) {
        sp.edit().remove(name).apply()
    }

    companion object {
        /** 引擎 = 新版（V2，感知色空间 + 二阶限速）。 */
        const val ENGINE_V2 = 0

        /** 引擎 = 旧版（观感回退，实车验证过的现网链路）。 */
        const val ENGINE_LEGACY = 1

        /**
         * 全局 Dock 开关（第八十七轮）。
         *
         * 单独抽成常量是因为有三处要写同一个键：设置页（`SettingsOverlay.pageDock`）、
         * 前台服务（`GlobalDockService` 启动/通知栏关闭）、以及这里与
         * 增强导航栏 / 下移 Dock 的互斥逻辑。键名写错一次 = 开关看起来能点但
         * 窗口永远不出现，所以不散落成字面量。
         */
        const val KEY_GLOBAL_DOCK = "global_dock_enabled"

        /** 天气定位：最近一次拿到的城市名。 */
        const val KEY_WEATHER_CITY = "weather_fix_city"

        /** 天气定位：最近一次拿到的省份。 */
        const val KEY_WEATHER_PROVINCE = "weather_fix_province"

        /** 天气定位：最近一次拿到的区划码。 */
        const val KEY_WEATHER_AREA_CODE = "weather_fix_area_code"

        /** 天气定位：最近一次成功定位的纬度。 */
        const val KEY_WEATHER_FIX_LAT = "weather_fix_lat"

        /** 天气定位：最近一次成功定位的经度。 */
        const val KEY_WEATHER_FIX_LON = "weather_fix_lon"

        /** 天气定位：最近一次成功定位的时间戳。 */
        const val KEY_WEATHER_FIX_TIME = "weather_fix_time"

        /** 日夜模式：跟随高德（`MODE_FOLLOW_AMAP`，默认值）。 */
        const val MODE_FOLLOW_AMAP = 0

        /** 日夜模式：跟随时间（`MODE_FOLLOW_TIME`）。 */
        const val MODE_FOLLOW_TIME = 1

        /** 日夜模式：跟随光感（`MODE_FOLLOW_SENSOR`）。 */
        const val MODE_FOLLOW_SENSOR = 2

        // 按键动作编码
        /** 切换驾驶模式（`ACTION_DRIVE_MODE` = 0）。 */
        const val ACTION_DRIVE_MODE = 0

        /** 360 环影开关（`ACTION_360_TOGGLE` = 1）。 */
        const val ACTION_360_TOGGLE = 1

        /** 唤起语音助手（`ACTION_VOICE_ASSIST` = 2）。 */
        const val ACTION_VOICE_ASSIST = 2

        /** 无动作（`ACTION_NONE` = 3）。 */
        const val ACTION_NONE = 3

        /** 导航投屏开关（`ACTION_DIM_NAVI_CAST_TOGGLE` = 4）。 */
        const val ACTION_DIM_NAVI_CAST_TOGGLE = 4

        /** 360 环影的延迟档位。 */
        val CAMERA360_DELAYS = intArrayOf(100, 300, 800)

        /** 360 环影的延迟档位文案。 */
        val CAMERA360_DELAY_LABELS = arrayOf("100 ms", "300 ms", "800 ms")

        /** 360 环影视角文案。 */
        val CAMERA360_VIEW_LABELS = arrayOf(
            "无", "关闭", "前视", "广角前视", "前视+顶部", "顶视",
            "后视", "广角后视", "后视放大", "后视+顶部", "两侧",
        )

        /** 360 环影视角编号。 */
        val CAMERA360_VIEW_IDS = intArrayOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

        private fun normalizeCamera360Delay(ms: Int): Int = when (ms) {
            100, 800 -> ms
            else -> 300
        }

        private fun normalizeCamera360View(view: Int): Int = if (view in -1..9) view else -1
    }
}
