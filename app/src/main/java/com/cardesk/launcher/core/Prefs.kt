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
            if (v) setFlag("navbar_enhanced_enabled", false)
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

    fun intValue(name: String, def: Int): Int = sp.getInt(name, def)

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
