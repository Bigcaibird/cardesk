package com.cardesk.launcher.core.nav

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 首页「导航引导卡」的**纯文案 / 纯计算**部分。
 *
 * 逐条
 *
 * | 函数 |位置 |
 * |---|---|
 * | [directionText] | |
 * | [formatSegmentDistance] | |
 * | [formatRouteDistance] | |
 * | [formatRemainDuration] | |
 * | [formatGuideEta] | |
 * | [roadText] | |
 * | [isValidGuide] | |
 *
 * 放在 `core` 下、**不碰任何 Android API**，所以能直接 JVM 单测
 * （`app/src/test/.../core/nav/NavGuideTextTest.kt`）。
 */
object NavGuideText {

    /** 掉头图标编码。 */
    const val ICON_UTURN = 8

    /** 左转类编码。 */
    val ICON_LEFT = setOf(2, 4, 6, 65)

    /** 右转类编码。 */
    val ICON_RIGHT = setOf(3, 5, 7, 66)

    /** 引导卡顶部的转向图标种类。 */
    enum class GuideIcon { UTURN, LEFT, RIGHT, STRAIGHT }

    /**
     * 转向图标种类。
     *
     * - 8 → 掉头
     * - 2 / 4 / 6 / 65 → 左转
     * - 3 / 5 / 7 / 66 → 右转
     * - 其余 → 直行
     */
    fun guideIcon(icon: Int): GuideIcon = when {
        icon == ICON_UTURN -> GuideIcon.UTURN
        icon in ICON_LEFT -> GuideIcon.LEFT
        icon in ICON_RIGHT -> GuideIcon.RIGHT
        else -> GuideIcon.STRAIGHT
    }

    /**
     * 转向文案。
     *
     * 注意**带一个尾随空格**（`"向左 "`），后面还要接「进入 + 路名」。
     */
    fun directionText(icon: Int): String = when (guideIcon(icon)) {
        GuideIcon.LEFT -> "向左 "
        GuideIcon.RIGHT -> "向右 "
        GuideIcon.UTURN -> "掉头 "
        GuideIcon.STRAIGHT -> "直行 "
    }

    /**
     * 分段距离（**下一段**还有多远）文案。
     *
     * - `< 1000` → 整数米，如 `423`
     * - `>= 1000` → 一位小数公里，如 `1.4`
     */
    fun formatSegmentDistance(meters: Int): String =
        if (meters < 1000) {
            max(0, meters).toString()
        } else {
            String.format(Locale.CHINA, "%.1f", meters / 1000f)
        }

    /** 分段距离单位：`>= 1000` 是「公里」，否则「米」。 */
    fun segmentUnit(meters: Int): String = if (meters >= 1000) "公里" else "米"

    /**
     * 路线剩余里程（**全程**还剩多少公里），单位固定是公里。
     *
     * 两个分支算出来**完全一样**
     * （都是 `m / 1000f` 的一位小数），这里照抄，不做"优化"。
     */
    fun formatRouteDistance(meters: Int): String =
        String.format(Locale.CHINA, "%.1f", meters / 1000f)

    /**
     * 剩余时长。
     *
     * - 先把秒**四舍五入**到分钟：`Math.max(0, Math.round(sec / 60f))`
     * - 有小时：`"01 时 30 分"`（小时补零到两位）
     * - 只有分钟：`"18 分"`
     * -在「时」「分」前面各留一个空格，这里保持一致。
     */
    fun formatRemainDuration(seconds: Int): String {
        val totalMinutes = max(0, (seconds / 60f).roundToInt())
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val sb = StringBuilder()
        if (hours > 0) {
            sb.append(String.format(Locale.CHINA, "%02d", hours)).append(' ').append('时')
            if (minutes > 0) sb.append(' ')
        }
        if (minutes > 0 || hours == 0) {
            sb.append(String.format(Locale.CHINA, "%02d", minutes)).append(' ').append('分')
        }
        return sb.toString()
    }

    /** ETA 的时间部分（`GUIDE_ETA = SimpleDateFormat("HH:mm", Locale.CHINA)`）。 */
    fun formatEtaTime(epochMillis: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(epochMillis))

    /** ETA「到达」后缀（`AbstractC0000a.m6g(str, " 到达")`）。 */
    const val ETA_SUFFIX = " 到达"

    /**
     * 预计到达完整文案，如。
     *
     * UI 上是把时间（21px）与「到达」（18px）**分开着色**的
     * 所以 UI 层用 [formatEtaTime] + [ETA_SUFFIX] 画；这里给日志和单测用整串。
     */
    fun formatGuideEta(epochMillis: Long): String = formatEtaTime(epochMillis) + ETA_SUFFIX

    /**
     * 道路文案。
     *
     * `directionText(icon)` + （`nextRoad` 优先，否则 `currentRoad`）非空时接 `"进入 " + 路名`。
     */
    fun roadText(icon: Int, currentRoad: String?, nextRoad: String?): String {
        val road = if (!nextRoad.isNullOrEmpty()) nextRoad else currentRoad
        return directionText(icon) + if (road.isNullOrEmpty()) "" else "进入 $road"
    }

    /**
     * 一条引导数据是否可用。
     *
     * `icon > 0 && segmentMeters >= 0 && (currentRoad 或 nextRoad 至少有一个非空)`。
     */
    fun isValidGuide(icon: Int, segmentMeters: Int, currentRoad: String?, nextRoad: String?): Boolean =
        icon > 0 && segmentMeters >= 0 && !(currentRoad.isNullOrEmpty() && nextRoad.isNullOrEmpty())

    /**
     * 灯态文案。
     *
     * `UNKNOWN` 显示成「灯态未知」而不是空白 ——
     * 空白会让人以为「没信号」，而实际是「信号在，但灯态枚举没验证过」。
     * 文字和颜色一起给，不靠颜色单独表意。
     */
    fun trafficLampText(lamp: AmapTrafficLight.Lamp): String = when (lamp) {
        AmapTrafficLight.Lamp.RED -> "红灯"
        AmapTrafficLight.Lamp.GREEN -> "绿灯"
        AmapTrafficLight.Lamp.AMBER -> "黄灯"
        AmapTrafficLight.Lamp.UNKNOWN -> "灯态未知"
    }

    /** 读秒文案；null = 这一帧没有读秒（或已经走完）。 */
    fun trafficCountdownText(seconds: Int?): String = if (seconds != null && seconds > 0) {
        seconds.toString()
    } else {
        "—"
    }
}
