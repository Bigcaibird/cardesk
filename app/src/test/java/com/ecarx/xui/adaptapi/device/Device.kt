package com.ecarx.xui.adaptapi.device

import android.content.Context
import com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode

/**
 * 车机 AdaptAPI `Device` 的**测试替身**。
 *
 * 只为让 `AdaptapiDayNight` 的反射路径（`Device.create(context)` → `getDayNightMode()`）
 * 在普通 JVM 上能被验证；真实类只存在于车机 `/system` 分区。
 */
class Device {

    companion object {
        @JvmStatic
        fun create(context: Context): Device = Device()
    }

    fun getDayNightMode(): IDayNightMode = FakeDayNightMode
}

/** 昼夜通道的假实现，测试里可以改明暗并主动推回调。 */
object FakeDayNightMode : IDayNightMode {

    /** 当前实际明暗（`1` 白天 / `2` 夜间）。 */
    @Volatile
    var display: Int = 1

    /** 当前模式（`1` 白天 / `2` 夜间 / `3` 跟随）。 */
    @Volatile
    var mode: Int = 3

    /** 已注册的回调（用来验证「只注册一次」）。 */
    val callbacks = mutableListOf<IDayNightMode.IDayNightChangeCallBack>()

    override fun getDayNight(): Int = display

    override fun getDayNightMode(): Int = mode

    override fun registerDayNightModeCallBack(callBack: IDayNightMode.IDayNightChangeCallBack): Boolean {
        callbacks += callBack
        return true
    }

    override fun unregisterDayNigntModeCallBack(callBack: IDayNightMode.IDayNightChangeCallBack): Boolean =
        callbacks.remove(callBack)

    /** 模拟车机推「明暗变了」。 */
    fun fireDisplay(value: Int) {
        display = value
        callbacks.toList().forEach { it.onDayNightChanged(value) }
    }

    /** 模拟车机推「模式变了」。 */
    fun fireMode(value: Int) {
        mode = value
        callbacks.toList().forEach { it.onDayNightModeChange(value) }
    }
}
