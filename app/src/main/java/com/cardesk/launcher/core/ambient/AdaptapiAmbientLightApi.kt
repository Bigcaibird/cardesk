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
 * | 亮度 | `SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_DRIVING` | `HIGH` / `LOW` / `OFF` |
 * | 主区域点亮 | `SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES` | `1` = 开 |
 *
 * 「互动效果」就是厂商那边的**音乐律动**（灯随音乐变化）；
 * 主色设成「自定义色」，颜色我们自己按频谱算着写进去。
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
     * 开关氛围灯。
     *
     * 开 = 点亮主区域 + 主色设成自定义色 + 打开**互动效果（音乐律动）**；
     * 关 = 关掉互动效果（主色留在自定义色，下次开直接亮）。
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
            // 主区域点亮
            val zonesFunc = AdaptapiConstants.resolve(AdaptapiConstants.FUNC_AMBIENT_MAIN_ZONES)
            if (zonesFunc != null) {
                gw.setHvac(zonesFunc, ZONE, ON)
            }
            // 互动效果 = 音乐律动
            ok = gw.setHvac(effectFunc, ZONE, ON)
        } else {
            ok = gw.setHvac(effectFunc, ZONE, OFF)
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
     * 设置亮度（0..100）。
     *
     * 厂商的亮度只有三档（`HIGH` / `LOW` / `OFF`），
     * 所以这里按 0 / ≤50 / >50 折算 —— 不假装有连续档位。
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

    companion object {
        /** 氛围灯功能号不带区域，统一用 `0`。 */
        const val ZONE = 0

        /** 开 / 关（厂商那边就是 `1` / `0`）。 */
        const val ON = 1
        const val OFF = 0

        /** 亮度兜底值（按名解析失败时用）。 */
        const val HIGH = 537528066
        const val LOW = 537528065

        /** 最小写入间隔（毫秒）。 */
        const val MIN_INTERVAL_MS = 120L

        /** 颜色变化小于这个距离就不重写。 */
        const val MIN_COLOR_DELTA = 8
    }
}
