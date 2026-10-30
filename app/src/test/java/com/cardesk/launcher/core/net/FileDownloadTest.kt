package com.cardesk.launcher.core.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * [FileDownload] / [Digest] 单测。
 *
 * 这段逻辑是「在线更新」和「云端应用库」**共用**的下载环节，
 * 它出错的后果很直接：把一个残包当好包装进车机。
 *
 * 所以这里真的起一个本地 HTTP 服务来下载，而不是只测纯函数 ——
 * 「长度不符」「摘要不符」「404」「连不上」都得走一遍真实链路。
 *
 * ## 为什么自己写 HTTP 服务
 *
 * `com.sun.net.httpserver` 在 **Android 编译单元测试**的类路径上不存在
 * （它属于 `jdk.httpserver` 模块，AGP 的测试 classpath 不含）。
 * `java.net.ServerSocket` 在 `java.base` 里，**永远可用**，
 * 所以这里用裸 Socket 手写一个只够用的 HTTP/1.1 响应 —— 三十来行，够测就行。
 */
class FileDownloadTest {

    /** 只够用的本地 HTTP 服务：固定路径 → 固定响应。 */
    private class TinyHttpServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, Pair<Int, ByteArray>>()

        val port: Int get() = socket.localPort

        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    acceptOne()
                } catch (e: IOException) {
                    return@Thread // 服务关掉了
                }
            }
        }

        init {
            worker.isDaemon = true
            worker.start()
        }

        fun serve(path: String, body: ByteArray, status: Int = 200) {
            routes[path] = status to body
        }

        private fun acceptOne() {
            val conn = socket.accept()
            conn.use { sock: Socket ->
                val reader = sock.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                // 请求头读完为止（GET 没有请求体）
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                val (status, body) = routes[path] ?: (404 to "not found".toByteArray())
                val reason = if (status == 200) "OK" else "Not Found"
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 $status $reason\r\n".toByteArray())
                out.write("Content-Length: ${body.size}\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
            }
        }

        override fun close() {
            socket.close()
        }
    }

    private lateinit var server: TinyHttpServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = TinyHttpServer()
        base = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun tmpFile(tag: String): File =
        File.createTempFile("cardesk-dl-$tag", ".bin").also { it.delete() }

    // ------------------------------------------------------------------ 摘要

    @Test
    fun `摘要算得对`() {
        val f = File.createTempFile("cardesk-digest", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            // 已知值：SHA-256("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Digest.sha256Hex(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `读不到的文件摘要返回空`() {
        assertNull(Digest.sha256Hex(File("/definitely/not/exists.bin")))
    }

    @Test
    fun `空文件也有摘要`() {
        val f = File.createTempFile("cardesk-digest-empty", ".bin")
        try {
            f.writeBytes(ByteArray(0))
            assertTrue(Digest.sha256Hex(f)!!.length == 64)
        } finally {
            f.delete()
        }
    }

    // ------------------------------------------------------------------ 下载

    @Test
    fun `正常下载拿到文件`() {
        val body = ByteArray(4096) { (it % 251).toByte() }
        server.serve("/ok.apk", body)
        val target = tmpFile("ok")

        val result = FileDownload.download(
            url = "$base/ok.apk",
            target = target,
            expectSize = body.size.toLong(),
            expectSha256 = sha256(body),
        )

        assertTrue("应当成功，实际 $result", result is FileDownload.Result.Ok)
        assertTrue(target.exists())
        assertEquals(body.size.toLong(), target.length())
    }

    @Test
    fun `长度对不上就判失败并删掉半成品`() {
        val body = ByteArray(4096) { 7 }
        server.serve("/short.apk", body)
        val target = tmpFile("short")

        val result = FileDownload.download(
            url = "$base/short.apk",
            target = target,
            expectSize = 8192L,
        )

        assertTrue(result is FileDownload.Result.Failed)
        // 残包必须删掉：留着的话下次重试会拿它当缓存，装的还是坏的
        assertFalse("残包应该被删掉", target.exists())
    }

    @Test
    fun `摘要对不上就判失败并删掉半成品`() {
        val body = ByteArray(4096) { 9 }
        server.serve("/bad.apk", body)
        val target = tmpFile("bad")

        val result = FileDownload.download(
            url = "$base/bad.apk",
            target = target,
            expectSize = body.size.toLong(),
            expectSha256 = "0".repeat(64),
        )

        assertTrue(result is FileDownload.Result.Failed)
        assertFalse("残包应该被删掉", target.exists())
    }

    @Test
    fun `HTTP 非 200 就失败`() {
        server.serve("/missing.apk", "nope".toByteArray(), status = 404)
        val target = tmpFile("404")

        val result = FileDownload.download(url = "$base/missing.apk", target = target)

        assertTrue(result is FileDownload.Result.Failed)
        assertTrue((result as FileDownload.Result.Failed).reason.contains("404"))
        assertFalse(target.exists())
    }

    @Test
    fun `内容太小也失败`() {
        server.serve("/tiny.apk", ByteArray(10))
        val target = tmpFile("tiny")

        val result = FileDownload.download(url = "$base/tiny.apk", target = target)

        assertTrue(result is FileDownload.Result.Failed)
        assertFalse(target.exists())
    }

    @Test
    fun `没给长度就不校验长度`() {
        val body = ByteArray(4096) { 3 }
        server.serve("/nolimit.apk", body)
        val target = tmpFile("nolimit")

        val result = FileDownload.download(url = "$base/nolimit.apk", target = target)

        assertTrue(result is FileDownload.Result.Ok)
        assertEquals(body.size.toLong(), target.length())
    }

    @Test
    fun `摘要大小写不敏感`() {
        val body = ByteArray(4096) { 11 }
        server.serve("/upper.apk", body)
        val target = tmpFile("upper")

        val result = FileDownload.download(
            url = "$base/upper.apk",
            target = target,
            expectSha256 = sha256(body).uppercase(),
        )

        assertTrue("应当成功，实际 $result", result is FileDownload.Result.Ok)
    }

    @Test
    fun `连不上就失败而不是抛异常`() {
        val target = tmpFile("down")
        // 端口 1 上不会有服务在听
        val result = FileDownload.download(url = "http://127.0.0.1:1/x.apk", target = target)
        assertTrue(result is FileDownload.Result.Failed)
    }

    @Test
    fun `进度回调会走到 100`() {
        val body = ByteArray(4096) { 5 }
        server.serve("/prog.apk", body)
        val target = tmpFile("prog")
        val seen = mutableListOf<Int>()

        FileDownload.download(url = "$base/prog.apk", target = target, onProgress = { seen.add(it) })

        assertTrue("应当收到进度", seen.isNotEmpty())
        assertEquals(100, seen.last())
    }

    /** 已知内容的 SHA-256（用 JDK 自带的 MessageDigest，不引三方库）。 */
    private fun sha256(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
}
