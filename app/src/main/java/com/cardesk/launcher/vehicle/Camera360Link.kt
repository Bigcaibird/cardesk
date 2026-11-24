package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cardesk.launcher.core.Camera360Policy
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 360 环影的**车机信号联动执行端**。
 *
 * 职责边界只有三件事：
 *
 * 1. 订阅车机信号：雷达图形功能号 [HvacIds.FUNC_RADAR_GRAPHICS] 与挡位来源 [GearSource]；
 * 2. 把当前状态拼成 [Camera360Policy.Input]，交给纯函数 [Camera360Policy.decide] 决策；
 * 3. 照决策调用 [VehicleController.set360] / [VehicleController.schedule360View] 执行。
 *
 * ## 为什么不在这里写规则
 *
 * 「挂 R 开环影、切 D 换视角」这类规则依赖上一挡位，分支多、边界多，
 * 写成纯函数才能用 JVM 单测把全部组合跑一遍（见 `Camera360PolicyTest.kt`）。
 * 这里只做「订阅 / 去重 / 防抖 / 打日志」，规则一改只动 policy。
 *
 * ## 为什么等网关就绪
 *
 * `VehicleController.gateway` 是 lateinit，车机服务连上之前根本拿不到。
 * 启动时机由桌面决定（可能在任何时刻），所以这里用后台线程最多等
 * [WAIT_TIMEOUT_MS]（500ms 轮询，与 `DriveModeAutoStart` 同一套写法），
 * 之后再由周期任务继续补绑 —— 车机通道晚到不应让雷达联动永久失效。
 * 雷达监听注册失败只影响雷达这一条，**不影响 360 手动通路**（两者互不依赖）。
 *
 * ## 防抖与去重
 *
 * - **状态去重**：[Camera360Policy.Deduper] 保证同一 `(总开关, 挡位, 雷达)` 组合只决策一次；
 * - **动作防抖**：同一动作（同一视角 / 同一开关方向）在 [WRITE_DEBOUNCE_MS] 内不重复写车。
 *   车机的 360 写入是幂等的，但连续写会造成画面闪断，所以宁可少写。
 */
object Camera360Link {

    /** 等车控通道就绪的上限（与 `DriveModeAutoStart.WAIT_TIMEOUT_MS` 一致）。 */
    const val WAIT_TIMEOUT_MS = 30_000L

    /** 等就绪期间的轮询间隔（与 `DriveModeAutoStart.WAIT_POLL_MS` 一致）。 */
    const val WAIT_POLL_MS = 500L

    /** 同一动作的最小重复写入间隔（防画面闪断）。 */
    const val WRITE_DEBOUNCE_MS = 1_500L

    /** 网关补绑周期：车机服务晚到 / 重连时靠它把监听补上。 */
    private const val GATEWAY_RECHECK_MS = 3_000L

    /** 雷达开关的通用偏好键（`Prefs` 里没有专门的字段，用通用 API 读写）。 */
    private const val KEY_RADAR_OPEN = "camera360_radar_open"

    /** 线程名：日志里一眼能看出是谁在等网关。 */
    private const val WAIT_THREAD_NAME = "cam360-link-wait"

    private val main = Handler(Looper.getMainLooper())

    private var started = false
    private var appContext: Context? = null
    private var prefs: Prefs? = null
    private var boundGateway: VehicleGateway? = null

    /** 当前雷达图形状态（功能号 value != 0）。 */
    private var radarActive = false

    /** 最近一次确认的挡位（null = 还没读到）。 */
    private var lastGear: Char? = null

    /** 最近一次同步到的视角状态（仅日志/排障用）。 */
    private var lastView = -1

    private val deduper = Camera360Policy.Deduper()

    /** 上一次真正写车的动作签名与时间（动作防抖用）。 */
    private var lastWriteSignature: String? = null
    private var lastWriteAt = 0L

    /**
     * 车控网关的值变化监听：
     * - `FUNC_RADAR_GRAPHICS` → 雷达图形出现/消失；
     * - `FUNC_360_VIEW` → 视角状态同步（只记日志，不参与决策）。
     *
     * 回调可能在 binder 线程，统一转到主线程处理（状态变量都在主线程读写）。
     */
    private val gatewayListener: (Int, Int, Int) -> Unit = { func, zone, value ->
        when (func) {
            HvacIds.FUNC_RADAR_GRAPHICS -> main.post { onRadarChanged(value != 0) }
            HvacIds.FUNC_360_VIEW -> main.post { onViewChanged(value, zone) }
        }
    }

    /** [GearSource] 的挡位回调（GearSource 已在主线程回调，这里再 post 一次保证顺序）。 */
    private val gearListener: (Char?) -> Unit = { gear -> main.post { onGearChanged(gear) } }

    /** 周期补绑网关（车机服务晚到 / 重连）。 */
    private val gatewayRecheck = object : Runnable {
        override fun run() {
            if (!started) return
            ensureGatewayBound()
            main.postDelayed(this, GATEWAY_RECHECK_MS)
        }
    }

    /**
     * 启动联动订阅。**幂等**：已在运行时不重复注册（设置页反复开关只调一次即可）。
     *
     * @param context 任意 context（内部取 applicationContext）
     * @param prefs 偏好读取入口；每次决策都现读，设置改了下一拍就生效
     */
    fun start(context: Context, prefs: Prefs) {
        if (started) {
            Logx.d(LogTags.CAM360, "360 联动已在运行，忽略重复启动")
            return
        }
        started = true
        val app = context.applicationContext ?: context
        appContext = app
        this.prefs = prefs
        deduper.reset()
        lastWriteSignature = null
        lastWriteAt = 0L
        Logx.i(
            LogTags.CAM360, "360 联动已启动",
            "masterEnabled" to prefs.camera360Enabled,
            "reverseView" to prefs.camera360ReverseView,
            "rdView" to prefs.camera360RdView,
            "dView" to prefs.camera360DView,
            "npClose" to prefs.camera360NpClose,
            "radarOpen" to radarOpen(),
            "delayMs" to prefs.camera360DelayMs,
        )
        // 车控通道可能要等；后台线程等就绪（不阻塞启动它的线程），期间挡位来源先跑起来。
        Thread({ awaitGateway() }, WAIT_THREAD_NAME).apply { isDaemon = true }.start()
        main.postDelayed(gatewayRecheck, GATEWAY_RECHECK_MS)
        startGearSource(app)
    }

    /** 停止联动订阅（注销监听、停轮询）。幂等。 */
    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(gatewayRecheck)
        boundGateway?.let { gw -> runCatching { gw.unregisterListener(gatewayListener) } }
        boundGateway = null
        GearSource.stop()
        prefs = null
        appContext = null
        deduper.reset()
        lastGear = null
        radarActive = false
        lastView = -1
        lastWriteSignature = null
        Logx.i(LogTags.CAM360, "360 联动已停止")
    }

    /** 是否正在联动（设置页可据此显示状态 / 避免重复启动）。 */
    fun isRunning(): Boolean = started

    /**
     * 设置项变化后强制重新决策一次。
     *
     * 决策层是按 `(总开关, 挡位, 雷达)` 去重的，改了「倒车视角 / 延迟」这类配置
     * 不会自动触发决策；设置页保存后调一次这里，下一次挂挡就会用新配置。
     * 不改变挡位记忆，所以不会因为「刷新一下」就凭空切一次视角。
     */
    fun refresh() {
        if (!started) return
        deduper.reset()
        evaluate("设置变化", lastGear)
    }

    /**
     * 调试用：注入一次挡位事件（设置页调试入口 / 模拟器验证）。
     *
     * 参数只接受 `"P"` / `"R"` / `"N"` / `"D"`（忽略大小写与空白）。
     * 模拟车辆通道下会把模拟网关的挡位一起改掉，保证后续轮询看到同一个值；
     * 真实车机通道下**忽略**（挡位只能来自车机信号，见 [GearSource.simulate]）。
     */
    fun simulateGear(gear: String) {
        val ctx = appContext
        if (ctx == null) {
            Logx.w(LogTags.CAM360, "模拟挡位：360 联动尚未启动，忽略", "gear" to gear)
            return
        }
        val normalized = Camera360Policy.gearFromMockText(gear) ?: run {
            Logx.w(LogTags.CAM360, "模拟挡位参数不合法（只接受 P/R/N/D）", "gear" to gear)
            return
        }
        Logx.event(LogTags.CAM360, "模拟挡位事件注入", "gear" to normalized)
        GearSource.simulate(ctx, normalized)
    }

    // ------------------------------------------------------------ 车控通道

    /**
     * 后台线程：最多 [WAIT_TIMEOUT_MS] 等车控通道就绪（500ms 轮询），就绪后回主线程绑定。
     *
     * 超时也不放弃：周期补绑任务会继续尝试，只是不再占用这个线程。
     */
    private fun awaitGateway() {
        var waited = 0L
        while (started && waited < WAIT_TIMEOUT_MS) {
            val gw = currentGateway()
            if (gw != null && runCatching { gw.isServiceConnected() }.getOrDefault(false)) {
                main.post { bindGateway(gw) }
                return
            }
            try {
                Thread.sleep(WAIT_POLL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            waited += WAIT_POLL_MS
        }
        if (started) {
            Logx.w(LogTags.CAM360, "360 联动：等待车控通道超时，后续继续补绑", "waitedMs" to waited)
        }
    }

    private fun currentGateway(): VehicleGateway? {
        if (!VehicleController.initialized) return null
        return runCatching { VehicleController.gateway }.getOrNull()
    }

    private fun ensureGatewayBound() {
        if (!started) return
        val gw = currentGateway() ?: return
        if (gw === boundGateway) return
        if (!runCatching { gw.isServiceConnected() }.getOrDefault(false)) return
        bindGateway(gw)
    }

    private fun bindGateway(gw: VehicleGateway) {
        if (!started) return
        boundGateway?.let { old -> if (old !== gw) runCatching { old.unregisterListener(gatewayListener) } }
        val channel = runCatching { gw.id }.getOrDefault("?")
        val ok = runCatching { gw.registerListener(gatewayListener) }.isSuccess
        boundGateway = gw
        if (ok) {
            Logx.i(LogTags.CAM360, "360 联动：已订阅雷达图形与视角状态", "channel" to channel)
        } else {
            // 注册失败只影响雷达联动，360 手动通路完全不受影响（两条路互不依赖）。
            Logx.w(LogTags.CAM360, "360 联动：订阅雷达监听失败（不影响手动 360）", "channel" to channel)
        }
        // 网关刚就绪时，模拟车辆通道的 gear 才可读 —— 补读一次，别等轮询的下一拍。
        main.post { syncGearNow() }
    }

    private fun startGearSource(context: Context) {
        GearSource.onGearChanged = gearListener
        GearSource.start(context)
    }

    private fun syncGearNow() {
        if (!started) return
        val ctx = appContext ?: return
        val gear = runCatching { GearSource.current(ctx) }.getOrNull() ?: return
        onGearChanged(gear)
    }

    // ------------------------------------------------------------ 信号回调

    private fun onGearChanged(gear: Char?) {
        if (!started) return
        if (gear == null) {
            Logx.d(LogTags.CAM360, "挡位未知，跳过联动决策")
            return
        }
        val prev = lastGear
        if (prev == gear) return
        lastGear = gear
        evaluate("挡位变化", prev)
    }

    /**
     * 调试用：注入一次雷达图形事件（设置页调试入口 / 模拟器验证）。
     *
     * 真车雷达图形走 [HvacIds.FUNC_RADAR_GRAPHICS]；模拟车辆通道只是把写下去的值回显，
     * 没有「上游真的出现图形」这回事，所以在模拟环境里这条联动永远看不到。
     * 这里直接喂给同一条边沿处理 [onRadarChanged] —— 判定路径与真信号完全一致，
     * 不是另开分支。
     */
    fun simulateRadar(active: Boolean) {
        if (!started) {
            Logx.w(LogTags.CAM360, "模拟雷达：360 联动尚未启动，忽略", "active" to active)
            return
        }
        Logx.event(LogTags.CAM360, "模拟雷达事件注入", "active" to active)
        onRadarChanged(active)
    }

    private fun onRadarChanged(active: Boolean) {
        if (!started) return
        if (radarActive == active) return
        radarActive = active
        Logx.i(LogTags.CAM360, "雷达图形状态变化", "active" to active)
        // 雷达只能是一条边沿：挡位没变，所以上一挡位就传当前挡位（不会触发挡位动作）。
        evaluate(if (active) "雷达图形出现" else "雷达图形消失", lastGear)
    }

    private fun onViewChanged(value: Int, zone: Int) {
        if (!started) return
        lastView = value
        Logx.d(LogTags.CAM360, "360 视角状态同步", "view" to value, "zone" to zone)
    }

    // ------------------------------------------------------------ 决策 / 执行

    private fun radarOpen(): Boolean = prefs?.flag(KEY_RADAR_OPEN, true) ?: true

    /**
     * 组一次输入 → 去重 → 决策 → 执行。所有状态变量的读都在主线程。
     *
     * @param trigger 触发原因（日志用）
     * @param prevGear 本次变化前的挡位；挡位事件传上一次的值，雷达事件传当前挡位
     */
    private fun evaluate(trigger: String, prevGear: Char?) {
        val p = prefs ?: return
        val gear = lastGear
        val input = Camera360Policy.Input(
            masterEnabled = p.camera360Enabled,
            gear = gear,
            prevGear = prevGear,
            radarActive = radarActive,
            reverseView = p.camera360ReverseView,
            rdView = p.camera360RdView,
            dView = p.camera360DView,
            npClose = p.camera360NpClose,
            radarOpen = radarOpen(),
            delayMs = p.camera360DelayMs,
            alreadyOpen = runCatching { VehicleController.is360On }.getOrDefault(false),
        )
        if (!deduper.accept(input)) {
            Logx.d(
                LogTags.CAM360, "联动决策：状态未变化，跳过",
                "key" to Camera360Policy.stateKey(input),
            )
            return
        }
        val actions = Camera360Policy.decide(input)
        Logx.i(
            LogTags.CAM360, "联动决策",
            "trigger" to trigger,
            "gear" to (gear?.toString() ?: "未知"),
            "prevGear" to (prevGear?.toString() ?: "未知"),
            "radar" to radarActive,
            "master" to input.masterEnabled,
            "alreadyOpen" to input.alreadyOpen,
            "actions" to if (actions.isEmpty()) "无" else actions.joinToString("；") { it.label },
        )
        if (actions.isNotEmpty()) execute(actions)
    }

    private fun execute(actions: List<Camera360Policy.Action>) {
        for (action in actions) {
            val signature = action.signature
            val now = SystemClock.elapsedRealtime()
            if (signature == lastWriteSignature && now - lastWriteAt < WRITE_DEBOUNCE_MS) {
                Logx.d(
                    LogTags.CAM360, "联动执行：动作防抖跳过",
                    "action" to action.label, "windowMs" to WRITE_DEBOUNCE_MS,
                )
                continue
            }
            lastWriteSignature = signature
            lastWriteAt = now
            when (action) {
                Camera360Policy.Action.None -> Unit

                is Camera360Policy.Action.OpenAndView -> {
                    val ok = runCatching { VehicleController.set360(true) }.getOrDefault(false)
                    Logx.i(
                        LogTags.CAM360, "联动执行：开环影",
                        "reason" to action.reason, "view" to action.view, "ok" to ok,
                    )
                    scheduleView(action.view, action.delayMs)
                }

                Camera360Policy.Action.OpenOnly -> {
                    val ok = runCatching { VehicleController.set360(true) }.getOrDefault(false)
                    Logx.i(LogTags.CAM360, "联动执行：开环影（雷达图形）", "ok" to ok)
                }

                Camera360Policy.Action.Close -> {
                    val ok = runCatching { VehicleController.set360(false) }.getOrDefault(false)
                    Logx.i(LogTags.CAM360, "联动执行：关环影", "ok" to ok)
                }

                is Camera360Policy.Action.View -> scheduleView(action.view, action.delayMs)
            }
        }
    }

    private fun scheduleView(view: Int, delayMs: Int) {
        if (view < 0) return
        runCatching { VehicleController.schedule360View(view, delayMs) }
            .onFailure { Logx.w(LogTags.CAM360, "联动执行：切视角失败", it, "view" to view, "delayMs" to delayMs) }
        Logx.i(
            LogTags.CAM360, "联动执行：切视角",
            "view" to view, "delayMs" to delayMs, "lastView" to lastView,
        )
    }
}
