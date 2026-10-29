package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * AdaptAPI 的 **ISensor** 通道：剩余续航 / 油量 / 环温。
 *
 * ## 取值通道
 *
 * 这三项都**不是**从属性通道读的，而是走 `ISensor`：
 *
 * - 句柄：`iCar.getSensorManager()`
 * - 监听注册：`sensor.registerListener(listener, SENSOR_XXX)`
 * - 取最新值：`sensor.getSensorLatestValue(id)`
 *
 * | 传感器 id | 常量 | 含义 | SDK 里实现了吗 |
 * | --- | --- | --- | --- |
 * | 1050624 | `SENSOR_ENDURANCE_KM` | 续航（km） | ✅ `SensorImpl.getSensorLatestValue` 有这个 case |
 * | 1050112 | `SENSOR_FUEL_LEVEL` | 油量（原始值） | ✅ 同上 |
 * | 4211456 | `SENSOR_POTENTIAL_ENDURANCE` | 潜在续航 | ❌ 只有常量定义，取值恒 `-1.0f` |
 * | 4211200 | `SENSOR_DISCHARGE_ENDURANCE` | 放电续航 | ❌ 同上 |
 * | 4210944 | `SENSOR_CHARGE_ENDURANCE` | 充电续航 | ❌ 同上 |
 * | 1051392 | `SENSOR_TEMP_AMBIENT` | 环温（℃） | ❌ 同上（连监听都注册不上） |
 *
 * 油箱容量另走 `iCar.getCarInfoManager().getCarInfoFloat(INFO_FUEL_CAPACITY = 2097408)`
 * 百分比换算口径见。
 *
 * ## 我们之前的问题
 *
 * 之前续航/油量只走 `ecarx.car.ECarXCar`（私有 SignalManager）那条路，
 * 而领克 dcy11 车机（Android 9 / SDK 28）上**那个类根本不存在**
 * 日志里表现为「续航变为不可读」、油量永远 `--%`。
 * 这次把真正用的 `ISensor` 通道补上。
 *
 * ## 取值口径（逐条）
 *
 * - 续航：
 *   `raw > 0` 时，`raw <= 3000` 直接取整，否则除以 1000；结果落在 `1..2999` 才认；
 * - 油量：
 *   油箱容量已知（> 200）时 `percent = raw / capacity * 100`；
 *   容量未知或很小（<= 200）时，`raw` 在 `0..100` 就当百分比直接用，
 *   否则先把 `raw` 除以 1000 再当百分比；最后统一夹到 `0..100`；
 *   **另外：`raw <= 0` 一律当「没有数据」**（见第六十四轮）；
 * - 环温：**没有这一条了** —— `ISensor` 上的环温传感器在 SDK 里没实现
 *   （见 [SENSOR_TEMP_AMBIENT]），不能拿它当环温来源。
 *
 * ## 传感器列表的收缩
 *
 * `ISensor` 的取值接口 `SensorImpl.getSensorLatestValue` 只实现了 8 个传感器，
 * 其余 id 恒返回 `-1.0f`。之前我们的列表里挂了几个**只定义了常量、没实现取值**的
 * 传感器（潜在/放电/充电续航）和一个没实现的环温传感器，
 * 探针每轮白读一次、日志里多一条 INFO —— 现在都从列表里去掉了。
 * 油量与续航这两条真正在用的探针逻辑**没有动**。
 *
 * ## 第六十四轮：油量「永远拿不到」的修复
 *
 * 真机诊断包（`cardesk-logs-20261028-203357.zip`）里油量是 **0%**，
 * 而更早的诊断包（10-24）里同一个车能正常拿到 **38%**（`raw=21000.0`）。
 * 对比下来有三个洞：
 *
 * 1. **占位值被当成了真值** —— 车端开机 / 重连时先推 `0.0`，
 *    之前直接发布成「0%」，真值晚到也被它盖住；
 * 2. **油量只探一次** —— 真实值约 5 分钟后才推来，
 *    而探针只跑一次（慢速复查只覆盖续航），推送漏一次就再也拿不到；
 * 3. **油箱容量读取完全静默** —— 读不到就返回 0，日志里没有任何痕迹，
 *    表现成「油量永远 `--%`」，根本看不出是容量没读到。
 *
 * 修法：占位值一律忽略 + 油量跟着续航探针一起周期复查 +
 * 容量读取多路兜底（float → int，接口优先取方法）且**失败必留日志**。
 */
object AdaptapiSensors {

    // ------------------------------------------------------------常量

    /** 油量（`ISensor.SENSOR_TYPE_FUEL_LEVEL`）；SDK 里有实现。 */
    const val SENSOR_FUEL_LEVEL = 1050112

    /** 续航（`ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE`）；SDK 里有实现。 */
    const val SENSOR_ENDURANCE_KM = 1050624

    /**
     * 潜在续航（`IVirtualSensor.TYPE_POTENTIAL_ENDURANCE_MILEAGE`）。
     *
     * **SDK 里只有常量定义，没有实现取值**：
     * `SensorImpl.getSensorLatestValue` 的 8 个 case 里没有它 →
     * 落到 `default: return -1.0f`（`SensorImpl.java:250-251`）；
     * `SensorListener.mListenableSensors` 里也没有它 →
     * `registerListener` 直接 `return false`（`SensorListener.java:271-274`）。
     *
     * 之前它挂在续航探针的传感器列表里，探针每轮都白读一次 `-1.0f`。
     */
    const val SENSOR_POTENTIAL_ENDURANCE = 4211456

    /**
     * 放电续航（`IVirtualSensor.TYPE_DISCHARGING_ENDURANCE_MILEAGE`）。
     *
     * **SDK 里没有实现取值**，理由同 [SENSOR_POTENTIAL_ENDURANCE]。
     */
    const val SENSOR_DISCHARGE_ENDURANCE = 4211200

    /**
     * 充电续航（`IVirtualSensor.TYPE_CHARGING_ENDURANCE_MILEAGE`）。
     *
     * **SDK 里没有实现取值**，理由同 [SENSOR_POTENTIAL_ENDURANCE]。
     */
    const val SENSOR_CHARGE_ENDURANCE = 4210944

    /**
     * 环温（`ISensor.SENSOR_TYPE_TEMPERATURE_AMBIENT`）。
     *
     * **这个传感器在 SDK 里根本没实现，读不到**：
     * - `SensorImpl.getSensorLatestValue` 的 8 个 case 里没有 1051392
     *   → 落到 `default: return -1.0f`（`SensorImpl.java:250-251`）；
     * - `SensorListener.mListenableSensors` 里也没有它
     *   → `registerListener` 直接 `return false`（`SensorListener.java:271-274`）。
     *
     * 注意 **`-1.0f` 不能当成无效值过滤掉**：-1℃ 是合法的环温，
     * 靠数值判断分不出「没实现」和「真的是 -1℃」——
     * 所以只能把它从传感器列表里去掉，不能留在探针里当环温来源。
     *
     * 真正的环温要走**属性通道**
     * `ENV_OUTSIDE_TEMPERATURE = 291505923`（`VehicleIds.ENV_OUTSIDE_TEMPERATURE`）——
     * 那是**另一条取数路径**，本次不接（只修缺陷，不加新通道）；
     * 这里把正确路径记下来，等以后要显示环温时再接。
     */
    const val SENSOR_TEMP_AMBIENT = 1051392

    const val INFO_FUEL_CAPACITY = 2097408

    const val RANGE_RETRY_MAX = 12

    const val RANGE_RETRY_MS = 2500L

    /**
     * 拿到续航之后的**慢速复查**间隔。
     *
     * 只在 12 次 × 2.5s = 30s 的窗口里重试。实测这台车机上
     * `ISensor` 的值是**车端有变化才推**：油量传感器开机时是 0.0，
     * 五分钟后才推来真实值（21000.0）。所以 30s 窗口过后仍然可能拿不到续航，
     * 这里额外保留一个慢速复查（30s 一次，DEBUG 级日志，不刷盘）。
     *
     * **第六十四轮**：这个慢速复查同时用来**复查油量**——
     * 之前只有续航走复查，油量只探一次（开机那一刻），
     * 于是「真实值晚到 / 推送漏掉 / 换算时容量还没读到」这三种情况都会让油量**永远拿不到**。
     */
    const val RANGE_SLOW_RETRY_MS = 30_000L

    /**
     * 油箱容量的**缓存**有效期（毫秒）。
     *
     * 容量是车机常量，读到一次就缓存住；读不到时每 30s（复查节奏）再试一次。
     */
    const val FUEL_CAPACITY_CACHE_MS = 30_000L

    /** 「油箱容量读不到」这条 WARN 的最小间隔（毫秒）。 */
    const val FUEL_CAPACITY_WARN_MS = 60_000L

    /**
     * 续航探针要探的传感器 id（按优先级顺序）。
     *
     * **只列 SDK 真正实现了取值的那些**：
     * `SensorImpl.getSensorLatestValue` 只有 8 个 case
     * （1048832 车速 / 1049344 PM2.5 / 1049856 AQI / 1050112 油量 /
     * 1050368 总里程 / 1050624 续航 / 4206592 保养里程 / 4210688 SOC%），
     * 其它 id 一律落 `default: return -1.0f`（`SensorImpl.java:224-252`）。
     *
     * 之前这里还挂着 4211456（潜在续航）/ 4211200（放电续航）/ 4210944（充电续航）——
     * 那三个在 SDK 里**只有常量定义、没有取值实现**，
     * 探针每轮都白读一次 `-1.0f`（还会打一条 INFO 日志），纯属浪费。
     *
     * 油量不在这个列表里：它走 [probeFuel]，跟着同一个探针节奏跑，
     * 但读的是自己的 id（1050112，SDK 里是有实现的）。
     */
    val RANGE_SENSORS = intArrayOf(
        SENSOR_ENDURANCE_KM,
    )

    /** 反射用：`int` 的原始类型。 */
    private val INT_CLASS: Class<*> = Int::class.javaPrimitiveType ?: Int::class.java

    // ------------------------------------------------------------ 状态

    private var car: Any? = null
    private var sensor: Any? = null
    private var listener: Any? = null
    private val registeredIds = LinkedHashSet<Int>()

    /** 续航探针的调度器（`main` handler）。 */
    private val main = Handler(Looper.getMainLooper())

    /** 剩余续航（km）；-1 = 不可读。 */
    private val rangeKm = AtomicInteger(-1)

    /** 油量百分比；-1 = 不可读。 */
    private val fuelPct = AtomicInteger(-1)

    /**
     * 环温（℃）；[Int.MIN_VALUE] = 不可读。
     *
     * **当前恒为「不可读」**：`ISensor` 那条通道上的环温传感器（1051392）
     * 在 SDK 里没有实现（`getSensorLatestValue` 恒 -1.0f、`registerListener` 直接 false），
     * 所以这里**不会**再有环温值进来 —— 理由见 [SENSOR_TEMP_AMBIENT]。
     *
     * 字段先留着：以后接上属性通道 `ENV_OUTSIDE_TEMPERATURE` 时直接复用。
     */
    private val ambientC = AtomicInteger(Int.MIN_VALUE)

    /** 上次一次取到的续航原始值（换算用）。 */
    private var lastFuelRaw = Float.NaN

    /** 上次读到的油箱容量（车机常量，读到就缓存）。 */
    private var fuelCapacityCache = 0f

    /** 油箱容量的缓存时刻。 */
    private var fuelCapacityAt = 0L

    /** 最近一次「油箱容量读不到」WARN 的时刻（限频用）。 */
    private var fuelCapacityWarnAt = 0L

    /** 是否已经初始化过（幂等）。 */
    private var started = false

    /** 续航重试计数。 */
    private var rangeRetryCount = 0

    /** 续航探针的重试调度。 */
    private val rangeRetry = Runnable { probeRangeSources("retry") }

    /** 拿到续航之后的慢速复查调度。 */
    private val rangeSlowRetry = Runnable { probeRangeSources("slow") }

    /** 最近一次失败原因（设置页/诊断用）。 */
    private var lastError: String? = null

    // ------------------------------------------------------------ 连接

    /**
     * 绑定车机句柄并注册传感器监听（幂等）。
     *
     * @param carHandle `Car.create(context)` 的返回值（本身就是 `ICar`）
     */
    fun attach(carHandle: Any?) {
        if (carHandle == null) return
        car = carHandle
        if (started) return
        val s = runCatching {
            carHandle.javaClass.getMethod("getSensorManager").invoke(carHandle)
        }.getOrElse {
            lastError = "getSensorManager(): ${it.javaClass.simpleName}: ${it.message}"
            // 排障场景：非 ECARX 车机上必然没有这个类，要能一眼看出「为什么读不到续航」。
            Logx.w(LogTags.SIGNAL, "ISensor 句柄不可用", it, "reason" to lastError)
            return
        }
        if (s == null) {
            lastError = "getSensorManager() 返回 null"
            Logx.w(LogTags.SIGNAL, "ISensor 句柄不可用", "reason" to lastError)
            return
        }
        sensor = s
        started = true
        Logx.i(LogTags.SIGNAL, "ISensor 句柄就绪", "class" to s.javaClass.name)
        registerListener()
        // 先把当前值拉一遍（车机刚起来时可能不会再推变化）
        probeLatest()
        // —— 续航要走「探针 + 重试」
        // 车端的续航值不一定在开机就有（实测油量传感器开机是 0.0，真实值几分钟后才推来），
        // 只探一次必然读不到。
        probeRangeSources("attach")
    }

    /** 断开车机句柄（切通道时调用）。 */
    fun detach() {
        val s = sensor
        val l = listener
        if (s != null && l != null) {
            runCatching {
                s.javaClass.methods.firstOrNull {
                    it.name == "unregisterListener" && it.parameterCount == 1
                }?.invoke(s, l)
            }
        }
        started = false
        car = null
        sensor = null
        listener = null
        registeredIds.clear()
        rangeKm.set(-1)
        fuelPct.set(-1)
        ambientC.set(Int.MIN_VALUE)
        lastFuelRaw = Float.NaN
        fuelCapacityCache = 0f
        fuelCapacityAt = 0L
        fuelCapacityWarnAt = 0L
        rangeRetryCount = 0
        main.removeCallbacks(rangeRetry)
        main.removeCallbacks(rangeSlowRetry)
    }

    /**
     * 注册传感器监听。
     *
     * `ISensor.ISensorListener` 是**接口** → 动态代理即可（不需要编译期依赖 ECARX SDK）。
     */
    private fun registerListener() {
        val s = sensor ?: return
        val register = s.javaClass.methods.firstOrNull {
            it.name == "registerListener" && it.parameterCount == 2
        }
        if (register == null) {
            lastError = "ISensor 没有 registerListener(listener, id)"
            Logx.w(LogTags.SIGNAL, "ISensor 注册接口缺失", "reason" to lastError)
            return
        }
        val listenerIface = register.parameterTypes[0]
        if (!listenerIface.isInterface) {
            lastError = "ISensor 监听不是接口，代理不了：${listenerIface.name}"
            Logx.w(LogTags.SIGNAL, "ISensor 监听不是接口", "reason" to lastError)
            return
        }
        val proxy = Proxy.newProxyInstance(listenerIface.classLoader, arrayOf(listenerIface)) { _, method, args ->
            when (method.name) {
                "onSensorValueChanged" -> {
                    val id = args?.getOrNull(0) as? Int ?: 0
                    val v = args?.getOrNull(1) as? Float ?: Float.NaN
                    dispatch(id, v)
                }
                "onSensorEventChanged", "onSensorSupportChanged" -> {
                    // 支持状态变了 / 事件变了 → 重新拉一次最新值
                    val id = args?.getOrNull(0) as? Int ?: return@newProxyInstance null
                    probeOne(id)
                }
            }
            null
        }
        listener = proxy
        // 油量 + 续航。
        // 环温（1051392）**不在这个列表里**：SDK 里 `getSensorLatestValue` 没有它的 case
        // （恒 -1.0f）、`registerListener` 也不让它注册，注册它只会多一条
        // 「注册失败」的日志，却拿不到任何环温 —— 理由见 [SENSOR_TEMP_AMBIENT]。
        for (id in intArrayOf(SENSOR_FUEL_LEVEL) + RANGE_SENSORS) {
            val ok = runCatching {
                register.invoke(s, proxy, id) as? Boolean ?: true
            }.getOrElse {
                Logx.w(LogTags.SIGNAL, "注册传感器监听失败", it, "id" to id)
                false
            }
            if (ok) registeredIds.add(id)
            // 排障场景：续航/油量读不到时，要能看出「这个传感器 id 注册成功没有」。
            Logx.i(
                LogTags.SIGNAL,
                "注册传感器监听",
                "id" to id,
                "name" to sensorName(id),
                "ok" to ok,
            )
        }
    }

    /** 主动拉一次所有传感器的最新值（启动时/订阅状态变化后）。 */
    private fun probeLatest() {
        for (id in registeredIds) probeOne(id)
    }

    /**
     * **续航探针 + 重试**。
     *
     * 实现是
     * `
     * for (int id: RANGE_SENSORS) {
     *     float v = sensor.getSensorLatestValue(id);
     *     Log.i(TAG, "range probe id=" + id + " raw=" + v + " support=" + supportLabel(id));
     *     if (publishRangeKm("sensor." + id, v)) return;
     * }
     *
     * if (lastRangeKm <= 0 && rangeRetryCount < 12) { rangeRetryCount++; postDelayed(rangeRetry, 2500); }
     * `
     *
     * 之前我们只在 时探一次 —— 而这台车机上 `ISensor` 的值是
     * **车端有变化才推**（油量传感器开机读到 0.0，真实值 5 分钟后才推来），
     * 所以只探一次必然读不到续航（诊断包里表现为「注册传感器监听 ok=true」
     * 但「续航变为不可读」。
     *
     * 这里按补上：12 次 × 2.5s 的快速重试；拿到值之后仍保留
     * [RANGE_SLOW_RETRY_MS] 的慢速复查（DEBUG 级日志，不刷盘）。
     */
    private fun probeRangeSources(reason: String) {
        val s = sensor ?: return
        var got = false
        for (id in RANGE_SENSORS) {
            val raw = readLatest(s, id) ?: continue
            if (reason == "slow") {
                Logx.d(
                    LogTags.SIGNAL,
                    "续航慢速探针",
                    "id" to id,
                    "name" to sensorName(id),
                    "raw" to raw,
                    "support" to supportLabel(s, id),
                )
            } else {
                Logx.i(
                    LogTags.SIGNAL,
                    "续航探针",
                    "id" to id,
                    "name" to sensorName(id),
                    "raw" to raw,
                    "support" to supportLabel(s, id),
                    "reason" to reason,
                )
            }
            publishRangeKm("sensor.$id", raw)
            if (rangeKm.get() > 0) got = true
        }
        // 第六十四轮：**油量也按同一个节奏复查**。
        //
        // 之前油量只在挂上传感器的那一刻探一次，而车端的油量值是
        // 「开机先推 0.0 占位、约 5 分钟后才推真实值」——
        // 只探一次必然读到占位值；推送漏掉一次就再也拿不到了。
        // 这里每次探针都顺带把油量重读一遍（读不到就跳过，不影响续航）。
        probeFuel(reason)
        if (got || rangeKm.get() > 0) {
            // 已经有续航了 → 停掉快速重试，转成慢速复查
            main.removeCallbacks(rangeRetry)
            main.removeCallbacks(rangeSlowRetry)
            main.postDelayed(rangeSlowRetry, RANGE_SLOW_RETRY_MS)
            return
        }
        // 还没有续航 → 按节奏重试（12 次 × 2.5s）
        if (rangeRetryCount < RANGE_RETRY_MAX) {
            rangeRetryCount++
            main.removeCallbacks(rangeRetry)
            main.postDelayed(rangeRetry, RANGE_RETRY_MS)
        } else {
            // 快速窗口用完了 → 转入慢速复查
            main.removeCallbacks(rangeRetry)
            main.removeCallbacks(rangeSlowRetry)
            main.postDelayed(rangeSlowRetry, RANGE_SLOW_RETRY_MS)
        }
    }

    /** 读一个传感器的最新值（读不到返回 null）。 */
    private fun readLatest(s: Any, id: Int): Float? = runCatching {
        s.javaClass.methods.firstOrNull {
            it.name == "getSensorLatestValue" && it.parameterCount == 1
        }?.invoke(s, id) as? Float
    }.getOrNull()

    /**
     * 传感器支持状态。
     *
     * 只用于日志：让「传感器不支持」和「支持但暂时没值」一眼可分。
     */
    private fun supportLabel(s: Any, id: Int): String = runCatching {
        val m = s.javaClass.methods.firstOrNull {
            it.name == "isSensorSupported" && it.parameterCount == 1
        } ?: return@runCatching "err"
        val st = m.invoke(s, id) ?: return@runCatching "null"
        st.javaClass.getMethod("name").invoke(st)?.toString() ?: "unknown"
    }.getOrElse { "err" }

    private fun probeOne(id: Int) {
        val s = sensor ?: return
        val v = readLatest(s, id) ?: return
        Logx.d(LogTags.SIGNAL, "读取传感器最新值", "id" to id, "name" to sensorName(id), "raw" to v)
        dispatch(id, v)
    }

    // ------------------------------------------------------------ 取值分发

    private fun dispatch(id: Int, raw: Float) {
        if (raw.isNaN() || raw == Float.MIN_VALUE) return
        when (id) {
            SENSOR_FUEL_LEVEL -> {
                // 第六十四轮：`raw <= 0` 是车端的**占位值**，不是「油箱空了」。
                // 实测：开机 / 车机服务重连时推 0.0，约 5 分钟后才推真实值（21000.0）。
                // 之前把占位值直接发布成「0%」，于是桌面显示一个假的 0%，
                // 真值再晚到也只会被占位值盖住 —— 现在占位值一律忽略。
                if (raw <= 0f) {
                    Logx.d(LogTags.SIGNAL, "油量占位值（忽略）", "raw" to raw)
                    return
                }
                lastFuelRaw = raw
                publishFuel(raw)
            }
            // 只有续航这一路是真的实现了取值（`SENSOR_ENDURANCE_KM` = 1050624）。
            // 环温没有分支：SDK 里那个传感器没实现（恒 -1.0f），
            // 留着分支只会把「没实现」当成「环温变了」发出去 —— 见 [SENSOR_TEMP_AMBIENT]。
            SENSOR_ENDURANCE_KM -> publishRangeKm("sensor.$id", raw)
        }
    }

    /**
     * 油量探针（跟着续航探针的节奏跑）。
     *
     * 读到的值照样走 [dispatch] —— 占位值会被忽略，
     * 真实值会换算成百分比并发布。
     */
    private fun probeFuel(reason: String) {
        val s = sensor ?: return
        val raw = readLatest(s, SENSOR_FUEL_LEVEL)
        if (raw == null) {
            // 读不到最新值，但之前拿到过原始值 → 用它再换算一次
            // （场景：真实值早到、当时油箱容量还没读到）
            if (!lastFuelRaw.isNaN()) publishFuel(lastFuelRaw)
            return
        }
        if (reason == "slow") {
            Logx.d(
                LogTags.SIGNAL,
                "油量慢速探针",
                "raw" to raw,
                "support" to supportLabel(s, SENSOR_FUEL_LEVEL),
            )
        } else {
            Logx.i(
                LogTags.SIGNAL,
                "油量探针",
                "raw" to raw,
                "support" to supportLabel(s, SENSOR_FUEL_LEVEL),
                "reason" to reason,
            )
        }
        dispatch(SENSOR_FUEL_LEVEL, raw)
    }

    /**
     * 发布油量百分比。
     *
     * 换不出来时**只记日志、不发布** —— 下一次探针会带着同一个原始值再试一次，
     * 等油箱容量读到了就自动补上（这正是「真实值早到、容量晚到」的情况）。
     */
    private fun publishFuel(raw: Float) {
        val cap = fuelCapacity()
        val pct = CarSignalMath.fuelPercentFromSensor(raw, cap)
        if (pct == null) {
            Logx.w(
                LogTags.SIGNAL,
                "油量无法换算成百分比",
                "raw" to raw,
                "capacity" to cap,
                "hint" to if (cap <= 0f) "油箱容量还没读到" else "原始值超出已知口径",
            )
            return
        }
        val old = fuelPct.getAndSet(pct)
        if (old != pct) {
            Logx.i(LogTags.SIGNAL, "油量变化", "from" to old, "to" to pct, "raw" to raw, "capacity" to cap)
        }
    }

    /**
     * 续航发布。
     *
     * `raw <= 3000` 直接当 km，否则除以 1000；结果必须落在 `1..2999`。
     */
    private fun publishRangeKm(source: String, raw: Float) {
        val km = CarSignalMath.rangeKm(raw) ?: return
        val old = rangeKm.getAndSet(km)
        if (old == km) return
        Logx.i(LogTags.SIGNAL, "续航变化", "from" to old, "to" to km, "source" to source, "raw" to raw)
        // 拿到续航了 → 停掉快速重试，转慢速复查
        main.removeCallbacks(rangeRetry)
        main.removeCallbacks(rangeSlowRetry)
        main.postDelayed(rangeSlowRetry, RANGE_SLOW_RETRY_MS)
    }

    /**
     * 油量百分比。
     *
     * @return null 表示换算不出来
     */
    fun fuelPercent(raw: Float): Int? = CarSignalMath.fuelPercent(raw, fuelCapacity())

    /**
     * 油箱容量（`iCar.getCarInfoManager().getCarInfoFloat(2097408)`）。
     *
     * 实测这台车机上它是 **55263**（单位 0.001 L → 55.26 L），
     * 油量原始值 `21000` 换算出来就是 38%。
     *
     * 第六十四轮补了三件事：
     * 1. **不再静默失败** —— 之前读不到就返回 0，日志里一点痕迹都没有，
     *    表现成「油量永远 `--%`」，完全看不出是容量没读到；
     * 2. **多路兜底** —— `getCarInfoFloat` → `getCarInfoInt`；
     * 3. **接口优先取方法** —— 车机 SDK 的实现类常常是 package-private 的，
     *    直接 `implClass.getMethod(...).invoke(...)` 会抛 `IllegalAccessException`
     *    （方法本身是 public，但声明它的类不可访问）；从 public 接口上取到的
     *    Method 没有这个问题。
     */
    private fun fuelCapacity(): Float {
        val c = car ?: return 0f
        val now = System.currentTimeMillis()
        // 刚读到过就先用缓存（容量是车机常量，不会变）
        if (fuelCapacityCache > 0f && now - fuelCapacityAt < FUEL_CAPACITY_CACHE_MS) {
            return fuelCapacityCache
        }
        val v = runCatching { readFuelCapacity(c) }.getOrElse {
            Logx.w(LogTags.SIGNAL, "读油箱容量失败", it, "id" to INFO_FUEL_CAPACITY)
            0f
        }
        val cap = if (v == Float.MIN_VALUE) 0f else v
        val prev = fuelCapacityCache
        fuelCapacityCache = cap
        fuelCapacityAt = now
        if (cap <= 0f) {
            // 限频：容量读不到时会每 30s 探一次，不能每次都刷一条 WARN
            if (now - fuelCapacityWarnAt > FUEL_CAPACITY_WARN_MS) {
                fuelCapacityWarnAt = now
                Logx.w(
                    LogTags.SIGNAL,
                    "油箱容量读不到",
                    "id" to INFO_FUEL_CAPACITY,
                    "hint" to "油量换算会失败，等车机服务就绪后重试",
                )
            }
        } else if (prev != cap) {
            Logx.i(LogTags.SIGNAL, "油箱容量", "value" to cap)
        }
        return cap
    }

    /** 读油箱容量的实际动作（float 优先，int 兜底）。 */
    private fun readFuelCapacity(c: Any): Float {
        val info = carInfoManager(c) ?: return 0f
        findAccessibleMethod(info, "getCarInfoFloat", INT_CLASS)?.let { m ->
            val v = runCatching { m.invoke(info, INFO_FUEL_CAPACITY) }.getOrNull()
            if (v is Number) return v.toFloat()
        }
        findAccessibleMethod(info, "getCarInfoInt", INT_CLASS)?.let { m ->
            val v = runCatching { m.invoke(info, INFO_FUEL_CAPACITY) }.getOrNull()
            if (v is Number) return v.toFloat()
        }
        return 0f
    }

    /** `iCar.getCarInfoManager()`。 */
    private fun carInfoManager(c: Any): Any? {
        val m = findAccessibleMethod(c, "getCarInfoManager") ?: return null
        return runCatching { m.invoke(c) }.getOrNull()
    }

    /**
     * 在对象上找方法，**优先用接口上的方法对象**。
     *
     * 车机 SDK 的实现类常常是 package-private 的，
     * 直接用实现类的 Method 去 invoke 会抛 `IllegalAccessException`；
     * 从 public 接口上取到的 Method 就能正常调用。
     */
    private fun findAccessibleMethod(obj: Any, name: String, vararg params: Class<*>): Method? {
        for (i in allInterfaces(obj.javaClass)) {
            runCatching { i.getMethod(name, *params) }.getOrNull()?.let { return it }
        }
        return runCatching { obj.javaClass.getMethod(name, *params) }.getOrNull()
    }

    /** 递归收集一个类实现的所有接口（含父接口）。 */
    private fun allInterfaces(clazz: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        clazz.interfaces.forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (out.contains(i)) continue
            out.add(i)
            i.interfaces.forEach { queue.addLast(it) }
        }
        return out
    }

    // ------------------------------------------------------------ 对外取值

    /** 剩余续航（km）；-1 = 不可读。 */
    fun rangeKm(): Int = rangeKm.get()

    /** 油量百分比；-1 = 不可读。 */
    fun fuelPercent(): Int = fuelPct.get()

    /** 最近一次拿到的油量原始值；[Float.NaN] = 还没拿到过。 */
    fun lastFuelRaw(): Float = lastFuelRaw

    /** 最近一次读到的油箱容量；0 = 没读到。 */
    fun fuelCapacityValue(): Float = fuelCapacity()

    /**
     * 环温（℃）；[Int.MIN_VALUE] = 不可读。
     *
     * **恒为 [Int.MIN_VALUE]（不可读）** —— `ISensor` 上的环温传感器在 SDK 里
     * 没有实现，不能把它当环温来源；正确的路径是属性通道
     * `ENV_OUTSIDE_TEMPERATURE = 291505923`（`VehicleIds.ENV_OUTSIDE_TEMPERATURE`），
     * 本次不接。
     */
    fun ambientTempC(): Int = ambientC.get()

    /** 是否已经挂上 ISensor。 */
    fun isAvailable(): Boolean = sensor != null

    /** 最近一次失败原因。 */
    fun lastError(): String? = lastError

    /** 传感器 id → 名字（日志用）。 */
    private fun sensorName(id: Int): String = when (id) {
        SENSOR_FUEL_LEVEL -> "油量"
        SENSOR_ENDURANCE_KM -> "续航km"
        SENSOR_POTENTIAL_ENDURANCE -> "潜在续航"
        SENSOR_DISCHARGE_ENDURANCE -> "放电续航"
        SENSOR_CHARGE_ENDURANCE -> "充电续航"
        SENSOR_TEMP_AMBIENT -> "环温"
        else -> "未知"
    }
}
