package com.cardesk.launcher.core.ambient

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.audio.SpectrumAnalyzer
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 氛围灯总控：**频谱 → 颜色 → 氛围灯通道**。
 *
 * ```
 * SpectrumAnalyzer（真实播放频谱）
 *      ↓ 频段能量
 * AmbientPalette（纯函数：频段 → 颜色 + 亮度）
 *      ↓ AmbientColor
 * AmbientLightApi（车机端 AdaptAPI 实装，见 AdaptapiAmbientLightApi）
 * ```
 *
 * ## 三件事这一轮补上了
 *
 * 1. **开灯要重试**：车控通道是**异步**连上的（桌面启动那一刻几乎必然还没连上），
 *    以前只在 `start()` 里写一次 `setEnabled(true)` —— 写不进去就永远不亮。
 *    现在按节流重试，直到写成功为止（见 [stateWriter]）。
 * 2. **设置项要立刻生效**：颜色模式 / 固定色 / 灵敏度 / 亮度滑杆改完
 *    立刻重算并下发，不等下一帧频谱（见 [applySettings] / [setBrightness]）。
 * 3. **亮度写绝对值**：用户拖的亮度就是车机亮度功能号的输入，
 *    不用频谱折算值（见 [setBrightness] 的注释）。
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

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var api: AmbientLightApi = NullAmbientLightApi
    private var lastColor: AmbientColor? = null
    private var lastHue = 0f
    private var running = false

    /** 最近一帧频谱（设置项改完要立刻重算颜色，就靠它）。 */
    private var lastBands = FloatArray(0)
    private var lastLevel = 0f

    /**
     * 用户拖出来的**绝对亮度**（0..100）。
     *
     * 车机那边的亮度只有 HIGH / LOW / OFF 三档，写进去的是这个值，
     * 不是频谱算出来的折算值 —— 用户拖到 80 就是 80（折算成 HIGH/LOW）。
     */
    private var absoluteBrightness = 100

    /** 待写入的绝对亮度（写成功才清掉；写失败由 [stateWriter] 重试）。 */
    private var pendingBrightness: Int? = null

    /** 期望的开关状态（车控通道没就绪时先记着，等就绪了再写）。 */
    private var desiredEnabled: Boolean? = null

    /** 已经**写成功**的开关状态（null = 还没写成功过）。 */
    private var writtenEnabled: Boolean? = null

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
        desiredEnabled = enabled
        // 强制重写（车控通道可能刚就绪，也可能上一次写失败了）
        writtenEnabled = null
        if (enabled) start(app) else stop(app)
        writeStateNow()
        notifyStatus()
    }

    /** 开始采集（幂等）。 */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        desiredEnabled = true
        writtenEnabled = null
        if (!running) {
            running = true
            // 绝对亮度的初值取用户设的上限（滑杆一动就会被覆盖）
            absoluteBrightness = Prefs(app).ambientMaxBrightness
            pendingBrightness = absoluteBrightness
            SpectrumAnalyzer.get().addListener(spectrumListener)
            SpectrumAnalyzer.get().start(app)
        }
        // 开灯序列要重试（通道是异步就绪的）—— 见 stateWriter
        scheduleStateWrite()
        writeStateNow()
        notifyStatus()
    }

    /** 停止采集（幂等）。 */
    fun stop(context: Context) {
        desiredEnabled = false
        writtenEnabled = null
        if (!running) {
            // 没在跑也要把「关灯」写出去（上次开灯可能没写成功）
            writeStateNow()
            return
        }
        running = false
        SpectrumAnalyzer.get().removeListener(spectrumListener)
        SpectrumAnalyzer.get().stop()
        writeStateNow()
        notifyStatus()
    }

    /**
     * 亮度滑杆：写用户拖出来的**绝对亮度**（行车 + 静止两档都写）。
     *
     * ## 为什么不用 `AmbientPalette.brightnessFor()` 的折算值
     *
     * 折算值是「频谱算出来的、随音乐忽大忽小的那个亮度」——
     * 拿它写车机的话，用户拖完滑杆，下一帧就被覆盖回去了，
     * 「拖了等于没拖」。用户拖的是**绝对亮度**：
     * 拖到 80 就写 80（车机那边再折算成 HIGH / LOW / OFF）。
     *
     * 频谱的强弱仍然体现在**颜色**上（见 [update]），
     * 所以「音乐律动」看得出来，而亮度由用户说了算。
     */
    fun setBrightness(context: Context, absolute: Int) {
        val v = absolute.coerceIn(0, 100)
        absoluteBrightness = v
        pendingBrightness = v
        writeBrightnessNow()
        // 立刻重算并下发颜色（不用等下一帧频谱）
        applySettings(context, "亮度")
    }

    /**
     * 设置项改完**立刻**生效（颜色模式 / 固定色 / 灵敏度 / 亮度上下限）。
     *
     * 用最近一帧频谱重算颜色并下发 —— 不用等下一帧（10Hz 的话最多 100ms，
     * 但用户拖滑杆时是要「跟手」的）。
     */
    fun applySettings(context: Context, reason: String) {
        val bands = lastBands
        if (bands.isEmpty()) {
            // 还没采到频谱 → 没有颜色可写，但亮度要写下去
            writeBrightnessNow()
            notifyStatus()
            return
        }
        Logx.d(LogTags.AMBIENT, "设置项变更，重算颜色", "reason" to reason)
        update(bands, lastLevel)
    }

    /**
     * 权限刚拿到（或通道刚就绪）时重启频谱采集。
     *
     * 采集器在「缺 RECORD_AUDIO」时会停在 UNAVAILABLE，
     * 不重启的话它永远起不来。
     */
    fun restartSpectrum(context: Context) {
        val app = context.applicationContext
        if (!running) return
        Logx.i(LogTags.AMBIENT, "重启频谱采集", "reason" to "权限或通道状态变化")
        SpectrumAnalyzer.get().restart(app)
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
        val source = analyzer.sourceLabel
        return "频谱：$base${if (reason.isBlank()) "" else "（$reason）"} · 来源：$source · 通道：$api"
    }

    // ------------------------------------------------------------------ 开灯重试

    /**
     * 开灯序列的重试（**这是「灯不亮」的直接原因**）。
     *
     * ## 为什么必须重试
     *
     * 车控通道（AdaptAPI）是**异步**连上的：桌面 `onCreate` 那一刻
     * 车控服务几乎必然还没连上，于是 `gateway()` 返回 null，
     * `setEnabled(true)` 直接失败 —— 而以前**只写一次**，就永远丢掉了。
     *
     * 这里按 [STATE_WRITE_INTERVAL_MS] 节流重试，直到写成功为止。
     * 写成功就不再排下一次（不空转）。
     */
    private val stateWriter = object : Runnable {
        override fun run() {
            writeStateNow()
            // 还有没写成功的东西 → 继续重试；否则不再排下一次
            if (pendingBrightness != null || (desiredEnabled != null && writtenEnabled != desiredEnabled)) {
                main.postDelayed(this, STATE_WRITE_INTERVAL_MS)
            }
        }
    }

    /** 排一次（延迟一点，给车控通道留出连接时间）。 */
    private fun scheduleStateWrite() {
        main.removeCallbacks(stateWriter)
        main.postDelayed(stateWriter, STATE_WRITE_INTERVAL_MS)
    }

    /**
     * 连续写失败的次数（用于日志节流）。
     *
     * 重试是每 3 秒一次，车控通道要是始终探不到，
     * 每 3 秒一条日志会把日志文件刷满 —— 所以**第一次**和**每第 10 次**才记。
     */
    private var failLogCount = 0

    /** 写失败时记一笔（带节流）。 */
    private fun logWriteFailure(message: String, vararg fields: Pair<String, Any?>) {
        failLogCount++
        if (failLogCount % FAIL_LOG_EVERY != 1) return
        Logx.d(LogTags.AMBIENT, message, *fields, "连续失败次数" to failLogCount)
    }

    /**
     * 立刻写一次期望的状态（开关 + 亮度）。
     *
     * @return true = 都写成功了
     */
    private fun writeStateNow(): Boolean {
        var ok = true
        val want = desiredEnabled
        if (want != null && writtenEnabled != want) {
            val wrote = api.setEnabled(want)
            if (wrote) {
                writtenEnabled = want
                failLogCount = 0
                Logx.i(LogTags.AMBIENT, "氛围灯开关已下发", "enabled" to want)
            } else {
                ok = false
                // 具体原因由通道自己记（多半是「车控通道还没就绪」）
                logWriteFailure("氛围灯开关下发失败，稍后重试", "enabled" to want)
            }
        }
        if (!writeBrightnessNow()) ok = false
        return ok
    }

    /**
     * 写绝对亮度（行车 + 静止两档都写）。
     *
     * 厂商把亮度拆成**两个功能号**（行车 / 静止），
     * 只写行车那一套的话，车停着的时候亮度调不动。
     */
    private fun writeBrightnessNow(): Boolean {
        val v = pendingBrightness ?: return true
        val driving = api.setBrightness(v)
        val stationary = api.setStationaryBrightness(v)
        val ok = driving || stationary
        if (ok) {
            pendingBrightness = null
            failLogCount = 0
            Logx.i(
                LogTags.AMBIENT, "氛围灯亮度已下发（绝对亮度）",
                "value" to v, "driving" to driving, "stationary" to stationary,
            )
        } else {
            logWriteFailure("氛围灯亮度下发失败，稍后重试", "value" to v)
        }
        return ok
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
     *
     * ## 亮度不在这里写
     *
     * 车机亮度是**用户拖出来的绝对值**（见 [setBrightness]），
     * 频谱的强弱体现在颜色的明暗上 —— 所以这里只写颜色。
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
        // 颜色按亮度整体缩放：频谱的强弱 → 看得见的明暗。
        // 不缩放的话「灵敏度 / 亮度上下限」就没有任何效果（车机亮度写的是用户的绝对值）。
        val scaled = AmbientPalette.scaleByBrightness(computed)
        lastColor = scaled
        lastBands = bands
        lastLevel = level
        // 原始频谱先给出去（设置页画频谱条）
        listeners.forEach { it.onAmbientSpectrum(bands, level) }
        // 颜色与亮度分开下发（车机氛围灯是「一组灯珠 + 一个统一亮度」）。
        // 通道自己会节流：亮度按三档折算后比较，颜色按间隔 + 变化幅度比较。
        api.apply(scaled)
        listeners.forEach { it.onAmbientColor(scaled) }
    }

    private fun notifyStatus() {
        val text = statusText()
        listeners.forEach { it.onAmbientStatus(text) }
    }

    /**
     * 状态写入的重试节奏（毫秒）。
     *
     * 3 秒一次：车控通道连上通常在几百毫秒~几秒内，
     * 太快会刷日志，太慢会让用户等。
     */
    const val STATE_WRITE_INTERVAL_MS = 3000L

    /**
     * 连续写失败时，每几次记一条日志。
     *
     * 重试是每 3 秒一次；车控通道要是始终探不到，
     * 每 3 秒一条会把日志刷满 —— 所以第一次和每第 10 次才记。
     */
    const val FAIL_LOG_EVERY = 10
}
