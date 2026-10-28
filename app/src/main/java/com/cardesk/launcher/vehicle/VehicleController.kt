package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 车控单例：负责选通道、装监听、把 UI 动作转给 [HvacLogic]。
 *
 * 通道选择：设置里「自动」= 先探测 AdaptAPI，探不到就用模拟车；
 * 也可以强制「模拟」或「真车」。连接期间不阻塞 UI，全部异步回到主线程。
 *
 * ## 为什么要暴露写入失败
 *
 * 车控写入失败时，最早的版本会**静默**回退到模拟车辆：UI 照常乐观更新，
 * 看起来「点得动」，但车机一点反应也没有——这就是「实际车控功能无效」的观感来源。
 * 现在这条链路上每一次失败都会留痕，并且通过 Toast + 设置页暴露给用户：
 *  - [lastWriteOk] / [lastWriteError]：最近一次写入的结果与原因；
 *  - [sourceLabel]：当前实际生效的通道（模拟车会明确标出）。
 */
object VehicleController {

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<(HvacState) -> Unit>()

    lateinit var gateway: VehicleGateway
        private set

    lateinit var logic: HvacLogic
        private set

    var sourceLabel: String = "未初始化"
        private set

    var initialized = false
        private set

    val state: HvacState
        get() = logic.state

    /** 360 状态（真车 PAC 回调或模拟开关） */
    var on360State: ((Boolean) -> Unit)? = null

    /**
     * 车身状态（车门 / 引擎盖 / 后备箱）变化的回调。
     *
     * 是**事件驱动**的：BCM_DOOR_POS 的函数值一变
     * （`onFunctionValueChanged`）就立刻
     * `car_signal` 的信号回调同理。
     *
     * 我们桌面是每秒轮询一次，所以车门开了最多要等 1 秒才显示 ——
     * 这里把车端的变化回调接出来，桌面**立刻**刷新车况卡。
     */
    var onBodyStateChanged: (() -> Unit)? = null

    /** 还没执行的下一次 360 视角请求（单飞）。 */
    private var pending360View: Runnable? = null

    /** 当前平台画像（屏幕密度/长边/签名/uid/通知权），设置页与兼容逻辑共用。 */
    var platformProfile: com.cardesk.launcher.core.PlatformProfile? = null
        private set

    /** 最近一次车控写入是否成功。 */
    var lastWriteOk: Boolean = true
        private set

    /** 最近一次写入失败的原因（成功时为 null）。 */
    var lastWriteError: String? = null
        private set

    /** 最近一次写入的时间戳（毫秒，SystemClock）。 */
    var lastWriteAt: Long = 0L
        private set

    /** 是否已经回退到模拟车辆（= 车控不会作用于实车）。 */
    var isMockFallback: Boolean = false
        private set

    /** 上一次通知出去的空调状态快照（用于只记录「变化」）。 */
    private var lastNotifiedState: HvacState? = null

    /** 是否有一笔「写入失败后的回读回滚」在等结果（用来把回滚结果也记下来）。 */
    private var rollbackPending = false

    private var appCtx: Context? = null

    /** 应用上下文（供信号/媒体等兼容通道延迟探测）。 */
    fun appContext(): Context? = appCtx

    fun init(context: Context, prefs: Prefs) {
        if (initialized) return
        initialized = true
        appCtx = context.applicationContext
        // `car_signal` 信号变化 → 车身状态变化 → 桌面立刻刷新
        // （收到变化就 publishBodyState）。
        EcarxSignals.onSignalChanged = {
            main.post { onBodyStateChanged?.invoke() }
        }
        platformProfile = com.cardesk.launcher.core.Platforms.detect(context, prefs.platformOverride)
        val profile = platformProfile!!
        val wantReal = when (prefs.vehicleSource) {
            2 -> true
            1 -> false
            else -> true
        }
        val candidates = if (wantReal) {
            val order = com.cardesk.launcher.core.Platforms.gatewayPriority(profile.platform)
            order.mapNotNull { channel ->
                when (channel) {
                    VehicleGateway.CHANNEL_ADAPTAPI ->
                        if (AdaptapiGateway.probe()) AdaptapiGateway(context) else null
                    VehicleGateway.CHANNEL_CARLIB ->
                        if (CarLibGateway.probe()) CarLibGateway(context) else null
                    VehicleGateway.CHANNEL_MOCK -> MockVehicleGateway()
                    else -> null
                }
            }
        } else {
            listOf(MockVehicleGateway())
        }
        val list = if (candidates.isEmpty()) listOf(MockVehicleGateway()) else candidates
        // 排障场景：要能回答「这台车机上到底选了哪条通道、按什么顺序试的」。
        Logx.i(
            LogTags.VEHICLE,
            "初始化车控",
            "platform" to profile.platform.label,
            "wantReal" to wantReal,
            "candidates" to list.joinToString(",") { it.id },
            "vehicleSourcePref" to prefs.vehicleSource,
        )
        tryNext(list, 0)
    }

    /** 依次尝试通道，全部失败才落到模拟车。 */
    private fun tryNext(candidates: List<VehicleGateway>, index: Int) {
        if (index >= candidates.size) {
            fallbackToMock()
            return
        }
        val gw = candidates[index]
        sourceLabel = "${gw.displayName}（连接中…）"
        // 排障场景：通道降级链的每一跳都要留痕——
        // 「真车通道连不上」是车控无效最常见的原因。
        Logx.i(
            LogTags.VEHICLE,
            "尝试连接车控通道",
            "channel" to gw.id,
            "impl" to gw.displayName,
            "index" to index,
            "total" to candidates.size,
        )
        bind(gw)
        gw.connect { ok ->
            main.post {
                if (ok) {
                    sourceLabel = gw.displayName
                    Logx.i(
                        LogTags.VEHICLE,
                        "车控通道连接成功",
                        "channel" to gw.id,
                        "impl" to gw.displayName,
                        "serviceConnected" to runCatching { gw.isServiceConnected() }.getOrDefault(false),
                    )
                    appCtx?.let { ctx -> runCatching { EcarxSignals.connect(ctx) } }
                    notifyChanged()
                    main.postDelayed({ resync() }, 1500L)
                } else {
                    val reason = gw.lastError() ?: "通道未返回失败原因"
                    Logx.w(
                        LogTags.VEHICLE,
                        "车控通道连接失败",
                        "channel" to gw.id,
                        "impl" to gw.displayName,
                        "reason" to reason,
                    )
                    try {
                        gw.disconnect()
                    } catch (ignored: Throwable) {
                        Logx.d(LogTags.VEHICLE, "断开失败通道时异常", "channel" to gw.id)
                    }
                    tryNext(candidates, index + 1)
                }
            }
        }
    }

    /** 运行时切换数据源：断开旧通道后按 prefs 重新探测/连接。 */
    fun reconnect(context: Context, prefs: Prefs) {
        // 排障场景：用户改设置里的「车控数据源」后要重连，日志里要能看到这次切换。
        Logx.i(LogTags.VEHICLE, "重连车控通道", "vehicleSourcePref" to prefs.vehicleSource)
        if (::gateway.isInitialized) {
            try {
                gateway.disconnect()
            } catch (t: Throwable) {
                // 旧通道断不开也不影响新通道
                Logx.w(LogTags.VEHICLE, "断开旧通道异常", t, "channel" to runCatching { gateway.id }.getOrDefault("?"))
            }
        }
        initialized = false
        isMockFallback = false
        lastWriteError = null
        lastWriteOk = true
        init(context, prefs)
    }

    fun addObserver(observer: (HvacState) -> Unit) {
        observers.add(observer)
        if (initialized) observer(state)
    }

    fun removeObserver(observer: (HvacState) -> Unit) {
        observers.remove(observer)
    }

    // ------------------------------------------------------------ 空调动作

    fun togglePower() = act("电源") { togglePower() }
    fun toggleAc() = act("A/C") { toggleAc() }
    fun toggleAuto() = act("AUTO") { toggleAuto() }
    fun toggleCirculation() = act("循环模式") { toggleCirculation() }
    fun stepFan(delta: Int) = act("风量") { stepFan(delta) }
    /** MAX 风量：7↔3 两态切换，未开机先上电（场景页 MAX 键）。 */
    fun toggleMaxFan() = act("MAX风量") { toggleMaxFan() }
    fun stepTemp(zone: Int, delta: Float) = act("温度") { stepTemp(zone, delta) }
    fun toggleSync() = act("双区同步") { toggleSync() }
    fun toggleDefrostFront() = act("前除霜") { toggleDefrostFront() }
    fun toggleDefrostRear() = act("后除霜") { toggleDefrostRear() }
    fun setBlowing(index: Int) = act("吹风模式") { setBlowing(index) }

    /** 按位掩码设吹风（face=1 / leg=2 / window=4）。 */
    fun setBlowingMask(mask: Int) = act("吹风模式(掩码)") { setBlowingMask(mask) }
    fun cycleSeat(zone: Int, heat: Boolean) = act(if (heat) "座椅加热" else "座椅通风") { cycleSeat(zone, heat) }

    private inline fun act(op: String, block: HvacLogic.() -> Unit) {
        if (!initialized) {
            Logx.w(LogTags.HVAC, "空调操作被忽略（车控未初始化）", "op" to op)
            return
        }
        val before = logic.writeAttempts
        logic.block()
        // 只有真的写过车才汇报结果（有些操作会因为值没变而直接返回）
        if (logic.writeAttempts != before) reportWriteResult(op)
        notifyChanged()
        scheduleResync()
    }

    /**
     * 把最近一次写入的结果记录下来，并在失败时给出可见提示。
     *
     * 失败时立刻回读一次，把乐观更新回滚——否则 UI 会一直显示一个车机并不认可的状态。
     */
    private fun reportWriteResult(op: String) {
        val ok = logic.lastWriteOk
        lastWriteOk = ok
        lastWriteAt = SystemClock.elapsedRealtime()
        if (ok) {
            lastWriteError = null
            Logx.i(
                LogTags.HVAC,
                "车控写入成功",
                "op" to op,
                "channel" to runCatching { gateway.id }.getOrDefault("?"),
                "ok" to true,
            )
            return
        }
        val reason = gateway.lastError() ?: "通道 ${runCatching { gateway.id }.getOrDefault("?")} 未返回成功"
        lastWriteError = reason
        // 排障场景：写入失败是用户最常反馈的问题——失败原因、是否回读回滚都要能看到。
        Logx.w(
            LogTags.HVAC,
            "车控写入失败",
            "op" to op,
            "channel" to runCatching { gateway.id }.getOrDefault("?"),
            "reason" to reason,
            "rollback" to "计划回读",
        )
        toast("车控写入失败：$reason")
        // 回滚乐观值
        rollbackPending = true
        main.post { resync() }
    }

    // ------------------------------------------------------------ 360

    fun set360(on: Boolean): Boolean {
        if (!initialized) {
            Logx.w(LogTags.CAM360, "360 操作被忽略（车控未初始化）", "on" to on)
            return false
        }
        val ok = gateway.set360(on)
        if (!ok) {
            val reason = gateway.lastError() ?: "通道 ${runCatching { gateway.id }.getOrDefault("?")} 未接受 360 请求"
            lastWriteError = reason
            lastWriteOk = false
            // 排障场景：360 打不开/关不掉，要能看出是写入被拒还是通道不支持。
            Logx.w(
                LogTags.CAM360,
                "360 写入失败",
                "on" to on,
                "channel" to runCatching { gateway.id }.getOrDefault("?"),
                "reason" to reason,
            )
        } else {
            Logx.event(
                LogTags.CAM360,
                if (on) "打开 360" else "关闭 360",
                "channel" to runCatching { gateway.id }.getOrDefault("?"),
                "ok" to true,
            )
        }
        return ok
    }

    fun select360View(view: Int): Boolean {
        if (!initialized) {
            Logx.w(LogTags.CAM360, "360 视角切换被忽略（车控未初始化）", "view" to view)
            return false
        }
        val ok = gateway.select360View(view)
        if (!ok) {
            val reason = gateway.lastError() ?: "通道 ${runCatching { gateway.id }.getOrDefault("?")} 未接受视角切换"
            lastWriteError = reason
            lastWriteOk = false
            Logx.w(
                LogTags.CAM360,
                "360 视角切换失败",
                "view" to view,
                "channel" to runCatching { gateway.id }.getOrDefault("?"),
                "reason" to reason,
            )
        } else {
            Logx.event(
                LogTags.CAM360,
                "切换 360 视角",
                "view" to view,
                "channel" to runCatching { gateway.id }.getOrDefault("?"),
                "ok" to true,
            )
        }
        return ok
    }

    /**
     * 按时序切 360 视角
     * 延时（设置里的「视角延迟」，是 100/300/800ms）→ 下发 → 800ms 后回读校验
     * 不一致就补发一次。
     *
     *  是**单飞**的：新一次调度会撤掉上一次还没执行的视角请求
     * （`MAIN.removeCallbacks(APPLY_VIEW)`），这里用同一个 pending 句柄保持一致。
     *
     * @param view 车端视角编号（0..9，-1 = 不设置）
     */
    fun schedule360View(view: Int, delayMs: Int) {
        if (!initialized) {
            Logx.w(LogTags.CAM360, "360 视角调度被忽略（车控未初始化）", "view" to view)
            return
        }
        pending360View?.let { main.removeCallbacks(it) }
        Logx.d(LogTags.CAM360, "调度 360 视角", "view" to view, "delayMs" to delayMs)
        val runnable = Runnable {
            pending360View = null
            select360View(view)
            // 是写完 700ms 回读校验
            // 这里取 800ms，回读不一致就补发一次。
            main.postDelayed({
                val readBack = runCatching {
                    gateway.readValue(HvacIds.FUNC_360_VIEW, HvacIds.ZONE_GLOBAL)
                }.getOrNull()
                if (readBack != null && readBack != view) {
                    Logx.w(
                        LogTags.CAM360, "360 视角回读不一致，补发一次",
                        "want" to view, "actual" to readBack,
                    )
                    select360View(view)
                }
            }, VIEW_VERIFY_MS)
        }
        pending360View = runnable
        main.postDelayed(runnable, delayMs.toLong())
    }

    /**
     * 车控通道自检：写一次 A/C 并读回，用来在真机上确认「写」这条路是通的。
     *
     * 返回一行可直接展示给用户的结果描述。
     */
    fun selfTest(): String {
        if (!initialized) return "车控未初始化"
        val before = logic.state.ac
        val want = !before
        Logx.i(LogTags.VEHICLE, "车控自检开始", "channel" to runCatching { gateway.id }.getOrDefault("?"))
        val ok = gateway.setHvac(HvacIds.AC, HvacIds.ZONE_GLOBAL, if (want) 1 else 0)
        if (!ok) {
            val reason = gateway.lastError() ?: "通道 ${runCatching { gateway.id }.getOrDefault("?")} 未返回成功"
            lastWriteOk = false
            lastWriteError = reason
            Logx.w(LogTags.VEHICLE, "车控自检：写入失败", "reason" to reason)
            return "写入失败：$reason"
        }
        lastWriteOk = true
        lastWriteError = null
        val readBack = gateway.readValue(HvacIds.AC, HvacIds.ZONE_GLOBAL)
        val readText = when {
            readBack == null -> "回读失败（该通道不支持回读）"
            (readBack != 0) == want -> "回读一致（A/C = ${if (want) "开" else "关"}）"
            else -> "回读不一致（写入 ${if (want) "开" else "关"}，读到 ${if (readBack != 0) "开" else "关"}）"
        }
        // 自检改变了车机状态，写回原值
        gateway.setHvac(HvacIds.AC, HvacIds.ZONE_GLOBAL, if (before) 1 else 0)
        // 排障场景：自检结果要能回答「写这条路到底通不通」。
        Logx.i(
            LogTags.VEHICLE,
            "车控自检完成",
            "write" to "ok",
            "readBack" to readBack,
            "result" to readText,
        )
        return "写入成功，$readText"
    }

    // ------------------------------------------------------------ 同步

    /**
     * **车辆信号自检**（第三十六轮新增）：逐条探测各条**读取**通道，输出一份可读结论。
     *
     * 与 [selfTest]（测「写」这条路）互补——这里测的是「读」：
     * 车门、车窗、总里程、续航、油耗、车速、行程各读得到读不到。
     *
     * 真机上这四条通道能不能通，之前只能靠翻日志猜；现在点一下就有明确结论。
     */
    fun signalSelfTest(): String {
        val ctx = appCtx
        val lines = ArrayList<String>(8)

        // —— CarPropertyManager（车门 / 车窗 / 总里程）——
        val carOk = ctx != null && CarBodySignals.attach(ctx)
        lines += "CarPropertyManager：${if (carOk) "可用" else "不可用（${CarProperties.lastError() ?: "未知原因"}）"}"

        val windows = if (carOk) CarBodySignals.readWindows() else null
        lines += "车窗 WINDOW_POS：${describeWindows(windows)}"

        val doors = if (carOk) CarBodySignals.readDoorsViaProperty() else null
        lines += "车门 DOOR_POS：${describeDoors(doors)}"

        // 第四十五轮新增的第四档：MC / Geely 事件属性。
        // 这组属性是 CEVT 平台的，ECARX 上大概率没有 —— 读不到就是「没有」，不影响其它档。
        val mc = McDoorSignals.read()
        lines += "车门 MC 事件属性：${if (mc == null) "读不到（该平台没有这组属性）" else {
            describeDoors(mc.doors) + " 后备箱=" + (mc.trunk?.let { if (it) "关" else "开" } ?: "?")
        }}"

        // 车身状态走的是**哪一条来源**（降级链
        // car_signal → BCM_DOOR_POS → DOOR_POS 属性。
        // 真车排障最需要的就是这一行 —— 「车门状态读不到」时能立刻知道卡在哪一档。
        val body = runCatching { BodyState.resolve(gateway, EcarxSignals.isAvailable()) }.getOrNull()
        lines += "车身状态降级链：${if (body == null) "读不到" else {
            "来源=${body.source} 全关=${body.allClosed} 有效=${body.valid}"
        }}"

        val odo = if (carOk) CarBodySignals.readOdometerKm() else null
        lines += "总里程 PERF_ODOMETER：${if (odo != null) "$odo km" else "读不到"}"

        // —— AdaptAPI 通道 ——
        lines += "续航 ISensor：${runCatching { AdaptapiSensors.rangeKm() }.getOrDefault(-1).let { if (it > 0) "$it km" else "读不到" }}"
        // 第六十四轮：油量这一行把**原始值 + 油箱容量**一起报出来 ——
        // 「油量没拿到」到底是传感器没推、还是容量没读到，只有这两个数能分清。
        val fuelPct = runCatching { AdaptapiSensors.fuelPercent() }.getOrDefault(-1)
        val fuelRaw = runCatching { AdaptapiSensors.lastFuelRaw() }.getOrDefault(Float.NaN)
        val fuelCap = runCatching { AdaptapiSensors.fuelCapacityValue() }.getOrDefault(0f)
        lines += "油量 ISensor：${if (fuelPct >= 0) "$fuelPct%" else "读不到"}" +
            "（原始值 ${if (fuelRaw.isNaN()) "无" else fuelRaw}，油箱容量 ${if (fuelCap > 0f) fuelCap else "读不到"}）"
        lines += "胎压 TPMS：${runCatching { AdaptapiTpms.tirePressures() }.getOrNull()?.let { a ->
            a.joinToString(",") { if (it.isNaN()) "-" else String.format(java.util.Locale.US, "%.2f", it) }
        } ?: "读不到"}"

        // —— 行程 / 油耗（HEV/PHEV 才有）——
        val trip = TripDataReader.stats()
        lines += "行程 ITripData：${if (TripDataReader.isAvailable()) "可用" else "不可用（${TripDataReader.lastError() ?: "无 getHevManager"}）"}"
        // 第三十七轮：平均油耗与本行程油耗**分开报**。
        // 之前只报「平均油耗」一个口径，而桌面卡片显示的是
        // `avgFuelConsumption ?: tripFuelConsumption` 的兜底值 —— 两者对不上，
        // 诊断快照里就会出现「平均油耗：读不到」但日志里明明有值的矛盾。
        lines += "平均油耗：${trip.avgFuelConsumption?.let { String.format(java.util.Locale.US, "%.2f L/100km", it) } ?: "读不到"}"
        lines += "本行程油耗：${trip.tripFuelConsumption?.let { String.format(java.util.Locale.US, "%.2f L/100km", it) } ?: "读不到"}"
        lines += "行程里程/时长：${trip.tripKm?.let { String.format(java.util.Locale.US, "%.1f km", it) } ?: "--"} / ${trip.tripDurationMs?.let { "${it / 60000.0} 分钟" } ?: "--"}"

        val text = lines.joinToString("\n")
        Logx.i(LogTags.SIGNAL, "车辆信号自检", "report" to text.replace("\n", " | "))
        return text
    }

    private fun describeWindows(flags: BooleanArray?): String = when {
        flags == null -> "读不到"
        else -> flags.joinToString(",") { if (it) "开" else "关" }
    }

    private fun describeDoors(flags: List<Boolean?>?): String = when {
        flags == null -> "读不到"
        else -> flags.joinToString(",") { c -> c?.let { if (it) "关" else "开" } ?: "?" }
    }

    private val resyncRunnable = Runnable { resync() }

    private fun scheduleResync() {
        main.removeCallbacks(resyncRunnable)
        main.postDelayed(resyncRunnable, HvacLogic.HOLD_MS + 250L)
    }

    /** 从车端全量读回，修正任何本地偏差（含其他端改了空调的情况）。 */
    fun resync() {
        if (!initialized) return
        val channel = runCatching { gateway.id }.getOrDefault("?")
        val snapshot = try {
            gateway.readAll()
        } catch (t: Throwable) {
            Logx.w(LogTags.HVAC, "回读全量失败", t, "channel" to channel)
            emptyMap()
        }
        var changed = logic.applySnapshot(snapshot)
        // 双区项按区域单独读回，避免副驾温度/座椅被主驾值覆盖
        for (func in listOf(HvacIds.TEMP, HvacIds.SEAT_HEAT, HvacIds.SEAT_VENT)) {
            val value = try {
                gateway.readValue(func, HvacIds.ZONE_PASSENGER)
            } catch (t: Throwable) {
                Logx.d(LogTags.HVAC, "回读分区属性失败", "func" to VehicleLog.hex(func), "channel" to channel)
                null
            } ?: continue
            if (logic.applyZone(func, HvacIds.ZONE_PASSENGER, value)) changed = true
        }
        if (changed) notifyChanged()
        // 排障场景：写入失败后的「回读回滚」靠这里完成，
        // 所以要能看到这次回读到底有没有把状态拉回车机的真值。
        Logx.d(
            LogTags.HVAC,
            "回读对齐完成",
            "channel" to channel,
            "snapshotSize" to snapshot.size,
            "changed" to changed,
        )
        if (rollbackPending) {
            rollbackPending = false
            // 回滚结果单独用 INFO 落盘：这是「UI 显示的和车机实际不一致」的关键证据。
            Logx.i(
                LogTags.HVAC,
                "回读回滚完成",
                "channel" to channel,
                "rolledBack" to changed,
                "state" to stateSummary(logic.state),
            )
        }
    }

    /**
     * 当前通道上的值变化监听者。
     *
     * 换通道时必须用它把旧通道的监听摘掉 —— 旧网关的监听者列表是长生命周期的
     * （CopyOnWriteArrayList），不摘就是泄漏，而且旧通道的回调还会继续触发刷新。
     */
    private var valueListener: ((Int, Int, Int) -> Unit)? = null

    private fun bind(gw: VehicleGateway) {
        val previous = if (::gateway.isInitialized) runCatching { gateway.id }.getOrDefault("?") else "无"
        if (::gateway.isInitialized) {
            // 排障场景：切通道后旧通道还在回调（重复刷新 / 内存泄漏）——摘监听要留痕。
            val old = gateway
            valueListener?.let { l ->
                runCatching { old.unregisterListener(l) }
                    .onFailure { Logx.w(LogTags.VEHICLE, "摘除旧通道监听失败", it, "from" to previous) }
                Logx.d(LogTags.VEHICLE, "摘除旧通道监听者", "from" to previous, "to" to gw.id)
            }
            old.on360State = null
        }
        gateway = gw
        // 排障场景：通道实现切换（AdaptAPI / AOSP Car / 模拟车）必须留痕——
        // 「车控点了没反应」时，第一件事就是确认当前到底跑在哪条通道上。
        Logx.i(LogTags.VEHICLE, "切换车控通道实现", "from" to previous, "to" to gw.id, "impl" to gw.displayName)
        gw.on360State = { on ->
            Logx.i(LogTags.CAM360, "360 状态通知", "on" to on, "channel" to gw.id)
            main.post { on360State?.invoke(on) }
        }
        logic = HvacLogic(
            clock = { SystemClock.elapsedRealtime() },
            write = { func, zone, value -> gw.setHvac(func, zone, value) },
            writeTempCelsius = { zone, celsius -> gw.setTemp(zone, celsius) },
            writeDefrost = { func, on -> gw.setDefrost(func, on) },
            // 写前从车端同步真值（每个 toggle 前的 sync*FromVehicle）
            readSync = { func, zone -> gw.readValue(func, zone) },
        )
        val listener: (Int, Int, Int) -> Unit = { func, zone, value ->
            main.post {
                // 车身状态变化：立刻刷新桌面（是事件驱动的 publishBodyState）
                if (func == AdaptapiBody.BCM_DOOR_POS) {
                    Logx.i(LogTags.SIGNAL, "车门位置回调", "zone" to zone, "value" to value)
                    onBodyStateChanged?.invoke()
                }
                if (logic.onRemote(func, zone, value)) notifyChanged()
            }
        }
        valueListener = listener
        gw.registerListener(listener)
    }

    private fun fallbackToMock() {
        val old = gateway.displayName
        val mock = MockVehicleGateway()
        bind(mock)
        isMockFallback = true
        sourceLabel = "$old 不可用，已回退模拟车辆"
        // 排障场景：回退模拟车 = 车控**不会**作用于实车，这是最关键的降级，必须落盘。
        Logx.w(LogTags.VEHICLE, "所有真车通道都失败，回退模拟车辆", "failed" to old, "mockFallback" to true)
        mock.connect { ok ->
            main.post {
                if (ok) {
                    sourceLabel = "模拟车辆（$old 无权限）"
                    Logx.w(LogTags.VEHICLE, "已切换到模拟车辆", "source" to sourceLabel)
                    notifyChanged()
                    main.postDelayed({ resync() }, 300L)
                    toast("未连上真车通道（$old），已切换到模拟车辆：车控不会作用于实车")
                }
            }
        }
    }

    private fun toast(message: String) {
        val ctx = appCtx ?: return
        runCatching {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun notifyChanged() {
        if (!initialized) return
        val snapshot = logic.state
        val prev = lastNotifiedState
        lastNotifiedState = snapshot
        if (prev != null && prev != snapshot) {
            // 排障场景：空调状态快照变化记一次。
            // 关键开关（电源/A-C/AUTO/风量/循环/除霜）变化用 INFO 落盘，
            // 其余（温度、座椅、吹风）用 DEBUG，避免高频刷盘。
            val keyChanged = prev.power != snapshot.power ||
                prev.ac != snapshot.ac ||
                prev.auto != snapshot.auto ||
                prev.fanLevel != snapshot.fanLevel ||
                prev.circulation != snapshot.circulation ||
                prev.defrostFront != snapshot.defrostFront ||
                prev.defrostRear != snapshot.defrostRear
            if (keyChanged) {
                Logx.i(LogTags.HVAC, "空调状态变化", "state" to stateSummary(snapshot))
            } else {
                Logx.d(LogTags.HVAC, "空调状态变化", "state" to stateSummary(snapshot))
            }
        }
        observers.forEach { it(snapshot) }
    }

    /** 空调状态快照的紧凑表示（方便真机日志里直接比对前后两条）。 */
    private fun stateSummary(s: HvacState): String = buildString {
        append("power=").append(s.power)
        append(" ac=").append(s.ac)
        append(" auto=").append(s.auto)
        append(" fan=").append(s.fanLevel)
        append(" fanAuto=").append(s.fanAuto)
        append(" circ=").append(s.circulation)
        append(" temp=").append(s.tempDriver).append('/').append(s.tempPassenger)
        append(" sync=").append(s.tempSynced)
        append(" defF=").append(s.defrostFront)
        append(" defR=").append(s.defrostRear)
        append(" blow=").append(s.blowing)
        append(" seatH=").append(s.seatHeatDriver).append('/').append(s.seatHeatPassenger)
        append(" seatV=").append(s.seatVentDriver).append('/').append(s.seatVentPassenger)
    }

    /** 视角下发后的回读校验间隔（是 700ms）。 */
    private const val VIEW_VERIFY_MS = 800L
}
