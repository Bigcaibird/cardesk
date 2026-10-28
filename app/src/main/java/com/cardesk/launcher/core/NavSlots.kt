package com.cardesk.launcher.core

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 导航栏槽位模型
 * （、
 *
 *
 * ## 持久化格式
 *
 * 存的是 **JSON 数组**，字段名固定
 *
 * - `type`：`action / app / datetime / empty / lyric / media`
 * - `actionId`：系统动作 id
 * - `packageName`：应用包名
 * - `label`：显示名
 * - `launchMode`：**仅当 `type == "app"` 时写**，
 *   取值 `fullscreen / pip1 / pip2`，语义是「全屏 / 主画 / 副画」。
 *
 * 本应用早期用竖线文本存，两边互读即崩，所以这里改成同样的 JSON 数组。
 * 老版本（竖线文本）的数据仍能读，读不出来就回落默认槽位（容错策略）。
 *
 * 键名沿用 `navbar_slots_json` + `navbar_slots_layout_rev`，
 * 布局版本号小于 5 时重置为默认 15 槽。
 */
data class NavSlot(
    val type: String = NavSlot.TYPE_ACTION,
    val actionId: String = NavSlot.ACTION_HOME,
    /** 字段名是 `packageName`；持久化时也写 `packageName`。 */
    val pkg: String = "",
    val label: String = "",
    val launchMode: String = NavSlot.LAUNCH_FULLSCREEN,
) {

    val packageName: String get() = pkg

    val isEmpty: Boolean get() = type == TYPE_EMPTY || actionId == ACTION_EMPTY

    val isMedia: Boolean get() = type == TYPE_MEDIA || actionId == ACTION_MEDIA

    val isLyric: Boolean get() = type == TYPE_LYRIC || actionId == ACTION_LYRIC

    val isDatetime: Boolean get() = type == TYPE_DATETIME || isDatetimeAction(actionId)

    val isTempSlot: Boolean get() = actionId == ACTION_HVAC_TEMP_DRIVER || actionId == ACTION_HVAC_TEMP_PASSENGER

    val isTextToggle: Boolean get() = actionId == ACTION_HVAC_AC || actionId == ACTION_HVAC_AUTO

    val isPip1Launch: Boolean get() = type == TYPE_APP && launchMode == LAUNCH_PIP1

    val isPip2Launch: Boolean get() = type == TYPE_APP && launchMode == LAUNCH_PIP2

    /** （空槽不计入 24 个上限）。 */
    val countsTowardLimit: Boolean get() = !isEmpty

    /** 单槽 JSON 对象。 */
    fun toJson(): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "type" to type,
        "actionId" to actionId,
        "packageName" to pkg,
        "label" to label,
    ).also { if (type == TYPE_APP) it["launchMode"] = launchMode }

    /** 单槽 JSON 字符串。 */
    fun encode(): String = JsonLite.write(toJson())

    companion object {
        // ---------------------------------------------------------------- 类型
        const val TYPE_ACTION = "action"
        const val TYPE_APP = "app"
        const val TYPE_DATETIME = "datetime"
        const val TYPE_EMPTY = "empty"
        const val TYPE_LYRIC = "lyric"
        const val TYPE_MEDIA = "media"

        // ---------------------------------------------------------------- 动作
        const val ACTION_BACK = "back"
        const val ACTION_CAMERA_360 = "camera_360"
        const val ACTION_CAR = "car"
        const val ACTION_DATE = "date"
        const val ACTION_DATETIME_HM = "datetime_hm"
        const val ACTION_DATETIME_HMS = "datetime_hms"
        const val ACTION_DATETIME_HMS_WEEK = "datetime_hms_week"
        const val ACTION_DATETIME_HM_WEEK = "datetime_hm_week"
        const val ACTION_DISPLAY_OFF = "display_off"
        const val ACTION_EMPTY = "empty"
        const val ACTION_HOME = "home"
        const val ACTION_HVAC = "hvac"
        const val ACTION_HVAC_AC = "hvac_ac"
        const val ACTION_HVAC_AUTO = "hvac_auto"
        const val ACTION_HVAC_CIRC = "hvac_circ"
        const val ACTION_HVAC_DEFROST_FRONT = "hvac_defrost_front"
        const val ACTION_HVAC_DEFROST_REAR = "hvac_defrost_rear"
        const val ACTION_HVAC_FAN = "hvac_fan"
        const val ACTION_HVAC_POWER = "hvac_power"
        const val ACTION_HVAC_TEMP_DRIVER = "hvac_temp_driver"
        const val ACTION_HVAC_TEMP_PASSENGER = "hvac_temp_passenger"
        const val ACTION_LYRIC = "lyric"
        const val ACTION_MEDIA = "media"
        const val ACTION_NOTIFICATION = "notification"
        const val ACTION_POWER = "power"
        const val ACTION_RECENTS = "recents"
        const val ACTION_SETTINGS = "settings"
        const val ACTION_SPLIT = "split"
        const val ACTION_TASKS = "tasks"

        // ---------------------------------------------------------------- 启动模式
        const val LAUNCH_FULLSCREEN = "fullscreen"
        const val LAUNCH_PIP1 = "pip1"
        const val LAUNCH_PIP2 = "pip2"

        fun isDatetimeAction(actionId: String): Boolean =
            actionId == ACTION_DATE || actionId == ACTION_DATETIME_HMS || actionId == ACTION_DATETIME_HM ||
                actionId == ACTION_DATETIME_HM_WEEK || actionId == ACTION_DATETIME_HMS_WEEK

        fun normalizeLaunchMode(mode: String): String =
            if (mode == LAUNCH_PIP1 || mode == LAUNCH_PIP2) mode else LAUNCH_FULLSCREEN

        /** （主画 / 副画 / 全屏）。 */
        fun launchModeLabel(mode: String): String = when (mode) {
            LAUNCH_PIP1 -> "主画"
            LAUNCH_PIP2 -> "副画"
            else -> "全屏"
        }

        fun datetimeLabel(actionId: String): String = when (actionId) {
            ACTION_DATETIME_HMS -> "日期时间（日期 时分秒）"
            ACTION_DATETIME_HM -> "日期时间（日期 时分）"
            ACTION_DATETIME_HM_WEEK -> "日期时间（日期 时分 星期）"
            ACTION_DATETIME_HMS_WEEK -> "日期时间（日期 时分秒 星期）"
            else -> "日期"
        }

        // ---------------------------------------------------------------- 工厂

        fun createAction(actionId: String, label: String) = NavSlot(TYPE_ACTION, actionId, "", label)

        fun createApp(pkg: String, label: String, launchMode: String = LAUNCH_FULLSCREEN) =
            NavSlot(TYPE_APP, "", pkg, label, normalizeLaunchMode(launchMode))

        fun createDatetime(actionId: String, label: String) = NavSlot(TYPE_DATETIME, actionId, "", label)

        /** （标签是「空槽位」）。 */
        fun createEmpty() = NavSlot(TYPE_EMPTY, ACTION_EMPTY, "", "空槽位")

        fun createLyric() = NavSlot(TYPE_LYRIC, ACTION_LYRIC, "", "歌词")

        fun createMedia() = NavSlot(TYPE_MEDIA, ACTION_MEDIA, "", "媒体")

        // ---------------------------------------------------------------- 反序列化

        fun fromJson(map: Map<String, Any?>?): NavSlot? {
            if (map == null) return null
            var type = JsonLite.str(map, "type", TYPE_ACTION)
            var actionId = JsonLite.str(map, "actionId", "")
            val pkg = JsonLite.str(map, "packageName", "")
            var label = JsonLite.str(map, "label", "")
            val launchMode = normalizeLaunchMode(JsonLite.str(map, "launchMode", LAUNCH_FULLSCREEN))
            when {
                type == TYPE_EMPTY || actionId == ACTION_EMPTY -> {
                    type = TYPE_EMPTY
                    actionId = ACTION_EMPTY
                }
                type == TYPE_MEDIA || actionId == ACTION_MEDIA -> {
                    type = TYPE_MEDIA
                    actionId = ACTION_MEDIA
                    if (label.isEmpty()) label = "媒体"
                }
                type == TYPE_LYRIC || actionId == ACTION_LYRIC -> {
                    type = TYPE_LYRIC
                    actionId = ACTION_LYRIC
                    if (label.isEmpty()) label = "歌词"
                }
                type == TYPE_DATETIME || isDatetimeAction(actionId) -> {
                    type = TYPE_DATETIME
                    if (actionId.isEmpty()) actionId = ACTION_DATE
                    if (label.isEmpty()) label = datetimeLabel(actionId)
                }
            }
            return NavSlot(type, actionId, pkg, label, launchMode)
        }

        /** 解析单个槽位 JSON。 */
        fun decode(text: String): NavSlot? = fromJson(JsonLite.parseObject(text))
    }
}

object NavSlots {

    const val MAX_COUNTED = 24
    const val LAYOUT_REV = 5

    const val KEY_SLOTS_JSON = "navbar_slots_json"
    const val KEY_SLOTS_LAYOUT_REV = "navbar_slots_layout_rev"

    /**
     * 导航栏承载样式：**槽位条**（槽位形态）。
     *
     * 与 `ui/shell/EnhancedNavBarView.MODE_SLOTS` 是同一个值（那边是 1）。
     */
    const val NAV_STYLE_SLOTS = 1

    /**
     * 歌词该显示在**导航栏歌词槽**还是**首页中央大字歌词**。
     *
     * 歌词只出现在导航栏歌词槽
     * ；我们额外在首页加了「中央大字歌词」
     * （第二十四轮按用户要求加的视觉重心）—— 两者**只能显示一处**，
     * 否则同一首歌会在屏上出现两处歌词。
     *
     * 规则：导航栏是**槽位条**形态、且槽位里确实配了歌词槽 → 歌词归导航栏；
     * 其余情况（默认的设计 Dock 形态 / 没配歌词槽）→ 歌词留在首页中央。
     *
     * 默认 15 槽里**没有**歌词槽
     * 和我们的一致 —— 歌词槽要用户自己在「编辑槽位」里加。
     *
     * @param enhancedNav 增强导航栏开关
     * @param navStyle 导航栏承载样式（0 = 设计 Dock，1 = 槽位条）
     * @param slots 当前槽位表
     */
    fun lyricBelongsToNavBar(
        enhancedNav: Boolean,
        navStyle: Int,
        slots: List<NavSlot>,
    ): Boolean = enhancedNav &&
        navStyle == NAV_STYLE_SLOTS &&
        slots.any { it.type == NavSlot.TYPE_LYRIC }

    /**
     * 22 项系统动作，顺序 = 槽位管理列表的展示顺序
     *
     */
    val CATALOG: List<Pair<String, String>> = listOf(
        NavSlot.ACTION_BACK to "返回",
        NavSlot.ACTION_HOME to "主页",
        NavSlot.ACTION_SETTINGS to "设置",
        NavSlot.ACTION_TASKS to "应用列表",
        NavSlot.ACTION_SPLIT to "分屏",
        NavSlot.ACTION_CAMERA_360 to "360环影",
        NavSlot.ACTION_MEDIA to "媒体",
        NavSlot.ACTION_LYRIC to "歌词",
        NavSlot.ACTION_DATE to "日期",
        NavSlot.ACTION_DATETIME_HMS to "日期时间（日期 时分秒）",
        NavSlot.ACTION_DATETIME_HM to "日期时间（日期 时分）",
        NavSlot.ACTION_DATETIME_HM_WEEK to "日期时间（日期 时分 星期）",
        NavSlot.ACTION_DATETIME_HMS_WEEK to "日期时间（日期 时分秒 星期）",
        NavSlot.ACTION_HVAC_POWER to "空调电源",
        NavSlot.ACTION_HVAC_TEMP_DRIVER to "主驾温度",
        NavSlot.ACTION_HVAC_FAN to "风扇",
        NavSlot.ACTION_HVAC_TEMP_PASSENGER to "副驾温度",
        NavSlot.ACTION_HVAC_AC to "AC",
        NavSlot.ACTION_HVAC_AUTO to "AUTO",
        NavSlot.ACTION_HVAC_CIRC to "内外循环",
        NavSlot.ACTION_HVAC_DEFROST_FRONT to "前除霜",
        NavSlot.ACTION_HVAC_DEFROST_REAR to "后除霜",
    )

    private val HVAC_ACTIONS = setOf(
        NavSlot.ACTION_HVAC_POWER, NavSlot.ACTION_HVAC_TEMP_DRIVER, NavSlot.ACTION_HVAC_TEMP_PASSENGER,
        NavSlot.ACTION_HVAC_AC, NavSlot.ACTION_HVAC_AUTO, NavSlot.ACTION_HVAC_FAN, NavSlot.ACTION_HVAC_CIRC,
        NavSlot.ACTION_HVAC_DEFROST_FRONT, NavSlot.ACTION_HVAC_DEFROST_REAR,
    )

    /**
     * 已下线 / 已移除的动作
     * 加载时直接剔除。
     */
    val REMOVED_ACTIONS: Set<String> = setOf(
        NavSlot.ACTION_NOTIFICATION,
        NavSlot.ACTION_RECENTS,
        NavSlot.ACTION_HVAC,
        NavSlot.ACTION_POWER,
        NavSlot.ACTION_CAR,
        NavSlot.ACTION_DISPLAY_OFF,
    )

    fun actionLabel(actionId: String): String = when (actionId) {
        NavSlot.ACTION_MEDIA -> "媒体"
        NavSlot.ACTION_LYRIC -> "歌词"
        else -> CATALOG.firstOrNull { it.first == actionId }?.second ?: actionId
    }

    fun isHvacAction(actionId: String): Boolean = HVAC_ACTIONS.contains(actionId)

    fun isTempSlot(slot: NavSlot): Boolean = slot.isTempSlot

    fun isTextToggle(slot: NavSlot): Boolean = slot.isTextToggle

    fun isDatetime(slot: NavSlot): Boolean = slot.isDatetime

    /**
     * 按动作 id 造槽位
     * 媒体 / 歌词走各自的类型，日期类走 `datetime` 类型，其余是 `action`。
     */
    fun createSlotForAction(actionId: String, label: String? = null): NavSlot = when {
        actionId == NavSlot.ACTION_MEDIA -> NavSlot.createMedia()
        actionId == NavSlot.ACTION_LYRIC -> NavSlot.createLyric()
        !NavSlot.isDatetimeAction(actionId) ->
            NavSlot.createAction(actionId, label?.takeIf { it.isNotEmpty() } ?: actionLabel(actionId))
        else -> NavSlot.createDatetime(
            actionId,
            label?.takeIf { it.isNotEmpty() } ?: NavSlot.datetimeLabel(actionId),
        )
    }

    /** 默认 15 个槽位。 */
    fun defaults(): MutableList<NavSlot> = mutableListOf(
        NavSlot.createAction(NavSlot.ACTION_BACK, "返回"),
        NavSlot.createAction(NavSlot.ACTION_HOME, "主页"),
        NavSlot.createAction(NavSlot.ACTION_TASKS, "应用列表"),
        NavSlot.createAction(NavSlot.ACTION_SPLIT, "分屏"),
        NavSlot.createAction(NavSlot.ACTION_CAMERA_360, "360环影"),
        NavSlot.createMedia(),
        NavSlot.createAction(NavSlot.ACTION_HVAC_POWER, "空调电源"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_TEMP_DRIVER, "主驾温度"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_FAN, "风扇"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_TEMP_PASSENGER, "副驾温度"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_AC, "AC"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_AUTO, "AUTO"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_CIRC, "内外循环"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_DEFROST_FRONT, "前除霜"),
        NavSlot.createAction(NavSlot.ACTION_HVAC_DEFROST_REAR, "后除霜"),
    )

    fun load(prefs: Prefs): MutableList<NavSlot> {
        val rev = prefs.intValue(KEY_SLOTS_LAYOUT_REV, 0)
        if (rev < LAYOUT_REV) {
            val d = defaults()
            save(prefs, d)
            // 排障场景：槽位「自己变了」——布局版本低于 5 时会被重置成默认 15 槽
            Logx.i(
                LogTags.SETTING,
                "槽位加载：布局版本过低，重置为默认槽位",
                "rev" to rev,
                "expectRev" to LAYOUT_REV,
                "count" to d.size,
            )
            return d
        }
        val raw = prefs.stringValue(KEY_SLOTS_JSON, "")
        if (raw.isBlank()) {
            val d = defaults()
            save(prefs, d)
            // 排障场景：槽位 JSON 为空 —— 说明从没保存过，或被清库了
            Logx.i(LogTags.SETTING, "槽位加载：JSON 为空，回落默认槽位", "count" to d.size)
            return d
        }
        val parsed = decode(raw).toMutableList()
        if (parsed.isEmpty()) {
            // 与上面两个分支保持一致：回落默认后要**落盘**，
            // 否则每次读都是「解析失败 → 回落默认 → 下次继续解析失败」。
            val d = defaults()
            save(prefs, d)
            Logx.w(LogTags.SETTING, "槽位加载：解析结果为空，回落默认槽位", "rawLen" to raw.length, "count" to d.size)
            return d
        }
        if (stripRemovedActions(parsed)) save(prefs, parsed)
        // 排障场景：槽位数量/内容与预期不符时，用这条确认实际加载了几个
        Logx.d(LogTags.SETTING, "槽位加载", "count" to parsed.size, "rev" to rev)
        return parsed
    }

    fun save(prefs: Prefs, slots: List<NavSlot>) {
        prefs.setStringValue(KEY_SLOTS_JSON, encode(slots))
        prefs.setIntValue(KEY_SLOTS_LAYOUT_REV, LAYOUT_REV)
        // 排障场景：槽位保存了多少项、有没有超上限
        val counted = countedCount(slots)
        if (counted > MAX_COUNTED) {
            Logx.w(LogTags.SETTING, "槽位保存：有效槽位超过上限", "counted" to counted, "max" to MAX_COUNTED)
        } else {
            Logx.i(LogTags.SETTING, "槽位保存", "count" to slots.size, "counted" to counted, "max" to MAX_COUNTED)
        }
    }

    /** 槽位列表 → JSON 数组字符串。 */
    fun encode(slots: List<NavSlot>): String = JsonLite.write(slots.map { it.toJson() })

    /** JSON 数组字符串 → 槽位列表；旧版竖线文本自动回落解析。 */
    fun decode(raw: String): List<NavSlot> {
        if (raw.isBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            // 排障场景：槽位 JSON 损坏（被别的 App 写坏、手改偏好库等）—— 解析失败必须留痕
            if (JsonLite.parse(trimmed) == null) {
                Logx.w(LogTags.SETTING, "槽位 JSON 解析失败", "rawLen" to trimmed.length, "raw" to trimmed.take(200))
                return emptyList()
            }
            val list = JsonLite.parseArray(trimmed).mapNotNull { NavSlot.fromJson(it) }
            if (list.isEmpty()) {
                Logx.w(LogTags.SETTING, "槽位 JSON 解析后没有可用条目", "rawLen" to trimmed.length)
            }
            return list
        }
        // 旧版竖线文本格式（0.x 的老数据）
        Logx.i(LogTags.SETTING, "槽位 JSON：命中旧版竖线文本格式，按兼容解析")
        return decodeLegacy(trimmed)
    }

    /**
     * 剔除已下线动作并修正标签。
     *
     * - `notification / recents / hvac / power / car / display_off` 直接删掉；
     * - `tasks` 的旧标签「多任务」修正成「应用列表」；
     * - `hvac_defrost_rear` 的旧标签「后除雾」或空标签修正成「后除霜」。
     *
     * @return true 表示有改动（调用方需要回写）
     */
    fun stripRemovedActions(slots: MutableList<NavSlot>): Boolean {
        var changed = false
        val removed = ArrayList<String>()
        val fixedLabels = ArrayList<String>()
        for (i in slots.indices.reversed()) {
            val slot = slots[i]
            if (slot.type != NavSlot.TYPE_ACTION) continue
            if (REMOVED_ACTIONS.contains(slot.actionId)) {
                slots.removeAt(i)
                changed = true
                removed.add(slot.actionId)
                continue
            }
            val fixed = when {
                slot.actionId == NavSlot.ACTION_TASKS && slot.label == "多任务" -> "应用列表"
                slot.actionId == NavSlot.ACTION_HVAC_DEFROST_REAR &&
                    (slot.label == "后除雾" || slot.label.isEmpty()) -> "后除霜"
                else -> null
            }
            if (fixed != null) {
                slots[i] = slot.copy(label = fixed)
                changed = true
                fixedLabels.add("${slot.actionId}:${slot.label}->$fixed")
            }
        }
        // 排障场景：槽位里的动作「自己变了」—— 下线动作被清理、标签被修正
        if (removed.isNotEmpty() || fixedLabels.isNotEmpty()) {
            Logx.i(
                LogTags.SETTING,
                "槽位清理已下线动作/修正标签",
                "removed" to removed.joinToString(","),
                "fixedLabels" to fixedLabels.joinToString(","),
                "count" to slots.size,
            )
        }
        return changed
    }

    /** 有效（计入上限）槽位数量，空槽不算。 */
    fun countedCount(slots: List<NavSlot>): Int = slots.count { it.countsTowardLimit }

    fun canAddCountedSlot(slots: List<NavSlot>): Boolean = countedCount(slots) < MAX_COUNTED

    // ------------------------------------------------------------------ 旧格式兜底

    /** 本应用 0.x 的竖线文本格式（`type|actionId|pkg|label|launchMode`），只用于读旧数据。 */
    private fun decodeLegacy(raw: String): List<NavSlot> =
        raw.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 2) return@mapNotNull null
            val type = parts.getOrElse(0) { NavSlot.TYPE_ACTION }
            val actionId = unesc(parts.getOrElse(1) { "" })
            val pkg = unesc(parts.getOrElse(2) { "" })
            val label = unesc(parts.getOrElse(3) { "" })
            val mode = NavSlot.normalizeLaunchMode(unesc(parts.getOrElse(4) { NavSlot.LAUNCH_FULLSCREEN }))
            NavSlot(type, actionId, pkg, label, mode)
        }

    fun esc(s: String): String = s.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")

    fun unesc(s: String): String = s.replace("%7C", "|").replace("%0A", "\n").replace("%25", "%")
}
