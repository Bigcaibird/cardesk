package com.cardesk.launcher.core.ambient

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 氛围灯控制通道（**接口先留空** —— 第七十轮）。
 *
 * ## 为什么是留空的
 *
 * 车机氛围灯的实际通道还没定：
 * ECARX 的 `AdaptAPI` 里没有现成的氛围灯函数（查过：`BCM_FUNC_LIGHT_*` 是车灯，
 * 不是氛围灯），CarProperty / AOSP 里也没有标准接口 ——
 * 剩下的多半是这车厂自己的私有通道。
 *
 * 所以这一轮先把**上层做完**：
 * - 频谱解析（[com.cardesk.launcher.core.audio.SpectrumAnalyzer] + `SpectrumBands`）✅
 * - 频谱 → 颜色 / 亮度的映射（[AmbientPalette]）✅
 * - 设置项（颜色模式 / 灵敏度 / 亮度上下限）✅
 *
 * 等拿到真实 API，只要另写一个 [AmbientLightApi] 的实现接进
 * [AmbientLightController.setApi] 就行，上层一行都不用改。
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
