package com.cardesk.launcher.core.update

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cardesk.launcher.core.SystemOps
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「检测开源仓库 Release → 有新版就下载并安装」（第六十九轮）。
 *
 * ## 更新源
 *
 * 原来是让用户手填一个 APK 地址（`prefs.updateUrl`）—— 填错就更新不了，
 * 而且地址一变就得重填。现在固定检测**本项目自己的 GitHub Release**：
 *
 * ```
 * GET https://api.github.com/repos/Bigcaibird/cardesk/releases
 * ```
 *
 * Release 的 tag 形如 `v1.0.1035`，附件是 `CarDesk-ecarx-cardesk-4131.apk`。
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

    /** 仓库地址（页面与日志里都用它）。 */
    const val REPO_URL = "https://github.com/Bigcaibird/cardesk"

    /** Release 列表接口（公开仓库，不需要 token）。 */
    const val RELEASES_API = "https://api.github.com/repos/Bigcaibird/cardesk/releases"

    /** 公共目录下的子目录名（诊断包、下载的更新包都放这儿）。 */
    const val PUBLIC_DIR_NAME = "CarDesk"

    // ------------------------------------------------------------------ 检查结果

    /** 检查结果。 */
    sealed interface CheckResult {
        /** 已是最新。 */
        data class UpToDate(val current: String, val latestVersion: String) : CheckResult

        /** 有新版本。 */
        data class Available(val release: GitHubRelease, val current: String) : CheckResult

        /** 没查到（网络不通 / 仓库没有 release / 返回体看不懂）。 */
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
     * 拉 Release 列表（阻塞）。
     *
     * @throws IOException 网络失败或 HTTP 非 200。
     */
    fun fetchReleases(context: Context, timeoutMs: Int = 10_000): List<GitHubRelease> {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        // GitHub API 没有 User-Agent 会直接 403
        conn.setRequestProperty("User-Agent", "CarDesk/${currentVersionName(context)}")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return GitHubRelease.parse(text)
    }

    /**
     * 检查有没有新版本（阻塞）。
     *
     * 拿「版本号最大」的那个 release（不是按发布时间，见 [GitHubRelease.pickLatest]），
     * 再和本地版本比。
     */
    fun check(context: Context): CheckResult {
        val current = currentVersionName(context)
        return try {
            val releases = fetchReleases(context)
            val latest = GitHubRelease.pickLatest(releases)
            if (latest == null) {
                Logx.w(LogTags.SETTING, "检查更新：仓库里没有可用的 Release", "count" to releases.size)
                return CheckResult.Failed("仓库里还没有发布 Release")
            }
            if (ReleaseVersion.isNewer(latest.tag, current)) {
                Logx.i(
                    LogTags.SETTING, "检查更新：有新版本",
                    "current" to current, "latest" to latest.tag,
                    "asset" to (latest.apk?.name ?: ""),
                )
                CheckResult.Available(latest, current)
            } else {
                Logx.i(LogTags.SETTING, "检查更新：已是最新", "current" to current, "latest" to latest.tag)
                CheckResult.UpToDate(current, latest.version)
            }
        } catch (t: Throwable) {
            Logx.w(LogTags.SETTING, "检查更新失败", t, "api" to RELEASES_API)
            CheckResult.Failed(t.message ?: "网络异常")
        }
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 下载 APK（阻塞）。
     *
     * @param onProgress 0..100 的进度（主线程回调）。
     * @return 下载好的文件；失败返回 null。
     */
    fun download(context: Context, release: GitHubRelease, onProgress: (Int) -> Unit): File? {
        val asset = release.apk
        if (asset == null || asset.downloadUrl.isBlank()) {
            Logx.w(LogTags.SETTING, "更新下载失败：Release 没挂 APK", "tag" to release.tag)
            return null
        }
        val file = File(context.cacheDir, "cardesk-update-${release.version}.apk")
        val started = System.currentTimeMillis()
        val ok = try {
            val conn = URL(asset.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CarDesk")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            val total = conn.contentLength
            var read = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
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
            file.length() > 1024
        } catch (t: Throwable) {
            Logx.w(LogTags.SETTING, "更新下载失败", t, "url" to asset.downloadUrl)
            file.delete()
            return null
        }
        if (!ok) {
            file.delete()
            return null
        }
        Logx.i(
            LogTags.SETTING, "更新下载完成",
            "file" to file.name, "bytes" to file.length(),
            "elapsedMs" to (System.currentTimeMillis() - started),
        )
        onProgress(100)
        return file
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
