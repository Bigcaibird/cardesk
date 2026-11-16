package com.cardesk.launcher.core.car3d

/**
 * 一辆车的**策展姿态**（curated pose）：只保留「画得出来」的那几个部件。
 *
 * ## 为什么只有这几个
 *
 * `car3d` 是**离线烘焙的图**，不是实时 3D：状态组合必须提前枚举、提前出图。
 * 所以姿态里只放资产真的有的部件——四门、四窗、尾门。
 *
 * **引擎盖不在资产里**：厂商通道（931 个属性 / 1346 个功能号）里 hood/frunk/bonnet
 * 零命中，真车上永远读不到（README 已知限制）。姿态里保留 [hood] 字段只为
 * 和 `CarStatus.items` 的口径对齐，**它不参与状态 key**（画不出来，右侧图例照旧显示「--」）。
 *
 * ## 下标口径
 *
 * - [doors] / [windows]：`0 = 左前(lf)`、`1 = 右前(rf)`、`2 = 左后(lb)`、`3 = 右后(rb)`，
 *   与 `CarStatus.items` 的 0..3 / 5..8 一一对应。
 * - `true` = **开着**（注意与入参 `doorsClosed` 的 `true = 关` 相反，这里在 [of] 里翻转过）。
 *
 * ## 读不到怎么办
 *
 * 仓库铁律是「读不到 ≠ 关着」。资产里没有「未知」这一档图，所以：
 * 读不到的部件按**关着**画（车身不会缺一块），但 [unknown] 置 true，
 * 绘制时右上角点一个小圆点提示「数据不全」，图例里照旧是「--」。
 */
data class CarPose(
    /** 四门：`true` = 开。下标 0=lf,1=rf,2=lb,3=rb。 */
    val doors: BooleanArray = BooleanArray(DOOR_COUNT),
    /** 四窗：`true` = 开。下标同上。 */
    val windows: BooleanArray = BooleanArray(DOOR_COUNT),
    /** 尾门：`true` = 开。 */
    val trunk: Boolean = false,
    /** 引擎盖：`true` = 开。**不参与状态 key**（没有资产）。 */
    val hood: Boolean = false,
    /** 是否有部件「读不到」（图例显示 --）。只影响角标，不影响挑选状态。 */
    val unknown: Boolean = false,
) {

    /** 任一扇门开着。 */
    val anyDoorOpen: Boolean get() = doors.any { it }

    /** 任一扇窗开着。 */
    val anyWindowOpen: Boolean get() = windows.any { it }

    /** 与 [other] 姿态相同（[BooleanArray] 不能直接用 data class 的 equals）。 */
    fun sameAs(other: CarPose?): Boolean {
        if (other == null) return false
        return doors.contentEquals(other.doors) &&
            windows.contentEquals(other.windows) &&
            trunk == other.trunk &&
            hood == other.hood
    }

    override fun toString(): String =
        "CarPose(doors=${doors.joinToString("") { if (it) "1" else "0" }}, " +
            "windows=${windows.joinToString("") { if (it) "1" else "0" }}, " +
            "trunk=$trunk, hood=$hood, unknown=$unknown)"

    companion object {
        /** 门的数量（前门 2 + 后门 2）。 */
        const val DOOR_COUNT = 4

        /** 全关、全未知（测试/兜底用）。 */
        val CLOSED: CarPose = CarPose()

        /**
         * 从车控信号造姿态。
         *
         * @param doorsClosed `true` = 关（与 `VehicleInfo.doorsClosed` / `BodyState` 一致）
         * @param windowsOpen `true` = 开（与 `VehicleInfo.windowsOpen` 一致）
         * @param hoodClosed `true` = 关
         * @param trunkClosed `true` = 关
         */
        fun of(
            doorsClosed: List<Boolean?>?,
            windowsOpen: BooleanArray?,
            hoodClosed: Boolean?,
            trunkClosed: Boolean?,
        ): CarPose {
            var unknown = false
            val doors = BooleanArray(DOOR_COUNT)
            for (i in 0 until DOOR_COUNT) {
                when (doorsClosed?.getOrNull(i)) {
                    true -> doors[i] = false
                    false -> doors[i] = true
                    else -> {
                        doors[i] = false
                        unknown = true
                    }
                }
            }
            val windows = BooleanArray(DOOR_COUNT)
            for (i in 0 until DOOR_COUNT) {
                val v = windowsOpen?.getOrNull(i)
                if (v == null) {
                    windows[i] = false
                    unknown = true
                } else {
                    windows[i] = v
                }
            }
            val trunk = when (trunkClosed) {
                true -> false
                false -> true
                null -> {
                    unknown = true
                    false
                }
            }
            // 引擎盖没有资产、也基本读不到：只进位，不算 unknown（否则角标永远亮着）。
            val hood = hoodClosed == false
            return CarPose(doors, windows, trunk, hood, unknown)
        }
    }
}
