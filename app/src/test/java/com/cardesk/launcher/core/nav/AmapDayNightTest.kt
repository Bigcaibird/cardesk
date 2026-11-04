package com.cardesk.launcher.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 高德日夜协议解析单测。
 *
 * 协议出处（`gd95_新基础版_v24t.apk` 反编译，不是猜的）：
 * - `AndroidProtocolExe.sendAutoStatus`：`case 37 → GAdaAndroid.onDayNightChange(1)`（白天）、
 *   `case 38 → onDayNightChange(2)`（黑夜）——`1` 白天 / `2` 黑夜是高德自己的口径。
 * - `defpackage/y70.java`：`30 → 37`、`32 → 38`（协议号 ↔ 广播号）。
 * - `defpackage/ab.java`：`37 → 30`、`38 → 32`（广播号 ↔ 协议号）。
 * - `defpackage/xh.java`：`EXTRA_DAY_NIGHT_MODE` 取 0/1/2 后 `nativeDayNightMode()`。
 */
class AmapDayNightTest {

    @Test
    fun nightFromAutoState_dayAndNight() {
        assertEquals(false, AmapDayNight.nightFromAutoState(AmapDayNight.STATE_DAY))
        assertEquals(true, AmapDayNight.nightFromAutoState(AmapDayNight.STATE_NIGHT))
    }

    @Test
    fun nightFromAutoState_navigationStatesAreNotDayNight() {
        // `10019` 是**共用通道**：导航中(8)、模拟(10)、巡航(9)、空闲(2)、
        // 在不在地图页(3/4) 与日夜(37/38)挤在同一个 `EXTRA_STATE` 上。
        // 这些状态必须返回 null —— 认成白天/黑夜就是「桌面把高德的日夜读错了」。
        for (state in listOf(8, 10, 9, 2, 3, 4, 40, 50, 51, 201)) {
            assertNull("state=$state 不是日夜状态", AmapDayNight.nightFromAutoState(state))
        }
    }

    @Test
    fun nightFromDayNightMode_matchesAmapDialect() {
        // 高德自己的口径：0 自动 / 1 白天 / 2 黑夜
        assertEquals(false, AmapDayNight.nightFromDayNightMode(AmapDayNight.MODE_DAY))
        assertEquals(true, AmapDayNight.nightFromDayNightMode(AmapDayNight.MODE_NIGHT))
        // 0 = 自动 → 由调用方回落（null）
        assertNull(AmapDayNight.nightFromDayNightMode(AmapDayNight.MODE_AUTO))
    }

    @Test
    fun dayNightModeFor_mapsDesktopStateToAmapMode() {
        assertEquals(AmapDayNight.MODE_DAY, AmapDayNight.dayNightModeFor(false))
        assertEquals(AmapDayNight.MODE_NIGHT, AmapDayNight.dayNightModeFor(true))
    }

    @Test
    fun protocolConstants_matchAmapApk() {
        assertEquals(10019, AmapDayNight.KEY_TYPE_AUTO_STATUS)
        assertEquals(10048, AmapDayNight.KEY_TYPE_SET_DAY_NIGHT)
        assertEquals(13030, AmapDayNight.KEY_TYPE_QUERY_DAY_NIGHT)
        assertEquals(37, AmapDayNight.STATE_DAY)
        assertEquals(38, AmapDayNight.STATE_NIGHT)
        assertEquals(0, AmapDayNight.MODE_AUTO)
        assertEquals(1, AmapDayNight.MODE_DAY)
        assertEquals(2, AmapDayNight.MODE_NIGHT)
        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", AmapDayNight.ACTION_SEND)
        assertEquals("AUTONAVI_STANDARD_BROADCAST_RECV", AmapDayNight.ACTION_RECV)
    }
}
