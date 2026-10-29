package com.cardesk.launcher.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
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
 * ## 第七十二轮：A 路为什么一直「Car.createCar 返回 null」
 *
 * 车机端 `ecarx.adapterapi.jar`（车机系统分区的 DEX）里带了一份
 * **ECARX 自己的 `android.car`**。把它反汇编后可以看到，它只有这两个工厂方法：
 *
 * ```java
 * static Car createCar(Context, ServiceConnection);
 * static Car createCar(Context, ServiceConnection, Handler);
 * ```
 *
 * 而我们这里试的签名是 `createCar(Context)` / `createCar(Context, Handler)` ——
 * **这两个重载在这个平台上压根不存在**，于是两次 `NoSuchMethodException` 被
 * `runCatching` 吞掉，`createCar()` 返回 null，日志就写成
 * 「Car.createCar 返回 null」。
 *
 * 换句话说：**不是车机没有 car service，是我们调错了重载**。
 * 厂商自己的 `se.cevt.vehiclesignals.VendorExtensionController` 就是这么连的：
 *
 * ```java
 * Car car = Car.createCar(context, mCarServiceConnection);
 * car.connect();   // → onServiceConnected → CarPropertyManager
 * ```
 *
 * 签名补对之后，整条属性通道（车门 `PA_CP_*`、总里程、MC 事件属性…）才有机会起来。
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

    /** `CarPropertyValue.STATUS_AVAILABLE` —— 属性值当前可用。 */
    private const val STATUS_AVAILABLE = 0

    /** 通道建连失败后的冷却时间（毫秒）—— 每秒调一次，必须限流。 */
    private const val RETRY_INTERVAL_MS = 30_000L

    /** A 路（`android.car.Car`）等待 `getCarManager` 就绪的轮询上限。 */
    private const val CONNECT_POLL_MAX = 40

    /** A 路轮询间隔（毫秒）。 */
    private const val CONNECT_POLL_INTERVAL_MS = 150L

    private val main = Handler(Looper.getMainLooper())

    /**
     * `Car.createCar(Context, ServiceConnection)` 需要的连接回调。
     *
     * ECARX 那份 `android.car` **只提供带 `ServiceConnection` 的重载**，
     * 所以这个对象不是「可选参数」—— 没有它就建连不上（见 [createCar]）。
     *
     * 这里只记日志：真正取值走 `car.getCarManager("property")`，
     * 不从回调参数里直接拿 binder（那只是 `ICar` 的 binder，还要再转一层）。
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Logx.i(
                LogTags.SIGNAL,
                "car_service 已连上",
                "component" to name?.flattenToString(),
                "binder" to (service?.javaClass?.simpleName ?: "null"),
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Logx.w(LogTags.SIGNAL, "car_service 断开", "component" to name?.flattenToString())
        }
    }

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

    /** `CarPropertyValue.getStatus()`（0 = 可用 / 1 = 不可用 / 2 = 出错）。 */
    private var mGetStatus: Method? = null

    /** 读 `byte[]` 属性的重载（`getProperty(int[], Class)`），只有 A 路有。 */
    private var mGetBytesA: Method? = null

    /**
     * 读**指定类型**属性的重载（`getProperty(Class, int, int)`），只有 A 路有。
     *
     * ## 第七十四轮：这才是这台车机上唯一能读 BYTES 属性的路
     *
     * 厂商 jar 里的 `CarPropertyManager` 只有两个 `getProperty`：
     *
     * ```
     * <E> CarPropertyValue<E> getProperty(Class<E> clazz, int propId, int area)
     *      CarPropertyValue    getProperty(int propId, int area)
     * ```
     *
     * **没有** `getProperty(int[], Class)` —— 那是 AOSP 公开 SDK 的形状，
     * ECARX 自己这份没有。所以原先那条「按字节读」的路在这台车机上**恒返回 null**。
     *
     * 属性类型由 `CarPropertyConfig.getPropertyType()` 给出（车机自己说了算），
     * 所以正确做法是：**先问类型，再按类型读**。
     */
    private var mGetTypedA: Method? = null

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
        mGetStatus = null
        mGetBytesA = null
        mGetTypedA = null
        propConfigCache = null
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

    /**
     * 读一个属性在某个区域的**整数值**。
     *
     * 车门状态那组属性（`PA_CP_Door*Sts`）是 0 / 1 的枚举值，
     * 用浮点通道读会多一层取整误差，所以单独给一个整型入口。
     *
     * @return 读不到返回 null
     */
    fun readInt(propId: Int, areaId: Int): Int? {
        val v = readNumber(propId, areaId) ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        if (v < Int.MIN_VALUE.toDouble() || v > Int.MAX_VALUE.toDouble()) return null
        return v.toInt()
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
     * ## 第七十二轮：多一条「属性清单」的路
     *
     * ECARX 那份 `CarPropertyManager` 的 `CarPropertyValue` **只有 `getAreaId()`**
     * （单个区域），没有 AOSP 的 `getAreaIds()`；而 `getProperty(int)` 单参重载也没有。
     * 所以这里在「拿不到 `getAreaIds()`」时，退到 [propertyConfigs] 的属性清单里查 ——
     * 那是 `getPropertyList()` 给的，每条配置都带 `getAreaIds()`。
     *
     * @return 区域号数组；读不到返回 null
     */
    fun availableAreas(propId: Int): IntArray? {
        val m = managerA ?: managerB ?: return null
        val get1 = if (m === managerA) {
            mGetProperty1A ?: run { resolveMethodsA(m); mGetProperty1A }
        } else {
            mGetProperty1B ?: run { resolveMethodsB(m); mGetProperty1B }
        }
        if (get1 != null) {
            val arr = runCatching {
                val value = get1.invoke(m, propId) ?: return null
                val getAreaIds = mGetAreaIds ?: return null
                (getAreaIds.invoke(value) as? IntArray)?.copyOf()
            }.getOrNull()
            if (arr != null && arr.isNotEmpty()) return arr
        }
        // 退路：属性清单（ECARX 的 CarPropertyManager 走这条）
        val cfg = propertyConfigs()?.firstOrNull { it.propertyId == propId } ?: return null
        return cfg.areaIds.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ 属性清单

    /**
     * 一个属性的配置摘要。
     *
     * @param propertyId 属性 id
     * @param access 访问权限（0 = 只读 / 1 = 可写 …，具体值由车机定义）
     * @param areaIds 该属性注册的区域号
     * @param typeName 属性类型名（`Integer` / `Float` / `String` …）
     */
    data class PropConfig(
        val propertyId: Int,
        val access: Int,
        val areaIds: IntArray,
        val typeName: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is PropConfig && other.propertyId == propertyId

        override fun hashCode(): Int = propertyId
    }

    /** 属性清单缓存（车机的属性表是静态的，查一次就够）。 */
    private var propConfigCache: List<PropConfig>? = null

    /**
     * 车机 `car_service` 支持的**属性清单**。
     *
     * ## 为什么要这个
     *
     * 这是**唯一能拿到「这台车到底支持哪些属性」的权威来源** ——
     * 比任何硬编码的属性号表都可靠。
     *
     * 有了它就能：
     *
     * 1. 确认某个属性在这台车上**存不存在**（不存在就别去读，省掉每秒几次无效 binder 调用）；
     * 2. 拿到它的**区域号**（ECARX 的 `CarPropertyValue` 没有 `getAreaIds()`）；
     * 3. 下一次诊断包里直接带出清单，排障不用再猜属性号。
     *
     * @return 读不到返回 null；空清单说明车机没报任何属性
     */
    fun propertyConfigs(): List<PropConfig>? {
        propConfigCache?.let { return it }
        val m = managerA ?: managerB ?: return null
        val list = runCatching {
            val getter = m.javaClass.methods.firstOrNull {
                it.name == "getPropertyList" && it.parameterCount == 0
            } ?: return null
            val raw = getter.invoke(m) as? List<*> ?: return null
            raw.mapNotNull { item -> item?.let { toPropConfig(it) } }
        }.getOrNull() ?: return null
        propConfigCache = list
        Logx.i(
            LogTags.SIGNAL,
            "car_service 属性清单",
            "count" to list.size,
            "doorProps" to list.filter { it.propertyId in DOOR_PROPERTY_IDS }
                .joinToString(",") {
                    // 第七十四轮：把 typeName 一并带出来。
                    // 车门那组属性是「BYTES 还是数值」此前只能靠属性 id 的类型位推断；
                    // 车机自己登记的 getPropertyType() 才是权威 —— 一次实车日志就能定论。
                    "0x%08X/type=%s/areas=%s".format(it.propertyId, it.typeName, it.areaIds.joinToString("&"))
                },
        )
        return list
    }

    /** 车门那组厂商属性的 id（`GeelyVehicleProperty.PA_CP_*`）。 */
    private val DOOR_PROPERTY_IDS = PaCpDoorProps.ALL

    /** 把一个 `CarPropertyConfig` 读成摘要（读不出来的字段留默认值，不抛）。 */
    private fun toPropConfig(raw: Any): PropConfig? {
        val id = callInt(raw, "getPropertyId") ?: return null
        val access = callInt(raw, "getAccess") ?: 0
        val areas = runCatching {
            val m = raw.javaClass.methods.firstOrNull {
                it.name == "getAreaIds" && it.parameterCount == 0
            }
            (m?.invoke(raw) as? IntArray)?.copyOf() ?: IntArray(0)
        }.getOrNull() ?: IntArray(0)
        val typeName = runCatching {
            val m = raw.javaClass.methods.firstOrNull {
                it.name == "getPropertyType" && it.parameterCount == 0
            }
            (m?.invoke(raw) as? Class<*>)?.simpleName ?: "?"
        }.getOrNull() ?: "?"
        return PropConfig(id, access, areas, typeName)
    }

    /**
     * 在候选属性 id 里挑一个**这台车机真的注册了**的。
     *
     * ## 为什么需要这个
     *
     * 同一个属性（AOSP `DOOR_POS` / `WINDOW_POS`）有两个号：
     * AOSP 标准号，和 ECARX 在自己那份 `android.car.VehiclePropertyIds`
     * 里改过的号。哪个是这台车机上真正注册的，硬编码猜不出来 ——
     * 但车机自己知道：`getPropertyList()` 会把注册的属性全列出来。
     *
     * @param candidates 候选 id，按优先级排
     * @return 清单里命中的第一个候选；清单读不到时返回第一个候选
     */
    fun resolvePropertyId(candidates: IntArray): Int {
        val configs = propertyConfigs()
        val registered = if (configs.isNullOrEmpty()) {
            emptySet()
        } else {
            val set = HashSet<Int>(configs.size)
            for (c in configs) set.add(c.propertyId)
            set
        }
        return CarSignalMath.resolvePropertyId(candidates, registered)
    }

    private fun callInt(target: Any, method: String): Int? = runCatching {
        val m = target.javaClass.methods.firstOrNull {
            it.name == method && it.parameterCount == 0
        } ?: return null
        m.invoke(target) as? Int
    }.getOrNull()

    // ------------------------------------------------------------ 反射细节

    private fun resolveMethodsA(m: Any) {
        mGetProperty2A = findMethod(m, "getProperty", 2)
        mGetProperty1A = findMethod(m, "getProperty", 1)
        mGetTypedA = findTypedPropertyMethod(m)
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
        mGetStatus = valueClass?.methods?.firstOrNull {
            it.name == "getStatus" && it.parameterCount == 0
        }
    }

    /**
     * 取 `CarPropertyValue.getValue()`，并把各种数值类型统一成 Double。
     *
     * ## 第七十二轮：先看状态，再取值
     *
     * `CarPropertyValue` 带一个状态字段：
     *
     * | 值 | 含义 |
     * | --- | --- |
     * | `0` | `STATUS_AVAILABLE` —— 值可用 |
     * | `1` | `STATUS_UNAVAILABLE` —— 属性存在但**当前没有值** |
     * | `2` | `STATUS_ERROR` —— 出错 |
     *
     * 不看状态的话，「属性存在但此刻没数据」会被当成「值 = 0」。
     * 对车门来说，**0 就是「门关着」** —— 那等于凭空造出一个「门全关」的假状态。
     * 所以这里先把状态判掉，`AVAILABLE` 之外一律当「读不到」。
     */
    private fun extractValue(value: Any): Double? {
        if (!isValueUsable(value)) return null
        val getValue = mGetValue ?: return (value as? Number)?.toDouble()
        val raw = runCatching { getValue.invoke(value) }.getOrNull() ?: return null
        return (raw as? Number)?.toDouble()
    }

    /** 属性值当前是不是「可用」。状态字段读不出来时按「可用」处理（不猜）。 */
    private fun isValueUsable(value: Any): Boolean {
        val getStatus = mGetStatus ?: return true
        val status = runCatching { getStatus.invoke(value) as? Int }.getOrNull() ?: return true
        return status == STATUS_AVAILABLE
    }

    /**
     * 读一个 **类型为 `byte[]`** 的属性。
     *
     * ## 第七十四轮：这条路在这台车机上**走不通**，别再指望它
     *
     * 这里匹配的是 `getProperty(int[], Class)` —— **AOSP 公开 SDK** 的形状。
     * ECARX 自己那份 `CarPropertyManager`（随 `ecarx.adapterapi.jar` 在系统分区）
     * 只有两个 `getProperty`：
     *
     * ```
     * <E> CarPropertyValue<E> getProperty(Class<E> clazz, int propId, int area)
     *      CarPropertyValue    getProperty(int propId, int area)
     * ```
     *
     * **没有 `int[] + Class` 那个重载**，所以 [mGetBytesA] 恒为 null，
     * 本方法在这台车机上**恒返回 null**（死路）。
     *
     * 保留它是因为：同一份代码在真正的 **CEVT 平台**（`android.car` 是 AOSP 原版）
     * 上仍然有效，而属性通道的形状是车机给的、不由我们决定。
     *
     * 在这台车机上要读字节属性，请走 [readBytesViaClass]。
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

    /**
     * 读一个属性（**按指定类型**），走 `getProperty(Class, propId, area)`。
     *
     * ## 为什么要带类型
     *
     * 同一个属性 id，取值类型是**属性 id 自带的位域**决定的，不是调用方随便挑的：
     * `propId = (group << 24) | (type << 16) | id`，`type` 位取自 `VehiclePropertyType`
     * （`STRING`/`BOOLEAN`/`INT32`/`FLOAT`/`BYTES`…）。
     *
     * 车门那组 `PA_CP_Door*Sts`（`0x21701414`~`0x21701418`）的类型位是
     * `0x70 << 16` = **`VehiclePropertyType.BYTES`** —— 也就是说它们**读出来是 `byte[]`**，
     * 用数值重载读会类型不符。
     *
     * 但这仍是**按位域推断**。车机自己知道真实类型：
     * `CarPropertyConfig.getPropertyType()` 给出期望的 Java 类型，
     * 见 [propertyTypeOf]。**先问类型再读**才是稳的做法。
     *
     * @return 读不到 / 类型不符返回 null
     */
    private fun readTypedA(propId: Int, areaId: Int, clazz: Class<*>): Any? =
        readTypedVariants(propId, areaId, listOf(clazz))

    /**
     * 按**多个候选类型**依次试读一个属性（第七十八轮新增）。
     *
     * ## 为什么要有「试多种类型」
     *
     * 车机的 `getProperty(Class, propId, area)` 会**严格比对值的实际类型**：
     * 值的 Java 类跟我们要的类型不是同一个，就直接抛 `IllegalArgumentException`
     * （jar 里 `CarPropertyManager.getProperty` 的实现就是这样写的）。
     *
     * 之前我们只按**属性 id 的类型位**推断（`0x70 << 16` = BYTES → 只试 `ByteArray`）。
     * 但那是**推断**，不是事实 —— 真机上这组车门属性（`PA_CP_Door*Sts`）
     * 到底返回 `byte[]` 还是被装成了 `Integer` / `Integer[]` / `String`，
     * 没人知道，因为**读失败时一条日志都没有**（异常被 `runCatching` 吞成 null）。
     *
     * 现在按顺序试一遍，谁的类型对就拿谁；全不对就把**异常原文**记下来。
     * 这既是修复，也是把排障的盲区补上 ——
     * 下一次诊断包里就能直接看到「车机到底返回了什么类型」。
     *
     * ## 为什么这样算是安全的
     *
     * `getProperty` 的类型校验是车机**既有**的行为，我们只是多问了几次，
     * 不会写、不会改车上的状态。
     */
    fun readTypedVariants(propId: Int, areaId: Int, classes: List<Class<*>>): Any? {
        if (classes.isEmpty()) return null
        val m = managerA ?: return null
        val getter = mGetTypedA ?: run {
            resolveMethodsA(m)
            mGetTypedA
        } ?: return null
        var lastError: String? = null
        for (clazz in classes) {
            var failure: String? = null
            val value = runCatching {
                val v = getter.invoke(m, clazz, propId, areaId) ?: return@runCatching null
                if (!isValueUsable(v)) return@runCatching null
                val getValue = mGetValue ?: return@runCatching v
                runCatching { getValue.invoke(v) }.getOrNull() ?: v
            }.onFailure { failure = "${it.javaClass.simpleName}: ${it.message?.take(160) ?: "-"}" }
                .getOrNull()
            if (value != null) {
                if (clazz != classes.first() && shouldLog("typed-ok/$propId/$areaId/${clazz.simpleName}")) {
                    Logx.i(
                        LogTags.SIGNAL, "属性按备用类型读到",
                        "prop" to "0x%08X".format(propId), "area" to areaId,
                        "type" to clazz.simpleName,
                    )
                }
                return value
            }
            if (failure != null) lastError = failure
        }
        // —— 一个都没读到：把原因记下来（第七十八轮：原来这里是全静默的）——
        val sig = "typed-fail/$propId/$areaId/${lastError ?: "-"}"
        if (shouldLog(sig)) {
            Logx.w(
                LogTags.SIGNAL, "属性按类型读取失败",
                "prop" to "0x%08X".format(propId), "area" to areaId,
                "types" to classes.joinToString("|") { it.simpleName },
                "err" to (lastError ?: "无异常（值为空或状态非可用）"),
            )
        }
        return null
    }

    /**
     * 按一组常见类型**试读**一个属性，返回原始对象（类型不定）。
     *
     * 给「不知道车机返回什么类型」的场景用 —— 目前只有车门那组属性
     * （`PA_CP_Door*Sts`，类型位是 BYTES，但实际值类型未知）。
     *
     * 候选顺序按「最可能」排：字节优先（属性 id 的类型位指向 BYTES），
     * 其次是数值、字符串。
     */
    fun readAnyViaClass(propId: Int, areaId: Int): Any? =
        readTypedVariants(propId, areaId, TYPED_CANDIDATES)

    /**
     * 试读时的候选类型（顺序即优先级）。
     *
     * 注意数值类型用**装箱类**（`java.lang.Integer`）而不是 `int`：
     * 车机那边比对的是值的**实际类**（`Integer.class`），
     * 传 `int.class` 一定对不上。
     */
    private val TYPED_CANDIDATES: List<Class<*>> = listOf(
        ByteArray::class.java,
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Float::class.java,
        java.lang.String::class.java,
        IntArray::class.java,
        Boolean::class.java,
    )

    /** 同一个签名最多打一次日志的时间窗（毫秒）。 */
    private const val LOG_THROTTLE_MS = 60_000L

    /** 各类日志的节流记录（签名 → 上次时间）。 */
    private val lastLogged = LinkedHashMap<String, Long>()

    /**
     * 日志节流：同一个 [key] 在 [LOG_THROTTLE_MS] 内只打一次。
     *
     * 为什么需要：属性轮询是**每秒几次**的节奏，
     * 而「读不到」是常态 —— 不节流的话，
     * 几百行同一条警告会把真正的信号淹掉
     * （真机诊断包里「车门状态读不到」刷了 730 条）。
     */
    @Synchronized
    private fun shouldLog(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastLogged[key] ?: 0L
        if (now - last < LOG_THROTTLE_MS) return false
        lastLogged[key] = now
        if (lastLogged.size > 64) {
            val it = lastLogged.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value < now - LOG_THROTTLE_MS) it.remove() else break
            }
        }
        return true
    }

    /**
     * 按类型读一个 `byte[]` 属性（`getProperty(ByteArray.class, propId, area)`）。
     *
     * 这才是这台车机上读字节属性的**唯一**通路。
     *
     * @param areaId 区域号；整车级属性传 `0`
     */
    fun readBytesViaClass(propId: Int, areaId: Int): ByteArray? =
        readTypedA(propId, areaId, ByteArray::class.java) as? ByteArray

    /**
     * 按类型读一个整数属性（`getProperty(Integer.class, propId, area)`）。
     *
     * 数值属性也可以用 [readInt]（走 `getProperty(int, int)`）；这条是
     * 「先问车机要 `Integer`」的显式版本，类型不符时车机会直接拒，
     * 不会像数值重载那样**把 byte[] 悄悄解成别的**。
     */
    fun readIntViaClass(propId: Int, areaId: Int): Int? {
        val v = readTypedA(propId, areaId, java.lang.Integer::class.java)
        return when (v) {
            is Number -> v.toInt()
            else -> null
        }
    }

    /**
     * 这个属性在这台车机上的**类型名**（`Integer` / `Float` / `byte[]` / `String` …）。
     *
     * 来源是 `CarPropertyConfig.getPropertyType()`，也就是车机自己登记的类型。
     * 清单读不到时返回 `null`（**不是**「读不到」而是「没问出类型」）。
     */
    fun propertyTypeOf(propId: Int): String? {
        val cfg = propertyConfigs()?.firstOrNull { it.propertyId == propId } ?: return null
        return cfg.typeName.takeIf { it.isNotEmpty() && it != "?" }
    }

    /** 找 `getProperty(Class, int, int)` 这个重载。 */
    private fun findTypedPropertyMethod(m: Any): Method? =
        m.javaClass.methods.firstOrNull {
            it.name == "getProperty" && it.parameterCount == 3 &&
                it.parameterTypes[0] == Class::class.java &&
                it.parameterTypes[1] == Integer.TYPE &&
                it.parameterTypes[2] == Integer.TYPE
        }

    private fun findMethod(m: Any, name: String, paramCount: Int): Method? =
        m.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == paramCount &&
                it.parameterTypes.all { p -> p == Integer.TYPE || p == java.lang.Integer::class.java }
        }

    /**
     * 建连 `android.car.Car`。
     *
     * ## 第七十二轮：候选顺序改了
     *
     * 这台车机上跑的 `android.car` 是 ECARX 自己的一份（随 `ecarx.adapterapi.jar`
     * 一起在系统分区），它**只有带 `ServiceConnection` 的重载**：
     *
     * ```java
     * createCar(Context, ServiceConnection)
     * createCar(Context, ServiceConnection, Handler)
     * ```
     *
     * 之前只试了 `createCar(Context)` 与 `createCar(Context, Handler)` ——
     * 两个都不存在，所以每次都是 null（诊断包里「Car.createCar 返回 null」）。
     * 现在把**带 `ServiceConnection` 的两个重载排在最前面**，
     * 厂商自己的 `VendorExtensionController` 也是这么连的。
     */
    private fun createCar(carClass: Class<*>, context: Context): Any? {
        val ctx = Context::class.java
        val handler = Handler::class.java
        val conn = ServiceConnection::class.java
        val attempts: List<() -> Any?> = listOf(
            { carClass.getMethod("createCar", ctx, conn, handler).invoke(null, context, serviceConnection, main) },
            { carClass.getMethod("createCar", ctx, conn).invoke(null, context, serviceConnection) },
            { carClass.getMethod("createCar", ctx, handler).invoke(null, context, main) },
            { carClass.getMethod("createCar", ctx).invoke(null, context) },
        )
        for (attempt in attempts) {
            runCatching { attempt() }.getOrNull()?.let { return it }
        }
        return null
    }
}
