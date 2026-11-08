package com.cardesk.launcher.core

/**
 * 日夜模式的信号来源。
 *
 * ## 三种模式（`DayNightSettings.MODE_*`）
 *
 * - **0 跟随高德**（默认）：高德广播的日夜信号优先；没有信号时回落到
 *   车机光感 → 车机日间模式功能值 → 系统 UI 夜间态。
 * - **1 跟随本地时间**（`daynight_day_start_min` / `daynight_night_start_min`）。
 * - **2 跟随车机光感**：车机光感（AdaptAPI 昼夜通道）→ 车机日间模式功能值 →
 *   系统 UI 夜间态。
 *
 * ## 车机日间模式功能值（常量）
 *
 * - 功能号 `538247424`（`0x20150100`）
 * - `538247425` 固定日间
 * - `538247426` 固定夜间
 * - `538247427` 跟随系统（此时要看系统 UI 的夜间态）
 *
 * ## AdaptAPI 昼夜通道（真·光感）
 *
 * `Device.getDayNightMode()`（`com.ecarx.xui.adaptapi.device`）给的是**实际明暗**：
 * - `getDayNight()`：`1` 白天 / `2` 夜间 / `0` 读不到；
 *   处于「跟随」时取车机光照信号 `Twlibrists`（`OFF`→夜间、`ON`→白天）。
 * - `getDayNightMode()`：`1` 固定白天 / `2` 固定夜间 / `3` 跟随。
 *
 * 参考 App（君君桌面）读明暗走的就是这条通道（`DayNightController.readSystemNightUi`），
 * 不是 Android 的 `UiModeManager` —— 后者是**设置值**，不是当前明暗。
 *
 * 这里是纯 Kotlin（不碰 Android），可在 JVM 单测里覆盖。
 */
object DayNightSource {

    // —— 车机日间模式——
    const val FUNCTION_DAYMODE_SETTING = 538247424
    const val VALUE_DAYMODE_DAY = 538247425
    const val VALUE_DAYMODE_NIGHT = 538247426
    const val VALUE_DAYMODE_AUTO = 538247427

    // —— AdaptAPI 昼夜通道的取值 ——

    /** `getDayNight()` / `onDayNightChanged()`：白天。 */
    const val DISPLAY_DAY = 1

    /** `getDayNight()` / `onDayNightChanged()`：夜间。 */
    const val DISPLAY_NIGHT = 2

    /** `getDayNightMode()` / `onDayNightModeChange()`：跟随（实际明暗另取）。 */
    const val MODE_AUTO = 3

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

    /**
     * AdaptAPI 的**实际明暗**（`getDayNight()` / 回调 `onDayNightChanged`）→ 是不是夜间。
     *
     * @param display `1` 白天 / `2` 夜间 / `0` 读不到
     */
    fun nightFromDisplay(display: Int): Boolean? = when (display) {
        DISPLAY_DAY -> false
        DISPLAY_NIGHT -> true
        else -> null
    }

    /**
     * AdaptAPI 的**模式**（`getDayNightMode()` / 回调 `onDayNightModeChange`）→ 是不是夜间。
     *
     * 「跟随」（[MODE_AUTO]）返回 null —— 模式本身不定明暗，要再读一次实际明暗。
     */
    fun nightFromMode(mode: Int): Boolean? = when (mode) {
        DISPLAY_DAY -> false
        DISPLAY_NIGHT -> true
        else -> null
    }
}
