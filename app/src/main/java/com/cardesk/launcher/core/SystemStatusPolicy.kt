package com.cardesk.launcher.core

/**
 * 状态栏连接类图标的**显示规则**（第138轮，**纯逻辑**，可 JVM 单测）。
 *
 * ## 之前是什么状态
 *
 * 状态栏右侧的四个连接图标（蜂窝 / WiFi / 热点 / 蓝牙）是**画上去就一直在那**的 ——
 * 不管车机连没连 WiFi、热点开没开、蓝牙开没开、4G 有没有信号，
 * 四个图标**永远同时亮着**。用户说「图标显示不太准确」指的就是这个。
 *
 * ## 规则从哪来
 *
 * 对齐参考工程 UNI 的状态栏右端图标槽（`system_status_row.dart` +
 * 原生 `SystemStatusAdapter`）。它的规则是这几条：
 *
 * 1. **蜂窝信号**：`移动网络已知` **且** `当前没连 WiFi` 才显示 ——
 *    连了 WiFi 就不该再显示蜂窝信号（两条链路同时亮是误导）；
 * 2. **热点**：开启才显示；**与 WiFi 互斥**（UNI 是 `if (hotspot) … else if (wifi)`）；
 * 3. **WiFi**：**已连接**才显示，图标按信号等级分档；
 * 4. **蓝牙**：`已知` **且** `已开启` 才显示，**已连接**用强调色。
 *
 * ## 与 UNI 的一处刻意不同
 *
 * UNI 的 WiFi 等级读不到时（`-1`）会**按满格画**（`?? 4`）。
 * 我们的口径一直是「**读不到就不猜**」——
 * 所以这里把「等级未知」原样传出去（[LEVEL_UNKNOWN]），
 * 由绘制层画成**弱色的空图标**，而不是假装满格。
 *
 * ## 显示顺序
 *
 * [slots] 返回的顺序是**从右往左**（第 0 个最靠右），
 * 与状态栏那一行的排版方向一致：时间 → 燃油 → 连接图标 → 位置/天气。
 */
object SystemStatusPolicy {

    /** 等级未知（读不到）。 */
    const val LEVEL_UNKNOWN: Int = -1

    /** 满格（4 格）。 */
    const val LEVEL_MAX: Int = 4

    /** 状态栏连接类图标的种类。 */
    enum class Kind {
        /** 蜂窝信号（车机自己的 4G）。 */
        CELLULAR,

        /** WiFi。 */
        WIFI,

        /** 热点（车机自己开的热点）。 */
        HOTSPOT,

        /** 蓝牙。 */
        BLUETOOTH,
    }

    /**
     * 一个要画出来的图标槽。
     *
     * @param kind 图标种类
     * @param level 强弱等级（0..4）；[LEVEL_UNKNOWN] = 读不到
     * @param emphasized 是否用**强调色**（已连接的蓝牙 / 开启的热点）
     */
    data class Slot(
        val kind: Kind,
        val level: Int = LEVEL_UNKNOWN,
        val emphasized: Boolean = false,
    )

    /**
     * 系统连接状态的一次采样。
     *
     * 每个字段都带「读不到」的表达：
     * - 布尔量用 [known] 字段区分「false」与「不知道」；
     * - 等级用 [LEVEL_UNKNOWN] 表示读不到。
     */
    data class Snapshot(
        /** WiFi 状态是否读到了（`ConnectivityManager` 可用）。 */
        val wifiKnown: Boolean = false,
        /** WiFi **已连接**。 */
        val wifiConnected: Boolean = false,
        /** WiFi 信号等级（0..4）。 */
        val wifiLevel: Int = LEVEL_UNKNOWN,
        /** 车机热点**已开启**。 */
        val hotspotOn: Boolean = false,
        /** 移动网络（车机 4G）状态是否读到了。 */
        val mobileKnown: Boolean = false,
        /** 移动数据**已开启**。 */
        val mobileEnabled: Boolean = true,
        /** 蜂窝信号等级（0..4）。 */
        val mobileLevel: Int = LEVEL_UNKNOWN,
        /** 移动网络类型文案（如 `4G`）；空 = 没有。 */
        val mobileLabel: String = "",
        /** 蓝牙状态是否读到了。 */
        val bluetoothKnown: Boolean = false,
        /** 蓝牙**已开启**。 */
        val bluetoothEnabled: Boolean = false,
        /** 蓝牙**已连接**（A2DP / 通话任一连上）。 */
        val bluetoothConnected: Boolean = false,
    ) {

        /**
         * 快照指纹（用于「变了才刷新、变了才记日志」）。
         *
         * 把所有会影响画面的字段拼成一条字符串。
         */
        fun signature(): String = listOf(
            wifiKnown, wifiConnected, wifiLevel, hotspotOn,
            mobileKnown, mobileEnabled, mobileLevel, mobileLabel,
            bluetoothKnown, bluetoothEnabled, bluetoothConnected,
        ).joinToString(",")
    }

    /**
     * 按规则算出**要画哪些图标**。
     *
     * @return 从右往左排的图标槽列表（空列表 = 一个都不画）
     */
    fun slots(s: Snapshot): List<Slot> {
        val out = ArrayList<Slot>(3)

        // ① 蜂窝信号：移动网络已知、且**当前没连 WiFi** 才显示。
        //    （连了 WiFi 还显示蜂窝信号，用户会以为流量在跑。）
        if (s.mobileKnown && !s.wifiConnected) {
            val level = when {
                // 移动数据关着 → 没有信号（0 格）。
                !s.mobileEnabled -> 0
                // 读到了就用读到的等级。
                s.mobileLevel >= 0 -> s.mobileLevel.coerceAtMost(LEVEL_MAX)
                // 读不到 → 不猜（弱色空图标）。
                else -> LEVEL_UNKNOWN
            }
            out.add(Slot(Kind.CELLULAR, level, emphasized = false))
        }

        // ② 热点 / WiFi：**互斥**，热点优先（UNI 是 `if (hotspot) … else if (wifi)`）。
        if (s.hotspotOn) {
            out.add(Slot(Kind.HOTSPOT, emphasized = true))
        } else if (s.wifiConnected) {
            out.add(Slot(Kind.WIFI, level = s.wifiLevel, emphasized = false))
        }

        // ③ 蓝牙：已知 **且** 已开启才显示；已连接用强调色。
        if (s.bluetoothKnown && s.bluetoothEnabled) {
            out.add(Slot(Kind.BLUETOOTH, emphasized = s.bluetoothConnected))
        }

        return out
    }

    /**
     * 蜂窝等级（给绘制层用）。
     *
     * 读不到移动网络时返回 [LEVEL_UNKNOWN]。
     */
    fun cellularLevel(s: Snapshot): Int =
        if (s.mobileKnown) {
            when {
                !s.mobileEnabled -> 0
                s.mobileLevel >= 0 -> s.mobileLevel.coerceAtMost(LEVEL_MAX)
                else -> LEVEL_UNKNOWN
            }
        } else {
            LEVEL_UNKNOWN
        }

    /**
     * WiFi 等级（给绘制层用）。
     *
     * 没连 WiFi 返回 [LEVEL_UNKNOWN]（不画）。
     */
    fun wifiLevel(s: Snapshot): Int =
        if (s.wifiConnected && !s.hotspotOn) s.wifiLevel else LEVEL_UNKNOWN
}
