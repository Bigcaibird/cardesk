package com.cardesk.launcher.core

/**
 * 桌面「快捷车控」卡片的可自定义槽位配置模型（纯 Kotlin，可 JVM 单测）。
 *
 * - 支持全车 19 项已打通的一键车控动作（座椅舒适 / 空调快捷 / 车身视野 / 驾驶座舱）；
 * - 允许用户自由增删与排序，保留 1~4 个槽位；
 * - 默认配置为 `[主驾加热, 主驾通风, 最大风挡]`，与历史版本完全一致。
 */
object SceneQuickControls {

    const val MIN_SLOTS = 1
    const val MAX_SLOTS = 4

    /** 持久化键：逗号分隔的车控动作 ID 列表。 */
    const val KEY = "scene_quick_controls_v1"

    // —— 座椅舒适（4 项，三档循环 0→1→2→3→0）——
    const val SEAT_HEAT_DRIVER = "seat_heat_driver"
    const val SEAT_VENT_DRIVER = "seat_vent_driver"
    const val SEAT_HEAT_PASSENGER = "seat_heat_passenger"
    const val SEAT_VENT_PASSENGER = "seat_vent_passenger"

    // —— 空调快捷（8 项）——
    const val MAX_FAN = "max_fan"
    const val HVAC_POWER = "hvac_power"
    const val HVAC_AC = "hvac_ac"
    const val HVAC_AUTO = "hvac_auto"
    const val HVAC_CIRC = "hvac_circ"
    const val DEFROST_FRONT = "defrost_front"
    const val DEFROST_REAR = "defrost_rear"
    const val HVAC_SYNC = "hvac_sync"

    // —— 车身与视野（4 项）——
    const val CAM_360 = "cam_360"
    const val WINDOWS_CLOSE = "windows_close"
    const val WINDOWS_OPEN = "windows_open"
    const val ISS_TOGGLE = "iss_toggle"

    // —— 驾驶与座舱（3 项）——
    const val DRIVE_MODE_CYCLE = "drive_mode_cycle"
    const val AMBIENT_TOGGLE = "ambient_toggle"
    const val HOTSPOT_TOGGLE = "hotspot_toggle"

    enum class Category(val title: String) {
        SEAT("座椅舒适"),
        HVAC("空调快捷"),
        BODY("车身与视野"),
        CABIN("驾驶与座舱"),
    }

    data class ControlDef(
        val id: String,
        val label: String,
        val description: String,
        val category: Category,
        val hasLevels: Boolean = false,
    )

    /** 全车目前支持的一键车控清单（共 19 项，均已打通真实车控通道）。 */
    val ALL: List<ControlDef> = listOf(
        // 座椅舒适
        ControlDef(SEAT_HEAT_DRIVER, "主驾加热", "三档循环切换", Category.SEAT, hasLevels = true),
        ControlDef(SEAT_VENT_DRIVER, "主驾通风", "三档循环切换", Category.SEAT, hasLevels = true),
        ControlDef(SEAT_HEAT_PASSENGER, "副驾加热", "三档循环切换", Category.SEAT, hasLevels = true),
        ControlDef(SEAT_VENT_PASSENGER, "副驾通风", "三档循环切换", Category.SEAT, hasLevels = true),
        // 空调快捷
        ControlDef(MAX_FAN, "最大风挡", "MAX 7↔3 切换", Category.HVAC),
        ControlDef(HVAC_POWER, "空调电源", "一键开/关空调", Category.HVAC),
        ControlDef(HVAC_AC, "A/C 制冷", "压缩机开关", Category.HVAC),
        ControlDef(HVAC_AUTO, "AUTO 自动", "自动空调开关", Category.HVAC),
        ControlDef(HVAC_CIRC, "内外循环", "内循环↔外循环", Category.HVAC),
        ControlDef(DEFROST_FRONT, "前除霜", "前风挡除霜开关", Category.HVAC),
        ControlDef(DEFROST_REAR, "后除霜", "后窗/后视镜除霜", Category.HVAC),
        ControlDef(HVAC_SYNC, "双区同步", "主副驾温度同步", Category.HVAC),
        // 车身与视野
        ControlDef(CAM_360, "360 全景", "一键开/关环影", Category.BODY),
        ControlDef(WINDOWS_CLOSE, "一键关窗", "四扇车窗全关", Category.BODY),
        ControlDef(WINDOWS_OPEN, "一键透气", "四扇车窗半开", Category.BODY),
        ControlDef(ISS_TOGGLE, "自动启停", "切换发动机启停", Category.BODY),
        // 驾驶与座舱
        ControlDef(DRIVE_MODE_CYCLE, "驾驶模式", "按顺序循环切换", Category.CABIN),
        ControlDef(AMBIENT_TOGGLE, "氛围灯", "音乐氛围灯开关", Category.CABIN),
        ControlDef(HOTSPOT_TOGGLE, "车载热点", "Wi-Fi 热点开关", Category.CABIN),
    )

    private val BY_ID: Map<String, ControlDef> = ALL.associateBy { it.id }

    /** 默认 3 个槽位（与原版快捷车控卡片完全一致）。 */
    val DEFAULT_SLOTS: List<String> = listOf(
        SEAT_HEAT_DRIVER,
        SEAT_VENT_DRIVER,
        MAX_FAN,
    )

    fun defOf(id: String): ControlDef? = BY_ID[id]

    /**
     * 返回该车控项在 `ScenePage.hits` 中的命中键。
     *
     * 默认三项保留历史键名（`seatHeat` / `seatVent` / `maxFan`），确保既有交互与回归测试完全兼容。
     */
    fun hitKeyFor(id: String): String = when (id) {
        SEAT_HEAT_DRIVER -> "seatHeat"
        SEAT_VENT_DRIVER -> "seatVent"
        MAX_FAN -> "maxFan"
        else -> "qctrl_$id"
    }

    /** 解析逗号分隔字符串，过滤未知项与重复项，空时回落 [DEFAULT_SLOTS]。 */
    fun parse(csv: String): List<String> {
        if (csv.isBlank()) return DEFAULT_SLOTS
        return normalize(csv.split(','))
    }

    fun toCsv(ids: List<String>): String = normalize(ids).joinToString(",")

    /** 规整为合法 ID 列表（1..4 项，去重、剔除未知项；结果为空时回落默认）。 */
    fun normalize(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>(MAX_SLOTS)
        for (item in raw) {
            val id = item.trim()
            if (id in BY_ID && seen.size < MAX_SLOTS) {
                seen.add(id)
            }
        }
        return if (seen.isEmpty()) DEFAULT_SLOTS else seen.toList()
    }

    /** 追加一个车控项（已存在或已满 [MAX_SLOTS] 时原样返回）。 */
    fun add(current: List<String>, id: String): List<String> {
        val base = normalize(current)
        if (id !in BY_ID || id in base || base.size >= MAX_SLOTS) return base
        return base + id
    }

    /** 移除一个车控项（至少保留 [MIN_SLOTS] 项，避免卡片空白）。 */
    fun remove(current: List<String>, id: String): List<String> {
        val base = normalize(current)
        if (base.size <= MIN_SLOTS || id !in base) return base
        return base.filterNot { it == id }
    }

    /** 交换相邻或指定两个位置，用于调整卡片内按钮顺序。 */
    fun swap(current: List<String>, from: Int, to: Int): List<String> {
        val out = normalize(current).toMutableList()
        if (from !in out.indices || to !in out.indices || from == to) return out
        val tmp = out[from]
        out[from] = out[to]
        out[to] = tmp
        return out
    }

    interface Store {
        fun load(): List<String>
        fun save(ids: List<String>)
        fun resetDefault()
    }

    class PrefsStore(private val prefs: Prefs) : Store {
        override fun load(): List<String> = parse(prefs.stringValue(KEY, ""))
        override fun save(ids: List<String>) {
            prefs.setStringValue(KEY, toCsv(ids))
        }
        override fun resetDefault() {
            prefs.setStringValue(KEY, "")
        }
    }
}
