package com.cardesk.launcher.vehicle

/**
 * 车辆信号的**纯数学**换算（不碰 Android，可单测）。
 *
 * 口径逐条
 * - 续航：
 * - 油量：
 * - 环温：
 */
object CarSignalMath {

    /**
     * 剥到**最内层**的异常（第七十九轮新增）。
     *
     * ## 为什么需要
     *
     * 反射调用抛的异常会被包一层 `InvocationTargetException`，
     * 而它自己的 `message` 是 **null** ⇒ 以前只记到
     * 「InvocationTargetException: -」，什么信息都没有（真机日志实证）。
     *
     * 剥开之后看到的是车机抛的**真异常**，例如
     * `IllegalArgumentException: Invalid property type. Expected: class java.lang.Integer,
     * but was: class [B` —— **`but was: class X` 就是车机亲口告诉我们值的真实类型**。
     *
     * @param maxDepth 最多剥几层（防异常链成环）
     */
    fun rootCause(t: Throwable, maxDepth: Int = 5): Throwable {
        var cur = t
        var depth = 0
        while (depth < maxDepth) {
            val c = cur.cause
            if (c == null || c === cur) break
            cur = c
            depth++
        }
        return cur
    }

    /**
     * 把一次反射失败剥到最内层，描述成一行（外层 + 内层）。
     *
     * 格式：`外层类名: 外层消息 | cause[N]=内层类名: 内层消息`
     */
    fun describeFailure(t: Throwable, maxDepth: Int = 5): String {
        val outer = "${t.javaClass.simpleName}: ${t.message?.take(160) ?: "-"}"
        val root = rootCause(t, maxDepth)
        if (root === t) return outer
        val depth = countDepth(t, maxDepth)
        return "$outer | cause[$depth]=${root.javaClass.name}: ${root.message?.take(200) ?: "-"}"
    }

    /** 异常链的层数（自身算第 0 层）。 */
    private fun countDepth(t: Throwable, maxDepth: Int): Int {
        var cur = t
        var depth = 0
        while (depth < maxDepth) {
            val c = cur.cause
            if (c == null || c === cur) break
            cur = c
            depth++
        }
        return depth
    }

    /**
     * 续航原始值 → 公里数。
     *
     *
     * - `raw > 0` 才认；
     * - `raw <= 3000` 直接当公里数，否则除以 1000（毫米的单位）；
     * - 结果必须落在 `1..2999`。
     *
     * @return null 表示这个原始值不是有效续航
     */
    fun rangeKm(raw: Float): Int? {
        if (raw.isNaN() || raw == Float.MIN_VALUE || raw <= 0f) return null
        val km = Math.round(if (raw <= 3000f) raw else raw / 1000f)
        if (km <= 0 || km >= 3000) return null
        return km
    }

    /**
     * 车门位置原始值 → 是否关闭。
     *
     *
     * - `< 0` → 读不出来（null）；
     * - `0` → 关闭；
     * - `1` → 打开；
     * - `553844994 / 553844993 / 553779457` → 打开（车端这三个值表示「开」）；
     * - `(3, 100]` → `<= 5` 关闭，否则打开（车端用 0..100 表示开度）；
     * - 其它 → 读不出来（null）。
     *
     * ## 第七十四轮：`2` 不再算「关闭」
     *
     * `2` 是厂商的 `COMMON_VALUE_DEFAULT`（**默认值**），不是开度百分比。
     * 之前 `2..5 → 关闭` 这条分支把它判成了「门关着」。
     *
     * 而 `BCM_FUNC_DOOR_POS` 在这台车机上**根本没实现**（恒返回 255），
     * 这条分支基本没机会用上，留着只是纯风险 —— 现在按「读不出来」处理。
     *
     * @return null 表示这个原始值不是有效的开闭状态
     */
    fun interpretClosed(raw: Int): Boolean? {
        if (raw < 0) return null
        if (raw == 0) return true
        if (raw == 1) return false
        if (raw == 553844994 || raw == 553844993 || raw == 553779457) return false
        if (raw == 2 || raw > 100) return null
        return raw <= 5
    }

    /**
     * 这个原始值是不是「**固件不支持这个功能号 / 区域**」。
     *
     * ## 为什么单独拎出来
     *
     * 真机（领克 dcy11 / Android 9）诊断包里，Adapt 的四个门位全部返回 `raw=255`。
     * `255` 就是 `-1` 的无符号写法，jar 里带着这句话：
     * `is not supported for getFunctionValue BCM_FUNC_WINDOW_POS, returns -1`。
     *
     * 也就是说 `255` **不是**「门开着」，而是「**这台车不支持这么读**」。
     * 把它当成「读不出来」是对的，但**日志里要能区分**：
     * - 「不支持」→ zone / 功能号不对，要换读法；
     * - 「值解释不出来」→ 读到了但语义不明，要换映射表。
     *
     * 两种情况在桌面上的表现一样（显示 `--`），但在排障时必须分开。
     *
     * ## 第七十四轮：把 253 / 254 也算进来
     *
     * 厂商的通用取值码（`ICarFunction`）有三个「不是真值」的：
     *
     * | 值 | 常量 | 含义 |
     * | --- | --- | --- |
     * | `253` | `COMMON_VALUE_ERROR` | 读取出错 |
     * | `254` | `COMMON_VALUE_NONE` | 无此能力 / 未定义 |
     * | `255` | `COMMON_VALUE_UNKNOWN` | 未知 / 未实现的功能号 |
     *
     * 之前只挡了 `255`，于是 `253` 会一路流进业务逻辑 ——
     * 最要命的是 **`HVAC_FUNC_TEMP` 的整型读恒返回 253**
     * （厂商源码 `CarFunctionValueImpl.java:399`）：
     * `253 / 2 = 126.5` → 被 clamp 成 **32℃**，
     * 也就是**「读不到」被伪装成「车机说 32 度」**。
     */
    fun isUnsupportedRaw(raw: Int): Boolean =
        raw == 255 || raw == 254 || raw == 253 || raw == -1 || raw == Int.MIN_VALUE

    /**
     * 车窗开闭原始值 → 是否打开（**true = 开**）。
     *
     * ## 口径
     *
     * `BCM_FUNC_WINDOW` 是**布尔**功能（不是百分比），所以只认：
     *
     * - `0` → 关（false）；
     * - `1` → 开（true）；
     * - `553844994` / `553844993` / `553779457` → 开（BCM 的「开」枚举，与车门同源）。
     *
     * 其它一律**读不出来**（null）。
     *
     * ## 为什么不套用车门那张表
     *
     * [interpretClosed] 里的 `2..5` 关闭 / `6..100` 打开是**位置百分比**的语义
     * （`BCM_FUNC_DOOR_POS` 返回的是开度）。车窗这一档返回的是**开 / 关**，
     * 把 `50` 解释成「开了一半」属于凭空捏造，所以不做。
     *
     * 文档也明确写了：车窗**开合百分比**是弱证据（`BCM_FUNC_WINDOW_POS` 可能整条
     * 不支持），**禁止**套用手机 BLE 的 `raw 2..26 → (raw-1)*4` 编码。
     * 真要拿百分比，等真机探测出 `BCM_FUNC_WINDOW_POS` 的实际值域再说。
     */
    fun interpretWindowOpen(raw: Int): Boolean? = when (raw) {
        0 -> false
        1 -> true
        553844994, 553844993, 553779457 -> true
        else -> null
    }

    /**
     * 逐扇车窗**开度** → 是否打开（**true = 开**）。
     *
     * ## 这条是第七十一轮才有的
     *
     * 车机端（`ecarx.adapterapi.jar`）里逐扇车窗只有一个信号：
     * `PaSpeechWinCtrlFrntLe` / `FrntRi` / `ReLe` / `ReRi`，
     * 它的 `data` 字段是**开度**（厂商给的合法区间是 `0..127`），
     * `status` 字段是 `NORMAL` / `ABNORMAL`。
     *
     * 厂商自己的取值函数（`getMappedFunctionValue`）的口径是：
     * - `data` 不在 `0..127` → 返回 `-1`（读不出来）；
     * - `status == ABNORMAL` → 返回 `-1`（读不出来）；
     * - 否则 → 返回 `data` 原值。
     *
     * 所以这里照搬同一套口径：
     *
     * - `< 0` → 读不出来（null）—— **异常 / 无数据**，不是「关着」；
     * - `0` → 关（false）；
     * - `> 0` → 开（true）。
     *
     * ## 为什么不用 [interpretWindowOpen]
     *
     * 那条是给**布尔**车窗信号（`BCM_FUNC_WINDOW`）用的；
     * 这条是给**开度**信号用的。两者值域不同，混用会把「开度 50」当成读不出来。
     *
     * ## 第七十四轮：`0` 可能是「关」，也可能是「读失败」
     *
     * 厂商的**浮点**读失败时返回 **`0.0f`，不是 `-1`**
     * （`status == ABNORMAL` / `data` 越界 / 信号为 null → 一律翻译成 `0.0f`，
     * 见文档 11 号「车窗」段）。
     * 而 `0.0f` 恰好又是「窗关着」的开度 ——
     * 于是**读不到会伪装成「窗全关」**。
     *
     * 单看数值分不开这两件事，只能靠「这个 zone 到底支不支持」来区分：
     * 调用方把 `isFunctionSupported(func, zone)` 的结论传进来，
     * 明确「不支持」时，`0` 也按「读不到」处理。
     *
     * @param supported 该区域是否被固件支持（true / false / 判不了=null）
     */
    fun interpretWindowPosition(raw: Int, supported: Boolean? = null): Boolean? = when {
        raw < 0 -> null
        supported == false -> null
        raw == 0 -> false
        else -> true
    }

    /**
     * 后备箱 / 引擎盖的开合原始值 → 是否关闭（**true = 关**）。
     *
     * ## 口径（第七十一轮，按 jar 实测定）
     *
     * 厂商的 `getMappedFunctionValue(PaSpeechTrCtrl)` 把信号枚举翻译成 BCM 门值：
     *
     * | 信号枚举 | 翻译出的值 | 含义 |
     * | --- | --- | --- |
     * | `OPEN` | `1` | 开 |
     * | `CLOSE` | `0` | 关 |
     * | `IDLE` | `553844993` | 静止不动 —— **不确定** |
     * | `STOP` | `553844993` | 停在中间 —— **不确定** |
     *
     * 所以这里只认 `0` / `1`，其余一律**读不出来**：
     * `IDLE` / `STOP` 说成「关」或「开」都是编的。
     *
     * @param raw 原始值
     * @return true = 关；false = 开；null = 读不出来 / 不确定
     */
    fun interpretLatchClosed(raw: Int): Boolean? = when (raw) {
        0 -> true
        1 -> false
        else -> null
    }

    /**
     * 油量原始值 → 百分比。
     *
     *
     * - 油箱容量未知（`<= 0`）→ `raw` 在 `0..100` 就当百分比直接用，否则读不出来；
     * - 油箱容量很小（`0 < capacity <= 200`）→ `raw > 2 * capacity` 时先除以 1000，
     *   然后当百分比；
     * - 油箱容量已知（`> 200`）→ `raw` 在 `0..200` 且 `raw * 1000 <= 1.2 * capacity`
     *   时先乘 1000，再 `raw / capacity * 100`；
     * - 结果统一夹到 `0..100`。
     *
     * @return null 表示换算不出百分比
     */
    fun fuelPercent(raw: Float, capacity: Float): Int? {
        val cap = if (capacity == Float.MIN_VALUE) 0f else capacity
        return if (cap <= 200f) {
            if (cap <= 0f) {
                if (raw < 0f || raw > 100f) null else Math.round(raw).coerceIn(0, 100)
            } else {
                val v = if (raw > 2f * cap) raw / 1000f else raw
                Math.round(v).coerceIn(0, 100)
            }
        } else {
            var r = raw
            if (r > 0f && r <= 200f) {
                val scaled = 1000f * r
                if (scaled <= 1.2f * cap) r = scaled
            }
            Math.round((r / cap) * 100f).coerceIn(0, 100)
        }
    }

    /**
     * 车端 `ISensor` 推来的油量原始值 → 百分比（**含占位值口径**）。
     *
     * 与 [fuelPercent] 只有一处不同：**`raw <= 0` 一律当「没有数据」（null）**。
     *
     * 原因（真机实测，见 `analysis/findings/09-fuel-sensor-fix.md`）：
     * 这台车机（领克 dcy11 / Android 9）的油量传感器
     * 在**开机**和**车机服务重连**时会先推一个 `0.0` 的**占位值**，
     * 真实值（实测 `21000.0`，约 5 分钟后）才会推来。
     * 把占位值当成「油箱空了」会让桌面显示一个**假的 0%**。
     *
     * 口径取「宁可显示 `--%`，也不显示一个假的 0%」。
     */
    fun fuelPercentFromSensor(raw: Float, capacity: Float): Int? {
        if (raw <= 0f) return null
        return fuelPercent(raw, capacity)
    }

    /**
     * 车端 `ISensor` 推来的**总里程**原始值 → 公里数（单位**确定就是 km**）。
     *
     * ## 为什么这条不能走 [core.VehicleStats.odometerKm] 那套换算
     *
     * 属性通道（`PERF_ODOMETER` / `ODOMETER`）的米/公里口径不明，
     * 所以老代码用「大于 10 万就当米、除以 1000」来猜。
     *
     * 而 `ISensor` 这条路的单位是**厂商写死在值域里的**：
     * `SensorPaUtil.getValue(PaDiDstTrVld)` 判 `data > 2000000 || data < 0` 即越界，
     * 即取值域 `0..2,000,000` —— 200 万**公里**是合理车龄上限，
     * 200 万**米**只有 2000 公里、不可能。所以单位就是 km。
     *
     * 走老那套猜测的话，一辆跑了 15 万公里的车会被当成
     * 「150000 米」再除以 1000 → 显示成 **150 km**，错得离谱。
     *
     * ## 为什么 `0` 判为无效
     *
     * `SensorPaUtil.getValue` 在「信号为 null」和「值越界」两种情况下
     * **都返回 `0.0f`** —— 它和真正的「0 公里」没法区分。
     * 宁可显示 `--`，也不显示一个假的 0 公里。
     *
     * @return null 表示这个原始值不是有效总里程
     */
    fun odometerKmFromSensor(raw: Float): Int? {
        if (raw.isNaN() || raw == Float.MIN_VALUE) return null
        if (raw <= 0f) return null
        // 厂商值域上限 2,000,000；超出说明车端给的不是公里数。
        if (raw > 2_000_000f) return null
        return Math.round(raw)
    }

    /**
     * 由**本行程油耗**反解出平均油耗（L/100km）。
     *
     * ## 为什么要反解，而不是直接拿行程油耗顶
     *
     * 厂商口径（`DrivingInfoManager`）：
     * - `getAvgFuelConsumption()` → **L/100km**；
     * - `getTripFuelConsumption()` → **L**（本行程总共烧了多少升），
     *   换算是 `avgFuel × odo / 100`。
     *
     * 这两个**差 100 倍**。之前平均油耗拿不到时直接顶行程油耗，
     * 128 km 跑掉 12.3 L 的车会显示成「12.3 L/100km」，
     * 而正确值是 `12.3 × 100 / 128 = 9.6 L/100km`。
     *
     * 按厂商公式反解即可：`avgFuel = tripFuel × 100 / tripKm`。
     *
     * @param tripFuelL 本行程油耗（**升**）
     * @param tripKm 本行程里程（**公里**）
     * @return null 表示反解不出来（任一缺失，或行程里程为 0）
     */
    fun avgFuelConsumptionFromTrip(tripFuelL: Double?, tripKm: Double?): Double? {
        if (tripFuelL == null || tripKm == null) return null
        if (tripFuelL.isNaN() || tripFuelL.isInfinite()) return null
        if (tripKm.isNaN() || tripKm.isInfinite()) return null
        if (tripFuelL < 0.0 || tripKm <= 0.0) return null
        return tripFuelL * 100.0 / tripKm
    }

    /**
     * 环温原始值 → 摄氏度。
     *
     * 四舍五入后必须落在 `-40..80`，否则丢弃。
     *
     * @return null 表示这个原始值不是有效环温
     */
    fun ambientTempC(raw: Float): Int? {
        if (raw.isNaN() || raw == Float.MIN_VALUE) return null
        val t = Math.round(raw)
        return if (t in -40..80) t else null
    }

    /**
     * 车机端 `FunctionStatus` 的**名字** → 「支不支持」三态结论。
     *
     * ## 为什么按名字而不是 ordinal
     *
     * 车机端 `com.ecarx.xui.adaptapi.FunctionStatus` 是个枚举：
     *
     * ```
     * active         支持且当前可用
     * notactive      支持但当前不可用（判不了）
     * notavailable   这台车不支持这一档
     * error          出错（判不了）
     * ```
     *
     * 枚举的 **ordinal 是实现细节**（增删一个常量就会整体错位），
     * 所以这里只认**名字**。
     *
     * @param name `FunctionStatus` 的枚举名；null / 认不出来 → 判不了
     * @return true = 支持；false = 这台车不支持；null = 判不了
     */
    fun functionSupported(name: String?): Boolean? = when (name?.lowercase()) {
        "active" -> true
        "notavailable" -> false
        else -> null
    }

    /**
     * 一个属性的**读法**（按车机登记的类型决定）。
     *
     * ## 第七十四轮：车门属性「按类型读」的决策抽成纯函数
     *
     * 车门那组厂商属性（`PA_CP_Door*Sts` / `PA_CP_TrSts`）的类型
     * 写在**属性 id 的位域**里（`type << 16`），按 `0x70 << 16` 推断是
     * `VehiclePropertyType.BYTES` —— 也就是 `byte[]`。
     * 但那只是推断，**车机自己登记的类型才是权威**
     * （`CarPropertyConfig.getPropertyType()`）。
     *
     * 所以读之前先按类型名分派：
     * - `byte[]` → 走字节读（`getProperty(ByteArray.class, id, area)`）+ 解 varint；
     * - 数值型 → 走 `Integer` / 数值读；
     * - 问不出来 → 两种都试，**字节优先**。
     */
    enum class PropReadKind {
        /** 字节属性（`byte[]`）。 */
        BYTES,

        /** 数值属性（`Integer` / `Float` / `Short` …）。 */
        NUMERIC,

        /** 类型没问出来：两种都试。 */
        UNKNOWN,
    }

    /**
     * 按车机登记的类型名 → 读法。
     *
     * @param typeName `CarPropertyConfig.getPropertyType().simpleName`；null / `?` → [PropReadKind.UNKNOWN]
     */
    fun propReadKind(typeName: String?): PropReadKind = when {
        typeName == null || typeName.isEmpty() || typeName == "?" -> PropReadKind.UNKNOWN
        typeName.startsWith("byte", ignoreCase = true) -> PropReadKind.BYTES
        typeName in NUMERIC_TYPE_NAMES -> PropReadKind.NUMERIC
        else -> PropReadKind.UNKNOWN
    }

    /** 数值型属性的类型名（`getPropertyType()` 返回的是 Java 类型的 simpleName）。 */
    private val NUMERIC_TYPE_NAMES = setOf(
        "Integer", "int", "Short", "short", "Long", "long", "Float", "float", "Double", "double",
    )

    /**
     * 在候选属性 id 里挑一个「车机真的注册了」的。
     *
     * ## 为什么需要这个
     *
     * 同一个属性（AOSP `DOOR_POS` / `WINDOW_POS`）有两个号：
     * AOSP 标准号，和 ECARX 在自己那份 `android.car.VehiclePropertyIds` 里改过的号
     * （车门 `0x16400B00` vs `0x16401000`；车窗 `0x13400BC0` vs `0x13401000`）。
     *
     * 哪个是这台车机上真正注册的，硬编码猜不出来 ——
     * 但车机自己知道：`CarPropertyManager.getPropertyList()` 会把注册的属性全列出来。
     *
     * ## 口径
     *
     * - 候选按优先级排，**第一个在注册清单里的**胜出；
     * - 一个都没命中 → 用第一个候选（也许清单不完整，先照常用）；
     * - 清单读不到（空集合）→ 用第一个候选；
     * - 候选为空 → 返回 `0`（读不到）。
     */
    fun resolvePropertyId(candidates: IntArray, registered: Set<Int>): Int {
        if (candidates.isEmpty()) return 0
        if (registered.isEmpty()) return candidates.first()
        return candidates.firstOrNull { registered.contains(it) } ?: candidates.first()
    }
}
