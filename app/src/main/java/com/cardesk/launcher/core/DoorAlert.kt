package com.cardesk.launcher.core

/**
 * 车门未关告警（第136轮，**纯逻辑**，可 JVM 单测）。
 *
 * ## 它回答三个问题
 *
 * 1. **现在有哪些门/盖是开着的？** → [openItems]
 * 2. **要不要弹窗？** → [shouldShow]
 * 3. **弹窗上写什么？** → [title] / [summary]
 *
 * ## 口径：读不到 ≠ 开着
 *
 * 车门信号有三种状态：**关 / 开 / 读不到**（`null`）。
 * 这三者必须分清 ——
 * - 「关」→ 不提示；
 * - 「开」→ 提示，并且**具体到是哪一扇门**；
 * - 「读不到」→ **不提示**（也不当成「关」）。
 *
 * 为什么「读不到」不提示：这套信号来自车机的属性/传感器通道，
 * 开机瞬间、信号缺失、服务重连时都会短暂读不到。
 * 拿「读不到」去弹「有车门未关」是**误报**，
 * 而误报一次用户就不信这个提示了（比不提示更糟）。
 * 这条口径与 [VehicleStats.interpretDoorClosed] 一致。
 *
 * ## 为什么把引擎盖 / 后备箱也算进来
 *
 * 用户要的是「有车未关」，不只是四扇门。
 * 车况卡本来就同时画门 / 窗 / 引擎盖 / 后备箱，
 * 而引擎盖没关、后备箱没关同样是「车没关好」，
 * 只提示四扇门会让提示自相矛盾（卡片显示引擎盖开着，弹窗却说都关好了）。
 * **车窗不算** —— 车窗开着不是「没关好」，不影响告警。
 */
object DoorAlert {

    /** 未关部件的种类。 */
    enum class Kind {
        /** 四扇门之一（[Item.index] 是 0..3）。 */
        DOOR,

        /** 引擎盖。 */
        HOOD,

        /** 后备箱。 */
        TRUNK,
    }

    /**
     * 一个未关的部件。
     *
     * @param kind 种类
     * @param index 门序号（0..3，对应 [CarStatus.DOOR_LABELS]）；非门时为 0
     * @param label 展示名（左前门 / 引擎盖 / 后备箱…）
     */
    data class Item(val kind: Kind, val index: Int, val label: String)

    /**
     * 列出当前**开着**的门 / 盖。
     *
     * @param doorsClosed 四门状态：`true` = 关，`false` = 开，`null` = 读不到
     * @param hoodClosed 引擎盖：`true` = 关，`false` = 开，`null` = 读不到
     * @param trunkClosed 后备箱：同上
     * @return 开着的部件（按「门 → 引擎盖 → 后备箱」顺序）；没有就返回空列表
     */
    fun openItems(
        doorsClosed: List<Boolean?>?,
        hoodClosed: Boolean?,
        trunkClosed: Boolean?,
    ): List<Item> {
        val out = ArrayList<Item>(6)
        for (i in 0 until 4) {
            // 读不到（null）→ 跳过，不当成「关」，也不当成「开」。
            val closed = doorsClosed?.getOrNull(i) ?: continue
            if (!closed) out.add(Item(Kind.DOOR, i, CarStatus.DOOR_LABELS[i]))
        }
        if (hoodClosed == false) out.add(Item(Kind.HOOD, 0, HOOD_LABEL))
        if (trunkClosed == false) out.add(Item(Kind.TRUNK, 0, TRUNK_LABEL))
        return out
    }

    /** 现在该不该弹窗（有开着的东西就弹）。 */
    fun shouldShow(
        doorsClosed: List<Boolean?>?,
        hoodClosed: Boolean?,
        trunkClosed: Boolean?,
    ): Boolean = openItems(doorsClosed, hoodClosed, trunkClosed).isNotEmpty()

    /**
     * 弹窗标题。
     *
     * 一处未关 → 「有车门未关」；多处 → 「N 处未关」。
     */
    fun title(count: Int): String = when {
        count <= 0 -> ""
        count == 1 -> "有车门未关"
        else -> "$count 处未关"
    }

    /**
     * 弹窗正文（具体到哪几个）。
     *
     * 例：「左前门、右后门、引擎盖」。
     */
    fun summary(items: List<Item>): String = items.joinToString("、") { it.label }

    /**
     * 副标题：把「哪几个」和「干什么」讲清楚。
     *
     * 例：「左前门、右后门、引擎盖 还没关好，请检查」。
     */
    fun detail(items: List<Item>): String {
        if (items.isEmpty()) return ""
        return summary(items) + " 还没关好，请检查"
    }

    /** 引擎盖名（与车况卡一致）。 */
    const val HOOD_LABEL: String = "引擎盖"

    /** 后备箱名（与车况卡一致）。 */
    const val TRUNK_LABEL: String = "后备箱"
}
