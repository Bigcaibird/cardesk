package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.VehicleStats
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车身级信号（门 / 窗 / 总里程）——走 `CarPropertyManager` 通道。
 *
 * ## 为什么要有这一层
 *
 * 第三十六轮核对《车辆运行数据 API 对照表》时发现：「`car_service` 属性通道」
 * 其实就是 AOSP 的 **`CarPropertyManager`**
 * 里面同时挂着标准属性和 ECARX 厂商扩展属性：
 *
 * | 量 | 属性 | id | 位段 |
 * | --- | --- | --- | --- |
 * | 车窗位置 | `WINDOW_POS` | `0x13400BC0` | AOSP 标准 |
 * | 车门位置 | `DOOR_POS` | `0x16400B00` | AOSP 标准 |
 * | 总里程 | `PERF_ODOMETER` | `291504644` | ECARX 扩展 |
 * | 总里程（兜底） | `ODOMETER` | `0x01010201` | AOSP 标准 |
 *
 * 这条通道正好补上我们两个**真实缺口**：
 *
 * 1. **车窗从来没有回读来源**——桌面上的「车窗关/开」一直是本地乐观值，
 *    车外用钥匙开窗、或者车机拒绝关窗，桌面都不会变；
 * 2. **车门只有 `BCM_DOOR_POS` 一条路**——降级链还有 `DOOR_POS` 这一档（我们没接）。
 *
 * ## 缓存
 *
 * 桌面每秒刷新一次（[com.cardesk.launcher.ui.LauncherView] 的 ticker），
 * 而每次读取都是反射调用，所以这里做两级缓存：
 *
 * - 门 / 窗：缓存 [CACHE_MS] 毫秒（窗口状态最多滞后 1 秒，与轮询节奏一致）；
 * - 总里程：缓存 [ODOMETER_CACHE_MS]（几乎不变，没必要每秒读）。
 */
object CarBodySignals {

    /** 门 / 窗缓存时长（毫秒）。 */
    const val CACHE_MS = 900L

    /** 总里程缓存时长（毫秒）。 */
    const val ODOMETER_CACHE_MS = 5_000L

    private var attached = false

    // 门 / 窗缓存
    private var windowCache: List<Boolean?>? = null
    private var windowCacheAt = 0L
    private var doorCache: List<Boolean?>? = null
    private var doorCacheAt = 0L

    // 引擎盖 / 后备箱缓存
    private var hoodTrunkCache: Pair<Boolean?, Boolean?>? = null
    private var hoodTrunkCacheAt = 0L

    // 里程缓存
    private var odometerCache: Int? = null
    private var odometerCacheAt = 0L

    /** 区域号缓存（属性 id → 可用区域）。区域表不会变，查一次就够。 */
    private val areaCache = HashMap<Int, IntArray>()

    // ------------------------------------------------------------ 连接

    /**
     * 挂上 `CarPropertyManager` 通道（幂等）。
     *
     * 第三十七轮起 [CarProperties.attach] 改成「B 路同步 + A 路后台」，
     * 所以这里每次都问一次通道状态，不能用本地 `attached` 标志缓存。
     *
     * @return 通道此刻是否可用
     */
    fun attach(context: Context): Boolean {
        val ctx = context.applicationContext ?: context
        attached = CarProperties.attach(ctx)
        return attached
    }

    /** 通道是否可用。 */
    fun isAvailable(): Boolean = CarProperties.isAvailable()

    /** 清缓存（换车 / 通道重建时调）。 */
    fun invalidate() {
        windowCache = null
        windowCacheAt = 0L
        doorCache = null
        doorCacheAt = 0L
        hoodTrunkCache = null
        hoodTrunkCacheAt = 0L
        odometerCache = null
        odometerCacheAt = 0L
    }

    // ------------------------------------------------------------ 车窗

    /**
     * 四扇车窗的开闭（**true = 开**），顺序 FL/FR/RL/RR；读不到返回 null。
     *
     * 走 `WINDOW_POS` 属性；区域号先翻译成角色（见 [VehicleStats.areaRoles]），
     * 拿不到再退回 AOSP 的固定顺序（`0/1/2/3`）。
     *
     * 注意：**读不到的那一扇在 [readWindowsEach] 里是 null**，
     * 这里转成布尔数组时按 `false`（关）处理 —— UI 的协议是布尔数组。
     */
    fun readWindows(): BooleanArray? =
        readWindowsEach()?.map { it == true }?.toBooleanArray()

    /**
     * 四扇车窗的开闭（**true = 开**），**逐扇独立**；每扇读不到时那一项是 null。
     *
     * 走 `WINDOW_POS` 属性；区域号先翻译成角色（见 [VehicleStats.areaRoles]），
     * 拿不到再退回 AOSP 的固定顺序（`0/1/2/3`）。
     */
    fun readWindowsEach(): List<Boolean?>? {
        val now = System.currentTimeMillis()
        windowCache?.let { if (now - windowCacheAt < CACHE_MS) return it }
        // 属性号：这台车机上 AOSP 标准号与 ECARX 自己那份不一样，
        // 拿属性清单核对一下，用真正注册的那个（见 [CarProperties.resolvePropertyId]）。
        val propId = CarProperties.resolvePropertyId(VehicleIds.WINDOW_POS_CANDIDATES)
        val roles = VehicleStats.areaRoles(
            areasOf(propId, VehicleIds.WINDOW_AREAS),
            VehicleStats.WINDOW_AREA_MASKS,
        )
        val values = DoubleArray(4) { Double.NaN }
        var any = false
        for (i in 0 until 4) {
            val area = roles[i]
            if (area < 0) continue
            val v = CarProperties.readNumber(propId, area)
            if (v != null) {
                values[i] = v
                any = true
            }
        }
        if (!any) {
            Logx.d(LogTags.SIGNAL, "车窗位置读不到", "prop" to "0x%08X".format(propId))
            return null
        }
        val flags = VehicleStats.windowOpenFlagsEach(values) ?: return null
        windowCache = flags
        windowCacheAt = now
        Logx.d(
            LogTags.SIGNAL,
            "车窗位置",
            "values" to values.joinToString(","),
            "open" to flags.joinToString(",") { c -> c?.let { if (it) "开" else "关" } ?: "?" },
        )
        return flags
    }

    // ------------------------------------------------------------ 车门

    /**
     * 车门位置（**true = 关**），顺序 FL/FR/RL/RR。
     *
     * 走 `DOOR_POS` 属性，是降级链里的**第三档**
     * `BCM_DOOR_POS`（`ICarFunction`）→ **`DOOR_POS`（属性）**。
     *
     * ## 逐门独立（第四十五轮）
     *
     * 之前是「四扇门**全都**读得到才算数」，少一个就整组作废 ——
     * 一台车只注册了三个门位区域时，连另外三扇门的正确状态一起丢掉。
     * 现在**每一扇独立**：读不到的那一扇是 null，其余照常。
     * （口径与另一个 一致：五个属性各自维护，谁变了就整包发布。）
     *
     * @return 每项 null = 这一扇读不到；整组 null = 一扇都读不到
     */
    fun readDoorsViaProperty(): List<Boolean?>? {
        val now = System.currentTimeMillis()
        doorCache?.let { if (now - doorCacheAt < CACHE_MS) return it }
        val propId = CarProperties.resolvePropertyId(VehicleIds.DOOR_POS_CANDIDATES)
        val roles = VehicleStats.areaRoles(
            areasOf(propId, VehicleIds.DOOR_AREAS),
            VehicleStats.DOOR_AREA_MASKS,
        )
        val out = MutableList<Boolean?>(4) { null }
        var any = false
        for (i in 0 until 4) {
            val area = roles[i]
            if (area < 0) continue
            val v = CarProperties.readNumber(propId, area)
            val closed = VehicleStats.interpretDoorClosed(v) ?: continue
            out[i] = closed
            any = true
        }
        if (!any) {
            Logx.d(LogTags.SIGNAL, "车门位置读不到", "prop" to "0x%08X".format(propId))
            return null
        }
        doorCache = out
        doorCacheAt = now
        Logx.d(
            LogTags.SIGNAL,
            "车门位置",
            "closed" to out.joinToString(",") { c -> c?.let { if (it) "关" else "开" } ?: "?" },
        )
        return out
    }

    /**
     * 引擎盖与后备箱（**true = 关**）；读不到返回 null。
     *
     * 走 `DOOR_POS` 属性里对应「引擎盖 / 后备箱」的两个区域
     * （AOSP： = 32、 = 64）。
     *
     * 车机若只注册了四个门位区域（没有盖），这里返回 null。
     */
    fun readHoodTrunkViaProperty(): Pair<Boolean?, Boolean?>? {
        val now = System.currentTimeMillis()
        hoodTrunkCache?.let { if (now - hoodTrunkCacheAt < CACHE_MS) return it }
        val propId = CarProperties.resolvePropertyId(VehicleIds.DOOR_POS_CANDIDATES)
        val roles = VehicleStats.areaRoles(
            areasOf(propId, VehicleIds.DOOR_AREAS),
            VehicleStats.DOOR_AREA_MASKS,
        )
        val hoodArea = roles.getOrNull(4)?.takeIf { it >= 0 }
        val trunkArea = roles.getOrNull(5)?.takeIf { it >= 0 }
        if (hoodArea == null && trunkArea == null) {
            Logx.d(LogTags.SIGNAL, "引擎盖/后备箱区域不可用", "areas" to areasOf(propId, VehicleIds.DOOR_AREAS).joinToString(","))
            return null
        }
        val hood = hoodArea?.let { VehicleStats.interpretDoorClosed(CarProperties.readNumber(propId, it)) }
        val trunk = trunkArea?.let { VehicleStats.interpretDoorClosed(CarProperties.readNumber(propId, it)) }
        if (hood == null && trunk == null) {
            Logx.d(LogTags.SIGNAL, "引擎盖/后备箱读不到")
            return null
        }
        val pair = hood to trunk
        hoodTrunkCache = pair
        hoodTrunkCacheAt = now
        Logx.d(LogTags.SIGNAL, "引擎盖/后备箱", "hood" to hood, "trunk" to trunk)
        return pair
    }

    // ------------------------------------------------------------ 总里程

    /**
     * 总里程（公里）；读不到返回 null。
     *
     * 先读 ECARX 厂商扩展属性 `PERF_ODOMETER`，读不到再退到 AOSP 标准 `ODOMETER`。
     *
     * ## 第七十五轮：这条退成兜底
     *
     * 这条属性通道在这台车机上**从来没读到过值** —— 桌面行程卡的「总里程」一直是 `--`。
     * 现在第一顺位是 AdaptAPI `ISensor` 的 `ODOMETER`（1050368，单位确定是 km），
     * 见 `AdaptapiSensors.odometerKm()`；这条留着当兜底 —— 万一将来在别的车型上能用。
     *
     * 另外这条的属性单位**米/公里不明**，所以走的是 `VehicleStats.odometerKm`
     * 那套「大于 10 万就当米」的猜测换算；`ISensor` 那条不需要猜。
     */
    fun readOdometerKm(): Int? {
        val now = System.currentTimeMillis()
        odometerCache?.let { if (now - odometerCacheAt < ODOMETER_CACHE_MS) return it }
        var km = VehicleStats.odometerKm(CarProperties.readNumber(VehicleIds.PERF_ODOMETER, 0))
        if (km == null) {
            km = VehicleStats.odometerKm(CarProperties.readNumber(VehicleIds.ODOMETER, 0))
        }
        if (km == null) {
            Logx.d(LogTags.SIGNAL, "总里程读不到")
            return null
        }
        odometerCache = km
        odometerCacheAt = now
        Logx.i(LogTags.SIGNAL, "总里程", "km" to km)
        return km
    }

    // ------------------------------------------------------------ 区域

    /**
     * 取一个属性的可用区域号。
     *
     * 优先用属性自己报的 （就是这么用的
     * `int[] areas`，拿不到再退回固定顺序。
     *
     * 这里**保留全部区域**（不再截断到前 4 个）——引擎盖 / 后备箱要用到第 5、6 个。
     * 区域号 → 角色（下标）的翻译交给 [VehicleStats.areaRoles]，
     * 因为这台车可能是位掩码、也可能是顺序索引。
     */
    private fun areasOf(propId: Int, fallback: IntArray): IntArray {
        areaCache[propId]?.let { return it }
        val discovered = CarProperties.availableAreas(propId)
        val areas = if (discovered == null || discovered.isEmpty()) fallback else discovered
        areaCache[propId] = areas
        Logx.d(LogTags.SIGNAL, "属性区域", "prop" to propId, "areas" to areas.joinToString(","))
        return areas
    }
}
