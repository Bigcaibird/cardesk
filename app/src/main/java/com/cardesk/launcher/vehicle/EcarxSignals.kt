package com.cardesk.launcher.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Proxy

/**
 * 第三级真实信号通道：反射 ECARX 私有 `ecarx.car.ECarXCar`。
 *
 * 里车门/续航/胎压的降级链是
 *   AdaptAPI 属性（SIG_DST_TO_EMPTY / SIG_SCR_DST_TO_EMPTY 等信号 id）
 *   PHEV DTE → 4 个 SENSOR_ENDURANCE → `car.RANGE_REMAINING` 广播。
 * 这里把第二级（ECarXCar 的 SignalManager）单独抽出来：即使 AdaptAPI 的空调通道可用，
 * 续航/车门这类信号也常常只能从 SignalManager 拿到。
 *
 * ## 第三十七轮修正（真机 4383 诊断包）
 *
 * 之前这条通道**从来没连上过**，日志里也没有一条成功记录。根因是取 manager 的方法名错了：
 *
 * - 我们写的是；
 * -写的是
 *   **`ecarxCar.getCarManager("car_signal")`**，
 *   PHEV 那条才是 `getCarManager("car_publicattribute").getECarXCarPhevManager()`。
 *
 * 还会在 之后**延迟 1.5 秒**再去取 manager
 * （的 `postDelayed(..., 1500L)`），
 * 因为服务那会儿还没起来 —— 我们现在也照做。
 *
 * ## 车门读法
 *
 * 读车门用的是 **car_signal 管理器上的无参 getter**
 *
 * `java
 * {"getDoorDrvrSts", "getDoorPassSts", "getDoorDrvrReSts", "getDoorPassReSts",
 *  "getHoodSts", "getTrSts"}
 * `
 *
 * 取值口径是 `interpretDoorSignal`：`0` = 关、`1` = 开、其它 = 读不出来。
 * 我们之前用的是 `getSignalValue(int)`，这台车上的 manager 没有这个方法 → 永远读不到。
 *
 * 所有调用都可能失败（不同固件的类名/方法名不一致），失败返回 null，由调用方继续降级。
 */
object EcarxSignals {

    private val main = Handler(Looper.getMainLooper())

    private var car: Any? = null
    private var signalManager: Any? = null
    private var phevManager: Any? = null
    private var connected = false
    private var lastError: String? = null

    /**
     * `car_signal` 信号变化回调（车门 / 引擎盖 / 后备箱 / 续航那几个 id）。
     *
     * 是事件驱动的：信号一变就。
     * 桌面每秒轮询一次，车门开了最多要等 1 秒 —— 这里把变化接出来，
     * 让桌面立刻刷新。
     */
    @Volatile
    var onSignalChanged: (() -> Unit)? = null

    /** 常量（内联的 id）。 */
    const val SIG_DOOR_DRVR = 29207
    const val SIG_DOOR_PASS = 29211
    const val SIG_DOOR_DRVR_RE = 29206
    const val SIG_DOOR_PASS_RE = 29210
    const val SIG_HOOD = 29222
    const val SIG_TRUNK = 29268
    const val SIG_DST_TO_EMPTY = 29524
    const val SIG_SCR_DST_TO_EMPTY = 29720

    /**
     * ——**6 项**：4 门 + 引擎盖 + 后备箱。
     *
     * 每一项列了**两种拼写**：里写的是 `getDoorDrvrSts`
     * 但真机（以及用户给的图）上是 `getDoorDrvSts`（少一个 r）——
     * 两种都试，先试拼写。
     */
    private val DOOR_GETTERS = arrayOf(
        arrayOf("getDoorDrvrSts", "getDoorDrvSts"),      // 主驾门 = 左前
        arrayOf("getDoorPassSts"),                      // 副驾门 = 右前
        arrayOf("getDoorDrvrReSts", "getDoorDrvReSts"), // 主驾后门 = 左后
        arrayOf("getDoorPassReSts"),                    // 副驾后门 = 右后
        arrayOf("getHoodSts"),                          // 引擎盖
        arrayOf("getTrSts"),                            // 后备箱
    )

    /** connect 之后延迟这么久再去取 manager。 */
    private const val MANAGER_DELAY_MS = 1500L

    /**
     * 「这一条通道不存在」的日志限频（毫秒）。
     *
     * 桌面每秒调一次 [connect]，不限频的话日志会被同一条刷爆
     * （真机诊断包里 `ECarXCar 信号通道不可用（类不存在）` 刷了几百行）。
     */
    private const val UNAVAILABLE_LOG_INTERVAL_MS = 60_000L

    /**
     * 直取 `CarSignalManager` 的重试间隔（毫秒）。
     *
     * 桌面每秒调一次 [connect]；拿不到时不用每秒都去反射一遍。
     */
    private const val DIRECT_RETRY_MS = 30_000L

    /** `ECarXCar` 的候选类名（不同固件的包名不一样）。 */
    private val ECARX_CAR_CLASSES = arrayOf(
        "ecarx.car.ECarXCar",
        "ecarx.car.hardware.ECarXCar",
        "com.ecarx.car.ECarXCar",
        "ecarx.vehicle.ECarXCar",
    )

    /**
     * `CarSignalManager` 的候选类名。
     *
     * 文档口径（`vehicle-body-signals-read.md` §2 / §3.1）：
     * 四门 + 机盖 + 后备箱的开合 getter 都在**这个类**上
     * （`getDoorDrvrSts` / `getHoodSts` / `getTrSts` …），
     * 事件回调是它嵌套类 `CarSignalEventCallback`。
     */
    private val SIGNAL_MANAGER_CLASSES = arrayOf(
        "ecarx.car.hardware.signal.CarSignalManager",
        "com.ecarx.car.hardware.signal.CarSignalManager",
    )

    /** 类可用性的缓存（`null` = 还没探测过）。 */
    private var cachedAvailable: Boolean? = null

    /** 找到的 `ECarXCar` 类（`null` = 这台车上没有）。 */
    private var ecarxCarClass: Class<*>? = null

    /** 找到的 `CarSignalManager` 类（`null` = 这台车上没有）。 */
    private var signalManagerClass: Class<*>? = null

    /** 上次打「通道不可用」日志的时刻。 */
    private var lastUnavailableLogAt = 0L

    /** 上次尝试直取 `CarSignalManager` 的时刻。 */
    private var lastDirectAttemptAt = 0L

    /**
     * `ecarx.car.ECarXCar` / `CarSignalManager` 这两个类在不在。
     *
     * 结果**缓存**起来： 会被桌面每秒调一次
     * （`currentVehicleInfo` → `EcarxSignals.connect`），
     * 非 ECARX 车机上每次都抛一次 太浪费。
     *
     * 注意口径：**两个类任意一个在就算这条通道有戏** ——
     * 就算 `ECarXCar` 不在（真机实测就是不在），
     * 也可能是 `CarSignalManager` 能直接拿到。
     */
    fun isAvailable(): Boolean {
        cachedAvailable?.let { return it }
        val car = findClass(ECARX_CAR_CLASSES)
        val mgr = findClass(SIGNAL_MANAGER_CLASSES)
        ecarxCarClass = car
        signalManagerClass = mgr
        val ok = car != null || mgr != null
        cachedAvailable = ok
        if (!ok) {
            // 排障场景：非 ECARX 车机上必然探测不到，说明「续航/车门为什么读不到」。
            Logx.d(
                LogTags.SIGNAL,
                "ECarXCar 信号通道探测失败",
                "reason" to "ECarXCar 与 CarSignalManager 两个类都不存在",
            )
        } else {
            Logx.i(
                LogTags.SIGNAL,
                "ECarXCar 信号通道类可用",
                "ecarxCar" to (car?.name ?: "无"),
                "signalManager" to (mgr?.name ?: "无"),
            )
        }
        return ok
    }

    /** 在候选名单里找第一个存在的类。 */
    private fun findClass(names: Array<String>): Class<*>? {
        for (name in names) {
            runCatching { Class.forName(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** 「这一条通道不存在」的日志（限频，别刷屏）。 */
    private fun logUnavailable(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnavailableLogAt < UNAVAILABLE_LOG_INTERVAL_MS) return
        lastUnavailableLogAt = now
        Logx.d(LogTags.SIGNAL, "ECarXCar 信号通道不可用", "reason" to reason)
    }

    /** 幂等连接；返回是否可用。 */
    fun connect(context: Context): Boolean {
        if (connected && signalManager != null) return true
        if (!isAvailable()) {
            logUnavailable("类不存在")
            return false
        }
        // 已经建过连接、只是 manager 还没就绪 → 只再试一次取 manager，
        // **不要重复 createCar**（服务只允许一个连接，重复建会抛异常）。
        if (car != null) {
            val cls = ecarxCarClass ?: return tryDirectSignalManager(context)
            resolveManagers(cls)
            return connected
        }
        // 排障场景：信号通道是独立于空调通道的一条链路，连不上要说清楚。
        Logx.d(LogTags.SIGNAL, "连接 ECarXCar 信号通道")
        val cls = ecarxCarClass
        val ok = if (cls == null) {
            // 这台车上没有 ECarXCar → 试试直接拿 CarSignalManager（第六十六轮新增）
            logUnavailable("ECarXCar 类不存在")
            tryDirectSignalManager(context)
        } else {
            runCatching {
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        // 服务连上后**立刻**取一次 manager（不等延迟）。
                        Logx.i(LogTags.SIGNAL, "ECarXCar 服务已连接", "pkg" to (name?.packageName ?: ""))
                        resolveManagers(cls)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        Logx.w(LogTags.SIGNAL, "ECarXCar 服务已断开", "pkg" to (name?.packageName ?: ""))
                        signalManager = null
                        phevManager = null
                        connected = false
                    }
                }
                car = create(cls, context, connection)
                val instance = car ?: return false
                instance.javaClass.methods.firstOrNull { it.name == "connect" && it.parameterTypes.isEmpty() }
                    ?.invoke(instance)
                // connect 之后延迟 1.5 秒再取 manager（服务那会儿还没完全就绪）。
                main.postDelayed({ resolveManagers(cls) }, MANAGER_DELAY_MS)
                // 立刻先试一次，manager 已经是现成的就不用等。
                resolveManagers(cls)
                connected = signalManager != null
                if (connected) {
                    Logx.i(LogTags.SIGNAL, "ECarXCar 信号通道连接成功", "phevManager" to (phevManager != null))
                } else {
                    Logx.w(LogTags.SIGNAL, "ECarXCar 信号通道连接失败", "reason" to "car_signal manager 未就绪")
                }
                connected
            }.getOrElse {
                lastError = it.toString()
                Logx.w(LogTags.SIGNAL, "ECarXCar 信号通道连接异常", it, "reason" to lastError)
                false
            }
        }
        // ECarXCar 这条路走不通时，再试直接拿 CarSignalManager。
        if (!ok) return tryDirectSignalManager(context)
        return ok
    }

    /**
     * **直接**拿 `CarSignalManager`（不走 `ECarXCar`）。
     *
     * ## 为什么需要这一条（第六十六轮）
     *
     * 真机（领克 dcy11 / Android 9）诊断包里，
     * `ECarXCar 信号通道不可用（类不存在）` 刷了几百行 —— 这台车机上
     * **没有** `ecarx.car.ECarXCar`，所以「源1」整档失效。
     *
     * 但文档口径里，四个门的 getter 挂在 **`ecarx.car.hardware.signal.CarSignalManager`**
     * 这个类上（事件回调还是它自己的嵌套类）。
     * 于是这里直接试这个类：
     * - 静态工厂方法（`getInstance` / `create` / `getCarSignalManager` …）；
     * - 公开构造器（无参 / 带 Context）。
     *
     * 拿不到就安静返回 false（这一档就当没有，不影响别的档）。
     */
    private fun tryDirectSignalManager(context: Context): Boolean {
        val cls = signalManagerClass ?: return false
        if (signalManager != null) {
            connected = true
            return true
        }
        // 桌面每秒调一次 connect，拿不到就别每秒都去反射一遍
        val now = System.currentTimeMillis()
        if (now - lastDirectAttemptAt < DIRECT_RETRY_MS) return false
        lastDirectAttemptAt = now
        val instance = createSignalManager(cls, context) ?: run {
            logUnavailable("CarSignalManager 存在但拿不到实例")
            return false
        }
        signalManager = instance
        registerSignalCallback(instance)
        connected = true
        Logx.i(
            LogTags.SIGNAL,
            "car_signal 管理器直取就绪",
            "class" to instance.javaClass.name,
        )
        return true
    }

    /**
     * 用反射造一个 `CarSignalManager` 实例。
     *
     * 依次试：静态工厂（无参 → 带 Context）→ 公开构造器（无参 → 带 Context）。
     */
    private fun createSignalManager(cls: Class<*>, context: Context): Any? {
        val ctx: Class<*> = Context::class.java
        for (name in SIGNAL_MANAGER_FACTORIES) {
            val m = runCatching { cls.getMethod(name) }.getOrNull()
            if (m != null) {
                runCatching { m.invoke(null) }.getOrNull()?.let { return it }
            }
            val m1 = runCatching { cls.getMethod(name, ctx) }.getOrNull()
            if (m1 != null) {
                runCatching { m1.invoke(null, context) }.getOrNull()?.let { return it }
            }
        }
        for (params in listOf(arrayOf<Any>(), arrayOf<Any>(context))) {
            val types = params.map { if (it is Context) ctx else Any::class.java }.toTypedArray()
            val ctor = runCatching { cls.getConstructor(*types) }.getOrNull() ?: continue
            runCatching { ctor.newInstance(*params) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** `CarSignalManager` 的候选静态工厂方法名。 */
    private val SIGNAL_MANAGER_FACTORIES = arrayOf(
        "getInstance", "create", "getCarSignalManager", "createSignalManager",
        "get", "newInstance",
    )

    /**
     * 取两个 manager。
     *
     * - `car_signal` → 车门 / 引擎盖 / 后备箱 / 续航信号；
     * - `car_publicattribute` → PHEV DTE。
     */
    private fun resolveManagers(cls: Class<*>) {
        val instance = car ?: return
        if (signalManager == null) {
            val sm = runCatching {
                cls.getMethod("getCarManager", String::class.java).invoke(instance, "car_signal")
            }.getOrNull()
            if (sm != null) {
                signalManager = sm
                registerSignalCallback(sm)
                Logx.i(LogTags.SIGNAL, "car_signal 管理器就绪", "class" to sm.javaClass.name)
            }
        }
        if (phevManager == null) {
            val pm = runCatching {
                val carManager = cls.getMethod("getCarManager", String::class.java)
                    .invoke(instance, "car_publicattribute")
                carManager?.javaClass
                    ?.getMethod("getECarXCarPhevManager")
                    ?.invoke(carManager)
            }.getOrNull()
            if (pm != null) {
                phevManager = pm
                Logx.i(LogTags.SIGNAL, "phev 管理器就绪")
            }
        }
        connected = signalManager != null
    }

    /**
     * 注册信号变化回调。
     *
     * `SignalFilter` 里 add 上关心的信号 id，再用 `CarSignalEventCallback` 代理接住回调。
     * 注册失败不影响取值（getter 是另一条路），所以只记 DEBUG。
     */
    private fun registerSignalCallback(sm: Any) {
        runCatching {
            val filterCls = Class.forName("ecarx.car.hardware.signal.SignalFilter")
            val filter = filterCls.getConstructor().newInstance()
            val add = filterCls.getMethod("add", Integer.TYPE)
            for (id in intArrayOf(
                SIG_DOOR_DRVR, SIG_DOOR_PASS, SIG_DOOR_DRVR_RE, SIG_DOOR_PASS_RE,
                SIG_HOOD, SIG_TRUNK, SIG_DST_TO_EMPTY, SIG_SCR_DST_TO_EMPTY,
            )) {
                add.invoke(filter, id)
            }
            val cbCls = Class.forName("ecarx.car.hardware.signal.CarSignalManager\$CarSignalEventCallback")
            val proxy = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, method, _ ->
                if (method.name == "onChangeEvent") {
                    Logx.d(LogTags.SIGNAL, "car_signal 变化回调")
                    // 收到变化就 publishBodyState()
                    // 我们桌面据此立刻刷新车况卡，不等下一秒的轮询。
                    onSignalChanged?.invoke()
                }
                null
            }
            val register = sm.javaClass.methods.firstOrNull {
                it.name == "registerCallback" && it.parameterCount == 2
            }
            if (register == null) {
                Logx.d(LogTags.SIGNAL, "car_signal 没有 registerCallback")
                return
            }
            register.invoke(sm, proxy, filter)
            Logx.i(LogTags.SIGNAL, "car_signal 变化回调已注册")
        }.onFailure {
            Logx.d(LogTags.SIGNAL, "注册 car_signal 回调失败", "reason" to "${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun disconnect() {
        connected = false
        signalManager = null
        phevManager = null
        car = null
    }

    /** 剩余续航（km）：SCR_DST_TO_EMPTY → DST_TO_EMPTY → PHEV DTE。 */
    fun rangeKm(): Int? {
        val sm = signalManager
        if (sm != null) {
            for (id in intArrayOf(SIG_SCR_DST_TO_EMPTY, SIG_DST_TO_EMPTY)) {
                readInt(sm, id)?.let {
                    if (it in 0..2000) {
                        // 排障场景：续航读不到时，要能看出「是哪个信号 id 没值」。
                        Logx.d(LogTags.SIGNAL, "读取续航信号", "signalId" to id, "value" to it)
                        return it
                    }
                }
            }
        }
        phevManager?.let { pm ->
            for (name in listOf("getDte", "getPhevDte", "getRemainDistance", "getEndurance")) {
                val v = runCatching { pm.javaClass.getMethod(name).invoke(pm) }.getOrNull()
                val asInt = (v as? Int) ?: (v as? Float)?.toInt() ?: (v as? Double)?.toInt()
                if (asInt != null && asInt in 0..2000) {
                    Logx.d(LogTags.SIGNAL, "读取续航（PHEV）", "method" to name, "value" to asInt)
                    return asInt
                }
            }
        }
        Logx.d(LogTags.SIGNAL, "续航读取失败", "signalReady" to (sm != null), "phevReady" to (phevManager != null))
        return null
    }

    /**
     * 车身 **6 项**（4 门 + 引擎盖 + 后备箱）的开闭：**true = 关**；读不到的那项是 null。
     *
     * 走**无参 getter**（[DOOR_GETTERS]），取值口径是
     * `interpretDoorSignal`：`0` = 关、`1` = 开、其它 = 读不出来
     * （见 [BodyState.interpretDoorSignal]，纯函数、可单测）。
     *
     * 与差别：只聚合成「全关 / 不全关」
     * 我们要显示**每一扇**门，所以逐项取、**逐项容错**（读不到的那扇按 null 处理）。
     */
    fun bodyItems(): List<Boolean?> {
        val sm = signalManager ?: return emptyList()
        return DOOR_GETTERS.map { names -> readDoorGetter(sm, names) }
    }

    /**
     * 车门四角开闭：true=关。
     *
     * 取 [bodyItems] 的前 4 项，**逐扇独立**（第四十五轮起）：
     * 读不到的那一扇是 null，其余照常 —— 不再因为少一扇就整组作废。
     */
    fun doorsClosed(): List<Boolean?>? {
        val items = bodyItems()
        if (items.size < 4) return null
        val four = items.subList(0, 4)
        if (four.all { it == null }) {
            Logx.d(LogTags.SIGNAL, "车门状态读不到", "items" to four.joinToString(","))
            return null
        }
        Logx.d(
            LogTags.SIGNAL,
            "读取车门状态",
            "driver" to four[0],
            "passenger" to four[1],
            "rearLeft" to four[2],
            "rearRight" to four[3],
        )
        return four
    }

    /** 引擎盖 / 后备箱（true = 关）；读不到返回 null。 */
    fun hoodClosed(): Boolean? = bodyItems().getOrNull(4)

    fun trunkClosed(): Boolean? = bodyItems().getOrNull(5)

    /**
     * 读一项门位 getter（[DOOR_GETTERS] 的顺序），**兼容两种拼写**。
     *
     * 口径
     * `0` → 关（true）、`1` → 开（false）、其它 → 读不出来（null）。
     */
    private fun readDoorGetter(sm: Any, names: Array<String>): Boolean? {
        for (name in names) {
            val v = invokeNoArgInt(sm, name) ?: continue
            val closed = BodyState.interpretDoorSignal(v)
            if (closed != null) return closed
            // 值解释不出来（既不是 0 也不是 1）→ 换另一种拼写再试
            Logx.d(
                LogTags.SIGNAL,
                "车门 getter 值无法解释",
                "name" to name,
                "raw" to v,
            )
        }
        return null
    }

    fun lastError(): String? = lastError

    /**
     * 取 `VfmiscManager`
     * `car.getCarManager("car_publicattribute").getECarXCarVfmiscManager()`。
     * 车窗开闭走这个通道（见 [OemWindowController]）。
     */
    fun vfmiscManager(): Any? {
        val instance = car ?: return null
        return runCatching {
            val carManager = instance.javaClass
                .getMethod("getCarManager", String::class.java)
                .invoke(instance, "car_publicattribute")
            carManager?.javaClass
                ?.getMethod("getECarXCarVfmiscManager")
                ?.invoke(carManager)
        }.onFailure {
            // 排障场景：车窗通道拿不到时的原因（类名/方法名不匹配）要留痕。
            Logx.d(LogTags.SIGNAL, "取 VfmiscManager 失败", "reason" to "${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    // ------------------------------------------------------------ 反射细节

    private fun create(cls: Class<*>, context: Context, connection: ServiceConnection): Any? {
        val ctx: Class<*> = Context::class.java
        val sc: Class<*> = ServiceConnection::class.java
        val handler: Class<*> = Handler::class.java
        val attempts: List<() -> Any?> = listOf(
            { cls.getMethod("createCar", ctx, sc).invoke(null, context, connection) },
            { cls.getMethod("createCar", ctx, sc, handler).invoke(null, context, connection, main) },
            { cls.getMethod("createCar", ctx, handler).invoke(null, context, main) },
            { cls.getMethod("createCar", ctx).invoke(null, context) },
        )
        for (attempt in attempts) {
            runCatching { attempt() }.getOrNull()?.let { return it }
        }
        return null
    }

    /** 调一个**无参**方法并把返回值转成 Int（找不到 / 失败都返回 null）。 */
    private fun invokeNoArgInt(target: Any, name: String): Int? = runCatching {
        val m = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        } ?: return null
        when (val v = m.invoke(target)) {
            is Int -> v
            is Float -> v.toInt()
            is Double -> v.toInt()
            is Boolean -> if (v) 1 else 0
            else -> null
        }
    }.getOrNull()

    private fun readInt(manager: Any, signalId: Int): Int? {
        val names = arrayOf(
            "getSignalValue", "getSignalIntValue", "getIntSignalValue",
            "getValue", "getSignal", "getSignalValueByKey",
        )
        for (name in names) {
            val method = runCatching {
                manager.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Integer.TYPE
                }
            }.getOrNull() ?: continue
            val v = runCatching { method.invoke(manager, signalId) }.getOrNull() ?: continue
            return when (v) {
                is Int -> v
                is Float -> v.toInt()
                is Double -> v.toInt()
                is Boolean -> if (v) 1 else 0
                else -> null
            }
        }
        return null
    }
}
