package com.cardesk.launcher.core.log

/**
 * 日志行的格式化与文件命名（**纯逻辑**，不依赖 Android，可在 JVM 单测里直接验）。
 *
 * ## 行格式
 *
 * `
 * MM-dd HH:mm:ss.SSS <级别> <tag>: <消息> [ key=value... ]
 * `
 *
 * 结构化字段拼成 `key=value`，真机上可以直接 grep：
 *
 * `bash
 * adb logcat | grep 'CarDesk/Hvac.*func=0x10010300'
 * `
 */
object LogFormat {

    /** 日志文件名前缀。 */
    const val FILE_PREFIX = "cardesk-"

    /** 日志文件名后缀。 */
    const val FILE_SUFFIX = ".txt"

    /**
     * 落盘时每条记录的结尾（行分隔符）。
     *
     * 每条记录必须自带换行 —— 否则整个文件会变成**一行**，
     * 用 `tail -n` / `grep -n` 看都会很难受，
     * 车机上的「最近日志预览」也会全部挤在一行里。
     */
    const val RECORD_SEPARATOR = "\n"

    /** 给一行日志加上行分隔符（落盘用，纯逻辑方便单测）。 */
    fun record(line: String): String = line + RECORD_SEPARATOR

    /**
     * 拼消息：把结构化字段追加成 `key=value` 序列。
     *
     * - `null` 值写成 `null`（不是省略），方便看出「字段存在但没值」；
     * - 值的字符串里如果含空格，不加引号（保持 grep 友好）。
     */
    fun message(message: String, fields: Array<out Pair<String, Any?>>): String {
        if (fields.isEmpty()) return message
        val sb = StringBuilder(message)
        for ((k, v) in fields) {
            sb.append(' ')
            sb.append(k)
            sb.append('=')
            sb.append(v?.toString() ?: "null")
        }
        return sb.toString()
    }

    /**
     * 拼一整行日志。
     *
     * @param timestamp 时间戳文本（调用方给，避免这里依赖 `SimpleDateFormat` 的线程安全）
     * @param error 异常：非空时追加 ` | 异常类: 消息`
     */
    fun line(
        timestamp: String,
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable? = null,
    ): String {
        val sb = StringBuilder()
        sb.append(timestamp)
        sb.append(' ')
        sb.append(level.label)
        sb.append(' ')
        sb.append(tag)
        sb.append(" : ")
        sb.append(message)
        if (error != null) {
            sb.append(" | ")
            sb.append(error.javaClass.simpleName)
            sb.append(": ")
            sb.append(error.message ?: "")
        }
        return sb.toString()
    }

    /**
     * 日志文件名：`cardesk-yyyyMMdd-<序号>.txt`。
     *
     * 同一天超过单文件上限时序号递增。
     */
    fun fileName(date: String, index: Int): String = "$FILE_PREFIX$date-$index$FILE_SUFFIX"

    /** 文件名里的日期部分是否匹配给定日期。 */
    fun isSameDay(fileName: String, date: String): Boolean = fileName.startsWith("$FILE_PREFIX$date-")

    /**
     * 裁剪保留列表：按修改时间正序，最多留 [maxFiles] 个，其余删除。
     *
     * 返回**要删除**的文件名集合（纯逻辑，方便单测）。
     */
    fun toRemove(orderedNames: List<String>, maxFiles: Int): List<String> {
        if (orderedNames.size <= maxFiles) return emptyList()
        return orderedNames.take(orderedNames.size - maxFiles)
    }

    /**
     * 按**总字节数**裁剪：从最老的开始删，直到总量不超过 [maxBytes]。
     *
     * 单靠「保留几份」兜不住总占用 —— 份数一样、每份大小不同的话占用差别很大，
     * 车机存储又很紧张，所以再加一道总字节的硬上限。
     *
     * @param orderedFiles 文件名 + 字节数，**按修改时间正序**（最老的在前）
     * @param maxBytes 总字节上限；`<= 0` 表示**不限制**（一个都不删）
     * @return 要删除的文件名（纯逻辑，方便单测）
     */
    fun toRemoveByBytes(orderedFiles: List<Pair<String, Long>>, maxBytes: Long): List<String> {
        if (orderedFiles.isEmpty() || maxBytes <= 0L) return emptyList()
        var total = orderedFiles.sumOf { it.second }
        if (total <= maxBytes) return emptyList()
        val drop = ArrayList<String>()
        for ((name, size) in orderedFiles) {
            if (total <= maxBytes) break
            drop.add(name)
            total -= size
        }
        return drop
    }
}
