package com.cardesk.launcher.core

/**
 * 桌面底部卡片的布局模型（第九十五轮 C2）。
 *
 * 从「只有顺序」升级为「顺序 + 尺寸档位 + 显隐」：
 * - [Slot.size] 决定这张卡的宽度档位（自适应 / 小 / 中 / 大）；
 * - [Slot.visible] 决定是否参与排布与绘制；
 * - 布局整体按 [encode] 落到偏好键 [KEY]（一行文本，便于诊断包排查）。
 *
 * 顺序拖拽仍由场景页处理：[move] 操作的是完整布局，隐藏卡不参与拖拽。
 */
object CardLayout {

    const val QUICK = "quick"
    const val MUSIC = "music"
    const val CLIMATE = "climate"
    const val TIRE = "tire"

    /**
     * 续航卡（底部卡片行左起第一张）：里程大字 + 油量条 + 「燃料剩余」。
     *
     * ## 为什么持久化 id 还叫 "car"
     *
     * 这个 id 是**参考 App（君君桌面）**的 `sceneCarCard`（它的底部卡 ids 是
     * 快捷 / 媒体 / 空调 / 胎压 / **续航**）—— 参考 App（君君桌面）那张 `sceneCarCard`
     * 画的就是续航，不是「车况」（我们自己的注释也写着
     * 「参考 `SceneDesktop.disableShortClick(sceneCarCard)`（:703）：续航卡不可点」）。
     *
     * cardesk 的「车况卡」（车门 / 车窗 / 引擎盖 / 后备箱的俯视车模）
     * 是**另一张卡**：固定画在右侧，不进底部卡片行，见 [CAR_STATUS]。
     *
     * ## 持久化值保持 "car" 不动
     *
     * `scene_card_layout_v2` 里存的就是这个字符串；改值会让老设备的布局
     * 整份读不出来（[decode] 认不出 id 直接回退默认布局），所以只改
     * Kotlin 里的常量名与显示名，**不动线上的 id**。
     *
     * ## 第111轮修的就是这个撞名
     *
     * 以前这个槽位的标题写成「车况」，于是设置页「桌面卡片 → 车况」的开关
     * 关掉的是**续航卡**，而真正的车况卡（右侧那张）压根没有开关。
     */
    const val RANGE = "car"

    /**
     * 车况卡（**右侧固定卡**，不进底部卡片行）。
     *
     * 车门 / 车窗 / 引擎盖 / 后备箱的俯视车模 + 状态图例，
     * 固定位置固定宽度（388×250），画法见 `ScenePage.drawCarStatusCard`。
     *
     * 它和底部卡片行是两套东西，所以：
     * - 有自己的 id（[title] 里叫「车况」）；
     * - 有自己的显隐开关（`Prefs.carStatusCardVisible`）；
     * - **不在 [ALL] 里**（进了就会被 [widths] 当成行内卡分宽度）。
     */
    const val CAR_STATUS = "carstatus"

    /**
     * 行程卡（第十二轮起只留这一张）：本次行程的里程 / 时长 / 平均油耗 / 总里程。
     *
     * ## 为什么持久化 id 还叫 "trip1"
     *
     * 第九十六轮把它拆成「行程1（总）/ 行程2（自复位）」两张，
     * 持久化的 id 是 `trip1`；第十二轮按用户要求**去掉行程2**，
     * 这张改叫「行程卡」——
     * 但**持久化 id 不动**（老设备布局里存的就是 `trip1`，
     * 改 id 会让整份布局读不出来）。
     *
     * ## 里程 / 时长的数据源也换了（第十二轮）
     *
     * 这两项原来读 VHAL `TS_TripDistance / TS_TripTime`，
     * 而真车上那两个属性**读不到**（诊断包实测：
     * `行程1 原始信号 distanceRaw=null distanceVia=- format=-1 timeRaw=null`）。
     *
     * 现在改读 `ITripData.getLatestDrivingInfo()` ——
     * 也就是**原来行程2 那张卡**的数据源，那条路是通的
     * （诊断包：`行程里程/时长：8.5 km / 31.0 分钟`）。
     */
    const val TRIP_CARD = "trip1"

    /**
     * 第112轮移除的「行程2（自复位）」卡的 id。
     *
     * **只用于迁移**：老设备布局里可能还留着 `trip2`，
     * 解码时要**丢掉它**（见 [decode]）——
     * 而不能让整份布局读不出来（那会把用户攒下来的顺序 / 尺寸 / 显隐全冲掉）。
     */
    const val LEGACY_TRIP2 = "trip2"
    const val STATUS = "status"

    /** 旧 id（第九十五轮的单张行程卡）；只用于迁移，不再出现在 [ALL] 里。 */
    const val LEGACY_TRIP = "trip"

    /**
     * 全部可布局的卡片，顺序即默认顺序。
     *
     * 注意：[CAR_STATUS] **不在**这里——它是右侧固定卡，不参与底部卡片行的排布与分宽。
     *
     * 第112轮：行程2 卡被移除（[LEGACY_TRIP2] 只用于迁移），只剩一张 [TRIP_CARD]。
     */
    val ALL: List<String> = listOf(QUICK, MUSIC, CLIMATE, TIRE, RANGE, TRIP_CARD, STATUS)

    /** 默认就显示的卡（其余从卡片库添加）。 */
    /**
     * 默认可见的卡（第九十六轮起）：**快捷应用卡默认移除**，
     * 原来在天气下面那张「行程数据」挪到底部卡片行（左起第一张）。
     *
     * 需求原文：「1.把快捷应用卡片默认移除，把天气下面的行程卡挪过来」——
     * 后续第九十六轮补丁把续航与行程1 **位置互换**，所以现在默认布局是
     * 续航 / 音乐 / 空调 / 胎压 / 行程卡；快捷应用卡收进卡片库，需要时自己加。
     */
    private val DEFAULT_VISIBLE = setOf(TRIP_CARD, MUSIC, CLIMATE, TIRE, RANGE)

    /** 偏好键（第九十五轮起，替代旧的 `scene_bottom_card_order_v1`）。 */
    const val KEY = "scene_card_layout_v2"

    /** 卡片中文名（设置页、日志用）。 */
    fun title(id: String): String = when (id) {
        QUICK -> "快捷应用"
        MUSIC -> "音乐"
        CLIMATE -> "空调"
        TIRE -> "胎压"
        RANGE -> "续航"
        CAR_STATUS -> "车况"
        TRIP_CARD -> "行程卡"
        STATUS -> "车门车窗"
        LEGACY_TRIP -> "行程"
        else -> id
    }

    /** 把历史 id 迁移到当前 id（目前只有 `trip` → `trip1`）。 */
    fun migrate(id: String): String = if (id == LEGACY_TRIP) TRIP_CARD else id

    /** 「快捷应用 → 行程1」一次性迁移的偏好标记（第九十六轮）。 */
    const val MIGRATE_QUICK_KEY = "card_layout_quick_to_trip_v1"
    /** 「行程/续航尺寸互换」一次性迁移的偏好标记（第九十六轮补丁 5）。 */
    const val MIGRATE_SIZES_KEY = "card_layout_sizes_v1"

    /**
     * 一次性把行程1/续航卡固定成各自的互换后尺寸。
     *
     * 用户口径「行程和续航大小互换」：行程卡 ≈311、续航卡 ≈420。
     * 之前两张都是「自适应」，遇到用户加过别的卡（剩余宽度不够）时续航会被挤小，
     * 看起来就像没互换；这里把两张卡都固定成 MEDIUM 档，宽度就稳定了。
     */
    fun migrateSizes(layout: List<Slot>): List<Slot> = layout.map { slot ->
        when (slot.id) {
            TRIP_CARD -> slot.copy(size = Size.MEDIUM)
            RANGE -> slot.copy(size = Size.MEDIUM)
            else -> slot
        }
    }

    /** 「续航 ↔ 行程1 位置互换」一次性迁移的偏好标记（第九十六轮补丁 7）。 */
    const val MIGRATE_SWAP_KEY = "card_layout_swap_car_trip_v1"

    /**
     * 一次性把续航卡与行程1卡的位置互换。
     *
     * 用户口径：让「续航」排到最左、「行程1」排到原来续航的位置；
     * 只换位置，不动各自的尺寸/显隐。
     */
    fun migrateSwapCarTrip(layout: List<Slot>): List<Slot> {
        val carIndex = layout.indexOfFirst { it.id == RANGE }
        val tripIndex = layout.indexOfFirst { it.id == TRIP_CARD }
        if (carIndex < 0 || tripIndex < 0 || carIndex == tripIndex) return layout
        val out = layout.toMutableList()
        val car = layout[carIndex]
        val trip = layout[tripIndex]
        out[carIndex] = trip.copy(size = car.size, visible = car.visible)
        out[tripIndex] = car.copy(size = trip.size, visible = trip.visible)
        return out
    }

    /**
     * 一次性迁移（第九十六轮）：把快捷应用卡收进卡片库，行程1 挪到最左并显示。
     *
     * 只在升级后的第一次读到布局时执行，之后用户怎么拖都不会再被动。
     * 其它卡的位置 / 尺寸 / 显隐原样保留。
     */
    fun migrateQuickToTrip(layout: List<Slot>): List<Slot> {
        val trip = layout.firstOrNull { it.id == TRIP_CARD } ?: Slot(TRIP_CARD, Size.AUTO, true)
        val rest = layout.filter { it.id != TRIP_CARD && it.id != QUICK }
        val quick = layout.firstOrNull { it.id == QUICK } ?: Slot(QUICK, Size.AUTO, false)
        return normalize(listOf(trip.copy(visible = true)) + rest + quick.copy(visible = false))
    }

    /**
     * 旧「行程」卡迁移时**一律按隐藏处理**（第九十六轮修正）。
     *
     * 需求原文：「不要默认加上来，做到卡片库里就行」——
     * 行程1/行程2 是卡片库能力，升级不替用户做决定：之前显示过旧行程卡的用户，
     * 升级后行程1 也先收进卡片库，需要时自己点「添加」。
     */
    private fun legacySlot(slot: Slot): Slot = Slot(TRIP_CARD, slot.size, visible = false)

    /**
     * 尺寸档位。
     *
     * [AUTO] = 自适应：普通卡取自然宽度；**续航卡**（[RANGE]，底部卡片行里那张）
     * 设为自适应时吃掉行尾剩余宽度（见 [widths]）。
     * 其余档位按自然宽 × [scale] 缩放。
     */
    enum class Size(val label: String, val scale: Float) {
        AUTO("自适应", 1f),
        SMALL("小", 0.82f),
        MEDIUM("中", 1f),
        LARGE("大", 1.25f),
    }

    data class Slot(val id: String, val size: Size = Size.AUTO, val visible: Boolean = true)

    /** 一张卡在默认布局里的样子（新增卡默认隐藏，等用户从卡片库添加）。 */
    fun defaultSlot(id: String): Slot = Slot(id, Size.AUTO, visible = id in DEFAULT_VISIBLE)

    /**
     * 默认布局：续航在左起第一张（原来快捷应用卡的位置），
     * 依次是 音乐 / 空调 / 胎压 / 行程卡；快捷应用、车门车窗只进卡片库。
     *
     * 第112轮：行程2 卡被移除，默认布局里不再有它。
     */
    val DEFAULT: List<Slot> = listOf(
        defaultSlot(RANGE).copy(size = Size.MEDIUM),
        defaultSlot(QUICK),
        defaultSlot(MUSIC),
        defaultSlot(CLIMATE),
        defaultSlot(TIRE),
        defaultSlot(TRIP_CARD).copy(size = Size.MEDIUM),
        defaultSlot(STATUS),
    )

    /** 编码成一行：`quick:AUTO:1,music:MEDIUM:1,...`。 */
    fun encode(layout: List<Slot>): String =
        layout.joinToString(",") { "${it.id}:${it.size.name}:${if (it.visible) 1 else 0}" }

    /** 解析；任何非法/重复/缺项都返回 null（调用方应回退默认布局）。 */
    fun decode(raw: String?): List<Slot>? {
        if (raw.isNullOrBlank()) return null
        val parsed = ArrayList<Slot>()
        for (part in raw.split(',')) {
            if (part.isBlank()) continue
            val fields = part.split(':')
            if (fields.size != 3) return null
            // 第112轮：行程2 卡被移除 —— 老布局里残留的 `trip2` **丢掉它**。
            //
            // 不能让它走下面那句 `if (id !in ALL) return null`：
            // 那样整份布局都读不出来 → 调用方回退默认布局 →
            // 用户攒下来的顺序 / 尺寸 / 显隐**全被冲掉**。
            // 丢一张卡只是少一张卡，代价小得多。
            if (fields[0] == LEGACY_TRIP2) continue
            val legacy = fields[0] == LEGACY_TRIP
            val id = migrate(fields[0])
            if (id !in ALL) return null
            val size = Size.entries.firstOrNull { it.name == fields[1] } ?: return null
            val visible = when (fields[2]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            if (parsed.any { it.id == id }) return null
            // 旧行程卡即使存的是「可见」，也按卡片库能力处理：升级不自动上桌。
            parsed.add(Slot(id, size, if (legacy) false else visible))
        }
        return if (parsed.isEmpty()) null else normalize(parsed)
    }

    /** 去重、补缺、丢弃未知 id；保持已有顺序。 */
    fun normalize(layout: List<Slot>): List<Slot> {
        val out = ArrayList<Slot>(ALL.size)
        for (slot in layout) {
            val id = migrate(slot.id)
            if (id !in ALL) continue
            if (out.any { it.id == id }) continue
            out.add(if (id == slot.id) slot else legacySlot(slot))
        }
        for (id in ALL) {
            if (out.none { it.id == id }) out.add(defaultSlot(id))
        }
        if (out.none { it.visible }) {
            // 至少留一张可见，否则桌面底部会整条消失。
            val idx = out.indexOfFirst { it.id == RANGE }.takeIf { it >= 0 } ?: 0
            out[idx] = out[idx].copy(visible = true)
        }
        return out
    }

    /** 把 [from] 位置挪到 [to]（索引基于完整布局；越界不做修改）。 */
    fun move(layout: List<Slot>, from: Int, to: Int): List<Slot> {
        if (from == to) return layout
        if (from !in layout.indices || to !in layout.indices) return layout
        val out = layout.toMutableList()
        val item = out.removeAt(from)
        out.add(to, item)
        return out
    }

    fun withSize(layout: List<Slot>, id: String, size: Size): List<Slot> =
        layout.map { if (it.id == id) it.copy(size = size) else it }

    /** 显示/隐藏一张卡；不允许把最后一张可见卡也隐藏。 */
    fun withVisible(layout: List<Slot>, id: String, visible: Boolean): List<Slot> {
        if (!visible && layout.count { it.visible } <= 1 && layout.any { it.id == id && it.visible }) return layout
        return layout.map { if (it.id == id) it.copy(visible = visible) else it }
    }

    fun visible(layout: List<Slot>): List<Slot> = layout.filter { it.visible }

    /**
     * 计算一条横排里每张卡的宽度。
     *
     * @param rowWidth 可用宽度
     * @param gap 卡间距
     * @param naturalWidth 该卡的自然/固定宽度（不含档位缩放）
     * @param minWidth 最小宽度保护（续航卡自适应时的兜底宽度）
     * @return 可见卡按顺序的 (id, width)；不会返回隐藏卡。
     */
    fun widths(
        layout: List<Slot>,
        rowWidth: Float,
        gap: Float,
        naturalWidth: (String) -> Float,
        minWidth: Float = 160f,
    ): List<Pair<String, Float>> {
        val slots = visible(layout)
        if (slots.isEmpty()) return emptyList()
        val gaps = gap * (slots.size - 1)
        val base = slots.map { slot ->
            if (slot.id == RANGE && slot.size == Size.AUTO) 0f else naturalWidth(slot.id) * slot.size.scale
        }
        val leftover = rowWidth - base.sum() - gaps
        val widths = base.toMutableList()
        val rangeIndex = slots.indexOfFirst { it.id == RANGE && it.size == Size.AUTO }
        if (rangeIndex >= 0) {
            // 续航卡自适应：吃掉行尾剩余宽度。卡太多/太宽时把其它「自适应」卡按比例收窄，
            // 保证整行不溢出（否则第 6、7 张卡会把续航卡挤到屏幕外）。
            if (leftover < minWidth) {
                val others = slots.indices.filter { it != rangeIndex && slots[it].size == Size.AUTO }
                val totalOthers = others.sumOf { base[it].toDouble() }.toFloat()
                val need = minWidth - leftover
                if (totalOthers > 0f) {
                    val keep = (1f - need / totalOthers).coerceIn(0.25f, 1f)
                    for (i in others) widths[i] = base[i] * keep
                }
                widths[rangeIndex] = minWidth
            } else {
                widths[rangeIndex] = leftover
            }
        } else {
            // 续航卡隐藏或被设成固定档位：剩余宽度在「自适应」卡之间均分/分摊。
            val targets = slots.indices.filter { slots[it].size == Size.AUTO }
            val pool = targets.ifEmpty { listOf(slots.lastIndex) }
            if (leftover >= 0f) {
                val share = leftover / pool.size
                for (i in pool) widths[i] += share
            } else {
                // 固定档位太宽：自适应卡按比例收窄，保证整行不溢出。
                val totalAuto = pool.sumOf { base[it].toDouble() }.toFloat()
                val deficit = -leftover
                if (totalAuto > 0f) {
                    val keep = (1f - deficit / totalAuto).coerceIn(0.4f, 1f)
                    for (i in pool) widths[i] = base[i] * keep
                } else {
                    widths[pool.last()] = (widths[pool.last()] + leftover).coerceAtLeast(minWidth)
                }
            }
        }
        return slots.mapIndexed { i, slot -> slot.id to widths[i].coerceAtLeast(minWidth * 0.75f) }
    }

    /** 顺序持久化通道（由宿主注入，例如 [PrefsStore]）。 */
    interface Store {
        fun load(): List<Slot>?
        fun save(layout: List<Slot>)
    }

    class PrefsStore(private val prefs: Prefs) : Store {
        override fun load(): List<Slot>? {
            val saved = decode(prefs.stringValue(KEY, ""))
            if (saved == null) {
                // 全新安装：DEFAULT 本身就是迁移后的最终形态，直接落盘并把迁移标记置位，
                // 避免「先迁移快捷应用、再互换位置」把默认顺序又挪一遍。
                save(DEFAULT)
                prefs.setFlag(MIGRATE_QUICK_KEY, true)
                prefs.setFlag(MIGRATE_SIZES_KEY, true)
                prefs.setFlag(MIGRATE_SWAP_KEY, true)
                return DEFAULT
            }
            var current: List<Slot>? = saved
            if (!prefs.flag(MIGRATE_QUICK_KEY, false)) {
                // 第九十六轮一次性迁移：快捷应用收进卡片库、行程1 挪到最左。
                current = migrateQuickToTrip(current ?: DEFAULT)
                save(current)
                prefs.setFlag(MIGRATE_QUICK_KEY, true)
            }
            if (!prefs.flag(MIGRATE_SIZES_KEY, false)) {
                // 第九十六轮补丁 5：行程/续航互换后的尺寸固定下来，避免被剩余宽度挤变形。
                current = migrateSizes(current ?: DEFAULT)
                save(current)
                prefs.setFlag(MIGRATE_SIZES_KEY, true)
            }
            if (!prefs.flag(MIGRATE_SWAP_KEY, false)) {
                // 第九十六轮补丁 7：续航卡与行程1卡位置互换（续航排最左、行程1 收尾）。
                current = migrateSwapCarTrip(current ?: DEFAULT)
                save(current)
                prefs.setFlag(MIGRATE_SWAP_KEY, true)
            }
            return current
        }

        override fun save(layout: List<Slot>) {
            prefs.setStringValue(KEY, encode(normalize(layout)))
        }
    }
}
