package com.cardesk.launcher.core.update

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cardesk.launcher.core.SystemOps
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.net.FileDownload
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「检测 R2 上的最新版本清单 → 有新版就下载并安装」（第六十九轮起，第八十三轮换源）。
 *
 * ## 更新源：Cloudflare R2
 *
 * 原来是让用户手填一个 APK 地址（`prefs.updateUrl`）—— 填错就更新不了，
 * 而且地址一变就得重填；接着改成检测**本项目自己的 GitHub Release**。
 *
 * 第八十三轮改成 **Cloudflare R2 自定义域**：车机在国内访问 `api.github.com`
 * 又慢又不稳，而 R2 自定义域走的是 Cloudflare 自己的 CDN，稳得多。
 *
 * ```
 * GET https://file.chuxi.bid/cardesk/latest.json
 * ```
 *
 * R2 只是对象存储、没有「release」这种东西，所以清单格式是我们自己定的
 * （见 [R2Release]）。**下载走同一个域名的匿名访问**——
 * App 里不存任何密钥（仓库是公开的，密钥一旦进包就等于公开）。
 *
 * ## 检测时机：**手动触发**
 *
 * 本类**不注册任何定时器、也不在启动时自动跑**。
 * 只有用户点了「检查更新」才会联网查一次 —— 车机常年挂在后台，
 * 自动检查既费流量又会在用户没想更新的时候弹窗。
 *
 * ## 线程
 *
 * [check] / [download] 都是**阻塞**的，必须在后台线程调用（对话框里就是这么用的）。
 */
object AppUpdate {

    /** 更新源的域名（自定义域，走 Cloudflare CDN）。 */
    const val R2_HOST = "file.chuxi.bid"

    /** 更新清单的路径（R2 对象 key）。 */
    const val MANIFEST_PATH = "cardesk/latest.json"

    /** 更新清单的完整地址（App 每次「检查更新」就拉这一个文件）。 */
    const val MANIFEST_URL = "https://$R2_HOST/$MANIFEST_PATH"

    /** 域名根（清单里写相对地址时按它拼开）。 */
    const val R2_ROOT_URL = "https://$R2_HOST/"

    /** 公共目录下的子目录名（诊断包、下载的更新包都放这儿）。 */
    const val PUBLIC_DIR_NAME = "CarDesk"

    // ------------------------------------------------------------------ 检查结果

    /** 检查结果。 */
    sealed interface CheckResult {
        /** 已是最新。 */
        data class UpToDate(val current: String, val latestVersion: String) : CheckResult

        /** 有新版本。 */
        data class Available(val release: R2Release, val current: String) : CheckResult

        /** 没查到（网络不通 / 清单取不到 / 返回体看不懂）。 */
        data class Failed(val reason: String) : CheckResult
    }

    /** 安装结果。 */
    sealed interface InstallResult {
        /** 已提交静默安装（PackageInstaller）。 */
        object Silent : InstallResult

        /** 静默不行，已转交系统安装器。 */
        data class Installer(val location: String) : InstallResult

        /** 没装成。 */
        data class Fail(val reason: String) : InstallResult
    }

    // ------------------------------------------------------------------ 版本

    /** 当前装着的 `versionName`（`1.0.1038-system`）。读不到写 `?`。 */
    fun currentVersionName(context: Context): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 当前装着的 `versionCode`。 */
    fun currentVersionCode(context: Context): Long = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pi = pm.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrDefault(0L)

    /** 版本展示串：`1.0.1038（4134）`。 */
    fun versionLabel(context: Context): String {
        val name = currentVersionName(context)
        val code = currentVersionCode(context)
        return if (code > 0) "$name（$code）" else name
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 拉 R2 上的最新版本清单（阻塞）。
     *
     * @return 解析好的清单；**网络失败抛 IOException**，清单结构不对返回 null。
     */
    fun fetchRelease(context: Context, timeoutMs: Int = 10_000): R2Release? {
        val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CarDesk/${currentVersionName(context)}")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return R2Release.parse(text, R2_ROOT_URL)
    }

    /**
     * 检查有没有新版本（阻塞）。
     *
     * 清单里只放**一个**最新版本，所以这里不需要「挑最新」那一步；
     * 跟本地比的时候优先比 `versionCode`，清单没给才退回比版本号字符串。
     */
    fun check(context: Context): CheckResult {
        val current = currentVersionName(context)
        val currentCode = currentVersionCode(context)
        return try {
            val release = fetchRelease(context)
            if (release == null) {
                Logx.w(LogTags.SETTING, "检查更新：清单读不懂", "url" to MANIFEST_URL)
                return CheckResult.Failed("更新清单读不懂（格式不对）")
            }
            if (isNewer(release, current, currentCode)) {
                Logx.i(
                    LogTags.SETTING, "检查更新：有新版本",
                    "current" to current, "latest" to release.version,
                    "apk" to (release.apk?.name ?: ""),
                )
                CheckResult.Available(release, current)
            } else {
                Logx.i(LogTags.SETTING, "检查更新：已是最新", "current" to current, "latest" to release.version)
                CheckResult.UpToDate(current, release.version)
            }
        } catch (t: Throwable) {
            Logx.w(LogTags.SETTING, "检查更新失败", t, "url" to MANIFEST_URL)
            CheckResult.Failed(t.message ?: "网络异常")
        }
    }

    /**
     * 远端是否比本地新。
     *
     * 优先比 `versionCode`：它是单调递增的整数，不会踩「1.0.9 比 1.0.10 新」
     * 这种字符串比较的坑。清单里没给 `versionCode`（或本地读不到）时，
     * 退回比版本号字符串，兼容手写的旧清单。
     */
    private fun isNewer(remote: R2Release, localName: String, localCode: Long): Boolean {
        if (remote.versionCode > 0 && localCode > 0) return remote.versionCode > localCode
        return ReleaseVersion.isNewer(remote.version, localName)
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 下载 APK（阻塞）。
     *
     * 下载完做两道校验：长度（清单给了大小才查）和 SHA-256（清单给了摘要才查）。
     * 任一对不上就删掉半成品、当作下载失败 —— 车机装一个残缺的 APK 比不更新更糟。
     *
     * 第八十四轮：真正的下载 + 校验抽到了 [FileDownload]，
     * 「云端应用库」用的是同一套逻辑（公网下载被截断不会报错，只看 HTTP 200 不够）。
     *
     * @param onProgress 0..100 的进度（主线程回调）。
     * @return 下载好的文件；失败返回 null。
     */
    fun download(context: Context, release: R2Release, onProgress: (Int) -> Unit): File? {
        val apk = release.apk
        if (apk == null || apk.url.isBlank()) {
            Logx.w(LogTags.SETTING, "更新下载失败：清单里没有安装包", "version" to release.version)
            return null
        }
        val file = File(context.cacheDir, "cardesk-update-${release.version}.apk")
        val result = FileDownload.download(
            url = apk.url,
            target = file,
            expectSize = apk.size,
            expectSha256 = apk.sha256,
            onProgress = onProgress,
        )
        return when (result) {
            is FileDownload.Result.Ok -> {
                Logx.i(
                    LogTags.SETTING, "更新下载完成",
                    "file" to file.name, "bytes" to result.bytes,
                    "elapsedMs" to result.elapsedMs,
                )
                file
            }
            is FileDownload.Result.Failed -> {
                Logx.w(LogTags.SETTING, "更新下载失败", "reason" to result.reason, "url" to apk.url)
                null
            }
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 安装下载好的 APK。
     *
     * 先走**静默安装**（system 变体有 `INSTALL_PACKAGES`，装完直接生效）；
     * 静默不行就退到**系统安装器**（普通安装包唯一能走的路）。
     */
    fun install(context: Context, apk: File): InstallResult {
        if (SystemOps.installApk(context, apk)) return InstallResult.Silent
        // —— 回退：把 APK 放进公共目录，再叫系统安装器 ——
        val published = publishToPublic(context, apk)
        return when (published) {
            null -> InstallResult.Fail("静默安装失败，且无法写入公共目录")
            else -> {
                val opened = openInstaller(context, published)
                if (opened) {
                    InstallResult.Installer(published.location)
                } else {
                    InstallResult.Fail("已下载到 ${published.location}，但拉不起系统安装器，请手动安装")
                }
            }
        }
    }

    /** 已发布到公共目录的文件。 */
    private data class Published(val uri: android.net.Uri?, val location: String)

    /**
     * 把 APK 放进 `Download/CarDesk/`。
     *
     * - Android 10+：走 `MediaStore.Downloads`，拿得到 `content://` Uri；
     * - Android 9 及以下：直接写文件（需要 `WRITE_EXTERNAL_STORAGE`）。
     */
    private fun publishToPublic(context: Context, apk: File): Published? {
        val name = apk.name
        return if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Published(uri, "Download/$PUBLIC_DIR_NAME/$name")
            } catch (t: Throwable) {
                Logx.w(LogTags.SETTING, "更新包写入公共目录失败", t)
                null
            }
        } else {
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_DIR_NAME,
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                val out = File(dir, name)
                apk.inputStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                Published(null, out.absolutePath)
            } catch (t: Throwable) {
                Logx.w(LogTags.SETTING, "更新包写入公共目录失败", t)
                null
            }
        }
    }

    /** 叫起系统安装器。 */
    private fun openInstaller(context: Context, published: Published): Boolean {
        val uri = published.uri ?: android.net.Uri.fromFile(File(published.location))
        return runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Logx.w(LogTags.SETTING, "拉起系统安装器失败", it, "location" to published.location)
            false
        }
    }

    // ------------------------------------------------------------------ 杂项
}
