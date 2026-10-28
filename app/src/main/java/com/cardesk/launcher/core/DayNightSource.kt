package com.cardesk.launcher.core

/**
 * 日夜模式的信号来源。
 *
 * ##三种模式（`DayNightSettings.MODE_*`）
 *
 * - （**默认**）：跟随高德/系统的昼夜信号。
 *   高德有信号时用高德的，没有信号时回落到车机日间模式信号。
 * -：跟随本地时间（`daynight_day_start_min` / `daynight_night_start_min`）。
 * -：跟随车机光感（车机把光照折算成日间模式功能值）。
 *
 * ## 车机日间模式功能值（常量）
 *
 * - 功能号
 * - （固定日间）
 * - （固定夜间）
 * - （跟随系统，此时要看系统 UI 的夜间态）
 *
 * 这里是纯 Kotlin（不碰 Android），可在 JVM 单测里覆盖。
 */
object DayNightSource {

    // —— 车机日间模式——
    const val FUNCTION_DAYMODE_SETTING = 538247424
    const val VALUE_DAYMODE_DAY = 538247425
    const val VALUE_DAYMODE_NIGHT = 538247426
    const val VALUE_DAYMODE_AUTO = 538247427

    /**
     * 把车机日间模式功能值翻译成「是不是夜间」。
     *
     * @param value 车机读到的功能值
     * @param systemNight 系统 UI 报告的夜间态（AUTO 模式下才有意义）；null 表示读不到
     * @return null 表示这个值无法判定（调用方应回落到下一个来源）
     */
    fun nightFromCarValue(value: Int, systemNight: Boolean?): Boolean? = when (value) {
        VALUE_DAYMODE_DAY -> false
        VALUE_DAYMODE_NIGHT -> true
        VALUE_DAYMODE_AUTO -> systemNight
        else -> null
    }
}
