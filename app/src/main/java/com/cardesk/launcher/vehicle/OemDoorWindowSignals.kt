package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车机原生的 **BCM 车身状态**通道：`VehicleAPI` → `getBcm()` → `getDoorAndWindowState()`。
 *
 * ## 为什么必须有这一条（第六十六轮）
 *
 * 真机（领克 dcy11 / Android 9）诊断包里，车门与车窗**四档全灭**：
 *
 * ```
 * ECarXCar 信号通道不可用（类不存在）          ← 源1（car_signal）
 * 车门状态读不到（BCM_DOOR_POS 所有探针都没有有效值）  ← 源2（zone 不对）
 * 车门位置读不到 prop=DOOR_POS                 ← 源3（car_service 没有）
 * 车门 MC 事件属性：读不到（该平台没有这组属性）      ← 源4（不是 CEVT 平台）
 * 车窗位置读不到 prop=WINDOW_POS               ← 车窗只有这一档，断了就没了
 * ```
 *
 * 结果是桌面上车门 / 车窗**永远是「读不到」**。
 *
 * 而车机上其实还有**第四条**路（见
 * `vehicle-body-signals-read.md` §3.3）：
 *
 * ```
 * VehicleAPI.createVehicleAPI(ctx)
 *   └─ getBcm()
 *        └─ getDoorAndWindowState()  →  IDoorAndWindow
 *             ├─ isDoorOpened(int)    门是否打开
 *             ├─ isDoorLocked(int)    门是否上锁
 *             └─ isWindowOpened(int)  窗是否打开（**布尔，不是百分比**）
 * ```
 *
 * 这一条是**车窗唯一的布尔来源**（AOSP `WINDOW_POS` 属性在这台车上根本没注册），
 * 所以车窗没有它就一定读不到。
 *
 * ## 区域（类型）号
 *
 * 文档口径：**门与窗各一套类型常量**，都是「前门左 / 前门右 / 后门左 / 后门右」= `1..4`。
 * 我们的全局顺序是 **FL / FR / RL / RR**，正好一一对应：
 *
 * | 我们的下标 | 位置 | 类型号 |
 * | --- | --- | --- |
 * | 0 | 左前（主驾） | 1 |
 * | 1 | 右前（副驾） | 2 |
 * | 2 | 左后 | 3 |
 * | 3 | 右后 | 4 |
 *
 * 读不出来的那一扇是 null，其余照常（逐扇独立，不作废整组）。
 *
 * ## 能力探测
 *
 * 这条通道的类名 / 方法名在不同固件上不一定一致，所以：
 * - 拿不到就**安静跳过**（负缓存 60s，不每秒重试）；
 * - 拿得到就缓存住，之后每 900ms 读一次（与桌面 1 秒刷新节奏一致）。
 */
object OemDoorWindowSignals {

    /** 缓存时长（毫秒）。与其它属性通道同一档节奏。 */
    const val CACHE_MS = 900L

    /** 「这一条不存在」的负缓存时长（毫秒）。 */
    const val NEGATIVE_CACHE_MS = 60_000L

    /** 类型号：前门左 / 前门右 / 后门左 / 后门右（门与窗各一套，取值相同）。 */
    val TYPE_IDS = intArrayOf(1, 2, 3, 4)

    /** 位置名（只用于日志）。 */
    private val POSITION_NAMES = arrayOf("左前", "右前", "左后", "右后")

    /** `VehicleAPI` 的候选类名（按可能性排序）。 */
    private val API_CLASSES = arrayOf(
        "ecarx.car.VehicleAPI",
        "com.ecarx.car.VehicleAPI",
        "ecarx.vehicle.VehicleAPI",
        "com.ecarx.vehicle.VehicleAPI",
        "ecarx.car.hardware.vehicle.VehicleAPI",
        "com.ecarx.car.hardware.vehicle.VehicleAPI",
        "ecarx.car.api.VehicleAPI",
        "com.ecarx.car.api.VehicleAPI",
        "ecarx.vehicleapi.VehicleAPI",
        "com.ecarx.vehicleapi.VehicleAPI",
        "ecarx.car.VehicleApi",
        "com.ecarx.car.VehicleApi",
    )

    /** 取 `VehicleAPI` 实例的静态工厂方法名。 */
    private val FACTORY_METHODS = arrayOf(
        "createVehicleAPI", "createVehicleApi", "getVehicleAPI", "getVehicleApi",
        "create", "getInstance", "newInstance",
    )

    // ------------------------------------------------------------ 状态

    private var api: Any? = null
    private var doorWindow: Any? = null
    private var resolved = false
    private var lastError: String? = null

    /** 确认「拿不到」的时刻；在这之前不再试。 */
    private var unavailableUntil = 0L

    private var doorCache: List<Boolean?>? = null
    private var doorCacheAt = 0L
    private var windowCache: List<Boolean?>? = null
    private var windowCacheAt = 0L

    // ------------------------------------------------------------ 连接

    /**
     * 解析通道（幂等）。
     *
     * @return 通道此刻是否可用
     */
    @Synchronized
    fun attach(context: Context): Boolean {
        if (resolved) return doorWindow != null
        val now = System.currentTimeMillis()
        if (now < unavailableUntil) return false
        val ctx = context.applicationContext ?: context

        val apiObj = obtainApi(ctx)
        if (apiObj == null) {
            lastError = "VehicleAPI 类不存在或取不到实例"
            unavailableUntil = now + NEGATIVE_CACHE_MS
            Logx.w(LogTags.SIGNAL, "BCM 车身通道不可用", "reason" to lastError)
            return false
        }
        api = apiObj

        val dw = obtainDoorAndWindow(apiObj)
        if (dw == null) {
            lastError = "取不到 IDoorAndWindow（getBcm/getDoorAndWindowState 失败）"
            unavailableUntil = now + NEGATIVE_CACHE_MS
            Logx.w(LogTags.SIGNAL, "BCM 车身通道不可用", "reason" to lastError)
            return false
        }
        doorWindow = dw
        resolved = true
        lastError = null
        Logx.i(
            LogTags.SIGNAL,
            "BCM 车身通道就绪",
            "api" to apiObj.javaClass.name,
            "class" to dw.javaClass.name,
        )
        return true
    }

    /** 通道是否可用。 */
    fun isAvailable(): Boolean = doorWindow != null

    /** 最近一次失败原因。 */
    fun lastError(): String? = lastError

    /** 清缓存（换车 / 通道重建时调）。 */
    @Synchronized
    fun invalidate() {
        api = null
        doorWindow = null
        resolved = false
        lastError = null
        unavailableUntil = 0L
        doorCache = null
        doorCacheAt = 0L
        windowCache = null
        windowCacheAt = 0L
    }

    // ------------------------------------------------------------ 读取

    /**
     * 四扇车门的开闭（**true = 关**），顺序 FL/FR/RL/RR。
     *
     * @return 整组 null = 一扇都读不到；每项 null = 这一扇读不到
     */
    fun doorsClosed(): List<Boolean?>? {
        val now = System.currentTimeMillis()
        doorCache?.let { if (now - doorCacheAt < CACHE_MS) return it }
        val dw = doorWindow ?: return null
        val out = MutableList<Boolean?>(TYPE_IDS.size) { null }
        var any = false
        for (i in TYPE_IDS.indices) {
            val opened = callBool(dw, "isDoorOpened", TYPE_IDS[i])
            if (opened == null) {
                Logx.d(LogTags.SIGNAL, "车门状态读不到（IDoorAndWindow）", "label" to POSITION_NAMES[i], "type" to TYPE_IDS[i])
                continue
            }
            out[i] = !opened
            any = true
        }
        if (!any) return null
        doorCache = out
        doorCacheAt = now
        Logx.d(
            LogTags.SIGNAL,
            "车门状态（IDoorAndWindow）",
            "closed" to out.joinToString(",") { c -> c?.let { if (it) "关" else "开" } ?: "?" },
        )
        return out
    }

    /**
     * 四扇车窗的开闭（**true = 开**），顺序 FL/FR/RL/RR。
     *
     * @return 整组 null = 一扇都读不到；每项 null = 这一扇读不到
     */
    fun windowsOpenEach(): List<Boolean?>? {
        val now = System.currentTimeMillis()
        windowCache?.let { if (now - windowCacheAt < CACHE_MS) return it }
        val dw = doorWindow ?: return null
        val out = MutableList<Boolean?>(TYPE_IDS.size) { null }
        var any = false
        for (i in TYPE_IDS.indices) {
            val opened = callBool(dw, "isWindowOpened", TYPE_IDS[i])
            if (opened == null) {
                Logx.d(LogTags.SIGNAL, "车窗状态读不到（IDoorAndWindow）", "label" to POSITION_NAMES[i], "type" to TYPE_IDS[i])
                continue
            }
            out[i] = opened
            any = true
        }
        if (!any) return null
        windowCache = out
        windowCacheAt = now
        Logx.d(
            LogTags.SIGNAL,
            "车窗状态（IDoorAndWindow）",
            "open" to out.joinToString(",") { c -> c?.let { if (it) "开" else "关" } ?: "?" },
        )
        return out
    }

    // ------------------------------------------------------------ 反射细节

    /**
     * 取 `VehicleAPI` 实例。
     *
     * 文档口径是 `VehicleAPI.createVehicleAPI(ctx)`；
     * 不同固件上方法名可能不同，所以按候选名单逐个试。
     */
    private fun obtainApi(context: Context): Any? {
        for (name in API_CLASSES) {
            val cls = runCatching { Class.forName(name) }.getOrNull() ?: continue
            for (factory in FACTORY_METHODS) {
                val m = runCatching {
                    cls.methods.firstOrNull {
                        it.name == factory && it.parameterCount == 1 &&
                            it.parameterTypes[0].isAssignableFrom(Context::class.java)
                    }
                }.getOrNull() ?: continue
                val v = runCatching { m.invoke(null, context) }.getOrNull()
                if (v != null) {
                    Logx.d(LogTags.SIGNAL, "VehicleAPI 实例就绪", "class" to cls.name, "factory" to factory)
                    return v
                }
            }
            // 无参静态工厂
            for (factory in FACTORY_METHODS) {
                val m = runCatching {
                    cls.methods.firstOrNull { it.name == factory && it.parameterCount == 0 }
                }.getOrNull() ?: continue
                val v = runCatching { m.invoke(null) }.getOrNull()
                if (v != null) {
                    Logx.d(LogTags.SIGNAL, "VehicleAPI 实例就绪", "class" to cls.name, "factory" to factory)
                    return v
                }
            }
            // 单例字段
            val inst = runCatching {
                cls.fields.firstOrNull { it.name == "INSTANCE" || it.name == "sInstance" }
                    ?.let { it.isAccessible = true; it.get(null) }
            }.getOrNull()
            if (inst != null) {
                Logx.d(LogTags.SIGNAL, "VehicleAPI 实例就绪", "class" to cls.name, "factory" to "单例字段")
                return inst
            }
        }
        return null
    }

    /**
     * 取 `IDoorAndWindow` 句柄。
     *
     * 两条路：
     * 1. `api.getBcm().getDoorAndWindowState()`（文档口径）；
     * 2. `api.getDoorAndWindowState()`（少数固件少一层）。
     */
    private fun obtainDoorAndWindow(apiObj: Any): Any? {
        val bcm = callNoArg(apiObj, "getBcm")
        if (bcm != null) {
            val dw = callNoArg(bcm, "getDoorAndWindowState")
            if (dw != null) return dw
            // getBcm() 直接就是 IDoorAndWindow 的情况
            if (hasMethod(bcm, "isDoorOpened") || hasMethod(bcm, "isWindowOpened")) {
                Logx.d(LogTags.SIGNAL, "IDoorAndWindow 句柄就绪", "path" to "getBcm()")
                return bcm
            }
        }
        val direct = callNoArg(apiObj, "getDoorAndWindowState")
        if (direct != null) {
            Logx.d(LogTags.SIGNAL, "IDoorAndWindow 句柄就绪", "path" to "getDoorAndWindowState()")
            return direct
        }
        return null
    }

    /** 调一个无参方法；失败返回 null。 */
    private fun callNoArg(target: Any, name: String): Any? {
        val m = runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(target) }.getOrNull()
    }

    /** 目标上有没有这个方法。 */
    private fun hasMethod(target: Any, name: String): Boolean =
        runCatching { target.javaClass.methods.any { it.name == name } }.getOrElse { false }

    /**
     * 调一个「返回 boolean、参数是 int」的方法。
     *
     * @return null = 方法不存在 / 调用失败 / 返回值不是布尔
     */
    private fun callBool(target: Any, name: String, arg: Int): Boolean? {
        val m = runCatching {
            target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 1 &&
                    (it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == Int::class.javaPrimitiveType)
            }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(target, arg) as? Boolean }.getOrNull()
    }
}
