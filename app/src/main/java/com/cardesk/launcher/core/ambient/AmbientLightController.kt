package com.cardesk.launcher.core.ambient

import android.content.Context
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.audio.SpectrumAnalyzer
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 氛围灯总控（第七十轮）：**频谱 → 颜色 → 氛围灯通道**。
 *
 * ```
 * SpectrumAnalyzer（真实播放频谱）
 *      ↓ 频段能量
 * AmbientPalette（纯函数：频段 → 颜色 + 亮度）
 *      ↓ AmbientColor
 * AmbientLightApi（车机端 AdaptAPI 实装，见 AdaptapiAmbientLightApi）
 * ```
 *
 * ## 现在做到哪儿了
 *
 * - 频谱采集：✅ 真的在采（`Visualizer` 挂在播放会话上）；
 * - 颜色映射：✅ 真的在算（三种模式、灵敏度、亮度上下限）；
 * - 灯珠下发：✅ **接上了**（第七十一轮）—— 车机端 AdaptAPI 的
 *   `SETTING_FUNC_AMBIENCE_LIGHT_*` 一组功能号：
 *   颜色（`0xRRGGBB`）、亮度（高/低/关）、互动效果（= 音乐律动）。
 *
 * 设置页里能看到实时频谱条与算出来的颜色。
 */
object AmbientLightController {

    interface Listener {
        /** 算出来一个颜色了。 */
        fun onAmbientColor(color: AmbientColor)

        /** 一帧原始频谱（设置页画频谱条用）。 */
        fun onAmbientSpectrum(bands: FloatArray, level: Float)

        /** 状态变化（一行字，给设置页显示）。 */
        fun onAmbientStatus(text: String)
    }

    private val listeners = LinkedHashSet<Listener>()

    private var appContext: Context? = null
    private var api: AmbientLightApi = NullAmbientLightApi
    private var lastColor: AmbientColor? = null
    private var lastHue = 0f
    private var running = false

    /** 当前算出来的颜色（设置页显示用）。 */
    val color: AmbientColor? get() = lastColor

    /** 换成真的氛围灯通道。 */
    fun setApi(impl: AmbientLightApi) {
        api = impl
        Logx.i(LogTags.AMBIENT, "氛围灯通道已切换", "backend" to impl.backendName)
    }

    /** 当前通道。 */
    fun api(): AmbientLightApi = api

    /** 绑上下文（App 启动时调一次）。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 订阅（主线程回调）。 */
    fun addListener(l: Listener) {
        listeners.add(l)
        l.onAmbientStatus(statusText())
    }

    /** 退订。 */
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** 当前是不是开着。 */
    fun isEnabled(context: Context): Boolean = Prefs(context.applicationContext).ambientEnabled

    /**
     * 开关氛围灯。
     *
     * 关 → 停采集 + 通知通道熄灯；开 → 起采集。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val from = prefs.ambientEnabled
        prefs.ambientEnabled = enabled
        Logx.i(LogTags.AMBIENT, "氛围灯开关", "from" to from, "to" to enabled)
        if (enabled) start(app) else stop(app)
        notifyStatus()
    }

    /** 开始采集（幂等）。 */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (running) return
        running = true
        SpectrumAnalyzer.get().addListener(spectrumListener)
        SpectrumAnalyzer.get().start(app)
        api.setEnabled(true)
        notifyStatus()
    }

    /** 停止采集（幂等）。 */
    fun stop(context: Context) {
        if (!running) return
        running = false
        SpectrumAnalyzer.get().removeListener(spectrumListener)
        SpectrumAnalyzer.get().stop()
        api.setEnabled(false)
        notifyStatus()
    }

    /** 状态一行字（设置页显示）。 */
    fun statusText(): String {
        val analyzer = SpectrumAnalyzer.get()
        val base = when (analyzer.state) {
            SpectrumAnalyzer.State.IDLE -> "未启动"
            SpectrumAnalyzer.State.WAITING -> "等待音乐播放"
            SpectrumAnalyzer.State.RUNNING -> "采集中"
            SpectrumAnalyzer.State.UNAVAILABLE -> "不可用"
        }
        val reason = analyzer.stateReason
        val api = api.backendName
        return "频谱：$base${if (reason.isBlank()) "" else "（$reason）"} · 通道：$api"
    }

    // ------------------------------------------------------------------ 频谱回调

    private val spectrumListener = object : SpectrumAnalyzer.Listener {
        override fun onSpectrum(bands: FloatArray, level: Float) {
            update(bands, level)
        }

        override fun onAnalyzerState(state: SpectrumAnalyzer.State, reason: String) {
            notifyStatus()
        }
    }

    /**
     * 一帧频谱 → 一个颜色 → 下发。
     *
     * 这是整个功能的核心三步；颜色怎么算见 [AmbientPalette]。
     */
    fun update(bands: FloatArray, level: Float) {
        val ctx = appContext ?: return
        val prefs = Prefs(ctx)
        val computed = AmbientPalette.colorFrom(
            bands = bands,
            level = level,
            mode = prefs.ambientMode,
            fixedHue = prefs.ambientFixedHue.toFloat(),
            sensitivity = prefs.ambientSensitivityValue(),
            minBrightness = prefs.ambientMinBrightness,
            maxBrightness = prefs.ambientMaxBrightness,
            fallbackHue = lastHue,
        )
        lastHue = AmbientPalette.spectrumHue(bands, lastHue)
        lastColor = computed
        // 原始频谱先给出去（设置页画频谱条）
        listeners.forEach { it.onAmbientSpectrum(bands, level) }
        // 亮度与颜色分开下发（车机氛围灯是「一组灯珠 + 一个统一亮度」）。
        // 通道自己会节流：亮度按三档折算后比较，颜色按间隔 + 变化幅度比较。
        api.setBrightness(computed.brightness)
        api.apply(computed)
        listeners.forEach { it.onAmbientColor(computed) }
    }

    private fun notifyStatus() {
        val text = statusText()
        listeners.forEach { it.onAmbientStatus(text) }
    }
}
