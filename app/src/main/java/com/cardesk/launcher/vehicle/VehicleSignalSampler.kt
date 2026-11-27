package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.cardesk.launcher.core.AutomationEngine
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * **车控信号采样器**（第220轮）。
 *
 * ## 为什么要有它
 *
 * 桌面每秒刷新一次（[com.cardesk.launcher.ui.LauncherView.refresh]），
 * 刷新时要拿一整套车辆信号：续航、驾驶模式、胎压、油量、车门、车窗、总里程、行程、油耗……
 *
 * 这些值**全部来自跨进程读取**，而降级链是**逐档试**的：
 *
 * | 信号 | 降级链 | 每轮读取次数 |
 * | --- | --- | --- |
 * | 车门 / 引擎盖 / 后备箱 | car_signal → IDoorAndWindow → BCM_DOOR_POS → DOOR_POS 属性 → MC 事件属性 | 5 档 × 6 项 |
 * | 车窗 | IDoorAndWindow → BCM_FUNC_WINDOW_POS → WINDOW_POS 属性 | 3 档 × 4 项 |
 * | 总里程 | ISensor 传感器 → 属性通道 | 2 |
 * | 行程 | ITripData → TS_* 属性 | 3~5 |
 * | 驾驶模式 | 一参读 → 带 zone 读（两次） | 1~3 |
 * | 胎压 / 油量 / 油箱容量 / 温度 | ISensor | 4 |
 *
 * 加起来**一轮 30~60 次同步 binder 往返**。
 *
 * 以前这些全在**主线程**上做。实车（高通 820A）一次 binder 往返 1~3ms，
 * 于是主线程每秒被堵 **50~150ms** ——
 * 诊断包里能直接量出来：那台车的「1 秒心跳」实际跑成 **1089~1153ms**，
 * 多出来的 89~153ms 就是这么来的。界面每秒掉几帧，交互时更明显。
 *
 * ## 现在
 *
 * 采样搬到这个对象自己的**后台线程**上：
 *
 * ```
 * 后台线程（每 1 秒）           主线程
 *   currentVehicleInfo()   ──►  snapshot（@Volatile）
 *   （30~60 次 binder）           refresh() 只读快照，不碰车控
 * ```
 *
 * 主线程一帧的活变成「读一个已经算好的不可变对象」，**纳秒级**。
 *
 * ## 口径不变
 *
 * 采样**频率不变**（还是每秒一轮）、**读的内容不变**、**降级链顺序不变** ——
 * 只是换了个线程做。车端看到的东西完全一样。
 *
 * ## 什么时候用同步读
 *
 * - [ensureSnapshot]：第一次（还没采到快照）时同步读一次 ——
 *   冷启动那一帧本来就慢，多这一次读没有体感差别；
 * - 用户主动触发（切驾驶模式、打开车况面板）时用 [snapshot]，
 *   **不再**同步读几十次 —— 那次点击原本要卡 100ms 以上。
 */
object VehicleSignalSampler {

    /** 后台线程名（诊断用）。 */
    const val THREAD_NAME = "cardesk-vehicle-signal"

    /** 采样周期（毫秒）。 */
    const val PERIOD_MS = 1000L

    /** 最近一份车辆快照（主线程读）。 */
    @Volatile
    private var snapshot: VehicleInfo? = null

    /** 快照的采集时刻（毫秒，单调时钟）。 */
    @Volatile
    private var snapshotAt = 0L

    /** 已经采了多少轮。 */
    @Volatile
    private var readCount = 0L

    /** 最近一轮采集的耗时（毫秒）。 */
    @Volatile
    private var lastCostMs = 0L

    /** 采集耗时峰值（毫秒）。 */
    @Volatile
    private var maxCostMs = 0L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 应用上下文（供后台线程评估熄火/锁车等自动任务，即使桌面不在前台也能触发）。 */
    @Volatile
    private var appContext: Context? = null

    /** 采样任务（后台线程上跑）。 */
    private var task: Runnable? = null

    /**
     * 起采样（幂等）。
     *
     * @param context 只用来兜底（`currentVehicleInfo()` 自己会找车控通道）
     */
    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        if (handler != null) return
        val t = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        val r = object : Runnable {
            override fun run() {
                readOnce()
                h.postDelayed(this, PERIOD_MS)
            }
        }
        task = r
        // 订阅电源/点火事件回调：熄火/离车锁车瞬间立刻触发自动任务评估，不等 1 秒轮询
        AdaptapiSensors.onIgnitionChanged = { ignEvent ->
            h.post {
                appContext?.let { ctx ->
                    runCatching {
                        AutomationEngine.tick(
                            ctx,
                            Prefs(ctx),
                            AutomationEngine.Snapshot(ignitionState = ignEvent),
                        )
                    }.onFailure { e ->
                        Logx.w(LogTags.TASK, "电源状态回调自动任务评估异常", e)
                    }
                }
            }
        }
        // 立刻采一轮（不等 1 秒）—— 冷启动时桌面马上就有真实信号
        h.post(r)
        Logx.i(LogTags.SIGNAL, "车控信号采样已启动（后台线程）", "periodMs" to PERIOD_MS)
    }

    /** 停采样（幂等）。 */
    @Synchronized
    fun stop() {
        AdaptapiSensors.onIgnitionChanged = null
        val h = handler
        val r = task
        if (h != null && r != null) h.removeCallbacks(r)
        handler = null
        task = null
        thread?.quitSafely()
        thread = null
    }

    /**
     * 采一轮（**只在后台线程上调用**）。
     *
     * 这里的 `currentVehicleInfo()` 会做 30~60 次同步 binder 往返，
     * 所以它**必须**待在后台线程上。
     */
    private fun readOnce() {
        val t0 = SystemClock.elapsedRealtime()
        val info = try {
            currentVehicleInfo()
        } catch (e: Throwable) {
            Logx.w(LogTags.SIGNAL, "车控信号采样异常", e)
            return
        }
        val cost = SystemClock.elapsedRealtime() - t0
        snapshot = info
        snapshotAt = SystemClock.elapsedRealtime()
        readCount++
        lastCostMs = cost
        if (cost > maxCostMs) maxCostMs = cost
        // 后台持续评估自动任务（留空 isNight，由桌面 UI 刷新继续负责日夜模式条件），
        // 确保高德导航等应用在前台时，熄火 / 离车锁车等状态切换依然能够及时触发。
        appContext?.let { ctx ->
            val prefs = Prefs(ctx)
            // 仅在开启日夜切换时于后台线程刷新车机光感与日间模式缓存，
            // 主线程 1Hz refresh() 只读快照，不再触发同步 Binder IPC 或固件异常堆栈刷屏。
            if (prefs.dayNightEnabled) {
                runCatching { com.cardesk.launcher.core.NightResolver.refreshBackgroundSignals() }
            }
            runCatching {
                AutomationEngine.tick(
                    ctx,
                    prefs,
                    AutomationEngine.Snapshot(
                        tempC = info.ambientTemp.takeIf { !it.isNaN() },
                        aqiIndoor = info.aqiIndoor,
                        pm25Indoor = info.pm25Indoor,
                        speedKmh = info.speedKmh,
                        batteryVoltage = info.batteryVoltage,
                        ignitionState = info.ignitionState,
                    ),
                )
            }.onFailure { e ->
                Logx.w(LogTags.TASK, "后台自动任务评估异常", e)
            }
        }
    }

    /** 最近一份快照（可能为 null = 还没采到）。 */
    fun snapshot(): VehicleInfo? = snapshot

    /** 快照的采集时刻（毫秒，单调时钟；0 = 还没有）。 */
    fun snapshotAgeMs(): Long {
        val at = snapshotAt
        return if (at <= 0L) 0L else SystemClock.elapsedRealtime() - at
    }

    /**
     * 一定要拿到一个快照。
     *
     * 第一次（还没采到）时**同步读一次** ——
     * 冷启动那一帧本来就要等，不差这一次；
     * 之后一律走后台线程的快照，主线程不再碰车控。
     */
    fun ensureSnapshot(): VehicleInfo {
        snapshot?.let { return it }
        val info = try {
            currentVehicleInfo()
        } catch (e: Throwable) {
            Logx.w(LogTags.SIGNAL, "车控信号同步兜底读异常", e)
            VehicleInfo(source = "未连接")
        }
        snapshot = info
        snapshotAt = SystemClock.elapsedRealtime()
        return info
    }

    /** 采样统计（诊断用）。 */
    fun stats(): String =
        "采样 $readCount 轮 · 最近 ${lastCostMs}ms · 峰值 ${maxCostMs}ms · 快照年龄 ${snapshotAgeMs()}ms"

    /** 采样耗时峰值（毫秒；诊断包用）。 */
    fun maxCostMs(): Long = maxCostMs

    /** 采样轮数（诊断包用）。 */
    fun readCount(): Long = readCount
}
