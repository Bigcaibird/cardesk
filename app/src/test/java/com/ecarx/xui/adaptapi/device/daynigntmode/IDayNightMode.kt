package com.ecarx.xui.adaptapi.device.daynigntmode

/**
 * 车机 AdaptAPI 昼夜接口的**测试替身**。
 *
 * 真实实现只存在于车机 `/system` 分区（`com.ecarx.xui.adaptapi.*`），
 * 单测跑在普通 JVM 上，所以这里按**同名同签名**造一份假的，
 * 让 `AdaptapiDayNight` 的反射路径（类名 / 方法名 / 动态代理）能被真实验证。
 *
 * 真实接口出处：`work/adapterapi-jar/sources/com/ecarx/xui/adaptapi/device/daynigntmode/IDayNightMode.java`
 * ——方法名 `unregisterDayNigntModeCallBack` 的拼写（nignt）是厂商原样，保留。
 */
interface IDayNightMode {

    /** 当前实际明暗：`1` 白天 / `2` 夜间 / `0` 读不到。 */
    fun getDayNight(): Int

    /** 模式：`1` 固定白天 / `2` 固定夜间 / `3` 跟随。 */
    fun getDayNightMode(): Int

    fun registerDayNightModeCallBack(callBack: IDayNightChangeCallBack): Boolean

    fun unregisterDayNigntModeCallBack(callBack: IDayNightChangeCallBack): Boolean

    /** 车机明暗变化回调。 */
    interface IDayNightChangeCallBack {
        /** 显示态变化：`1` 白天 / `2` 夜间。 */
        fun onDayNightChanged(display: Int)

        /** 模式变化：`1` 白天 / `2` 夜间 / `3` 跟随。 */
        fun onDayNightModeChange(mode: Int)
    }
}
