package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Method

/**
 * `android.car` 的 **CarPropertyManager** 通道：按「属性 id + 区域号」读车身属性。
 *
 * ##依据
 *
 * 车身属性读取走的是
 *
 * `java
 * Car car = Car.createCar(app); // android.car.Car
 * car.connect();
 *  props = () car.getCarManager("property"); // CarPropertyManager
 * props.getProperty(291504904); // 取属性句柄（拿到可用区域）
 * props.getProperty(Float.class, 291504904, area); // 取某个区域的值
 * `
 *
 * ## 第三十七轮：为什么原来读不到（真机 4383 诊断包）
 *
 * 真机日志里每次刷新都报：
 * `CarPropertyManager 通道不可用 reason=InvocationTargetException: null`。
 *
 * 根因是**我们创建了 `android.car.Car` 却从来没有调用 **：
 * AOSP 的 `Car.getCarManager(name)` 内部先
 * 没连上就抛 `IllegalStateException`，反射层把它包成。
 * 于是 `CarProperties.manager` 永远是 null → 车门 / 车窗 / 总里程全部读不到。
 *
 * APK 内嵌了一份 AOSP `android.car` 的源码副本（脱壳重命名版）
 * 它揭示了**另一条更稳的路**：
 *
 * `java
 * IBinder service = ServiceManager.getService("car_service"); // 同步拿 binder
 * m29f("property") → ICar.getService("property") → PropertyManager
 * `
 *
 * 也就是说 **PropertyManager 的 binder 可以直接拿，不需要先 **；
 * 里那句 只是为了监听服务生死，不参与取值。
 *
 * ## 现在的两条路
 *
 * | 路 | 做法 | 特点 |
 * | --- | --- | --- |
 * | **B（主）** | `ServiceManager.getService("car_service")` → → `getService("property")` → | 同步、无需连接、与同源；全反射，不 import 隐藏类 |
 * | **A（备）** | `android.car.Car.createCar(ctx)` → → 轮询 `getCarManager("property")` | 公开 API，但 可能阻塞 → 放后台线程跑 |

 * B 路失败（隐藏 API 被拦 / 车机没有 `car_service`）时自动退回 A 路；
 * A 路在后台线程连接，接好后 `readNumber` 立刻生效。
 *
 * ## 生命周期
 *
 * `android.car.Car` 的连接在进程内**只做一次**（`createCar` 重复调用会抛
 * `already connected or connecting`，所以这里是**单例 + 幂等**：
 * [CarLibGateway] 也共用这里的连接，不再自己 `createCar`。
 */
object CarProperties {

    private const val CLS_CAR = "android.car.Car"
    private const val CLS_PROPERTY_VALUE = "android.car.hardware.CarPropertyValue"
    private const val CLS_SERVICE_MANAGER = "android.os.ServiceManager"
    private const val CLS_ICAR_STUB = "android.car.ICar\$Stub"
    private const val CLS_IPROPERTY_STUB = "android.car.hardware.property.IPropertyManager\$Stub"
    private const val CLS_PROPERTY_MANAGER = "android.car.hardware.property.PropertyManager"

    private const val CAR_SERVICE = "car_service"
    private const val MANAGER_PROPERTY = "property"

    /** 通道建连失败后的冷却时间（毫秒）—— 每秒调一次，必须限流。 */
    private const val RETRY_INTERVAL_MS = 30_000L

    /** A 路（`android.car.Car`）等待 `getCarManager` 就绪的轮询上限。 */
    private const val CONNECT_POLL_MAX = 40

    /** A 路轮询间隔（毫秒）。 */
    private const val CONNECT_POLL_INTERVAL_MS = 150L

    private val main = Handler(Looper.getMainLooper())

    private var car: Any? = null

    /** A 路拿到的 `CarPropertyManager`（公开 API 的 `getCarManager("property")`）。 */
    private var managerA: Any? = null

    /** B 路拿到的 `IPropertyManager` 代理。 */
    private var managerB: Any? = null

    private var connected = false
    private var connecting = false
    private var lastError: String? = null

    /**
     * 两条路各自的**下次重试时间**。
     *
     *  会被桌面每秒调一次（`currentVehicleInfo` → `CarBodySignals.attach`），
     * 而路 B 每次都要做一次 binder 事务、路 A 每次都要起一个线程轮询 6 秒 ——
     * 不限流的话，真机上就是「每秒一次 binder 调用 + 每秒一个新线程」。
     * 所以：失败后按 [RETRY_INTERVAL_MS] 冷却一次。
     */
    private var pathBNextTryAt = 0L
    private var pathANextTryAt = 0L

    /** 当前生效的路径名（仅用于日志）。 */
    private var activePath: String = ""

    /**
     * 已知的读取方法（缓存反射结果，避免每次都 `getMethod`）。
     *
     * **两条路各存一份** —— `Method` 是绑定在声明类上的，
     * 拿 A 路的 `Method` 去 invoke B 路的代理会抛 `IllegalArgumentException`。
     */
    private var mGetProperty2A: Method? = null
    private var mGetProperty1A: Method? = null
    private var mGetProperty2B: Method? = null
    private var mGetProperty1B: Method? = null
    private var mGetValue: Method? = null
    private var mGetAreaIds: Method? = null

    /** 读 `byte[]` 属性的重载（`getProperty(int[], Class)`），只有 A 路有。 */
    private var mGetBytesA: Method? = null

    // ------------------------------------------------------------ 连接

    /**
     * 建立（或复用）属性通道。幂等。
     *
     * B 路是**同步**的（只是拿 binder），所以这里不会阻塞调用方；
     * A 路放到后台线程，避免车机服务慢的时候把桌面刷新卡住。
     *
     * @return 通道此刻是否已经可用
     */
    @Synchronized
    fun attach(context: Context): Boolean {
        if (connected && (managerA != null || managerB != null)) return true
        val app = context.applicationContext ?: context

        val now = System.currentTimeMillis()

        // —— 路 B：直接拿 car_service 的 binder（与同源，快） ——
        if (managerB == null && now >= pathBNextTryAt) {
            connectViaCarService()
            if (managerB == null) pathBNextTryAt = now + RETRY_INTERVAL_MS
        }

        // —— 路 A：公开 API 的 android.car.Car，放后台连 ——
        if (managerA == null && !connecting && now >= pathANextTryAt) {
            connecting = true
            pathANextTryAt = now + RETRY_INTERVAL_MS
            Thread({
                connectViaCarClass(app)
                connecting = false
            }, "cardesk-car-conn").apply { isDaemon = true }.start()
        }

        connected = managerA != null || managerB != null
        return connected
    }

    /**
     * 路 B：`ServiceManager.getService("car_service")` → `ICar` → `getService("property")`。
     *
     * 全程反射，不 import 任何隐藏类；任一步失败就安静退回（由路 A 兜底）。
     */
    private fun connectViaCarService() {
        val binder = runCatching {
            Class.forName(CLS_SERVICE_MANAGER)
                .getMethod("getService", String::class.java)
                .invoke(null, CAR_SERVICE) as? IBinder
        }.getOrNull()
        if (binder == null) {
            Logx.d(LogTags.SIGNAL, "car_service binder 取不到", "path" to "B")
            return
        }
        val propertyBinder = runCatching {
            val iCar = Class.forName(CLS_ICAR_STUB)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            iCar?.javaClass
                ?.getMethod("getService", String::class.java)
                ?.invoke(iCar, MANAGER_PROPERTY) as? IBinder
        }.getOrNull()
        if (propertyBinder == null) {
            Logx.d(LogTags.SIGNAL, "ICar.getService(\"property\") 取不到", "path" to "B")
            return
        }
        val mgr = runCatching {
            Class.forName(CLS_IPROPERTY_STUB)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, propertyBinder)
        }.getOrNull() ?: runCatching {
            // 少数固件把 binder 直接交给了 PropertyManager 包装类。
            Class.forName(CLS_PROPERTY_MANAGER)
                .getDeclaredConstructors()
                .firstOrNull { it.parameterCount == 2 }
                ?.let { ctor ->
                    ctor.isAccessible = true
                    ctor.newInstance(null, propertyBinder)
                }
        }.getOrNull()
        if (mgr == null) {
            Logx.d(LogTags.SIGNAL, "PropertyManager 代理取不到", "path" to "B")
            return
        }
        managerB = mgr
        activePath = "B:car_service"
        resolveMethodsB(mgr)
        connected = true
        lastError = null
        Logx.i(
            LogTags.SIGNAL,
            "CarPropertyManager 通道就绪",
            "path" to activePath,
            "class" to mgr.javaClass.name,
        )
    }

    /**
     * 路 A：`android.car.Car` → → 等 `getCarManager("property")` 就绪。
     *
     *  在不同 Android 版本上可能是同步的、也可能是异步的（bindService），
     * 所以这里**轮询**到 `getCarManager` 返回非 null 为止。
     */
    private fun connectViaCarClass(context: Context) {
        val started = System.currentTimeMillis()
        runCatching {
            val carClass = Class.forName(CLS_CAR)
            val instance = createCar(carClass, context)
            if (instance == null) {
                lastError = "Car.createCar 返回 null"
                Logx.w(LogTags.SIGNAL, "CarPropertyManager 通道不可用", "path" to "A", "reason" to lastError)
                return
            }
            car = instance
            // 关键一步：没 connect()，getCarManager 一定会抛 IllegalStateException。
            runCatching { instance.javaClass.getMethod("connect").invoke(instance) }
                .onFailure {
                    Logx.d(LogTags.SIGNAL, "Car.connect() 调用失败", "reason" to "${it.javaClass.simpleName}: ${it.message}")
                }
            val get = instance.javaClass.getMethod("getCarManager", String::class.java)
            var mgr: Any? = null
            var lastReason = "getCarManager 未就绪"
            for (i in 0 until CONNECT_POLL_MAX) {
                val r = runCatching { get.invoke(instance, MANAGER_PROPERTY) }
                mgr = r.getOrNull()
                if (mgr != null) break
                lastReason = "${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}"
                Thread.sleep(CONNECT_POLL_INTERVAL_MS)
            }
            if (mgr == null) {
                lastError = "CarPropertyManager 通道不可用"
                Logx.w(
                    LogTags.SIGNAL, "CarPropertyManager 通道不可用",
                    "path" to "A", "reason" to lastReason, "waitedMs" to (System.currentTimeMillis() - started),
                )
                return
            }
            managerA = mgr
            activePath = "A:android.car.Car"
            resolveMethodsA(mgr)
            connected = true
            lastError = null
            Logx.i(
                LogTags.SIGNAL,
                "CarPropertyManager 通道就绪",
                "path" to activePath,
                "class" to mgr.javaClass.name,
                "waitedMs" to (System.currentTimeMillis() - started),
            )
        }.onFailure {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            Logx.w(LogTags.SIGNAL, "CarPropertyManager 通道不可用", it, "path" to "A", "reason" to lastError)
        }
    }

    /** 断开（进程退出或通道整体重建时）。 */
    @Synchronized
    fun detach() {
        connected = false
        managerA = null
        managerB = null
        car = null
        activePath = ""
        mGetProperty1A = null
        mGetProperty2A = null
        mGetProperty1B = null
        mGetProperty2B = null
        mGetValue = null
        mGetAreaIds = null
    }

    /** 通道是否可用。 */
    fun isAvailable(): Boolean = connected && (managerA != null || managerB != null)

    /** 最近一次失败原因。 */
    fun lastError(): String? = lastError

    /** 当前生效的通道路径（仅日志用）。 */
    fun activePath(): String = activePath

    /** 供 [CarLibGateway] 共用同一条连接。 */
    fun manager(): Any? = managerA ?: managerB

    // ------------------------------------------------------------ 读取

    /**
     * 读一个属性在某个区域的值。
     *
     * @param propId 属性 id（标准或厂商扩展）
     * @param areaId 区域号；单值属性传 0
     * @return 读不到返回 null
     */
    fun readNumber(propId: Int, areaId: Int): Double? {
        readViaA(propId, areaId)?.let { return it }
        return readViaB(propId, areaId)
    }

    /** 路 A：`CarPropertyManager.getProperty(int, int)`。 */
    private fun readViaA(propId: Int, areaId: Int): Double? {
        val m = managerA ?: return null
        val get2 = mGetProperty2A ?: run {
            resolveMethodsA(m)
            mGetProperty2A
        } ?: return null
        return runCatching {
            val value = get2.invoke(m, propId, areaId) ?: return null
            extractValue(value)
        }.getOrNull()
    }

    /** 路 B：`IPropertyManager.getProperty(int, int)`。 */
    private fun readViaB(propId: Int, areaId: Int): Double? {
        val m = managerB ?: return null
        val get2 = mGetProperty2B ?: run {
            resolveMethodsB(m)
            mGetProperty2B
        } ?: return null
        return runCatching {
            val value = get2.invoke(m, propId, areaId) ?: return null
            extractValue(value)
        }.getOrNull()
    }

    /**
     * 读一个属性在**所有可用区域**的值。
     *
     * @return 长度 = 区域数的数组；读不到返回 null
     */
    fun readAllAreas(propId: Int): DoubleArray? {
        val areas = availableAreas(propId) ?: return null
        if (areas.isEmpty()) return null
        val out = DoubleArray(areas.size)
        var any = false
        for (i in areas.indices) {
            val v = readNumber(propId, areas[i])
            if (v != null) {
                out[i] = v
                any = true
            } else {
                out[i] = Double.NaN
            }
        }
        return if (any) out else null
    }

    /**
     * 查一个属性有哪些可用区域。
     *
     * 就是这么用的
     *  拿到区域数组，再对每个区域取一次值。
     * AOSP 的等价物是 `CarPropertyManager.getProperty(int propertyId)` → `CarPropertyValue.getAreaIds()`。
     *
     * @return 区域号数组；读不到返回 null
     */
    fun availableAreas(propId: Int): IntArray? {
        val m = managerA ?: managerB ?: return null
        val get1 = if (m === managerA) {
            mGetProperty1A ?: run { resolveMethodsA(m); mGetProperty1A }
        } else {
            mGetProperty1B ?: run { resolveMethodsB(m); mGetProperty1B }
        } ?: return null
        return runCatching {
            val value = get1.invoke(m, propId) ?: return null
            val getAreaIds = mGetAreaIds ?: return null
            val arr = getAreaIds.invoke(value) as? IntArray ?: return null
            if (arr.isEmpty()) null else arr.copyOf()
        }.getOrNull()
    }

    // ------------------------------------------------------------ 反射细节

    private fun resolveMethodsA(m: Any) {
        mGetProperty2A = findMethod(m, "getProperty", 2)
        mGetProperty1A = findMethod(m, "getProperty", 1)
        resolveValueMethods()
    }

    private fun resolveMethodsB(m: Any) {
        mGetProperty2B = findMethod(m, "getProperty", 2)
        mGetProperty1B = findMethod(m, "getProperty", 1)
        resolveValueMethods()
    }

    private fun resolveValueMethods() {
        val valueClass = runCatching { Class.forName(CLS_PROPERTY_VALUE) }.getOrNull()
        mGetValue = valueClass?.methods?.firstOrNull {
            it.name == "getValue" && it.parameterCount == 0
        }
        mGetAreaIds = valueClass?.methods?.firstOrNull {
            it.name == "getAreaIds" && it.parameterCount == 0
        }
    }

    /** 取 `CarPropertyValue.getValue()`，并把各种数值类型统一成 Double。 */
    private fun extractValue(value: Any): Double? {
        val getValue = mGetValue ?: return (value as? Number)?.toDouble()
        val raw = runCatching { getValue.invoke(value) }.getOrNull() ?: return null
        return (raw as? Number)?.toDouble()
    }

    /**
     * 读一个 **类型为 `byte[]`** 的属性。
     *
     * ## 为什么单独开一条
     *
     * 厂商事件属性（MC / Geely 那一套）不是数值属性，而是**一段 protobuf 字节**：
     * `CarPropertyManager.getProperty(int[] propertyIds, Class<T> clazz)`
     * `List<T>`，取 `clazz = byte[].class` 时拿到的就是原始字节。
     *
     * 这个重载**只有 A 路**（公开 API 的 `android.car.Car` → `CarPropertyManager`）有；
     * B 路的 `IPropertyManager` 只有 `getProperty(int, int)`，读不了字节属性。
     * 所以这里只走 A 路，读不到就返回 null。
     */
    fun readBytes(propId: Int): ByteArray? {
        val m = managerA ?: return null
        val getter = mGetBytesA ?: run {
            mGetBytesA = m.javaClass.methods.firstOrNull {
                it.name == "getProperty" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == IntArray::class.java &&
                    it.parameterTypes[1] == Class::class.java
            }
            mGetBytesA
        } ?: return null
        return runCatching {
            val list = getter.invoke(m, intArrayOf(propId), ByteArray::class.java) as? List<*> ?: return null
            list.firstOrNull() as? ByteArray
        }.getOrNull()
    }

    private fun findMethod(m: Any, name: String, paramCount: Int): Method? =
        m.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == paramCount &&
                it.parameterTypes.all { p -> p == Integer.TYPE || p == java.lang.Integer::class.java }
        }

    /**
     * 建连 `android.car.Car`。
     *
     * 候选顺序按 AOSP `Car.createCar` 的公开重载来（不同 Android 版本重载不一样）。
     */
    private fun createCar(carClass: Class<*>, context: Context): Any? {
        val ctx = Context::class.java
        val handler = Handler::class.java
        val attempts: List<() -> Any?> = listOf(
            { carClass.getMethod("createCar", ctx, handler).invoke(null, context, main) },
            { carClass.getMethod("createCar", ctx).invoke(null, context) },
        )
        for (attempt in attempts) {
            runCatching { attempt() }.getOrNull()?.let { return it }
        }
        return null
    }
}
