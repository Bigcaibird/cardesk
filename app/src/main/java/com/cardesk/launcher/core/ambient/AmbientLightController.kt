package com.cardesk.launcher.core.ambient

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.audio.SpectrumAnalyzer
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

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
 *
 * ## 第七十九轮 P0：**写车控一律不在主线程**
 *
 * 这一版修的是「一进氛围灯设置页就黑屏、过一会儿闪退」。
 *
 * ### 症状
 *
 * 打开氛围灯页 → 立刻黑屏 → 过一会儿闪退。
 *
 * ### 根因
 *
 * 以前 [update] 在**主线程**里调 `api.apply()`，
 * 而 `api.apply()` 底下是对车控 AIDL 的**同步 binder 事务**（`ICarFunction.setFunctionValue`）。
 * 频谱是 10Hz（见 `SpectrumCapture.POLL_INTERVAL_MS`），
 * 于是变成：**每秒 10 次同步 binder + 每秒 10 次「开文件→写→关文件」落盘**，全在主线程上。
 *
 * 车机那边如果不认氛围灯的功能号，写就**每次都失败**，
 * 而节流时间只在**写成功时**才推进 ⇒ 退化成 10Hz 全量写。
 *
 * 主线程被拖住 → 一帧都画不出来 → 窗口只剩 `windowBackground`（`#05080F`，**黑**），
 * 再久就被系统看门狗当 ANR 杀掉 ⇒ **闪退**。
 *
 * ### 现在
 *
 * - 颜色 / 开关 / 亮度的**所有车控写入**都放到 [writer] 那个后台线程上；
 * - 主线程只做「算颜色 + 更新界面」，一帧的活是纳秒级的；
 * - 后台写入用**取最新一帧**的方式（[enqueueColor]）：
 * 写得慢就丢中间帧，**不排队、不堆积**。
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

    /**
     * 车控写入线程（第七十九轮 P0 新增）。
     *
     * **所有**对车机的同步调用都在这里，主线程不碰。
     */
    @Volatile
    private var writerThread: HandlerThread? = null

    @Volatile
    private var writer: Handler? = null

    private var appContext: Context? = null

    @Volatile
    private var api: AmbientLightApi = NullAmbientLightApi
    private var lastColor: AmbientColor? = null
    private var running = false

    /**
     * 动感层的状态（跨帧保留）。
     *
     * 只在主线程上动（频谱回调与设置项回调都在主线程），不需要加锁。
     */
    private var motionState = AmbientMotion.State()

    /** 动感层实例（参数走默认值，测试里可以另建一个带参数的）。 */
    private val motion = AmbientMotion()

    /** 上一帧的时刻（单调时钟，毫秒）—— 用来算这一帧隔了多久。 */
    private var lastFrameAt = 0L

    /** 上一条逐帧日志的时刻（节流用）。 */
    private var lastFrameLogAt = 0L

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
    @Volatile
    private var pendingBrightness: Int? = null

    /** 期望的开关状态（车控通道没就绪时先记着，等就绪了再写）。 */
    @Volatile
    private var desiredEnabled: Boolean? = null

    /** 已经**写成功**的开关状态（null = 还没写成功过）。 */
    @Volatile
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
     *
     * ## 写车控不在这里做
     *
     * 开灯一次要写 6~8 个功能号（主色来源 / 体验模式 / 三个分区 / 互动效果 / 两档亮度），
     * 全是**同步 binder** —— 在主线程上连着做会卡住一帧。
     * 所以这里只**改状态**，真正的写交给 [writer] 后台线程（见 [writeStateAsync]）。
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
        writeStateAsync()
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
        writeStateAsync()
        notifyStatus()
    }

    /** 停止采集（幂等）。 */
    fun stop(context: Context) {
        desiredEnabled = false
        writtenEnabled = null
        if (!running) {
            // 没在跑也要把「关灯」写出去（上次开灯可能没写成功）
            writeStateAsync()
            return
        }
        running = false
        SpectrumAnalyzer.get().removeListener(spectrumListener)
        SpectrumAnalyzer.get().stop()
        writeStateAsync()
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
        writeStateAsync()
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
            writeStateAsync()
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
     *
     * ## 第七十九轮：**重试的判据挪到后台线程上算**
     *
     * 以前是「在主线程写 → 写完立刻看成没成功 → 决定要不要再排一次」。
     * 现在写本身在后台线程，主线程这里只负责**排下一次**，
     * 判据读的是后台线程写完后更新的字段（[writtenEnabled] / [pendingBrightness]，
     * 都是 `@Volatile`）—— 慢一拍没关系，重试本来就是 3 秒一档。
     */
    private val stateWriter = object : Runnable {
        override fun run() {
            writeStateAsync()
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
    @Volatile
    private var failLogCount = 0

    /** 写失败时记一笔（带节流）。 */
    private fun logWriteFailure(message: String, vararg fields: Pair<String, Any?>) {
        failLogCount++
        if (failLogCount % FAIL_LOG_EVERY != 1) return
        Logx.d(LogTags.AMBIENT, message, *fields, "连续失败次数" to failLogCount)
    }

    /**
     * 把「写期望状态」排到后台线程（**主线程不碰车控**）。
     *
     * 开灯一次要写 6~8 个功能号，全是同步 binder；
     * 在主线程上连着做会卡住一帧（第七十九轮 P0 的成因之一）。
     */
    private fun writeStateAsync() {
        writer()?.post(stateWriteTask)
    }

    /** 后台写任务（跑在 [writer] 线程上）。 */
    private val stateWriteTask = Runnable {
        runCatching { writeStateNow() }
            .onFailure { Logx.w(LogTags.AMBIENT, "氛围灯状态下发异常", it) }
    }

    /**
     * 立刻写一次期望的状态（开关 + 亮度）。
     *
     * **只在后台线程调用**（见 [writeStateAsync]）。
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
     *
     * ## 第七十九轮 P0：**写车控挪到后台线程**
     *
     * 以前这里是主线程直接 `api.apply(scaled)`，
     * 而它底下是对车控 AIDL 的**同步 binder**（10Hz 一帧），
     * 再加一次「开文件→写→关文件」的日志落盘 —— 全在主线程上，
     * 主线程一被拖住，窗口就只剩 `windowBackground`（黑），再久就被看门狗杀掉。
     *
     * 现在主线程只做「算颜色 + 通知界面」，写交给 [enqueueColor]。
     */
    fun update(bands: FloatArray, level: Float) {
        val ctx = appContext ?: return
        val prefs = Prefs(ctx)
        // —— 第一步：算「目标」色相与目标亮度（这一段与以前一样）——
        val targetHue = AmbientPalette.targetHue(
            bands = bands,
            mode = prefs.ambientMode,
            fixedHue = prefs.ambientFixedHue.toFloat(),
            // 没有声音时保留**当前**色相（动感层里的那个），别跳回 0°。
            fallbackHue = motionState.hue,
        )
        val targetLevel = AmbientPalette.targetLevel(
            bands = bands,
            level = level,
            mode = prefs.ambientMode,
            sensitivity = prefs.ambientSensitivityValue(),
            minBrightness = prefs.ambientMinBrightness,
            maxBrightness = prefs.ambientMaxBrightness,
        )
        // —— 第二步：动感层把「目标」变成平滑流动的「当前值」——
        //
        // 这一层是「灯不再僵硬」的关键：
        // 颜色走最短路径 + 限速，亮度快起慢落，低频起拍再叠一下脉冲。
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameAt > 0L) now - lastFrameAt else DEFAULT_FRAME_MS
        lastFrameAt = now
        val bass = AmbientPalette.bassEnergy(bands)
        motionState = motion.step(
            prev = motionState,
            targetHue = targetHue,
            targetLevel = targetLevel,
            bass = bass,
            dtMs = dt,
        )
        // —— 第三步：落到颜色上（亮度整体缩放 RGB，所以明暗变化是连续的）——
        val scaled = AmbientPalette.colorOf(
            hue = motionState.hue,
            brightness = (motionState.brightness * 100f).roundToInt(),
        )
        lastColor = scaled
        lastBands = bands
        lastLevel = level
        logFrame(now, targetHue, targetLevel, bass, dt, scaled)
        // 原始频谱先给出去（设置页画频谱条）
        listeners.forEach { it.onAmbientSpectrum(bands, level) }
        // 颜色与亮度分开下发（车机氛围灯是「一组灯珠 + 一个统一亮度」）。
        // 通道自己会节流：亮度按三档折算后比较，颜色按间隔 + 变化幅度比较。
        enqueueColor(scaled)
        listeners.forEach { it.onAmbientColor(scaled) }
    }

    // ------------------------------------------------------------------ 写入线程

    /**
     * 逐帧日志（**节流**）。
     *
     * ## 为什么要有这一行
     *
     * 「灯在不在流动」这件事以前**看不出来**：
     * - 车控通道写成功时才记颜色（真机上偶尔能看，模拟器上根本写不出去）；
     * - 设置页只显示**当前**一个颜色，看不出它是「流过去的」还是「跳过去的」。
     *
     * 这一行把**目标值**与**平滑后的当前值**并排记下来，
     * 于是「目标是台阶、当前是斜坡」在日志里一眼可见 ——
     * 第八十五轮在模拟器上就是靠它量化出「单帧最大跳变 6 倍改善」的。
     *
     * ## 为什么节流到 500ms
     *
     * 频谱 10Hz，每帧都记的话一天能写几十兆日志。
     * 500ms 一次既看得出「在动」，又压得住日志量。
     */
    private fun logFrame(
        now: Long,
        targetHue: Float,
        targetLevel: Int,
        bass: Float,
        dt: Long,
        color: AmbientColor,
    ) {
        if (now - lastFrameLogAt < FRAME_LOG_INTERVAL_MS) return
        lastFrameLogAt = now
        Logx.d(
            LogTags.AMBIENT, "氛围灯帧",
            "targetHue" to targetHue,
            "hue" to motionState.hue,
            "targetLevel" to targetLevel,
            "brightness" to (motionState.brightness * 100f).roundToInt(),
            "bass" to bass,
            "dtMs" to dt,
            "hex" to color.toHex(),
        )
    }

    /**
     * 车控写入线程（懒创建）。
     *
     * 单例级的一个后台线程，全程只服务氛围灯写入。
     * 线程优先级压到后台 —— 氛围灯是锦上添花，不能跟界面抢 CPU。
     */
    private fun writer(): Handler? {
        writer?.let { return it }
        synchronized(this) {
            writer?.let { return it }
            val thread = HandlerThread("cardesk-ambient-write", Process.THREAD_PRIORITY_BACKGROUND)
            thread.start()
            writerThread = thread
            writer = Handler(thread.looper)
            return writer
        }
    }

    /** 待写的颜色（后台线程只取**最新**的一帧）。 */
    private val pendingColor = AtomicReference<AmbientColor?>(null)

    /** 颜色写入任务是否已经排上（避免每帧都 post 一次，队列会堆起来）。 */
    private val colorWriterScheduled = AtomicBoolean(false)

    /**
     * 排一次颜色写入。
     *
     * ## 为什么是「取最新一帧」而不是「排队」
     *
     * 频谱 10Hz，车控写得慢的话队列会越堆越长（越堆越慢，越慢越堆）。
     * 所以这里只保留**最新的一帧**颜色：
     * 中间的帧直接丢掉 —— 颜色本来就是「当前该是什么颜色」，
     * 补写历史帧没有意义。
     */
    private fun enqueueColor(color: AmbientColor) {
        pendingColor.set(color)
        if (colorWriterScheduled.compareAndSet(false, true)) {
            writer()?.post(colorWriter)
        }
    }

    /** 后台颜色写入任务（跑在 [writer] 线程上）。 */
    private val colorWriter = object : Runnable {
        override fun run() {
            try {
                val c = pendingColor.getAndSet(null)
                if (c != null) {
                    runCatching { api.apply(c) }
                        .onFailure { Logx.w(LogTags.AMBIENT, "氛围灯颜色写入异常", it) }
                }
            } finally {
                colorWriterScheduled.set(false)
                // 写的这会儿又来了新颜色 → 再跑一轮（不排队、不堆积）
                if (pendingColor.get() != null && colorWriterScheduled.compareAndSet(false, true)) {
                    writer()?.post(this)
                }
            }
        }
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

    /**
     * 频谱的标称帧间隔（毫秒）。
     *
     * 只在**第一帧**用（还没有上一帧的时刻可差），
     * 之后都是拿真实的时间差算 —— 平滑系数跟帧率无关。
     */
    const val DEFAULT_FRAME_MS = 100L

    /**
     * 逐帧日志的间隔（毫秒）。
     *
     * **100ms = 频谱帧率**，也就是**每帧一行** —— 这样日志里相邻两行
     * 就是**相邻两帧**，量化出来的「单帧跳变」才是真的。
     * （早先取 500ms，采样比帧率粗，量出来的改善会被低估成一半。）
     *
     * 这一行是 **D 级**，默认**不落盘**（见 `LogLevel`），
     * 所以 10Hz 也不会把日志文件撑爆 —— 需要时用 `adb logcat` 抓。
     */
    const val FRAME_LOG_INTERVAL_MS = 100L
}
