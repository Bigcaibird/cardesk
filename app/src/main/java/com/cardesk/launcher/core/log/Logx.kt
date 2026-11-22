package com.cardesk.launcher.core.log

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 统一日志门面：**同时**写 Logcat 与本地文件。
 *
 * ## 用法
 *
 * `kotlin
 * Logx.i(LogTags.HVAC, "写入空调", "func" to 0x10010300, "zone" to 1, "value" to 1)
 * Logx.w(LogTags.VEHICLE, "通道连接失败", t)
 * `
 *
 * 结构化字段会拼成 `key=value`，方便在真机上直接 grep：
 *
 * `bash
 * adb logcat | grep 'CarDesk/Hvac.*func=0x10010300'
 * `
 *
 * ## 级别
 *
 * - `DEBUG`：调试细节，**默认不落盘**（只进 Logcat），避免车机长期跑爆存储；
 * - `INFO`：关键状态与用户操作，**默认落盘**；
 * - `WARN` / `ERROR`：异常与降级，**总是落盘**。
 *
 * 落盘级别可以在设置页调整（设置 → 系统 → 诊断日志）。
 *
 * ## 线程安全
 *
 * 全局单例；[init] 之后才能写文件（未 init 时只进 Logcat，不会崩）。
 */
object Logx {

    @Volatile
    private var sink: FileLogSink? = null

    /** 落盘级别的持久化（和 `core/Prefs.kt` 用同一个 prefs 文件）。 */
    @Volatile
    private var prefs: SharedPreferences? = null

    /** 已初始化（便于诊断包记录）。 */
    val initialized: Boolean get() = sink != null

    /** 初始化（App 启动时调一次）。 */
    fun init(context: Context) {
        if (sink != null) return
        synchronized(this) {
            if (sink == null) {
                runCatching {
                    sink = FileLogSink(context.applicationContext)
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    // 回读上次设置的落盘级别 —— 车机重启 / App 重启后不能丢，
                    // 否则用户调到「调试」后又重启一次，日志突然没了还以为没开。
                    persistedLevel()?.let {
                        sink?.level = it
                        Log.i(LogTags.LOG, "落盘级别（上次设置） -> ${it.label}")
                    }
                }.onFailure { Log.w(LogTags.LOG, "init failed: ${it.message}") }
            }
        }
    }

    /** 设置落盘级别（同时持久化）。 */
    fun setLevel(level: LogLevel) {
        sink?.level = level
        runCatching {
            prefs?.edit()?.putString(KEY_LEVEL, level.name)?.apply()
        }.onFailure { Log.w(LogTags.LOG, "写入日志级别失败: ${it.message}") }
        i(LogTags.LOG, "文件日志级别 -> ${level.label}")
    }

    /** 当前落盘级别。 */
    fun level(): LogLevel = sink?.level ?: LogLevel.INFO

    /** 持久化里记的上次级别（没有或认不出来就是 null）。 */
    private fun persistedLevel(): LogLevel? =
        LogLevel.byNameOrNull(runCatching { prefs?.getString(KEY_LEVEL, null) }.getOrNull())

    // ------------------------------------------------------------------ 写日志

    fun d(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        write(LogLevel.DEBUG, tag, message, null, fields)

    /**
     * 调试级日志：**Logcat 全量，落盘按 key 节流**（第220轮）。
     *
     * ## 为什么要这样
     *
     * 高频来源（氛围灯逐帧 20/s、车控回调 21/s、信号降级链 14/s）
     * 全量落盘的话，4MB 的日志预算 **6.5 分钟**就写满 ——
     * 诊断包只能装最近 8 份，最老的直接被丢，
     * 「出问题之前发生了什么」全没了。
     *
     * 所以：**Logcat 保持全量**（要查细节随时 `adb logcat` 能查到），
     * **落盘按 key 节流**（每 [intervalMs] 最多一条），
     * 把有限的落盘额度留给真正的异常。
     *
     * @param key 区分来源的键（同一个 key 共享一个节流窗口）
     * @param intervalMs 落盘的最小间隔（毫秒）
     */
    fun dThrottled(
        tag: String,
        key: String,
        intervalMs: Long,
        message: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val full = LogFormat.message(message, fields)
        try {
            Log.d(tag, full)
        } catch (t: Throwable) {
            // Logcat 本身失败不能影响业务
        }
        if (!LogThrottle.allow("file:$key", intervalMs)) return
        if (!LogLevel.DEBUG.reached(level())) return
        runCatching { sink?.write(LogLevel.DEBUG, tag, full, null) }
    }

    /** 调试级（带异常）。DEBUG 不落盘，只在 logcat 里看。 */
    fun d(tag: String, message: String, error: Throwable, vararg fields: Pair<String, Any?>) =
        write(LogLevel.DEBUG, tag, message, error, fields)

    fun i(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        write(LogLevel.INFO, tag, message, null, fields)

    /** 警告（带异常）。 */
    fun w(tag: String, message: String, error: Throwable, vararg fields: Pair<String, Any?>) =
        write(LogLevel.WARN, tag, message, error, fields)

    /** 警告（不带异常）。 */
    fun w(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        write(LogLevel.WARN, tag, message, null, fields)

    /** 错误（带异常）。 */
    fun e(tag: String, message: String, error: Throwable, vararg fields: Pair<String, Any?>) =
        write(LogLevel.ERROR, tag, message, error, fields)

    /** 错误（不带异常）。 */
    fun e(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        write(LogLevel.ERROR, tag, message, null, fields)

    /**
     * 关键交互事件。
     *
     * 用 `[EVT]` 前缀标记，方便在日志里一眼筛出「用户做了什么」：
     * `adb logcat | grep '\[EVT\]'`。
     */
    fun event(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        write(LogLevel.INFO, tag, "[EVT] $message", null, fields)

    private fun write(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable?,
        fields: Array<out Pair<String, Any?>>,
    ) {
        val full = LogFormat.message(message, fields)
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, full, error)
                LogLevel.INFO -> Log.i(tag, full, error)
                LogLevel.WARN -> Log.w(tag, full, error)
                LogLevel.ERROR -> Log.e(tag, full, error)
            }
        } catch (t: Throwable) {
            // Logcat 本身失败不能影响业务
        }
        // 第220轮：**DEBUG 级的重复内容，落盘按窗口去重**。
        //
        // 车端每秒都在刷同一批「读不到 / 不被固件支持 / 通道不可用」——
        // 实车诊断包实测 14 行/秒，全是同几句话。
        // 这些行**第一次必须记**（现场信息），
        // 之后同内容在窗口内就不重复落盘了（Logcat 里仍然全量）。
        //
        // 窗口内被去重的只有「**逐字相同**」的 DEBUG 行 ——
        // 任何带变化字段的行（值变了、序号变了）都不受影响。
        if (level == LogLevel.DEBUG) {
            if (!level.reached(level())) return
            if (!LogThrottle.allow("dup:$tag|$full", DEBUG_DUP_WINDOW_MS)) return
        }
        runCatching { sink?.write(level, tag, full, error) }
    }

    /**
     * DEBUG 级重复内容落盘的去重窗口（毫秒）。
     *
     * 60 秒：足够把「同一个探针一直读不到」压成一条，
     * 又不会把两次独立故障（间隔很久）合并掉。
     */
    private const val DEBUG_DUP_WINDOW_MS = 60_000L

    // ------------------------------------------------------------------ 导出

    /** 日志目录（App 私有目录）。 */
    fun logDirectory() = sink?.directory()

    /**
     * 落盘是否已降级（磁盘空间不足，停止写盘、只留 Logcat）。
     *
     * 诊断包里要能看到这个状态 —— 否则用户会以为「没日志」是 App 坏了，
     * 其实是车机存储满了。
     */
    fun degraded(): Boolean = sink?.degraded ?: false

    /** 当前日志占用的总字节数。 */
    fun totalBytes(): Long = sink?.totalBytes() ?: 0L

    /** 末尾若干行（预览用）。 */
    fun tail(lines: Int = 200): String = sink?.tail(lines).orEmpty()

    /** 清空日志。 */
    fun clear() {
        sink?.clear()
        i(LogTags.LOG, "日志已清空")
    }

    /**
     * 等落盘队列排空（导出诊断包前调一次）。
     *
     * 落盘是**后台线程**做的（第220轮），所以刚写完的日志可能还在队列里。
     * 导出前不等一下，包里会少最后几行。
     *
     * @return true = 已经排空
     */
    fun flush(timeoutMs: Long = 2000L): Boolean = sink?.flush(timeoutMs) ?: true

    /**
     * 落盘的平均耗时（微秒/行）—— 诊断用。
     *
     * 旧写法每行都要「开文件 → lseek → write → 关文件」，
     * 实车（高通 820A + eMMC）上一次要 2~5ms；
     * 现在是常驻句柄 + 一次 write()，应该只有几十微秒。
     */
    fun avgWriteMicros(): Long = sink?.avgWriteMicros() ?: 0L

    /** 已经落盘的行数（诊断用）。 */
    fun writtenLines(): Long = sink?.writtenLines() ?: 0L

    // ------------------------------------------------------------------ 持久化键

    /** 和 `core/Prefs.kt` 用同一个 prefs 文件（"cardesk"）。 */
    private const val PREFS_NAME = "cardesk"

    /** 落盘级别的持久化键。 */
    private const val KEY_LEVEL = "log_level"
}
