package com.cardesk.launcher.core.log

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘日志：按天分文件、单文件限大小、滚动保留若干份。
 *
 * ## 为什么自己写
 *
 * 真机调试时 `adb logcat` 会被别的进程刷走，而且**重启就丢**。
 * 这里把日志同时写到 App 私有目录，一键导出成诊断包发给开发。
 *
 * ## 文件规则（车机存储紧张，占用必须封顶）
 *
 * - 目录：`context.filesDir/logs/`
 * - 命名：`cardesk-yyyyMMdd-<序号>.txt`（同一天超过 [MAX_FILE_BYTES] 就换下一个序号）
 * - 单文件上限：[MAX_FILE_BYTES]（512KB）
 * - 份数上限：[MAX_FILES]（8 份）
 * - **总字节上限**：[MAX_TOTAL_BYTES]（4MB）—— 份数一样、每份大小不同的话占用差别很大，
 *   所以再加一道总字节的硬上限；两者取更严的那个
 * - **磁盘余量保护**：剩余可用空间低于 [MIN_FREE_BYTES]（16MB）就**不再写盘**，
 *   只留 Logcat —— 日志不能把车机写满，那会影响导航、空调这些正经功能
 *
 * 最坏情况占用 = 8 × 512KB = 4MB，和总字节上限一致。
 *
 * ## 线程安全
 *
 * 写入用一把锁串行化；Logcat 输出不锁（`android.util.Log` 自己有锁）。
 */
internal class FileLogSink(context: Context) {

    companion object {
        /** 单个日志文件上限（512KB）。 */
        const val MAX_FILE_BYTES = 512L * 1024

        /** 最多保留多少个日志文件。 */
        const val MAX_FILES = 8

        /** 全部日志文件的总字节上限（4MB）。与份数上限取更严的那个。 */
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024

        /** 剩余可用空间低于这个值就不再写盘（把空间留给系统）。 */
        const val MIN_FREE_BYTES = 16L * 1024 * 1024

        /** 同一天最多造多少个文件（防止 index 无限增长）。 */
        const val MAX_INDEX_PER_DAY = 64

        /** 每写多少条做一次保留份数/总字节的清理（避免每条日志都 listFiles）。 */
        const val TRIM_INTERVAL = 256

        /** 日志目录名。 */
        const val DIR_NAME = "logs"

        private val DAY_FMT = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
        private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    }

    private val dir: File = File(context.filesDir, DIR_NAME)
    private val lock = Any()
    private var current: File? = null

    /** 距离下一次清理还剩多少条。 */
    private var writesSinceTrim = 0

    /**
     * 是否已经因为**磁盘写不进去**而降级（停止落盘，只留 Logcat）。
     *
     * 车机存储满了以后继续写只会一直抛异常、一直刷 Logcat 警告，
     * 所以一旦确认是空间问题就直接停写，等用户清日志或系统腾出空间再说。
     */
    @Volatile
    var degraded: Boolean = false
        private set

    /** 当前落盘级别（低于这个级别的只进 Logcat，不落盘）。 */
    @Volatile
    var level: LogLevel = LogLevel.INFO

    /** 写一行。返回 true 表示已落盘。 */
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?): Boolean {
        if (!level.reached(this.level)) return false
        if (degraded) return false
        val line = LogFormat.line(TIME_FMT.format(Date()), level, tag, message, error)
        synchronized(lock) {
            try {
                val file = target() ?: return false
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(raf.length())
                    // 每条记录必须带行分隔符，否则整个文件会挤成一行
                    raf.write(LogFormat.record(line).toByteArray(Charsets.UTF_8))
                }
                if (++writesSinceTrim >= TRIM_INTERVAL) {
                    writesSinceTrim = 0
                    trim()
                }
                return true
            } catch (e: Throwable) {
                // 日志系统自己不能把 App 弄崩
                if (isOutOfSpace(e)) {
                    degraded = true
                    Log.w(LogTags.LOG, "file log write failed: out of space, stop persisting logs")
                } else {
                    Log.w(LogTags.LOG, "file log write failed: ${e.message}")
                }
                return false
            }
        }
    }

    /**
     * 当前正在写的文件；需要换文件时按规则挑一个新的。
     *
     * 返回 null 表示**现在不该写盘**：
     * - 磁盘剩余空间低于 [MIN_FREE_BYTES]；
     * - 当天的文件序号已经用满（不应该发生，有总字节上限兜底）。
     */
    private fun target(): File? {
        if (!dir.exists() && !dir.mkdirs()) return null
        val today = DAY_FMT.format(Date())
        val file = current
        if (file != null && file.exists() && LogFormat.isSameDay(file.name, today)) {
            if (file.length() < MAX_FILE_BYTES) return file
        }
        // 要开新文件了：先把旧文件清一清，保证总占用不超标
        trim()
        // 磁盘余量不够就别写了 —— 日志不能把车机存储写满
        if (!hasFreeSpace()) {
            Log.w(LogTags.LOG, "free space below floor, stop persisting logs")
            degraded = true
            return null
        }
        // 新文件：找当天下一个可用序号
        var index = 0
        while (index < MAX_INDEX_PER_DAY) {
            val candidate = File(dir, LogFormat.fileName(today, index))
            if (!candidate.exists() || candidate.length() < MAX_FILE_BYTES) {
                current = candidate
                return candidate
            }
            index++
        }
        return null
    }

    /**
     * 删掉超出保留份数**或**超出总字节上限的旧文件。
     *
     * 两道约束取更严的那个：份数够但每份都不小，照样能把车机占满。
     */
    private fun trim() {
        val files = dir.listFiles { f ->
            f.name.startsWith(LogFormat.FILE_PREFIX) && f.name.endsWith(LogFormat.FILE_SUFFIX)
        } ?: return
        if (files.isEmpty()) return
        val ordered = files.sortedBy { it.lastModified() }
        val dropByCount = LogFormat.toRemove(ordered.map { it.name }, MAX_FILES)
        val dropByBytes = LogFormat.toRemoveByBytes(
            ordered.map { it.name to it.length() },
            MAX_TOTAL_BYTES,
        )
        val drop = (dropByCount + dropByBytes).distinct()
        if (drop.isEmpty()) return
        drop.forEach { name ->
            val f = File(dir, name)
            runCatching { f.delete() }
            if (current?.name == name) current = null
        }
        Logx.d(
            LogTags.LOG, "清理旧日志文件",
            "deleted" to drop.size, "left" to (ordered.size - drop.size),
        )
    }

    /** 剩余可用空间是否还够写日志。取不到就按「够」处理，别误杀。 */
    private fun hasFreeSpace(): Boolean = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes > MIN_FREE_BYTES
    } catch (t: Throwable) {
        true
    }

    /** 是不是「磁盘满了」这类不可恢复的错误。 */
    private fun isOutOfSpace(e: Throwable): Boolean {
        val msg = "${e.message} ${e.javaClass.simpleName}"
        return msg.contains("ENOSPC") || msg.contains("No space") || msg.contains("空间")
    }

    /** 日志目录。 */
    fun directory(): File = dir

    /** 全部日志文件（按时间正序）。 */
    fun files(): List<File> = dir.listFiles { f ->
        f.name.startsWith(LogFormat.FILE_PREFIX) && f.name.endsWith(LogFormat.FILE_SUFFIX)
    }
        ?.sortedBy { it.lastModified() }
        ?: emptyList()

    /** 当前日志占用的总字节数。 */
    fun totalBytes(): Long = files().sumOf { it.length() }

    /** 末尾 [lines] 行（给「导出前预览」用）。 */
    fun tail(lines: Int): String {
        val out = StringBuilder()
        for (file in files().asReversed()) {
            runCatching {
                file.readLines().asReversed().take(lines).asReversed().forEach { out.append(it).append('\n') }
            }
        }
        return out.toString()
    }

    /** 清空全部日志（顺带解除降级状态，让落盘重新可用）。 */
    fun clear() {
        synchronized(lock) {
            current = null
            writesSinceTrim = 0
            files().forEach { runCatching { it.delete() } }
            degraded = false
        }
    }
}
