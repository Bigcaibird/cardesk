package com.cardesk.launcher.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.cardesk.launcher.core.Permissions
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 播放频谱采集。
 *
 * ## 数据从哪来（这一轮换了主路径）
 *
 * 主路径是 **`Visualizer(0)` = 全局输出混音**（output mix）：
 * 所有 App 播出来的声音混在一起被抓，**不用追别人的会话 id，也不用隐藏 API**。
 * 参照工程 `uni-launcher`（同一款车机，已跑通）就是这么做的：
 * `uni-launcher/app/android/.../audio/SpectrumBridge.java`。
 *
 * 次选路径是「挂到某个播放会话上」（`Visualizer(sessionId)`）：
 * 会话 id 要靠隐藏 API 反射 `AudioPlaybackConfiguration.getAudioSessionId()` 才拿得到，
 * 隐藏 API 一被拦这条路就废了 —— 所以它**只是兜底**，主路径不依赖它。
 *
 * ## 为什么换主路径
 *
 * 之前只有「挂别人会话」这一条路，而它有两个前置条件：
 * 1. `RECORD_AUDIO` 必须真的被授予（以前全仓没人申请过 → 第一道门就返回）；
 * 2. 隐藏 API 豁免必须成功（豁免方法签名当年写错了 → 必然 `NoSuchMethodException`）。
 *
 * 两个条件任意一个不满足，频谱就永远停在 IDLE —— 灯不亮、频谱条全空，
 * 而且**日志里一条都没有**（静默早退）。这一轮把主路径换成不依赖隐藏 API 的
 * 输出混音，并且所有失败都**可见**。
 *
 * ## 权限
 *
 * - `RECORD_AUDIO`：抓输出必须要它（system app 用 privapp 白名单给）；
 * - `MODIFY_AUDIO_SETTINGS`：注册播放配置监听器必须要它（**只影响次选路径**）。
 *
 * 缺 `RECORD_AUDIO` 时状态是 [State.UNAVAILABLE]，原因写「缺少 RECORD_AUDIO 权限」。
 *
 * ## 线程
 *
 * `Visualizer` 的创建与 `getFft()` 轮询都在**后台线程**（`HandlerThread`）上，
 * 对外回调统一回主线程 —— 界面直接用，不用自己切线程。
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

    /** 采集来源（诊断用）。 */
    enum class Source(val label: String) {
        /** 全局输出混音（`Visualizer(0)`）—— **主路径**。 */
        OUTPUT_MIX("输出混音"),

        /** 挂在别的 App 的播放会话上（靠隐藏 API 拿 sessionId）—— 次选。 */
        SESSION("播放会话"),
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null

    /** 采集线程（创建 Visualizer 与轮询都在这里，别占主线程）。 */
    private var worker: Handler? = null
    private var pollTask: Runnable? = null

    private var visualizer: Visualizer? = null
    private var fftBuffer: ByteArray? = null
    private var captureSize = 0
    private var sampleRate = SpectrumBands.DEFAULT_SAMPLE_RATE
    private var sessionId = 0
    private var source: Source? = null
    private var started = false
    private var smoothed = FloatArray(0)

    /** 隐藏 API 取会话 id 失败只警告一次（避免每次配置变化都刷屏）。 */
    private var sessionIdWarned = false

    /** 最近一帧的频段（0..1）。 */
    @Volatile
    var bands: FloatArray = FloatArray(0)
        private set

    /** 最近一帧的整体电平（0..1）。 */
    @Volatile
    var level: Float = 0f
        private set

    /** 当前状态。 */
    @Volatile
    var state: State = State.IDLE
        private set

    /** 状态说明（缺哪个权限 / 报什么错）。 */
    @Volatile
    var stateReason: String = ""
        private set

    /** 当前采集来源（诊断用；没在采就是「无」）。 */
    val sourceLabel: String get() = source?.label ?: "无"

    // ------------------------------------------------------------------ 生命周期

    /** 启动采集。 */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // ① 权限是第一道门：没有 RECORD_AUDIO，一帧都采不到。
        if (!hasRecordAudio(app)) {
            setState(State.UNAVAILABLE, "缺少 RECORD_AUDIO 权限")
            Logx.w(
                LogTags.AMBIENT, "频谱采集不可用：缺少 RECORD_AUDIO 权限",
                "pkg" to app.packageName,
            )
            return
        }

        // ② 主路径：全局输出混音（Visualizer(0)）—— 不依赖隐藏 API。
        //    创建与轮询都在后台线程上。
        startWorker()
        worker?.post { attachOutputMix(app) }

        // ③ 次选路径：播放配置监听（只有它能给出「谁在放」）。
        //    注册失败**不影响**主路径，所以只记日志、不改状态。
        registerPlaybackCallback()
    }

    /**
     * 重启采集。
     *
     * 权限是**运行时**拿到的（用户进设置页才申请），
     * 拿到之后必须重启一次 —— 否则采集器还停在「缺权限」的死状态里。
     */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    /**
     * 停止采集。
     *
     * ## 第七十九轮 P0：**Visualizer 必须在采集线程上释放**
     *
     * 以前这里是：
     * ```
     * workerThread?.quitSafely()   // 不等待线程真正退出
     * worker = null
     * releaseVisualizer()          // 在**主线程**上 release
     * ```
     *
     * 而采集线程可能正卡在 `v.getFft(buf)` 里 —— 主线程把句柄 release 掉，
     * 采集线程再碰一次就是 **use-after-release**，
     * 结果是 native 层 SIGSEGV（进程直接死，**我们自己的文件日志里一条都没有**）。
     *
     * 现在：
     * - 采集线程**常驻**（不再 `quitSafely`），停采集只是「不再排下一次轮询」；
     * - 释放句柄的任务**排到采集线程上**执行 ⇒ 与 `getFft` 天然串行，不会竞态。
     */
    fun stop() {
        started = false
        pollTask?.let { worker?.removeCallbacks(it) }
        pollTask = null
        // 释放句柄排到采集线程上（同一线程 ⇒ 与 getFft 串行）
        worker?.post { releaseVisualizer() }
        audioManager?.let { am ->
            runCatching { am.unregisterAudioPlaybackCallback(playbackCallback) }
        }
        bands = FloatArray(0)
        level = 0f
        smoothed = FloatArray(0)
        source = null
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
     *
     * ## 为什么这里必须留痕
     *
     * 以前这里是 `if (id == sessionId) return` —— **静默早退**，日志一条都没有。
     * 结果就是「频谱没起来」在日志里完全看不出来（本次缺陷的直接教训）。
     * 现在每次配置变化都记一行（会话数 / 会话 id / 当前采集来源）。
     */
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val list = configs ?: emptyList()
            val id = list.firstNotNullOfOrNull { sessionIdOf(it) } ?: 0
            Logx.i(
                LogTags.AMBIENT,
                "播放配置变化",
                "count" to list.size,
                "sessionId" to id,
                "source" to sourceLabel,
                "note" to if (id == 0) "拿不到会话 id（隐藏 API 可能被拦）" else "",
            )
            if (id == 0) return
            // 主路径在跑 → 会话 id 只作诊断用，不再重复挂一个 Visualizer
            if (source == Source.OUTPUT_MIX) return
            if (source == Source.SESSION && id == sessionId) return
            attachSession(id)
        }
    }

    /**
     * 从 `AudioPlaybackConfiguration` 里反射拿 audioSessionId。
     *
     * `getAudioSessionId()` 在 AOSP 里是 **@hide**（android-29 / android-34 的公开 jar 里都没有），
     * 只能反射 —— 本项目启动时已经 `setHiddenApiExemptions("L")` 放开了隐藏 API，
     * 所以反射能调到；调不到就当「没有活动会话」，不影响主路径。
     *
     * 失败**必须看得见**（以前是完全静默的），但只警告一次，避免刷屏。
     */
    private fun sessionIdOf(config: Any?): Int {
        if (config == null) return 0
        return try {
            val m = config.javaClass.getMethod("getAudioSessionId")
            (m.invoke(config) as? Int) ?: 0
        } catch (t: Throwable) {
            if (!sessionIdWarned) {
                sessionIdWarned = true
                Logx.w(LogTags.AMBIENT, "取 audioSessionId 失败（隐藏 API 没放开？）", t)
            }
            0
        }
    }

    /** 注册播放配置监听（只服务次选路径；失败不影响主路径）。 */
    private fun registerPlaybackCallback() {
        val am = audioManager ?: return
        try {
            am.registerAudioPlaybackCallback(playbackCallback, main)
            Logx.i(LogTags.AMBIENT, "已注册播放配置监听", "sampleRate" to sampleRateOf(am))
        } catch (t: Throwable) {
            Logx.w(LogTags.AMBIENT, "注册播放配置监听失败（不影响输出混音主路径）", t)
        }
    }

    // ------------------------------------------------------------------ 输出混音（主路径）

    /**
     * 起采集线程。
     *
     * ## 第七十九轮：线程**常驻**，不再用完就退
     *
     * 以前 `stop()` 会 `quitSafely()`，下次 `start()` 再建一条新线程。
     * 那样一来「旧线程还没退出」与「新线程已经建好」会撞在一起 ——
     * 旧线程里排着的 `releaseVisualizer()` 可能把**新线程刚建好的句柄**给释放掉。
     *
     * 现在只保留**一条**线程：停采集 = 不再排轮询；句柄的释放也在这条线程上排队。
     */
    private fun startWorker() {
        if (worker != null) return
        val thread = HandlerThread("cardesk-spectrum", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        worker = Handler(thread.looper)
    }

    /**
     * 挂到**全局输出混音**上（主路径）。
     *
     * 挂了不上（车机 ROM 不给 output mix / 权限其实没批下来）时，
     * 退到次选路径，并把失败原因记下来 —— 不能再像以前那样一声不吭。
     */
    private fun attachOutputMix(app: Context) {
        // 跑在采集线程上；停采集之后就别再挂上（stop() 已经排了解放任务）
        if (!started) return
        releaseVisualizer()
        val v = try {
            openVisualizer(SpectrumCapture.OUTPUT_MIX_SESSION_ID)
        } catch (t: Throwable) {
            Logx.w(LogTags.AMBIENT, "输出混音挂载失败", t, "sessionId" to SpectrumCapture.OUTPUT_MIX_SESSION_ID)
            attachSessionFallback(t)
            return
        }
        visualizer = v
        sessionId = SpectrumCapture.OUTPUT_MIX_SESSION_ID
        source = Source.OUTPUT_MIX
        setState(State.RUNNING, "")
        Logx.i(
            LogTags.AMBIENT, "频谱已挂上输出混音",
            "captureSize" to captureSize,
            "fftBytes" to (fftBuffer?.size ?: 0),
            "sampleRate" to sampleRate,
            "source" to Source.OUTPUT_MIX.label,
        )
        schedulePoll()
    }

    /**
     * 次选路径：挂到某个播放会话上。
     *
     * 只有主路径失败时才用；会话 id 拿不到（隐藏 API 被拦）就等下一次配置变化。
     */
    private fun attachSessionFallback(cause: Throwable) {
        // 权限不够 → 次选路径也一样不行（它同样要 RECORD_AUDIO），
        // 直接报「不可用」，别让状态停在「等待音乐播放」上误导人。
        if (SpectrumCapture.isPermissionError(cause)) {
            setState(State.UNAVAILABLE, "缺少 RECORD_AUDIO 权限")
            Logx.w(LogTags.AMBIENT, "频谱采集被系统拒绝：缺少 RECORD_AUDIO", cause)
            return
        }
        val id = currentSessionId()
        if (id == 0) {
            setState(State.WAITING, "输出混音不可用，等待播放会话")
            Logx.w(
                LogTags.AMBIENT, "输出混音与播放会话都不可用",
                "reason" to (cause.message ?: cause.javaClass.simpleName),
            )
            return
        }
        attachSession(id)
    }

    /**
     * 挂到指定的播放会话上。
     *
     * ## 第七十九轮 P0：**必须排到采集线程上做**
     *
     * 调用点有两个：主线程的播放配置回调、以及采集线程里的降级路径。
     * 主线程那条路以前直接 `releaseVisualizer()`，
     * 而采集线程可能正在 `getFft()` —— **use-after-release 会 native SIGSEGV**。
     * 现在统一排到采集线程：与 `getFft` 天然串行。
     */
    private fun attachSession(id: Int) {
        worker?.post { attachSessionOnWorker(id) }
    }

    /** 采集线程上的「挂到播放会话」实现。 */
    private fun attachSessionOnWorker(id: Int) {
        if (!started) return
        releaseVisualizer()
        val v = try {
            openVisualizer(id)
        } catch (t: Throwable) {
            Logx.w(LogTags.AMBIENT, "播放会话频谱挂载失败", t, "sessionId" to id)
            setState(State.UNAVAILABLE, "挂载失败：${t.message ?: t.javaClass.simpleName}")
            return
        }
        visualizer = v
        sessionId = id
        source = Source.SESSION
        setState(State.RUNNING, "")
        Logx.i(
            LogTags.AMBIENT, "频谱已挂上播放会话",
            "sessionId" to id,
            "captureSize" to captureSize,
            "sampleRate" to sampleRate,
            "source" to Source.SESSION.label,
        )
        schedulePoll()
    }

    /** 当前活动会话 id（拿不到就是 0）。 */
    private fun currentSessionId(): Int {
        val am = audioManager ?: return 0
        val active = runCatching { am.activePlaybackConfigurations?.firstOrNull() }.getOrNull()
        return sessionIdOf(active)
    }

    /**
     * 打开一个 Visualizer：设采集点数、启用。
     *
     * @throws RuntimeException 车机 ROM 不支持 / 权限不足 / 通道被占
     */
    private fun openVisualizer(id: Int): Visualizer {
        val v = Visualizer(id)
        try {
            val range = runCatching { Visualizer.getCaptureSizeRange() }.getOrNull()
            val minSize = if (range != null && range.size > 0) range[0] else 0
            val maxSize = if (range != null && range.size > 1) range[1] else 0
            val size = SpectrumCapture.resolveCaptureSize(minSize, maxSize)
            val sizeOk = v.setCaptureSize(size)
            if (sizeOk != Visualizer.SUCCESS) {
                throw IllegalStateException("setCaptureSize 返回 $sizeOk")
            }
            captureSize = size
            // 缓冲区按 captureSize/2 分配（见 SpectrumCapture.fftBufferSize 的说明）
            fftBuffer = ByteArray(SpectrumCapture.fftBufferSize(size))
            sampleRate = resolveSampleRate(v)
            val enabled = v.setEnabled(true)
            if (enabled != Visualizer.SUCCESS) {
                throw IllegalStateException("setEnabled 返回 $enabled")
            }
            return v
        } catch (t: Throwable) {
            runCatching { v.release() }
            throw t
        }
    }

    /** 采样率：Visualizer 的值优先，不合理就退回 AudioManager 的属性值。 */
    private fun resolveSampleRate(v: Visualizer): Int {
        val fromVisualizer = SpectrumCapture.normalizeSampleRate(
            runCatching { v.samplingRate }.getOrDefault(0),
        )
        if (SpectrumCapture.isPlausibleSampleRate(fromVisualizer)) return fromVisualizer
        val am = audioManager
        val fromManager = am?.let { sampleRateOf(it) } ?: 0
        if (SpectrumCapture.isPlausibleSampleRate(fromManager)) return fromManager
        return SpectrumBands.DEFAULT_SAMPLE_RATE
    }

    // ------------------------------------------------------------------ 轮询

    /** 排下一次轮询（10Hz）。 */
    private fun schedulePoll() {
        val h = worker ?: return
        pollTask?.let { h.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!started) return
                captureOnce()
                h.postDelayed(this, SpectrumCapture.POLL_INTERVAL_MS)
            }
        }
        pollTask = task
        h.post(task)
    }

    /**
     * 采一帧（后台线程）。
     *
     * 失败**必须可见**：以前采集失败是静默的，日志里什么都没有。
     *
     * ## 第七十九轮 P0：**`getFft()` 的返回值不能拿来判成败**
     *
     * `Visualizer.getFft(byte[])` 的返回值语义**在不同 ROM 上不一样**：
     *
     * - 有的实现返回**写入的字节数**（> 0）；
     * - 有的实现成功时返回 `Visualizer.SUCCESS`（= **0**）。
     *
     * 参照工程 `uni-launcher` 用的是后者口径：
     * `if (visualizer.getFft(fft) != Visualizer.SUCCESS) throw ...` ——
     * 它在这台车机上**跑得通**，说明这台 ROM 成功时返回的就是 0。
     *
     * 而我们以前写的是 `if (bytes <= 0) return`（把 0 当成「本帧无数据」）——
     * **两套口径正好相反** ⇒ 在这台 ROM 上**每一帧成功都被当成没数据**，
     * 频谱永远是空的（这就是「频谱也没有」的直接原因）。
     *
     * 现在：**不靠返回值判断成败**，只看有没有抛异常；
     * 返回值只用来决定「这一帧用缓冲区的前几个字节」。
     */
    private fun captureOnce() {
        val v = visualizer ?: return
        val buf = fftBuffer ?: return
        val bytes = try {
            v.getFft(buf)
        } catch (t: Throwable) {
            Logx.w(LogTags.AMBIENT, "频谱采集失败", t, "source" to sourceLabel)
            setState(
                State.UNAVAILABLE,
                "采集失败：${t.message ?: t.javaClass.simpleName}",
            )
            releaseVisualizer()
            return
        }
        // 返回值 > 0 且小于缓冲区长度 ⇒ 只用前这么多个字节；
        // 返回 0（= SUCCESS 口径）⇒ 整个缓冲区都是这一帧的。
        val frame = if (bytes > 0 && bytes < buf.size) buf.copyOf(bytes) else buf
        val raw = SpectrumBands.fromFft(frame, SpectrumBands.DEFAULT_BAND_COUNT, sampleRate)
        val calibrated = SpectrumBands.calibrate(raw)
        smoothed = SpectrumBands.smooth(smoothed, calibrated, SMOOTH_DECAY)
        bands = smoothed
        level = smoothed.maxOrNull() ?: 0f
        publish()
    }

    /**
     * 释放 Visualizer 句柄。
     *
     * **只在采集线程上调用**（见 [stop]）——
     * 与 `getFft()` 同一线程 ⇒ 不存在 use-after-release。
     */
    private fun releaseVisualizer() {
        val v = visualizer
        // 先把字段清掉再释放：万一释放过程抛异常，也不会留下一个「看起来还在用」的句柄
        visualizer = null
        fftBuffer = null
        captureSize = 0
        sessionId = 0
        if (v == null) return
        runCatching { v.setEnabled(false) }
        runCatching { v.release() }
    }

    /** 把这一帧推给订阅者（主线程）。 */
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

        /**
         * 帧间**回落**系数（非对称平滑，第七十九轮对齐 uni-launcher）。
         *
         * `v = max(当前值, 上一帧 × 0.70)`：**瞬时上升、缓慢回落**。
         * 以前是对称 EMA（α=0.35），上升沿只有 35% 权重 ⇒ 灯跟不上鼓点。
         */
        const val SMOOTH_DECAY = 0.70f

        @Volatile
        private var instance: SpectrumAnalyzer? = null

        /** 单例。 */
        fun get(): SpectrumAnalyzer = instance ?: synchronized(this) {
            instance ?: SpectrumAnalyzer().also { instance = it }
        }
    }
}
