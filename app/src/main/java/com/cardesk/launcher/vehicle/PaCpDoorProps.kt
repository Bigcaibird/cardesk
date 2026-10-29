package com.cardesk.launcher.vehicle

/**
 * 车机端 `GeelyVehicleProperty` 里的**车门状态属性**（`PA_CP_*`）。
 *
 * ## 来源
 *
 * 车机端 `ecarx.adapterapi.jar`（车机系统分区里的 DEX）里，
 * `vendor.cevt.hardware.automotive.vehicle.geely.V2_0.GeelyVehicleProperty`
 * 定义了这一组常量：
 *
 * | 常量 | id | 十六进制 | 车辆页上的位置 |
 * | --- | --- | --- | --- |
 * | `PA_CP_DoorDrvrSts` | `560993300` | `0x21701414` | 主驾门（左前） |
 * | `PA_CP_DoorDrvrReSts` | `560993301` | `0x21701415` | 左后门（驾驶员侧后门） |
 * | `PA_CP_DoorPassSts` | `560993302` | `0x21701416` | 副驾门（右前） |
 * | `PA_CP_DoorPassReSts` | `560993303` | `0x21701417` | 右后门（乘客侧后门） |
 * | `PA_CP_TrSts` | `560993304` | `0x21701418` | 尾门 / 后备箱 |
 *
 * ## 这是**第四条**车门来源（前三档都走不通）
 *
 * | 档 | 来源 | 这台车机上 |
 * | --- | --- | --- |
 * | 1 | `car_signal` 事件 | 读不到 |
 * | 2 | `IDoorAndWindow` | 这台车机**没有这个类** |
 * | 3 | `BCM_DOOR_POS`（AdaptAPI 整型通道） | 厂商功能号分派表里**没有这一项**，恒返回 `255` |
 * | 4 | **`PA_CP_*` 属性** | 车机 `car_service` 清单里**标为只读，存在** |
 *
 * 也就是说：侧门与尾门的状态**是有的**，只是前三条路都走不通。
 *
 * ## 读法
 *
 * 走 `car_service` 的属性通道（`android.car.hardware.property.CarPropertyManager`），
 * 按「属性 id + 区域号」读；区域号从属性清单里查（全局属性用 `0`）。
 *
 * ## 值的口径
 *
 * `0` = 关、`1` = 开。依据是**同一个 jar** 里的 BCM 门值常量
 * `IBcm.DOOR_CLOSE = 0` / `IBcm.DOOR_OPEN = 1`
 * （车窗同理：`IBcm.WINDOW_CLOSE = 0` / `WINDOW_OPEN = 1`）。
 *
 * 真机还没验证过，所以每次读到都会把**原始值**打进日志
 * （见 [McDoorSignals]）—— 万一口径反了，看一眼日志就能定位，
 * 改动只在 [McDoorSignals.interpret] 一处。
 */
object PaCpDoorProps {

    /** `PA_CP_DoorDrvrSts` —— 主驾门（左前）。 */
    const val DOOR_DRIVER = 560993300

    /** `PA_CP_DoorDrvrReSts` —— 左后门（驾驶员侧后门）。 */
    const val DOOR_DRIVER_REAR = 560993301

    /** `PA_CP_DoorPassSts` —— 副驾门（右前）。 */
    const val DOOR_PASSENGER = 560993302

    /** `PA_CP_DoorPassReSts` —— 右后门（乘客侧后门）。 */
    const val DOOR_PASSENGER_REAR = 560993303

    /** `PA_CP_TrSts` —— 尾门 / 后备箱。 */
    const val TAILGATE = 560993304

    /** 全部五个属性（只用于「车机支持哪些属性」的对照）。 */
    val ALL = intArrayOf(DOOR_DRIVER, DOOR_DRIVER_REAR, DOOR_PASSENGER, DOOR_PASSENGER_REAR, TAILGATE)

    /**
     * 四门的属性 id，顺序同我们全局的 **FL / FR / RL / RR**。
     *
     * 注意 id 的自然顺序是 FL / BL / FR / BR，这里按车辆页的顺序重排。
     */
    val DOORS_IN_FL_FR_RL_RR = intArrayOf(DOOR_DRIVER, DOOR_PASSENGER, DOOR_DRIVER_REAR, DOOR_PASSENGER_REAR)

    /** 属性名（只用于日志；顺序同 [ALL]）。 */
    private val NAMES = arrayOf("主驾门", "左后门", "副驾门", "右后门", "尾门")

    /** 属性 id → 名字（只用于日志）。 */
    fun nameOf(propId: Int): String {
        val i = ALL.indexOf(propId)
        return if (i >= 0) NAMES[i] else "0x%08X".format(propId)
    }
}
