package com.cardesk.launcher.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream

/**
 * 第221轮 · 日志落盘的两条硬约束（验收 P1-1 / P1-2 的回归测试）。
 *
 * ## 为什么要写这两个测试
 *
 * 这两个缺陷都是**「看起来在跑、其实没生效」**的类型，
 * 光读代码看不出来，非要等实车/设备上跑出 2.8MB 的日志文件才发现：
 *
 * - **P1-2 单文件轮换失效**：第220轮把落盘改成「常驻句柄」，
 *   轮换检查只在 `openTarget()` 里，而 `openTarget()` 只在 `stream == null` 时才调
 *   ⇒ 句柄一旦打开，文件就能一直长（实测长到 3.3MB），
 *   到 4MB 总量上限时 `trim()` 会把**正在写的文件**也删掉 ⇒ 一次性丢光。
 *
 *   旧写法每行都走 `target()`（那里判 `file.length() < MAX_FILE_BYTES`），
 *   所以旧包的文件是 512KB + 一行 —— **轮换一直是好的，是常驻句柄这版漏了检查**。
 *
 * - **P1-1 诊断包不含当前会话**：装不下时用 `continue` **跳过整个文件**，
 *   于是当前会话（最大的那个）被跳过，包里只剩更老的小文件 ——
 *   实车排障最需要的「刚刚发生了什么」不在包里。
 *
 * 两条都锁在这里，以后再有人改落盘/导出，测试会先喊。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileLogSinkRotationTest {

    // -------------------------------------------------------------- P1-2 单文件轮换

    /**
     * 写超过 512KB 之后，**必须**换文件。
     *
     * 这条就是 P1-2 的回归点：常驻句柄版本下 `writtenBytes` 会一路涨到几 MB。
     */
    @Test
    fun `写满 512KB 之后轮换到新文件`() {
        val ctx = RuntimeEnvironment.getApplication()
        val sink = FileLogSink(ctx)
        sink.level = LogLevel.DEBUG
        val dir = File(ctx.filesDir, "logs")
        dir.listFiles()?.forEach { it.delete() }

        // 每行 ~1KB，写 600 行 ⇒ 约 600KB ⇒ 必须跨过 512KB 的门槛
        val payload = "x".repeat(1000)
        repeat(600) { sink.write(LogLevel.DEBUG, "CarDesk/Test", payload, null) }
        // 落盘在后台线程上做，等它排空
        waitForDrain(sink)

        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        assertTrue("应该至少写出一个日志文件", files.isNotEmpty())
        for (f in files) {
            assertTrue(
                "文件 ${f.name} 有 ${f.length()} 字节，超过单文件上限 " +
                    "${FileLogSink.MAX_FILE_BYTES} —— 轮换没生效",
                f.length() <= FileLogSink.MAX_FILE_BYTES + 4096,
            )
        }
        assertTrue(
            "写满 512KB 应该产生第二个文件，实际只有 ${files.size} 个：" +
                files.joinToString { "${it.name}=${it.length()}" },
            files.size >= 2,
        )
    }

    /**
     * 轮换之后，**最新的**文件必须还在写（不是「一次性丢光」）。
     *
     * 这是 P1-2 的另一半：旧缺陷到 4MB 总量上限时，
     * `trim()` 会把正在写的文件删掉，表现为日志「断崖式」消失。
     */
    @Test
    fun `轮换之后最新文件仍在增长`() {
        val ctx = RuntimeEnvironment.getApplication()
        val sink = FileLogSink(ctx)
        sink.level = LogLevel.DEBUG
        val dir = File(ctx.filesDir, "logs")
        dir.listFiles()?.forEach { it.delete() }

        repeat(600) { sink.write(LogLevel.DEBUG, "CarDesk/Test", "y".repeat(1000), null) }
        waitForDrain(sink)
        val before = dir.listFiles()?.maxByOrNull { it.lastModified() }?.length() ?: 0L

        repeat(5) { sink.write(LogLevel.DEBUG, "CarDesk/Test", "z".repeat(1000), null) }
        waitForDrain(sink)
        val after = dir.listFiles()?.maxByOrNull { it.lastModified() }?.length() ?: 0L

        assertTrue(
            "轮换后最新文件应该继续增长（之前 $before，之后 $after）",
            after > before,
        )
    }

    // -------------------------------------------------------------- P1-1 诊断包取尾部

    /**
     * 文件超过预算时，要**取尾部**，而不是把整个文件跳过。
     *
     * 这条锁的是 P1-1 的核心：当前会话的日志必须进包。
     */
    @Test
    fun `超预算时取尾部而不是跳过整个文件`() {
        val src = File.createTempFile("cardesk-tail", ".txt")
        try {
            // 造一个 100 行、每行 100 字节的文件
            val sb = StringBuilder()
            repeat(100) { sb.append("line-").append(it.toString().padStart(4, '0')).append(" ")
                .append("y".repeat(90)).append('\n') }
            src.writeText(sb.toString())
            val full = src.readBytes()
            // 100 行 × 每行 101 字节（"line-0000 " 10 字节 + 90 个 y + 换行）
            assertEquals(10_100L, full.size.toLong())

            // 只取最后 500 字节
            val out = java.io.ByteArrayOutputStream()
            Diagnostics.copyTailForTest(out, src, 500L)
            val tail = out.toByteArray()

            assertTrue("应该取到了尾部", tail.isNotEmpty())
            assertTrue("尾部不超过预算", tail.size <= 500)
            // 尾部必须是**完整行**（第一行不能是半行）
            val text = String(tail, Charsets.UTF_8)
            assertTrue("尾部第一行应当是完整行（不能以半行开头）", text.startsWith("line-"))
            // 而且要真的是最后那几行
            assertTrue("尾部应当包含最后一行", text.contains("line-0099"))
            // 不该包含最前面那几行
            assertTrue("尾部不该包含开头那几行", !text.contains("line-0000\n"))
        } finally {
            src.delete()
        }
    }

    /** 尾部字节数与文件长度一致时，等于整份。 */
    @Test
    fun `预算足够时取整份`() {
        val src = File.createTempFile("cardesk-tail-full", ".txt")
        try {
            src.writeText("hello\nworld\n")
            val out = java.io.ByteArrayOutputStream()
            Diagnostics.copyTailForTest(out, src, 1024L)
            assertEquals("hello\nworld\n", String(out.toByteArray(), Charsets.UTF_8))
        } finally {
            src.delete()
        }
    }

    // -------------------------------------------------------------- 截断文案

    /**
     * 尾部不足 1MB 时，截断文案**不能**写成「最后 0 MB」。
     *
     * 第221轮**设备实测**发现的：包体上限 2MB 会被前面几个整文件先占掉大半，
     * 留给最老那个文件的尾部只剩 ~486KB ——
     * 旧写法 `bytes / 1024 / 1024` 直接把 486,524 字节算成「0 MB」，
     * 摘要里写「已只保留最后 0 MB」，看的人只会以为「什么都没保留」。
     */
    @Test
    fun `尾部不足 1MB 时截断文案不能写 0 MB`() {
        val tail = Diagnostics.humanBytes(486_524L)
        assertEquals("475 KB", tail)
        assertTrue("截断文案不能出现「0 MB」：$tail", !tail.contains("0 MB"))
        // 其余量级也别退化
        assertEquals("2 MB", Diagnostics.humanBytes(2L * 1024 * 1024))
        assertEquals("1 KB", Diagnostics.humanBytes(1024L))
        assertEquals("512 B", Diagnostics.humanBytes(512L))
    }

    // -------------------------------------------------------------- 辅助

    /** 等落盘队列排空（落盘在后台线程上做）。 */
    private fun waitForDrain(sink: FileLogSink, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sink.flush(200L)) {
                // 再等一小会儿，确保写线程真的把最后一批写完了
                Thread.sleep(120)
                if (sink.flush(200L)) return
            }
            Thread.sleep(50)
        }
    }
}
