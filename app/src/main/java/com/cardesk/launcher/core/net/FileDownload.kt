package com.cardesk.launcher.core.net

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通用文件下载（第八十四轮）。
 *
 * ## 为什么抽出来
 *
 * 「在线更新」和「云端应用库」都要从 R2 拉文件，都要**下载完校验长度和 SHA-256**：
 * R2 走的是公网自定义域，下载被中途截断时**不一定报错** ——
 * 只看 HTTP 200、再看「文件大于 1KB」，拦不住「下了 90% 就断」的残包。
 * 这段逻辑只写一份，两边共用；谁下载谁自己决定日志和提示文案。
 *
 * ## 校验规则（不猜）
 *
 * - [expectSize] > 0 才校验长度；
 * - [expectSha256] 非空才校验摘要；
 * - **任一不符就删掉半成品、判失败** —— 车机上留一个残包比什么都没有更糟，
 *   下次「重试」会拿它当缓存，装的还是坏的。
 */
object FileDownload {

    /** 下载结果。 */
    sealed interface Result {
        /** 下载成功，且该校验的都校验过了。 */
        data class Ok(val bytes: Long, val elapsedMs: Long) : Result

        /** 失败。[reason] 是能直接给人看的短句。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 下载 [url] 到 [target]。
     *
     * @param expectSize 期望字节数（0 = 不校验长度）。
     * @param expectSha256 期望的 SHA-256（空 = 不校验摘要）。
     * @param minBytes 「至少要有多少字节」才算下成功（APK 不会小于 1KB）。
     * @param onProgress 0..100 的进度（主线程回调）。
     */
    fun download(
        url: String,
        target: File,
        expectSize: Long = 0L,
        expectSha256: String = "",
        minBytes: Long = 1024,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 30_000,
        onProgress: (Int) -> Unit = {},
    ): Result {
        val started = System.currentTimeMillis()
        target.parentFile?.mkdirs()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CarDesk")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return Result.Failed("HTTP $code")
            }
            val total = conn.contentLength
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            conn.disconnect()

            // —— 大小下限：太小的一定不是完整的包 ——
            if (target.length() < minBytes) {
                target.delete()
                return Result.Failed("下载内容过小（${target.length()} 字节）")
            }
            // —— 长度校验：清单给了大小就必须对上 ——
            if (expectSize > 0 && target.length() != expectSize) {
                target.delete()
                return Result.Failed("长度对不上（应为 $expectSize，实际 ${target.length()}）")
            }
            // —— 摘要校验：清单给了摘要就必须对上 ——
            if (expectSha256.isNotEmpty()) {
                val actual = Digest.sha256Hex(target)
                if (actual == null || !actual.equals(expectSha256, ignoreCase = true)) {
                    target.delete()
                    return Result.Failed("SHA-256 对不上")
                }
            }
            onProgress(100)
            return Result.Ok(target.length(), System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            target.delete()
            return Result.Failed(t.message ?: "网络异常")
        }
    }
}
