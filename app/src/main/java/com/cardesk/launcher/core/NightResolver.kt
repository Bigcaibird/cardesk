package com.cardesk.launcher.core

import android.content.Context
import java.util.Calendar

/**
 * 日夜判定的**统一入口** —— 桌面主题和仪表歌词投射服务都用它。
 *
 * ## 为什么要抽出来（第一百三十七轮）
 *
 * 仪表歌词的配色要跟着日夜走。最初的做法是让投射服务直接读
 * [Skin.isNight]（桌面主题的单例），看着够简单，实测有两个坑：
 *
 * 1. **桌面没起来时这个值是死的**。
 *    开机广播（`BootReceiver`）会**独立**把投射服务拉起来 ——
 *    那时桌面还没跑过一次，`Skin.apply()` 从没被调过，
 *    `Skin.isNight` 停在初始值（夜间）。
 *    结果就是：白天的白色仪表上，投的是浅青字 —— 跟用户反馈的一模一样。
 * 2. **桌面被销毁后这个值会过期**。
 *    投射服务是前台服务、活在桌面那个进程里；桌面 Activity 一旦被销毁，
 *    每秒的刷新循环就停了，`Skin.isNight` 冻在最后一次的值上。
 *
 * 所以改成：**两边各自去问同一个解析器**。
 * 解析器把四个来源按优先级排好（见 [DayNight.resolveNightUi]），
 * 高德那路信号放在 [amapNightUi] 里**共享**，
 * 所以「桌面是日间、仪表歌词是夜间」这种劈叉不会发生。
 *
 * ## 四个来源（优先级见 [DayNight]）
 *
 * 1. 高德广播的日夜信号（桌面收到时写进 [amapNightUi]，两边共用）；
 * 2. 车机光感（AdaptAPI 昼夜通道 `Twlibrists` 真光照）；
 * 3. 车机日间模式功能值（真车恒为 AUTO，明暗要从第 2 条拿）；
 * 4. 系统 UI 夜间态（`UiModeManager`）。
 *
 * 再加「跟随本地时间」这一档（用户自己设的时段表）。
 */
object NightResolver {

    /**
     * 高德最近一次报的日夜态（`null` = 没收到过高德信号）。
     *
     * 桌面收到高德广播时调 [onAmapDayNight] 写进来，服务直接读 ——
     * **两边看到的是同一个值**，这是「桌面和仪表歌词不劈叉」的关键。
     */
    @Volatile
    var amapNightUi: Boolean? = null
        private set

    /** 桌面收到高德「我切成白天/黑夜了」的广播时调这个。 */
    fun onAmapDayNight(nightUi: Boolean) {
        amapNightUi = nightUi
    }

    /** 当前这一分钟是当天第几分钟（给「跟随本地时间」用）。 */
    fun minuteOfDay(): Int {
        val cal = Calendar.getInstance()
        return DayNight.minuteOfDay(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    /**
     * 当前是夜间吗 —— 桌面与投射服务共用的那一份判定。
     *
     * @param prefs 用户的日夜模式设置（总开关 / 模式 / 时段表）。
     * @param context 读系统 UI 夜间态要 Context；传 `null` 就跳过这一路。
     */
    fun resolve(prefs: Prefs, context: Context?): Boolean =
        resolve(prefs, context, minuteOfDay())

    /** [resolve] 的重载：分钟数由调用方给（便于测试）。 */
    fun resolve(prefs: Prefs, context: Context?, minuteOfDay: Int): Boolean {
        val lightNight = com.cardesk.launcher.vehicle.AdaptapiDayNight.nightUi()
        val sysNight = systemNightMode(context)
        val carNight = carDayModeNight(sysNight)
        return DayNight.resolveNightUi(
            enabled = prefs.dayNightEnabled,
            mode = prefs.dayNightMode,
            minuteOfDay = minuteOfDay,
            dayStartMin = prefs.dayStartMin,
            nightStartMin = prefs.nightStartMin,
            amapNightUi = amapNightUi,
            sensorNightUi = lightNight,
            carNightUi = carNight,
            systemNightUi = sysNight,
            lastAppliedNightUi = prefs.lastAppliedNight(),
        )
    }

    /**
     * 车机日间模式功能值（`538247424`）。
     *
     * 真车上这个值**恒为 AUTO**，而 AUTO 的明暗只能从光感通道拿 ——
     * 所以 AUTO 时这里回落到系统 UI 的夜间态（由 [systemNightMode] 传入）。
     */
    private fun carDayModeNight(systemNightUi: Boolean?): Boolean? = try {
        val gateway = com.cardesk.launcher.vehicle.VehicleController.gateway
        val value = gateway.readValue(
            DayNightSource.FUNCTION_DAYMODE_SETTING,
            com.cardesk.launcher.vehicle.HvacIds.ZONE_GLOBAL,
        )
        value?.let { DayNightSource.nightFromCarValue(it, systemNightUi) }
    } catch (e: Throwable) {
        // 车控通道还没连上（服务比桌面先起来时就是这样）⇒ 这一路读不到，
        // 让 DayNight 按优先级回落到下一路。
        null
    }

    /** 系统 UI 的夜间态（车机上由高德 / 系统昼夜信号驱动）。 */
    private fun systemNightMode(context: Context?): Boolean? = try {
        val um = context?.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        when (um?.nightMode) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
            android.content.res.Configuration.UI_MODE_NIGHT_NO -> false
            else -> null
        }
    } catch (e: Throwable) {
        null
    }
}
