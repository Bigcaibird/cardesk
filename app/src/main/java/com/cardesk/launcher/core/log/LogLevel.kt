package com.cardesk.launcher.core.log

import android.util.Log

/**
 * 日志级别。
 *
 * 单独成文件是为了让格式化逻辑能在 **JVM 单测**里直接用（不碰 `Logx`，
 * 也就不会牵扯 `android.util.Log` 的 stub。
 *
 * 落盘规则：低于 [Logx.level] 的级别**只进 Logcat 不落盘**，
 * 避免车机长期运行把存储写满。
 */
enum class LogLevel(val label: String, val androidPriority: Int) {
    /** 调试细节：默认不落盘。 */
    DEBUG("D", Log.DEBUG),

    /** 关键状态与用户操作：默认落盘。 */
    INFO("I", Log.INFO),

    /** 异常与降级：总是落盘。 */
    WARN("W", Log.WARN),

    /** 错误：总是落盘。 */
    ERROR("E", Log.ERROR),
    ;

    /** 是否达到落盘门槛。 */
    fun reached(threshold: LogLevel): Boolean = this >= threshold

    companion object {
        /**
         * 按名字解析（用于持久化后回读）。
         *
         * 认不出来就返回 null（调用方回落默认级别）——
         * 持久化的值可能来自旧版本或被手改，不能让它把日志系统搞挂。
         */
        fun byNameOrNull(name: String?): LogLevel? {
            if (name.isNullOrBlank()) return null
            return entries.firstOrNull { it.name == name }
        }
    }
}
