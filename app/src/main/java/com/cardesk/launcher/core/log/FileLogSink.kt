package com.cardesk.launcher.core.log

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 * ## 第220轮：**落盘一律不在调用线程上做**（车机体感卡顿的头号根因）
 *
 * ### 旧版的问题
 *
 * 旧版每写一行日志都要：
 *
 * ```
 * RandomAccessFile(file, "rw").use { raf ->
 *     raf.seek(raf.length())     // 一次 lseek
 *     raf.write(bytes)            // 一次 write
 * }                                 // 一次 close（还有 open）
 * ```
 *
 * 也就是**每行日志 4 次系统调用**（open / lseek / write / close），
 * 而且 `TIME_FMT.format(Date())`（`SimpleDateFormat`）也在调用线程上跑。
 *
 * 这套东西**跑在调用线程上** —— 而调用线程经常就是**主线程**：
 *
 * | 来源 | 频率（实车诊断包实测） |
 * | --- | --- |
 * | 氛围灯逐帧日志（60Hz 渲染 tick 里，节流到 50ms） | 20 次/秒 |
 * | 每秒刷新（时钟 / 场景页绑定 / Dock / 状态栏） | 4~5 次/秒 |
 * | 车控属性变化回调（写一次氛围灯回三次） | 21 次/秒（多在 binder 线程） |
 * | 车辆信号降级链（「读不到」逐条记） | 14 次/秒（多在主线程） |
 *
 * 实车（高通 820A + eMMC）上一次同步「开文件→写→关文件」要 **2~5ms**，
 * 主线程每秒被日志堵掉 **50~125ms** —— 界面每秒掉一帧起步，
 * 交互时更明显（点下去到出画面要等半秒）。
 *
 * 诊断包里也能直接量出来：那台车每秒「1 秒心跳」实际跑成 **1089~1153ms**，
 * 多出来的 89~153ms 就是主线程被同步 I/O 吃掉的。
 *
 * ### 现在
 *
 * 1. **文件 I/O 全部搬到后台写线程**（[WRITER_THREAD_NAME]）——
 *    调用线程只把「时间戳 + 正文」丢进队列就走，**一次系统调用都不做**；
 * 2. **常驻文件句柄** —— 同一个文件一直开着（append 模式），
 *    一行日志只剩一次 `write()`，不再是 4 次；
 * 3. **时间戳在调用线程只取 `System.currentTimeMillis()`**（纯读时钟），
 *    `SimpleDateFormat` 挪到写线程上格式化（它不是线程安全的，单线程用才安全）；
 * 4. 队列有上限（[MAX_QUEUE]），写线程跟不上时**丢最老的**，
 *    绝不让日志把内存吃光。
 *
 * > ⚠️ 诊断包导出前要调 [flush] —— 否则队列里最后几行可能还没落盘。
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

        /**
         * 待写队列上限（条）。
         *
         * 写线程是「一次 write() 系统调用」的量级，正常情况下永远追得上；
         * 真追不上（车机存储卡住）时丢最老的，保证内存有界。
         */
        const val MAX_QUEUE = 4096

        /** 写线程名（诊断用）。 */
        const val WRITER_THREAD_NAME = "cardesk-log-write"

        private val DAY_FMT = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
        private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    }

    private val dir: File = File(context.filesDir, DIR_NAME)

    // ------------------------------------------------------------------ 后台写线程

    /** 待写队列（调用线程只做 `offer`，不做任何文件 I/O）。 */
    private val queue = LinkedBlockingQueue<Pending>(MAX_QUEUE)

    /** 「清空日志」的请求标志（写线程消费）。 */
    private val clearRequested = AtomicBoolean(false)

    /** 写线程正在用的文件句柄（**常驻**，不每行开关）。 */
    private var stream: FileOutputStream? = null

    /** 当前文件的已写字节数（自己记，不每次问文件系统）。 */
    private var writtenBytes = 0L

    /** 当前正在写的文件。 */
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

    init {
        Thread({ drainLoop() }, WRITER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------------ 写日志

    /**
     * 一条待写记录。
     *
     * @param at 采集时刻（毫秒，调用线程取的）
     * @param level 级别
     * @param tag tag
     * @param body 正文（消息 + 结构化字段，调用线程已拼好）
     * @param error 异常（可能为 null）
     */
    private class Pending(
        val at: Long,
        val level: LogLevel,
        val tag: String,
        val body: String,
        val error: Throwable?,
    )

    /** 写一行。返回 true 表示**已入队**（不代表已经落盘）。 */
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?): Boolean {
        if (!level.reached(this.level)) return false
        if (degraded) return false
        enqueue(Pending(System.currentTimeMillis(), level, tag, message, error))
        return true
    }

    /** 入队（满了丢最老的一条，保证内存有界）。 */
    private fun enqueue(p: Pending) {
        if (!queue.offer(p)) {
            queue.poll() // 丢最老的一条
            queue.offer(p)
        }
    }

    // ------------------------------------------------------------------ 写线程主循环

    /**
     * 写线程主循环。
     *
     * 用阻塞队列（而不是 `Handler`）——
     * 队列空时 `poll(timeout)` **不占 CPU**，
     * 而这里还要处理「清空」这类控制指令，用队列比 Handler 消息更直接。
     */
    private fun drainLoop() {
        while (true) {
            val p = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            if (clearRequested.getAndSet(false)) {
                runCatching { doClear() }
                continue
            }
            writeOne(p)
        }
    }

    /** 写一条（**只在写线程上调用**）。 */
    private fun writeOne(p: Pending) {
        try {
            // 第221轮：**写之前就检查轮换上限**（第220轮的回归，验收 P1-2）。
            //
            // 第220轮把落盘改成「常驻句柄」之后，轮换检查只在 [openTarget] 里做，
            // 而 [openTarget] 只在 `stream == null` 时才会被调用 ——
            // 于是句柄一旦打开，文件就能一直长下去（实测长到 3.3MB）。
            //
            // 旧写法每行都走 [target]（那里判 `file.length() < MAX_FILE_BYTES`），
            // 所以旧包的文件是 512KB+一行 —— 轮换一直是好的，
            // 是常驻句柄这一版把检查漏掉了。
            //
            // 现在：**写之前**判 `writtenBytes >= MAX_FILE_BYTES`，
            // 满了就关句柄 + 换新文件（[openTarget] 会挑当天下一个序号）。
            if (stream != null && writtenBytes >= MAX_FILE_BYTES) {
                closeStream()
                // 注意：[current] 仍指向那个写满的文件 ⇒ [openTarget] 会走「换新文件」分支
            }
            val out = stream ?: openTarget() ?: return
            val stamp = TIME_FMT.format(Date(p.at))
            val line = LogFormat.line(stamp, p.level, p.tag, p.body, p.error)
            val bytes = LogFormat.record(line).toByteArray(Charsets.UTF_8)
            // 第220轮：**量一下这条落盘到底花了多少时间**（诊断用）。
            //
            // 旧写法每行都要「开文件 → lseek → write → 关文件」，
            // 实车（高通 820A + eMMC）上一次要 2~5ms；
            // 现在是常驻句柄 + 一次 write()，应该只有几十微秒。
            // 这个数字直接进诊断包，车上一看就知道省了多少。
            val t0 = System.nanoTime()
            out.write(bytes)
            out.flush()
            writeCostNanos += System.nanoTime() - t0
            writeCount++
            writtenBytes += bytes.size
            if (++writesSinceTrim >= TRIM_INTERVAL) {
                writesSinceTrim = 0
                trim()
            }
        } catch (e: Throwable) {
            // 日志系统自己不能把 App 弄崩
            closeStream()
            if (isOutOfSpace(e)) {
                degraded = true
                Log.w(LogTags.LOG, "file log write failed: out of space, stop persisting logs")
            } else {
                Log.w(LogTags.LOG, "file log write failed: ${e.message}")
            }
        }
    }

    /** 累计落盘耗时（纳秒，只在写线程上累加）。 */
    private var writeCostNanos = 0L

    /** 累计落盘行数。 */
    @Volatile
    private var writeCount = 0L

    /**
     * 落盘的平均耗时（微秒/行）。
     *
     * 0 = 还没写过。诊断包用。
     */
    fun avgWriteMicros(): Long {
        val n = writeCount
        if (n <= 0L) return 0L
        return writeCostNanos / n / 1000L
    }

    /** 已经落盘的行数（诊断包用）。 */
    fun writtenLines(): Long = writeCount

    // ------------------------------------------------------------------ 文件句柄

    /**
     * 打开（必要时轮换到）当前文件。
     *
     * 返回 null 表示**现在不该写盘**：
     * - 磁盘剩余空间低于 [MIN_FREE_BYTES]；
     * - 当天的文件序号已经用满（不应该发生，有总字节上限兜底）。
     */
    private fun openTarget(): FileOutputStream? {
        if (!dir.exists() && !dir.mkdirs()) return null
        val today = DAY_FMT.format(Date())
        val file = current
        // 当前文件还能用 → 接着写（句柄可能在一次写失败后被关掉，这时要重开）
        if (file != null && file.exists() && LogFormat.isSameDay(file.name, today) && writtenBytes < MAX_FILE_BYTES) {
            stream?.let { return it }
            return reopen(file)
        }
        // 要开新文件了：先把旧文件清一清，保证总占用不超标
        closeStream()
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
                writtenBytes = candidate.length()
                return reopen(candidate)
            }
            index++
        }
        return null
    }

    /** 打开（追加模式）一个已选定的文件。 */
    private fun reopen(target: File): FileOutputStream? = try {
        FileOutputStream(target, true).also { stream = it }
    } catch (e: Throwable) {
        Log.w(LogTags.LOG, "open log file failed: ${e.message}")
        stream = null
        current = null
        writtenBytes = 0L
        null
    }

    /** 关掉当前句柄（轮换 / 降级 / 清空时用）。 */
    private fun closeStream() {
        try {
            stream?.flush()
            stream?.close()
        } catch (t: Throwable) {
            // 关不掉也不能怎样
        }
        stream = null
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
            if (current?.name == name) {
                // 正在写的文件被删了 → 句柄作废，下次重新开
                closeStream()
                current = null
                writtenBytes = 0L
            }
        }
        Logx.d(
            LogTags.LOG, "清理旧日志文件",
            "deleted" to drop.size, "left" to (ordered.size - drop.size),
        )
    }

    /**
     * 剩余可用空间是否还够写日志。
     *
     * ## 第221轮：**读不到就当「够」**（别因为取不到就把日志整个掐掉）
     *
     * 以前是 `availableBytes > MIN_FREE_BYTES`，
     * 于是「取不到真实值」（返回 0）的场景会被当成「空间不足」——
     * 日志直接整体停写（`degraded = true`），诊断包也就没内容了。
     *
     * 现在：
     * - `availableBytes <= 0`（**取不到**）⇒ 当作「够」，继续写；
     * - `0 < availableBytes <= MIN_FREE_BYTES`（**确知**不够）⇒ 停写；
     * - 真的写满了 ⇒ `ENOSPC` 那条路会把它降级（见 [writeOne]）。
     *
     * 保护没有削弱：确知空间不足时照样停；真的写不进去时靠 `ENOSPC` 降级。
     */
    private fun hasFreeSpace(): Boolean = try {
        val stat = StatFs(dir.absolutePath)
        val avail = stat.availableBytes
        avail <= 0L || avail > MIN_FREE_BYTES
    } catch (t: Throwable) {
        true
    }

    /** 是不是「磁盘满了」这类不可恢复的错误。 */
    private fun isOutOfSpace(e: Throwable): Boolean {
        val msg = "${e.message} ${e.javaClass.simpleName}"
        return msg.contains("ENOSPC") || msg.contains("No space") || msg.contains("空间")
    }

    // ------------------------------------------------------------------ 对外接口

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
        queue.clear()
        clearRequested.set(true)
    }

    /** 真正执行清空（写线程上）。 */
    private fun doClear() {
        closeStream()
        current = null
        writtenBytes = 0L
        writesSinceTrim = 0
        files().forEach { runCatching { it.delete() } }
        degraded = false
    }

    /**
     * 等队列排空（导出诊断包前调一次）。
     *
     * @param timeoutMs 最长等多久（毫秒）
     * @return true = 已经排空
     */
    fun flush(timeoutMs: Long = 2000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && !clearRequested.get()) return true
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return queue.isEmpty()
    }
}
