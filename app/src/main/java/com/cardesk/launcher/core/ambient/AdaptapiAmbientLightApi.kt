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
     * 取当前车辆通道。
     *
     * 默认走 [VehicleController]；留这个接缝是为了**单测**能塞一个假通道进来
     * （真机上通道是异步选出来的，用的时候才取）。
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
     * 体验模式（`CUSTOM`）这一轮有没有写过。
     *
     * 一次开灯周期里写一次就够 —— 频谱每帧都来，
     * 每帧再写一遍体验模式是白费车机 IO。
     */
    private var experienceCustomWritten = false

    override fun isAvailable(): Boolean = gateway() != null

    /**
     * 当前车辆通道。
     *
     * 车控通道是**异步**选出来的（探不到就退模拟车），
     * 所以这里不能缓存句柄 —— 用的时候才取，取不到就当「通道还没就绪」。
     */
    private fun gateway(): VehicleGateway? = gatewayProvider()

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
        val gw = gateway() ?: return false
        val func = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_COLOR_SET) ?: run {
            Logx.d(LogTags.AMBIENT, "氛围灯颜色功能号按名解析不到", "name" to AdaptapiConstants.FUNC_AMBIENT_COLOR_SET)
            return false
        }
        // 节流：颜色没怎么变就别写
        val now = System.currentTimeMillis()
        val prev = lastColor
        if (prev != null && now - lastWriteAt < minIntervalMs) return true
        if (prev != null && AmbientPalette.colorDistance(prev, color) < MIN_COLOR_DELTA) return true
        // 体验模式 = CUSTOM —— **必须在写自定义色之前**
        ensureExperienceCustom(gw)
        val rgb = (color.r.coerceIn(0, 255) shl 16) or
            (color.g.coerceIn(0, 255) shl 8) or
            color.b.coerceIn(0, 255)
        val ok = gw.setHvac(func, ZONE, rgb)
        if (ok) {
            lastWriteAt = now
            lastColor = color
            Logx.d(LogTags.AMBIENT, "氛围灯颜色已下发", "hex" to color.toHex(), "rgb" to rgb)
        } else {
            Logx.d(LogTags.AMBIENT, "氛围灯颜色下发失败", "hex" to color.toHex(), "rgb" to rgb)
        }
        return ok
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
        val gw = gateway() ?: return false
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
            val label = AdaptapiConstants.AMBIENT_ZONE_FUNC_NAMES[i]
            if (ok) {
                Logx.d(LogTags.AMBIENT, "氛围灯分区点亮", "name" to label, "func" to func)
            } else {
                Logx.w(LogTags.AMBIENT, "氛围灯分区点亮失败", "name" to label, "func" to func)
            }
        }
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
        val gw = gateway() ?: return false
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
        val gw = gateway() ?: return false
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

        /** 最小写入间隔（毫秒）。 */
        const val MIN_INTERVAL_MS = 120L

        /** 颜色变化小于这个距离就不重写。 */
        const val MIN_COLOR_DELTA = 8
    }
}
