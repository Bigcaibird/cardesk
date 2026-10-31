package com.cardesk.launcher.core.ambient

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.audio.SpectrumAnalyzer
import com.cardesk.launcher.core.audio.SpectrumPerception
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * 氛围灯总控：**频谱 → 颜色 → 氛围灯通道**。
 *
 * ```
 * SpectrumAnalyzer（真实播放频谱，10Hz）
 *      ↓ 绝对 dB 频段
 * SpectrumPerception（感知层：每段自适应参考 + 静音门）
 *      ↓ 相对频段 + 相对电平
 * AmbientPalette（纯函数：频段 → 色相 / 明度 / 彩度）
 *      ↓
 * AmbientMotion（运动层：二阶色相 + attack/decay + 起拍）
 *      ↓ 60Hz 独立时钟逐帧积分
 * AmbientLightApi（车机端 AdaptAPI 实装，见 AdaptapiAmbientLightApi）
 * ```
 *
 * ## 第八十八轮 V2：把「渲染」从「频谱回调」里拆出来
 *
 * ### 为什么要拆
 *
 * 旧版是「频谱来一帧 → 算一个颜色 → 写车机」，整条链都是 **10Hz**。
 * 而平滑（限速、attack/decay）本来就是为了消掉台阶的 ——
 * 可 10Hz 的台阶本身有 **100ms** 那么大，平滑只能把台阶连成斜坡，
 * 斜坡上每 100ms 还是一次可见的「拐点」。
 *
 * 拆开之后：
 *
 * | | 频谱分析 | 颜色计算 / 运动积分 | 写车机 |
 * | --- | --- | --- | --- |
 * | 旧版 | 10 Hz | 10 Hz | 10 Hz（100ms） |
 * | **V2** | 10 Hz | **60 Hz**（独立定时器） | **20 Hz**（50ms） |
 *
 * 频谱还是 10Hz（采集点数的限制不动），
 * 但**运动层在 60Hz 上逐帧积分** —— 平滑出来的曲线是真的连续的。
 *
 * ### 两条链路可以在车上直接对比
 *
 * 设置页的 [Prefs.ambientEngine]：
 * - `0` = **新版**（V2，本文件的主链路）；
 * - `1` = **旧版**（保留现网观感，见 [AmbientMotionLegacy]）。
 *
 * 旧版路径**仍然按 10Hz 步进**（渲染 tick 只在收到新频谱帧时积分，
 * `dt` 固定 100ms）—— 这才是「现网观感」，不是「新版跑慢一点」。
 *
 * ## 第七十九轮 P0：**写车控一律不在主线程**（仍然有效）
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
 * - 颜色 / 开关 / 亮度 / 分区的**所有车控写入**都放到 [writer] 那个后台线程上；
 * - 主线程只做「算颜色 + 更新界面」，一帧的活是纳秒级的；
 * - 后台写入用**取最新一帧**的方式（[enqueueColor]）：
 *   写得慢就丢中间帧，**不排队、不堆积**。
 *
 * > ⚠️ 写入频率从 10Hz 提到 20Hz 之后，这条约束**更要守住**。
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

    /**
     * **一个写入周期内**的感知色差上限（ΔE00）。
     *
     * 演示台实测：旧版单步跳变 ΔE00 ≈ 9.8~32.5，
     * V2 限速后每帧 ≤ 1.2（限速器留了一半余量）。
     *
     * ## 这里的「每帧」是**写下去的那一帧**
     *
     * 写入周期是 [WRITE_INTERVAL_MS]（50ms），渲染周期是 [RENDER_INTERVAL_MS]（16ms）——
     * 一次写入对应 3 帧渲染。所以这个上限要**按比例分摊**到每个渲染帧上，
     * 否则渲染层会跑得比写入层快，写入层就一直追不上（见 [perFrameDeltaECap]）。
     */
    const val DEFAULT_MAX_DELTA_E = 1.8f

    /**
     * 分区驻留的默认时间（毫秒）—— 低于 700ms 会看出硬边。
     */
    const val DEFAULT_ZONE_DWELL_MS = 800

    /**
     * **写入间隔**（毫秒）—— 必须与通道的节流一致。
     *
     * 见 [perFrameDeltaECap]：渲染帧的色差预算要按这个比例分摊。
     */
    const val WRITE_INTERVAL_MS = 50L

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

    // ------------------------------------------------------------------ 感知层

    /**
     * 感知层：绝对 dB → 相对电平。
     *
     * 每段一个慢速参考电平（τ=2s）+ 全局静音门。
     * 没有它的话，绝对 dB 下典型电平远低于 0.55，
     * 明度区间映射永远落到底光 —— 这就是旧版「明暗变化不明显」的根因。
     */
    private val perception = SpectrumPerception()

    /** 分析器给出的**原始**频段（绝对 dB 标定，旧版链路用这个）。 */
    private var rawBands = FloatArray(0)

    /** 感知层归一后的**相对**频段（V2 链路用这个）。 */
    private var perceivedBands = FloatArray(0)

    /** 分析器给出的整体电平（旧版链路用这个）。 */
    private var rawLevel = 0f

    // ------------------------------------------------------------------ 艺术层目标

    /** 目标色相（0..360）。 */
    private var targetHue = 0f

    /**
     * 色相自转的累计相位（0..360，逐帧推进）。
     *
     * 只有 V2、非固定色、且 [Prefs.ambientHueRotateDegPerSec] > 0 时才动。
     * 每次 [start] 归零（相位只是「从哪个颜色开始转」，不需要跨会话保留）；
     * 拖滑杆重算目标时**不**归零 —— 否则每动一下滑杆颜色就跳一下。
     */
    private var rotationPhaseDeg = 0f

    /** 色相自转转速（度/秒，缓存；渲染 tick 是 60Hz，不能每帧查 Prefs）。 */
    private var hueRotateDegPerSec = 0f

    /** 当前颜色模式（缓存，渲染 tick 判定要不要自转用）。 */
    private var currentMode = AmbientPalette.MODE_BASS

    /** 目标明度（0..1）。 */
    private var targetLevel = 0f

    /** 目标彩度（OKLCH 的 C）。 */
    private var targetChroma = 0f

    /** 低频能量（0..1，起拍判定用）。 */
    private var bass = 0f

    /** 是否处于「没有声音」状态（走空闲呼吸）。 */
    private var silent = true

    // ------------------------------------------------------------------ 运动层

    /**
     * V2 动感层的状态（跨帧保留）。
     *
     * 只在主线程上动（渲染 tick 与频谱回调都在主线程），不需要加锁。
     */
    private var motionState = AmbientMotion.State()

    /** V2 动感层实例（参数来自设置项；参数改了要重建）。 */
    private var motion: AmbientMotion = AmbientMotion()

    /** 旧版动感层的状态。 */
    private var legacyState = AmbientMotionLegacy.State()

    /** 旧版动感层（参数写死成现网默认值 —— 这就是「现网观感」）。 */
    private val legacyMotion = AmbientMotionLegacy()

    /** 上一帧的时刻（单调时钟，毫秒）—— 用来算这一帧隔了多久。 */
    private var lastFrameAt = 0L

    /** 上一条逐帧日志的时刻（节流用）。 */
    private var lastFrameLogAt = 0L

    /** 上一次把颜色推给界面预览的时刻（节流用）。 */
    private var lastPublishAt = 0L

    /** 收到新频谱帧了吗（旧版链路用它决定要不要积分）。 */
    private var spectrumDirty = false

    /** 当前是否跑的是旧版链路。 */
    private var legacyMode = false

    /**
     * 当前是否在「跳跃」模式（色相跳变步长 > 0）。
     *
     * 跳跃模式**两层 ΔE 限速都要关**：限速器存在的意义是把跳变抹成滑动，
     * 留着它，一次换色会被拖成十几帧的滑坡 —— 那正是「还是太平顺」的原因。
     * 关掉之后换色在 1~2 个写入周期（50~100ms）内直接到位，和两块桌面
     * 抢写同一个通道时的观感一致。
     */
    private var jumpMode = false

    /**
     * 分区编排是否开启（缓存）。
     *
     * ## 为什么要缓存
     *
     * 渲染 tick 是 **60Hz**，而 `Prefs` 的构造函数每次都要
     * `context.getSharedPreferences(...)` 查一次 ——
     * 每帧都查一次是纯浪费（主线程上）。
     *
     * 设置项变了会走 [applySettings]，那里会重新读一遍缓存。
     */
    private var zoneChoreoEnabled = false

    /** 分区驻留时间（毫秒，缓存）。 */
    private var zoneDwellMs = DEFAULT_ZONE_DWELL_MS

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
        // 新通道要立刻继承当前的引擎/模式策略（写入层限速跟不跟引擎走）。
        impl.setDeltaELimitEnabled(!legacyMode && !jumpMode)
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
            // 感知层重新学一遍参考电平（换歌 / 重开都不该沿用上一首的）
            perception.reset()
            motionState = AmbientMotion.State()
            legacyState = AmbientMotionLegacy.State()
            // 自转相位重新从 0 开始（开灯/重开是一次新会话）
            rotationPhaseDeg = 0f
            // ΔE00 限速的基准也要重置 —— 否则第一帧会拿「上次关灯前」的颜色当基准
            lastRenderedColor = null
            // 分区编排重新开始（开灯序列会把三个区都点亮，巡游从第一区重新起）
            zoneIndex = -1
            zoneLitCount = AmbientLightApi.ZONE_COUNT
            rebuildMotion(Prefs(app))
            SpectrumAnalyzer.get().addListener(spectrumListener)
            SpectrumAnalyzer.get().start(app)
            // 渲染时钟（60Hz）—— 与频谱回调**解耦**
            main.removeCallbacks(renderTicker)
            main.post(renderTicker)
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
        main.removeCallbacks(renderTicker)
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
     * 频谱的强弱仍然体现在**颜色**上（见 [renderFrame]），
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
     * 设置项改完**立刻**生效（颜色模式 / 固定色 / 灵敏度 / 亮度上下限 / 引擎 / 参数）。
     *
     * 用最近一帧频谱重算目标并**立刻渲染一帧** ——
     * 用户拖滑杆时是要「跟手」的，不能等下一个渲染 tick。
     */
    fun applySettings(context: Context, reason: String) {
        val app = context.applicationContext
        appContext = app
        val prefs = Prefs(app)
        // 参数变了 → 运动层要按新参数重建（顺带刷新引擎与分区缓存）
        rebuildMotion(prefs)
        if (rawBands.isEmpty()) {
            // 还没采到频谱 → 没有颜色可写，但亮度要写下去
            writeStateAsync()
            notifyStatus()
            return
        }
        Logx.d(LogTags.AMBIENT, "设置项变更，重算颜色", "reason" to reason)
        computeTargets(prefs)
        // 立刻渲染一帧（不等 tick）
        renderFrame(force = true)
        notifyStatus()
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
        // 引擎从设置项直接读（不是读缓存）——
        // 这一行会在开灯之前就被调用（进设置页时），那时缓存还没初始化。
        val engine = if (appContext?.let { Prefs(it).ambientEngine } == Prefs.ENGINE_LEGACY) "旧版" else "新版"
        return "频谱：$base${if (reason.isBlank()) "" else "（$reason）"} · 来源：$source · 通道：$api · 引擎：$engine"
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

    // ------------------------------------------------------------------ 频谱回调（10Hz）

    private val spectrumListener = object : SpectrumAnalyzer.Listener {
        override fun onSpectrum(bands: FloatArray, level: Float) {
            onSpectrumFrame(bands, level)
        }

        override fun onAnalyzerState(state: SpectrumAnalyzer.State, reason: String) {
            notifyStatus()
        }
    }

    /**
     * 一帧频谱（10Hz）→ 感知层 → 艺术层目标。
     *
     * ## 这一层**不产出颜色**，只产出「目标」
     *
     * 颜色的合成在 60Hz 的渲染 tick 里做（见 [renderFrame]）。
     * 频谱再快也只有 10Hz（采集点数的限制），
     * 但目标值在两次频谱之间保持不变，
     * 运动层照样能以 60Hz 把它平滑地走过去。
     *
     * ## 两条链路的频段不一样
     *
     * - **旧版**用 [rawBands]（绝对 dB 标定）—— 与现网一致；
     * - **新版**用 [perceivedBands]（相对电平）—— 感知层归一后的结果。
     */
    private fun onSpectrumFrame(bands: FloatArray, level: Float) {
        val ctx = appContext ?: return
        val prefs = Prefs(ctx)
        rawBands = bands
        rawLevel = level
        // 感知层：绝对 dB → 相对电平（每段自适应参考 + 静音门）
        val perceived = perception.process(bands, SPECTRUM_FRAME_MS)
        perceivedBands = perceived
        computeTargets(prefs)
        spectrumDirty = true
        // 频谱条给界面（设置页画频谱条用）
        listeners.forEach { it.onAmbientSpectrum(bands, level) }
    }

    /**
     * 艺术层：频段 + 相对电平 → 色相 / 明度 / 彩度三个**目标**。
     *
     * ## 三路信号刻意**不同步**
     *
     * | 输出维度 | 驱动信号 | 特征时间 |
     * | --- | --- | --- |
     * | 色相 | 频谱质心（音色 / 音高结构） | 秒级 |
     * | 明度 | 整体相对电平 | 10~100 ms |
     * | 彩度 | 高频瞬态（镲片、齿音） | 10~50 ms |
     *
     * 三者来自频谱的不同统计量，天然不同步 ⇒ 观感是「流动」而不是「一起抖」。
     */
    private fun computeTargets(prefs: Prefs) {
        val bands = perceivedBands
        val rel = perception.relativeLevel
        // —— 色相：模式 → 质心 → 展开 → 跨度（默认 span=150 / 恒等展开，逐位不变）——
        val span = prefs.ambientHueSpanDeg.toFloat()
        val winLow = prefs.ambientCentroidWindowLow()
        val winSpan = prefs.ambientCentroidWindowSpan()
        currentMode = prefs.ambientMode
        targetHue = when (prefs.ambientMode) {
            AmbientPalette.MODE_FIXED -> prefs.ambientFixedHue.toFloat()
            AmbientPalette.MODE_SPECTRUM ->
                AmbientPalette.centroidHue(bands, motionState.hue, span, winLow, winSpan)
            else ->
                AmbientPalette.lowCentroidHue(bands, motionState.hue, span, winLow, winSpan)
        }
        // —— 明度：相对电平 → 区间映射 → 对比曲线 ——
        targetLevel = AmbientPalette.levelFromRelative(
            relative = rel,
            sensitivity = prefs.ambientSensitivityValue(),
            floorPercent = prefs.ambientFloor.toFloat(),
        )
        // —— 彩度：跟着高频瞬态走（上限可抬，彩虹预设 0.32）——
        targetChroma = AmbientPalette.chromaTarget(
            bands = bands,
            saturation = prefs.ambientSaturationValue(),
            maxChroma = prefs.ambientMaxChromaValue(),
        )
        // —— 低频能量（起拍判定用）——
        bass = AmbientPalette.bassEnergy(bands)
        // —— 静音判定：相对电平太低就是「没有声音」——
        silent = rel < SILENT_RELATIVE_LEVEL
    }

    // ------------------------------------------------------------------ 渲染时钟（60Hz）

    /**
     * 渲染 tick（**60Hz 独立时钟**）。
     *
     * ## 这是第八十八轮最核心的一处改动
     *
     * 旧版是「频谱回调里算颜色」，整条链 10Hz。
     * 现在渲染与频谱**解耦**：频谱仍然 10Hz 提供目标，
     * 颜色在 60Hz 上逐帧积分出来。
     *
     * ## 跑在主线程，但一帧的活是纳秒级的
     *
     * 运动层是纯数学（几个 exp / sin），算一帧的耗时在微秒级；
     * 真正会卡主线程的**车控写入**全在 [writer] 后台线程上（第七十九轮 P0）。
     */
    private val renderTicker = object : Runnable {
        override fun run() {
            renderFrame(force = false)
            if (running) main.postDelayed(this, RENDER_INTERVAL_MS)
        }
    }

    /**
     * 渲染一帧。
     *
     * @param force true = 忽略「有没有新频谱帧」的限制，立刻出一帧
     *   （设置项改完要立刻看到效果）。
     */
    private fun renderFrame(force: Boolean) {
        if (!running && !force) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameAt > 0L) {
            (now - lastFrameAt).coerceIn(1L, MAX_FRAME_DT_MS)
        } else {
            RENDER_INTERVAL_MS
        }
        lastFrameAt = now

        // 分区编排（与引擎无关，两条链路都生效）
        updateZoneChoreo(now)

        if (legacyMode) {
            // 旧版：**只在收到新频谱帧时积分**，dt 固定 100ms ——
            // 这才是「现网观感」，不是「新版跑慢一点」。
            if (!spectrumDirty && !force) return
            spectrumDirty = false
            renderLegacy(now, LEGACY_FRAME_MS)
        } else {
            renderV2(now, dt)
        }
    }

    /** V2 渲染：运动层积分 → OKLCH 颜色 → 下发。 */
    private fun renderV2(now: Long, dt: Long) {
        // 色相自转：目标色相上叠一个**连续**相位（固定色模式不自转 ——
        // 那是用户明确选的某一个颜色，转了就不是「固定色」了）。
        val hue = if (hueRotateDegPerSec > 0f && currentMode != AmbientPalette.MODE_FIXED) {
            rotationPhaseDeg = AmbientPalette.rotationPhase(rotationPhaseDeg, hueRotateDegPerSec, dt)
            AmbientPalette.normalizeHue(targetHue + rotationPhaseDeg)
        } else {
            targetHue
        }
        motionState = motion.step(
            prev = motionState,
            targetHue = hue,
            targetLevel = targetLevel,
            targetChroma = targetChroma,
            bass = bass,
            dtMs = dt,
            nowMs = now,
            silent = silent,
        )
        // 运动层的明度 → 感知明度 L（留一点底，暗部才不是死黑）
        val lightness = AmbientPalette.perceptualLightness(motionState.level)
        val color = AmbientPalette.colorOfOklch(
            hue = motionState.hue,
            lightness = lightness,
            chroma = motionState.chroma,
        )
        publishColor(color, now)
    }

    /** 旧版渲染：现网链路（HSV + 线性缩放 RGB + 10Hz 步进）。 */
    private fun renderLegacy(now: Long, dt: Long) {
        val prefs = appContext?.let { Prefs(it) }
        val mode = prefs?.ambientMode ?: AmbientPalette.MODE_BASS
        val hue = AmbientPalette.targetHue(
            bands = rawBands,
            mode = mode,
            fixedHue = prefs?.ambientFixedHue?.toFloat() ?: 0f,
            fallbackHue = legacyState.hue,
        )
        val level = AmbientPalette.targetLevel(
            bands = rawBands,
            level = rawLevel,
            mode = mode,
            sensitivity = prefs?.ambientSensitivityValue() ?: 1f,
            minBrightness = prefs?.ambientMinBrightness ?: 20,
            maxBrightness = prefs?.ambientMaxBrightness ?: 100,
        )
        legacyState = legacyMotion.step(
            prev = legacyState,
            targetHue = hue,
            targetLevel = level,
            bass = AmbientPalette.bassEnergy(rawBands),
            dtMs = dt,
        )
        val color = AmbientPalette.colorOf(
            hue = legacyState.hue,
            brightness = (legacyState.brightness * 100f).roundToInt(),
        )
        publishColor(color, now)
    }

    /**
     * 把颜色推出去（写车机 + 通知界面）。
     *
     * ## 亮度不在这里写
     *
     * 车机亮度是**用户拖出来的绝对值**（见 [setBrightness]），
     * 频谱的强弱体现在颜色的明暗上 —— 所以这里只写颜色。
     *
     * ## 顺序很重要：分区补偿 → ΔE00 限速 → 下发
     *
     * 分区补偿会把 RGB 整体乘 1.6（只亮一个区时），
     * 那是一次**突变** —— 必须在限速**之后**才能被限速器看到，
     * 否则切换分区时会出现一次可见的亮度跳变。
     *
     * ## 写车控挪到后台线程（第七十九轮 P0）
     *
     * 主线程只做「算颜色 + 通知界面」，写交给 [enqueueColor]。
     */
    private fun publishColor(color: AmbientColor, now: Long) {
        // **旧版链路不限速** —— 旧版是现网验证过的观感（10Hz 硬跳变），
        // 限速是 V2 才有的东西。给它套上就等于把旧版压成一条慢坡，
        // 「旧版 vs 新版」的比对就没有意义了。
        val compensated = applyZoneCompensation(color)
        // 旧版与跳跃模式都不限速：跳跃要的就是「瞬间换色」，
        // 限速器会把一次跳变抹成十几帧的滑坡（就是「太平顺」的来源）。
        val adjusted = if (legacyMode || jumpMode) compensated else limitDeltaE(compensated)
        lastColor = adjusted
        logFrame(now, adjusted)
        // 界面预览节流到 20Hz —— 预览不需要 60Hz，再快也看不出来
        if (now - lastPublishAt >= PUBLISH_INTERVAL_MS) {
            lastPublishAt = now
            listeners.forEach { it.onAmbientColor(adjusted) }
        }
        // 颜色与亮度分开下发（车机氛围灯是「一组灯珠 + 一个统一亮度」）。
        // 通道自己会节流：亮度按三档折算后比较，颜色按间隔 + 变化幅度比较。
        enqueueColor(adjusted)
    }

    // ------------------------------------------------------------------ ΔE00 限速

    /**
     * 上一个**渲染帧**的颜色（ΔE00 限速的基准）。
     *
     * ## 为什么渲染层与写入层各限一次
     *
     * | | 基准 | 保证 |
     * | --- | --- | --- |
     * | 渲染层（这里） | 上一个渲染帧（60Hz） | 渲染出来的曲线连续，**预览与日志**可信 |
     * | 写入层（[AdaptapiAmbientLightApi]） | 上一个**已写**颜色（20Hz） | **车上的每一帧**都满足上限 |
     *
     * 只在写入层限是不够的：被丢掉的那几帧的变化会攒到下一次写入，
     * 真正落在车上的两帧色差可能好几倍（验收第 2 条就废了）。
     * 只在渲染层限也不够：中间帧被丢掉之后，写下去的两帧仍然可能超限。
     */
    private var lastRenderedColor: AmbientColor? = null

    /** 每帧感知色差上限（ΔE00）—— 语义是「一个写入周期内」。 */
    private var maxDeltaE = DEFAULT_MAX_DELTA_E

    /** 单个渲染帧的色差预算（见下面的纯函数）。 */
    private fun perFrameDeltaECap(): Float = perFrameDeltaECap(maxDeltaE)

    /**
     * **单个渲染帧**能走的色差预算（ΔE00）—— 纯函数（可单测）。
     *
     * = [maxDeltaE] × 渲染周期 / 写入周期
     *
     * ## 为什么要按比例分摊
     *
     * 写入层的限速基准是「上一个**已写**颜色」，
     * 渲染层每 16ms 就往前挪一格，而写入每 50ms 才落一次。
     * 如果两层用**同一个**预算：
     *
     * | | 每 50ms 能走多远 |
     * | --- | --- |
     * | 渲染层（3 帧 × 预算） | 3 × 预算 |
     * | 写入层（1 次 × 预算） | 1 × 预算 |
     *
     * 候选色跑得比写入快一倍 → 写入层每次都要**往回拉** →
     * 写下去的颜色与真实颜色的差**每帧累积**，越差越远（发散）。
     * 几秒之后车上的颜色和音乐已经完全不是一回事了。
     *
     * 分摊之后，渲染层一个写入周期内最多走满 [maxDeltaE]，
     * 写入层的「上一个已写色」正好跟得上 —— 写入层退化成**安全网**，
     * 正常情况下根本不触发，也就没有发散。
     */
    fun perFrameDeltaECap(maxDeltaE: Float): Float {
        if (maxDeltaE <= 0f) return 0f
        return maxDeltaE * RENDER_INTERVAL_MS.toFloat() / WRITE_INTERVAL_MS.toFloat()
    }

    /**
     * ΔE00 限速：把候选颜色拉回到与「上一个渲染帧」相差 ≤ [perFrameDeltaECap]。
     *
     * ## 做法
     *
     * 1. 候选色与上一帧色都转到 CIELAB；
     * 2. 算 ΔE00；
     * 3. 超过上限就按比例往回拉：在 **OKLab** 里插值（感知均匀，不会偏色）。
     *
     * ## 为什么要乘 0.5
     *
     * 线性拉回会刚好落在上限上（ΔE 恰好 = maxDeltaE），
     * 但那是**数学上限** —— 实车上每帧都顶在上限上看着仍然偏跳。
     * 乘 0.5 是留一半余量：实际每帧的色差大约是上限的一半，
     * 观感上就是「连续流动」。
     */
    private fun limitDeltaE(color: AmbientColor): AmbientColor {
        val prev = lastRenderedColor ?: return color
        val cap = perFrameDeltaECap()
        if (cap <= 0f) {
            lastRenderedColor = color
            return color
        }
        val prevLab = AmbientColorSpace.rgbToLab(prev.r, prev.g, prev.b)
        val curLab = AmbientColorSpace.rgbToLab(color.r, color.g, color.b)
        val de = AmbientColorSpace.deltaE00(prevLab, curLab)
        if (de <= cap) {
            lastRenderedColor = color
            return color
        }
        val rgb = AmbientColorSpace.limitByDeltaE(
            prev.r, prev.g, prev.b,
            color.r, color.g, color.b,
            cap,
        )
        val limited = AmbientColor(rgb.first, rgb.second, rgb.third, color.brightness)
        lastRenderedColor = limited
        return limited
    }

    // ------------------------------------------------------------------ 分区编排

    /** 当前点亮的分区（-1 = 整舱统一 / 还没编排）。 */
    private var zoneIndex = -1

    /** 下一次切换分区的时刻。 */
    private var zoneNextAt = 0L

    /** 当前亮着的分区数（1 = 巡游中，3 = 整舱统一）。 */
    private var zoneLitCount = AmbientLightApi.ZONE_COUNT

    /**
     * 分区编排的状态机。
     *
     * ## 为什么只能做「低频编排」
     *
     * 这台车机**只有分区开关，没有分区颜色 / 分区亮度**
     * （`0x2A` 段在本固件上没有实现，`set` 恒 false）——
     * 所以分区切换必然是**硬边**：一片亮、一片黑。
     *
     * 三条规则让它读起来像「呼吸」而不是「闪」：
     * 1. **驻留约束**：每次切换后至少保持 [Prefs.ambientZoneDwellMs]（默认 800ms）——
     *    慢于音乐的 1~2Hz 律动，不与之打架；
     * 2. **亮度补偿**：只亮一个分区时把全局 RGB 调亮一点，
     *    让舱内**总光通量**不至于明显凹陷（见 [applyZoneCompensation]）；
     * 3. **默认关闭**：行车建议三区常亮，只在驻车 / 展示时开启。
     */
    private fun updateZoneChoreo(now: Long) {
        // 关着灯就别发分区指令了 —— 那是白白的车控 IO。
        // （分区开关只是「配置」，灯本身的开关是互动效果。）
        if (!running) return
        if (!zoneChoreoEnabled) {
            if (zoneLitCount != AmbientLightApi.ZONE_COUNT) {
                zoneLitCount = AmbientLightApi.ZONE_COUNT
                zoneIndex = -1
                postZoneCmd(ZoneCmd(open = -1, close = -1))
            }
            return
        }
        if (zoneIndex < 0) {
            zoneIndex = 0
            zoneNextAt = now + zoneDwellMs
            zoneLitCount = 1
            postZoneCmd(ZoneCmd(open = 0, close = -1))
            return
        }
        if (now >= zoneNextAt) {
            val next = (zoneIndex + 1) % AmbientLightApi.ZONE_COUNT
            val prev = zoneIndex
            zoneIndex = next
            zoneNextAt = now + zoneDwellMs
            zoneLitCount = 1
            // 先开新的、再关旧的 —— 中间态是「多一片亮着」，
            // 不会出现「少了一片灯」的凹陷（验收第 8 条）。
            postZoneCmd(ZoneCmd(open = next, close = prev))
        }
    }

    /** 分区指令（`open = -1` 表示「三个区全开」）。 */
    private data class ZoneCmd(val open: Int, val close: Int)

    /** 待写的分区指令（后台线程只取最新一条）。 */
    private val pendingZoneCmd = AtomicReference<ZoneCmd?>(null)

    /** 把分区指令排到后台写线程。 */
    private fun postZoneCmd(cmd: ZoneCmd) {
        pendingZoneCmd.set(cmd)
        writer()?.post(zoneWriteTask)
    }

    /** 后台分区写入任务（跑在 [writer] 线程上）。 */
    private val zoneWriteTask = Runnable {
        val cmd = pendingZoneCmd.getAndSet(null) ?: return@Runnable
        runCatching {
            if (cmd.open < 0) {
                for (z in 0 until AmbientLightApi.ZONE_COUNT) api.setZoneEnabled(z, true)
            } else {
                api.setZoneEnabled(cmd.open, true)
                if (cmd.close >= 0) api.setZoneEnabled(cmd.close, false)
            }
        }.onFailure { Logx.w(LogTags.AMBIENT, "氛围灯分区编排下发异常", it) }
    }

    /**
     * 分区亮度补偿。
     *
     * ## 为什么需要
     *
     * 三个区都亮 → 只亮一个区，舱内的**总光通量**少掉三分之二，
     * 肉眼会看到「灯暗了一下」—— 这正是验收第 8 条要消除的硬边。
     *
     * ## 为什么只补偿一部分
     *
     * 这台车机**没有分区亮度**，只有一个全局 RGB ——
     * 补偿只能作用在唯一亮着的那一片上。
     *
     * 理论上要补满就得把 RGB 乘 3（三个区各 1/3 光通量 → 一个区扛 3 份），
     * 但那会直接过曝、颜色也会失真。
     * 所以只补 [ZONE_BRIGHTNESS_COMPENSATION] 倍 ——
     * 剩下那点凹陷靠**先开后关**的顺序（见 [updateZoneChoreo]）来消化。
     */
    private fun applyZoneCompensation(color: AmbientColor): AmbientColor {
        if (zoneLitCount >= AmbientLightApi.ZONE_COUNT) return color
        val k = ZONE_BRIGHTNESS_COMPENSATION
        return AmbientColor(
            (color.r * k).roundToInt().coerceIn(0, 255),
            (color.g * k).roundToInt().coerceIn(0, 255),
            (color.b * k).roundToInt().coerceIn(0, 255),
            color.brightness,
        )
    }

    // ------------------------------------------------------------------ 逐帧日志

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
     * 于是「目标是台阶、当前是斜坡」在日志里一眼可见。
     *
     * ## 为什么节流到 50ms
     *
     * V2 渲染是 60Hz，每帧都记的话一天能写几十兆日志。
     * 50ms 一次（= 写入频率）既看得出「在动」，又压得住日志量。
     */
    private fun logFrame(now: Long, color: AmbientColor) {
        if (now - lastFrameLogAt < FRAME_LOG_INTERVAL_MS) return
        lastFrameLogAt = now
        Logx.d(
            LogTags.AMBIENT, "氛围灯帧",
            "engine" to if (legacyMode) "旧版" else "新版",
            "targetHue" to targetHue,
            "hue" to motionState.hue,
            "targetLevel" to (targetLevel * 100f).roundToInt(),
            "level" to (motionState.level * 100f).roundToInt(),
            "chroma" to motionState.chroma,
            "rel" to perception.relativeLevel,
            "gate" to perception.gate,
            "bass" to bass,
            "hex" to color.toHex(),
        )
    }

    // ------------------------------------------------------------------ 写入线程

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
     * 渲染 60Hz，车控写得慢的话队列会越堆越长（越堆越慢，越慢越堆）。
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
     * 按当前设置项重建运动层。
     *
     * 运动层的参数（attack / decay / hueTau / 限速 / 限加速 / 呼吸幅度）
     * 都是构造参数，不是运行期可改的 —— 所以设置项一变就重建一个。
     *
     * ## 注意：重建**不重置状态**
     *
     * 色相与明度是**连续**的：改一个参数不该让灯「跳一下」。
     * 所以只换实例，状态（[motionState]）继续用。
     */
    private fun rebuildMotion(prefs: Prefs) {
        maxDeltaE = prefs.ambientMaxDeltaE()
        val legacy = prefs.ambientEngine == Prefs.ENGINE_LEGACY
        if (legacy != legacyMode) {
            // **换引擎要重置限速基准** —— 两条链路的颜色不是一个量纲
            // （旧版是 HSV 直接算的跳变，新版是 OKLCH 的连续曲线）。
            // 拿旧基准去限新颜色会莫名其妙地拉一下。
            lastRenderedColor = null
            legacyState = AmbientMotionLegacy.State()
            motionState = AmbientMotion.State()
            // 相位是「从哪个颜色开始转」—— 换引擎时顺手归零，反正颜色已经重置了
            rotationPhaseDeg = 0f
        }
        legacyMode = legacy
        jumpMode = prefs.ambientHueJumpDeg > 0
        // 写入层的限速跟着引擎/模式走（旧版与跳跃不限速，见 [AmbientLightApi.setDeltaELimitEnabled]）。
        api.setDeltaELimitEnabled(!legacyMode && !jumpMode)
        zoneChoreoEnabled = prefs.ambientZoneChoreo
        zoneDwellMs = prefs.ambientZoneDwellMs
        // 自转转速缓存下来：渲染 tick 60Hz，不能每帧构造一次 Prefs
        hueRotateDegPerSec = prefs.ambientHueRotateDegPerSec.toFloat()
        motion = AmbientMotion(
            hueTauMs = prefs.ambientHueTauMs.toFloat(),
            hueMaxDegPerSec = prefs.ambientHueMaxDeg.toFloat(),
            hueMaxAccelDegPerSec2 = prefs.ambientHueMaxAccel.toFloat(),
            attackMs = prefs.ambientAttackMs.toFloat(),
            decayMs = prefs.ambientDecayMs.toFloat(),
            breathAmount = prefs.ambientBreathValue(),
            // 「跳跃」三件套：色相跳变档位 / 起拍闪光强度 / 起拍最短间隔
            hueJumpDeg = prefs.ambientHueJumpDeg.toFloat(),
            jumpHoldMs = prefs.ambientJumpHoldMs.toLong(),
            pulseGain = prefs.ambientPulseGainValue(),
            beatMinGapMs = prefs.ambientBeatMinGapMs.toLong(),
        )
    }

    const val STATE_WRITE_INTERVAL_MS = 3000L

    /**
     * 连续写失败时，每几次记一条日志。
     *
     * 重试是每 3 秒一次；车控通道要是始终探不到，
     * 每 3 秒一条会把日志刷满 —— 所以第一次和每第 10 次才记。
     */
    const val FAIL_LOG_EVERY = 10

    /**
     * **渲染时钟的间隔（毫秒）** —— 60Hz。
     *
     * 这是 V2 的核心：颜色合成不再跟着频谱的 10Hz 走。
     */
    const val RENDER_INTERVAL_MS = 16L

    /**
     * 旧版链路的帧间隔（毫秒）—— 固定 100ms（10Hz）。
     *
     * 旧版是「频谱来一帧算一帧」，所以它的 dt 就该是频谱的帧间隔；
     * 跟真实的墙上时钟走只会引入抖动。
     */
    const val LEGACY_FRAME_MS = 100L

    /** 频谱帧间隔（毫秒）—— 分析器 10Hz，用来喂感知层的 dt。 */
    const val SPECTRUM_FRAME_MS = 100L

    /** 单帧时间差上限（毫秒）。 */
    const val MAX_FRAME_DT_MS = 1000L

    /**
     * 界面预览的推送间隔（毫秒）—— 20Hz。
     *
     * 预览是给人看的，60Hz 推过去人眼也看不出区别，
     * 反而让设置页多做事。
     */
    const val PUBLISH_INTERVAL_MS = 50L

    /**
     * 逐帧日志的间隔（毫秒）—— 50ms（= 写入频率）。
     */
    const val FRAME_LOG_INTERVAL_MS = 50L

    /**
     * 「没有声音」的判定阈值（相对电平）。
     *
     * 低于它就走空闲态：色相缓慢漂移 + 呼吸底光。
     */
    const val SILENT_RELATIVE_LEVEL = 0.02f

    /**
     * 分区亮度补偿倍数（只亮一个分区时）。
     *
     * 见 [applyZoneCompensation]：理论上是 3，实测 1.6 更合适
     * —— 补满会过曝，剩下那点凹陷靠「先开后关」的顺序消化。
     */
    const val ZONE_BRIGHTNESS_COMPENSATION = 1.6f
}
