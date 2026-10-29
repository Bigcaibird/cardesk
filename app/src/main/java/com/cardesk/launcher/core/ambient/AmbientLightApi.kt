package com.cardesk.launcher.core.ambient

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 氛围灯控制通道。
 *
 * ## 实现状态（第七十一轮）
 *
 * 之前是**接口先留空**：上层（频谱解析 → 颜色映射 → 设置项）都做完了，
 * 但车机氛围灯的真实通道还没定，所以下发那一步挂着空实现。
 *
 * 第七十一轮拿到车机端 `ecarx.adapterapi.jar` 之后通道确定了：
 * **AdaptAPI 的 `SETTING_FUNC_AMBIENCE_LIGHT_*` 一组设置类功能号**，
 * 写法和空调属性一样（`setFunctionValue(func, zone, value)`）。
 * 实装见 [AdaptapiAmbientLightApi]。
 *
 * [NullAmbientLightApi] 仍然留着：车控通道探不到（退模拟车）时用它兜底，
 * 频谱与颜色照常算，只是**不往车上发**。
 *
 * ## 形状为什么这么定
 *
 * 颜色（RGB）和亮度**分开** —— 车机氛围灯通常是「一组灯珠 + 一个统一亮度」，
 * 两边混在一起（把亮度乘进 RGB）就没法只调亮度了。
 */
interface AmbientLightApi {

    /** 后端名称（设置页显示用，例如「未接入」「ECARX 私有通道」）。 */
    val backendName: String

    /** 这个通道现在能不能用。 */
    fun isAvailable(): Boolean

    /**
     * 应用一个颜色。
     *
     * @return true = 已下发；false = 下发失败（通道不可用 / 调用抛异常）
     */
    fun apply(color: AmbientColor): Boolean

    /** 开关氛围灯（关灯 / 开灯）。 */
    fun setEnabled(enabled: Boolean): Boolean

    /** 设置亮度（0..100）。 */
    fun setBrightness(brightness: Int): Boolean

    /**
     * 设置**静止时**亮度（0..100）。
     *
     * ## 第七十四轮：行车亮度与静止亮度是**两个功能号**
     *
     * 厂商把氛围灯亮度拆成两档：
     * `SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_DRIVING`（行车）与
     * `SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_STATIONARY`（静止），
     * 底层信号分别是 `PaAmlBriDrivSetting` / `PaAmlBriStatSetting`。
     *
     * 停好车、等人上车时该亮的那档，是**静止**这一档。
     *
     * 默认实现返回 false —— 没实装的通道（[NullAmbientLightApi]）当作「不支持」，
     * 调用方据此保持原状即可，不必每个实现都写一遍。
     */
    fun setStationaryBrightness(brightness: Int): Boolean = false
}

/**
 * 空实现：什么都不做。
 *
 * 频谱与颜色的计算照常跑（设置页能看到实时频谱、也能看到算出来的颜色），
 * 只是**不往车上发** —— 这正是「API 先留空」阶段应有的样子。
 */
object NullAmbientLightApi : AmbientLightApi {

    override val backendName: String = "未接入"

    override fun isAvailable(): Boolean = false

    override fun apply(color: AmbientColor): Boolean {
        // 排障场景：算出来的颜色对不对 —— 空实现也记一笔，方便对着日志核对
        Logx.d(
            LogTags.AMBIENT, "氛围灯（空实现，未下发）",
            "color" to color.toHex(), "brightness" to color.brightness,
        )
        return false
    }

    override fun setEnabled(enabled: Boolean): Boolean = false

    override fun setBrightness(brightness: Int): Boolean = false
}
