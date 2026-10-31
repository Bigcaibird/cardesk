package com.cardesk.launcher.core.ambient

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.vehicle.AdaptapiConstants
import com.cardesk.launcher.vehicle.VehicleController
import com.cardesk.launcher.vehicle.VehicleGateway

/**
 * 氛围灯通道：**车机端 AdaptAPI 实装**（第七十一轮）。
 *
 * ## 这一轮补上的东西
 *
 * 第七十轮把上层做完了（频谱 → 颜色 → 亮度），灯珠下发**留空**，
 * 理由是「不知道车机氛围灯的真实通道」。
 *
 * 拿到车机端 `ecarx.adapterapi.jar` 之后这件事有答案了 ——
 * 氛围灯在 AdaptAPI 里是一组**设置类功能号**（`0x200A01xx` ~ `0x200A09xx`），
 * 写法和空调属性完全一样：`setFunctionValue(func, zone, value)`。
 *
 * | 我们要做的事 | 功能号（常量名） | 写入的值 |
 * | --- | --- | --- |
 * | 开关氛围灯 | `SETTING_FUNC_AMBIENCE_LIGHT_INTERACTIVE_EFFECT` | `1` = 开 / `0` = 关 |
 * | 主色来源 = 自定义色 | `SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR` | `AMBIENCE_LIGHT_MAINCOLOR_SETCOLOR` |
 * | 颜色 | `SETTING_FUNC_AMBIENCE_LIGHT_COLOR_SET` | `0xRRGGBB` 打包 |
 * | 行车亮度 | `SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_DRIVING` | `HIGH` / `LOW` / `OFF` |
 * | 静止亮度 | `SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_STATIONARY` | `HIGH` / `LOW` / `OFF` |
 * | 点亮主区 | `SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES` | `1` = 开 |
 * | 点亮上区 | `SETTING_FUNC_AMBIENCE_LIGHT_TOPZONES` | `1` = 开 |
 * | 点亮下区 | `SETTING_FUNC_AMBIENCE_LIGHT_BOTZONES` | `1` = 开 |
 * | 体验模式 = 自定义 | `SETTING_FUNC_AMBIENCE_LIGHT_EXPERIENCE` | `AMBIENCE_LIGHT_EXPERIENCE_CUSTOM` |
 *
 * 「互动效果」就是厂商那边的**音乐律动**（灯随音乐变化）；
 * 主色设成「自定义色」，颜色我们自己按频谱算着写进去。
 *
 * ## 分区是**三个独立功能号**
 *
 * 厂商把主 / 上 / 下做成了三个功能号（`0x200A0500` / `0x200A0400` / `0x200A0300`），
 * 写值都只认 `0` / `1`。写主区**不会**带上区与下区 ——
 * 只写主区的话，上/下分区（门板那圈 / 脚部）就点不亮，
 * 所以开灯时三个分区都要写。
 *
 * ## 亮度有两套：**行车** 与 **静止**
 *
 * `BRIGHTNESS_DRIVING_*`（行车，`0x200A0700`）与 `BRIGHTNESS_STATIONARY_*`
 * （静止，`0x200A0600`）是两个**不同**的功能号，档位常量也各有一套。
 *
 * **陷阱：亮度 `0` 是 `OFF`（把灯关掉），不是「最低档」。**
 * 厂商 `getMappedCbAmlBriStatSettingValue` / `getMappedCbAmlBriDrivSettingValue`
 * 把 `0` 映射成 `OFF`；`LOW` 必须传 `BRIGHTNESS_*_LOW` 那个常量。
 *
 * ## 数值一律**按名字解析**
 *
 * 厂商把常量放在 `com.ecarx.xui.adaptapi.car.vehicle.IAmbienceLight`
 * 与 `IVehicle` 两个常量接口里（`IBcm` 里是车灯）。
 * 我们通过 [AdaptapiConstants] 按名字取，**取不到就不写** ——
 * 猜一个数值去写别的功能，比不写更糟。
 *
 * ## 写频节流
 *
 * 频谱是每帧来的，直接一帧一写会把车机 IO 打满。
 * 这里加了两道闸：**最小间隔** [MIN_INTERVAL_MS] + **颜色变化幅度** [MIN_COLOR_DELTA]。
 */
class AdaptapiAmbientLightApi(
    /**
     * 每帧**感知色差**上限（ΔE00）。
     *
     * 见 [limitByDeltaE]：这是「不生硬」最硬的量化保证。
     */
    private val maxDeltaE: Float = DEFAULT_MAX_DELTA_E,
    /**
     * 取当前车辆通道。
     *
     * 默认走 [VehicleController]；留这个接缝是为了**单测**能塞一个假通道进来
     * （真机上通道是异步选出来的，用的时候才取）。
     *
     * ## 它必须是**最后一个**参数
     *
     * 单测里用的是尾随 lambda 写法（`AdaptapiAmbientLightApi { gw }`），
     * 尾随 lambda 只能绑到最后一个参数上。
     */
    private val gatewayProvider: () -> VehicleGateway? = {
        if (!VehicleController.initialized) null
        else runCatching { VehicleController.gateway }.getOrNull()
    },
) : AmbientLightApi {

    override val backendName: String = "ECARX AdaptAPI"

    /** 最小写入间隔（毫秒）。 */
    private val minIntervalMs = MIN_INTERVAL_MS

    private var lastWriteAt = 0L
    private var lastColor: AmbientColor? = null
    private var lastEnabled: Boolean? = null
    private var lastBrightnessValue: Int? = null
    private var lastStationaryBrightnessValue: Int? = null

    /**
     * 上一个分区状态（三区各自的开 / 关）。
     *
     * null = 还没写过（开灯时统一写一次「全开」）。
     */
    private val lastZoneState = BooleanArray(AmbientLightApi.ZONE_COUNT) { false }

    /**
     * 上一次写颜色**是不是失败了**（第七十九轮 P0）。
     *
     * 失败后要多等一会儿再试（[FAIL_BACKOFF_MS]）——
     * 车机不认这个功能号时，10Hz 全量写会把主线程和车控 IO 一起拖死。
     */
    @Volatile
    private var lastWriteFailed = false

    /**
     * 体验模式（`CUSTOM`）这一轮有没有写过。
     *
     * 一次开灯周期里写一次就够 —— 频谱每帧都来，
     * 每帧再写一遍体验模式是白费车机 IO。
     */
    private var experienceCustomWritten = false

    /**
     * 限速器介入次数（诊断用：看 ΔE00 限速到底拦了多少次）。
     *
     * 拦得越多说明**渲染帧率跟不上写入节流**，或者音乐本身变化太猛。
     */
    @Volatile
    var deltaELimitedCount: Int = 0
        private set

    /** 最近一次被限速前的原始 ΔE00（诊断用）。 */
    @Volatile
    var lastRawDeltaE: Float = 0f
        private set

    /**
     * 写入层的 ΔE00 限速开关。
     *
     * ## 为什么旧版链路要把它关掉
     *
     * 旧版是**现网验证过**的链路：10Hz 的硬跳变（单步 ΔE00 ≈ 9.8~32.5）。
     * 限速是 V2 才有的东西 —— 套到旧版上，它会被压成一条几乎不动的慢坡，
     * 「旧版 vs 新版」就比不出东西了。
     *
     * 控制器在切引擎时调 [setDeltaELimitEnabled]。
     */
    @Volatile
    private var deltaELimitEnabled = true

    /**
     * 打开 / 关闭写入层的 ΔE00 限速（见 [deltaELimitEnabled]）。
     */
    override fun setDeltaELimitEnabled(enabled: Boolean) {
        deltaELimitEnabled = enabled
        if (!enabled) {
            // 基准也要清掉 —— 旧版的颜色跟 V2 的不是一个量纲，
            // 拿旧基准去限新颜色会莫名其妙地拉回。
            lastColor = null
        }
    }

    override fun isAvailable(): Boolean = gatewayProvider() != null

    /**
     * 当前车辆通道。
     *
     * 车控通道是**异步**选出来的（探不到就退模拟车），
     * 所以这里不能缓存句柄 —— 用的时候才取，取不到就当「通道还没就绪」。
     *
     * ## 为什么拿不到要打日志
     *
     * 以前这里是**完全静默**的 `gateway() ?: return false` ——
     * 「通道还没就绪」这件事在日志里一个字都没有，
     * 于是「灯不亮」看起来像「代码没跑」，而不是「车控没连上」。
     * 这一轮把每一次跳过都留痕（`op` 说明是哪一步跳过的）。
     *
     * @param op 这次调用在做什么（写颜色 / 开灯 / 亮度……）
     */
    private fun gateway(op: String): VehicleGateway? {
        val gw = gatewayProvider()
        if (gw == null) {
            Logx.d(LogTags.AMBIENT, "氛围灯通道还没就绪，本次写入跳过", "op" to op)
        }
        return gw
    }

    /**
     * 应用一个颜色。
     *
     * 颜色按 `0xRRGGBB` 打包（厂商那边就是拿 `Color.red/green/blue` 拆的）。
     *
     * ## 写自定义色之前先置**体验模式 = CUSTOM**
     *
     * 厂商有**两个**互不相干的模式开关：
     * - `SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR`（`0x200A0200`）= **主色来源**
     *   （`THEME` / `DRIVER_MODE` / `SET_COLOR`）；
     * - `SETTING_FUNC_AMBIENCE_LIGHT_EXPERIENCE`（`0x200A0100`）= **体验模式**
     *   （`FULL` → `PUSHED` / `CUSTOM` → `NOTPUSHED`）。
     *
     * 我们写的是频谱算出来的**自定义色**，所以先把体验模式置成 `CUSTOM`，
     * 再写颜色 —— 顺序不能反。
     *
     * **推断**：先置 `CUSTOM` 更稳，成本极低（一次写）。
     * 「不置 `CUSTOM` 则自定义色不生效」这条**因果关系厂商文档与源码都没有明说** ——
     * jar 里 `getMappedCbAmlExperienceSettingValue` 只给了两者的**取值域**
     * （`FULL` / `CUSTOM` 各映射到 `PUSHED` / `NOTPUSHED`），没说它和颜色写入的先后关系。
     * 这属于**未验证**项：真机上若自定义色仍不亮，要回头查这条。
     */
    override fun apply(color: AmbientColor): Boolean {
        // 第八十轮：**节流必须放在最前面**。
        //
        // 以前是「先解析功能号 → 解析不到就直接 return」，
        // 节流写在解析**之后** ⇒ 车机不认这个功能号时，
        // 每一次都提前返回，`lastWriteAt` 永远不推进 → **节流形同虚设**：
        // 频谱 10Hz ⇒ 每秒 10 次解析 + 10 次日志（模拟器上实测 ≈ 8 次/秒）。
        // 写失败时虽然有 backoff，但它也被解析这道门挡在后面，同样够不着。
        //
        // 现在先把节流与 backoff 判掉，写不出去就直接返回，后面的解析一次都不做。
        val now = System.currentTimeMillis()
        if (now - lastWriteAt < minIntervalMs) return true
        // 第七十九轮 P0：**写失败也要推进节流时间**。
        //
        // 以前 `lastWriteAt` 只在写成功时推进 —— 车机那边不认这个功能号时，
        // 每一次都「失败」，于是节流形同虚设：频谱 10Hz ⇒ 每秒 10 次
        // 同步 binder + 每秒 10 次日志落盘，全在主线程上，
        // 主线程被拖死 → 窗口只剩背景色（黑）→ 看门狗杀进程。
        //
        // 现在无论成败都推进；失败了再多等一会儿（FAIL_BACKOFF_MS）。
        if (lastWriteFailed && now - lastWriteAt < FAIL_BACKOFF_MS) return false
        // —— ΔE00 限速：把这一帧拉到「与上一个**已写**颜色」相差 ≤ maxDeltaE ——
        //
        // ## 为什么比较的是「上一个已写颜色」而不是「上一个渲染帧」
        //
        // 渲染跑 60Hz、写入节流 50ms（20Hz），中间有 2~3 帧被丢掉。
        // 若拿「上一渲染帧」当基准，被丢掉的那几帧的变化会**攒到下一次写入**，
        // 于是真正落在车上的两帧色差可能是好几倍 —— 验收第 2 条（ΔE00 ≤ 2.0）就废了。
        //
        // 拿「上一个已写颜色」当基准，才能保证**车上的每一帧**都满足上限。
        val target = limitByDeltaE(color)
        val prev = lastColor
        if (prev != null && AmbientPalette.colorDistance(prev, target) < MIN_COLOR_DELTA) return true
        lastWriteAt = now
        val gw = gateway("写颜色") ?: return false
        val func = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_COLOR_SET) ?: run {
            Logx.d(LogTags.AMBIENT, "氛围灯颜色功能号按名解析不到", "name" to AdaptapiConstants.FUNC_AMBIENT_COLOR_SET)
            lastWriteFailed = true
            return false
        }
        // 体验模式 = CUSTOM —— **必须在写自定义色之前**
        ensureExperienceCustom(gw)
        val rgb = (target.r.coerceIn(0, 255) shl 16) or
            (target.g.coerceIn(0, 255) shl 8) or
            target.b.coerceIn(0, 255)
        val ok = gw.setHvac(func, ZONE, rgb)
        if (ok) {
            lastWriteFailed = false
            lastColor = target
            Logx.d(LogTags.AMBIENT, "氛围灯颜色已下发", "hex" to target.toHex(), "rgb" to rgb)
        } else {
            lastWriteFailed = true
            Logx.d(LogTags.AMBIENT, "氛围灯颜色下发失败", "hex" to target.toHex(), "rgb" to rgb)
        }
        return ok
    }

    /**
     * ΔE00 限速：把候选颜色拉回到与「上一个已写颜色」相差 ≤ [maxDeltaE]。
     *
     * ## 怎么做
     *
     * 1. 候选色与上一个已写色都转到 CIELAB；
     * 2. 算 ΔE00；
     * 3. 超过上限就把候选色**按比例往回拉**：
     *    `k = 1 − (maxDeltaE / ΔE) × 0.5`，
     *    在 **OKLab** 里插值（感知均匀，不会因为插值而偏色），
     *    再转回 8 bit sRGB。
     *
     * ## 为什么要乘 0.5
     *
     * 线性拉回会刚好落在上限上（ΔE 恰好 = maxDeltaE），
     * 但那是**数学上限** —— 实车上每帧都顶在上限上看着仍然偏跳。
     * 乘 0.5 是留一半余量：实际每帧的色差大约是上限的一半，
     * 观感上就是「连续流动」。
     *
     * ## 为什么在 OKLab 里插值而不是 RGB
     *
     * RGB 直插会在两端之间**穿过灰轴**（尤其两端色相差别大时），
     * 中间那一帧会「淡一下」—— 这恰恰是要消除的生硬。
     * OKLab 是感知均匀的，插值路径在感知上就是一条直线。
     */
    private fun limitByDeltaE(color: AmbientColor): AmbientColor {
        // 旧版链路不限速（现网观感要原样保留，见 [deltaELimitEnabled]）。
        if (!deltaELimitEnabled) return color
        val prev = lastColor ?: return color
        if (maxDeltaE <= 0f) return color
        val prevLab = AmbientColorSpace.rgbToLab(prev.r, prev.g, prev.b)
        val curLab = AmbientColorSpace.rgbToLab(color.r, color.g, color.b)
        val de = AmbientColorSpace.deltaE00(prevLab, curLab)
        lastRawDeltaE = de
        if (de <= maxDeltaE) return color
        val rgb = AmbientColorSpace.limitByDeltaE(
            prev.r, prev.g, prev.b,
            color.r, color.g, color.b,
            maxDeltaE,
        )
        deltaELimitedCount++
        Logx.d(
            LogTags.AMBIENT, "氛围灯颜色被 ΔE00 限速",
            "rawDeltaE" to de, "max" to maxDeltaE,
            "from" to color.toHex(), "to" to prev.toHex(),
        )
        return AmbientColor(rgb.first, rgb.second, rgb.third, color.brightness)
    }

    /**
     * 把体验模式置成 **CUSTOM**（一次开灯周期只写一次）。
     *
     * 厂商映射：`AMBIENCE_LIGHT_EXPERIENCE_CUSTOM` → `CbAmlExperienceSettingValue.NOTPUSHED`
     * （`CarFunctionValueUtil.getMappedCbAmlExperienceSettingValue`）。
     *
     * **推断**：先置 `CUSTOM` 更稳，成本极低；因果关系厂商未明说，属**未验证**。
     *
     * 解析不到功能号 / 档位常量时**不写**（不猜数值），
     * 但也**不拦下**颜色写入 —— 颜色才是主功能，不能被一个辅助开关拖住。
     */
    private fun ensureExperienceCustom(gw: VehicleGateway) {
        if (experienceCustomWritten) return
        val func = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_EXPERIENCE)
        if (func == null) {
            Logx.d(LogTags.AMBIENT, "氛围灯体验模式功能号按名解析不到", "name" to AdaptapiConstants.FUNC_AMBIENT_EXPERIENCE)
            return
        }
        val custom = AdaptapiConstants.resolve(AdaptapiConstants.AMBIENT_EXPERIENCE_CUSTOM)
        if (custom == null) {
            Logx.d(LogTags.AMBIENT, "氛围灯体验模式 CUSTOM 按名解析不到", "name" to AdaptapiConstants.AMBIENT_EXPERIENCE_CUSTOM)
            return
        }
        val ok = gw.setHvac(func, ZONE, custom)
        if (ok) {
            experienceCustomWritten = true
            Logx.d(LogTags.AMBIENT, "氛围灯体验模式置 CUSTOM", "func" to func, "value" to custom)
        } else {
            Logx.w(LogTags.AMBIENT, "氛围灯体验模式置 CUSTOM 失败", "func" to func, "value" to custom)
        }
    }

    /**
     * 开关氛围灯。
     *
     * 开 = 点亮**三个分区** + 主色设成自定义色 + 打开**互动效果（音乐律动）**；
     * 关 = 关掉互动效果（主色留在自定义色，下次开直接亮）。
     *
     * ## 为什么三个分区都要写
     *
     * 厂商把主 / 上 / 下做成了**三个独立功能号**
     * （`SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES` / `TOPZONES` / `BOTZONES`），
     * 写值都只认 `0` / `1`（`getMappedCbAml*ZonesSettingValue` 只映射 `0` / `1`）。
     * 写主区**不会**带上区与下区 —— 只写主区的话上/下分区点不亮。
     *
     * 分区功能号按名解析（见 [AdaptapiConstants.ambientZoneFuncs]），
     * **解析不到的分区就跳过**（不拿别的分区的号顶替）。
     */
    override fun setEnabled(enabled: Boolean): Boolean {
        val gw = gateway("开关灯") ?: return false
        if (lastEnabled == enabled) return true
        val effectFunc = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_INTERACTIVE_EFFECT)
        if (effectFunc == null) {
            Logx.d(LogTags.AMBIENT, "氛围灯互动效果功能号按名解析不到")
            return false
        }
        var ok: Boolean
        if (enabled) {
            // 主色来源 = 自定义色（颜色由我们自己按频谱写）
            val mainColorFunc = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_MAIN_COLOR)
            val setColor = AdaptapiConstants.resolve(AdaptapiConstants.AMBIENT_MAIN_COLOR_SET_COLOR)
            if (mainColorFunc != null && setColor != null) {
                gw.setHvac(mainColorFunc, ZONE, setColor)
            }
            // 体验模式 = CUSTOM（**推断**：先置更稳；因果关系厂商未明说，属未验证）
            ensureExperienceCustom(gw)
            // 三个分区都点亮（只写主区的话上/下分区不亮）
            writeZones(gw)
            // 互动效果 = 音乐律动
            ok = gw.setHvac(effectFunc, ZONE, ON)
        } else {
            ok = gw.setHvac(effectFunc, ZONE, OFF)
            // 关灯了 → 体验模式复位，下次开灯重新置一次 CUSTOM
            experienceCustomWritten = false
        }
        if (ok) {
            lastEnabled = enabled
            // 开关变了，之前写进去的颜色不再作数
            lastColor = null
            Logx.i(LogTags.AMBIENT, "氛围灯开关已下发", "enabled" to enabled)
        } else {
            Logx.w(LogTags.AMBIENT, "氛围灯开关下发失败", "enabled" to enabled)
        }
        return ok
    }

    /**
     * 点亮三个分区（主 / 上 / 下）。
     *
     * 三个分区是**三个独立功能号**，写值都只认 `1` = 开。
     * 某个分区的功能号按名解析不到就**跳过那一个**（其余照常）。
     */
    private fun writeZones(gw: VehicleGateway) {
        val funcs = AdaptapiConstants.ambientZoneFuncs()
        for (i in funcs.indices) {
            val func = funcs[i] ?: continue
            val ok = gw.setHvac(func, ZONE, ON)
            // 记下状态：分区编排要靠它判断「要不要重写」
            lastZoneState[i] = ok
            val label = AdaptapiConstants.AMBIENT_ZONE_FUNC_NAMES[i]
            if (ok) {
                Logx.d(LogTags.AMBIENT, "氛围灯分区点亮", "name" to label, "func" to func)
            } else {
                Logx.w(LogTags.AMBIENT, "氛围灯分区点亮失败", "name" to label, "func" to func)
            }
        }
    }

    /**
     * 开关**某一个分区**（分区编排用）。
     *
     * 三个分区是**三个独立功能号**（`0x200A0500` / `0x200A0400` / `0x200A0300`），
     * 写值只认 `0` / `1`。
     *
     * ## 状态去重
     *
     * 同一个状态不重写 —— 分区编排是低频动作（驻留 ≥ 800ms），
     * 但状态抖动（比如「刚好重写」与「编排切换」撞在一起）不能变成车控 IO 风暴。
     */
    override fun setZoneEnabled(zone: Int, enabled: Boolean): Boolean {
        if (zone < 0 || zone >= AmbientLightApi.ZONE_COUNT) return false
        val gw = gateway("分区开关") ?: return false
        if (lastZoneState[zone] == enabled) return true
        val func = AdaptapiConstants.ambientZoneFuncs().getOrNull(zone) ?: run {
            Logx.d(LogTags.AMBIENT, "氛围灯分区功能号按名解析不到", "zone" to zone)
            return false
        }
        val ok = gw.setHvac(func, ZONE, if (enabled) ON else OFF)
        if (ok) {
            lastZoneState[zone] = enabled
            Logx.d(
                LogTags.AMBIENT, "氛围灯分区开关已下发",
                "zone" to zone,
                "name" to AdaptapiConstants.AMBIENT_ZONE_FUNC_NAMES[zone],
                "enabled" to enabled,
            )
        } else {
            Logx.w(LogTags.AMBIENT, "氛围灯分区开关下发失败", "zone" to zone, "enabled" to enabled)
        }
        return ok
    }

    /**
     * 设置**行车**亮度（0..100）。
     *
     * 厂商的亮度只有三档（`HIGH` / `LOW` / `OFF`），
     * 所以这里按 0 / ≤50 / >50 折算 —— 不假装有连续档位。
     *
     * ## 陷阱：`0` 是 **OFF**（把灯关掉），不是「最低档」
     *
     * 厂商 `CarFunctionValueUtil.getMappedCbAmlBriDrivSettingValue` 里
     * `0` 被映射成 `CbAmlBriDrivSettingValue.OFF`，
     * `LOW` 必须传 `BRIGHTNESS_DRIVING_LOW` 那个常量。
     * 传 `0` 当「最低档」会把氛围灯**直接关掉**。
     *
     * 所以这里的 `brightness <= 0 -> OFF` 是**故意的**（用户把亮度拉到 0 = 要关灯），
     * 不是「最低档」的写法 —— 别把它改成 `0 -> LOW`。
     */
    override fun setBrightness(brightness: Int): Boolean {
        val gw = gateway("行车亮度") ?: return false
        val func = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_DRIVING)
        if (func == null) {
            Logx.d(LogTags.AMBIENT, "氛围灯亮度功能号按名解析不到")
            return false
        }
        val value = when {
            brightness <= 0 -> OFF
            brightness <= 50 -> AdaptapiConstants.resolve(AdaptapiConstants.AMBIENT_BRIGHTNESS_LOW) ?: LOW
            else -> AdaptapiConstants.resolve(AdaptapiConstants.AMBIENT_BRIGHTNESS_HIGH) ?: HIGH
        }
        // 厂商只有三档，所以比较的是**折算后的档位**而不是原始百分比 ——
        // 否则频谱每帧都在动，亮度就会每帧都写一次。
        if (lastBrightnessValue == value) return true
        val ok = gw.setHvac(func, ZONE, value)
        if (ok) {
            lastBrightnessValue = value
            Logx.d(LogTags.AMBIENT, "氛围灯亮度已下发", "brightness" to brightness, "value" to value)
        }
        return ok
    }

    /**
     * 设置**静止时**的亮度（0..100）。
     *
     * ## 为什么要有这一条
     *
     * 厂商把亮度分成**两套**：行车（`0x200A0700`）与静止（`0x200A0600`），
     * 各有自己的功能号与各自的档位常量
     * （`BRIGHTNESS_DRIVING_LOW` ≠ `BRIGHTNESS_STATIONARY_LOW`）。
     * 只写行车那一套的话，**车停着的时候亮度调不动**。
     *
     * ## 陷阱：`0` 是 **OFF**，不是「最低档」
     *
     * 厂商 `CarFunctionValueUtil.getMappedCbAmlBriStatSettingValue`：
     * `0` → `CbAmlBriStatSettingValue.OFF`，
     * `BRIGHTNESS_STATIONARY_LOW` → `LOW`，`BRIGHTNESS_STATIONARY_HIGH` → `HIGH`。
     * 传 `0` 当「最低档」会把氛围灯**直接关掉**。
     *
     * ## 与 [setBrightness] 的关系
     *
     * 两条方法各写各的功能号，互不覆盖；
     * 上层按「车在不在动」挑一条调。
     */
    override fun setStationaryBrightness(brightness: Int): Boolean {
        val gw = gateway("静止亮度") ?: return false
        val func = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_STATIONARY)
        if (func == null) {
            Logx.d(LogTags.AMBIENT, "氛围灯静止亮度功能号按名解析不到", "name" to AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_STATIONARY)
            return false
        }
        val value = when {
            brightness <= 0 -> OFF
            brightness <= 50 -> AdaptapiConstants.resolve(AdaptapiConstants.AMBIENT_BRIGHTNESS_STATIONARY_LOW) ?: STATIONARY_LOW
            else -> AdaptapiConstants.resolve(AdaptapiConstants.AMBIENT_BRIGHTNESS_STATIONARY_HIGH) ?: STATIONARY_HIGH
        }
        // 同样比较**折算后的档位**，避免同一档反复写
        if (lastStationaryBrightnessValue == value) return true
        val ok = gw.setHvac(func, ZONE, value)
        if (ok) {
            lastStationaryBrightnessValue = value
            Logx.d(LogTags.AMBIENT, "氛围灯静止亮度已下发", "brightness" to brightness, "value" to value)
        }
        return ok
    }

    companion object {
        /** 氛围灯功能号不带区域，统一用 `0`。 */
        const val ZONE = 0

        /** 开 / 关（厂商那边就是 `1` / `0`）。 */
        const val ON = 1
        const val OFF = 0

        /** 亮度兜底值（按名解析失败时用）。 */
        const val HIGH = 537528066
        const val LOW = 537528065

        /**
         * **静止档**亮度的兜底值（按名解析失败时用）。
         *
         * 这两个数是车机端 jar 里 `IVehicle` 的**确定值**
         * （`BRIGHTNESS_STATIONARY_LOW=537527809` / `BRIGHTNESS_STATIONARY_HIGH=537527810`），
         * 不是猜的 —— 但仍按名解析优先。
         */
        const val STATIONARY_LOW = 537527809
        const val STATIONARY_HIGH = 537527810

        /**
         * 最小写入间隔（毫秒）。
         *
         * ## 从 100 改成 50：写入频率 10Hz → 20Hz
         *
         * 旧版渲染跑在 10Hz（频谱帧率），所以 100ms 的节流正好对齐。
         * V2 的渲染跑在 **60Hz 独立时钟**上，平滑是在渲染层做的；
         * 写入再慢就会把平滑重新切成台阶 —— 灯又「跳」起来了。
         *
         * 50ms = 20Hz，是「车控 IO 压力」与「观感连续性」的折中。
         *
         * ## ⚠️ 提高写入频率必须守住第七十九轮 P0
         *
         * 20Hz 的**同步 binder** 比 10Hz 更容易把主线程拖死。
         * 本类所有写入都在后台线程上（见 [AmbientLightController] 的写线程），
         * 且：只取最新一帧、不排队、写失败退避 1s。
         * 这三条是**不能动**的。
         *
         * 若实车观察发现车控 IO 或主线程负载不稳，
         * 先退到 60ms（≈16.7Hz），观感仍然远好于 10Hz。
         */
        const val MIN_INTERVAL_MS = 50L

        /**
         * 每帧感知色差上限（ΔE00）的默认值。
         *
         * 演示台实测：旧版单步跳变 ΔE00 ≈ 9.8~32.5，
         * V2 限速后每帧 ≤ 1.2（限速器留了一半余量）。
         */
        const val DEFAULT_MAX_DELTA_E = 1.8f

        /**
         * 写失败后的退避（毫秒）。
         *
         * 车机那边不认氛围灯的功能号时，写**每次都失败**。
         * 以前节流只在成功时推进，等于没有节流 —— 10Hz 全量同步写，
         * 主线程被拖死（第七十九轮 P0 的直接成因）。
         * 现在失败后退避这么久再试。
         */
        const val FAIL_BACKOFF_MS = 1000L

        /**
         * 颜色变化小于这个距离就不重写。
         *
         * ## 为什么从 8 降到 2
         *
         * 以前颜色是**直接从频谱算出来**的，一帧一跳，变化幅度天然很大，
         * 阈值 8 用来挡掉「同一个颜色反复写」。
         *
         * 现在颜色先过了 [AmbientMotion]（平滑 + 限速），
         * **每帧只挪一点点**（色相每帧 ≤ 9°）——
         * 阈值还留在 8 的话，一半以上的帧会被判成「没变」而丢掉，
         * 平滑又被切回台阶，灯照样是跳的。
         *
         * 所以阈值必须跟着「平滑后的单帧变化幅度」一起调小。
         * 真正的节流靠 [MIN_INTERVAL_MS]（10Hz 上限），不靠这个幅度阈值。
         */
        const val MIN_COLOR_DELTA = 2
    }
}
