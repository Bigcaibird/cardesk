package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车机 VIN（车辆识别代号）读取器。
 *
 * ## 为什么要有它
 *
 * 设置页左栏和「开源与声明」页的 VIN 此前都是写死的「（空）」——
 * 不是从车机读出来为空，而是**根本没人去读**。原车 Launcher 的
 * `Device.getVin()` 读的是 `PA_VIN_VinNum`（见
 * `docs/ecarx-adapterapi/17-device-nav-audio-diag.md` §5.1），
 * 我们已经有同样的两条设施，接上即可：
 *
 * 1. **VHAL 属性**：`PA_VIN_VinNum = 560992300`。属性类型是 byte[]，
 *    内容是一条 `GeelyProto.PublicAttrValue`，VIN 字符串在 **field 6**
 *    （wire type 2），所以用 [PublicAttrValue.parse] 取 [PublicAttrValue.stringData]。
 *    同语义的 `DID_VIN_VinNum = 554709014` 在部分固件上是 String，
 *    也有的直接把裸 UTF-8 字节塞进 byte[] —— 两种都试。
 * 2. **厂商信号**：ECARX 的 `CarSignalManager.getPaVinVinNum()` /
 *    `getDidVinVinNum()` 分别返回 `PaVinVinNum.data` / `DidVinVinNum.value`。
 *    这是 `EcarxSignals` 已经在用的同一个 Binder 通道，只是它的字段是私有，
 *    这里按同样的反射口径单独取一次 manager（拿不到就安静降级）。
 *
 * ## 为什么读到也要校验
 *
 * VIN 只可能是 **17 位**、且字母表里**没有 I/O/Q**（避免和 1/0 混淆）。
 * 车机在未配置 / 服务刚起 / 故障时会返回空串、`"NO_VIN"` 或半截数据；
 * 直接显示出去只会让用户以为读错了车。所以一律按「读不到」处理，
 * 并留一条 WARN 证据（原始串）方便看诊断包。
 *
 * ## 缓存口径
 *
 * VIN 不会变：成功就永久缓存。失败 60 秒后才允许重试 —— 桌面刚启动时
 * 车机服务可能还没就绪，永久放弃会一直显示「读不到」。
 *
 * [vin] 是同步接口（调用方别在主线程直接调）；UI 用 [refresh]，它在后台线程读，
 * 结果通过回调交回，由 UI 自己 post 到主线程。
 */
object VinReader {

    /** `GeelyVehicleProperty.PA_VIN_VinNum`（0x2170102C）。 */
    const val PROP_PA_VIN = 560992300

    /** `GeelyVehicleProperty.DID_VIN_VinNum`（0x21103016）。 */
    const val PROP_DID_VIN = 554709014

    /** 失败后的重试窗口：服务刚起来时读到 null 很正常，别永久放弃。 */
    private const val FAILURE_RETRY_MS = 60_000L

    /** 回退通道（CarSignalManager）拿不到实例时的重试间隔。 */
    private const val SIGNAL_RETRY_MS = 30_000L

    /**
     * VIN 的合法形状：17 位，去掉 I/O/Q。
     *
     * 先把不可见字符清掉再匹配；大写化之后才比对（车机偶尔给小写）。
     */
    private val VIN_PATTERN = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    private val lock = Any()

    @Volatile
    private var cachedVin: String? = null

    @Volatile
    private var lastFailureAt = 0L

    @Volatile
    private var reading = false

    /** 正在等结果的回调（同一时刻只允许一个读取线程）。 */
    private val waiting = ArrayList<(String?) -> Unit>()

    @Volatile
    private var signalManager: Any? = null

    @Volatile
    private var signalManagerTriedAt = 0L

    /** `CarSignalManager` 的候选类名（不同固件包名不同，与 [EcarxSignals] 同口径）。 */
    private val SIGNAL_MANAGER_CLASSES = arrayOf(
        "ecarx.car.hardware.signal.CarSignalManager",
        "com.ecarx.car.hardware.signal.CarSignalManager",
    )

    /** `CarSignalManager` 的候选静态工厂方法名（真机上不保证有，后面还会试构造器）。 */
    private val SIGNAL_MANAGER_FACTORIES = arrayOf(
        "getInstance", "create", "getCarSignalManager", "createSignalManager", "get", "newInstance",
    )

    /** 已读到的 VIN；`null` = 还没读到（不代表永远读不到）。 */
    fun cached(): String? = cachedVin

    /**
     * 同步读 VIN（带缓存）。
     *
     * 会阻塞在 Binder / 反射上，**不要在主线程直接调**；UI 请用 [refresh]。
     */
    fun vin(context: Context?): String? {
        cachedVin?.let { return it }
        synchronized(lock) {
            cachedVin?.let { return it }
            if (System.currentTimeMillis() - lastFailureAt < FAILURE_RETRY_MS) return null
            val found = runCatching { read(context) }.getOrNull()
            if (found != null) {
                cachedVin = found
            } else {
                lastFailureAt = System.currentTimeMillis()
            }
            return found
        }
    }

    /**
     * 后台读一次，[callback] 在**后台线程**回调（`null` = 读不到）。
     *
     * 已有缓存 / 正在读 / 失败窗口内会直接复用结果，不会重复敲车机；
     * 设置页左栏和 VIN 行可能同时触发，这里用单飞（single-flight）合并。
     */
    fun refresh(context: Context, callback: ((String?) -> Unit)? = null) {
        val app = context.applicationContext ?: context
        cachedVin?.let {
            callback?.invoke(it)
            return
        }
        synchronized(lock) {
            cachedVin?.let {
                callback?.invoke(it)
                return
            }
            if (reading) {
                if (callback != null) waiting.add(callback)
                return
            }
            if (System.currentTimeMillis() - lastFailureAt < FAILURE_RETRY_MS) {
                callback?.invoke(null)
                return
            }
            reading = true
            if (callback != null) waiting.add(callback)
        }
        Thread({
            val result = runCatching { read(app) }.getOrNull()
            val callbacks: List<(String?) -> Unit>
            synchronized(lock) {
                if (result != null) {
                    cachedVin = result
                } else {
                    lastFailureAt = System.currentTimeMillis()
                }
                reading = false
                callbacks = waiting.toList()
                waiting.clear()
            }
            // 逐个回调；某个回调抛异常不能影响后续（比如页面已被关掉）。
            for (cb in callbacks) {
                runCatching { cb(result) }
            }
        }, "vin-reader").start()
    }

    /** 启动预热（可选）：不等结果、不阻塞主线程。 */
    fun preload(context: Context) = refresh(context)

    // ------------------------------------------------------------ 读法

    private fun read(context: Context?): String? {
        readPaVin()?.let { return it }
        readDidVin()?.let { return it }
        readSignalManager(context)?.let { return it }
        Logx.d(
            LogTags.VEHICLE,
            "VIN 未读到",
            "pa" to "0x%08X".format(PROP_PA_VIN),
            "did" to "0x%08X".format(PROP_DID_VIN),
        )
        return null
    }

    /**
     * ① `PA_VIN_VinNum`：优先按 PA 消息解（byte[] → PublicAttrValue → field 6 string），
     * 再试「属性本身就是 String」的固件形态。
     */
    private fun readPaVin(): String? {
        val read = CarProperties.readBytesWithStatus(PROP_PA_VIN, 0)
        if (!read.usable) {
            Logx.d(LogTags.VEHICLE, "VIN 属性不可用", "source" to "PA", "reason" to read.describe())
        } else {
            read.bytes?.let { bytes ->
                val payload = PublicAttrValue.parse(bytes)
                if (payload == null) {
                    Logx.d(
                        LogTags.VEHICLE,
                        "VIN PA 消息解析失败",
                        "source" to "VHAL-PA",
                        "bytes" to bytes.size,
                    )
                } else {
                    payload.stringData?.let { raw ->
                        accept(raw, "VHAL-PA")?.let { return it }
                    }
                    if (payload.stringData == null) {
                        Logx.d(
                            LogTags.VEHICLE,
                            "VIN PA 消息无 string 字段",
                            "source" to "VHAL-PA",
                            "availability" to payload.availability,
                            "format" to payload.format,
                        )
                    }
                }
            }
        }
        // 有的固件把该属性按 String 类型暴露（getProperty(Class,int,int) 直接给值）。
        (CarProperties.readAnyViaClass(PROP_PA_VIN, 0) as? String)?.let { raw ->
            accept(raw, "VHAL-PA-string")?.let { return it }
        }
        return null
    }

    /**
     * ② `DID_VIN_VinNum`：可能被包成 PA 消息、可能是裸 UTF-8 字节、也可能是 String。
     */
    private fun readDidVin(): String? {
        val read = CarProperties.readBytesWithStatus(PROP_DID_VIN, 0)
        if (!read.usable) {
            Logx.d(LogTags.VEHICLE, "VIN 属性不可用", "source" to "DID", "reason" to read.describe())
        } else {
            read.bytes?.let { bytes ->
                PublicAttrValue.parse(bytes)?.stringData?.let { raw ->
                    accept(raw, "VHAL-DID")?.let { return it }
                }
                rawUtf8(bytes)?.let { raw ->
                    accept(raw, "VHAL-DID-raw")?.let { return it }
                }
            }
        }
        (CarProperties.readAnyViaClass(PROP_DID_VIN, 0) as? String)?.let { raw ->
            accept(raw, "VHAL-DID-string")?.let { return it }
        }
        return null
    }

    /** ③ `CarSignalManager.getPaVinVinNum()` / `getDidVinVinNum()`。 */
    private fun readSignalManager(context: Context?): String? {
        val manager = signalManager(context) ?: run {
            Logx.d(LogTags.VEHICLE, "VIN 信号通道不可用", "reason" to "CarSignalManager 未就绪")
            return null
        }
        invokeNoArg(manager, "getPaVinVinNum")?.let { obj ->
            fieldString(obj, "data")?.let { raw -> accept(raw, "signal-PA")?.let { return it } }
        }
        invokeNoArg(manager, "getDidVinVinNum")?.let { obj ->
            fieldString(obj, "value")?.let { raw -> accept(raw, "signal-DID")?.let { return it } }
        }
        Logx.d(
            LogTags.VEHICLE,
            "VIN 信号读取为空",
            "reason" to "PaVinVinNum.data / DidVinVinNum.value 都为空",
        )
        return null
    }

    // ------------------------------------------------------------ 校验

    /**
     * 把车机给的原始串清洗成合法 VIN；不合法返回 `null`。
     *
     * 规则：去掉空白与不可见字符（NUL、BOM 等）→ 统一大写 → 必须恰好 17 位
     * 且不含 I/O/Q。I/O/Q 在 VIN 标准里是禁用的，出现基本就是乱码/占位串。
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isWhitespace() || ch.isISOControl() || ch == '\uFEFF') continue
            cleaned.append(ch)
        }
        if (cleaned.isEmpty()) return null
        val vin = cleaned.toString().uppercase()
        return if (VIN_PATTERN.matches(vin)) vin else null
    }

    /** 校验候选串；成功记 INFO，失败留 WARN 原始证据（原始串只进日志，不上屏）。 */
    private fun accept(raw: String?, source: String): String? {
        if (raw.isNullOrBlank()) {
            Logx.d(LogTags.VEHICLE, "VIN 候选为空", "source" to source)
            return null
        }
        val vin = normalize(raw)
        if (vin == null) {
            Logx.w(LogTags.VEHICLE, "VIN 校验失败", "raw" to raw, "source" to source)
            return null
        }
        Logx.i(LogTags.VEHICLE, "读取到 VIN", "vin" to vin, "source" to source)
        return vin
    }

    /** 裸 UTF-8 字节 → 字符串；全是空白/控制字符时视为没值。 */
    private fun rawUtf8(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        return text.takeIf { raw -> raw.any { ch -> !ch.isWhitespace() && !ch.isISOControl() } }
    }

    // ------------------------------------------------------------ 反射细节

    /** 拿一个 `CarSignalManager` 实例（静态工厂 → 公开构造器）；拿不到返回 null。 */
    private fun signalManager(context: Context?): Any? {
        signalManager?.let { return it }
        synchronized(lock) {
            signalManager?.let { return it }
            val now = System.currentTimeMillis()
            if (now - signalManagerTriedAt < SIGNAL_RETRY_MS) return null
            signalManagerTriedAt = now
            val cls = SIGNAL_MANAGER_CLASSES.firstNotNullOfOrNull { name ->
                runCatching { Class.forName(name) }.getOrNull()
            } ?: return null
            val ctx = context?.applicationContext
            for (name in SIGNAL_MANAGER_FACTORIES) {
                runCatching { cls.getMethod(name) }.getOrNull()
                    ?.let { m -> runCatching { m.invoke(null) }.getOrNull() }
                    ?.let { return it.also { instance -> signalManager = instance } }
                if (ctx != null) {
                    runCatching { cls.getMethod(name, Context::class.java) }.getOrNull()
                        ?.let { m -> runCatching { m.invoke(null, ctx) }.getOrNull() }
                        ?.let { return it.also { instance -> signalManager = instance } }
                }
            }
            runCatching { cls.getConstructor().newInstance() }.getOrNull()
                ?.let { return it.also { instance -> signalManager = instance } }
            if (ctx != null) {
                runCatching { cls.getConstructor(Context::class.java).newInstance(ctx) }.getOrNull()
                    ?.let { return it.also { instance -> signalManager = instance } }
            }
            return null
        }
    }

    private fun invokeNoArg(target: Any, name: String): Any? = runCatching {
        target.javaClass.methods
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(target)
    }.getOrNull()

    /** 取对象上的 String 字段：先按 public（jar 里就是 public final），再试 declared。 */
    private fun fieldString(target: Any, name: String): String? = runCatching {
        target.javaClass.getField(name).get(target) as? String
    }.getOrNull() ?: runCatching {
        @Suppress("DEPRECATION")
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field.get(target) as? String
    }.getOrNull()
}
