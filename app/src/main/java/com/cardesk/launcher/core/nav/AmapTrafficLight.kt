package com.cardesk.launcher.core.nav

import com.cardesk.launcher.core.JsonLite

/**
 * 高德「红绿灯」协议解析（**纯逻辑**，不碰 Android，可直接 JVM 单测）。
 *
 * ## 证据来源
 *
 * 本地两份高德 APK 的静态分析（`uni-launcher/analysis/amap-traffic-evidence.json`
 * 与 `uni-launcher/docs/AMAP_TRAFFIC_LIGHT_CARD.md`）：
 *
 * - 官方包 `com.autonavi.amapauto` 9.5.0.600013
 * - 修改版 `com.autonavi.amapautp.yh` 9.5.0.600013
 *
 * 本机没有那两份 APK（原始反编译输出也不在库里），所以**这里只复用已固化的结论**，
 * 不重新推断。
 *
 * ## 两套协议
 *
 * ### 官方标准协议
 *
 * action `AUTONAVI_STANDARD_BROADCAST_SEND`，`KEY_TYPE = 60073`，
 * 模型是 `com.autonavi.amapauto.protocol.model.service.RspTrafficLightsCountdownInfoModel`。
 *
 * | extra | 含义 |
 * |---|---|
 * | `dir` | 方向原始值 |
 * | `trafficLightStatus` | 灯态原始值（**枚举未实测**） |
 * | `redLightCountDownSeconds` | **红灯**倒计时秒数 |
 * | `waitRound` | 等待轮次 |
 * | `greenLightLastSecond` | 受条件控制，**不保证每次携带**，语义未验证 |
 *
 * 关键取舍：
 * - **灯态枚举不猜**。`trafficLightStatus` 的取值含义没有实测过，
 *   所以官方协议**不**拿它去判红/黄/绿。
 * - 但 `redLightCountDownSeconds` 这个**字段名本身**就是「红灯倒计时秒数」，
 *   所以**读秒 > 0 就是「红灯在倒计时」** —— 这是字段名给的依据，不是枚举猜的。
 * - `greenLightLastSecond` **不当成绿灯倒计时**（它有条件门、语义未验证）。
 *
 * ### 修改版巡航协议
 *
 * action `NAVITOOL_CRUISE_TRAFFIC_LIGHTS`，extra `data` 是 JSON 字符串：
 *
 * ```json
 * { "ts": 1712345678901, "lights": [ { "direction": 2, "status": 0, "countdown": 23, "waitNum": 1, "showType": 0 } ] }
 * ```
 *
 * 这一套的枚举在 YH 包里**已验证**（`FloatViewUtils` 的方向表 / 背景表）：
 * - 方向：`1` 左转 / `2` 直行 / `3` 右转；
 * - 灯态：`0` 红 / `1` 绿；`2` 在浮窗里也用绿色，语义未确定 → 按**未知**处理。
 *
 * ⚠️ 这套枚举**只适用于巡航协议**，不能套到官方协议的 `dir` / `trafficLightStatus` 上。
 */
object AmapTrafficLight {

    /** 灯态。 */
    enum class Lamp {
        /** 红灯。 */
        RED,

        /** 绿灯。 */
        GREEN,

        /** 黄灯。 */
        AMBER,

        /** 灯态未确认（枚举未实测，或收到无法识别的值）。 */
        UNKNOWN,
    }

    /**
     * 一条红绿灯信号（**最近路口**）。
     *
     * @param lamp 灯态；[Lamp.UNKNOWN] 表示**不猜**。
     * @param countdownSeconds 读秒（秒）；null = 这一帧没带读秒，或已经走完。
     * @param rawStatus 原始灯态值（只做诊断，不参与显示）。
     * @param rawDirection 原始方向值（只做诊断，不参与显示）。
     * @param enumVerified 灯态枚举是否已验证（只有 YH 巡航协议为 true）。
     */
    data class Signal(
        val lamp: Lamp,
        val countdownSeconds: Int?,
        val rawStatus: Int?,
        val rawDirection: Int?,
        val enumVerified: Boolean,
    )

    // ------------------------------------------------------------ 常量

    /** 官方标准协议里红绿灯广播的 `KEY_TYPE`。 */
    const val KEY_TYPE_TRAFFIC_LIGHT = 60073

    /** 修改版（YH）巡航协议的 action。 */
    const val ACTION_CRUISE = "NAVITOOL_CRUISE_TRAFFIC_LIGHTS"

    /** 巡航协议的 JSON 所在 extra 名。 */
    const val EXTRA_CRUISE_DATA = "data"

    /** 消息类型键。 */
    const val EXTRA_KEY_TYPE = "KEY_TYPE"

    /** 方向原始值。 */
    const val EXTRA_DIR = "dir"

    /** 灯态原始值。 */
    const val EXTRA_STATUS = "trafficLightStatus"

    /** 红灯倒计时秒数。 */
    const val EXTRA_RED_COUNTDOWN = "redLightCountDownSeconds"

    /** 等待轮次。 */
    const val EXTRA_WAIT_ROUND = "waitRound"

    /** 绿灯剩余秒数（**语义未验证，不用于显示**）。 */
    const val EXTRA_GREEN_LAST_SECOND = "greenLightLastSecond"

    /** 官方协议里需要取的那几个 extra（其余忽略）。 */
    val STANDARD_EXTRAS = arrayOf(
        EXTRA_KEY_TYPE,
        EXTRA_DIR,
        EXTRA_STATUS,
        EXTRA_RED_COUNTDOWN,
        EXTRA_WAIT_ROUND,
        EXTRA_GREEN_LAST_SECOND,
    )

    /** 巡航方向：左转（YH 包里已验证）。 */
    const val DIR_LEFT = 1

    /** 巡航方向：直行（YH 包里已验证）。 */
    const val DIR_STRAIGHT = 2

    /** 巡航方向：右转（YH 包里已验证）。 */
    const val DIR_RIGHT = 3

    /** 读秒上限（超过就不是秒数了）。 */
    const val MAX_COUNTDOWN_SECONDS = 999

    /** 巡航数组长度上限。 */
    const val MAX_CRUISE_LIGHTS = 16

    /** 巡航 JSON 长度上限（防止超大报文）。 */
    const val MAX_CRUISE_JSON_LENGTH = 16384

    // ------------------------------------------------------------ 官方协议

    /**
     * 解析官方标准协议的一帧。
     *
     * @param extras 只放**数值型** extra 的 map（字符串 / 布尔一律当没有）。
     * @return null = 这一帧不是红绿灯数据（或字段不全）。
     */
    fun standard(extras: Map<String, Any?>): Signal? {
        if (strictInt(extras[EXTRA_KEY_TYPE]) != KEY_TYPE_TRAFFIC_LIGHT) return null
        // `dir` 与 `trafficLightStatus` 两个字段缺一不可 ——
        // 这是 `wh0.c()` 写进 Intent 的固定形态，缺了就说明不是这一类广播。
        val dir = strictInt(extras[EXTRA_DIR]) ?: return null
        val status = strictInt(extras[EXTRA_STATUS]) ?: return null
        val countdown = seconds(extras[EXTRA_RED_COUNTDOWN])
        // 灯态：`trafficLightStatus` 的枚举**没有实测** → 不猜红/黄/绿。
        // 只有读秒 > 0 时能确定是红灯（依据是**字段名**，不是枚举）。
        val lamp = if (countdown != null && countdown > 0) Lamp.RED else Lamp.UNKNOWN
        return Signal(
            lamp = lamp,
            countdownSeconds = countdown,
            rawStatus = status,
            rawDirection = dir,
            enumVerified = false,
        )
    }

    // ------------------------------------------------------------ 巡航协议

    /**
     * 解析修改版（YH）巡航协议的一帧，**只取一个方向**。
     *
     * ## 为什么只取一个
     *
     * 需求是「只显示最近的路口的红绿灯状态和读秒」。
     * 巡航协议一次会带上**同一个路口**的多个方向，
     * 我们没有可靠的办法知道「 ours 走哪个方向」，所以：
     *
     * - 有匹配当前转向方向（[preferredDirection]）的那条 → 用它；
     * - 否则整个数组只有一条 → 用它；
     * - 再否则 → 返回 null，**不画**。
     *   宁可没有，也不要拼出一个看起来像真的信号。
     *
     * @param json extra `data` 的原文。
     * @param preferredDirection 当前导航转向对应的巡航方向（见 `DIR_*`）；null = 不知道。
     */
    fun cruise(json: String, preferredDirection: Int? = null): Signal? {
        if (json.isEmpty() || json.length > MAX_CRUISE_JSON_LENGTH) return null
        val root = JsonLite.parseObject(json) ?: return null
        val lights = root["lights"] as? List<*> ?: return null
        if (lights.isEmpty() || lights.size > MAX_CRUISE_LIGHTS) return null
        val parsed = ArrayList<CruiseLight>(lights.size)
        for (item in lights) {
            val map = item as? Map<*, *> ?: return null
            val direction = strictInt(map["direction"]) ?: return null
            val status = strictInt(map["status"]) ?: return null
            parsed.add(
                CruiseLight(
                    direction = direction,
                    status = status,
                    countdown = seconds(map["countdown"]),
                ),
            )
        }
        val picked = preferredDirection?.let { want -> parsed.firstOrNull { it.direction == want } }
            ?: parsed.singleOrNull()
            ?: return null
        // 灯态枚举在这一套里**已验证**（YH 包的浮窗方向表 / 背景表）。
        val lamp = when (picked.status) {
            0 -> Lamp.RED
            1 -> Lamp.GREEN
            // 浮窗里索引 2 也用绿色，但语义没确定 → 按未知处理，不当成绿灯。
            else -> Lamp.UNKNOWN
        }
        return Signal(
            lamp = lamp,
            countdownSeconds = picked.countdown,
            rawStatus = picked.status,
            rawDirection = picked.direction,
            enumVerified = true,
        )
    }

    // ------------------------------------------------------------ 内部

    /** 巡航协议里的一条灯。 */
    private data class CruiseLight(
        val direction: Int,
        val status: Int,
        val countdown: Int?,
    )

    /**
     * 严格取整数。
     *
     * 只认 **Number 且是整数值**；
     * 字符串 / 布尔 / 小数一律当没有 —— 协议里这些字段本来就是数值。
     */
    private fun strictInt(value: Any?): Int? {
        if (value !is Number) return null
        val d = value.toDouble()
        if (d.isNaN() || d.isInfinite()) return null
        if (d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
        if (d != Math.rint(d)) return null
        return d.toInt()
    }

    /**
     * 严格取读秒：必须是 `0..999` 的整数，否则 null。
     *
     * 越界说明车端给的不是秒数（比如毫秒），宁可不显示。
     */
    private fun seconds(value: Any?): Int? {
        val n = strictInt(value) ?: return null
        return if (n in 0..MAX_COUNTDOWN_SECONDS) n else null
    }
}
