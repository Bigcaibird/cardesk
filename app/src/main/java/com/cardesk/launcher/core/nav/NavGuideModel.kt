package com.cardesk.launcher.core.nav

import kotlin.math.max

/**
 * 首页引导卡的**渲染模型**。
 *
 * 把「高德原始数据」+「格式化规则」算成一份 UI 直接能画的东西
 * 这样 UI 层只负责画，不重复实现格式化（格式化规则见 [NavGuideText]）。
 */
data class DesktopGuide(
    /** 分段距离文案，如 `423` / `1.4`。 */
    val segmentText: String,
    /** 分段距离单位：「米」/「公里」。 */
    val segmentUnit: String,
    /** 道路文案，如「向左 进入 金峰北路」。 */
    val road: String,
    /** 全程剩余里程（公里），如 `12.4`。 */
    val remainKm: String,
    /** 全程剩余时长，如 `18 分` / `01 时 30 分`。 */
    val remainTime: String,
    /** 预计到达时间，如 （不含「到达」后缀，后缀由 UI 分开着色）。 */
    val etaTime: String,
    /** 转向图标种类。 */
    val icon: NavGuideText.GuideIcon,
    /** 车道线（解析好的，可能为空）。 */
    val lanes: List<LaneTile>,
    /**
     * 最近路口的红绿灯（**可为空**）。
     *
     * null = 当前没有有效信号（没收到广播 / 超时 / 协议不匹配）——
     * 这种情况下引导卡**不画**红绿灯那一块（不占位）。
     */
    val trafficLight: AmapTrafficLight.Signal? = null,
    /**
     * 是否是**巡航卡**（不是导航卡）。
     *
     * 巡航态没有「分段距离 / 剩余里程 / ETA」这些导航专属信息，
     * 只展示当前道路 + 车道线 + 多方向红绿灯。
     */
    val cruise: Boolean = false,
    /**
     * 巡航态的多方向红绿灯（左/直/右各一枚，可能为空）。
     *
     * 导航卡不使用这个字段（导航卡用 [trafficLight] 的单枚信号）。
     */
    val cruiseLights: List<AmapTrafficLight.DirectionLight> = emptyList(),
)

/**
 * 引导模型构建器。
 */
object NavGuideModel {

    /**
     * 由原始数据构建渲染模型。
     *
     * @param lanesRaw 车道线原文（`EXTRA_DRIVE_WAY`）；为空时按回落到最近一次的车道线。
     * @param now 当前时间戳（ETA = now + remainSeconds）。
     * @param trafficLight 最近路口的红绿灯；null = 没有有效信号（不画）。
     */
    fun build(
        icon: Int,
        segmentMeters: Int,
        currentRoad: String?,
        nextRoad: String?,
        remainSeconds: Int,
        remainMeters: Int,
        lanesRaw: String?,
        now: Long = System.currentTimeMillis(),
        trafficLight: AmapTrafficLight.Signal? = null,
    ): DesktopGuide = DesktopGuide(
        segmentText = NavGuideText.formatSegmentDistance(segmentMeters),
        segmentUnit = NavGuideText.segmentUnit(segmentMeters),
        road = NavGuideText.roadText(icon, currentRoad, nextRoad),
        remainKm = NavGuideText.formatRouteDistance(remainMeters),
        remainTime = NavGuideText.formatRemainDuration(remainSeconds),
        etaTime = NavGuideText.formatEtaTime(now + max(0, remainSeconds) * 1000L),
        icon = NavGuideText.guideIcon(icon),
        lanes = DriveWay.parse(lanesRaw).orEmpty(),
        trafficLight = trafficLight,
    )

    /**
     * 构建**巡航卡**模型。
     *
     * @param icon 最近一次转向图标原始编码（0 = 没有，画直行）
     * @param road 当前道路名（可为空）
     * @param lanesRaw 最近一次车道线原文（可为空）
     * @param lights 多方向红绿灯（可为空）
     */
    fun buildCruise(
        icon: Int,
        road: String?,
        lanesRaw: String?,
        lights: List<AmapTrafficLight.DirectionLight>,
    ): DesktopGuide = DesktopGuide(
        segmentText = "",
        segmentUnit = "",
        road = road?.trim().orEmpty().ifEmpty { CRUISE_ROAD_FALLBACK },
        remainKm = "",
        remainTime = "",
        etaTime = "",
        icon = NavGuideText.guideIcon(icon),
        lanes = DriveWay.parse(lanesRaw).orEmpty(),
        trafficLight = null,
        cruise = true,
        cruiseLights = lights,
    )

    /** 巡航态拿不到道路名时的兜底文案。 */
    private const val CRUISE_ROAD_FALLBACK = "巡航中"
}
