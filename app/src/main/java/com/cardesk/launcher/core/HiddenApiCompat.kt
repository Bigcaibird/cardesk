package com.cardesk.launcher.core

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Method

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

    /**
     * 豁免前缀。
     *
     * `"L"` = 放开所有类（`Lxxx/` 形式的前缀匹配）。
     */
    const val EXEMPT_PREFIX = "L"

    /** 结果缓存（只试一次）。 */
    @Volatile
    private var result: String? = null

    /**
     * 豁免参数（`setHiddenApiExemptions` 的实参）。
     *
     * 单独抽出来是为了**能单测**：参数形状错了（当年写成单个 String）
     * 会让整条隐藏 API 通路静默失效，而真机上只能从诊断包里看出来。
     */
    fun exemptionArgs(): Array<String> = arrayOf(EXEMPT_PREFIX)

    /**
     * 找出 `setHiddenApiExemptions` 方法。
     *
     * ## 为什么必须是 `String[]`
     *
     * 真实签名是 `void setHiddenApiExemptions(String[] methodSignaturePrefixes)`。
     * 之前这里写的是 `String::class.java` —— 必然 `NoSuchMethodException`，
     * 于是「隐藏 API 豁免」永远显示「未放开」，
     * 依赖隐藏 API 的功能（频谱取 `audioSessionId`、AppOps `setMode`）全都调不通。
     *
     * Android 10 之后 AOSP 又加了一个**单参数**的重载
     * （`setHiddenApiExemptions(String)`），
     * 所以这里不能「按名字随便挑一个」—— 必须明确挑 `String[]` 那个。
     *
     * @param cls `dalvik.system.VMRuntime` 的 Class
     * @return 找到的方法；找不到返回 null
     */
    internal fun findSetHiddenApiExemptions(cls: Class<*>): Method? {
        // 先按「名字 + 参数类型」精确找（Android 9 / API 28 上只有这一个重载）
        runCatching {
            return cls.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
        }
        // 兜底：扫一遍所有同名方法，挑参数是 `String[]` 的那个
        // （万一某些 ROM 把它挪到了父类或改成了别的可见性）
        return cls.declaredMethods.firstOrNull { m ->
            m.name == "setHiddenApiExemptions" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Array<String>::class.java
        }
    }

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
            val setExemptions = findSetHiddenApiExemptions(vmRuntimeClass)
                ?: throw NoSuchMethodException("setHiddenApiExemptions [class java.lang.String[]]")
            // ## 为什么这里要用展开符 `*`
            //
            // `Method.invoke(Object, Object...)` 是 Java 的**可变参数**方法，
            // 从 Kotlin 传数组时**不会自动展开**：
            // 直接把 `Array<String>` 传过去，它会被当成**一个**参数（元素是 String[]），
            // 于是真正调用到的是 `setHiddenApiExemptions(Object[])` → 抛
            // `IllegalArgumentException: argument type mismatch`。
            //
            // 用 `*` 展开才是一组参数：数组里那一个元素（String[]）作为**唯一**实参。
            val args = arrayOf<Any>(exemptionArgs())
            setExemptions.invoke(vmRuntime, *args)
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
