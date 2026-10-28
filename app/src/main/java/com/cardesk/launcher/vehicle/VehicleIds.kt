package com.cardesk.launcher.vehicle

/**
 * 车身属性 id 表（`android.car` 的 `CarPropertyManager` 通道）。
 *
 * ## 这条通道是什么
 *
 * 「`car_service` 属性通道」其实就是 AOSP 的 **`CarPropertyManager`**
 * 走的是
 * `android.car.Car` → `getCarManager("property")` → `CarPropertyManager`，
 * 属性 id 与标准 `VehiclePropertyIds` 一致，只是车机上还**额外注册了厂商扩展属性**。
 *
 * 第三十六轮核对《车辆运行数据 API 对照表》时确认：文档只写了 `com.android.car` 的
 * `CarPropertyManager`，却完全没提实际用的这条属性通道——
 * 而这正是「车门/车窗/总里程」这些量的真实来源。
 *
 * ## 两种 id 混在一张表里
 *
 * | 位段 | 来源 | 例子 |
 * | --- | --- | --- |
 * | `0x01x` / `0x13x` / `0x15x` / `0x16x` | **AOSP 标准** `VehiclePropertyIds` | `WINDOW_POS` `0x13400BC0` |
 * | `0x11x` | **ECARX 厂商扩展** | `PERF_ODOMETER` `0x11600204` |
 *
 * AOSP 的属性 id 可以用 `android.car.VehiclePropertyIds` 反射取到；
 * **厂商扩展属性没有常量名，只能按数值用**（`CarPropertyManager.getProperty(id, area)`
 * 对厂商 id 一样有效——它底层就是同一个 PropertyManager 服务。
 */
object VehicleIds {

    // ================================================================ 车门 / 车窗

    /**
     * 车窗位置（**AOSP 标准** `VehiclePropertyIds.WINDOW_POS`）。
     *
     * 值域 `0.0`（全关）– `1.0`（全开）；ECARX 原生语义是百分比（`0`–`100`），
     * 两种都兼容，见 [com.cardesk.launcher.core.VehicleStats.windowOpenFlags]。
     */
    const val WINDOW_POS = 0x13400BC0

    /**
     * 车门位置（**AOSP 标准** `VehiclePropertyIds.DOOR_POS`）。
     *
     * 值：`0` = 关、`1` = 开、`2` = 关闭中、`3` = 开启中。
     */
    const val DOOR_POS = 0x16400B00

    /** 车窗锁（`WINDOW_LOCK`）。 */
    const val WINDOW_LOCK = 0x13400BC1

    /** 门锁（`DOOR_LOCK`）。 */
    const val DOOR_LOCK = 0x16400B01

    // ================================================================ 里程 / 续航 / 能源

    /** 总里程（**ECARX 厂商扩展** `PERF_ODOMETER`）；单位米。 */
    const val PERF_ODOMETER = 291504644

    /** 总里程（AOSP 标准 `ODOMETER`）；单位米。作为厂商扩展读不到时的兜底。 */
    const val ODOMETER = 0x01010201

    /** 剩余续航（**ECARX 厂商扩展** `RANGE_REMAINING`）。 */
    const val RANGE_REMAINING = 291504904

    /** 燃油液位（**ECARX 厂商扩展** `FUEL_LEVEL`）。 */
    const val FUEL_LEVEL = 291504903

    /** 动力电池电量（**ECARX 厂商扩展** `EV_BATTERY_LEVEL`）。 */
    const val EV_BATTERY_LEVEL = 291504905

    // ================================================================ 行驶 / 温度

    /** 实时车速（**ECARX 厂商扩展** `PERF_VEHICLE_SPEED`）；单位 km/h。 */
    const val PERF_VEHICLE_SPEED = 291504647

    /** 车外环境温度（**ECARX 厂商扩展** `ENV_OUTSIDE_TEMPERATURE`）；单位 ℃。 */
    const val ENV_OUTSIDE_TEMPERATURE = 291505923

    /** 当前档位（**ECARX 厂商扩展** `CURRENT_GEAR`）。 */
    const val CURRENT_GEAR = 289408001

    // ================================================================ 区域（areaId）

    /**
     * 车窗区域号。
     *
     * AOSP：`0` = 左前、`1` = 右前、`2` = 左后、`3` = 右后。
     * ECARX 的 area 号不一定照抄 AOSP，所以实际使用时**以 读回来的为准**
     * （见 [CarProperties.availableAreas]），这里只是兜底顺序。
     */
    val WINDOW_AREAS = intArrayOf(0, 1, 2, 3)

    /**
     * 车门区域号（**兜底顺序**）。
     *
     * AOSP 是**位掩码**（左前 2 / 右前 4 / 左后 8 / 右后 16 /
     * 引擎盖 32 / 后备箱 64，但不少厂商 HAL 用顺序索引（`0..5`）。
     * 这里放**索引口径**作为读不到 时的兜底，
     * 实际语义由 [com.cardesk.launcher.core.VehicleStats.areaRoles] 判定。
     */
    val DOOR_AREAS = intArrayOf(0, 1, 2, 3, 4, 5)

    /** 四个门位（主驾/副驾/左后/右后）的名称，顺序同 [AdaptapiBody.DOOR_ZONES]。 */
    val DOOR_LABELS = arrayOf("左前门", "右前门", "左后门", "右后门")

    /** 四个窗位的名称，顺序 FL/FR/RL/RR。 */
    val WINDOW_LABELS = arrayOf("左前窗", "右前窗", "左后窗", "右后窗")
}
