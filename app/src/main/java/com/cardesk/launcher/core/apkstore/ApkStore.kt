package com.cardesk.launcher.core.apkstore

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.net.FileDownload
import com.cardesk.launcher.core.update.AppUpdate
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端应用库（第八十四轮）。
 *
 * ## 它是什么
 *
 * 车机要装别的 App（导航、音乐、视频…）时，只有两条老路：
 *
 * - 带 U 盘到车上插；
 * - 在车上让电脑和车机连**同一个局域网**，用 CarDesk 的「局域网传输」推过去。
 *
 * 两条都要求**人在车旁边**。这里做的是第三条路：
 *
 * ```
 * 手机浏览器 ──上传──▶ Cloudflare Worker ──▶ R2（cardesk/apks/）
 *                                              │
 * 车机 CarDesk ──GET https://file.chuxi.bid/cardesk/apks.json ──▶ 清单
 *                    └── 下载 APK ──校验──▶ 静默安装 / 系统安装器
 * ```
 *
 * **出门前在手机上上传，到车上直接装** —— 不用带 U 盘，
 * 也不用和车机连同一个局域网（车机走 4G 就行）。
 *
 * ## 和「在线更新」的关系
 *
 * 两者共用同一套基础设施：同一个 R2 bucket、同一个公开自定义域、同一套
 * 「下载 → 校验长度和 SHA-256 → 安装」链路（见 [AppUpdate.install]）。
 * 区别只在于**清单和语义**：
 *
 * | | 在线更新 | 云端应用库 |
 * |---|---|---|
 * | 清单 | `cardesk/latest.json` | `cardesk/apks.json` |
 * | 条目 | 一个（CarDesk 自己） | 多个（任意 APK） |
 * | 版本比较 | 要比 versionCode | 不用比（列表全给你） |
 *
 * ## 线程
 *
 * [fetchIndex] / [download] 都是**阻塞**的，必须在后台线程调用。
 */
object ApkStore {

    /** 云端应用库的域名（自定义域，走 Cloudflare CDN）。 */
    const val HOST = "file.chuxi.bid"

    /** 清单的路径（R2 对象 key）。 */
    const val INDEX_PATH = "cardesk/apks.json"

    /** 清单的完整地址（车机每次刷新列表就拉这一个文件）。 */
    const val INDEX_URL = "https://$HOST/$INDEX_PATH"

    /** 域名根（清单里写相对 key 时按它拼开）。 */
    const val ROOT_URL = "https://$HOST/"

    /** 拉清单的结果。 */
    sealed interface FetchResult {
        /** 拉到了。 */
        data class Ok(val index: ApkIndex) : FetchResult

        /** 没拉到。[reason] 是能直接给人看的短句。 */
        data class Failed(val reason: String) : FetchResult
    }

    /**
     * 拉云端应用清单（阻塞）。
     *
     * 清单读不懂**不算失败**：解析出来是空清单，页面上显示「还没有应用」，
     * 和「网络不通」要区分开 —— 后者是问题，前者只是还没传。
     */
    fun fetchIndex(context: Context, timeoutMs: Int = 10_000): FetchResult {
        return try {
            val conn = URL(INDEX_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CarDesk")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw java.io.IOException("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val index = ApkIndex.parse(text, ROOT_URL)
            Logx.i(
                LogTags.APK_STORE, "云端应用清单已拉取",
                "count" to index.items.size, "updatedAt" to index.updatedAt,
            )
            FetchResult.Ok(index)
        } catch (t: Throwable) {
            Logx.w(LogTags.APK_STORE, "云端应用清单拉取失败", t, "url" to INDEX_URL)
            FetchResult.Failed(t.message ?: "网络异常")
        }
    }

    /**
     * 下载一个 APK（阻塞）。
     *
     * 走 [FileDownload]：下载完校验**长度**和 **SHA-256**（清单给了才校验），
     * 对不上就删掉半成品、判失败 —— 车机装一个残缺的 APK 比不装更糟。
     */
    fun download(context: Context, entry: ApkEntry, onProgress: (Int) -> Unit = {}): File? {
        // 文件名来自清单（外部数据）—— 只取最后一段，别让它带路径
        val fileName = entry.file.substringAfterLast('/').ifBlank { "app.apk" }
        val target = File(File(context.cacheDir, "apkstore"), fileName)
        val result = FileDownload.download(
            url = entry.url,
            target = target,
            expectSize = entry.size,
            expectSha256 = entry.sha256,
            onProgress = onProgress,
        )
        return when (result) {
            is FileDownload.Result.Ok -> {
                Logx.i(
                    LogTags.APK_STORE, "云端应用下载完成",
                    "file" to fileName, "bytes" to result.bytes, "elapsedMs" to result.elapsedMs,
                )
                target
            }
            is FileDownload.Result.Failed -> {
                Logx.w(LogTags.APK_STORE, "云端应用下载失败", "reason" to result.reason, "url" to entry.url)
                null
            }
        }
    }

    /**
     * 安装下载好的 APK。
     *
     * 直接复用「在线更新」的安装链路（[AppUpdate.install]）：
     * system 变体有 `INSTALL_PACKAGES` → 静默装；普通安装包没这个权限 →
     * 退到系统安装器。两条路都试过了，没必要再写一份。
     */
    fun install(context: Context, apk: File): AppUpdate.InstallResult = AppUpdate.install(context, apk)
}
