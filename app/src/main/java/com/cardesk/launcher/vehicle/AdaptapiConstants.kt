package com.cardesk.launcher.vehicle

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * AdaptAPI（`com.ecarx.xui.adaptapi.*`）的**常量按名字解析**。
 *
 * ## 为什么需要这一层（第六十六轮）
 *
 * 真机（领克 dcy11 / Android 9）诊断包里，车门四档全部返回 `raw=255`：
 *
 * ```
 * 车门状态探针值无法解释 label=主驾 zone=1 raw=255
 * 车门状态探针值无法解释 label=副驾 zone=4 raw=255
 * 车门状态探针值无法解释 label=左后 zone=16 raw=255
 * 车门状态探针值无法解释 label=右后 zone=64 raw=255
 * ```
 *
 * `255` 就是 `-1`（AdaptAPI 的 jar 里带着这句话：
 * `is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`）。
 *
 * 也就是说：**`BCM_FUNC_DOOR_POS` 本身能用，但我们传的 zone 不对**。
 * 我们用的 `{1, 4, 16, 64}` 是参考桌面（君君）那台车的 zone 约定，
 * 而这台车机上的门区常量是**另外一套编号**——
 * Adapt jar 里的符号名是 `DOOR_ROW_1_LEFT` / `DOOR_ROW_1_RIGHT` /
 * `DOOR_ROW_2_LEFT` / `DOOR_ROW_2_RIGHT` / `DOOR_HOOD` / `DOOR_REAR`，
 * 窗区是 `WINDOW_ROW_1_LEFT` / `WINDOW_ROW_1_RIGHT` /
 * `WINDOW_ROW_2_LEFT` / `WINDOW_ROW_2_RIGHT`。
 *
 * ## 做法：不猜数值，**按名字找**
 *
 * 这些常量就住在我们**已经拿在手里的** `ICarFunction` 句柄的类（或它的接口 /
 * 嵌套类）上。所以这里做的是：
 *
 * 1. 从 `ICarFunction` 句柄的类出发，展开「类 + 它实现的接口 + 它们的嵌套类」；
 * 2. 在这些类里找**同名**的静态 int 字段（接口常量、`static final` 都算）；
 * 3. 顺带把 `BCM_FUNC_*` 的功能号也按名字解析出来
 *    （`BCM_FUNC_WINDOW` 的数值我们**本来就不清楚**，只能靠名字拿）。
 *
 * 找不到就返回 null —— **宁可读不到，也不猜一个数值**。
 * 猜错的后果是「读到别的功能的值」，那比读不到更糟。
 *
 * ## 缓存
 *
 * 常量表不会变，查一次就够：
 * - 命中 → 缓存值；
 * - 未命中 → 也缓存 null，但**不反复打日志**（每秒刷新一次会刷屏）。
 */
object AdaptapiConstants {

    // ------------------------------------------------------------ 名字表

    /**
     * 门区常量的名字，顺序 **FL / FR / RL / RR / 引擎盖 / 后备箱**。
     *
     * `DOOR_ROW_1_LEFT` = 前排左 = 主驾门；`DOOR_REAR` = 后备箱（不是尾门侧门）。
     */
    val DOOR_ZONE_NAMES = arrayOf(
        "DOOR_ROW_1_LEFT",
        "DOOR_ROW_1_RIGHT",
        "DOOR_ROW_2_LEFT",
        "DOOR_ROW_2_RIGHT",
        "DOOR_HOOD",
        "DOOR_REAR",
    )

    /** 窗区常量的名字，顺序 **FL / FR / RL / RR**。 */
    val WINDOW_ZONE_NAMES = arrayOf(
        "WINDOW_ROW_1_LEFT",
        "WINDOW_ROW_1_RIGHT",
        "WINDOW_ROW_2_LEFT",
        "WINDOW_ROW_2_RIGHT",
    )

    /** 车门位置功能号（与 `AdaptapiBody.BCM_DOOR_POS` 同名，解析失败时用它兜底）。 */
    const val FUNC_DOOR_POS = "BCM_FUNC_DOOR_POS"

    /** 车窗开合功能号（**布尔**开合，不是百分比）。 */
    const val FUNC_WINDOW = "BCM_FUNC_WINDOW"

    /** 车窗位置功能号（百分比，弱证据，只作探测用）。 */
    const val FUNC_WINDOW_POS = "BCM_FUNC_WINDOW_POS"

    // ------------------------------------------------------------ 候选类

    /**
     * 候选类名（按可能性排序）。
     *
     * 这些常量按 jar 的组织习惯，通常在 `car` 包下的某个「常量/枚举/接口」里。
     * 真机解析时优先走 [registerRoot] 注册的**运行时类**（那是最准的），
     * 这里只是兜底。
     */
    private val CANDIDATE_CLASSES = arrayOf(
        "com.ecarx.xui.adaptapi.car.Car",
        "com.ecarx.xui.adaptapi.car.ICarFunction",
        "com.ecarx.xui.adaptapi.car.CarFunction",
        "com.ecarx.xui.adaptapi.car.CarFunctionId",
        "com.ecarx.xui.adaptapi.car.CarFunctionIds",
        "com.ecarx.xui.adaptapi.car.CarFunc",
        "com.ecarx.xui.adaptapi.car.CarZone",
        "com.ecarx.xui.adaptapi.car.CarZones",
        "com.ecarx.xui.adaptapi.car.CarArea",
        "com.ecarx.xui.adaptapi.car.CarAreas",
        "com.ecarx.xui.adaptapi.car.CarConstant",
        "com.ecarx.xui.adaptapi.car.CarConstants",
        "com.ecarx.xui.adaptapi.car.BcmFunc",
        "com.ecarx.xui.adaptapi.car.BcmFuncId",
        "com.ecarx.xui.adaptapi.car.CarBcm",
        "com.ecarx.xui.adaptapi.car.CarProperty",
        "com.ecarx.xui.adaptapi.car.CarProperties",
        "com.ecarx.xui.adaptapi.ICarFunction",
        "com.ecarx.xui.adaptapi.Car",
        "com.ecarx.xui.adaptapi.FunctionId",
        "com.ecarx.xui.adaptapi.FunctionIds",
        "com.ecarx.xui.adaptapi.Constants",
    )

    // ------------------------------------------------------------ 状态

    /** 运行时注册进来的根类（`ICarFunction` 句柄的类、`Car` 的类……）。 */
    private val roots = LinkedHashSet<Class<*>>()

    /** 解析结果缓存：名字 → 值（null = 找不到）。 */
    private val cache = HashMap<String, Int?>()

    /**
     * **单测覆盖**：名字 → 值。
     *
     * 真机上这些常量来自车机 jar，只能反射解析；单测里没法造一个车机 jar，
     * 所以留这个接缝：置上之后 [resolve] 直接按覆盖值返回。
     * 置 null 恢复正常解析。
     */
    @Volatile
    internal var testOverride: Map<String, Int>? = null

    /** 已经打过的「找不到」日志（每个名字只打一次）。 */
    private val loggedMisses = HashSet<String>()

    /**
     * 注册一个「根类」，后续按名字解析时从它开始找。
     *
     * 由 [AdaptapiGateway] 在拿到 `ICarFunction` 句柄时调用 ——
     * 那是**最准**的来源：常量和句柄在同一个 jar 里。
     */
    @Synchronized
    fun registerRoot(cls: Class<*>?) {
        if (cls == null) return
        if (roots.add(cls)) {
            // 新根类进来 → 缓存作废（之前按名字找的结论可能不完整）。
            cache.clear()
            loggedMisses.clear()
            Logx.d(LogTags.SIGNAL, "AdaptAPI 常量根类已注册", "class" to cls.name)
        }
    }

    /** 清缓存（换车 / 通道重建时调）。 */
    @Synchronized
    fun invalidate() {
        cache.clear()
        loggedMisses.clear()
        roots.clear()
    }

    /** 单测复位：清缓存 + 清覆盖值。 */
    @Synchronized
    internal fun resetForTest() {
        invalidate()
        testOverride = null
    }

    // ------------------------------------------------------------ 解析

    /**
     * 按名字解析一个 int 常量。
     *
     * @return null = 找不到（**不猜**）
     */
    @Synchronized
    fun resolve(name: String): Int? {
        testOverride?.let { return it[name] }
        if (cache.containsKey(name)) return cache[name]
        var hit: Int? = null
        var from: String? = null
        for (cls in searchClasses()) {
            val v = lookupIn(cls, name)
            if (v != null) {
                hit = v
                from = cls.name
                break
            }
        }
        cache[name] = hit
        if (hit == null) {
            if (loggedMisses.add(name)) {
                Logx.d(LogTags.SIGNAL, "AdaptAPI 常量按名解析不到", "name" to name)
            }
        } else {
            Logx.i(LogTags.SIGNAL, "AdaptAPI 常量按名解析成功", "name" to name, "value" to hit, "from" to from)
        }
        return hit
    }

    /**
     * 门区号（顺序 **FL / FR / RL / RR / 引擎盖 / 后备箱**）。
     *
     * 每一项独立：解析不到的留 null，其余照常。
     *
     * @return 全都解析不到时返回 null
     */
    fun doorZones(): List<Int?>? {
        val list = DOOR_ZONE_NAMES.map { resolve(it) }
        return if (list.all { it == null }) null else list
    }

    /** 窗区号（顺序 **FL / FR / RL / RR**）。解析不到的留 null。 */
    fun windowZones(): List<Int?>? {
        val list = WINDOW_ZONE_NAMES.map { resolve(it) }
        return if (list.all { it == null }) null else list
    }

    /**
     * 引擎盖区域号（jar 里的 `DOOR_HOOD`）；解析不到返回 null。
     *
     * 这台车机上「机盖」目前**只有** `car_signal` 的 `getHoodSts` 与 AOSP
     * `DOOR_POS` 的 HOOD 区域两条路，Adapt 这条是第三条。
     */
    fun hoodZone(): Int? = resolve("DOOR_HOOD")

    /**
     * 后备箱区域号（jar 里的 `DOOR_REAR`）；解析不到返回 null。
     *
     * 说明：jar 里四个侧门是 `DOOR_ROW_1/2_LEFT/RIGHT`，
     * 剩下两个开口就是 `DOOR_HOOD`（机盖）与 `DOOR_REAR`（**后备箱 / 尾门**）——
     * 这是按命名做的推断，值本身仍以真机读数为准（诊断包里会带上 zone 一起回传）。
     */
    fun trunkZone(): Int? = resolve("DOOR_REAR")

    /**
     * 解析一个功能号；解析不到时用 [fallback]。
     *
     * @param fallback 兜底值；`null` 表示没有兜底（解析不到就返回 null）
     */
    fun funcId(name: String, fallback: Int? = null): Int? = resolve(name) ?: fallback

    // ------------------------------------------------------------ 内部

    /**
     * 候选类列表：运行时注册进来的根类优先，然后是 jar 里的固定候选。
     */
    private fun searchClasses(): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        for (root in roots) expand(root, out, 2)
        for (name in CANDIDATE_CLASSES) {
            val cls = runCatching { Class.forName(name) }.getOrNull() ?: continue
            expand(cls, out, 1)
        }
        return out.toList()
    }

    /**
     * 展开一个类：它自己 + 嵌套类 + 它实现的接口 + 接口的嵌套类。
     *
     * @param depth 还能往下展开几层
     */
    private fun expand(cls: Class<*>, out: LinkedHashSet<Class<*>>, depth: Int) {
        if (!out.add(cls)) return
        if (depth <= 0) return
        val nested = runCatching { cls.declaredClasses.toList() }.getOrElse { emptyList() }
        for (n in nested) expand(n, out, depth - 1)
        val ifaces = runCatching { cls.interfaces.toList() }.getOrElse { emptyList() }
        for (i in ifaces) expand(i, out, depth - 1)
    }

    /**
     * 在一个类里找一个同名常量。
     *
     * 依次尝试：
     * 1. 静态字段（`public` / `declared` 都试，接口常量也在这一路）；
     * 2. 枚举常量（`Enum` 自带一个 int 取值方法时）。
     */
    internal fun lookupIn(cls: Class<*>, name: String): Int? {
        // —— 静态字段 ——
        val field = runCatching { cls.getField(name) }.getOrNull()
            ?: runCatching { cls.getDeclaredField(name) }.getOrNull()
        if (field != null) {
            runCatching { field.isAccessible = true }
            val v = runCatching { field.get(null) }.getOrNull()
            toInt(v)?.let { return it }
        }
        // —— 枚举常量 ——
        val enumConst = runCatching {
            cls.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == name }
        }.getOrNull()
        if (enumConst != null) {
            enumInt(enumConst)?.let { return it }
        }
        return null
    }

    /** 把字段值转成 int；转不了返回 null。 */
    private fun toInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Long -> v.toInt()
        is Enum<*> -> enumInt(v)
        else -> null
    }

    /**
     * 枚举常量的数值：找它身上**返回 int** 的取值方法。
     *
     * 枚举本身没有「值」，只能靠 `getValue()` / `getZone()` / `getCode()` 这类
     * 访问器拿。找不到就返回 null（不猜 ordinal）。
     */
    private fun enumInt(enumConst: Any): Int? {
        val cls = enumConst.javaClass
        for (name in ENUM_VALUE_ACCESSORS) {
            val m = runCatching {
                cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            }.getOrNull() ?: continue
            val v = runCatching { m.invoke(enumConst) }.getOrNull()
            val asInt = toInt(v)
            if (asInt != null) return asInt
        }
        return null
    }

    /** 枚举取值方法的候选名。 */
    private val ENUM_VALUE_ACCESSORS = arrayOf(
        "getValue", "getZone", "getCode", "getId", "getArea", "getType", "value",
    )
}
