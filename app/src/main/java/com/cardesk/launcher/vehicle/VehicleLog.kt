package com.cardesk.launcher.vehicle

/**
 * 车控域的日志辅助：把「属性号 / 区域号」翻译成人话。
 *
 * 排障场景：真机上抓到的日志里只有 `func=0x10010300` 这种裸属性号时，
 * 要靠人肉对照 [HvacIds] 才能知道是哪个功能；这里统一在日志里同时带上
 * 十六进制属性号与中文名，`grep A/C` 和 `grep 0x10010300` 都能命中。
 */
internal object VehicleLog {

    /** 属性号 → 十六进制字符串（真机上直接按属性号 grep 用）。 */
    fun hex(func: Int): String = "0x%08X".format(func)

    /** 属性号 → 中文功能名（写日志时与 [hex] 一起带上，方便肉眼读）。 */
    fun funcName(func: Int): String = when (func) {
        HvacIds.POWER -> "空调电源"
        HvacIds.AC -> "A/C"
        HvacIds.AC_MAX -> "A/C最大冷"
        HvacIds.AUTO -> "AUTO"
        HvacIds.FAN_SPEED -> "风量"
        HvacIds.CIRCULATION -> "循环模式"
        HvacIds.DEFROST_FRONT -> "前除霜"
        HvacIds.DEFROST_FRONT_MAX -> "前除霜(MAX)"
        HvacIds.DEFROST_REAR -> "后除霜"
        HvacIds.SEAT_VENT -> "座椅通风"
        HvacIds.SEAT_HEAT -> "座椅加热"
        HvacIds.TEMP -> "温度"
        HvacIds.TEMP_DUAL -> "双区同步"
        HvacIds.BLOWING -> "吹风模式"
        HvacIds.FUNC_360 -> "360全景"
        HvacIds.FUNC_360_VIEW -> "360视角"
        DriveMode.FUNCTION_DRIVE_MODE_SELECT -> "驾驶模式"
        else -> "未知属性"
    }

    /** 区域号 → 中文区域名（主驾 / 副驾 / 全局）。 */
    fun zoneName(zone: Int): String = when (zone) {
        HvacIds.ZONE_DRIVER -> "主驾"
        HvacIds.ZONE_PASSENGER -> "副驾"
        0 -> "全局"
        else -> "区域$zone"
    }
}
