package com.cardesk.launcher.core.nav

/**
 * 高德车机版的「日夜模式」协议。
 *
 * ## 协议出处
 *
 * 全部来自 `gd95_新基础版_v24t.apk`（高德车机版 9.5）反编译，**不是猜的**：
 *
 * | 方向 | action | `KEY_TYPE` | 字段 | 语义 | 证据 |
 * |---|---|---|---|---|---|
 * | 高德 → 车机 | `AUTONAVI_STANDARD_BROADCAST_SEND` | `10019` | `EXTRA_STATE` | `37` 白天 / `38` 黑夜 | `AndroidProtocolExe.sendAutoStatus` 的 `case 37 → onDayNightChange(1)`、`case 38 → onDayNightChange(2)` |
 * | 车机 → 高德 | `AUTONAVI_STANDARD_BROADCAST_RECV` | `10048` | `EXTRA_DAY_NIGHT_MODE` | `0` 自动 / `1` 白天 / `2` 黑夜 | `defpackage/xh.java`：取 extra 后 `nativeDayNightMode()` |
 * | 车机 → 高德 | `AUTONAVI_STANDARD_BROADCAST_RECV` | `13030` | — | 查询高德当前日夜（高德回 `10019` 的 `37`/`38`） | `defpackage/ga0.java`：`nativeRequestDayNightMode()` |
 *
 * 高德主题一变（地图切白天/黑夜）就由 `sendAutoStatus(37|38)` 主动广播 `10019`；
 * 状态号 `30`/`32` 与广播号 `37`/`38` 的互换见 `defpackage/y70.java`（`30→37`、`32→38`）
 * 与 `defpackage/ab.java`（`37→30`、`38→32`）。
 *
 * ## 为什么单独一个文件
 *
 * 高德的 `10019` 是**一条共用通道**：导航中(8)、巡航(9)、模拟导航(10)、
 * 在不在地图页(3/4) 和 **白天(37)/黑夜(38)** 都在这一个 `EXTRA_STATE` 上。
 *
 * 之前桌面把 `10019` 的状态值一律当成「导航状态」，`37`/`38` 被静默丢掉，
 * 于是「跟随高德」永远读不到高德的日夜信号（详见 `ui/LauncherView.kt` 的
 * `resolveNight`）。这里把协议解析单独拆出来，是纯 Kotlin，可以在 JVM 单测里覆盖。
 */
object AmapDayNight {

    // ------------------------------------------------------------------ 广播字段

    /** 高德发出来的广播 action。 */
    const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"

    /** 我们发给高德的 action。 */
    const val ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"

    /** 高德广播接收组件（发 `ACTION_RECV` 时要指名收件人）。 */
    const val AMAP_RECEIVER = "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"

    /** 消息类型键。 */
    const val KEY_TYPE = "KEY_TYPE"

    /** 状态键（`10019` 的状态号就装在这里）。 */
    const val EXTRA_STATE = "EXTRA_STATE"

    /** 日夜模式键（`10048` 的模式值装在这里）。 */
    const val EXTRA_DAY_NIGHT_MODE = "EXTRA_DAY_NIGHT_MODE"

    // ------------------------------------------------------------------ KEY_TYPE

    /** 高德 → 车机：自动状态（`EXTRA_STATE` 里既有导航状态，也有日夜状态）。 */
    const val KEY_TYPE_AUTO_STATUS = 10019

    /** 车机 → 高德：设置高德的日夜模式。 */
    const val KEY_TYPE_SET_DAY_NIGHT = 10048

    /** 车机 → 高德：查询高德当前的日夜模式。 */
    const val KEY_TYPE_QUERY_DAY_NIGHT = 13030

    // ------------------------------------------------------------------ 日夜状态

    /** `10019` + `EXTRA_STATE=37` → 高德当前是**白天**。 */
    const val STATE_DAY = 37

    /** `10019` + `EXTRA_STATE=38` → 高德当前是**黑夜**。 */
    const val STATE_NIGHT = 38

    // ------------------------------------------------------------------ 日夜模式

    /** 日夜模式：自动（跟着车机/光线走）。 */
    const val MODE_AUTO = 0

    /** 日夜模式：白天。 */
    const val MODE_DAY = 1

    /** 日夜模式：黑夜。 */
    const val MODE_NIGHT = 2

    /**
     * 高德 `10019` 的状态号 → 是不是夜间。
     *
     * @param state `EXTRA_STATE` 的值
     * @return true=夜间 / false=白天；**null = 这个状态号不是日夜**（导航、巡航等）
     */
    fun nightFromAutoState(state: Int): Boolean? = when (state) {
        STATE_DAY -> false
        STATE_NIGHT -> true
        else -> null
    }

    /**
     * 高德的 `EXTRA_DAY_NIGHT_MODE` → 是不是夜间。
     *
     * 取值口径与高德自己一致（`defpackage/xh.java` / `floatapp/daynight/DayNight.java`）：
     * `0` 自动 / `1` 白天 / `2` 黑夜。
     *
     * @param mode `EXTRA_DAY_NIGHT_MODE` 的值
     * @return true=夜间 / false=白天；**null = 自动**（由调用方回落）
     */
    fun nightFromDayNightMode(mode: Int): Boolean? = when (mode) {
        MODE_DAY -> false
        MODE_NIGHT -> true
        else -> null
    }

    /**
     * 桌面的夜间态 → 高德的 `EXTRA_DAY_NIGHT_MODE`。
     *
     * 只给「白天/黑夜」两档；`[MODE_AUTO]` 留给「交给高德自己判断」的场景。
     */
    fun dayNightModeFor(nightUi: Boolean): Int = if (nightUi) MODE_NIGHT else MODE_DAY
}
