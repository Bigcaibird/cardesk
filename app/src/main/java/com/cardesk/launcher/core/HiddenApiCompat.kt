package com.cardesk.launcher.core

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * **隐藏 API（hidden API）限制的兜底**。
 *
 * ## 为什么需要它
 *
 * 车机的天气 SDK（`com.ecarx.sdk.weather.WeatherAPI`）、TBox、车辆信息接口
 * 都是**车机 ROM 私有类**（在 boot classpath 里，但不在 AOSP 公开 SDK 里）。
 * Android 的隐藏 API 限制会拦掉对它们的反射：
 *
 * `
 * java.lang.NoSuchMethodException: android.os.ServiceManager.getServiceNames []
 * `
 *
 * （实测：模拟器上 uid 1000 + `sharedUserId=android.uid.system` **照样被拦** ——
 *  共享 system uid 并不等于隐藏 API 豁免。
 *
 * 限制的严格程度按 分级：
 *
 * | targetSdk | 还能访问的成员 |
 * |---|---|
 * | 34（本项目此前） | 只剩「API 27 及以下支持」的那一档，车机 SDK 基本全被挡 |
 * | 29（**口径**） | 「API 29 及以下支持」的那一档，车机 SDK 多半能拿到 |
 *
 * 所以本项目已经把 对齐到 **29**。
 * 但 ROM 的 hiddenapi flags 是车机厂商自己填的，**到底哪一档只有真机知道** ——
 * 这就是这里再加一层**进程内豁免**的原因。
 *
 * ## 这个豁免安全吗
 *
 * - 只影响**我们自己的进程**，不影响系统、也不影响别的应用；
 * - 不改任何系统状态，只是让本进程的反射不再被拦；
 * - 失败就失败，代码里全部 `runCatching`，不影响任何功能路径。
 */
object HiddenApiCompat {

    /** 结果缓存（只试一次）。 */
    @Volatile
    private var result: String? = null

    /**
     * 尝试放开本进程的隐藏 API 限制（只试一次）。
     *
     * @return 一句话结论，进日志与诊断包
     */
    @Synchronized
    fun tryExempt(): String {
        result?.let { return it }
        val text = try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val vmRuntime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                String::class.java,
            )
            // "L" = 放开所有类（Lxxx/ 形式的前缀匹配）
            setExemptions.invoke(vmRuntime, "L")
            "已放开"
        } catch (t: Throwable) {
            "未放开(${t.javaClass.simpleName}: ${t.message?.take(80) ?: "-"})"
        }
        result = text
        return text
    }

    /** 应用启动时调一次：结果落日志，失败也不影响任何功能。 */
    fun applyOnStart(context: Context) {
        val text = tryExempt()
        Logx.i(LogTags.APP, "隐藏 API 豁免", "result" to text, "pkg" to context.packageName)
    }
}
