package com.cardesk.launcher.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.Permissions
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 播放频谱采集（第七十轮）。
 *
 * ## 数据从哪来
 *
 * 用系统自带的 `android.media.audiofx.Visualizer` 挂在**正在播放的音频会话**上，
 * 拿 `getFft()` 的频域数据（比麦克风录音靠谱得多：不受车内噪音影响、也不占麦）。
 *
 * 会话 id 从 `AudioManager.OnAudioPlaybackConfigurationChangedListener` 拿
 * （`AudioPlaybackConfiguration.getAudioSessionId()`，API 26+）——
 * 这是公开 API 里唯一能拿到「别人正在放的那个会话」的口子。
 *
 * ## 权限
 *
 * - `RECORD_AUDIO`：抓**别的 App** 的输出必须要它（system app 用 privapp 白名单给）；
 * - `MODIFY_AUDIO_SETTINGS`：注册播放配置监听器必须要它。
 *
 * 两个都拿不到时状态是 [State.UNAVAILABLE]，设置页会直接写清楚缺哪个。
 *
 * ## 线程
 *
 * 采集回调在**创建 Visualizer 的线程**（这里是主线程）上；
 * 对外也统一在主线程回调，方便界面直接用。
 */
class SpectrumAnalyzer private constructor() {

    interface Listener {
        /** 一帧频谱（0..1 的频段能量）+ 整体电平。 */
        fun onSpectrum(bands: FloatArray, level: Float)

        /** 状态变化。 */
        fun onAnalyzerState(state: State, reason: String)
    }

    /** 采集状态。 */
    enum class State(val label: String) {
        /** 没启动。 */
        IDLE("未启动"),

        /** 已启动，但在等音乐播起来（还没拿到活动会话）。 */
        WAITING("等待音乐播放"),

        /** 正在采集。 */
        RUNNING("采集中"),

        /** 用不了（缺权限 / 系统不支持）。 */
        UNAVAILABLE("不可用"),
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var visualizer: Visualizer? = null
    private var sessionId = 0
    private var started = false
    private var smoothed = FloatArray(0)

    /** 最近一帧的频段（0..1）。 */
    var bands: FloatArray = FloatArray(0)
        private set

    /** 最近一帧的整体电平（0..1）。 */
    var level: Float = 0f
        private set

    /** 当前状态。 */
    var state: State = State.IDLE
        private set

    /** 状态说明（缺哪个权限 / 报什么错）。 */
    var stateReason: String = ""
        private set

    // ------------------------------------------------------------------ 生命周期

    /** 启动采集。 */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = am
        if (am == null) {
            setState(State.UNAVAILABLE, "拿不到 AudioManager")
            return
        }
        if (!hasRecordAudio(app)) {
            // 排障场景：频谱起不来，先看是不是缺录音权限
            setState(State.UNAVAILABLE, "缺少 RECORD_AUDIO 权限")
            Logx.w(
                LogTags.AMBIENT, "频谱采集不可用：缺少 RECORD_AUDIO",
                "pkg" to app.packageName,
            )
            return
        }
        try {
            am.registerAudioPlaybackCallback(playbackCallback, main)
            Logx.i(LogTags.AMBIENT, "已注册播放配置监听", "sampleRate" to sampleRateOf(am))
        } catch (t: Throwable) {
            Logx.w(LogTags.AMBIENT, "注册播放配置监听失败", t)
            setState(State.UNAVAILABLE, t.message ?: "注册播放配置监听失败")
            return
        }
        // 已经有人在放的话，监听器回调可能已经过去了 → 主动查一次
        queryActivePlayback(am)
    }

    /** 停止采集。 */
    fun stop() {
        started = false
        releaseVisualizer()
        audioManager?.let { am ->
            runCatching { am.unregisterAudioPlaybackCallback(playbackCallback) }
        }
        bands = FloatArray(0)
        level = 0f
        smoothed = FloatArray(0)
        setState(State.IDLE, "")
    }

    /** 订阅（回调在主线程）。 */
    fun addListener(l: Listener) {
        listeners.add(l)
        l.onAnalyzerState(state, stateReason)
    }

    /** 退订。 */
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ------------------------------------------------------------------ 采集

    /**
     * 播放配置变化（公开 API：API 26+ 的 `AudioManager.AudioPlaybackCallback`）。
     *
     * 回调给的是 `AudioPlaybackConfiguration` 列表 —— 但**会话 id 在公开 jar 里没有**
     * （`getAudioSessionId()` 是 @hide），所以靠 [sessionIdOf] 反射拿。
     */
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val id = configs?.firstNotNullOfOrNull { sessionIdOf(it) } ?: 0
            onSessionChanged(id)
        }
    }

    private fun queryActivePlayback(am: AudioManager) {
        val active = runCatching { am.activePlaybackConfigurations?.firstOrNull() }.getOrNull()
        val id = sessionIdOf(active)
        onSessionChanged(id)
    }

    /** 活动会话变了：0 = 没在放了；否则挂上去。 */
    private fun onSessionChanged(id: Int) {
        if (id == sessionId) return
        if (id == 0) {
            // 没有在放的东西了 → 释放掉，等下一个会话
            releaseVisualizer()
            setState(State.WAITING, "")
        } else {
            attach(id)
        }
    }

    /**
     * 从 `AudioPlaybackConfiguration` 里反射拿 audioSessionId。
     *
     * `getAudioSessionId()` 在 AOSP 里是 **@hide**（android-29 / android-34 的公开 jar 里都没有），
     * 只能反射 —— 本项目启动时已经 `setHiddenApiExemptions("L")` 放开了隐藏 API，
     * 所以反射能调到；调不到就当「没有活动会话」，不影响别的功能。
     */
    private fun sessionIdOf(config: Any?): Int {
        if (config == null) return 0
        return runCatching {
            val m = config.javaClass.getMethod("getAudioSessionId")
            (m.invoke(config) as? Int) ?: 0
        }.getOrDefault(0)
    }

    /** 挂到某个音频会话上。 */
    private fun attach(id: Int) {
        releaseVisualizer()
        val ctx = appContext ?: return
        val am = audioManager ?: return
        val created = runCatching {
            Visualizer(id).apply {
                setCaptureSize(CAPTURE_SIZE)
                setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
                // 签名是 (listener, fftRate, waveformCapture, fftCapture) —— 波形和频谱
                // 是一起采的，帧率只有一个参数。
                setDataCaptureListener(
                    captureListener,
                    CAPTURE_RATE_HZ,
                    true,
                    true,
                )
                setEnabled(true)
            }
        }.getOrElse { t ->
            Logx.w(LogTags.AMBIENT, "频谱挂载失败", t, "sessionId" to id)
            setState(State.UNAVAILABLE, t.message ?: "挂载失败")
            return
        }
        visualizer = created
        sessionId = id
        setState(State.RUNNING, "")
        Logx.i(
            LogTags.AMBIENT, "频谱采集已挂上",
            "sessionId" to id, "captureSize" to CAPTURE_SIZE,
            "sampleRate" to sampleRateOf(am), "ctx" to (ctx.packageName),
        )
    }

    private fun releaseVisualizer() {
        visualizer?.let { v ->
            runCatching { v.setEnabled(false) }
            runCatching { v.setDataCaptureListener(null, 0, false, false) }
            runCatching { v.release() }
        }
        visualizer = null
        sessionId = 0
    }

    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(
            visualizer: Visualizer?,
            waveform: ByteArray?,
            samplingRate: Int,
        ) {
            waveform ?: return
            level = SpectrumBands.levelFromWaveform(waveform)
        }

        override fun onFftDataCapture(
            visualizer: Visualizer?,
            fft: ByteArray?,
            samplingRate: Int,
        ) {
            fft ?: return
            val raw = SpectrumBands.fromFft(fft, SpectrumBands.DEFAULT_BAND_COUNT, samplingRate)
            val norm = SpectrumBands.normalize(raw)
            smoothed = SpectrumBands.smooth(smoothed, norm, SMOOTH_ALPHA)
            bands = smoothed
            publish()
        }
    }

    /** 把这一帧推给订阅者。 */
    private fun publish() {
        if (listeners.isEmpty()) return
        val snapshot = bands.copyOf()
        val lv = level
        main.post {
            listeners.forEach { it.onSpectrum(snapshot, lv) }
        }
    }

    // ------------------------------------------------------------------ 杂项

    private fun setState(s: State, reason: String) {
        if (state == s && stateReason == reason) return
        state = s
        stateReason = reason
        Logx.i(LogTags.AMBIENT, "频谱采集状态", "state" to s.name, "reason" to reason)
        main.post {
            listeners.forEach { it.onAnalyzerState(s, reason) }
        }
    }

    private fun hasRecordAudio(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
            Permissions.hasRecordAudioAppOp(context)

    private fun sampleRateOf(am: AudioManager): Int =
        runCatching {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull() ?: SpectrumBands.DEFAULT_SAMPLE_RATE

    companion object {
        /** 采集点数（2 的幂，256~8192）。512 → FFT 字节 256 个 → 覆盖到采样率 / 4。 */
        const val CAPTURE_SIZE = 512

        /** 采集帧率（Hz）。25 → 每 40ms 一帧，够跟得上鼓点又不费电。 */
        const val CAPTURE_RATE_HZ = 25

        /** 帧间平滑系数（越大越跟手）。 */
        const val SMOOTH_ALPHA = 0.35f

        @Volatile
        private var instance: SpectrumAnalyzer? = null

        /** 单例。 */
        fun get(): SpectrumAnalyzer = instance ?: synchronized(this) {
            instance ?: SpectrumAnalyzer().also { instance = it }
        }
    }
}
