package com.cardesk.launcher.vehicle

/**
 * 空调的完整可显示状态。不可变 data class，UI 拿快照渲染。
 */
data class HvacState(
    val power: Boolean = false,
    val ac: Boolean = false,
    val auto: Boolean = false,
    /** 0=内循环 1=外循环 2=自动（只有内/外两态，2 仅为读回兜底） */
    val circulation: Int = 1,
    /** 0..7 */
    val fanLevel: Int = 0,
    val fanAuto: Boolean = false,
    // —— 初始温度是 23.5℃（不是 23）
    val tempDriver: Float = 23.5f,
    val tempPassenger: Float = 23.5f,
    val tempSynced: Boolean = false,
    val defrostFront: Boolean = false,
    val defrostRear: Boolean = false,
    /** 0..6，见 [HvacIds.BLOW_LABELS] */
    val blowing: Int = 2,
    val seatHeatDriver: Int = 0,
    val seatVentDriver: Int = 0,
    val seatHeatPassenger: Int = 0,
    val seatVentPassenger: Int = 0,
) {
    fun tempOf(zone: Int): Float =
        if (zone == HvacIds.ZONE_DRIVER) tempDriver else tempPassenger

    fun seatHeatOf(zone: Int): Int =
        if (zone == HvacIds.ZONE_DRIVER) seatHeatDriver else seatHeatPassenger

    fun seatVentOf(zone: Int): Int =
        if (zone == HvacIds.ZONE_DRIVER) seatVentDriver else seatVentPassenger
}
