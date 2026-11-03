package com.cardesk.launcher.core.nav

import com.cardesk.launcher.core.JsonLite

/**
 * 高德「红绿灯」协议解析（**纯逻辑**，不碰 Android，可直接 JVM 单测）。
 *
 * ## 证据来源
 *
 * **第八十五轮：从模拟器里直接取包反编译**（不再靠转述结论）。
 *
 * 模拟器 `emulator-5554` 里装的就是车机同款高德（`com.autonavi.amapautp.yh`），
 * 于是把它从设备上 pull 下来用 `jadx` 解 `classes.dex`：
 *
 * ```
 * adb shell pm path com.autonavi.amapautp.yh
 * adb pull <base.apk>          # 167,168,086 B
 * unzip -p base.apk classes.dex > classes.dex
 * jadx --single-class com.autonavi.floatwindow.FloatViewUtils --single-class-output out/FloatViewUtils.java classes.dex
 * ```
 *
 * 取到的关键结论（每条都能在反编译源码里逐字对上）：
 *
 * | 结论 | 出处 |
 * |---|---|
 * | 巡航协议灯态 `0`=红 / `1`=绿 | `FloatViewUtils.<clinit>`：`TYPE_BGS = {red_night, green_night, green_night}`，浮窗按 `status` 取背景 |
 * | 官方协议灯态 `trafficLightStatus` `1`=**红** / `4`=**绿** | `floatapp.trafficlight.GuideTrafficLightVoice.playTrafficLightAnnouncement` + 高德语音设置项文案 |
 * | 官方协议方向 `1`=左 / `2`=右 / `4`=直 | 同上模块的 `dir` 分支 |
 * | `greenLightLastSecond` 高德 8.1.0 起恒带 | `ib.t()` = `i.a \|\| majorVersion >= 810`；车机装的是 9.5.0 |
 * | 官方广播形态 `KEY_TYPE=60073` + 灯态 + 方向 + 两个读秒 | `wh0.c()`（广播构造） |
 *
 * 另有早前固化在 `uni-launcher/analysis/amap-traffic-evidence.json`
 * 与 `uni-launcher/docs/AMAP_TRAFFIC_LIGHT_CARD.md` 的两份包分析结论，两边一致。
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
 * | `trafficLightStatus` | **灯态**（枚举见 [STATUS_STANDARD_RED] / [STATUS_STANDARD_GREEN]） |
 * | `redLightCountDownSeconds` | **变灯**倒计时秒数（**所有灯态都适用**，不是「红灯」专用） |
 * | `waitRound` | 等待轮次 |
 * | `greenLightLastSecond` | **灯态伴随标志**（红=`0` / 绿=`3`），不是读秒（高德 8.1.0 起每次都带） |
 *
 * 关键取舍：
 * - **灯态看 `trafficLightStatus`，不靠读秒反推**。
 *   以前这里是「红灯倒计时 > 0 就判红」——
 *   而那个字段在**绿灯期间照样可能是正数**，
 *   于是绿灯被画成红灯（真车实测到的就是这个问题）。
 * - **读秒对所有灯态都取 `redLightCountDownSeconds`**（它是「距离下次变灯还有几秒」）。
 *   绿灯期间不能拿 `greenLightLastSecond` 当读秒 —— 它恒为 `3`，
 *   照字段名当剩余秒数用，绿灯倒计时就会在 3/2 之间来回跳。
 *   依据见 [EXTRA_GREEN_LAST_SECOND] 的 KDoc。
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
 * ⚠️ 这套枚举**只适用于巡航协议**，不能套到官方协议的 `dir` / `trafficLightStatus` 上 ——
 * 两套协议的灯态取值**正好是反的**（巡航 0=红，官方 1=红）。
 *
 * 另外：那份 YH APK 里**没有** `CruiseTrafficLightBroadcaster` ⇒ 真车走的是**官方标准协议**。
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
     * @param countdownSeconds 距离**下次变灯**的读秒（秒，所有灯态同一口径）；
     *   `0` = 马上变灯（显示层按「没有数字」处理）；null = 这一帧没带读秒，或已经走完。
     * @param rawStatus 原始灯态值（只做诊断，不参与显示）。
     * @param rawDirection 原始方向值（只做诊断，不参与显示）。
     * @param enumVerified 灯态枚举是否**已核对过**（两套协议都是 true ——
     *   巡航那张来自高德浮窗的资源表，官方那张来自高德自己的红绿灯语音模块）。
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

    /**
     * AMap Companion 风格：`60073` 帧里额外带一个 `lightsData`（CameraLightInfo 数组的 JSON），
     * 一次给出**同一路口所有方向**的灯态；`clearLights` / `EXTRA_CLEAR_LIGHTS` 表示清空。
     *
     * 这是社区修改版高德（巡航包）的扩展，官方包不带；桌面按多方向数组处理。
     */
    const val EXTRA_LIGHTS_DATA = "lightsData"
    const val EXTRA_CLEAR_LIGHTS = "clearLights"
    const val EXTRA_CLEAR_LIGHTS_ALT = "EXTRA_CLEAR_LIGHTS"

    /** 消息类型键。 */
    const val EXTRA_KEY_TYPE = "KEY_TYPE"

    /** 方向原始值。 */
    const val EXTRA_DIR = "dir"

    /** 灯态原始值。 */
    const val EXTRA_STATUS = "trafficLightStatus"

    /**
     * **变灯**倒计时秒数（`redLightCountDownSeconds`）。
     *
     * 字段名带 `red`，但它**不是红灯专用** —— 它是「距离下次变灯还有几秒」，
     * 绿灯期间照样有效。所以 [Signal.countdownSeconds] 对所有灯态都取它，
     * 不能按字段名拆成「红灯读秒 / 绿灯读秒」两路。
     *
     * `0` 是合法值（马上变灯），按约定照常下发；显示层对 `0` 不画数字。
     */
    const val EXTRA_RED_COUNTDOWN = "redLightCountDownSeconds"

    /** 等待轮次。 */
    const val EXTRA_WAIT_ROUND = "waitRound"

    /**
     * 绿灯帧的**灯态伴随标志**（`greenLightLastSecond`）—— **不是**绿灯剩余秒数。
     *
     * ## 语义定论（第三方桌面「嘟嘟」生产代码 + UNI 参考实现）
     *
     * 高德 60073 协议里它的取值只有两种：**红=`0`、绿=`3`**。
     * 它跟着灯态走，用来**佐证灯态**，不随时间递减，
     * 所以「绿灯剩余秒数」这个望文生义的解读是错的：
     * 真车实测绿灯读秒一直在 `3 ↔ 2` 之间跳，正是因为它恒为 `3`。
     *
     * 逐行证据：
     * - `work/dudu/jadx_out/sources/com/dudu/autoui/manage/p194s/p195j/C3691g.java`
     *   的 60073 分支（`onReceive`）：
     *   - 灯态是**成对**判定的：`greenLast == 0 && status == 1` 判红、
     *     `greenLast == 3 && status == 4` 判绿；
     *   - 去重 key 是 `greenLast-status-dir`（只有灯态变了才重新起一轮）；
     *   - 两个灯态分支用的是**同一个**变灯时刻 `j2`（由
     *     `redLightCountDownSeconds` 算出），也就是说读秒**根本不分红绿**。
     * - `uni-launcher/app/android/app/src/main/java/xyz/i4o/uni/launcher/integration/AmapTrafficLightProtocol.java`
     *   的 `standard()` 恒取 `redLightCountDownSeconds` 作 `countdownSeconds`，
     *   这个字段只存成 `rawGreenLightLastSecond` 诊断字段，
     *   注释写明 *never turn it into a green countdown*。
     *
     * 因此这里的口径是：**保留它做灯态佐证/诊断，永不当读秒**。
     * 帧里的原始值仍然由 `AmapDesktopGuide` 以 `rawGreen` 打进日志，供真车复核。
     */
    const val EXTRA_GREEN_LAST_SECOND = "greenLightLastSecond"

    /** [EXTRA_GREEN_LAST_SECOND] 在**绿灯**帧里的取值（红=`0`，见 [EXTRA_GREEN_LAST_SECOND]）。 */
    const val GREEN_LAST_WHEN_GREEN = 3

    /** [EXTRA_GREEN_LAST_SECOND] 在**红灯**帧里的取值。 */
    const val GREEN_LAST_WHEN_RED = 0

    /** 官方协议里需要取的那几个 extra（其余忽略）。 */
    val STANDARD_EXTRAS = arrayOf(
        EXTRA_KEY_TYPE,
        EXTRA_DIR,
        EXTRA_STATUS,
        EXTRA_RED_COUNTDOWN,
        EXTRA_WAIT_ROUND,
        EXTRA_GREEN_LAST_SECOND,
    )

    /**
     * 官方标准协议的 `trafficLightStatus`：**红灯**。
     *
     * ## 依据（不是猜的）
     *
     * 高德自己的红绿灯语音模块 `floatapp.trafficlight.GuideTrafficLightVoice`
     * 读的就是这个字段：
     *
     * ```kotlin
     * if (trafficLightStatus == 1) announcement.append("即将绿灯")
     * else if (trafficLightStatus == 4) announcement.append("即将红灯")
     * ```
     *
     * 而高德的语音设置项把这句话的意思写死了：
     *
     * > 开启后红绿灯播报**不播绿灯状态下的即将红灯**，只播报**红灯状态下的即将绿灯**
     *
     * 也就是「即将红灯」发生在**当前是绿灯**的时候，
     * 「即将绿灯」发生在**当前是红灯**的时候。
     * 于是：`trafficLightStatus == 1` ⇒ 当前**红灯**；`== 4` ⇒ 当前**绿灯**。
     *
     * ## 为什么以前会画反
     *
     * 以前这里**不看这个字段**，只按「红灯倒计时 > 0 就判红」。
     * 而那个倒计时在**绿灯期间照样可能是正数** ⇒ 绿灯被画成红灯。
     *
     * ⚠️ 与巡航协议的灯态**正好相反**（巡航 0=红 / 1=绿）——
     * 两套表的取值不能互相套用。
     */
    const val STATUS_STANDARD_RED = 1

    /** 官方标准协议的 `trafficLightStatus`：**绿灯**（依据见 [STATUS_STANDARD_RED]）。 */
    const val STATUS_STANDARD_GREEN = 4

    /** 官方标准协议的方向：`1` 左转 / `2` 右转 / `4` 直行（其余按掉头处理）。 */
    const val DIR_STANDARD_LEFT = 1
    const val DIR_STANDARD_RIGHT = 2
    const val DIR_STANDARD_STRAIGHT = 4

    /** 巡航方向：左转（YH 包里已验证）。 */
    const val DIR_LEFT = 1

    /** 巡航方向：直行（YH 包里已验证）。 */
    const val DIR_STRAIGHT = 2

    /** 巡航方向：右转（YH 包里已验证）。 */
    const val DIR_RIGHT = 3

    /**
     * 官方标准协议的方向 → 中文（诊断用，日志里直接看）。
     *
     * 依据同 [STATUS_STANDARD_RED]：高德语音模块
     * `1` 左转 / `2` 右转 / `4` 直行，其余按掉头处理。
     *
     * ⚠️ 与巡航协议的 `dir` **不是同一张表**（巡航 1=左 / 2=直 / 3=右）。
     */
    fun standardDirectionLabel(dir: Int?): String = when (dir) {
        DIR_STANDARD_LEFT -> "左转"
        DIR_STANDARD_RIGHT -> "右转"
        DIR_STANDARD_STRAIGHT -> "直行"
        null -> "未知"
        else -> "掉头"
    }

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
        val lamp = lampFromStandardStatus(status, extras)
        // 读秒**所有灯态都取同一个字段** `redLightCountDownSeconds`
        // （它是「距离下次变灯还有几秒」，不是「红灯专用」；见 [EXTRA_RED_COUNTDOWN]）。
        //
        // ⚠️ 绿灯时**绝不**改取 `greenLightLastSecond`：
        // 那个字段是灯态伴随标志（绿恒为 3），当读秒用就会显示成
        // `3 → 2 → 3 → 2 …` 的锯齿 —— 正是用户报的「绿灯倒计时不正确」。
        //
        // 字段缺失/negative 时干脆不给读秒（显示层画「—」），
        // **不拿别的字段顶替**；这与 dudu 的 `red >= 0` 门限一致。
        val countdown = seconds(extras[EXTRA_RED_COUNTDOWN])
        return Signal(
            lamp = lamp,
            countdownSeconds = countdown,
            rawStatus = status,
            rawDirection = dir,
            enumVerified = true,
        )
    }

    /**
     * 官方协议的灯态。
     *
     * `trafficLightStatus` 认得的就直接用（`1` = 红，`4` = 绿）——
     * 这是权威来源，不因为伴随标志对不上就丢帧（丢帧会让卡片整段不显示）。
     *
     * ## 认不出来时怎么兜底
     *
     * 高德要是改了枚举取值，我们**不猜**它是什么意思，
     * 按 dudu 的成对条件退到「灯态伴随标志」：
     *
     * - `greenLightLastSecond == 3` ⇒ 正在绿灯（dudu：`greenLast==3 && status==4` 判绿）；
     * - 否则若变灯倒计时 > 0 ⇒ 正在等灯，按红灯画
     *   （dudu 里 `greenLast==0 && status==1` 判红；绿灯那一侧已被上一行判掉）。
     *
     * 这样即使枚举变了，也不会退回到「永远画红灯」那个老毛病。
     * `greenLast` 缺失（老版本高德不带这个字段）时上一行自然跳过，不影响判定。
     */
    private fun lampFromStandardStatus(status: Int, extras: Map<String, Any?>): Lamp {
        when (status) {
            STATUS_STANDARD_RED -> return Lamp.RED
            STATUS_STANDARD_GREEN -> return Lamp.GREEN
        }
        val green = seconds(extras[EXTRA_GREEN_LAST_SECOND])
        if (green == GREEN_LAST_WHEN_GREEN) return Lamp.GREEN
        val red = seconds(extras[EXTRA_RED_COUNTDOWN])
        if (red != null && red > 0) return Lamp.RED
        return Lamp.UNKNOWN
    }

    // ------------------------------------------------------------ 巡航协议

    /**
     * 巡航协议里**一个方向**的红绿灯（多方向并列展示用）。
     *
     * @param direction 巡航方向码（[DIR_LEFT]/[DIR_STRAIGHT]/[DIR_RIGHT]），识别不出为 null。
     * @param lamp 灯态（枚举已核对）。
     * @param countdownSeconds 变灯读秒；null = 这一帧没带。
     * @param rawStatus 原始灯态值（诊断用）。
     */
    data class DirectionLight(
        val direction: Int?,
        val lamp: Lamp,
        val countdownSeconds: Int?,
        val rawStatus: Int?,
    )

    /**
     * 解析 `lightsData` 数组（社区修改版高德 / AMap Companion 扩展）。
     *
     * 顶层是 JSON 数组，每个元素来自 `CameraLightInfo`：
     * `direction`（1 左 / 2 直 / 3 右）、`status`（0 红 / 1 绿；部分版本 2 也当绿）、
     * `countdown`、`waitNum`、`showType`。也兼容 `dir` / `trafficLightStatus` /
     * `redLightCountDownSeconds` 这些标准命名。
     *
     * @return 按 左→直→右→其它 排好序、同方向去重的灯列表；空数组 / 坏 JSON 返回空列表。
     */
    fun standardArray(raw: String?): List<DirectionLight> {
        if (raw.isNullOrEmpty() || raw.length > MAX_CRUISE_JSON_LENGTH) return emptyList()
        val items = runCatching { JsonLite.parseArray(raw) }.getOrNull() ?: return emptyList()
        if (items.isEmpty()) return emptyList()
        val out = LinkedHashMap<Int, DirectionLight>()
        for (item in items) {
            if (out.size >= MAX_CRUISE_LIGHTS) break
            // 社区扩展用 CameraLightInfo 的名字（direction/status），也兼容标准命名
            // （dir/trafficLightStatus）；两者的方向与灯态枚举**不是同一套**，分别映射。
            val standardNames = item.containsKey("trafficLightStatus") || item.containsKey("dir")
            val dirRaw = strictInt(item["direction"] ?: item["dir"])
            val direction = if (standardNames) {
                when (dirRaw) {
                    1 -> DIR_LEFT
                    2 -> DIR_RIGHT
                    4 -> DIR_STRAIGHT
                    else -> null
                }
            } else {
                when (dirRaw) {
                    DIR_LEFT, DIR_STRAIGHT, DIR_RIGHT -> dirRaw
                    else -> null
                }
            }
            val status = strictInt(item["status"] ?: item["trafficLightStatus"]) ?: continue
            val lamp = if (standardNames) {
                when (status) {
                    STATUS_STANDARD_RED -> Lamp.RED
                    STATUS_STANDARD_GREEN -> Lamp.GREEN
                    else -> Lamp.UNKNOWN
                }
            } else {
                // CameraLightInfo：0=红、1/2=绿（社区与 UNI 实测一致）。
                when (status) {
                    0 -> Lamp.RED
                    1, 2 -> Lamp.GREEN
                    else -> Lamp.UNKNOWN
                }
            }
            val countdown = seconds(item["countdown"] ?: item["redLightCountDownSeconds"])
            val key = direction ?: -1
            if (!out.containsKey(key)) {
                out[key] = DirectionLight(direction, lamp, countdown, status)
            }
        }
        return out.values.sortedBy { directionOrder(it.direction) }
    }

    /**
     * 解析巡航协议的**全部方向**（最多 [MAX_CRUISE_LIGHTS] 条），用于巡航卡并列展示。
     *
     * 与 [cruise] 的区别：
     * - [cruise] 是「导航卡右上角那一个灯」，要按当前转向挑一条，挑不出就不画；
     * - 本方法是「巡航卡那一排灯」，**有多少方向就展示多少**（左/直/右各一枚），
     *   单条坏数据只跳过那一条，不影响其它灯；同方向重复只保留第一条。
     *
     * 排序固定为 左 → 直 → 右 → 其它，避免每帧顺序抖动。
     */
    fun cruiseAll(json: String?): List<DirectionLight> {
        if (json.isNullOrEmpty() || json.length > MAX_CRUISE_JSON_LENGTH) return emptyList()
        val root = JsonLite.parseObject(json) ?: return emptyList()
        val lights = root["lights"] as? List<*> ?: return emptyList()
        if (lights.isEmpty()) return emptyList()
        val out = LinkedHashMap<Int, DirectionLight>()
        for (item in lights) {
            if (out.size >= MAX_CRUISE_LIGHTS) break
            val map = item as? Map<*, *> ?: continue
            val direction = strictInt(map["direction"])
            val status = strictInt(map["status"]) ?: continue
            val lamp = when (status) {
                0 -> Lamp.RED
                1 -> Lamp.GREEN
                else -> Lamp.UNKNOWN
            }
            // 方向识别不出的帧统一归到一个「未知方向」槽位，避免同一帧里重复占位。
            val key = direction ?: UNKNOWN_DIRECTION_KEY
            if (!out.containsKey(key)) {
                out[key] = DirectionLight(direction, lamp, seconds(map["countdown"]), status)
            }
        }
        return out.values.sortedBy { directionOrder(it.direction) }
    }

    /** 方向识别不出时用的槽位键（只保留第一条）。 */
    private const val UNKNOWN_DIRECTION_KEY = -1

    /** 巡航方向的展示顺序：左、直、右、其它。 */
    private fun directionOrder(direction: Int?): Int = when (direction) {
        DIR_LEFT -> 0
        DIR_STRAIGHT -> 1
        DIR_RIGHT -> 2
        else -> 3
    }

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
