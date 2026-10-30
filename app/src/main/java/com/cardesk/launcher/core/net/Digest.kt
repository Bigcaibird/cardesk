package com.cardesk.launcher.core.net

import java.io.File
import java.security.MessageDigest

/**
 * 文件摘要（第八十三轮新增，第八十四轮挪到 `core.net`）。
 *
 * ## 为什么要自己算摘要
 *
 * R2 的自定义域走公网，下载**中断或被截断时不一定会报错**：
 * 只看 HTTP 200、再看「文件大于 1KB」，拦不住「下了 90% 就断」的残包。
 * 清单里带了 APK 的 SHA-256，下载完本地算一遍对不上，
 * 就当这次下载失败，不去装一个可能残缺的包。
 *
 * ## 为什么挪包
 *
 * 第八十三轮它只服务于「在线更新」，放在 `core.update` 里没问题；
 * 第八十四轮「云端应用库」也要算摘要，就成了**两个上层功能都要用**的底层工具。
 * 放在 `core.net` 里，`core.update` 和 `core.apkstore` 都依赖它，
 * 方向是「上层依赖下层」—— 反过来（`core.net` 去依赖 `core.update`）就成了环。
 */
internal object Digest {

    /** 文件的 SHA-256（小写十六进制）；读不了或算法不可用时返回 null。 */
    fun sha256Hex(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }.getOrNull()
}
