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
     * - `(1, 100]` → `<= 5` 关闭，否则打开（车端用 0..100 表示开度）；
     * - 其它 → 读不出来（null）。
     *
     * @return null 表示这个原始值不是有效的开闭状态
     */
    fun interpretClosed(raw: Int): Boolean? {
        if (raw < 0) return null
        if (raw == 0) return true
        if (raw == 1) return false
        if (raw == 553844994 || raw == 553844993 || raw == 553779457) return false
        if (raw <= 1 || raw > 100) return null
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
     */
    fun isUnsupportedRaw(raw: Int): Boolean =
        raw == 255 || raw == -1 || raw == Int.MIN_VALUE

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
     */
    fun interpretWindowPosition(raw: Int): Boolean? = when {
        raw < 0 -> null
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
